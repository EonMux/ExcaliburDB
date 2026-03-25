# ExcaliburDB

A disk-backed data relevancy retrieval engine for LLMs. ExcaliburDB provides efficient vector similarity search with intelligent filtering and LLM-based relevancy scoring.

## Overview

ExcaliburDB is designed for large-scale semantic search over document corpora. It combines:

- **HNSW (Hierarchical Navigable Small World) graphs** for approximate nearest neighbor search
- **Cuckoo filters** for efficient term existence testing with deletion support
- **LSH (Locality-Sensitive Hashing)** for coarse vector similarity pre-filtering
- **Product Quantization** for compressed vector storage and fast approximate distances
- **Epoch-based visibility tracking** for consistent query snapshots

The system is built on Java 21's Panama Foreign Function & Memory API for zero-copy, memory-mapped file I/O, enabling it to handle indexes larger than available RAM.

## Requirements

- Java 21 or later (required for Panama FFM and virtual threads)
- Gradle 8.11+ (included via wrapper)

## Building

```bash
# Build all modules
./gradlew build

# Build fat JAR
./gradlew :excalibur-api:shadowJar

# Run tests
./gradlew test

# Run tests with coverage
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

Configuration is loaded from `excalibur.properties` in the working directory by default. Environment variables can override properties.

### Key Configuration Options

| Property | Default | Description |
|----------|---------|-------------|
| `excalibur.visibility.epoch-tick-ms` | 100 | Visibility epoch tick interval (ms) |
| `excalibur.visibility.query-timeout-ms` | 30000 | Query timeout (ms) |
| `excalibur.query.top-k-default` | 100 | Default number of results |
| `excalibur.query.recall-target` | 0.95 | Target recall for ANN search |
| `excalibur.query.vector-dimensions` | 1536 | Vector dimensionality |
| `excalibur.query.include-partial-segments` | true | Include indexing segments in queries |
| `excalibur.shard.segment-load-factor` | 0.7 | Segment load factor for splitting |
| `excalibur.shard.default-ef-construction` | 128 | HNSW construction candidate list size |
| `excalibur.shard.default-ef-search` | 64 | HNSW search candidate list size |
| `excalibur.shard.hnsw-integration-queue-depth-limit` | 100000 | Max vectors in HNSW integration queue |
| `excalibur.llm.base-url` | https://api.openai.com/v1 | LLM API endpoint |
| `excalibur.llm.chat-model` | gpt-4o-mini | Chat model for relevancy scoring |
| `excalibur.llm.embedding-model` | text-embedding-3-small | Embedding model name |
| `excalibur.llm.relevancy-threshold` | 0.3 | Minimum relevancy score (0.0-1.0) |
| `excalibur.health.readiness-threshold` | 0.5 | Readiness coverage threshold |
| `excalibur.health.healthy-threshold` | 0.9 | Healthy coverage threshold |

### Environment Variable Overrides

Properties can be overridden via environment variables using uppercase with underscores. For example, to override the LLM API key:

```bash
export EXCALIBUR_LLM_API_KEY=sk-your-api-key-here
export EXCALIBUR_LLM_BASE_URL=https://api.openai.com/v1
export EXCALIBUR_PORT=9090
```

## HTTP API

ExcaliburDB exposes an HTTP API on port 8080 by default.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Health check for load balancers |
| `GET` | `/v1/stats` | System statistics and metrics |
| `POST` | `/v1/query` | Execute vector similarity query |
| `POST` | `/v1/ingest` | Register ingestion source |
| `POST` | `/v1/reindex` | Force reindex of a source |
| `GET` | `/v1/ingest/status` | Get ingestion status by source |

### Query Endpoint

```bash
curl -X POST http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "vector": [0.1, 0.2, 0.3, ...],
    "top_k": 10,
    "threshold": 0.5,
    "timeout_ms": 5000,
    "include_partial_segments": true,
    "enable_llm_scoring": false
  }'
```

**Response:**
```json
{
  "results": [
    {"document_id": 123, "score": 0.95}
  ],
  "coverage": {
    "total_segments": 1024,
    "active_segments": 980,
    "estimated_corpus_coverage": 0.96,
    "degraded": false
  },
  "timed_out": false,
  "query_time_ms": 42
}
```

### Ingest Endpoint

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

### Stats Endpoint

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
  "coverage": 0.9600
}
```

## Architecture

ExcaliburDB is organized into modules:

| Module | Description |
|--------|-------------|
| `excalibur-core` | Memory-mapped I/O, hash functions, codecs, atomic operations |
| `excalibur-store` | Storage engine, segment management, write-ahead log |
| `excalibur-vector` | HNSW graphs, distance functions, product quantization |
| `excalibur-filter` | Cuckoo filters, LSH indexes, Count-Min Sketch |
| `excalibur-query` | Query pipeline with multi-stage filtering |
| `excalibur-ingest` | Document ingestion, chunking, source adapters |
| `excalibur-shard` | Index orchestration, volume management, visibility tracking |
| `excalibur-llm` | LLM client with circuit breaker and retry logic |
| `excalibur-api` | HTTP server and API endpoints |
| `excalibur-metrics` | JMX metrics and observability |
| `excalibur-config` | Configuration loading and validation |

### Query Pipeline

Queries execute through a 6-stage pipeline:

1. **Epoch Snapshot** - Capture visibility epoch, resolve queryable segments
2. **Cuckoo Filter Cascade** - Eliminate segments that cannot contain relevant terms
3. **LSH Pre-filter** - Coarse vector similarity filtering
4. **HNSW Vector Search** - Precise ANN search with tail scan
5. **Full Vector Rerank** - Exact distance computation on candidates
6. **LLM Relevancy Scoring** - Optional semantic relevancy scoring

### Storage Model

Data is organized into segments, each backed by memory-mapped files:

- **Hash-based routing** provides O(1) segment lookup
- **Append-only write log** with CRC32 checksums
- **Background compaction** removes tombstones without blocking reads
- **Elastic segment splitting** based on load factor thresholds

## Development

### Project Structure

```
excaliburdb/
├── excalibur-api/          # HTTP server and endpoints
├── excalibur-core/         # Primitives: memory, hash, codecs
├── excalibur-config/       # Configuration loading
├── excalibur-filter/       # Cuckoo filters, LSH, sketches
├── excalibur-ingest/       # Document ingestion pipeline
├── excalibur-integration/  # Integration tests
├── excalibur-llm/          # LLM client
├── excalibur-metrics/      # JMX metrics
├── excalibur-query/        # Query execution
├── excalibur-shard/        # Index orchestration
├── excalibur-store/        # Storage engine
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

### Test Coverage

The project maintains 80% minimum test coverage. Run coverage reports:

```bash
./gradlew coverage
```

Reports are generated at:
- `build/reports/jacoco/COVERAGE_SUMMARY.html`
- `build/reports/jacoco/aggregate/html/index.html`

## License

MIT License
