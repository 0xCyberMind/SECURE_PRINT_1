from fastapi.testclient import TestClient


def test_api_v1_prefix_routing(client: TestClient):
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    data = response.json()
    assert data["apiVersion"] == "v1"

    # Root /health should not exist, only /healthz or /api/v1/health
    response_root_health = client.get("/health")
    assert response_root_health.status_code == 404
