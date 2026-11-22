"""One opt-in hosted adapter; no production fake, URL override, or retry."""

import asyncio
import logging
import os
from typing import Protocol, TypeVar

import httpx
from openai import APIError, APITimeoutError, AsyncOpenAI
from pydantic import BaseModel, ValidationError

from agent_runtime.execution.contracts import (
    Answer,
    Completion,
    ExecutionFailure,
    Usage,
)
from agent_runtime.proposals.contracts import ProposalCompletion, ToolProposal

_CompletionT = TypeVar("_CompletionT", Completion, ProposalCompletion)

MODEL = "gpt-5.4-mini-2026-03-17"
for name in ("openai", "httpx", "httpcore"):
    logging.getLogger(name).setLevel(logging.CRITICAL + 1)
    logging.getLogger(name).disabled = True


class Provider(Protocol):
    async def complete(self, messages: list[dict[str, str]]) -> Completion: ...


class OpenAIProvider:
    def __init__(self):
        self.key = os.environ.get("OPENAI_API_KEY", "")
        self.model = os.environ.get("EXECUTION_MODEL", "")
        try:
            self.tokens = int(os.environ.get("EXECUTION_MAX_OUTPUT_TOKENS", "800"))
            self.timeout = float(
                os.environ.get("EXECUTION_PROVIDER_TIMEOUT_SECONDS", "30")
            )
        except ValueError:
            self.tokens, self.timeout = 0, 0

    async def complete(self, messages: list[dict[str, str]]) -> Completion:
        return await self._request(messages, Answer, Completion)

    async def propose(self, messages: list[dict[str, str]]) -> ProposalCompletion:
        return await self._request(messages, ToolProposal, ProposalCompletion)

    async def _request(
        self,
        messages: list[dict[str, str]],
        schema: type[BaseModel],
        completion_type: type[_CompletionT],
    ) -> _CompletionT:
        # Schemas are selected only by these two fixed methods, never caller input.
        if (
            not self.key
            or self.model != MODEL
            or not 1 <= self.tokens <= 800
            or not 1 <= self.timeout <= 40
        ):
            raise ExecutionFailure("MODEL_UNAVAILABLE", 503)
        # Construct only on an execution, so startup/health/embedding need no API key.
        # Explicit URL and trust_env=False ignore caller/environment proxy/base URLs.
        async with AsyncOpenAI(
            api_key=self.key,
            base_url="https://api.openai.com/v1",
            max_retries=0,
            timeout=self.timeout,
            http_client=httpx.AsyncClient(
                trust_env=False,
                timeout=self.timeout,
                follow_redirects=False,
                limits=httpx.Limits(max_connections=2, max_keepalive_connections=0),
            ),
        ) as client:
            try:
                async with asyncio.timeout(self.timeout):
                    response = await client.responses.parse(
                        model=MODEL,
                        input=messages,
                        text_format=schema,
                        max_output_tokens=self.tokens,
                        store=False,
                        reasoning={"effort": "none"},
                    )
                usage = Usage(
                    inputTokens=response.usage.input_tokens if response.usage else None,
                    outputTokens=response.usage.output_tokens
                    if response.usage
                    else None,
                )
                if response.model != MODEL:
                    raise ExecutionFailure("INVALID_MODEL_OUTPUT", 502)
                if response.status != "completed" or response.output_parsed is None:
                    raise ExecutionFailure(
                        "INVALID_MODEL_OUTPUT", 502, model_id=MODEL, usage=usage
                    )
                return completion_type(
                    result=response.output_parsed,
                    modelId=response.model,
                    usage=usage,
                )
            except (TimeoutError, APITimeoutError):
                raise ExecutionFailure("PROVIDER_TIMEOUT", 504) from None
            except APIError:
                raise ExecutionFailure("PROVIDER_UNAVAILABLE", 502) from None
            except (ValidationError, ValueError):
                raise ExecutionFailure("INVALID_MODEL_OUTPUT", 502) from None
