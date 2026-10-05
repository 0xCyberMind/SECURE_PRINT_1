$ErrorActionPreference = "Stop"

$agentDir = $PSScriptRoot
$repoRoot = Split-Path -Parent $agentDir
$entryPoint = Join-Path $agentDir "main.py"
$distDir = Join-Path $agentDir "dist"
$workDir = Join-Path $agentDir "build"
$python = Get-Command python -ErrorAction Stop

Push-Location $repoRoot
try {
    & $python.Source -m PyInstaller `
        --noconfirm `
        --clean `
        --onefile `
        --noconsole `
        --name PrivPrintStationWorker `
        --paths $repoRoot `
        --distpath $distDir `
        --workpath $workDir `
        --specpath $workDir `
        --hidden-import win32crypt `
        --hidden-import win32print `
        --collect-all uvicorn `
        --collect-all websockets `
        --collect-all cryptography `
        $entryPoint
    if ($LASTEXITCODE -ne 0) {
        throw "PyInstaller failed with exit code $LASTEXITCODE"
    }

    Copy-Item (Join-Path $agentDir "shop_station_config.example.json") $distDir -Force
    Copy-Item (Join-Path $agentDir "README.md") $distDir -Force
    Compress-Archive `
        -LiteralPath (Join-Path $distDir "PrivPrintStationWorker.exe"), (Join-Path $distDir "shop_station_config.example.json"), (Join-Path $distDir "README.md") `
        -DestinationPath (Join-Path $distDir "PrivPrintStationWorker.zip") `
        -Force
    Write-Output "Created station worker: $(Join-Path $distDir 'PrivPrintStationWorker.exe')"
    Write-Output "Sample configuration: $(Join-Path $distDir 'shop_station_config.example.json')"
    Write-Output "Download bundle: $(Join-Path $distDir 'PrivPrintStationWorker.zip')"
}
finally {
    Pop-Location
}
