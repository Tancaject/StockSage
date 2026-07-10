param(
    [switch]$SkipFrontendBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Invoke-Checked {
    param(
        [string]$Command,
        [string[]]$Arguments
    )
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Command failed with exit code $LASTEXITCODE"
    }
}

Write-Host "=== Backend offline demo smoke ==="
Push-Location (Join-Path $root "stocksage-backend")
try {
    Invoke-Checked ".\mvnw.cmd" @("-Dtest=OfflineDemoSampleServiceTest,WorkbenchCockpitServiceTest,ChatServiceOfflineFallbackTest", "test")
} finally {
    Pop-Location
}

Write-Host "=== Frontend workbench smoke ==="
Push-Location (Join-Path $root "stocksage-frontend")
try {
    Invoke-Checked "npm.cmd" @("run", "test")
    if (-not $SkipFrontendBuild) {
        Invoke-Checked "npm.cmd" @("run", "build")
    }
} finally {
    Pop-Location
}

Write-Host "Offline demo smoke passed."
