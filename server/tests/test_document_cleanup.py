import pytest
from datetime import datetime, timezone, timedelta
from fastapi.testclient import TestClient

from app.models.enums import CleanupState
from app.services.storage import get_storage_service


@pytest.fixture
def test_setup(client: TestClient):
    # 1. Register Shop Operator & create shop
    op_res = client.post("/api/v1/auth/register", json={
        "email": "cleanup_op@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    op_token = op_res["access_token"]
    shop = client.post("/api/v1/shops", json={
        "name": "Cleanup Test Shop",
        "address": "500 Storage Way"
    }, headers={"Authorization": f"Bearer {op_token}"}).json()

    # 2. Register standard user
    user_res = client.post("/api/v1/auth/register", json={
        "email": "cleanup_user@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()
    user_token = user_res["access_token"]

    return {
        "op_token": op_token,
        "shop_id": shop["id"],
        "user_token": user_token,
        "user_id": user_res["user"]["id"]
    }


def test_cleanup_retention_preserved_while_job_is_active(client: TestClient, test_setup):
    """
    Test 1: Document retention is preserved while print job is in progress (non-terminal state).
    Storage object must NOT be deleted while job is QUEUED or PRINTING.
    """
    u_auth = {"Authorization": f"Bearer {test_setup['user_token']}"}
    op_auth = {"Authorization": f"Bearer {test_setup['op_token']}"}

    # Create session & upload doc
    sess = client.post("/api/v1/sessions", json={"shop_id": test_setup["shop_id"]}, headers=u_auth).json()
    doc = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"],
        "filename": "active_retention.pdf.enc",
        "file_size_bytes": 1024,
        "mime_type": "application/pdf",
        "sha256_hash": "e" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_retention_01",
        "copies_authorized": 1
    }, headers=u_auth).json()
    doc_id = doc["document_id"]
    storage_path = doc["storage_path"]
    client.post(f"/api/v1/documents/{doc['upload_id']}/complete-upload", json={
        "document_id": doc_id,
        "session_id": sess["id"]
    }, headers=u_auth)

    # Put mock data into storage
    storage = get_storage_service()
    storage.put_object_data(storage_path, b"ENCRYPTED_PAYLOAD_CIPHERTEXT")
    assert storage.object_exists(storage_path) is True

    # Create job (state = CREATED / QUEUED)
    job = client.post("/api/v1/jobs", json={
        "shop_id": test_setup["shop_id"],
        "session_id": sess["id"],
        "document_id": doc_id,
        "requested_copies": 1
    }, headers=u_auth).json()
    job_id = job["id"]

    # Attempt cleanup while job is non-terminal
    cleanup_res = client.post(f"/api/v1/cleanup/execute/{job_id}", headers=op_auth)
    assert cleanup_res.status_code == 200
    data = cleanup_res.json()

    # Retention must be required, storage object preserved
    assert data["retention_required"] is True
    assert data["storage_verified_deleted"] is False
    assert storage.object_exists(storage_path) is True


def test_cleanup_shredded_and_verified_after_job_completion(client: TestClient, test_setup):
    """
    Test 2: After job completion and copy consumption, document is deleted from storage,
    verified deleted, and marked SHREDDED with audit log entry.
    """
    u_auth = {"Authorization": f"Bearer {test_setup['user_token']}"}
    op_auth = {"Authorization": f"Bearer {test_setup['op_token']}"}

    # Create session & upload doc
    sess = client.post("/api/v1/sessions", json={"shop_id": test_setup["shop_id"]}, headers=u_auth).json()
    doc = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"],
        "filename": "complete_shred.pdf.enc",
        "file_size_bytes": 1024,
        "mime_type": "application/pdf",
        "sha256_hash": "f" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_shred_02",
        "copies_authorized": 1
    }, headers=u_auth).json()
    doc_id = doc["document_id"]
    storage_path = doc["storage_path"]
    client.post(f"/api/v1/documents/{doc['upload_id']}/complete-upload", json={
        "document_id": doc_id,
        "session_id": sess["id"]
    }, headers=u_auth)

    # Put mock data into storage
    storage = get_storage_service()
    storage.put_object_data(storage_path, b"ENCRYPTED_PAYLOAD_CIPHERTEXT_TO_SHRED")
    assert storage.object_exists(storage_path) is True

    # Create & authorize job
    job = client.post("/api/v1/jobs", json={
        "shop_id": test_setup["shop_id"],
        "session_id": sess["id"],
        "document_id": doc_id,
        "requested_copies": 1
    }, headers=u_auth).json()
    job_id = job["id"]
    client.post(f"/api/v1/jobs/{job_id}/authorize", headers=u_auth)

    # Start and increment copy to completion
    client.post(f"/api/v1/print/jobs/{job_id}/start", headers=op_auth)
    inc_res = client.post(f"/api/v1/print/jobs/{job_id}/increment-copy?delta=1", headers=op_auth)
    assert inc_res.status_code == 200
    assert inc_res.json()["status"] == "COMPLETED"

    # Execute cleanup
    cleanup_res = client.post(f"/api/v1/cleanup/execute/{job_id}", headers=op_auth)
    assert cleanup_res.status_code == 200
    data = cleanup_res.json()

    assert data["cleanup_state"] == "SHREDDED"
    assert data["storage_verified_deleted"] is True
    assert data["retention_required"] is False
    assert data["guarantee_level"] == "OBJECT_DELETION_VERIFIED"

    # Verify object no longer exists in storage
    assert storage.object_exists(storage_path) is False

    # Check document cleanup status endpoint
    status_res = client.get(f"/api/v1/cleanup/status/{doc_id}", headers=u_auth)
    assert status_res.status_code == 200
    status_data = status_res.json()
    assert status_data["cleanup_state"] == "SHREDDED"
    assert status_data["is_deleted"] is True
    assert status_data["storage_exists"] is False


