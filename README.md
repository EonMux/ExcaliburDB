# ExcaliburDB

A disk-backed vector similarity search engine with LLM-based relevancy scoring. ExcaliburDB provides efficient approximate nearest neighbor (ANN) search over large document corpora using memory-mapped files and hierarchical filtering.

## Overview

ExcaliburDB is built on Java 21's Panama Foreign Function & Memory API for zero-copy, memory-mapped file I/O. It handles vector indexes larger than available RAM by keeping data on disk and navigating graphs directly from memory-mapped regions.

### Key Features

- **HNSW (Hierarchical Navigable Small World) graphs** for approximate nearest neighbor search with configurable efConstruction and efSearch parameters
- **Cuckoo filters** for probabilistic set membership testing with deletion support
- **LSH (Locality-Sensitive Hashing)** for coarse vector pre-filtering
- **Product quantization** for compressed vector storage
- **Epoch-based visibility tracking** for consistent query snapshots during concurrent ingestion
- **LLM integration** for semantic relevancy scoring with circuit breaker fallback
- **Queryability guarantee** - the system remains queryable at all times, including during initial indexing and re-indexing operations
- **Memory-mapped CSR graph** alongside the vector index, with a mandatory reverse index, so retrieval can traverse relationships rather than only find similar text
- **Bitemporal facts** - each edge carries the interval over which it held in the world, kept strictly separate from the epoch that records when the index learned it
- **Provenance per edge** - source, confidence and extractor version, stored columnar and serialised into the model's context so a curated claim can be told from a guess
- **Hybrid retrieval** - BM25 over an embedded Lucene index fused by reciprocal rank, then reranked against the entities' uncompressed vectors, then reranked again in one batched call to the chat model; each stage falls back to the ordering beneath it when its endpoint is unavailable
- **Calibrated abstention** - a sealed result type that can answer, decline, or hand back an ambiguity, with the threshold fitted against verifiable data rather than chosen by hand
- **Erasure that removes data** - `POST /v1/graph/erasure` records an obligation durably, and a compaction rewrites the tombstoned edges out of the store, driven by the oldest outstanding request and not only by how much has accumulated
- **Per-namespace isolation** - one graph directory per tenant, selected by a request header, with mapping-count and descriptor budgets that evict the least recently used graph before the kernel's limits are reached; a request holds a lease, so eviction never unmaps a graph out from under a reader
- **A graph the node builds** - ingestion extracts facts from the documents it publishes and stages them; `POST /v1/graph/build` turns everything staged into the edge store, the dictionaries and the two Lucene indexes, and serves the result without a restart

## Requirements

- Java 21 or later (required for Panama FFM API and virtual threads)
- Gradle 8.5 (supplied by the wrapper; `./gradlew` needs nothing installed)

## Building

```bash
# Build all modules
./gradlew build

# Build without running tests
./gradlew build -x test

# Build fat JAR for deployment
./gradlew :excalibur-api:shadowJar

# Run tests
./gradlew test

# Run tests with coverage reporting
./gradlew coverage
```

The fat JAR is output to `excalibur-api/build/libs/excalibur.jar`.

## Running

```bash
# Run with default configuration
java --enable-preview --enable-native-access=ALL-UNNAMED -jar excalibur.jar

# Run with custom configuration file
java --enable-preview --enable-native-access=ALL-UNNAMED -jar excalibur.jar --config /path/to/excalibur.properties

# Set port via environment variable
EXCALIBUR_PORT=9090 java --enable-preview --enable-native-access=ALL-UNNAMED -jar excalibur.jar
```

## Configuration

Configuration is loaded from `excalibur.properties` in the working directory by default. Environment variables can override properties using uppercase with underscores:

```bash
export EXCALIBUR_LLM_API_KEY=sk-your-api-key-here
export EXCALIBUR_LLM_BASE_URL=https://api.openai.com/v1
export EXCALIBUR_PORT=9090
```

