"""HTTP integration scenarios for local news collection sources."""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from email.utils import format_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Iterator
from urllib.parse import urlparse

import allure
import httpx
import pytest


ML_ROOT = Path(__file__).resolve().parents[1]
HEALTH_TIMEOUT_SECONDS = 20
ARTICLE_TEXT = " ".join(
    [
        "The regional manufacturer announced a major investment in production capacity and logistics.",
        "The company expects the new facility to increase annual output and create jobs across the region.",
        "Management said financing will come from operating cash flow and a long term credit agreement.",
        "The project reflects continuing demand from business customers and a broader expansion plan.",
    ]
)


def _free_port() -> int:
    """Находит свободный loopback-порт для локального HTTP-сервера."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


class _NewsFixtureHandler(BaseHTTPRequestHandler):
    """Отдаёт локальную RSS-ленту, статью и источник с ошибкой."""

    def do_GET(self) -> None:
        """Обрабатывает запросы к ресурсам HTTP-фикстуры."""
        path = urlparse(self.path).path
        if path == "/robots.txt":
            status, content_type, body = 200, "text/plain", "User-agent: *\nAllow: /\n"
        elif path == "/feed.rss":
            article = f"http://127.0.0.1:{self.server.server_port}/article/one"
            published = format_datetime(datetime.now(timezone.utc))
            body = f"""<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0"><channel><title>Local fixture</title>
<item><title>Manufacturer announces regional production investment</title><link>{article}?utm_source=fixture</link>
<description>Business investment and production capacity</description><pubDate>{published}</pubDate></item>
<item><title>Manufacturer announces regional production investment</title><link>{article}?ref=duplicate</link>
<description>Duplicate canonical article</description><pubDate>{published}</pubDate></item>
</channel></rss>"""
            status, content_type = 200, "application/rss+xml"
        elif path == "/article/one":
            status, content_type = 200, "text/html; charset=utf-8"
            body = f"<html><body><article><h1>Manufacturer announces regional production investment</h1><p>{ARTICLE_TEXT}</p></article></body></html>"
        elif path == "/broken.rss":
            status, content_type, body = 503, "text/plain", "fixture source unavailable"
        else:
            status, content_type, body = 404, "text/plain", "not found"
        encoded = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format: str, *args: object) -> None:
        """Отключает стандартный access-log фикстурного сервера."""
        return


@pytest.fixture
def news_api(tmp_path: Path) -> Iterator[tuple[httpx.Client, Path]]:
    """Запускает Uvicorn с локальными RSS/HTTP-фикстурами и временным хранилищем."""
    fixture_server = ThreadingHTTPServer(("127.0.0.1", 0), _NewsFixtureHandler)
    fixture_thread = threading.Thread(target=fixture_server.serve_forever, daemon=True)
    fixture_thread.start()
    fixture_base = f"http://127.0.0.1:{fixture_server.server_port}"

    bootstrap = tmp_path / "bootstrap"
    bootstrap.mkdir()
    (bootstrap / "sitecustomize.py").write_text(
        "import os\n"
        "from pathlib import Path\n"
        "import app.news as news\n"
        "base = os.environ['ML_TEST_NEWS_BASE']\n"
        "news.SOURCES = {\n"
        "    'fixture': {'url': base + '/feed.rss', 'tier': 1},\n"
        "    'broken': {'url': base + '/broken.rss', 'tier': 2},\n"
        "}\n"
        "news.DATA_FILE = Path(os.environ['ML_TEST_NEWS_DATA_FILE'])\n",
        encoding="utf-8",
    )

    port = _free_port()
    env = os.environ.copy()
    env["ML_EMBEDDER"] = "stub"
    env["ML_TEST_NEWS_BASE"] = fixture_base
    data_file = tmp_path / "data" / "news.jsonl"
    env["ML_TEST_NEWS_DATA_FILE"] = str(data_file)
    existing_path = env.get("PYTHONPATH", "")
    env["PYTHONPATH"] = os.pathsep.join(part for part in (str(bootstrap), str(ML_ROOT), existing_path) if part)
    log_file = (tmp_path / "uvicorn.log").open("w", encoding="utf-8")
    process = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(port)],
        cwd=ML_ROOT,
        env=env,
        stdout=log_file,
        stderr=subprocess.STDOUT,
    )
    client = httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=30.0)
    try:
        deadline = time.monotonic() + HEALTH_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            if process.poll() is not None:
                pytest.fail(f"Локальный news uvicorn завершился при старте: {(tmp_path / 'uvicorn.log').read_text()[-4000:]}")
            try:
                if client.get("/health").status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            threading.Event().wait(0.05)
        else:
            pytest.fail("Локальный news uvicorn не стал доступен за 20 секунд")
        yield client, data_file
    finally:
        client.close()
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        log_file.close()
        fixture_server.shutdown()
        fixture_server.server_close()
        fixture_thread.join(timeout=5)


def _attach_news_exchange(response: httpx.Response) -> None:
    """Прикладывает параметры запроса и HTTP-ответ к отчёту Allure."""
    allure.attach(
        json.dumps(
            {
                "request": {"method": response.request.method, "url": str(response.request.url)},
                "response": {
                    "status": response.status_code,
                    "headers": dict(response.headers),
                    "body": response.text,
                },
            },
            ensure_ascii=False,
            indent=2,
        ),
        name="HTTP запрос и ответ news collect",
        attachment_type=allure.attachment_type.JSON,
    )


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("News")
@allure.title("TestIntegrationMlNewsCollect — сбор читает локальный RSS и сохраняет статьи")
@allure.description("""Шаги:
1. Вызвать сбор новостей с локальным RSS, HTTP-статьёй, повтором URL и недоступным источником.
1.1. Проверить статистику, сохранённую статью и дедупликацию в JSONL.
1.2. Повторить сбор и проверить, что уже сохранённая статья отмечена дублем.

