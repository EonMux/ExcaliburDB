# ExcaliburDB Usage Guide

A disk-backed vector similarity search engine with LLM-based relevancy scoring.

## TL;DR — Quick Start

```bash
# 1. Build the fat JAR
./gradlew :excalibur-api:shadowJar

# 2. Set your LLM API key (required for embeddings/scoring)
export EXCALIBUR_LLM_API_KEY=sk-your-openai-key

# 2b. Allow-list the directory you want indexed. Empty means DISABLED, not unrestricted,
#     so without this step step 4 is answered with 403.
cp excalibur.properties.example excalibur.properties
echo 'excalibur.ingest.allowed-roots=/absolute/path/to/docs' >> excalibur.properties

# 3. Run the server (listens on :8080)
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -jar excalibur-api/build/libs/excalibur.jar

# 4. Register a source to index (in another terminal)
curl -X POST http://localhost:8080/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "source_type": "filesystem",
    "config": {
      "source_id": "my-docs",
      "root_path": "/absolute/path/to/docs",
      "included_extensions": [".txt", ".md"]
    }
  }'

# 5. Query — just send the raw text; the server embeds it server-side
curl -X POST http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "how does the system handle concurrent writes?",
    "top_k": 10,
    "timeout_ms": 5000,
    "enable_llm_scoring": true
  }'

# 6. Check health/stats
curl http://localhost:8080/health
curl http://localhost:8080/v1/stats
```

---

## Table of Contents

