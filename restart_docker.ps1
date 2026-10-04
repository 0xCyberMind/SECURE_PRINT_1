#!/usr/bin/env powershell
<#
.SYNOPSIS
Restart Docker daemon and verify connectivity.
#>

$dockerService = "com.docker.service"

# Check if Docker Desktop is running
$dockerProc = Get-Process "Docker Desktop" -ErrorAction SilentlyContinue
if (-not $dockerProc) {
    Write-Host "Starting Docker Desktop..." -ForegroundColor Cyan
    # Try common Docker Desktop paths
    $paths = @(
        "C:\Program Files\Docker\Docker\Docker.exe",
        "C:\Program Files\Docker\Docker\docker.exe",
        "C:\ProgramData\DockerDesktop\Docker.exe"
    )
    
    $found = $false
    foreach ($path in $paths) {
        if (Test-Path $path) {
            Start-Process -FilePath $path -WindowStyle Minimized
            $found = $true
            break
        }
    }
    
    if (-not $found) {
        Write-Host "Docker Desktop executable not found in common paths." -ForegroundColor Yellow
        Write-Host "Try starting Docker Desktop manually or checking installation." -ForegroundColor Yellow
        exit 1
    }
} else {
    Write-Host "Docker Desktop is already running" -ForegroundColor Green
}

# Wait for daemon to be ready
Write-Host "Waiting for Docker daemon..." -ForegroundColor Cyan
$maxAttempts = 30
$attempt = 0
while ($attempt -lt $maxAttempts) {
    $versionOutput = (docker version --format "{{.Client.Version}}" 2>&1)
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Docker daemon ready (version: $versionOutput)" -ForegroundColor Green
        break
    }
    $attempt++
    Start-Sleep -Seconds 2
}

if ($attempt -eq $maxAttempts) {
    Write-Host "Docker daemon failed to start after $maxAttempts attempts." -ForegroundColor Red
    exit 1
}

# Verify containers
Write-Host "Checking containers..." -ForegroundColor Cyan
docker compose -f "d:\secure_pri\server\docker-compose.yml" ps
