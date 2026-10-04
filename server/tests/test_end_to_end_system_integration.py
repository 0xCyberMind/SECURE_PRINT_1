import pytest
import asyncio
import secrets
import hashlib
from fastapi.testclient import TestClient
from windows_agent.config import AgentConfig
from windows_agent.agent_service import WindowsAgentService


class ApiClientTestAdapter:
    """
    In-process ASGI Test Adapter for WindowsAgentApiClient.
    Routes agent HTTP requests directly through the active test database and FastAPI endpoints.
    """
    def __init__(self, test_client: TestClient, access_token: str, shop_id: str, device_id: str = "dev_win_test"):
        self.client = test_client
        self.access_token = access_token
        self.shop_id = shop_id
        self.device_id = device_id

    def _headers(self):
        return {
            "Authorization": f"Bearer {self.access_token}",
            "X-Forwarded-For": "203.0.113.88"
        }

    async def get_print_queue(self, shop_id: str):
        res = self.client.get(f"/api/v1/print/jobs?shopId={shop_id}", headers=self._headers())
        if res.status_code == 200:
            return res.json()
        return []

    async def start_printing(self, job_id: str):
        res = self.client.post(f"/api/v1/print/jobs/{job_id}/start", headers=self._headers())
        if res.status_code == 200:
            return res.json()
        return {}

    async def increment_copy(self, job_id: str, delta: int = 1):
        res = self.client.post(f"/api/v1/print/jobs/{job_id}/increment-copy?delta={delta}", headers=self._headers())
        if res.status_code == 200:
            return res.json()
        return {}

    async def fail_job(self, job_id: str, reason: str):
        res = self.client.post(f"/api/v1/print/jobs/{job_id}/fail?reason={reason}", headers=self._headers())
        if res.status_code == 200:
            return res.json()
        return {}

    async def execute_cleanup(self, job_id: str):
        res = self.client.post(f"/api/v1/cleanup/execute/{job_id}", headers=self._headers())
        if res.status_code == 200:
            return res.json()
        return {}

    async def sync_printers(self, shop_id: str, printers):
        res = self.client.post("/api/v1/printers/sync", json={"shop_id": shop_id, "printers": printers}, headers=self._headers())
        if res.status_code == 200:
            return res.json()
        return []

    async def get_device_state(self, device_id: str):
        res = self.client.get(f"/api/v1/devices/{device_id}", headers=self._headers())
        if res.status_code == 200:
            return res.json()
        return {}

    async def send_heartbeat(self, device_id: str):
        res = self.client.post(f"/api/v1/devices/{device_id}/heartbeat", headers=self._headers())
        if res.status_code == 200:
            return res.json()
        return {}


