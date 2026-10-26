"""HTTP integration-сценарии персонального ранжирования событий."""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterator

import allure
import httpx
import pytest


ML_ROOT = Path(__file__).resolve().parents[1]
HEALTH_TIMEOUT_SECONDS = 20
RATE_TEXT = (
    "Банк России повысил ключевую ставку до 18 процентов. "
    "Стоимость кредитования и заимствований для бизнеса выросла."
)


def _free_port() -> int:
    """Находит свободный loopback-порт для локального API."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


@pytest.fixture(scope="module")
def rank_http(tmp_path_factory: pytest.TempPathFactory) -> Iterator[httpx.Client]:
    """Запускает настоящий FastAPI через Uvicorn с локальной заглушкой embeddings."""
    port = _free_port()
    log_path = tmp_path_factory.mktemp("ml-rank-uvicorn") / "server.log"
    env = os.environ.copy()
    env["ML_EMBEDDER"] = "stub"
    log_file = log_path.open("w", encoding="utf-8")
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
                pytest.fail(f"Локальный ML API завершился при старте: {log_path.read_text(encoding='utf-8')[-4000:]}")
            try:
                response = client.get("/health")
                if response.status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            threading.Event().wait(0.05)
        else:
            pytest.fail(f"ML API не стал доступен за {HEALTH_TIMEOUT_SECONDS} секунд")
        yield client
    finally:
        client.close()
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        log_file.close()


def _exchange(client: httpx.Client, body: dict, label: str) -> httpx.Response:
    """Отправляет запрос ранжирования и сохраняет его и ответ в Allure."""
    response = client.post("/rank", json=body)
    allure.attach(
        json.dumps(
            {
                "request": {
                    "method": response.request.method,
                    "url": str(response.request.url),
                    "headers": dict(response.request.headers),
                    "body": json.loads(response.request.content),
                },
                "response": {
                    "status": response.status_code,
                    "headers": dict(response.headers),
                    "body": response.text,
                },
            },
            ensure_ascii=False,
            indent=2,
        ),
        name=f"{label} — HTTP запрос и ответ",
        attachment_type=allure.attachment_type.JSON,
    )
    return response


def _rank_body(company: dict, candidates: list[dict], top_k: int = 3, role: str | None = None) -> dict:
    """Собирает тело запроса API с контекстом компании и событиями."""
    return {"company": company, "candidates": candidates, "topK": top_k, "role": role}


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("Ranking")
@allure.title("TestIntegrationMlRankLoanAndDeposit — рост ставки объяснён через продукты компании")
@allure.description("""Шаги:
1. Отправить одно событие о росте ключевой ставки для компании-заёмщика и компании с депозитом.
1.1. Проверить риск и причину про кредит.
1.2. Проверить возможность и причину про депозит.

Ожидание: событие связано с имеющимся продуктом компании и получает эффект согласно направлению финансового воздействия.""")
def test_integration_rank_rate_growth_changes_effect_for_loan_and_deposit(rank_http: httpx.Client) -> None:
    """Проверяет, что одна новость о ставке создаёт разные последствия для кредита и депозита."""
    candidate = {
        "id": "11111111-1111-4111-8111-111111111111",
        "title": "Банк России повысил ключевую ставку до 18 процентов",
        "text": RATE_TEXT,
        "publishedAt": datetime.now(timezone.utc).isoformat(),
    }
    borrower = {"profile": {"companyId": "borrower", "hasLoan": True}}
    depositor = {"profile": {"companyId": "depositor", "hasDeposit": True}}
    with allure.step("1. Сопоставить влияние роста ставки для заёмщика и вкладчика"):
        with allure.step("1.1. Отправить событие для компании с кредитом"):
            loan_response = _exchange(rank_http, _rank_body(borrower, [candidate], 1), "Компания-заёмщик")
        assert loan_response.status_code == 200, f"Ожидался HTTP 200: {loan_response.text[:1000]}"
        loan_body = loan_response.json()
        assert loan_body["modelVersion"] == "factors-v1"
        assert len(loan_body["items"]) == 1 and loan_body["items"][0]["id"] == candidate["id"]
        loan_item = loan_body["items"][0]
        assert loan_item["effect"] == "risk", f"Рост ставки должен быть риском для заёмщика: {loan_item}"
        assert any("у компании кредит" in reason for reason in loan_item["why"]), (
            f"Причина должна указывать на продукт кредита: {loan_item['why']}"
        )
        assert any(factor["id"] == "key_rate" and factor["effect"] == -1 for factor in loan_item["factors"])

        with allure.step("1.2. Отправить то же событие для компании с депозитом"):
            deposit_response = _exchange(rank_http, _rank_body(depositor, [candidate], 1), "Компания с депозитом")
        assert deposit_response.status_code == 200, f"Ожидался HTTP 200: {deposit_response.text[:1000]}"
        deposit_body = deposit_response.json()
        assert isinstance(deposit_body.get("modelVersion"), str) and deposit_body["modelVersion"].strip()
        deposit_item = deposit_body["items"][0]
        assert deposit_item["id"] == candidate["id"]
        assert deposit_item["effect"] == "opportunity", f"Рост ставки должен быть выгоден вкладчику: {deposit_item}"
        assert any("у компании депозиты" in reason for reason in deposit_item["why"]), (
            f"Причина должна указывать на продукт депозита: {deposit_item['why']}"
        )
        assert any(factor["id"] == "key_rate" and factor["effect"] == 1 for factor in deposit_item["factors"])


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("Ranking")
@allure.title("TestIntegrationMlRankCounterparty — релевантность учитывает ключевого контрагента")
@allure.description("""Шаги:
1. Передать компанию без прямой чувствительности к ставке и значимого контрагента из чувствительной отрасли.
1.1. Отправить новость о повышении ключевой ставки.
1.2. Проверить рост релевантности и объяснение связи с контрагентом.

