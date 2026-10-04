import pytest
import time
from datetime import datetime, timedelta, timezone
from fastapi.testclient import TestClient
from app.models.entities import Device
from windows_agent.config import AgentConfig
from windows_agent.agent_service import WindowsAgentService
from windows_agent.realtime_client import WindowsAgentRealtimeClient, ConnectionState


def test_device_heartbeat_and_stale_detection_flow(client: TestClient):
    # 1. Register operator & create shop
    op_res = client.post("/api/v1/auth/register", json={
        "email": "op_heartbeat@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    op_token = op_res["access_token"]

    shop_res = client.post("/api/v1/shops", json={
        "name": "Heartbeat Test Shop",
        "address": "404 Spooler Road"
    }, headers={"Authorization": f"Bearer {op_token}"}).json()
    shop_id = shop_res["id"]

    # 2. Register Station Device
    dev_res = client.post("/api/v1/devices/register", json={
        "shop_id": shop_id,
        "name": "Windows Front Desk Station",
        "os_info": "Windows 11 Pro",
        "hardware_fingerprint": "HW-FP-TEST-001"
    }, headers={"Authorization": f"Bearer {op_token}"}).json()
    device_id = dev_res["device_id"]
    api_key = dev_res["api_key"]

    # 3. Send Heartbeat
    hb_res = client.post(
        f"/api/v1/devices/{device_id}/heartbeat",
        headers={"Authorization": f"Bearer {op_token}"}
    )
    assert hb_res.status_code == 200, hb_res.text
    hb_data = hb_res.json()
    assert hb_data["status"] == "ONLINE"
    assert "last_heartbeat_at" in hb_data

    time.sleep(0.05)

    # 4. Check Stale Detection with low threshold (0 seconds timeout)
    stale_res = client.post(
        "/api/v1/devices/check-stale?timeout_seconds=0",
        headers={"Authorization": f"Bearer {op_token}"}
    )
    assert stale_res.status_code == 200
    stale_data = stale_res.json()
    assert stale_data["marked_stale_count"] >= 1
    assert device_id in stale_data["stale_device_ids"]

    # Verify device state is now OFFLINE
    dev_info = client.get(
        f"/api/v1/devices/{device_id}",
        headers={"Authorization": f"Bearer {op_token}"}
    ).json()
    assert dev_info["status"] == "OFFLINE"

    # Heartbeat revives device to ONLINE
    hb_revive = client.post(
        f"/api/v1/devices/{device_id}/heartbeat",
        headers={"Authorization": f"Bearer {op_token}"}
    ).json()
    assert hb_revive["status"] == "ONLINE"


def test_duplicate_event_deduplication():
    config = AgentConfig(server_base_url="https://test.com", shop_id="SHOP-101")
    events_received = []

    def on_event(ev_type, ev_data):
        events_received.append((ev_type, ev_data))

    client = WindowsAgentRealtimeClient(config, on_event_callback=on_event)

    # First event
    assert client._is_duplicate_event("EVENT-UUID-001") is False
    # Duplicate of first event
    assert client._is_duplicate_event("EVENT-UUID-001") is True
    # Different event
    assert client._is_duplicate_event("EVENT-UUID-002") is False
    assert client._is_duplicate_event("EVENT-UUID-002") is True


@pytest.mark.asyncio
async def test_reconnect_reconciliation_sequence(tmp_path):
    config = AgentConfig(
        server_base_url="https://test.com",
        shop_id="SHOP-RECON-101",
        device_id="dev_win_recon_01"
    )
    service = WindowsAgentService(config)
    await service.initialize()

    # Trigger 7-step reconnect sequence
    await service.reconcile_on_reconnect()

    audit_types = [entry["eventType"] for entry in service.audit_log]
    assert "RECONNECT_SEQUENCE_START" in audit_types
    assert "RECONNECT_SEQUENCE_COMPLETE" in audit_types
