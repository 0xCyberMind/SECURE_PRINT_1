#!/usr/bin/env powershell
<#
.SYNOPSIS
    One-command bring-up of the free PrivPrint stack on this laptop.

.DESCRIPTION
    1. Starts Docker Desktop if it is not running.
    2. Builds and starts api + cleanup worker + postgres + redis + minio (server/docker-compose.yml).
    3. Applies database migrations (alembic) and seeds demo shops.
    4. Waits for /healthz and prints the URLs the Android app must use.

    Optional public access (any user, any network):
        .\start_privprint.ps1 -NgrokToken <YOUR_AUTHTOKEN>
    Starts an ngrok tunnel (free static domain, configured once via the token)
    and prints the public HTTPS URL to give to the Android app.

.EXAMPLE
    .\start_privprint.ps1
    .\start_privprint.ps1 -NgrokToken "2abc...xyz"
#>
param(
    [string]$NgrokToken = "",
    [int]$PublicPort = 0
)

$ErrorActionPreference = "Stop"
$composeFile = Join-Path $PSScriptRoot "server\docker-compose.yml"

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

# --- 1. Docker daemon -------------------------------------------------------
Write-Step "Checking Docker daemon"
$dockerProc = Get-Process "Docker Desktop" -ErrorAction SilentlyContinue
if (-not $dockerProc) {
    $paths = @(
        "C:\Program Files\Docker\Docker\Docker.exe",
        "C:\Program Files\Docker\Docker\docker.exe",
        "C:\ProgramData\DockerDesktop\Docker.exe"
    )
    $dockerExe = $paths | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($dockerExe) {
        Write-Host "Starting Docker Desktop..."
        Start-Process -FilePath $dockerExe -WindowStyle Minimized
    } else {
        Write-Warning "Docker Desktop not found. Install it from https://www.docker.com/products/docker-desktop/"
        exit 1
    }
}

$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    docker version --format "{{.Server.Version}}" 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $ready) { Write-Warning "Docker daemon did not become ready."; exit 1 }
Write-Host "Docker daemon ready." -ForegroundColor Green

# --- 2. Build and start the stack -------------------------------------------
Write-Step "Building and starting containers (api, worker, postgres, redis, minio)"
docker compose -f $composeFile up -d --build
if ($LASTEXITCODE -ne 0) { Write-Warning "docker compose failed."; exit 1 }

# --- 3. Migrations + seed ----------------------------------------------------
Write-Step "Applying database migrations (alembic upgrade head)"
docker compose -f $composeFile exec -T api alembic upgrade head

Write-Step "Seeding demo shops (SHOP-101/102/103 with coordinates)"
docker compose -f $composeFile exec -T api python -m app.seed_demo

# --- 4. Health check ---------------------------------------------------------
Write-Step "Waiting for /healthz"
$healthy = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:8080/healthz" -UseBasicParsing -TimeoutSec 3
        if ($resp.StatusCode -eq 200) { $healthy = $true; break }
    } catch { Start-Sleep -Seconds 2 }
}
if ($healthy) {
    Write-Host "API is HEALTHY on http://localhost:8080/healthz" -ForegroundColor Green
} else {
    Write-Warning "API did not become healthy in time. Check: docker compose -f server\docker-compose.yml logs api"
    exit 1
}

# --- 5. LAN IP for the phone --------------------------------------------------
$lanIp = (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254*" -and $_.InterfaceAlias -notmatch "vEthernet|VMware|Loopback" } |
    Select-Object -First 1).IPAddress

Write-Host ""
Write-Host "================ PRIVPRINT LOCAL STACK READY ================" -ForegroundColor Green
Write-Host " API (laptop):        http://localhost:8080"
if ($lanIp) {
    Write-Host " API (phone / Wi-Fi): http://$lanIp:8080"
    Write-Host "   -> Android: .\gradlew installDebug -PDEBUG_API_BASE_URL=http://${lanIp}:8080/"
    Write-Host "   -> Add '$lanIp' to app/src/debug/res/xml/network_security_config.xml if not present."
}
Write-Host " OTP (free dev mode): 123456  (DEVELOPMENT_OTP_ENABLED=true)"
Write-Host "==============================================================" -ForegroundColor Green

# --- 6. Optional public tunnel -----------------------------------------------
if ($NgrokToken -ne "") {
    Write-Step "Configuring ngrok authtoken"
    ngrok config add-authtoken $NgrokToken
    $port = if ($PublicPort -gt 0) { $PublicPort } else { 8080 }
    Write-Step "Starting public tunnel on port $port (keep this window open)"
    Write-Host "Copy the 'Forwarding https://...ngrok-free.app' URL below into the app:" -ForegroundColor Yellow
    ngrok http $port
} else {
    Write-Host ""
    Write-Host "Public access (optional): get a free authtoken at https://dashboard.ngrok.com"
    Write-Host "then run:  .\start_privprint.ps1 -NgrokToken <YOUR_AUTHTOKEN>"
}
