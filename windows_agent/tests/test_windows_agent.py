import os
import json
import pytest
import asyncio
import base64
import hashlib
import time
import httpx
from unittest.mock import AsyncMock
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from windows_agent.config import AgentConfig
from windows_agent.secure_store import SecureCredentialStore
from windows_agent.printer_spooler import PrinterSpoolerManager
from windows_agent.agent_service import WindowsAgentService
from windows_agent.api_client import WindowsAgentApiClient
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


def _test_token_with_expiration(expires_at):
    payload = base64.urlsafe_b64encode(
        json.dumps({"exp": expires_at}).encode("utf-8")
    ).decode("ascii").rstrip("=")
    return f"header.{payload}.signature"


def test_station_access_token_expiry_detection():
    assert WindowsAgentService._access_token_expires_soon(
        _test_token_with_expiration(time.time() + 60)
    )
    assert not WindowsAgentService._access_token_expires_soon(
        _test_token_with_expiration(time.time() + 600)
    )
    assert WindowsAgentService._access_token_expires_soon("malformed-token")


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

    private_key_pem = "encrypted-station-private-key"
    store.store_credentials(
        device_id="dev_win_test_123",
        api_key="ppdev_secret_key_99",
        access_token="acc_tok_111",
        refresh_token="ref_tok_222",
        station_private_key_pem=private_key_pem,
    )
    store.store_credentials(
        device_id="dev_win_test_123",
        api_key="ppdev_secret_key_99",
        access_token="acc_tok_333",
        refresh_token="ref_tok_444",
    )
    assert store.retrieve_credentials()["station_private_key_pem"] == private_key_pem

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
async def test_agent_service_initialization(agent_config, monkeypatch):
    service = WindowsAgentService(agent_config)
    monkeypatch.setattr(service.secure_store, "retrieve_credentials", lambda: None)
    await service.initialize()
    assert len(service.audit_log) >= 1
    assert service.audit_log[0]["eventType"] == "SYSTEM_INIT"

