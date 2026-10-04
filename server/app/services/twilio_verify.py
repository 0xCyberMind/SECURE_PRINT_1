import httpx
import logging

from app.core.config import settings


logger = logging.getLogger("privprint.twilio_verify")


class TwilioVerifyError(Exception):
    def __init__(self, message: str, status_code: int | None = None, provider_code: int | None = None):
        super().__init__(message)
        self.status_code = status_code
        self.provider_code = provider_code


def _provider_response_body(response: httpx.Response) -> dict:
    try:
        body = response.json()
    except ValueError:
        body = {}
    provider_code = body.get("code") if isinstance(body, dict) else None
    provider_message = body.get("message") if isinstance(body, dict) else None

    if not response.is_success:
        logger.warning(
            "Twilio Verify request rejected: http_status=%s provider_code=%s",
            response.status_code,
            provider_code,
        )
        raise TwilioVerifyError(
            provider_message or "Twilio rejected the verification request",
            status_code=response.status_code,
            provider_code=provider_code if isinstance(provider_code, int) else None,
        )

    if not isinstance(body, dict):
        raise TwilioVerifyError("Twilio returned an invalid response")
    return body


def is_configured() -> bool:
    return all(
        (
            settings.TWILIO_ACCOUNT_SID,
            settings.TWILIO_AUTH_TOKEN,
            settings.TWILIO_VERIFY_SERVICE_SID,
        )
    )


def _endpoint(resource: str) -> str:
    return (
        "https://verify.twilio.com/v2/Services/"
        f"{settings.TWILIO_VERIFY_SERVICE_SID}/{resource}"
    )


async def start_verification(phone_number: str) -> None:
    try:
        async with httpx.AsyncClient(
            auth=httpx.BasicAuth(
                settings.TWILIO_ACCOUNT_SID,
                settings.TWILIO_AUTH_TOKEN,
            ),
            timeout=10.0,
        ) as client:
            response = await client.post(
                _endpoint("Verifications"),
                data={"To": phone_number, "Channel": "sms"},
            )
            body = _provider_response_body(response)
            if body.get("status") != "pending":
                raise TwilioVerifyError("Twilio did not start the verification")
    except httpx.HTTPError as exc:
        raise TwilioVerifyError("Twilio could not send the verification") from exc


async def check_verification(phone_number: str, code: str) -> bool:
    try:
        async with httpx.AsyncClient(
            auth=httpx.BasicAuth(
                settings.TWILIO_ACCOUNT_SID,
                settings.TWILIO_AUTH_TOKEN,
            ),
            timeout=10.0,
        ) as client:
            response = await client.post(
                _endpoint("VerificationCheck"),
                data={"To": phone_number, "Code": code},
            )
            if response.status_code == 404:
                return False
            body = _provider_response_body(response)
            return body.get("status") == "approved"
    except httpx.HTTPError as exc:
        raise TwilioVerifyError("Twilio could not verify the code") from exc
