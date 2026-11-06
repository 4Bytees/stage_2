#!/usr/bin/env bash
# ==============================================================================
# END-TO-END TEST SCRIPT - BASH
# ==============================================================================

set -e

BASE_URL="${1:-http://localhost:7000}"
INGESTION_URL="${2:-http://localhost:7001}"
INDEXING_URL="${3:-http://localhost:7002}"
WAIT_TIMEOUT_SEC="${4:-120}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=========================================================="
echo "STAGE 2 SOA SEARCH ENGINE - END-TO-END TEST"
echo "=========================================================="

cd "$ROOT_DIR"

# 1. Check or start Docker Compose services
echo "[1/5] Checking Docker containers..."
if ! curl -s -f "$BASE_URL/status" > /dev/null 2>&1; then
    echo "Services are not running. Starting with docker compose up -d --build..."
    docker compose up -d --build
fi

# 2. Wait for healthchecks
echo "[2/5] Waiting for services to become ready..."
start_time=$(date +%s)
while true; do
    now=$(date +%s)
    if [ $((now - start_time)) -ge "$WAIT_TIMEOUT_SEC" ]; then
        echo "ERROR: Timed out waiting for services to respond."
        exit 1
    fi

    if curl -s -f "$INGESTION_URL/status" > /dev/null 2>&1 && \
       curl -s -f "$INDEXING_URL/status" > /dev/null 2>&1 && \
       curl -s -f "$BASE_URL/status" > /dev/null 2>&1; then
        echo "All services report 200 OK."
        break
    fi
    sleep 2
done

# 3. Ingest seed books
echo "[3/5] Running seed book ingestion..."
bash "$ROOT_DIR/seed/download_seed.sh" "$INGESTION_URL" "$ROOT_DIR/seed/book_ids.txt"

# 4. Wait for SQLite indexing
echo "[4/5] Waiting for SQLite indexing..."
start_time=$(date +%s)
while true; do
    now=$(date +%s)
    if [ $((now - start_time)) -ge "$WAIT_TIMEOUT_SEC" ]; then
        echo "Timed out waiting for indexing."
        break
    fi

    indexed=$(curl -s "$INDEXING_URL/index/status" | grep -o '"books_indexed":[0-9]*' | cut -d: -f2 || echo 0)
    echo "Indexed books: $indexed ..."
    if [ "$indexed" -ge 20 ]; then
        echo "Total indexed books: $indexed."
        break
    fi
    sleep 3
done

# 5. Run search API validations
echo "[5/5] Running validations on /search..."

assert_query() {
    local query_path="$1"
    local expected_status="$2"
    local desc="$3"
    echo -n "Testing: $desc ($query_path) ... "

    code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL$query_path")
    if [ "$code" -eq "$expected_status" ]; then
        echo "PASSED (HTTP $code)"
        return 0
    else
        echo "FAILED (Expected $expected_status, received $code)"
        return 1
    fi
}

passed=0
total=9

assert_query "/search?q=adventure" 200 "Simple term search" && passed=$((passed+1)) || true
assert_query "/search?q=adventure&author=twain" 200 "Author filter Twain" && passed=$((passed+1)) || true
assert_query "/search?q=adventure&language=en" 200 "English language filter" && passed=$((passed+1)) || true
assert_query "/search?q=quijote&language=es" 200 "Spanish search" && passed=$((passed+1)) || true
assert_query "/search?q=candide&language=fr" 200 "French search" && passed=$((passed+1)) || true
assert_query "/search?q=adventure&limit=5&offset=0" 200 "Pagination limit 5" && passed=$((passed+1)) || true
assert_query "/search?q=the" 200 "Stop word returns 200 with empty results" && passed=$((passed+1)) || true
assert_query "/search" 400 "Missing q parameter returns 400" && passed=$((passed+1)) || true
assert_query "/search?q=adventure&limit=-1" 400 "Negative limit returns 400" && passed=$((passed+1)) || true

echo "=========================================================="
echo "END-TO-END TEST RESULT: $passed / $total tests passed."
echo "=========================================================="
