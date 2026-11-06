# ==============================================================================
# STAGE 2 SOA SEARCH ENGINE - AUTOMATED BENCHMARK SUITE (POWERSHELL)
# ==============================================================================
# Executes:
#   1. JMH Microbenchmarks (HeaderParser, BodyTokenizer, SQLite insert,
#      Hazelcast coordination cost, SQLite read-only search, HTTP client search)
#      with 5 warmup iterations, 10 measurement iterations, 1 fork (-Xms2g -Xmx2g).
#   2. HTTP Search Load Benchmarks against Nginx (:7000) across client
#      concurrency levels c = 1, 4, 8, 16, 32 using a fixed query mix.
#   3. Live Indexing & Coordination Benchmarks across representative Project
#      Gutenberg books and container resource telemetry (docker stats).
# ==============================================================================

param(
    [string]$BaseUrl = "http://localhost:7000",
    [string]$IngestionUrl = "http://localhost:7001",
    [string]$MavenCmd = "C:\Users\User\maven\apache-maven-3.9.6\bin\mvn.cmd",
    [string]$ResultsDir = "",
    [switch]$SkipJmh,
    [switch]$SkipIndexing
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$env:Path = "C:\Program Files\Docker\Docker\resources\bin;" + $env:Path
$RootDir = (Resolve-Path "$PSScriptRoot\..").Path
if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $RootDir "results"
}
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

Set-Location $RootDir

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "STAGE 2 BENCHMARK SUITE - GROUP 4BYTEES" -ForegroundColor Cyan
Write-Host "Results directory: $ResultsDir" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# ------------------------------------------------------------------------------
# 0. Record Host, Git, JDK, and Docker Metadata
# ------------------------------------------------------------------------------
$ErrorActionPreference = "Continue"
$gitCommit = (git rev-parse --short HEAD 2>$null).Trim()
$gitBranch = (git rev-parse --abbrev-ref HEAD 2>$null).Trim()
$javaVer = ((cmd /c "java -version 2>&1") | Select-Object -First 2) -join " | "
$dockerVer = (docker --version 2>$null).Trim()
$composeVer = (docker compose version 2>$null).Trim()
$ErrorActionPreference = "Stop"

$metadata = [ordered]@{
    timestamp_utc   = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    git_branch      = $gitBranch
    git_commit      = $gitCommit
    jdk_version     = $javaVer
    docker_version  = $dockerVer
    compose_version = $composeVer
    jmh_config      = [ordered]@{
        warmup_iterations      = 5
        measurement_iterations = 10
        forks                  = 1
        jvm_args               = "-Xms2g -Xmx2g"
    }
}
$metadata | ConvertTo-Json -Depth 5 | Set-Content -Path (Join-Path $ResultsDir "benchmark-metadata.json") -Encoding UTF8

# ------------------------------------------------------------------------------
# 1. Run JMH Microbenchmarks
# ------------------------------------------------------------------------------
if (-not $SkipJmh) {
    Write-Host "[1/4] Running JMH Microbenchmarks (5 warmup, 10 measurement iterations)..." -ForegroundColor Yellow

    if (-not (Test-Path $MavenCmd)) {
        $MavenCmd = "mvn"
    }

    & $MavenCmd test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-cp.txt" -o
    if ($LASTEXITCODE -ne 0) {
        throw "Maven test-compile failed."
    }

    $indexingDepCp = (Get-Content (Join-Path $RootDir "indexing-service\target\test-cp.txt") -Raw).Trim()
    $indexingCp = @(
        (Join-Path $RootDir "indexing-service\target\test-classes"),
        (Join-Path $RootDir "indexing-service\target\classes"),
        (Join-Path $RootDir "common-dto\target\classes"),
        $indexingDepCp
    ) -join ";"

    Write-Host "  -> Running IndexingMicroBenchmark..." -ForegroundColor Cyan
    java -cp $indexingCp com.fourbytees.benchmark.IndexingMicroBenchmark $ResultsDir
    if ($LASTEXITCODE -ne 0) {
        throw "IndexingMicroBenchmark failed."
    }

    $searchDepCp = (Get-Content (Join-Path $RootDir "search-service\target\test-cp.txt") -Raw).Trim()
    $searchCp = @(
        (Join-Path $RootDir "search-service\target\test-classes"),
        (Join-Path $RootDir "search-service\target\classes"),
        (Join-Path $RootDir "common-dto\target\classes"),
        $searchDepCp
    ) -join ";"

    Write-Host "  -> Running SearchMicroBenchmark..." -ForegroundColor Cyan
    java -cp $searchCp com.fourbytees.benchmark.SearchMicroBenchmark $ResultsDir
    if ($LASTEXITCODE -ne 0) {
        throw "SearchMicroBenchmark failed."
    }
}