### Configuration Options

| Property | Default | Description |
|----------|---------|-------------|
| `excalibur.visibility.epoch-tick-ms` | 100 | Visibility epoch tick interval (ms) |
| `excalibur.visibility.query-timeout-ms` | 30000 | Query timeout (ms) |
| `excalibur.query.top-k-default` | 100 | Default number of results |
| `excalibur.query.recall-target` | 0.95 | Default for the `recall_target` request field. Accepted and validated, but not currently consumed by the search path — breadth is set by `excalibur.shard.default-ef-search` |
| `excalibur.query.vector-dimensions` | 1536 | Vector dimensionality |
| `excalibur.query.include-partial-segments` | true | Include indexing segments in queries |
| `excalibur.query.llm-timeout-ms` | 60000 | LLM scoring timeout (ms) |
| `excalibur.shard.segment-load-factor` | 0.7 | Fraction of slots a segment may fill before it splits |
| `excalibur.shard.default-ef-construction` | 128 | HNSW construction candidate list size |
| `excalibur.shard.default-ef-search` | 64 | HNSW search candidate list size |
| `excalibur.shard.hnsw-integration-queue-depth-limit` | 100000 | Max vectors in HNSW integration queue |
| `excalibur.shard.compaction-delay-ms` | 10000 | Delay before compaction starts (ms) |
| `excalibur.ingest.document-atomicity-timeout-ms` | 2000 | Document atomicity commit timeout (ms) |
| `excalibur.llm.base-url` | https://api.openai.com/v1 | LLM API endpoint |
| `excalibur.llm.chat-model` | gpt-4o-mini | Chat model for relevancy scoring |
| `excalibur.llm.embedding-model` | text-embedding-3-small | Embedding model name |
| `excalibur.llm.embeddings-dimensions` | 1536 | Embedding vector dimensions |
| `excalibur.llm.relevancy-threshold` | 0.3 | Minimum relevancy score (0.0-1.0) |
| `excalibur.llm.timeout-ms` | 60000 | LLM request timeout (ms) |
| `excalibur.llm.max-retries` | 3 | Maximum retry attempts |
| `excalibur.llm.retry-base-delay-ms` | 500 | Base delay for exponential backoff (ms) |
| `excalibur.llm.failure-threshold` | 5 | Failures before circuit opens |
| `excalibur.llm.cooldown-period-ms` | 60000 | Circuit breaker cooldown (ms) |
| `excalibur.llm.max-prompts-per-query` | 10 | Max LLM prompts per query |
| `excalibur.llm.max-batch-tokens` | 4000 | Max tokens per LLM batch |
| `excalibur.health.readiness-threshold` | 0.5 | Readiness coverage threshold |
| `excalibur.health.healthy-threshold` | 0.9 | Healthy coverage threshold |
| `excalibur.health.max-latency-ms` | 5000 | Maximum acceptable latency (ms) |
| `excalibur.api.auth-token` | _(none)_ | Bearer token. Supply as `EXCALIBUR_API_AUTH_TOKEN`, not in this file |
| `excalibur.api.bind-address` | `127.0.0.1` | Interface to bind. Anything else also requires an auth token and TLS |
| `excalibur.api.allow-plaintext` | `false` | Permit a non-loopback bind without TLS, for a proxy that terminates it |
| `excalibur.api.tls-keystore` | _(none)_ | Keystore with the server certificate and key. Setting it enables TLS |
| `excalibur.api.tls-keystore-password` | _(none)_ | Supply as `EXCALIBUR_API_TLS_KEYSTORE_PASSWORD`, not in this file |
| `excalibur.api.tls-keystore-type` | `PKCS12` | Keystore format |
| `excalibur.api.tls-key-alias` | _(none)_ | Which key entry to serve; required when the keystore holds several |
| `excalibur.api.tls-key-password` | _(store password)_ | Key entry password when it differs from the store's |
| `excalibur.api.tls-protocols` | `TLSv1.3,TLSv1.2` | Enabled protocols, in preference order |
| `excalibur.api.tls-cipher-suites` | _(forward-secret AEAD)_ | Enabled suites, in server preference order |
| `excalibur.api.tls-client-auth` | `none` | `none`, `want` or `need` for client certificates |
| `excalibur.api.tls-truststore` | _(JVM default)_ | Truststore of client CAs, for mutual TLS |
| `excalibur.api.tls-truststore-password` | _(none)_ | Supply as `EXCALIBUR_API_TLS_TRUSTSTORE_PASSWORD` |
| `excalibur.api.tls-truststore-type` | _(keystore format)_ | Truststore format |
| `excalibur.api.read-rate-per-second` | 50 | Sustained read requests per client per second |
| `excalibur.api.read-burst` | 200 | Read burst allowance |
| `excalibur.api.mutate-rate-per-second` | 5 | Sustained mutating requests per client per second |
| `excalibur.api.mutate-burst` | 20 | Mutation burst allowance |
| `excalibur.api.rate-limit-max-clients` | 10000 | Clients tracked before eviction |
| `excalibur.api.max-top-k` | 1000 | Largest `top_k` a query may request |
| `excalibur.api.max-query-timeout-ms` | 60000 | Largest query timeout a request may ask for |
| `excalibur.ingest.allowed-roots` | _(empty)_ | Filesystem roots ingest may read. Empty means filesystem ingest is **disabled** |
| `excalibur.ingest.allowed-hosts` | _(empty)_ | Hosts ingest may crawl. Empty means URL ingest is **disabled** |
| `excalibur.ingest.max-sources` | 256 | Registered ingestion sources before new ones are refused |
| `excalibur.graph.path` | `<data dir>/graph` | Graph store directory; facts stage in `<that>-staging` |
| `excalibur.graph.extraction-enabled` | false | Extract facts from ingested documents with the chat model |
| `excalibur.graph.extractor-version` | 1 | Recorded on every extracted fact, so one extractor's output can be erased by version |
| `excalibur.graph.entity-vectors-enabled` | true | Embed each entity's text at build time, so retrieval can rerank against real vectors |
| `excalibur.graph.single-valued-relations` | *(empty)* | Comma-separated relations that hold one value at a time, whose new values close the previous interval |
| `excalibur.graph.max-open-namespaces` | 16 | Graphs held open at once before the least recently used is evicted |
| `excalibur.graph.erasure-tombstone-ratio` | 0.2 | Tombstoned fraction of the store at which a compaction becomes worthwhile |
| `excalibur.graph.erasure-max-latency-seconds` | 3600 | Longest an erasure obligation waits before a compaction runs regardless |
| `excalibur.graph.top-k` | 10 | Documents each retrieval signal contributes before fusion |
| `excalibur.graph.context-token-budget` | 2000 | Tokens of serialised subgraph an answer may carry |
| `excalibur.graph.min-score` | 0.005 | Smallest fused score a candidate must reach to be answered |
| `excalibur.graph.min-separation` | 0.001 | Smallest gap between the top two candidates before the result is ambiguous |
| `excalibur.graph.min-mean-confidence` | 0.3 | Smallest mean provenance confidence across the surviving evidence |
| `excalibur.graph.max-ambiguous-candidates` | 5 | Candidates returned when the answer is ambiguous |
| `excalibur.graph.ram-buffer-mb` | 16 | Lucene RAM buffer for the graph's lexical indexes |

