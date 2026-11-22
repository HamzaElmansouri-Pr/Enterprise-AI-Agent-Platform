import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from agent_runtime import operations
from agent_runtime.main import create_app


def environment(monkeypatch, tmp_path):
    monkeypatch.setenv("APP_ENV", "pilot")
    monkeypatch.setenv("EAAP_DEPLOYMENT_MODE", "pilot")
    monkeypatch.setenv("EAAP_RELEASE_SHA", "a" * 40)
    monkeypatch.setenv("EAAP_PROVIDER_MODE", "disabled")
    for name, value in (
        ("EMBEDDING_SECRET", "e" * 40),
        ("EXECUTION_INTERNAL_SECRET", "x" * 40),
    ):
        monkeypatch.delenv(name, raising=False)
        path = tmp_path / name
        path.write_text(value)
        monkeypatch.setenv(name + "_FILE", str(path))
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.delenv("EXECUTION_MODEL", raising=False)
    monkeypatch.setenv("EMBEDDING_MODEL_DIR", str(tmp_path))
    # Unit fixtures validate the manifest path without claiming real embedding quality.
    import hashlib

    hashes = {}
    for name in operations.FILES:
        (tmp_path / name).write_bytes(b"synthetic artifact")
        hashes[name] = hashlib.sha256(b"synthetic artifact").hexdigest()
    (tmp_path / "manifest.json").write_text(
        json.dumps({"modelId": operations.MODEL_ID, "sha256": hashes})
    )


def test_pilot_files_and_private_diagnostics(monkeypatch, tmp_path):
    environment(monkeypatch, tmp_path)
    with TestClient(create_app()) as client:
        assert client.get("/ready").status_code == 200
        assert client.get("/internal/operations").status_code == 401
        assert (
            client.get(
                "/internal/operations", headers={"X-Execution-Secret": "incorrect"}
            ).status_code
            == 401
        )
        response = client.get(
            "/internal/operations", headers={"X-Execution-Secret": "x" * 40}
        )
        assert response.status_code == 200
        assert response.json()["revision"] == "a" * 40
        assert "x" * 40 not in response.text


@pytest.mark.parametrize(
    "name,value",
    [
        ("EAAP_DEPLOYMENT_MODE", "development"),
        ("EAAP_PROVIDER_MODE", "unknown"),
        ("EXECUTION_MODEL", "unapproved"),
        ("EXECUTION_MAX_OUTPUT_TOKENS", "801"),
        ("PYTHONPATH", "/untrusted"),
        ("OPENAI_API_KEY", "test-only-bait"),
    ],
)
def test_unsafe_pilot_rejected_without_private_values(
    monkeypatch, tmp_path, name, value
):
    environment(monkeypatch, tmp_path)
    monkeypatch.setenv(name, value)
    with pytest.raises(ValueError, match="Unsafe pilot runtime configuration") as error:
        create_app()
    assert value not in str(error.value)


def test_missing_secret_rejected(monkeypatch, tmp_path):
    environment(monkeypatch, tmp_path)
    Path(tmp_path / "EMBEDDING_SECRET").unlink()
    with pytest.raises(ValueError, match="Unsafe pilot runtime configuration"):
        create_app()


def test_bad_manifest_and_weak_or_duplicate_secrets(monkeypatch, tmp_path):
    environment(monkeypatch, tmp_path)
    for value in ("weak", "x" * 40):
        (tmp_path / "EMBEDDING_SECRET").write_text(value)
        with pytest.raises(ValueError):
            create_app()
    (tmp_path / "EMBEDDING_SECRET").write_text("e" * 40)
    (tmp_path / "model.onnx").write_bytes(b"tampered")
    with pytest.raises(ValueError):
        create_app()


def test_placeholder_hosted_key_rejected(monkeypatch, tmp_path):
    environment(monkeypatch, tmp_path)
    key = tmp_path / "provider-key"
    key.write_text("sk-placeholder-" + "z" * 40)
    monkeypatch.setenv("OPENAI_API_KEY_FILE", str(key))
    monkeypatch.setenv("EXECUTION_MODEL", "gpt-5.4-mini-2026-03-17")
    monkeypatch.setenv("EAAP_PROVIDER_MODE", "hosted")
    with pytest.raises(ValueError, match="Unsafe pilot runtime configuration"):
        create_app()


def test_secret_file_errors_do_not_echo(monkeypatch, tmp_path):
    monkeypatch.setenv("SYNTHETIC_FILE", str(tmp_path / "missing"))
    with pytest.raises(ValueError, match="Invalid service secret file"):
        operations.secret("SYNTHETIC")


def test_diagnostics_not_added_to_development(monkeypatch):
    monkeypatch.setenv("APP_ENV", "test")
    with TestClient(create_app()) as client:
        assert client.get("/ready").status_code == 404
        assert client.get("/internal/operations").status_code == 404
