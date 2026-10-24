"""HTTP integration checks for the ML PDF analysis API."""

from __future__ import annotations

import json
import hashlib
import os
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Iterator

import allure
import httpx
import pymupdf as fitz
import pytest


ML_ROOT = Path(__file__).resolve().parents[1]
PUBLIC_PDF_FIXTURE_DIR = ML_ROOT / "tests" / "fixtures" / "pdf"
HEALTH_TIMEOUT_SECONDS = 20
STUB_EMBEDDING = [1.0, 0.5, 0.25, 0.125, 0.0, 0.0, 0.0, 0.0]
MIXED_SCAN_TEXT = (
    "Operating loss reached 12.7 percent in 2024. Revenue forecast remains negative by "
    "9.8 billion rubles. The company expects lower production and higher borrowing costs."
)


def _make_pdf(pages: list[str | None]) -> bytes:
    """Создаёт PDF из текстовых страниц; None означает физически пустую страницу."""
    document = fitz.open()
    for text in pages:
        page = document.new_page(width=595, height=1800)
        if text:
            fit_height = page.insert_textbox(
                fitz.Rect(36, 36, 559, 1764), text, fontname="helv", fontsize=8
            )
            assert fit_height >= 0, "Текст тестовой PDF-страницы обрезан при создании фикстуры"
    data = document.tobytes()
    document.close()
    return data


def _make_image_only_pdf() -> bytes:
    """Создаёт PDF со сканоподобной картинкой и без текстового слоя."""
    document = fitz.open()
    _add_image_only_page(document)
    data = document.tobytes()
    document.close()
    return data


def _add_image_only_page(document: fitz.Document) -> None:
    """Добавляет страницу с изображением без извлекаемого текста."""
    source = fitz.open()
    scanned_page = source.new_page(width=595, height=1800)
    fit_height = scanned_page.insert_textbox(
        fitz.Rect(36, 36, 559, 1764), MIXED_SCAN_TEXT, fontname="helv", fontsize=16
    )
    assert fit_height >= 0, "Текст сканоподобной страницы не поместился при создании фикстуры"
    image = scanned_page.get_pixmap(matrix=fitz.Matrix(1.5, 1.5), alpha=False).tobytes("png")
    source.close()
    page = document.new_page()
    page.insert_image(page.rect, stream=image)


def _make_mixed_text_image_pdf() -> bytes:
    """Создаёт текстовую страницу и существенную страницу-скан без OCR-текста."""
    document = fitz.open()
    text_page = document.new_page(width=595, height=1800)
    fit_height = text_page.insert_textbox(
        fitz.Rect(36, 36, 559, 1764), "Readable first page", fontname="helv", fontsize=8
    )
    assert fit_height >= 0, "Текст тестовой PDF-страницы обрезан при создании фикстуры"
    _add_image_only_page(document)
    data = document.tobytes()
    document.close()
    return data


def _read_public_pdf_fixture(filename: str) -> bytes:
    """Читает согласованный публичный PDF из каталога тестовых фикстур."""
    fixture_dir = PUBLIC_PDF_FIXTURE_DIR.resolve()
    fixture_path = (fixture_dir / filename).resolve()
    if fixture_path.parent != fixture_dir or fixture_path.suffix.lower() != ".pdf":
        raise ValueError("Ожидается имя PDF-файла из tests/fixtures/pdf")
    if not fixture_path.is_file():
        raise FileNotFoundError(f"Не найдена PDF-фикстура: {fixture_path.name}")
    return fixture_path.read_bytes()


