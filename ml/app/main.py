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