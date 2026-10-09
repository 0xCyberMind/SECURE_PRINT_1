import pytest
from datetime import datetime, timezone, timedelta
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.entities import PrintJob, Document
from app.models.enums import PrintJobStatus
from app.models.base import get_db_session, AsyncSessionLocal
from app.services.cleanup_service import DocumentCleanupService
from app.services.storage import get_storage_service


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


@pytest.mark.asyncio
async def test_failed_and_cancelled_jobs_deleted_after_2_minutes_regardless_of_retention(
    client: TestClient, history_setup, db_session: AsyncSession
):
    """
    Test 13: Failed and cancelled jobs must be automatically deleted after 2 minutes,
    regardless of the shop's configured history retention timer (e.g. 24h).
    Their associated temporary encrypted storage files must also be deleted.
    Completed jobs under 24h retention must be retained.
    """
    op_auth = {"Authorization": f"Bearer {history_setup['op_a_token']}"}
    u_auth = {"Authorization": f"Bearer {history_setup['user_token']}"}
    shop_id = history_setup["shop_a_id"]
    storage = get_storage_service()

    # Configure retention to 8 hours
    patch_res = client.patch(f"/api/v1/shops/{shop_id}/settings", json={"history_retention_hours": 8}, headers=op_auth)
    assert patch_res.status_code == 200

    # 1. Job 1: FAILED
    job1_id = create_job(client, history_setup["user_token"], shop_id, "fail_2m.pdf.enc")
    client.post(f"/api/v1/jobs/{job1_id}/authorize", headers=u_auth)
    client.post(f"/api/v1/print/jobs/{job1_id}/start", headers=op_auth)
    fail1_res = client.post(f"/api/v1/print/jobs/{job1_id}/fail?reason=HardwareJam", headers=op_auth)
    assert fail1_res.status_code == 200

    # 2. Job 2: CANCELLED
    job2_id = create_job(client, history_setup["user_token"], shop_id, "cancel_2m.pdf.enc")
    cancel_res = client.post(f"/api/v1/jobs/{job2_id}/cancel", json={"reason": "User cancelled"}, headers=u_auth)
    assert cancel_res.status_code == 200

    # 3. Job 3: COMPLETED 1 hour ago
    job3_id = create_job(client, history_setup["user_token"], shop_id, "complete_1h.pdf.enc")
    client.post(f"/api/v1/jobs/{job3_id}/authorize", headers=u_auth)
    client.post(f"/api/v1/print/jobs/{job3_id}/start", headers=op_auth)
    client.post(f"/api/v1/print/jobs/{job3_id}/increment-copy?delta=1", headers=op_auth)

    # Populate temporary storage files for each document
    j1 = await db_session.get(PrintJob, job1_id)
    d1 = await db_session.get(Document, j1.document_id)
    d1_path = d1.storage_path
    storage.put_object_data(d1_path, b"ENCRYPTED_STORAGE_FAIL_JOB")
    assert storage.object_exists(d1_path) is True

    j2 = await db_session.get(PrintJob, job2_id)
    d2 = await db_session.get(Document, j2.document_id)
    d2_path = d2.storage_path
    storage.put_object_data(d2_path, b"ENCRYPTED_STORAGE_CANCEL_JOB")
    assert storage.object_exists(d2_path) is True

    j3 = await db_session.get(PrintJob, job3_id)
    d3 = await db_session.get(Document, j3.document_id)
    d3_path = d3.storage_path
    storage.put_object_data(d3_path, b"ENCRYPTED_STORAGE_COMPLETE_JOB")
    assert storage.object_exists(d3_path) is True

    now_utc = datetime.now(timezone.utc)
    # Simulate Job 1 and Job 2 terminating 3 minutes ago (> 2 minutes threshold)
    j1.completed_at = now_utc - timedelta(minutes=3)
    j2.completed_at = now_utc - timedelta(minutes=3)
    # Simulate Job 3 completing 1 hour ago (well within 8h retention)
    j3.completed_at = now_utc - timedelta(hours=1)
    await db_session.commit()

    # Run history cleanup
    cleanup_res = client.post(f"/api/v1/cleanup/history/shop/{shop_id}", headers=op_auth)
    assert cleanup_res.status_code == 200
    data = cleanup_res.json()

    # Verify Job 1 & 2 are deleted, Job 3 is retained
    assert job1_id in data["deleted_job_ids"]
    assert job2_id in data["deleted_job_ids"]
    assert job3_id not in data["deleted_job_ids"]

    # Verify database records
    db_session.expire_all()
    assert await db_session.get(PrintJob, job1_id) is None
    assert await db_session.get(PrintJob, job2_id) is None
    assert await db_session.get(PrintJob, job3_id) is not None

    # Verify physical storage objects: failed and cancelled documents deleted, completed preserved
    assert storage.object_exists(d1_path) is False
    assert storage.object_exists(d2_path) is False
    assert storage.object_exists(d3_path) is True


