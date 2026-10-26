import hashlib
import json
import os
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import fitz

STUB = os.getenv("ML_STUB", "false").lower() == "true"
MODE = "stub" if STUB else os.getenv("ML_EMBEDDER") or os.getenv("ML_EMBEDDING_MODEL") or "stub"
MAX_WORDS = 250

OCR_LANG = os.getenv("ML_OCR_LANG", "rus+eng")
OCR_DPI = int(os.getenv("ML_OCR_DPI", "200"))
OCR_WORKERS = int(os.getenv("ML_OCR_WORKERS", "0")) or min(4, os.cpu_count() or 1)
OCR_CACHE_DIR = Path(os.getenv("ML_OCR_CACHE_DIR", "/tmp/ml-ocr-cache"))
MIN_PAGE_CHARS = 20

_model = None
if MODE != "stub":
    from fastembed import TextEmbedding
    if MODE == "intfloat/multilingual-e5-small":
        from fastembed.common.model_description import ModelSource, PoolingType
        TextEmbedding.add_custom_model(
            model=MODE,
            pooling=PoolingType.MEAN,
            normalization=True,
            sources=ModelSource(hf=MODE),
            dim=384,
            model_file="onnx/model.onnx",
        )
    _model = TextEmbedding(model_name=MODE)


def embed(texts: list[str]) -> list[list[float]]:
    if _model is None:
        return [[1.0, 0.5, 0.25, 0.125, 0.0, 0.0, 0.0, 0.0] for _ in texts]
    return [vector.tolist() for vector in _model.embed(texts)]


def split_into_chunks(text: str, max_words: int = MAX_WORDS) -> list[str]:
    words = text.split()
    return [" ".join(words[i:i + max_words]) for i in range(0, len(words), max_words)]


def _ocr_page(args: tuple[bytes, int]) -> str:
    data, index = args
    with fitz.open(stream=data, filetype="pdf") as doc:
        page = doc[index]
        textpage = page.get_textpage_ocr(language=OCR_LANG, dpi=OCR_DPI, full=True)
        return page.get_text(textpage=textpage).strip()


def _ocr_pages(data: bytes, indexes: list[int]) -> dict[int, str]:
    cache_file = OCR_CACHE_DIR / f"{hashlib.sha256(data).hexdigest()}.json"
    if cache_file.exists():
        return {int(index): text for index, text in json.loads(cache_file.read_text(encoding="utf-8")).items()}
    try:
        if len(indexes) == 1 or OCR_WORKERS == 1:
            texts = [_ocr_page((data, index)) for index in indexes]
        else:
            with ProcessPoolExecutor(max_workers=min(OCR_WORKERS, len(indexes))) as pool:
                texts = list(pool.map(_ocr_page, [(data, index) for index in indexes]))
    except Exception as error:
        pages = [index + 1 for index in indexes]
        raise ValueError(f"Страницы {pages} — изображения без текста, а OCR недоступен: {error}")
    recognized = dict(zip(indexes, texts))
    if all(recognized.values()):
        OCR_CACHE_DIR.mkdir(parents=True, exist_ok=True)
        cache_file.write_text(json.dumps(recognized, ensure_ascii=False), encoding="utf-8")
    return recognized


def extract_pages(data: bytes) -> list[tuple[int, str]]:
    try:
        doc = fitz.open(stream=data, filetype="pdf")
    except Exception:
        raise ValueError("Не удалось прочитать PDF")
    with doc:
        if doc.needs_pass:
            raise ValueError("PDF защищён паролем")
        texts = [page.get_text().strip() for page in doc]
        scanned = [index for index, text in enumerate(texts)
                   if len(text) < MIN_PAGE_CHARS and doc[index].get_images()]
    if scanned:
        recognized = _ocr_pages(data, scanned)
        unreadable = [index + 1 for index in scanned if not recognized[index]]
        if unreadable:
            raise ValueError(f"Не удалось распознать текст на страницах-изображениях: {unreadable}")
        for index in scanned:
            texts[index] = recognized[index]
    return [(index + 1, text) for index, text in enumerate(texts) if text]


def analyze_pdf(data: bytes) -> dict:
    chunks = []
    for page_number, text in extract_pages(data):
        for piece in split_into_chunks(text):
            chunks.append({"ordinal": len(chunks), "page": page_number, "text": piece})

    if not chunks:
        raise ValueError("В PDF нет текста")

    vectors = embed([f"passage: {c['text']}" for c in chunks])
    for chunk, vector in zip(chunks, vectors):
        chunk["embedding"] = vector

    return {
        "schemaVersion": 1,
        "stub": _model is None,
        "embeddingModel": "stub-v1" if _model is None else MODE,
        "embeddingDimension": len(vectors[0]),
        "chunks": chunks,
    }