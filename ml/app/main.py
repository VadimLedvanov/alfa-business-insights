from fastapi import FastAPI, Request, HTTPException
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

from pydantic import BaseModel
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
