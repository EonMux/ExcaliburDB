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
# Fuse BM25 with vector search on a node that holds both. false gives vector-only ranking,
# and with it a `score` that is a similarity rather than a fused rank sum.
excalibur.query.hybrid-retrieval=true

# HNSW tuning (accuracy vs. speed)
excalibur.shard.default-ef-construction=128
excalibur.shard.default-ef-search=64

# Circuit breakers (fallback to vector-only when LLM is unavailable). One each for the chat and
# embedding paths, so an outage of one does not stop the other.
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
| `GET` | `/v1/documents` | bearer | Read one chunk's text, by `document_id` |
| `POST` | `/v1/documents/fetch` | bearer | Read the text of a page of chunks |
| `POST` | `/v1/documents` | bearer | Index documents supplied in the request. `503` on a node with no ingestion pipeline |
| `DELETE` | `/v1/query/{query_id}` | bearer | Cancel an in-progress query |
| `POST` | `/v1/ingest` | bearer | Register an ingestion source |
| `DELETE` | `/v1/ingest` | bearer | Stop crawling a source, keeping its documents |
| `GET` | `/v1/ingest/status` | bearer | Per-source ingestion status |
| `POST` | `/v1/reindex` | bearer | Force re-crawl of a registered source. `503` on a node that does not embed its corpus |
| `DELETE` | `/v1/reindex` | bearer | Ask a running re-index to stop |
| `GET` | `/v1/reindex/status` | bearer | Per-source re-index status |
| `POST` | `/v1/erasure` | bearer | Remove named documents from the corpus |
| `POST` | `/v1/compact` | bearer | Reclaim the space tombstoned entries still hold, now rather than on the next sweep |
| `GET` | `/v1/compact/status` | bearer | What the last compaction examined, reclaimed and failed on |
| `POST` | `/v1/graph/query` | bearer | Answer a question from the graph |
| `POST` | `/v1/graph/build` | bearer | Publish the facts ingestion has staged as the served graph |
| `POST` | `/v1/graph/erasure` | bearer | Record an obligation to erase, and compact when it is due |

All request/response bodies are JSON. Request size limit: **1 MB**, and a body over it is refused with `413` by every endpoint that reads one - split the payload rather than retrying it. The `GET` routes and `POST /v1/graph/build` read no body, so they never produce a `413`.