## HTTP API

ExcaliburDB exposes an HTTP API on port 8080, bound to `127.0.0.1` by default.

### Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/health` | none | Health check for load balancers |
| `GET` | `/v1/stats` | bearer | System statistics and metrics |
| `POST` | `/v1/query` | bearer | Execute vector similarity query |
| `DELETE` | `/v1/query/{query_id}` | bearer | Cancel an in-progress query |
| `POST` | `/v1/graph/query` | bearer | Answer a question from the graph |
| `POST` | `/v1/ingest` | bearer | Register ingestion source |
| `POST` | `/v1/reindex` | bearer | Force reindex of a source |
| `GET` | `/v1/ingest/status` | bearer | Get ingestion status by source |
| `POST` | `/v1/graph/query` | bearer | Answer a question from the graph |
| `POST` | `/v1/graph/build` | bearer | Build the graph from the facts ingestion has staged |
| `POST` | `/v1/graph/erasure` | bearer | Record an obligation to erase, and compact when it is due |

### The graph store

The node builds its own graph from what it ingests. Ingestion extracts facts from each published
document and stages them; `POST /v1/graph/build` turns everything staged into the store and starts
serving it, without a restart. A node that has ingested but never built answers `/v1/graph/query`
with 503 and says so, rather than 404 — the capability is there, the data is not yet.

