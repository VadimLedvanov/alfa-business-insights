import json
import unittest
from unittest.mock import patch

import allure
import fitz

from app import main, pdf


MODEL_NAME = "intfloat/multilingual-e5-small"
VECTOR = [float(i) / 384 for i in range(1, 385)]


class Model:
    def __init__(self):
        self.inputs = None

    def embed(self, texts):
        self.inputs = texts
        return iter(Vectors(VECTOR) for _ in texts)


class Vectors(list):
    def tolist(self):
        return list(self)


class EmbeddingModelTest(unittest.TestCase):
    @allure.parent_suite("ML")
    @allure.suite("Unit")
    @allure.sub_suite("Embedding model")
    @allure.title("TestUnitPdfPassageEmbedding — PDF использует passage-префикс")
    @allure.description("""Unit-проверка с подменённой моделью и детерминированным вектором.
Реальная модель E5 не загружается и внешние сервисы не вызываются.

Шаги:
1. Создать PDF с текстом и передать анализатору.
2. Проверить вход mock-модели и метаданные результата.

Ожидание: текст передан с префиксом passage:, указаны имя и размерность
настроенной модели, stub=false, а ответ содержит вектор mock-модели.""")
    def test_pdf_chunks_use_passage_prefix_and_real_model_metadata(self):
        """Проверяет подготовку passage-входа и контракт mock-результата."""
        document = fitz.open()
        document.new_page().insert_text((72, 72), "Company revenue growth")
        source = document.tobytes()
        document.close()
        model = Model()
        allure.attach(
            json.dumps(
                {
                    "pdfText": "Company revenue growth",
                    "configuredModel": MODEL_NAME,
                    "execution": "mock; real model is not loaded",
                },
                ensure_ascii=False,
                indent=2,
            ),
            name="Вход unit-проверки PDF embedding",
            attachment_type=allure.attachment_type.JSON,
        )

        with allure.step("1. Передать PDF анализатору с подменённой моделью"):
            with patch.object(pdf, "_model", model), patch.object(pdf, "MODE", MODEL_NAME):
                result = pdf.analyze_pdf(source)
        with allure.step("2. Проверить passage-вход и метаданные mock-результата"):
            self.assertEqual(model.inputs, ["passage: Company revenue growth"])
            self.assertEqual(result["embeddingModel"], MODEL_NAME)
            self.assertEqual(result["embeddingDimension"], 384)
            self.assertFalse(result["stub"])
            self.assertEqual(result["chunks"][0]["embedding"], VECTOR)
            allure.attach(
                json.dumps(
                    {
                        "modelInput": model.inputs,
                        "embeddingModel": result["embeddingModel"],
                        "embeddingDimension": result["embeddingDimension"],
                        "stub": result["stub"],
                        "embedding": result["chunks"][0]["embedding"],
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                name="Выход unit-проверки PDF embedding",
                attachment_type=allure.attachment_type.JSON,
            )

    @allure.parent_suite("ML")
    @allure.suite("Unit")
    @allure.sub_suite("Embedding model")
    @allure.title("TestUnitQueryEmbedding — запрос использует query-префикс")
    @allure.description("""Unit-проверка прямого вызова функции без HTTP и реальной модели.
Кодирование подменено mock-функцией с детерминированным вектором.

Шаги:
1. Передать текст запроса функции embeddings.
2. Проверить query-вход mock-кодировщика и метаданные результата.

Ожидание: mock получает текст с префиксом query:, результат содержит имя
и размерность настроенной модели и отмечен как не stub.""")
    def test_query_endpoint_uses_query_prefix_and_real_model_metadata(self):
        """Проверяет подготовку query-входа и контракт mock-результата."""
        model = Model()
        allure.attach(
            json.dumps(
                {
                    "queryText": "ставка ЦБ",
                    "configuredModel": MODEL_NAME,
                    "execution": "direct function call with mocked embed; no HTTP or model load",
                },
                ensure_ascii=False,
                indent=2,
            ),
            name="Вход unit-проверки query embedding",
            attachment_type=allure.attachment_type.JSON,
        )

        with patch.object(main, "_model", model), patch.object(main, "MODE", MODEL_NAME), patch.object(
            main, "embed", side_effect=lambda texts: [VECTOR for _ in texts]
        ) as encode:
            with allure.step("1. Вызвать функцию embeddings для текста запроса"):
                result = main.embeddings(main.EmbeddingRequest(text="ставка ЦБ"))

        with allure.step("2. Проверить query-вход и метаданные mock-результата"):
            self.assertEqual(encode.call_args.args[0], ["query: ставка ЦБ"])
            self.assertEqual(result["embeddingModel"], MODEL_NAME)
            self.assertEqual(result["embeddingDimension"], 384)
            self.assertFalse(result["stub"])
            allure.attach(
                json.dumps(
                    {
                        "encoderInput": encode.call_args.args[0],
                        "embeddingModel": result["embeddingModel"],
                        "embeddingDimension": result["embeddingDimension"],
                        "stub": result["stub"],
                        "embedding": result["embedding"],
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                name="Выход unit-проверки query embedding",
                attachment_type=allure.attachment_type.JSON,
            )
