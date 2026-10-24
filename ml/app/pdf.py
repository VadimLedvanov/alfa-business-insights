import os
import fitz

MODE = os.getenv("ML_EMBEDDING_MODEL") or "stub"
MAX_WORDS = 250

_model = None
if MODE != "stub":
    from sentence_transformers import SentenceTransformer
    _model = SentenceTransformer(MODE)


def embed(texts: list[str]) -> list[list[float]]:
    if _model is None:
        return [[1.0, 0.5, 0.25, 0.125, 0.0, 0.0, 0.0, 0.0] for _ in texts]
    vectors = _model.encode(texts, normalize_embeddings=True)
    return vectors.astype("float32").tolist()


def split_into_chunks(text: str, max_words: int = MAX_WORDS) -> list[str]:
    words = text.split()
    return [" ".join(words[i:i + max_words]) for i in range(0, len(words), max_words)]


def analyze_pdf(data: bytes) -> dict:
    try:
        doc = fitz.open(stream=data, filetype="pdf")
    except Exception:
        raise ValueError("Не удалось прочитать PDF")

    chunks = []
    for page_number, page in enumerate(doc, start=1):
        text = page.get_text().strip()
        if not text:
            continue
        for piece in split_into_chunks(text):
            chunks.append({"ordinal": len(chunks), "page": page_number, "text": piece})
    doc.close()

    if not chunks:
        raise ValueError("В PDF нет текста")

    vectors = embed([c["text"] for c in chunks])
    for chunk, vector in zip(chunks, vectors):
        chunk["embedding"] = vector

    return {
        "schemaVersion": 1,
        "stub": _model is None,
        "embeddingModel": "stub-v1" if _model is None else MODE,
        "embeddingDimension": len(vectors[0]),
        "chunks": chunks,
    }