The store is a directory — `<data dir>/graph` by default, or wherever `excalibur.graph.path`
points — holding:

| File | What it is |
|------|------------|
| the edge columns | CSR adjacency, its transpose, validity and provenance |
| `documents/` | Lucene index over each entity's text, for the lexical retrieval signal |
| `labels/` | Lucene index over entity names, for entity linking |
| `entity_vectors.vec` | one full-precision vector per entity, for the exact rerank |
| `entities.idx` / `entities.dat` | entity id → name, so answers name people rather than integers |
| `relations.idx` / `relations.dat` | relation id → name |

All of it or none of it: a directory holding some of these files fails the start rather than
serving a pipeline that silently resolves nothing. The whole directory is assembled beside the live
one and renamed into place, so a reader sees the previous graph or the new one and never a mixture.

Facts accumulate in `<graph dir>-staging`, beside the store rather than inside it — every build
replaces the store wholesale, and the record the next build reads has to survive that. Entity ids
are assigned once and never renumbered, so the Lucene indexes that store them stay correct across a
rebuild they did not take part in.

Extraction is off by default. It is one chat-model call per document, which is the most expensive
thing ingestion can do, and a deployment doing vector search should not start paying for a graph it
never asked for. Turn it on with `excalibur.graph.extraction-enabled=true`.

### How a graph question is ranked

Three stages, cheapest first, each narrowing what the next has to look at:

1. **BM25** over each entity's text decides which few dozen entities are worth considering. Term
   overlap is what makes this a good first pass and a poor last one — it cannot tell an entity whose
   document repeats the query's words from one that is about them.
2. **The exact rerank** re-scores those candidates against the vectors stored for them, which is the
   correction BM25 cannot make. It over-fetches four times what is wanted, because reranking only
   the top-k cannot recover an entity that term overlap put at k+1.
3. **The chat model** reranks what survives, in one batched call.

Each stage needs something the deployment may not have, and each degrades to the stage beneath it
rather than failing the question: no stored vectors means no exact rerank, and an embedding endpoint
that is briefly unavailable means a query with no embedding, which skips the exact rerank for that
request. No API key means neither rerank, and BM25 answers alone.

### Namespaces

Every graph request carries an optional `X-Excalibur-Namespace` header. A node that never sees it
uses one namespace called `shared`, which is the single-tenant arrangement — multi-tenancy is not
something a node should acquire by accident. Each namespace gets its own directory under the graph
root: its own store, its own staged facts, its own erasure obligations.

A served store is thirteen memory mappings, and a process has a finite number of them, so only
`excalibur.graph.max-open-namespaces` graphs stay open at once and the least recently used is
evicted to make room. Every request holds a lease for its duration: a namespace being read is passed
over by eviction, and an eviction that lands on one is deferred until the last reader is done.

### Securing the API

Every endpoint except `/health` requires a bearer token. `/health` is exempt because a liveness
probe cannot carry one, and it is still rate limited.

