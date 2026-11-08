# Stage 2 — Service-Oriented Search Engine for Project Gutenberg (Group FourByTees / 4Bytees)

Single-host Service-Oriented Architecture (SOA) search engine for Project Gutenberg books, developed by **Group FourByTees (`com.fourbytees` / `4Bytees`)** for **Stage 2** of the Big Data course at **Universidad de Las Palmas de Gran Canaria (ULPGC)**.

- **Technical Report (PDF):** [`Stage_2_FourByTees.pdf`](Stage_2_FourByTees.pdf)

---

## Table of Contents

1. [System Architecture Overview](#1-system-architecture-overview)
2. [Prerequisites](#2-prerequisites)
3. [Project Structure & Module Layout](#3-project-structure--module-layout)
4. [Building & Running Unit/Integration Tests (Maven)](#4-building--running-unitintegration-tests-maven)
5. [Environment Configuration (`.env`)](#5-environment-configuration-env)
6. [Docker Compose Deployment (Single-Host)](#6-docker-compose-deployment-single-host)
7. [API Specification & HTTP/Operational Endpoints](#7-api-specification--httpoperational-endpoints)
8. [Seed Dataset Ingestion & End-to-End Validation](#8-seed-dataset-ingestion--end-to-end-validation)
9. [Benchmarking & Performance Evaluation](#9-benchmarking--performance-evaluation)
10. [Generated Directories & Cleanup](#10-generated-directories--cleanup)
11. [Stage 2 Single-Host Limitations & Stage 3 Outlook](#11-stage-2-single-host-limitations--stage-3-outlook)

---

## 1. System Architecture Overview

In Stage 2, the monolithic pipeline from Stage 1 is decomposed into three decoupled Java 17 microservices coordinated via **Apache ActiveMQ Classic 5.18.3** (asynchronous event queues) and **Hazelcast 5.3.6** (distributed state maps, atomic counters, and CP locks), with **Nginx** acting as the reverse proxy and load balancer in front of replicated search instances. All containers run on a **single host** sharing local bind-mounted volumes (`./datalake` and `./datamarts`).

```text
                       +-----------------------------------+
                       |     Client / E2E / Benchmark      |
                       +-----------------+-----------------+
                                         |
                    +--------------------+--------------------+
                    | HTTP :7001                              | HTTP :7000
                    v                                         v
        +-----------------------+                 +-----------------------+
        |   ingestion-service   |                 |   nginx (load bal.)   |
        |      (Port 7001)      |                 |   (Host Port 7000)    |
        +-----------+-----------+                 +-----------+-----------+
                    |                                         |
     1. Write book  |  2. State=DOWNLOADED                    | Round-robin
     + .done marker |  + Publish BookDownloaded               v
                    |  + .published marker        +-----------------------+
                    |                             |    search-service     |
                    v                             |  (Replicas, :7000)    |
        +-----------------------+                 +-----------+-----------+
        |   Shared Datalake     |                             |
        | (./datalake/YYYYMMDD/ |                             | Read-only SQLite
        |   HH/<id>.header.txt, |                             | connections (WAL)
        |   <id>.body.txt,      |                             v
        |   <id>.done,          |                 +-----------------------+
        |   <id>.published)     |                 |   Shared Datamart     |
        +-----------+-----------+                 | (./datamarts/         |
                    ^                             |   metadata.db)        |
                    |                             +-----------+-----------+
                    | 4. Parse header &                       ^
                    |    tokenize body                        | 5. Write under
                    +--------------------+--------------------+    FencedLock
                                         |                         (index-writer)
                                         |
                             +-----------+-----------+
                             |   indexing-service    |
                             | (Internal Port 7002)  |
                             +-----------+-----------+
                                         ^
                                         | 3. Consume books.downloaded
                                         |    Claim book in Hazelcast
                                         |    Publish books.indexed / DLQ
                             +-----------+-----------+
                             | ActiveMQ Classic 5.18 |
                             |  & Hazelcast 5.3.6    |
                             | (:61616 / :5701)      |
                             +-----------------------+
```

### Core Components

| Component | Technology | Internal Port | Host Port | Role |
| :--- | :--- | :---: | :---: | :--- |
| **`ingestion-service`** | Java 17 / Javalin 6.1.3 | `7001` | `7001` | Downloads Project Gutenberg books, splits header/body into the timestamped datalake, creates `<bookId>.done`, updates Hazelcast `book-state` to `DOWNLOADED`, publishes `BookDownloaded` to ActiveMQ (`books.downloaded`), and writes `<bookId>.published` with background outbox recovery. |
| **`indexing-service`** | Java 17 / Javalin 6.1.3 / SQLite | `7002` | Internal only (`7002/tcp`) | Consumes `books.downloaded` via `JMS_CONSUMERS` threads, coordinates per-book claims (`book-claims` with `INDEXING_TIMEOUT_MS` TTL), parses metadata and inverted index postings using the frozen Stage 1 tokenizer contract, writes to SQLite under Hazelcast `FencedLock("index-writer")`, and publishes `BookIndexed` via a Hazelcast-backed post-commit outbox (`book-indexed-outbox`). Also provides atomic full-index rebuild (`POST /index/rebuild`). |
| **`search-service`** | Java 17 / Javalin 6.1.3 / SQLite | `7000` | Exposed via Nginx | Stateless read-only query replicas (`SEARCH_REPLICAS=2` by default) querying `/data/datamarts/metadata.db` in WAL mode with TF-IDF ranking (`freq` descending, tie-broken by `book_id` ascending), metadata filters (`author`, `language`, `year`), and pagination (`offset`, `limit`). |
| **`nginx`** | `nginx:alpine` | `80` | `7000` | Reverse proxy load-balancing client HTTP traffic across `search-service` replicas. |
| **`activemq`** | `apache/activemq-classic:5.18.3` | `61616` | `61616` | Persistent JMS message broker managing `books.downloaded`, `books.indexed`, and `books.dlq`. |
| **`hazelcast`** | `hazelcast/hazelcast:5.3.6` | `5701` | `5701` | Distributed coordination node hosting `IMap` structures (`book-state`, `book-claims`, `book-retries`, `book-indexed-outbox`), `IAtomicLong` (`index-published-version`), and CP `FencedLock`s (`index-writer`, `lock:index-rebuild`). |

### Frozen Stage 1 Tokenizer Contract

Both `indexing-service` (`BodyTokenizer`) and `search-service` (`DatabaseManager`) enforce the exact same tokenization rules:
- Input text is converted to lowercase.
- Token extraction uses the regular expression `\b[a-z]{3,}\b`.
- 0-based token positions are assigned over **all** regex matches *before* filtering stop words, and positions are **not** renumbered after stop-word removal.
- **Stop words (30 terms):** `a`, `an`, `and`, `are`, `as`, `at`, `be`, `but`, `by`, `for`, `if`, `in`, `into`, `is`, `it`, `no`, `not`, `of`, `on`, `or`, `such`, `that`, `the`, `their`, `then`, `there`, `these`, `they`, `this`, `to`.

---

## 2. Prerequisites

- **Java Development Kit (JDK):** Java 17 (e.g., Eclipse Temurin 17 or Microsoft OpenJDK 17). Verify with `java -version`.
- **Apache Maven:** Maven 3.9+ (for local builds, unit tests, and JMH microbenchmarks). Verify with `mvn -version`.
- **Docker & Docker Compose:** Docker Desktop (with Docker Engine 24+ and Docker Compose v2+). Verify with `docker info` and `docker compose version`.
- **Shell Environment:**
  - **Windows:** PowerShell 5.1+ or PowerShell 7+ (primary environment for `.ps1` validation and benchmark scripts).
  - **Linux / macOS / WSL2 / Git Bash:** Bash + `curl` (supported via `.sh` scripts).

---

## 3. Project Structure & Module Layout

```text
stage_2/
├── common-dto/                  # Shared DTO events (BookDownloaded, BookIndexed), constants, and JMS/Hazelcast client factories
├── ingestion-service/           # P1: Book download, datalake storage (.done/.published), and outbox publisher
├── indexing-service/            # P2: Header parser, body tokenizer, SQLite repository, atomic IndexRebuilder, JMS consumer
├── search-service/              # P3: Read-only SQLite query engine, SearchController, BookController, StatusController
├── docs/
│   └── openapi.yaml             # OpenAPI 3.0.3 contract + non-HTTP operational & coordination specification
├── hazelcast/
│   └── hazelcast.yaml           # Hazelcast 5.3.6 cluster and CP subsystem configuration
├── nginx/
│   └── nginx.conf               # Upstream load-balancer configuration for search-service replicas
├── scripts/
│   ├── test_end_to_end.ps1      # Official Windows PowerShell End-to-End validation script (9 API checks)
│   ├── test_end_to_end.sh       # Bash End-to-End validation script
│   └── run_benchmarks.ps1       # Automated benchmark suite (JMH + live Docker coordination & HTTP load experiments)
├── seed/
│   ├── book_ids.txt             # Curated 25-book Project Gutenberg seed dataset (English, Spanish, French)
│   ├── download_seed.ps1        # PowerShell seed ingestion runner
│   └── download_seed.sh         # Bash seed ingestion runner
├── tests/
│   └── search-api-tests.http    # 22 HTTP request definitions for IDE REST clients (IntelliJ / VS Code)
├── .env.example                 # Environment template for Docker Compose
├── Stage_2_FourByTees.pdf       # Official Stage 2 Technical Report & Benchmark Evaluation (PDF)
├── docker-compose.yml           # Single-host multi-container orchestration
└── pom.xml                      # Root Maven multimodule reactor POM
```

---

## 4. Building & Running Unit/Integration Tests (Maven)

From the repository root (`stage_2/`):

### Compile and Run the Full Automated Test Suite

```powershell
mvn clean test
```

The automated test suite executes **41 tests** across the reactor without requiring external Docker containers (using embedded ActiveMQ `vm://` brokers, local Hazelcast instances, and temporary `@TempDir` SQLite databases):
- **`ingestion-service` (`IngestionServiceTest`):** 2 tests — datalake persistence and crash recovery of `.done` files missing `.published`.
- **`indexing-service` (25 tests total):**
  - `HeaderParserTest` (6 tests): multiline Gutenberg headers, ISO-639-1 language normalization (`en`, `es`, `fr`, etc.), missing/nullable year and language handling.
  - `IndexingServiceTest` (5 tests): concurrent consumer claim exclusion, `RETRY_LATER` on active claims, expired claim takeover (`INDEXING_TIMEOUT_MS`), claim cleanup, and `ALREADY_INDEXED` idempotency.
  - `IndexRebuilderTest` (5 tests): atomic full rebuild into temporary SQLite database + `ATTACH DATABASE` swap, preservation of active index on failure, zero partial-state exposure to concurrent search readers, rejection of concurrent rebuilds (`RebuildInProgressException`), and cleanup of `.tmp`, `-wal`, and `-shm` files.
  - `IndexingControllerTest` (3 tests): HTTP `200 OK`, `409 Conflict`, and safe `500 Internal Server Error` mapping on `POST /index/rebuild`.
  - `BookIndexedPublicationTest` (4 tests): post-commit retry of `BookIndexed` events via `book-indexed-outbox` without re-executing SQLite writes, background outbox relay recovery, and idempotent duplicate event handling.
  - `JmsRedeliveryTest` (2 tests): end-to-end ActiveMQ redelivery after `session.recover()`, takeover after claim expiration, and routing to `books.dlq` after `MAX_RETRIES`.
- **`search-service` (`SearchServiceTest`):** 14 tests — TF-IDF ordering, tie-breaking, filters (`author`, `language`, `year`), pagination, stop-word handling, `400 Bad Request` validation, `404 Not Found` on missing books, and safe generic `500 Internal Server Error` responses (`{"error":"Internal server error"}`) that never leak database paths or SQL details.

### Package Executable Shaded JARs Without Running Tests

```powershell
mvn clean package -DskipTests
```

### Run Tests for a Single Module

```powershell
mvn -pl indexing-service -am clean test
```

---

## 5. Environment Configuration (`.env`)

Before starting Docker Compose, create a local `.env` file from `.env.example` (never commit `.env`):

```powershell
Copy-Item .env.example .env
```

Or on Linux/macOS/Git Bash:

```bash
cp .env.example .env
```

### Configurable Parameters

| Variable | Default (`.env.example`) | Applies To | Description |
| :--- | :---: | :--- | :--- |
| `SEARCH_PORT` | `7000` | `nginx` | Host port mapped to the Nginx reverse proxy fronting `search-service`. |
| `INGESTION_PORT` | `7001` | `ingestion` | Host and container port for `ingestion-service`. |
| `INDEXING_PORT` | `7002` | `indexing` | Internal container port for `indexing-service` operational endpoints. |
| `SEARCH_REPLICAS` | `2` | `search` | Number of stateless `search-service` container replicas behind Nginx. |
| `INDEXING_REPLICAS` | `1` | `indexing` | Number of `indexing-service` container replicas consuming `books.downloaded`. |
| `HZ_CLUSTER_NAME` | `dev` | All Java services, `hazelcast` | Hazelcast cluster name. |
| `HZ_ADDRESS` | `hazelcast:5701` | All Java services | Hazelcast member address (`host:port`). |
| `ACTIVEMQ_URL` | `tcp://activemq:61616` | `ingestion`, `indexing` | ActiveMQ Classic OpenWire broker URL. |
| `INDEX_WORKER_THREADS` | `4` | `indexing` | Parallel worker threads used during `POST /index/rebuild` batch parsing. |
| `JMS_CONSUMERS` | `2` | `indexing` | Concurrent `BookDownloadedConsumer` JMS listener threads per indexing replica. |
| `MAX_RETRIES` | `3` | `indexing` | Maximum failed indexing attempts in `book-retries` before routing to `books.dlq`. |
| `INDEXING_TIMEOUT_MS` | `300000` | `indexing` | Claim TTL in milliseconds (`5 minutes`). Claims older than this are treated as stale and can be taken over by another consumer on JMS redelivery. |
| `DATALAKE_PATH` | `/data/datalake` | `ingestion`, `indexing` | Container path where `./datalake` is mounted. |
| `SQLITE_DB_PATH` | `/data/datamarts/metadata.db` | `indexing`, `search` | Container path to the shared SQLite datamart (`METADATA_DB_PATH` alias in `search`). |
| `BOOK_INDEXED_PUBLISH_ATTEMPTS` | `3` | `indexing` (JVM env/prop) | Immediate post-commit attempts to publish `BookIndexed` before leaving the event in `book-indexed-outbox` for background relay. |
| `OUTBOX_FLUSH_INTERVAL_MS` | `10000` | `indexing` (JVM env/prop) | Background relay interval (ms) for flushing pending `book-indexed-outbox` events. |
| `RETRY_LATER_DELAY_MS` | `500` | `indexing` (JVM env/prop) | Delay (ms) before calling `session.recover()` when another worker holds an active claim. |
| `JMS_REDELIVERY_INITIAL_DELAY_MS` | `500` | `indexing` (JVM env/prop) | Initial ActiveMQ redelivery delay (ms), with exponential backoff (`2.0x`, max `10000 ms`). |
| `ACTIVEMQ_MAX_HEAP` / `*_MAX_HEAP` | `256m` / `512m` | JVM containers | Maximum JVM heap (`-Xmx`) per service container. |
| `*_CPUS` / `*_MEM_LIMIT` | `1.0`–`1.5` / `512M`–`1024M` | Docker Compose | Per-container CPU and memory resource limits enforced by Docker. |

---

## 6. Docker Compose Deployment (Single-Host)

### Build Images and Start the Stack

```powershell
docker compose build
docker compose up -d
```

### Inspect Container Status and Health Checks

```powershell
docker compose ps
```

All six services configure Docker health checks:
- **`activemq`:** TCP check on `127.0.0.1:61616`
- **`hazelcast`:** `GET http://localhost:5701/hazelcast/health/ready`
- **`ingestion`:** `GET http://localhost:7001/status`
- **`indexing`:** `GET http://localhost:7002/status` (internal container network)
- **`search` (replicas):** `GET http://localhost:7000/status` (internal container network)
- **`nginx`:** `GET http://127.0.0.1/status` (mapped to host `http://localhost:7000/status`)

### Quick Health Probes from Host

```powershell
curl http://localhost:7000/status
curl http://localhost:7001/status
docker compose exec indexing curl -s http://localhost:7002/status
docker compose exec indexing curl -s http://localhost:7002/index/status
```

### Scale Indexing or Search Replicas Dynamically

```powershell
docker compose up -d --scale indexing=2 --scale search=2
```

### Stop the Stack

```powershell
docker compose down -v
```

---

## 7. API Specification & HTTP/Operational Endpoints

The complete OpenAPI 3.0.3 specification—including **Front-End HTTP APIs**, **Internal Service HTTP APIs**, **Operational HTTP APIs**, and **Non-HTTP Asynchronous & Distributed Coordination Contracts** (ActiveMQ queues, `.done`/`.published` markers, and Hazelcast maps/locks)—is located at:

- [`docs/openapi.yaml`](docs/openapi.yaml)
- Interactive REST client test collection: [`tests/search-api-tests.http`](tests/search-api-tests.http)

### Summary of HTTP Endpoints

| Service / Gateway | Method & Path | Category | Status Codes | Description |
| :--- | :--- | :--- | :---: | :--- |
| **Search (`:7000` via Nginx)** | `GET /search?q=<term>[&author=...][&language=...][&year=...][&offset=0][&limit=20]` | Front-End | `200`, `400`, `500` | Single-term full-text search ordered by TF-IDF (`freq DESC, book_id ASC`). Stop words return `200 OK` with `total: 0`. Multi-word queries or out-of-range parameters return `400 Bad Request`. |
| **Search (`:7000` via Nginx)** | `GET /books/{id}` | Front-End | `200`, `400`, `404`, `500` | Retrieve metadata (`book_id`, `title`, `author`, nullable `language`, nullable `year`) for a single book ID. |
| **Search (`:7000` via Nginx)** | `GET /search/filters` | Front-End | `200`, `500` | List distinct non-null `languages` and `years` currently present in the SQLite datamart. |
| **Search (`:7000` via Nginx)** | `GET /status` | Operational | `200` | Health check reporting `status` (`running` or `degraded`), `hazelcast_status`, `sqlite_status` (`SELECT 1`), and `active_index_version`. |
| **Ingestion (`:7001`)** | `POST /ingest/{book_id}` | Operational / Internal | `200`, `400`, `404`, `500` | Synchronously downloads a Gutenberg book into `./datalake` and publishes `BookDownloaded` (`status: "downloaded"` or `"already_downloaded"`). Indexing proceeds asynchronously. |
| **Ingestion (`:7001`)** | `GET /ingest/status/{book_id}` | Operational | `200`, `400` | Checks whether `<bookId>.done` exists in the datalake (`available` vs `not_found`). |
| **Ingestion (`:7001`)** | `GET /ingest/list` | Operational | `200`, `500` | Lists all book IDs that have a `<bookId>.done` marker in the datalake (`count`, `books`). |
| **Ingestion (`:7001`)** | `GET /status` | Operational | `200` | Health and datalake path status for `ingestion-service`. |
| **Indexing (`:7002` internal)** | `POST /index/update/{id}` | Internal / Operational | `200`, `400`, `404`, `500` | Force synchronous re-indexing of a single book from the datalake (`404` if no `.done` marker exists). |
| **Indexing (`:7002` internal)** | `POST /index/rebuild` | Operational | `200`, `409`, `500` | Safely rebuilds the entire SQLite index in a temporary database and atomically swaps its contents under `lock:index-rebuild` and `index-writer` without exposing partial or empty state to search readers. Concurrent rebuilds return `409 Conflict`. |
| **Indexing (`:7002` internal)** | `GET /index/status` | Operational | `200` | Returns `books_indexed`, `last_update` (ISO-8601), and `index_size_MB`. |
| **Indexing (`:7002` internal)** | `GET /status` | Operational | `200` | Liveness check for `indexing-service`. |

---

## 8. Seed Dataset Ingestion & End-to-End Validation

The repository includes a deterministic 25-book Project Gutenberg seed list in [`seed/book_ids.txt`](seed/book_ids.txt) containing 23 English books, 1 Spanish book (`2000`, *Don Quijote*), and 1 French book (`4650`, *Candide*).

### Run Seed Ingestion Only

- **PowerShell (Windows):**
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File ".\seed\download_seed.ps1"
  ```
- **Bash (Linux / macOS / WSL2):**
  ```bash
  bash ./seed/download_seed.sh
  ```

### Run the Complete Automated End-to-End Validation

- **PowerShell (Windows — Official Validation Command):**
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\test_end_to_end.ps1"
  ```
- **Bash (Linux / macOS / WSL2):**
  ```bash
  bash ./scripts/test_end_to_end.sh
  ```

### Guaranteed Behavior vs. Validated Baseline Example

- **Guaranteed behavior:** When all containers are healthy and Project Gutenberg is reachable, `test_end_to_end.ps1` verifies service readiness, ingests the seed list idempotently, waits until English (`q=adventure&language=en`), Spanish (`q=quijote&language=es`), and French (`q=candide&language=fr`) queries all return at least 1 match, and executes **9 API validation checks** (simple query, author filter, English/Spanish/French language filters, pagination, stop-word empty result `total=0`, missing `q` `400`, and negative `limit` `400`).
- **Validated baseline example (for the 25-book `seed/book_ids.txt`):**
  - 25 books ingested (`0` HTTP 404, `0` errors)
  - 25 books indexed in SQLite (`en=23`, `es=1`, `fr=1`)
  - `9 / 9` E2E API checks passed (`total=13` for `q=adventure`, `total=2` for `q=adventure&author=twain`, `total=1` for `q=quijote&language=es`, `total=1` for `q=candide&language=fr`).

---

## 9. Benchmarking & Performance Evaluation

All benchmark harnesses and outputs follow the Stage 2 experimental methodology:
1. **JMH Microbenchmarks** (`indexing-service/src/test/java/com/fourbytees/benchmark/` and `search-service/src/test/java/com/fourbytees/benchmark/`):
   - Evaluates `HeaderParser`, `BodyTokenizer`, `SqliteRepository` single-book and batch insertion, and `DatabaseManager` read-only SQLite search queries.
   - Configured with **5 warm-up iterations** (`400 ms` each), **10 measurement iterations** (`400 ms` each), **1 fork**, and fixed heap (`-Xms2g -Xmx2g`), exporting raw JSON and CSV artifacts.
2. **System, Coordination & HTTP Load Benchmarks** (`scripts/run_benchmarks.ps1`):
   - Evaluates indexing throughput and coordination overhead across varying worker threads (`INDEX_WORKER_THREADS` = 1, 2, 4, 8) and indexing replicas (`INDEXING_REPLICAS` = 1, 2), safe `POST /index/rebuild` latency, and HTTP `/search` latency (mean, standard deviation, p95, throughput, error rate) under increasing client concurrency (`c = 1, 4, 8, 16, 32`) along with container CPU/RAM telemetry (`docker stats --no-stream`).

### Run the Complete Benchmark Suite

Ensure the Docker Compose stack is up and the 25 seed books are ingested, then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\run_benchmarks.ps1"
```

### Benchmark Result Locations

Raw measured outputs (JSON, CSV, and environment metadata including commit hash, JDK version, and hardware specifications) are written to:
- `results/` (or `docs/benchmarks/` for tracked summary tables referenced by the technical report).

---

## 10. Generated Directories & Cleanup

Running the stack locally creates the following untracked runtime directories and files (all excluded via `.gitignore` and **must never be committed**):
- `.env` — local environment configuration file
- `datalake/` — downloaded Gutenberg headers, bodies, `.done`, and `.published` markers
- `datamarts/` — `metadata.db`, `metadata.db-wal`, `metadata.db-shm`, and temporary `.rebuild-*.tmp` files during index rebuilds
- `activemq-data/` — ActiveMQ KahaDB persistent message store
- `**/target/` — Maven build outputs and shaded JARs

### Full Clean Reset

```powershell
docker compose down -v
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue .\datalake, .\datamarts, .\activemq-data
mvn clean
```

---

## 11. Stage 2 Single-Host Limitations & Stage 3 Outlook

Stage 2 intentionally deploys all services and coordination brokers on a **single physical host**. Key architectural limitations that will be addressed in **Stage 3 (Multi-Node Cluster Deployment)** include:
1. **Shared Host Bind Mounts (`./datalake` and `./datamarts`):** Stage 2 relies on a shared local filesystem for the datalake and SQLite database file (`metadata.db`). Across multiple physical nodes, local bind mounts are not shared, and SQLite's OS-level file locking (`WAL`/`SHM` shared memory) is unsafe over network filesystems (NFS/SMB). Stage 3 requires a distributed storage layer or partitioned/replicated index shards.
2. **Single-Writer SQLite Bottleneck (`FencedLock("index-writer")`):** While multiple `indexing-service` replicas can download/parse books in parallel, SQLite commits are serialized through a distributed lock. Under Windows/WSL2 bind mounts (`9p`/`virtiofs`), `fsync` overhead dominates per-book commit latency.
3. **Single-Node Hazelcast CP Subsystem:** With a single Hazelcast member (`hazelcast:5701`), CP locks (`FencedLock`) and `IAtomicLong` lack multi-node Raft quorum fault tolerance, and `IMap` partition backups require multiple cluster members (`backup-count >= 1`) to survive node crashes.
4. **Single ActiveMQ Broker & Single Nginx Gateway:** Both `activemq` and `nginx` run as single container instances without broker clustering or virtual-IP failover.
5. **Project Gutenberg Publication Year Semantics:** Gutenberg headers typically record the **Project Gutenberg release/posting year** (`Release Date: ... [EBook #...]`) rather than the historical first-edition year of the literary work.
