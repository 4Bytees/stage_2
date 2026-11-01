#!/usr/bin/env bash
# ==============================================================================
# SCRIPT DE INGESTA DE SEMILLAS - BASH
# ==============================================================================

INGESTION_URL="${1:-http://localhost:7001}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_FILE="${2:-$SCRIPT_DIR/book_ids.txt}"

echo "=========================================================="
echo "Iniciando ingesta de dataset semilla desde $SEED_FILE"
echo "Servicio Ingestion: $INGESTION_URL"
echo "=========================================================="

if [ ! -f "$SEED_FILE" ]; then
    echo "ERROR: No se encontró el fichero de semillas: $SEED_FILE"
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
    echo -n "[$current/$total] Ingestando libro ID: $book_id ... "

    http_code=$(curl -s -o /tmp/ingest_resp.json -w "%{http_code}" -X POST "$INGESTION_URL/ingest/$book_id")

    if [ "$http_code" -eq 200 ]; then
        status=$(grep -o '"status":"[^"]*"' /tmp/ingest_resp.json | cut -d'"' -f4)
        echo "OK ($status)"
        success=$((success + 1))
    elif [ "$http_code" -eq 404 ]; then
        echo "FALLO 404 (Libro no existe en Gutenberg, FAILED sin reintentos)"
        failed404=$((failed404 + 1))
    else
        echo "ERROR (HTTP $http_code)"
        errors=$((errors + 1))
    fi
done < "$SEED_FILE"

echo "----------------------------------------------------------"
echo "Resumen de Ingesta Semilla:"
echo "Total procesados: $total"
echo "Completados con éxito: $success"
echo "Libros no encontrados (404): $failed404"
echo "Errores: $errors"
echo "=========================================================="