Supply the token through the environment, never the properties file:

```bash
export EXCALIBUR_API_AUTH_TOKEN='...'
```

```
Authorization: Bearer <token>
```

There is no default token and none is compiled in. The server refuses to start when it is asked to
bind a non-loopback address without one, rather than silently serving the world — so binding
`0.0.0.0` requires setting both `excalibur.api.bind-address` and the token.

### TLS

Point `excalibur.api.tls-keystore` at a keystore holding the server certificate and its private key,
and TLS is on:

```bash
keytool -genkeypair -alias excalibur -keyalg RSA -keysize 2048 \
  -dname "CN=db.example.com" -ext "SAN=dns:db.example.com" \
  -keystore /etc/excalibur/server.p12 -storetype PKCS12

export EXCALIBUR_API_TLS_KEYSTORE_PASSWORD='...'
```

```properties
excalibur.api.tls-keystore=/etc/excalibur/server.p12
```

The default handshake policy is TLS 1.3 and 1.2 with forward-secret AEAD suites only, in server
preference order. `excalibur.api.tls-protocols` and `excalibur.api.tls-cipher-suites` override it;
a name this JVM does not support is dropped with a warning, and a list with nothing left fails at
startup rather than binding a socket that cannot complete a handshake.

Everything that can fail — an unreadable keystore, a wrong password, a missing or ambiguous key
entry, an empty protocol list — fails **before** the port is bound, so a TLS misconfiguration is a
startup error with the key to fix in the message, not a client-side handshake failure.

A non-loopback bind requires TLS as well as a token, because a bearer token sent in cleartext is a
credential on the wire in every request. Where TLS is genuinely terminated in front of this process
— a sidecar, or an ingress forwarding over loopback — set `excalibur.api.allow-plaintext=true` to
say so explicitly.

For mutual TLS, set `excalibur.api.tls-client-auth` to `want` or `need` and name the client CAs in
`excalibur.api.tls-truststore`. Without a truststore, client certificates are checked against the
JVM default store, which trusts the public web CAs — so almost any certificate would pass.

Requests are rate limited with separate budgets for reads and mutations; exceeding either returns
429 with `Retry-After`.

**Ingestion is deny-by-default.** `POST /v1/ingest` accepts a filesystem path or a URL, and both are
checked against allow-lists that are empty until configured:

| Key | Effect when empty |
|-----|-------------------|
| `excalibur.ingest.allowed-roots` | filesystem ingest disabled |
| `excalibur.ingest.allowed-hosts` | URL ingest disabled |

An empty list means **disabled**, not unrestricted. A filesystem path is canonicalised before it is
compared against the roots, so symlinks and `..` cannot escape them. A URL's host must be
allow-listed and every address it resolves to is rejected if it is loopback, link-local, site-local,
any-local or multicast, which is what keeps ingestion away from cloud metadata endpoints and
internal services.

### Query Example

Send raw query text. The server embeds it using the same model that ingestion uses, guaranteeing the query lives in the same embedding space as the indexed vectors:

```bash
curl -X POST http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "how does the system handle concurrent writes?",
    "top_k": 10,
    "threshold": 0.5,
    "timeout_ms": 5000,
    "include_partial_segments": true,
    "enable_llm_scoring": true
  }'
```

The HTTP API does not accept pre-computed vectors. Embedding consistency between ingest and query is a server invariant — allowing clients to submit a vector would let a model mismatch silently produce meaningless results. Configure `excalibur.llm.embedding-model` (and the corresponding API key) before starting the server.

**Response:**
```json
{
  "query_id": "b3d9f4a0-1e2c-4a7b-9c3d-5e6f7a8b9c0d",
  "results": [
    {"document_id": 123, "score": 0.95}
  ],
  "coverage": {
    "total_segments": 1024,
    "active_segments": 980,
    "indexing_segments": 40,
    "warming_segments": 2,
    "estimated_corpus_coverage": 0.96,
    "tail_scan_entries": 12500,
    "degraded": false
  },
  "timed_out": false,
  "query_time_ms": 42
}
```