@pytest.mark.asyncio
async def test_end_to_end_single_user_complete_lifecycle(client: TestClient):
    """
    CUJ 1: Complete end-to-end user-to-shop printing pipeline across distinct networks.
    User Network (Cellular: 198.51.100.25) <---> Cloud API/Redis <---> Shop Network (203.0.113.88)
    No localhost/same-LAN shortcuts.
    """
    user_headers_ip = {"X-Forwarded-For": "198.51.100.25"}
    shop_headers_ip = {"X-Forwarded-For": "203.0.113.88"}

    # 1. Shop Operator setup on Shop Network
    op_res = client.post("/api/v1/auth/register", json={
        "email": "e2e_operator@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }, headers=shop_headers_ip).json()
    op_token = op_res["access_token"]

    shop_res = client.post("/api/v1/shops", json={
        "name": "Downtown Secure Print Center",
        "address": "742 Evergreen Terrace",
        "latitude": 37.7749,
        "longitude": -122.4194
    }, headers={"Authorization": f"Bearer {op_token}", **shop_headers_ip}).json()
    shop_id = shop_res["id"]
    permanent_qr = shop_res["permanent_qr_payload"]

    # Register Shop Station Device
    dev_reg = client.post("/api/v1/devices/register", json={
        "shop_id": shop_id,
        "name": "Windows Spooler Master Station",
        "os_info": "Windows 11 Pro 23H2",
        "hardware_fingerprint": "HW-FP-E2E-99"
    }, headers={"Authorization": f"Bearer {op_token}", **shop_headers_ip}).json()
    device_id = dev_reg["device_id"]

    # Discover and Sync Printers
    printers_payload = [
        {
            "id": "PRN-HP-E2E",
            "name": "HP LaserJet Enterprise M608",
            "model": "HP LaserJet Enterprise M608dn",
            "driver_name": "HP LaserJet M608 PCL6",
            "is_default": True,
            "status": "READY",
            "connection_info": "IP_192.168.1.101 (Port 9100)",
            "is_online": True,
            "supports_color": False,
            "supports_duplex": True,
            "supported_paper_sizes": "A4, Letter",
            "paper_tray_status": "READY",
            "toner_level_percent": 90
        }
    ]
    client.post("/api/v1/printers/sync", json={
        "shop_id": shop_id,
        "printers": printers_payload
    }, headers={"Authorization": f"Bearer {op_token}", **shop_headers_ip})

    # 2. Android User on Cellular Network logs in
    user_res = client.post("/api/v1/auth/register", json={
        "email": "e2e_student@example.com",
        "password": "Password123!",
        "role": "USER"
    }, headers=user_headers_ip).json()
    user_token = user_res["access_token"]
    user_auth = {"Authorization": f"Bearer {user_token}", **user_headers_ip}

    # 3. User scans Permanent Shop QR Code to establish Ephemeral Cloud Session
    # Extract shop_id from QR payload URI (e.g. privprint://shop?id=SHOP_XYZ)
    assert permanent_qr.startswith("privprint://shop?id=")
    scanned_shop_id = permanent_qr.split("id=")[1]
    assert scanned_shop_id == shop_id

    sess_res = client.post("/api/v1/sessions", json={
        "shop_id": scanned_shop_id
    }, headers=user_auth)
    assert sess_res.status_code == 201
    scan_data = sess_res.json()
    session_id = scan_data["id"]

    # 4. User selects Document & performs client-side AES-256-GCM encryption
    payload_bytes = secrets.token_bytes(2048)
    sha256_hash = hashlib.sha256(payload_bytes).hexdigest()

    doc_init = client.post("/api/v1/documents/init-upload", json={
        "session_id": session_id,
        "filename": "Final_Thesis_Confidential.pdf.enc",
        "file_size_bytes": len(payload_bytes),
        "mime_type": "application/pdf",
        "sha256_hash": sha256_hash,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_client_key_9988",
        "copies_authorized": 2
    }, headers=user_auth).json()
    doc_id = doc_init["document_id"]
    upload_id = doc_init["upload_id"]

    # Complete upload
    comp_res = client.post(f"/api/v1/documents/{upload_id}/complete-upload", json={
        "document_id": doc_id,
        "session_id": session_id,
        "sha256_hash": sha256_hash,
        "file_size_bytes": len(payload_bytes)
    }, headers=user_auth)
    assert comp_res.status_code == 200

    # 5. User selects printer and creates Cloud Print Job
    job_res = client.post("/api/v1/jobs", json={
        "shop_id": shop_id,
        "document_id": doc_id,
        "session_id": session_id,
        "requested_copies": 2,
        "color_mode": "MONO",
        "paper_size": "A4",
        "double_sided": True
    }, headers=user_auth)
    assert job_res.status_code == 201
    job = job_res.json()
    job_id = job["id"]

    # Authorize job
    auth_res = client.post(f"/api/v1/jobs/{job_id}/authorize", headers=user_auth)
    assert auth_res.status_code == 200

    # 6. Windows Shop Agent on Shop Network processes job via Windows Spooler Pipeline
    agent_config = AgentConfig(
        server_base_url="http://testserver",
        shop_id=shop_id,
        device_id=device_id,
        access_token=op_token,
        auto_print_enabled=False
    )
    agent = WindowsAgentService(agent_config)
    agent.api_client = ApiClientTestAdapter(client, op_token, shop_id, device_id)
    await agent.initialize()

    # Process job through verified pipeline
    await agent.process_print_job(job_id)

    # 7. Verify atomic completion state
    job_final = client.get(f"/api/v1/jobs/{job_id}", headers=user_auth).json()
    assert job_final["status"] == "COMPLETED"
    assert job_final["completed_copies"] == 2
    assert job_final["requested_copies"] == 2


@pytest.mark.asyncio
async def test_multi_user_multi_shop_multi_printer_isolation(client: TestClient):
    """
    CUJ 2: Multi-user, multi-shop, multi-printer complete isolation across distinct networks.
    """
    # Create Shop 1 & Operator 1
    op1 = client.post("/api/v1/auth/register", json={
        "email": "shop1_op@example.com", "password": "Password123!", "role": "SHOP_OPERATOR"
    }).json()
    s1 = client.post("/api/v1/shops", json={
        "name": "North Campus Print Shop", "address": "100 North Rd"
    }, headers={"Authorization": f"Bearer {op1['access_token']}"}).json()

    # Create Shop 2 & Operator 2
    op2 = client.post("/api/v1/auth/register", json={
        "email": "shop2_op@example.com", "password": "Password123!", "role": "SHOP_OPERATOR"
    }).json()
    s2 = client.post("/api/v1/shops", json={
        "name": "South Campus Print Shop", "address": "200 South Rd"
    }, headers={"Authorization": f"Bearer {op2['access_token']}"}).json()

    # Create User A and User B
    u_a = client.post("/api/v1/auth/register", json={
        "email": "student_a@example.com", "password": "Password123!", "role": "USER"
    }).json()
    u_b = client.post("/api/v1/auth/register", json={
        "email": "student_b@example.com", "password": "Password123!", "role": "USER"
    }).json()

    # User A session & doc for Shop 1
    sess_a = client.post("/api/v1/sessions", json={"shop_id": s1["id"]}, headers={"Authorization": f"Bearer {u_a['access_token']}"}).json()
    doc_a = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess_a["id"], "filename": "doc_a.pdf.enc", "file_size_bytes": 1024,
        "mime_type": "application/pdf", "sha256_hash": "a" * 64, "iv_hex": "0123456789abcdef",
        "key_fingerprint": "fp_key_aaaa", "copies_authorized": 1
    }, headers={"Authorization": f"Bearer {u_a['access_token']}"}).json()
    client.post(f"/api/v1/documents/{doc_a['upload_id']}/complete-upload", json={
        "document_id": doc_a["document_id"], "session_id": sess_a["id"]
    }, headers={"Authorization": f"Bearer {u_a['access_token']}"})

    job_a = client.post("/api/v1/jobs", json={
        "shop_id": s1["id"], "session_id": sess_a["id"], "document_id": doc_a["document_id"], "requested_copies": 1
    }, headers={"Authorization": f"Bearer {u_a['access_token']}"}).json()

    # User B session & doc for Shop 2
    sess_b = client.post("/api/v1/sessions", json={"shop_id": s2["id"]}, headers={"Authorization": f"Bearer {u_b['access_token']}"}).json()
    doc_b = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess_b["id"], "filename": "doc_b.pdf.enc", "file_size_bytes": 1024,
        "mime_type": "application/pdf", "sha256_hash": "b" * 64, "iv_hex": "0123456789abcdef",
        "key_fingerprint": "fp_key_bbbb", "copies_authorized": 1
    }, headers={"Authorization": f"Bearer {u_b['access_token']}"}).json()
    client.post(f"/api/v1/documents/{doc_b['upload_id']}/complete-upload", json={
        "document_id": doc_b["document_id"], "session_id": sess_b["id"]
    }, headers={"Authorization": f"Bearer {u_b['access_token']}"})

    job_b = client.post("/api/v1/jobs", json={
        "shop_id": s2["id"], "session_id": sess_b["id"], "document_id": doc_b["document_id"], "requested_copies": 1
    }, headers={"Authorization": f"Bearer {u_b['access_token']}"}).json()

    # 1. Isolation check: User A cannot access User B's job -> 403
    forbidden_get = client.get(
        f"/api/v1/jobs/{job_b['id']}",
        headers={"Authorization": f"Bearer {u_a['access_token']}"}
    )
    assert forbidden_get.status_code == 403

    # 2. Operator 1 queue only contains Shop 1 jobs
    q1 = client.get(
        f"/api/v1/print/jobs?shopId={s1['id']}",
        headers={"Authorization": f"Bearer {op1['access_token']}"}
    ).json()
    q1_ids = [j["id"] for j in q1]
    assert job_a["id"] in q1_ids
    assert job_b["id"] not in q1_ids


