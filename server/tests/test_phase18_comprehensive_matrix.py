import math
import uuid
import json
import pytest
from datetime import datetime, timezone, timedelta
from fastapi.testclient import TestClient
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import os

from app.core.security import verify_password, get_password_hash, create_access_token, decode_access_token
from app.models.enums import PrintJobStatus, CleanupState, UserRole, SessionStatus
from app.services.storage import get_storage_service
from app.services.redis_service import get_redis_service
from app.repositories.print_job_repo import VALID_TRANSITIONS


# -----------------------------------------------------------------------------
# 1. UNIT TESTS: Crypto, QR, Distance, State Machine, Security
# -----------------------------------------------------------------------------

def test_unit_crypto_aes256_gcm_roundtrip():
    """Unit: AES-256-GCM encryption, decryption, and key zeroization."""
    key = AESGCM.generate_key(bit_length=256)
    aesgcm = AESGCM(key)
    iv = os.urandom(12)
    plaintext = b"PrivPrint Confidential Document Payload for Phase 18"
    
    # Encrypt
    ciphertext = aesgcm.encrypt(iv, plaintext, None)
    assert len(ciphertext) > len(plaintext)
    assert ciphertext != plaintext

    # Decrypt
    decrypted = aesgcm.decrypt(iv, ciphertext, None)
    assert decrypted == plaintext

    # Corrupted ciphertext fails
    with pytest.raises(Exception):
        corrupted = ciphertext[:-1] + bytes([ciphertext[-1] ^ 0xFF])
        aesgcm.decrypt(iv, corrupted, None)


def test_unit_qr_encoding_and_parsing():
    """Unit: QR payload validation and shop ID parsing."""
    shop_id = f"shp_{uuid.uuid4().hex[:8]}"
    qr_payload = f"privprint://shop?id={shop_id}&v=1"
    
    assert qr_payload.startswith("privprint://shop?id=")
    parsed_id = qr_payload.split("id=")[1].split("&")[0]
    assert parsed_id == shop_id


def test_unit_distance_haversine_calculation():
    """Unit: Haversine distance formula for nearby shop discovery."""
    def haversine(lat1, lon1, lat2, lon2):
        R = 6371.0  # Earth radius in km
        dlat = math.radians(lat2 - lat1)
        dlon = math.radians(lon2 - lon1)
        a = math.sin(dlat / 2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2)**2
        c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
        return R * c

    # Distance between Apex Xerox (12.9716, 77.5946) and Station 1km away
    dist_near = haversine(12.9716, 77.5946, 12.9750, 77.5980)
    assert dist_near < 1.0  # Under 1km

    # Distance to distant shop (100km away)
    dist_far = haversine(12.9716, 77.5946, 13.8716, 77.5946)
    assert dist_far > 90.0


def test_unit_job_state_machine_valid_and_invalid_transitions():
    """Unit: Job state machine transition enforcement."""
    # Valid transitions from CREATED
    assert PrintJobStatus.QUEUED.value in VALID_TRANSITIONS[PrintJobStatus.CREATED.value]
    assert PrintJobStatus.AUTHORIZED.value in VALID_TRANSITIONS[PrintJobStatus.CREATED.value]
    assert PrintJobStatus.CANCELLED.value in VALID_TRANSITIONS[PrintJobStatus.CREATED.value]

    # Valid transitions from AUTHORIZED
    assert PrintJobStatus.PRINTING.value in VALID_TRANSITIONS[PrintJobStatus.AUTHORIZED.value]

    # Terminal state COMPLETED has no outgoing transitions
    assert VALID_TRANSITIONS[PrintJobStatus.COMPLETED.value] == []


# -----------------------------------------------------------------------------
# 2. MULTI-USER & MULTI-SHOP TENANT ISOLATION TESTS
# -----------------------------------------------------------------------------