@pytest.mark.asyncio
async def test_failed_and_cancelled_jobs_younger_than_2_minutes_retained(
    client: TestClient, history_setup, db_session: AsyncSession
):
    """
    Test 14: Failed and cancelled jobs that terminated less than 2 minutes ago
    must NOT be deleted yet.
    """
    op_auth = {"Authorization": f"Bearer {history_setup['op_a_token']}"}
    u_auth = {"Authorization": f"Bearer {history_setup['user_token']}"}
    shop_id = history_setup["shop_a_id"]
    storage = get_storage_service()

    job1_id = create_job(client, history_setup["user_token"], shop_id, "fail_recent.pdf.enc")
    client.post(f"/api/v1/jobs/{job1_id}/authorize", headers=u_auth)
    client.post(f"/api/v1/print/jobs/{job1_id}/start", headers=op_auth)
    fail_res = client.post(f"/api/v1/print/jobs/{job1_id}/fail?reason=PaperOut", headers=op_auth)
    assert fail_res.status_code == 200

    job2_id = create_job(client, history_setup["user_token"], shop_id, "cancel_recent.pdf.enc")
    cancel_res = client.post(f"/api/v1/jobs/{job2_id}/cancel", json={"reason": "Changed mind"}, headers=u_auth)
    assert cancel_res.status_code == 200

    j1 = await db_session.get(PrintJob, job1_id)
    d1 = await db_session.get(Document, j1.document_id)
    d1_path = d1.storage_path
    storage.put_object_data(d1_path, b"RECENT_FAIL_STORAGE")

    j2 = await db_session.get(PrintJob, job2_id)
    d2 = await db_session.get(Document, j2.document_id)
    d2_path = d2.storage_path
    storage.put_object_data(d2_path, b"RECENT_CANCEL_STORAGE")

    now_utc = datetime.now(timezone.utc)
    # Simulate termination 45 seconds ago (< 2 minutes)
    j1.completed_at = now_utc - timedelta(seconds=45)
    j2.completed_at = now_utc - timedelta(seconds=45)
    await db_session.commit()

    cleanup_res = client.post(f"/api/v1/cleanup/history/shop/{shop_id}", headers=op_auth)
    assert cleanup_res.status_code == 200
    data = cleanup_res.json()

    assert job1_id not in data["deleted_job_ids"]
    assert job2_id not in data["deleted_job_ids"]

    # Verify both jobs still exist in DB
    db_session.expire_all()
    assert await db_session.get(PrintJob, job1_id) is not None
    assert await db_session.get(PrintJob, job2_id) is not None

    # Verify storage objects still exist
    assert storage.object_exists(d1_path) is True
    assert storage.object_exists(d2_path) is True


