import pytest
from datetime import datetime, timedelta, timezone
from app.models.enums import UserRole, PrintJobStatus
from app.repositories.user_repo import UserRepository
from app.repositories.shop_repo import ShopRepository, DeviceRepository, PrinterRepository
from app.repositories.document_repo import DocumentRepository
from app.repositories.print_job_repo import PrintJobRepository
from app.repositories.session_repo import SessionRepository
from app.core.security import create_access_token, get_password_hash


@pytest.mark.asyncio
async def test_multi_tenant_isolation_complete_matrix(client, db_session):
    user_repo = UserRepository(db_session)
    shop_repo = ShopRepository(db_session)
    device_repo = DeviceRepository(db_session)
    printer_repo = PrinterRepository(db_session)
    session_repo = SessionRepository(db_session)
    doc_repo = DocumentRepository(db_session)
    job_repo = PrintJobRepository(db_session)

    now = datetime.now(timezone.utc)

    # 1. Setup Operators
    op_a = await user_repo.create(
        id="usr_op_a",
        email="operator_a@shop.com",
        hashed_password=get_password_hash("Pass123!"),
        role=UserRole.SHOP_OPERATOR.value,
        is_active=True,
        is_verified=True
    )
    op_b = await user_repo.create(
        id="usr_op_b",
        email="operator_b@shop.com",
        hashed_password=get_password_hash("Pass123!"),
        role=UserRole.SHOP_OPERATOR.value,
        is_active=True,
        is_verified=True
    )

    # 2. Setup Shops
    shop_a = await shop_repo.create(
        id="SHOP-AAA",
        name="Shop Alpha",
        owner_id=op_a.id,
        address="123 Alpha St",
        status="ACTIVE",
        is_verified=True,
        permanent_qr_payload="privprint://shop?id=SHOP-AAA"
    )
    shop_b = await shop_repo.create(
        id="SHOP-BBB",
        name="Shop Beta",
        owner_id=op_b.id,
        address="456 Beta Ave",
        status="ACTIVE",
        is_verified=True,
        permanent_qr_payload="privprint://shop?id=SHOP-BBB"
    )

    # 3. Setup Devices and Device Users
    dev_a = await device_repo.create(
        id="DEV-WIN-AAA",
        shop_id=shop_a.id,
        name="Station A",
        device_type="WINDOWS_STATION",
        is_active=True,
        status="ONLINE"
    )
    await user_repo.create(
        id=dev_a.id,
        email="dev_a@device.privprint.local",
        hashed_password=get_password_hash("DevPass!"),
        role=UserRole.PRINT_DEVICE.value,
        is_active=True,
        is_verified=True
    )

    dev_b = await device_repo.create(
        id="DEV-WIN-BBB",
        shop_id=shop_b.id,
        name="Station B",
        device_type="WINDOWS_STATION",
        is_active=True,
        status="ONLINE"
    )
    await user_repo.create(
        id=dev_b.id,
        email="dev_b@device.privprint.local",
        hashed_password=get_password_hash("DevPass!"),
        role=UserRole.PRINT_DEVICE.value,
        is_active=True,
        is_verified=True
    )

    # 4. Setup Printers
    prn_a = await printer_repo.create(
        id="PRN-AAA-PDF",
        shop_id=shop_a.id,
        name="Microsoft Print to PDF",
        model="PDF",
        is_online=True,
        status="ONLINE"
    )
    prn_b = await printer_repo.create(
        id="PRN-BBB-LASER",
        shop_id=shop_b.id,
        name="HP LaserJet Pro",
        model="LaserJet",
        is_online=True,
        status="ONLINE"
    )

    # 5. Setup Customers (Users)
    cust_a = await user_repo.create(
        id="usr_cust_a",
        email="customer_a@gmail.com",
        hashed_password=get_password_hash("Pass123!"),
        role=UserRole.USER.value,
        is_active=True,
        is_verified=True
    )
    cust_b = await user_repo.create(
        id="usr_cust_b",
        email="customer_b@gmail.com",
        hashed_password=get_password_hash("Pass123!"),
        role=UserRole.USER.value,
        is_active=True,
        is_verified=True
    )

    # 6. Customer A creates session and document for Shop A
    sess_a = await session_repo.create(
        id="SES-AAA-01",
        user_id=cust_a.id,
        shop_id=shop_a.id,
        token="tok_cust_a_123",
        status="ACTIVE",
        expires_at=now + timedelta(minutes=30)
    )
    doc_a = await doc_repo.create(
        id="DOC-AAA-01",
        user_id=cust_a.id,
        session_id=sess_a.id,
        filename="contract_alpha.pdf",
        storage_path="docs/contract_alpha.enc",
        file_size_bytes=1024,
        sha256_hash="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        iv_hex="00112233445566778899aabbccddeeff",
        key_fingerprint="fp_alpha",
        copies_authorized=5,
        expires_at=now + timedelta(minutes=30)
    )

    # Job A for Shop A
    job_a = await job_repo.create(
        id="PRV-AAA-001",
        user_id=cust_a.id,
        shop_id=shop_a.id,
        session_id=sess_a.id,
        document_id=doc_a.id,
        printer_id=prn_a.id,
        batch_id="BAT-AAA-100",
        requested_copies=2,
        page_count=3,
        status=PrintJobStatus.AUTHORIZED.value,
        expires_at=now + timedelta(minutes=30)
    )

    await db_session.commit()

    # Generate Auth Headers
    token_op_a = create_access_token(subject=op_a.id, role=op_a.role)
    token_op_b = create_access_token(subject=op_b.id, role=op_b.role)
    token_dev_a = create_access_token(subject=dev_a.id, role=UserRole.PRINT_DEVICE.value)
    token_dev_b = create_access_token(subject=dev_b.id, role=UserRole.PRINT_DEVICE.value)
    token_cust_a = create_access_token(subject=cust_a.id, role=cust_a.role)
    token_cust_b = create_access_token(subject=cust_b.id, role=cust_b.role)

    hdr_op_a = {"Authorization": f"Bearer {token_op_a}"}
    hdr_op_b = {"Authorization": f"Bearer {token_op_b}"}
    hdr_dev_a = {"Authorization": f"Bearer {token_dev_a}"}
    hdr_dev_b = {"Authorization": f"Bearer {token_dev_b}"}
    hdr_cust_a = {"Authorization": f"Bearer {token_cust_a}"}
    hdr_cust_b = {"Authorization": f"Bearer {token_cust_b}"}

    # =========================================================================
    # TEST 1: QUEUE ISOLATION (Device & Operator)
    # =========================================================================
    # Device A queries queue -> Receives Job A
    res = client.get("/api/v1/print/jobs", headers=hdr_dev_a)
    assert res.status_code == 200
    job_ids = [j["id"] for j in res.json()]
    assert job_a.id in job_ids

    # Device B queries queue -> MUST NOT receive Job A (empty list)
    res = client.get("/api/v1/print/jobs", headers=hdr_dev_b)
    assert res.status_code == 200
    job_ids = [j["id"] for j in res.json()]
    assert job_a.id not in job_ids
    assert len(job_ids) == 0

    # Device B maliciously queries Shop A via query param ?shopId=SHOP-AAA -> 403 Forbidden!
    res = client.get(f"/api/v1/print/jobs?shopId={shop_a.id}", headers=hdr_dev_b)
    assert res.status_code == 403

    # Operator B maliciously queries Shop A via query param ?shopId=SHOP-AAA -> 403 Forbidden!
    res = client.get(f"/api/v1/print/jobs?shopId={shop_a.id}", headers=hdr_op_b)
    assert res.status_code == 403

    # Operator A queries Shop A -> 200 OK with Job A
    res = client.get(f"/api/v1/print/jobs?shopId={shop_a.id}", headers=hdr_op_a)
    assert res.status_code == 200
    assert any(j["id"] == job_a.id for j in res.json())

    # =========================================================================
    # TEST 2: JOB DETAILS & BATCH ISOLATION
    # =========================================================================
    # Device B tries to inspect Job A -> 403 Forbidden!
    res = client.get(f"/api/v1/jobs/{job_a.id}", headers=hdr_dev_b)
    assert res.status_code == 403

    # Operator B tries to inspect Job A -> 403 Forbidden!
    res = client.get(f"/api/v1/jobs/{job_a.id}", headers=hdr_op_b)
    assert res.status_code == 403

    # Customer B tries to inspect Job A -> 403 Forbidden!
    res = client.get(f"/api/v1/jobs/{job_a.id}", headers=hdr_cust_b)
    assert res.status_code == 403

    # Device B tries to inspect Job A's Batch -> 403 Forbidden!
    res = client.get(f"/api/v1/jobs/batch/BAT-AAA-100", headers=hdr_dev_b)
    assert res.status_code == 403

    # Customer B tries to inspect Job A's Batch -> 403 Forbidden!
    res = client.get(f"/api/v1/jobs/batch/BAT-AAA-100", headers=hdr_cust_b)
    assert res.status_code == 403

    # =========================================================================
    # TEST 3: JOB EXECUTION & ACTION ISOLATION
    # =========================================================================
    # Device B tries to start Job A -> 403 Forbidden!
    res = client.post(f"/api/v1/print/jobs/{job_a.id}/start", headers=hdr_dev_b)
    assert res.status_code == 403

    # Operator B tries to start Job A -> 403 Forbidden!
    res = client.post(f"/api/v1/print/jobs/{job_a.id}/start", headers=hdr_op_b)
    assert res.status_code == 403

    # Device B tries to update progress for Job A -> 403 Forbidden!
    res = client.post(f"/api/v1/print/jobs/{job_a.id}/progress", json={"pages_printed": 1}, headers=hdr_dev_b)
    assert res.status_code == 403

    # Device B tries to increment copies for Job A -> 403 Forbidden!
    res = client.post(f"/api/v1/print/jobs/{job_a.id}/increment-copy?delta=1", headers=hdr_dev_b)
    assert res.status_code == 403

    # Device B tries to fail Job A -> 403 Forbidden!
    res = client.post(f"/api/v1/print/jobs/{job_a.id}/fail?reason=Hacked", headers=hdr_dev_b)
    assert res.status_code == 403

    # Customer B tries to cancel Job A -> 403 Forbidden!
    res = client.post(f"/api/v1/jobs/{job_a.id}/cancel", headers=hdr_cust_b)
    assert res.status_code == 403

    # Operator B tries to cancel Job A -> 403 Forbidden!
    res = client.post(f"/api/v1/jobs/{job_a.id}/cancel", headers=hdr_op_b)
    assert res.status_code == 403

    # =========================================================================
    # TEST 4: JOB CREATION BOUNDARY & CROSS-SHOP PRINTER TAMPERING
    # =========================================================================
    # Customer A tries to submit a job with tampered shop_id (Shop B) for Session A (bound to Shop A)
    res = client.post(
        "/api/v1/jobs",
        json={
            "session_id": sess_a.id,
            "document_id": doc_a.id,
            "shop_id": shop_b.id,
            "requested_copies": 1,
            "page_count": 1
        },
        headers=hdr_cust_a
    )
    assert res.status_code == 400
    assert "Shop mismatch" in res.json()["error"]["message"]

    # Customer A tries to specify Shop B's printer for a Shop A session -> 400 Validation Error
    res = client.post(
        "/api/v1/jobs",
        json={
            "session_id": sess_a.id,
            "document_id": doc_a.id,
            "shop_id": shop_a.id,
            "printer_id": prn_b.id,  # Belongs to Shop B!
            "requested_copies": 1,
            "page_count": 1
        },
        headers=hdr_cust_a
    )
    assert res.status_code == 400
    assert "does not belong to shop" in res.json()["error"]["message"]

    # Customer B tries to create a job using Customer A's session -> 403 Forbidden
    res = client.post(
        "/api/v1/jobs",
        json={
            "session_id": sess_a.id,
            "document_id": doc_a.id,
            "shop_id": shop_a.id,
            "requested_copies": 1,
            "page_count": 1
        },
        headers=hdr_cust_b
    )
    assert res.status_code == 403
    assert "Session ownership mismatch" in res.json()["error"]["message"]

    # =========================================================================
    # TEST 5: WEBSOCKET REALTIME CHANNEL ISOLATION
    # =========================================================================
    # Device B connects to WS and tries to subscribe to Shop A's channel -> FORBIDDEN
    with client.websocket_connect(f"/api/v1/realtime/ws?token={token_dev_b}") as ws_b:
        _ = ws_b.receive_json()  # connected frame

        ws_b.send_json({"type": "subscribe", "channel": f"shop:{shop_a.id}"})
        err_res = ws_b.receive_json()
        assert err_res["type"] == "error"
        assert err_res["code"] == "FORBIDDEN"

        # Device B tries to subscribe to Shop A's job -> FORBIDDEN
        ws_b.send_json({"type": "subscribe", "channel": f"job:{job_a.id}"})
        err_res = ws_b.receive_json()
        assert err_res["type"] == "error"
        assert err_res["code"] == "FORBIDDEN"

        # Device B tries to subscribe to Station A's device channel -> FORBIDDEN
        ws_b.send_json({"type": "subscribe", "channel": f"device:{dev_a.id}"})
        err_res = ws_b.receive_json()
        assert err_res["type"] == "error"
        assert err_res["code"] == "FORBIDDEN"

    # Operator B connects to WS and tries to subscribe to Shop A's channel -> FORBIDDEN
    with client.websocket_connect(f"/api/v1/realtime/ws?token={token_op_b}") as ws_op_b:
        _ = ws_op_b.receive_json()  # connected frame

        ws_op_b.send_json({"type": "subscribe", "channel": f"shop:{shop_a.id}"})
        err_res = ws_op_b.receive_json()
        assert err_res["type"] == "error"
        assert err_res["code"] == "FORBIDDEN"

    # Device A connects and subscribes to its own shop channel -> SUBSCRIBED
    with client.websocket_connect(f"/api/v1/realtime/ws?token={token_dev_a}") as ws_a:
        _ = ws_a.receive_json()  # connected frame

        ws_a.send_json({"type": "subscribe", "channel": f"shop:{shop_a.id}"})
        sub_res = ws_a.receive_json()
        assert sub_res["type"] == "subscribed"
        assert sub_res["channel"] == f"shop:{shop_a.id}"

        # Device A subscribes to authorized job in its shop -> SUBSCRIBED
        ws_a.send_json({"type": "subscribe", "channel": f"job:{job_a.id}"})
        sub_res = ws_a.receive_json()
        assert sub_res["type"] == "subscribed"
        assert sub_res["channel"] == f"job:{job_a.id}"


