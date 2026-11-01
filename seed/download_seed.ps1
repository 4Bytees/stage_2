# ==============================================================================
# SEED DATASET INGESTION SCRIPT - POWERSHELL
# ==============================================================================

param(
    [string]$IngestionUrl = "http://localhost:7001",
    [string]$SeedFile = "$PSScriptRoot/book_ids.txt",
    [int]$RequestTimeoutSec = 30
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "SEED DATASET INGESTION" -ForegroundColor Cyan
Write-Host "Seed file: $SeedFile" -ForegroundColor Cyan
Write-Host "Ingestion service: $IngestionUrl" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

if (-not (Test-Path $SeedFile)) {
    Write-Error "Seed file not found: $SeedFile"
    exit 1
}

$bookIds = @(
    Get-Content $SeedFile |
        ForEach-Object { $_.Trim() } |
        Where-Object {
            $_ -match '^\d+$'
        }
)

$total = $bookIds.Count

if ($total -eq 0) {
    Write-Error "The seed file does not contain any valid numeric book IDs: $SeedFile"
    exit 1
}

$current = 0
$successful = 0
$notFound = 0
$errors = 0

foreach ($id in $bookIds) {
    $current++
    $uri = "$IngestionUrl/ingest/$id"

    Write-Host "[$current/$total] Ingesting book ID $id ... " -NoNewline

    try {
        $response = Invoke-WebRequest `
            -Uri $uri `
            -Method Post `
            -UseBasicParsing `
            -TimeoutSec $RequestTimeoutSec `
            -ErrorAction Stop

        $json = $response.Content | ConvertFrom-Json

        Write-Host "OK ($($json.status))" -ForegroundColor Green
        $successful++
    }
    catch {
        $statusCode = 0

        if ($null -ne $_.Exception.Response) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            catch {
                $statusCode = 0
            }
        }

        if ($statusCode -eq 404) {
            Write-Host "FAILED (HTTP 404: book not found in Project Gutenberg)" `
                -ForegroundColor Yellow
            $notFound++
        }
        elseif ($statusCode -gt 0) {
            Write-Host "FAILED (HTTP $statusCode): $($_.Exception.Message)" `
                -ForegroundColor Red
            $errors++
        }
        else {
            Write-Host "FAILED (no HTTP response): $($_.Exception.Message)" `
                -ForegroundColor Red
            $errors++
        }
    }
}

Write-Host "----------------------------------------------------------" -ForegroundColor Cyan
Write-Host "SEED INGESTION SUMMARY" -ForegroundColor Cyan
Write-Host "Books processed:  $total"
Write-Host "Successful:       $successful" -ForegroundColor Green
Write-Host "Not found (404):  $notFound" -ForegroundColor Yellow
Write-Host "Other errors:     $errors" -ForegroundColor Red
Write-Host "==========================================================" -ForegroundColor Cyan

if ($successful -ne $total) {
    Write-Error "Seed ingestion was incomplete: $successful of $total books succeeded."
    exit 1
}

exit 0
