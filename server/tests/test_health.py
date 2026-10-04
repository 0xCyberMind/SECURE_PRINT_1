from fastapi.testclient import TestClient


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
    assert "X-Request-ID" in response.headers


def test_v1_health_endpoint(client: TestClient):
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert data["apiVersion"] == "v1"
    assert "checks" in data
    assert "X-Request-ID" in response.headers