The server-generated `query_id` can be used to cancel the query mid-flight via `DELETE /v1/query/{query_id}`.

### Ingest Example

```bash
curl -X POST http://localhost:8080/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "source_type": "filesystem",
    "config": {
      "source_id": "my-docs",
      "root_path": "/path/to/documents",
      "included_extensions": [".txt", ".md"],
      "excluded_directories": [".git", "node_modules"]
    }
  }'
```

### Stats Example

```bash
curl http://localhost:8080/v1/stats
```

**Response:**
```json
{
  "segments": {
    "total": 1024,
    "active": 980,
    "indexing": 40,
    "warming": 2,
    "compacting": 0,
    "tombstoned": 0,
    "purged": 2
  },
  "entries": {"total": 15000000},
  "volumes": {"count": 3, "totalBytes": 5368709120, "usedBytes": 3221225472},
  "hnsw": {"queueDepth": 50000, "queueDepthLimit": 100000, "vectorsIntegrated": 14000000},
  "cuckoo": {"pendingDeletions": 100, "totalDeletions": 5000},
  "visibility": {"epoch": 42, "registeredSegments": 1024, "visibleSegments": 1024},
  "coverage": 0.96
}
```

## Architecture

### Module Structure

| Module | Description |
|--------|-------------|
| `excalibur-core` | Memory-mapped I/O, hash functions, codecs, atomic operations |
| `excalibur-store` | Storage engine, segment management, write-ahead log |
| `excalibur-vector` | HNSW graphs, distance functions, product quantization |
| `excalibur-graph` | Memory-mapped CSR adjacency, bitemporal and provenance columns, erasure |
| `excalibur-retrieval` | Ranking signals, reciprocal rank fusion, rerank, serialisation budget, abstention |
| `excalibur-planner` | Deterministic query classification and lexical coreference |
| `excalibur-tenancy` | Per-namespace index isolation, mapping budget, eviction |
| `excalibur-filter` | Cuckoo filters, LSH indexes, Count-Min Sketch |
| `excalibur-query` | Query pipeline with multi-stage filtering |
| `excalibur-ingest` | Document ingestion, chunking, source adapters |
| `excalibur-shard` | Index orchestration, volume management, visibility tracking |
| `excalibur-llm` | LLM client with circuit breaker and retry logic |
| `excalibur-api` | HTTP server and API endpoints |
| `excalibur-metrics` | JMX metrics and observability |
| `excalibur-config` | Configuration loading and validation |
| `excalibur-test` | Test utilities and fixtures |
| `excalibur-integration` | Integration tests |
| `excalibur-bench` | JMH benchmarks |

### Query Pipeline

Queries execute through a 6-stage pipeline:

1. **Epoch Snapshot** - Capture visibility epoch, resolve queryable segments
2. **Cuckoo Filter Cascade** - Eliminate segments that cannot contain relevant terms
3. **LSH Pre-filter** - Coarse vector similarity filtering
4. **HNSW Vector Search** - Precise ANN search with tail scan for unindexed entries
5. **Full Vector Rerank** - Exact distance computation on candidates
6. **LLM Relevancy Scoring** - Optional semantic relevancy scoring

Each stage checks for cancellation and timeout, allowing queries to return partial results if time-constrained.

### Storage Model

Data is organized into segments, each backed by memory-mapped files:

- Hash-based routing provides O(1) segment lookup
- Append-only write log with CRC32 checksums
- Background compaction removes tombstones without blocking reads
- Elastic segment splitting based on load factor thresholds (default 0.7)

### Visibility and Consistency

The system uses epoch-based visibility tracking to ensure:

- Queries see a consistent snapshot of data
- No torn reads during concurrent ingestion
- Document atomicity - all chunks of a document become visible together
- Monotonic epoch advancement

