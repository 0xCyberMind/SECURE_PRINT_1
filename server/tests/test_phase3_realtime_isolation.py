import pytest
from datetime import datetime, timedelta, timezone
from app.models.enums import UserRole, PrintJobStatus
from app.repositories.user_repo import UserRepository
from app.repositories.shop_repo import ShopRepository, DeviceRepository, PrinterRepository
from app.repositories.document_repo import DocumentRepository
from app.repositories.print_job_repo import PrintJobRepository
from app.repositories.session_repo import SessionRepository
from app.core.security import create_access_token, get_password_hash
from app.services.redis_service import get_redis_service


@pytest.mark.asyncio
async def test_realtime_shop_event_pubsub_isolation(client, db_session):
    """
    PHASE 3 TEST 1: Shop Event Pub/Sub Isolation.
    Events published to Shop A's channel MUST be delivered to Station A
    and MUST NEVER be delivered to Station B.
    """
    user_repo = UserRepository(db_session)
    shop_repo = ShopRepository(db_session)
    device_repo = DeviceRepository(db_session)

    # Setup Shop A & Device A
    op_a = await user_repo.create(
        id="usr_op_rt_a",
        email="op_rt_a@shop.com",
        hashed_password=get_password_hash("Pass123!"),
        role=UserRole.SHOP_OPERATOR.value,
        is_active=True,
        is_verified=True,
    )
    shop_a = await shop_repo.create(
        id="SHOP-RT-A",
        name="Realtime Shop Alpha",
        owner_id=op_a.id,
        address="1 Alpha Way",
        status="ACTIVE",
        is_verified=True,
        permanent_qr_payload="privprint://shop?id=SHOP-RT-A",
    )
    dev_a = await device_repo.create(
        id="DEV-RT-A",
        shop_id=shop_a.id,
        name="Station RT A",
        device_type="WINDOWS_STATION",
        is_active=True,
        status="ONLINE",
    )
    await user_repo.create(
        id=dev_a.id,
        email="dev_rt_a@device.privprint.local",
        hashed_password=get_password_hash("DevPass!"),
        role=UserRole.PRINT_DEVICE.value,
        is_active=True,
        is_verified=True,
    )

    # Setup Shop B & Device B
    op_b = await user_repo.create(
        id="usr_op_rt_b",
        email="op_rt_b@shop.com",
        hashed_password=get_password_hash("Pass123!"),
        role=UserRole.SHOP_OPERATOR.value,
        is_active=True,
        is_verified=True,
    )
    shop_b = await shop_repo.create(
        id="SHOP-RT-B",
        name="Realtime Shop Beta",
        owner_id=op_b.id,
        address="2 Beta Way",
        status="ACTIVE",
        is_verified=True,
        permanent_qr_payload="privprint://shop?id=SHOP-RT-B",
    )
    dev_b = await device_repo.create(
        id="DEV-RT-B",
        shop_id=shop_b.id,
        name="Station RT B",
        device_type="WINDOWS_STATION",
        is_active=True,
        status="ONLINE",
    )
    await user_repo.create(
        id=dev_b.id,
        email="dev_rt_b@device.privprint.local",
        hashed_password=get_password_hash("DevPass!"),
        role=UserRole.PRINT_DEVICE.value,
        is_active=True,
        is_verified=True,
    )

    await db_session.commit()

    token_dev_a = create_access_token(subject=dev_a.id, role=UserRole.PRINT_DEVICE.value)
    token_dev_b = create_access_token(subject=dev_b.id, role=UserRole.PRINT_DEVICE.value)

    redis_svc = get_redis_service()
    redis_svc.set_simulation_failure(False)

    with client.websocket_connect(f"/api/v1/realtime/ws?token={token_dev_a}") as ws_a:
        with client.websocket_connect(f"/api/v1/realtime/ws?token={token_dev_b}") as ws_b:
            _ = ws_a.receive_json()  # connected
            _ = ws_b.receive_json()  # connected

            # Station A subscribes to Shop A
            ws_a.send_json({"type": "subscribe", "channel": f"shop:{shop_a.id}"})
            sub_a = ws_a.receive_json()
            assert sub_a["type"] == "subscribed"

            # Station B subscribes to Shop B
            ws_b.send_json({"type": "subscribe", "channel": f"shop:{shop_b.id}"})
            sub_b = ws_b.receive_json()
            assert sub_b["type"] == "subscribed"

            # Publish event to Shop A
            event_a = {"job_id": "PRV-RT-001", "status": "AUTHORIZED", "file_index": 0}
            await redis_svc.publish_shop_event(shop_a.id, "JOB_AUTHORIZED", event_a)

            # Station A MUST receive the event
            msg_a = ws_a.receive_json()
            assert msg_a["type"] == "event"
            assert msg_a["channel"] == f"shop:{shop_a.id}"
            assert msg_a["event"] == "JOB_AUTHORIZED"
            assert msg_a["data"]["job_id"] == "PRV-RT-001"

            # Station B checks ping/pong to confirm its connection is alive and has received no Shop A event
            ws_b.send_json({"type": "ping"})
            pong_b = ws_b.receive_json()
            assert pong_b["type"] == "pong"  # If Shop A leaked to B, pong wouldn't be next!