def _free_port() -> int:
    """Выбирает свободный loopback-порт для локального uvicorn."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def _attach_exchange(
    label: str, pdf: bytes, response: httpx.Response, *, attach_pdf: bool = True
) -> None:
    """Прикладывает HTTP-вход и ответ без локальных путей к файлам."""
    allure.attach(
        json.dumps(
            {
                "method": "POST",
                "url": str(response.request.url),
                "headers": dict(response.request.headers),
                "bodyBytes": len(pdf),
                "bodySha256": hashlib.sha256(pdf).hexdigest(),
                "firstBytes": pdf[:5].decode("ascii", errors="replace"),
                "bodyAttachmentOmitted": not attach_pdf,
            },
            ensure_ascii=False,
            indent=2,
        ),
        name=f"{label} — HTTP запрос",
        attachment_type=allure.attachment_type.JSON,
    )
    if attach_pdf:
        allure.attach(pdf, name=f"{label} — PDF запроса", attachment_type="application/pdf")
    allure.attach(
        json.dumps(
            {
                "status": response.status_code,
                "headers": dict(response.headers),
                "body": response.text,
            },
            ensure_ascii=False,
            indent=2,
        ),
        name=f"{label} — HTTP ответ",
        attachment_type=allure.attachment_type.JSON,
    )


def _assert_status(response: httpx.Response, expected: int, scenario: str) -> None:
    """Показывает ожидаемый и фактический статус вместе со сценарием запроса."""
    assert response.status_code == expected, (
        f"{scenario}: ожидался HTTP {expected}, получен HTTP {response.status_code}; "
        f"ответ: {response.text[:1000]}"
    )


def _assert_status_in(response: httpx.Response, expected: set[int], scenario: str) -> None:
    """Проверяет допустимые статусы без закрепления рекомендуемого кода как обязательного."""
    assert response.status_code in expected, (
        f"{scenario}: ожидался один из HTTP {sorted(expected)}, получен HTTP {response.status_code}; "
        f"ответ: {response.text[:1000]}"
    )


def _assert_useful_error(response: httpx.Response, scenario: str) -> None:
    """Проверяет, что отказ содержит короткую понятную причину."""
    try:
        detail = response.json().get("detail")
    except (ValueError, AttributeError):
        detail = None
    assert isinstance(detail, str) and len(detail.strip()) >= 5, (
        f"{scenario}: ответ об отказе должен содержать понятную причину в detail; "
        f"получено: {response.text[:1000]}"
    )


def _attach_server_log(config: pytest.Config, reason: str) -> None:
    """Прикладывает конец лога uvicorn и код завершения для диагностики сбоя."""
    log_path = getattr(config, "_ml_server_log", None)
    process = getattr(config, "_ml_server_process", None)
    log_tail = ""
    if log_path is not None and Path(log_path).exists():
        log_tail = Path(log_path).read_text(encoding="utf-8", errors="replace")[-8000:]
        log_tail = log_tail.replace(str(_request_root(config)), "<ml-root>")
    exit_code = None if process is None else process.poll()
    allure.attach(
        f"Причина: {reason}\nКод завершения uvicorn: {exit_code}\n\n{log_tail}",
        name="Диагностика локального ML-сервера",
        attachment_type=allure.attachment_type.TEXT,
    )


def _request_root(config: pytest.Config) -> Path:
    """Возвращает корень pytest для очистки диагностического лога от локального пути."""
    return Path(config.rootpath)


@pytest.fixture(scope="session")
def ml_http(tmp_path_factory: pytest.TempPathFactory, request: pytest.FixtureRequest) -> Iterator[httpx.Client]:
    """Запускает настоящий FastAPI через uvicorn с локальной embedding-заглушкой."""
    port = _free_port()
    log_path = tmp_path_factory.mktemp("ml-uvicorn") / "server.log"
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
    request.config._ml_server_log = log_path
    request.config._ml_server_process = process
    client = httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=5.0)
    deadline = time.monotonic() + HEALTH_TIMEOUT_SECONDS
    try:
        while time.monotonic() < deadline:
            if process.poll() is not None:
                _attach_server_log(request.config, "uvicorn завершился до готовности API")
                pytest.fail("Локальный uvicorn завершился до готовности API")
            try:
                response = client.get("/health")
                if response.status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            threading.Event().wait(0.05)
        else:
            _attach_server_log(request.config, "тайм-аут готовности API")
            pytest.fail("ML API не стал доступен за отведённое время")
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


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlHealth — API отвечает на проверку готовности")
@allure.description("""Шаги:
1. Проверить готовность локального ML API.
1.1. Запросить GET /health.

