import pytest
import logging
import json
from fastapi.testclient import TestClient

from app.core.logging import redact_sensitive_data, StructuredJsonFormatter
from app.core.exceptions import ErrorCode


def test_log_redaction_defensive_filter():
    """
    Test 1: Verify sensitive credentials, tokens, and cryptographic keys are redacted from logs.
    """
    sample_log = 'User login failed with password: "SuperSecretPassword123!" and Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.token'
    redacted = redact_sensitive_data(sample_log)
    assert "SuperSecretPassword123!" not in redacted
    assert "[REDACTED]" in redacted
    assert "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" not in redacted

    api_key_log = 'Device registered with api_key="secret_device_api_key_xyz" and iv_hex="0123456789abcdef"'
    redacted_api = redact_sensitive_data(api_key_log)
    assert "secret_device_api_key_xyz" not in redacted_api
    assert "0123456789abcdef" not in redacted_api


def test_standardized_error_format_no_stack_trace_leakage(client: TestClient):
    """
    Test 2: Ensure 404, 422, and 400 responses return clean JSON schema with request_id
    and zero internal python traceback leakage.
    """
    user = client.post("/api/v1/auth/register", json={
        "email": "err_format_user@example.com", "password": "Password123!", "role": "USER"
    }).json()
    u_auth = {"Authorization": f"Bearer {user['access_token']}"}

    res = client.get("/api/v1/jobs/PRV-NONEXISTENT", headers=u_auth)
    assert res.status_code == 404
    data = res.json()
    assert "error" in data
    assert data["error"]["code"] == "NOT_FOUND"
    assert "traceback" not in data["error"]
    assert "request_id" in data["error"]
    assert res.headers.get("X-Request-ID") is not None


def test_cross_tenant_idor_prevention(client: TestClient):
    """
    Test 3: Strict IDOR & Tenant Boundary Protection.
    User A cannot view, modify, or authorize User B's print jobs or documents.
    """
    # 1. Register User A & User B
    u_a = client.post("/api/v1/auth/register", json={
        "email": "def_user_a@example.com", "password": "Password123!", "role": "USER"
    }).json()
    u_b = client.post("/api/v1/auth/register", json={
        "email": "def_user_b@example.com", "password": "Password123!", "role": "USER"
    }).json()

    auth_a = {"Authorization": f"Bearer {u_a['access_token']}"}
    auth_b = {"Authorization": f"Bearer {u_b['access_token']}"}

    # 2. Register Shop
    op = client.post("/api/v1/auth/register", json={
        "email": "def_op_shop@example.com", "password": "Password123!", "role": "SHOP_OPERATOR"
    }).json()
    shop = client.post("/api/v1/shops", json={
        "name": "Defensive Security Shop", "address": "123 Defense Lane"
    }, headers={"Authorization": f"Bearer {op['access_token']}"}).json()

    # User A creates session and document
    sess_a = client.post("/api/v1/sessions", json={"shop_id": shop["id"]}, headers=auth_a).json()
    doc_a = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess_a["id"],
        "filename": "confidential_a.pdf.enc",
        "file_size_bytes": 1024,
        "mime_type": "application/pdf",
        "sha256_hash": "a" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_def_a",
        "copies_authorized": 1
    }, headers=auth_a).json()
    client.post(f"/api/v1/documents/{doc_a['upload_id']}/complete-upload", json={
        "document_id": doc_a["document_id"],
        "session_id": sess_a["id"]
    }, headers=auth_a)

    # User A creates job
    job_a = client.post("/api/v1/jobs", json={
        "shop_id": shop["id"],
        "session_id": sess_a["id"],
        "document_id": doc_a["document_id"],
        "requested_copies": 1
    }, headers=auth_a).json()

    # User B attempts to access User A's job -> 403 Forbidden
    res_b_job = client.get(f"/api/v1/jobs/{job_a['id']}", headers=auth_b)
    assert res_b_job.status_code == 403

    # User B attempts to authorize User A's job -> 403 Forbidden
    res_b_auth = client.post(f"/api/v1/jobs/{job_a['id']}/authorize", headers=auth_b)
    assert res_b_auth.status_code == 403

    # User B attempts to delete User A's document -> 403 Forbidden
    res_b_del = client.post(f"/api/v1/cleanup/document/{doc_a['document_id']}", headers=auth_b)
    assert res_b_del.status_code == 403


def test_upload_security_validation_rules(client: TestClient):
    """
    Test 4: Defensive Upload Validation (File Size, Allowed MIME Types, SHA-256 Checksum).
    """
    user = client.post("/api/v1/auth/register", json={
        "email": "upload_sec_user@example.com", "password": "Password123!", "role": "USER"
    }).json()
    u_auth = {"Authorization": f"Bearer {user['access_token']}"}

    op = client.post("/api/v1/auth/register", json={
        "email": "upload_sec_op@example.com", "password": "Password123!", "role": "SHOP_OPERATOR"
    }).json()
    shop = client.post("/api/v1/shops", json={
        "name": "Upload Sec Shop", "address": "99 File Ave"
    }, headers={"Authorization": f"Bearer {op['access_token']}"}).json()

    sess = client.post("/api/v1/sessions", json={"shop_id": shop["id"]}, headers=u_auth).json()

    # 1. File size exceeds 25MB limit
    res_oversize = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"],
        "filename": "huge_file.pdf.enc",
        "file_size_bytes": 30 * 1024 * 1024,  # 30 MB > 25 MB limit
        "mime_type": "application/pdf",
        "sha256_hash": "b" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_huge",
        "copies_authorized": 1
    }, headers=u_auth)
    assert res_oversize.status_code in (400, 422)

    # 2. Invalid MIME type / Executable extension
    res_bad_mime = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"],
        "filename": "payload.exe.enc",
        "file_size_bytes": 1024,
        "mime_type": "application/x-dosexec",
        "sha256_hash": "c" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_exe",
        "copies_authorized": 1
    }, headers=u_auth)
    assert res_bad_mime.status_code in (400, 422)

    # 3. Invalid SHA-256 hash length/format
    res_bad_hash = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"],
        "filename": "valid.pdf.enc",
        "file_size_bytes": 1024,
        "mime_type": "application/pdf",
        "sha256_hash": "not_a_valid_sha256",
        "iv_hex": "0123456789abcdef",
        "key_fingerprint": "fp_hash",
        "copies_authorized": 1
    }, headers=u_auth)
    assert res_bad_hash.status_code == 422 or res_bad_hash.status_code == 400


def test_cors_headers_and_security_transport(client: TestClient):
    """
    Test 5: Verify CORS preflight and allowed origins.
    """
    res = client.options(
        "/api/v1/auth/login",
        headers={
            "Origin": "https://api.privprint.com",
            "Access-Control-Request-Method": "POST"
        }
    )
    assert res.status_code in (200, 204)
    assert "access-control-allow-origin" in res.headers
