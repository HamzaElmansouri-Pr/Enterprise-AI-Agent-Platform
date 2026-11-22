from fastapi import FastAPI

from agent_runtime.api.embeddings import EmbeddingService
from agent_runtime.api.embeddings import router as embedding_router
from agent_runtime.api.executions import router as execution_router
from agent_runtime.api.health import router
from agent_runtime.config import Settings
from agent_runtime.execution.service import ExecutionService
from agent_runtime.operations import router as operations_router
from agent_runtime.operations import validate_pilot


def create_app() -> FastAPI:
    validate_pilot()
    settings = Settings.from_environment()
    app = FastAPI(
        title="Agent Runtime",
        debug=False,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = settings
    app.state.embeddings = EmbeddingService()
    app.state.executions = ExecutionService()
    app.include_router(router)
    if settings.environment == "pilot":
        app.include_router(operations_router)
    app.include_router(embedding_router)
    app.include_router(execution_router)
    return app


app = create_app()