Ожидание: сервис возвращает HTTP 200 и status=ok.""")
def test_integration_health_returns_ok(ml_http: httpx.Client) -> None:
    """Проверяет health endpoint поднятого сервиса."""
    with allure.step("1. Проверить готовность сервиса документов"):
        with allure.step("1.1. Выполнить GET /health"):
            response = ml_http.get("/health")
            allure.attach(
                json.dumps(
                    {
                        "method": "GET",
                        "url": str(response.request.url),
                        "headers": dict(response.request.headers),
                        "status": response.status_code,
                        "responseHeaders": dict(response.headers),
                        "body": response.text,
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                name="HTTP запрос и ответ health",
                attachment_type=allure.attachment_type.JSON,
            )
        _assert_status(response, 200, "Проверка готовности GET /health")
        assert response.json() == {"status": "ok"}, (
            f"GET /health должен вернуть {{'status': 'ok'}}, получено: {response.text}"
        )


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlPdfPages — сохраняются страницы и идентификатор документа")
@allure.description("""Шаги:
1. Отправить текстовый PDF из двух страниц с пустой физической страницей между ними.
1.1. Передать исходные PDF-байты на analyze endpoint.
1.2. Сверить идентификатор, страницы, ordinal, текст и синтетические embeddings.

Ожидание: чанки относятся к физическим страницам 1 и 3, documentId совпадает с URL.""")
def test_integration_multpage_pdf_keeps_page_numbers_and_document_id(ml_http: httpx.Client) -> None:
    """Проверяет страницы 1 и 3 при пустой физической странице 2."""
    document_id = "bcb6ce48-1317-499b-aa1d-cfd347cc3ccb"
    pdf = _make_pdf(["First source", None, "Source on the third page"])
    headers = {"Content-Type": "application/pdf", "Accept": "application/json"}
    with allure.step("1. Передать аналитический PDF с пустой страницей в середине"):
        with allure.step("1.1. Отправить исходные PDF-байты на analyze endpoint"):
            response = ml_http.post(f"/documents/{document_id}/analyze", content=pdf, headers=headers)
            _attach_exchange("Многостраничный документ", pdf, response)
        with allure.step("1.2. Сверить идентификатор и данные чанков"):
            _assert_status(response, 200, "Текстовый PDF с пустой страницей должен обработаться")
            body = response.json()
            chunks = body["chunks"]
            assert body["documentId"] == document_id, (
                f"documentId ответа должен совпадать с URL {document_id}; получено {body['documentId']}"
            )
            assert [chunk["page"] for chunk in chunks] == [1, 3], (
                f"Чанки должны сохранить физические страницы 1 и 3, пропустив пустую страницу 2; "
                f"получено {[chunk['page'] for chunk in chunks]}"
            )
            assert [chunk["ordinal"] for chunk in chunks] == [0, 1], (
                f"Ordinal должны идти подряд в порядке страниц; получено {[chunk['ordinal'] for chunk in chunks]}"
            )
            assert [chunk["text"] for chunk in chunks] == ["First source", "Source on the third page"], (
                f"Ожидались тексты двух исходных страниц; получено {[chunk['text'] for chunk in chunks]}"
            )
            assert all(chunk["embedding"] == STUB_EMBEDDING for chunk in chunks), (
                "Каждый чанк stub-профиля должен содержать ожидаемый 8-мерный вектор"
            )


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlPdfChunking — разбиение сохраняет порядок слов")
@allure.description("""Шаги:
1. Передать PDF со страницей из 620 уникальных слов.
1.1. Отправить PDF и сохранить HTTP evidence.
1.2. Объединить слова чанков и проверить известный порядок и страницу.

