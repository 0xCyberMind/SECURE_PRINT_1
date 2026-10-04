import httpx

from app.core.config import settings


class TwilioVerifyError(Exception):
    pass


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
            response.raise_for_status()
            if response.json().get("status") != "pending":
                raise TwilioVerifyError("Twilio did not start the verification")
    except (httpx.HTTPError, ValueError) as exc:
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
            response.raise_for_status()
            return response.json().get("status") == "approved"
    except (httpx.HTTPError, ValueError) as exc:
        raise TwilioVerifyError("Twilio could not verify the code") from exc