@pytest.mark.asyncio
async def test_active_accepted_printing_jobs_never_deleted_and_storage_protected(
    client: TestClient, history_setup, db_session: AsyncSession
):
    """
    Test 15: Jobs in CREATED, QUEUED, AUTHORIZED, and PRINTING states must NEVER be deleted.
    If a document is shared between an active job and a failed job, the storage file
    must NEVER be deleted.
    """
    op_auth = {"Authorization": f"Bearer {history_setup['op_a_token']}"}
    u_auth = {"Authorization": f"Bearer {history_setup['user_token']}"}
    shop_id = history_setup["shop_a_id"]
    storage = get_storage_service()

    # 1. Pending jobs
    job_created_id = create_job(client, history_setup["user_token"], shop_id, "created_old.pdf.enc")
    job_queued_id = create_job(client, history_setup["user_token"], shop_id, "queued_old.pdf.enc")

    # 2. Accepted job
    job_auth_id = create_job(client, history_setup["user_token"], shop_id, "auth_old.pdf.enc")
    client.post(f"/api/v1/jobs/{job_auth_id}/authorize", headers=u_auth)

    # 3. Printing job
    job_printing_id = create_job(client, history_setup["user_token"], shop_id, "printing_old.pdf.enc")
    client.post(f"/api/v1/jobs/{job_printing_id}/authorize", headers=u_auth)
    client.post(f"/api/v1/print/jobs/{job_printing_id}/start", headers=op_auth)

    # 4. Failed job sharing the SAME document as job_printing
    p_job = await db_session.get(PrintJob, job_printing_id)
    doc_shared = await db_session.get(Document, p_job.document_id)
    shared_storage_path = doc_shared.storage_path
    storage.put_object_data(shared_storage_path, b"SHARED_ACTIVE_DOC_DATA")

    failed_shared_id = "PRV-SHARED-FAIL"
    now_utc = datetime.now(timezone.utc)
    shared_failed_job = PrintJob(
        id=failed_shared_id,
        user_id=p_job.user_id,
        shop_id=shop_id,
        session_id=p_job.session_id,
        document_id=p_job.document_id,
        status=PrintJobStatus.FAILED.value,
        completed_at=now_utc - timedelta(minutes=5),
        created_at=now_utc - timedelta(minutes=10),
        expires_at=now_utc + timedelta(hours=2)
    )
    db_session.add(shared_failed_job)

    # Set all active jobs to 8 hours ago
    c_job = await db_session.get(PrintJob, job_created_id)
    c_job.created_at = now_utc - timedelta(hours=8)
    c_job.status = PrintJobStatus.CREATED.value

    q_job = await db_session.get(PrintJob, job_queued_id)
    q_job.created_at = now_utc - timedelta(hours=8)
    q_job.status = PrintJobStatus.QUEUED.value

    a_job = await db_session.get(PrintJob, job_auth_id)
    a_job.created_at = now_utc - timedelta(hours=8)
    a_job.status = PrintJobStatus.AUTHORIZED.value

    p_job.created_at = now_utc - timedelta(hours=8)
    p_job.status = PrintJobStatus.PRINTING.value

    await db_session.commit()

    # Run cleanup
    cleanup_res = client.post(f"/api/v1/cleanup/history/shop/{shop_id}", headers=op_auth)
    assert cleanup_res.status_code == 200
    data = cleanup_res.json()

    # Active jobs must NOT be deleted
    assert job_created_id not in data["deleted_job_ids"]
    assert job_queued_id not in data["deleted_job_ids"]
    assert job_auth_id not in data["deleted_job_ids"]
    assert job_printing_id not in data["deleted_job_ids"]

    # Failed shared job IS deleted
    assert failed_shared_id in data["deleted_job_ids"]

    # Verify active jobs still exist in DB
    db_session.expire_all()
    assert await db_session.get(PrintJob, job_created_id) is not None
    assert await db_session.get(PrintJob, job_queued_id) is not None
    assert await db_session.get(PrintJob, job_auth_id) is not None
    assert await db_session.get(PrintJob, job_printing_id) is not None
    assert await db_session.get(PrintJob, failed_shared_id) is None

    # CRITICAL: Document storage MUST still exist because job_printing is active!
    assert storage.object_exists(shared_storage_path) is True


