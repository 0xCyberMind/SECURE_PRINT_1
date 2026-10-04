import base64

import pytest
import httpx

from app.core.config import settings
from app.services import twilio_verify


class StubAsyncClient:
    status_code = 200
    response_body = {"status": "pending"}
    requests = []

    def __init__(self, *, auth, timeout):
        self.auth = auth
        self.timeout = timeout

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return None

    async def post(self, url, data):
        self.requests.append((url, data, self.auth, self.timeout))
        return httpx.Response(
            self.status_code,
            json=self.response_body,
            request=httpx.Request("POST", url),
        )


@pytest.fixture
def twilio_settings(monkeypatch):
    monkeypatch.setattr(settings, "TWILIO_ACCOUNT_SID", "AC-test")
    monkeypatch.setattr(settings, "TWILIO_AUTH_TOKEN", "test-token")
    monkeypatch.setattr(settings, "TWILIO_VERIFY_SERVICE_SID", "VA-test")
    monkeypatch.setattr(twilio_verify.httpx, "AsyncClient", StubAsyncClient)
    StubAsyncClient.requests = []
    StubAsyncClient.status_code = 200
    StubAsyncClient.response_body = {"status": "pending"}


@pytest.mark.asyncio
async def test_start_verification_sends_sms_to_twilio(twilio_settings):
    await twilio_verify.start_verification("+919876543210")

    url, data, auth, timeout = StubAsyncClient.requests[0]
    assert url.endswith("/Services/VA-test/Verifications")
    assert data == {"To": "+919876543210", "Channel": "sms"}
    encoded_credentials = base64.b64encode(b"AC-test:test-token").decode()
    authenticated_request = next(auth.auth_flow(httpx.Request("POST", url)))
    assert authenticated_request.headers["Authorization"] == f"Basic {encoded_credentials}"
    assert timeout == 10.0


@pytest.mark.asyncio
async def test_check_verification_accepts_approved_code(twilio_settings):
    StubAsyncClient.response_body = {"status": "approved"}

    assert await twilio_verify.check_verification("+919876543210", "123456")
    assert StubAsyncClient.requests[0][0].endswith("/Services/VA-test/VerificationCheck")


@pytest.mark.asyncio
async def test_check_verification_rejects_invalid_code(twilio_settings):
    StubAsyncClient.status_code = 404
    StubAsyncClient.response_body = {"code": 20404}

    assert not await twilio_verify.check_verification("+919876543210", "000000")


@pytest.mark.asyncio
async def test_start_verification_preserves_safe_provider_error_metadata(twilio_settings):
    StubAsyncClient.status_code = 403
    StubAsyncClient.response_body = {
        "code": 60200,
        "message": "Provider rejected request",
        "phone_number": "+919876543210",
    }

    with pytest.raises(twilio_verify.TwilioVerifyError) as error:
        await twilio_verify.start_verification("+919876543210")

    assert error.value.status_code == 403
    assert error.value.provider_code == 60200
    assert str(error.value) == "Provider rejected request"
    assert "+919876543210" not in str(error.value)
