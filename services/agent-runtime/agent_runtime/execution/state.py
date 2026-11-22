from typing import TypedDict

from agent_runtime.execution.contracts import Completion, ExecutionRequest


class ExecutionState(TypedDict, total=False):
    request: ExecutionRequest
    messages: list[dict[str, str]]
    completion: Completion
    source_ids: list[str]
