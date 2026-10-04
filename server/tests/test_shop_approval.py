import pytest
from fastapi.testclient import TestClient

from app.core.security import create_access_token, get_password_hash
from app.models.enums import UserRole
from app.repositories.user_repo import UserRepository


@pytest.mark.asyncio
async def test_shop_registration_is_hidden_until_admin_approval(
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
    assert shop["status"] == "PENDING_APPROVAL"
    assert shop["is_verified"] is False
    assert shop["is_online"] is False

    nearby = client.get(
        "/api/v1/shops/nearby?latitude=23.0225&longitude=72.5714&radius=10.0"
    )
    assert nearby.status_code == 200
    assert all(item["id"] != shop["id"] for item in nearby.json())

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
