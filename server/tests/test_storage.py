import pytest
import hashlib
from fastapi.testclient import TestClient
from app.core.config import settings
from app.services.storage import InMemoryStorageService, get_storage_service


def test_document_init_and_complete_upload_lifecycle(client: TestClient):
    # 1. Register User & Operator
    user = client.post("/api/v1/auth/register", json={
        "email": "uploader_user@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()
    user_token = user["access_token"]
    user_id = user["user"]["id"]

    op = client.post("/api/v1/auth/register", json={
        "email": "doc_shop_op@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()

    # Create Shop & Ephemeral Session
    shop = client.post(
        "/api/v1/shops",
        json={"name": "Storage Test Shop", "address": "123 Storage Road"},
        headers={"Authorization": f"Bearer {op['access_token']}"}
    ).json()

    session = client.post(
        "/api/v1/sessions",
        json={"shop_id": shop["id"]},
        headers={"Authorization": f"Bearer {user_token}"}
    ).json()
    session_id = session["id"]

    # 2. Test Init Upload with Valid Encrypted Payload
    payload_bytes = b"PRIVPRINT-AES-256-GCM-ENCRYPTED-DATA-BLOCK"
    sha256_hash = hashlib.sha256(payload_bytes).hexdigest()

    init_payload = {
        "session_id": session_id,
        "filename": "confidential_tax_records.pdf.enc",
        "file_size_bytes": len(payload_bytes),
        "mime_type": "application/pdf",
        "sha256_hash": sha256_hash,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_client_key_9988",
        "copies_authorized": 2
    }

    init_res = client.post(
        "/api/v1/documents/init-upload",
        json=init_payload,
        headers={"Authorization": f"Bearer {user_token}"}
    )
    assert init_res.status_code == 201, init_res.text
    init_data = init_res.json()

    upload_id = init_data["upload_id"]
    document_id = init_data["document_id"]
    storage_path = init_data["storage_path"]
    presigned_upload_url = init_data["presigned_upload_url"]

    # Verify no public URL
    assert "public" not in presigned_upload_url.lower()
    assert init_data["expires_in_seconds"] == 300
    assert user_id in storage_path

    # Simulate client uploading ciphertext to S3 presigned URL
    storage = get_storage_service()
    storage.put_object_data(storage_path, payload_bytes, content_type="application/pdf")
    assert storage.object_exists(storage_path)

    # 3. Complete Upload
    complete_payload = {
        "document_id": document_id,
        "session_id": session_id,
        "sha256_hash": sha256_hash,
        "file_size_bytes": len(payload_bytes)
    }
    comp_res = client.post(
        f"/api/v1/documents/{upload_id}/complete-upload",
        json=complete_payload,
        headers={"Authorization": f"Bearer {user_token}"}
    )
    assert comp_res.status_code == 200, comp_res.text
    doc_data = comp_res.json()

    assert doc_data["id"] == document_id
    assert doc_data["user_id"] == user_id
    assert doc_data["session_id"] == session_id
    assert doc_data["sha256_hash"] == sha256_hash
    assert doc_data["file_size_bytes"] == len(payload_bytes)
    assert doc_data["encryption_algorithm"] == "AES-256-GCM"

    # 4. Get Document with short-lived access authorization
    get_res = client.get(
        f"/api/v1/documents/{document_id}",
        headers={"Authorization": f"Bearer {user_token}"}
    )
    assert get_res.status_code == 200
    doc_get = get_res.json()
    assert doc_get["id"] == document_id
    assert "download_url" in doc_get
    assert doc_get["download_expires_in"] == 300
    # No public download URL
    assert "public" not in doc_get["download_url"].lower()


def test_document_ownership_enforcement(client: TestClient):
    # User A & User B
    user_a = client.post("/api/v1/auth/register", json={
        "email": "user_a_doc@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()

    user_b = client.post("/api/v1/auth/register", json={
        "email": "user_b_doc@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()

    # Setup Shop & User A Session
    op = client.post("/api/v1/auth/register", json={
        "email": "doc_owner_op@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    shop = client.post(
        "/api/v1/shops",
        json={"name": "Owner Shop", "address": "123 High St"},
        headers={"Authorization": f"Bearer {op['access_token']}"}
    ).json()

    sess_a = client.post(
        "/api/v1/sessions",
        json={"shop_id": shop["id"]},
        headers={"Authorization": f"Bearer {user_a['access_token']}"}
    ).json()

    # User B attempts to upload into User A's session -> 403 FORBIDDEN
    bad_init = client.post(
        "/api/v1/documents/init-upload",
        json={
            "session_id": sess_a["id"],
            "filename": "hacked.pdf.enc",
            "file_size_bytes": 1000,
            "mime_type": "application/pdf",
            "sha256_hash": "a" * 64,
            "iv_hex": "0123456789abcdef",
            "key_fingerprint": "fp_bad_fingerprint",
            "copies_authorized": 1
        },
        headers={"Authorization": f"Bearer {user_b['access_token']}"}
    )
    assert bad_init.status_code == 403
    assert bad_init.json()["error"]["code"] == "FORBIDDEN"

    # User A uploads their document
    good_init = client.post(
        "/api/v1/documents/init-upload",
        json={
            "session_id": sess_a["id"],
            "filename": "legit.pdf.enc",
            "file_size_bytes": 1000,
            "mime_type": "application/pdf",
            "sha256_hash": "b" * 64,
            "iv_hex": "0123456789abcdef",
            "key_fingerprint": "fp_good_fingerprint",
            "copies_authorized": 1
        },
        headers={"Authorization": f"Bearer {user_a['access_token']}"}
    ).json()

    doc_a_id = good_init["document_id"]
    client.post(
        f"/api/v1/documents/{good_init['upload_id']}/complete-upload",
        json={"document_id": doc_a_id, "session_id": sess_a["id"]},
        headers={"Authorization": f"Bearer {user_a['access_token']}"}
    )

    # User B attempts to GET User A's document -> 403 FORBIDDEN
    bad_get = client.get(
        f"/api/v1/documents/{doc_a_id}",
        headers={"Authorization": f"Bearer {user_b['access_token']}"}
    )
    assert bad_get.status_code == 403
    assert bad_get.json()["error"]["code"] == "FORBIDDEN"


def test_file_size_limits_and_type_validation(client: TestClient):
    user = client.post("/api/v1/auth/register", json={
        "email": "limits_user@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()

    op = client.post("/api/v1/auth/register", json={
        "email": "limits_op@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    shop = client.post(
        "/api/v1/shops",
        json={"name": "Limits Shop", "address": "123 Limit St"},
        headers={"Authorization": f"Bearer {op['access_token']}"}
    ).json()

    session = client.post(
        "/api/v1/sessions",
        json={"shop_id": shop["id"]},
        headers={"Authorization": f"Bearer {user['access_token']}"}
    ).json()

    # 1. File size exceeds 25 MB limit
    oversized_res = client.post(
        "/api/v1/documents/init-upload",
        json={
            "session_id": session["id"],
            "filename": "huge_movie.pdf.enc",
            "file_size_bytes": 30 * 1024 * 1024,  # 30 MB > 25 MB limit
            "mime_type": "application/pdf",
            "sha256_hash": "c" * 64,
            "iv_hex": "0123456789abcdef",
            "key_fingerprint": "fp_huge_fingerprint"
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert oversized_res.status_code == 400
    assert oversized_res.json()["error"]["code"] == "VALIDATION_ERROR"

    # 2. File size <= 0
    zero_size_res = client.post(
        "/api/v1/documents/init-upload",
        json={
            "session_id": session["id"],
            "filename": "empty.pdf.enc",
            "file_size_bytes": 0,
            "mime_type": "application/pdf",
            "sha256_hash": "d" * 64,
            "iv_hex": "0123456789abcdef",
            "key_fingerprint": "fp_zero_fingerprint"
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert zero_size_res.status_code == 422 or zero_size_res.status_code == 400

    # 3. Disallowed extension (.exe / .sh)
    disallowed_res = client.post(
        "/api/v1/documents/init-upload",
        json={
            "session_id": session["id"],
            "filename": "malware.exe",
            "file_size_bytes": 1024,
            "mime_type": "application/octet-stream",
            "sha256_hash": "e" * 64,
            "iv_hex": "0123456789abcdef",
            "key_fingerprint": "fp_malware_file"
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert disallowed_res.status_code == 400
    assert disallowed_res.json()["error"]["code"] == "VALIDATION_ERROR"

    # 4. Invalid checksum format
    bad_hash_res = client.post(
        "/api/v1/documents/init-upload",
        json={
            "session_id": session["id"],
            "filename": "valid.pdf.enc",
            "file_size_bytes": 1024,
            "mime_type": "application/pdf",
            "sha256_hash": "not_a_valid_sha256_hash",
            "iv_hex": "0123456789abcdef",
            "key_fingerprint": "fp_hash_fingerprint"
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert bad_hash_res.status_code == 422 or bad_hash_res.status_code == 400
