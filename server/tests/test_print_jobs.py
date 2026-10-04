import pytest
import pytest_asyncio
from datetime import datetime, timedelta, timezone
from fastapi.testclient import TestClient


@pytest_asyncio.fixture
async def test_setup(client: TestClient, admin_headers):
    # Register Customer User
    user = client.post("/api/v1/auth/register", json={
        "email": "job_user@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()

    # Register Shop A Operator
    op_a = client.post("/api/v1/auth/register", json={
        "email": "shop_a_op@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()

    # Register Shop B Operator
    op_b = client.post("/api/v1/auth/register", json={
        "email": "shop_b_op@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()

    # Create Shop A
    shop_a = client.post(
        "/api/v1/shops",
        json={"name": "Print Hub Alpha", "address": "100 Alpha Ave"},
        headers={"Authorization": f"Bearer {op_a['access_token']}"}
    ).json()

    # Create Shop B
    shop_b = client.post(
        "/api/v1/shops",
        json={"name": "Print Hub Beta", "address": "200 Beta Blvd"},
        headers={"Authorization": f"Bearer {op_b['access_token']}"}
    ).json()

    for shop in (shop_a, shop_b):
        approval = client.post(
            f"/api/v1/shops/{shop['id']}/approve",
            headers=admin_headers,
        )
        assert approval.status_code == 200, approval.text

    # User creates Ephemeral Session for Shop A
    sess_res = client.post(
        "/api/v1/sessions",
        json={"shop_id": shop_a["id"]},
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    session_a = sess_res.json()

    # Upload Document for User in Session A (authorized for 2 copies)
    init_res = client.post(
        "/api/v1/documents/init-upload",
        json={
            "session_id": session_a["id"],
            "filename": "lease_agreement.pdf.enc",
            "file_size_bytes": 2048,
            "mime_type": "application/pdf",
            "sha256_hash": "f" * 64,
            "iv_hex": "0123456789abcdef",
            "key_fingerprint": "fp_lease_1234",
            "copies_authorized": 2
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    ).json()

    doc_res = client.post(
        f"/api/v1/documents/{init_res['upload_id']}/complete-upload",
        json={"document_id": init_res["document_id"], "session_id": session_a["id"]},
        headers={"Authorization": f"Bearer {user['access_token']}"}
    ).json()

    return {
        "user": user,
        "op_a": op_a,
        "op_b": op_b,
        "shop_a": shop_a,
        "shop_b": shop_b,
        "session_a": session_a,
        "document": doc_res
    }


@pytest.mark.asyncio
async def test_normal_job_lifecycle_and_atomic_completion(client: TestClient, test_setup):
    user = test_setup["user"]
    op_a = test_setup["op_a"]
    shop_a = test_setup["shop_a"]
    session_a = test_setup["session_a"]
    doc = test_setup["document"]

    # 1. Create Job -> CREATED state
    create_res = client.post(
        "/api/v1/jobs",
        json={
            "shop_id": shop_a["id"],
            "session_id": session_a["id"],
            "document_id": doc["id"],
            "requested_copies": 2,
            "color_mode": "COLOR",
            "paper_size": "A4"
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert create_res.status_code == 201, create_res.text
    job = create_res.json()
    job_id = job["id"]

    assert job["user_id"] == user["user"]["id"]
    assert job["shop_id"] == shop_a["id"]
    assert job["session_id"] == session_a["id"]
    assert job["document_id"] == doc["id"]
    assert job["requested_copies"] == 2
    assert job["completed_copies"] == 0
    assert job["status"] == "CREATED"
    assert job["state"] == "CREATED"
    assert "created_at" in job
    assert "expires_at" in job

    # 2. Authorize Job -> AUTHORIZED state
    auth_res = client.post(
        f"/api/v1/jobs/{job_id}/authorize",
        json={"printer_id": "PRN-HP-01"},
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert auth_res.status_code == 200
    assert auth_res.json()["status"] == "AUTHORIZED"
    assert auth_res.json()["printer_id"] == "PRN-HP-01"

    # 3. Shop Spooler Starts Printing -> PRINTING state
    start_res = client.post(
        f"/api/v1/print/jobs/{job_id}/start",
        headers={"Authorization": f"Bearer {op_a['access_token']}"}
    )
    assert start_res.status_code == 200
    assert start_res.json()["status"] == "PRINTING"

    # 4. Atomic Increment Copy 1
    inc1_res = client.post(
        f"/api/v1/print/jobs/{job_id}/increment-copy?delta=1",
        headers={"Authorization": f"Bearer {op_a['access_token']}"}
    )
    assert inc1_res.status_code == 200
    job_inc1 = inc1_res.json()
    assert job_inc1["completed_copies"] == 1
    assert job_inc1["status"] == "PRINTING"

    # 5. Atomic Increment Copy 2 (Reaches authorized copies -> Auto-COMPLETED)
    inc2_res = client.post(
        f"/api/v1/print/jobs/{job_id}/increment-copy?delta=1",
        headers={"Authorization": f"Bearer {op_a['access_token']}"}
    )
    assert inc2_res.status_code == 200
    job_comp = inc2_res.json()
    assert job_comp["completed_copies"] == 2
    assert job_comp["status"] == "COMPLETED"
    assert job_comp["completed_at"] is not None


@pytest.mark.asyncio
async def test_duplicate_request_idempotency(client: TestClient, test_setup):
    user = test_setup["user"]
    shop_a = test_setup["shop_a"]
    session_a = test_setup["session_a"]
    doc = test_setup["document"]

    idempotency_key = "idem_key_unique_test_12345"

    # First request
    res1 = client.post(
        "/api/v1/jobs",
        json={
            "shop_id": shop_a["id"],
            "session_id": session_a["id"],
            "document_id": doc["id"],
            "requested_copies": 1,
            "idempotency_key": idempotency_key
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert res1.status_code == 201
    job1 = res1.json()

    # Second duplicate request with identical Idempotency-Key
    res2 = client.post(
        "/api/v1/jobs",
        json={
            "shop_id": shop_a["id"],
            "session_id": session_a["id"],
            "document_id": doc["id"],
            "requested_copies": 1,
            "idempotency_key": idempotency_key
        },
        headers={
            "Authorization": f"Bearer {user['access_token']}",
            "Idempotency-Key": idempotency_key
        }
    )
    assert res2.status_code in [200, 201]
    job2 = res2.json()

    # Both must return the exact same job ID without database record duplication
    assert job1["id"] == job2["id"]


@pytest.mark.asyncio
async def test_copy_limit_enforcement(client: TestClient, test_setup):
    user = test_setup["user"]
    shop_a = test_setup["shop_a"]
    session_a = test_setup["session_a"]
    doc = test_setup["document"]  # Has authorized_copies = 2

    # Attempt to request 5 copies (exceeds authorized 2)
    bad_req = client.post(
        "/api/v1/jobs",
        json={
            "shop_id": shop_a["id"],
            "session_id": session_a["id"],
            "document_id": doc["id"],
            "requested_copies": 5
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert bad_req.status_code == 400
    assert bad_req.json()["error"]["code"] == "COPY_LIMIT_REACHED"


@pytest.mark.asyncio
async def test_cancellation_workflow_and_terminal_protection(client: TestClient, test_setup):
    user = test_setup["user"]
    op_a = test_setup["op_a"]
    shop_a = test_setup["shop_a"]
    session_a = test_setup["session_a"]
    doc = test_setup["document"]

    # Create Job
    job = client.post(
        "/api/v1/jobs",
        json={
            "shop_id": shop_a["id"],
            "session_id": session_a["id"],
            "document_id": doc["id"],
            "requested_copies": 1
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    ).json()

    # Cancel Job from CREATED state
    cancel_res = client.post(
        f"/api/v1/jobs/{job['id']}/cancel",
        json={"reason": "Customer changed mind"},
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert cancel_res.status_code == 200
    assert cancel_res.json()["status"] == "CANCELLED"
    assert cancel_res.json()["failure_reason"] == "Customer changed mind"

    # Attempting to start printing a CANCELLED job -> 400 INVALID_STATE_TRANSITION
    start_bad = client.post(
        f"/api/v1/print/jobs/{job['id']}/start",
        headers={"Authorization": f"Bearer {op_a['access_token']}"}
    )
    assert start_bad.status_code == 400
    assert start_bad.json()["error"]["code"] == "INVALID_STATE_TRANSITION"


@pytest.mark.asyncio
async def test_expired_job_protection(client: TestClient, test_setup, db_session):
    from app.models.entities import PrintJob
    from app.repositories.print_job_repo import PrintJobRepository

    user = test_setup["user"]
    shop_a = test_setup["shop_a"]
    session_a = test_setup["session_a"]
    doc = test_setup["document"]

    # Create job
    job = client.post(
        "/api/v1/jobs",
        json={
            "shop_id": shop_a["id"],
            "session_id": session_a["id"],
            "document_id": doc["id"],
            "requested_copies": 1
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    ).json()

    # Manually expire the job in DB
    repo = PrintJobRepository(db_session)
    db_job = await repo.get_by_id(job["id"])
    db_job.expires_at = datetime.now(timezone.utc) - timedelta(minutes=5)
    await db_session.commit()

    # Attempt to authorize expired job -> 400 JOB_EXPIRED
    auth_bad = client.post(
        f"/api/v1/jobs/{job['id']}/authorize",
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert auth_bad.status_code == 400
    assert auth_bad.json()["error"]["code"] == "JOB_EXPIRED"


@pytest.mark.asyncio
async def test_wrong_shop_and_unauthorized_device_isolation(client: TestClient, test_setup):
    user = test_setup["user"]
    op_a = test_setup["op_a"]
    op_b = test_setup["op_b"]  # Different shop operator
    shop_a = test_setup["shop_a"]
    shop_b = test_setup["shop_b"]
    session_a = test_setup["session_a"]  # Session bound to Shop A
    doc = test_setup["document"]

    # 1. Attempt to create job for Shop B using Session bound to Shop A -> 400 Validation Error
    mismatch_res = client.post(
        "/api/v1/jobs",
        json={
            "shop_id": shop_b["id"],
            "session_id": session_a["id"],
            "document_id": doc["id"],
            "requested_copies": 1
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    )
    assert mismatch_res.status_code == 400
    assert mismatch_res.json()["error"]["code"] == "VALIDATION_ERROR"

    # 2. Create valid job for Shop A
    job = client.post(
        "/api/v1/jobs",
        json={
            "shop_id": shop_a["id"],
            "session_id": session_a["id"],
            "document_id": doc["id"],
            "requested_copies": 1
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    ).json()

    # 3. Shop B Operator/Device attempts to view or start Shop A's job -> 403 FORBIDDEN
    bad_spool = client.post(
        f"/api/v1/print/jobs/{job['id']}/start",
        headers={"Authorization": f"Bearer {op_b['access_token']}"}
    )
    assert bad_spool.status_code == 403
    assert bad_spool.json()["error"]["code"] == "FORBIDDEN"


@pytest.mark.asyncio
async def test_direct_completed_state_change_prevention(client: TestClient, test_setup):
    user = test_setup["user"]
    shop_a = test_setup["shop_a"]
    session_a = test_setup["session_a"]
    doc = test_setup["document"]

    # 1. Check that JobCreateRequest has no status field allowed to set COMPLETED directly
    job = client.post(
        "/api/v1/jobs",
        json={
            "shop_id": shop_a["id"],
            "session_id": session_a["id"],
            "document_id": doc["id"],
            "requested_copies": 1,
            "status": "COMPLETED"  # Malicious attempt to force completed
        },
        headers={"Authorization": f"Bearer {user['access_token']}"}
    ).json()

    # The authoritative state machine must ignore client input and enforce CREATED
    assert job["status"] == "CREATED"
