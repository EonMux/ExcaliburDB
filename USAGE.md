# ExcaliburDB Usage Guide

A disk-backed search engine for document corpora: BM25 over its own inverted index on every
node, vector search fused with it on a node that has an embedding model, and LLM-based relevancy
scoring on top.

## TL;DR — Quick Start

```bash
# 1. Build the fat JAR
./gradlew :excalibur-api:shadowJar

# 2. Set your LLM API key (required for embeddings/scoring)
export EXCALIBUR_LLM_API_KEY=sk-your-openai-key

# 2b. Allow-list the directory you want indexed. Empty means DISABLED, not unrestricted,
#     so without it, step 4 is answered with 403.
cp excalibur.properties.example excalibur.properties
echo 'excalibur.ingest.allowed-roots=/absolute/path/to/docs' >> excalibur.properties

# 3. Run the server (listens on :8080)
java --enable-preview --enable-native-access=ALL-UNNAMED \
     --add-modules=jdk.incubator.vector \
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

**Prefer the distribution to the bare fat JAR for anything but a quick try.** A plain
`java -jar excalibur.jar` needs three flags on every invocation. Every module is compiled with
`--enable-preview`, and a preview class file is refused outright by a JVM that was not started with
the same flag, so without it the launch fails with `UnsupportedClassVersionError`.
`--enable-native-access=ALL-UNNAMED` is for the Panama FFM calls the storage engine makes, and
`--add-modules=jdk.incubator.vector` for the SIMD distance kernels, which without it fall back to
scalar arithmetic without a word and make every search several times slower. The generated
`bin/excalibur-api` carries all three, along with the out-of-memory flags — a heap dump written to
the working directory, then an exit rather than a JVM limping on — so the launch cannot be got
wrong. Start it from a working directory that is not the data directory, or add
`-XX:HeapDumpPath=<dir>` to `JAVA_OPTS`, which is applied after the defaults and so wins: a dump
is the size of the heap, and in the data directory it is a file every snapshot has to leave out
and every check has to explain.

```bash
excalibur-api/build/install/excalibur-api/bin/excalibur-api --config /etc/excalibur.properties
```

`JAVA_OPTS` is passed through for anything extra (heap sizing, JMX), and `EXCALIBUR_*`
environment variables work the same way they do for the jar.

## Running the Server

### Basic run

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED \
     --add-modules=jdk.incubator.vector \
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
     --add-modules=jdk.incubator.vector \
     -jar excalibur.jar --config /path/to/excalibur.properties
```

### Override the port

```bash
EXCALIBUR_PORT=9090 java --enable-preview --enable-native-access=ALL-UNNAMED \
     --add-modules=jdk.incubator.vector \
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
# The node drains in-flight queries and closes storage durably on SIGTERM; give it room to:
# excalibur.api.shutdown-timeout-seconds (30 by default) plus 10.
KillSignal=SIGTERM
TimeoutStopSec=40
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
  -v excalibur-diagnostics:/var/lib/excalibur-diagnostics \
  -e EXCALIBUR_API_BIND_ADDRESS=0.0.0.0 \
  -e EXCALIBUR_API_AUTH_TOKEN="$TOKEN" \
  -e EXCALIBUR_API_ALLOW_PLAINTEXT=true \
  -e EXCALIBUR_LLM_API_KEY="$OPENAI_API_KEY" \
  --stop-timeout 40 \
  excalibur
```

A node bound to `0.0.0.0` will not start with a token alone: it also needs TLS, or an explicit
statement that something in front of it provides TLS. `EXCALIBUR_API_ALLOW_PLAINTEXT=true` is that
statement, and it is the right one only behind an ingress or sidecar that terminates TLS — without
it, or the TLS keystore settings, the node refuses to start rather than accept the bearer token in
cleartext over the network. To terminate TLS in the node itself, set `EXCALIBUR_API_TLS_KEYSTORE`
and `EXCALIBUR_API_TLS_KEYSTORE_PASSWORD` instead and drop the plaintext variable.

The `Dockerfile` in the repository root builds the shadow jar and runs it on a Temurin 21 JRE as
uid `10001`, with the jar owned by `root` and read-only to the process. Nothing is baked in:

| | |
|---|---|
| Data | `/var/lib/excalibur`, declared as a `VOLUME`. `EXCALIBUR_DATA_DIR` already points at it |
| Reachability | The API binds `127.0.0.1` by default. Serving from outside the container needs `EXCALIBUR_API_BIND_ADDRESS=0.0.0.0`, `EXCALIBUR_API_AUTH_TOKEN`, and either the TLS keystore settings or `EXCALIBUR_API_ALLOW_PLAINTEXT=true` behind something that terminates TLS |
| Secrets | None are in the image. Pass `EXCALIBUR_LLM_API_KEY` at run time, or mount a properties file and point `--config` at it — arguments after the image name reach the application |
| Heap | `-XX:MaxRAMPercentage=60.0`, so the same image is right under any `docker run -m`. Not 75%: segments live in mapped arenas outside the heap, and a heap that crowds out the page cache turns every search into a disk read |
| Heap dumps | Written on an out-of-memory exit to `/var/lib/excalibur-diagnostics`, not the data directory. Mount a volume there to keep one past `docker rm` |
| Operator tools | `excalibur-fsck` and `excalibur-backup` are on the `PATH`. Run them with the node stopped, against the same volume; a repair needs `/var/lib/excalibur-quarantine` mounted, and a snapshot `/var/lib/excalibur-backups` — see below |
| Health | `HEALTHCHECK` polls `/health/ready` with a 90s start period, which covers segment recovery on a large data directory |
| Stop | The JVM is pid 1, so `SIGTERM` reaches the shutdown hook. The hook publishes the chunk index's buffer, which is the tail of an ingestion run, and closes the storage engine. It does all of this within `excalibur.api.shutdown-timeout-seconds` (30 by default). Give it that plus 10: `--stop-timeout 40`, or `terminationGracePeriodSeconds: 40`, and more if you raise the setting |

Bind-mounting a host directory instead of a named volume means `chown 10001:10001` on it first;
the process does not run as root and will not be able to create its catalog otherwise. The same
goes for the three other directories the image owns for its user — diagnostics, quarantine and
backups. None of the three is a declared `VOLUME`, deliberately: an unmounted declared volume is an
anonymous one, which `docker run --rm` deletes with the container, taking a repair's quarantined
files or a fresh snapshot with it. Mount each one by name when it is used:

```bash
docker stop excalibur

# Check the stopped node's directory; without --repair nothing is written.
docker run --rm -v excalibur-data:/var/lib/excalibur \
  --entrypoint excalibur-fsck excalibur /var/lib/excalibur

# Repair it. The default quarantine is a new directory in /var/lib, which uid 10001 cannot
# create, so the image's own quarantine directory is named and mounted.
docker run --rm -v excalibur-data:/var/lib/excalibur \
  -v excalibur-quarantine:/var/lib/excalibur-quarantine \
  --entrypoint excalibur-fsck excalibur \
  /var/lib/excalibur --repair --quarantine-dir=/var/lib/excalibur-quarantine

# Snapshot it into a backup volume, then verify the image.
docker run --rm -v excalibur-data:/var/lib/excalibur \
  -v excalibur-backups:/var/lib/excalibur-backups \
  --entrypoint excalibur-backup excalibur \
  snapshot --data-dir /var/lib/excalibur --out /var/lib/excalibur-backups/$(date +%F)

docker start excalibur
```

The data and the quarantine are two mounts, which no rename can cross, so `excalibur-fsck` copies
each file it sets aside, forces the copy to disk and compares it byte for byte before removing the
original.

### Taking a node out of rotation

Before a restart, an upgrade, or a manual compaction, tell the load balancer to stop sending
work — `POST /v1/drain` makes `/health/ready` answer `503` while the node keeps serving what it
already has, and `DELETE /v1/drain` puts it back. Point the readiness probe at `/health/ready`,
not `/health`: `/health` answers `200` whatever its `ready` field says, because it is also the
liveness probe and an unready node is not a dead one.

### Shutdown

Send `SIGTERM` (or `Ctrl+C`). The server performs a graceful shutdown, waiting for in-flight queries and stopping the index orchestrator cleanly.

Work that rewrites data is asked to stop at its next safe point rather than interrupted: an on-demand compaction, the erasure sweep, and an erasure request made with `"compact": true` all end without publishing half a rewrite. The chunk lexical index's buffer — the tail of an ingestion run — is published on every shutdown, even when the index's files must stay open. If anything still reading storage outlives its wait, the node logs an ERROR naming it and leaves the storage engine (and, when that is what is still running, the chunk index) open rather than closing them underneath it, which could crash the JVM. The process still exits; the next start replays the write log and loses nothing that was acknowledged.

---

## Configuration

Configuration comes from three sources, each overriding the one before it:

1. Built-in defaults
2. **One** properties file
3. Environment variables prefixed with `EXCALIBUR_`

Step 2 is one file, not a chain. The path is whatever `--config <path>` gives, defaulting to
`excalibur.properties`; it is read from the filesystem if it exists there, and otherwise from the
classpath under the same name — the jar carries its own `excalibur.properties`, so a node started
with no file beside it runs on that one. A file on the classpath does **not** layer underneath a
filesystem one — the first that resolves is the only one read. A path named with `--config` that
cannot be read refuses to start, because starting on the packaged defaults would run a node nobody
configured; only the default path falls back to the built-in defaults, with a warning.

The file is `key=value` lines, split at the first `=`. A line whose first character is `#` or `;`
is a comment, and that is the only kind of comment there is: everything after the `=` is the value,
so `excalibur.api.max-top-k=1000   # ceiling` sets the value `1000   # ceiling` and startup refuses
it as not a number. Put comments on their own line. One pair of surrounding `"` or `'` quotes is
stripped from a value.

Three variables are read directly rather than as overrides of a key: `EXCALIBUR_PORT` (the listen
port, taking precedence over `excalibur.api.port`), `EXCALIBUR_DATA_DIR` (the data directory,
default `./data`; there is no property for it) and `EXCALIBUR_LOG_LEVEL` (the root log level,
default `INFO`). Every other `EXCALIBUR_` variable is the override of the key it names — including
`EXCALIBUR_API_AUTH_TOKEN`, which is `excalibur.api.auth-token` like any other.

A value that is present but unparseable (a bad number, a non-finite double, an unrecognised
boolean) fails startup naming the key, rather than silently falling back to the default. Blank and
absent values still take the default. Booleans under `excalibur.query.*`, `excalibur.ingest.*`,
`excalibur.llm.*`, `excalibur.shard.*` and `excalibur.graph.*` accept `true`/`1`/`yes`/`on` and
`false`/`0`/`no`/`off`; `excalibur.api.allow-plaintext` and `excalibur.api.tls-enabled` accept only
`true` or `false`. Both are case-insensitive.

