import pytest
import base64
from fastapi.testclient import TestClient
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from app.core.security import create_access_token, get_password_hash
from app.models.enums import UserRole
from app.models.entities import Shop
from app.repositories.user_repo import UserRepository


@pytest.mark.asyncio
async def test_shop_registration_is_active_without_granting_admin_access(
    client: TestClient,
    db_session,
):
    registration = client.post(
        "/api/v1/auth/register",
        json={
            "email": "pending_shop@example.com",
            "password": "Password123!",
            "full_name": "Shop Operator",
            "role": "SHOP_OPERATOR",
        },
    )
    assert registration.status_code == 201, registration.text
    operator_token = registration.json()["access_token"]

    created = client.post(
        "/api/v1/shops",
        json={
            "name": "Pending Xerox Shop",
            "address": "Address pending admin approval",
            "latitude": 23.0260,
            "longitude": 72.5742,
        },
        headers={"Authorization": f"Bearer {operator_token}"},
    )
    assert created.status_code == 201, created.text
    shop = created.json()
    assert shop["status"] == "ACTIVE"
    assert shop["is_verified"] is True
    assert shop["is_online"] is False
    station_keys = client.get(
        f"/api/v1/shops/{shop['id']}/print-keys",
        headers={"Authorization": f"Bearer {operator_token}"},
    )
    assert station_keys.status_code == 200, station_keys.text
    assert station_keys.json() == []

    nearby = client.get(
        "/api/v1/shops/nearby?latitude=23.0225&longitude=72.5714&radius=10.0"
    )
    assert nearby.status_code == 200
    assert any(item["id"] == shop["id"] for item in nearby.json())

    operator_approval = client.post(
        f"/api/v1/shops/{shop['id']}/approve",
        headers={"Authorization": f"Bearer {operator_token}"},
    )
    assert operator_approval.status_code == 403

    admin = await UserRepository(db_session).create(
        email="shop_approval_admin@example.com",
        hashed_password=get_password_hash("AdminPassword123!"),
        role=UserRole.ADMIN.value,
        is_active=True,
        is_verified=True,
    )
    await db_session.commit()
    admin_token = create_access_token(subject=admin.id, role=admin.role)

    approved = client.post(
        f"/api/v1/shops/{shop['id']}/approve",
        headers={"Authorization": f"Bearer {admin_token}"},
    )
    assert approved.status_code == 200, approved.text
    assert approved.json()["status"] == "ACTIVE"
    assert approved.json()["is_verified"] is True
    assert approved.json()["is_online"] is True

    nearby_after_approval = client.get(
        "/api/v1/shops/nearby?latitude=23.0225&longitude=72.5714&radius=10.0"
    )
    assert any(item["id"] == shop["id"] for item in nearby_after_approval.json())


@pytest.mark.asyncio
async def test_shop_operator_login_activates_legacy_shop_and_restores_station_keys(
    client: TestClient,
    db_session,
):
    email = "legacy_shop_operator@example.com"
    registration = client.post(
        "/api/v1/auth/register",
        json={
            "email": email,
            "password": "Password123!",
            "full_name": "Legacy Shop Operator",
            "role": "SHOP_OPERATOR",
        },
    )
    assert registration.status_code == 201, registration.text
    user_id = registration.json()["user"]["id"]

    shop = Shop(
        id="SHOP-LEGACY-APPROVAL",
        name="Legacy Pending Xerox",
        owner_id=user_id,
        address="Existing shop before automatic activation",
        status="PENDING_APPROVAL",
        is_verified=False,
        is_online=False,
        permanent_qr_payload="SHOP-LEGACY-APPROVAL",
    )
    db_session.add(shop)
    await db_session.commit()

    operator_headers = {
        "Authorization": f"Bearer {registration.json()['access_token']}"
    }
    station = client.post(
        "/api/v1/devices/register",
        json={"shop_id": shop.id, "name": "Existing Windows Station"},
        headers=operator_headers,
    )
    assert station.status_code == 201, station.text
    station_credentials = station.json()
    station_auth = client.post(
        "/api/v1/devices/authenticate",
        json={
            "device_id": station_credentials["device_id"],
            "api_key": station_credentials["api_key"],
        },
    )
    assert station_auth.status_code == 200, station_auth.text

    station_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public_key = base64.b64encode(
        station_key.public_key().public_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    ).decode("ascii")
    key_registration = client.put(
        f"/api/v1/devices/{station_credentials['device_id']}/print-key",
        json={"public_key": public_key},
        headers={
            "Authorization": f"Bearer {station_auth.json()['access_token']}"
        },
    )
    assert key_registration.status_code == 204, key_registration.text

    before_login = client.get(
        f"/api/v1/shops/{shop.id}/print-keys",
        headers=operator_headers,
    )
    assert before_login.status_code == 404

    login = client.post(
        "/api/v1/auth/login",
        json={"email": email, "password": "Password123!"},
    )
    assert login.status_code == 200, login.text
    assert login.json()["user"]["role"] == "SHOP_OPERATOR"

    after_login = client.get(
        f"/api/v1/shops/{shop.id}/print-keys",
        headers={"Authorization": f"Bearer {login.json()['access_token']}"},
    )
    assert after_login.status_code == 200, after_login.text
    assert after_login.json() == [
        {"device_id": station_credentials["device_id"], "public_key": public_key}
    ]