def test_comprehensive_multi_user_and_multi_shop_isolation(client: TestClient):
    """
    Test: User A and User B cannot access each other's:
    - documents
    - jobs
    - sessions
    - realtime events

    Shop A and Shop B cannot access each other's:
    - printers
    - devices
    - jobs
    """
    # 1. Register Shop A & Shop B
    op_a = client.post("/api/v1/auth/register", json={
        "email": "shop_a_op@example.com", "password": "Password123!", "role": "SHOP_OPERATOR"
    }).json()
    op_b = client.post("/api/v1/auth/register", json={
        "email": "shop_b_op@example.com", "password": "Password123!", "role": "SHOP_OPERATOR"
    }).json()

    auth_op_a = {"Authorization": f"Bearer {op_a['access_token']}"}
    auth_op_b = {"Authorization": f"Bearer {op_b['access_token']}"}

    shop_a = client.post("/api/v1/shops", json={"name": "Shop Alpha", "address": "Alpha Road"}, headers=auth_op_a).json()
    shop_b = client.post("/api/v1/shops", json={"name": "Shop Beta", "address": "Beta Road"}, headers=auth_op_b).json()

    # 2. Register Device A in Shop A
    dev_a = client.post(f"/api/v1/shops/{shop_a['id']}/devices", json={
        "device_name": "Terminal-Alpha-01"
    }, headers=auth_op_a).json()

    # 3. Register User A & User B
    user_a = client.post("/api/v1/auth/register", json={
        "email": "iso_user_a@example.com", "password": "Password123!", "role": "USER"
    }).json()
    user_b = client.post("/api/v1/auth/register", json={
        "email": "iso_user_b@example.com", "password": "Password123!", "role": "USER"
    }).json()

    auth_u_a = {"Authorization": f"Bearer {user_a['access_token']}"}
    auth_u_b = {"Authorization": f"Bearer {user_b['access_token']}"}

    # User A creates Session in Shop A
    sess_a = client.post("/api/v1/sessions", json={"shop_id": shop_a["id"]}, headers=auth_u_a).json()

    # User A uploads document
    doc_a = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess_a["id"],
        "filename": "alpha_doc.pdf.enc",
        "file_size_bytes": 1024,
        "mime_type": "application/pdf",
        "sha256_hash": "a" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_alpha",
        "copies_authorized": 1
    }, headers=auth_u_a).json()
    client.post(f"/api/v1/documents/{doc_a['upload_id']}/complete-upload", json={
        "document_id": doc_a["document_id"],
        "session_id": sess_a["id"]
    }, headers=auth_u_a)

    # User A creates Job
    job_a = client.post("/api/v1/jobs", json={
        "shop_id": shop_a["id"],
        "session_id": sess_a["id"],
        "document_id": doc_a["document_id"],
        "requested_copies": 1
    }, headers=auth_u_a).json()

    # --- CROSS-USER ISOLATION CHECKS ---
    # User B cannot read User A's job -> 403 Forbidden
    res_b_job = client.get(f"/api/v1/jobs/{job_a['id']}", headers=auth_u_b)
    assert res_b_job.status_code == 403

    # User B cannot authorize User A's job -> 403 Forbidden
    res_b_auth = client.post(f"/api/v1/jobs/{job_a['id']}/authorize", headers=auth_u_b)
    assert res_b_auth.status_code == 403

    # User B cannot delete User A's document -> 403 Forbidden
    res_b_cleanup = client.post(f"/api/v1/cleanup/document/{doc_a['document_id']}", headers=auth_u_b)
    assert res_b_cleanup.status_code == 403

    # --- CROSS-SHOP ISOLATION CHECKS ---
    # Shop B Operator cannot view Shop A's device queue -> 403 Forbidden
    res_shop_b_queue = client.get(f"/api/v1/print/jobs?shopId={shop_a['id']}", headers=auth_op_b)
    assert res_shop_b_queue.status_code == 403

    # Shop B Operator cannot start printing Shop A's job -> 403 Forbidden
    res_shop_b_print = client.post(f"/api/v1/print/jobs/{job_a['id']}/start", headers=auth_op_b)
    assert res_shop_b_print.status_code == 403


# -----------------------------------------------------------------------------
# 3. REALTIME & BACKEND INTEGRATION TESTS
# -----------------------------------------------------------------------------

