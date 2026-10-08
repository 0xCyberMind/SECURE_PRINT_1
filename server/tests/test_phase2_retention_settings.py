import os
import tempfile
import pytest
from fastapi.testclient import TestClient

from windows_agent.config import AgentConfig


@pytest.fixture
def phase2_setup(client: TestClient):
    # 1. Register Operator A & Shop A
    op_a_res = client.post("/api/v1/auth/register", json={
        "email": "phase2_op_a@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    op_a_token = op_a_res["access_token"]
    shop_a = client.post("/api/v1/shops", json={
        "name": "Phase 2 Shop A",
        "address": "100 Retention Ave"
    }, headers={"Authorization": f"Bearer {op_a_token}"}).json()

    # Register Device A for Shop A
    dev_a_reg = client.post("/api/v1/devices/register", json={
        "shop_id": shop_a["id"],
        "name": "Station A"
    }, headers={"Authorization": f"Bearer {op_a_token}"}).json()
    dev_a_auth = client.post("/api/v1/devices/authenticate", json={
        "device_id": dev_a_reg["device_id"],
        "api_key": dev_a_reg["api_key"]
    }).json()
    dev_a_token = dev_a_auth["access_token"]

    # 2. Register Operator B & Shop B
    op_b_res = client.post("/api/v1/auth/register", json={
        "email": "phase2_op_b@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    op_b_token = op_b_res["access_token"]
    shop_b = client.post("/api/v1/shops", json={
        "name": "Phase 2 Shop B",
        "address": "200 Retention Blvd"
    }, headers={"Authorization": f"Bearer {op_b_token}"}).json()

    # Register Device B for Shop B
    dev_b_reg = client.post("/api/v1/devices/register", json={
        "shop_id": shop_b["id"],
        "name": "Station B"
    }, headers={"Authorization": f"Bearer {op_b_token}"}).json()
    dev_b_auth = client.post("/api/v1/devices/authenticate", json={
        "device_id": dev_b_reg["device_id"],
        "api_key": dev_b_reg["api_key"]
    }).json()
    dev_b_token = dev_b_auth["access_token"]

    # 3. Register regular User
    user_res = client.post("/api/v1/auth/register", json={
        "email": "phase2_user@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()
    user_token = user_res["access_token"]

    return {
        "shop_a_id": shop_a["id"],
        "op_a_token": op_a_token,
        "dev_a_token": dev_a_token,
        "shop_b_id": shop_b["id"],
        "op_b_token": op_b_token,
        "dev_b_token": dev_b_token,
        "user_token": user_token
    }


def test_default_retention_setting(client: TestClient, phase2_setup):
    """
    Default retention for a newly created shop must be 4 hours.
    """
    op_a_auth = {"Authorization": f"Bearer {phase2_setup['op_a_token']}"}
    res = client.get(f"/api/v1/shops/{phase2_setup['shop_a_id']}/settings", headers=op_a_auth)
    assert res.status_code == 200
    data = res.json()
    assert data["shop_id"] == phase2_setup["shop_a_id"]
    assert data["history_retention_hours"] == 4


@pytest.mark.parametrize("valid_hours", [1, 2, 4, 6, 8])
def test_valid_retention_values_accepted(client: TestClient, phase2_setup, valid_hours):
    """
    Setting retention to 1, 2, 4, 6, or 8 hours must succeed and update server state.
    """
    op_a_auth = {"Authorization": f"Bearer {phase2_setup['op_a_token']}"}
    res = client.patch(
        f"/api/v1/shops/{phase2_setup['shop_a_id']}/settings",
        json={"history_retention_hours": valid_hours},
        headers=op_a_auth
    )
    assert res.status_code == 200
    assert res.json()["history_retention_hours"] == valid_hours

    # Verify retrieval reflects new setting
    get_res = client.get(f"/api/v1/shops/{phase2_setup['shop_a_id']}/settings", headers=op_a_auth)
    assert get_res.status_code == 200
    assert get_res.json()["history_retention_hours"] == valid_hours


@pytest.mark.parametrize("invalid_hours", [0, -1, 9, 10, 24, 100])
def test_invalid_retention_values_rejected(client: TestClient, phase2_setup, invalid_hours):
    """
    Values other than 1, 2, 4, 6, 8 (including 0, -1, 9, 10, 24, 100) must be rejected.
    """
    op_a_auth = {"Authorization": f"Bearer {phase2_setup['op_a_token']}"}
    res = client.patch(
        f"/api/v1/shops/{phase2_setup['shop_a_id']}/settings",
        json={"history_retention_hours": invalid_hours},
        headers=op_a_auth
    )
    assert res.status_code in [400, 422]


def test_station_device_can_update_own_shop_retention(client: TestClient, phase2_setup):
    """
    Windows Station device token can read and update its own shop's retention setting.
    """
    dev_a_auth = {"Authorization": f"Bearer {phase2_setup['dev_a_token']}"}
    res = client.patch(
        f"/api/v1/shops/{phase2_setup['shop_a_id']}/settings",
        json={"history_retention_hours": 2},
        headers=dev_a_auth
    )
    assert res.status_code == 200
    assert res.json()["history_retention_hours"] == 2


def test_cross_tenant_isolation_shop_settings(client: TestClient, phase2_setup):
    """
    Shop B operator or Station B device MUST NOT be able to view or modify Shop A's settings.
    """
    op_b_auth = {"Authorization": f"Bearer {phase2_setup['op_b_token']}"}
    dev_b_auth = {"Authorization": f"Bearer {phase2_setup['dev_b_token']}"}
    user_auth = {"Authorization": f"Bearer {phase2_setup['user_token']}"}

    # Shop B operator tries to read Shop A
    res = client.get(f"/api/v1/shops/{phase2_setup['shop_a_id']}/settings", headers=op_b_auth)
    assert res.status_code == 403

    # Shop B operator tries to update Shop A
    res = client.patch(
        f"/api/v1/shops/{phase2_setup['shop_a_id']}/settings",
        json={"history_retention_hours": 1},
        headers=op_b_auth
    )
    assert res.status_code == 403

    # Station B tries to update Shop A
    res = client.patch(
        f"/api/v1/shops/{phase2_setup['shop_a_id']}/settings",
        json={"history_retention_hours": 1},
        headers=dev_b_auth
    )
    assert res.status_code == 403

    # Regular customer tries to read or update Shop A settings
    res = client.get(f"/api/v1/shops/{phase2_setup['shop_a_id']}/settings", headers=user_auth)
    assert res.status_code == 403

    res = client.patch(
        f"/api/v1/shops/{phase2_setup['shop_a_id']}/settings",
        json={"history_retention_hours": 1},
        headers=user_auth
    )
    assert res.status_code == 403


def test_agent_config_persistence():
    """
    Verifies that the Python Agent configuration persists history_retention_hours to file and reloads cleanly.
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        cfg_file = os.path.join(tmpdir, "test_config.json")
        cfg = AgentConfig(
            server_base_url="https://api.test.privprint.com/",
            shop_id="SHOP-TEST-RETENTION",
            history_retention_hours=6
        )
        cfg.save(cfg_file)

        # Reload configuration
        loaded = AgentConfig.load(cfg_file)
        assert loaded.shop_id == "SHOP-TEST-RETENTION"
        assert loaded.history_retention_hours == 6