The three `/v1/graph/*` routes are documented in the README, under
[Graph Examples](README.md#graph-examples) for the request and response shapes. They are named
here so the surface is complete in one table; the shapes are kept in one place rather than two so
they cannot disagree.

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

**Optional**, all of them filesystem-only:

| Field | Type | Default | Meaning |
|-------|------|---------|---------|
| `included_extensions` | array of string | _(all)_ | Only files with one of these extensions are indexed |
| `excluded_directories` | array of string | `.git`, `.svn`, `.hg`, `node_modules`, `target`, `build`, `.idea`, `.vscode` | Directory names skipped anywhere under the root. Supplying this **replaces** the default list |
| `max_depth` | integer | 100 | Directory levels below the root to descend, 1 to 1000 |
| `follow_symlinks` | bool | `false` | Whether the walk descends through symbolic links. A link whose real target lies outside the root is skipped whatever this is set to, so a link cannot be used to read a directory the allow-list does not permit |
| `include_hidden` | bool | `false` | Whether dot-files are indexed |
| `max_file_bytes` | integer | 2147483648 | Largest single file read, 1 byte to 2 GiB. A larger file is skipped, not truncated |

A value outside its range is answered `400` rather than clamped: clamping would register the
source and then crawl to a depth or a size the caller did not ask for, which reads back as a
crawl that stopped early for no stated reason.

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

**Optional**, all of them HTTP-only:

| Field | Type | Default | Meaning |
|-------|------|---------|---------|
| `max_depth` | integer | 3 | Link hops followed from the base URL, 0 to 1000 |
| `max_documents` | integer | 10000 | Pages fetched before the crawl stops, 1 to 10000000 |
| `max_response_bytes` | integer | 10485760 | Largest response body held, 1 byte to 256 MiB |
| `timeout_seconds` | integer | 30 | Budget for one request, 1 to 600 |
| `included_path_patterns` | array of string | _(all)_ | A URL path must contain one of these substrings to be crawled. Plain substrings, not globs or regexes |
| `excluded_path_patterns` | array of string | `/api`, `/admin`, `/login`, `/logout` | A URL path containing one of these is skipped. Supplying this **replaces** the default list, and `[]` clears it |
| `user_agent` | string | `ExcaliburDB/1.0` | How the crawl identifies itself. At most 200 characters and no control characters: a carriage return or newline would end the header and turn the rest of the value into headers this node never agreed to send |

The crawl stays on the base URL's host regardless of any of these — redirects are followed by the
crawler itself, with the host policy re-applied at every hop — and hosts resolving to loopback,
private, link-local or multicast addresses are refused whatever the allow-list says.

Settings that belong to the other source type are refused here with a `400` rather than accepted
and ignored, in both directions: `included_extensions`, `excluded_directories`,
`follow_symlinks`, `include_hidden` and `max_file_bytes` on a crawl, and `max_documents`,
`max_response_bytes`, `timeout_seconds`, `included_path_patterns`, `excluded_path_patterns` and
`user_agent` on a filesystem walk. See [error responses](#error-responses). `max_depth` is the one
setting both understand.

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

**`409 Conflict` — documents were supplied under that id**

```json
{"error": "Source 'notes' holds documents supplied directly; a crawl registered under its id would remove them on its first pass. Use a different source_id."}
```

A crawl decides for itself what its source holds, and its first pass removes every document under
that id it did not find — which is every document [supplied by hand](#supply-documents-directly).
A node only remembers the ids supplied during its current run, so after a restart it will accept a
crawl registered over documents it took earlier; keep the two kinds of source on separate ids
rather than relying on the refusal.

**`400 Bad Request` — missing or invalid field**

```json
{"error": "Bad Request: Field 'config.source_id' is required"}
```

**`400 Bad Request` — a filesystem filter sent to an HTTP crawl**

```json
{"error": "Bad Request: Field 'config.included_extensions' applies to a filesystem walk only: an HTTP crawl matches URLs, which have neither a file extension nor a directory, no symbolic links, and no local file to size. Remove it, or register this source with the source_type it belongs to."}
```

Refused rather than dropped. The crawler has a URL and a host policy, not a directory tree, so
there is nothing for either filter to match. Accepting them would answer `200 registered` and then
crawl the whole site, with nothing in the response or in `/v1/ingest/status` to say the filter had
been discarded.

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

### Supply documents directly

`POST /v1/ingest` registers a target for the node to crawl, and a crawl target has to be
allow-listed — a filesystem root or a host the operator named. That is the right policy for a node
that goes and reads things by itself, and it leaves no way in for a client that already holds the
documents and has nowhere the node is permitted to read them from. `POST /v1/documents` is the
other direction: the caller brings the content.

```bash
curl -X POST http://localhost:8080/v1/documents \
  -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "source_id": "notes",
    "documents": [
      {"document_id": "meeting-2024-03.md", "text": "Notes from the March review ..."},
      {"document_id": "handbook.md", "text": "Onboarding handbook ...", "content_type": "text/plain"}
    ]
  }'
```

```json
{"source_id": "notes", "documents_ingested": 2, "chunks_ingested": 9,
 "documents_failed": 0, "stopped": false}
```

The documents are chunked, embedded and indexed inside this request, so a `200` means they are
searchable — not that indexing was scheduled. That is the difference from `POST /v1/ingest`, which
answers `202` because a crawl is of unknown size and duration.

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `source_id` | string | yes | What the documents are recorded as belonging to, so `/v1/erasure` and `/v1/reindex` can name them later. At most 256 characters |
| `documents` | array | yes | 1 to 256 documents |
| `documents[].document_id` | string | yes | Names the document within its source. Posting the same id again replaces what the node holds under it |
| `documents[].text` | string | yes | The document's text. Empty is refused rather than indexed, because it would produce no chunks and be reported as ingested while being unfindable |
| `documents[].content_type` | string | no | Defaults to `text/plain` |
| `documents[].last_modified_millis` | int | no | Epoch millis; `0` when unknown |

**Error — `400 Bad Request`** — no `source_id`, no `documents`, more than 256 of them, a document
with no `document_id` or no `text`, or the same `document_id` twice in one request.

**Error — `409 Conflict`** — either the `source_id` is a registered crawl source, or a run over
that source is already in flight. The first is refused because documents recorded under a crawl's
id are removed the next time that crawl is reindexed — the crawl does not list them, and
reindexing removes what a source has stopped listing. Use a `source_id` of your own.

**Error — `413 Payload Too Large`** — the body is over 1 MB. Split the batch.

**Error — `405 Method Not Allowed`** — a method other than `GET` or `POST` on `/v1/documents`.
Unauthenticated and charged against the read budget, for the same reason as the other two-method
routes above. The `503` for a node with no ingestion pipeline is *not* unauthenticated: it
describes the deployment, so it is answered only to a caller that has already authenticated.

**Error — `429 Too Many Requests`** — this node already holds supplied documents under as many
`source_id`s as `excalibur.ingest.max-sources` allows (256 by default). Adding to a `source_id` it
already holds is never refused for this reason; only a new one is. The bound is the same one the
crawl registry uses, because an id holding supplied documents is an id no crawl can be registered
under.

**Error — `500 Internal Server Error`** — the run itself failed, for example an embedding endpoint
that refused. Reported rather than answered `200` with zero counts: a run that failed and a run
that ingested nothing are different outcomes.

**Error — `503 Service Unavailable`** — the node was started without an ingestion pipeline.

The `409` for a registered crawl source is checked twice, before and after the id is recorded, so
a crawl registered while the request was being handled is caught rather than raced past. The crawl
wins that race and the supplied batch is refused, because the crawl is what would delete the
documents.

The refusal runs the other way too: `POST /v1/ingest` answers `409` for a `source_id` that
documents have been supplied under, because a crawl decides for itself what its source holds and
its first pass removes every document under that id it did not find. A node only remembers the
supplied ids from its current run, so after a restart it will accept a crawl registered over
documents it took earlier — the loss is the same either way, so keep the two kinds of source on
separate ids.

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

**Error — `503 Service Unavailable` (this node does not embed its corpus)**

```json
{"error": "Reindexing is not configured on this server"}
```

**Error — `405 Method Not Allowed` — a method other than POST or DELETE**

```json
{"error": "Method Not Allowed - Use POST to start a reindex or DELETE to stop one"}
```

Unauthenticated, and charged against the read budget, like the other routes that answer two
methods: which handler serves a request is decided before its token is, so an uncharged 405 would
be free to send. It names only the verbs this route takes, which the route table already gives.

Reindexing re-embeds every chunk it rewrites, so it needs the embedding endpoint ingestion used.
A node running without one answers this to every reindex request, for every source, for as long
as it runs - the startup log says so too, under `This node does not embed its corpus`.

The scheduled re-crawl is inert on the same node for the same reason: it still fires on
`excalibur.ingest.reindex-interval-ms`, finds no handler, logs `ReindexOperationHandler not
configured` and records the run as failed. So a node without an embedding endpoint indexes what
it crawled when the source was registered and does not pick up later edits to those files; it
keeps serving BM25 over the chunks it already has. Configure an embedding endpoint on a node
whose corpus changes.

### Re-index status

`POST /v1/reindex` answers 202 and works in the background, so this is how the run's verdict
gets back to the caller. Same shape as `/v1/ingest/status`, with one source or all of them.

**One source**

```bash
curl "http://localhost:8080/v1/reindex/status?source_id=project-docs"
```

```json
{
  "source_id": "project-docs",
  "state": "indexing",
  "progress": 0.6200,
  "documents_processed": 62,
  "documents_total": 100,
  "started_at_ms": 1730000000000,
  "last_error": null
}
```

**All sources**

```bash
curl http://localhost:8080/v1/reindex/status
```

```json
{"sources": [{"source_id": "project-docs", "state": "idle", "progress": 1.0000, "documents_processed": 100, "documents_total": 100, "started_at_ms": 1730000000000, "last_error": null}]}
```

`state` is one of `crawling`, `embedding`, `indexing` while a run is in flight, `idle` at
`progress: 1.0` once it finished, or `failed` with `last_error` set when it stopped early.
`last_error` names the shape of the failure and how many documents it affected, and points at
the node's log for the reason itself — the reasons come from storage, the lexical index and the
embedding client, and carry filesystem paths and endpoint URLs that a read token should not be
enough to see. A source that has never been reindexed is a `404`, not an invented idle run — "nothing has been
asked of it" and "it ran and did nothing" are different answers, and only the second means the
corpus was rewritten. Deregistering a source with `DELETE /v1/ingest` clears its entry here.

`documents_`, not `entries_`: a reindex counts documents, where the ingest status's `entries_`
counts chunks.

**Error — `404 Not Found`**

```json
{"error": "No reindex has been run for source: project-docs"}
```

### Stop a re-index

A rolling replacement holds its source's claim until it ends, and every other re-index of that
source is answered `409` while it does. One that hangs — a source whose fetch never returns — holds
it for the life of the process, and without this the only way out is restarting the node.

```bash
curl -X DELETE http://localhost:8080/v1/reindex \
  -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"source_id": "project-docs"}'
```

```json
{"source_id": "project-docs", "cancelled": true,
 "message": "Reindex was asked to stop; poll /v1/reindex/status for when it has"}
```

`"cancelled": false` means no re-index of that source was running — an answer rather than an error,
since the state you were asking for is the state you now have.

The run is *asked* to stop; it stops at its next checkpoint and releases its claim then. The claim
is deliberately not dropped here: handing the source to a second rolling replacement while the
first is still winding down is the concurrent-replacement data loss the claim exists to prevent,
because each tombstones the segments the other has just published. `GET /v1/reindex/status` is what
says when it has actually ended.

**Error — `404 Not Found`** — no source is registered under that id.

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
  "documents_incomplete": 0,
  "documents_not_found": 0,
  "documents_remaining": 0,
  "source_deregistered": false,
  "chunks_erased": 7,
  "chunks_unindexed": 7,
  "chunks_not_removed": 0,
  "chunks_still_searchable": 0,
  "vectors_hidden": 7,
  "compacted": false,
  "chunks_reclaimed": 0,
  "outstanding": 7,
  "detail": "not due: 7 of 41230 chunk(s) tombstoned across 3 segment(s)"
}
```

The removals are counted separately because either can fall short:

- `chunks_erased` left storage, which is the only place a chunk's text and vector live.
- `chunks_unindexed` were erased from the lexical index, which stops them matching immediately.
- `vectors_hidden` were retired from the graph, so no entity search ranks the document again. It counts graph nodes, not vectors in a segment's HNSW index — those are reclaimed with the segment, as below.
- `compacted` and `chunks_reclaimed` say whether the index was rewritten, which is what takes the chunks off disk. `outstanding` is what the node still owes; it falls to zero once a compaction has run. Watch it on `GET /v1/stats` under `lexical`.
- `documents_not_found` is reported rather than folded into success: a caller under an obligation needs to know this node never held the document instead of assuming it was removed.

The request is safe to repeat. Nothing is removed from the registry until storage and the index are
done with it, so re-issuing a request that was interrupted finishes what it left.

**Error — `500 Internal Server Error`** — some of the content is still there.

```json
{
  "error": "Some documents could not be fully erased; repeat the request",
  "source_id": "project-docs",
  "documents_erased": 3,
  "documents_incomplete": 1,
  "chunks_not_removed": 2,
  "chunks_still_searchable": 0,
  "...": "the rest of the fields as above"
}
```

A document whose chunks would not all come out of storage or out of the index is counted in
`documents_incomplete`, and the reply is a `500`. `chunks_not_removed` says how many are still in
storage and `chunks_still_searchable` how many the lexical index still matches — two numbers
rather than one, because a chunk can be in both states and a single total would report twice as
much content left behind as there is. It is a failure rather than a partial success: the caller asked for content to be gone
and some of it is not. The remedy is to repeat the request, which is safe — the registry entry is
kept for exactly the documents that fell short, so the repeat can still name their chunks. A
caller looping a source erasure until `documents_remaining` reaches zero **must also watch
`documents_incomplete`**, or it will loop forever over documents the node keeps failing to erase.

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
    "enable_llm_scoring": true,
    "llm_threshold": 0.3,
    "oversampling_factor": 2
  }'
```

| Field | Type | Default | Description |
|---|---|---|---|
| `query` | string | **required** | Raw query text. Embedded server-side; also used for LLM re-ranking when enabled. |
| `top_k` | int | `100` | Max results to return. Must be > 0. |
| `threshold` | float | `excalibur.query.threshold` | Minimum similarity score to include. Omitting it does not mean "no floor": the server's configured floor applies, `excalibur.query.threshold`, which is `0.0` - no filtering - unless the deployment raised it. |
| `timeout_ms` | int | `excalibur.visibility.query-timeout-ms` (`30000`) | Partial results returned if exceeded. Capped by `excalibur.api.max-query-timeout-ms` (`60000`); asking for more is a `400`. |
| `include_partial_segments` | bool | `true` | Include segments that are still indexing. |
| `enable_llm_scoring` | bool | `true` | Re-rank top candidates using the LLM. Silently has no effect on a node with no chat model configured — the results are the vector ranking, and `coverage.degraded` stays `false`, because a node without a chat model is running as configured rather than failing. If you need to know whether a particular answer was LLM-ranked, check that the node has `excalibur.llm.api-key` set. |
| `llm_threshold` | float | `excalibur.llm.relevancy-threshold` (`0.3`) | Score a candidate must reach in the LLM re-rank to survive it. Outside `[0.0, 1.0]` is a `400`. Inert when `enable_llm_scoring` is false or the node has no chat model. |
| `oversampling_factor` | int | `2` | How many times `top_k` the vector stages fetch before reranking narrows the list back down. Higher trades latency for recall. Must be at least 1 and at most `excalibur.api.max-oversampling-factor` (16); outside that is a `400`. |
| `include_text` | bool | `false` | Return each result's text alongside its identifier. Every result then carries `text` (`null` when the node no longer holds that chunk) and `text_truncated`. Text past `excalibur.api.max-result-text-chars` (4096) is clipped. A node with no chunk storage answers `400`. |

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
  "retrieval": "vector",
  "coverage": {
    "total_segments": 1000,
    "active_segments": 950,
    "indexing_segments": 40,
    "warming_segments": 10,
    "compacting_segments": 0,
    "tombstoned_segments": 0,
    "estimated_corpus_coverage": 0.9600,
    "segments_searched": 960,
    "segments_excluded": 40,
    "segments_with_errors": 0,
    "segments_timed_out": 0,
    "tail_scan_entries": 12500,
    "degraded": false
  },
  "timed_out": false,
  "cancelled": false,
  "query_time_ms": 42
}
```

Key fields:

- **`query_id`** — Server-generated UUID for this query. Pass it to `DELETE /v1/query/{query_id}` to cancel mid-flight.
- **`results`** — Each item has `document_id` (string), `score` (float, six decimal places) and `segment_id` (int). `document_id` is the identifier ingestion assigned, `sourceId:documentId:chunk:N`; it is always a JSON string, never a bare number, and it is never the HNSW node id, which compaction renumbers. Text is not returned unless the request asked for it — send `"include_text": true` and every result also carries `text` and `text_truncated`.
- **`retrieval`** — Which signals answered: `"hybrid"`, `"vector"` or `"lexical"`. Always present, on every path. It also tells you how to read `score`, because the three have no common scale — see [Hybrid retrieval](#hybrid-retrieval--both-signals).
- **`coverage.estimated_corpus_coverage`** — Fraction of the live corpus this query actually read. Segments it did not read (`segments_excluded`), that failed (`segments_with_errors`) or that did not answer in time (`segments_timed_out`) each reduce it, and each is counted once. < 1.0 during cold-start or heavy ingestion. Use this to decide whether to retry.
- **`coverage.segments_excluded`** — Live segments this query did not read for a reason that is not a failure. Two things land here: segments held back because `include_partial_segments` was `false`, which is your own choice; and segments with no vector index to search, which is the node's — their entries are in storage and no graph speaks for them. If you sent `include_partial_segments: true` and this is not zero, it is the second, and the node's log says which segments and why.
- **`coverage.tail_scan_entries`** — How many entries this query read straight from segment write-log tails, i.e. entries that are already stored and searchable but not yet built into an HNSW graph. It is a freshness signal, not a fault: a steady non-zero value under ingestion is normal, and a large one means the tail scan is doing work the graph would otherwise do faster. If a tail scan has to stop early, `degraded` says so.
- **`coverage.degraded`** — `true` when this answer is not what a fully healthy node would have returned. Four things set it: `estimated_corpus_coverage` below the node's threshold; a segment that errored; a segment that did not answer in time; and a segment whose write-log tail scan stopped early, which is the one case the counts above cannot show you — it is neither an error nor a timeout, its results are kept, and `segments_searched` still counts it, so without this flag the response would claim the whole corpus was read. It is also set when `enable_llm_scoring` was honoured and the LLM re-rank fell back to the vector ranking. `degraded` never means the results are wrong; it means they are partial or ranked by a weaker signal, so retrying is worth considering.
- **`timed_out`** — `true` if the timeout was hit. Partial results may still be returned (best-effort).
- **`cancelled`** — `true` if `DELETE /v1/query/{query_id}` withdrew this query before it finished. A
  cancelled query answers with whatever it had reached, which is usually nothing; without this field
  that answer is indistinguishable from a query that searched the whole corpus and matched nothing.
  It is separate from `timed_out` on purpose: a deadline the server missed and a request you
  withdrew are different events, and only one of them is the server's to answer for.

### Timeout response — still `200 OK`

Timeouts are a normal outcome, not an error, so partial results still return with HTTP 200 and `"timed_out": true`:

```json
{
  "query_id": "f12c8d34-5678-4abc-9def-0123456789ab",
  "results": [
    {"document_id": "my-docs:runbook.md:chunk:0", "score": 0.880000, "segment_id": 11}
  ],
  "retrieval": "vector",
  "coverage": {
    "total_segments": 1024,
    "active_segments": 1024,
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
  "cancelled": false,
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
  "ignored_fields": ["threshold"],
  "timed_out": false,
  "cancelled": false,
  "query_time_ms": 0
}
```

Three differences from a vector answer:

- **`degraded` is always `true`,** even at full coverage. The answer is real; it came from one
  signal where a fully configured node fuses several, and that is worth telling a caller.
- **`segment_id` is absent from each result.** The lexical store has no HNSW segment to name.
- **`ignored_fields` names what this path could not honour.** It lists the fields you sent that
  steer a stage this path does not have — `threshold`, `include_partial_segments`,
  `enable_llm_scoring`, `llm_threshold`, `oversampling_factor`. They are still
  validated and still refused if out of range; they just change nothing here, and the list is how
  you find that out rather than inferring it from results that look wrong. It is always present,
  empty included. `query`, `top_k` and `timeout_ms` are honoured and never appear in it.

Scores are BM25, not cosine similarity — they are not comparable to a vector answer's scores and
are not bounded by 1.0. Rank them; do not threshold them against a number tuned on the vector path.
That is why `threshold` is reported as ignored rather than applied: a floor tuned against cosine
scores would cut an arbitrary part of a correct BM25 ranking.

Two ways to reach this path. With no API key it is plain BM25 — the startup log says so. With a
key and `excalibur.llm.embeddings-enabled=false` the chat model is kept and spent on expanding the
query instead of on embedding every document, which is the deployment this path was built for: a
few dozen generated tokens per query against billions of embedded tokens per corpus.

### Hybrid retrieval — both signals

A node with an embedding model indexes its chunks twice: into the HNSW graphs vector search reads,
and into the inverted index BM25 reads. The second is written, compacted and erasure-swept whether
or not a query consults it. So by default `/v1/query` consults it: both signals run — concurrently,
since BM25 needs no embedding — and their rankings are fused by reciprocal rank.

```json
{
  "query_id": "3f8a1b2c-4d5e-4f60-8a91-2b3c4d5e6f70",
  "results": [
    {"document_id": "kb:log4j.md:chunk:4", "score": 0.032522, "segment_id": 3, "signals": ["vector", "bm25"]},
    {"document_id": "kb:cve.md:chunk:0", "score": 0.016393, "segment_id": null, "signals": ["bm25"]},
    {"document_id": "kb:jvm.md:chunk:2", "score": 0.016129, "segment_id": 7, "signals": ["vector"]}
  ],
  "retrieval": "hybrid",
  "coverage": { "...": "as on the vector path" },
  "timed_out": false,
  "cancelled": false,
  "query_time_ms": 48
}
```

What is different from a vector answer:

- **`score` is a sum of reciprocal ranks, not a similarity.** Fusion reads position, never
  magnitude, because a BM25 score of 31.7 and a cosine of 0.91 have no common scale and any
  attempt to give them one is a calibration that goes stale as the corpus changes. The numbers
  are small and near zero by construction; rank them, do not threshold them. `threshold` is
  still applied — by the pipeline, to the vector signal, before fusion — so it keeps meaning
  exactly what it meant, on the stage it always acted on. It does not reach the BM25 signal,
  which has no similarity to compare it against, so a result carrying only `"bm25"` in its
  `signals` was never tested against it. Set `excalibur.query.hybrid-retrieval=false` if every
  result must have cleared the floor.
- **A result both signals returned outranks one a single signal loves.** That is what fusion is
  for: agreement between independent signals is different evidence from a high score from one.
- **`signals` names which signals returned each result,** best rank first.
- **`segment_id` is `null` for a result only BM25 found.** It was never in a vector segment, and
  naming one would invent a provenance it does not have.

Neither signal is allowed to fail the query, and that runs both ways.

- **BM25 breaks, or misses the budget.** The vector answer stands and the response reports
  `"retrieval": "vector"`.
- **The embedding endpoint or the vector pipeline misses the deadline.** You get the answer BM25
  already finished, with `"retrieval": "lexical"` and `"timed_out": true` — the request did miss
  its deadline, and it was answered by fewer signals than this node has, so both are said. If BM25
  had not finished either, the response is the empty timeout answer as before.
- **The node is saturated.** Concurrent BM25 searches are capped at one per core, and never
  below two, because a search inside the lexical engine does not stop when its request does; past that cap the second
  signal is shed for that request and the answer comes from vector search. This is load shedding,
  not an error, and `retrieval` is the only place it shows.
- **You cancel with `DELETE /v1/query/{query_id}`.** Both halves stop and nothing is returned,
  including from the half that had finished: a withdrawn question is not answered.

In every case `retrieval` names the signals that actually ran, never a fusion that did not.

Set `excalibur.query.hybrid-retrieval=false` for vector-only ranking, which is what a deployment
with a threshold tuned against cosine scores wants. The key has no effect on a node holding only
one signal.

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

### Read a result's text

A search answers with chunk identifiers. `include_text` on the query returns the text alongside
them, clipped to `excalibur.api.max-result-text-chars`. These routes read it back on its own —
for a caller that kept identifiers from an earlier search, or that wants the text for a page of
results without re-running the query.

One chunk:

```bash
curl -G http://localhost:8080/v1/documents \
  -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" \
  --data-urlencode "document_id=notes:handbook.md:chunk:2"
```

A page of them — a search returns `top_k` identifiers at once, and resolving them one at a time
turns one answer into `top_k` round trips:

```bash
curl -X POST http://localhost:8080/v1/documents/fetch \
  -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"document_ids": ["notes:handbook.md:chunk:2", "notes:handbook.md:chunk:3"]}'
```

```json
{"documents": [
   {"document_id": "notes:handbook.md:chunk:2", "text": "...", "text_truncated": false},
   {"document_id": "notes:handbook.md:chunk:3", "text": null, "text_truncated": false}],
 "documents_not_found": 1}
```

Every identifier asked for comes back, in the order asked, whether or not the node holds it: a
caller that named three and received two cannot tell which was dropped. `text` is `null` for one
the node does not hold — usually a chunk erased but not yet compacted out of the vector index —
and `documents_not_found` counts them. A miss is not a `404`: the request is for a set, and part of
a set being absent does not make the request wrong.

The identifier goes in a query parameter or a JSON array rather than in the path because a chunk
identifier is built from a source id, a document path and an ordinal, so it routinely contains both
colons and slashes.

**Bound** — `POST /v1/documents/fetch` accepts at most `excalibur.api.max-top-k` (1000)
identifiers, so one page of results is always fetchable in one request.

**Error — `400 Bad Request`** — a `GET` naming no `document_id`, a `POST` naming none, more than the
bound, or an empty identifier in the array.

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

Use the `query_id` returned in the original query response. Cancellation is cooperative and
propagates through all query pipeline stages — an in-flight query will observe the cancel signal and
return its own response with partial (or empty) results, marked `"cancelled": true`. A lexical
answer is cancellable the same way: the search is interrupted where it stands.

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
  "coverage": 1.0000,
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
  "coverage": 0.0000,
  "latencyMs": 2
}
```

- `live: true` as long as the process is running — use for liveness probes. The endpoint always returns HTTP 200 if the process is alive, so liveness probes should check this field rather than the status code.
- `ready` flips to `true` when `coverage ≥ excalibur.health.readiness-threshold` (default `0.5`) — use for readiness probes (load balancers / K8s readiness).
- `healthy` flips to `true` when `coverage ≥ excalibur.health.healthy-threshold` (default `0.9`) — the higher bar of the two.
- `epoch` — current visibility epoch (monotonically increasing).
- `coverage` — the share of this node's live segments that are in a state a query reads, in `[0.0, 1.0]`. Live means everything the catalog holds and has not retired, so a segment compaction folded into its successor is not counted as corpus this node is missing. **This is not a warm-up gauge.** A segment still absorbing its write log is searched, so it counts, and an empty segment counts as readily as a full one — on a node whose segments are all accounted for this reads `1.0000` from the first moment it starts. It falls when a segment is stranded in a state no query reads, and reads `0.0000` before the first segment exists, which is the cold start above. For how much of the corpus a *particular answer* came from, read `coverage.estimated_corpus_coverage` in the query response; for ingestion progress, read `entries.total` and `segments.active` from `/v1/stats`.
- `latencyMs` — time taken to compute the health response. Exceeding `excalibur.health.max-latency-ms` (default `5000`) sets `healthy` to `false` by itself, whatever the coverage. `ready` is left on coverage alone: a probe that waited that long has already timed out, and a slow answer here is not a reason to stop serving traffic.

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
    "warming": 2, "compacting": 0, "tombstoned": 2, "purged": 0
  },
  "entries": {"total": 15000000},
  "volumes": {"count": 1, "totalBytes": 5368709120, "usedBytes": 3221225472},
  "hnsw": {"queueDepth": 50000, "queueDepthLimit": 100000, "vectorsIntegrated": 14000000},
  "cuckoo": {"pendingDeletions": 100, "totalDeletions": 5000,
             "avgFpRate": 0.000500, "maxFpRate": 0.001000,
             "saturated": false, "saturatedSegments": 0},
  "visibility": {"epoch": 42, "registeredSegments": 1024, "visibleSegments": 1024},
  "coverage": 1.0000,
  "lexical": {
    "engine": "native", "indexed": 15000000, "segments": 12,
    "liveChunks": 14980000, "erasedChunks": 20000, "erasedRatio": 0.0013,
    "erasureRequests": 3, "oldestErasureAgeMs": 412000, "compactionDue": false
  }
}
```

`purged` is always `0`: reclaiming a segment deletes its catalog row rather than keeping a
tombstone of one, so a retired segment is counted under `tombstoned` until it is reclaimed and
then counted nowhere. `coverage` is the same number `/health` reports — see above for what it
does and does not measure.

`volumes.count` is one on a node that keeps everything in its data directory, which is the
default. It rises with `excalibur.shard.volumes`, and `totalBytes`/`usedBytes` are then the sum
across all of them — so a node running short of room shows it here before a write fails.

Watch `hnsw.queueDepth` — if it grows without bound, the integration worker can't keep up with ingestion (check logs for errors).

`cuckoo.saturated` is the membership filter having run out of room at the corpus level, and
`saturatedSegments` counts the segments whose own filter did. Neither changes an answer: a level
that has refused a term is no longer trusted to rule a segment out, so its scope is searched in
full instead of being pruned. What they cost is latency, and nothing else in this response would
show it. Raise `excalibur.filter.corpus-capacity` and restart.

The `lexical` block is present only when this node keeps a chunk index; an absent block and an index
reporting zeroes are different states. Within it:

- `engine` is which implementation is answering, `native` or `lucene`. `segments` is how many the index holds, which is what a query has to visit — compaction is what keeps it bounded.
- `indexed` counts the chunks handed to this index since the process opened it, which is what to watch during an ingestion run. It is not the size of the corpus: a node restarted over a populated store reports `0` here and a non-zero `liveChunks` until something ingests again.
- `liveChunks` is what a query can match. `erasedChunks` is hidden from queries but still on disk — erased chunks and replaced versions of re-crawled ones both count here, and a compaction is what removes them.
- `erasureRequests` and `oldestErasureAgeMs` are the erasure backlog: what was promised and not yet discharged, and how long the oldest has waited. Compare the age against `excalibur.ingest.lexical-max-latency-seconds`. Nothing else in this response would show that deadline going unmet.
- `compactionDue` is what the index's policy would decide if asked right now. Compaction runs at the end of an ingestion or reindex run, and on the sweep that enforces `excalibur.ingest.lexical-max-latency-seconds`, so a node reporting `true` settles within a tenth of that bound even when nothing ingests.
- On `engine: "lucene"` the erasure fields are always zero and `compactionDue` always `false`: Lucene reclaims deleted documents on its own merge schedule, holding no obligation this node could report on.

### Reclaim space now

Erasing a document, replacing it on a re-crawl and splitting a segment all leave tombstones
behind: entries that no query can match and every byte of which is still on disk. Two background
sweeps reclaim them on a timer — `excalibur.shard.compaction-interval-ms` for segment files and
`excalibur.shard.hnsw-compaction-interval-ms` for vector graphs — which is the right default and
the wrong answer when the space is needed before the next sweep.

```bash
curl -X POST http://localhost:8080/v1/compact \
  -H 'Content-Type: application/json' \
  -d '{"force": false}'
