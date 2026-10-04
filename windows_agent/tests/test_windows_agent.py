import os
import json
import pytest
import asyncio
from unittest.mock import AsyncMock
from windows_agent.config import AgentConfig
from windows_agent.secure_store import SecureCredentialStore
from windows_agent.printer_spooler import PrinterSpoolerManager
from windows_agent.agent_service import WindowsAgentService
from windows_agent.realtime_client import WindowsAgentRealtimeClient, ConnectionState
from windows_agent.dashboard import dashboard_app, init_dashboard
from fastapi.testclient import TestClient

@pytest.fixture
def agent_config(tmp_path):
    config_file = str(tmp_path / "test_config.json")
    config = AgentConfig(
        server_base_url="https://ais-dev-6u62dc37mqabbjyehi6umo-408539472511.asia-southeast1.run.app/",
        shop_id="SHOP-101",
        device_name="Test Windows Station Agent"
    )
    config.save(config_file)
    return config

def test_config_load_and_save(tmp_path):
    config_path = str(tmp_path / "cfg.json")
    cfg = AgentConfig(server_base_url="https://test.com", shop_id="SHOP-TEST")
    cfg.save(config_path)

    loaded = AgentConfig.load(config_path)
    assert loaded.server_base_url == "https://test.com"
    assert loaded.shop_id == "SHOP-TEST"
    stored_config = json.loads(open(config_path, encoding="utf-8").read())
    assert not {"api_key", "access_token", "refresh_token", "device_id"}.intersection(stored_config)


def test_station_config_defaults_to_render_without_assuming_shop():
    config = AgentConfig()
    assert config.server_base_url == "https://secure-print-1.onrender.com/"
    assert config.shop_id == ""


def test_realtime_url_never_puts_access_token_in_query():
    config = AgentConfig(access_token="test-access-token")
    client = WindowsAgentRealtimeClient(config)
    assert client._get_ws_url() == "wss://secure-print-1.onrender.com/api/v1/realtime/ws"
    assert "test-access-token" not in client._get_ws_url()


@pytest.mark.asyncio
async def test_realtime_does_not_connect_without_station_credentials():
    client = WindowsAgentRealtimeClient(AgentConfig())
    await client.start()
    assert client.state == ConnectionState.ERROR

def test_secure_credential_store(tmp_path):
    store_path = str(tmp_path / "sec.dat")
    store = SecureCredentialStore(storage_path=store_path)

    store.store_credentials(
        device_id="dev_win_test_123",
        api_key="ppdev_secret_key_99",
        access_token="acc_tok_111",
        refresh_token="ref_tok_222"
    )

    retrieved = store.retrieve_credentials()
    assert retrieved is not None
    assert retrieved["device_id"] == "dev_win_test_123"
    assert retrieved["api_key"] == "ppdev_secret_key_99"
    assert retrieved["access_token"] == "acc_tok_111"

    store.clear()
    assert store.retrieve_credentials() is None

def test_printer_spooler_discovery():
    spooler = PrinterSpoolerManager()
    printers = spooler.discover_local_printers()
    assert len(printers) >= 1

    # On machines with real hardware the local spooler fleet is authoritative;
    # without hardware the verified simulated fleet is reported. Either way
    # every record must be a complete, cloud-syncable printer description.
    required_keys = {"id", "name", "driver_name", "connection_info", "is_default", "status"}
    assert all(required_keys.issubset(set(p.keys())) for p in printers)

    printer = spooler.get_printer_by_id("PRN-HP-01")
    assert printer is not None
    assert printer["is_online"] is True
    assert printer["status"] in ("READY", "BUSY", "OFFLINE", "ERROR", "UNKNOWN")
    assert "driver_name" in printer
    assert "connection_info" in printer
    assert "is_default" in printer


def test_printer_status_mapping_exact_states():
    spooler = PrinterSpoolerManager()
    
    # Ready
    assert spooler._map_windows_status(0, 0) == "READY"
    
    # Offline
    assert spooler._map_windows_status(0x00000080, 0) == "OFFLINE"
    assert spooler._map_windows_status(0, 0x00000400) == "OFFLINE"
    
    # Error (Paper Jam, No Toner, Error)
    assert spooler._map_windows_status(0x00000008, 0) == "ERROR"
    assert spooler._map_windows_status(0x00000040, 0) == "ERROR"
    assert spooler._map_windows_status(0x00040000, 0) == "ERROR"
    
    # Busy (Printing, Busy, Processing)
    assert spooler._map_windows_status(0x00000400, 0) == "BUSY"
    assert spooler._map_windows_status(0x00000200, 0) == "BUSY"
    assert spooler._map_windows_status(0x00004000, 0) == "BUSY"


