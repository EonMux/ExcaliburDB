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

# Build an installable distribution: the jar, its start scripts and nothing else needed
# (output: excalibur-api/build/install/excalibur-api/)
./gradlew :excalibur-api:installDist

# The same thing as an archive, for shipping to a host
./gradlew :excalibur-api:distTar    # or :distZip
```

**Prefer the distribution to the bare fat JAR for anything but a quick try.** Every module is
compiled with `--enable-preview`, and a preview class file is refused outright by a JVM that was
not started with the same flag — so `java -jar excalibur.jar` fails with
`UnsupportedClassVersionError` unless you remember two flags on every invocation. The generated
`bin/excalibur-api` carries them, along with the heap-dump-on-OOM flags, so the launch cannot be
got wrong:

```bash
excalibur-api/build/install/excalibur-api/bin/excalibur-api --config /etc/excalibur.properties
```

`JAVA_OPTS` is passed through for anything extra (heap sizing, JMX), and `EXCALIBUR_*`
environment variables work the same way they do for the jar.

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

### As a supervised service

There is no packaged unit file, because the distribution is a plain directory and a service
manager wants its own paths. A minimal systemd unit is:

```ini
[Unit]
Description=ExcaliburDB
After=network.target

[Service]
ExecStart=/opt/excalibur/bin/excalibur-api --config /etc/excalibur/excalibur.properties
Environment=EXCALIBUR_DATA_DIR=/var/lib/excalibur/data
Environment=EXCALIBUR_API_AUTH_TOKEN=...
WorkingDirectory=/var/lib/excalibur
User=excalibur
# The node drains in-flight queries and closes storage durably on SIGTERM; give it room to.
KillSignal=SIGTERM
TimeoutStopSec=60
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Read the token from a file with `EnvironmentFile=` rather than inlining it: `Environment=` lines
are world-readable through `systemctl show`.

### In a container

```bash
docker build -t excalibur .

docker run -d --name excalibur \
  -p 8080:8080 \
  -v excalibur-data:/var/lib/excalibur \
  -e EXCALIBUR_API_BIND_ADDRESS=0.0.0.0 \
  -e EXCALIBUR_API_AUTH_TOKEN="$TOKEN" \
  -e EXCALIBUR_LLM_API_KEY="$OPENAI_API_KEY" \
  --stop-timeout 40 \
  excalibur
```

The `Dockerfile` in the repository root builds the shadow jar and runs it on a Temurin 21 JRE as
uid `10001`, with the jar owned by `root` and read-only to the process. Nothing is baked in:

| | |
|---|---|
| Data | `/var/lib/excalibur`, declared as a `VOLUME`. `EXCALIBUR_DATA_DIR` already points at it |
| Reachability | The API binds `127.0.0.1` by default. Serving from outside the container needs `EXCALIBUR_API_BIND_ADDRESS=0.0.0.0`, `EXCALIBUR_API_AUTH_TOKEN`, and either the TLS keystore settings or `EXCALIBUR_API_ALLOW_PLAINTEXT=true` behind something that terminates TLS |
| Secrets | None are in the image. Pass `EXCALIBUR_LLM_API_KEY` at run time, or mount a properties file and point `--config` at it — arguments after the image name reach the application |
| Heap | `-XX:MaxRAMPercentage=60.0`, so the same image is right under any `docker run -m`. Not 75%: segments live in mapped arenas outside the heap, and a heap that crowds out the page cache turns every search into a disk read |
| Health | `HEALTHCHECK` polls `/health/ready` with a 90s start period, which covers segment recovery on a large data directory |
| Stop | Allow at least 30 seconds. The JVM is pid 1, so `SIGTERM` reaches the shutdown hook that closes the storage engine and the chunk index — which is what publishes the tail of an ingestion run. `--stop-timeout 40`, or `terminationGracePeriodSeconds: 40` |

Bind-mounting a host directory instead of a named volume means `chown 10001:10001` on it first;
the process does not run as root and will not be able to create its catalog otherwise.

### Taking a node out of rotation

Before a restart, an upgrade, or a manual compaction, tell the load balancer to stop sending
work — `POST /v1/drain` makes `/health/ready` answer `503` while the node keeps serving what it
already has, and `DELETE /v1/drain` puts it back. Point the readiness probe at `/health/ready`,
not `/health`: `/health` answers `200` whatever its `ready` field says, because it is also the
liveness probe and an unready node is not a dead one.

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

The command line is held to the same standard. `--config` with no path after it, two `--config`
arguments, or any argument the server does not recognise refuses to start and exits `2`, rather
than starting a node on settings nobody chose. `--config=<path>` is accepted as well as
`--config <path>`, and `excalibur-api --help` prints the arguments and the exit codes without
starting anything.

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
| `GET` | `/health` | none | Liveness probe; always `200` while the process is alive |
| `GET` | `/health/ready` | none | Readiness probe; `200` when this node can serve, `503` when it cannot |
| `GET` | `/metrics` | bearer | Prometheus text exposition, for a scraper |
| `GET` | `/v1/stats` | bearer | Runtime statistics |
| `GET` | `/v1/version` | bearer | Which build and JVM this process is running |
| `POST` | `/v1/drain` | bearer | Report unready, so a load balancer stops choosing this node |
| `DELETE` | `/v1/drain` | bearer | Report ready again |
| `GET` | `/v1/query/status` | bearer | Queries in flight, with the ids `DELETE /v1/query/{id}` takes |
| `POST` | `/v1/query` | bearer | Vector similarity search |
| `GET` | `/v1/documents` | bearer | Read one chunk's text, by `document_id` |
| `POST` | `/v1/documents/fetch` | bearer | Read the text of a page of chunks |
| `GET` | `/v1/documents/list` | bearer | Which documents this node holds, with their chunk ids. `404` on a node that keeps no document registry |
| `POST` | `/v1/documents` | bearer | Index documents supplied in the request. `503` on a node with no ingestion pipeline |
| `DELETE` | `/v1/query/{query_id}` | bearer | Cancel an in-progress query |
| `POST` | `/v1/ingest` | bearer | Register an ingestion source |
| `GET` | `/v1/ingest` | bearer | Read back what every source was registered as |
| `DELETE` | `/v1/ingest` | bearer | Stop crawling a source, keeping its documents |
| `DELETE` | `/v1/ingest/run` | bearer | Stop the ingestion run in flight, keeping the source registered |
| `GET` | `/v1/ingest/status` | bearer | Per-source ingestion status |
| `POST` | `/v1/reindex` | bearer | Force re-crawl of a registered source |
| `DELETE` | `/v1/reindex` | bearer | Ask a running re-index to stop |
| `GET` | `/v1/reindex/status` | bearer | Per-source re-index status |
| `POST` | `/v1/erasure` | bearer | Remove named documents from the corpus, and withdraw what was extracted from them into the graph |
| `POST` | `/v1/compact` | bearer | Reclaim the space tombstoned entries still hold, now rather than on the next sweep |
| `DELETE` | `/v1/compact` | bearer | Ask the compaction in progress to stop |
| `GET` | `/v1/compact/status` | bearer | What the last compaction examined, reclaimed and failed on |
| `POST` | `/v1/graph/query` | bearer | Answer a question from the graph |
| `POST` | `/v1/graph/build` | bearer | Start publishing the facts ingestion has staged as the served graph. `202`; the build runs behind the response. `409` while one is already running |
| `DELETE` | `/v1/graph/build` | bearer | Stop the build in progress. It publishes nothing; the namespace keeps the graph it had |
| `GET` | `/v1/graph/build/status` | bearer | What the build is doing, and what the last one published |
| `POST` | `/v1/graph/erasure` | bearer | Record an obligation to erase, and compact when it is due |
| `GET` | `/v1/graph/namespaces` | bearer | Every namespace on disk: whether it is open, whether it has built a graph, what it has staged and what it still owes |
| `DELETE` | `/v1/graph/namespaces/{namespace}` | bearer | Remove a namespace entirely - its graph, its staged facts and its erasure log. Irreversible; `409` for `shared` |