```

```json
{"state": "running", "force": false, "started_at_ms": 1736899200000}
```

The reply is `202`: compaction on a large corpus is minutes of I/O, far longer than a request
should be held open, so the run happens behind the response. A second request while one is
running is answered `409` rather than queued.

`force` is optional and defaults to `false`, which applies exactly the thresholds the background
sweeps apply — `excalibur.shard.compaction-tombstone-ratio` and
`excalibur.shard.hnsw-compaction-tombstone-ratio` — and so brings forward work that would have
happened anyway. `{"force": true}` rewrites every live segment and rebuilds every graph whether
or not there is anything dead in them. That is what to send after erasing a large fraction of the
corpus, and otherwise a great deal of I/O for nothing.

Queries and ingestion continue throughout. A segment is rewritten beside its original and swapped
in at the end, under the same lock the background worker takes, so a manual run and a scheduled
one can never rewrite the same segment twice.

### Compaction status

`POST /v1/compact` answers before the work is done, so this is how the run's verdict is read back.

```bash
curl http://localhost:8080/v1/compact/status
```

```json
{
  "state": "completed",
  "force": false,
  "started_at_ms": 1736899200000,
  "finished_at_ms": 1736899213820,
  "duration_ms": 13820,
  "segments_considered": 64,
  "segments_compacted": 9,
  "segments_failed": 0,
  "segments_skipped": 55,
  "graphs_considered": 64,
  "graphs_compacted": 4,
  "graphs_skipped": 60,
  "graphs_failed": 0
}
```

- **`state`** — `idle` before the first run since startup, `running` while one is happening,
  `completed` once it finished, and `failed` when the run itself raised an error, in which case
  `error` carries the reason and there are no counts.
- **`graphs_compacted`** counts only graphs where space actually came back. Forcing a run skips
  the tombstone-ratio policy, not the check for whether there is anything to reclaim, so a forced
  run over a corpus with few deletions reports most of its graphs under **`graphs_skipped`** —
  which is the honest answer, not a sign the force was ignored.
- **`segments_considered`** is every live segment the run looked at; **`segments_skipped`** is
  those it left alone, because the policy declined them or the background worker was already
  rewriting them. Neither is a fault.
- **`segments_failed`** is the honest middle: those segments still hold their dead weight, the
  reason for each is in the node's log, and the run is still reported as `completed` because the
  rest of it succeeded. A run is not all-or-nothing and is not reported as though it were.
- The `graphs_*` counts say the same for the HNSW graphs, which reclaim deleted nodes by
  renumbering rather than by rewriting a log.

Only the most recent run is kept; the next one replaces it.

### JMX metrics

The server also exposes JMX MBeans under the `io.excalibur` domain. Attach any JMX client (VisualVM, JConsole, Prometheus JMX exporter) to the JVM.

### Logs

Logback is pre-configured with JSON structured logging via logstash-encoder. Point your log shipper at stdout.

### Backups

Every byte of a node's state lives under its data directory — segments, write logs, the segment
catalog, the routing directory, the chunk index, the graph, the document registry and the source
catalog. Nothing is kept anywhere else, so backing the node up is copying that one directory and
restoring it is putting the directory back.

```bash
# 1. Stop the node and wait for the process to exit.
kill -TERM "$(pgrep -f excalibur.jar)"

