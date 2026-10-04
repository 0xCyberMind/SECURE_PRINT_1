"""Tests for the laptop/public-deployment stack:
- POST /api/v1/documents/{upload_id}/chunk  (raw ciphertext upload)
- POST /api/v1/devices/authenticate         (Windows agent login)
- POST /api/v1/sessions/{session_id}/revoke (emergency revocation)
"""

import pytest


def _register(client, email: str, password: str, role: str = "USER") -> dict:
    resp = client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": password, "role": role},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


def _auth_header(tokens: dict) -> dict:
    return {"Authorization": f"Bearer {tokens['access_token']}"}


def _create_shop(client, tokens: dict) -> str:
    resp = client.post(
        "/api/v1/shops",
        json={"name": "Stack Test Xerox", "address": "12 Test Lane"},
        headers=_auth_header(tokens),
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


# ---------------------------------------------------------------------------
# Device registration -> authentication -> authorized print-queue access
# ---------------------------------------------------------------------------

def test_device_register_and_authenticate_flow(client):
    operator = _register(client, "device_op@test.com", "operator_pass_1", role="SHOP_OPERATOR")
    shop_id = _create_shop(client, operator)

    reg = client.post(
        "/api/v1/devices/register",
        json={"shop_id": shop_id, "name": "Windows Spooler Station"},
        headers=_auth_header(operator),
    )
    assert reg.status_code == 201, reg.text
    body = reg.json()
    device_id, api_key = body["device_id"], body["api_key"]

    # Wrong API key must be rejected
    bad = client.post(
        "/api/v1/devices/authenticate",
        json={"device_id": device_id, "api_key": "ppdev_totally_wrong_key"},
    )
    assert bad.status_code == 401

    # Correct API key yields a PRINT_DEVICE token pair
    auth = client.post(
        "/api/v1/devices/authenticate",
        json={"device_id": device_id, "api_key": api_key},
    )
    assert auth.status_code == 200, auth.text
    payload = auth.json()
    assert payload["access_token"]
    assert payload["refresh_token"]
    assert payload["role"] == "PRINT_DEVICE"
    assert payload["device_id"] == device_id
    assert payload["shop_id"] == shop_id

    # The device token can read its own shop queue (empty list)
    queue = client.get(
        f"/api/v1/print/jobs?shopId={shop_id}",
        headers={"Authorization": f"Bearer {payload['access_token']}"},
    )
    assert queue.status_code == 200, queue.text
    assert queue.json() == []


def test_device_authenticate_unknown_device(client):
    resp = client.post(
        "/api/v1/devices/authenticate",
        json={"device_id": "dev_never_registered", "api_key": "ppdev_some_key_value"},
    )
    assert resp.status_code == 401


# ---------------------------------------------------------------------------
# Document chunk upload -> complete-upload -> authorized download URL
# ---------------------------------------------------------------------------

def test_document_chunk_upload_flow(client):
    operator = _register(client, "chunk_op@test.com", "operator_pass_2", role="SHOP_OPERATOR")
    shop_id = _create_shop(client, operator)
    user = _register(client, "chunk_user@test.com", "user_pass_12345")

    sess = client.post(
        "/api/v1/sessions",
        json={"shop_id": shop_id},
        headers=_auth_header(user),
    )
    assert sess.status_code == 201, sess.text
    session_id = sess.json()["id"]

    ciphertext = b"PRIVPRINT-AES-256-GCM-CIPHERTEXT-BYTES-FOR-TEST"
    sha256_hex = "a" * 64
    init = client.post(
        "/api/v1/documents/init-upload",
        json={
            "session_id": session_id,
            "filename": "confidential.pdf",
            "file_size_bytes": len(ciphertext),
            "mime_type": "application/pdf",
            "sha256_hash": sha256_hex,
            "iv_hex": "0123456789abcdef01234567",
            "key_fingerprint": "KEYFP12345",
            "copies_authorized": 2,
        },
        headers=_auth_header(user),
    )
    assert init.status_code == 201, init.text
    upload = init.json()
    assert upload["upload_id"]
    assert upload["document_id"]

    # A different user must not be able to push chunks into this upload
    intruder = _register(client, "chunk_intruder@test.com", "intruder_pass_1")
    stolen = client.post(
        f"/api/v1/documents/{upload['upload_id']}/chunk",
        content=b"malicious bytes",
        headers=_auth_header(intruder),
    )
    assert stolen.status_code in (401, 403)

    # The owner uploads the raw ciphertext body
    chunk = client.post(
        f"/api/v1/documents/{upload['upload_id']}/chunk",
        content=ciphertext,
        headers={**_auth_header(user), "Content-Type": "application/octet-stream"},
    )
    assert chunk.status_code == 200, chunk.text
    assert chunk.json()["receivedBytes"] == len(ciphertext)

    # Empty payload is rejected
    # (fresh upload to avoid the finalized-state check)
    sess2 = client.post(
        "/api/v1/sessions",
        json={"shop_id": shop_id},
        headers=_auth_header(user),
    )
    init2 = client.post(
        "/api/v1/documents/init-upload",
        json={
            "session_id": sess2.json()["id"],
            "filename": "empty.pdf",
            "file_size_bytes": 10,
            "mime_type": "application/pdf",
            "sha256_hash": sha256_hex,
            "iv_hex": "0123456789abcdef01234567",
            "key_fingerprint": "KEYFP12345",
        },
        headers=_auth_header(user),
    )
    empty = client.post(
        f"/api/v1/documents/{init2.json()['upload_id']}/chunk",
        content=b"",
        headers=_auth_header(user),
    )
    assert empty.status_code == 400

    # Complete the first upload: storage object must already exist (chunk wrote it)
    done = client.post(
        f"/api/v1/documents/{upload['upload_id']}/complete-upload",
        json={
            "document_id": upload["document_id"],
            "session_id": session_id,
            "sha256_hash": sha256_hex,
            "file_size_bytes": len(ciphertext),
        },
        headers=_auth_header(user),
    )
    assert done.status_code == 200, done.text
    assert done.json()["id"] == upload["document_id"]

    # Owner can fetch a presigned download URL
    doc = client.get(
        f"/api/v1/documents/{upload['document_id']}",
        headers=_auth_header(user),
    )
    assert doc.status_code == 200
    assert doc.json()["download_url"]


# ---------------------------------------------------------------------------
# Session emergency revocation
# ---------------------------------------------------------------------------

def test_session_revoke_endpoint(client):
    operator = _register(client, "revoke_op@test.com", "operator_pass_3", role="SHOP_OPERATOR")
    shop_id = _create_shop(client, operator)
    user = _register(client, "revoke_user@test.com", "user_pass_67890")

    sess = client.post(
        "/api/v1/sessions",
        json={"shop_id": shop_id},
        headers=_auth_header(user),
    )
    assert sess.status_code == 201, sess.text
    session_id = sess.json()["id"]

    # Another user cannot revoke someone else's session
    other = _register(client, "revoke_other@test.com", "other_pass_12345")
    forbidden = client.post(
        f"/api/v1/sessions/{session_id}/revoke",
        json={"reason": "not mine"},
        headers=_auth_header(other),
    )
    assert forbidden.status_code == 403

    # Owner revokes successfully
    revoke = client.post(
        f"/api/v1/sessions/{session_id}/revoke",
        json={"reason": "Emergency revoke from Privacy Center"},
        headers=_auth_header(user),
    )
    assert revoke.status_code == 200, revoke.text
    assert revoke.json()["success"] is True
    assert revoke.json()["status"] == "REVOKED"