Ожидание: все исходные слова встречаются в исходном порядке; допустимое перекрытие чанков не считается ошибкой.""")
def test_integration_long_page_chunks_preserve_word_order(ml_http: httpx.Client) -> None:
    """Проверяет полноту и порядок слов, разрешая перекрытие соседних чанков."""
    words = [f"word{i:03d}" for i in range(620)]
    pdf = _make_pdf([" ".join(words)])
    headers = {"Content-Type": "application/pdf", "Accept": "application/json"}
    with allure.step("1. Передать длинную страницу и проверить результат"):
        with allure.step("1.1. Отправить PDF и сохранить HTTP evidence"):
            response = ml_http.post("/documents/11111111-1111-4111-8111-111111111111/analyze", content=pdf,
                                    headers=headers)
            _attach_exchange("Длинная страница", pdf, response)
        with allure.step("1.2. Объединить чанки и проверить исходный порядок слов"):
            _assert_status(response, 200, "Длинная текстовая страница должна быть разбита на чанки")
            chunks = response.json()["chunks"]
            assert chunks, "Длинная текстовая страница должна дать хотя бы один чанк"
            assert all(chunk["page"] == 1 for chunk in chunks), (
                f"Все чанки одной физической страницы должны ссылаться на страницу 1; "
                f"получено {[chunk['page'] for chunk in chunks]}"
            )
            assert all(chunk["text"].strip() for chunk in chunks), "Текст каждого чанка должен быть непустым"
            actual_words = [word for chunk in chunks for word in chunk["text"].split()]
            expected_words = set(words)
            seen_words: set[str] = set()
            first_seen_words: list[str] = []
            for word in actual_words:
                assert word in expected_words, f"В чанках появился текст, которого нет в исходном PDF: {word!r}"
                if word not in seen_words:
                    first_seen_words.append(word)
                    seen_words.add(word)
            assert first_seen_words == words, (
                "После удаления допустимых повторов перекрытия должен сохраниться исходный порядок всех слов; "
                f"ожидалось {len(words)} слов, получено {len(first_seen_words)}"
            )
            assert [chunk["ordinal"] for chunk in chunks] == list(range(len(chunks))), (
                f"Ordinal длинной страницы должны быть последовательными от нуля; "
                f"получено {[chunk['ordinal'] for chunk in chunks]}"
            )


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlPdfRepeatability — повторный анализ стабилен")
@allure.description("""Шаги:
1. Дважды отправить одинаковые PDF-байты с одним documentId.
1.1. Выполнить первый HTTP-запрос.
1.2. Выполнить повторный HTTP-запрос.
1.3. Сравнить полные JSON-ответы.

Ожидание: оба ответа успешны и полностью совпадают.""")
def test_integration_repeated_analysis_is_stable(ml_http: httpx.Client) -> None:
    """Сравнивает два HTTP-ответа для одинаковых PDF-байтов и documentId."""
    document_id = "22222222-2222-4222-8222-222222222222"
    pdf = _make_pdf(["Rate 16 percent", "Forecast for 2027"])
    headers = {"Content-Type": "application/pdf", "Accept": "application/json"}
    with allure.step("1. Дважды проанализировать один и тот же PDF"):
        with allure.step("1.1. Выполнить первый HTTP-запрос"):
            first = ml_http.post(f"/documents/{document_id}/analyze", content=pdf, headers=headers)
            _attach_exchange("Повторный анализ — попытка 1", pdf, first)
        with allure.step("1.2. Выполнить повторный HTTP-запрос"):
            second = ml_http.post(f"/documents/{document_id}/analyze", content=pdf, headers=headers)
            _attach_exchange("Повторный анализ — попытка 2", pdf, second)
        with allure.step("1.3. Сверить результаты двух запросов"):
            _assert_status(first, 200, "Первый анализ документа должен завершиться успешно")
            _assert_status(second, 200, "Повторный анализ того же документа должен завершиться успешно")
            assert first.json() == second.json(), (
                "Повторный анализ тех же PDF-байтов тем же профилем должен вернуть идентичный JSON"
            )


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlPdfBusinessText — числа и отрицание сохранены")
@allure.description("""Шаги:
1. Извлечь текст PDF со ставкой, годом, отрицанием и денежной единицей.
1.1. Отправить PDF в анализатор.
1.2. Найти ключевые значения в возвращённых чанках.