Ожидание: ранжирование учитывает контрагента с существенной долей платежей и объясняет его роль.""")
def test_integration_rank_uses_counterparty_exposure(rank_http: httpx.Client) -> None:
    """Проверяет косвенную чувствительность к ставке через контрагента."""
    company = {
        "profile": {"companyId": "buyer", "mainIndustryCode": "99", "mainIndustryName": "прочие услуги"},
        "counterparties": [
            {
                "companyId": "counterparty-auto",
                "mainIndustryCode": "29",
                "mainIndustryName": "производство автомобилей",
                "paymentShare": 0.3,
                "outgoingPaymentAmount": 900000,
                "incomingPaymentAmount": 100000,
            }
        ],
    }
    candidate = {
        "id": "22222222-2222-4222-8222-222222222222",
        "title": "ЦБ повысил ключевую ставку для экономики",
        "text": RATE_TEXT,
        "publishedAt": datetime.now(timezone.utc).isoformat(),
    }
    with allure.step("1. Проверить влияние ставки через контрагента компании"):
        with allure.step("1.1. Отправить контекст компании и новость в POST /rank"):
            response = _exchange(rank_http, _rank_body(company, [candidate], 1), "Риск через контрагента")
        assert response.status_code == 200, f"Ожидался HTTP 200: {response.text[:1000]}"
        body = response.json()
        assert isinstance(body.get("modelVersion"), str) and body["modelVersion"].strip()
        assert len(body["items"]) == 1
        item = body["items"][0]
        assert item["id"] == candidate["id"]
        assert item["effect"] == "unknown", f"Без условий договоров нельзя приписывать компании однозначный риск: {item}"
        baseline_response = _exchange(rank_http, _rank_body({"profile": company["profile"]}, [candidate], 1), "Та же компания без контрагента")
        assert baseline_response.status_code == 200, baseline_response.text
        baseline_items = baseline_response.json()["items"]
        baseline_score = baseline_items[0]["score"] if baseline_items else 0
        assert item["score"] > baseline_score, (
            f"Значимая связь должна повышать релевантность: с контрагентом={item['score']}, без={baseline_score}"
        )
        assert any(factor["id"] == "key_rate" and factor["channel"] == "counterparty" for factor in item["factors"]), (
            f"Фактор ключевой ставки должен прийти по каналу контрагента: {item['factors']}"
        )
        assert any("контрагент (получатель платежей)" in reason for reason in item["why"]), (
            f"Объяснение должно учитывать направление существенных платежей: {item['why']}"
        )


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("Ranking")
@allure.title("TestIntegrationMlRankFreshnessAndDiversity — свежесть и разные факторы в выдаче")
@allure.description("""Шаги:
1. Передать два события о ставке разной давности и отдельное налоговое событие.
1.1. Проверить, что свежее событие получает большую часть freshness и score.
1.2. Проверить, что в подборке есть ключевая ставка и налог, а ID принадлежат входным кандидатам.