@pytest.mark.asyncio
async def test_agent_service_initialization(agent_config):
    service = WindowsAgentService(agent_config)
    await service.initialize()
    assert len(service.audit_log) >= 1
    assert service.audit_log[0]["eventType"] == "SYSTEM_INIT"

@pytest.mark.asyncio
async def test_job_spooling_workflow(agent_config):
    service = WindowsAgentService(agent_config)
    await service.initialize()

    job_id = "PRV-TEST-JOB-101"
    await service.process_print_job(job_id)

    assert len(service.job_history) == 1
    assert service.job_history[0]["jobId"] == job_id
    assert service.job_history[0]["status"] == "COMPLETED"
    assert "verification_status" in service.job_history[0]


def test_spooler_state_transitions_and_tracking():
    spooler = PrinterSpoolerManager()
    states_observed = []

    def callback(copy_num, total, state):
        states_observed.append(state)

    payload = b"%PDF-1.4 sample verified doc payload"
    record = spooler.print_document("PRN-HP-01", payload, "test.pdf", copies=2, progress_callback=callback)

    assert record.status == "COMPLETED"
    assert record.copies_printed == 2
    assert "SUBMITTED" in states_observed
    assert "PRINTING" in states_observed
    assert "COMPLETED" in states_observed


def test_spooler_offline_error_handling():
    spooler = PrinterSpoolerManager()
    with pytest.raises(PrinterSpoolerManager.__module__ and Exception) as exc_info:
        spooler.print_document("PRN-EPS-04", b"dummy bytes", "test.pdf")
    assert "OFFLINE" in str(exc_info.value) or "offline" in str(exc_info.value).lower()


def test_spooler_removed_printer_error_handling():
    spooler = PrinterSpoolerManager()
    with pytest.raises(Exception) as exc_info:
        spooler.print_document("PRN-NON-EXISTENT-999", b"dummy bytes", "test.pdf")
    assert "not found" in str(exc_info.value) or "removed" in str(exc_info.value).lower()


def test_spooler_invalid_empty_document_handling():
    spooler = PrinterSpoolerManager()
    with pytest.raises(Exception) as exc_info:
        spooler.print_document("PRN-HP-01", b"", "empty.pdf")
    assert "empty" in str(exc_info.value) or "invalid" in str(exc_info.value).lower()


def test_dashboard_api_status(agent_config):
    service = WindowsAgentService(agent_config)
    init_dashboard(service)

    client = TestClient(dashboard_app)
    response = client.get("/api/status")
    assert response.status_code == 200
    data = response.json()
    assert data["shop_id"] == "SHOP-101"
    assert "printers" in data
    assert "audit_log" in data
    assert data["authenticated"] is False
    assert data["server_base_url"] == agent_config.server_base_url


def test_dashboard_operator_otp_setup_routes(agent_config, monkeypatch):
    service = WindowsAgentService(agent_config)
    service.request_operator_otp = AsyncMock()
    service.verify_operator_otp = AsyncMock(return_value=[
        {"id": "shop-test", "name": "Test Xerox Shop"}
    ])
    service.connect_operator_shop = AsyncMock()
    init_dashboard(service)

    client = TestClient(dashboard_app)
    requested = client.post(
        "/api/auth/request-otp",
        json={"phone_number": "+919876543210"},
    )
    assert requested.status_code == 200
    service.request_operator_otp.assert_awaited_once_with("+919876543210")

    verified = client.post(
        "/api/auth/verify-otp",
        json={"phone_number": "+919876543210", "otp": "123456"},
    )
    assert verified.status_code == 200
    assert verified.json()["shops"] == [{"id": "shop-test", "name": "Test Xerox Shop"}]

    connected = client.post("/api/auth/connect", json={"shop_id": "shop-test"})
    assert connected.status_code == 200
    service.connect_operator_shop.assert_awaited_once_with("shop-test")