Ожидание: 2026, 16%, not reduced и 12 billion rubles присутствуют без потери.""")
def test_integration_text_preserves_numbers_negation_and_units(ml_http: httpx.Client) -> None:
    """Проверяет проценты, дату, единицы и отрицание после извлечения текста."""
    source_text = "For 2026, the rate of 16% was not reduced; output was 12 billion rubles."
    pdf = _make_pdf([source_text])
    headers = {"Content-Type": "application/pdf", "Accept": "application/json"}
    with allure.step("1. Извлечь текст страницы с ключевыми экономическими значениями"):
        with allure.step("1.1. Отправить PDF в анализатор"):
            response = ml_http.post("/documents/33333333-3333-4333-8333-333333333333/analyze", content=pdf,
                                    headers=headers)
            _attach_exchange("Числа и оговорки", pdf, response)
        with allure.step("1.2. Проверить ключевые значения в извлечённом тексте"):
            _assert_status(response, 200, "PDF с числом, отрицанием и единицей измерения должен обработаться")
            extracted = " ".join(chunk["text"] for chunk in response.json()["chunks"])
            for phrase in ("2026", "16%", "not reduced", "12 billion rubles"):
                assert phrase in extracted, (
                    f"Извлечённый текст должен сохранить исходный ориентир {phrase!r}; текст чанков: {extracted!r}"
                )


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlMixedPdfNoPageLoss — скан прочитан или отказ дан явно")
@allure.description("""Шаги:
1. Передать PDF с читаемой текстовой страницей и значимой страницей-сканом.
1.1. Отправить исходный документ в analyze endpoint.
1.2. Проверить, что страница 2 распознана с контрольными числами либо анализ явно отклонён.

Ожидание: OCR может вернуть 200 только с содержимым скана на физической странице 2; иначе ожидается явная ошибка HTTP 400/422 с причиной. Страница 2 не должна молча теряться.""")
def test_integration_mixed_pdf_never_silently_drops_scan_page(ml_http: httpx.Client) -> None:
    """Разрешает OCR-успех только при наличии проверяемого текста физической страницы-скана."""
    document_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    pdf = _make_mixed_text_image_pdf()
    headers = {"Content-Type": "application/pdf", "Accept": "application/json"}
    with allure.step("1. Проверить документ с непрочитанным содержательным листом"):
        with allure.step("1.1. Передать PDF с текстовой страницей и сканом"):
            response = ml_http.post(f"/documents/{document_id}/analyze", content=pdf, headers=headers)
            _attach_exchange("Текстовая страница и скан", pdf, response)
        with allure.step("1.2. Проверить содержимое скана либо явную причину отказа"):
            if response.status_code in {400, 422}:
                _assert_status_in(response, {400, 422}, "Нераспознанный значимый скан допускает явный отказ")
                _assert_useful_error(response, "Отказ при обработке значимой страницы-скана")
            else:
                _assert_status(response, 200, "OCR-успех допустим только при сохранении страницы-скана")
                chunks = response.json().get("chunks", [])
                scan_page_text = " ".join(
                    " ".join(chunk.get("text", "").split())
                    for chunk in chunks
                    if chunk.get("page") == 2
                )
                assert scan_page_text, (
                    "При HTTP 200 OCR должен вернуть непустые чанки физической страницы 2; "
                    f"номера страниц в ответе: {[chunk.get('page') for chunk in chunks]}"
                )
                for landmark in ("12.7 percent", "9.8 billion rubles"):
                    assert landmark in scan_page_text, (
                        f"OCR-успех должен сохранить контрольный факт {landmark!r} со страницы 2; "
                        f"распознано: {scan_page_text[:1200]!r}"
                    )


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlPublicPdfLandmarks — сохраняет ключевые данные источников")
@allure.description("""Шаги:
1. Отправить каждый согласованный публичный PDF через реальный HTTP API.
1.1. Отправить исходные байты в analyze endpoint.
1.2. Проверить формат ответа, ordinal и stub-векторы.
1.3. Нормализовать пробелы и сверить фиксированные ориентиры на нужной странице.

