import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from agent_runtime.api.health import HealthResponse
from agent_runtime.main import create_app


def test_health_schema_and_response():
    with TestClient(create_app()) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "agent-runtime"}
    assert HealthResponse.model_validate(response.json()).status == "ok"


def test_health_schema_rejects_incorrect_status():
    with pytest.raises(ValidationError):
        HealthResponse(status="up")


@pytest.mark.parametrize("path", ["/docs", "/redoc", "/openapi.json", "/unknown"])
def test_unconfigured_routes_are_not_exposed(path):
    with TestClient(create_app()) as client:
        assert client.get(path).status_code == 404


def test_production_defaults(monkeypatch):
    monkeypatch.delenv("APP_ENV", raising=False)
    app = create_app()
    assert app.state.settings.environment == "production"
    assert app.debug is False


def test_environment_configuration(monkeypatch):
    monkeypatch.setenv("APP_ENV", "development")
    assert create_app().state.settings.environment == "development"


def test_invalid_environment_fails_closed(monkeypatch):
    monkeypatch.setenv("APP_ENV", "unknown")
    with pytest.raises(ValueError, match="APP_ENV"):
        create_app()


def test_health_does_not_enable_cross_origin_access():
    with TestClient(create_app()) as client:
        response = client.get("/health", headers={"Origin": "https://example.invalid"})
    assert "access-control-allow-origin" not in response.headers
