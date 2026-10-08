import pytest
from datetime import datetime, timezone, timedelta
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.entities import PrintJob
from app.models.enums import PrintJobStatus
from app.models.base import get_db_session
from app.services.cleanup_service import DocumentCleanupService


@pytest.fixture
def history_setup(client: TestClient):
    # 1. Register Operator A & Shop A
    op_a_res = client.post("/api/v1/auth/register", json={
        "email": "hist_op_a@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    op_a_token = op_a_res["access_token"]
    shop_a = client.post("/api/v1/shops", json={
        "name": "History Test Shop A",
        "address": "100 History Way",
        "history_retention_hours": 4
    }, headers={"Authorization": f"Bearer {op_a_token}"}).json()

    # 2. Register Operator B & Shop B
    op_b_res = client.post("/api/v1/auth/register", json={
        "email": "hist_op_b@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    op_b_token = op_b_res["access_token"]
    shop_b = client.post("/api/v1/shops", json={
        "name": "History Test Shop B",
        "address": "200 History Blvd",
        "history_retention_hours": 4
    }, headers={"Authorization": f"Bearer {op_b_token}"}).json()

    # 3. Register regular User
    user_res = client.post("/api/v1/auth/register", json={
        "email": "hist_user@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()
    user_token = user_res["access_token"]

    return {
        "op_a_token": op_a_token,
        "shop_a_id": shop_a["id"],
        "op_b_token": op_b_token,
        "shop_b_id": shop_b["id"],
        "user_token": user_token,
        "user_id": user_res["user"]["id"]
    }


def create_job(client: TestClient, user_token: str, shop_id: str, filename: str) -> str:
    u_auth = {"Authorization": f"Bearer {user_token}"}
    sess = client.post("/api/v1/sessions", json={"shop_id": shop_id}, headers=u_auth).json()
    doc = client.post("/api/v1/documents/init-upload", json={
        "session_id": sess["id"],
        "filename": filename,
        "file_size_bytes": 1024,
        "mime_type": "application/pdf",
        "sha256_hash": "a" * 64,
        "iv_hex": "0123456789abcdef0123456789abcdef",
        "key_fingerprint": "fp_history_01",
        "copies_authorized": 1
    }, headers=u_auth).json()
    client.post(f"/api/v1/documents/{doc['upload_id']}/complete-upload", json={
        "document_id": doc["document_id"],
        "session_id": sess["id"]
    }, headers=u_auth)
    job = client.post("/api/v1/jobs", json={
        "shop_id": shop_id,
        "session_id": sess["id"],
        "document_id": doc["document_id"],
        "requested_copies": 1
    }, headers=u_auth).json()
    return job["id"]


@pytest.mark.asyncio
async def test_history_cleanup_1_hour_retention(client: TestClient, history_setup, db_session: AsyncSession):
    """
    Test 1: Set retention = 1 hour. Completed history older than 1 hour is deleted.
    """
    op_auth = {"Authorization": f"Bearer {history_setup['op_a_token']}"}
    shop_id = history_setup["shop_a_id"]

    # Configure retention to 1 hour
    patch_res = client.patch(f"/api/v1/shops/{shop_id}/settings", json={"history_retention_hours": 1}, headers=op_auth)
    assert patch_res.status_code == 200

    # Create job and mark completed 75 minutes ago
    job_id = create_job(client, history_setup["user_token"], shop_id, "test_1h.pdf.enc")

    # Authorize & complete job
    client.post(f"/api/v1/jobs/{job_id}/authorize", headers={"Authorization": f"Bearer {history_setup['user_token']}"})
    client.post(f"/api/v1/print/jobs/{job_id}/start", headers=op_auth)
    client.post(f"/api/v1/print/jobs/{job_id}/increment-copy?delta=1", headers=op_auth)

    now_utc = datetime.now(timezone.utc)
    # Simulate completion 75 minutes ago
    job = await db_session.get(PrintJob, job_id)
    job.completed_at = now_utc - timedelta(minutes=75)
    await db_session.commit()

    # Trigger cleanup
    cleanup_res = client.post(f"/api/v1/cleanup/history/shop/{shop_id}", headers=op_auth)
    assert cleanup_res.status_code == 200
    data = cleanup_res.json()
    assert data["deleted_count"] == 1
    assert job_id in data["deleted_job_ids"]

    # Verify job no longer exists in database
    db_session.expire_all()
    job_check = await db_session.get(PrintJob, job_id)
    assert job_check is None


@pytest.mark.asyncio
async def test_history_cleanup_younger_than_retention_retained(client: TestClient, history_setup, db_session: AsyncSession):
    """
    Test 2 & 3: History younger than configured retention is retained (tested with 4h and 8h).
    """
    op_auth = {"Authorization": f"Bearer {history_setup['op_a_token']}"}
    shop_id = history_setup["shop_a_id"]

    # Set retention to 4 hours
    client.patch(f"/api/v1/shops/{shop_id}/settings", json={"history_retention_hours": 4}, headers=op_auth)

    job_id = create_job(client, history_setup["user_token"], shop_id, "test_young.pdf.enc")
    client.post(f"/api/v1/jobs/{job_id}/authorize", headers={"Authorization": f"Bearer {history_setup['user_token']}"})
    client.post(f"/api/v1/print/jobs/{job_id}/start", headers=op_auth)
    client.post(f"/api/v1/print/jobs/{job_id}/increment-copy?delta=1", headers=op_auth)

    now_utc = datetime.now(timezone.utc)
    # Simulate completion 2 hours ago (within 4h retention window)
    job = await db_session.get(PrintJob, job_id)
    job.completed_at = now_utc - timedelta(hours=2)
    await db_session.commit()

    cleanup_res = client.post(f"/api/v1/cleanup/history/shop/{shop_id}", headers=op_auth)
    assert cleanup_res.status_code == 200
    data = cleanup_res.json()
    assert data["deleted_count"] == 0
    assert data["retained_count"] >= 1

    # Verify job still exists in DB
    db_session.expire_all()
    job_check = await db_session.get(PrintJob, job_id)
    assert job_check is not None


@pytest.mark.asyncio
async def test_active_and_pending_jobs_never_deleted(client: TestClient, history_setup, db_session: AsyncSession):
    """
    Test 6 & 7: Active printing jobs and pending jobs older than retention period must NEVER be deleted.
    """
    op_auth = {"Authorization": f"Bearer {history_setup['op_a_token']}"}
    shop_id = history_setup["shop_a_id"]

    client.patch(f"/api/v1/shops/{shop_id}/settings", json={"history_retention_hours": 1}, headers=op_auth)

    # 1. Job in PRINTING state
    printing_job_id = create_job(client, history_setup["user_token"], shop_id, "printing.pdf.enc")
    client.post(f"/api/v1/jobs/{printing_job_id}/authorize", headers={"Authorization": f"Bearer {history_setup['user_token']}"})
    client.post(f"/api/v1/print/jobs/{printing_job_id}/start", headers=op_auth)

    # 2. Job in PENDING/CREATED state
    pending_job_id = create_job(client, history_setup["user_token"], shop_id, "pending.pdf.enc")

    now_utc = datetime.now(timezone.utc)
    # Artificially set created_at far in past
    p_job = await db_session.get(PrintJob, printing_job_id)
    p_job.created_at = now_utc - timedelta(hours=5)
    p_job.status = PrintJobStatus.PRINTING.value

    pend_job = await db_session.get(PrintJob, pending_job_id)
    pend_job.created_at = now_utc - timedelta(hours=5)
    pend_job.status = PrintJobStatus.QUEUED.value
    await db_session.commit()

    # Run cleanup
    cleanup_res = client.post(f"/api/v1/cleanup/history/shop/{shop_id}", headers=op_auth)
    assert cleanup_res.status_code == 200
    data = cleanup_res.json()
    assert printing_job_id not in data["deleted_job_ids"]
    assert pending_job_id not in data["deleted_job_ids"]

    # Verify both active jobs still exist in database
    db_session.expire_all()
    assert await db_session.get(PrintJob, printing_job_id) is not None
    assert await db_session.get(PrintJob, pending_job_id) is not None


@pytest.mark.asyncio
async def test_shop_isolation_during_history_cleanup(client: TestClient, history_setup, db_session: AsyncSession):
    """
    Test 8: Cleaning Shop A history must NEVER touch Shop B history.
    """
    op_a_auth = {"Authorization": f"Bearer {history_setup['op_a_token']}"}
    op_b_auth = {"Authorization": f"Bearer {history_setup['op_b_token']}"}
    shop_a_id = history_setup["shop_a_id"]
    shop_b_id = history_setup["shop_b_id"]

    # Both set retention to 1 hour
    client.patch(f"/api/v1/shops/{shop_a_id}/settings", json={"history_retention_hours": 1}, headers=op_a_auth)
    client.patch(f"/api/v1/shops/{shop_b_id}/settings", json={"history_retention_hours": 1}, headers=op_b_auth)

    job_a_id = create_job(client, history_setup["user_token"], shop_a_id, "job_a.pdf.enc")
    job_b_id = create_job(client, history_setup["user_token"], shop_b_id, "job_b.pdf.enc")

    # Complete both jobs
    client.post(f"/api/v1/jobs/{job_a_id}/authorize", headers={"Authorization": f"Bearer {history_setup['user_token']}"})
    client.post(f"/api/v1/print/jobs/{job_a_id}/start", headers=op_a_auth)
    client.post(f"/api/v1/print/jobs/{job_a_id}/increment-copy?delta=1", headers=op_a_auth)

    client.post(f"/api/v1/jobs/{job_b_id}/authorize", headers={"Authorization": f"Bearer {history_setup['user_token']}"})
    client.post(f"/api/v1/print/jobs/{job_b_id}/start", headers=op_b_auth)
    client.post(f"/api/v1/print/jobs/{job_b_id}/increment-copy?delta=1", headers=op_b_auth)

    now_utc = datetime.now(timezone.utc)
    # Set completion timestamp 2 hours ago for both
    ja = await db_session.get(PrintJob, job_a_id)
    ja.completed_at = now_utc - timedelta(hours=2)
    jb = await db_session.get(PrintJob, job_b_id)
    jb.completed_at = now_utc - timedelta(hours=2)
    await db_session.commit()

    # Operator A cleans Shop A only
    cleanup_res = client.post(f"/api/v1/cleanup/history/shop/{shop_a_id}", headers=op_a_auth)
    assert cleanup_res.status_code == 200
    data = cleanup_res.json()
    assert job_a_id in data["deleted_job_ids"]
    assert job_b_id not in data["deleted_job_ids"]

    # Verify Job A is deleted, Job B is intact
    db_session.expire_all()
    assert await db_session.get(PrintJob, job_a_id) is None
    assert await db_session.get(PrintJob, job_b_id) is not None


@pytest.mark.asyncio
async def test_dynamic_policy_reevaluation_on_retention_change(client: TestClient, history_setup, db_session: AsyncSession):
    """
    Test 9: Change retention from 8 hours to 1 hour. Existing eligible records are removed.
    """
    op_auth = {"Authorization": f"Bearer {history_setup['op_a_token']}"}
    shop_id = history_setup["shop_a_id"]

    # 1. Start with 8 hours retention
    client.patch(f"/api/v1/shops/{shop_id}/settings", json={"history_retention_hours": 8}, headers=op_auth)

    job_id = create_job(client, history_setup["user_token"], shop_id, "dynamic.pdf.enc")
    client.post(f"/api/v1/jobs/{job_id}/authorize", headers={"Authorization": f"Bearer {history_setup['user_token']}"})
    client.post(f"/api/v1/print/jobs/{job_id}/start", headers=op_auth)
    client.post(f"/api/v1/print/jobs/{job_id}/increment-copy?delta=1", headers=op_auth)

    now_utc = datetime.now(timezone.utc)
    # Completed 3 hours ago: under 8h retention, it is retained
    job = await db_session.get(PrintJob, job_id)
    job.completed_at = now_utc - timedelta(hours=3)
    await db_session.commit()

    # Cleanup with 8h retention -> retained
    res1 = client.post(f"/api/v1/cleanup/history/shop/{shop_id}", headers=op_auth)
    assert job_id not in res1.json()["deleted_job_ids"]

    # 2. Change retention to 1 hour
    client.patch(f"/api/v1/shops/{shop_id}/settings", json={"history_retention_hours": 1}, headers=op_auth)

    # Cleanup with 1h retention -> now eligible and deleted!
    res2 = client.post(f"/api/v1/cleanup/history/shop/{shop_id}", headers=op_auth)
    assert job_id in res2.json()["deleted_job_ids"]

    db_session.expire_all()
    assert await db_session.get(PrintJob, job_id) is None


@pytest.mark.asyncio
async def test_cleanup_idempotency_run_twice(client: TestClient, history_setup, db_session: AsyncSession):
    """
    Test 12: Run cleanup twice. Second run must execute cleanly without error and delete nothing.
    """
    op_auth = {"Authorization": f"Bearer {history_setup['op_a_token']}"}
    shop_id = history_setup["shop_a_id"]

    client.patch(f"/api/v1/shops/{shop_id}/settings", json={"history_retention_hours": 1}, headers=op_auth)

    job_id = create_job(client, history_setup["user_token"], shop_id, "idempotent.pdf.enc")
    client.post(f"/api/v1/jobs/{job_id}/authorize", headers={"Authorization": f"Bearer {history_setup['user_token']}"})
    client.post(f"/api/v1/print/jobs/{job_id}/start", headers=op_auth)
    client.post(f"/api/v1/print/jobs/{job_id}/increment-copy?delta=1", headers=op_auth)

    now_utc = datetime.now(timezone.utc)
    job = await db_session.get(PrintJob, job_id)
    job.completed_at = now_utc - timedelta(hours=2)
    await db_session.commit()

    # First run
    res1 = client.post(f"/api/v1/cleanup/history/shop/{shop_id}", headers=op_auth)
    assert res1.status_code == 200
    assert job_id in res1.json()["deleted_job_ids"]

    # Second run immediately
    res2 = client.post(f"/api/v1/cleanup/history/shop/{shop_id}", headers=op_auth)
    assert res2.status_code == 200
    assert res2.json()["deleted_count"] == 0
    assert res2.json()["deleted_job_ids"] == []
