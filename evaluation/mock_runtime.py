"""TEST ONLY. Mounted solely into a disposable, network-isolated offline lab.

Exercises the real SDK/graph contract. Answers are deliberately synthetic and
must never contribute to hosted-model quality scores.
"""

import asyncio
import json
from pathlib import Path

import httpx
from openai import AsyncOpenAI

from agent_runtime.execution import provider
from agent_runtime.main import app  # noqa: F401

proof = []


def handler(request):
    body = json.loads(request.content)
    assert str(request.url) == "https://api.openai.com/v1/responses"
    assert body["model"] == provider.MODEL and body["store"] is False
    assert body["max_output_tokens"] <= 800 and "tools" not in body
    data = json.loads(body["input"][2]["content"].split("\n", 1)[1])
    history = data.get("prior_complete_turns_untrusted", [])
    assert len(history) <= 6
    assert sum(len(t["question"]) + len(t["answer"]) for t in history) <= 8000
    assert sum(len(m["content"]) for m in body["input"]) <= 40000
    question = data["question"]
    proof.append({"question": question, "history": history})
    Path("/tmp/evaluation-proof.json").write_text(json.dumps(proof))
    source_ids = [data["evidence"][0]["sourceId"]]
    if question == "Invalid citation control":
        source_ids = ["S99"]
    if question == "Missing citation control":
        source_ids = []
    answer = "SYNTHETIC CONTRACT ANSWER"
    if question == "Large answer control":
        answer = "A" * 5900
    output = {"answer": answer, "sourceIds": source_ids}
    return httpx.Response(
        200,
        json={
            "id": "resp_synthetic",
            "object": "response",
            "created_at": 1,
            "status": "completed",
            "model": provider.MODEL,
            "output": [
                {
                    "type": "message",
                    "id": "msg_synthetic",
                    "role": "assistant",
                    "status": "completed",
                    "content": [
                        {
                            "type": "output_text",
                            "text": json.dumps(output),
                            "annotations": [],
                        }
                    ],
                }
            ],
            "usage": {
                "input_tokens": 37,
                "output_tokens": 9,
                "total_tokens": 46,
                "input_tokens_details": {"cached_tokens": 0},
                "output_tokens_details": {"reasoning_tokens": 0},
            },
        },
    )


def mock_client(**kwargs):
    asyncio.get_running_loop().create_task(kwargs["http_client"].aclose())
    kwargs["http_client"] = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return AsyncOpenAI(**kwargs)


provider.AsyncOpenAI = mock_client