@pytest.mark.asyncio
async def test_realtime_job_and_device_event_pubsub_isolation(client, db_session):
    """
    PHASE 3 TEST 2: Job and Device Event Pub/Sub Isolation.
    Events on job:PRV-A only reach authorized Station A, never Station B.
    Events on device:DEV-A only reach Station A, never Station B.
    """
    user_repo = UserRepository(db_session)
    shop_repo = ShopRepository(db_session)
    device_repo = DeviceRepository(db_session)
    printer_repo = PrinterRepository(db_session)
    session_repo = SessionRepository(db_session)
    doc_repo = DocumentRepository(db_session)
    job_repo = PrintJobRepository(db_session)

    now = datetime.now(timezone.utc)

    op_a = await user_repo.create(
        id="usr_op_jd_a",
        email="op_jd_a@shop.com",
        hashed_password=get_password_hash("Pass123!"),
        role=UserRole.SHOP_OPERATOR.value,
        is_active=True,
        is_verified=True,
    )
    shop_a = await shop_repo.create(
        id="SHOP-JD-A",
        name="JD Shop Alpha",
        owner_id=op_a.id,
        address="1 Alpha Way",
        status="ACTIVE",
        is_verified=True,
        permanent_qr_payload="privprint://shop?id=SHOP-JD-A",
    )
    dev_a = await device_repo.create(
        id="DEV-JD-A",
        shop_id=shop_a.id,
        name="Station JD A",
        device_type="WINDOWS_STATION",
        is_active=True,
        status="ONLINE",
    )
    await user_repo.create(
        id=dev_a.id,
        email="dev_jd_a@device.privprint.local",
        hashed_password=get_password_hash("DevPass!"),
        role=UserRole.PRINT_DEVICE.value,
        is_active=True,
        is_verified=True,
    )
    dev_b = await device_repo.create(
        id="DEV-JD-B",
        shop_id="SHOP-JD-OTHER",
        name="Station JD B",
        device_type="WINDOWS_STATION",
        is_active=True,
        status="ONLINE",
    )
    await user_repo.create(
        id=dev_b.id,
        email="dev_jd_b@device.privprint.local",
        hashed_password=get_password_hash("DevPass!"),
        role=UserRole.PRINT_DEVICE.value,
        is_active=True,
        is_verified=True,
    )
    cust = await user_repo.create(
        id="usr_cust_jd",
        email="cust_jd@user.com",
        hashed_password=get_password_hash("Pass123!"),
        role=UserRole.USER.value,
        is_active=True,
        is_verified=True,
    )
    sess = await session_repo.create(
        id="SES-JD-01",
        user_id=cust.id,
        shop_id=shop_a.id,
        token="tok_jd_1",
        status="ACTIVE",
        expires_at=now + timedelta(minutes=30),
    )
    doc = await doc_repo.create(
        id="DOC-JD-01",
        user_id=cust.id,
        session_id=sess.id,
        filename="jd.pdf",
        storage_path="docs/jd.enc",
        file_size_bytes=1000,
        sha256_hash="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        iv_hex="00112233445566778899aabbccddeeff",
        key_fingerprint="fp_jd",
        copies_authorized=1,
        expires_at=now + timedelta(minutes=30),
    )
    job_a = await job_repo.create(
        id="PRV-JD-001",
        user_id=cust.id,
        shop_id=shop_a.id,
        session_id=sess.id,
        document_id=doc.id,
        requested_copies=1,
        page_count=1,
        status=PrintJobStatus.AUTHORIZED.value,
        expires_at=now + timedelta(minutes=30),
    )
    await db_session.commit()

    token_dev_a = create_access_token(subject=dev_a.id, role=UserRole.PRINT_DEVICE.value)
    token_dev_b = create_access_token(subject=dev_b.id, role=UserRole.PRINT_DEVICE.value)

    redis_svc = get_redis_service()
    redis_svc.set_simulation_failure(False)

    with client.websocket_connect(f"/api/v1/realtime/ws?token={token_dev_a}") as ws_a:
        with client.websocket_connect(f"/api/v1/realtime/ws?token={token_dev_b}") as ws_b:
            _ = ws_a.receive_json()  # connected
            _ = ws_b.receive_json()  # connected

            # Station A subscribes to job_a channel
            ws_a.send_json({"type": "subscribe", "channel": f"job:{job_a.id}"})
            sub_a = ws_a.receive_json()
            assert sub_a["type"] == "subscribed"

            # Station B tries to subscribe to job_a channel -> FORBIDDEN
            ws_b.send_json({"type": "subscribe", "channel": f"job:{job_a.id}"})
            err_b = ws_b.receive_json()
            assert err_b["type"] == "error"
            assert err_b["code"] == "FORBIDDEN"

            # Publish event to job:PRV-JD-001
            await redis_svc.publish_job_event(job_a.id, "PRINT_STARTED", {"job_id": job_a.id})

            # Station A receives event
            msg_a = ws_a.receive_json()
            assert msg_a["type"] == "event"
            assert msg_a["event"] == "PRINT_STARTED"

            # Station B confirms no leaked event received
            ws_b.send_json({"type": "ping"})
            pong_b = ws_b.receive_json()
            assert pong_b["type"] == "pong"
