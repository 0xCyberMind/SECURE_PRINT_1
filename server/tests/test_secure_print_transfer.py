import base64
import hashlib

from fastapi.testclient import TestClient
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa


def test_station_key_registration_and_authorized_ciphertext_delivery(
    client: TestClient,
    admin_headers: dict[str, str],
):
    operator = client.post(
        "/api/v1/auth/register",
        json={
            "email": "secure_transfer_operator@example.com",
            "password": "Password123!",
            "role": "SHOP_OPERATOR",
        },
    ).json()
    operator_headers = {"Authorization": f"Bearer {operator['access_token']}"}
    shop = client.post(
        "/api/v1/shops",
        json={"name": "Secure Transfer Shop", "address": "100 Encryption Street"},
        headers=operator_headers,
    ).json()
    approved_shop = client.post(
        f"/api/v1/shops/{shop['id']}/approve",
        headers=admin_headers,
    )
    assert approved_shop.status_code == 200

    station = client.post(
        "/api/v1/devices/register",
        json={"shop_id": shop["id"], "name": "Secure Windows Station"},
        headers=operator_headers,
    ).json()
    station_auth = client.post(
        "/api/v1/devices/authenticate",
        json={"device_id": station["device_id"], "api_key": station["api_key"]},
    ).json()
    station_headers = {"Authorization": f"Bearer {station_auth['access_token']}"}
    station_private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    station_public_key = base64.b64encode(
        station_private_key.public_key().public_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    ).decode("ascii")
    key_response = client.put(
        f"/api/v1/devices/{station['device_id']}/print-key",
        json={"public_key": station_public_key},
        headers=station_headers,
    )
    assert key_response.status_code == 204

    second_station = client.post(
        "/api/v1/devices/register",
        json={"shop_id": shop["id"], "name": "Second Windows Station"},
        headers=operator_headers,
    ).json()
    second_station_auth = client.post(
        "/api/v1/devices/authenticate",
        json={
            "device_id": second_station["device_id"],
            "api_key": second_station["api_key"],
        },
    ).json()
    second_station_headers = {
        "Authorization": f"Bearer {second_station_auth['access_token']}"
    }
    second_station_public_key = base64.b64encode(
        rsa.generate_private_key(public_exponent=65537, key_size=2048)
        .public_key()
        .public_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    ).decode("ascii")
    second_key_response = client.put(
        f"/api/v1/devices/{second_station['device_id']}/print-key",
        json={"public_key": second_station_public_key},
        headers=second_station_headers,
    )
    assert second_key_response.status_code == 204

    user = client.post(
        "/api/v1/auth/register",
        json={
            "email": "secure_transfer_user@example.com",
            "password": "Password123!",
            "role": "USER",
        },
    ).json()
    user_headers = {"Authorization": f"Bearer {user['access_token']}"}
    keys_response = client.get(
        f"/api/v1/shops/{shop['id']}/print-keys",
        headers=user_headers,
    )
    assert keys_response.status_code == 200, keys_response.text
    assert {
        item["device_id"]: item["public_key"] for item in keys_response.json()
    } == {
        station["device_id"]: station_public_key,
        second_station["device_id"]: second_station_public_key,
    }

    session = client.post(
        "/api/v1/sessions",
        json={"shop_id": shop["id"]},
        headers=user_headers,
    ).json()
    ciphertext = b"actual encrypted bytes from the phone"
    wrapped_key = base64.b64encode(b"opaque wrapped AES key").decode("ascii")
    upload_payload = {
        "session_id": session["id"],
        "filename": "private.pdf.enc",
        "file_size_bytes": len(ciphertext),
        "mime_type": "application/pdf",
        "sha256_hash": hashlib.sha256(ciphertext).hexdigest(),
        "iv_hex": "0123456789abcdef01234567",
        "key_fingerprint": "fingerprint123",
        "wrapped_keys": {station["device_id"]: wrapped_key},
        "copies_authorized": 1,
    }
    missing_station_key = client.post(
        "/api/v1/documents/init-upload",
        json=upload_payload,
        headers=user_headers,
    )
    assert missing_station_key.status_code == 400

    upload_payload["wrapped_keys"] = {
        station["device_id"]: wrapped_key,
        second_station["device_id"]: wrapped_key,
    }
    upload = client.post(
        "/api/v1/documents/init-upload",
        json=upload_payload,
        headers=user_headers,
    ).json()
    chunk_response = client.post(
        f"/api/v1/documents/{upload['upload_id']}/chunk",
        content=ciphertext,
        headers={**user_headers, "Content-Type": "application/octet-stream"},
    )
    assert chunk_response.status_code == 200
    complete = client.post(
        f"/api/v1/documents/{upload['upload_id']}/complete-upload",
        json={
            "document_id": upload["document_id"],
            "session_id": session["id"],
            "sha256_hash": hashlib.sha256(ciphertext).hexdigest(),
            "file_size_bytes": len(ciphertext),
        },
        headers=user_headers,
    )
    assert complete.status_code == 200

    job = client.post(
        "/api/v1/jobs",
        json={
            "shop_id": shop["id"],
            "session_id": session["id"],
            "document_id": upload["document_id"],
            "requested_copies": 1,
        },
        headers=user_headers,
    ).json()
    authorized = client.post(
        f"/api/v1/jobs/{job['id']}/authorize",
        headers=user_headers,
    )
    assert authorized.status_code == 200

    content_response = client.get(
        f"/api/v1/documents/{upload['document_id']}/print-content",
        params={"job_id": job["id"]},
        headers=station_headers,
    )
    assert content_response.status_code == 200
    assert content_response.content == ciphertext
    assert content_response.headers["x-privprint-wrapped-key"] == wrapped_key
    assert content_response.headers["x-privprint-iv"] == "0123456789abcdef01234567"
    assert content_response.headers["x-privprint-sha256"] == hashlib.sha256(ciphertext).hexdigest()

    forbidden_response = client.get(
        f"/api/v1/documents/{upload['document_id']}/print-content",
        params={"job_id": job["id"]},
        headers=operator_headers,
    )
    assert forbidden_response.status_code == 403
