import logging
import os
import threading
from collections.abc import Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware

from assistant.config import Settings
from assistant.graph import Assistant
from assistant.schemas import AskRequest, AskResponse

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("rag-assistant")


def _load_real_assistant() -> Assistant:
    from assistant.runtime import build_assistant  # imports torch-backed adapters lazily

    return build_assistant(Settings.from_env())


def create_app(
    loader: Callable[[], Assistant] = _load_real_assistant, background: bool = True
) -> FastAPI:
    """`loader` builds the Assistant. It runs in a background thread by default so uvicorn is
    serving (and `/health` answers) while the model weights load; `/ready` and `/assistant/ask`
    return 503 until it finishes. Tests pass `background=False` and a fake-backed loader."""

    def load(app: FastAPI) -> None:
        try:
            app.state.assistant = loader()
            logger.info("assistant ready")
        except Exception as exc:  # surfaced via /ready and 503s, not swallowed silently
            logger.exception("assistant failed to load")
            app.state.load_error = str(exc)
        finally:
            app.state.loaded.set()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.assistant = None
        app.state.load_error = None
        app.state.loaded = threading.Event()
        if background:
            threading.Thread(target=load, args=(app,), daemon=True).start()
        else:
            load(app)
        yield

    app = FastAPI(title="Claims & Policy Assistant", lifespan=lifespan)

    # Same env var and default as claims-intake-service's CORS config. Read here rather than via
    # Settings, which is only built inside the background loader (Settings requires MONGODB_URI).
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[os.environ.get("CORS_ALLOWED_ORIGIN") or "http://localhost:3000"],
        allow_methods=["POST"],
        allow_headers=["Content-Type"],
    )

    @app.get("/health")
    def health() -> dict[str, str]:
        # Liveness only: stays 200 while models load so Docker's healthcheck doesn't flap.
        return {"status": "ok"}

    @app.get("/ready")
    def ready(request: Request) -> dict[str, str]:
        state = request.app.state
        if getattr(state, "assistant", None) is not None:
            return {"status": "ready"}
        if getattr(state, "load_error", None):
            raise HTTPException(503, f"assistant failed to load: {state.load_error}")
        raise HTTPException(503, "assistant is still loading models")

    @app.post("/assistant/ask", response_model=AskResponse)
    def ask(body: AskRequest, request: Request) -> AskResponse:
        state = request.app.state
        assistant = getattr(state, "assistant", None)
        if assistant is None:
            if getattr(state, "load_error", None):
                raise HTTPException(503, f"assistant failed to load: {state.load_error}")
            raise HTTPException(503, "assistant is still loading models")
        return assistant.ask(body.question, body.claimId)

    return app


app = create_app()