All request/response bodies are JSON. Request size limit: **1 MB**, and a body over it is refused with `413` by every endpoint that reads one - split the payload rather than retrying it. The `GET` routes, `DELETE /v1/graph/namespaces/{namespace}`, `DELETE /v1/graph/build` and
`POST /v1/graph/build` read no body, so they never produce a `413`.

Three routes that change what is registered — `POST /v1/ingest`, `DELETE /v1/ingest` and
`POST /v1/erasure` — answer `503` when the change could not be recorded in the source catalog.
The registration itself is refused in that case rather than applied in memory alone: a source
this node is crawling and has no record of is one that silently stops being crawled at the next
restart.

A field of the wrong JSON type is a `400` naming the field, never a silent default. `{"top_k":
"ten"}`, `{"as_of": 2019}` and `{"document_ids": "a.md"}` are each refused rather than read as
though the field had been left out — a request answered `200` after quietly discarding half of
what it said is the failure worth avoiding, and on `/v1/erasure` it is the difference between
removing one document and removing a whole source. An explicit `null` is the exception and means
exactly what omitting the field means, because that is what most encoders emit for an unset
optional value.

`X-Excalibur-Namespace` names the graph namespace a request belongs to. It is read by the
`/v1/graph/*` routes and by `POST /v1/ingest`, which binds the registered source to that namespace
for the life of the registration — every document it crawls is staged there. A node holds one
corpus, so every other `/v1` route answers `400` when a request names a namespace rather than
quietly serving the shared index — set the header to `shared` or leave it off. The probes and node-level routes (`/health`, `/health/ready`, `/metrics`,
`/v1/version`, `/v1/drain`) ignore it. See
[Namespaces](README.md#namespaces) for what a namespace holds.

The `/v1/graph/*` routes are documented in the README, under
[Graph Examples](README.md#graph-examples) for the request and response shapes. They are named
here so the surface is complete in one table; the shapes are kept in one place rather than two so
they cannot disagree.

### Authentication

Authentication is off until a token is configured, which is why the examples in this guide carry no
`Authorization` header. Set one and every path except `/health` and `/health/ready` then requires
it:

```bash
export EXCALIBUR_API_AUTH_TOKEN='...'
curl -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" ...
```

There is no default token and none is compiled in, and the startup log warns for as long as none is
set. That default is safe only because it is paired with the bind policy: the server binds
`127.0.0.1` unless told otherwise, and refuses to start on a non-loopback address without a token
rather than serving the world by accident. `/health` and `/health/ready` are exempt because the
infrastructure that reads them - a kubelet, a service mesh, a cloud load balancer's target-group
health check - is frequently not yours to configure and several of those cannot attach an
Authorization header at all. Both are still rate limited.

`/metrics` is **not** exempt, deliberately. Its consumer is a scraper you do configure, every
scraper supports a bearer token, and the exposition names disk capacity, corpus size, segment
counts and error rates. Because authentication is off until a token is set, a development node
still scrapes with no setup; a node that has a token can close the route, which it could never do
if the route were public.

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

### Read the registrations back

What every source was registered as, so the crawl a node is running can be checked against the
one that was intended — after a restart restored them from the catalog, or on a node nobody
present configured.

**Request — all sources**

```bash
curl http://localhost:8080/v1/ingest
```

**Response — `200 OK`**

```json
{
  "sources": [
    {
      "source_id": "project-docs",
      "source_type": "filesystem",
      "registered": true,
      "namespace": "shared",
      "config": {"included_extensions": "md,txt", "max_depth": "10", "root_path": "/data/docs"}
    },
    {
      "source_id": "docs-site",
      "source_type": "http",
      "registered": true,
      "namespace": "acme",
      "config": {"base_url": "https://docs.example.com", "max_documents": "500"}
    }
  ]
}
```

**Request — specific source**

```bash
curl "http://localhost:8080/v1/ingest?source_id=project-docs"
```

Answers the bare object rather than the array, and `404` for an id nothing is registered under.

`namespace` is the graph namespace this source's facts are staged into, fixed when it was
registered by the `X-Excalibur-Namespace` header on `POST /v1/ingest`. It is always present: a
registration that named none reads `shared`, so a single-tenant node looks the same as a
multi-tenant one and a missing field never has to be interpreted.

`registered: false` means the catalog holds the registration but the node is not crawling it —
a source whose target stopped being allow-listed, which the startup log names. Its documents
are still queryable; it is not being re-crawled.

`config` is an allow-list of the settings a source is built from, not an echo of the stored
record. Any other key is listed by name under `withheld` and its value is not returned. Nothing
in a source's configuration is secret today, but a read-back that echoes whatever it finds
becomes a way out for the first setting that is — a header, a cookie, a signed URL — and that
change would be made in the registration code, not here.

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
      "last_updated_ms": 1730000042000,
      "last_error": null
    },
    {
      "source_id": "docs-site",
      "state": "idle",
      "progress": 1.0000,
      "entries_ingested": 12034,
      "entries_pending": 0,
      "last_updated_ms": 1729900000000,
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
  "last_updated_ms": 1730000042000,
  "last_error": null
}
```

`last_updated_ms` is when this source last did anything — finished a crawl, failed one, reported
progress. Without it an `idle` source reads the same whether it was crawled a minute ago or
before a restart three weeks back, and "is the index still following the source?" cannot be
answered from the API at all.

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

**Error — `405 Method Not Allowed` — a method other than POST or DELETE**

```json
{"error": "Method Not Allowed - Use POST to start a reindex or DELETE to stop one"}
```

**Error — `503 Service Unavailable`** — the node was started without a reindexer, so there is
nothing behind the route. Reported rather than accepted: answering `202` would leave the source
marked "reindexing" for the life of the process while nothing ran, and block every later request
for it.

Unauthenticated, and charged against the read budget, like the other routes that answer two
methods: which handler serves a request is decided before its token is, so an uncharged 405 would
be free to send. It names only the verbs this route takes, which the route table already gives.

A node without an embedding endpoint reindexes too. It re-reads the source, re-chunks it and
rewrites the lexical index, and skips only the embedding step — the same half it skipped when
the source was first ingested, so the reindexed corpus is exactly what an ingestion on that node
would have produced. `state` goes to `indexing` rather than `embedding` while it runs, and the
startup log says which under `Reindexing will re-chunk and re-index without embedding`.

The scheduled re-crawl on `excalibur.ingest.reindex-interval-ms` follows the same path, so a
lexical-only node does pick up later edits to its sources.

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
  "cancelled": false,
  "progress": 0.6200,
  "documents_processed": 62,
  "documents_total": 100,
  "started_at_ms": 1730000000000,
  "finished_at_ms": 0,
  "last_error": null
}
```

**All sources**

```bash
curl http://localhost:8080/v1/reindex/status
```

```json
{"sources": [{"source_id": "project-docs", "state": "idle", "cancelled": false, "progress": 1.0000, "documents_processed": 100, "documents_total": 100, "started_at_ms": 1730000000000, "finished_at_ms": 1730000042000, "last_error": null}]}
```

`state` is one of `crawling`, `embedding`, `indexing` while a run is in flight, `idle` at
`progress: 1.0` once it finished, `cancelled` when someone stopped it, or `failed` with
`last_error` set when it broke. `cancelled` and `failed` are different answers and the
`cancelled` boolean says which without parsing `state`: a stopped run is the outcome you asked
for, and reporting it as a crash sends an operator looking for a fault that is not there.
`finished_at_ms` is when the run ended — `0` while one is still going — so how long it took, and
how stale its result is, are both readable without watching it.
`last_error` names the shape of the failure and how many documents it affected, and points at
the node's log for the reason itself — the reasons come from storage, the lexical index and the
embedding client, and carry filesystem paths and endpoint URLs that a read token should not be
enough to see. A source that has never been reindexed is a `404`, not an invented idle run — "nothing has been
asked of it" and "it ran and did nothing" are different answers, and only the second means the
corpus was rewritten. Deregistering a source with `DELETE /v1/ingest` clears its entry here.

`documents_`, not `entries_`: a reindex counts documents, where the ingest status's `entries_`
counts chunks. Both counters are the run's own — `documents_total` is the number of documents
the change detection found to be new or changed, and `documents_processed` goes up by one as
each of them is written. `progress` is their ratio and nothing else.

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

The run is *asked* to stop; it stops at its next checkpoint and releases its claim then. **The
checkpoint is a document boundary**, so the longest it can keep going is the one document already
under way — by design, not by omission: a document's chunks are published together or not at all,
and a stop that landed mid-document would leave chunks in storage that no registry entry names.
A document interrupted partway through is abandoned whole and left on its previous version. The
claim is deliberately not dropped here: handing the source to a second rolling replacement while the
first is still winding down is the concurrent-replacement data loss the claim exists to prevent,
because each tombstones the segments the other has just published. `GET /v1/reindex/status` is what
says when it has actually ended.

**Error — `404 Not Found`** — no source is registered under that id.

### Stop an ingestion run

Stops the run in flight and leaves the source registered. This is what you want for a crawl that
is hammering a site: `DELETE /v1/ingest` stops it too, but also deregisters the source, and
getting it back means re-sending the whole registration. Here the source stays, and is crawled
again at its next scheduled interval.

**Request**

```bash
curl -X DELETE http://localhost:8080/v1/ingest/run \
  -H "Content-Type: application/json" \
  -d '{"source_id": "docs-site"}'
```

**Response — `200 OK`**

```json
{
  "source_id": "docs-site",
  "stopped": true,
  "still_registered": true,
  "detail": "Ingestion was asked to stop; poll /v1/ingest/status for when it has. The source stays registered and is crawled again at its next scheduled interval."
}
```

`"stopped": false` means nothing was running for that source — an answer, not an error, since the
state you asked for is the state you have. The run is *asked* to stop and stops at its next
checkpoint, so `/v1/ingest/status` is what says when it has.

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

**Response — `200 OK`, or `202 Accepted` while the graph half is still queued**

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
  "graph_requests_submitted": 1,
  "graph_requests_satisfied": 1,
  "graph_requests_withheld": 0,
  "graph_outstanding": 0,
  "graph_compacted": true,
  "graph_erasure_complete": true,
  "graph_detail": "compacted: 4 edge(s) removed for 1 request(s)",
  "detail": "not due: 7 of 41230 chunk(s) tombstoned across 3 segment(s)"
}
```

The removals are counted separately because either can fall short:

- `chunks_erased` left storage, which is the only place a chunk's text and vector live.
- `chunks_unindexed` were erased from the lexical index, which stops them matching immediately.
- `vectors_hidden` were retired from the graph, so no entity search ranks the document again. It counts graph nodes, not vectors in a segment's HNSW index — those are reclaimed with the segment, as below.
- `compacted` and `chunks_reclaimed` say whether the index was rewritten, which is what takes the chunks off disk. `outstanding` is what the node still owes; it falls to zero once a compaction has run. Watch it on `GET /v1/stats` under `lexical`.
- `documents_not_found` is reported rather than folded into success: a caller under an obligation needs to know this node never held the document instead of assuming it was removed.
- The `graph_*` fields are the graph half, below. `graph_erasure_complete` is the one to read: while it is `false` the reply is a `202`, not a `200`.

**The graph half.** A document that was ingested into the knowledge graph left two things there
that removing its chunks does not touch: the facts an extractor read out of it, and up to two
kilobytes of its text recorded against every entity it mentioned. Both carry a provenance id
derived from the document's key, so this endpoint submits one source-scoped graph obligation per
document it actually erased — including for a source-scoped request, whose documents the caller
never named. `graph_requests_submitted` is that count.

Like every graph obligation it is recorded durably and discharged by a compaction, which the
policy schedules. So the reply is a **`202 Accepted`** with `graph_erasure_complete` false
whenever the graph still owes something, and a `200` only when both halves are done. That
distinction is the point: the content is out of the corpus either way, and a caller under an
obligation of their own must not read a `200` over an erasure that is still queued. `"compact":
true` discharges both halves before replying, which is what turns it back into a `200`.

A node serving no graph reports `graph_requests_submitted: 0` and `graph_erasure_complete: true`,
because nothing was ever extracted into one. A document counted in `documents_incomplete`
submits no graph obligation: withdrawing its facts while its text is still in storage is the
wrong half to erase first.

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
| `offset` | int | `0` | Skip this many results before the page. A ranked index has no cursor to resume from, so the engine is searched to `offset + top_k` and the page sliced out of it — which is why `offset + top_k` must not exceed `excalibur.api.max-top-k` (1000); past it is a `400`. Negative is a `400`. |
| `explain` | bool | `false` | Say why each result was returned: `explain.signals` (each contributing signal's own rank and score) and `explain.matched_terms`. Needs chunk storage, and a query of at most 64 distinct terms; otherwise `400`. |
| `snippet` | bool | `false` | Return the part of the passage the query matched rather than the passage from its start. Requires `include_text: true`; without it, `400`. |
| `snippet_chars` | int | `min(320, ceiling)` | Width of that window. Requires `snippet`. Between 16 and `excalibur.api.max-result-text-chars`; outside is a `400`. |

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
  "page": {"offset": 0, "limit": 2, "returned": 2, "next_offset": 2},
  "result_total": {"value": 2, "relation": "at_least"},
  "timed_out": false,
  "cancelled": false,
  "from_cache": false,
  "query_time_ms": 42
}
```