Ожидание: числовые факты доступны в чанках своих физических страниц, а ответ соответствует stub-v1.""")
@pytest.mark.parametrize(
    ("fixture_name", "document_id", "expected_page", "landmarks"),
    [
        (
            "cbr-inflation-expectations-2024-01.pdf",
            "88888888-8888-4888-8888-888888888888",
            2,
            ("снизилась до 12,7%", "9,8%", "7,2%", "5,1%"),
        ),
        (
            "ecb-monetary-policy-2024-04-11.pdf",
            "99999999-9999-4999-8999-999999999999",
            1,
            ("4.50%, 4.75% and 4.00%", "unchanged"),
        ),
    ],
    ids=["cbr-january-2024-page-2", "ecb-april-2024-page-1"],
)
def test_integration_public_pdf_preserves_page_landmarks(
    ml_http: httpx.Client,
    fixture_name: str,
    document_id: str,
    expected_page: int,
    landmarks: tuple[str, ...],
) -> None:
    """Проверяет заранее выбранные факты на физических страницах исходных PDF."""
    pdf = _read_public_pdf_fixture(fixture_name)
    headers = {"Content-Type": "application/pdf", "Accept": "application/json"}
    with allure.step(f"1. Обработать публичный PDF: {fixture_name}"):
        with allure.step("1.1. Отправить исходные байты в analyze endpoint"):
            response = ml_http.post(f"/documents/{document_id}/analyze", content=pdf, headers=headers)
            _attach_exchange(fixture_name, pdf, response)
        with allure.step("1.2. Проверить контракт ответа и stub-векторы"):
            _assert_status(response, 200, f"Публичный PDF {fixture_name} должен обработаться")
            body = response.json()
            chunks = body["chunks"]
            assert body["documentId"] == document_id, (
                f"{fixture_name}: documentId ответа должен совпасть с URL; получено {body['documentId']}"
            )
            assert body["schemaVersion"] == 1, f"{fixture_name}: ожидается версия схемы 1, получено {body.get('schemaVersion')}"
            assert body["stub"] is True, f"{fixture_name}: локальный тест должен работать с stub=true"
            assert body["embeddingModel"] == "stub-v1", (
                f"{fixture_name}: ожидалась модель stub-v1, получено {body.get('embeddingModel')}"
            )
            assert body["embeddingDimension"] == 8, (
                f"{fixture_name}: ожидается embeddingDimension=8, получено {body.get('embeddingDimension')}"
            )
            assert [chunk["ordinal"] for chunk in chunks] == list(range(len(chunks))), (
                f"{fixture_name}: ordinal должны быть последовательными от нуля"
            )
            assert all(len(chunk["embedding"]) == 8 for chunk in chunks), (
                f"{fixture_name}: embedding каждого чанка должен иметь размерность 8"
            )
            assert all(chunk["embedding"] == STUB_EMBEDDING for chunk in chunks), (
                f"{fixture_name}: каждый чанк должен содержать ожидаемый вектор stub-v1"
            )
        with allure.step("1.3. Сверить фиксированные ориентиры на физической странице"):
            page_text = " ".join(
                " ".join(chunk["text"].split())
                for chunk in chunks
                if chunk["page"] == expected_page
            )
            assert page_text, (
                f"{fixture_name}: на физической странице {expected_page} должен быть хотя бы один текстовый чанк"
            )
            for landmark in landmarks:
                assert landmark in page_text, (
                    f"{fixture_name}, страница {expected_page}: ожидался ориентир {landmark!r}; "
                    f"извлечено: {page_text[:1200]!r}"
                )


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlPdfEmptyBody — пустой запрос отклонён")
@allure.description("""Шаги:
1. Проверить реакцию analyze endpoint на пустое тело.
1.1. Отправить POST analyze без PDF-байтов.
1.2. Проверить HTTP-статус и сообщение.

