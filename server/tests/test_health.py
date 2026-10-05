import pytest
from fastapi.testclient import TestClient
from unittest.mock import MagicMock, patch

from app.api.v1.endpoints import health as health_endpoint
from app.core.config import EnvironmentType, settings
from app.services.redis_service import RedisService


def test_root_healthz_endpoint(client: TestClient):
    response = client.get("/healthz")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert data["apiVersion"] == "v1"
    assert "environment" in data
    assert "timestamp" in data
    assert "checks" in data
    assert data["checks"]["api"] == "healthy"
    assert data["checks"]["database_driver"] == "ready"
    assert "X-Request-ID" in response.headers


def test_v1_health_endpoint(client: TestClient):
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert data["apiVersion"] == "v1"
    assert "checks" in data
    assert "X-Request-ID" in response.headers


def test_health_endpoints_report_database_failures(client: TestClient):
    class FailingConnection:
        async def __aenter__(self):
            raise RuntimeError("database unavailable")

        async def __aexit__(self, exc_type, exc, traceback):
            return False

    failing_engine = MagicMock()
    failing_engine.connect.return_value = FailingConnection()

    with patch.object(health_endpoint, "engine", failing_engine):
        for path in ("/healthz", "/api/v1/health"):
            response = client.get(path)
            assert response.status_code == 503
            assert response.json()["status"] == "unhealthy"
            assert "database" in response.json()["checks"] or (
                "database_driver" in response.json()["checks"]
            )


@pytest.mark.asyncio
async def test_production_redis_failure_does_not_use_fake_redis(monkeypatch):
    monkeypatch.setattr(settings, "ENVIRONMENT", EnvironmentType.PRODUCTION)
    monkeypatch.setattr(settings, "REDIS_ENABLED", True)
    monkeypatch.setattr(
        "app.services.redis_service.aioredis.from_url",
        MagicMock(side_effect=OSError("connection refused")),
    )

    service = RedisService()
    assert await service.get_client() is None
