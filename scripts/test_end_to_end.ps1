# ==============================================================================
# END-TO-END TEST SCRIPT - POWERSHELL
# ==============================================================================

param(
    [string]$BaseUrl = "http://localhost:7000",
    [string]$IngestionUrl = "http://localhost:7001",
    [int]$WaitTimeoutSec = 180,
    [int]$RequestTimeoutSec = 10
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$RootDir = (Resolve-Path "$PSScriptRoot\..").Path
$SeedFile = Join-Path $RootDir "seed\book_ids.txt"
$SeedScript = Join-Path $RootDir "seed\download_seed.ps1"

function Invoke-HttpRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Uri,

        [ValidateSet("GET", "POST")]
        [string]$Method = "GET",

        [int]$TimeoutSec = $RequestTimeoutSec
    )

    return Invoke-WebRequest `
        -Uri $Uri `
        -Method $Method `
        -UseBasicParsing `
        -TimeoutSec $TimeoutSec `
        -ErrorAction Stop
}

function Get-HttpStatusCode {
    param(
        [Parameter(Mandatory = $true)]
        $Exception
    )

    if ($null -ne $Exception.Response) {
        try {
            return [int]$Exception.Response.StatusCode
        }
        catch {
            return 0
        }
    }

    return 0
}

function Test-Endpoint {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ServiceName,

        [Parameter(Mandatory = $true)]
        [string]$Uri
    )

    try {
        $response = Invoke-HttpRequest -Uri $Uri -TimeoutSec 3

        if ($response.StatusCode -eq 200) {
            return $true
        }

        Write-Host "$ServiceName returned HTTP $($response.StatusCode)." `
            -ForegroundColor Yellow
        return $false
    }
    catch {
        Write-Host "${ServiceName} is not available at ${Uri}: $($_.Exception.Message)" `
            -ForegroundColor Yellow
        return $false
    }
}

function Assert-Query {
    param(
        [Parameter(Mandatory = $true)]
        [string]$QueryPath,

        [Parameter(Mandatory = $true)]
        [int]$ExpectedStatus,

        [Parameter(Mandatory = $true)]
        [string]$Description,

        [int]$MinimumResults = -1,

        [int]$ExactResults = -1
    )

    Write-Host "Testing: $Description ($QueryPath) ... " -NoNewline

    try {
        $response = Invoke-HttpRequest `
            -Uri "$BaseUrl$QueryPath" `
            -TimeoutSec $RequestTimeoutSec

        if ($response.StatusCode -ne $ExpectedStatus) {
            Write-Host "FAILED (expected HTTP $ExpectedStatus, received HTTP $($response.StatusCode))" `
                -ForegroundColor Red
            return $false
        }

        $total = $null

        if (-not [string]::IsNullOrWhiteSpace($response.Content)) {
            try {
                $json = $response.Content | ConvertFrom-Json

                if ($null -ne $json.total) {
                    $total = [int]$json.total
                }
            }
            catch {
                # A valid response may not contain a JSON search result.
            }
        }

        if ($MinimumResults -ge 0) {
            if ($null -eq $total) {
                Write-Host "FAILED (response does not contain a total field)" `
                    -ForegroundColor Red
                return $false
            }

            if ($total -lt $MinimumResults) {
                Write-Host "FAILED (total=$total, minimum expected=$MinimumResults)" `
                    -ForegroundColor Red
                return $false
            }
        }

        if ($ExactResults -ge 0) {
            if ($null -eq $total) {
                Write-Host "FAILED (response does not contain a total field)" `
                    -ForegroundColor Red
                return $false
            }

            if ($total -ne $ExactResults) {
                Write-Host "FAILED (total=$total, expected=$ExactResults)" `
                    -ForegroundColor Red
       return $false
            }
        }

        if ($null -ne $total) {
            Write-Host "PASSED (HTTP $($response.StatusCode), total=$total)" `
                -ForegroundColor Green
        }
        else {
            Write-Host "PASSED (HTTP $($response.StatusCode))" `
                -ForegroundColor Green
        }

        return $true
    }
    catch {
        $statusCode = Get-HttpStatusCode -Exception $_.Exception

        if ($statusCode -eq $ExpectedStatus) {
            Write-Host "PASSED (expected HTTP $statusCode)" -ForegroundColor Green
            return $true
        }

        if ($statusCode -gt 0) {
            Write-Host "FAILED (expected HTTP $ExpectedStatus, received HTTP $statusCode): $($_.Exception.Message)" `
                -ForegroundColor Red
        }
        else {
            Write-Host "FAILED (no HTTP response): $($_.Exception.Message)" `
                -ForegroundColor Red
        }

        return $false
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "STAGE 2 SOA SEARCH ENGINE - END-TO-END TEST" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

Set-Location $RootDir

# ------------------------------------------------------------------------------
# 1. Check Docker Compose services
# ------------------------------------------------------------------------------

Write-Host "[1/5] Checking Docker Compose services..." -ForegroundColor Yellow

$searchAvailable = Test-Endpoint `
    -ServiceName "search-service/nginx" `
    -Uri "$BaseUrl/status"

$ingestionAvailable = Test-Endpoint `
    -ServiceName "ingestion-service" `
    -Uri "$IngestionUrl/status"

if (-not ($searchAvailable -and $ingestionAvailable)) {
    Write-Host "One or more published services are unavailable. Running docker compose up -d..." `
        -ForegroundColor Yellow

    docker compose up -d

    if ($LASTEXITCODE -ne 0) {
        Write-Error "docker compose up -d failed with exit code $LASTEXITCODE."
        exit 1
    }
}
else {
    Write-Host "Published endpoints are already available. Images will not be rebuilt." `
        -ForegroundColor Green
}

# ------------------------------------------------------------------------------
# 2. Wait for service availability
# ------------------------------------------------------------------------------

Write-Host "[2/5] Waiting for services to become available..." -ForegroundColor Yellow

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$ready = $false

$lastSearchState = "not checked"
$lastIngestionState = "not checked"
$lastIndexingState = "not checked"

while ($stopwatch.Elapsed.TotalSeconds -lt $WaitTimeoutSec) {
    $searchReady = $false
    $ingestionReady = $false
    $indexingReady = $false

    try {
        $response = Invoke-HttpRequest -Uri "$BaseUrl/status" -TimeoutSec 3
        $searchReady = ($response.StatusCode -eq 200)
        $lastSearchState = "HTTP $($response.StatusCode)"
    }
    catch {
        $lastSearchState = $_.Exception.Message
    }

    try {
        $response = Invoke-HttpRequest -Uri "$IngestionUrl/status" -TimeoutSec 3
        $ingestionReady = ($response.StatusCode -eq 200)
        $lastIngestionState = "HTTP $($response.StatusCode)"
    }
    catch {
        $lastIngestionState = $_.Exception.Message
    }

    try {
        $indexingContainerIds = @(
            docker compose ps --status running -q indexing 2>$null
        ) | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_)
        }

        $indexingReady = ($indexingContainerIds.Count -gt 0)

        if ($indexingReady) {
            $lastIndexingState = "container is running"
        }
        else {
            $lastIndexingState = "no running indexing container was found"
        }
    }
    catch {
        $lastIndexingState = $_.Exception.Message
    }

    if ($searchReady -and $ingestionReady -and $indexingReady) {
        $ready = $true
        break
    }

    Start-Sleep -Seconds 2
}