Ожидание: HTTP API сохраняет одну статью, учитывает повтор и ошибку источника, а повторный сбор не создаёт вторую запись.""")
def test_integration_news_collect_uses_local_sources_and_deduplicates(news_api: tuple[httpx.Client, Path]) -> None:
    """Проверяет сбор статьи, повторы URL, ошибку feed и повторный запуск API."""
    client, data_file = news_api
    with allure.step("1. Собрать новости из локального HTTP fixture"):
        with allure.step("1.1. Вызвать POST /news/collect?hours=1"):
            first = client.post("/news/collect", params={"hours": 1})
            _attach_news_exchange(first)
        assert first.status_code == 200, f"Сбор должен вернуть HTTP 200: {first.text[:1000]}"
        first_body = first.json()
        assert first_body["stats"] == {
            "new": 1,
            "duplicate": 1,
            "near_duplicate": 0,
            "non_business": 0,
            "too_short": 0,
            "partial_text": 0,
            "source_errors": 1,
        }, f"Ожидалась одна статья, повтор URL и одна ошибка источника: {first_body['stats']}"
        assert len(first_body["materials"]) == 1, f"В API должна попасть одна уникальная статья: {first_body['materials']}"
        material = first_body["materials"][0]
        assert material["title"] == "Manufacturer announces regional production investment"
        assert len(material["text"]) >= 200, f"Извлечённый текст статьи слишком короткий: {material['text']!r}"
        assert material["source"] == "fixture" and material["sourceTier"] == 1
        assert data_file.is_file(), "Сбор должен записать материал в временный JSONL-файл"
        rows = [json.loads(line) for line in data_file.read_text(encoding="utf-8").splitlines()]
        assert len(rows) == 1 and rows[0]["text_partial"] is False, f"Ожидался полный текст одной статьи: {rows}"

        with allure.step("1.2. Повторить сбор и проверить постоянство дедупликации"):
            second = client.post("/news/collect", params={"hours": 168})
            _attach_news_exchange(second)
        assert second.status_code == 200, f"Повторный сбор должен вернуть HTTP 200: {second.text[:1000]}"
        assert second.json()["stats"]["new"] == 0, f"Повтор не должен создавать новую запись: {second.text[:1000]}"
        assert second.json()["stats"]["duplicate"] == 2, f"Обе записи feed должны считаться дублями: {second.text[:1000]}"
        assert len(data_file.read_text(encoding="utf-8").splitlines()) == 1, "Повторный сбор не должен дублировать JSONL"


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("News")
@allure.title("TestIntegrationMlNewsHoursBounds — сбор принимает только диапазон 1–168 часов")
@allure.description("""Шаги:
1. Проверить нижнюю и верхнюю границы параметра hours, включая значения за диапазоном.
1.1. Отправить запросы с hours=0 и hours=169.
1.2. Отправить запросы с hours=1 и hours=168.

Ожидание: 0 и 169 дают HTTP 400; 1 и 168 принимаются с HTTP 200.""")
def test_integration_news_collect_hours_bounds(news_api: tuple[httpx.Client, Path]) -> None:
    """Проверяет допустимые границы hours через реальный HTTP API."""
    client, _ = news_api
    with allure.step("1. Проверить допустимые значения hours через HTTP"):
        for index, (hours, expected) in enumerate(((0, 400), (169, 400), (1, 200), (168, 200)), start=1):
            with allure.step(f"1.{index}. POST /news/collect?hours={hours}, ожидание HTTP {expected}"):
                response = client.post("/news/collect", params={"hours": hours})
                _attach_news_exchange(response)
                assert response.status_code == expected, (
                    f"Для hours={hours} ожидался HTTP {expected}, получен {response.status_code}: {response.text[:1000]}"
                )
                if expected == 400:
                    assert "1" in response.text and "168" in response.text, (
                        f"Ошибка должна объяснять допустимый диапазон hours: {response.text[:1000]}"
                    )
