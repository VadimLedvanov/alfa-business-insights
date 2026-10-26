from fastapi import FastAPI, Request, HTTPException
from pydantic import BaseModel
from app.pdf import analyze_pdf

app = FastAPI()


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/documents/{document_id}/analyze")
async def analyze(document_id: str, request: Request):
    data = await request.body()
    if not data:
        raise HTTPException(status_code=400, detail="Пустое тело запроса")
    try:
        result = analyze_pdf(data)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))
    result["documentId"] = document_id
    return result

from app.pdf import embed, MODE, _model


class EmbeddingRequest(BaseModel):
    text: str


@app.post("/embeddings")
def embeddings(body: EmbeddingRequest):
    if not body.text.strip():
        raise HTTPException(status_code=400, detail="Пустой текст")
    vector = embed([f"query: {body.text}"])[0]
    return {
        "embeddingModel": "stub-v1" if _model is None else MODE,
        "embeddingDimension": len(vector),
        "stub": _model is None,
        "embedding": vector,
    }


class CompareEmbeddingsRequest(BaseModel):
    companyText: str
    eventTexts: list[str]


@app.post("/embeddings/compare")
def compare_embeddings(body: CompareEmbeddingsRequest):
    if not body.companyText.strip() or not body.eventTexts or any(not text.strip() for text in body.eventTexts):
        raise HTTPException(status_code=400, detail="Нужны текст компании и непустые тексты событий")
    if len(body.eventTexts) > 100:
        raise HTTPException(status_code=400, detail="Можно сравнить не более 100 событий")

    vectors = embed([f"query: {body.companyText}", *(f"passage: {text}" for text in body.eventTexts)])
    stub = _model is None
    return {
        "embeddingModel": "stub-v1" if stub else MODE,
        "embeddingDimension": len(vectors[0]),
        "stub": stub,
        "companyEmbedding": vectors[0],
        "eventEmbeddings": vectors[1:],
    }

from app.ranking import rank as rank_events


class RankRequest(BaseModel):
    company: dict
    candidates: list[dict]
    topK: int = 3
    role: str | None = None


@app.post("/rank")
def rank(body: RankRequest):
    if not 1 <= body.topK <= 50:
        raise HTTPException(status_code=400, detail="topK должен быть от 1 до 50")
    if len(body.candidates) > 500:
        raise HTTPException(status_code=400, detail="Можно ранжировать не более 500 событий")
    return rank_events(body.company, body.candidates, body.topK, body.role)
import threading

from app.news import collect, recent_materials

collect_lock = threading.Lock()


@app.post("/news/collect")
def collect_news(hours: int = 24):
    if not 1 <= hours <= 168:
        raise HTTPException(status_code=400, detail="hours должен быть от 1 до 168")
    if collect_lock.acquire(blocking=False):
        try:
            stats = collect()
        finally:
            collect_lock.release()
    else:
        stats = {"skipped": "сбор уже выполняется"}
    return {"stats": stats, "materials": recent_materials(hours)}