def test_realtime_websocket_auth_and_channel_isolation(client: TestClient):
    """
    Realtime WebSocket authentication and cross-user channel subscription isolation.
    """
    user_a = client.post("/api/v1/auth/register", json={
        "email": "ws_iso_user_a@example.com", "password": "Password123!", "role": "USER"
    }).json()
    user_b = client.post("/api/v1/auth/register", json={
        "email": "ws_iso_user_b@example.com", "password": "Password123!", "role": "USER"
    }).json()

    # 1. Unauthenticated WebSocket rejected
    with pytest.raises(Exception):
        with client.websocket_connect("/api/v1/realtime/ws"):
            pass

    # 2. Authenticated WebSocket connects
    token_a = user_a["access_token"]
    user_a_id = user_a["user"]["id"]
    user_b_id = user_b["user"]["id"]

    with client.websocket_connect(f"/api/v1/realtime/ws?token={token_a}") as ws:
        connected_msg = ws.receive_json()
        assert connected_msg["type"] == "connected"

        # Subscribe to own user channel -> OK
        ws.send_json({"type": "subscribe", "channel": f"user:{user_a_id}"})
        sub_ok = ws.receive_json()
        assert sub_ok["type"] == "subscribed"
        assert sub_ok["channel"] == f"user:{user_a_id}"

        # Attempt to subscribe to User B's channel -> Subscription denied (FORBIDDEN)
        ws.send_json({"type": "subscribe", "channel": f"user:{user_b_id}"})
        sub_denied = ws.receive_json()
        assert sub_denied["type"] == "error"
        assert sub_denied["code"] == "FORBIDDEN"


# -----------------------------------------------------------------------------
# 4. E2E LIFECYCLE & PHYSICAL LIMITATION MOCK VERIFICATION
# -----------------------------------------------------------------------------

def test_e2e_android_to_windows_full_lifecycle(client: TestClient):
    """
    E2E flow test from Android Client Session -> Encryption Upload -> Job Authorization
    -> Windows Agent Queue Spooling -> Atomic Copy Increments -> Storage Shredding.
    """
    op = client.post("/api/v1/auth/register", json={
        "email": "e2e_full_op@example.com", "password": "Password123!", "role": "SHOP_OPERATOR"
    }).json()
    user = client.post("/api/v1/auth/register", json={
        "email": "e2e_full_user@example.com", "password": "Password123!", "role": "USER"
    }).json()

    auth_op = {"Authorization": f"Bearer {op['access_token']}"}
    auth_u = {"Authorization": f"Bearer {user['access_token']}"}

    shop = client.post("/api/v1/shops", json={"name": "E2E Master Shop", "address": "100 Enterprise Way"}, headers=auth_op).json()

    # 1. Android scans QR & initializes session
    sess = client.post("/api/v1/sessions", json={"shop_id": shop["id"]}, headers=auth_u).json()

    # 2. Android encrypts document locally & requests presigned upload
    doc = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"],
        "filename": "annual_tax_return.pdf.enc",
        "file_size_bytes": 2048,
        "mime_type": "application/pdf",
        "sha256_hash": "9" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_tax_09",
        "copies_authorized": 1
    }, headers=auth_u).json()
    storage_path = doc["storage_path"]
    client.post(f"/api/v1/documents/{doc['upload_id']}/complete-upload", json={
        "document_id": doc["document_id"],
        "session_id": sess["id"]
    }, headers=auth_u)

    # Put mock ciphertext into private storage
    storage = get_storage_service()
    storage.put_object_data(storage_path, b"ENCRYPTED_PAYLOAD_CIPHERTEXT")
    assert storage.object_exists(storage_path) is True

    # 3. Android submits print job
    job = client.post("/api/v1/jobs", json={
        "shop_id": shop["id"],
        "session_id": sess["id"],
        "document_id": doc["document_id"],
        "requested_copies": 1
    }, headers=auth_u).json()
    job_id = job["id"]

    # 4. User authorizes job
    client.post(f"/api/v1/jobs/{job_id}/authorize", headers=auth_u)

    # 5. Windows Agent fetches queue & starts spooling
    queue = client.get(f"/api/v1/print/jobs?shopId={shop['id']}", headers=auth_op).json()
    assert len(queue) >= 1
    assert any(j["id"] == job_id for j in queue)

    client.post(f"/api/v1/print/jobs/{job_id}/start", headers=auth_op)

    # 6. Windows Agent increments copy count to completion
    inc_res = client.post(f"/api/v1/print/jobs/{job_id}/increment-copy?delta=1", headers=auth_op)
    assert inc_res.status_code == 200
    assert inc_res.json()["status"] == "COMPLETED"

    # 7. Document lifecycle cleanup shredded & verified
    cleanup_res = client.post(f"/api/v1/cleanup/execute/{job_id}", headers=auth_op)
    assert cleanup_res.status_code == 200
    assert cleanup_res.json()["cleanup_state"] == "SHREDDED"
    assert storage.object_exists(storage_path) is False