# ------------------------------------------------------------------------------
# 2. HTTP Search Load Benchmarks Across Client Concurrency (c = 1, 4, 8, 16, 32)
# ------------------------------------------------------------------------------
Write-Host "[2/4] Running HTTP Search Load Benchmarks against $BaseUrl..." -ForegroundColor Yellow

$queries = @(
    "/search?q=adventure",
    "/search?q=adventure&author=twain",
    "/search?q=adventure&language=en",
    "/search?q=quijote&language=es",
    "/search?q=candide&language=fr",
    "/search?q=adventure&limit=5&offset=0",
    "/search?q=the",
    "/books/1342"
)

# Warm-up HTTP pool
for ($w = 0; $w -lt 16; $w++) {
    $q = $queries[$w % $queries.Count]
    Invoke-WebRequest -Uri "$BaseUrl$q" -UseBasicParsing -TimeoutSec 10 | Out-Null
}

$concurrencyLevels = @(1, 4, 8, 16, 32)
$requestsPerWorker = 25
$httpLoadResults = @()

foreach ($c in $concurrencyLevels) {
    $totalRequests = $c * $requestsPerWorker
    Write-Host "  -> Concurrency c=$c ($totalRequests requests across $($queries.Count) query patterns)..." -ForegroundColor Cyan

    $runspacePool = [runspacefactory]::CreateRunspacePool(1, $c)
    $runspacePool.Open()
    $jobs = @()

    $wallWatch = [System.Diagnostics.Stopwatch]::StartNew()

    for ($worker = 0; $worker -lt $c; $worker++) {
        $ps = [powershell]::Create()
        $ps.RunspacePool = $runspacePool
        [void]$ps.AddScript({
            param($BaseUrl, $Queries, $RequestsPerWorker, $WorkerIndex)
            Add-Type -AssemblyName System.Net.Http
            $client = New-Object System.Net.Http.HttpClient
            $client.Timeout = [TimeSpan]::FromSeconds(15)
            $latencies = New-Object System.Collections.Generic.List[double]
            $errors = 0
            try {
                for ($i = 0; $i -lt $RequestsPerWorker; $i++) {
                    $q = $Queries[($WorkerIndex * 7 + $i) % $Queries.Count]
                    $sw = [System.Diagnostics.Stopwatch]::StartNew()
                    try {
                        $resp = $client.GetAsync("$BaseUrl$q").GetAwaiter().GetResult()
                        $sw.Stop()
                        if ([int]$resp.StatusCode -eq 200) {
                            $latencies.Add($sw.Elapsed.TotalMilliseconds)
                        } else {
                            $errors++
                        }
                    } catch {
                        $sw.Stop()
                        $errors++
                    }
                }
            } finally {
                $client.Dispose()
            }
            return [PSCustomObject]@{
                Latencies = $latencies.ToArray()
                Errors    = $errors
            }
        }).AddArgument($BaseUrl).AddArgument($queries).AddArgument($requestsPerWorker).AddArgument($worker)

        $jobs += [PSCustomObject]@{
            Pipe   = $ps
            Handle = $ps.BeginInvoke()
        }
    }

    $allLatencies = New-Object System.Collections.Generic.List[double]
    $totalErrors = 0

    foreach ($job in $jobs) {
        $res = $job.Pipe.EndInvoke($job.Handle)
        foreach ($r in $res) {
            foreach ($lat in $r.Latencies) {
                $allLatencies.Add([double]$lat)
            }
            $totalErrors += [int]$r.Errors
        }
        $job.Pipe.Dispose()
    }
    $wallWatch.Stop()
    $runspacePool.Close()
    $runspacePool.Dispose()

    $sorted = $allLatencies.ToArray() | Sort-Object
    $n = $sorted.Count
    $sum = 0.0
    foreach ($val in $sorted) { $sum += $val }
    $mean = if ($n -gt 0) { $sum / $n } else { 0.0 }

    $sqDiff = 0.0
    foreach ($val in $sorted) {
        $diff = $val - $mean
        $sqDiff += ($diff * $diff)
    }
    $stddev = if ($n -gt 1) { [Math]::Sqrt($sqDiff / ($n - 1)) } else { 0.0 }
    $p50 = if ($n -gt 0) { $sorted[[Math]::Min($n - 1, [int][Math]::Floor($n * 0.50))] } else { 0.0 }
    $p95 = if ($n -gt 0) { $sorted[[Math]::Min($n - 1, [int][Math]::Floor($n * 0.95))] } else { 0.0 }
    $p99 = if ($n -gt 0) { $sorted[[Math]::Min($n - 1, [int][Math]::Floor($n * 0.99))] } else { 0.0 }
    $throughput = if ($wallWatch.Elapsed.TotalSeconds -gt 0) { $n / $wallWatch.Elapsed.TotalSeconds } else { 0.0 }

    $row = [PSCustomObject]@{
        concurrency      = $c
        total_requests   = $totalRequests
        successful       = $n
        errors           = $totalErrors
        wall_time_ms     = [Math]::Round($wallWatch.Elapsed.TotalMilliseconds, 2)
        throughput_rps   = [Math]::Round($throughput, 2)
        mean_latency_ms  = [Math]::Round($mean, 2)
        stddev_ms        = [Math]::Round($stddev, 2)
        p50_latency_ms   = [Math]::Round($p50, 2)
        p95_latency_ms   = [Math]::Round($p95, 2)
        p99_latency_ms   = [Math]::Round($p99, 2)
    }
    $httpLoadResults += $row
    Write-Host ("     throughput={0} req/s | mean={1} ms | std={2} ms | p95={3} ms | errors={4}" -f `
        $row.throughput_rps, $row.mean_latency_ms, $row.stddev_ms, $row.p95_latency_ms, $row.errors) -ForegroundColor Green
}

$httpLoadResults | Export-Csv -Path (Join-Path $ResultsDir "http-search-concurrency.csv") -NoTypeInformation -Encoding UTF8
$httpLoadResults | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $ResultsDir "http-search-concurrency.json") -Encoding UTF8

# ------------------------------------------------------------------------------
# 3. Single-Book Indexing Latency Across Book Sizes (POST /index/update/{id})
# ------------------------------------------------------------------------------
if (-not $SkipIndexing) {
Write-Host "[3/4] Measuring per-book indexing latency across book sizes..." -ForegroundColor Yellow

$sampleBooks = @(
    [PSCustomObject]@{ book_id = 1080;  title = "A Modest Proposal";                  category = "Short pamphlet" },
    [PSCustomObject]@{ book_id = 16;    title = "Peter Pan";                          category = "Medium novel" },
    [PSCustomObject]@{ book_id = 1342;  title = "Pride and Prejudice";                category = "Standard novel" },
    [PSCustomObject]@{ book_id = 2701;  title = "Moby Dick";                          category = "Large novel" },
    [PSCustomObject]@{ book_id = 2000;  title = "Don Quijote (Spanish)";              category = "Very large novel" }
)

$indexingBookResults = @()
foreach ($b in $sampleBooks) {
    $id = $b.book_id
    $bodyFile = Get-ChildItem -Path (Join-Path $RootDir "datalake") -Recurse -Filter "$id.body.txt" | Select-Object -First 1
    $sizeKB = if ($null -ne $bodyFile) { [Math]::Round($bodyFile.Length / 1024.0, 1) } else { 0.0 }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $out = docker compose exec -T indexing curl -s -X POST "http://localhost:7002/index/update/$id"
    $sw.Stop()

    $row = [PSCustomObject]@{
        book_id        = $id
        title          = $b.title
        category       = $b.category
        body_size_kb   = $sizeKB
        index_time_ms  = [Math]::Round($sw.Elapsed.TotalMilliseconds, 2)
        index_time_sec = [Math]::Round($sw.Elapsed.TotalSeconds, 3)
        response       = ($out -join "").Trim()
    }
    $indexingBookResults += $row
    Write-Host ("  -> Book {0} ({1}, {2} KB): {3} ms" -f $id, $b.title, $sizeKB, $row.index_time_ms) -ForegroundColor Green
}

$indexingBookResults | Export-Csv -Path (Join-Path $ResultsDir "indexing-book-latency.csv") -NoTypeInformation -Encoding UTF8
$indexingBookResults | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $ResultsDir "indexing-book-latency.json") -Encoding UTF8
}

# ------------------------------------------------------------------------------
# 4. Capture Container Resource Telemetry (docker stats --no-stream)
# ------------------------------------------------------------------------------
Write-Host "[4/4] Capturing Docker container resource telemetry..." -ForegroundColor Yellow

$statsLines = docker stats --no-stream --format "{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.NetIO}},{{.BlockIO}}"
$dockerStats = @()
foreach ($line in $statsLines) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split ","
    if ($parts.Count -ge 6) {
        $dockerStats += [PSCustomObject]@{
            container  = $parts[0]
            cpu_pct    = $parts[1]
            mem_usage  = $parts[2]
            mem_pct    = $parts[3]
            net_io     = $parts[4]
            block_io   = $parts[5]
        }
    }
}
$dockerStats | Export-Csv -Path (Join-Path $ResultsDir "docker-stats.csv") -NoTypeInformation -Encoding UTF8
$dockerStats | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $ResultsDir "docker-stats.json") -Encoding UTF8

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "BENCHMARK SUITE COMPLETED SUCCESSFULLY." -ForegroundColor Green
Write-Host "All raw JSON and CSV artifacts saved in: $ResultsDir" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