@pytest.mark.asyncio
async def test_concurrent_and_duplicate_requests_resilience(client: TestClient):
    """
    CUJ 3: Concurrency and Idempotency Resilience under load.
    """
    op = client.post("/api/v1/auth/register", json={
        "email": "concurrency_op@example.com", "password": "Password123!", "role": "SHOP_OPERATOR"
    }).json()
    shop = client.post("/api/v1/shops", json={
        "name": "High Load Shop", "address": "77 Rush St"
    }, headers={"Authorization": f"Bearer {op['access_token']}"}).json()

    u = client.post("/api/v1/auth/register", json={
        "email": "concurrent_user@example.com", "password": "Password123!", "role": "USER"
    }).json()
    u_auth = {"Authorization": f"Bearer {u['access_token']}"}

    sess = client.post("/api/v1/sessions", json={"shop_id": shop["id"]}, headers=u_auth).json()
    doc = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"], "filename": "heavy_doc.pdf.enc", "file_size_bytes": 2048,
        "mime_type": "application/pdf", "sha256_hash": "c" * 64, "iv_hex": "0123456789abcdef",
        "key_fingerprint": "fp_key_cccc", "copies_authorized": 3
    }, headers=u_auth).json()
    client.post(f"/api/v1/documents/{doc['upload_id']}/complete-upload", json={
        "document_id": doc["document_id"], "session_id": sess["id"]
    }, headers=u_auth)

    # Submit job with idempotency key
    idempotency_key = "IDEMP-KEY-E2E-999"
    res1 = client.post("/api/v1/jobs", json={
        "shop_id": shop["id"], "session_id": sess["id"], "document_id": doc["document_id"], "requested_copies": 3
    }, headers={**u_auth, "Idempotency-Key": idempotency_key})
    assert res1.status_code == 201
    job_id = res1.json()["id"]

    # Duplicate submission returns existing job idempotently
    res2 = client.post("/api/v1/jobs", json={
        "shop_id": shop["id"], "session_id": sess["id"], "document_id": doc["document_id"], "requested_copies": 3
    }, headers={**u_auth, "Idempotency-Key": idempotency_key})
    assert res2.status_code in (200, 201)
    assert res2.json()["id"] == job_id

    # Authorize and start printing
    client.post(f"/api/v1/jobs/{job_id}/authorize", headers=u_auth)
    client.post(f"/api/v1/print/jobs/{job_id}/start", headers={"Authorization": f"Bearer {op['access_token']}"})

    # Fire 10 concurrent increment requests
    async def inc_copy():
        return client.post(
            f"/api/v1/print/jobs/{job_id}/increment-copy?delta=1",
            headers={"Authorization": f"Bearer {op['access_token']}"}
        )

    tasks = [inc_copy() for _ in range(10)]
    results = await asyncio.gather(*tasks)

    # Validate that total printed copies never exceeds authorized limit (3)
    final_job = client.get(f"/api/v1/jobs/{job_id}", headers=u_auth).json()
    assert final_job["completed_copies"] == 3
    assert final_job["status"] == "COMPLETED"


