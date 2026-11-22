import asyncio
import threading

from langsmith import tracing_context

from agent_runtime.execution.contracts import ExecutionFailure, ExecutionRequest
from agent_runtime.execution.graph import build_graph
from agent_runtime.execution.provider import OpenAIProvider
from agent_runtime.operations import secret
from agent_runtime.proposals.contracts import ProposalRequest
from agent_runtime.proposals.graph import build_proposal_graph


class ExecutionService:
    def __init__(self):
        self.secret = secret("EXECUTION_INTERNAL_SECRET")
        if self.secret and (
            not 32 <= len(self.secret) <= 128
            or any(not 33 <= ord(c) <= 126 for c in self.secret)
            or self.secret == secret("EMBEDDING_SECRET")
        ):
            raise ValueError("Invalid dedicated execution secret")
        self.slots = threading.BoundedSemaphore(2)
        provider = OpenAIProvider()
        self.graph = build_graph(provider)
        self.proposal_graph = build_proposal_graph(provider)

    async def execute(self, request: ExecutionRequest):
        if not self.slots.acquire(blocking=False):
            raise ExecutionFailure("EXECUTION_BUSY", 503)
        try:
            # LangGraph's transitive tracer must not export private state, even if
            # a host inherited tracing environment settings. No trace backend is used.
            with tracing_context(enabled=False):
                async with asyncio.timeout(45):
                    state = await self.graph.ainvoke(
                        {"request": request}, {"recursion_limit": 5, "callbacks": []}
                    )
            completion = state["completion"]
            return {
                "answer": completion.result.answer,
                "sourceIds": state["source_ids"],
                "modelId": completion.modelId,
                "usage": completion.usage.model_dump(),
            }
        except TimeoutError:
            raise ExecutionFailure("PROVIDER_TIMEOUT", 504) from None
        finally:
            self.slots.release()

    async def propose(self, request: ProposalRequest):
        # Same slots as ordinary answers; no second paid concurrency allowance.
        if not self.slots.acquire(blocking=False):
            raise ExecutionFailure("EXECUTION_BUSY", 503)
        try:
            with tracing_context(enabled=False):
                async with asyncio.timeout(45):
                    state = await self.proposal_graph.ainvoke(
                        {"request": request}, {"recursion_limit": 5, "callbacks": []}
                    )
            completion = state["completion"]
            return {
                "proposal": state["proposal"].model_dump(),
                "modelId": completion.modelId,
                "usage": completion.usage.model_dump(),
            }
        except TimeoutError:
            raise ExecutionFailure("PROVIDER_TIMEOUT", 504) from None
        finally:
            self.slots.release()