# 2. Copy the data directory. --sparse=always matters: the segment and HNSW files are
#    preallocated and mostly holes, so a naive copy writes gigabytes of zeroes.
cp -a --sparse=always /var/lib/excalibur/data /backups/excalibur-$(date +%F)

# 3. Restart.
EXCALIBUR_DATA_DIR=/var/lib/excalibur/data java --enable-preview \
    --enable-native-access=ALL-UNNAMED -jar excalibur.jar
```

To restore, point `EXCALIBUR_DATA_DIR` at the copy — or move the copy back into place — and start
the node. It rediscovers the segments from the catalog, replays each write log, and serves the
same answers the node it was taken from served; no import step and no reindex.

**Take the copy with the node stopped.** The state is spread over many files, written two
different ways: the catalog, the manifests and the registries are replaced by an atomic rename,
while the segments, their write logs and the HNSW graphs are written in place through their
mappings. Nothing orders one file against another. A copy taken while the node is writing can
therefore catch a catalog naming a segment the copy does not yet hold, or a mapping caught
half-flushed. Stopping first is the only way to get files that agree with each other, and a
`SIGTERM` shutdown is clean: outstanding writes are fsynced before the process exits.

If a hot copy is the only option, take it knowing what it is worth. Every write-log append is
fsynced before it is acknowledged, so the acknowledged writes are on disk; what an inconsistent
copy loses is whatever the catalog and the segments disagree about, not the corpus.

**Copy the whole directory, including the `.compact` files.** An HNSW compaction stages its
replacement as `<graph>.hnsw.compact` and `<graph>.hnsw.compact.docmap`, then renames them into
place in that order. Those two files are the crash-recovery record: on the next open, a staged
graph still present means the swap never started and both are discarded, and a staged mapping
alone means the graph was already replaced and the mapping rename has to be finished. A backup
filter that excludes `*.compact` but keeps `*.compact.docmap` — or deletes one without the other
— turns a recoverable interruption into a graph whose node numbering and document mapping
disagree, which answers with the wrong document rather than failing.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Server is not configured for text queries: this node has neither an embedding client nor a lexical index` | Not a missing API key on its own — without one the node still answers from BM25. Both retrieval paths are gone, so the chunk lexical index also failed to open | Search the startup log for `Chunk lexical index unavailable` and fix what it names (usually the data directory); set `EXCALIBUR_LLM_API_KEY` as well if you want vector retrieval |
| Results look like noise / no relevant matches | The `excalibur.llm.embedding-model` config changed between ingestion and query runs | Reingest with the current model — old vectors are incompatible once the embedding model changes |
| `Field 'query' is required and must be non-empty` | Request body is missing the `query` field | Send the query text in the `query` field (the HTTP API no longer accepts raw vectors) |
| `hnsw.queueDepth` keeps growing | Integration worker falling behind | Check logs for errors; slow down ingestion; increase hardware |
| All LLM calls failing | A circuit breaker tripped. Chat and embeddings have one each, so check which: relevancy scoring stopping while ingestion continues is the chat breaker, and the reverse is the embedding one | Wait for the cooldown (default 60s), after which one probe is admitted. Confirm API key/endpoint. Queries still work without LLM scoring. |
| Queries return nothing on a fresh node, but `coverage` reads `1.0000` | Not a cold-start indicator. `/health` coverage counts the segments a query reads, and an empty segment is read as readily as a full one, so it reads `1.0000` and `/health` reports `ready` and `healthy` | Look at `/v1/stats` instead: `entries.total` and `segments.active` are `0` until something is indexed |
| A source keeps being re-crawled after you erased its documents | Document-scoped erasure does not deregister the source; only `scope: "source"` does | `DELETE /v1/ingest` to stop the crawl, or erase with `scope: "source"` |
| `POST /v1/erasure` returns 404 | The route is not registered, which the packaged server never does - it wires an erasure service on every start, whether or not the ingestion pipeline came up. So this is either a path that is not quite `/v1/erasure`, or a host embedding `ExcaliburDBServer` directly and never calling `withDocumentErasure` | Check the startup log's registered-endpoint line: it names `/v1/erasure` when the route is there |
| `/v1/erasure` reports `documents_not_found` for a document you can query | Its registry entry is gone, so the chunk ids it named are unknown; the chunks are still in storage | Reindex the source, which re-registers the document, then erase it |
| A source stops being re-crawled after a restart, and `/v1/ingest/status` does not list it | Its target is no longer allow-listed, so restoring it was refused; the startup log names it | Put the root or host back on `excalibur.ingest.allowed-roots` / `excalibur.ingest.allowed-hosts`, or `DELETE /v1/ingest` if the source is finished |
| A re-crawl reingests everything and tombstones nothing | `<data dir>/document-registry` is missing, so change detection reads every document as new | Restore the data directory whole; the registry belongs with the segments it describes |
| `lexical.oldestErasureAgeMs` exceeds `lexical-max-latency-seconds` | The sweep that enforces the bound runs every tenth of it, so a reading just past the bound is ordinary; one far past it means the sweep is failing, and the node's log says why | Read the log for a failed compaction. Ingesting or reindexing anything also settles the backlog before the run finishes |
| A re-crawled document appears twice in results | Not the chunk index — it replaces a chunk identifier rather than appending. Check whether the source is listing one document under two ids | Compare the `document_id` values on the two hits — that is the chunk identifier, `source:document:chunk:N`: identical means a bug, different means the source |
| Queries slow down on a large corpus, and `/v1/stats` reports `cuckoo.saturated: true` | The membership filter is full. A level that cannot record a term is no longer trusted to rule a segment out, so pruning is off and every query scans every segment | Raise `excalibur.filter.corpus-capacity` past your term count and restart |
| Node refuses to start: `Refusing to create segment N in <dir>: <other dir> already holds segment_N.dat` | Two volumes hold a file for the same segment index, usually from a volume set that changed between runs on an older build | Move or delete the stale copy — the one whose directory the catalog does not name — then restart |
| Node refuses to start naming two `excalibur.shard.volumes` entries that resolve to one directory | A symlink, a relative path or a `..` in the list makes two entries the same directory, which would give it two shares of the ring | Drop one of the two spellings |
| Node fails to start with `CatalogLoadException` | `<data dir>/source-catalog` was written by a build older than the end marker, or was cut short by a crash | [Upgrading a data directory](#upgrading-a-data-directory-written-by-an-older-build) — append the marker, or delete the file and re-register the sources |
| A `WARN` at every start naming a lexical segment and an old index format | The segment predates `index.meta` version 2, so block pruning is disabled on it. Results are correct; the segment is read more slowly | Nothing, or force a compaction with `POST /v1/compact` to rewrite it into the new format |
| `--enable-preview` error | Wrong Java version | Java 21 exactly — a preview class file is refused by any other JDK |
| `--enable-native-access=ALL-UNNAMED` warning | Missing flag | Required for Panama FFM API |
| Port already in use | Another process on 8080 | `EXCALIBUR_PORT=9090 java ...` |
| Process exits 1 with no message at all | A fatal startup error was lost before the async log appender drained | Fixed by the logback shutdown hook; if you see it again on a modified `logback.xml`, check that `<shutdownHook/>` is still declared |

### Upgrading a data directory written by an older build

Two on-disk formats changed and a node reads both, but one of them needs a manual step.

**`<data dir>/source-catalog` — a manual step is required.** The catalog of registered ingestion
sources now ends with a marker line, `\#end|<count>`, written after the records are forced to
disk. Without it a catalog cut short by a crash is a file of whole, well-formed records — a node
comes back silently having forgotten the sources it acknowledged. A catalog written by an older
build has no marker, so the node **fails to start** with `CatalogLoadException` rather than
guessing which case it is looking at. Either append the marker, with `<count>` the number of
record lines:

```bash
printf '\\#end|%s\n' "$(wc -l < "$DATA_DIR/source-catalog")" >> "$DATA_DIR/source-catalog"
```

— or delete the file and re-register the sources with `POST /v1/ingest`. Nothing else in the data
directory is affected, and no indexed document is at risk either way.

**The native lexical index — no step required.** `index.meta` is now version 2, and a segment
written by an older build keeps opening and keeps returning exactly the same results. The old
format stored each postings block's score bound computed against that segment's own mean document
length, which is not the mean a query across several segments scores with, so such a bound could
be exceeded and a document that belonged in the top-k silently dropped. A version 2 segment stores
the ingredients instead and computes the bound at query time, against the statistics actually in
use. An older segment is read with block pruning disabled — correct, and slower — and logs a
`WARN` naming itself at open. Expect that warning after the upgrade; it is not data loss. A
compaction rewrites the segment into the new format and the warning stops.

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
