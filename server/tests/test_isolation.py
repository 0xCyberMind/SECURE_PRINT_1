import pytest
from datetime import datetime, timedelta, timezone
from app.models.enums import UserRole, PrintJobStatus
from app.repositories.user_repo import UserRepository
from app.repositories.shop_repo import ShopRepository, DeviceRepository, PrinterRepository
from app.repositories.document_repo import DocumentRepository
from app.repositories.print_job_repo import PrintJobRepository
from app.repositories.session_repo import SessionRepository


@pytest.mark.asyncio
async def test_user_and_shop_isolation_boundaries(db_session):
    user_repo = UserRepository(db_session)
    shop_repo = ShopRepository(db_session)
    device_repo = DeviceRepository(db_session)
    printer_repo = PrinterRepository(db_session)
    session_repo = SessionRepository(db_session)
    doc_repo = DocumentRepository(db_session)
    job_repo = PrintJobRepository(db_session)

    now = datetime.now(timezone.utc)

    # Setup User A and User B
    user_a = await user_repo.create(
        id="usr_alice",
        email="alice@test.com",
        hashed_password="pw",
        role=UserRole.USER.value
    )
    user_b = await user_repo.create(
        id="usr_bob",
        email="bob@test.com",
        hashed_password="pw",
        role=UserRole.USER.value
    )

    # Setup Shop 101 and Shop 102
    shop_101 = await shop_repo.create(
        id="SHOP-101",
        name="Apex Xerox",
        address="Campus",
        permanent_qr_payload="privprint://shop?id=SHOP-101"
    )
    shop_102 = await shop_repo.create(
        id="SHOP-102",
        name="Metro QuickPrint",
        address="Metro Stn",
        permanent_qr_payload="privprint://shop?id=SHOP-102"
    )

    # Setup Device and Printer for Shop 101
    dev_101 = await device_repo.create(
        id="DEV-SHOP101-WIN",
        shop_id=shop_101.id,
        name="Shop 101 Station"
    )
    prn_101 = await printer_repo.create(
        id="PRN-101-LASER",
        shop_id=shop_101.id,
        name="Shop 101 Laser",
        model="HP-Enterprise"
    )

    # Setup Session, Document, and Job for User A at Shop 101
    sess_a = await session_repo.create(
        id="SES-A-101",
        user_id=user_a.id,
        shop_id=shop_101.id,
        token="tok_alice_123",
        expires_at=now + timedelta(minutes=15)
    )
    doc_a = await doc_repo.create(
        id="DOC-ALICE-SECRET",
        user_id=user_a.id,
        session_id=sess_a.id,
        filename="alice_tax_return.pdf",
        storage_path="docs/alice.enc",
        file_size_bytes=5000,
        sha256_hash="hash1",
        iv_hex="iv1",
        key_fingerprint="fp1",
        copies_authorized=1,
        expires_at=now + timedelta(minutes=15)
    )
    job_a = await job_repo.create(
        id="JOB-ALICE-101",
        user_id=user_a.id,
        shop_id=shop_101.id,
        session_id=sess_a.id,
        document_id=doc_a.id,
        printer_id=prn_101.id,
        requested_copies=1,
        expires_at=now + timedelta(minutes=15)
    )

    # --- RULE 1: User A must never access another user's documents/jobs ---
    # User A accesses their own document -> SUCCESS
    assert await doc_repo.get_by_id_and_user(doc_a.id, user_a.id) is not None
    # User B tries to access User A's document -> BLOCKED (None returned)
    assert await doc_repo.get_by_id_and_user(doc_a.id, user_b.id) is None

    # User A accesses their own job -> SUCCESS
    assert await job_repo.get_by_id_and_user(job_a.id, user_a.id) is not None
    # User B tries to access User A's job -> BLOCKED (None returned)
    assert await job_repo.get_by_id_and_user(job_a.id, user_b.id) is None

    # --- RULE 2: A shop must never access another shop's devices/jobs/printers ---
    # Shop 101 accesses its own printer & device -> SUCCESS
    assert await printer_repo.get_by_id_and_shop(prn_101.id, shop_101.id) is not None
    assert await device_repo.get_by_id_and_shop(dev_101.id, shop_101.id) is not None

    # Shop 102 tries to access Shop 101's printer & device -> BLOCKED (None returned)
    assert await printer_repo.get_by_id_and_shop(prn_101.id, shop_102.id) is None
    assert await device_repo.get_by_id_and_shop(dev_101.id, shop_102.id) is None

    # Shop 101 accesses job queued at Shop 101 -> SUCCESS
    assert await job_repo.get_by_id_and_shop(job_a.id, shop_101.id) is not None
    # Shop 102 tries to access job queued at Shop 101 -> BLOCKED (None returned)
    assert await job_repo.get_by_id_and_shop(job_a.id, shop_102.id) is None

    # --- RULE 3: A print device must only access its registered shop ---
    # dev_101 is registered to shop_101, not shop_102
    assert dev_101.shop_id == shop_101.id
    assert dev_101.shop_id != shop_102.id
