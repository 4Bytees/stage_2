# Stage 2 Benchmark Results Summary — Group 4Bytees

This directory stores the tracked, reproducible benchmark datasets (`CSV` and `JSON`) collected by [`scripts/run_benchmarks.ps1`](../../scripts/run_benchmarks.ps1) against the live Stage 2 Service-Oriented Search Engine.

---

## 1. Experimental Hardware & Software Environment

| Component | Specification |
| :--- | :--- |
| **Host System** | HP OMEN Transcend Gaming Laptop 14-fb1xxx |
| **Processor (CPU)** | Intel(R) Core(TM) Ultra 9 285H (16 cores, 16 logical processors, 2.90 GHz base clock) |
| **System Memory (RAM)** | 64 GB physical RAM (`68,073,553,920` bytes); 31.05 GiB allocated to WSL2 VM |
| **Operating System** | Microsoft Windows 11 Pro (10.0.26300, Build 26300) + WSL2 Linux Kernel `6.18.54.1-microsoft-standard-WSL2` |
| **Java Runtime (JDK)** | Microsoft OpenJDK `17.0.20.1+1-LTS` (64-bit Server VM) |
| **Container Engine** | Docker Desktop `29.9.2` (`build a82e528`), Docker Compose `v5.6.1` |
| **Dataset Baseline** | 25 Project Gutenberg seed books (`seed/book_ids.txt`: 23 `en`, 1 `es`, 1 `fr`; SQLite index size `22.56 MB`) |
| **JMH Configuration** | JMH `1.37`, `Mode.AverageTime` (`us/op`), 5 warmup iterations (1 s each), 10 measurement iterations (1 s each), 1 fork (`-Xms2g -Xmx2g`) |

---

## 2. JMH Microbenchmarks (`jmh-indexing.csv` / `jmh-search.csv`)

| Module | Benchmark Method | Mode | Samples | Mean (`µs/op`) | 99.9% CI Error (`± µs/op`) | StdDev (`µs/op`) | Min (`µs/op`) | Max (`µs/op`) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `indexing-service` | `IndexingMicroBenchmark.headerParsing` | `avgt` | 10 | `545.02` | `79.67` | `52.70` | `454.29` | `622.56` |
| `indexing-service` | `IndexingMicroBenchmark.bodyTokenization` | `avgt` | 10 | `726.43` | `68.67` | `45.42` | `653.95` | `828.01` |
| `indexing-service` | `IndexingMicroBenchmark.hazelcastCoordinationCost` | `avgt` | 10 | `102.78` | `18.42` | `12.18` | `89.15` | `124.11` |
| `indexing-service` | `IndexingMicroBenchmark.sqliteIndexInsertion` | `avgt` | 10 | `10,483.95` | `6,996.90` | `4,628.00` | `5,844.57` | `20,036.86` |
| `search-service` | `SearchMicroBenchmark.sqliteGetBookById` | `avgt` | 10 | `3,049.84` | `802.44` | `530.76` | `2,286.65` | `3,893.80` |
| `search-service` | `SearchMicroBenchmark.sqliteSearchUnfiltered` | `avgt` | 10 | `4,274.86` | `1,702.74` | `1,126.26` | `2,761.44` | `6,108.86` |
| `search-service` | `SearchMicroBenchmark.sqliteSearchFiltered` | `avgt` | 10 | `4,525.42` | `1,460.29` | `965.89` | `3,388.45` | `6,464.51` |
| `search-service` | `SearchMicroBenchmark.httpSearchClientQuery` | `avgt` | 10 | `8,621.27` | `2,085.44` | `1,379.39` | `6,887.58` | `11,012.82` |

---

## 3. HTTP Search Load Benchmarks Across Client Concurrency (`http-search-concurrency.csv`)

Measured against Nginx reverse proxy (`http://localhost:7000`) balancing 2 `search-service` replicas (`stage_2-search-1`, `stage_2-search-2`) over an 8-query workload mix (`/search?q=adventure`, `/search?q=adventure&author=twain`, `/search?q=adventure&language=en`, `/search?q=quijote&language=es`, `/search?q=candide&language=fr`, `/search?q=adventure&limit=5&offset=0`, `/search?q=the`, `/books/1342`).

