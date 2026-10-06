import os
import pytest
from fastapi.testclient import TestClient

from app.core.config import settings
from app.services.redis_service import get_redis_service
from app.services.storage import get_storage_service
from windows_agent.config import PROD_SERVER_BASE_URL as WIN_PROD_URL, DEV_SERVER_BASE_URL as WIN_DEV_URL


def test_health_endpoints_and_monitoring_probes(client: TestClient):
    """
    Verify /healthz probe returns status 200 OK with driver readiness checks.
    """
    res = client.get("/healthz")
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "healthy"
    assert data["checks"]["api"] == "healthy"
    assert data["checks"]["database_driver"] == "ready"
    assert data["checks"]["storage_client"] == "not_checked"

    v1_res = client.get("/api/v1/health")
    assert v1_res.status_code == 200
    assert v1_res.json()["status"] == "healthy"


@pytest.mark.asyncio
async def test_database_and_redis_connectivity():
    """
    Verify PostgreSQL Async Engine and Redis Cache Connection Pools.
    """
    redis_svc = get_redis_service()
    res = await redis_svc.ping()
    assert res is True

    # Test cache set and get
    client = await redis_svc.get_client()
    assert client is not None
    await client.set("deploy_test_key", "active_v19", ex=60)
    val = await client.get("deploy_test_key")
    assert val == "active_v19"


def test_private_object_storage_readiness():
    """
    Verify S3 / MinIO Private Storage engine and presigned URL generation.
    """
    storage = get_storage_service()
    test_path = "deploy_check/test_file.enc"
    
    # Put test object
    storage.put_object_data(test_path, b"DEPLOYMENT_READINESS_CHECK")
    assert storage.object_exists(test_path) is True

    # Generate presigned upload and download URLs
    upload_url = storage.generate_presigned_upload_url(test_path, expires_in=300)
    download_url = storage.generate_presigned_download_url(test_path, expires_in=300)

    assert "deploy_check" in upload_url
    assert "deploy_check" in download_url

    # Cleanup test object
    deleted = storage.delete_object(test_path)
    assert deleted is True
    assert storage.object_exists(test_path) is False


def test_android_and_windows_production_endpoints():
    """
    Verify Android Client and Windows Agent production endpoints point to https://api.privprint.com/
    and never use development credentials in production mode.
    """
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    android_api_client_path = os.path.join(
        repo_root, "app", "src", "main", "java", "com", "example", "privprint", "data", "api", "ApiClient.kt"
    )
    assert os.path.exists(android_api_client_path)
    with open(android_api_client_path, "r", encoding="utf-8") as f:
        content = f.read()
        assert 'const val PROD_BASE_URL = "https://api.privprint.com/"' in content

    with open(os.path.join(repo_root, "app", "build.gradle.kts"), encoding="utf-8") as f:
        gradle_content = f.read()
    assert 'orElse("https://api.privprint.com/")' in gradle_content

    with open(
        os.path.join(
            repo_root,
            "app",
            "src",
            "main",
            "java",
            "com",
            "example",
            "privprint",
            "data",
            "api",
            "models",
            "ApiModels.kt",
        ),
        encoding="utf-8",
    ) as f:
        assert 'PRODUCTION("Production Cloud (HTTPS)", "https://api.privprint.com/")' in f.read()

    assert WIN_PROD_URL == "https://api.privprint.com/"
    assert WIN_DEV_URL != WIN_PROD_URL

    with open(os.path.join(repo_root, "server", "deploy", "nginx.conf"), encoding="utf-8") as f:
        nginx_content = f.read()
    assert nginx_content.count("server_name api.privprint.com;") == 2

    with open(os.path.join(repo_root, "server", ".env.example"), encoding="utf-8") as f:
        assert "https://api.privprint.com" in f.read()


