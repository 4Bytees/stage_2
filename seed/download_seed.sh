#!/usr/bin/env bash
# ==============================================================================
# SEED DATASET INGESTION SCRIPT - BASH
# ==============================================================================

INGESTION_URL="${1:-http://localhost:7001}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_FILE="${2:-$SCRIPT_DIR/book_ids.txt}"

echo "=========================================================="
echo "Starting seed dataset ingestion from $SEED_FILE"
echo "Ingestion service: $INGESTION_URL"
echo "=========================================================="

if [ ! -f "$SEED_FILE" ]; then
    echo "ERROR: Seed file not found: $SEED_FILE"
    exit 1
fi

total=$(grep -E '^[0-9]+$' "$SEED_FILE" | wc -l)
current=0
success=0
failed404=0
errors=0

while read -r book_id || [ -n "$book_id" ]; do
    [[ "$book_id" =~ ^[0-9]+$ ]] || continue
    current=$((current + 1))
    echo -n "[$current/$total] Ingesting book ID: $book_id ... "

    http_code=$(curl -s -o /tmp/ingest_resp.json -w "%{http_code}" -X POST "$INGESTION_URL/ingest/$book_id")

    if [ "$http_code" -eq 200 ]; then
        status=$(grep -o '"status":"[^"]*"' /tmp/ingest_resp.json | cut -d'"' -f4)
        echo "OK ($status)"
        success=$((success + 1))
    elif [ "$http_code" -eq 404 ]; then
        echo "FAILED 404 (Book does not exist in Project Gutenberg)"
        failed404=$((failed404 + 1))
    else
        echo "ERROR (HTTP $http_code)"
        errors=$((errors + 1))
    fi
done < "$SEED_FILE"

echo "----------------------------------------------------------"
echo "Seed Ingestion Summary:"
echo "Books processed: $total"
echo "Successful:      $success"
echo "Not found (404): $failed404"
echo "Other errors:    $errors"
echo "=========================================================="
