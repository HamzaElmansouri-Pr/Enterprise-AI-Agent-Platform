"""Pilot-only file secrets, startup checks and bounded process diagnostics."""

import hashlib
import json
import os
import secrets
import time
from collections import Counter
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException

from agent_runtime.embedding.model import FILES, MODEL_ID

STARTED = time.monotonic()
COUNTERS = Counter()
EVENTS = {
    "embedding_failed",
    "execution_failed",
    "provider_timeout",
    "busy",
    "execution_completed",
}


def record(event):
    if event not in EVENTS:
        raise ValueError("Unknown operation category")
    COUNTERS[event] += 1


def secret(name):
    filename = os.environ.get(name + "_FILE")
    if filename:
        try:
            data = Path(filename).read_bytes()
            if not 1 <= len(data) <= 4096:
                raise ValueError()
            return data.decode("ascii").rstrip("\n")
        except (OSError, UnicodeError, ValueError):
            raise ValueError("Invalid service secret file") from None
    return os.environ.get(name, "")


def validate_pilot():
    if os.environ.get("APP_ENV") != "pilot":
        if os.environ.get("EAAP_DEPLOYMENT_MODE") == "pilot":
            raise ValueError("Pilot mode requires pilot settings")
        return
    try:
        if os.environ.get("EAAP_DEPLOYMENT_MODE") != "pilot":
            raise ValueError()
        revision = os.environ.get("EAAP_RELEASE_SHA", "")
        if len(revision) != 40 or any(c not in "0123456789abcdef" for c in revision):
            raise ValueError()
        values = []
        for name in ("EMBEDDING_SECRET", "EXECUTION_INTERNAL_SECRET"):
            if os.environ.get(name) or not os.environ.get(name + "_FILE"):
                raise ValueError()
            value = secret(name)
            if not 32 <= len(value) <= 128 or any(
                not 33 <= ord(c) <= 126 for c in value
            ):
                raise ValueError()
            values.append(value)
        if len(set(values)) != len(values):
            raise ValueError()
        for name in os.environ:
            if name.startswith(("LANGCHAIN_", "LANGSMITH_")) or name in {
                "PYTHONINSPECT",
                "PYTHONPATH",
                "OPENAI_BASE_URL",
                "HTTPS_PROXY",
                "HTTP_PROXY",
            }:
                raise ValueError()
        from agent_runtime.execution.provider import MODEL

        mode = os.environ.get("EAAP_PROVIDER_MODE")
        if mode == "disabled":
            if secret("OPENAI_API_KEY") or os.environ.get("EXECUTION_MODEL"):
                raise ValueError()
        elif mode == "hosted":
            key = secret("OPENAI_API_KEY")
            if (
                not os.environ.get("OPENAI_API_KEY_FILE")
                or os.environ.get("OPENAI_API_KEY")
                or len(key) < 32
                or not key.startswith("sk-")
                or any(
                    s in key.lower()
                    for s in ("placeholder", "test-only", "example", "changeme")
                )
                or os.environ.get("EXECUTION_MODEL") != MODEL
            ):
                raise ValueError()
        else:
            raise ValueError()
        if (
            not 1 <= int(os.environ.get("EXECUTION_MAX_OUTPUT_TOKENS", "800")) <= 800
            or not 1
            <= float(os.environ.get("EXECUTION_PROVIDER_TIMEOUT_SECONDS", "30"))
            <= 40
        ):
            raise ValueError()
        directory = Path(os.environ["EMBEDDING_MODEL_DIR"])
        manifest = json.loads((directory / "manifest.json").read_bytes())
        if manifest["modelId"] != MODEL_ID:
            raise ValueError()
        for name in FILES:
            with (directory / name).open("rb") as stream:
                if (
                    hashlib.file_digest(stream, "sha256").hexdigest()
                    != manifest["sha256"][name]
                ):
                    raise ValueError()
    except (OSError, KeyError, ValueError, TypeError):
        raise ValueError("Unsafe pilot runtime configuration") from None


router = APIRouter()


@router.get("/ready")
def ready():
    # Startup verifies configuration/artifacts once. No inference in probes.
    return {"status": "ready"}


@router.get("/internal/operations")
def operations(x_execution_secret: str = Header(default="")):
    expected = secret("EXECUTION_INTERNAL_SECRET")
    if not expected or not secrets.compare_digest(
        expected.encode(), x_execution_secret.encode()
    ):
        raise HTTPException(401, "unauthorized")
    return {
        "revision": os.environ.get("EAAP_RELEASE_SHA", "development"),
        "uptimeSeconds": int(time.monotonic() - STARTED),
        "counters": {event: COUNTERS[event] for event in sorted(EVENTS)},
    }