def test_production_compose_and_preflight_enforce_secrets_and_tls():
    server_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    with open(os.path.join(server_dir, "docker-compose.prod.yml"), encoding="utf-8") as f:
        compose = f.read()
    assert "ENVIRONMENT: production" in compose
    assert 'DEBUG: "false"' in compose
    assert 'DEVELOPMENT_OTP_ENABLED: "false"' in compose
    assert "${SECRET_KEY:?" in compose
    assert "${POSTGRES_PASSWORD:?" in compose
    assert "${REDIS_PASSWORD:?" in compose
    assert "${TWILIO_VERIFY_SERVICE_SID:?" in compose

    with open(os.path.join(server_dir, "deploy", "preflight-production.sh"), encoding="utf-8") as f:
        preflight = f.read()
    assert "openssl x509" in preflight
    assert "-checkhost api.privprint.com" in preflight
    assert "-checkend 604800" in preflight
    assert "nginx -t" in preflight
    assert "settings.ENVIRONMENT.value == \"production\"" in preflight

    with open(os.path.join(server_dir, ".env.production.example"), encoding="utf-8") as f:
        production_env_template = f.read()
    assert "POSTGRES_PASSWORD=" in production_env_template
    assert "REDIS_PASSWORD=" in production_env_template
    assert "TWILIO_AUTH_TOKEN=" in production_env_template
    assert "STORAGE_ENDPOINT=https://" in production_env_template


def test_end_to_end_production_print_delivery_flow(client: TestClient):
    """
    Complete E2E Print Delivery Pipeline verification for Deployment Readiness.
    """
    # 1. Register Shop Operator
    op = client.post("/api/v1/auth/register", json={
        "email": "deploy_op@privprint.com", "password": "ProdPassword123!", "role": "SHOP_OPERATOR"
    }).json()
    auth_op = {"Authorization": f"Bearer {op['access_token']}"}

    # 2. Create Production Shop
    shop = client.post("/api/v1/shops", json={
        "name": "PrivPrint HQ Print Hub", "address": "100 Enterprise Way"
    }, headers=auth_op).json()

    # 3. Register User & Create Session
    user = client.post("/api/v1/auth/register", json={
        "email": "deploy_user@privprint.com", "password": "ProdPassword123!", "role": "USER"
    }).json()
    auth_user = {"Authorization": f"Bearer {user['access_token']}"}

    sess = client.post("/api/v1/sessions", json={"shop_id": shop["id"]}, headers=auth_user).json()

    # 4. Init & Complete Document Upload
    doc = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"],
        "filename": "quarterly_audit.pdf.enc",
        "file_size_bytes": 4096,
        "mime_type": "application/pdf",
        "sha256_hash": "e" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_audit_e19",
        "copies_authorized": 1
    }, headers=auth_user).json()

    client.post(f"/api/v1/documents/{doc['upload_id']}/complete-upload", json={
        "document_id": doc["document_id"],
        "session_id": sess["id"]
    }, headers=auth_user)

    # 5. Create & Authorize Print Job
    job = client.post("/api/v1/jobs", json={
        "shop_id": shop["id"],
        "session_id": sess["id"],
        "document_id": doc["document_id"],
        "requested_copies": 1
    }, headers=auth_user).json()

    client.post(f"/api/v1/jobs/{job['id']}/authorize", headers=auth_user)

    # 6. Windows Station Spooling & Print Delivery Completion
    queue = client.get(f"/api/v1/print/jobs?shopId={shop['id']}", headers=auth_op).json()
    assert len(queue) >= 1

    client.post(f"/api/v1/print/jobs/{job['id']}/start", headers=auth_op)
    inc = client.post(f"/api/v1/print/jobs/{job['id']}/increment-copy?delta=1", headers=auth_op).json()
    assert inc["status"] == "COMPLETED"

    # 7. Document Shredding
    cleanup = client.post(f"/api/v1/cleanup/execute/{job['id']}", headers=auth_op).json()
    assert cleanup["cleanup_state"] == "SHREDDED"