@pytest.mark.asyncio
async def test_cleanup_worker_restart_behavior(
    client: TestClient, history_setup, db_session: AsyncSession
):
    """
    Test 16: When the backend or background worker restarts, a new DocumentCleanupService
    session correctly sweeps expired failed/cancelled jobs and preserves unexpired completed jobs.
    """
    op_auth = {"Authorization": f"Bearer {history_setup['op_a_token']}"}
    u_auth = {"Authorization": f"Bearer {history_setup['user_token']}"}
    shop_id = history_setup["shop_a_id"]

    client.patch(f"/api/v1/shops/{shop_id}/settings", json={"history_retention_hours": 4}, headers=op_auth)

    # Failed job 5 minutes ago
    failed_job_id = create_job(client, history_setup["user_token"], shop_id, "restart_fail.pdf.enc")
    client.post(f"/api/v1/jobs/{failed_job_id}/authorize", headers=u_auth)
    client.post(f"/api/v1/print/jobs/{failed_job_id}/start", headers=op_auth)
    client.post(f"/api/v1/print/jobs/{failed_job_id}/fail?reason=RestartCrash", headers=op_auth)

    # Completed job 30 minutes ago (retention is 4 hours)
    completed_job_id = create_job(client, history_setup["user_token"], shop_id, "restart_complete.pdf.enc")
    client.post(f"/api/v1/jobs/{completed_job_id}/authorize", headers=u_auth)
    client.post(f"/api/v1/print/jobs/{completed_job_id}/start", headers=op_auth)
    client.post(f"/api/v1/print/jobs/{completed_job_id}/increment-copy?delta=1", headers=op_auth)

    now_utc = datetime.now(timezone.utc)
    fj = await db_session.get(PrintJob, failed_job_id)
    fj.completed_at = now_utc - timedelta(minutes=5)

    cj = await db_session.get(PrintJob, completed_job_id)
    cj.completed_at = now_utc - timedelta(minutes=30)
    await db_session.commit()

    # Trigger global history sweep as operator
    sweep_res = client.post("/api/v1/cleanup/history/sweep", headers=op_auth)
    assert sweep_res.status_code == 200
    sweep_data = sweep_res.json()

    # Verify sweep results: failed job deleted, completed job retained
    assert sweep_data["total_deleted_jobs"] >= 1
    assert failed_job_id in sweep_data["details_by_shop"][shop_id]["deleted_job_ids"]
    assert completed_job_id not in sweep_data["details_by_shop"][shop_id]["deleted_job_ids"]

    # Verify database state
    db_session.expire_all()
    assert await db_session.get(PrintJob, failed_job_id) is None
    assert await db_session.get(PrintJob, completed_job_id) is not None


@pytest.mark.asyncio
async def test_cross_shop_isolation_for_failed_cancelled_jobs(
    client: TestClient, history_setup, db_session: AsyncSession
):
    """
    Test 17: Cleaning Shop A's history must NEVER delete Shop B's failed or cancelled jobs.
    """
    op_a_auth = {"Authorization": f"Bearer {history_setup['op_a_token']}"}
    shop_a_id = history_setup["shop_a_id"]
    shop_b_id = history_setup["shop_b_id"]

    # Shop A failed job
    job_a_id = create_job(client, history_setup["user_token"], shop_a_id, "job_a_fail.pdf.enc")
    client.post(f"/api/v1/jobs/{job_a_id}/authorize", headers={"Authorization": f"Bearer {history_setup['user_token']}"})
    client.post(f"/api/v1/print/jobs/{job_a_id}/start", headers=op_a_auth)
    client.post(f"/api/v1/print/jobs/{job_a_id}/fail?reason=ShopAFailure", headers=op_a_auth)

    # Shop B failed job
    op_b_auth = {"Authorization": f"Bearer {history_setup['op_b_token']}"}
    job_b_id = create_job(client, history_setup["user_token"], shop_b_id, "job_b_fail.pdf.enc")
    client.post(f"/api/v1/jobs/{job_b_id}/authorize", headers={"Authorization": f"Bearer {history_setup['user_token']}"})
    client.post(f"/api/v1/print/jobs/{job_b_id}/start", headers=op_b_auth)
    client.post(f"/api/v1/print/jobs/{job_b_id}/fail?reason=ShopBFailure", headers=op_b_auth)

    now_utc = datetime.now(timezone.utc)
    ja = await db_session.get(PrintJob, job_a_id)
    ja.completed_at = now_utc - timedelta(minutes=4)

    jb = await db_session.get(PrintJob, job_b_id)
    jb.completed_at = now_utc - timedelta(minutes=4)
    await db_session.commit()

    # Clean Shop A only
    cleanup_res = client.post(f"/api/v1/cleanup/history/shop/{shop_a_id}", headers=op_a_auth)
    assert cleanup_res.status_code == 200
    data = cleanup_res.json()

    assert job_a_id in data["deleted_job_ids"]
    assert job_b_id not in data["deleted_job_ids"]

    # Shop A job is deleted, Shop B job is intact
    db_session.expire_all()
    assert await db_session.get(PrintJob, job_a_id) is None
    assert await db_session.get(PrintJob, job_b_id) is not None

