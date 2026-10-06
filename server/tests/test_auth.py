import pytest
from datetime import timedelta
from fastapi.testclient import TestClient
from app.core.security import create_access_token
from app.models.enums import UserRole
from app.repositories.user_repo import UserRepository
from app.repositories.shop_repo import ShopRepository, DeviceRepository, PrinterRepository
from app.repositories.document_repo import DocumentRepository
from app.repositories.print_job_repo import PrintJobRepository


def test_register_and_valid_login(client: TestClient):
    # 1. Register new user
    register_payload = {
        "email": "auth_test_user@example.com",
        "password": "SecurePassword123!",
        "full_name": "Auth Tester",
        "phone_number": "+1234567890"
    }
    reg_res = client.post("/api/v1/auth/register", json=register_payload)
    assert reg_res.status_code == 201, reg_res.text
    reg_data = reg_res.json()
    assert "access_token" in reg_data
    assert "refresh_token" in reg_data
    assert reg_data["user"]["email"] == "auth_test_user@example.com"
    assert reg_data["user"]["role"] == "USER"

    # 2. Login with valid password
    login_payload = {
        "email": "auth_test_user@example.com",
        "password": "SecurePassword123!"
    }
    login_res = client.post("/api/v1/auth/login", json=login_payload)
    assert login_res.status_code == 200
    login_data = login_res.json()
    assert "access_token" in login_data
    assert "refresh_token" in login_data
    assert login_data["user"]["email"] == "auth_test_user@example.com"


def test_phone_otp_endpoints_are_removed(client: TestClient):
    request_response = client.post(
        "/api/v1/auth/phone/request-otp",
        json={"phone_number": "+1234567890", "role": "USER"},
    )
    verify_response = client.post(
        "/api/v1/auth/phone/verify-otp",
        json={"phone_number": "+1234567890", "otp": "123456", "role": "USER"},
    )

    assert request_response.status_code == 404
    assert verify_response.status_code == 404


def test_invalid_password_rejection(client: TestClient):
    # Register user first
    client.post("/api/v1/auth/register", json={
        "email": "wrong_pw_user@example.com",
        "password": "CorrectPassword123!"
    })

    # Attempt login with wrong password
    bad_res = client.post("/api/v1/auth/login", json={
        "email": "wrong_pw_user@example.com",
        "password": "IncorrectPassword123!"
    })
    assert bad_res.status_code == 401
    bad_data = bad_res.json()
    assert bad_data["error"]["code"] == "AUTH_INVALID"


def test_expired_token_rejection(client: TestClient):
    # Register user
    reg = client.post("/api/v1/auth/register", json={
        "email": "expired_test@example.com",
        "password": "Password123!"
    }).json()
    user_id = reg["user"]["id"]

    # Generate an expired token
    expired_token = create_access_token(
        subject=user_id,
        role="USER",
        expires_delta=timedelta(seconds=-60)
    )

    # Attempt to access protected /me endpoint with expired token
    res = client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {expired_token}"}
    )
    assert res.status_code == 401
    data = res.json()
    assert data["error"]["code"] == "TOKEN_EXPIRED"


def test_refresh_token_rotation_and_replay_detection(client: TestClient):
    # 1. Register user and obtain initial refresh token
    reg = client.post("/api/v1/auth/register", json={
        "email": "rotation_user@example.com",
        "password": "Password123!"
    }).json()

    initial_refresh = reg["refresh_token"]

    # 2. Perform valid refresh rotation
    rot_res1 = client.post("/api/v1/auth/refresh", json={"refresh_token": initial_refresh})
    assert rot_res1.status_code == 200
    rot_data1 = rot_res1.json()
    new_refresh = rot_data1["refresh_token"]
    assert new_refresh != initial_refresh

    # 3. Perform second rotation with the NEW refresh token -> SUCCESS
    rot_res2 = client.post("/api/v1/auth/refresh", json={"refresh_token": new_refresh})
    assert rot_res2.status_code == 200

    # 4. REPLAY ATTACK DETECTION:
    # Attempt to replay the initial (already rotated & revoked) refresh token!
    replay_res = client.post("/api/v1/auth/refresh", json={"refresh_token": initial_refresh})
    assert replay_res.status_code == 401
    replay_data = replay_res.json()
    assert replay_data["error"]["code"] == "TOKEN_REPLAY_DETECTED"

    # 5. Verify all active sessions were invalidated after replay detection
    rot_res3 = client.post("/api/v1/auth/refresh", json={"refresh_token": new_refresh})
    assert rot_res3.status_code == 401


def test_logout_and_revocation(client: TestClient):
    reg = client.post("/api/v1/auth/register", json={
        "email": "logout_test@example.com",
        "password": "Password123!"
    }).json()

    access_token = reg["access_token"]
    refresh_token = reg["refresh_token"]

    # Logout
    logout_res = client.post(
        "/api/v1/auth/logout",
        json={"refresh_token": refresh_token},
        headers={"Authorization": f"Bearer {access_token}"}
    )
    assert logout_res.status_code == 200
    assert logout_res.json()["success"] is True

    # Attempting to refresh with the logged-out token must be rejected
    ref_res = client.post("/api/v1/auth/refresh", json={"refresh_token": refresh_token})
    assert ref_res.status_code == 401