if (-not $ready) {
    Write-Host "Service availability timeout after $WaitTimeoutSec seconds." `
        -ForegroundColor Red
    Write-Host "  search-service/nginx: $lastSearchState" -ForegroundColor Red
    Write-Host "  ingestion-service:    $lastIngestionState" -ForegroundColor Red
    Write-Host "  indexing-service:     $lastIndexingState" -ForegroundColor Red
    Write-Host ""
    Write-Host "Current Docker Compose state:" -ForegroundColor Yellow

    docker compose ps

    exit 1
}

Write-Host "Search, ingestion, and indexing are available." -ForegroundColor Green

# ------------------------------------------------------------------------------
# 3. Ingest seed books
# ------------------------------------------------------------------------------

Write-Host "[3/5] Ingesting seed books..." -ForegroundColor Yellow

if (-not (Test-Path $SeedFile)) {
    Write-Error "Seed file not found: $SeedFile"
    exit 1
}

if (-not (Test-Path $SeedScript)) {
    Write-Error "Seed ingestion script not found: $SeedScript"
    exit 1
}

$seedIds = @(
    Get-Content $SeedFile |
        ForEach-Object { $_.Trim() } |
        Where-Object {
            $_ -match '^\d+$'
        }
)

if ($seedIds.Count -eq 0) {
    Write-Error "The seed file does not contain any valid numeric book IDs: $SeedFile"
    exit 1
}

Write-Host "Valid book IDs in seed file: $($seedIds.Count)" -ForegroundColor Cyan

& $SeedScript `
    -IngestionUrl $IngestionUrl `
    -SeedFile $SeedFile `
    -RequestTimeoutSec $RequestTimeoutSec

if ($LASTEXITCODE -ne 0) {
    Write-Error "Seed ingestion failed with exit code $LASTEXITCODE."
    exit 1
}

# ------------------------------------------------------------------------------
# 4. Wait until the published index is searchable
# ------------------------------------------------------------------------------

Write-Host "[4/5] Waiting for the published index to become searchable..." `
    -ForegroundColor Yellow

$stopwatch.Restart()
$indexReady = $false
$lastIndexState = "No valid search response has been received."

while ($stopwatch.Elapsed.TotalSeconds -lt $WaitTimeoutSec) {
    try {
        $englishResponse = Invoke-HttpRequest `
            -Uri "$BaseUrl/search?q=adventure&language=en&limit=1" `
            -TimeoutSec 5

        $spanishResponse = Invoke-HttpRequest `
            -Uri "$BaseUrl/search?q=quijote&language=es&limit=1" `
            -TimeoutSec 5

        $frenchResponse = Invoke-HttpRequest `
            -Uri "$BaseUrl/search?q=candide&language=fr&limit=1" `
            -TimeoutSec 5

        $englishJson = $englishResponse.Content | ConvertFrom-Json
        $spanishJson = $spanishResponse.Content | ConvertFrom-Json
        $frenchJson = $frenchResponse.Content | ConvertFrom-Json

        $lastIndexState = "en=$($englishJson.total), es=$($spanishJson.total), fr=$($frenchJson.total)"

        Write-Host "Published index state: $lastIndexState"

        if (
            [int]$englishJson.total -ge 1 -and
            [int]$spanishJson.total -ge 1 -and
            [int]$frenchJson.total -ge 1
        ) {
            $indexReady = $true
            break
        }
    }
    catch {
        $lastIndexState = $_.Exception.Message
    }

    Start-Sleep -Seconds 3
}

if (-not $indexReady) {
    Write-Host "The index did not become ready within $WaitTimeoutSec seconds." `
        -ForegroundColor Red
    Write-Host "Last observed state: $lastIndexState" -ForegroundColor Red
    Write-Host ""
    Write-Host "Latest ingestion and indexing logs:" -ForegroundColor Yellow

    docker compose logs --tail=100 ingestion indexing

    exit 1
}