@pytest.mark.asyncio
async def test_concurrent_three_shop_isolation(client, db_session):
    """
    PHASE 5 TEST: Full multi-shop concurrent integration test across Shop A, Shop B, and Shop C.
    Verifies that simultaneous print queues and execution pipelines never leak cross-shop jobs.
    """
    user_repo = UserRepository(db_session)
    shop_repo = ShopRepository(db_session)
    device_repo = DeviceRepository(db_session)
    printer_repo = PrinterRepository(db_session)
    session_repo = SessionRepository(db_session)
    doc_repo = DocumentRepository(db_session)
    job_repo = PrintJobRepository(db_session)

    now = datetime.now(timezone.utc)
    shops_data = []

    # Create 3 independent shops with their operators, devices, printers, and customers
    for prefix in ["A", "B", "C"]:
        op = await user_repo.create(
            id=f"usr_op_{prefix.lower()}",
            email=f"operator_{prefix.lower()}@domain.com",
            hashed_password=get_password_hash("Pass123!"),
            role=UserRole.SHOP_OPERATOR.value,
            is_active=True,
            is_verified=True
        )
        shop = await shop_repo.create(
            id=f"SHOP-{prefix}100",
            name=f"Print Express {prefix}",
            owner_id=op.id,
            address=f"{prefix} Street, Suite 100",
            status="ACTIVE",
            is_verified=True,
            permanent_qr_payload=f"privprint://shop?id=SHOP-{prefix}100"
        )
        dev = await device_repo.create(
            id=f"DEV-{prefix}-WIN",
            shop_id=shop.id,
            name=f"Station {prefix}",
            device_type="WINDOWS_STATION",
            is_active=True,
            status="ONLINE"
        )
        await user_repo.create(
            id=dev.id,
            email=f"dev_{prefix.lower()}@device.privprint.local",
            hashed_password=get_password_hash("DevPass!"),
            role=UserRole.PRINT_DEVICE.value,
            is_active=True,
            is_verified=True
        )
        prn = await printer_repo.create(
            id=f"PRN-{prefix}-01",
            shop_id=shop.id,
            name=f"Printer {prefix}",
            model="OfficeJet",
            is_online=True,
            status="ONLINE"
        )
        cust = await user_repo.create(
            id=f"usr_cust_{prefix.lower()}",
            email=f"customer_{prefix.lower()}@domain.com",
            hashed_password=get_password_hash("Pass123!"),
            role=UserRole.USER.value,
            is_active=True,
            is_verified=True
        )
        sess = await session_repo.create(
            id=f"SES-{prefix}-999",
            user_id=cust.id,
            shop_id=shop.id,
            token=f"tok_{prefix.lower()}_999",
            status="ACTIVE",
            expires_at=now + timedelta(minutes=30)
        )
        doc = await doc_repo.create(
            id=f"DOC-{prefix}-999",
            user_id=cust.id,
            session_id=sess.id,
            filename=f"confidential_doc_{prefix}.pdf",
            storage_path=f"docs/doc_{prefix}.enc",
            file_size_bytes=2048,
            sha256_hash="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            iv_hex="00112233445566778899aabbccddeeff",
            key_fingerprint=f"fp_{prefix.lower()}",
            copies_authorized=5,
            expires_at=now + timedelta(minutes=30)
        )
        job = await job_repo.create(
            id=f"PRV-{prefix}-JOB",
            user_id=cust.id,
            shop_id=shop.id,
            session_id=sess.id,
            document_id=doc.id,
            printer_id=prn.id,
            batch_id=f"BAT-{prefix}-999",
            requested_copies=1,
            page_count=2,
            status=PrintJobStatus.AUTHORIZED.value,
            expires_at=now + timedelta(minutes=30)
        )
        dev_token = create_access_token(subject=dev.id, role=UserRole.PRINT_DEVICE.value)
        cust_token = create_access_token(subject=cust.id, role=UserRole.USER.value)
        op_token = create_access_token(subject=op.id, role=UserRole.SHOP_OPERATOR.value)

        shops_data.append({
            "prefix": prefix,
            "shop": shop,
            "device": dev,
            "printer": prn,
            "job": job,
            "dev_headers": {"Authorization": f"Bearer {dev_token}"},
            "cust_headers": {"Authorization": f"Bearer {cust_token}"},
            "op_headers": {"Authorization": f"Bearer {op_token}"},
        })

    await db_session.commit()

    # Verify that each station sees ONLY its own shop's job
    for current in shops_data:
        res = client.get("/api/v1/print/jobs", headers=current["dev_headers"])
        assert res.status_code == 200
        jobs = res.json()
        assert len(jobs) == 1
        assert jobs[0]["id"] == current["job"].id
        assert jobs[0]["shop_id"] == current["shop"].id

        # Verify cross-shop queries are blocked
        for other in shops_data:
            if other["prefix"] != current["prefix"]:
                # Station trying to query other shop queue
                cross_res = client.get(f"/api/v1/print/jobs?shopId={other['shop'].id}", headers=current["dev_headers"])
                assert cross_res.status_code == 403

                # Station trying to inspect other shop's job details
                cross_job = client.get(f"/api/v1/jobs/{other['job'].id}", headers=current["dev_headers"])
                assert cross_job.status_code == 403

                # Station trying to start other shop's job
                cross_start = client.post(f"/api/v1/print/jobs/{other['job'].id}/start", headers=current["dev_headers"])
                assert cross_start.status_code == 403

    # Simulate simultaneous printing across all 3 stations
    for current in shops_data:
        # Start printing
        start_res = client.post(f"/api/v1/print/jobs/{current['job'].id}/start", headers=current["dev_headers"])
        assert start_res.status_code == 200
        assert start_res.json()["status"] == "PRINTING"

        # Update progress
        prog_res = client.post(
            f"/api/v1/print/jobs/{current['job'].id}/progress",
            json={"pages_printed": 2, "completed_copies": 1},
            headers=current["dev_headers"]
        )
        assert prog_res.status_code == 200
        assert prog_res.json()["status"] == "COMPLETED"

    # Verify queues are now clear (completed jobs don't appear in default active queue)
    for current in shops_data:
        res = client.get("/api/v1/print/jobs?status=AUTHORIZED", headers=current["dev_headers"])
        assert res.status_code == 200
        assert len(res.json()) == 0