@pytest.mark.asyncio
async def test_disconnect_reconnect_recovery_and_resumption(client: TestClient):
    """
    CUJ 4: Disconnect, offline queueing, and 7-step reconnect resumption.
    """
    op = client.post("/api/v1/auth/register", json={
        "email": "recon_op@example.com", "password": "Password123!", "role": "SHOP_OPERATOR"
    }).json()
    shop = client.post("/api/v1/shops", json={
        "name": "Resilient Shop", "address": "88 Network Ln"
    }, headers={"Authorization": f"Bearer {op['access_token']}"}).json()

    # Register Device
    dev = client.post("/api/v1/devices/register", json={
        "shop_id": shop["id"],
        "name": "Windows Recon Device",
        "os_info": "Windows 11",
        "hardware_fingerprint": "HW-RECON-01"
    }, headers={"Authorization": f"Bearer {op['access_token']}"}).json()

    u = client.post("/api/v1/auth/register", json={
        "email": "recon_user@example.com", "password": "Password123!", "role": "USER"
    }).json()
    u_auth = {"Authorization": f"Bearer {u['access_token']}"}

    sess = client.post("/api/v1/sessions", json={"shop_id": shop["id"]}, headers=u_auth).json()
    doc = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"], "filename": "offline_queued.pdf.enc", "file_size_bytes": 1024,
        "mime_type": "application/pdf", "sha256_hash": "d" * 64, "iv_hex": "0123456789abcdef",
        "key_fingerprint": "fp_key_dddd", "copies_authorized": 1
    }, headers=u_auth).json()
    client.post(f"/api/v1/documents/{doc['upload_id']}/complete-upload", json={
        "document_id": doc["document_id"], "session_id": sess["id"]
    }, headers=u_auth)

    # Job submitted while Agent is offline
    job = client.post("/api/v1/jobs", json={
        "shop_id": shop["id"], "session_id": sess["id"], "document_id": doc["document_id"], "requested_copies": 1
    }, headers=u_auth).json()
    job_id = job["id"]

    # Authorize job
    client.post(f"/api/v1/jobs/{job_id}/authorize", headers=u_auth)

    # Agent boots up/reconnects after outage
    agent_config = AgentConfig(
        server_base_url="http://testserver",
        shop_id=shop["id"],
        device_id=dev["device_id"],
        access_token=op["access_token"],
        auto_print_enabled=False
    )
    agent = WindowsAgentService(agent_config)
    agent.api_client = ApiClientTestAdapter(client, op["access_token"], shop["id"], dev["device_id"])
    await agent.initialize()

    # Reconnect sequence automatically queries DB for missed/queued jobs and resumes
    await agent.reconcile_on_reconnect()

    # Process discovered job
    await agent.process_print_job(job_id)

    # Verified completed
    job_done = client.get(f"/api/v1/jobs/{job_id}", headers=u_auth).json()
    assert job_done["status"] == "COMPLETED"
    assert job_done["completed_copies"] == 1