Ожидание: свежесть влияет на оценку, разнообразие факторов сохраняет в выдаче разные значимые темы, API возвращает корректные метаданные.""")
def test_integration_rank_freshness_diversity_and_candidate_ids(rank_http: httpx.Client) -> None:
    """Проверяет свежесть, разнообразие факторов, версию ответа и происхождение ID."""
    now = datetime.now(timezone.utc)
    recent = {
        "id": "33333333-3333-4333-8333-333333333333",
        "title": "Финансирование бизнеса дорожает после решения регулятора",
        "text": RATE_TEXT,
        "publishedAt": now.isoformat(),
    }
    stale = {
        "id": "44444444-4444-4444-8444-444444444444",
        "title": "Компаниям приходится пересматривать стоимость займов",
        "text": RATE_TEXT,
        "publishedAt": (now - timedelta(days=4)).isoformat(),
    }
    tax = {
        "id": "55555555-5555-4555-8555-555555555555",
        "title": "ФНС повысила налог на прибыль до 25 процентов",
        "text": "ФНС сообщила о повышении налога для компаний. Новое правило увеличивает налоговую нагрузку.",
        "publishedAt": now.isoformat(),
    }
    company = {
        "profile": {
            "companyId": "industrial-trader",
            "mainIndustryCode": "46.71",
            "mainIndustryName": "оптовая торговля нефтепродуктами",
            "hasLoan": True,
        }
    }
    candidates = [recent, stale, tax]
    with allure.step("1. Проверить свежесть и разнообразие результата"):
        with allure.step("1.1. Отправить новости разной давности по ставке и налогу"):
            response = _exchange(rank_http, _rank_body(company, candidates, 3, "CFO"), "Свежесть и разнообразие")
        assert response.status_code == 200, f"Ожидался HTTP 200: {response.text[:1000]}"
        body = response.json()
        assert isinstance(body.get("modelVersion"), str) and body["modelVersion"].strip()
        items = body["items"]
        assert items, "При релевантных кандидатах подборка не должна быть пустой"
        result_ids = [item["id"] for item in items]
        input_ids = {candidate["id"] for candidate in candidates}
        assert len(result_ids) == len(set(result_ids)), f"Ответ не должен повторять ID: {result_ids}"
        assert set(result_ids) <= input_ids, f"API не должен придумывать ID: входные={input_ids}, ответ={result_ids}"
        by_id = {item["id"]: item for item in items}
        assert recent["id"] in by_id and stale["id"] in by_id, (
            f"Оба несхожих материала о ставке должны попасть в топ-3: {result_ids}"
        )
        assert by_id[recent["id"]]["parts"]["freshness"] > by_id[stale["id"]]["parts"]["freshness"], (
            f"Свежая новость должна иметь большую свежесть: {by_id[recent['id']]['parts']} vs "
            f"{by_id[stale['id']]['parts']}"
        )
        assert by_id[recent["id"]]["score"] > by_id[stale["id"]]["score"], (
            f"При прочих равных более свежая новость должна иметь больший score: "
            f"{by_id[recent['id']]['score']} <= {by_id[stale['id']]['score']}"
        )

        with allure.step("1.2. Проверить, что выдача сохраняет отдельный налоговый фактор"):
            assert tax["id"] in by_id, f"Разнообразная выдача должна включать налоговую тему: {result_ids}"
            returned_factors = {
                factor["id"] for item in items for factor in item["factors"]
            }
            assert {"key_rate", "tax"} <= returned_factors, (
                f"Ответ должен объяснять обе темы; найдены факторы {returned_factors}"
            )


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("Ranking")
@allure.title("TestIntegrationMlRankTopKBounds — API отклоняет topK вне диапазона")
@allure.description("""Шаги:
1. Отправить запросы с topK ниже и выше разрешённого диапазона.
1.1. Проверить значения 0 и 51.

Ожидание: API возвращает HTTP 400 и коротко сообщает диапазон topK от 1 до 50.""")
@pytest.mark.parametrize("top_k", [0, 51], ids=["below-minimum", "above-maximum"])
def test_integration_rank_rejects_top_k_out_of_range(rank_http: httpx.Client, top_k: int) -> None:
    """Проверяет контрактные границы topK через HTTP."""
    body = _rank_body({"profile": {}}, [], top_k)
    with allure.step(f"1. Проверить POST /rank с topK={top_k}"):
        response = _exchange(rank_http, body, f"Недопустимый topK={top_k}")
        assert response.status_code == 400, f"Ожидался HTTP 400 для topK={top_k}: {response.text[:1000]}"
        assert "1" in response.text and "50" in response.text, (
            f"Ответ должен указывать диапазон 1–50: {response.text[:1000]}"
        )
