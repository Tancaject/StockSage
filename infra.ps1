param(
    [ValidateSet("up", "stop", "status", "logs")]
    [string]$Action = "up",
    [string]$MilvusComposePath = "D:\milvus\docker-compose.yml",
    [string]$AttuContainer = "attu"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Invoke-Docker {
    param([string[]]$Arguments)

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Assert-Prerequisites {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker CLI was not found on PATH."
    }

    docker info --format '{{.ServerVersion}}' *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Desktop is not running. Start Docker Desktop and retry."
    }

    if (-not (Test-Path -LiteralPath $MilvusComposePath)) {
        throw "Existing Milvus Compose file was not found: $MilvusComposePath"
    }
}

function Test-ContainerExists {
    param([string]$Name)

    $id = docker ps -a --filter "name=^/$Name$" --format '{{.ID}}'
    return -not [string]::IsNullOrWhiteSpace(($id | Select-Object -First 1))
}

function Start-Infrastructure {
    Assert-Prerequisites

    Write-Host "=== Existing Milvus ==="
    Invoke-Docker -Arguments @("compose", "-f", $MilvusComposePath, "up", "-d")

    if (Test-ContainerExists $AttuContainer) {
        Write-Host "=== Existing Attu ==="
        Invoke-Docker -Arguments @("start", $AttuContainer)
    } else {
        Write-Warning "Attu container '$AttuContainer' was not found; Milvus will still be available on port 19530."
    }

    Write-Host "=== StockSage MySQL / Redis / Ollama ==="
    Push-Location $Root
    try {
        Invoke-Docker -Arguments @("compose", "up", "-d")
    } finally {
        Pop-Location
    }

    if (Test-ContainerExists "stocksage-ollama-init") {
        Write-Host "Waiting for the Ollama embedding model..."
        $modelExit = docker wait stocksage-ollama-init
        if ($LASTEXITCODE -ne 0 -or ($modelExit | Select-Object -Last 1) -ne "0") {
            throw "Ollama model initialization failed. Run: docker logs stocksage-ollama-init"
        }
    }

    Show-Status
}

function Stop-Infrastructure {
    Assert-Prerequisites

    if (Test-ContainerExists $AttuContainer) {
        Invoke-Docker -Arguments @("stop", $AttuContainer)
    }

    Invoke-Docker -Arguments @("compose", "-f", $MilvusComposePath, "stop")

    Push-Location $Root
    try {
        Invoke-Docker -Arguments @("compose", "stop")
    } finally {
        Pop-Location
    }
}

function Show-Status {
    Assert-Prerequisites

    Write-Host "=== StockSage infrastructure ==="
    Push-Location $Root
    try {
        Invoke-Docker -Arguments @("compose", "ps", "-a")
    } finally {
        Pop-Location
    }

    Write-Host "=== Existing Milvus ==="
    Invoke-Docker -Arguments @("compose", "-f", $MilvusComposePath, "ps", "-a")

    Write-Host "=== Existing Attu ==="
    if (Test-ContainerExists $AttuContainer) {
        Invoke-Docker -Arguments @("ps", "-a", "--filter", "name=^/$AttuContainer$", "--format", "table {{.Names}}\t{{.Status}}\t{{.Ports}}")
    } else {
        Write-Warning "Attu container '$AttuContainer' was not found."
    }
}

function Show-Logs {
    Assert-Prerequisites

    Push-Location $Root
    try {
        Invoke-Docker -Arguments @("compose", "logs", "--tail", "50")
    } finally {
        Pop-Location
    }
    Invoke-Docker -Arguments @("compose", "-f", $MilvusComposePath, "logs", "--tail", "50")
    if (Test-ContainerExists $AttuContainer) {
        Invoke-Docker -Arguments @("logs", "--tail", "50", $AttuContainer)
    }
}

switch ($Action) {
    "up" { Start-Infrastructure }
    "stop" { Stop-Infrastructure }
    "status" { Show-Status }
    "logs" { Show-Logs }
}