Ожидание: API явно отказывает с HTTP 400 или 422 и сообщает причину. HTTP 400 рекомендован контрактом.""")
def test_integration_empty_request_body_returns_400(ml_http: httpx.Client) -> None:
    """Проверяет явный отказ с причиной для пустого HTTP-тела."""
    headers = {"Content-Type": "application/pdf", "Accept": "application/json"}
    with allure.step("1. Отправить запрос без байтов PDF"):
        with allure.step("1.1. Выполнить POST analyze с пустым телом"):
            response = ml_http.post("/documents/44444444-4444-4444-8444-444444444444/analyze", content=b"",
                                    headers=headers)
            _attach_exchange("Пустое тело", b"", response)
        with allure.step("1.2. Проверить статус и понятность ответа"):
            _assert_status_in(response, {400, 422}, "Пустое тело не является допустимым PDF")
            _assert_useful_error(response, "Отказ для пустого тела POST analyze")


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlPdfInvalidDocumentId — неверный UUID отклонён")
@allure.description("""Шаги:
1. Передать валидный PDF с идентификатором, который не является UUID.
1.1. Отправить запрос на analyze endpoint.
1.2. Проверить HTTP-статус.

Ожидание: API явно отказывает с HTTP 400 или 422 и сообщает причину. HTTP 400 рекомендован контрактом.""")
def test_integration_invalid_document_id_returns_400(ml_http: httpx.Client) -> None:
    """Проверяет ошибку запроса при неверном UUID документа."""
    pdf = _make_pdf(["A valid PDF for UUID validation"])
    headers = {"Content-Type": "application/pdf", "Accept": "application/json"}
    with allure.step("1. Проверить валидацию UUID маршрута"):
        with allure.step("1.1. Отправить валидный PDF с documentId=not-a-uuid"):
            response = ml_http.post("/documents/not-a-uuid/analyze", content=pdf, headers=headers)
            _attach_exchange("Неверный UUID", pdf, response)
        with allure.step("1.2. Проверить статус ошибки запроса"):
            _assert_status_in(response, {400, 422}, "Валидный PDF с невалидным documentId должен быть отклонён")
            _assert_useful_error(response, "Отказ для documentId, не соответствующего UUID")


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlPdfSizeLimit — превышение 20 МиБ отклонено")
@allure.description("""Шаги:
1. Дополнить валидный PDF пробелами до 20 971 521 байта.
1.1. Сохранить исходную сигнатуру и сформировать HTTP-тело.
1.2. Отправить тело без загрузки большого файла в Allure-вложение.
1.3. Проверить HTTP-статус.

Ожидание: API явно отказывает с HTTP 400 или 413. HTTP 413 рекомендован контрактом.""")
def test_integration_pdf_over_20_mib_returns_413(ml_http: httpx.Client) -> None:
    """Проверяет лимит PDF, используя валидный документ с хвостовыми пробелами."""
    document_id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    size_limit = 20_971_520
    headers = {"Content-Type": "application/pdf", "Accept": "application/json"}
    with allure.step("1. Подготовить PDF размером выше 20 МиБ"):
        with allure.step("1.1. Проверить размер и сигнатуру исходных байтов"):
            base_pdf = _make_pdf(["A valid PDF padded with trailing whitespace"])
            pdf = base_pdf + b" " * (size_limit + 1 - len(base_pdf))
            assert len(pdf) == size_limit + 1, (
                f"Тело должно быть ровно на 1 байт больше лимита {size_limit}; получено {len(pdf)}"
            )
            assert pdf.startswith(b"%PDF-"), "Тестовое тело должно начинаться с сигнатуры PDF"
        with allure.step("1.2. Отправить тело и сохранить digest вместо вложения PDF"):
            response = ml_http.post(f"/documents/{document_id}/analyze", content=pdf, headers=headers)
            _attach_exchange("PDF выше лимита", pdf, response, attach_pdf=False)
        with allure.step("1.3. Проверить отказ по размеру"):
            _assert_status_in(
                response,
                {400, 413},
                f"PDF размером {size_limit + 1} байт превышает лимит {size_limit}",
            )
            _assert_useful_error(response, "Отказ для PDF выше лимита размера")


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlPdfCorrupt — повреждённый документ отклонён")
@allure.description("""Шаги:
1. Проверить реакцию анализатора на повреждённый PDF с корректной сигнатурой.
1.1. Отправить файл с сигнатурой %PDF- и повреждённым содержимым.
1.2. Проверить HTTP-ответ анализатора.