## Development

### Project Structure

```
excaliburdb/
├── excalibur-api/          # HTTP server and endpoints
├── excalibur-core/         # Primitives: memory, hash, codecs
├── excalibur-config/       # Configuration loading
├── excalibur-filter/       # Cuckoo filters, LSH, sketches
├── excalibur-graph/        # CSR adjacency, bitemporal, provenance, erasure
├── excalibur-ingest/       # Document ingestion pipeline
├── excalibur-integration/  # Integration tests
├── excalibur-llm/          # LLM client
├── excalibur-metrics/      # JMX metrics
├── excalibur-planner/      # Query classification and coreference
├── excalibur-query/        # Query execution
├── excalibur-retrieval/    # Ranking, fusion, rerank, serialisation, abstention
├── excalibur-shard/        # Index orchestration
├── excalibur-store/        # Storage engine
├── excalibur-tenancy/      # Namespace isolation and eviction
├── excalibur-test/         # Test utilities
├── excalibur-vector/       # HNSW, distance, quantization
└── excalibur-bench/        # JMH benchmarks
```

### Running Benchmarks

```bash
# Run all benchmarks
./gradlew :excalibur-bench:jmh

# Run specific benchmark
./gradlew :excalibur-bench:jmh --tests "MappedFileBenchmark"
```

Available benchmarks:
- `CuckooFilterBenchmark` - Filter lookup/insert operations
- `CuckooFilterInsertBenchmark` - Insert-heavy workload
- `CuckooFilterDeleteBenchmark` - Deletion operations
- `CuckooFilterFPRateBenchmark` - False positive rate measurement
- `CuckooFilterMemoryMappedBenchmark` - Memory-mapped Cuckoo filter I/O
- `DistanceBenchmark` - Distance function performance
- `DistanceVectorApiBenchmark` - SIMD-accelerated distance
- `EndToEndQueryBenchmark` - Full query pipeline latency
- `HashBenchmark` - Hash function throughput
- `HnswRecallBenchmark` - HNSW recall@10 measurement
- `MappedFileBenchmark` - Memory-mapped I/O throughput
- `PointLookupBenchmark` - Storage point lookup
- `SegmentBenchmark` - Segment-level operations
- `TailScanBenchmark` - Brute-force scan throughput
- `VectorLayoutBenchmark` - Vector memory layout

### Test Coverage

The project maintains 80% minimum test coverage. Run coverage reports:

```bash
./gradlew coverage
```

Reports are generated at:
- `build/reports/jacoco/COVERAGE_SUMMARY.html` - Summary report
- `build/reports/jacoco/aggregate/html/index.html` - Detailed aggregate report

To analyze coverage gaps:
```bash
./gradlew coverageGaps
```

### Integration Tests

Integration tests verify the core system requirements:

- `ColdStartIntegrationTest` - Empty index queries, progressive results
- `ConcurrentQueryDuringIngestionIntegrationTest` - Epoch isolation, document atomicity
- `ReindexWhileQueryingIntegrationTest` - Zero-downtime reindexing
- `RollingReindexIntegrationTest` - Rolling reindex across segments
- `LLMUnavailabilityIntegrationTest` - Circuit breaker and fallback behavior
- `DeletionCorrectnessIntegrationTest` - Cuckoo filter deletion correctness
- `FullPipelineIntegrationTest` - End-to-end ingestion and query
- `LiveIndexingIntegrationTest` - Query correctness during active indexing
- `EpochIsolationIntegrationTest` - Epoch-based snapshot isolation
- `DocumentAtomicVisibilityIntegrationTest` - Multi-chunk document atomicity
- `EpochTickSchedulerIntegrationTest` - Epoch tick advancement
- `HealthConfigurationIntegrationTest` - Health endpoint configuration
- `TailScanIntegrationTest` - Tail scan for unindexed entries

## License

MIT License
