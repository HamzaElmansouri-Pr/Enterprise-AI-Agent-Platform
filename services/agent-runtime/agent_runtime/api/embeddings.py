import math
import os
import secrets
import threading
from typing import Annotated, Literal

from fastapi import APIRouter, Header, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from agent_runtime.embedding.model import MODEL_ID, LocalEmbeddings

router = APIRouter()


class Batch(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    kind: Literal["passage", "query"]
    texts: Annotated[
        list[Annotated[str, Field(min_length=1, max_length=1200)]],
        Field(min_length=1, max_length=8),
    ]


class EmbeddingService:
    def __init__(self):
        self.secret = os.environ.get("EMBEDDING_SECRET", "")
        if self.secret and (
            len(self.secret) < 32
            or len(self.secret) > 128
            or not self.secret.isascii()
            or any(ord(c) < 33 or ord(c) > 126 for c in self.secret)
        ):
            raise ValueError(
                "EMBEDDING_SECRET must contain 32-128 visible ASCII characters"
            )
        self.lock = threading.Lock()
        self.adapter = LocalEmbeddings(
            os.environ.get("EMBEDDING_MODEL_DIR", "/models/e5-small")
        )


@router.post("/internal/embeddings")
async def embeddings(request: Request, x_embedding_secret: str = Header(default="")):
    service = request.app.state.embeddings
    if not service.secret or not secrets.compare_digest(
        x_embedding_secret.encode("utf-8"), service.secret.encode("utf-8")
    ):
        raise HTTPException(401, "unauthorized")
    body = bytearray()
    async for part in request.stream():
        body.extend(part)
        if len(body) > 64 * 1024:
            raise HTTPException(413, "batch_too_large")
    try:
        batch = Batch.model_validate_json(bytes(body))
    except ValidationError:
        # Default FastAPI validation details may echo private input. Never return those.
        raise HTTPException(400, "invalid_batch") from None
    if not service.lock.acquire(blocking=False):
        raise HTTPException(429, "embedding_busy")
    try:
        from starlette.concurrency import run_in_threadpool

        vectors = await run_in_threadpool(
            service.adapter.embed, batch.kind, batch.texts
        )
        if len(vectors) != len(batch.texts) or any(
            len(v) != 384
            or not all(math.isfinite(x) for x in v)
            or not 0.99 < sum(x * x for x in v) < 1.01
            for v in vectors
        ):
            raise ValueError("Invalid embeddings")
        return {"modelId": MODEL_ID, "dimension": 384, "vectors": vectors}
    except Exception:
        raise HTTPException(503, "embedding_unavailable") from None
    finally:
        service.lock.release()