def test_cleanup_failed_storage_deletion_handling(client: TestClient, test_setup, monkeypatch):
    """
    Test 3: If storage provider fails to delete the object or verification fails,
    cleanup is NOT marked successful / SHREDDED.
    """
    u_auth = {"Authorization": f"Bearer {test_setup['user_token']}"}
    op_auth = {"Authorization": f"Bearer {test_setup['op_token']}"}

    sess = client.post("/api/v1/sessions", json={"shop_id": test_setup["shop_id"]}, headers=u_auth).json()
    doc = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"],
        "filename": "failing_storage.pdf.enc",
        "file_size_bytes": 1024,
        "mime_type": "application/pdf",
        "sha256_hash": "1" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_fail_03",
        "copies_authorized": 1
    }, headers=u_auth).json()
    doc_id = doc["document_id"]
    storage_path = doc["storage_path"]
    client.post(f"/api/v1/documents/{doc['upload_id']}/complete-upload", json={
        "document_id": doc_id,
        "session_id": sess["id"]
    }, headers=u_auth)

    storage = get_storage_service()
    storage.put_object_data(storage_path, b"CIPHERTEXT_FAIL")

    job = client.post("/api/v1/jobs", json={
        "shop_id": test_setup["shop_id"],
        "session_id": sess["id"],
        "document_id": doc_id,
        "requested_copies": 1
    }, headers=u_auth).json()
    job_id = job["id"]
    client.post(f"/api/v1/jobs/{job_id}/authorize", headers=u_auth)
    client.post(f"/api/v1/print/jobs/{job_id}/start", headers=op_auth)
    client.post(f"/api/v1/print/jobs/{job_id}/increment-copy?delta=1", headers=op_auth)

    # Mock storage.delete_object to fail
    def failing_delete(path):
        return False

    monkeypatch.setattr(storage, "delete_object", failing_delete)

    # Execute cleanup
    cleanup_res = client.post(f"/api/v1/cleanup/execute/{job_id}", headers=op_auth)
    assert cleanup_res.status_code == 200
    data = cleanup_res.json()

    # Must NOT claim SHREDDED or verified deleted
    assert data["cleanup_state"] != "SHREDDED"
    assert data["storage_verified_deleted"] is False


