from fastapi import FastAPI

app = FastAPI(title="Claims & Policy Assistant")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