def test_role_based_authorization_restrictions(client: TestClient):
    # 1. Standard USER
    user_res = client.post("/api/v1/auth/register", json={
        "email": "normal_user@example.com",
        "password": "Password123!",
        "role": "USER"
    }).json()
    user_token = user_res["access_token"]

    # 2. SHOP_OPERATOR
    op_res = client.post("/api/v1/auth/register", json={
        "email": "operator_user@example.com",
        "password": "Password123!",
        "role": "SHOP_OPERATOR"
    }).json()
    op_token = op_res["access_token"]

    # Public registration must never grant the ADMIN role.
    admin_res = client.post("/api/v1/auth/register", json={
        "email": "admin_user@example.com",
        "password": "Password123!",
        "role": "ADMIN"
    })
    assert admin_res.status_code == 403
    assert admin_res.json()["error"]["code"] == "FORBIDDEN"

    # USER calls /admin-only -> 403 FORBIDDEN
    res_user_admin = client.get("/api/v1/auth/admin-only", headers={"Authorization": f"Bearer {user_token}"})
    assert res_user_admin.status_code == 403
    assert res_user_admin.json()["error"]["code"] == "FORBIDDEN"

    # USER calls /operator-only -> 403 FORBIDDEN
    res_user_op = client.get("/api/v1/auth/operator-only", headers={"Authorization": f"Bearer {user_token}"})
    assert res_user_op.status_code == 403

    # SHOP_OPERATOR calls /operator-only -> 200 OK
    res_op = client.get("/api/v1/auth/operator-only", headers={"Authorization": f"Bearer {op_token}"})
    assert res_op.status_code == 200
    assert res_op.json()["status"] == "ok"

    # SHOP_OPERATOR calls /admin-only -> 403 FORBIDDEN
    res_op_admin = client.get("/api/v1/auth/admin-only", headers={"Authorization": f"Bearer {op_token}"})
    assert res_op_admin.status_code == 403

    # The rejected registration response contains no administrator access token.
    assert "access_token" not in admin_res.json()


@pytest.mark.asyncio
async def test_cross_user_and_cross_shop_access_prevention(db_session):
    user_repo = UserRepository(db_session)
    shop_repo = ShopRepository(db_session)
    device_repo = DeviceRepository(db_session)
    printer_repo = PrinterRepository(db_session)
    doc_repo = DocumentRepository(db_session)
    job_repo = PrintJobRepository(db_session)

    # User 1 & User 2
    u1 = await user_repo.create(email="u1@test.com", hashed_password="pw", role="USER")
    u2 = await user_repo.create(email="u2@test.com", hashed_password="pw", role="USER")

    # Shop 1 & Shop 2
    s1 = await shop_repo.create(id="SH-01", name="Shop One", address="Addr 1", permanent_qr_payload="p1")
    s2 = await shop_repo.create(id="SH-02", name="Shop Two", address="Addr 2", permanent_qr_payload="p2")

    # Printers & Devices
    prn1 = await printer_repo.create(id="PRN-S1", shop_id=s1.id, name="Prn 1", model="M")
    dev1 = await device_repo.create(id="DEV-S1", shop_id=s1.id, name="Dev 1")

    # Document belonging to User 1
    doc1 = await doc_repo.create(
        id="DOC-U1",
        user_id=u1.id,
        session_id="S-01",
        filename="u1.pdf",
        storage_path="p",
        file_size_bytes=100,
        sha256_hash="h",
        iv_hex="iv",
        key_fingerprint="fp",
        copies_authorized=1,
        expires_at=u1.created_at
    )

    # Job belonging to User 1 at Shop 1
    job1 = await job_repo.create(
        id="JOB-U1-S1",
        user_id=u1.id,
        shop_id=s1.id,
        session_id="S-01",
        document_id=doc1.id,
        requested_copies=1,
        expires_at=u1.created_at
    )

    # 1. CROSS-USER ISOLATION:
    # User 2 must NOT access User 1's document
    assert await doc_repo.get_by_id_and_user(doc1.id, user_id=u2.id) is None
    # User 1 CAN access User 1's document
    assert await doc_repo.get_by_id_and_user(doc1.id, user_id=u1.id) is not None

    # User 2 must NOT access User 1's job
    assert await job_repo.get_by_id_and_user(job1.id, user_id=u2.id) is None
    # User 1 CAN access User 1's job
    assert await job_repo.get_by_id_and_user(job1.id, user_id=u1.id) is not None

    # 2. CROSS-SHOP ISOLATION:
    # Shop 2 operator must NOT access Shop 1's printer
    assert await printer_repo.get_by_id_and_shop(prn1.id, shop_id=s2.id) is None
    # Shop 1 operator CAN access Shop 1's printer
    assert await printer_repo.get_by_id_and_shop(prn1.id, shop_id=s1.id) is not None

    # Shop 2 operator must NOT access Shop 1's device
    assert await device_repo.get_by_id_and_shop(dev1.id, shop_id=s2.id) is None
    # Shop 1 operator CAN access Shop 1's device
    assert await device_repo.get_by_id_and_shop(dev1.id, shop_id=s1.id) is not None

    # Shop 2 operator must NOT access job submitted to Shop 1
    assert await job_repo.get_by_id_and_shop(job1.id, shop_id=s2.id) is None
    # Shop 1 operator CAN access job submitted to Shop 1
    assert await job_repo.get_by_id_and_shop(job1.id, shop_id=s1.id) is not None