def test_cleanup_authorization_isolation(client: TestClient, test_setup):
    """
    Test 4: User A cannot trigger cleanup on User B's job or document.
    """
    u_auth = {"Authorization": f"Bearer {test_setup['user_token']}"}

    # Register User B
    u2_res = client.post("/api/v1/auth/register", json={
        "email": "user_b_cleanup@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()
    u2_auth = {"Authorization": f"Bearer {u2_res['access_token']}"}

    sess = client.post("/api/v1/sessions", json={"shop_id": test_setup["shop_id"]}, headers=u_auth).json()
    doc = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"],
        "filename": "user_a_doc.pdf.enc",
        "file_size_bytes": 1024,
        "mime_type": "application/pdf",
        "sha256_hash": "2" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_isolation_04",
        "copies_authorized": 1
    }, headers=u_auth).json()
    client.post(f"/api/v1/documents/{doc['upload_id']}/complete-upload", json={
        "document_id": doc["document_id"],
        "session_id": sess["id"]
    }, headers=u_auth)

    # User B tries to delete User A's document -> 403 Forbidden
    cross_del = client.post(f"/api/v1/cleanup/document/{doc['document_id']}", headers=u2_auth)
    assert cross_del.status_code == 403

    # User B tries to check status -> 403 Forbidden
    cross_status = client.get(f"/api/v1/cleanup/status/{doc['document_id']}", headers=u2_auth)
    assert cross_status.status_code == 403


def test_cleanup_background_sweep(client: TestClient, test_setup):
    """
    Test 5: Background sweep scans pending and expired documents, deletes eligible storage objects.
    """
    op_auth = {"Authorization": f"Bearer {test_setup['op_token']}"}
    u_auth = {"Authorization": f"Bearer {test_setup['user_token']}"}

    sess = client.post("/api/v1/sessions", json={"shop_id": test_setup["shop_id"]}, headers=u_auth).json()
    doc = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"],
        "filename": "sweep_doc.pdf.enc",
        "file_size_bytes": 1024,
        "mime_type": "application/pdf",
        "sha256_hash": "3" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_sweep_05",
        "copies_authorized": 1
    }, headers=u_auth).json()
    doc_id = doc["document_id"]
    storage_path = doc["storage_path"]
    client.post(f"/api/v1/documents/{doc['upload_id']}/complete-upload", json={
        "document_id": doc_id,
        "session_id": sess["id"]
    }, headers=u_auth)

    storage = get_storage_service()
    storage.put_object_data(storage_path, b"SWEEP_CIPHERTEXT")

    # Complete job so doc is eligible for cleanup
    job = client.post("/api/v1/jobs", json={
        "shop_id": test_setup["shop_id"],
        "session_id": sess["id"],
        "document_id": doc_id,
        "requested_copies": 1
    }, headers=u_auth).json()
    job_id = job["id"]
    client.post(f"/api/v1/jobs/{job_id}/authorize", headers=u_auth)
    client.post(f"/api/v1/print/jobs/{job_id}/start", headers=op_auth)
    client.post(f"/api/v1/print/jobs/{job_id}/increment-copy?delta=1", headers=op_auth)

    # Trigger sweep
    sweep_res = client.post("/api/v1/cleanup/sweep", headers=op_auth)
    assert sweep_res.status_code == 200
    sweep_data = sweep_res.json()
    assert sweep_data["scanned_documents"] >= 1
    assert sweep_data["shredded_documents"] >= 1

    # Storage object was removed and verified
    assert storage.object_exists(storage_path) is False
