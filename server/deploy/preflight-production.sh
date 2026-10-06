#!/usr/bin/env bash
set -euo pipefail

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_DIR="$SERVER_DIR/deploy/certs"
COMPOSE_FILE="$SERVER_DIR/docker-compose.prod.yml"
ENV_FILE="$SERVER_DIR/.env.production"

cd "$SERVER_DIR"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing server/.env.production. Copy .env.production.example and set production secrets." >&2
  exit 1
fi

if [[ ! -s "$CERT_DIR/fullchain.pem" || ! -s "$CERT_DIR/privkey.pem" ]]; then
  echo "TLS files missing; provision deploy/certs/fullchain.pem and privkey.pem first." >&2
  exit 1
fi

openssl x509 -in "$CERT_DIR/fullchain.pem" -noout -checkhost api.privprint.com
if ! openssl x509 -in "$CERT_DIR/fullchain.pem" -noout -checkend 604800 >/dev/null; then
  echo "TLS certificate expires within 7 days; renew it before deployment." >&2
  exit 1
fi

CERT_PUBLIC_KEY="$(
  openssl x509 -in "$CERT_DIR/fullchain.pem" -pubkey -noout |
    openssl pkey -pubin -outform DER |
    openssl dgst -sha256
)"
PRIVATE_PUBLIC_KEY="$(
  openssl pkey -in "$CERT_DIR/privkey.pem" -pubout -outform DER |
    openssl dgst -sha256
)"
if [[ "$CERT_PUBLIC_KEY" != "$PRIVATE_PUBLIC_KEY" ]]; then
  echo "TLS certificate and private key do not match." >&2
  exit 1
fi

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" run \
  --build --rm --no-deps --entrypoint python api \
  -c 'from app.core.config import settings; assert settings.ENVIRONMENT.value == "production"; print("Production application configuration is valid.")'
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" run \
  --rm --no-deps reverse_proxy nginx -t

echo "Production preflight passed. No services were started or modified."