Ожидание: API явно отказывает с HTTP 400 или 422 и сообщает причину. HTTP 422 рекомендован контрактом.""")
def test_integration_corrupt_pdf_returns_422(ml_http: httpx.Client) -> None:
    """Проверяет отказ PDF-парсера на повреждённых данных с заголовком %PDF-."""
    pdf = "%PDF-1.7\nэто не корректная структура PDF".encode("utf-8")
    headers = {"Content-Type": "application/pdf", "Accept": "application/json"}
    with allure.step("1. Передать повреждённый файл, прошедший проверку сигнатуры"):
        with allure.step("1.1. Выполнить POST analyze"):
            response = ml_http.post("/documents/55555555-5555-4555-8555-555555555555/analyze", content=pdf,
                                    headers=headers)
            _attach_exchange("Повреждённый PDF", pdf, response)
        with allure.step("1.2. Проверить явную ошибку чтения PDF"):
            _assert_status_in(
                response,
                {400, 422},
                "PDF с корректной сигнатурой и повреждённой структурой должен быть отклонён",
            )
            _assert_useful_error(response, "Отказ для повреждённой структуры PDF")


@allure.parent_suite("ML")
@allure.suite("Integration")
@allure.sub_suite("PDF")
@allure.title("TestIntegrationMlPdfNoText — PDF без текста завершается ошибкой")
@allure.description("""Шаги:
1. Проверить PDF без извлекаемого текстового слоя.
1.1. Отправить документ соответствующего параметризованного случая.
1.2. Проверить явный отказ для этого документа.

Ожидание: каждый запрос явно отклоняется с HTTP 400 или 422 и полезной причиной. HTTP 422 рекомендован контрактом.""")
@pytest.mark.parametrize(
    ("document_id", "pdf", "case_name"),
    [
        ("66666666-6666-4666-8666-666666666666", _make_pdf([None]), "Физически пустая страница"),
        ("77777777-7777-4777-8777-777777777777", _make_image_only_pdf(), "Страница-изображение без OCR"),
    ],
    ids=["blank-page", "image-only"],
)
def test_integration_pdf_without_extractable_text_returns_422(
    ml_http: httpx.Client, document_id: str, pdf: bytes, case_name: str
) -> None:
    """Проверяет явный отказ для PDF, из которого не извлечён ни один текстовый чанк."""
    headers = {"Content-Type": "application/pdf", "Accept": "application/json"}
    with allure.step(f"1. Обработать PDF без текстового слоя: {case_name}"):
        with allure.step("1.1. Выполнить POST analyze"):
            response = ml_http.post(f"/documents/{document_id}/analyze", content=pdf, headers=headers)
            _attach_exchange(case_name, pdf, response)
        with allure.step("1.2. Проверить отказ без фиктивного успеха"):
            _assert_status_in(
                response,
                {400, 422},
                f"{case_name}: PDF без извлекаемого текста должен быть отклонён",
            )
            _assert_useful_error(response, f"Отказ для случая «{case_name}» без извлекаемого текста")
