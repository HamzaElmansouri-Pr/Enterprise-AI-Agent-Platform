import math

import pytest
from fastapi.testclient import TestClient

from agent_runtime.embedding.model import MODEL_ID, LocalEmbeddings
from agent_runtime.main import create_app


@pytest.fixture
def app(monkeypatch):
    monkeypatch.setenv("EMBEDDING_SECRET", "x" * 40)
    return create_app()


def post(client, payload):
    return client.post(
        "/internal/embeddings", json=payload, headers={"X-Embedding-Secret": "x" * 40}
    )


def test_auth_and_health(app):
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        for headers in ({}, {"X-Embedding-Secret": "wrong"}):
            assert (
                client.post(
                    "/internal/embeddings", json={}, headers=headers
                ).status_code
                == 401
            )


def test_unconfigured_secret_fails_closed(monkeypatch):
    monkeypatch.delenv("EMBEDDING_SECRET", raising=False)
    with TestClient(create_app()) as client:
        assert client.post("/internal/embeddings", json={}).status_code == 401


def test_short_secret_rejected(monkeypatch):
    monkeypatch.setenv("EMBEDDING_SECRET", "short")
    with pytest.raises(ValueError, match="EMBEDDING_SECRET"):
        create_app()


@pytest.mark.parametrize(
    "payload",
    [
        {},
        {"kind": "query", "texts": []},
        {"kind": "query", "texts": ["x"] * 9},
        {"kind": "passage", "texts": ["x" * 1201]},
        {"kind": "query", "texts": [12]},
        {"kind": "model-url", "texts": ["secret-text"]},
        {"kind": "query", "texts": ["secret-text"], "model": "other"},
    ],
)
def test_strict_batches_and_safe_errors(app, payload):
    with TestClient(app) as client:
        response = post(client, payload)
    assert response.status_code == 400
    assert "secret-text" not in response.text


def test_bounded_body(app):
    with TestClient(app) as client:
        response = client.post(
            "/internal/embeddings",
            content=b" " * 65537,
            headers={"X-Embedding-Secret": "x" * 40},
        )
    assert response.status_code == 413


def test_missing_model_has_no_fake_fallback(app):
    app.state.embeddings.adapter = LocalEmbeddings("/nonexistent-test-only-model")
    with TestClient(app) as client:
        response = post(client, {"kind": "query", "texts": ["private-text"]})
    assert response.status_code == 503
    assert response.json() == {"detail": "embedding_unavailable"}


def test_single_inference_and_contract(app):
    class TestOnlyAdapter:
        def embed(self, kind, texts):
            assert kind == "passage"
            return [[1.0] + [0.0] * 383 for _ in texts]

    app.state.embeddings.adapter = TestOnlyAdapter()
    with TestClient(app) as client:
        response = post(client, {"kind": "passage", "texts": ["fixture"]})
        assert response.status_code == 200
        assert response.json()["modelId"] == MODEL_ID
        assert response.json()["dimension"] == 384
        app.state.embeddings.lock.acquire()
        try:
            assert (
                post(client, {"kind": "passage", "texts": ["fixture"]}).status_code
                == 429
            )
        finally:
            app.state.embeddings.lock.release()


@pytest.mark.parametrize(
    "vector",
    [
        [0.0] * 384,
        [1.0] * 383,
        [math.nan] * 384,
        [math.inf] * 384,
    ],
)
def test_invalid_adapter_vectors_fail_closed(app, vector):
    class InvalidAdapter:
        def embed(self, kind, texts):
            return [vector]

    app.state.embeddings.adapter = InvalidAdapter()
    with TestClient(app) as client:
        assert post(client, {"kind": "query", "texts": ["fixture"]}).status_code == 503


def test_prefix_conventions_are_applied_before_real_model_computation():
    class CaptureModel:
        def __init__(self):
            self.values = []

        def embed(self, values, batch_size):
            import numpy as np

            self.values = values
            assert batch_size == 8
            return [np.array([1.0] + [0.0] * 383) for _ in values]

    adapter = LocalEmbeddings("/unused-test-path")
    adapter.model = CaptureModel()
    adapter.embed("passage", ["العربية français"])
    assert adapter.model.values == ["passage: العربية français"]
    adapter.embed("query", ["admission requirements"])
    assert adapter.model.values == ["query: admission requirements"]
