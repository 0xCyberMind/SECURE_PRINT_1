#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  echo "Usage: sudo bash deploy/provision-certificate.sh <certificate-contact-email>" >&2
  exit 2
fi
if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run this script as root so it can install the private key securely." >&2
  exit 1
fi
if ! command -v certbot >/dev/null 2>&1; then
  echo "Certbot is required; install it from your operating system's trusted package source." >&2
  exit 1
fi

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERTBOT_DIR="/etc/letsencrypt/live/api.privprint.com"
CERT_DIR="$SERVER_DIR/deploy/certs"

certbot certonly --standalone --non-interactive --agree-tos \
  --email "$1" --cert-name api.privprint.com -d api.privprint.com

install -d -o root -g root -m 0750 "$CERT_DIR"
install -o root -g root -m 0644 "$CERTBOT_DIR/fullchain.pem" "$CERT_DIR/fullchain.pem"
install -o root -g root -m 0600 "$CERTBOT_DIR/privkey.pem" "$CERT_DIR/privkey.pem"
openssl x509 -in "$CERT_DIR/fullchain.pem" -noout -checkhost api.privprint.com

echo "Certificate installed. Run bash deploy/preflight-production.sh before starting the stack."
