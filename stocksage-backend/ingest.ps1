# StockSage Knowledge Base Initialization Script
# Usage: powershell -File ingest.ps1
#
# Prerequisites:
#   - Java backend running on localhost:8080
#   - Python data service running on localhost:8001
#   - MySQL, Redis, Milvus, Ollama all running

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string[]]$Tickers = @("AAPL","NVDA","MSFT","TSLA","GOOGL","AMZN","META","JPM","JNJ","XOM"),
    [int]$AnnualCount = 3,
    [int]$QuarterlyCount = 2,
    [int]$TimeoutSec = 3600
)

Write-Host "============================================"
Write-Host " StockSage Knowledge Base Initialization"
Write-Host "============================================"
Write-Host ""

# Step 1: Ingest local docs
Write-Host "[1/3] Ingesting local documents from docs/ ..."
try {
    $r = Invoke-RestMethod -Uri "$BaseUrl/api/docs/ingest" -Method Post -TimeoutSec $TimeoutSec
    Write-Host "  Done: $r"
} catch {
    Write-Host "  Failed: $($_.Exception.Message)"
}
Write-Host ""

# Step 2: Ingest SEC EDGAR 10-K annual reports
Write-Host "[2/3] Ingesting SEC 10-K annual reports ($($Tickers.Count) companies x $AnnualCount filings) ..."
foreach ($t in $Tickers) {
    Write-Host -NoNewline "  $t 10-K x$AnnualCount ... "
    try {
        $r = Invoke-RestMethod -Uri "$BaseUrl/api/docs/edgar/ingest?ticker=$t&type=10-K&count=$AnnualCount" -Method Post -TimeoutSec $TimeoutSec
        Write-Host "$($r.total_chunks) chunks"
    } catch {
        Write-Host "FAILED: $($_.Exception.Message)"
    }
}
Write-Host ""

# Step 3: Ingest SEC EDGAR 10-Q quarterly reports
Write-Host "[3/3] Ingesting SEC 10-Q quarterly reports ($($Tickers.Count) companies x $QuarterlyCount filings) ..."
foreach ($t in $Tickers) {
    Write-Host -NoNewline "  $t 10-Q x$QuarterlyCount ... "
    try {
        $r = Invoke-RestMethod -Uri "$BaseUrl/api/docs/edgar/ingest?ticker=$t&type=10-Q&count=$QuarterlyCount" -Method Post -TimeoutSec $TimeoutSec
        Write-Host "$($r.total_chunks) chunks"
    } catch {
        Write-Host "FAILED: $($_.Exception.Message)"
    }
}
Write-Host ""

Write-Host "============================================"
Write-Host " Initialization complete!"
Write-Host " Total: $($Tickers.Count) companies, $($Tickers.Count * $AnnualCount) annual + $($Tickers.Count * $QuarterlyCount) quarterly filings"
Write-Host " Covers: $($Tickers -join ' ')"
Write-Host "============================================"
