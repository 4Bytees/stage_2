#!/usr/bin/env bash
# ==============================================================================
# SCRIPT DE PRUEBA END-TO-END - BASH
# ==============================================================================

set -e

BASE_URL="${1:-http://localhost:7000}"
INGESTION_URL="${2:-http://localhost:7001}"
INDEXING_URL="${3:-http://localhost:7002}"
WAIT_TIMEOUT_SEC="${4:-120}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=========================================================="
echo "STAGE 2 SOA SEARCH ENGINE - PRUEBA END-TO-END"
echo "=========================================================="

cd "$ROOT_DIR"

# 1. Comprobar o levantar servicios
echo "[1/5] Verificando contenedores Docker..."
if ! curl -s -f "$BASE_URL/status" > /dev/null 2>&1; then
    echo "Servicios no detectados. Levantando con docker compose up -d --build..."
    docker compose up -d --build
fi

# 2. Esperar healthchecks
echo "[2/5] Esperando a que los servicios estén listos..."
start_time=$(date +%s)
while true; do
    now=$(date +%s)
    if [ $((now - start_time)) -ge "$WAIT_TIMEOUT_SEC" ]; then
        echo "ERROR: Timeout esperando a que los servicios respondan."
        exit 1
    fi

    if curl -s -f "$INGESTION_URL/status" > /dev/null 2>&1 && \
       curl -s -f "$INDEXING_URL/status" > /dev/null 2>&1 && \
       curl -s -f "$BASE_URL/status" > /dev/null 2>&1; then
        echo "Todos los servicios reportan 200 OK."
        break
    fi
    sleep 2
done

# 3. Ingestar semillas
echo "[3/5] Ejecutando ingesta de libros semilla..."
bash "$ROOT_DIR/seed/download_seed.sh" "$INGESTION_URL" "$ROOT_DIR/seed/book_ids.txt"

# 4. Esperar indexación en SQLite
echo "[4/5] Esperando indexación en SQLite..."
start_time=$(date +%s)
while true; do
    now=$(date +%s)
    if [ $((now - start_time)) -ge "$WAIT_TIMEOUT_SEC" ]; then
        echo "Timeout esperando indexación."
        break
    fi

    indexed=$(curl -s "$INDEXING_URL/index/status" | grep -o '"books_indexed":[0-9]*' | cut -d: -f2 || echo 0)
    echo "Libros indexados: $indexed ..."
    if [ "$indexed" -ge 20 ]; then
        echo "Total de libros indexados: $indexed."
        break
    fi
    sleep 3
done

# 5. Consultas de prueba sobre /search
echo "[5/5] Ejecutando validaciones sobre /search..."

assert_query() {
    local query_path="$1"
    local expected_status="$2"
    local desc="$3"
    echo -n "Probando: $desc ($query_path) ... "

    code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL$query_path")
    if [ "$code" -eq "$expected_status" ]; then
        echo "PASÓ (HTTP $code)"
        return 0
    else
        echo "FALLÓ (Esperado $expected_status, obtenido $code)"
        return 1
    fi
}

passed=0
total=9

assert_query "/search?q=adventure" 200 "Búsqueda simple por término" && passed=$((passed+1)) || true
assert_query "/search?q=adventure&author=twain" 200 "Filtro autor Twain" && passed=$((passed+1)) || true
assert_query "/search?q=adventure&language=en" 200 "Filtro idioma en" && passed=$((passed+1)) || true
assert_query "/search?q=quijote&language=es" 200 "Búsqueda en español" && passed=$((passed+1)) || true
assert_query "/search?q=candide&language=fr" 200 "Búsqueda en francés" && passed=$((passed+1)) || true
assert_query "/search?q=adventure&limit=5&offset=0" 200 "Paginación limit 5" && passed=$((passed+1)) || true
assert_query "/search?q=the" 200 "Stop word devuelve 200 resultados vacíos" && passed=$((passed+1)) || true
assert_query "/search" 400 "Falta q devuelve 400" && passed=$((passed+1)) || true
assert_query "/search?q=adventure&limit=-1" 400 "Limit negativo devuelve 400" && passed=$((passed+1)) || true

echo "=========================================================="
echo "RESULTADO PRUEBA END-TO-END: $passed / $total pruebas superadas."
echo "=========================================================="