1. [Requirements](#requirements)
2. [Installation](#installation)
3. [Running the Server](#running-the-server)
4. [Configuration](#configuration)
5. [HTTP API Reference](#http-api-reference)
6. [Ingesting Data](#ingesting-data)
7. [Querying](#querying)
8. [Monitoring & Operations](#monitoring--operations)
9. [Troubleshooting](#troubleshooting)
10. [Programmatic Usage](#programmatic-usage-jvm-embedded)

---

## Requirements

- **Java 21 — exactly, not 21+.** The modules compile with `--enable-preview` for the Panama FFM
  API, and a preview class file is refused by any JDK other than the one that produced it, so the
  runtime has to be 21 as well as the build.
- **Gradle 8.5** — supplied by the wrapper, whose distribution checksum is pinned in `gradle/wrapper/gradle-wrapper.properties`. Nothing needs to be installed.
- ~2 GB heap for typical workloads. The server sets **no** default heap size — pass `-Xmx` yourself.
  (`-Xmx2g` appears in the build only for the test JVM.) Size it deliberately: Lucene's inverted
  index works on the heap while the vector and graph columns are memory-mapped outside it, so both
  budgets are real and they compete for the same machine.
- An **LLM API key** if you want embeddings or relevancy scoring (OpenAI-compatible)

## Installation

```bash
# Clone and enter the repo
cd ExcaliburDB

# Build everything and run tests
./gradlew build

# Build the fat JAR only (output: excalibur-api/build/libs/excalibur.jar)
./gradlew :excalibur-api:shadowJar

# Build without tests (faster)
./gradlew build -x test
```

## Running the Server

### Basic run

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -jar excalibur-api/build/libs/excalibur.jar
```

The server listens on port **8080**, on loopback only (`127.0.0.1`). Reaching it from another
host means setting `excalibur.api.bind-address` — and a non-loopback bind is refused without TLS
unless `excalibur.api.allow-plaintext` says otherwise, because the bearer token would otherwise
cross the network in the clear.

### With a custom config file

Place `excalibur.properties` in the working directory, or pass it explicitly:

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -jar excalibur.jar --config /path/to/excalibur.properties
```

### Override the port

```bash
EXCALIBUR_PORT=9090 java --enable-preview --enable-native-access=ALL-UNNAMED \
     -jar excalibur.jar
```

### Via Gradle (development)

```bash
./gradlew :excalibur-api:run
```

### Shutdown

Send `SIGTERM` (or `Ctrl+C`). The server performs a graceful shutdown, waiting for in-flight queries and stopping the index orchestrator cleanly.

---

## Configuration

Configuration comes from exactly two places, applied in this order:

1. Built-in defaults
2. **One** properties file
3. Environment variables prefixed with `EXCALIBUR_`

Step 2 is one file, not a chain. The path is whatever `--config <path>` gives, defaulting to
`excalibur.properties`; it is read from the filesystem if it exists there, and otherwise from the
classpath under the same name. A file on the classpath does **not** layer underneath a filesystem
one — the first that resolves is the only one read.

A value that is present but unparseable (a bad number, a non-finite double, an unrecognised
boolean) fails startup naming the key, rather than silently falling back to the default. Blank and
absent values still take the default.

### Environment variable naming

Property keys convert to env vars by uppercasing and replacing `.` / `-` with `_`:

| Property | Environment variable |
|---|---|
| `excalibur.llm.api-key` | `EXCALIBUR_LLM_API_KEY` |
| `excalibur.llm.base-url` | `EXCALIBUR_LLM_BASE_URL` |
| `excalibur.query.top-k-default` | `EXCALIBUR_QUERY_TOP_K_DEFAULT` |

Only variables starting with `EXCALIBUR_` are processed — other env vars (`HOME`, `PATH`, etc.) are ignored.

### Common configuration

The most important knobs:

```properties
# LLM (required for embeddings; optional for scoring)
excalibur.llm.api-key=sk-...
# Either spelling works: the clients append /v1/embeddings themselves and a trailing
# /v1 is stripped rather than duplicated.
excalibur.llm.base-url=https://api.openai.com/v1
excalibur.llm.embedding-model=text-embedding-3-small
excalibur.llm.chat-model=gpt-4o-mini
excalibur.llm.embeddings-dimensions=1536
# Set false to skip the corpus embedding pass and keep the chat model: retrieval becomes
# BM25 over the chunk index with the query expanded by the chat model.
excalibur.llm.embeddings-enabled=true

# Query
excalibur.query.top-k-default=100
excalibur.query.vector-dimensions=1536      # MUST match your embedding model
excalibur.query.llm-timeout-ms=60000

# HNSW tuning (accuracy vs. speed)
excalibur.shard.default-ef-construction=128
excalibur.shard.default-ef-search=64

# Circuit breaker (fallback to vector-only when LLM is unavailable)
excalibur.llm.failure-threshold=5
excalibur.llm.cooldown-period-ms=60000
```

See [README.md](README.md#configuration-options) for the full property table.

---

## HTTP API Reference

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/health` | none | Liveness + readiness probe |
| `GET` | `/v1/stats` | bearer | Runtime statistics |
| `POST` | `/v1/query` | bearer | Vector similarity search |
| `DELETE` | `/v1/query/{query_id}` | bearer | Cancel an in-progress query |
| `POST` | `/v1/ingest` | bearer | Register an ingestion source |
| `DELETE` | `/v1/ingest` | bearer | Stop crawling a source, keeping its documents |
| `GET` | `/v1/ingest/status` | bearer | Per-source ingestion status |
| `POST` | `/v1/reindex` | bearer | Force re-crawl of a registered source |
| `POST` | `/v1/erasure` | bearer | Remove named documents from the corpus |
| `POST` | `/v1/graph/query` | bearer | Answer a question from the graph |
| `POST` | `/v1/graph/build` | bearer | Publish the facts ingestion has staged as the served graph |
| `POST` | `/v1/graph/erasure` | bearer | Record an obligation to erase, and compact when it is due |

All request/response bodies are JSON. Request size limit: **1 MB**.

### Authentication

Authentication is off until a token is configured, which is why the examples in this guide carry no
`Authorization` header. Set one and every path except `/health` then requires it:

```bash
export EXCALIBUR_API_AUTH_TOKEN='...'
curl -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" ...
```

There is no default token and none is compiled in, and the startup log warns for as long as none is
set. That default is safe only because it is paired with the bind policy: the server binds
`127.0.0.1` unless told otherwise, and refuses to start on a non-loopback address without a token
rather than serving the world by accident. `/health` is exempt because a liveness probe cannot carry
a token, and it is still rate limited.

Reads and mutations have separate rate-limit budgets; exceeding either returns `429` with
`Retry-After`.

Budgets are per caller, keyed on the address the request arrives from. Behind a reverse proxy that
address is the proxy's and every caller shares one budget, so list the proxy in
`excalibur.api.trusted-proxies` to have its `X-Forwarded-For` read instead.

### TLS

The examples here use `http://localhost:8080`, which is the loopback default. Serving anything else
requires TLS as well as a token — a bearer token over cleartext is a credential on the wire in every
request:

```bash
export EXCALIBUR_API_TLS_KEYSTORE_PASSWORD='...'
```

```properties
excalibur.api.bind-address=0.0.0.0
excalibur.api.tls-keystore=/etc/excalibur/server.p12
```

Every URL then becomes `https://`. If TLS is terminated in front of this process, set
`excalibur.api.allow-plaintext=true` to say so; the server will not otherwise bind a non-loopback
address in cleartext. See the README for the full key list, including mutual TLS.

The graph routes are registered on every node. A node that has never run a build answers
`/v1/graph/query` with `503` naming the build as what is missing — not `404`, which would say the
node cannot do this at all.

---

## Ingesting Data

Register a source with `POST /v1/ingest`. Ingestion runs in the background after registration.

A registration lasts until it is cancelled, not until the process ends: it is written to
`<data dir>/source-catalog` before the response is sent, and a restarted node puts every record
back on the re-crawl schedule. Only `DELETE /v1/ingest` or a source-scoped erasure removes it.
Registrations are restored through the same allow-lists a fresh request would meet, so a source
whose root or host is no longer permitted is left on the catalog and not crawled, with a warning
naming it at startup — allow the target again, or `DELETE /v1/ingest` to cancel it for good.

### Filesystem source

**Request**

```bash
curl -X POST http://localhost:8080/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "source_type": "filesystem",
    "config": {
      "source_id": "project-docs",
      "root_path": "/home/user/projects/docs",
      "included_extensions": [".txt", ".md", ".json"],
      "excluded_directories": [".git", "node_modules", "build"]
    }
  }'
```

**Required:** `source_id`, `root_path` (absolute path)
**Optional:** `included_extensions`, `excluded_directories`

**Response — `200 OK`**

```json
{
  "source_id": "project-docs",
  "source_type": "filesystem",
  "status": "registered"
}
```

### HTTP source (web crawler)

**Request**

```bash
curl -X POST http://localhost:8080/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "source_type": "http",
    "config": {
      "source_id": "docs-site",
      "base_url": "https://example.com/docs"
    }
  }'
```

**Required:** `source_id`, `base_url`
Crawler defaults: max depth 3, 30s timeout, 10 MB max response size. Only URLs under the base path are followed.

**Response — `200 OK`**

```json
{
  "source_id": "docs-site",
  "source_type": "http",
  "status": "registered"
}
```

### Error responses

All ingest errors use the common envelope `{"error": "<message>"}` with the appropriate HTTP status code.

**`409 Conflict` — source already registered**

```json
{"error": "Source already registered: project-docs"}
```

**`400 Bad Request` — missing or invalid field**

```json
{"error": "Bad Request: Field 'config.source_id' is required"}
```

**`400 Bad Request` — `source_id` longer than 256 characters**

```json
{"error": "Bad Request: 'config.source_id' must be at most 256 characters: 300"}
```

A source id is copied into every document's registry record and printed with every log line about
the source, so its length is multiplied by the size of the corpus rather than by the number of
requests. The routes that only look a source up — deregister, reindex, erasure and status — refuse
the same lengths, because nothing can be registered under one.

**`405 Method Not Allowed` — a method other than POST or DELETE**

```json
{"error": "Method Not Allowed - Use POST to register a source or DELETE to stop crawling one"}
```

Unauthenticated, and charged against the read budget: which handler serves a request is decided
before its token is, so an uncharged 405 would be free to send.

### Check progress

**Request — all sources**

```bash
curl http://localhost:8080/v1/ingest/status
```

**Response — `200 OK`** (wrapped in `sources` array)

```json
{
  "sources": [
    {
      "source_id": "project-docs",
      "state": "crawling",
      "progress": 0.7300,
      "entries_ingested": 450000,
      "entries_pending": 170000,
      "last_error": null
    },
    {
      "source_id": "docs-site",
      "state": "idle",
      "progress": 1.0000,
      "entries_ingested": 12034,
      "entries_pending": 0,
      "last_error": null
    }
  ]
}
```

**Request — specific source**

```bash
curl "http://localhost:8080/v1/ingest/status?source_id=project-docs"
```

**Response — `200 OK`** (bare status object, not wrapped)

```json
{
  "source_id": "project-docs",
  "state": "crawling",
  "progress": 0.7300,
  "entries_ingested": 450000,
  "entries_pending": 170000,
  "last_error": null
}
```

**Response — `404 Not Found`** (unknown source)

```json
{"error": "Source not found: project-docs"}
```

`state` is one of `idle`, `crawling`, `embedding`, `indexing`, or `unconfigured`. `progress` is
`[0.0, 1.0]`. `last_error` is `null` unless the most recent ingestion cycle failed.

`unconfigured` means this node's ingestion pipeline failed to wire at startup. The source was still
crawled, so the documents that exist are counted into `entries_pending` and none were indexed, and
`last_error` says where the reason is — the startup log, under `Failed to configure ingestion
pipeline`. It is deliberately not reported as `idle` at `progress: 1.0`, which would make a node
that indexes nothing at all look exactly like a successful run. A missing API key is not this
state: that node has a pipeline and indexes lexically.

### Re-index a source

Force a full re-crawl with zero-downtime rolling replacement. The old index stays queryable until the new one finishes indexing; then the old segments are tombstoned.

**Request**

```bash
curl -X POST http://localhost:8080/v1/reindex \
  -H "Content-Type: application/json" \
  -d '{"source_id": "project-docs"}'
```

**Response — `202 Accepted`**

```json
{
  "source_id": "project-docs",
  "status": "reindexing",
  "message": "Reindex started in background"
}
```

**Error — `404 Not Found`**

```json
{"error": "Source not found: unknown-source"}
```

**Error — `409 Conflict` (reindex already running for that source)**

```json
{"error": "Reindex already in progress for source: project-docs"}
```

### Stop crawling a source

Takes a source out of the crawl: any ingestion run in flight is stopped, its `/v1/ingest/status`
entry is cleared, and it is removed from the re-crawl schedule. **The documents it already indexed
are kept** and stay queryable — this is the routine case of a source that moved, went stale or
finished, and it is deliberately not the same route as erasing them.

**Request**

```bash
curl -X DELETE http://localhost:8080/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{"source_id": "project-docs"}'
```

**Response — `200 OK`**

```json
{
  "source_id": "project-docs",
  "deregistered": true,
  "documents_kept": true,
  "detail": "Crawling stopped. Documents already indexed are unchanged and still queryable; POST /v1/erasure removes them."
}
```

**Error — `404 Not Found`** — no source is registered under that id. Reported rather than answered
with a quiet success, so a misspelled id does not read as a stopped crawl.

### Erase a document

Removes named documents from the corpus: their chunks leave storage, they are erased from the
lexical index, and their registry entries are dropped. Use this for content someone asked to have
removed. For content that merely changed, reindex the source instead — a reindex removes what the
source stopped listing, and it does not touch a document the source still has.

**Request**

```bash
curl -X POST http://localhost:8080/v1/erasure \
  -H "Content-Type: application/json" \
  -d '{"source_id": "project-docs", "document_id": "handbook.md"}'
```

`document_ids` takes an array instead, up to 1000 per request. Add `"compact": true` to rewrite the
lexical index before the reply, so the chunks are off disk rather than scheduled for removal — it
costs a rewrite of every segment holding a tombstone, which is why it is not the default.

To erase everything one source contributed, without listing its documents:

```bash
curl -X POST http://localhost:8080/v1/erasure \
  -H "Content-Type: application/json" \
  -d '{"source_id": "project-docs", "scope": "source"}'
```

`scope` is a separate word rather than an omission: a request naming no document is a `400`, so
"everything" cannot be reached by a client that forgot a field. It cannot be combined with
`document_id` or `document_ids` either. A source-scoped erasure also **deregisters the source** —
stopping any ingestion run in flight, clearing its `/v1/ingest/status` entry and cancelling its
re-crawl schedule — so nothing restores what was just erased, and reports `source_deregistered`.
Each request erases up to 1000 documents and reports `documents_remaining`; repeat until it reaches
zero. A run stopped mid-flight finishes the document it was on, so one more may land between two
calls, which is what the remaining count is for.

**Response — `200 OK`**

```json
{
  "source_id": "project-docs",
  "scope": "document",
  "documents_erased": 1,
  "documents_not_found": 0,
  "documents_remaining": 0,
  "source_deregistered": false,
  "chunks_erased": 7,
  "chunks_unindexed": 7,
  "compacted": false,
  "chunks_reclaimed": 0,
  "outstanding": 7,
  "detail": "not due: 7 of 41230 chunk(s) tombstoned across 3 segment(s)"
}
```

The removals are counted separately because either can fall short:

- `chunks_erased` left storage, which is the only place a chunk's text and vector live.
- `chunks_unindexed` were erased from the lexical index, which stops them matching immediately.
- `compacted` and `chunks_reclaimed` say whether the index was rewritten, which is what takes the chunks off disk. `outstanding` is what the node still owes; it falls to zero once a compaction has run. Watch it on `GET /v1/stats` under `lexical`.
- `documents_not_found` is reported rather than folded into success: a caller under an obligation needs to know this node never held the document instead of assuming it was removed.

The request is safe to repeat. Nothing is removed from the registry until storage and the index are
done with it, so re-issuing a request that was interrupted finishes what it left.

What it does not cover: the vector indexes are reclaimed per segment rather than per entry, so until
the segment holding an erased chunk is compacted a vector query can still rank it — without content,
which is resolved from storage and no longer there, but under its chunk id. The membership filter is
also left alone deliberately: it indexes terms, which are shared with documents that remain.

Nor can it reach the source. A document erased here and still present in the directory or website it
was crawled from comes back on the next re-crawl — which is why a source-scoped erasure deregisters
the source, and why a document-scoped one is only half of an obligation if the source still serves
the document.

**Error — `400 Bad Request`**

```json
{"error": "'document_id' or a non-empty 'document_ids' array is required"}
```

**Error — `404 Not Found`** — the node was built without an erasure service, so the route is not
registered. Not the same as a document that does not exist, which is a `200` with
`documents_not_found`.

---

## Querying

### Request

`POST /v1/query` — send raw query text. The server embeds it using the configured `excalibur.llm.embedding-model` (the same model used for ingestion) and runs the search.

```bash
curl -X POST http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "what is the system'\''s consistency model?",
    "top_k": 10,
    "threshold": 0.5,
    "timeout_ms": 5000,
    "include_partial_segments": true,
    "enable_llm_scoring": true
  }'
```

| Field | Type | Default | Description |
|---|---|---|---|
| `query` | string | **required** | Raw query text. Embedded server-side; also used for LLM re-ranking when enabled. |
| `top_k` | int | `100` | Max results to return. Must be > 0. |
| `threshold` | float | none | Minimum similarity score to include. |
| `timeout_ms` | int | `30000` | Partial results returned if exceeded. |
| `include_partial_segments` | bool | `true` | Include segments that are still indexing. |
| `enable_llm_scoring` | bool | `true` | Re-rank top candidates using the LLM. |

### Why no client-side vectors?

The HTTP API intentionally does **not** accept pre-computed vectors. A vector is only meaningful in the embedding space of a specific model — if a client embeds with a different model than ingestion used, similarity scores become meaningless but still look plausible. Embedding is a server invariant, so the server owns both sides of it.

If you need custom embeddings (e.g., a local model, a different provider), change the server config — `excalibur.llm.base-url`, `excalibur.llm.embedding-model`, and `excalibur.llm.api-key` work with any OpenAI-compatible embedding API (OpenAI, Ollama, vLLM, TGI, etc.).

### Response — `200 OK`

```json
{
  "query_id": "b3d9f4a0-1e2c-4a7b-9c3d-5e6f7a8b9c0d",
  "results": [
    {"document_id": "my-docs:guide.md:chunk:2", "score": 0.950000, "segment_id": 3},
    {"document_id": "my-docs:guide.md:chunk:7", "score": 0.910000, "segment_id": 3}
  ],
  "coverage": {
    "total_segments": 1024,
    "active_segments": 980,
    "indexing_segments": 40,
    "warming_segments": 4,
    "compacting_segments": 0,
    "tombstoned_segments": 0,
    "estimated_corpus_coverage": 0.9600,
    "segments_searched": 980,
    "segments_excluded": 44,
    "segments_with_errors": 0,
    "segments_timed_out": 0,
    "tail_scan_entries": 12500,
    "degraded": false
  },
  "timed_out": false,
  "query_time_ms": 42
}
```

Key fields:

- **`query_id`** — Server-generated UUID for this query. Pass it to `DELETE /v1/query/{query_id}` to cancel mid-flight.
- **`results`** — Each item has `document_id` (string), `score` (float, six decimal places) and `segment_id` (int). `document_id` is the identifier ingestion assigned, `sourceId:documentId:chunk:N`; it is always a JSON string, never a bare number, and it is never the HNSW node id, which compaction renumbers. Result payloads and text are not returned — the API returns identifiers, and the caller looks content up in its own document store.
- **`coverage.estimated_corpus_coverage`** — Fraction of the corpus queried. < 1.0 during cold-start or heavy ingestion. Use this to decide whether to retry.
- **`coverage.degraded`** — `true` if the query had to fall back due to partial unavailability.
- **`timed_out`** — `true` if the timeout was hit. Partial results may still be returned (best-effort).

### Timeout response — still `200 OK`

Timeouts are a normal outcome, not an error, so partial results still return with HTTP 200 and `"timed_out": true`:

```json
{
  "query_id": "f12c8d34-5678-4abc-9def-0123456789ab",
  "results": [
    {"document_id": "my-docs:runbook.md:chunk:0", "score": 0.880000, "segment_id": 11}
  ],
  "coverage": {
    "total_segments": 1024,
    "active_segments": 512,
    "indexing_segments": 0,
    "warming_segments": 0,
    "compacting_segments": 0,
    "tombstoned_segments": 0,
    "estimated_corpus_coverage": 0.5000,
    "segments_searched": 512,
    "segments_excluded": 0,
    "segments_with_errors": 0,
    "segments_timed_out": 512,
    "tail_scan_entries": 0,
    "degraded": true
  },
  "timed_out": true,
  "query_time_ms": 5000
}
```

### Degraded retrieval — lexical answers

A node started without an embedding endpoint still chunks, stores and indexes what it ingests; it
just indexes for BM25 rather than for vectors. `/v1/query` answers from that index, with the same
status code and the same coverage block, plus a `retrieval` field naming the path taken:

```json
{
  "query_id": "8d190cb4-8171-4561-a2bb-8d7cce1ecdde",
  "results": [
    {"document_id": "project-docs:a.md:chunk:0", "score": 0.016393}
  ],
  "retrieval": "lexical",
  "coverage": {
    "total_segments": 1,
    "active_segments": 1,
    "indexing_segments": 0,
    "warming_segments": 0,
    "compacting_segments": 0,
    "tombstoned_segments": 0,
    "estimated_corpus_coverage": 1.0000,
    "segments_searched": 1,
    "segments_excluded": 0,
    "segments_with_errors": 0,
    "segments_timed_out": 0,
    "tail_scan_entries": 0,
    "degraded": true
  },
  "timed_out": false,
  "query_time_ms": 0
}
```

Three differences from a vector answer:

- **`retrieval` is present and reads `lexical`.** The vector path omits the field. Branch on its
  absence, not on its value, if you want to know which one you got.
- **`degraded` is always `true`,** even at full coverage. The answer is real; it came from one
  signal where a fully configured node fuses several, and that is worth telling a caller.
- **`segment_id` is absent from each result.** The lexical store has no HNSW segment to name.

Scores are BM25, not cosine similarity — they are not comparable to a vector answer's scores and
are not bounded by 1.0. Rank them; do not threshold them against a number tuned on the vector path.

Two ways to reach this path. With no API key it is plain BM25 — the startup log says so. With a
key and `excalibur.llm.embeddings-enabled=false` the chat model is kept and spent on expanding the
query instead of on embedding every document, which is the deployment this path was built for: a
few dozen generated tokens per query against billions of embedded tokens per corpus.

### Error responses

**`400 Bad Request` — missing `query` field**

```json
{"error": "Bad Request: Field 'query' is required and must be non-empty"}
```

**`400 Bad Request` — `query` longer than `excalibur.api.max-query-chars`**

```json
{"error": "Bad Request: Field 'query' must be at most 8192 characters: 40000"}
```

The query text is the body of the embedding call, and on the lexical path of the expansion call as
well, so its length is an outbound request the caller sizes. The default is four times a chunk,
which is the largest unit anything in the index was embedded as.

**`503 Service Unavailable` — no retrieval path configured**

```json
{"error": "Server is not configured for text queries: this node has neither an embedding client nor a lexical index, so there is no index to search. Set excalibur.llm.api-key (or EXCALIBUR_LLM_API_KEY) so the server can embed queries with the same model that ingestion uses, or see the startup log for why the chunk lexical index was not opened."}
```

A missing embedding key alone does not produce this: without one the node answers from BM25
instead — see **Degraded retrieval** above. Reaching 503 means the chunk lexical
index failed to open as well. The body names the configuration key and nothing else — no paths,
no exception text — so it stays safe to return to an unauthenticated caller while still being
actionable; the reason the index would not open is in the startup log.

**`401 Unauthorized` — missing or wrong bearer token**

```json
{"error": "Authentication failed"}
```

**`429 Too Many Requests` — rate limit exceeded**

```json
{"error": "Rate limit exceeded"}
```

Carries a `Retry-After` header. Reads and mutations have separate budgets.

**`405 Method Not Allowed` — a method other than POST or DELETE**

```json
{"error": "Method Not Allowed - Use POST for queries or DELETE for cancellation"}
```

Unauthenticated, and charged against the read budget: which handler serves a request is decided
before its token is, so an uncharged 405 would be free to send.

### Cancel a query

**Request**

```bash
curl -X DELETE http://localhost:8080/v1/query/b3d9f4a0-1e2c-4a7b-9c3d-5e6f7a8b9c0d
```

**Response — `200 OK`** (cancellation signalled successfully)

```json
{"cancelled": true, "query_id": "b3d9f4a0-1e2c-4a7b-9c3d-5e6f7a8b9c0d"}
```

**Response — `200 OK`** (query was already cancelled by a prior request)

```json
{"cancelled": false, "query_id": "b3d9f4a0-1e2c-4a7b-9c3d-5e6f7a8b9c0d", "message": "Query was already cancelled"}
```

**Response — `200 OK`** (query completed between lookup and cancel — race)

```json
{"cancelled": false, "query_id": "b3d9f4a0-1e2c-4a7b-9c3d-5e6f7a8b9c0d", "message": "Query completed before cancellation"}
```

**Response — `404 Not Found`** (unknown or already-completed query)

```json
{"error": "Query not found or already completed"}
```

**Response — `400 Bad Request`** (missing query ID in path)

```json
{"error": "Bad Request - Query ID is required in path (e.g., /v1/query/{query_id})"}
```

Use the `query_id` returned in the original query response. Cancellation is cooperative and propagates through all query pipeline stages — an in-flight query will observe the cancel signal and return its own response with partial (or empty) results.

---

## Monitoring & Operations

### Health check

**Request**

```bash
curl http://localhost:8080/health
```

**Response — always `200 OK` while the process is alive**

```json
{
  "live": true,
  "ready": true,
  "healthy": true,
  "epoch": 1234,
  "coverage": 0.98,
  "latencyMs": 5
}
```

**Response during cold start** (ready/healthy still false, but still `200 OK`)

```json
{
  "live": true,
  "ready": false,
  "healthy": false,
  "epoch": 0,
  "coverage": 0.0,
  "latencyMs": 2
}
```

- `live: true` as long as the process is running — use for liveness probes. The endpoint always returns HTTP 200 if the process is alive, so liveness probes should check this field rather than the status code.
- `ready` flips to `true` when `coverage ≥ excalibur.health.readiness-threshold` (default `0.5`) — use for readiness probes (load balancers / K8s readiness).
- `healthy` flips to `true` when `coverage ≥ excalibur.health.healthy-threshold` (default `0.9`) — the higher bar, useful for alerting when the corpus is not yet fully warmed.
- `epoch` — current visibility epoch (monotonically increasing).
- `coverage` — estimated corpus coverage in `[0.0, 1.0]`.
- `latencyMs` — time taken to compute the health response. If it exceeds `excalibur.health.max-latency-ms` (default `5000`), treat the system as degraded.

### Stats

**Request**

```bash
curl http://localhost:8080/v1/stats
```

**Response — `200 OK`**

```json
{
  "segments": {
    "total": 1024, "active": 980, "indexing": 40,
    "warming": 2, "compacting": 0, "tombstoned": 0, "purged": 2
  },
  "entries": {"total": 15000000},
  "volumes": {"count": 3, "totalBytes": 5368709120, "usedBytes": 3221225472},
  "hnsw": {"queueDepth": 50000, "queueDepthLimit": 100000, "vectorsIntegrated": 14000000},
  "cuckoo": {"pendingDeletions": 100, "totalDeletions": 5000,
             "avgFpRate": 0.000500, "maxFpRate": 0.001000},
  "visibility": {"epoch": 42, "registeredSegments": 1024, "visibleSegments": 1024},
  "coverage": 0.96,
  "lexical": {
    "engine": "native", "indexed": 15000000, "segments": 12,
    "liveChunks": 14980000, "erasedChunks": 20000, "erasedRatio": 0.0013,
    "erasureRequests": 3, "oldestErasureAgeMs": 412000, "compactionDue": false
  }
}
```

Watch `hnsw.queueDepth` — if it grows without bound, the integration worker can't keep up with ingestion (check logs for errors).

The `lexical` block is present only when this node keeps a chunk index; an absent block and an index
reporting zeroes are different states. Within it:

- `engine` is which implementation is answering, `native` or `lucene`. `segments` is how many the index holds, which is what a query has to visit — compaction is what keeps it bounded.
- `indexed` counts the chunks handed to this index since the process opened it, which is what to watch during an ingestion run. It is not the size of the corpus: a node restarted over a populated store reports `0` here and a non-zero `liveChunks` until something ingests again.
- `liveChunks` is what a query can match. `erasedChunks` is hidden from queries but still on disk — erased chunks and replaced versions of re-crawled ones both count here, and a compaction is what removes them.
- `erasureRequests` and `oldestErasureAgeMs` are the erasure backlog: what was promised and not yet discharged, and how long the oldest has waited. Compare the age against `excalibur.ingest.lexical-max-latency-seconds`. Nothing else in this response would show that deadline going unmet.
- `compactionDue` is what the index's policy would decide if asked right now. Compaction runs at the end of an ingestion or reindex run, so a node reporting `true` and left idle stays that way until something ingests.
- On `engine: "lucene"` the erasure fields are always zero and `compactionDue` always `false`: Lucene reclaims deleted documents on its own merge schedule, holding no obligation this node could report on.

### JMX metrics

The server also exposes JMX MBeans under the `io.excalibur` domain. Attach any JMX client (VisualVM, JConsole, Prometheus JMX exporter) to the JVM.

### Logs

Logback is pre-configured with JSON structured logging via logstash-encoder. Point your log shipper at stdout.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Server is not configured for text queries: this node has neither an embedding client nor a lexical index` | Not a missing API key on its own — without one the node still answers from BM25. Both retrieval paths are gone, so the chunk lexical index also failed to open | Search the startup log for `Chunk lexical index unavailable` and fix what it names (usually the data directory); set `EXCALIBUR_LLM_API_KEY` as well if you want vector retrieval |
| Results look like noise / no relevant matches | The `excalibur.llm.embedding-model` config changed between ingestion and query runs | Reingest with the current model — old vectors are incompatible once the embedding model changes |
| `Field 'query' is required and must be non-empty` | Request body is missing the `query` field | Send the query text in the `query` field (the HTTP API no longer accepts raw vectors) |
| `hnsw.queueDepth` keeps growing | Integration worker falling behind | Check logs for errors; slow down ingestion; increase hardware |
| All LLM calls failing | Circuit breaker tripped | Wait for cooldown period (default 60s). Confirm API key/endpoint. Queries still work without LLM scoring. |
| Queries return nothing on a fresh node, but `coverage` reads `1.0000` | Not a cold-start indicator. A node with no documents has searched all of the nothing it has, so coverage is 1.0 and `/health` reports `ready` and `healthy` | Look at `/v1/stats` instead: `entries.total` and `segments.active` are `0` until something is indexed |
| A source keeps being re-crawled after you erased its documents | Document-scoped erasure does not deregister the source; only `scope: "source"` does | `DELETE /v1/ingest` to stop the crawl, or erase with `scope: "source"` |
| `POST /v1/erasure` returns 404 | The node was built without an erasure service; the route is not registered | Check the startup log's registered-endpoint line. A node whose ingestion pipeline failed to wire has no registry to erase from |
| `/v1/erasure` reports `documents_not_found` for a document you can query | Its registry entry is gone, so the chunk ids it named are unknown; the chunks are still in storage | Reindex the source, which re-registers the document, then erase it |
| A source stops being re-crawled after a restart, and `/v1/ingest/status` does not list it | Its target is no longer allow-listed, so restoring it was refused; the startup log names it | Put the root or host back on `excalibur.ingest.allowed-roots` / `excalibur.ingest.allowed-hosts`, or `DELETE /v1/ingest` if the source is finished |
| A re-crawl reingests everything and tombstones nothing | `<data dir>/document-registry` is missing, so change detection reads every document as new | Restore the data directory whole; the registry belongs with the segments it describes |
| `lexical.oldestErasureAgeMs` exceeds `lexical-max-latency-seconds` | Compaction runs at the end of an ingestion or reindex run, and this node has not had one since the erasure | Ingest or reindex anything; the run settles the backlog before it finishes |
| A re-crawled document appears twice in results | Not the chunk index — it replaces a chunk identifier rather than appending. Check whether the source is listing one document under two ids | Compare the `chunk_id` values on the two hits: identical means a bug, different means the source |
| Queries return nothing on a large corpus, and coverage looks fine | The membership filter is full. It cannot record a term it has no room for, and an unrecorded term prunes every segment | Raise `excalibur.filter.corpus-capacity` past your entry count and restart |
| `--enable-preview` error | Wrong Java version | Java 21 exactly — a preview class file is refused by any other JDK |
| `--enable-native-access=ALL-UNNAMED` warning | Missing flag | Required for Panama FFM API |
| Port already in use | Another process on 8080 | `EXCALIBUR_PORT=9090 java ...` |
| Process exits 1 with no message at all | A fatal startup error was lost before the async log appender drained | Fixed by the logback shutdown hook; if you see it again on a modified `logback.xml`, check that `<shutdownHook/>` is still declared |

### Enabling debug logging

Add `-Dlogback.configurationFile=/path/to/logback-debug.xml` to the JVM args, or edit the default `logback.xml` in the classpath to set `<root level="DEBUG"/>`.

---

## Programmatic Usage (JVM Embedded)

You can embed ExcaliburDB directly in a JVM application.

**Nothing is on Maven Central.** `./gradlew publishToMavenLocal` installs all sixteen library
modules into `~/.m2`, so a coordinate dependency resolves there; `excalibur-bench` and
`excalibur-integration` are not published, being a benchmark harness and a test module.

```bash
./gradlew publishToMavenLocal
```

A composite build is the alternative, and the better one while you are also changing ExcaliburDB:
it needs no publishing step and always matches your checkout.

```kotlin
// settings.gradle.kts of the consuming project
includeBuild("/path/to/ExcaliburDB")
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.excalibur:excalibur-store:1.0.0-SNAPSHOT")
    implementation("io.excalibur:excalibur-query:1.0.0-SNAPSHOT")
    implementation("io.excalibur:excalibur-vector:1.0.0-SNAPSHOT")
}
```

Gradle substitutes each coordinate with the project in the included build. The consuming project
must also compile and run on Java 21 with `--enable-preview`, for the reason in
[Requirements](#requirements).

### Minimal query example

When embedding ExcaliburDB directly, the pipeline still operates on vectors — it's your wrapper's job to generate the embedding (typically via `EmbeddingClient`) before invoking the pipeline. The HTTP API handles this step automatically; the embedded API is one level lower.

```java
import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.query.executor.CancellationToken;
import excalibur.llm.embedding.EmbeddingClient;

// Assume `pipeline` and `embeddingClient` are already configured.
// Use the SAME embeddingClient for ingestion and query to keep
// the vectors in the same embedding space.
String queryText = "search query text";
float[] vector = embeddingClient.embed(queryText);

QueryRequest request = QueryRequest.builder()
    .queryText(queryText)
    .queryVector(vector)
    .topK(10)
    .timeoutMs(5000)
    .includePartialSegments(true)
    .enableLlmScoring(false)
    .build();

CancellationToken token = new CancellationToken();
QueryResponse response = pipeline.executeSync(request, token);

response.results().forEach(item ->
    System.out.println(item.documentIdStr() + " -> " + item.score()));
```

Use `documentIdStr()`, not `documentId()`. The string is the identifier ingestion assigned and the
one the HTTP API returns; `documentId()` is the numeric handle, which for a string-keyed result is
the HNSW node id and matches nothing a caller can look up.

### Remember when embedding

- Always pass `--enable-preview --enable-native-access=ALL-UNNAMED` JVM flags.
- HNSW graphs cap at `Integer.MAX_VALUE` nodes per segment.
- Segment counts are power-of-two (extensible hashing on split).
- `StorageEngine` is thread-safe; multiple concurrent queries and ingests are supported.

---

## Further Reading

- [README.md](README.md) — architecture overview, module breakdown, benchmark list, Quick Start
- `excalibur.properties.example` — the tracked configuration template, annotated key by key. The
  working copy `excalibur.properties` is gitignored, because it is the file a key ends up in
- `excalibur-integration/src/test/java/` — end-to-end examples (ColdStart, LiveIndexing, RollingReindex, etc.)
