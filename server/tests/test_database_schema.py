import pytest
from datetime import datetime, timedelta, timezone
from app.models.entities import (
    User,
    Shop,
    Device,
    Printer,
    Session,
    Document,
    PrintJob,
    RefreshToken,
    AuditLog,
)
from app.models.enums import UserRole, SessionStatus, PrintJobStatus, CleanupState
from app.repositories.user_repo import UserRepository
from app.repositories.shop_repo import ShopRepository, DeviceRepository, PrinterRepository
from app.repositories.document_repo import DocumentRepository
from app.repositories.print_job_repo import PrintJobRepository
from app.repositories.session_repo import SessionRepository, RefreshTokenRepository, AuditLogRepository


@pytest.mark.asyncio
async def test_full_database_schema_and_relationships(db_session):
    user_repo = UserRepository(db_session)
    shop_repo = ShopRepository(db_session)
    device_repo = DeviceRepository(db_session)
    printer_repo = PrinterRepository(db_session)
    session_repo = SessionRepository(db_session)
    doc_repo = DocumentRepository(db_session)
    job_repo = PrintJobRepository(db_session)
    refresh_repo = RefreshTokenRepository(db_session)
    audit_repo = AuditLogRepository(db_session)

    # 1. Create User
    user = await user_repo.create(
        id="usr_test_1001",
        email="customer1@example.com",
        hashed_password="bcrypt_hashed_secret",
        full_name="Alice Customer",
        role=UserRole.USER.value
    )
    assert user.id == "usr_test_1001"
    assert user.email == "customer1@example.com"

    # 2. Create Shop
    shop = await shop_repo.create(
        id="SHOP-101",
        name="Apex Campus Xerox & Print",
        owner_id=user.id,
        address="Student Center Building 3",
        permanent_qr_payload="privprint://shop?id=SHOP-101"
    )
    assert shop.id == "SHOP-101"

    # 3. Create Device
    device = await device_repo.create(
        id="dev_win_01",
        shop_id=shop.id,
        name="Front Desk Windows Spooler Station",
        device_type="WINDOWS_STATION"
    )
    assert device.shop_id == shop.id

    # 4. Create Printer
    printer = await printer_repo.create(
        id="PRN-HP-01",
        shop_id=shop.id,
        name="HP LaserJet Enterprise M608",
        model="LaserJet Enterprise",
        supports_color=True,
        supports_duplex=True
    )
    assert printer.shop_id == shop.id

    # 5. Create Session
    now = datetime.now(timezone.utc)
    session = await session_repo.create(
        id="SES-TEST-01",
        user_id=user.id,
        shop_id=shop.id,
        token="tok_secret_session_pair_123",
        expires_at=now + timedelta(minutes=15)
    )
    assert session.shop_id == shop.id

    # 6. Create Document
    doc = await doc_repo.create(
        id="DOC-TEST-01",
        user_id=user.id,
        session_id=session.id,
        filename="resume.pdf",
        storage_path="documents/usr_1001/doc_01.enc",
        file_size_bytes=10240,
        sha256_hash="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        iv_hex="0102030405060708090a0b0c",
        key_fingerprint="FP-TEST-1234",
        copies_authorized=3,
        copies_consumed=0,
        expires_at=now + timedelta(minutes=15)
    )
    assert doc.copies_authorized == 3

    # 7. Create PrintJob
    job = await job_repo.create(
        id="PRV-TEST-01",
        user_id=user.id,
        shop_id=shop.id,
        session_id=session.id,
        document_id=doc.id,
        printer_id=printer.id,
        page_count=2,
        requested_copies=3,
        completed_copies=0,
        status=PrintJobStatus.QUEUED.value,
        expires_at=now + timedelta(minutes=15)
    )
    assert job.requested_copies == 3

    # 8. Create RefreshToken
    refresh_token = await refresh_repo.create(
        user_id=user.id,
        token_hash="hash_refr_9999",
        jti="jti_uuid_12345",
        expires_at=now + timedelta(days=7)
    )
    assert refresh_token.user_id == user.id

    # 9. Create AuditLog
    audit = await audit_repo.log_event(
        event_type="JOB_CREATED",
        details="Print job PRV-TEST-01 successfully queued.",
        user_id=user.id,
        shop_id=shop.id,
        job_id=job.id
    )
    assert audit.event_type == "JOB_CREATED"
    assert audit.job_id == job.id
