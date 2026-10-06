#!/usr/bin/env bash
set -euo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run this script as root so Certbot and the private key remain protected." >&2
  exit 1
fi
if ! command -v certbot >/dev/null 2>&1; then
  echo "Certbot is required; install it from your operating system's trusted package source." >&2
  exit 1
fi

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SERVER_DIR"
if [[ ! -f .env.production ]]; then
  echo "Missing server/.env.production; refusing to operate on the production proxy." >&2
  exit 1
fi

certbot renew --cert-name api.privprint.com \
  --pre-hook "docker compose --env-file .env.production -f docker-compose.prod.yml stop reverse_proxy" \
  --deploy-hook "install -o root -g root -m 0644 /etc/letsencrypt/live/api.privprint.com/fullchain.pem deploy/certs/fullchain.pem && install -o root -g root -m 0600 /etc/letsencrypt/live/api.privprint.com/privkey.pem deploy/certs/privkey.pem" \
  --post-hook "docker compose --env-file .env.production -f docker-compose.prod.yml start reverse_proxy"
