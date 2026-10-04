param(
    [ValidateSet("fast", "backend", "frontend", "python")]
    [string]$Mode = "fast",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root
$Failures = New-Object System.Collections.Generic.List[string]

function Invoke-HarnessStep {
    param(
        [string]$Name,
        [scriptblock]$Body
    )

    try {
        & $Body
        Write-Host "PASS: $Name"
    } catch {
        $message = $_.Exception.Message
        $Failures.Add("${Name}: ${message}")
        Write-Host "FAIL: $Name"
        Write-Host "  $message"
    }
}

function Assert-Command {
    param([string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found on PATH."
    }
}

function Resolve-Maven {
    $command = Get-Command "mvn" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $candidates = @()
    if ($env:MAVEN_HOME) {
        $candidates += "$env:MAVEN_HOME\bin\mvn.cmd"
        $candidates += "$env:MAVEN_HOME\bin\mvn"
    }
    if ($env:M2_HOME) {
        $candidates += "$env:M2_HOME\bin\mvn.cmd"
        $candidates += "$env:M2_HOME\bin\mvn"
    }
    if ($env:USERPROFILE) {
        $candidates += "$env:USERPROFILE\scoop\shims\mvn.cmd"
        $candidates += "$env:USERPROFILE\scoop\shims\mvn"
    }
    $candidates += "C:\ProgramData\chocolatey\bin\mvn.exe"
    $candidates += "C:\Program Files\Apache\maven\bin\mvn.cmd"

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    $wrapper = "$Root\stocksage-backend\mvnw.cmd"
    if (Test-Path $wrapper) {
        return $wrapper
    }

    throw "Required command 'mvn' was not found on PATH, MAVEN_HOME, M2_HOME, common Scoop/Chocolatey locations, or stocksage-backend\mvnw.cmd."
}

function Resolve-Npm {
    $command = Get-Command "npm.cmd" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $command = Get-Command "npm" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw "Required command 'npm' was not found on PATH."
}

function Resolve-Python {
    $candidates = @()
    $candidates += "C:\Users\Orion\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
    $candidates += "$Root\stocksage-data-service\.venv\Scripts\python.exe"
    $candidates += "$Root\.venv-rag-eval\Scripts\python.exe"
    $candidates += "C:\Users\Orion\AppData\Local\Programs\Python\Python312\python.exe"

    $command = Get-Command "python" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "Required command 'python' was not found on PATH and no known local Python executable exists."
}

function Invoke-CheckedCommand {
    param(
        [string]$FilePath,
        [string[]]$CommandArgs
    )

    & $FilePath @CommandArgs
    $exitCode = $LASTEXITCODE
    if ($null -ne $exitCode -and $exitCode -ne 0) {
        throw "Command failed with exit code ${exitCode}: $FilePath $($CommandArgs -join ' ')"
    }
}

function Invoke-BackendCheck {
    Write-Host "=== Backend: Maven compile ==="
    $maven = Resolve-Maven
    Push-Location "$Root\stocksage-backend"
    try {
        Invoke-CheckedCommand -FilePath $maven -CommandArgs @("compile")
    } finally {
        Pop-Location
    }
}

function Invoke-FrontendCheck {
    Write-Host "=== Frontend: production build ==="
    $npm = Resolve-Npm
    Push-Location "$Root\stocksage-frontend"
    try {
        if (-not $SkipInstall -and -not (Test-Path "node_modules")) {
            Invoke-CheckedCommand -FilePath $npm -CommandArgs @("install")
        }
        Invoke-CheckedCommand -FilePath $npm -CommandArgs @("run", "build")
    } finally {
        Pop-Location
    }
}

function Invoke-PythonCheck {
    Write-Host "=== Data service: python compile smoke ==="
    $python = Resolve-Python
    Invoke-CheckedCommand -FilePath $python -CommandArgs @("-m", "compileall", "$Root\stocksage-data-service\main.py", "$Root\stocksage-data-service\app")
}

Write-Host "=== StockSage harness verification: $Mode ==="

switch ($Mode) {
    "backend" {
        Invoke-HarnessStep "backend" { Invoke-BackendCheck }
    }
    "frontend" {
        Invoke-HarnessStep "frontend" { Invoke-FrontendCheck }
    }
    "python" {
        Invoke-HarnessStep "python" { Invoke-PythonCheck }
    }
    "fast" {
        Invoke-HarnessStep "backend" { Invoke-BackendCheck }
        Invoke-HarnessStep "frontend" { Invoke-FrontendCheck }
        Invoke-HarnessStep "python" { Invoke-PythonCheck }
    }
}

if ($Failures.Count -gt 0) {
    Write-Host "=== Harness verification failed ==="
    foreach ($failure in $Failures) {
        Write-Host "- $failure"
    }
    exit 1
}

Write-Host "=== Harness verification complete ==="
