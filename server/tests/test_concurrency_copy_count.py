import pytest
import asyncio
import os
import tempfile
from datetime import datetime, timedelta, timezone
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
from sqlalchemy.pool import NullPool
from app.models.base import Base
from app.models.entities import User, Shop, Session, Document, PrintJob
from app.models.enums import UserRole, PrintJobStatus
from app.repositories.print_job_repo import PrintJobRepository, CopyLimitExceededException


CONC_DB_PATH = os.path.join(tempfile.gettempdir(), "test_privprint_conc.db")
CONC_DB_URL = f"sqlite+aiosqlite:///{CONC_DB_PATH}"


@pytest.fixture
async def conc_session_factory():
    if os.path.exists(CONC_DB_PATH):
        try:
            os.remove(CONC_DB_PATH)
        except OSError:
            pass

    engine = create_async_engine(
        CONC_DB_URL,
        poolclass=NullPool,
        connect_args={"timeout": 30}
    )

    # Enable WAL mode for concurrent access
    async with engine.begin() as conn:
        await conn.exec_driver_sql("PRAGMA journal_mode=WAL;")
        await conn.run_sync(Base.metadata.create_all)

    session_factory = async_sessionmaker(
        bind=engine,
        class_=AsyncSession,
        expire_on_commit=False,
        autocommit=False,
        autoflush=False
    )

    yield session_factory

    await engine.dispose()
    if os.path.exists(CONC_DB_PATH):
        try:
            os.remove(CONC_DB_PATH)
        except OSError:
            pass


@pytest.mark.asyncio
async def test_atomic_copy_count_concurrency_prevention(conc_session_factory):
    now = datetime.now(timezone.utc)

    # 1. Setup base records in database
    async with conc_session_factory() as session:
        user = User(
            id="usr_concurrency_user",
            email="conc@test.com",
            hashed_password="pw",
            role=UserRole.USER.value
        )
        shop = Shop(
            id="SHOP-CONC",
            name="Concurrent Xerox",
            address="123 Speed Way",
            permanent_qr_payload="privprint://shop?id=SHOP-CONC"
        )
        session.add_all([user, shop])
        await session.flush()

        sess = Session(
            id="SES-CONC-01",
            user_id=user.id,
            shop_id=shop.id,
            token="tok_conc_123",
            expires_at=now + timedelta(minutes=15)
        )
        session.add(sess)
        await session.flush()

        doc = Document(
            id="DOC-CONC-01",
            user_id=user.id,
            session_id=sess.id,
            filename="confidential.pdf",
            storage_path="docs/conf.enc",
            file_size_bytes=1000,
            sha256_hash="h",
            iv_hex="iv",
            key_fingerprint="fp",
            copies_authorized=5,
            expires_at=now + timedelta(minutes=15)
        )
        session.add(doc)
        await session.flush()

        # Job authorized for exactly 5 copies
        job = PrintJob(
            id="JOB-CONC-5COPIES",
            user_id=user.id,
            shop_id=shop.id,
            session_id=sess.id,
            document_id=doc.id,
            requested_copies=5,
            completed_copies=0,
            status=PrintJobStatus.QUEUED.value,
            expires_at=now + timedelta(minutes=15)
        )
        session.add(job)
        await session.commit()

    # 2. Concurrency Scenario A: Two simultaneous requests each demanding 5 copies (5 + 5)
    # Exactly ONE must succeed and ONE must fail with CopyLimitExceededException
    success_count = 0
    failure_count = 0

    async def attempt_five_copies():
        nonlocal success_count, failure_count
        async with conc_session_factory() as session:
            repo = PrintJobRepository(session)
            try:
                await repo.increment_copies_atomic("JOB-CONC-5COPIES", delta=5)
                await session.commit()
                success_count += 1
            except CopyLimitExceededException:
                await session.rollback()
                failure_count += 1

    # Execute competing parallel tasks
    await asyncio.gather(attempt_five_copies(), attempt_five_copies())

    assert success_count == 1, f"Expected 1 success, got {success_count}"
    assert failure_count == 1, f"Expected 1 failure, got {failure_count}"

    # Verify state in database
    async with conc_session_factory() as session:
        repo = PrintJobRepository(session)
        updated_job = await repo.get_by_id("JOB-CONC-5COPIES")
        assert updated_job.completed_copies == 5
        assert updated_job.status == PrintJobStatus.COMPLETED.value


@pytest.mark.asyncio
async def test_atomic_single_copy_increments_concurrency(conc_session_factory):
    now = datetime.now(timezone.utc)

    # Setup job with 3 authorized copies
    async with conc_session_factory() as session:
        user = User(id="usr_conc_2", email="conc2@test.com", hashed_password="pw", role=UserRole.USER.value)
        shop = Shop(id="SHOP-CONC-2", name="Xerox 2", address="St", permanent_qr_payload="p")
        session.add_all([user, shop])
        await session.flush()

        sess = Session(id="SES-CONC-02", user_id=user.id, shop_id=shop.id, token="t2", expires_at=now + timedelta(minutes=15))
        doc = Document(id="DOC-CONC-02", user_id=user.id, session_id=sess.id, filename="f", storage_path="p", file_size_bytes=10, sha256_hash="h", iv_hex="iv", key_fingerprint="fp", copies_authorized=3, expires_at=now + timedelta(minutes=15))
        job = PrintJob(id="JOB-CONC-3COPIES", user_id=user.id, shop_id=shop.id, session_id=sess.id, document_id=doc.id, requested_copies=3, completed_copies=0, status=PrintJobStatus.QUEUED.value, expires_at=now + timedelta(minutes=15))
        session.add_all([sess, doc, job])
        await session.commit()

    # Launch 6 concurrent requests each trying to print 1 copy
    successful_increments = 0
    blocked_increments = 0

    async def attempt_one_copy():
        nonlocal successful_increments, blocked_increments
        async with conc_session_factory() as session:
            repo = PrintJobRepository(session)
            try:
                await repo.increment_copies_atomic("JOB-CONC-3COPIES", delta=1)
                await session.commit()
                successful_increments += 1
            except CopyLimitExceededException:
                await session.rollback()
                blocked_increments += 1

    tasks = [attempt_one_copy() for _ in range(6)]
    await asyncio.gather(*tasks)

    # Exactly 3 must succeed, 3 must be blocked
    assert successful_increments == 3, f"Expected 3 successes, got {successful_increments}"
    assert blocked_increments == 3, f"Expected 3 rejections, got {blocked_increments}"

    # Verify database total never exceeded 3
    async with conc_session_factory() as session:
        repo = PrintJobRepository(session)
        final_job = await repo.get_by_id("JOB-CONC-3COPIES")
        assert final_job.completed_copies == 3
        assert final_job.status == PrintJobStatus.COMPLETED.value
