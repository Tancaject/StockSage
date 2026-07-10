#!/bin/bash
# StockSage Knowledge Base Initialization Script
# Usage: bash ingest.sh
#
# Prerequisites:
#   - Java backend running on localhost:8080
#   - Python data service running on localhost:8001
#   - MySQL, Redis, Milvus all running

BASE_URL="http://localhost:8080"
TIMEOUT=600

echo "============================================"
echo " StockSage Knowledge Base Initialization"
echo "============================================"
echo ""

# Step 1: Ingest local docs (glossary, markdown, PDF)
echo "[1/3] Ingesting local documents from docs/ ..."
result=$(curl -s --max-time $TIMEOUT -X POST "$BASE_URL/api/docs/ingest")
if [ $? -eq 0 ]; then
    echo "  Done: $result"
else
    echo "  Failed. Is the backend running on port 8080?"
    exit 1
fi
echo ""

# Step 2: Ingest SEC EDGAR filings (10-K annual reports)
echo "[2/3] Ingesting SEC 10-K annual reports (10 companies x 3 years) ..."
TICKERS=("AAPL" "NVDA" "MSFT" "TSLA" "GOOGL" "AMZN" "META" "JPM" "JNJ" "XOM")
for ticker in "${TICKERS[@]}"; do
    echo -n "  $ticker 10-K x3 ... "
    result=$(curl -s --max-time $TIMEOUT -X POST "$BASE_URL/api/docs/edgar/ingest?ticker=$ticker&type=10-K&count=3")
    chunks=$(echo "$result" | grep -o '"total_chunks":[0-9]*' | grep -o '[0-9]*')
    echo "${chunks:-0} chunks"
done
echo ""

# Step 3: Ingest SEC EDGAR filings (10-Q quarterly reports)
echo "[3/3] Ingesting SEC 10-Q quarterly reports (10 companies x 2 quarters) ..."
for ticker in "${TICKERS[@]}"; do
    echo -n "  $ticker 10-Q x2 ... "
    result=$(curl -s --max-time $TIMEOUT -X POST "$BASE_URL/api/docs/edgar/ingest?ticker=$ticker&type=10-Q&count=2")
    chunks=$(echo "$result" | grep -o '"total_chunks":[0-9]*' | grep -o '[0-9]*')
    echo "${chunks:-0} chunks"
done
echo ""

echo "============================================"
echo " Initialization complete!"
echo " Total: 10 companies, 30 annual + 20 quarterly filings"
echo " Covers: AAPL NVDA MSFT TSLA GOOGL AMZN META JPM JNJ XOM"
echo "============================================"
