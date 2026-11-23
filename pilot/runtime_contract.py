"""TEST ONLY: launch accepted pilot runtime with the existing offline SDK transport.

Not baked into any image. Real startup validation runs with provider disabled;
then only this owned test process receives a synthetic in-memory provider adapter.
The Compose runtime network is internal, so no external provider is reachable.
"""

import os
import sys

import uvicorn

sys.path.insert(0, "/app")
sys.path.insert(0, "/evaluation")
from mock_runtime import app  # noqa: E402

from agent_runtime.execution.provider import MODEL  # noqa: E402
from agent_runtime.execution.service import ExecutionService  # noqa: E402

if os.environ.get("EAAP_PROVIDER_MODE") != "disabled":
    raise RuntimeError("Contract harness requires disabled production provider")
# mock_runtime imports the real app first: pilot secrets/model/startup checks ran.
# Rebuild only the execution service with the existing test-only transport installed.
os.environ["OPENAI_API_KEY"] = "synthetic-not-a-provider-key"
os.environ["EXECUTION_MODEL"] = MODEL
try:
    app.state.executions = ExecutionService()
finally:
    del os.environ["OPENAI_API_KEY"]
    del os.environ["EXECUTION_MODEL"]

if __name__ == "__main__":
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=8000,
        access_log=False,
        server_header=False,
        proxy_headers=False,
        limit_concurrency=8,
        backlog=16,
    )