@pytest.mark.asyncio
async def test_job_spooling_workflow(agent_config):
    service = WindowsAgentService(agent_config)
    await service.initialize()
    service.config.device_id = "dev_win_test_123"

    job_id = "PRV-TEST-JOB-101"
    plaintext = b"%PDF-1.4\nPrivPrint encrypted print test\n%%EOF"
    aes_key = AESGCM.generate_key(bit_length=256)
    iv = b"123456789012"
    ciphertext = AESGCM(aes_key).encrypt(iv, plaintext, None)
    wrapped_key = service._station_private_key.public_key().encrypt(
        aes_key,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    service.api_client.get_print_queue = AsyncMock(return_value=[{
        "id": job_id,
        "shop_id": agent_config.shop_id,
        "document_id": "DOC-TEST-101",
        "status": "AUTHORIZED",
        "requested_copies": 1,
        "printer_id": "PRN-HP-01",
    }])
    service.api_client.get_print_content = AsyncMock(return_value={
        "ciphertext": ciphertext,
        "wrapped_key": base64.b64encode(wrapped_key).decode("ascii"),
        "iv": iv.hex(),
        "sha256": hashlib.sha256(ciphertext).hexdigest(),
        "filename": "test.pdf",
    })
    service.api_client.start_printing = AsyncMock(return_value={})
    service.api_client.increment_copy = AsyncMock(return_value={})
    service.api_client.execute_cleanup = AsyncMock(return_value={})
    service.api_client.fail_job = AsyncMock(return_value={})

    await service.process_print_job(job_id)

    assert len(service.job_history) == 1
    assert service.job_history[0]["id"] == job_id
    assert service.job_history[0]["status"] == "COMPLETED"
    assert "verification_status" in service.job_history[0]
    assert service.api_client.get_print_content.await_count == 1
    service.api_client.start_printing.assert_awaited_once_with(job_id)
    assert service.api_client.fail_job.await_count == 0


@pytest.mark.asyncio
async def test_missing_authorized_job_is_not_replaced_with_synthetic_document(agent_config):
    service = WindowsAgentService(agent_config)
    await service.initialize()
    service.config.device_id = "dev_win_test_123"
    service.api_client.get_print_queue = AsyncMock(return_value=[])
    service.api_client.get_print_content = AsyncMock()
    service.api_client.fail_job = AsyncMock(return_value={})

    await service.process_print_job("PRV-MISSING")

    assert service.job_history == []
    service.api_client.get_print_content.assert_not_awaited()
    service.api_client.fail_job.assert_awaited_once()


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


def test_dashboard_queue_api_returns_cloud_jobs(agent_config):
    service = WindowsAgentService(agent_config)
    service.config.access_token = "station-token"
    service.api_client.get_print_queue = AsyncMock(return_value=[{"id": "PRV-123", "status": "AUTHORIZED"}])
    init_dashboard(service)

    response = TestClient(dashboard_app).get("/api/queue")

    assert response.status_code == 200
    assert response.json() == [{"id": "PRV-123", "status": "AUTHORIZED"}]
    service.api_client.get_print_queue.assert_awaited_once_with(agent_config.shop_id)


def test_dashboard_queue_api_reports_backend_failure(agent_config):
    service = WindowsAgentService(agent_config)
    service.config.access_token = "station-token"
    service.api_client.get_print_queue = AsyncMock(side_effect=RuntimeError("backend unavailable"))
    init_dashboard(service)

    response = TestClient(dashboard_app).get("/api/queue")

    assert response.status_code == 502
    assert "backend unavailable" in response.json()["detail"]


def test_dashboard_printer_refresh_syncs_to_cloud(agent_config):
    service = WindowsAgentService(agent_config)
    service.config.access_token = "station-token"
    service.spooler.discover_local_printers = lambda: [{"id": "WIN-PRN-1", "name": "Xerox", "status": "ONLINE"}]
    service.api_client.sync_printers = AsyncMock(return_value=[{"id": "WIN-PRN-1"}])
    init_dashboard(service)

    response = TestClient(dashboard_app).post("/api/printers/refresh")

    assert response.status_code == 200
    assert response.json()["synced_count"] == 1
    service.api_client.sync_printers.assert_awaited_once_with(
        agent_config.shop_id,
        [{"id": "WIN-PRN-1", "name": "Xerox", "status": "ONLINE", "shop_id": agent_config.shop_id}],
    )


def test_dashboard_realtime_reconnect_route(agent_config):
    service = WindowsAgentService(agent_config)
    service.reconnect_realtime = AsyncMock()
    init_dashboard(service)

    response = TestClient(dashboard_app).post("/api/realtime/reconnect")

    assert response.status_code == 200
    assert response.json() == {"status": "reconnecting"}
    service.reconnect_realtime.assert_awaited_once()


@pytest.mark.asyncio
async def test_service_realtime_reconnect_replaces_running_task(agent_config):
    service = WindowsAgentService(agent_config)
    service.config.access_token = "station-token"
    service.realtime_client.start = AsyncMock()
    previous_task = asyncio.create_task(asyncio.sleep(60))
    service._realtime_task = previous_task

    await service.reconnect_realtime()
    await asyncio.sleep(0)

    assert previous_task.cancelled()
    assert service._realtime_task is not previous_task
    service._realtime_task.cancel()
    await asyncio.gather(service._realtime_task, return_exceptions=True)


@pytest.mark.asyncio
async def test_expired_station_token_falls_back_to_device_auth(agent_config, monkeypatch):
    service = WindowsAgentService(agent_config)
    monkeypatch.setattr(service.secure_store, "retrieve_credentials", lambda: None)
    monkeypatch.setattr(service.secure_store, "store_credentials", lambda **kwargs: None)
    await service.initialize()
    service.config.device_id = "dev_win_test"
    service.config.api_key = "device-key"
    service.config.access_token = "expired-access-token"
    service.config.refresh_token = "expired-refresh-token"
    service.api_client.refresh_token = "expired-refresh-token"
    service.api_client.refresh_auth_tokens = AsyncMock(side_effect=RuntimeError("refresh token expired"))
    async def authenticate_station(device_id, api_key):
        service.api_client.access_token = _test_token_with_expiration(time.time() + 900)
        service.api_client.refresh_token = "rotated-refresh-token"

    service.api_client.authenticate_device = AsyncMock(side_effect=authenticate_station)
    service.api_client.register_device_print_key = AsyncMock()

    await service.register_or_authenticate()

    service.api_client.authenticate_device.assert_awaited_once_with("dev_win_test", "device-key")
    assert service.config.refresh_token == "rotated-refresh-token"
    assert service.config.access_token == service.api_client.access_token
    assert service.encryption_key_registered is True


def test_api_client_surfaces_backend_error_message(agent_config):
    response = httpx.Response(
        500,
        json={"error": {"code": "INTERNAL_SERVER_ERROR", "message": "Printer sync failed"}},
    )

    with pytest.raises(RuntimeError, match="Printer sync failed"):
        WindowsAgentApiClient._raise_for_response(response)


def test_dashboard_email_password_setup_routes(agent_config):
    service = WindowsAgentService(agent_config)
    service.login_operator = AsyncMock(return_value=[
        {"id": "shop-test", "name": "Test Xerox Shop"}
    ])
    service.register_operator = AsyncMock(return_value=[
        {"id": "shop-new", "name": "New Xerox Shop"}
    ])
    service.connect_operator_shop = AsyncMock()
    init_dashboard(service)

    client = TestClient(dashboard_app)
    logged_in = client.post(
        "/api/auth/login",
        json={"email": "operator@example.com", "password": "Password123!"},
    )
    assert logged_in.status_code == 200
    assert logged_in.json()["shops"] == [{"id": "shop-test", "name": "Test Xerox Shop"}]
    service.login_operator.assert_awaited_once_with(
        "operator@example.com",
        "Password123!",
    )

    registered = client.post(
        "/api/auth/register",
        json={
            "email": "new-operator@example.com",
            "password": "Password123!",
            "full_name": "Shop Operator",
            "shop_name": "New Xerox Shop",
            "shop_address": "12 Main Market Road",
        },
    )
    assert registered.status_code == 200
    assert registered.json()["shops"] == [{"id": "shop-new", "name": "New Xerox Shop"}]
    service.register_operator.assert_awaited_once_with(
        "new-operator@example.com",
        "Password123!",
        "Shop Operator",
        "New Xerox Shop",
        "12 Main Market Road",
    )

    connected = client.post("/api/auth/connect", json={"shop_id": "shop-test"})
    assert connected.status_code == 200
    service.connect_operator_shop.assert_awaited_once_with("shop-test")


@pytest.mark.asyncio
async def test_operator_email_login_loads_owned_shops(agent_config):
    service = WindowsAgentService(agent_config)
    service.api_client.login_operator = AsyncMock(return_value={
        "access_token": "operator-token",
        "user": {"role": "SHOP_OPERATOR"},
    })
    service.api_client.list_operator_shops = AsyncMock(return_value=[
        {"id": "shop-test", "name": "Test Xerox Shop"},
    ])

    shops = await service.login_operator("operator@example.com", "Password123!")

    assert shops == [{"id": "shop-test", "name": "Test Xerox Shop"}]
    assert service._operator_token == "operator-token"
    service.api_client.list_operator_shops.assert_awaited_once_with("operator-token")


@pytest.mark.asyncio
async def test_operator_email_login_rejects_non_shop_role(agent_config):
    service = WindowsAgentService(agent_config)
    service.api_client.login_operator = AsyncMock(return_value={
        "access_token": "customer-token",
        "user": {"role": "USER"},
    })
    service.api_client.list_operator_shops = AsyncMock()

    with pytest.raises(RuntimeError, match="not registered as a Xerox shop operator"):
        await service.login_operator("customer@example.com", "Password123!")

    service.api_client.list_operator_shops.assert_not_awaited()


@pytest.mark.asyncio
async def test_connecting_shop_registers_station_key_before_starting(agent_config, monkeypatch):
    service = WindowsAgentService(agent_config)
    monkeypatch.setattr(service.secure_store, "retrieve_credentials", lambda: None)
    monkeypatch.setattr(service.secure_store, "store_credentials", lambda **kwargs: None)
    monkeypatch.setattr(service.config, "save", lambda: None)
    await service.initialize()
    service._operator_token = "operator-token"
    service._pending_operator_shops = {"shop-test": "Test Xerox Shop"}
    service.api_client.register_device = AsyncMock(return_value={
        "device_id": "dev_win_test_123",
        "api_key": "station-api-key",
    })
    service.api_client.authenticate_device = AsyncMock(return_value={
        "access_token": "station-access-token",
        "refresh_token": "station-refresh-token",
    })
    service.api_client.register_device_print_key = AsyncMock()
    service._start_authenticated_tasks = AsyncMock()

    await service.connect_operator_shop("shop-test")

    service.api_client.register_device_print_key.assert_awaited_once()
    args = service.api_client.register_device_print_key.await_args.args
    assert args[0] == "dev_win_test_123"
    assert base64.b64decode(args[1], validate=True)
    assert service.encryption_key_registered is True
    service._start_authenticated_tasks.assert_awaited_once()


@pytest.mark.asyncio
async def test_shop_connection_fails_if_station_key_cannot_register(agent_config, monkeypatch):
    service = WindowsAgentService(agent_config)
    monkeypatch.setattr(service.secure_store, "retrieve_credentials", lambda: None)
    monkeypatch.setattr(service.secure_store, "store_credentials", lambda **kwargs: None)
    monkeypatch.setattr(service.config, "save", lambda: None)
    await service.initialize()
    service._operator_token = "operator-token"
    service._pending_operator_shops = {"shop-test": "Test Xerox Shop"}
    service.api_client.register_device = AsyncMock(return_value={
        "device_id": "dev_win_test_123",
        "api_key": "station-api-key",
    })
    service.api_client.authenticate_device = AsyncMock(return_value={
        "access_token": "station-access-token",
        "refresh_token": "station-refresh-token",
    })
    service.api_client.register_device_print_key = AsyncMock(
        side_effect=RuntimeError("backend unavailable")
    )
    service._start_authenticated_tasks = AsyncMock()

    with pytest.raises(RuntimeError, match="backend unavailable"):
        await service.connect_operator_shop("shop-test")

    assert service.encryption_key_registered is False
    service._start_authenticated_tasks.assert_not_awaited()


@pytest.mark.asyncio
async def test_discovered_printer_sync_includes_required_shop_id(agent_config):
    service = WindowsAgentService(agent_config)
    service.config.access_token = "station-access-token"
    printers = [{
        "id": "PRN-LOCAL-01",
        "name": "Local Xerox",
        "model": "Xerox Model",
        "status": "READY",
    }]
    service.spooler.discover_local_printers = lambda: printers
    service.api_client.sync_printers = AsyncMock(return_value=[])

    await service.sync_discovered_printers()

    service.api_client.sync_printers.assert_awaited_once_with(
        agent_config.shop_id,
        [{**printers[0], "shop_id": agent_config.shop_id}],
    )
    assert service.audit_log[-1]["eventType"] == "PRINTERS_SYNCED"
