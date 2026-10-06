import pytest
from fastapi.testclient import TestClient
from app.models.enums import UserRole


def test_get_shop_printers_and_sync(client: TestClient):
    # 1. Register operator & create shop
    op_res = client.post("/api/v1/auth/register", json={
        "email": "operator_prn_test@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    op_token = op_res["access_token"]

    shop_res = client.post("/api/v1/shops", json={
        "name": "Printers Test Shop",
        "address": "123 Main St",
        "supports_color": True,
        "supports_duplex": True
    }, headers={"Authorization": f"Bearer {op_token}"}).json()
    shop_id = shop_res["id"]

    # 2. Windows Agent syncs discovered printers
    printers_payload = {
        "shop_id": shop_id,
        "printers": [
            {
                "id": "PRN-WIN-HP608",
                "shop_id": shop_id,
                "name": "HP LaserJet Enterprise M608",
                "model": "LaserJet Enterprise M608dn",
                "driver_name": "HP LaserJet M608 PCL6",
                "connection_info": "IP_192.168.1.101",
                "status": "READY",
                "is_default": True,
                "is_online": True,
                "supports_color": False,
                "supports_duplex": True,
                "supported_paper_sizes": "A4, Letter, Legal",
                "paper_tray_status": "READY",
                "toner_level_percent": 92
            },
            {
                "id": "PRN-WIN-XRX405",
                "shop_id": shop_id,
                "name": "Xerox VersaLink C405",
                "model": "VersaLink C405 Color",
                "driver_name": "Xerox GPD PCL6 V5.6",
                "connection_info": "USB001",
                "status": "READY",
                "is_default": False,
                "is_online": True,
                "supports_color": True,
                "supports_duplex": True,
                "supported_paper_sizes": "A4, Letter",
                "paper_tray_status": "READY",
                "toner_level_percent": 85
            }
        ]
    }

    sync_res = client.post(
        "/api/v1/printers/sync",
        json=printers_payload,
        headers={"Authorization": f"Bearer {op_token}"}
    )
    assert sync_res.status_code == 200, sync_res.text
    synced = sync_res.json()
    assert len(synced) == 2

    # 3. GET /api/v1/shops/{id}/printers
    get_res = client.get(f"/api/v1/shops/{shop_id}/printers")
    assert get_res.status_code == 200
    prn_list = get_res.json()
    assert len(prn_list) == 2
    assert any(p["id"] == "PRN-WIN-HP608" and p["status"] == "READY" for p in prn_list)
    assert any(p["id"] == "PRN-WIN-XRX405" and p["driver_name"] == "Xerox GPD PCL6 V5.6" for p in prn_list)


def test_printer_sync_is_idempotent_and_rejects_cross_shop_id_collision(client: TestClient):
    def create_shop(email: str, name: str):
        operator = client.post("/api/v1/auth/register", json={
            "email": email,
            "password": "Password123!",
            "role": "SHOP_OPERATOR",
        }).json()
        shop = client.post(
            "/api/v1/shops",
            json={"name": name, "address": "123 Main St"},
            headers={"Authorization": f"Bearer {operator['access_token']}"},
        )
        assert shop.status_code == 201, shop.text
        return shop.json(), operator["access_token"]

    first_shop, first_token = create_shop("printer_sync_first@example.com", "First Printer Shop")
    second_shop, second_token = create_shop("printer_sync_second@example.com", "Second Printer Shop")
    printer = {
        "id": "PRN-WIN-COLLISION",
        "name": "Shared Windows Printer",
        "model": "Laser Printer",
        "driver_name": "Generic Driver",
        "connection_info": "USB001",
        "status": "READY",
    }

    first_sync_headers = {"Authorization": f"Bearer {first_token}"}
    first_sync = client.post(
        "/api/v1/printers/sync",
        json={"shop_id": first_shop["id"], "printers": [{**printer, "shop_id": first_shop["id"]}]},
        headers=first_sync_headers,
    )
    assert first_sync.status_code == 200, first_sync.text

    updated_printer = {**printer, "name": "Renamed Windows Printer", "status": "BUSY"}
    repeated_sync = client.post(
        "/api/v1/printers/sync",
        json={
            "shop_id": first_shop["id"],
            "printers": [{**updated_printer, "shop_id": first_shop["id"]}],
        },
        headers=first_sync_headers,
    )
    assert repeated_sync.status_code == 200, repeated_sync.text
    assert repeated_sync.json()[0]["name"] == "Renamed Windows Printer"
    assert repeated_sync.json()[0]["status"] == "BUSY"

    second_sync = client.post(
        "/api/v1/printers/sync",
        json={"shop_id": second_shop["id"], "printers": [{**printer, "shop_id": second_shop["id"]}]},
        headers={"Authorization": f"Bearer {second_token}"},
    )
    assert second_sync.status_code == 409, second_sync.text


def test_select_printer_valid_and_cross_shop_isolation(client: TestClient):
    # Setup Shop 1
    op1 = client.post("/api/v1/auth/register", json={
        "email": "op_shop1@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    shop1 = client.post("/api/v1/shops", json={
        "name": "Shop Alpha",
        "address": "Alpha Ave"
    }, headers={"Authorization": f"Bearer {op1['access_token']}"}).json()
    shop1_id = shop1["id"]

    # Setup Shop 2
    op2 = client.post("/api/v1/auth/register", json={
        "email": "op_shop2@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    shop2 = client.post("/api/v1/shops", json={
        "name": "Shop Beta",
        "address": "Beta Blvd"
    }, headers={"Authorization": f"Bearer {op2['access_token']}"}).json()
    shop2_id = shop2["id"]

    # Register Printer P1 in Shop 1
    client.post("/api/v1/printers/sync", json={
        "shop_id": shop1_id,
        "printers": [{
            "id": "PRN-ALPHA-01",
            "shop_id": shop1_id,
            "name": "Alpha Laser Printer",
            "model": "HP LaserJet",
            "driver_name": "HP PCL6",
            "connection_info": "IP_10.0.0.1",
            "status": "READY",
            "is_default": True,
            "is_online": True,
            "supports_color": True,
            "supports_duplex": True
        }]
    }, headers={"Authorization": f"Bearer {op1['access_token']}"})

    # Register Printer P2 in Shop 2
    client.post("/api/v1/printers/sync", json={
        "shop_id": shop2_id,
        "printers": [{
            "id": "PRN-BETA-02",
            "shop_id": shop2_id,
            "name": "Beta Color Printer",
            "model": "Canon imageRUNNER",
            "driver_name": "Canon Generic",
            "connection_info": "WSD-CANON",
            "status": "READY",
            "is_default": True,
            "is_online": True,
            "supports_color": True,
            "supports_duplex": True
        }]
    }, headers={"Authorization": f"Bearer {op2['access_token']}"})

    # User registers and logs in
    user = client.post("/api/v1/auth/register", json={
        "email": "customer_prn@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()
    u_token = user["access_token"]

    # 1. Valid Printer Selection (Printer P1 belongs to Shop 1)
    sel_res_valid = client.post(
        "/api/v1/printers/select",
        json={"shop_id": shop1_id, "printer_id": "PRN-ALPHA-01"},
        headers={"Authorization": f"Bearer {u_token}"}
    )
    assert sel_res_valid.status_code == 200, sel_res_valid.text
    sel_data = sel_res_valid.json()
    assert sel_data["success"] is True
    assert sel_data["printer_id"] == "PRN-ALPHA-01"
    assert sel_data["shop_id"] == shop1_id

    # 2. Cross-Shop Printer Selection REJECTION:
    # Client attempts to select Printer P2 (belonging to Shop 2) with shop_id=Shop 1
    sel_res_invalid = client.post(
        "/api/v1/printers/select",
        json={"shop_id": shop1_id, "printer_id": "PRN-BETA-02"},
        headers={"Authorization": f"Bearer {u_token}"}
    )
    assert sel_res_invalid.status_code == 403
    err_data = sel_res_invalid.json()
    assert "error" in err_data
    assert "does not belong to shop" in err_data["error"]["message"]

    # 3. Non-existent printer selection REJECTION
    sel_res_404 = client.post(
        "/api/v1/printers/select",
        json={"shop_id": shop1_id, "printer_id": "PRN-NON-EXISTENT"},
        headers={"Authorization": f"Bearer {u_token}"}
    )
    assert sel_res_404.status_code == 403