| Concurrency (`c`) | Total Requests | Successful | Errors | Wall Time (`ms`) | Throughput (`req/s`) | Mean Latency (`ms`) | StdDev (`ms`) | p50 (`ms`) | p95 (`ms`) | p99 (`ms`) |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `1` | 25 | 25 | 0 | `1,069.48` | `23.38` | `39.65` | `17.07` | `43.66` | `63.35` | `67.24` |
| `4` | 100 | 100 | 0 | `631.36` | `158.39` | `23.70` | `10.69` | `25.43` | `39.84` | `45.62` |
| `8` | 200 | 200 | 0 | `517.95` | `386.14` | `18.09` | `12.04` | `15.94` | `43.80` | `52.99` |
| `16` | 400 | 400 | 0 | `978.54` | `408.77` | `32.22` | `24.35` | `25.57` | `84.79` | `99.44` |
| `32` | 800 | 800 | 0 | `1,730.31` | `462.35` | `56.87` | `46.00` | `43.85` | `155.37` | `182.72` |

---

## 4. Per-Book Indexing & Distributed Coordination Latency (`indexing-book-latency.csv`)

Measured via `POST http://localhost:7002/index/update/{id}` inside the containerized stack (`./datalake` and `./datamarts` mounted over WSL2 bind mounts with Hazelcast `FencedLock("index-writer")` and post-commit `BookIndexed` outbox publication):

| Book ID | Title | Category | Body Size (`KB`) | Indexing Latency (`ms`) | Indexing Latency (`s`) |
| :---: | :--- | :--- | :---: | :---: | :---: |
| `1080` | *A Modest Proposal* | Short pamphlet | `37.8` | `11,216.60` | `11.217` |
| `16` | *Peter Pan* | Medium novel | `281.2` | `25,168.65` | `25.169` |
| `1342` | *Pride and Prejudice* | Standard novel | `753.3` | `31,768.26` | `31.768` |
| `2701` | *Moby Dick* | Large novel | `1,245.5` | `65,778.74` | `65.779` |
| `2000` | *Don Quijote (Spanish)* | Very large novel | `2,172.7` | `52,602.16` | `52.602` |

- **Full-Corpus Atomic Rebuild (`POST /index/rebuild`, 25 seed books):**
  - Build + atomic swap time: **`455.30 s`** (`books_processed: 25`, temporary file `metadata.db.rebuild-8d180740-6c98-49d0-9163-78ac875757ef.tmp` of `23,625,728` bytes swapped under `FencedLock("index-writer")`).
  - Overlapping concurrent `POST /index/rebuild`: **`HTTP 409 Conflict`** (`< 15 ms`, rejected via `lock:index-rebuild.tryLock(0)`).
  - Concurrent `GET /search?q=adventure` during rebuild: **`HTTP 200 OK`** (`total: 13`, zero downtime or partial index exposure).

---

## 5. Container Resource Telemetry (`docker-stats.csv`)

Captured with `2` `indexing-service` replicas and `2` `search-service` replicas (`docker stats --no-stream`):

| Container | CPU (`%`) | Memory Usage / Limit | Memory (`%`) | Network I/O | Block I/O |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `stage_2-nginx-1` | `0.00%` | `4.438MiB / 128MiB` | `3.47%` | `1.67MB / 1.84MB` | `0B / 12.3kB` |
| `stage_2-search-1` | `0.54%` | `129.1MiB / 768MiB` | `16.81%` | `485kB / 1.58MB` | `0B / 1.69MB` |
| `stage_2-search-2` | `0.54%` | `127.2MiB / 768MiB` | `16.56%` | `487kB / 1.54MB` | `0B / 1.7MB` |
| `stage_2-indexing-1` | `3.12%` | `216.9MiB / 1GiB` | `21.18%` | `261kB / 917kB` | `0B / 25.2MB` |
| `stage_2-indexing-2` | `3.66%` | `107.2MiB / 1GiB` | `10.47%` | `68.5kB / 294kB` | `0B / 1.31MB` |
| `stage_2-ingestion-1` | `0.57%` | `92.84MiB / 768MiB` | `12.09%` | `68.1kB / 299kB` | `0B / 291kB` |
| `stage_2-activemq-1` | `0.24%` | `178.4MiB / 512MiB` | `34.85%` | `112kB / 72kB` | `0B / 901kB` |
| `stage_2-hazelcast-1` | `0.98%` | `182.3MiB / 512MiB` | `35.60%` | `3.64MB / 905kB` | `0B / 918kB` |
