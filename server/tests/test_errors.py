from fastapi.testclient import TestClient


def test_404_not_found_standard_error_format(client: TestClient):
    response = client.get("/non-existent-route-for-testing")
    assert response.status_code == 404
    data = response.json()
    assert "error" in data
    assert data["error"]["code"] == "NOT_FOUND"
    assert "message" in data["error"]
    assert "request_id" in data["error"]
    assert "X-Request-ID" in response.headers


def test_request_id_propagation_and_header(client: TestClient):
    custom_req_id = "test-custom-tracing-id-12345"
    response = client.get("/healthz", headers={"X-Request-ID": custom_req_id})
    assert response.status_code == 200
    assert response.headers.get("X-Request-ID") == custom_req_id
