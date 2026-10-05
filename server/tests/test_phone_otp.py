import pytest
from fastapi.testclient import TestClient

from app.core.config import settings
from app.services import twilio_verify


def test_development_phone_otp_authenticates_user(client: TestClient):
    phone = "+919876543210"
    request = client.post(
        "/api/v1/auth/phone/request-otp",
        json={"phone_number": phone, "role": "USER"},
    )

    assert request.status_code == 200, request.text
    assert request.json()["development_otp"] == "123456"

    verification = client.post(
        "/api/v1/auth/phone/verify-otp",
        json={"phone_number": phone, "otp": "123456", "role": "USER"},
    )

    assert verification.status_code == 200, verification.text
    assert verification.json()["user"]["role"] == "USER"
    assert verification.json()["access_token"]


def test_twilio_phone_otp_uses_provider_and_normalized_number(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
):
    monkeypatch.setattr(settings, "TWILIO_ACCOUNT_SID", "AC-test")
    monkeypatch.setattr(settings, "TWILIO_AUTH_TOKEN", "test-token")
    monkeypatch.setattr(settings, "TWILIO_VERIFY_SERVICE_SID", "VA-test")
    sent_numbers = []
    checked_codes = []

    async def start_verification(phone_number: str) -> None:
        sent_numbers.append(phone_number)

    async def check_verification(phone_number: str, code: str) -> bool:
        checked_codes.append((phone_number, code))
        return code == "654321"

    monkeypatch.setattr(twilio_verify, "start_verification", start_verification)
    monkeypatch.setattr(twilio_verify, "check_verification", check_verification)

    request = client.post(
        "/api/v1/auth/phone/request-otp",
        json={"phone_number": "+91 98765-43210", "role": "USER"},
    )

    assert request.status_code == 200, request.text
    assert request.json()["development_otp"] is None
    assert sent_numbers == ["+919876543210"]

    verification = client.post(
        "/api/v1/auth/phone/verify-otp",
        json={
            "phone_number": "+91 (98765) 43210",
            "otp": "654321",
            "role": "USER",
        },
    )

    assert verification.status_code == 200, verification.text
    assert checked_codes == [("+919876543210", "654321")]


def test_shop_operator_authentication_does_not_depend_on_phone_otp(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
):
    monkeypatch.setattr(settings, "TWILIO_ACCOUNT_SID", "AC-test")
    monkeypatch.setattr(settings, "TWILIO_AUTH_TOKEN", "test-token")
    monkeypatch.setattr(settings, "TWILIO_VERIFY_SERVICE_SID", "VA-test")
    def fail_if_twilio_used(*args, **kwargs):
        pytest.fail("Shop password registration must not call Twilio")

    monkeypatch.setattr(twilio_verify, "start_verification", fail_if_twilio_used)
    monkeypatch.setattr(twilio_verify, "check_verification", fail_if_twilio_used)

    request = client.post(
        "/api/v1/auth/phone/request-otp",
        json={
            "phone_number": "+919876543210",
            "role": "SHOP_OPERATOR",
            "shop_id": "SHOP-TEST",
        },
    )
    assert request.status_code == 403, request.text
    assert request.json()["error"]["message"] == (
        "Shop accounts use email and password; phone OTP is not supported"
    )

    verify = client.post(
        "/api/v1/auth/phone/verify-otp",
        json={
            "phone_number": "+919876543210",
            "otp": "654321",
            "role": "SHOP_OPERATOR",
            "shop_id": "SHOP-TEST",
        },
    )
    assert verify.status_code == 403, verify.text
