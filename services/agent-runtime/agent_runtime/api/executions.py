import json
import logging
import secrets
import time

from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from agent_runtime.execution.contracts import ExecutionFailure, ExecutionRequest
from agent_runtime.proposals.contracts import ProposalRequest

router = APIRouter()
log = logging.getLogger(__name__)


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError("duplicate field")
        obj[key] = value
    return obj


@router.post("/internal/executions")
async def execute(request: Request, x_execution_secret: str = Header(default="")):
    service = request.app.state.executions
    if not service.secret or not secrets.compare_digest(
        x_execution_secret.encode("utf-8"), service.secret.encode("utf-8")
    ):
        raise HTTPException(401, "unauthorized")
    body = bytearray()
    async for part in request.stream():
        if len(body) + len(part) > 128 * 1024:
            raise HTTPException(413, "execution_too_large")
        body.extend(part)
    try:
        json.loads(body, object_pairs_hook=unique_object)
        payload = ExecutionRequest.model_validate_json(bytes(body))
    except (ValidationError, ValueError, UnicodeError):
        raise HTTPException(400, "invalid_execution") from None
    start = time.monotonic()
    try:
        result = await service.execute(payload)
        log.info(
            "execution_completed run_id=%s correlation_id=%s status=SUCCEEDED "
            "model=%s duration_ms=%d input_tokens=%s output_tokens=%s",
            payload.runId,
            payload.correlationId,
            result["modelId"],
            (time.monotonic() - start) * 1000,
            result["usage"]["inputTokens"],
            result["usage"]["outputTokens"],
        )
        return result
    except ExecutionFailure as error:
        log.info(
            "execution_failed run_id=%s correlation_id=%s status=FAILED "
            "code=%s model=%s duration_ms=%d input_tokens=%s output_tokens=%s",
            payload.runId,
            payload.correlationId,
            error.code,
            error.model_id,
            (time.monotonic() - start) * 1000,
            error.usage.inputTokens if error.usage else None,
            error.usage.outputTokens if error.usage else None,
        )
        return JSONResponse(
            status_code=error.status,
            content={
                "detail": error.code,
                "modelId": error.model_id,
                "usage": error.usage.model_dump() if error.usage else None,
            },
        )
    except Exception:
        log.error(
            "execution_failed run_id=%s correlation_id=%s code=RUNTIME_FAILURE",
            payload.runId,
            payload.correlationId,
        )
        raise HTTPException(500, "RUNTIME_FAILURE") from None


@router.post("/internal/tool-proposals")
async def propose(request: Request, x_execution_secret: str = Header(default="")):
    service = request.app.state.executions
    if not service.secret or not secrets.compare_digest(
        x_execution_secret.encode("utf-8"), service.secret.encode("utf-8")
    ):
        raise HTTPException(401, "unauthorized")
    body = bytearray()
    async for part in request.stream():
        if len(body) + len(part) > 128 * 1024:
            raise HTTPException(413, "proposal_too_large")
        body.extend(part)
    try:
        json.loads(body, object_pairs_hook=unique_object)
        payload = ProposalRequest.model_validate_json(bytes(body))
    except (ValidationError, ValueError, UnicodeError):
        raise HTTPException(400, "invalid_proposal_request") from None
    start = time.monotonic()
    try:
        result = await service.propose(payload)
        log.info(
            "proposal_generated request_id=%s correlation_id=%s model=%s "
            "duration_ms=%d input_tokens=%s output_tokens=%s",
            payload.runId,
            payload.correlationId,
            result["modelId"],
            (time.monotonic() - start) * 1000,
            result["usage"]["inputTokens"],
            result["usage"]["outputTokens"],
        )
        return result
    except ExecutionFailure as error:
        log.info(
            "proposal_failed request_id=%s correlation_id=%s code=%s model=%s "
            "duration_ms=%d input_tokens=%s output_tokens=%s",
            payload.runId,
            payload.correlationId,
            error.code,
            error.model_id,
            (time.monotonic() - start) * 1000,
            error.usage.inputTokens if error.usage else None,
            error.usage.outputTokens if error.usage else None,
        )
        return JSONResponse(
            status_code=error.status,
            content={
                "detail": error.code,
                "modelId": error.model_id,
                "usage": error.usage.model_dump() if error.usage else None,
            },
        )
    except Exception:
        log.error(
            "proposal_failed request_id=%s correlation_id=%s code=RUNTIME_FAILURE",
            payload.runId,
            payload.correlationId,
        )
        raise HTTPException(502, "INVALID_MODEL_OUTPUT") from None
