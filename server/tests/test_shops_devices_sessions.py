import pytest
from fastapi.testclient import TestClient
from app.models.enums import UserRole


def test_shop_endpoints_and_permanent_qr(client: TestClient):
    # 1. Register Shop Operator
    op_res = client.post("/api/v1/auth/register", json={
        "email": "operator_campus@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    op_token = op_res["access_token"]

    # 2. Create Shop
    shop_create_payload = {
        "name": "Apex Campus Print Hub",
        "address": "400 Student Union Blvd",
        "latitude": 37.7749,
        "longitude": -122.4194,
        "supports_color": True,
        "supports_duplex": True
    }
    create_res = client.post(
        "/api/v1/shops",
        json=shop_create_payload,
        headers={"Authorization": f"Bearer {op_token}"}
    )
    assert create_res.status_code == 201, create_res.text
    shop = create_res.json()
    shop_id = shop["id"]

    # Verify all required shop fields exist
    assert shop["name"] == "Apex Campus Print Hub"
    assert shop["address"] == "400 Student Union Blvd"
    assert shop["latitude"] == 37.7749
    assert shop["longitude"] == -122.4194
    assert shop["status"] == "ACTIVE"
    assert "created_at" in shop
    assert "updated_at" in shop

    # 3. GET /api/v1/shops (authenticated: operator sees their own shops)
    list_res = client.get("/api/v1/shops", headers={"Authorization": f"Bearer {op_token}"})
    assert list_res.status_code == 200
    shops_list = list_res.json()
    assert any(s["id"] == shop_id for s in shops_list)

    # 4. GET /api/v1/shops/{id}
    get_res = client.get(f"/api/v1/shops/{shop_id}")
    assert get_res.status_code == 200
    assert get_res.json()["id"] == shop_id

    # 5. GET /api/v1/shops/{id}/permanent-qr
    qr_res = client.get(f"/api/v1/shops/{shop_id}/permanent-qr")
    assert qr_res.status_code == 200
    qr_data = qr_res.json()
    assert qr_data["shop_id"] == shop_id
    assert qr_data["qr_payload"] == f"privprint://shop?id={shop_id}"
    # Permanent QR contains NO secrets
    assert "secret" not in qr_data["qr_payload"]
    assert "token" not in qr_data["qr_payload"]


def test_independent_ephemeral_sessions_for_same_qr_scan(client: TestClient):
    # Setup Shop
    op = client.post("/api/v1/auth/register", json={
        "email": "shop_sess_op@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()

    shop = client.post(
        "/api/v1/shops",
        json={"name": "Library Xerox", "address": "Floor 1"},
        headers={"Authorization": f"Bearer {op['access_token']}"}
    ).json()
    shop_id = shop["id"]

    # Register User 1 and User 2
    u1 = client.post("/api/v1/auth/register", json={
        "email": "scanner_u1@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()

    u2 = client.post("/api/v1/auth/register", json={
        "email": "scanner_u2@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()

    # User 1 scans the permanent shop QR
    sess_res_1 = client.post(
        "/api/v1/sessions",
        json={"shop_id": shop_id},
        headers={"Authorization": f"Bearer {u1['access_token']}"}
    )
    assert sess_res_1.status_code == 201
    sess_1 = sess_res_1.json()

    # User 2 scans the EXACT same permanent shop QR
    sess_res_2 = client.post(
        "/api/v1/sessions",
        json={"shop_id": shop_id},
        headers={"Authorization": f"Bearer {u2['access_token']}"}
    )
    assert sess_res_2.status_code == 201
    sess_2 = sess_res_2.json()

    # Verify session fields
    for s in [sess_1, sess_2]:
        assert s["shop_id"] == shop_id
        assert s["status"] == "ACTIVE"
        assert "nonce" in s
        assert "token" in s
        assert "expires_at" in s

    # CRITICAL REQUIREMENT:
    # Two users scanning the same QR must receive completely independent sessions!
    assert sess_1["id"] != sess_2["id"]
    assert sess_1["token"] != sess_2["token"]
    assert sess_1["nonce"] != sess_2["nonce"]
    assert sess_1["user_id"] == u1["user"]["id"]
    assert sess_2["user_id"] == u2["user"]["id"]
    assert sess_1["user_id"] != sess_2["user_id"]


def test_windows_device_management_and_authorization(client: TestClient):
    # Setup Shop A & Operator A
    op_a = client.post("/api/v1/auth/register", json={
        "email": "op_a_device@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    shop_a = client.post(
        "/api/v1/shops",
        json={"name": "Shop A", "address": "100 Broadway Ave"},
        headers={"Authorization": f"Bearer {op_a['access_token']}"}
    ).json()

    # Setup Shop B & Operator B
    op_b = client.post("/api/v1/auth/register", json={
        "email": "op_b_device@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    shop_b = client.post(
        "/api/v1/shops",
        json={"name": "Shop B", "address": "200 Market Street"},
        headers={"Authorization": f"Bearer {op_b['access_token']}"}
    ).json()

    # Standard User
    user = client.post("/api/v1/auth/register", json={
        "email": "regular_user_device@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()

    # 1. Standard user tries to register a device -> 403 FORBIDDEN
    bad_reg = client.post(
        "/api/v1/devices/register",
        json={
            "shop_id": shop_a["id"],
            "name": "Unauthorized Station"
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert bad_reg.status_code == 403

    # 2. Operator B tries to register a device for Shop A -> 403 FORBIDDEN
    cross_reg = client.post(
        "/api/v1/devices/register",
        json={
            "shop_id": shop_a["id"],
            "name": "Rogue Station"
        },
        headers={"Authorization": f"Bearer {op_b['access_token']}"}
    )
    assert cross_reg.status_code == 403

    # 3. Operator A registers Windows Shop Station for Shop A -> 201 CREATED
    device_payload = {
        "shop_id": shop_a["id"],
        "name": "Front Desk Windows Spooler",
        "os_info": "Windows 11 Pro 23H2 (Build 22631)",
        "app_version": "1.4.2",
        "hardware_fingerprint": "HWID-DELL-OPTIPLEX-7090"
    }
    dev_res = client.post(
        "/api/v1/devices/register",
        json=device_payload,
        headers={"Authorization": f"Bearer {op_a['access_token']}"}
    )
    assert dev_res.status_code == 201
    dev_data = dev_res.json()
    assert dev_data["shop_id"] == shop_a["id"]
    assert dev_data["name"] == "Front Desk Windows Spooler"
    assert dev_data["os_info"] == "Windows 11 Pro 23H2 (Build 22631)"
    assert dev_data["app_version"] == "1.4.2"
    assert dev_data["auth_state"] == "AUTHENTICATED"
    assert dev_data["status"] == "ONLINE"
    assert "api_key" in dev_data
    dev_id = dev_data["device_id"]

    # 4. Operator A can inspect their own device
    get_dev = client.get(
        f"/api/v1/devices/{dev_id}",
        headers={"Authorization": f"Bearer {op_a['access_token']}"}
    )
    assert get_dev.status_code == 200
    assert get_dev.json()["id"] == dev_id

    # 5. Operator B cannot inspect Operator A's device -> 403 FORBIDDEN
    cross_get = client.get(
        f"/api/v1/devices/{dev_id}",
        headers={"Authorization": f"Bearer {op_b['access_token']}"}
    )
    assert cross_get.status_code == 403
