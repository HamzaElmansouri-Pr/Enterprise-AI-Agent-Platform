"""The only proposed action is inert data, never a model-invoked function."""

import re
from typing import Annotated, Literal, Protocol
from uuid import UUID

from pydantic import Field, field_validator

from agent_runtime.execution.contracts import PrivateModel, Usage


class FollowupArguments(PrivateModel):
    title: Annotated[str, Field(min_length=1, max_length=120)]
    description: Annotated[str, Field(min_length=1, max_length=1000)]
    priority: Literal["LOW", "NORMAL", "HIGH"]

    @field_validator("title", "description")
    @classmethod
    def safe_text(cls, value):
        if not value.strip() or "\0" in value:
            raise ValueError("invalid argument")
        value.encode("utf-8", errors="strict")
        if re.search(
            r"(?i)\b[a-z][a-z0-9+.-]*://|\b(?:mailto|file|data):|\bwww\.", value
        ):
            raise ValueError("URLs are not tool arguments")
        return value

    @field_validator("title")
    @classmethod
    def safe_title(cls, value):
        if any(
            ord(c) < 32
            or 127 <= ord(c) <= 159
            or 0x2028 <= ord(c) <= 0x202E
            or 0x2066 <= ord(c) <= 0x2069
            for c in value
        ):
            raise ValueError("unsafe title")
        return value


class ToolProposal(PrivateModel):
    toolName: Literal["admissions.create_followup_task"]
    arguments: FollowupArguments
    rationale: Annotated[str, Field(min_length=1, max_length=500)]

    @field_validator("rationale")
    @classmethod
    def safe_rationale(cls, value):
        if not value.strip() or "\0" in value:
            raise ValueError("invalid rationale")
        value.encode("utf-8", errors="strict")
        return value


class ProposalRequest(PrivateModel):
    runId: UUID
    correlationId: UUID
    instructions: Annotated[str, Field(min_length=1, max_length=10000)]
    intent: Annotated[str, Field(min_length=1, max_length=1000)]

    @field_validator("instructions", "intent")
    @classmethod
    def safe_text(cls, value):
        if not value.strip() or "\0" in value:
            raise ValueError("invalid text")
        value.encode("utf-8", errors="strict")
        return value


class ProposalCompletion(PrivateModel):
    result: ToolProposal
    modelId: str
    usage: Usage


class ProposalProvider(Protocol):
    async def propose(self, messages: list[dict[str, str]]) -> ProposalCompletion: ...
