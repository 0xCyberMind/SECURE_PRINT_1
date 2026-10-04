# PrivPrint Cloud Backend

Production-ready FastAPI backend for the PrivPrint zero-knowledge printing ecosystem.

## Features
- **FastAPI Framework**: High performance, type-safe API with automatic OpenAPI documentation.
- **Environment Management**: Strict separation of `development`, `staging`, and `production`.
- **Structured JSON Logging**: Traceable with correlation/request IDs across every request.
- **Standardized Error Responses**: Uniform error schema with distinct error codes and request IDs.
- **CORS & Correlation ID Middleware**: Request tracking and secure origins enforcement.
- **SQLAlchemy 2.0 Async Ready**: Prepared for PostgreSQL connection pooling and migrations.
- **JWT & Role Authentication**: Declarative role-based security dependencies.

## Setup & Running

```bash
# 1. Create and activate virtual environment
python3 -m venv venv
source venv/bin/activate

# 2. Install dependencies
pip install -r requirements.txt

# 3. Configure environment
cp .env.example .env

# 4. Run application
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

## Running Tests

```bash
pytest tests/ -v
```

## Phone OTP configuration

Local development uses the fixed OTP `123456` only when Twilio Verify is not
configured. For staging or production, create a Twilio Verify Service and set
`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, and `TWILIO_VERIFY_SERVICE_SID` in
the deployment platform's secret/environment settings. Do not commit these
values or put them in source control. Phone numbers sent to Twilio must include
their country code (E.164 format, for example `+919876543210`).

## Windows agent scope

The Windows terminal implementation currently lives in the Android module as
`WindowsTerminalServer`. It is a local LAN print-station server, not a separately
deployable Windows service. Production device authentication and heartbeats use
the backend `/api/v1/devices` endpoints; a production Windows deployment must
run a dedicated agent process that stores the returned device API key securely
and never expose the local server outside the shop LAN.
