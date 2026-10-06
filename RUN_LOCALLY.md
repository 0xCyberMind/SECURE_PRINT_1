# Run PrivPrint 100% Free on Your Laptop (and give it a Public URL)

This guide runs the **entire PrivPrint platform for free**: API, PostgreSQL, Redis,
object storage, cleanup worker, email/password login, and — optionally — a public
HTTPS URL so **any user on any network** (mobile data included) can use the app.

Nothing here requires a paid cloud service.

---

## 1. One-command start (laptop)

Requirements: **Docker Desktop** ([download](https://www.docker.com/products/docker-desktop/)).

```powershell
.\start_privprint.ps1
```

The script starts Docker, builds and launches the stack, applies migrations,
seeds demo shops, waits for the health check, and prints the URLs your phone needs.

What it starts (from [server/docker-compose.yml](server/docker-compose.yml)):

| Service | Container | Port | Purpose |
|---|---|---|---|
| FastAPI | privprint-api-dev | **8080** | The `/api/v1` backend |
| Cleanup worker | privprint-worker-dev | — | Verified document shredding sweep |
| PostgreSQL 15 | privprint-db-dev | 5432 | Real database (all business data) |
| Redis 7 | privprint-redis-dev | 6379 | Rate limits and realtime pub/sub |
| MinIO | privprint-minio-dev | 9000/9001 | S3-compatible ciphertext storage |

Config lives in [server/.env.development](server/.env.development) (gitignored).
It already contains a generated `SECRET_KEY` and compose-service hostnames.

Useful commands:

```powershell
docker compose -f server\docker-compose.yml logs -f api   # follow API logs
docker compose -f server\docker-compose.yml down          # stop (data persists in the db container/volume)
docker compose -f server\docker-compose.yml down -v       # stop AND wipe data
```

> **Never touch it again:** Docker Desktop has *Start when you log in* enabled by
> default and every service uses `restart: unless-stopped`, so the stack comes
> back after reboot. Re-run `.\start_privprint.ps1` only after code changes.

---

## 2. Email/password login

Customer and shop-operator accounts sign in using the email and password
entered during account registration. Phone OTP and Twilio Verify are not part
of the current authentication flow.

---

## 3. Connect the Android app (same Wi-Fi)

**Emulator:** the emulator reaches your laptop as `10.0.2.2`:

```powershell
.\gradlew installDebug -PDEBUG_API_BASE_URL=http://10.0.2.2:8080/
```

**Real phone (same Wi-Fi):** find the laptop's Wi-Fi IP
(`ipconfig` → *Wireless LAN adapter Wi-Fi* → IPv4, e.g. `10.49.14.50`), then:

1. Add that IP to the debug cleartext allowlist:
   [app/src/debug/res/xml/network_security_config.xml](app/src/debug/res/xml/network_security_config.xml)
   (already contains `10.49.14.50` — update it if your router assigns a new IP).
2. Build and install:
   ```powershell
   .\gradlew installDebug -PDEBUG_API_BASE_URL=http://10.49.14.50:8080/
   ```
3. In the app: **Settings → API Environment** can also switch endpoints at runtime.

Then: sign in with email/password → scan QR (or tap a nearby shop) → pick a document →
print. The job, copy limits, and cleanup are enforced by the real server.

---

## 4. Public access — every user, any network (free)

Your laptop is only reachable on your LAN by default. To open it to the world
**without router configuration and for free**, use a tunnel.

### Option A — ngrok free static domain (recommended start, ~5 minutes)

1. Create a free account at [dashboard.ngrok.com](https://dashboard.ngrok.com)
   and copy your **authtoken** (a free static domain is assigned automatically).
2. Start everything with the tunnel:
   ```powershell
   .\start_privprint.ps1 -NgrokToken "2abc...your_token"
   ```
3. Copy the printed `https://<your-static-domain>.ngrok-free.app` URL.
4. Point the app at it (HTTPS, works on any network):
   ```powershell
   .\gradlew installDebug -PDEBUG_API_BASE_URL=https://<your-static-domain>.ngrok-free.app/
   ```

Free-tier note: ~1 GB/month transfer — perfect for demos and testing.

### Option B — Cloudflare Tunnel (unlimited transfer, needs any domain)

```powershell
cloudflared tunnel --url http://localhost:8080
```

Free, stable HTTPS + WebSocket (realtime) support. Requires a Cloudflare
account + a domain added to it.

### Option C — Oracle Cloud "Always Free" VPS (true 24/7 production)

When you outgrow the laptop, lift the **same code** onto Oracle's always-free
VM and run the production stack — your laptop stops being a server entirely:

```bash
# on the VPS
git clone <your-repo> && cd privprint/server
cp .env.example .env.production   # fill secrets and a strong SECRET_KEY
docker compose -f docker-compose.prod.yml up -d
```

`docker-compose.prod.yml` + [deploy/nginx.conf](server/deploy/nginx.conf) already
provide TLS, security headers, WSS, and rate limiting. Zero code changes.

### Reminder before going public

- `SECRET_KEY` regenerated (never reuse the committed dev key).
- Postgres/Redis/MinIO stay **unexposed** — only the API goes through the tunnel.

---

## 5. Backups & operations

- Nightly DB dump (prod naming): [server/deploy/backup_db.sh](server/deploy/backup_db.sh)
  — for the dev stack run:
  ```powershell
  docker exec -t privprint-db-dev pg_dump -U privprint_dev privprint_dev_db | gzip > backup_$(Get-Date -Format yyyyMMdd).sql.gz
  ```
- Logs are structured JSON with automatic secret redaction.
- Login/register/session/upload endpoints are rate limited (Redis).

## 6. Tests

```powershell
cd server ; python -m pytest tests/ -v        # 87 backend tests
python -m pytest windows_agent/tests/ -v      # 11 agent tests
.\gradlew :app:testDebugUnitTest              # Android (Robolectric) suite
```

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| `healthz` unreachable | `docker compose -f server\docker-compose.yml logs api` |
| Phone can't reach laptop | Phone on same Wi-Fi; laptop IP in debug `network_security_config.xml`; Windows Firewall allow port 8080 for private networks |
| Login or rate limit fails | Redis container down: `docker compose -f server\docker-compose.yml up -d redis` |
| App shows local-only (offline) behavior | App is in a fallback mode — check the API base URL and that the phone can open `/healthz` |