Write-Host "The published index contains searchable English, Spanish, and French data." `
    -ForegroundColor Green

# ------------------------------------------------------------------------------
# 5. Run API validations
# ------------------------------------------------------------------------------

Write-Host "[5/5] Running search API validations..." -ForegroundColor Yellow

$passedTests = 0
$totalTests = 9

if (Assert-Query `
    -QueryPath "/search?q=adventure" `
    -ExpectedStatus 200 `
    -Description "Simple term search" `
    -MinimumResults 1) {
    $passedTests++
}

if (Assert-Query `
    -QueryPath "/search?q=adventure&author=twain" `
    -ExpectedStatus 200 `
    -Description "Search with author filter") {
    $passedTests++
}

if (Assert-Query `
    -QueryPath "/search?q=adventure&language=en" `
    -ExpectedStatus 200 `
    -Description "Search with English language filter" `
    -MinimumResults 1) {
    $passedTests++
}

if (Assert-Query `
    -QueryPath "/search?q=quijote&language=es" `
    -ExpectedStatus 200 `
    -Description "Spanish search" `
    -MinimumResults 1) {
    $passedTests++
}

if (Assert-Query `
    -QueryPath "/search?q=candide&language=fr" `
    -ExpectedStatus 200 `
    -Description "French search" `
    -MinimumResults 1) {
    $passedTests++
}

if (Assert-Query `
    -QueryPath "/search?q=adventure&limit=5&offset=0" `
    -ExpectedStatus 200 `
    -Description "Search pagination") {
    $passedTests++
}

if (Assert-Query `
    -QueryPath "/search?q=the" `
    -ExpectedStatus 200 `
    -Description "Stop word returns an empty result set" `
    -ExactResults 0) {
    $passedTests++
}

if (Assert-Query `
    -QueryPath "/search" `
    -ExpectedStatus 400 `
    -Description "Missing q parameter") {
    $passedTests++
}

if (Assert-Query `
    -QueryPath "/search?q=adventure&limit=-1" `
    -ExpectedStatus 400 `
    -Description "Invalid negative limit") {
    $passedTests++
}

Write-Host "==========================================================" -ForegroundColor Cyan

if ($passedTests -eq $totalTests) {
    Write-Host "END-TO-END RESULT: $passedTests / $totalTests tests passed." `
        -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Cyan
    exit 0
}

Write-Host "END-TO-END RESULT: $passedTests / $totalTests tests passed." `
    -ForegroundColor Red
Write-Host "==========================================================" -ForegroundColor Cyan

exit 1


