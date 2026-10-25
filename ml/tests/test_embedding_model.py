import unittest
from unittest.mock import patch

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
    def test_pdf_chunks_use_passage_prefix_and_real_model_metadata(self):
        document = fitz.open()
        document.new_page().insert_text((72, 72), "Company revenue growth")
        source = document.tobytes()
        document.close()
        model = Model()

        with patch.object(pdf, "_model", model), patch.object(pdf, "MODE", MODEL_NAME):
            result = pdf.analyze_pdf(source)

        self.assertEqual(model.inputs, ["passage: Company revenue growth"])
        self.assertEqual(result["embeddingModel"], MODEL_NAME)
        self.assertEqual(result["embeddingDimension"], 384)
        self.assertFalse(result["stub"])
        self.assertEqual(result["chunks"][0]["embedding"], VECTOR)

    def test_query_endpoint_uses_query_prefix_and_real_model_metadata(self):
        model = Model()

        with patch.object(main, "_model", model), patch.object(main, "MODE", MODEL_NAME), patch.object(
            main, "embed", side_effect=lambda texts: [VECTOR for _ in texts]
        ) as encode:
            result = main.embeddings(main.EmbeddingRequest(text="ставка ЦБ"))

        self.assertEqual(encode.call_args.args[0], ["query: ставка ЦБ"])
        self.assertEqual(result["embeddingModel"], MODEL_NAME)
        self.assertEqual(result["embeddingDimension"], 384)
        self.assertFalse(result["stub"])
