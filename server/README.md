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

## Render deployment (current production target)

The Android and Windows clients currently use
**`https://secure-print-1.onrender.com/`**, the Render service URL. Before
releasing clients, verify the service health endpoint at
`https://secure-print-1.onrender.com/healthz`.

`api.privprint.com` is a separate custom-domain/self-hosted Compose target.
Do not switch clients to that hostname until its DNS points to the active
backend and HTTPS `/healthz` responds successfully. The Compose Nginx and
Certbot instructions below apply to a host you operate; they do not configure
Render's custom-domain routing or certificates.

Create a private production environment file from the placeholder-only sample:

```bash
cd server
cp .env.production.example .env.production
chmod 600 .env.production
openssl rand -hex 32
```

Replace every placeholder in `.env.production` with values from the deployment
secret store. Use separate generated hexadecimal values for
`POSTGRES_PASSWORD`, `REDIS_PASSWORD`, and `SECRET_KEY`; configure real Twilio
Verify credentials and non-default object-storage credentials with an HTTPS
storage endpoint. Do not commit or paste the populated file into support logs.
The Compose interpolation values must be supplied to Compose itself; the
service-level `env_file` alone does not supply `${...}` substitutions. The
commands below use `--env-file` for that reason:

```bash
sudo bash deploy/provision-certificate.sh ops@example.com
bash deploy/preflight-production.sh
docker compose --env-file .env.production -f docker-compose.prod.yml up -d --build
```

The certificate command uses Certbot's standalone HTTP challenge. Run it after
the self-hosted custom-domain DNS has propagated and while port 80 is available. The preflight checks the
certificate hostname, expiry and public/private-key match; validates required
Compose substitutions; loads the API's production settings in a one-off
container; and runs `nginx -t`. It does not start or modify the services.
Production startup fails fast if the signing key, database/Redis credentials,
TLS storage settings, Twilio values, debug mode, or development OTP settings
are unsafe or incomplete.

For renewal, configure the host's Certbot timer to run this script as root.
Certbot runs the pre-hook only when renewal is due, while the proxy is stopped
so the standalone challenge can bind port 80. The deploy hook installs the
renewed files and the post-hook starts the proxy again:

```bash
sudo bash deploy/renew-certificate.sh
```

Schedule that command through the host's Certbot/systemd timer and monitor its
result and proxy health after renewal.

## Windows agent scope

The Android module's `WindowsTerminalServer` is a local LAN print-station
server. The standalone Windows station agent is in `windows_agent/` and uses
the backend `/api/v1/devices` endpoints for authentication and heartbeats.

The station registers an RSA public key and protects its private key with
Windows DPAPI. Android wraps each document's AES key for registered stations;
the backend returns ciphertext only to the matching authenticated station for
an authorized, unexpired job. Apply the Alembic migration at
`a31f0de29c7b` and deploy the updated Windows station and Android client
together before enabling this transfer flow in production.
