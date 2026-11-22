"""Bounded internal DTOs. Customer authorization remains in Spring."""

from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class PrivateModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)

    def __repr__(self):
        return f"{type(self).__name__}[redacted]"

    def __str__(self):
        return self.__repr__()


class Passage(PrivateModel):
    sourceId: Annotated[str, Field(pattern=r"^S[1-6]$")]
    text: Annotated[str, Field(min_length=1, max_length=1200)]


class ExecutionRequest(PrivateModel):
    runId: UUID
    correlationId: UUID
    instructions: Annotated[str, Field(min_length=1, max_length=10000)]
    message: Annotated[str, Field(min_length=1, max_length=1000)]
    passages: Annotated[list[Passage], Field(min_length=1, max_length=6)]

    @field_validator("instructions", "message")
    @classmethod
    def valid_text(cls, value):
        if not value.strip() or "\0" in value:
            raise ValueError("invalid text")
        value.encode("utf-8", errors="strict")
        return value

    @model_validator(mode="after")
    def context_bounds(self):
        if sum(len(p.text) for p in self.passages) > 8000:
            raise ValueError("context too large")
        if len({p.sourceId for p in self.passages}) != len(self.passages):
            raise ValueError("duplicate sources")
        for p in self.passages:
            p.text.encode("utf-8", errors="strict")
            if not p.text.strip() or "\0" in p.text:
                raise ValueError("invalid passage")
        return self


class Answer(PrivateModel):
    # Empty sources mean insufficient evidence. Never return this as grounded success.
    answer: Annotated[str, Field(min_length=1, max_length=6000)]
    sourceIds: Annotated[list[str], Field(max_length=6)]

    @field_validator("answer")
    @classmethod
    def valid_answer(cls, value):
        if not value.strip() or "\0" in value:
            raise ValueError("invalid answer")
        value.encode("utf-8", errors="strict")
        return value


class Usage(PrivateModel):
    inputTokens: Annotated[int, Field(ge=0, le=1000000)] | None
    outputTokens: Annotated[int, Field(ge=0, le=800)] | None


class Completion(PrivateModel):
    result: Answer
    modelId: str
    usage: Usage


class ExecutionFailure(Exception):
    def __init__(
        self,
        code: str,
        status: int,
        completion: Completion | None = None,
        *,
        model_id: str | None = None,
        usage: Usage | None = None,
    ):
        super().__init__(code)
        self.code = code
        self.status = status
        self.model_id = completion.modelId if completion else model_id
        self.usage = completion.usage if completion else usage