A value outside its key's range is refused the same way — the README's
[property table](README.md#configuration-options) gives each range — and so are settings that
contradict each other:

- `excalibur.query.top-k-default` above `excalibur.api.max-top-k`, which would make a request
  that leaves `top_k` out one the node refuses;
- `excalibur.visibility.query-timeout-ms` above `excalibur.api.max-query-timeout-ms`, for the
  same reason with `timeout_ms`;
- `excalibur.query.vector-dimensions` differing from `excalibur.llm.embeddings-dimensions`;
- a count under `excalibur.api.*` (`max-top-k`, `max-query-chars`, `max-result-text-chars`,
  `max-oversampling-factor`, `rate-limit-max-clients`) above `2147483647`, the largest the node
  can hold.

A key the node does not recognise — in the file or the environment — is logged as a warning with
the nearest key it does know, since a misspelt key is a setting the operator believes is in force
and is not.

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
# Must equal excalibur.llm.embeddings-dimensions, the embedding model's native width.
excalibur.query.vector-dimensions=1536
excalibur.query.llm-timeout-ms=60000
# Fuse BM25 with vector search on a node that holds both. false gives vector-only ranking,
# and with it a `score` that is a similarity rather than a fused rank sum.
excalibur.query.hybrid-retrieval=true

# HNSW tuning (accuracy vs. speed)
excalibur.shard.default-ef-construction=128
excalibur.shard.default-ef-search=64

# Circuit breakers (fallback to vector-only when LLM is unavailable). Embeddings and each chat
# caller - relevancy scoring, query expansion, relation extraction, the graph rerank, the graph
# question embedding and the graph build's entity embedding - have one of their own, so an outage
# of one does not stop the others. The graph three are shared by every namespace. Each is
# reported under `breakers` on /v1/stats.
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
| `POST` | `/v1/query` | bearer | Search the corpus: BM25, vector, or the two fused, depending on what the node holds |
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

All request/response bodies are JSON. Request size limit: **1 MB**, and a body over it is refused with `413` by every endpoint that reads one - split the payload rather than retrying it. The `GET` routes read no body, and neither do `POST` and `DELETE /v1/drain`,
`DELETE /v1/query/{query_id}`, `DELETE /v1/compact`,
`DELETE /v1/graph/namespaces/{namespace}`, `DELETE /v1/graph/build` or `POST /v1/graph/build`,
so none of them ever produces a `413`.

A method a registered path does not serve is answered `405` on every path, and it is answered
before authentication: the `405` is a routing answer, decided by the path and the method alone, so
it tells an anonymous caller nothing a token would protect — the route table above already says
which methods each path takes. It is still charged, against the read budget, so an anonymous
caller cannot spend routing decisions at line rate for free. That holds for the single-method
routes as much as for the ones that answer two or three methods: `GET /v1/stats`, `GET /metrics`,
`GET /v1/version`, `GET /v1/query/status`, `DELETE /v1/ingest/run`, `GET /v1/ingest/status`,
`GET /v1/documents/list`, `GET /v1/reindex/status`, `POST /v1/erasure`, `GET /v1/compact/status`,
`POST /v1/graph/query`, `GET /v1/graph/build/status` and `POST /v1/graph/erasure` each answer any
other method with `405` rather than `401`, and a wrong method on a mutating route costs a read, not
a mutation. A path that is not registered at all is `404`.

Three routes that change what is registered — `POST /v1/ingest`, `DELETE /v1/ingest` and
`POST /v1/erasure` — answer `503` when the change could not be recorded in the source catalog.
The registration itself is refused in that case rather than applied in memory alone: a source
this node is crawling and has no record of is one that silently stops being crawled at the next
restart.

A field of the wrong JSON type is a `400` naming the field, never a silent default. `{"top_k":
"ten"}` on `/v1/query`, `{"document_ids": "a.md"}` on `/v1/erasure`, `{"source_id": 42}` on
`DELETE /v1/ingest` or `DELETE /v1/ingest/run`, and `{"query": 7}`, `{"context_entities": "Ada"}`
or `{"as_of": 2019}` on `/v1/graph/query` are each refused with the reason in the body rather than
read as though the field had been left out — a request answered `200` after quietly discarding half of
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
it — for every method the path serves; a method it does not serve is the unauthenticated `405`
described above:

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
| `max_file_bytes` | integer | 104857600 | Largest single file read, 1 byte to 100 MiB — the [largest document the node indexes](#document-size-limits). A larger file is never listed or read, so it is neither indexed nor truncated |
| `charset` | string | UTF-8 | The encoding a file is read in when it carries no byte-order mark, by any name or alias the JVM knows (`windows-1252`, `latin1`, `UTF-16LE`). A file that does carry a mark is read in the encoding the mark names, whatever is declared here: the mark was written by the program that encoded that file, the declaration by someone describing the whole directory. An unknown name is a `400`, never a quiet fall-back to UTF-8, and the name is stored and read back in its canonical form |

A value outside its range is answered `400` rather than clamped: clamping would register the
source and then crawl to a depth or a size the caller did not ask for, which reads back as a
crawl that stopped early for no stated reason. An explicit `null` is the same as leaving the field
out, on this route and the HTTP one below: the default applies.

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
| `max_response_bytes` | integer | 10485760 | Largest response body held, 1 byte to 100 MiB — the [largest document the node indexes](#document-size-limits). A larger body fails that page's document; it is never truncated |
| `timeout_seconds` | integer | 30 | Budget for one request, 1 to 600 |
| `included_path_patterns` | array of string | _(all)_ | A URL path must contain one of these substrings to be crawled. Plain substrings, not globs or regexes |
| `excluded_path_patterns` | array of string | `/api`, `/admin`, `/login`, `/logout` | A URL path containing one of these is skipped. Supplying this **replaces** the default list, and `[]` clears it |
| `user_agent` | string | `ExcaliburDB/1.0` | How the crawl identifies itself. At most 200 characters and no control characters: a carriage return or newline would end the header and turn the rest of the value into headers this node never agreed to send |
| `crawl_delay_ms` | integer | `excalibur.ingest.crawl-delay-ms` (200) | The gap left between two requests to the host, 0 to 60000. The node takes the larger of this and its own setting, so a registration can ask to be gentler than the deployment requires and never quicker; `0` therefore reads as "no preference", not "crawl flat out" |
| `respect_robots_txt` | boolean | `true` | Whether the crawl reads each origin's `robots.txt` and leaves out what it disallows. `null` or absent is `true`, and so is a registration recorded before the field existed. Set `false` only for a site whose owner has agreed to be crawled regardless. See [robots.txt](#robotstxt) below |

The walk is sequential and makes two requests per page — one to read its links, one to fetch it —
and every one of them, redirect hops included, waits out the gap above. A crawl left unpaced asks
a host for pages as fast as it will answer them, up to the ten million `max_documents` allows.

The crawl stays on the base URL's host regardless of any of these — redirects are followed by the
crawler itself, with the host policy re-applied at every hop — and hosts resolving to loopback,
private, link-local or multicast addresses are refused whatever the allow-list says.

#### robots.txt

Unless `respect_robots_txt` is `false`, the crawl follows each site's `robots.txt` as
[RFC 9309](https://www.rfc-editor.org/rfc/rfc9309) describes it.

- **When it is read.** The file is read once per origin (scheme, host and port) per run, whether
  that run is the first crawl, a scheduled re-crawl or `POST /v1/reindex`, and it is kept for the
  rest of that run.
- **How it is fetched.** It goes through the same host allow-list, public-address check, timeout,
  pacing, `user_agent` and redirect handling as any page. The path filters do not apply to it.
- **Which rules apply.**
  - The rules come from the group that names the product token of `user_agent`. That is
    `ExcaliburDB` for the default `ExcaliburDB/1.0`, compared case-insensitively. When no group
    names it, the `*` group applies.
  - Within the group, the longest matching rule wins, and `Allow` wins a tie.
  - `*` and a trailing `$` work as the RFC defines them, and percent-encoded paths are compared
    after normalisation.
  - The first 512 KiB of the file are read.
- **Which URLs are checked.** Every URL is checked before it is requested: the base URL, each link
  the walk finds, and each redirect hop, conditional re-crawl requests included.
  - A disallowed URL is never listed or fetched.
  - A page redirected onto one is not fetched.
  - Both are counted in `entries_disallowed` on the source's status. They are not failures and
    not `entries_skipped`.
  - A page indexed before its site disallowed it is removed at the next re-crawl.
- **What each answer means.**

  | robots.txt answers | Meaning for that origin, for this run |
  |---|---|
  | `2xx` | Its rules apply |
  | any `4xx`, `404` included, or more than five redirects | Nothing is disallowed |
  | `5xx`, no answer, or a redirect off the allowed hosts | Everything is disallowed: the crawl fails, with `last_error` naming the origin and the reason |

  A crawl that meets an unreadable `robots.txt` fails rather than indexing what it could reach.
  A listing with that site's pages missing would otherwise be read as the site having deleted
  them. The indexed documents stay as they were, and the next re-crawl tries again.
- **`Crawl-delay` can only slow the crawl.** The gap before each request is the larger of this
  registration's pace and the site's `Crawl-delay`. The site's value is capped at 60 seconds.

The switch is per registration and there is no node-level one. The hosts a crawl may reach are
already the operator's choice, through `excalibur.ingest.allowed-hosts`, and ignoring one site's
robots.txt is a decision about that site. `GET /v1/ingest` shows it for a registration that set it.

Settings that belong to the other source type are refused here with a `400` rather than accepted
and ignored, in both directions: `included_extensions`, `excluded_directories`,
`follow_symlinks`, `include_hidden`, `max_file_bytes` and `charset` on a crawl — whose
responses each name their own encoding in `Content-Type` — and `max_documents`,
`max_response_bytes`, `timeout_seconds`, `included_path_patterns`, `excluded_path_patterns`,
`user_agent`, `crawl_delay_ms` and `respect_robots_txt` on a filesystem walk. See [error responses](#error-responses). `max_depth` is the one
setting both understand.

**Response — `200 OK`**

```json
{
  "source_id": "docs-site",
  "source_type": "http",
  "status": "registered"
}
```

### Document size limits

A document is held whole while it is decoded, chunked and embedded, and is published only once
every chunk is written, so the node bounds a single document two ways, whichever way it arrives —
a crawled file, a fetched page, or one supplied to `POST /v1/documents`:

| Limit | Value | What happens past it |
|-------|-------|----------------------|
| Bytes | 104857600 (100 MiB) | The document is not indexed |
| Chunks | 10000 | The document is not indexed |

At the default chunk size (512 tokens, 64 overlapping) the chunk limit is the one a text document
meets first, at roughly 18 MB. A document over either limit is counted in `documents_failed` on the
first crawl and on every re-crawl alike, and nothing of it is searchable; a larger document has to
be split before it is ingested.

`max_file_bytes` and `max_response_bytes` cannot be set past the byte limit, since a larger file or
body could only be read to be discarded. A filesystem walk never lists a file over its
`max_file_bytes`, so such a file is not read, not counted as failed and not counted in
`entries_skipped`; an HTTP response over `max_response_bytes` is stopped as soon as it passes the
bound and counts as a failed document. A registration recorded by an earlier version with a larger
value is restored at the limit, with a warning in the log, rather than refused.

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

**`400 Bad Request` — an `X-Excalibur-Namespace` that is not a legal namespace id.** Refused
before the body is read, rather than registered into `shared`: a source filed under a tenant the
caller did not name has crossed a tenancy boundary with nothing downstream to show it.

**`403 Forbidden` — the target is not allow-listed.** A `root_path` outside
`excalibur.ingest.allowed-roots`, or a `base_url` whose host is not in
`excalibur.ingest.allowed-hosts` or resolves to a loopback, private, link-local or multicast
address. Both lists are empty by default, and empty means disabled.

**`413 Payload Too Large`** — the body is over 1 MB.

**`429 Too Many Requests`** — the node already holds `excalibur.ingest.max-sources` registrations
(256 by default). Not the rate limiter's `429`, and it carries no `Retry-After`: it clears when a
source is deregistered, not when time passes.

**`405 Method Not Allowed` — a method other than GET, POST or DELETE**

```json
{"error": "Method Not Allowed - Use GET to read the registrations, POST to register a source or DELETE to stop crawling one"}
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
      "config": {"base_url": "https://docs.example.com", "crawl_delay_ms": "500", "max_documents": "500"}
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
record. The keys it returns are `root_path`, `included_extensions`, `excluded_directories`,
`max_depth`, `follow_symlinks`, `include_hidden`, `max_file_bytes`, `charset`, `base_url`,
`max_documents`,
`max_response_bytes`, `timeout_seconds`, `included_path_patterns`, `excluded_path_patterns`,
`user_agent`, `crawl_delay_ms` and `respect_robots_txt` — each only when the registration stored
it; an HTTP source with no `respect_robots_txt` obeys robots.txt. Any other key is listed by name under `withheld` and its value is not returned. Nothing
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
      "entries_skipped": 1204,
      "entries_disallowed": 0,
      "last_updated_ms": 1730000042000,
      "last_error": null
    },
    {
      "source_id": "docs-site",
      "state": "idle",
      "progress": 1.0000,
      "entries_ingested": 12034,
      "entries_pending": 0,
      "entries_skipped": 0,
      "entries_disallowed": 37,
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
  "entries_skipped": 1204,
  "entries_disallowed": 0,
  "last_updated_ms": 1730000042000,
  "last_error": null
}
```

`last_updated_ms` is when this source last did anything — finished a crawl, failed one, reported
progress. Without it an `idle` source reads the same whether it was crawled a minute ago or
before a restart three weeks back, and "is the index still following the source?" cannot be
answered from the API at all.

The whole response survives a restart. The node writes each source's last finished run to
`<data dir>/source-status`, and a source it brings back reports that run — the same counts, error
and `last_updated_ms` — until its next run replaces it. A run the restart cut short is not
reported as still running: it comes back `idle`, with `last_error` reading `The run in progress
when the node stopped did not finish`. A `source-status` the node cannot read is logged and
ignored rather than failing the start; each source then reports from its next run.

`entries_skipped` is how many of the files the crawl listed were not text and so had nothing in
them to index — images, archives, compiled output, anything whose decoded bytes carry a U+0000 or
run a third or more replacement characters. It is reported apart from `last_error` on purpose: a
crawl root holding those beside its documents is ordinary, not broken. A source that indexed far
fewer documents than the directory holds, with `last_error` null, is answered by this number.
Narrow `included_extensions` if you want those files never read in the first place.

Every run rewrites it, including a re-crawl — the scheduled one and `POST /v1/reindex` alike — so
it describes what the root holds now and not what it held the first time the node walked it. A
run that lists no documents at all sets it back to zero too.
Every run also counts the same thing: an empty file, or one that decodes to nothing the chunker
can index, is passed over without being counted, on the first crawl and on every re-crawl, so an
unchanged root reads the same number after either. The
other counts on this response belong to the run that wrote them; a reindex reports its own
progress through `GET /v1/reindex/status`.

`entries_disallowed` is how many pages a web crawl left alone because the site's `robots.txt`
disallows them: the links it met and did not follow, and the pages whose redirect led onto a
disallowed URL. It is a separate count, not part of `entries_skipped`, because the two mean
different things. A skipped file is not text. A disallowed page is one the site asked to be left
out, and only the site's owner, or a registration with `respect_robots_txt: false`, can change
that. It is not an error and is not in `last_error`.

Like `entries_skipped`, it is rewritten by every run, re-crawls included, and it is always 0 for a
filesystem source. A `robots.txt` the site cannot serve is a different case, reported through
`last_error`: see [robots.txt](#robotstxt).

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
answers as soon as the source is registered and leaves the crawl to run behind the response,
because a crawl is of unknown size and duration.

On a node with `excalibur.graph.extraction-enabled=true` and an LLM API key (without one,
extraction is inactive and `/v1/graph/build` reports `"extraction": "missing_api_key"`), the facts
extracted from supplied
documents are staged into the `shared` namespace's graph, ready for the next
`POST /v1/graph/build` there. That is fixed rather than chosen per request: this route refuses the
`X-Excalibur-Namespace` header like every other corpus route, and a supplied source leaves no
registration to record a namespace in. `POST /v1/erasure` withdraws them from the same place.

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `source_id` | string | yes | What the documents are recorded as belonging to, so `/v1/erasure` can name them later. It is not a crawl registration: there is nothing behind it for `/v1/reindex` to re-read, which answers `404` for it. At most 256 characters |
| `documents` | array | yes | 1 to 256 documents |
| `documents[].document_id` | string | yes | Names the document within its source. Posting the same id again replaces what the node holds under it; see below |
| `documents[].text` | string | yes | The document's text. Empty is refused rather than indexed, because it would produce no chunks and be reported as ingested while being unfindable. Text that is not text — one carrying U+0000, which a crawl treats as the mark of a binary file — is refused for the same reason |
| `documents[].content_type` | string | no | Defaults to `text/plain` |
| `documents[].last_modified_millis` | int | no | Epoch millis; `0` when unknown |

Re-posting a `document_id` is how a supplied document is updated. The new version is written and
published first; only then is whatever the previous version held past the new one's last chunk
removed from storage and from the lexical index, so a query sees one version or the other and never
neither, and a later `/v1/erasure` of the document removes all of it. A replacement that fails is
counted in `documents_failed` and the previous version stays registered and searchable: one that
could not be embedded wrote nothing, and one that failed while being written has what it wrote
past the previous version removed — the chunk positions the two share may already hold the new
text, so post the document again to finish the replacement. The same holds for the first crawl of
a source registered again after `DELETE /v1/ingest`, whose documents the node kept. Graph facts
extracted from the previous version are not withdrawn by a replacement; `/v1/erasure` withdraws
them.

**Error — `400 Bad Request`** — no `source_id`, no `documents`, more than 256 of them, a document
with no `document_id`, no `text` or text that is not text, or the same `document_id` twice in one
request.

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

Re-crawl a source now and index what changed, without taking it out of service. Each document's
previous version stays queryable until its replacement has been published, and a document's chunks
are published together, so a query sees one version or the other and never half of each. A node with
a segment catalog first asks the storage engine for a separate segment to rebuild the source into;
the engine never offers one — a segment the catalog creates is not one the engine writes to — so the
run rebuilds in place, and no segment is retired.

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

**Error — `404 Not Found`** — no crawl is registered under that id. That includes a `source_id`
used only with `POST /v1/documents`: supplied documents have no target to re-read, and are updated
by posting them again.

```json
{"error": "Source not found: unknown-source. Only a source registered with POST /v1/ingest can be reindexed; documents supplied with POST /v1/documents are replaced by posting them again"}
```

**Error — `409 Conflict` (reindex already running for that source)**

```json
{"error": "Reindex already in progress for source: project-docs"}
```

**Error — `405 Method Not Allowed` — a method other than POST or DELETE**

```json
{"error": "Method Not Allowed - Use POST to start a reindex or DELETE to stop one"}
```

**Error — `400 Bad Request`** — the body is not JSON, or `source_id` is missing, not a string, or
not a legal source id. **`413 Payload Too Large`** — the body is over the request size limit.
**`500 Internal Server Error`** — an unexpected failure starting the run; the reason is in
the node's log.

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

Either way, only what changed is rewritten. An HTTP source is asked about each page it already
holds with a conditional GET — the `ETag` and `Last-Modified` it reported last time, sent back as
`If-None-Match` and `If-Modified-Since` — and a `304 Not Modified` leaves that page as it is
without its body being transferred. Any other answer is compared by content hash, and a page that
changed is fetched and replaced. The two validators are kept in `<data dir>/document-registry`, so
the first re-crawl after a restart asks the same questions rather than transferring the whole site.
A filesystem source reads each file and compares modification time, length and content hash.

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
`last_error` set when it broke — which means it indexed nothing. A run that indexed some documents
and failed on others finished, so it reads `idle`, with `last_error` counting what failed and what
was indexed (`"Reindex completed with 3 errors; 400 documents indexed; see the node's log for the
reason"`); a clean run is `idle` with `last_error: null`. `cancelled` and `failed` are different answers and the
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

**Error — `400 Bad Request`** — the `source_id` parameter is not a legal source id.

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
claim is deliberately not dropped here: handing the source to a second re-index while the first is
still winding down is the data loss the claim exists to prevent, because both compare against the
same registry entries and each retires the version it replaced — which can be the one the other has
just published. `GET /v1/reindex/status` is what
says when it has actually ended.

**Error — `404 Not Found`** — no source is registered under that id. **`400`** and **`413`** as for
starting one.

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

**Error — `400 Bad Request`** — the body is not JSON, or `source_id` is missing, not a string, or
not a legal source id. **`413 Payload Too Large`** — the body is over the request size limit.
**`405 Method Not Allowed`** — any method but `DELETE`.

### Stop crawling a source

Takes a source out of the crawl: any ingestion run in flight is stopped, a re-crawl in flight is
stopped and waited for (up to thirty seconds — it finishes the document it is on), its
`/v1/ingest/status` and `/v1/reindex/status` entries are cleared, and it is removed from the
re-crawl schedule. **The documents it already indexed
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

**Error — `400 Bad Request`** — the body is not JSON, or `source_id` is missing, not a string, or
not a legal source id. **`413 Payload Too Large`** — the body is over the request size limit.
**`503 Service Unavailable`** — the deregistration could not be recorded in the source catalog;
the source is still registered and still crawled, and the request can be retried.

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
`document_id` or `document_ids` either. A source-scoped erasure first **deregisters the source**
— stopping any ingestion run in flight, stopping any re-crawl in flight and waiting for it to end,
clearing its status entries and cancelling its re-crawl schedule — so nothing restores what was
just erased, and reports `source_deregistered`. Each request erases up to 1000 documents and
reports `documents_remaining`; repeat until it reaches zero. An ingestion run stopped mid-flight
finishes the document it was on without being waited for, so one more may land between two calls,
which is what the remaining count is for. A re-crawl is waited for before anything is erased, up
to thirty seconds; one that outlives that is logged and may likewise land one more document.

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
- `compacted` is true only when nothing is owed afterwards: the index was rewritten and `outstanding` is zero. A compaction that ran but had to leave some of its segments as they were — because a chunk's text could not be read back from storage just then — reports `false`, and the erasure stays owed until a later compaction discharges it. `chunks_reclaimed` is how many chunks the rewrite took off disk. `outstanding` is what the node still owes; watch it on `GET /v1/stats` under `lexical`.
- `documents_not_found` is reported rather than folded into success: a caller under an obligation needs to know this node never held the document instead of assuming it was removed.
- The `graph_*` fields are the graph half, below. `graph_erasure_complete` is the one to read: while it is `false` the reply is a `202`, not a `200`.

**The graph half.** A document that was ingested into the knowledge graph left two things there
that removing its chunks does not touch: the facts an extractor read out of it, and up to two
kilobytes of its text recorded against every entity it mentioned. Both carry a provenance id
derived from the document's key, so this endpoint submits one source-scoped graph obligation per
document it actually erased — including for a source-scoped request, whose documents the caller
never named. `graph_requests_submitted` is that count.

The obligations go to the namespace the document's facts were staged into, which the node works out
before it removes anything: the namespace the source was registered in, or `shared` for documents
supplied through `POST /v1/documents` in this process. A source the node can no longer place — one
deregistered since, or documents supplied before a restart — is withdrawn from every namespace that
exists on disk, `shared` included, rather than from a guess that could leave the facts where the
guess was wrong. A namespace with no directory has had nothing staged into it and is skipped, never
created. When several namespaces are visited, the `graph_*` fields are their combination: the
counts are summed, `graph_compacted` is true if any of them was rewritten, `graph_erasure_complete`
is true only if every one of them is done, and `graph_detail` joins their details with `; `.

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

The same `400` answers a body that is not JSON, a field of the wrong type, more than 1000 ids, or an
illegal `X-Excalibur-Namespace` header — the corpus has no namespaces, so the header is accepted
only as `shared` or left off. **`413 Payload Too Large`** — the body is over the request size limit.
**`405 Method Not Allowed`** — any method but `POST`. **`503 Service Unavailable`** — a
source-scoped erasure could not record the deregistration in the source catalog; the source comes
off the crawl before its documents go, so nothing has been erased and the request can be repeated
once the catalog is writable. A failure the node did not anticipate — including one recording the
graph obligation — is a bare `500` with the detail in the log.

**Error — `404 Not Found`** — the node was built without an erasure service, so the route is not
registered. Not the same as a document that does not exist, which is a `200` with
`documents_not_found`.

---

## Querying

### Request

`POST /v1/query` — send raw query text. On a node with an embedding model the server embeds it using the configured `excalibur.llm.embedding-model` (the same model used for ingestion) and runs the search; a node without one searches its BM25 index instead, as described under [Degraded retrieval](#degraded-retrieval--lexical-answers).

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
| `top_k` | int | `excalibur.query.top-k-default` (`100`) | Max results to return. At least 1 and at most `excalibur.api.max-top-k` (`1000`); outside that is a `400`. |
| `threshold` | float | `excalibur.query.threshold` | Minimum similarity score to include. Omitting it does not mean "no floor": the server's configured floor applies, `excalibur.query.threshold`, which is `0.0` - no filtering - unless the deployment raised it. |
| `timeout_ms` | int | `excalibur.visibility.query-timeout-ms` (`30000`) | Partial results returned if exceeded. Capped by `excalibur.api.max-query-timeout-ms` (`60000`); asking for more is a `400`. |
| `include_partial_segments` | bool | `true` | Include segments that are still indexing. |
| `enable_llm_scoring` | bool | `true` | Re-rank the top candidates with the LLM. On a vector answer the pipeline scores them; on a hybrid answer the fused list is scored after fusion; on a lexical answer the BM25 candidates are scored. `llm_scored` on the response says whether it happened. On a node with no chat model it has no effect and the field is named in `ignored_fields`; `coverage.degraded` stays `false`, because the node is running as configured rather than failing. |
| `llm_threshold` | float | `excalibur.llm.relevancy-threshold` (`0.3`) | Score a candidate must reach in the LLM re-rank to survive it, on every route. Outside `[0.0, 1.0]` is a `400`. Inert when `enable_llm_scoring` is false; named in `ignored_fields` when the node cannot score. |
| `oversampling_factor` | int | `2` | How many times the requested depth is ranked before the LLM re-rank narrows the list back down, on every route; it also widens each segment's HNSW search. Higher trades latency for recall. Named in `ignored_fields` only on a lexical answer with no scoring. Must be at least 1 and at most `excalibur.api.max-oversampling-factor` (16); outside that is a `400`. |
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
  "llm_scored": false,
  "coverage": {
    "total_segments": 1000,
    "active_segments": 950,
    "indexing_segments": 40,
    "warming_segments": 10,
    "compacting_segments": 0,
    "tombstoned_segments": 0,
    "estimated_corpus_coverage": 1.0000,
    "segments_searched": 1000,
    "segments_excluded": 0,
    "segments_with_errors": 0,
    "segments_timed_out": 0,
    "tail_scan_entries": 12500,
    "degraded": false
  },
  "ignored_fields": [],
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
- **`results`** — Each item has `document_id` (string), `score` (float, six decimal places) and `segment_id` (int). `document_id` is the identifier ingestion assigned, `sourceId:documentId:chunk:N`; it is always a JSON string, never a bare number, and it is never the HNSW node id, which compaction renumbers. A vector hit whose graph node has no document identity — its segment's node-to-document mapping is missing or damaged — is dropped before the page is cut, so the page is still filled from results that do have one; each drop sets `coverage.degraded`, counts in `excalibur_query_unnamed_results_dropped_total`, and names its segment in the node's log. Text is not returned unless the request asked for it — send `"include_text": true` and every result also carries `text` and `text_truncated`; add `"snippet": true` for the part of the passage the query matched, and `"explain": true` for why the result is there at all.
- **`retrieval`** — Which signals answered: `"hybrid"`, `"vector"` or `"lexical"`. Always present, on every path. It also tells you how to read `score`, because the three have no common scale — see [Hybrid retrieval](#hybrid-retrieval--both-signals).
- **`llm_scored`** — `true` when a relevancy model reordered this answer. `score` is then the model's judgement between 0 and 1 for each result it judged, and not a similarity or a rank sum. It is set on every route: a vector answer is scored before it leaves the pipeline, a hybrid answer after fusion, a lexical answer over its BM25 candidates. `false` when scoring was not asked for, the node has no chat model, or the model failed or ran out of time. In that last case `coverage.degraded` is `true` and the results are in retrieval order.
- **`coverage.estimated_corpus_coverage`** — Fraction of the live corpus this query actually read. Segments it did not read (`segments_excluded`), that failed (`segments_with_errors`) or that did not answer in time (`segments_timed_out`) each reduce it, and each is counted once. < 1.0 during cold-start or heavy ingestion. Use this to decide whether to retry.
- **`coverage.segments_excluded`** — Live segments this query did not read for a reason that is not a failure. Two things land here: segments held back because `include_partial_segments` was `false`, which is your own choice; and segments with no vector index to search, which is the node's — their entries are in storage and no graph speaks for them. If you sent `include_partial_segments: true` and this is not zero, it is the second, and the node's log says which segments and why.
- **`coverage.tail_scan_entries`** — How many entries this query read straight from segment write-log tails, i.e. entries that are already stored and searchable but not yet built into an HNSW graph. It is a freshness signal, not a fault: a steady non-zero value under ingestion is normal, and a large one means the tail scan is doing work the graph would otherwise do faster. If a tail scan has to stop early, `degraded` says so.
- **`coverage.degraded`** — `true` when this answer is not what a fully healthy node would have returned. Four things set it: `estimated_corpus_coverage` below the node's threshold; a segment that errored; a segment that did not answer in time; and a segment whose write-log tail scan stopped early, which is the one case the counts above cannot show you — it is neither an error nor a timeout, its results are kept, and `segments_searched` still counts it, so without this flag the response would claim the whole corpus was read. It is also set when `enable_llm_scoring` was honoured and the relevancy model failed or ran out of time, so the answer kept its retrieval order; when a result was dropped for having no document identity; and on every lexical answer, which came from one signal — see [the lexical answer](#degraded-retrieval--lexical-answers). `degraded` never means the results are wrong; it means they are partial or ranked by a weaker signal, so retrying is worth considering.
- **`ignored_fields`** — the fields you sent that this answer accepted and did not act on. It is on every answer, empty included, on every route. See [the lexical answer](#degraded-retrieval--lexical-answers) and [hybrid retrieval](#hybrid-retrieval--both-signals) for which fields each route can leave unacted on.
- **`timed_out`** — `true` if the timeout was hit. Partial results may still be returned (best-effort).
- **`cancelled`** — `true` exactly when `DELETE /v1/query/{query_id}` withdrew this query, which is
  exactly when that call answered `"cancelled": true`. A withdrawn query answers with no results and
  `coverage.degraded: true`, on every route and however far its search had got — a search that had
  already finished included, since a withdrawn question is not answered, not even in part. Without
  this field that answer is indistinguishable from a query that searched the whole corpus and
  matched nothing.
  It is separate from `timed_out` on purpose: a deadline the server missed and a request you
  withdrew are different events, and only one of them is the server's to answer for.
- **`from_cache`** — `true` when this answer came out of the query result cache instead of being
  searched for. On every answer, `false` included, because it is what makes `query_time_ms`
  readable: on a hit that number is how long this request took, not how long the search took,
  and without this beside it a cache hit looks like a search that suddenly got faster. Always
  `false` on a node with `excalibur.query.cache.enabled=false`, which is the default; otherwise it
  can be `true` on every route. A cached answer is never a stale one — see
  [the query cache](#the-query-cache) — and an answer that is the product of a moment's trouble is
  never cached at all, so that trouble cannot be replayed as a persistent fault. See the cache
  section for exactly which answers those are.
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
  ran", which would make that path never able to report an exact total. An answer a relevancy
  threshold shortened is `exact` only when retrieval had also run out of candidates; otherwise its
  total is `at_least`, because the model judged only the candidates it was shown.
- **`explain`** (per result, only with `"explain": true`) — `signals` gives each contributing
  signal's own `rank` (absolute, from the start of the whole ranking, not of the page) and `score`
  **on that signal's own scale**: a similarity for `vector`, and for `bm25` whatever the lexical
  service returns, which is its own internal fused score when more than one ranker contributed.
  The top-level `score` remains the fused rank sum and is not comparable with either — unless
  `llm_scored` is `true`, when it is the model's judgement.
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
  "llm_scored": false,
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
  "ignored_fields": [],
  "page": {"offset": 0, "limit": 10, "returned": 1, "next_offset": null},
  "result_total": {"value": 1, "relation": "at_least"},
  "timed_out": true,
  "cancelled": false,
  "from_cache": false,
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
  "llm_scored": false,
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
  "page": {"offset": 0, "limit": 10, "returned": 1, "next_offset": null},
  "result_total": {"value": 1, "relation": "exact"},
  "timed_out": false,
  "cancelled": false,
  "from_cache": false,
  "query_time_ms": 0
}
```

Three differences from a vector answer:

- **`degraded` is always `true`,** even at full coverage. The answer is real; it came from one
  signal where a fully configured node fuses several, and that is worth telling a caller.
- **`segment_id` is absent from each result.** The lexical store has no HNSW segment to name.
- **`ignored_fields` names what this path could not honour.** `threshold` and
  `include_partial_segments` steer vector search, which this path does not have.
  `enable_llm_scoring`, `llm_threshold` and `oversampling_factor` appear only when the node has no
  chat model to score with; with one, the BM25 candidates are scored exactly as a vector answer's
  are. Fields are still validated and still refused if out of range; the list is how you find out
  one changed nothing. It names only fields you sent, and `query`, `top_k` and `timeout_ms` are
  honoured and never appear in it.

Scores are reciprocal-rank sums, small and near zero — the lexical rankers are fused by rank even
when only one contributes — so they are not comparable to a vector answer's scores. Rank them; do
not threshold them against a number tuned on the vector path. That is why `threshold` is reported
as ignored rather than applied. With `llm_scored: true` a score is instead the model's 0–1
judgement, and `llm_threshold` is what filtered it.

Two ways to reach this path. With no API key it is plain BM25 — the startup log says so. With a
key and `excalibur.llm.embeddings-enabled=false` the chat model is kept and spent on expanding the
query instead of on embedding every document, which is the deployment this path was built for: a
few dozen generated tokens per query against billions of embedded tokens per corpus.

A node that does embed its corpus answers here too, one query at a time, when its vector half
cannot answer: the embedding endpoint fails or misses the deadline, or the pipeline does. That
answer has the same shape, with `timed_out` saying whether a deadline was the cause — see
[Hybrid retrieval](#hybrid-retrieval--both-signals) for each case.

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
  "llm_scored": false,
  "coverage": { "...": "as on the vector path" },
  "ignored_fields": [],
  "page": {"offset": 0, "limit": 3, "returned": 3, "next_offset": 3},
  "result_total": {"value": 3, "relation": "at_least"},
  "timed_out": false,
  "cancelled": false,
  "from_cache": false,
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
- **LLM scoring runs after fusion.** With `enable_llm_scoring` the fused candidates are judged
  together and `llm_threshold` filters them, so a result only BM25 found is tested too.
  `query_time_ms` covers the whole request.

Neither signal is allowed to fail the query, and that runs both ways.

- **BM25 breaks, or misses the budget.** The vector answer stands and the response reports
  `"retrieval": "vector"`.
- **The vector pipeline reaches its deadline with partial results.** The pipeline hands back what
  it had found, and that is still fused with BM25: the answer says `"retrieval": "hybrid"` with
  `"timed_out": true` and `coverage.degraded: true`, because both signals did contribute.
- **The embedding endpoint misses the deadline, or the pipeline hands back nothing in time.** You
  get the answer BM25 already finished, with `"retrieval": "lexical"` and `"timed_out": true` — the
  request did miss its deadline, and it was answered by fewer signals than this node has, so both
  are said. If BM25 had not finished either, the response is the empty timeout answer.
- **The embedding endpoint fails** — refuses the key, is behind an open circuit breaker, answers
  `429` or `5xx`, or returns a vector with no usable values — **or the pipeline fails.** The query
  is answered from BM25 with `"retrieval": "lexical"`, `"timed_out": false` and
  `coverage.degraded: true`. Only when BM25 cannot answer either is it a `500` or `503`.
- **The node is saturated.** Concurrent BM25 searches are capped at one per core, and never
  below two, because a search inside the lexical engine does not stop when its request does; past that cap the second
  signal is shed for that request and the answer comes from vector search. On a node with both
  signals this is load shedding, not an error, and `retrieval` is the only place it shows. A node
  with no embedding client has nothing to shed to, and answers `503` — see
  [Error responses](#error-responses).
- **You cancel with `DELETE /v1/query/{query_id}`.** Both halves stop and nothing is returned,
  including from the half that had finished: a withdrawn question is not answered.

In every case `retrieval` names the signals that actually ran, never a fusion that did not.

Set `excalibur.query.hybrid-retrieval=false` for vector-only ranking, which is what a deployment
with a threshold tuned against cosine scores wants. The key has no effect on a node holding only
one signal. It turns off fusion, not the lexical index: when the vector half fails on such a node,
a BM25 search is started then — under the same concurrency cap, and within what is left of the
request's deadline — and answers with `"retrieval": "lexical"` exactly as above. After a missed
deadline there is nothing left to run one in, so a vector-only node's timeout answer is the empty
one.

The BM25 half of a hybrid query is not expanded by default. Query expansion is what carries the
meaning of a question when there are no vectors, and here there are — so the lexical signal
matches the words as typed, which makes it weaker than the same node would run with no embedding
model at all. Whether that trade pays depends on the corpus, so
`excalibur.query.expand-hybrid-queries=true` expands the lexical half as well, at one extra chat
call per query. It requires `excalibur.llm.api-key`. The retrieval harness in `excalibur-bench`
measures the two side by side on your corpus; see
[Evaluating it](README.md#evaluating-it).

A query longer than `excalibur.api.max-query-chars` is refused, on every route; there is no
second, shorter bound for expansion. A hybrid answer carries `ignored_fields` like every other:
`threshold` and `include_partial_segments` are applied to the vector signal before fusion, so they
never appear in it, and the scoring fields appear only on a node with no chat model.

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

Vector, hybrid and lexical answers are all cached, after relevancy scoring, keyed on everything
that changes the answer — the query, the route, the depth and every scoring and filtering field.
A lexical or hybrid answer is fingerprinted twice: the segments as above, and the lexical index as
a search started now would see it — the manifest entries visible at the query epoch on the native
engine, the reader version on Lucene — so an epoch crossing a manifest entry discards it like any
other change.

Three things are deliberately never cached:

- **Result text, snippets and explanations.** Read from chunk storage on every answer, so quoted
  text can never be stale.
- **Answers that are the product of a moment's trouble.** A vector or hybrid answer is not
  stored when it is degraded, timed out or cancelled, or when its relevancy scoring fell back or ran
  out of time. A lexical answer is not stored when it timed out or was cancelled, when its scoring
  fell back, or when it read less than the whole index. Its `degraded` flag alone does not stop it:
  that flag is set on every lexical answer, saying the answer came from one signal, and is not a
  sign of trouble on a node that has only that one. And an answer from BM25 standing in for a
  failed vector half is never stored, because it is not the answer the node's cache key names.
  Enforced where entries are stored rather than at each call site, so no path can forget it.
- **Anything at all, when either fingerprint cannot be read.** "I could not check whether anything
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

**`400 Bad Request` — `explain` or `snippet` on a query of more than 64 distinct terms**

```json
{"error": "Bad Request: the query holds 71 distinct terms and at most 64 can be explained or highlighted: shorten the query, or drop 'explain' and 'snippet'"}
```

Each term is looked for in every returned passage, so the count bounds the work a single request
can ask for. Without `explain` and `snippet` the query is not held to it.

**`413 Payload Too Large` — the body is over 1 MB**

```json
{"error": "Request body too large (max 1048576 bytes)"}
```

**`500 Internal Server Error` — the search failed**

```json
{"error": "Query execution failed"}
```

The vector half failed and BM25 could not answer in its place, or, on a node with no embedding
client, the BM25 search itself failed. The reason is in the node's log, not the body.

**`503 Service Unavailable` — the query service is saturated**

```json
{"error": "Query service is saturated"}
```

Only on a node with no embedding client: BM25 is its only signal, and every permit for a
concurrent BM25 search is taken. Retry with backoff. A node that also has vector search sheds the
BM25 half instead and answers from vectors.

**`503 Service Unavailable` — the query service is not available, or was interrupted**

```json
{"error": "Query service is not available"}
```

The embedding came back unusable and BM25 could not answer in its place, or the node is not ready
to search. `"Query interrupted"` is the same status for a request the node stopped while shutting
down. Retry against a node that is up.

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
bound, an empty identifier in the array, or the same identifier twice. A repeat is refused rather
than answered twice, because otherwise the bound would limit how many identifiers are named and not
how much text comes back. **`413 Payload Too Large`** — the body is over the request size limit.

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
propagates through all query pipeline stages, both signals of a hybrid query, and the relevancy
model. The two answers agree: when this route answers `"cancelled": true`, the query's own response
is `200` with `"cancelled": true`, no results, and `coverage.degraded: true` — on the vector, hybrid
and lexical routes alike, and even when the search had already finished, because the query's answer
is written only once it can no longer be withdrawn. When this route answers `"cancelled": false` or
`404`, the query was not withdrawn and answers as it would have. A query that misses its deadline
is never reported cancelled, and is not "already cancelled" to this route.

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
  "latencyMs": 5,
  "indexerRunning": true
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
  "latencyMs": 2,
  "indexerRunning": true
}
```

- `live: true` as long as the process is running — use for liveness probes. The endpoint always returns HTTP 200 if the process is alive, so liveness probes should check this field rather than the status code.
- `ready` flips to `true` when `coverage ≥ excalibur.health.readiness-threshold` (default `0.5`) — use for readiness probes (load balancers / K8s readiness).
- `healthy` flips to `true` when `coverage ≥ excalibur.health.healthy-threshold` (default `0.9`) — the higher bar of the two.
- `epoch` — current visibility epoch (monotonically increasing).
- `coverage` — the share of this node's live segments that are in a state a query reads, in `[0.0, 1.0]`. Live means everything the catalog holds and has not retired, so a segment compaction folded into its successor is not counted as corpus this node is missing. **This is not a warm-up gauge.** A segment still absorbing its write log is searched, so it counts, and an empty segment counts as readily as a full one — on a node whose segments are all accounted for this reads `1.0000` from the first moment it starts. It falls when a segment is stranded in a state no query reads, and reads `0.0000` before the first segment exists, which is the cold start above. For how much of the corpus a *particular answer* came from, read `coverage.estimated_corpus_coverage` in the query response; for ingestion progress, read `entries.total` and `segments.active` from `/v1/stats`.
- `latencyMs` — time taken to compute the health response. Exceeding `excalibur.health.max-latency-ms` (default `5000`) sets `healthy` to `false` by itself, whatever the coverage. `ready` is left on coverage alone: a probe that waited that long has already timed out, and a slow answer here is not a reason to stop serving traffic.
- `indexerRunning` — whether the worker that drains the HNSW integration queue is alive. A node started by the application always carries it; it is left out only where the endpoint is embedded without an index orchestrator to ask. Reported beside the verdict rather than folded into it, because `live`, `ready` and `healthy` mean what they are documented to mean above and clients already parse them. If this is `false` the node is indexing nothing while everything else here still reads healthy; `/health/ready` is the route that turns that into a status code.

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
`/health`'s `ready` field gives, carried in the status code: a node holding nothing
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
    {"query_id": "1f0c...", "correlation_id": "9c41e07a",
     "started_at_ms": 1736899200000, "duration_ms": 91400, "cancelled": false}
  ]
}
```

Longest-running first, so the query that will not finish is the first line. Its `query_id` is what
`DELETE /v1/query/{query_id}` takes, which makes this the way to learn the id of a query you did not
issue yourself. At most 100 queries are listed; `truncated` counts the rest.

`correlation_id` is generated by the server for each query — eight hexadecimal characters, not
something a client supplies — and is the `correlationId` the node's log lines for that query carry,
and the `X-Correlation-ID` header on the query's request to the embedding endpoint. It is how a slow query listed here is found in the log.

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
  "cuckoo": {"pendingDeletions": 0, "totalDeletions": 212,
             "avgFpRate": 0.0000070547, "maxFpRate": 0.0000137324,
             "saturated": false, "saturatedSegments": 0},
  "visibility": {"epoch": 42, "registeredSegments": 1024, "visibleSegments": 1024},
  "coverage": 1.0000,
  "workers": {"indexOrchestratorRunning": true},
  "vectorIndex": {"segmentsWithoutIndex": 0},
  "breakers": {"embedding": "CLOSED", "llmRelevancy": "CLOSED",
               "queryExpansion": "CLOSED", "graphExtraction": "CLOSED",
               "graphRerank": "CLOSED", "graphQueryEmbedding": "CLOSED",
               "graphBuildEmbedding": "CLOSED"},
  "lexical": {
    "engine": "native", "indexed": 15000000, "segments": 12,
    "liveChunks": 14980000, "erasedChunks": 20000, "erasedRatio": 0.0013,
    "erasureRequests": 3, "oldestErasureAgeMs": 412000, "compactionDue": false,
    "unreadableChunksDropped": 0, "unsearchableChunks": 0, "unreadableSegments": 0,
    "outdatedSegments": 0
  },
  "graph": {
    "namespaces": {"open": 3, "maxOpen": 16, "evictions": 41},
    "budget": {"graphMappings": 81, "processMappings": 12408, "mappingHighWater": 45871,
               "mappingLimit": 65530, "limitRead": true, "graphDescriptors": 0,
               "processDescriptors": 412, "descriptorHighWater": 734003, "descriptorLimit": 1048576},
    "builds": {"running": false, "completed": 12, "failed": 0, "cancelled": 1},
    "erasure": {"namespacesOwing": 1, "outstanding": 2, "overdueNamespaces": 0,
                "unreadableNamespaces": 0, "oldestAgeMs": 91000},
    "ingest": {"documentsSeen": 20412, "factsStaged": 131877, "unresolvedDocuments": 0,
               "extractionFailures": 3, "tenantFailures": 0, "sinkRefusals": 0}
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
  and each omitted where there is no such component rather than reported as healthy. Each
  breaker guards a different call, and any one can be open while the others are closed.
  `breakers.embedding`: when it is open the node has stopped calling its embedding provider and
  retrieval has silently fallen back to lexical-only. `breakers.llmRelevancy`: when it is open
  LLM re-ranking is bypassed on every route and the answer keeps the ranking it already had.
  `breakers.queryExpansion`: when it is open every query is searched as typed, without the
  formulations the chat model adds to widen the lexical half's recall. `breakers.graphExtraction`:
  when it is open documents are still ingested and indexed, but none of them adds a fact to the
  graph. `breakers.graphRerank`: when it is open `POST /v1/graph/query` stops asking the chat
  model to rerank its candidates and answers in the order BM25 - or, for a graph built with
  entity vectors, the exact vector rerank - gave them. `breakers.graphQueryEmbedding`: when it is
  open a graph question carries no embedding, so that exact vector rerank is skipped.
  `breakers.graphBuildEmbedding`: when it is open a graph build still publishes, but the entities
  it could not embed carry no vector, so questions about them skip the vector rerank until a later
  build embeds them. It is a separate breaker from `graphQueryEmbedding` though both call the same
  endpoint and model, because they stop different things: a build's bulk batches failing must not
  take the vector rerank away from every query, nor a burst of failed questions strip the vectors
  from a build. The three graph breakers are one each for the node, not one per namespace - every
  namespace's graph calls the same endpoint through the same client - and each appears once it is
  first needed: `graphRerank` with the first graph on a node that has an API key,
  `graphQueryEmbedding` with the first graph built with entity vectors, and `graphBuildEmbedding`
  with the first build that embeds an entity (never, with `excalibur.graph.entity-vectors-enabled`
  off). All seven leave the node
  answering `200` with nothing in the response saying so, which is the whole reason they are
  reported here. On `/metrics` each appears as
  `excalibur_circuit_breaker_state` under a `breaker` label, so one alert on `> 0` covers each new
  breaker without being rewritten.

`queryCache` appears only on a node with `excalibur.query.cache.enabled=true`. Read `evictions`
and `invalidations` separately rather than together: an eviction is the cache being too small for
the traffic, which a larger `excalibur.query.cache.max-entries` fixes, and an invalidation is the
corpus having moved under an entry, which no size would have helped. A node with a low
`hitRatio` and near-zero `evictions` is not under-sized — it is being written to faster than the
same question comes back, and the cache is not the right tool for it.

The other four `cuckoo` figures describe the per-segment membership filters that stage 3 of a
query prunes with:

- `avgFpRate` and `maxFpRate` are the mean and the worst estimated false-positive rate across the
  segment filters holding anything, computed from their current load when the request is served.
  They are printed as exact decimals rather than in exponent notation; `0` means no filter holds a
  term yet. A rising `maxFpRate` is one segment's filter filling up, and a false positive only
  costs a search of a segment that turns out not to match.
- `pendingDeletions` is the number of filter entries (one per distinct term a segment was filed
  under) still held for segments that have been tombstoned, and `totalDeletions` how many such
  entries have been removed since the process started. The maintenance worker removes them in
  batches, so after a segment is retired its entries move from the first figure to the second.
  A `pendingDeletions` that stays high means the worker is not keeping up or is not running.

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
- `unsearchableChunks` is on disk and matched by no query, because the segment holding it could not be opened: a column failed its checksum or its files are missing. `unreadableSegments` counts those segments, and `unreadable` — present only when there is one, capped at 32 entries with `unreadableTruncated: true` past that — names each: `segment`, its `chunks`, whether it is `rebuildable`, and the `reason`. A rebuildable segment is rebuilt by the next compaction from its key table and the chunk text in storage, and the node asks for that compaction itself; one that is not rebuildable needs its documents reindexed. Neither makes the node unready — it keeps answering from the other segments.
- `outdatedSegments` counts segments written in an older format. They answer exactly, but carry no checksums, so damage to them cannot be detected. Compaction upgrades them a few at a time on its own.
- `erasureRequests` and `oldestErasureAgeMs` are the erasure backlog: what was promised and not yet discharged, and how long the oldest has waited. Compare the age against `excalibur.ingest.lexical-max-latency-seconds`. Nothing else in this response would show that deadline going unmet.
- `compactionDue` is whether the index would compact if asked right now — because its policy says so, or because a segment is waiting to be rebuilt or upgraded. Compaction runs at the end of an ingestion or reindex run, and on the sweep that enforces `excalibur.ingest.lexical-max-latency-seconds`, so a node reporting `true` settles within a tenth of that bound even when nothing ingests.
- `unreadableChunksDropped` counts chunks a native-index compaction dropped because their stored text failed its checksum or could not be decoded — damage no retry repairs, and waiting on it would keep every erasure owed in those segments on disk. Each one stops matching keyword queries while still being served by vector search. Above zero means a storage segment is damaged: check the disk, and reindex the affected documents from their source. It stays `0` on the Lucene engine, which keeps its own copy of the text.
- Both engines answer these the same way. Lucene's own merges reclaim deleted documents on a schedule of their own, which never fires for one erased chunk in a large index, so the Lucene engine records the same durable obligation the native one does and is held to the same `excalibur.ingest.lexical-max-latency-seconds`. What differs is only what a compaction rewrites: the native engine rewrites the segments it selected, Lucene force-merges every segment holding a deletion.

The `graph` block is present only on a node that serves a graph. Every figure in it is a total
across namespaces, never a row per namespace — which namespace owes what is
`GET /v1/graph/namespaces`'s job. Within it:

- `namespaces.evictions` climbing steadily means the working set does not fit `excalibur.graph.max-open-namespaces` or the mapping budget, and every eviction is a reopen some request pays for.
- `budget` sets two shares against each limit. `graphMappings` and `graphDescriptors` are what eviction can give back; `processMappings` and `processDescriptors` are the whole process — segments, vector graphs, the chunk index and the JVM itself — which is what the kernel limit is measured against. Graph eviction starts when the whole process crosses `mappingHighWater`. A process figure is `null` where the platform cannot be asked, and `descriptorLimit` is `0` where no descriptor limit is enforced.
- `builds` counts builds since startup by how they ended. A cancelled build is one somebody asked to stop, not a failure.
- `erasure` is the graph's erasure backlog across every namespace on disk, resident or not. Compare `oldestAgeMs` against `excalibur.graph.erasure-max-latency-seconds`; `overdueNamespaces` is how many have already missed it. `unreadableNamespaces` are namespaces whose log could not be read, and so are in none of the other figures. `oldestAgeMs` is `null` when nothing is owed. The block is absent when the graph root cannot be listed, which is not the same as owing nothing.
- `ingest` is present only where ingestion stages facts into the graph (a chat model is configured). `unresolvedDocuments`, `extractionFailures`, `tenantFailures` and `sinkRefusals` are documents that were published and are searchable but whose facts never reached the graph. `sinkRefusals` counts documents the graph sink failed on outright rather than under one of the other three reasons; any non-zero value is worth a look in the log. None of these documents is offered to the graph again unless it changes. Nothing else shows that.

### Reclaim space now

Erasing a document, replacing it on a re-crawl and splitting a segment all leave tombstones
behind: entries that no query can match and every byte of which is still on disk. Background
sweeps reclaim them on a timer — `excalibur.shard.compaction-interval-ms` for segment files,
`excalibur.shard.hnsw-compaction-interval-ms` for vector graphs, and a sweep every tenth of
`excalibur.ingest.lexical-max-latency-seconds` for the lexical chunk index — which is the right
default and the wrong answer when the space is needed before the next sweep. One request
compacts all three.

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
`excalibur.shard.hnsw-compaction-tombstone-ratio`, and for the chunk index
`excalibur.ingest.lexical-tombstone-ratio`, `lexical-max-latency-seconds` and
`lexical-max-segments` — and so brings forward work that would have happened anyway.
`{"force": true}` rewrites every live segment and rebuilds every graph whether or not there is
anything dead in them, and rewrites every live segment of the chunk index, dropping any chunk the
store no longer holds (under `lexical-engine=lucene`, every deletion Lucene is holding is
expunged). That is what to send after erasing a large fraction of the corpus, and otherwise a
great deal of I/O for nothing.

Queries and ingestion continue throughout. A segment is rewritten beside its original and swapped
in at the end, under the same lock the background worker takes, so a manual run and a scheduled
one can never rewrite the same segment twice.

A body that is not JSON or a `force` that is not a boolean is a `400`, a body over the request size
limit a `413`, and any method but `POST` or `DELETE` a `405`.

### Stop a compaction

A forced run rewrites every live segment, rebuilds every graph and rewrites the chunk index, which
on a large corpus is minutes of saturated I/O. One started at the wrong time is stopped here rather than by stopping the
process.

```bash
curl -X DELETE http://localhost:8080/v1/compact \
  -H "Authorization: Bearer $EXCALIBUR_API_AUTH_TOKEN"
```

**Response — `200 OK`**

```json
{"cancelled": true, "message": "Compaction was asked to stop; poll /v1/compact/status for when it has"}
```

`"cancelled": false`, with `"message": "No compaction is running"`, means there was no run in
progress — an answer rather than an error, since the state you asked for is the state you have.
It is charged against the mutate budget like starting one.

The run is *asked* to stop and ends at its next segment boundary, keeping what it had already
reclaimed. That is safe at any point: a rewrite builds its replacement beside the original and
swaps it in only at the end, so what a stopped run loses is its own unfinished work, never data.
The chunk index is rewritten as one replacement rather than segment by segment, so a stop during
its rewrite keeps none of that rewrite: what it had built is discarded and the index is left
exactly as it was. `GET /v1/compact/status` reads `cancelled` once it has ended. Only a run started with
`POST /v1/compact` is stopped; the background sweeps carry on as configured.

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
  "graphs_failed": 0,
  "lexical": {
    "performed": false,
    "segments_rewritten": 0,
    "segments_produced": 0,
    "chunks_removed": 0,
    "groups_abandoned": 0,
    "segments_rebuilt": 0,
    "segments_upgraded": 0,
    "reason": "not due: 12 of 7200 document(s) tombstoned across 4 segment(s)"
  }
}
```

- **`state`** — `idle` before the first run since startup, `running` while one is happening,
  `completed` once it finished, `cancelled` when `DELETE /v1/compact` stopped it — with the counts
  of what it did before it stopped — and `failed` when the run itself raised an error, in which
  case `error` carries the reason and there are no counts.
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
- **`lexical`** is the chunk index's half, present only on a node that keeps one. It is compacted
  after the segment store and reported whatever the store's half came to, so `state` and `error`
  describe the store alone. `performed: false` with a `reason` is the index's policy finding
  nothing due, as above, or a run stopped before it reached the index or during its rewrite; with an `error` instead of
  counts, the index's compaction failed. **`segments_rewritten`** and **`segments_produced`** are
  lexical segments in and out, and **`chunks_removed`** is the tombstoned chunks — and chunks the
  store no longer holds — that did not survive the rewrite. **`groups_abandoned`** is groups of
  segments the rewrite left untouched because a surviving chunk's text could not be read back from
  storage just then; they keep their dead weight, and any erasure owed in them stays owed for the
  next compaction. When every group was abandoned nothing was published: `performed` is `false`
  and the `reason` begins `nothing published:`. When only some were, `performed` is `true` and the
  `reason` begins `partial:`. A `DELETE /v1/compact` arriving while
  the index is being rewritten ends the rewrite at its next checkpoint — the next group of
  segments or the next chunk — rather than interrupt I/O the index's queries share. What it had
  built is discarded and nothing is published, so the index is exactly as it was: the run reads
  `cancelled` and `lexical` reads `performed: false` with a `reason` beginning `stopped:`.
  **`segments_rebuilt`** and **`segments_upgraded`** are the rewritten segments
  chosen for what they were: damaged ones, searchable again, and ones in an older format, now in
  the current one. Every compaction takes these on whatever the policy says — every rebuildable
  damaged segment, and up to the merge fan-in of older-format ones, oldest first; a forced run
  upgrades all of them.

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
| `excalibur_http_rejections_total{reason="throttled"\|"unauthenticated"}` | counter | Rate-limit and capacity refusals, and authentication failures, counted rather than left to the log |
| `excalibur_queries_executed_total`, `excalibur_entries_ingested_total`, `excalibur_ingestion_rate_entries_per_second` | counter, gauge | Query and ingestion throughput |
| `excalibur_queries_active`, `_registered_total`, `_cancelled_total` | gauge, counter | What is running now; the same figures `/v1/query/status` serves |
| `excalibur_segments{state=...}`, `excalibur_segments_total`, `excalibur_entries_total` | gauge | The segment lifecycle and corpus size |
| `excalibur_coverage_ratio`, `excalibur_visibility_epoch`, `excalibur_visible_segments` | gauge | Visibility |
| `excalibur_hnsw_queue_depth`, `_limit`, `excalibur_hnsw_vectors_integrated_total` | gauge, counter | Vector integration backlog |
| `excalibur_cuckoo_*` | gauge, counter | Membership filter saturation, live false-positive rates, and filter entries of tombstoned segments pending and removed |
| `excalibur_volume_bytes_total`, `_used`, `_available`, `excalibur_volume_count` | gauge | Disk |
| `excalibur_index_orchestrator_running`, `excalibur_segments_without_vector_index`, `excalibur_circuit_breaker_state{breaker="embedding"\|"llm_relevancy"\|"query_expansion"\|"graph_extraction"\|"graph_rerank"\|"graph_query_embedding"\|"graph_build_embedding"}` | gauge | The three partial failures described under Stats. Each breaker is `0` closed, `1` half-open, `2` open, so `> 0` is "this node is not using that part of its provider". A label is present only where the node has that component: `query_expansion` with a chat model behind query expansion, `graph_extraction` with a chat model behind relation extraction, `graph_rerank` once a graph that reranks with the chat model has been opened, `graph_query_embedding` once a graph built with entity vectors has, `graph_build_embedding` once a graph build has embedded an entity. The graph three carry no namespace label - every namespace shares them - so the series count stays at seven however many tenants the node serves |
| `excalibur_lexical_*` | gauge | Chunk index size and the erasure backlog, including `_oldest_erasure_age_seconds` |
| `excalibur_lexical_chunks{state="live"\|"erased"\|"unsearchable"}` | gauge | Chunks by state; the three sum to everything the index holds. `unsearchable` is held in a segment that could not be opened and matched by no query |
| `excalibur_lexical_unreadable_segments`, `excalibur_lexical_outdated_segments` | gauge | Lexical segments that could not be opened, and segments in an older format awaiting rewrite. Above zero on the first is damage: alert on it |
| `excalibur_lexical_unreadable_chunks_dropped_total` | counter | Chunks a compaction dropped from the native index because their stored text was corrupt. Any increase means a damaged storage segment; alert on it |
| `excalibur_query_unnamed_results_dropped_total` | counter | Vector hits dropped from `/v1/query` answers because their graph node has no document identity. Any increase means a segment's node-to-document mapping is missing entries; alert on it |
| `excalibur_query_cache_lookups_total{result="hit"\|"miss"}`, `_evictions_total`, `_invalidations_total`, `excalibur_query_cache_entries`, `_capacity` | counter, gauge | Present only on a node with `excalibur.query.cache.enabled=true`. Hits and misses share one family so a hit ratio cannot be computed from a stale pairing. Evictions and invalidations are separate because they call for different actions: evictions mean the cache is too small for the traffic and `max-entries` is the answer, invalidations mean the corpus moved and no size would have helped |
| `excalibur_graph_namespaces_open`, `_open_limit`, `excalibur_graph_namespace_evictions_total` | gauge, counter | Graph tenants open, the open bound, and evictions |
| `excalibur_graph_build_running`, `excalibur_graph_builds_total{outcome="completed"\|"failed"\|"cancelled"}` | gauge, counter | Graph builds |
| `excalibur_mappings{scope="graphs"\|"process"}`, `_high_water`, `_limit`; `excalibur_descriptors{scope=...}`, `_high_water`, `_limit` | gauge | The tenant budget. `scope="process"` is omitted where it cannot be measured, and the descriptor mark and limit where no limit is enforced |
| `excalibur_graph_erasure_obligations`, `excalibur_graph_erasure_namespaces{state="owing"\|"overdue"\|"unreadable"}`, `excalibur_graph_erasure_oldest_age_seconds` | gauge | The graph erasure backlog, totalled across namespaces |
| `excalibur_graph_ingest_documents_total`, `_facts_staged_total`, `excalibur_graph_ingest_failures_total{reason="unresolved_source"\|"extraction"\|"namespace_unavailable"\|"sink_refused"}` | counter | Documents whose facts did not reach the graph |
| `excalibur_node_draining`, `excalibur_uptime_seconds`, `excalibur_build_info{version,jvm}` | gauge | Whether an operator has drained the node, how long it has been up, and which build it is |

Five alerts worth having, in ascending order of how quietly the condition fails:

```
rate(excalibur_http_responses_total{class="5xx"}[5m]) > 0.01 * rate(excalibur_http_responses_total[5m])
excalibur_index_orchestrator_running == 0
excalibur_mappings{scope="process"} / excalibur_mappings_limit > 0.9
excalibur_segments_without_vector_index > 0
excalibur_graph_erasure_namespaces{state=~"overdue|unreadable"} > 0
```

#### JMX

Seven MBeans are published under the `com.eonmux.excalibur` domain, for a local VisualVM or
JConsole session attached to the process. They cover a subset of what `/metrics` serves, not all
of it:

| MBean | Attributes |
|---|---|
| `type=QueryLatency` | `count`, `p50`, `p90`, `p95`, `p99`, `p99.9`, `average`, `minimum`, `maximum`, `overflowCount` — milliseconds |
| `type=SystemHealth` | `health`, `isHealthy`, `ready`, `coverage` |
| `type=IngestionQueue` | `depth`, `percent`, `isUnderControl`, `isCritical`, `isFull` |
| `type=HnswQueueDepth` | `depth`, `percent`, `isUnderControl`, `isCritical`, `isFull`, and the tail-scan figures `averageTailScan`, `maxTailScan`, `totalTailScanned`, `totalVectorEntriesFound` |
| `type=VisibilityEpoch` | `epoch` |
| `type=CuckooFpRate` | `fpRate`, `fpRateEpoch`, `fpRateCount`, `avgFpRate`, `maxFpRate`, `currentEpoch` |
| `type=IngestionThroughput` | `entriesIngested`, `queriesExecuted`, `ingestionRate`, `peakIngestionRate` |

Everything else in the table above is served by `/metrics` only: HTTP responses and rejections,
the active, registered and cancelled query counts, segments by state, corpus size, visible
segments, volumes, the index orchestrator, segments without a vector index, the circuit breakers,
the lexical index, the query cache, every graph family, the tenant budget, draining, uptime and
build info. JMX is **not** reachable from outside the
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
catalog, the routing directory, the chunk index, the graph, the document registry, the source
catalog and the source status history. Nothing is kept anywhere else, so backing the node up is copying that one directory and
restoring it is putting the directory back.

`excalibur-backup` does that copy and, unlike `cp`, can prove afterwards that it worked.

```bash
./gradlew :excalibur-backup:installDist
B=excalibur-backup/build/install/excalibur-backup/bin/excalibur-backup

# 1. Stop the node and wait for the process to exit.
#    Under a supervisor, stop it through the supervisor: `systemctl stop excalibur` and
#    `docker stop excalibur` both return only once the process has exited, and a signal sent
#    around them gets the node restarted under you.
#    Started by hand, find it by either way it can be launched. bin/excalibur-api and
#    ./gradlew :excalibur-api:run name the main class; `java -jar excalibur.jar` names only the
#    jar. The MCP server is launched with `-cp excalibur.jar`, not `-jar`, so it never matches.
#    No pid file is written, so exactly one match is required rather than guessed at.
pids="$(pgrep -f -- 'com\.eonmux\.excalibur\.api\.ExcaliburApplication( |$)|-jar ([^ ]*/)?excalibur\.jar( |$)')"
[ "$(printf '%s\n' "$pids" | grep -c .)" -eq 1 ] \
  || { echo "expected one node, found: ${pids:-none}"; exit 1; }
kill -TERM "$pids"
#    Up to excalibur.api.shutdown-timeout-seconds (30 by default) while it drains and publishes.
while kill -0 "$pids" 2>/dev/null; do sleep 1; done

# 2. Take the image.
$B snapshot --data-dir /var/lib/excalibur/data --out /backups/excalibur-$(date +%F)

# 3. Restart.
EXCALIBUR_DATA_DIR=/var/lib/excalibur/data java --enable-preview \
    --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector -jar excalibur.jar
```

The image is self-describing: the payload, plus a manifest naming every file with its length,
its CRC32C and its SHA-256, the format versions, and the epoch the image is coherent at. Verify
it before you need it, not after:

```bash
$B verify --snapshot /backups/excalibur-2026-09-14 --deep
```

`verify` checks in both directions — every named file must be present and match, and every
present file must be named — so a file added to or removed from the image is caught as well as
one that rotted. `--deep` additionally compares SHA-256, and hands every file to the checker for
its format, which is what turns "these bytes are unaltered" into "these segments and graphs will
open" — a checksum proves only that a file is the one that was copied, so damage already there at
the copy verifies faithfully without it:

- Segments and `catalog.dat`, HNSW graphs and their document mappings, quantiser codebooks and
  LSH tables go to the engine's own checkers, on a scratch copy, because several of them repair
  what they open. Proven means every entry's checksum, the catalog's header and record CRCs, or
  the seal a clean close writes.
- Everything else goes to `excalibur-fsck`'s checkers, read-only, where the file lies — the same
  dispatch `excalibur-fsck` surveys a live directory with. Write-ahead logs have every record
  replayed against its checksum; the lexical store is checked as one structure, every column of
  every segment its manifest names; the graph erasure ledger has every record checked; a segment's
  term sketch (`segment_<N>.tfs`) has its header and body checked against their CRC32Cs. A damaged
  sketch is reported and does not fail the image, because a node restored from it rebuilds the
  sketch when it opens the segment; one that cannot be read at all does fail it. A compaction's
  staged pair, `segment_<N>.hnsw.compact` and `segment_<N>.hnsw.compact.docmap`, comes here too,
  though each is in a format the engine checks: while both are there the compaction never
  swapped, the node discards the pair on open, and a torn pair is a leftover that does not fail
  the image; a staged mapping alone is the one the swapped graph matches, and is verified.
- Some formats carry nothing to prove them by: `routing.dir`, the document registry and source
  catalog (whose record count is checked, not their contents), the in-flight journal, the lexical
  store's sidecars, and the graph triple, entity and label logs, whose only open path writes.

The report counts the structures proven and lists every one that was not, with why, rather than
leaving them out, which would read as though nothing went unverified.

Restoring verifies the image first, builds the tree beside the target, and renames it on in one
move. It verifies to the same depth `verify` does by default — every length and CRC32C — and
`restore --deep` adds the SHA-256 comparison and the format checkers, which is worth the time
whenever the image has not already been through `verify --deep`:

```bash
$B restore --snapshot /backups/excalibur-2026-09-14 --target /var/lib/excalibur/data --deep
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
built edge store, the quantiser codebooks, the segments' term sketches — at the cost of a reindex
and a graph build on the far side; the sketches the node rebuilds by itself as it opens each
segment. The HNSW graphs and their document mappings are never left out, derived though they
are, because nothing rebuilds them: deleting a `.hnsw` gives an empty graph attached to a
populated segment, not a repopulated one. A mapping an interrupted compaction staged
(`segment_<N>.hnsw.compact.docmap`) is kept with them for the reason below. The write-ahead logs
are never left out either.

Two things are never copied, and each is reported under `omitted` with the reason: staged names a
crash left behind, which nothing reads, and JVM heap dumps (`*.hprof`), which are a record of a
process rather than state and roughly the size of its heap.

`excalibur-backup <command> --help` prints that command's options and the exit codes, and exits
`0` without touching anything.

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

Without `--repair`, no file is opened for writing. Most are mapped read-only — enforced by the
kernel, because several of the engine's own open paths stamp headers or migrate formats on the
way in — and the rest are streamed past a digest without being mapped at all, which is what lets a
file too damaged to open still be judged. Each is reported as one of:

| Status | Means |
|---|---|
| `INTACT` | Bytes were weighed against a stored checksum and matched. The only status that is a claim of soundness |
| `CORRUPT` | Bytes were weighed against a stored checksum and did not match |
| `UNSEALED` | A writer still holds it. Deliberately not the same statement as intact |
| `LEGACY_UNPROTECTED` | Structure proven whole, but the format carries no checksum, so the contents cannot be verified |
| `UNCHECKED` | Not a format this tool knows, or one with no read-only replay. Not evidence of damage and not evidence of health |
| `MISSING` / `UNREADABLE` | Named but absent, or a device or permission problem |
| `NOT_DATA` | A JVM heap dump (`*.hprof`) a node wrote into the directory. Reported with its size and never read: not engine state, so it neither passes nor fails anything, `--strict` included. Point `-XX:HeapDumpPath` elsewhere and move it there |

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

Two derived files are judged by what the node does with them rather than by name alone:

- **`segment_<N>.tfs`, a segment's term sketch**, is the one derived file the node rebuilds by
  itself, from the segment's live entries, whenever it opens the segment and finds the sketch
  missing, stale or damaged. A sketch that fails its checksum is reported `CORRUPT` and not
  counted as damage: it does not set exit code `1` or fail `--strict`, the summary counts it
  separately (`corruptRebuiltOnOpen` in `--json`, where each file also carries `damage`), and the
  repair plan moves it aside. Only corruption is excused: a sketch the operating system will not
  read — an I/O error, a permission refused — is `UNREADABLE` and fails the run, because that says
  something about the device rather than about one derived file. Every other derived file is
  rebuilt only by a reindex or a retrain, so its damage fails the run. A sketch whose segment is gone is an orphaned index like any other,
  and a stranded `segment_<N>.tfs.<id>.tmp` is a staging leftover.
- **`source-status`, how each source's last crawl ended**, is not derived and nothing rebuilds it,
  but nothing the node serves depends on it either: a node that cannot trust it logs that, treats
  the history as empty, and reports each source from its next run. So a `source-status` whose
  record count disagrees with its records is reported `CORRUPT`, counted with the sketches under
  `corruptRebuiltOnOpen` rather than as damage, and moved aside by the repair plan — what is lost
  either way is the history, never indexed data. As with a sketch, one the operating system will
  not read is `UNREADABLE` and fails the run.
- **`segment_<N>.hnsw.compact.docmap`, a compaction's staged mapping**, means one of two things.
  Beside `segment_<N>.hnsw.compact`, the compaction never swapped its graph in: both are staging
  leftovers the node would discard, and the repair moves the mapping aside before the graph —
  the other order, interrupted between the two, would leave a mapping the node reads as a
  finished swap. Alone, the swap completed and this is the only mapping that matches the graph;
  the node installs it over `segment_<N>.hnsw.docmap` on its next open. It is then verified,
  reported as `compacted-mapping-pending` (a warning, not damage), and never moved by a repair.

Repair is off by default — without `--repair` the plan is printed and nothing is touched. With it,
the tool writes, stating each write before it makes it, and **no repair ever deletes anything**:

- A damaged file, an index whose segment is gone, or a staged file a rename never published is
  moved into a quarantine directory byte for byte, so the action is reversible by hand.
- A `catalog.dat` row naming a segment file that is no longer there is marked purged — written in
  place, through the engine's own catalog code — rather than cut out.
- A write-ahead log ending in a torn record, an append that never completed, has that record's
  opcode cleared in place: the one-byte write the log itself makes when an append fails its fsync,
  after which the log reads as ending before it.

The quarantine is a timestamped directory beside the data directory unless
`--quarantine-dir=<path>` names another; the default needs the data directory's parent to be
writable. A quarantine on another filesystem works as well: a rename cannot cross, so each file is
copied, forced to disk and compared byte for byte before the original is removed. What it refuses
to attempt matters as much:

| Left for an operator | Why |
|---|---|
| A damaged `catalog.dat` | Segment files are rediscovered on start-up, but lifecycle state, integrated offsets, source ids and epochs live only there. A node that starts without them looks like a first start |
| A damaged `routing.dir`, or a slot pointing at a missing segment | Which hash slot belongs to which segment after a split is not derivable from file names. That is why the directory is written down |
| A write-ahead log damaged in the middle | The records behind the damage were fsynced and acknowledged. Trimming past them would destroy committed writes, and so would moving the file aside — nothing rebuilds a log the way a reindex rebuilds an index |
| A damaged `segment_<N>.hnsw.compact.docmap` with no `.hnsw.compact` beside it | The graph already holds the compacted numbering, and without this mapping it would be served through the pre-compaction one — wrong documents rather than an error. Restore the graph and both mappings together, or reindex the segment |
| A damaged lexical store `manifest` | A store without its manifest opens as a new, empty index and deletes every segment the empty manifest does not name. Restore the manifest from a backup, or remove the whole `chunk-index` directory and reindex |
| Anything `UNREADABLE` | A device or permission problem, where nothing is known about the contents |

Exit codes: `0` no damage, `1` damage found, `2` the command line was wrong, `3` the check itself failed — which says nothing about the directory and must not be read as `0`.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Server is not configured for text queries: this node has neither an embedding client nor a lexical index` | Not a missing API key on its own — without one the node still answers from BM25. Both retrieval paths are gone, so the chunk lexical index also failed to open | Search the startup log for `Chunk lexical index unavailable` and fix what it names (usually the data directory); set `EXCALIBUR_LLM_API_KEY` as well if you want vector retrieval |
| Results look like noise / no relevant matches | The `excalibur.llm.embedding-model` config changed between ingestion and query runs | Reingest with the current model — old vectors are incompatible once the embedding model changes |
| `Field 'query' is required and must be non-empty` | Request body is missing the `query` field | Send the query text in the `query` field; the HTTP API does not accept raw vectors — see [Why no client-side vectors?](#why-no-client-side-vectors) |
| `hnsw.queueDepth` keeps growing | Integration worker falling behind | Check logs for errors; slow down ingestion; increase hardware |
| All LLM calls failing | A circuit breaker tripped. Embeddings, relevancy scoring, query expansion, relation extraction, the graph rerank, the graph question embedding and the graph build's entity embedding each have their own, so check which on `breakers` in `/v1/stats`: relevancy scoring stopping while ingestion continues is `llmRelevancy`, the reverse is `embedding`, queries losing their expansions is `queryExpansion`, a graph that stopped growing is `graphExtraction`, graph answers no longer reranked by the model is `graphRerank` (or `graphQueryEmbedding` for the vector rerank), and a build that published entities without vectors is `graphBuildEmbedding` | Wait for the cooldown (default 60s), after which one probe is admitted. Confirm API key/endpoint. Queries still work without LLM scoring. |
| Queries return nothing on a fresh node, but `coverage` reads `1.0000` | Not a cold-start indicator. `/health` coverage counts the segments a query reads, and an empty segment is read as readily as a full one, so it reads `1.0000` and `/health` reports `ready` and `healthy` | Look at `/v1/stats` instead: `entries.total` and `segments.active` are `0` until something is indexed |
| A node answers `200` to everything but its results are getting worse | A circuit breaker has opened: the embedding one drops retrieval to lexical-only, the relevancy one bypasses stage-5 re-ranking, the query-expansion one searches every query as typed, and on the graph route the rerank one keeps the BM25 order and the question-embedding one skips the vector rerank; the graph build-embedding one publishes a build's entities without vectors | `breakers` on `/v1/stats`, or `excalibur_circuit_breaker_state` on `/metrics`. Alert on `> 0` for any `breaker` label |
| `hnsw.queueDepth` grows without bound while `coverage` stays `1.0000` and `/health` stays healthy | The index orchestrator is dead | `workers.indexOrchestratorRunning` on `/v1/stats`, `indexerRunning` on `/health`, and `/health/ready` answers `503` with `indexer` in `failed` |
| Vector search returns fewer results than the corpus should hold, with no error | One or more segments came up without their vector index and are serving key-value data only | `vectorIndex.segmentsWithoutIndex` on `/v1/stats` names each segment and why |
| A rolling restart drops in-flight requests | The load balancer was still choosing the node when it stopped | `POST /v1/drain`, wait for in-flight work to finish, then restart. `/health/ready` answers `503` for the duration |
| A source keeps being re-crawled after you erased its documents | Document-scoped erasure does not deregister the source; only `scope: "source"` does | `DELETE /v1/ingest` to stop the crawl, or erase with `scope: "source"` |
| `POST /v1/erasure` returns 404 | The route is not registered, which the packaged server never does - it wires an erasure service on every start, whether or not the ingestion pipeline came up. So this is either a path that is not quite `/v1/erasure`, or a host embedding `ExcaliburDBServer` directly and never calling `withDocumentErasure` | Check the startup log's registered-endpoint line: it names `/v1/erasure` when the route is there |
| `/v1/erasure` reports `documents_not_found` for a document you can query | Its registry entry is gone, so the chunk ids it named are unknown; the chunks are still in storage | Reindex the source, which re-registers the document, then erase it |
| A source stops being re-crawled after a restart, and `/v1/ingest/status` does not list it | Its target is no longer allow-listed, so restoring it was refused; the startup log names it | Put the root or host back on `excalibur.ingest.allowed-roots` / `excalibur.ingest.allowed-hosts`, or `DELETE /v1/ingest` if the source is finished |
| A re-crawl reingests everything and tombstones nothing | `<data dir>/document-registry` is missing, so change detection reads every document as new | Restore the data directory whole; the registry belongs with the segments it describes |
| Node fails to start with `RegistryLoadException` naming a record count | `<data dir>/document-registry` was cut short by a crash, so it omits documents the node is still serving. It is refused rather than loaded, because a shorter registry silently makes those documents unerasable and unreindexable | `excalibur-backup restore` from the last image. Deleting the registry also starts the node, at the cost of a full reingest on the next crawl |
| A node refuses to start after a crash or a full disk, naming a file | Something under the data directory is damaged. Which file and how badly is the question, and guessing at it is how a recoverable node gets deleted | Stop the node and run `excalibur-fsck <data dir>`. Without `--repair` it only reads, and names what is damaged. `--repair` then writes: it quarantines what can be moved aside safely, marks purged a catalog row whose segment file is gone, and clears the opcode of a torn last log record — and leaves a damaged catalog or routing directory for you |
| A directory you restored by hand serves wrong or missing results | A partial copy: a `.hnsw` without its `.docmap`, a `*.compact` without its pair, or a sparse copy that dropped a file | `excalibur-fsck <data dir>` names the orphans and the mismatches. Take images with `excalibur-backup` instead, which classifies every file and can verify the result |
| A `WARN` at startup naming documents that "were being ingested when this node last stopped" | Those documents were half written when the node died, and their chunks are hidden until the ingest is redone. Not an error: it is the crash being cleaned up after | Nothing — the next crawl of that source re-ingests them and they become visible again. `POST /v1/erasure` removes them instead |
| `lexical.oldestErasureAgeMs` exceeds `lexical-max-latency-seconds` | The sweep that enforces the bound runs every tenth of it, so a reading just past the bound is ordinary; one far past it means the sweep is failing, and the node's log says why | Read the log for a failed compaction. Ingesting or reindexing anything also settles the backlog before the run finishes |
| `lexical.unreadableChunksDropped` is above zero, or `excalibur_lexical_unreadable_chunks_dropped_total` rose | A compaction found chunks whose stored text fails its checksum or cannot be decoded, and dropped them from keyword search so the rest of their segments could be rewritten. The node's log names each chunk at ERROR | Check the disk under the data directory, run `excalibur-fsck` with the node stopped, and reindex the named chunks' documents from their source |
| `excalibur_query_unnamed_results_dropped_total` rose, or query answers are `degraded` at full coverage with a WARN naming segments whose results "carry no document identity" | Those segments' `.docmap` is missing or damaged, so their graph nodes name no document and cannot be returned | Run `excalibur-fsck` with the node stopped to confirm which mapping is damaged. Nothing rebuilds a mapping in place, and a compaction only re-keys the one it finds: restore the graph and its mapping together from an `excalibur-backup` image, or reindex the sources whose documents that segment holds |
| `lexical.unreadableSegments` is above zero, or `excalibur_lexical_unreadable_segments` is | A lexical segment failed its checksum or lost a file, so its chunks (`unsearchableChunks`) match no keyword query. The node's log names it at `ERROR` as it opens, with the reason | If `lexical.unreadable` says `rebuildable: true`, nothing: the next compaction rebuilds it, and `segments_rebuilt` on `/v1/compact/status` reports it. Otherwise its key table is damaged too — check the disk, and reindex the documents of the sources it served |
| A re-crawled document appears twice in results | Not the chunk index — it replaces a chunk identifier rather than appending. Check whether the source is listing one document under two ids | Compare the `document_id` values on the two hits — that is the chunk identifier, `source:document:chunk:N`: identical means a bug, different means the source |
| Queries slow down on a large corpus, and `/v1/stats` reports `cuckoo.saturated: true` | The membership filter is full. A level that cannot record a term is no longer trusted to rule a segment out, so pruning is off and every query scans every segment | Raise `excalibur.filter.corpus-capacity` past your term count and restart |
| Node refuses to start: `Refusing to create segment N in <dir>: <other dir> already holds segment_N.dat` | Two volumes hold a file for the same segment index, usually from a volume set that changed between runs on an older build | Move or delete the stale copy — the one whose directory the catalog does not name — then restart |
| Node refuses to start naming two `excalibur.shard.volumes` entries that resolve to one directory | A symlink, a relative path or a `..` in the list makes two entries the same directory, which would give it two shares of the ring | Drop one of the two spellings |
| Node fails to start with `CatalogLoadException` | `<data dir>/source-catalog` was written by a build older than the end marker, or was cut short by a crash | [Upgrading a data directory](#upgrading-a-data-directory-written-by-an-older-build) — append the marker, or delete the file and re-register the sources |
| A `WARN` at start counting lexical segments written in an older format | They predate `index.meta` version 3, so they carry no column checksums; one older than version 2 also prunes against a looser bound and is read more slowly. Results are exact | Nothing: compaction upgrades a few on each pass, and the warning stops when none are left. `POST /v1/compact` with `{"force": true}` upgrades all of them at once |
| `--enable-preview` error | Wrong Java version | Java 21 exactly — a preview class file is refused by any other JDK |
| `--enable-native-access=ALL-UNNAMED` warning | Missing flag | Required for Panama FFM API |
| Port already in use | Another process on 8080 | `EXCALIBUR_PORT=9090 java ...` |
| Process exits 1 with no message at all | A fatal startup error was lost before the async log appender drained | The shipped `logback.xml` declares `<shutdownHook/>` so the appender drains before exit; on a modified `logback.xml`, check that it is still declared |

### Upgrading a data directory written by an older build

Three on-disk formats differ from those older builds wrote. A node reads the old and the current form of each, but one of them needs a manual step.

**`<data dir>/source-catalog` — a manual step is required.** The catalog of registered ingestion
sources ends with a marker line, `\#end|<count>`, written after the records are forced to
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

**`<data dir>/document-registry` — no step required.** The registry carries the same header and
end marker, for the same reason: without them a registry cut short by a crash is a file of whole
records that quietly omits documents, leaving their chunks in storage where no erasure or reindex
can name them. A registry from an older build has neither line, is read as the older format, and is
rewritten with a version and a count as the node starts, without waiting for something to be
ingested; if that save fails, the start continues with a `WARN` and the next save retries it. From
then on a short registry fails the start instead of loading, and the message names the count it
expected.

The `ETag` a record keeps for an HTTP document is an optional last field, and the format stays
version 1 with it: the previous build reads the first seven fields of a record and ignores the rest,
so rolling back to it keeps the registry and loses only the tags. A document whose record has no
tag gains one the first time a re-crawl finds it unchanged.

**The native lexical index — no step required.** `index.meta` is version 3, which adds a length
and a CRC32C for every column; a segment written by an older build keeps opening and keeps
returning exactly the same results, but without checksums damage to it goes undetected, and
`excalibur-fsck` reports it *legacy, unprotected*. A segment older than version 2 also stored each
postings block's score bound computed against that segment's own mean document length, which is
not the mean a query across several segments scores with, so such a bound could be exceeded and a
document that belonged in the top-k silently dropped. Version 2 and later store the ingredients
instead and compute the bound at query time; a segment older than that ignores its stored bounds
and prunes against the looser saturation ceiling — exact, and slower — and logs a `WARN` naming
itself at open. Expect those warnings after the upgrade; they are not data loss.
`lexical.outdatedSegments` on `/v1/stats` counts the segments still in an older format. Every
compaction rewrites a few of them, oldest first, up to the merge fan-in — including the one the
node's sweep asks for when nothing is being ingested — so they are upgraded without being asked
for, and `segments_upgraded` on `/v1/compact/status` reports each pass. `POST /v1/compact` with
`{"force": true}` upgrades all of them at once.

**`<data dir>/source-status` — no step required.** A new file rather than a changed one: an
older build never wrote it, so a directory it left has none, and every source reports from its
next run exactly as it did before the upgrade. The file appears when the first run ends.

### Enabling debug logging

At startup, set `EXCALIBUR_LOG_LEVEL`:

```bash
EXCALIBUR_LOG_LEVEL=DEBUG java --enable-preview --enable-native-access=ALL-UNNAMED \
    --add-modules=jdk.incubator.vector \
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
    implementation("com.eonmux:excalibur-core:1.0.0-SNAPSHOT")    // ForeignMemoryBootstrap
    implementation("com.eonmux:excalibur-store:1.0.0-SNAPSHOT")   // StorageEngine, HnswInitializer
    implementation("com.eonmux:excalibur-vector:1.0.0-SNAPSHOT")  // distance functions, HNSW config
    implementation("com.eonmux:excalibur-shard:1.0.0-SNAPSHOT")   // IndexOrchestrator
    implementation("com.eonmux:excalibur-llm:1.0.0-SNAPSHOT")     // EmbeddingClient
    implementation("com.eonmux:excalibur-ingest:1.0.0-SNAPSHOT")  // IngestionOrchestrator
    implementation("com.eonmux:excalibur-query:1.0.0-SNAPSHOT")   // QueryPipeline
}
```

Each is named directly because the example below uses a class from each. Some of them would
arrive anyway, since a few modules expose theirs as `api` (`excalibur-vector` hands on
`excalibur-core`, for one). Most declare their dependencies as `implementation`, which a consumer
sees at run time and not at compile time, so relying on what happens to be exposed would break
when a module's internals change. These seven are also enough: `excalibur-integration`'s
`embeddedExampleTest` compiles and runs the example against exactly this list, through the same
variants these POMs give a consumer, and nothing else.

Gradle substitutes each coordinate with the project in the included build. The consuming project
must also compile and run on Java 21 with `--enable-preview`, for the reason in
[Requirements](#requirements).

### Ingesting and querying in-process

The example opens a data directory, ingests three documents and queries them, assembled in the
order the server's own bootstrap uses. It is not a sketch: it is the body of
`EmbeddedUsageExampleTest.ingestAndQuery` in `excalibur-integration`, which compiles it against the
current API and runs it on every build, against an in-process stand-in for the embeddings
endpoint. A test beside it compares the block below with that method, token for token, so neither
can change without the other.

```java
import com.eonmux.excalibur.core.memory.ForeignMemoryBootstrap;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.source.InlineSource;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.HnswInitializer;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

static List<QueryResultItem> ingestAndQuery(Path dataDir, String embeddingEndpoint)
        throws Exception {
    // First statement, before anything can start a thread.
    ForeignMemoryBootstrap.ensureInitialized();

    // The embedding width and the distance metric are properties of the corpus: ingestion and
    // queries must use the same model, width and metric for the lifetime of dataDir.
    int dimension = 1536;
    DistanceFunction metric = CosineSimilarity.getInstance();
    EmbeddingClient embeddingClient = new EmbeddingClient(LLMClientConfig.builder()
        .baseUrl(embeddingEndpoint)                  // e.g. "https://api.openai.com"
        .apiKey(System.getenv().getOrDefault("OPENAI_API_KEY", ""))
        .embeddingModel("text-embedding-3-small")
        .embeddingDimension(dimension)
        .build());

    try (StorageEngine storage = new StorageEngine(dataDir)) {
        // Installed before start(), so every segment start() opens or creates has a graph.
        storage.setHnswInitializer(new HnswInitializer(HnswConfigFactory.defaults(), metric,
            dimension, HnswInitializer.DEFAULT_MAX_NODES, storage.arena()));
        storage.start();

        try (IndexOrchestrator indexing = new IndexOrchestrator(storage.getCatalog(), storage)) {
            // One visibility clock for storage, ingestion and queries.
            storage.setVisibilityTracker(indexing.getVisibilityTracker());
            indexing.syncSegmentsFromStorage();
            indexing.start();
            storage.startLifecycleManager();

            DocumentVisibilityTracker documents = new DocumentVisibilityTracker(
                new DocumentRegistry(), indexing.getVisibilityTracker());
            IngestionOrchestrator ingestion = new IngestionOrchestrator(
                new TextChunker(), embeddingClient, storage, indexing,
                sourceId -> new IngestionOrchestrator.IngestionStatus() {
                    @Override
                    public void update(String state, double progress, long ingested,
                                       long pending) {
                    }

                    @Override
                    public void setSkipped(long skipped) {
                    }

                    @Override
                    public void setDisallowed(long disallowed) {
                    }

                    @Override
                    public void setError(String error) {
                        System.err.println(sourceId + ": " + error);
                    }
                },
                documents);

            QueryPipeline pipeline = new QueryPipeline(
                storage,
                indexing.getVisibilityTracker(),
                new DocumentVisibilityTrackerAdapter(documents),
                segmentId -> {
                    Segment segment = storage.getSegment(segmentId);
                    return segment != null ? segment.getHnswGraph() : null;
                },
                metric);

            try {
                // Ingest: chunk, embed, write, publish. Returns once the documents are
                // published; results are named "<sourceId>:<documentId>".
                ingestion.ingestNow(InlineSource.of("handbook", List.of(
                    text("tiering.txt",
                        "Cold segments are moved to cheaper disks by the tiering policy."),
                    text("hnsw.txt",
                        "The HNSW graph links every vector to its nearest neighbours."),
                    text("backup.txt",
                        "Backups copy the write log and the catalog to object storage."))));

                // Query: embed the question with the SAME client, then search.
                String queryText = "which policy moves cold segments to cheaper disks";
                QueryRequest request = QueryRequest.builder()
                    .queryText(queryText)
                    .queryVector(embeddingClient.embed(queryText))
                    .topK(3)
                    .timeoutMs(5000)
                    .includePartialSegments(true)
                    .enableLlmScoring(false)
                    .build();
                QueryResponse response = pipeline.executeSync(request, new CancellationToken());

                response.results().forEach(item ->
                    System.out.println(item.documentIdStr() + " -> " + item.score()));
                return response.results();
            } finally {
                // Stop taking work before the storage underneath it closes.
                pipeline.shutdown();
                ingestion.shutdown();
            }
        }
    }
}

static InlineSource.Document text(String documentId, String content) {
    return new InlineSource.Document(documentId, content.getBytes(StandardCharsets.UTF_8),
        "text/plain", 0L);
}
```

What each piece is for:

- **`StorageEngine`** owns the data directory. The `HnswInitializer` must be installed before
  `start()`, or the segments it opens carry no vector index and vector search finds nothing in
  them. `storage.arena()` is the engine's own arena, so the graphs live and die with it.
- **`IndexOrchestrator`** owns the visibility clock and the worker that integrates each written
  vector into its segment's HNSW graph. Handing its clock to the engine, to the document tracker
  and to the pipeline is what makes a published document visible to all three at once.
- **`IngestionOrchestrator.ingestNow`** chunks, embeds, writes and publishes the documents of one
  source, and returns once they are published — the in-process equivalent of `POST /v1/documents`.
  The status callback is where a crawl's progress and errors would be reported; a crawl of a
  filesystem or a website goes through the same orchestrator with a different `Source`.
- **`QueryPipeline`** is built over the same engine and clock, and the document tracker's adapter,
  which hides a document until all of its chunks are published. This 5-argument constructor is
  the plain pipeline; the wider constructors add what the server adds on top — LLM relevancy
  scoring and its coverage threshold, the hierarchical Cuckoo filter for segment pruning, the
  epoch isolation validator and product-quantisation settings — and `withLlmScorer` adds the
  scorer to a pipeline already built.

The pipeline operates on vectors, so the caller embeds the question — with the **same**
`EmbeddingClient`, model and width the corpus was ingested with; the HTTP API does this step
itself. Results come back closest first. Read `documentIdStr()`, not `documentId()`: the string is
the identifier ingestion assigned — `<sourceId>:<documentId>`, so `handbook:tiering.txt` here — and
the one the HTTP API returns; `documentId()` is the numeric handle, which for a string-keyed result
is the HNSW node id and matches nothing a caller can look up. A result whose graph node has no
document identity carries an empty `documentIdStr()`; set `requireNamedResults(true)` on the
`QueryRequest`, as the HTTP API does, to have such results dropped before the page is cut.

Shut down in the reverse of the order things were started: the pipeline and the ingestion
orchestrator first, so nothing is still taking work, then the index orchestrator and the storage
engine, which the `try`-with-resources blocks close.

### Remember when embedding

- **Call `ForeignMemoryBootstrap.ensureInitialized()` as the first statement of your startup,
  before anything spawns a thread.** Two threads reaching `java.lang.foreign` for the first time
  at the same instant deadlock the JVM inside the JDK's own class-initialization monitors, and a
  thread waiting on one of those cannot be interrupted, so nothing times out and the process
  hangs rather than fails. The call is idempotent and costs about 34ms once. The server does this
  in `ExcaliburApplication.main`; an embedder has no such entry point, so it has to do it itself.
  Two threads is all it takes - storage, query, retrieval, graph and shard components hand work
  to virtual threads, so one sequential caller that starts a worker is already a race.
- Always pass `--enable-preview --enable-native-access=ALL-UNNAMED
  --add-modules=jdk.incubator.vector` JVM flags. Without the last one the SIMD distance
  kernels fall back to scalar arithmetic silently.
- HNSW graphs cap at `Integer.MAX_VALUE` nodes per segment.
- Segment counts are power-of-two (extensible hashing on split).
- `StorageEngine` is thread-safe; multiple concurrent queries and ingests are supported.

---

## Further Reading

- [README.md](README.md) — architecture overview, module breakdown, benchmark list, Quick Start
- `excalibur.properties.example` — the tracked configuration template, annotated key by key. The
  working copy `excalibur.properties` is gitignored, because it is the file a key ends up in
- `excalibur-integration/src/test/java/` — end-to-end examples (ColdStart, LiveIndexing, RollingReindex, etc.)
