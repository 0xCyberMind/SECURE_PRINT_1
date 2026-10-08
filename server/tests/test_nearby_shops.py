import pytest
from fastapi.testclient import TestClient
from app.core.security import create_access_token, get_password_hash
from app.models.entities import Device
from app.models.enums import UserRole
from app.repositories.user_repo import UserRepository


@pytest.mark.asyncio
async def test_nearby_and_distant_shops_discovery(client: TestClient, db_session):
    admin = await UserRepository(db_session).create(
        email="nearby_admin@example.com",
        hashed_password=get_password_hash("Password123!"),
        role=UserRole.ADMIN.value,
        is_active=True,
        is_verified=True,
    )
    await db_session.commit()
    token = create_access_token(subject=admin.id, role=admin.role)

    # 1. Create a Nearby Shop (approx. 0.4 km away from 23.0225, 72.5714)
    nearby_res = client.post(
        "/api/v1/shops",
        json={
            "name": "Nearby Xerox Center",
            "address": "Opposite Main Campus Gate",
            "latitude": 23.0260,
            "longitude": 72.5742,
            "supports_color": True,
            "supports_duplex": True
        },
        headers={"Authorization": f"Bearer {token}"}
    )
    assert nearby_res.status_code == 201
    nearby_shop = nearby_res.json()
    db_session.add(
        Device(
            id="dev_nearby_ready",
            shop_id=nearby_shop["id"],
            name="Ready Windows Station",
            encryption_public_key="registered-public-key",
        )
    )
    await db_session.commit()

    # 2. Create a Distant Shop (in Mumbai: approx 440 km away)
    distant_res = client.post(
        "/api/v1/shops",
        json={
            "name": "Distant Express Print",
            "address": "Marine Drive, Mumbai",
            "latitude": 18.9438,
            "longitude": 72.8234,
            "supports_color": True,
            "supports_duplex": True
        },
        headers={"Authorization": f"Bearer {token}"}
    )
    assert distant_res.status_code == 201
    distant_shop = distant_res.json()

    # 3. Query nearby with radius 10 km
    res = client.get("/api/v1/shops/nearby?latitude=23.0225&longitude=72.5714&radius=10.0")
    assert res.status_code == 200, res.text
    shops = res.json()

    # Nearby shop must be returned
    assert any(s["shop_id"] == nearby_shop["id"] for s in shops)
    found_nearby = next(s for s in shops if s["shop_id"] == nearby_shop["id"])
    assert found_nearby["name"] == "Nearby Xerox Center"
    assert found_nearby["address"] == "Opposite Main Campus Gate"
    assert found_nearby["status"] == "ACTIVE"
    assert found_nearby["distance_km"] < 1.0  # Approx 0.48 km

    # Distant shop must NOT be returned within 10 km
    assert not any(s["shop_id"] == distant_shop["id"] for s in shops)

    unpaired_res = client.post(
        "/api/v1/shops",
        json={
            "name": "Nearby Shop Without Station",
            "address": "Station not configured",
            "latitude": 23.0230,
            "longitude": 72.5720,
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert unpaired_res.status_code == 201
    unpaired_shop = unpaired_res.json()
    refreshed = client.get(
        "/api/v1/shops/nearby?latitude=23.0225&longitude=72.5714&radius=10.0"
    )
    assert refreshed.status_code == 200
    assert not any(s["shop_id"] == unpaired_shop["id"] for s in refreshed.json())

    # 4. Security Hygiene: Never expose private shop/device/owner data
    for s in shops:
        assert "owner_id" not in s
        assert "devices" not in s
        assert "api_key" not in s
        assert "printers" not in s


def test_invalid_coordinates_validation(client: TestClient):
    # Latitude > 90
    res1 = client.get("/api/v1/shops/nearby?latitude=95.0&longitude=72.0&radius=10")
    assert res1.status_code == 400
    assert res1.json()["error"]["code"] == "VALIDATION_ERROR"

    # Latitude < -90
    res2 = client.get("/api/v1/shops/nearby?latitude=-95.0&longitude=72.0&radius=10")
    assert res2.status_code == 400
    assert res2.json()["error"]["code"] == "VALIDATION_ERROR"

    # Longitude > 180
    res3 = client.get("/api/v1/shops/nearby?latitude=23.0&longitude=185.0&radius=10")
    assert res3.status_code == 400
    assert res3.json()["error"]["code"] == "VALIDATION_ERROR"

    # Longitude < -180
    res4 = client.get("/api/v1/shops/nearby?latitude=23.0&longitude=-185.0&radius=10")
    assert res4.status_code == 400
    assert res4.json()["error"]["code"] == "VALIDATION_ERROR"


def test_radius_validation(client: TestClient):
    # Radius <= 0
    res1 = client.get("/api/v1/shops/nearby?latitude=23.0&longitude=72.0&radius=0")
    assert res1.status_code == 400
    assert res1.json()["error"]["code"] == "VALIDATION_ERROR"

    res2 = client.get("/api/v1/shops/nearby?latitude=23.0&longitude=72.0&radius=-5")
    assert res2.status_code == 400
    assert res2.json()["error"]["code"] == "VALIDATION_ERROR"

    # Radius > 100 km limit
    res3 = client.get("/api/v1/shops/nearby?latitude=23.0&longitude=72.0&radius=150")
    assert res3.status_code == 400
    assert res3.json()["error"]["code"] == "VALIDATION_ERROR"


@pytest.mark.asyncio
async def test_disabled_and_unverified_shop_exclusion(client: TestClient, db_session):
    from app.models.entities import Shop
    from app.repositories.shop_repo import ShopRepository

    repo = ShopRepository(db_session)

    # 1. Inactive Shop within radius
    await repo.create(
        id="SHOP-INACTIVE",
        name="Inactive Shop",
        address="123 Closed Road",
        latitude=23.0230,
        longitude=72.5720,
        status="INACTIVE",
        is_verified=True,
        permanent_qr_payload="privprint://shop?id=SHOP-INACTIVE"
    )

    # 2. Unverified Shop within radius
    await repo.create(
        id="SHOP-UNVERIFIED",
        name="Unverified Shop",
        address="456 Pending Road",
        latitude=23.0230,
        longitude=72.5720,
        status="ACTIVE",
        is_verified=False,
        permanent_qr_payload="privprint://shop?id=SHOP-UNVERIFIED"
    )

    await db_session.commit()

    # Query nearby
    res = client.get("/api/v1/shops/nearby?latitude=23.0225&longitude=72.5714&radius=10.0")
    assert res.status_code == 200
    shops = res.json()

    # Neither inactive nor unverified shop should be returned
    assert not any(s["shop_id"] == "SHOP-INACTIVE" for s in shops)
    assert not any(s["shop_id"] == "SHOP-UNVERIFIED" for s in shops)


def test_no_location_permission_qr_fallback(client: TestClient):
    # Setup Shop
    op = client.post("/api/v1/auth/register", json={
        "email": "qr_fallback_op@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()

    shop = client.post(
        "/api/v1/shops",
        json={"name": "Fallback Print Hub", "address": "Station Arcade 4"},
        headers={"Authorization": f"Bearer {op['access_token']}"}
    ).json()
    shop_id = shop["id"]

    # When user denies location permission, "Scan Shop QR" remains 100% operational:
    # 1. The permanent QR payload is fetched/decoded directly
    qr_res = client.get(f"/api/v1/shops/{shop_id}/permanent-qr")
    assert qr_res.status_code == 200
    qr_payload = qr_res.json()["qr_payload"]
    assert qr_payload == f"privprint://shop?id={shop_id}"

    # 2. Ephemeral session can be created directly from the scanned QR without GPS coordinates
    user = client.post("/api/v1/auth/register", json={
        "email": "no_gps_user@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()

    sess_res = client.post(
        "/api/v1/sessions",
        json={"shop_id": shop_id},
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert sess_res.status_code == 201
    sess_data = sess_res.json()
    assert sess_data["shop_id"] == shop_id
    assert sess_data["status"] == "ACTIVE"
    assert "token" in sess_data
    assert "nonce" in sess_data


@pytest.mark.asyncio
async def test_shop_location_update_and_ownership(client: TestClient, db_session):
    # 1. Register operator 1 and shop 1
    op1 = client.post("/api/v1/auth/register", json={
        "email": "loc_op1@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    token1 = op1["access_token"]

    shop1 = client.post(
        "/api/v1/shops",
        json={"name": "Loc Test Shop 1", "address": "Unconfigured Lane"},
        headers={"Authorization": f"Bearer {token1}"}
    ).json()
    shop1_id = shop1["id"]

    # Register active device with encryption key for shop1
    db_session.add(
        Device(
            id="dev_loc_test_1",
            shop_id=shop1_id,
            name="Windows Station 1",
            encryption_public_key="station-1-public-key",
            is_active=True,
        )
    )
    await db_session.commit()

    # Shop 1 has no location initially -> must NOT appear in nearby
    res_initial = client.get("/api/v1/shops/nearby?lat=23.0225&lng=72.5714&radius=10.0")
    assert res_initial.status_code == 200
    assert not any(s["shop_id"] == shop1_id for s in res_initial.json())

    # 2. Register operator 2 and shop 2 (attacker)
    op2 = client.post("/api/v1/auth/register", json={
        "email": "loc_op2@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    token2 = op2["access_token"]

    # Operator 2 tries to update Shop 1's location -> MUST be rejected (403 Forbidden)
    attack_res = client.put(
        f"/api/v1/shops/{shop1_id}/location",
        json={"latitude": 23.0225, "longitude": 72.5714},
        headers={"Authorization": f"Bearer {token2}"}
    )
    assert attack_res.status_code == 403

    # 3. Legitimate operator 1 updates Shop 1's real location
    update_res = client.put(
        f"/api/v1/shops/{shop1_id}/location",
        json={
            "latitude": 23.0230,
            "longitude": 72.5720,
            "address": "Updated Campus Station 1"
        },
        headers={"Authorization": f"Bearer {token1}"}
    )
    assert update_res.status_code == 200
    updated_shop = update_res.json()
    assert updated_shop["latitude"] == 23.0230
    assert updated_shop["longitude"] == 72.5720
    assert updated_shop["address"] == "Updated Campus Station 1"

    # 4. Check settings endpoint reflects updated location
    settings_res = client.get(
        f"/api/v1/shops/{shop1_id}/settings",
        headers={"Authorization": f"Bearer {token1}"}
    )
    assert settings_res.status_code == 200
    assert settings_res.json()["location_enabled"] is True
    assert settings_res.json()["latitude"] == 23.0230

    # 5. Now Shop 1 MUST appear in Nearby Shops query
    res_after = client.get("/api/v1/shops/nearby?lat=23.0225&lng=72.5714&radius=10.0")
    assert res_after.status_code == 200
    found = [s for s in res_after.json() if s["shop_id"] == shop1_id]
    assert len(found) == 1
    assert found[0]["name"] == "Loc Test Shop 1"
    assert found[0]["address"] == "Updated Campus Station 1"
    assert found[0]["distance_km"] < 1.0
    assert found[0]["is_online"] is False
    assert found[0]["is_verified"] is True
    assert found[0]["permanent_qr_payload"] == f"privprint://shop?id={shop1_id}"