Key fields:

- **`query_id`** — Server-generated UUID for this query. Pass it to `DELETE /v1/query/{query_id}` to cancel mid-flight.
- **`results`** — Each item has `document_id` (string), `score` (float, six decimal places) and `segment_id` (int). `document_id` is the identifier ingestion assigned, `sourceId:documentId:chunk:N`; it is always a JSON string, never a bare number, and it is never the HNSW node id, which compaction renumbers. Text is not returned unless the request asked for it — send `"include_text": true` and every result also carries `text` and `text_truncated`; add `"snippet": true` for the part of the passage the query matched, and `"explain": true` for why the result is there at all.
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
- **`from_cache`** — `true` when this answer came out of the query result cache instead of being
  searched for. On every answer, `false` included, because it is what makes `query_time_ms`
  readable: on a hit that number is how long this request took, not how long the search took,
  and without this beside it a cache hit looks like a search that suddenly got faster. Always
  `false` on the lexical path and on a node with `excalibur.query.cache.enabled=false`, which is
  the default. A cached answer is never a stale one — see
  [the query cache](#the-query-cache) — and degraded, timed-out and cancelled answers are never
  cached at all, so a moment's trouble cannot be replayed as a persistent fault.
- **`page`** — `offset`, `limit`, `returned` and `next_offset` for the page you got. Present on
  every answer, including the empty one a timed-out or cancelled query returns, so a caller never
  has to branch on whether it is there. `next_offset` is `null` unless the engine actually filled
  the depth it was searched to.
- **`result_total`** — `{"value": N, "relation": "exact" | "at_least"}`. It is `exact` only when
  the engine came back *shallower* than the depth it was asked for **and** the search reached the
  whole corpus — `estimated_corpus_coverage` at 1.0, nothing excluded, nothing errored, nothing
  timed out, not cancelled. Otherwise `at_least`, which is a lower bound and not a count.
  So `{"value": 0, "relation": "exact"}` means *nothing matched*; `{"value": 0, "relation":
  "at_least"}` means *the node could not establish that*. The two are different answers and a
  single integer cannot tell them apart, which is why this is an object. Note it is deliberately
  **not** keyed off `coverage.degraded`: on the lexical path `degraded` also means "fewer signals
  ran", which would make that path never able to report an exact total.
- **`explain`** (per result, only with `"explain": true`) — `signals` gives each contributing
  signal's own `rank` (absolute, from the start of the whole ranking, not of the page) and `score`
  **on that signal's own scale**: a similarity for `vector`, and for `bm25` whatever the lexical
  service returns, which is its own internal fused score when more than one ranker contributed.
  The top-level `score` remains the fused rank sum and is not comparable with either.
  `matched_terms` are the terms of the query as you typed it that occur in the passage, whole-word
  and case-insensitive, in the order you typed them — computed the same way on every retrieval
  path, so a vector-only answer explains its matches too.
- **`query_expansion`** (response level, only with `"explain": true`) — what the query was widened
  with, once for the answer rather than per result. `status` is `applied`, `not_applied` or
  `unreported`; `unreported` means *this node cannot say* — no expander is wired, or no
  query-widening signal ran — which is a different fact from "the query was not widened".
- **`text_offset`** and **`highlights`** (per result, only with `"snippet": true`) — where the
  returned window starts in the chunk, and where the matched terms sit inside the window
  (`start`/`length`, relative to the `text` you were given). The window is a verbatim substring
  with no ellipsis and no markup, so those offsets stay meaningful. `include_text` on its own is
  unchanged: the chunk from the start, `text` and `text_truncated`, and neither new field.

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

  One entry is not a request field: `query_expansion`, which appears when the query was longer
  than the bound this node expands within and was therefore searched as plain BM25 while every
  shorter query is searched expanded. It is in the same list because it is the same failure —
  a request accepted in full and answered by less than it asked for — and nothing in a result
  list shows it.

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

The BM25 half of a hybrid query is not expanded by default. Query expansion is what carries the
meaning of a question when there are no vectors, and here there are — so the lexical signal
matches the words as typed, which makes it weaker than the same node would run with no embedding
model at all. Whether that trade pays depends on the corpus, so
`excalibur.query.expand-hybrid-queries=true` expands the lexical half as well, at one extra chat
call per query. It requires `excalibur.llm.api-key`.

The same expansion bound applies — a query longer than `excalibur.api.max-query-chars` is refused,
and one longer than the bound this node expands within is searched as typed. A hybrid answer
carries no `ignored_fields`: that list belongs to the lexical path, where expansion is the only
thing carrying the meaning of the question and losing it changes the retrieval strategy. Here the
vector signal still carries it, so the degradation is reported where it is proportionate — ask for
`"explain": true` and `query_expansion.status` reads `not_applied` on exactly those queries.

### The query cache

Off by default. `excalibur.query.cache.enabled=true` lets a repeated identical query be answered
without searching again; `from_cache` on the answer says when that happened.

**A cached answer is never a stale one.** Before every lookup the node reads a fingerprint of the
live corpus, and an entry is served only to a lookup presenting the identical fingerprint — so a
cache hit is a proof that nothing the answer was computed against has changed, not a bet that
nothing has. When anything moves, every entry is discarded rather than the one that was looked
up, because the thing that changed was the corpus and not one question about it.

The fingerprint is not the visibility epoch, which would be both too strict and too loose. Too
strict because the epoch advances on a timer whether or not a byte was written, so entries would
expire within one `excalibur.visibility.epoch-tick-ms` and the cache would never pay. Too loose
because the epoch fixes which *segments* are visible, not their contents: two queries at one
epoch legitimately differ once a write log has grown, and HNSW integration and segment compaction
advance no epoch at all. What is fingerprinted instead is each visible segment's own account of
itself — the epochs it was created and tombstoned at, its modification epoch, its live and total
entry counts, its tombstone count, its write-log head, its lifecycle state, its last compaction,
and its graph's integrated offset, node count, encoded-vector count and tombstone generation —
each read through the same accessor the query path reads it through, which is what makes the
fingerprint move exactly when an answer would.

Four things are deliberately never cached:

- **The BM25 half of a hybrid query, and the fusion.** A lexical answer can change with no
  mutation to observe at all, purely because the query epoch later crosses a manifest entry, so
  there is nothing cheap to fingerprint. It runs fresh on every request; a cached vector ranking
  fused with a fresh lexical one is still a correct fusion.
- **Result text, snippets and explanations.** Read from chunk storage on every answer, so quoted
  text can never be stale.
- **Degraded, timed-out and cancelled answers.** Enforced where entries are stored rather than at
  each call site, so no path can forget it. Otherwise one moment's trouble would be replayed as a
  persistent fault.
- **Anything at all, when the fingerprint cannot be read.** "I could not check whether anything
  changed" is not evidence that nothing did, so the cache stands aside for that request.

The cache key is the whole request — query text, `top_k`, `offset`, `threshold`,
`include_partial_segments`, the LLM scoring settings, the presentation flags — plus a SHA-256 of
the presented bearer token, never the token itself. One shared token costs nothing in hit rate;
several partition the cache automatically rather than letting one caller read another's answers.
`timeout_ms` is deliberately not part of it: a deadline does not change the correct answer, and
nothing from an unfinished search is ever stored.

Size it in entries, not bytes: `excalibur.query.cache.max-entries`, default 1024. Watch
`queryCache` on `/v1/stats` — `evictions` climbing means it is too small for the traffic, while
`invalidations` climbing means the corpus is moving faster than the same question comes back, and
no size fixes that one.

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

### List what the node holds

The two routes above read a chunk whose identifier you already have. This one produces the
identifiers. It reads the document registry — the record of what was ingested, under which chunk
ids, which is also what change detection and `POST /v1/erasure` resolve against — so it answers
"what is in this node" for a corpus nobody has a manifest of, including everything ingested before
the last restart.

```bash
curl -G http://localhost:8080/v1/documents/list \
  -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" \
  --data-urlencode "source_id=notes" \
  --data-urlencode "limit=2"
```

```json
{"documents": [
   {"source_id": "notes", "document_id": "appendix.md", "chunk_count": 1,
    "chunk_ids": ["notes:appendix.md:chunk:0"], "content_length": 1840,
    "content_hash": "913472233", "last_modified_ms": 1757808000000,
    "indexed_at_ms": 1757808123456},
   {"source_id": "notes", "document_id": "handbook.md", "chunk_count": 2,
    "chunk_ids": ["notes:handbook.md:chunk:0", "notes:handbook.md:chunk:1"],
    "content_length": 8241, "content_hash": "771002914",
    "last_modified_ms": 1757807000000, "indexed_at_ms": 1757808123999}],
 "total": 7, "returned": 2, "next_cursor": "bm90ZXMgaGFuZGJvb2subWQ"}
```

Pass `next_cursor` back as `cursor` for the following page, and stop when it comes back `null` —
that is the only termination condition to check. A cursor rather than an offset because the
registry is written to while it is read: an offset into a set that is growing repeats and skips
documents between two requests that asked for adjacent pages. The cursor is opaque; do not
construct one.

`total` counts the whole filtered set rather than the page, so a caller knows how far it has to
walk before it starts.

`content_hash` is a string, not a number. It is a 64-bit xxHash and spans the whole positive
`long` range, and a JSON parser backed by doubles — JavaScript, `jq` by default — silently rounds
anything past 2^53. A reconciler comparing a rounded hash against a recomputed one re-ingests the
same documents forever.

**Parameters** — `source_id` narrows to one source; an id nothing is registered under is an empty
page rather than a `404`, because documents outlive the registration that created them (`DELETE
/v1/ingest` keeps them), so "this source holds nothing" is an answer. A blank `source_id=` is no
filter at all. `limit` defaults to 100 and is capped at 1000.

**Bound** — a page also ends at 20 000 chunk ids, whichever comes first. A document may hold
thousands of chunks, so a page counted only in documents bounds nothing; `returned` below the
`limit` you asked for with a non-null `next_cursor` means the chunk budget cut the page, not that
the walk is over. A single document over the whole budget is still returned on its own, so the
walk always advances.

**Error — `400 Bad Request`** — a `limit` above the cap or not a whole number, or a `cursor` this
node did not issue. A limit above the cap is refused rather than trimmed: a caller paging by the
limit it asked for would otherwise skip the documents it never received.

**Error — `404 Not Found`** — the node keeps no document registry, so it has no record to read
back. An empty listing would be indistinguishable from an empty corpus.

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
- `indexerRunning` — whether the worker that drains the HNSW integration queue is alive. Reported beside the verdict rather than folded into it, because `live`, `ready` and `healthy` mean what they are documented to mean above and clients already parse them. If this is `false` the node is indexing nothing while everything else here still reads healthy; `/health/ready` is the route that turns that into a status code.

### Readiness check

`/health` answers `200` whatever it finds, so a `httpGet` probe pointed at it can only ever
conclude that the process is alive. `/health/ready` answers the other question, with a status code
an unmodified Kubernetes probe or load balancer health check acts on.

**Request**

```bash
curl -i http://localhost:8080/health/ready
```

**Response — `200 OK` when this node can serve**

```json
{
  "ready": true,
  "failed": [],
  "draining": false,
  "coverage": 1.0000,
  "indexerRunning": true,
  "diskAvailableRatio": 0.4200
}
```

**Response — `503 Service Unavailable` when it cannot**

```json
{
  "ready": false,
  "failed": ["drained", "disk"],
  "draining": true,
  "coverage": 1.0000,
  "indexerRunning": true,
  "diskAvailableRatio": 0.0100
}
```

`failed` names every check that did not pass, so the `503` says what to do about itself:

| Check | Fails when | What it catches that nothing else does |
|---|---|---|
| `drained` | `POST /v1/drain` was called | An operator taking the node out of rotation before a restart |
| `indexer` | the index orchestrator is not running | A dead integration worker: the queue grows while coverage stays `1.0000` |
| `disk` | free volume space is below `excalibur.health.min-free-disk-ratio` (default `0.05`) | A node that can no longer write the replacement segment a compaction or a rolling reindex produces |
| `coverage` | `coverage < excalibur.health.readiness-threshold` | Segments stranded in a state no query reads |
| `unreadable` | this node's own state could not be read at all | A node too broken to answer the question, reported as `503` rather than `500` — a probe reads a `500` from a readiness route as "the probe is broken" and keeps sending traffic. It is the only label that appears alone, without the measurements beside it |

Use it as the readiness probe and `/health` as the liveness probe:

```yaml
livenessProbe:
  httpGet: { path: /health, port: 8080 }
readinessProbe:
  httpGet: { path: /health/ready, port: 8080 }
```

A node with no corpus yet reports `503` with `coverage` in `failed`. That is the same verdict
`/health`'s `ready` field has always given, now with a status code attached: a node holding nothing
a query can read should not be receiving query traffic.

The free-disk floor is `excalibur.health.min-free-disk-ratio`, 5% by default. It is not a "disk is
filling up" warning — `volumes` on `/v1/stats` is that — it is the point past which a rewrite can no
longer be relied on to complete, so size it against the largest segment a compaction will rewrite:
volumes that need more headroom than that raise it, and a deployment whose volumes sit deliberately
full lowers it rather than living with a node that reports itself out of rotation while working.

### Drain

Tell a load balancer to stop choosing this node, without stopping the node. Do this before a
rolling restart or a manual compaction; without it the in-flight requests at the moment of
shutdown are dropped.

```bash
# Take it out of rotation
curl -X POST -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" \
  'http://localhost:8080/v1/drain?reason=rolling%20restart'
# {"draining": true, "sinceMs": 1736899200000, "reason": "rolling restart"}

# ... wait for in-flight work to finish, then restart ...

# Put it back
curl -X DELETE -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" \
  http://localhost:8080/v1/drain
```

A drained node keeps serving every request it is sent. Only `/health/ready` changes, because the
traffic still arriving during a drain is the traffic that has not noticed yet, and refusing it
would cause the outage the drain exists to avoid. Both verbs are idempotent, and the flag is not
persisted: a restart clears it.

### Version

```bash
curl -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" http://localhost:8080/v1/version
```

```json
{
  "name": "ExcaliburDB",
  "version": "1.0.0-SNAPSHOT",
  "jvm": "21.0.5",
  "startedAtMs": 1736899200000,
  "uptimeMs": 43200000
}
```

`version` reads `Implementation-Version` from the jar manifest, so it is `unknown` when the node
was launched from a directory of class files rather than from the packaged jar. The same line is
printed at startup.

### Queries in flight

```bash
curl -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" \
  http://localhost:8080/v1/query/status
```

```json
{
  "active": 2,
  "registered": 1841,
  "cancelled": 3,
  "queries": [
    {"query_id": "1f0c...", "correlation_id": "checkout-42",
     "started_at_ms": 1736899200000, "duration_ms": 91400, "cancelled": false}
  ]
}
```

Longest-running first, so the query that will not finish is the first line. Its `query_id` is what
`DELETE /v1/query/{query_id}` takes — before this route there was no way to learn an id you had not
issued yourself. At most 100 queries are listed; `truncated` counts the rest.

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
  "volumes": {"count": 1, "totalBytes": 5368709120, "usedBytes": 3221225472,
              "availableBytes": 2147483648},
  "hnsw": {"queueDepth": 50000, "queueDepthLimit": 100000, "vectorsIntegrated": 14000000},
  "cuckoo": {"pendingDeletions": 100, "totalDeletions": 5000,
             "avgFpRate": 0.000500, "maxFpRate": 0.001000,
             "saturated": false, "saturatedSegments": 0},
  "visibility": {"epoch": 42, "registeredSegments": 1024, "visibleSegments": 1024},
  "coverage": 1.0000,
  "workers": {"indexOrchestratorRunning": true},
  "vectorIndex": {"segmentsWithoutIndex": 0},
  "breakers": {"embedding": "CLOSED", "llmRelevancy": "CLOSED"},
  "lexical": {
    "engine": "native", "indexed": 15000000, "segments": 12,
    "liveChunks": 14980000, "erasedChunks": 20000, "erasedRatio": 0.0013,
    "erasureRequests": 3, "oldestErasureAgeMs": 412000, "compactionDue": false
  },
  "queryCache": {
    "hits": 8412, "misses": 2190, "evictions": 0, "invalidations": 1877,
    "size": 1024, "capacity": 1024, "hitRatio": 0.7934
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

The three blocks after `coverage` are the states this node keeps serving through, and none of them
shows up anywhere else:

- `workers.indexOrchestratorRunning` — `false` means the HNSW integration worker is dead. Nothing
  is being integrated, `hnsw.queueDepth` grows without bound, and `coverage` and `/health` both
  stay exactly as they were.
- `vectorIndex.segmentsWithoutIndex` — segments that came up without their vector index. Zero is
  the only healthy value. Such a segment stays in service with its key-value data intact, so a
  vector search over it is neither an error nor empty: it is *short*, by whatever that segment
  held. When it is non-zero, `reasons` names each segment and why (at most 32 of them, then
  `truncated`), because a dimension mismatch and an unreadable file need different answers.
- `breakers` — one entry per circuit breaker this node has, each `CLOSED`, `HALF_OPEN` or `OPEN`,
  and each omitted where there is no such component rather than reported as healthy. The two
  breakers guard different things and either can be open while the other is closed.
  `breakers.embedding`: when it is open the node has stopped calling its embedding provider and
  retrieval has silently fallen back to lexical-only. `breakers.llmRelevancy`: when it is open
  stage-5 re-ranking is bypassed and the answer keeps the ranking it already had. Both leave
  queries answering `200` with worse results and nothing in the response saying so, which is the
  whole reason they are reported here. On `/metrics` both appear as
  `excalibur_circuit_breaker_state` under a `breaker` label, so one alert on `> 0` covers each new
  breaker without being rewritten.

`queryCache` appears only on a node with `excalibur.query.cache.enabled=true`. Read `evictions`
and `invalidations` separately rather than together: an eviction is the cache being too small for
the traffic, which a larger `excalibur.query.cache.max-entries` fixes, and an invalidation is the
corpus having moved under an entry, which no size would have helped. A node with a low
`hitRatio` and near-zero `evictions` is not under-sized — it is being written to faster than the
same question comes back, and the cache is not the right tool for it.

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

### Metrics

`GET /metrics` serves the Prometheus text exposition format. A scrape config needs the bearer
token, which every scraper supports:

```yaml
scrape_configs:
  - job_name: excaliburdb
    authorization:
      credentials_file: /etc/prometheus/excalibur-token
    static_configs:
      - targets: ['excalibur:8080']
```

```bash
curl -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN" http://localhost:8080/metrics
```

Everything exported is a figure the node already measures; nothing here is estimated or derived.

| Family | Type | What it answers |
|---|---|---|
| `excalibur_query_latency_milliseconds{quantile=...}` | summary | p50, p90, p95, p99, p99.9 and `_count`. No `_sum`: the histogram reconstructs its mean from bucket positions, and an estimate published as an exact total is worse than an absent one |
| `excalibur_http_responses_total{class="2xx".."5xx"}` | counter | Request rate and error rate. Counted where every response body is written, so a route added later is instrumented by existing |
| `excalibur_http_rejections_total{reason="throttled"\|"unauthenticated"}` | counter | Rate-limit and capacity refusals, and authentication failures — both of which previously only reached the log |
| `excalibur_queries_executed_total`, `excalibur_entries_ingested_total`, `excalibur_ingestion_rate_entries_per_second` | counter, gauge | Query and ingestion throughput |
| `excalibur_queries_active`, `_registered_total`, `_cancelled_total` | gauge, counter | What is running now; the same figures `/v1/query/status` serves |
| `excalibur_segments{state=...}`, `excalibur_segments_total`, `excalibur_entries_total` | gauge | The segment lifecycle and corpus size |
| `excalibur_coverage_ratio`, `excalibur_visibility_epoch`, `excalibur_visible_segments` | gauge | Visibility |
| `excalibur_hnsw_queue_depth`, `_limit`, `excalibur_hnsw_vectors_integrated_total` | gauge, counter | Vector integration backlog |
| `excalibur_cuckoo_*` | gauge, counter | Membership filter saturation and false-positive rates |
| `excalibur_volume_bytes_total`, `_used`, `_available`, `excalibur_volume_count` | gauge | Disk |
| `excalibur_index_orchestrator_running`, `excalibur_segments_without_vector_index`, `excalibur_circuit_breaker_state{breaker="embedding"}` | gauge | The three partial failures described under Stats. The breaker is `0` closed, `1` half-open, `2` open, so `> 0` is "this node is not using its embedding provider" |
| `excalibur_lexical_*` | gauge | Chunk index size and the erasure backlog, including `_oldest_erasure_age_seconds` |
| `excalibur_query_cache_lookups_total{result="hit"\|"miss"}`, `_evictions_total`, `_invalidations_total`, `excalibur_query_cache_entries`, `_capacity` | counter, gauge | Present only on a node with `excalibur.query.cache.enabled=true`. Hits and misses share one family so a hit ratio cannot be computed from a stale pairing. Evictions and invalidations are separate because they call for different actions: evictions mean the cache is too small for the traffic and `max-entries` is the answer, invalidations mean the corpus moved and no size would have helped |
| `excalibur_node_draining`, `excalibur_uptime_seconds`, `excalibur_build_info{version,jvm}` | gauge | Whether an operator has drained the node, how long it has been up, and which build it is |

Three alerts worth having, in ascending order of how quietly the condition fails:

```
rate(excalibur_http_responses_total{class="5xx"}[5m]) > 0.01 * rate(excalibur_http_responses_total[5m])
excalibur_index_orchestrator_running == 0
excalibur_segments_without_vector_index > 0
```

#### JMX

The same figures are also published as MBeans under the `io.excalibur` domain, for a local
VisualVM or JConsole session attached to the process. JMX is **not** reachable from outside the
host unless the JVM was started with the flags below, and none of the packaged start scripts set
them — RMI's callback address does not survive a container's port mapping either. `/metrics` is
the supported remote route, and JMX is worth enabling for one thing `/metrics` cannot do: change
the log level on a running node (see *Enabling debug logging*).

```bash
JAVA_OPTS="-Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9010 \
  -Dcom.sun.management.jmxremote.rmi.port=9010 \
  -Dcom.sun.management.jmxremote.local.only=true \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false" bin/excalibur-api
```

`local.only=true` with authentication off is the combination to ship: it is reachable over an SSH
tunnel or `kubectl port-forward` and not from the network. An unauthenticated JMX port that *is*
reachable is remote code execution, so if you open it, turn authentication and TLS on with it.

### Logs

Logback is pre-configured with JSON structured logging via logstash-encoder. Point your log shipper at stdout.

### Backups

Every byte of a node's state lives under its data directory — segments, write logs, the segment
catalog, the routing directory, the chunk index, the graph, the document registry and the source
catalog. Nothing is kept anywhere else, so backing the node up is copying that one directory and
restoring it is putting the directory back.

`excalibur-backup` does that copy and, unlike `cp`, can prove afterwards that it worked.

```bash
./gradlew :excalibur-backup:installDist
B=excalibur-backup/build/install/excalibur-backup/bin/excalibur-backup

# 1. Stop the node and wait for the process to exit. Match on the main class, not on the jar
#    name: a node started through bin/excalibur-api or ./gradlew :excalibur-api:run has no
#    "excalibur.jar" on its command line, so a pattern matching that silently selects nothing.
pid="$(pgrep -f excalibur.api.ExcaliburApplication)" || { echo "no node found"; exit 1; }
kill -TERM "$pid"
while kill -0 "$pid" 2>/dev/null; do sleep 1; done

# 2. Take the image.
$B snapshot --data-dir /var/lib/excalibur/data --out /backups/excalibur-$(date +%F)

# 3. Restart.
EXCALIBUR_DATA_DIR=/var/lib/excalibur/data java --enable-preview \
    --enable-native-access=ALL-UNNAMED -jar excalibur.jar
```

The image is self-describing: the payload, plus a manifest naming every file with its length,
its CRC32C and its SHA-256, the format versions, and the epoch the image is coherent at. Verify
it before you need it, not after:

```bash
$B verify --snapshot /backups/excalibur-2026-09-14 --deep
```

`verify` checks in both directions — every named file must be present and match, and every
present file must be named — so a file added to or removed from the image is caught as well as
one that rotted. `--deep` additionally compares SHA-256 and hands each file to the engine's own
integrity checker for its format, which is what turns "these bytes are unaltered" into "these
segments and graphs will open".

Restoring verifies in full first, builds the tree beside the target, and renames it on in one
move:

```bash
$B restore --snapshot /backups/excalibur-2026-09-14 --target /var/lib/excalibur/data
```

An image that does not verify is refused and the target is left exactly as it was — exit 3, and
nothing written. A target that already holds something needs `--force`, because a restore
replaces a data directory wholesale rather than merging into one. Afterwards, start the node
against it: it rediscovers the segments from the catalog, replays each write log, and serves the
same answers the node it was taken from served. No import step and no reindex.

**Take the image with the node stopped.** The state is spread over many files, written two
different ways: the catalog, the manifests and the registries are replaced by an atomic rename,
while the segments, their write logs and the HNSW graphs are written in place through their
mappings. Nothing orders one file against another, and nothing locks the directory. A copy taken
while the node is writing can catch a catalog naming a segment the copy does not yet hold, or a
mapping caught half-flushed. A clean `SIGTERM` shutdown is the only moment at which every file
describes the same instant.

The tool enforces this as far as it can: it fingerprints the directory before and after the copy
and refuses an image whose source moved underneath it, with exit 5. That check is one-sided — it
can prove a node *was* running, never that one was not — so `--allow-live` downgrades the refusal
to a warning and stamps the image `LIVE_UNSAFE` rather than claiming a consistency it cannot
establish. There is no online-backup mode. Stopping the background workers while the write path
kept accepting writes would produce something that looked like a consistent image and was not,
which is worse than having none.

If a hot copy is the only option, take it knowing what it is worth. Every write-log append is
fsynced before it is acknowledged, so the acknowledged writes are on disk; what an inconsistent
copy loses is whatever the catalog and the segments disagree about, not the corpus.

`--exclude-derived` leaves out what something will rebuild — the lexical chunk index, a tenant's
built edge store, the quantiser codebooks — at the cost of a reindex and a graph build on the
far side. The HNSW graphs and their document mappings are never left out, derived though they
are, because nothing rebuilds them: deleting a `.hnsw` gives an empty graph attached to a
populated segment, not a repopulated one. The write-ahead logs are never left out either.

**If you copy the directory by hand instead**, two things the tool handles that `cp` and `rsync`
do not:

- **Preserve sparseness.** Write-ahead logs are mapped at 64 MiB and HNSW graphs at 755 MiB
  apiece, so a node holding 50 MB of data occupies 50 GB apparent. `cp -a --sparse=always`, or
  `rsync -S`; without it you write gigabytes of zeroes.
- **Copy the `.compact` files, and never one without the other.** An HNSW compaction stages its
  replacement as `<graph>.hnsw.compact` and `<graph>.hnsw.compact.docmap`, then renames them into
  place in that order. Those two files are the crash-recovery record: on the next open, a staged
  graph still present means the swap never started and both are discarded, and a staged mapping
  alone means the graph was already replaced and the mapping rename has to be finished. A filter
  that excludes `*.compact` but keeps `*.compact.docmap` turns a recoverable interruption into a
  graph whose node numbering and document mapping disagree, which answers with the wrong
  document rather than failing.

### Checking a data directory

`excalibur-fsck` verifies a stopped node's files against their checksums and reports what it
finds. Use it when a node will not start, after a crash or a full disk, and before trusting a
directory you restored by hand.

```bash
./gradlew :excalibur-fsck:installDist
excalibur-fsck/build/install/excalibur-fsck/bin/excalibur-fsck /var/lib/excalibur/data
```

`excalibur-fsck --help` prints the flags and the exit codes and does nothing else, so it is safe
to run against a node you are unsure about.

No file is opened for writing. Most are mapped read-only — enforced by the kernel, because
several of the engine's own open paths stamp headers or migrate formats on the way in — and the
rest are streamed past a digest without being mapped at all, which is what lets a file too
damaged to open still be judged. Each is reported as one of:

| Status | Means |
|---|---|
| `INTACT` | Bytes were weighed against a stored checksum and matched. The only status that is a claim of soundness |
| `CORRUPT` | Bytes were weighed against a stored checksum and did not match |
| `UNSEALED` | A writer still holds it. Deliberately not the same statement as intact |
| `LEGACY_UNPROTECTED` | Structure proven whole, but the format carries no checksum, so the contents cannot be verified |
| `UNCHECKED` | Not a format this tool knows, or one with no read-only replay. Not evidence of damage and not evidence of health |
| `MISSING` / `UNREADABLE` | Named but absent, or a device or permission problem |

A file can also be `INTACT` and still only *partly* verified — an older segment whose entries
carry checksums but whose slot table does not is the case that matters. Calling it
`LEGACY_UNPROTECTED` would be false, since its entries genuinely were weighed, so it keeps
`INTACT` and the shortfall is counted on its own axis and named in the detail line.

The unverifiable counts are all reported separately and described in words, because "no damage
found" over a directory half of which could not be checked is not the same answer as one over a
directory that could. `--strict` exits non-zero on any of them, partly-verified included, which
is how you ask for proof rather than the absence of doubt. `--json` emits the whole report as
one document.

It also checks across files: a catalog row naming a segment that is not there, an index with no
segment, a staged file a rename never published.

Repair is off by default — without `--repair` the plan is printed and nothing is touched — and
**no repair ever deletes anything**. A damaged file is moved into a quarantine directory byte for
byte, so every action is reversible by hand. What it refuses to attempt matters as much:

| Left for an operator | Why |
|---|---|
| A damaged `catalog.dat` | Segment files are rediscovered on start-up, but lifecycle state, integrated offsets, source ids and epochs live only there. A node that starts without them looks like a first start |
| A damaged `routing.dir`, or a slot pointing at a missing segment | Which hash slot belongs to which segment after a split is not derivable from file names. That is why the directory is written down |
| A write-ahead log damaged in the middle | The records behind the damage were fsynced and acknowledged. Trimming past them would destroy committed writes, and so would moving the file aside — nothing rebuilds a log the way a reindex rebuilds an index |
| Anything `UNREADABLE` | A device or permission problem, where nothing is known about the contents |

Exit codes: `0` no damage, `1` damage found, `2` the command line was wrong, `3` the check itself failed — which says nothing about the directory and must not be read as `0`.

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
| A node answers `200` to everything but its results are getting worse | A circuit breaker has opened: the embedding one drops retrieval to lexical-only, the relevancy one bypasses stage-5 re-ranking | `breakers` on `/v1/stats`, or `excalibur_circuit_breaker_state` on `/metrics`. Alert on `> 0` for any `breaker` label |
| `hnsw.queueDepth` grows without bound while `coverage` stays `1.0000` and `/health` stays healthy | The index orchestrator is dead | `workers.indexOrchestratorRunning` on `/v1/stats`, `indexerRunning` on `/health`, and `/health/ready` answers `503` with `indexer` in `failed` |
| Vector search returns fewer results than the corpus should hold, with no error | One or more segments came up without their vector index and are serving key-value data only | `vectorIndex.segmentsWithoutIndex` on `/v1/stats` names each segment and why |
| A rolling restart drops in-flight requests | The load balancer was still choosing the node when it stopped | `POST /v1/drain`, wait for in-flight work to finish, then restart. `/health/ready` answers `503` for the duration |
| A source keeps being re-crawled after you erased its documents | Document-scoped erasure does not deregister the source; only `scope: "source"` does | `DELETE /v1/ingest` to stop the crawl, or erase with `scope: "source"` |
| `POST /v1/erasure` returns 404 | The route is not registered, which the packaged server never does - it wires an erasure service on every start, whether or not the ingestion pipeline came up. So this is either a path that is not quite `/v1/erasure`, or a host embedding `ExcaliburDBServer` directly and never calling `withDocumentErasure` | Check the startup log's registered-endpoint line: it names `/v1/erasure` when the route is there |
| `/v1/erasure` reports `documents_not_found` for a document you can query | Its registry entry is gone, so the chunk ids it named are unknown; the chunks are still in storage | Reindex the source, which re-registers the document, then erase it |
| A source stops being re-crawled after a restart, and `/v1/ingest/status` does not list it | Its target is no longer allow-listed, so restoring it was refused; the startup log names it | Put the root or host back on `excalibur.ingest.allowed-roots` / `excalibur.ingest.allowed-hosts`, or `DELETE /v1/ingest` if the source is finished |
| A re-crawl reingests everything and tombstones nothing | `<data dir>/document-registry` is missing, so change detection reads every document as new | Restore the data directory whole; the registry belongs with the segments it describes |
| Node fails to start with `RegistryLoadException` naming a record count | `<data dir>/document-registry` was cut short by a crash, so it omits documents the node is still serving. It is refused rather than loaded, because a shorter registry silently makes those documents unerasable and unreindexable | `excalibur-backup restore` from the last image. Deleting the registry also starts the node, at the cost of a full reingest on the next crawl |
| A node refuses to start after a crash or a full disk, naming a file | Something under the data directory is damaged. Which file and how badly is the question, and guessing at it is how a recoverable node gets deleted | Stop the node and run `excalibur-fsck <data dir>`. It reports every file read-only and names what is damaged; `--repair` quarantines what can be moved aside safely and leaves the catalog and the routing directory for you |
| A directory you restored by hand serves wrong or missing results | A partial copy: a `.hnsw` without its `.docmap`, a `*.compact` without its pair, or a sparse copy that dropped a file | `excalibur-fsck <data dir>` names the orphans and the mismatches. Take images with `excalibur-backup` instead, which classifies every file and can verify the result |
| A `WARN` at startup naming documents that "were being ingested when this node last stopped" | Those documents were half written when the node died, and their chunks are hidden until the ingest is redone. Not an error: it is the crash being cleaned up after | Nothing — the next crawl of that source re-ingests them and they become visible again. `POST /v1/erasure` removes them instead |
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

Catalogs written by a build that had the marker but no version header need nothing: the header's
absence is what identifies them, since a crash takes the tail of a file and never its first line.
Such a catalog loads, its count is still checked, and the next registration rewrites it with a
version.

**`<data dir>/document-registry` — no step required.** The registry now carries the same header and
end marker, for the same reason: without them a registry cut short by a crash is a file of whole
records that quietly omits documents, leaving their chunks in storage where no erasure or reindex
can name them. A registry from an older build has neither line, is read as the older format, and is
rewritten with a version and a count at the next save — which the node performs on its own, without
waiting for something to be ingested. From then on a short registry fails the start instead of
loading, and the message names the count it expected.

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

At startup, set `EXCALIBUR_LOG_LEVEL`:

```bash
EXCALIBUR_LOG_LEVEL=DEBUG java --enable-preview --enable-native-access=ALL-UNNAMED \
    -jar excalibur.jar
```

On a node that is already running, change it over JMX. The shipped `logback.xml` declares
`<jmxConfigurator/>`, which registers `ch.qos.logback.classic:Name=default,Type=ch.qos.logback.classic.jmx.JMXConfigurator`;
its `setLoggerLevel` operation takes effect on the next log call, and the `LevelChangePropagator`
carries the change onto the `java.util.logging` loggers the storage engine writes through, so the
two halves do not drift apart. This needs the JMX flags from the section above, and it is the
reason they are worth setting: raising the level to diagnose a live incident otherwise costs a
restart, which destroys the state being diagnosed.

To replace the configuration wholesale, pass your own file:
`-Dlogback.configurationFile=/path/to/logback-debug.xml`. Do **not** try to edit the shipped
`logback.xml` — it lives inside `excalibur.jar`. Its `scan="true"` never fires either: logback
watches only `file:` and `http:` URLs, and a `jar:` URL is not one.

---

## Programmatic Usage (JVM Embedded)

You can embed ExcaliburDB directly in a JVM application.

**Nothing is on Maven Central.** `./gradlew publishToMavenLocal` installs all sixteen library
modules into `~/.m2`, so a coordinate dependency resolves there. The other five are not libraries:
`excalibur-bench` is a benchmark harness, `excalibur-integration` and `excalibur-test-bootstrap`
are test modules, and `excalibur-fsck` and `excalibur-backup` are commands rather than something
to depend on.

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

- **Call `ForeignMemoryBootstrap.ensureInitialized()` as the first statement of your startup,
  before anything spawns a thread.** Two threads reaching `java.lang.foreign` for the first time
  at the same instant deadlock the JVM inside the JDK's own class-initialization monitors, and a
  thread waiting on one of those cannot be interrupted, so nothing times out and the process
  hangs rather than fails. The call is idempotent and costs about 34ms once. The server does this
  in `ExcaliburApplication.main`; an embedder has no such entry point, so it has to do it itself.
  Two threads is all it takes - storage, query, retrieval, graph and shard components hand work
  to virtual threads, so one sequential caller that starts a worker is already a race.
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
