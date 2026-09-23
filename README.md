# ExcaliburDB

A disk-backed search engine for large document corpora. Every node ranks with BM25 over its own inverted index; a node with an embedding model adds approximate nearest neighbor (ANN) vector search over memory-mapped segments with hierarchical filtering, fused with BM25 by reciprocal rank, and a node with a chat model can re-rank with LLM-based relevancy scoring.

## Overview

ExcaliburDB is built on Java 21's Panama Foreign Function & Memory API for zero-copy, memory-mapped file I/O. It handles vector indexes larger than available RAM by keeping data on disk and navigating graphs directly from memory-mapped regions.

### Key Features

- **HNSW (Hierarchical Navigable Small World) graphs** for approximate nearest neighbor search with configurable efConstruction and efSearch parameters
- **Cuckoo filters** for probabilistic set membership testing with deletion support: a corpus-and-segment hierarchy that tells a query which segments hold its terms, so those are searched first. It orders and never excludes: a vector query's nearest neighbours need not share its words
- **LSH (Locality-Sensitive Hashing)** for vector candidate generation beside the HNSW search: a query's bucket-mates are measured exactly and ranked in with the graph's results, never used to exclude or confine a search. Built per segment on a node that embeds its corpus. A lexical-only node writes none: it would be dead weight on every write, keyed by node ids nothing assigns
- **Epoch-based visibility tracking** for consistent query snapshots during concurrent ingestion
- **LLM integration** for semantic relevancy scoring, with a circuit breaker on each of the chat and embedding paths and a fallback when either is open
- **Queryability guarantee** - the system remains queryable at all times, including during initial indexing and re-indexing operations
- **Memory-mapped CSR graph** alongside the vector index, with a mandatory reverse index, so retrieval can traverse relationships rather than only find similar text. A traversal with no relation filter follows every relation in **both** directions: a question is about an edge, not about the end someone happened to assert it from, so "who did Ada Lovelace collaborate with" and "who did Charles Babbage collaborate with" reach the same fact. A caller wanting one direction asks for it with `RelationSelector.forward` or `reverse` — over HTTP and MCP, with `direction`: `forward`, `reverse` or `both`, the default. When the question names a relation the graph holds — "who **collaborated** with Ada Lovelace" against a `collaborated_with` edge — the traversal narrows to it, in both directions, so the model is handed that relation rather than everything the entity touches. The match is by prefix over letters and digits, so the extractor's casing convention does not matter. It stays a hint: if narrowing finds no edge, the traversal is retried across every relation, because the cues are a fixed English vocabulary matched against names an extractor chose and a hint that lands on the wrong relation must not turn an answerable question into a refusal
- **Bitemporal facts** - every edge carries the interval over which it held in the world, kept strictly separate from the epoch that records when the index learned it, and every traversal is filtered by it. `POST /v1/graph/query` takes an `as_of` date, instant or `all`, so a question is asked at an instant the caller states rather than one inferred from its wording. The node's extractor records the dates a document states for a fact and never guesses one, so an undated fact is valid over all of time. For a relation declared single-valued (`excalibur.graph.single-valued-relations`) the graph builder closes the previous value when a later statement gives a different one. It orders statements by the start date the text gave, else by the day each was stated (the document's own date — file modification time or HTTP `Last-Modified` — else the day it was staged), else by staging order. The new value starts, and the old one ends, on that day, so `as_of` before the change returns the old value and after it the new one
- **Provenance per edge** - source, confidence and extractor version, stored columnar and serialised into the model's context so a curated claim can be told from a guess
- **Staged reranking on the graph route** - BM25 over an embedded Lucene index, then reranked against the entities' uncompressed vectors, then reranked again in one batched call to the chat model; each stage falls back to the ordering beneath it when its endpoint is unavailable. The route has one signal, so its scores are handed through rather than fused - reciprocal-rank fusion over a single list would score every top result alike and leave the abstention thresholds nothing to read. Every stage reports on one unit relevance scale: BM25 as a share of what the question's words could score in this graph, the vector rerank as a similarity (an entity with no vector ranks beneath every one with), and the model as the relevance it judged. It is `/v1/query` that actually fuses two signals
- **Abstention rather than a weak answer** - a sealed result type that can answer, decline, or hand back an ambiguity, rather than returning a weak answer as though it were a strong one. The thresholds a running node applies are configuration (`excalibur.graph.min-*`). `AbstentionCalibrator.calibrate` fits all three together instead, from labelled outcomes you supply - together rather than one at a time, because the gates overlap and combining three separate fits gives a node stricter than any of them measured. It is a library API rather than a server route, because the labels have to come from a query set whose answers are known, and a node cannot manufacture one for itself. The fit also reports where the coverage it gave up went, split between queries handed back as ambiguous and queries refused outright, and prefers the former where the two buy the same precision. The confidence attached to an answer is fitted separately and by a different method: `AbstentionCalibrator.fitTemperature` scales the reported confidence so it can be read as a probability, and `expectedCalibrationError` and `reliability` say how far off it was before and after. A fitted temperature goes into `excalibur.graph.confidence-temperature`, which is the whole of what a node needs to apply it; scaling is monotone and fixes the midpoint, so it changes how strongly an answer is claimed and never which answer is given. Which queries to answer and how strongly to claim them fail independently, so neither fit stands in for the other
- **Erasure that removes data** - `POST /v1/graph/erasure` records an obligation durably, and a compaction rewrites the tombstoned edges out of the store and out of the staged facts it was built from, driven by the oldest outstanding request and not only by how much has accumulated. Erasing only the store would leave the next `POST /v1/graph/build` free to replay the staging log and restore exactly what was erased
- **Per-namespace isolation** - one graph directory per tenant, selected by a request header, with mapping-count and descriptor budgets - `vm.max_map_count` and `RLIMIT_NOFILE`, read from the running process - counted against the whole process rather than the graphs alone — segments, vector indexes and the JVM share the same limits — that evict the least recently used graph before either limit is reached; a request holds a lease, so eviction never unmaps a graph out from under a reader. Ingestion routes by the same header: a source registered with it stages its facts into that tenant, one registered without it into `shared`, and the binding is written into the source catalog so a re-crawl after a restart stages where it did before
- **A graph the node builds** - with `excalibur.graph.extraction-enabled=true` and a chat model, ingestion extracts facts from the documents it publishes and stages them; `POST /v1/graph/build` turns everything staged into the edge store, the dictionaries and the two Lucene indexes, and serves the result without a restart. Extraction is off by default because it is one chat-model call per document, the most expensive thing ingestion can do, and it has to be on *before* the documents are ingested - a build after the fact finds nothing staged
- **An MCP server** - the ingest, search, build and graph-query calls exposed as tools, so a model can be pointed at a directory and then question what was built from it without any adapter code
- **Retrieval without embedding the corpus** - every ingested chunk is indexed into the engine's own inverted index, and a node with no embedding endpoint answers `POST /v1/query` from BM25 instead of refusing the query
- **Hybrid retrieval on the document route** - a node with an embedding model runs BM25 and vector search concurrently on `POST /v1/query` and fuses their rankings by reciprocal rank, so the inverted index it maintains anyway is read rather than merely paid for; `excalibur.query.hybrid-retrieval=false` restores vector-only ranking
- **Query-side semantic expansion** - a chat model turns one question into the vocabulary a matching document would contain (`out of RAM` becomes `OOM killer`, `ENOMEM`, `SIGKILL`, `cgroup memory limit`) and weights each formulation itself, so the semantic work is paid for once per query instead of once per chunk of the corpus. It runs on a node that has a chat model and does not embed its corpus, which is where it replaces work the embeddings would otherwise have done; a node doing both takes it with `excalibur.query.expand-hybrid-queries=true`
- **Text, and only text** - a crawl with no extension filter lists every file under its root, and a file's content type is guessed from its name, so what a document actually holds is decided by decoding it: anything whose decoded characters carry a U+0000, or run a third or more replacement characters, is passed over before it is chunked rather than indexed as several kilobytes of mojibake and paid for at the embedding endpoint. Skipped files are counted into `entries_skipped` on the source's status and are deliberately not errors - a directory holding photographs beside its documents is the ordinary case. There are no format parsers: a PDF or a `.docx` is skipped, not extracted
- **One copy of the corpus** - the chunk index stores postings and not text, reading a chunk's text back from the storage engine on the rare occasion a compaction needs it, so indexing does not double what a corpus costs on disk
- **Erasure that removes chunks too** - a rolled-back or erased chunk is recorded durably, hidden immediately, and physically rewritten out of the index by a compaction driven by the oldest outstanding obligation rather than by accumulated volume

### Documentation

| Where | What it covers |
|-------|----------------|
| [Quick Start](#quick-start) | Build, configure, run, ingest, query — the shortest correct path |
| This README | Architecture, every configuration key, the API surface, the graph store |
| [FAQ](#faq) | The questions a first run actually raises, each answer checked against a running node |
| [USAGE.md](USAGE.md) | Operator's guide: error responses, monitoring, backups, troubleshooting, embedded JVM use |
| `excalibur.properties.example` | The tracked configuration template, annotated key by key |

## Requirements

- **Java 21 — exactly, not 21 or later.** Every module compiles with `--enable-preview` for the
  Panama FFM API, and a preview class file is refused by any JDK other than the one that produced
  it. The Gradle toolchain pins 21 for the build; the runtime has to be 21 as well.
- Gradle 8.5, supplied by the wrapper; `./gradlew` needs nothing installed.
- An OpenAI-compatible endpoint for embeddings is **optional**. Without one the node still
  chunks, stores and lexically indexes every document, and answers `POST /v1/query` from BM25.
  Configure one to add dense retrieval as a second signal — see [Retrieval](#retrieval).

## Quick Start

From a clone to an answer. Everything here runs on loopback without a token, which is the only
shape the server will start in without TLS.

### 1. Build

```bash
./gradlew :excalibur-api:shadowJar
```

`excalibur-api/build/libs/excalibur.jar` is the whole node: one file, no install step.

### 2. Configure

Two things have no usable default, and ingestion does nothing until both are set.

```bash
cp excalibur.properties.example excalibur.properties
```

Name the directory you want indexed, in the copy:

```properties
# Empty means DISABLED, not unrestricted. A requested root_path is canonicalised and must
# resolve inside one of these, so symlinks and ".." cannot escape them.
excalibur.ingest.allowed-roots=/home/you/documents
```

Supply the API key through the environment, never the file:

```bash
export EXCALIBUR_LLM_API_KEY=sk-...
```

`excalibur.properties` is gitignored precisely because it is the file a key ends up in;
`excalibur.properties.example` is the tracked template. A node started without a key still runs, and
still indexes: documents are chunked, stored and term-indexed, `/v1/ingest/status` reports each
source as `idle` once done, and `POST /v1/query` answers from BM25. Only dense retrieval is missing,
which is one signal of several rather than the precondition for having an index at all.

### 3. Run

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED \
     --add-modules=jdk.incubator.vector \
     -jar excalibur-api/build/libs/excalibur.jar
```

It binds `127.0.0.1:8080` and keeps its segments, indexes and graph under `./data`
(`EXCALIBUR_DATA_DIR` moves that). With no `EXCALIBUR_API_AUTH_TOKEN` set, authentication is off
and the startup log says so; binding anything but loopback requires both a token and TLS, and the
server refuses to start rather than serving the network without them.

```bash
curl http://localhost:8080/health
```

### 4. Ingest

```bash
curl -X POST http://localhost:8080/v1/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "source_type": "filesystem",
    "config": {
      "source_id": "my-docs",
      "root_path": "/home/you/documents",
      "included_extensions": [".txt", ".md"],
      "excluded_directories": [".git", "node_modules"]
    }
  }'
```

A path outside `excalibur.ingest.allowed-roots` is answered with 403. An empty allow-list refuses
everything, so a 403 on the first attempt means step 2 was skipped, not that the path is wrong.

Registration returns as soon as the source is recorded; indexing runs in the background. Poll it:

```bash
curl 'http://localhost:8080/v1/ingest/status?source_id=my-docs'
```

```json
{"source_id": "my-docs", "state": "embedding", "progress": 0.7300,
 "entries_ingested": 450, "entries_pending": 170, "entries_skipped": 12,
 "last_error": null}
```

`state` runs `crawling` → `embedding` → `indexing` → `idle`. `unconfigured` means this node's
ingestion pipeline failed to wire at startup; `last_error` says where the reason is.
`entries_skipped` counts the files under the root that are not text — see
[What gets indexed](#what-gets-indexed); a web crawl also reports `entries_disallowed`, the pages
the site's [robots.txt](#robotstxt) kept it out of. Drop `?source_id=` to get every source instead, wrapped
in a `sources` array.

### 5. Query

Send the text, not a vector. The server embeds it with the same model ingestion used, which is what
keeps the query and the index in one embedding space:

```bash
curl -X POST http://localhost:8080/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"query": "how does the system handle concurrent writes?", "top_k": 10}'
```

There is no point at which the index is too incomplete to search: a query issued while ingestion is
still running is answered from whatever is visible at that epoch, rather than refused. `coverage` in
the response says how much of the corpus that actually was, which is what separates "nothing
matched" from "most of it has not been indexed yet".

`retrieval` says which signals answered: `"hybrid"` when the node has both an embedding model and
a chunk index, `"vector"` or `"lexical"` when it has one of them. Without an embedding endpoint
this still answers. See the [FAQ](#faq) for the other first-run surprises.

### 6. Optionally, the graph

Vector search needs nothing further. To ask questions that traverse relationships rather than find
similar text, turn extraction on *before* ingesting — it is one chat-model call per document, which
is why it is off by default:

```properties
excalibur.graph.extraction-enabled=true
```

Ingest, wait for `idle`, then publish what was staged and ask:

```bash
curl -X POST http://localhost:8080/v1/graph/build
curl -X POST http://localhost:8080/v1/graph/query \
  -H 'Content-Type: application/json' \
  -d '{"query": "who reported the Frankfurt outage?", "as_of": "2019-03-01"}'
```

`/v1/graph/query` answers 503 until a build has run, and names the build as what is missing rather
than 404 — the capability is there, the data is not. Answers come back as `answered`, `needs_input`
or `declined`; see [Graph Examples](#graph-examples) for the three shapes.

Everything past this point — tokens, TLS, rate limits, HTTP sources, reindexing, embedded use — is
in [USAGE.md](USAGE.md) and the sections below.

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

`build` runs `check`, and `check` runs the JaCoCo coverage verification, so a module that drops
below its floor fails the build rather than a report nobody opens. See
[Test Coverage](#test-coverage) for what the floors are and why they differ.

## Running

```bash
# Run with default configuration
java --enable-preview --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector -jar excalibur.jar

# Run with custom configuration file
java --enable-preview --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector -jar excalibur.jar --config /path/to/excalibur.properties

# Set port via environment variable (or excalibur.api.port in the properties file)
EXCALIBUR_PORT=9090 java --enable-preview --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector -jar excalibur.jar

# Put the segments, indexes and graph somewhere other than ./data
EXCALIBUR_DATA_DIR=/var/lib/excalibur java --enable-preview --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector -jar excalibur.jar
```

The port is resolved from `EXCALIBUR_PORT`, then `excalibur.api.port`, then 8080. A value that is
not a port number fails startup: falling back to the default would produce a node that starts,
reports itself healthy, and is not where anything is looking for it.

### In a container

```bash
docker build -t excalibur .

docker run --rm \
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

Binding `0.0.0.0` requires a token *and* TLS, so the third variable is not optional: without
either it or the TLS keystore settings the node refuses to start rather than sending the bearer
token over the network in cleartext. `EXCALIBUR_API_ALLOW_PLAINTEXT=true` is the right answer only
where something in front of this process terminates TLS — an ingress or a sidecar. Terminating it
here instead means `EXCALIBUR_API_TLS_KEYSTORE` with `EXCALIBUR_API_TLS_KEYSTORE_PASSWORD`, and
dropping the plaintext variable.

The image is multi-stage: Gradle builds the shadow jar, and the node runs on a Temurin 21 JRE as
an unprivileged user with the jar owned by root and read-only to the process. The three flags it
launches with are not tuning — they are the conditions the process runs correctly under at all.
`--enable-preview`, because every class here is compiled with it and a JVM without it refuses the
class files before `main` runs. `--enable-native-access=ALL-UNNAMED`, because the storage engine
maps segments through Panama. `--add-modules=jdk.incubator.vector`, because without it the SIMD
distance kernels fall back to scalar arithmetic *silently* — a node that works and is several
times slower at every search.

Heap comes from the container's limit rather than a fixed `-Xmx`, capped at 60% rather than the
usual 75%: segments live in memory-mapped arenas outside the heap, and a heap sized to crowd out
the page cache turns every search into a disk read. The image also sets `+UseG1GC`,
`+HeapDumpOnOutOfMemoryError`, and `+ExitOnOutOfMemoryError` — a JVM that survives an OOM in a
worker goes on answering `/health` while indexing nothing.

The heap dump is written to `/var/lib/excalibur-diagnostics`, not the data directory. A dump is
the size of the heap it records, and beside the segments it is a file every snapshot has to leave
out, every check has to explain, and on a volume sized for the data, possibly the one that fills
it. Mount a volume there, as above, to keep a dump past the container; without one it lives in the
container's writable layer and goes with `docker rm`.

The image owns four directories for its unprivileged user (uid 10001), and declares only the data
directory a volume:

| Directory | Holds | Mount it |
|---|---|---|
| `/var/lib/excalibur` | the data directory | always — it is the `VOLUME` |
| `/var/lib/excalibur-diagnostics` | heap dumps from an out-of-memory exit | to keep a dump |
| `/var/lib/excalibur-quarantine` | files `excalibur-fsck --repair` sets aside | for every repair |
| `/var/lib/excalibur-backups` | snapshots `excalibur-backup` writes from the image | for every snapshot |

The last three are deliberately not `VOLUME`s: an unmounted declared volume is an anonymous one,
which `docker run --rm` deletes with the container — a repair's quarantined evidence or a fresh
backup gone the moment the command returns, with nothing to say so. A named volume takes the
directory's owner when first mounted; a host directory bind-mounted in its place needs
`chown 10001:10001` first.

Both operator tools are on the image's `PATH`, so they are reachable at the moments anyone wants
them — when the node will not start, and when it has been stopped to be backed up. Stop the node
first; then check its volume:

```bash
docker run --rm -v excalibur-data:/var/lib/excalibur \
  --entrypoint excalibur-fsck excalibur /var/lib/excalibur
```

A repair needs the quarantine directory named and mounted. Its default is a new directory beside
the data directory, in `/var/lib`, which the image's user cannot create:

```bash
docker run --rm -v excalibur-data:/var/lib/excalibur \
  -v excalibur-quarantine:/var/lib/excalibur-quarantine \
  --entrypoint excalibur-fsck excalibur \
  /var/lib/excalibur --repair --quarantine-dir=/var/lib/excalibur-quarantine
```

The two are separate mounts, which no rename can cross, so each file set aside is copied into the
quarantine, forced to disk, compared byte for byte, and only then removed from the data directory.
A snapshot is the same shape, into a backup volume:

```bash
docker run --rm -v excalibur-data:/var/lib/excalibur \
  -v excalibur-backups:/var/lib/excalibur-backups \
  --entrypoint excalibur-backup excalibur \
  snapshot --data-dir /var/lib/excalibur --out /var/lib/excalibur-backups/2026-09-22
```

CI runs all three against a node the image started and stopped.

Nothing is baked in. The API binds `127.0.0.1` by default and binding anything else requires a
bearer token and either TLS or an explicit `EXCALIBUR_API_ALLOW_PLAINTEXT=true` where something in
front of the process terminates it — so the container ships with none of those set, because a
default that listened on every interface unauthenticated is the one mistake that policy exists to
prevent. `HEALTHCHECK` points at `/health/ready`, not `/health`: readiness answers 200 or 503 on
whether this node can serve a query, where liveness answers 200 while it is still opening
segments.

### Stopping the node

`SIGTERM` runs a shutdown hook that stops the listener and drains the node. It then publishes the
chunk index's buffer, which is the tail of an ingestion run. Next it forces staged graph facts and
the document registry to disk, and finally closes the storage engine. The whole sequence runs
against one deadline, `excalibur.api.shutdown-timeout-seconds` (30 by default). The drains run in
parallel and share that deadline minus its last 10 seconds. Those 10 seconds are kept for the
writes, so a slow drain cannot use them up. A reindex, compaction or graph build still running when
the drains' share runs out is reported and left alone. Nothing it is using is closed underneath it,
and the next start recovers from its write log.

Set the supervisor's stop timeout to **the configured deadline plus 10 seconds**. The margin covers
the JVM reaching its hook and the storage engine's own close. With the default, that is
`docker run --stop-timeout 40` or `docker stop --time 40`, `terminationGracePeriodSeconds: 40`, or
systemd `TimeoutStopSec=40`. Raising the deadline means raising these to match. A SIGKILL that
arrives before the hook finishes loses the unpublished buffer, and nothing reindexes those chunks
afterwards.

## Operator tools

Two commands ship beside the server. Both run against a data directory rather than a running
node, both run and exit, and neither opens a port. Both are also in the container image — see
[In a container](#in-a-container) for the mounts each needs there.

```bash
./gradlew :excalibur-fsck:installDist :excalibur-backup:installDist
```

### Checking a data directory — `excalibur-fsck`

```bash
excalibur-fsck /var/lib/excalibur              # report only; nothing is touched
excalibur-fsck /var/lib/excalibur --strict     # also fail on anything unverifiable
excalibur-fsck /var/lib/excalibur --repair     # apply only the repairs shown to be safe
excalibur-fsck /var/lib/excalibur --repair --quarantine-dir=/var/tmp/quarantine
excalibur-fsck /var/lib/excalibur --json
excalibur-fsck --help                          # the flags and the exit codes
```

Without `--repair` it never opens a file for writing: most are mapped read-only — enforced by the
kernel, not by intention, because several of the engine's own open paths stamp headers or migrate
formats on the way in — and the rest are streamed past a digest without being mapped at all. It
reports each file as one of `INTACT`, `CORRUPT`, `UNREADABLE`, `MISSING`, `UNSEALED`,
`LEGACY_UNPROTECTED`, `UNCHECKED` or `NOT_DATA`. Only `INTACT` means bytes were weighed against a
stored checksum. `UNSEALED`, `LEGACY_UNPROTECTED` and `UNCHECKED` are reported as *not shown to be
damaged*, which is deliberately not the same claim as *sound*. `NOT_DATA` is a JVM heap dump
(`*.hprof`) left in the directory by a node whose `-XX:HeapDumpPath` pointed there: it is reported
with its size, so the disk it takes is seen, and never read — it is evidence about a process, not
state, so it neither passes nor fails anything, `--strict` included.
A file can also be `INTACT` and only partly verified — an older segment whose entries carry
checksums but whose slot table does not — which is counted on its own axis rather than being
mislabelled either way. `--strict` exits non-zero on all of these, which is how an operator asks
for proof rather than the absence of doubt. It also checks
across files — a catalog row naming a segment that is not there, an index with no segment, a
staged file a rename never published.

Two derived files are judged by what the node does with them rather than by their name alone. A
segment's term sketch, `segment_<N>.tfs`, is the one derived file the node rebuilds by itself: a
sketch that fails its checksum is reported `CORRUPT` but not counted as damage, so it neither sets
exit code `1` nor fails `--strict`, and the repair plan moves it aside (the node would rebuild it
anyway). Only corruption is excused: a sketch the operating system will not read — an I/O error, a
permission refused — is `UNREADABLE`, and fails the run like any file the device refuses. The
source status history, `source-status`, is excused the same way for a different reason: it is not
rebuilt, but the node reads one it cannot trust as no history and reports each source from its next
run, so a damaged one costs how the last crawls ended and nothing the node serves. Every other derived file — a graph, a mapping, a codebook, an LSH table — is rebuilt only
by a reindex or a retrain, and its damage fails the run. A `segment_<N>.hnsw.compact.docmap` is
the mapping an HNSW compaction stages: beside its `segment_<N>.hnsw.compact` the compaction never
swapped, both are staging leftovers, and the mapping is moved aside first; alone, the graph swap
completed and it is the mapping the node installs on its next open, so it is verified, reported
as `compacted-mapping-pending`, and never repaired — a damaged one goes to an operator.

Repair is off by default: without `--repair` the plan is printed and nothing is touched. With it,
the tool writes, and each write is stated before it is made. **No repair ever deletes anything.**
A damaged file, an index whose segment is gone, or a staged file a rename never published is moved
into a quarantine directory byte for byte, which is reversible by hand. Two repairs write in place,
each the smallest write that says what the check proved: a `catalog.dat` row naming a segment file
that is gone is marked purged, through the engine's own catalog code, rather than cut out; and a
write-ahead log that ends in a torn record — an append that never completed — has that record's
opcode cleared, the same one-byte write the log itself makes when an append fails its fsync, so
the log reads as ending before it. What it will not attempt matters as much as what it will: a
damaged `catalog.dat`, a damaged `routing.dir`, a routing slot pointing at a missing segment, a
write-ahead log damaged in the middle, or a damaged lexical store `manifest` are all reported under
*needs an operator* and left alone. Which hash slot belongs to which segment after a split is not
recoverable from file names — that is why the directory is written down — and the records behind
damage in a log were fsynced and acknowledged, so trimming past them would destroy committed
writes. A log damaged in the middle is not moved aside either, for the same reason: setting the
file aside sets those records aside with it, and unlike an index, nothing rebuilds a log. Nor is a
lexical manifest: a store without one opens as a new, empty index and deletes every segment the
empty manifest does not name. A damaged lexical segment, by contrast, is moved aside like any
damaged file — the node keeps it out of every query either way, and its next compaction rebuilds
it from its key table and the stored text.

Damaged files are moved into a timestamped directory beside the data directory unless
`--quarantine-dir=<path>` names another. It takes the `--flag=value` form only. The default needs
the data directory's parent to be writable; a quarantine on another filesystem works too, by a
copy that is forced and compared before the original is removed, since a rename cannot cross — a
lexical segment's directory included, copied whole and compared file by file.

Exit codes: `0` no damage, `1` damage found, `2` the command line was wrong, `3` the check itself
failed, so nothing is known about the directory — which is not the same claim as `0`.

### Snapshots — `excalibur-backup`

```bash
excalibur-backup snapshot --data-dir /var/lib/excalibur --out /backups/2026-09-14
excalibur-backup verify   --snapshot /backups/2026-09-14 --deep
excalibur-backup restore  --snapshot /backups/2026-09-14 --target /var/lib/excalibur
```

A snapshot is a self-describing image: the payload, plus a manifest naming every file with its
length, its CRC32C and its SHA-256, the format versions, and the epoch the image is coherent at.
`verify` checks an image against that manifest in both directions — every named file must be
present and match, and every present file must be named. By default matching means the length and
the CRC32C. `--deep` adds the SHA-256, and hands every file in the image to the checker for its
format, so a snapshot can be shown to hold openable segments and graphs rather than merely
unaltered bytes — a checksum only proves a file is the one that was copied, and damage that was
already there when it was copied verifies faithfully:

| Format | Checked by | Proven means |
|---|---|---|
| segments, `catalog.dat` | the storage engine, on a scratch copy | every entry's checksum, and the header and record CRCs |
| HNSW graphs and document mappings, quantiser codebooks, LSH tables | the vector and filter modules, on a scratch copy | the seal a clean close writes |
| a compaction's staged pair (`segment_<N>.hnsw.compact`, `.hnsw.compact.docmap`) | `excalibur-fsck`, read-only | nothing, while both are there: the compaction never swapped, the node discards the pair on open, and a torn pair does not fail the image. A staged mapping alone is the only mapping the swapped graph matches, and is verified against its seal like any mapping |
| write-ahead logs | `excalibur-fsck`'s checkers, read-only | every record replayed against its checksum |
| graph staging logs | `excalibur-fsck`, read-only | the erasure ledger: every record against its CRC. The triple, entity and label logs: only their header, because their one open path writes |
| the lexical store | `excalibur-fsck`, read-only, as one structure | every column of every segment its manifest names |
| segment term sketches (`segment_<N>.tfs`) | `excalibur-fsck`, read-only | a CRC32C over the header and one over the body. A damaged sketch is reported and does not fail the image: a node restored from it rebuilds the sketch when it opens the segment. One that cannot be read at all fails it |
| `routing.dir`, the document registry and source catalog, the in-flight journal, lexical sidecars | `excalibur-fsck`, read-only | nothing — these formats carry no checksum, so they are well-formed at best |

The engine's own checkers run on a scratch copy because several of them repair what they open;
`excalibur-fsck`'s are the ones it surveys a live directory with, so the two tools cannot disagree
about which checker a file belongs to. The report counts how many structures were proven against
a checksum or seal and lists every one that was not, with the reason: a format that carries
nothing to prove it by is reported as checked and unproven rather than left out, which would read
as though nothing went unverified. `restore` verifies before it writes anything, to the same default depth and to
the deeper one with `restore --deep`, then builds the new tree beside the target and renames it on
in one move; an image that does not verify is refused with the target untouched, and a target that
already holds something needs `--force`.

**The node must be stopped.** Nothing in this engine orders one file against another and nothing
locks the directory, so a clean shutdown is the only moment at which every file describes the
same instant. The directory is fingerprinted before and after the copy and a snapshot whose
source moved underneath it is refused. That check is one-sided — it can prove a node *was*
running, never that one was not — so `--allow-live` downgrades the refusal to a warning and
records the image as `LIVE_UNSAFE` rather than claiming a consistency it cannot establish. There
is no online-backup mode, and a half-barrier that stopped the background workers while the write
path kept accepting writes would be worse than none: it would look like a consistent image and
not be one.

`--exclude-derived` leaves out what something will rebuild — the lexical chunk index, a tenant's
built edge store, the quantiser codebooks, the segments' term sketches (which the node rebuilds by
itself as it opens each segment, at a cost in start-up time). The HNSW graphs and their document
mappings are never left out, derived though they are, because **nothing rebuilds them**: deleting a
`.hnsw` gives an empty graph attached to a populated segment, not a repopulated one. That includes
a `segment_<N>.hnsw.compact.docmap` an interrupted compaction staged — alone, it is the only mapping
that matches a graph whose swap already completed. The write-ahead logs are
never left out either — a segment's data file is forced only on flush, every log append is
fsynced before it is acknowledged, and the graph integration cursor points into the log.

Two things are never copied, whatever the flags, and each is reported under `omitted` with the
reason: staged names a crash left behind (`TRANSIENT`), which nothing reads, and JVM heap dumps
(`*.hprof`, `DIAGNOSTIC`), which are a record of a process rather than state and roughly the size
of its heap.

`excalibur-backup <command> --help` (or `-h`) prints that command's options and the exit codes, and
exits `0` without touching anything; `excalibur-backup --help` prints all three.

Snapshots preserve sparseness. The engine maps write-ahead logs at 64 MiB and graphs at 755 MiB
apiece, so a node holding 50 MB of data occupies 50 GB apparent; a copy that did not preserve
holes would turn that into a 50 GB backup. Checksums still cover every logical byte.

Exit codes: `0` done, `2` bad command line — including an `--out` inside `--data-dir`, a
`--target` inside `--snapshot`, and a path the platform cannot name — `3` the image did not verify
and nothing was restored, `4` the restore target or the snapshot's `--out` is not empty, `5` the data directory
moved while being copied, `6` a file could not be read or written — or a copy did not read back as
what was written — and `70` something this build did not anticipate, reported rather than hidden.

## Configuration

Configuration is loaded from `excalibur.properties` in the working directory by default, or from
the path given to `--config` (or `--config=<path>`); an argument the server does not recognise,
or a `--config` with no path after it, refuses to start rather than quietly using the defaults,
and `--help` prints the arguments without starting anything. One file is read, not a chain: when
the path is not on the filesystem it is looked up on the classpath, where the jar carries its own
`excalibur.properties`. A file named with `--config` that cannot be read refuses to start; only the
default path falls back, with a warning, to the built-in defaults.
Copy `excalibur.properties.example` to start; the working copy is
gitignored, because it is the file a key ends up in.

The file is `key=value` lines split at the first `=`, with `#` or `;` starting a comment line. There
are no trailing comments — everything after the `=` is the value, so
`excalibur.api.max-top-k=1000  # ceiling` is refused at startup as not a number — and one pair of
surrounding quotes is stripped. A value outside its key's range, or one that cannot be parsed, fails
startup naming the key; a key the node does not recognise is logged as a warning with the nearest
key it does. Booleans accept `true`/`1`/`yes`/`on` and `false`/`0`/`no`/`off`, case-insensitive,
except `excalibur.api.allow-plaintext` and `excalibur.api.tls-enabled`, which accept only `true` or
`false`.

Any property can be overridden from the environment by upper-casing it and replacing `.` and `-`
with `_`. The environment always wins over the file, which is how secrets stay out of it:

```bash
export EXCALIBUR_LLM_API_KEY=sk-your-api-key-here
export EXCALIBUR_LLM_BASE_URL=https://api.openai.com/v1
export EXCALIBUR_PORT=9090
```

Three variables are read directly rather than as property overrides, because deployments already
spell them this way:

| Variable | Meaning |
|----------|---------|
| `EXCALIBUR_PORT` | Listen port; checked before `excalibur.api.port` |
| `EXCALIBUR_DATA_DIR` | Where segments, indexes and the graph live. Default `./data`, created if absent. There is no property for it |
| `EXCALIBUR_LOG_LEVEL` | Root log level, read by `logback.xml` at startup. Default `INFO` |

`EXCALIBUR_API_AUTH_TOKEN` is not among them: it is the ordinary environment override of
`excalibur.api.auth-token`, and setting either turns authentication on. The MCP server reads a
variable of the same name as the token it presents to the node — see
[Pointing a model at it](#pointing-a-model-at-it).

### Configuration Options

| Property | Default | Description |
|----------|---------|-------------|
| `excalibur.api.port` | 8080 | Port to listen on; `EXCALIBUR_PORT` overrides it |
| `excalibur.api.shutdown-timeout-seconds` | 30 | How long a stop may take, from the signal to the last file closed. Every drain shares this one deadline. The last 10 seconds are kept for publishing buffered chunks, forcing staged graph facts and persisting the registry. Work still running when the drains' share runs out is reported and left for the next start to recover. Set the supervisor's stop timeout to at least this plus 10 (see [Stopping the node](#stopping-the-node)). 15 to 3600; any other value stops startup |
| `excalibur.visibility.epoch-tick-ms` | 100 | Visibility epoch tick interval (ms). 10 to 10000 |
| `excalibur.visibility.query-timeout-ms` | 30000 | Query timeout (ms) for a request that names none. Must not exceed `excalibur.api.max-query-timeout-ms`, or the node refuses to start: every such request would be refused. 1000 to 300000 |
| `excalibur.query.top-k-default` | 100 | Default number of results. Must not exceed `excalibur.api.max-top-k`, or the node refuses to start, for the same reason. 1 to 10000 |
| `excalibur.query.threshold` | 0.0 | Score floor for requests that omit `threshold`; a request's own `threshold` overrides it. 0 disables filtering. 0.0 to 1.0 |
| `excalibur.query.vector-dimensions` | 1536 | Vector dimensionality. 128 to 4096, and must equal `excalibur.llm.embeddings-dimensions` |
| `excalibur.query.include-partial-segments` | true | Include indexing segments in queries |
| `excalibur.query.llm-timeout-ms` | 60000 | LLM scoring timeout (ms). 5000 to 300000 |
| `excalibur.query.hybrid-retrieval` | true | Fuse BM25 with vector search on a node that holds both. `false` restores vector-only ranking, and with it a `score` that is a similarity rather than a fused rank sum |
| `excalibur.query.distance-metric` | cosine | Which distance the vector index is built and searched with: `cosine`, `l2` or `inner_product`. The choice belongs to the embedding model. It is written into each graph's header at creation, and a graph built under one metric **refuses to open** under another rather than returning a quietly worse ranking — the edges encode the neighbourhood the old metric saw. Changing it means reindexing |
| `excalibur.query.rrf-k` | 60 | Reciprocal rank fusion's rank offset. Higher flattens the weight curve, so a document ranked tenth by one signal counts nearly as much as one ranked first; lower lets the top of each list dominate. At least 1 |
| `excalibur.query.cache.enabled` | false | Whether a repeated identical query is answered without searching again. Every lookup re-reads a fingerprint of the live corpus and an entry is served only to a lookup presenting the identical one, so an entry is discarded the moment anything it was computed against changes |
| `excalibur.query.cache.max-entries` | 1024 | How many answers the cache may hold. Entries, not bytes: one entry is one answer, whose result list is bounded by `excalibur.api.max-top-k`. 1–1000000, refused rather than clamped |
| `excalibur.query.expand-hybrid-queries` | false | Whether a node that embeds its corpus also expands the BM25 half of a hybrid query with the chat model. Off, the vector signal carries the meaning and BM25 matches the words as typed; on, the lexical half contributes the recall it would on a node with no embeddings, at one extra chat call per query |
| `excalibur.query.expansion-timeout-ms` | 5000 | How long one query-side expansion call may take before it is abandoned and the query runs as typed. Its own budget, not the LLM client's 60-second one: expansion sits in front of a search a caller is waiting on, and a timeout that generous would be indistinguishable from a hang. 500 to 60000 |
| `excalibur.query.per-signal-over-fetch` | 3 | How many times `top_k` each signal contributes before fusion. A document has to be in a signal's list to be fused at all, so this is what lets a result that is mediocre on one signal and excellent on the other survive to be ranked. At least 1 |
| `excalibur.shard.segment-load-factor` | 0.7 | Fraction of slots a segment may fill before it splits. 0.5 to 0.9 |
| `excalibur.shard.default-ef-construction` | 128 | HNSW construction candidate list size. 16 to 1024 |
| `excalibur.shard.default-ef-search` | 64 | HNSW search candidate list size. 8 to 1024 |
| `excalibur.shard.hnsw-integration-queue-depth-limit` | 100000 | Max vectors in HNSW integration queue. 10000 to 1000000 |
| `excalibur.shard.compaction-delay-ms` | 10000 | Delay before compaction starts (ms). At least 0 |
| `excalibur.shard.default-max-connections` | 16 | Neighbours an HNSW node keeps above layer 0, the graph's `M`. More means better recall, a larger file and a slower build. Written into a graph's header at creation, so it governs graphs built from here on; 2 to 256 |
| `excalibur.shard.default-max-connections-layer0` | _(twice `M`)_ | Neighbours kept at layer 0, where the walk stops descending and becomes exhaustive. `0` means twice the value above; it may not be narrower than it |
| `excalibur.shard.compaction-interval-ms` | 5000 | How often the segment compactor looks for work. At least 100 |
| `excalibur.shard.segment-sync-interval-ms` | 1000 | How often the index registry is reconciled with the storage engine's directory. The sweep is what notices a segment the engine created without announcing it, so this is the longest a new segment can stay unsearchable. Refused rather than clamped below the sweep's own floor. At least 250 |
| `excalibur.shard.compaction-tombstone-ratio` | 0.2 | Tombstoned fraction of a segment at which a rewrite repays itself. Above 0, at most 1 |
| `excalibur.shard.compaction-write-log-mb` | 32 | Write-log size that compacts a segment whatever its tombstone ratio — what keeps a segment that is appended to but never deleted from accumulating an unbounded log. 1 to 48: a write-ahead log holds a fixed size and cannot grow, so a trigger above it would never fire |
| `excalibur.shard.compaction-max-concurrent` | 4 | Segments the scheduled sweep will rewrite in one pass, so a burst of eligible segments cannot spend the whole machine on rewrites. They are rewritten one after another, not at once, and `POST /v1/compact` is not subject to this. 1 to 16; a value outside that is refused at startup against the property name |
| `excalibur.shard.hnsw-compaction-interval-ms` | 60000 | The same sweep for the vector graph, which reclaims deleted nodes by renumbering rather than by rewriting a log. At least 10000 |
| `excalibur.shard.hnsw-compaction-tombstone-ratio` | 0.2 | Deleted fraction of a graph at which its nodes are worth renumbering. 0.05 to 0.5 |
| `excalibur.shard.lsh-hash-tables` | 15 | Hash tables in a segment's LSH index, whose bucket-mates of a query are measured exactly beside the graph search and ranked in with its results. More names more of the true neighbours the graph search might walk past, and costs more buckets probed and more nodes measured per query. The candidates only ever add to an answer, so no shape can lose a result. At most 256: each table allocates hash-functions × dimensions floats, per segment |
| `excalibur.shard.lsh-hash-functions` | 16 | Hash functions concatenated into one bucket key. More makes each bucket narrower: fewer candidates measured per query, and fewer missed neighbours recovered. At most 63, because the key is packed into a long |
| `excalibur.shard.lsh-probes` | 3 | Neighbouring buckets probed beside the query's own, widening the candidates the same way more tables do. At most 64 |
| `excalibur.shard.volumes` | _(empty)_ | Directories segments are placed across, beyond the data directory. Empty keeps everything in the data directory. Named volumes are spread over by a consistent hash of the segment id, so adding one later moves as few existing segments as possible — and each must already exist as a directory and stay mounted. A volume the node cannot find is refused rather than created, and a restart that cannot see a segment its catalog records refuses to start rather than quietly serving a partial corpus. Two entries that resolve to one directory — a symlink, a relative path, a `..` that walks out and back in — are refused at startup naming both spellings, since the ring would give that directory two shares. Changing the set re-weights the ring for **new** segments only: what is on disk stays where it is, write-ahead logs and split children included |
| `excalibur.shard.pq-search-enabled` | false | Whether a segment's product quantiser answers the first pass of a vector search. On, candidates are scored from their quantised codes and the survivors rescored against their full-precision vectors, so the ranking returned is still exact; what the approximation can cost is a document the first pass never shortlisted |
| `excalibur.shard.pq-oversample` | 4 | Candidates the quantised pass collects per result asked for — what buys back the recall the approximation costs. At least 1 |
| `excalibur.shard.pq-subspaces` | 8 | Parts a vector is split into before each is quantised on its own, one code byte per part. More means better recall and a larger index; high-dimensional embeddings need more of them. 1–128, and must divide `excalibur.query.vector-dimensions` — a split that does not is replaced by one that does, with a warning naming this key |
| `excalibur.shard.pq-centroids` | 256 | Centroids each subspace is clustered to — the alphabet a code is written in. 2–256 |
| `excalibur.shard.pq-training-samples` | 100000 | Vectors the codebook training samples from a segment, or 0 for all of them. Training runs while a segment is `warming`, which is a queryable state like every other one a segment is searched in — the segment answers from its full-precision vectors throughout, and `pq-search-enabled` simply has nothing to use on it until the codebook is fitted. What this bounds is how long that lasts. Must be at least `pq-centroids` |
| `excalibur.ingest.document-atomicity-timeout-ms` | 2000 | Document atomicity commit timeout (ms). 1000 to 30000 |
| `excalibur.ingest.document-atomicity` | true | Whether a document's chunks become visible together. Turning it off removes the guarantee listed under [Visibility and Consistency](#visibility-and-consistency): a query can then see half a document |
| `excalibur.ingest.reindex-interval-ms` | 3600000 | How often a registered source is re-crawled on its own (1 hour). A node with no embedding endpoint re-crawls too, re-chunking and re-indexing lexically. 60000 to 86400000 |
| `excalibur.ingest.crawl-delay-ms` | 200 | The shortest gap a web crawl leaves between two requests to its host — 200ms is five a second. A crawl walks sequentially and makes two requests per page, one to read its links and one to fetch it, so nothing but the host's own latency limited how fast it was asked for pages. A registration's `crawl_delay_ms` may ask for a longer gap and never a shorter one. `0` paces nothing; the maximum is 60000, and a value outside that range is refused at startup against the property name |
| `excalibur.ingest.chunk-target-tokens` | 512 | Tokens one chunk aims for. Larger chunks carry more context into a single result; smaller ones rank more precisely, because a chunk's score is diluted by whatever in it the question did not ask about. At least 1 |
| `excalibur.ingest.chunk-overlap-tokens` | 64 | Tokens a chunk repeats from the one before it, so an answer that straddles a boundary is not split across two chunks holding half of it each. Must be less than the target, or chunking would never advance; the node refuses the pair at startup rather than failing on the first document |
| `excalibur.ingest.bm25-k1` | 1.2 | Where a repeated term stops earning more score. 0 reduces term frequency to a yes-or-no. Under `lexical-engine=native` this is **recorded in the index** and an index built under one value refuses to open under another — not because the bytes depend on it, but because changing it changes every score the corpus can produce, and with it every ranking and every threshold a deployment has calibrated. Changing it means reingesting. Under `lexical-engine=lucene` it is applied at query time and nothing is recorded, so a change takes effect on the next query. At least 0 |
| `excalibur.ingest.bm25-b` | 0.75 | How much a match is discounted for the length of the chunk it was found in. 1 normalises fully by length, 0 not at all. Recorded, or not, on the same terms as `bm25-k1`. 0 to 1 |
| `excalibur.filter.corpus-capacity` | 10000000 | Entries the membership filter is sized for. **Size this to your corpus.** A filter with no room left cannot record a term, and a level that has refused one stops being trusted to say what its segments lack: `/v1/stats` reports `cuckoo.saturated`, and queries order segments by their term sketches alone. Results are unaffected; an undersized value costs the ordering, which matters to a query that runs out of time. The filter allocates about twice this many slots, rounded up to a power of two: a cuckoo filter refuses inserts well before its slots are full, so one sized to exactly its capacity would saturate at the corpus it was configured for. At least 1 |
| `excalibur.filter.segment-capacity` | 10000 | Distinct terms one segment's level of the membership filter is sized for. A segment that outgrows it is no longer ordered by its terms rather than being ordered wrongly; `/v1/stats` counts those under `cuckoo.saturatedSegments`. 1 to 2147483647, and at most `excalibur.filter.corpus-capacity` |
| `excalibur.filter.fingerprint-bits` | 13 | Bits of each term the filter keeps, 8 to 16. Each extra bit halves the chance two unrelated terms look alike — and so halves the segments a query moves ahead of others for nothing — at one more bit per slot |
| `excalibur.llm.api-key` | _(none)_ | Key for the OpenAI-compatible endpoint. Supply as `EXCALIBUR_LLM_API_KEY`, not in this file. Without it the corpus is still chunked, stored and term-indexed; only the vector signal is missing |
| `excalibur.llm.base-url` | https://api.openai.com/v1 | API root. The clients append `/v1/embeddings` and `/v1/chat/completions` themselves; a trailing `/v1` is stripped, so `https://api.openai.com` and `https://api.openai.com/v1` mean the same thing |
| `excalibur.llm.chat-model` | gpt-4o-mini | Chat model for relevancy scoring |
| `excalibur.llm.embedding-model` | text-embedding-3-small | Embedding model name |
| `excalibur.llm.embeddings-dimensions` | 1536 | Embedding vector dimensions. 128 to 4096, and must equal `excalibur.query.vector-dimensions` |
| `excalibur.llm.embeddings-enabled` | true | Whether the key is spent on embedding the corpus. Set `false` to keep the chat model and skip the embedding pass: retrieval is then BM25 over the chunk index with the chat model expanding the query. Inert without a key |
| `excalibur.llm.relevancy-threshold` | 0.3 | Minimum relevancy score (0.0-1.0) |
| `excalibur.llm.timeout-ms` | 60000 | LLM request timeout (ms). 1000 to 300000 |
| `excalibur.llm.max-retries` | 3 | Maximum retry attempts. 0 to 10 |
| `excalibur.llm.retry-base-delay-ms` | 500 | Base delay for exponential backoff (ms). 10 to 30000 |
| `excalibur.llm.failure-threshold` | 5 | Consecutive failures before a circuit opens. Embeddings and each chat caller — relevancy scoring, query expansion, relation extraction, the graph rerank, the graph question embedding and the graph build's entity embedding — have **a breaker of their own** (the graph three one per node, shared by every namespace), all configured by these two keys. They can fail alone, and one breaker for all of them would make a dead reranker stop ingestion. Each is reported under `breakers` on `/v1/stats`. 1 to 100 |
| `excalibur.llm.cooldown-period-ms` | 60000 | How long an open circuit stays open before it half-opens (ms). A half-open circuit admits **one** probe; everything else keeps failing fast until that probe settles the state, which is the stampede the breaker exists to prevent. 1000 to 600000 |
| `excalibur.llm.max-prompts-per-query` | 10 | Max LLM prompts per query. 1 to 100 |
| `excalibur.llm.max-batch-tokens` | 4000 | Max tokens per LLM batch. 100 to 100000 |
| `excalibur.health.readiness-threshold` | 0.5 | `/health` reports `ready`, and `/health/ready` answers 200, at or above this share of live segments being readable. 0.0 to 1.0, and at most `healthy-threshold` |
| `excalibur.health.healthy-threshold` | 0.9 | The same check at a higher bar, reported as `healthy`. 0.0 to 1.0 |
| `excalibur.health.max-latency-ms` | 5000 | A `/health` response slower than this reports `healthy: false`. 100 to 60000 |
| `excalibur.health.min-free-disk-ratio` | 0.05 | `/health/ready` answers 503 with `disk` in `failed` below this share of volume capacity being free — the point past which a compaction's replacement segment can no longer be relied on to fit. 0.0 to 1.0 |
| `excalibur.api.auth-token` | _(none)_ | Bearer token. Supply as `EXCALIBUR_API_AUTH_TOKEN`, not in this file |
| `excalibur.api.bind-address` | `127.0.0.1` | Interface to bind. Anything else also requires an auth token and TLS |
| `excalibur.api.allow-plaintext` | `false` | Permit a non-loopback bind without TLS, for a proxy that terminates it |
| `excalibur.api.tls-keystore` | _(none)_ | Keystore with the server certificate and key. Setting it enables TLS |
| `excalibur.api.tls-enabled` | _(keystore is set)_ | Overrides that inference. `false` serves plaintext **despite** a configured keystore, so set it only where that is meant |
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
| `excalibur.api.read-rate-per-second` | 50 | Sustained read requests per client per second. Above 0 |
| `excalibur.api.read-burst` | 200 | Read burst allowance. Above 0 |
| `excalibur.api.mutate-rate-per-second` | 5 | Sustained mutating requests per client per second. Above 0 |
| `excalibur.api.mutate-burst` | 20 | Mutation burst allowance. Above 0 |
| `excalibur.api.rate-limit-max-clients` | 10000 | Clients tracked before eviction. 1 to 2147483647 |
| `excalibur.api.trusted-proxies` | _(empty)_ | Addresses and CIDR ranges whose `X-Forwarded-For` is believed, so callers behind a proxy are rate-limited individually instead of sharing the proxy's budget. Empty means only the peer address is ever metered |
| `excalibur.api.max-top-k` | 1000 | Largest `top_k` a query may request. At most 2147483647, like every count limit under `excalibur.api.*`; a larger value is refused at startup |
| `excalibur.api.max-query-timeout-ms` | 60000 | Largest query timeout a request may ask for. At least 1 |
| `excalibur.api.max-query-chars` | 8192 | Longest `query` text a request may send. It is embedded, and on the lexical path expanded, so its size is an outbound call the caller picks. 1 to 2147483647 |
| `excalibur.api.max-oversampling-factor` | 16 | Largest `oversampling_factor` a request may ask for. It multiplies `top_k` into the HNSW beam width, so it is what decides how much of a segment a search walks. 1 to 2147483647 |
| `excalibur.api.max-result-text-chars` | 4096 | Longest text returned for one chunk, by `include_text` on a query or by `/v1/documents`. Past it the text is clipped and `text_truncated` says so. 1 to 2147483647 |
| `excalibur.ingest.allowed-roots` | _(empty)_ | Filesystem roots ingest may read. Empty means filesystem ingest is **disabled** |
| `excalibur.ingest.allowed-hosts` | _(empty)_ | Hosts ingest may crawl. Empty means URL ingest is **disabled** |
| `excalibur.ingest.max-sources` | 256 | Registered ingestion sources before new ones are refused. Bounds the source ids documents may be supplied under too, since a supplied id is one no crawl can be registered under. 1 to 2147483647 |
| `excalibur.graph.path` | `<data dir>` | Root the per-namespace graphs live under. Namespace `n` serves `<root>/n/graph` and stages in `<root>/n/graph-staging`; a node that never sees the namespace header uses `shared` |
| `excalibur.graph.extraction-enabled` | false | Extract facts from ingested documents with the chat model |
| `excalibur.graph.extractor-version` | 1 | Recorded on every extracted fact, so one extractor's output can be erased by version. -32768 to 32767 |
| `excalibur.graph.entity-vectors-enabled` | true | Embed each entity's text at build time, so retrieval can rerank against real vectors |
| `excalibur.graph.single-valued-relations` | *(empty)* | Comma-separated relations that hold one value at a time, per subject. At build time a new value closes the previous one on the day it began — its stated start, else the day it was stated (document date, else staging day), with staging order breaking ties. A restatement of the current value closes nothing, and one document's same-day values stay ambiguous |
| `excalibur.graph.max-open-namespaces` | 16 | Graphs held open at once before the least recently used is evicted. At least 1 |
| `excalibur.graph.hot-namespaces` | *(empty)* | Comma-separated namespaces never evicted, alongside the shared index. Each one spends a slot permanently |
| `excalibur.graph.cold-namespaces` | *(empty)* | Comma-separated namespaces evicted before any other, whatever their recency — for a bulk import or an idle tenant. Naming one in both lists refuses to start |
| `excalibur.graph.erasure-tombstone-ratio` | 0.2 | Tombstoned fraction of the store at which a compaction becomes worthwhile. Above 0, at most 1 |
| `excalibur.graph.erasure-max-latency-seconds` | 3600 | Longest an erasure obligation waits before a compaction runs regardless. Checked every tenth of it by a sweep over **every namespace on disk**, so the bound holds on an idle node and on an evicted tenant nobody is querying. The sweep reads each non-resident namespace's erasure log — a small append-only file, not a served store — and reopens only the tenants whose oldest obligation has passed the bound, at most four per pass, oldest first; a longer backlog is logged at WARN, because the node is then not meeting its deadline. At least 1 |
| `excalibur.graph.top-k` | 10 | Documents each retrieval signal contributes before fusion. At least 1 |
| `excalibur.graph.rerank-over-fetch` | 4 | Candidates the full-precision reranker reads per result it returns. Reranking can only reorder what it is given, so this is what lets an entity that term overlap ranked poorly be rescued by its vector. At least 1 |
| `excalibur.graph.context-token-budget` | 2000 | Tokens of serialised subgraph an answer may carry. At least 1 |
| `excalibur.graph.min-score` | 0.05 | Smallest relevance the entity the question named must reach to be answered, on the unit scale: its share of the question's BM25 weight, counting only words the graph holds, or its vector similarity. 0.0 to 1.0 |
| `excalibur.graph.min-separation` | 0.01 | Smallest gap, as a fraction of the best, between the two best entities sharing the name the question used before the result is ambiguous. 0.0 to 1.0 |
| `excalibur.graph.min-mean-confidence` | 0.3 | Smallest mean provenance confidence across the surviving evidence. 0.0 to 1.0 |
| `excalibur.graph.max-ambiguous-candidates` | 5 | Candidates returned when the answer is ambiguous |
| `excalibur.graph.confidence-temperature` | 1.0 | Divisor on the log-odds of the confidence an answer reports, in `(0.0, 20.0]`. The default leaves it untouched, which is honest: the reported number is arithmetic over a ranking score until labelled outcomes say otherwise. `AbstentionCalibrator.fitTemperature` fits it and reports the calibration error before and after — above 1.0 softens overconfident claims, below 1.0 sharpens underconfident ones. Scaling is monotone and fixes 0.5, so it changes how strongly an answer is claimed and never which answer is given or whether one is given at all |
| `excalibur.graph.ram-buffer-mb` | 16 | Lucene RAM buffer per index. Named for the graph, but it sizes the ingestion pipeline's chunk index too when that runs on the `lucene` engine — a node with no graph still pays it, once per open index. Above 0 |

`max-ambiguous-candidates` is at least 2: the abstention policy calls a result ambiguous by
comparing competing readings, so one candidate is not a value it can work with.

The three `min-*` gates and `confidence-temperature` are separate keys because they are separately
measured, and a deployment can have measured either, both or neither. The gates decide which
queries get answered and are fitted together by `AbstentionCalibrator.calibrate`; the temperature
decides how strongly an answer is claimed and is fitted by `AbstentionCalibrator.fitTemperature`.
Neither fit substitutes for the other: a policy can pick exactly the right queries to answer and
still state its confidence in them dishonestly.

Both fits are run by one harness, over a graph on disk:

```bash
./gradlew :excalibur-bench:runGraphCalibration \
    -PcalibrationArgs="data/shared/graph judged.tsv 0.9 excalibur.properties"
```

`judged.tsv` is one question per line, `question<TAB>name[,name...]`, naming the entities that
answer it — the same shape as the document route's qrels, with entity names rather than chunk ids
because the graph route answers with entities. Names rather than ids, because an id is an artefact
of the build that assigned it and a judgement file written against ids goes silently stale the
moment the graph is rebuilt.

The harness retrieves for each question exactly once through the pipeline's own
`GraphRagPipeline.gather`, which stops short of abstention. That is what makes the fit honest in
both directions: the gates a node is currently running never touch the evidence, so a fit is not
anchored to the last one, and the retrieval being measured is the retrieval a served query gets
rather than a reimplementation of it. It then fits the gates, builds the policy those gates
describe, and fits the temperature against *that* policy's confidences — not the configured
policy's, which is about to be replaced. It prints the `excalibur.properties` lines to paste, and
comments out any it cannot stand behind, with the reason.

Pass the node's properties file. Thresholds are only valid for the retrieval that produced them,
and `top-k`, `rerank-over-fetch` and whether an embedding endpoint is configured all change what
retrieval returns.

A correct answer is one where the expected entity is an endpoint of an edge in the subgraph the
traversal assembled — not the top-ranked candidate, which on this route is the entity the question
is *about* rather than its answer. So what is measured is whether the evidence needed to answer
survived into the context, which is what abstention decides about. Whether a model then used that
context correctly is a different question and this does not answer it.

### System properties

Three switches are read from the JVM rather than the properties file, because they are decided
before any configuration is loaded. Pass them with `-D`. They are named for the project's domain
rather than `excalibur.*`, so that a JVM flag never reads like one of the configuration keys
above; nothing resolves them from the file or the environment.

| Property | Default | Effect |
| --- | --- | --- |
| `com.eonmux.excalibur.useFallback` | false | Forces the ByteBuffer implementation instead of Panama FFM. For a JVM or a sandbox where native access is unavailable; slower, and the node says so at startup |
| `com.eonmux.excalibur.enableNativeAccessLog` | true | Whether the Panama detection result is logged. Affects logging only |
| `com.eonmux.excalibur.graph.pageSize` | 4096 | Page size the graph's `madvise` hints are aligned to. Set it where the platform's pages are not 4 KiB |


### Chunk lexical index

| Key | Default | Meaning |
| --- | --- | --- |
| `excalibur.ingest.lexical-engine` | `native` | Which engine indexes chunk text: `native` (the engine's own inverted index) or `lucene` |
| `excalibur.ingest.lexical-tombstone-ratio` | 0.2 | Tombstoned fraction at which reclaiming repays the rewrite. Above 0, at most 1 |
| `excalibur.ingest.lexical-max-latency-seconds` | 3600 | How long an erased chunk may remain on disk before cost stops being a consideration. Checked every tenth of it by a sweep, so the bound holds on an idle node. At least 1 |
| `excalibur.ingest.lexical-max-segments` | 16 | Segments tolerated before the smallest are merged. At least 1 |
| `excalibur.ingest.lexical-refresh-interval-ms` | 30000 | Longest a document published by a run still in progress waits to become searchable by BM25. A run also refreshes when it ends or is stopped. At least 1000 |

The two engines are alternatives, not layers. The native one stores postings and reads a chunk's
text back from storage when a compaction needs it, so the corpus exists once; Lucene keeps a second
full copy of every chunk's text. Only the configured engine is written, so switching engines leaves
the other's directory behind unread and the corpus has to be reingested.

The three compaction settings above govern both. Lucene reclaims deleted documents on a merge
schedule of its own, which is the right default for space and no answer at all for a deadline: one
erased chunk in a large index is below every threshold that schedule has, from the moment it is
erased. So the Lucene engine records the obligation the same way — appended to a log and forced to
disk before the erasure returns — and the same sweep holds it to
`excalibur.ingest.lexical-max-latency-seconds`. The second copy of the text is why this matters
more here than on the native engine, not less.

## Retrieval

Semantic retrieval here is a query-planning problem, not a corpus-transformation problem. The
expensive interpretation happens once per query — a sentence — rather than once per chunk of the
corpus.

Two routes retrieve, and they are separate pipelines rather than two entry points into one.

`POST /v1/query` searches the corpus:

```
user query
   -> query expansion (chat model, and only when the corpus is not embedded)
   -> BM25 over ingested chunks (the engine's own inverted index)
   -> vector retrieval                           [when the corpus is embedded]
   -> reciprocal rank fusion                     [when both signals ran]
   -> batched LLM relevancy scoring              [when a chat model is present]
   -> ranked chunk identifiers, with their text when the request asked for it
```

`POST /v1/graph/query` asks the graph, and shares no stage with the above:

```
user question
   -> classify the question
   -> link its entities to the graph's
   -> traverse (CSR + reverse index), filtered by the as_of interval
   -> rerank against entity vectors              [when present]
   -> batched LLM relevancy scoring              [when a chat model is present]
   -> answer, ambiguity, or calibrated abstention
```

Abstention is the graph route's; `/v1/query` ranks and returns. A graph is never a signal in a
`/v1/query` answer, and corpus vector retrieval is never a stage of a graph answer.

### Why the corpus is not embedded by default

Embedding every chunk makes a dense vector the mandatory representation of every document. For a
corpus of identifiers, titles, DOIs, error codes and version numbers that is both the most
expensive way to index it and the least accurate way to search it: exact terms are what BM25
answers well and what a dense vector blurs. Vector retrieval remains available as an additional
signal where it genuinely improves recall; it is not the price of admission.

Concretely, on a 165 GiB corpus this was the difference between a multi-month GPU pass and an index
that is searchable while the crawl is still running.

### The chunk index

Every ingested chunk is indexed for BM25 by the engine's own inverted index. Lucene remains
available (`excalibur.ingest.lexical-engine=lucene`) and still backs the graph's entity indexes,
but it is not what the corpus is indexed into by default, for one reason: it keeps a second full
copy of every chunk's text. The native index stores postings only, and when a compaction needs a
surviving chunk's text it reads it back from the storage engine under the chunk's own key. The
corpus exists once.

**Segments.** A run's chunks go into new segments; nothing already written is rewritten. Adding a
thousand chunks to a hundred-million-chunk store costs a thousand chunks. Chunk names live in a
mapped file rather than the heap, so naming a hit is a page-cache read and a hundred million chunk
identifiers are not several gigabytes of resident memory.

**Epochs.** A segment records the epoch it became visible at, and a query names the epoch it reads
at. The buffer spills to disk whenever it fills, but a spilled segment is not a readable one — it is
staged, and a refresh publishes everything staged together in a single manifest replacement, an
atomic rename. A document reaches the index whole: its chunks are added in one step once the
document itself is durably published in storage, never chunk by chunk as they are written, so a
refresh — whichever concurrent run triggers it — can never publish half of a document, and a
document that is rolled back never reaches the index at all. Half a document is a wrong answer
rather than a partial one. A run refreshes every `excalibur.ingest.lexical-refresh-interval-ms`
(30 seconds by default) while it is running, and again when it ends, is stopped or fails, so a
crawl that runs for days is searchable as it goes rather than only when it finishes, and whatever a
stopped run published is searchable once it stops. Publication is at the epoch *after* the current
one, so a query already running does not acquire results halfway through.

**Reingestion.** A chunk identifier names one chunk, so indexing it again is a new version rather
than a second copy: publication retires the copy the store already held under that name. Without
that, a source re-crawled on its timer would return the same chunk twice, count it twice in fusion
and grow the index without bound — and worse, go on matching the words the chunk used to hold while
its storage entry says otherwise, which is a wrong answer rather than a stale one. Each segment
carries a table from the hash of a name to its document, so a batch of a thousand chunks costs a
few thousand probes rather than a pass over every identifier in the store. A replaced copy is
counted as dead weight for compaction but is owed to nobody: it is a previous version, not a
promise, and giving every re-crawl an erasure deadline would make routine indexing urgent.

Retiring a copy is epoch-scoped where erasing one is not. The new copy is published at a later
epoch than a query already running is reading at, so a query before that epoch goes on matching the
old copy and a query at or after it matches only the new one — a republished chunk is always
findable by one version of its text and never by both. Erasure is the opposite, deliberately:
hiding has to be immediate or erasure is not erasure, so an erased chunk disappears from every
query at once, including one that could still see an earlier copy. A compaction leaves a replaced
copy on disk until no query can be reading from before its replacement, which is at most a query
timeout: it rewrites a segment from the text in storage, which holds only the new version. An
erasure owed in the same segment waits with it, and the next compaction — the erasure sweep's, on a
quiet node — discharges it.

**Erasure.** Two parts, deliberately. The obligation is appended to a log and forced to disk before
the call returns, and the chunks are tombstoned so no query returns them. Physical removal happens
in a compaction. Once the tombstones themselves are durable, the log records the obligation as
applied. On open, only obligations the log does not record as applied are re-applied — the ones a
crash interrupted between the log and the tombstones — so a document erased and then ingested
again is not hidden by a restart replaying an erasure it had already outlived. Hiding alone would not be erasure; rewriting the store on every request would make
erasure a denial of service. A document whose ingestion fails is rolled back the same way — out of
storage and out of the index — so a failed write cannot leave a hit that resolves to nothing.

`POST /v1/erasure` is the direct instruction, for content someone asked to have removed rather than
content that changed. It removes a document from storage first, then the lexical index, then the
registry — the registry last on purpose, because it holds the chunk ids the first two steps need, so
until it is gone the request can be repeated to finish whatever an interruption left undone.
`"compact": true` rewrites the index before replying, for a caller who has to be able to say the
data is gone rather than that its removal is scheduled.

`"scope": "source"` erases everything a source contributed without the caller naming its documents,
which is the usual shape of an obligation about a person rather than a file — the node has the list
and the caller generally does not. It is a separate word rather than an omission, because a request
that meant "everything" by leaving out an identifier is one a malformed client sends by accident. It
also takes the source out of the crawl: erasing every document while leaving the source scheduled is
not an erasure, it is a pause. Each request is bounded and reports `documents_remaining`, so a large
source is a loop of requests that ends when the count reaches zero.

A document whose chunks would not all come out of storage or out of the index is answered with a
`500`, counted in `documents_incomplete`, and left in the registry so the request can be repeated
to finish it. That is a failure rather than a partial success: the caller asked for content to be
gone and some of it is not.

What no erasure can reach is the source itself. A document removed here and still served by the
directory or website it was crawled from comes back on the next re-crawl. That half of an obligation
belongs to whoever owns the source.

`DELETE /v1/ingest` stops a crawl without destroying anything, which is the ordinary case — a source
moved, a feed went stale, a job finished. It is a separate route from erasure on purpose: an
operator stopping a runaway crawl should not have to reach for the button that also deletes. Both go
through the same removal, which stops an ingestion run in flight, stops a re-crawl in flight and
waits for it to end, clears the source's `/v1/ingest/status` and `/v1/reindex/status` entries and
cancels its place in the re-crawl schedule. The re-crawl is waited for — up to thirty seconds,
which is one document's fetch, embedding and writes — because it finishes the document it is on
when asked to stop, and a document that landed after an erasure had looked would put back what
the erasure removed.

It stops short of the vector indexes, which are reclaimed per segment rather than per entry: until
the segment holding an erased chunk is compacted, a vector query can still rank it — without
content, since that is resolved from storage and storage no longer has it, but under its chunk id.
That is the same residue a source-driven deletion leaves, and the response does not claim
otherwise.

The log is also what the tombstones are checked against on open. Tombstone files are written by
atomic rename, which orders them but does not force them, so a crash can leave a promise recorded
and unapplied; anything the log still owes is marked again as the store opens. Without that the next
compaction would discharge the log entry on the grounds that it names nothing tombstoned — forgetting
the promise while the document was readable again.

**Reindexing.** A reindex is the one operation that removes content a query could still be matching:
a deleted document's chunks, and the tail chunks a shortened document no longer has. Both are erased
from the index as they are tombstoned in storage, and the reindex settles its own erasures at the end
of its run rather than leaving them for whatever ingests next — except in a segment that also holds
the previous copy of a document the same run re-published, which waits for readers to catch up as
described under Reingestion, and is then settled by the sweep. A node that only ever reindexes still
meets the erasure deadline.

**Compaction.** Checked at the end of an ingestion run, at the end of a reindex, when an erasure
arrives, on `POST /v1/compact`, and by a sweep every tenth of `lexical-max-latency-seconds` — which
is what holds the deadline on a node doing none of the others. It is due when any of three conditions holds,
and *what* it rewrites depends on which:

| Trigger | What it selects |
| --- | --- |
| An erasure obligation older than `lexical-max-latency-seconds` | Every segment holding a tombstone, cost no object |
| Store-wide tombstones at or above `lexical-tombstone-ratio` | Only segments whose own ratio repays their rewrite |
| More than `lexical-max-segments` segments | The smallest segments, and only as many as the bound needs |

The deadline is the condition that matters most, because it is the one that fires for a single
erased chunk in a large corpus — which is the shape of every erasure that was promised to somebody,
and the shape a volume threshold never reaches. Cost governs the other two: a segment with one
tombstone in ten million documents costs ten million documents of writing to reclaim one, so the
store-wide ratio decides *that* reclaiming is due while each segment decides whether it repays it.

Merging is confined to segments at or below an epoch floor, since merging across epochs changes what
a reader between them can see. The floor is derived rather than configured — a query cannot outlive
its own timeout, so `query-timeout-ms / epoch-tick-ms` bounds how stale the oldest reader can be.
Purging is not confined by it: removing erased chunks is meant to be retroactive, and a
single-segment rewrite keeps that segment's epoch. That asymmetry is what lets an erasure deadline be
met while a long-running query holds an old epoch open.

A compaction builds its replacement segments while queries and ingestion both continue; only the
manifest swap at the end takes the index exclusively, and that is a rename and some deletions.

**What it will tell you.** `GET /v1/stats` carries a `lexical` block: the engine answering, chunks
indexed, segments, live and erased chunk counts, and `oldestErasureAgeMs` — how long the oldest
undischarged erasure request has been waiting, to compare against `lexical-max-latency-seconds`.
Nothing else in that response would show a deadline going unmet, because nothing else in the node
holds one. The same block counts what the index holds and cannot search: `unsearchableChunks` in
`unreadableSegments` segments it could not open, each named under `unreadable` with whether a
compaction can rebuild it, and `outdatedSegments` still in an older format. `/metrics` carries them
as `excalibur_lexical_chunks{state="unsearchable"}`, `excalibur_lexical_unreadable_segments` and
`excalibur_lexical_outdated_segments`. A damaged segment does not make the node unready: it keeps
answering from the rest, the way it does with a segment that has no vector index.

**What it does with damaged text.** The native index keeps postings, not text, so a compaction
reads each surviving chunk's text back out of storage. Text whose stored entry fails its checksum
or cannot be decoded will never read back, and waiting on it would hold every erasure owed in its
segments hostage: such a chunk is dropped from the index, logged at ERROR, and counted in
`lexical.unreadableChunksDropped` on `/v1/stats` and in
`excalibur_lexical_unreadable_chunks_dropped_total` on `/metrics`. A dropped chunk stops matching
keyword queries but is still served by vector search; any increase means a storage segment is
damaged, and reindexing the chunk's document from its source restores it. Any other read failure
is treated as passing: the group it belongs to is left as it was and retried by the next
compaction.

### Query expansion

A chat model turns one question into the terms a matching document would literally contain, and
weights each formulation itself:

```
"why does my service die when the machine runs out of RAM?"
  -> "OOM killer" 0.9, "ENOMEM" 0.8, "cgroup memory limit" 0.7, "SIGKILL" 0.6
```

The model is not asked to answer the question, and never sees the corpus. Its output is a short,
constrained list — which is what makes it affordable on a query path. The formulations collapse
into one weighted term set and are searched as a single query, so expansion costs a fraction of a
search rather than one search per formulation. A term several formulations agree on has their
weights summed, so agreement counts for more than the strongest single suggestion. The user's own
words always carry weight `1.0` and cannot be outranked by a guess about vocabulary: the summed
weight of the expansions on any one term is held at `0.9`, and when it would pass that every
expansion is scaled by the same factor, so the order the model asked for and the extra weight
agreement earns both survive the ceiling.

The call carries the query's deadline rather than the LLM client's, which is sized for ingestion —
`excalibur.query.expansion-timeout-ms`, default 5000. A model slower than that costs the expansion
and not the query. A query longer than `excalibur.api.max-query-chars` is refused outright; every
query shorter than that is expanded, so one endpoint runs one retrieval strategy.

Expansion belongs to the lexical path: it rewrites the terms BM25 is searched with. The deployment
it is for by default has a chat model and no embedding pass — `excalibur.llm.api-key` set,
`excalibur.llm.embeddings-enabled=false` — and spends the model on each query instead of on the
whole corpus.

A node that does embed its corpus leaves expansion off, because the vector signal is what carries
the meaning of the question there. That makes the BM25 half of a hybrid query plain BM25 — weaker
than the same node would run with no embeddings at all — and whether the trade pays depends on the
corpus, so `excalibur.query.expand-hybrid-queries=true` turns expansion on for the hybrid case as
well, at one extra chat call per query. `excalibur-bench` scores both — `BM25_VECTOR` and
`BM25_EXPANSION_VECTOR` — on the same ingested corpus; see [Evaluating it](#evaluating-it).

### Answering the same question twice

Off by default. `excalibur.query.cache.enabled=true` lets a repeated identical query be answered
without searching again, and `from_cache` on the answer says when that happened.

A cached answer is never a stale one. Before every lookup the node reads a fingerprint of the
live corpus, and an entry is served only to a lookup presenting the identical fingerprint — so a
hit is a proof that nothing the answer was computed against has changed, not a bet that nothing
has. The fingerprint is deliberately not the visibility epoch: that advances on a timer whether
or not a byte was written, so entries would expire within one tick, and it fixes which segments
are visible rather than what they contain, so it misses a grown write log, an HNSW integration
and a compaction. What is read instead is each visible segment's own account of itself, through
the same accessors the query path uses.

Every route's answer is cached, after relevancy scoring: vector, hybrid and lexical. A lexical or
hybrid answer is also fingerprinted on the lexical index — the manifest entries visible at the
query epoch, or the Lucene reader version — so an epoch crossing a manifest entry discards it like
any other change. Result text, snippets and explanations are read from storage on every answer, so
quoted text can never be stale. An answer that is the product of a moment's trouble is never
stored, so that trouble cannot be replayed as a persistent fault: a vector or hybrid answer that is
degraded, timed out, cancelled or whose relevancy scoring fell back; a lexical answer that timed
out, was cancelled, fell back or read less than the whole index (its `degraded` flag, set on every
lexical answer, does not count); and a BM25 answer standing in for a failed vector half. See
[USAGE.md](USAGE.md#the-query-cache).

### Degradation

Every stage falls back to the one beneath it, and no single unavailable subsystem takes down
retrieval:

| Missing | Effect |
|---------|--------|
| Chat model | No expansion; plain BM25 on the query as typed |
| Chat model slower than `excalibur.query.expansion-timeout-ms` | The call is abandoned; plain BM25 on the query as typed |
| Embedding endpoint | No dense signal and no vector rerank; lexical and graph retrieval unaffected |
| Graph | `/v1/graph/query` answers 503; `/v1/query` is unaffected, having never read the graph |
| Expansion returns nothing usable | The original query is executed directly |

Two of those rows are configurations rather than failures. Without `excalibur.llm.api-key` a node has
neither a chat model nor an embedding endpoint, so the first two apply together and retrieval is
plain BM25. With a key and `excalibur.llm.embeddings-enabled=false` only the second applies: the
chat model expands the query and BM25 answers it.

A response says which signals answered it. `retrieval` is `"hybrid"`, `"vector"` or `"lexical"`,
and a `coverage.degraded` flag distinguishes "this is all there was" from "this is all we looked
at". A signal that was configured and then failed mid-query does not silently vanish from that
field: a BM25 search that breaks or misses the request's budget leaves the vector answer intact and
the response reports `"vector"`, because that is what ran. It runs the other way too: an embedding
endpoint or vector pipeline that fails, or misses the deadline without a partial answer, leaves
the query answered from BM25 with `"lexical"` - on a node with `excalibur.query.hybrid-retrieval=false`
as well, which starts its BM25 search once the vector half has failed. See
[USAGE.md](USAGE.md#hybrid-retrieval--both-signals).

### Evaluating it

`excalibur-bench` carries a harness that scores the retrieval configurations independently — BM25;
BM25 + expansion, with and without a vocabulary gate; BM25 + graph; BM25 + vector; and the
combinations — on recall@k, precision@k, MRR, nDCG@k and latency. Strategies a deployment cannot run
are reported as skipped rather than scored zero, so an unavailable signal cannot flatter the ones
that are present, and the reason names what is missing: the engine, the model argument, an
ingestion that did not complete, or a graph build that published no facts.

The question it exists to answer is whether query-side expansion over a lexical index reaches the
same documents as embedding the corpus, at a fraction of the indexing cost — and that comparison is
only meaningful with the indexing cost reported beside the quality number. So the harness reports
the index it built as well as the quality it reached: chunks per second, and the bytes on disk as a
multiple of the text indexed. For the model-backed strategies it reports the ingestion instead —
chunks ingested, seconds, whether they were embedded and had facts extracted — and the graph build's
facts, entities and seconds. A node's disk size is not reported, because a node preallocates its
storage and the number would describe the preallocation, not the corpus.

```
./gradlew :excalibur-bench:runRetrievalEvaluation \
    -PevalArgs="<corpusDir> <indexDir> <qrels.tsv> [k] [baseUrl] [chatModel] [native|lucene|both] [embeddingModel] [dimensions]"
```

The optional arguments are positional, so `-` stands in for one being skipped. `k` defaults to 10,
the engine to `native`, and the dimensions to 1536. `baseUrl` is one OpenAI-compatible endpoint
serving both models; its key is read from `EXCALIBUR_LLM_API_KEY`, and a placeholder is sent when it
is unset, since a local server accepts any key. Naming a model without a base URL is refused.

| Strategy | Needs | How it runs |
|---|---|---|
| `BM25`, `BM25_EXPANSION*` | nothing; expansion needs `chatModel` | the harness's own index of the corpus, chunked by the production chunker |
| `BM25_VECTOR` | `embeddingModel` | `/v1/query` on an evaluation node that ingested the corpus with embeddings: the node's own hybrid fusion |
| `BM25_GRAPH` | `chatModel` | BM25 fused with the graph's answer, from a graph the node extracted during ingestion and built through `/v1/graph/build` |
| `BM25_EXPANSION_GRAPH` | `chatModel` | the same, over BM25 + expansion |
| `BM25_EXPANSION_VECTOR` | both models | `/v1/query` after the node restarts with `excalibur.query.expand-hybrid-queries=true` |
| `FULL_HYBRID` | both models | that expanded hybrid, fused with the graph |

The evaluation node is a real node composed in-process under `<indexDir>/node-<engine>`, with the
chosen engine, the query cache and LLM relevance scoring off, and its rate, `top_k` and timeout
limits raised so the harness measures retrieval rather than admission. The directory must be empty
— an earlier run's node would be evaluated with its old corpus in it — so the harness refuses one
that is not. The node ingests once; the expanded hybrid reuses that ingestion across a restart. With
`both`, each engine gets its own node, so the model cost of ingestion is paid twice.

A graph answer is a set of facts, not a ranked list of documents, so the harness credits it with the
documents its facts were extracted from, in the order the answer first cites them, and fuses that
list with the base ranking by reciprocal rank fusion — the node's `rrf-k`, with the base ranking
fetched at `k` times the node's per-signal over-fetch. A question the graph has no answer for leaves
the base ranking as it was. Every strategy is scored by document: a hit naming a chunk and a hit
naming the document count as the same document, and a qrels line may name either.

The hybrid route says which signals found each result, and the harness adds a note under the table
for any answer the vector signal found nothing for, or that the route answered from one signal
only; those answers are BM25's under the hybrid's name.

It measures the engine a node ships with. `native` is the default because a benchmark of an engine
the product does not use is a number about nothing; `both` indexes the same corpus with the same
chunking on each in turn, which is the only way to read the two side by side:

```
./gradlew :excalibur-bench:runRetrievalEvaluation \
    -PevalArgs="<corpusDir> <indexDir> <qrels.tsv> 10 - - both"

EXCALIBUR_LLM_API_KEY=... ./gradlew :excalibur-bench:runRetrievalEvaluation \
    -PevalArgs="<corpusDir> <indexDir> <qrels.tsv> 10 http://localhost:11434 <chatModel> native <embeddingModel> 768"
```

## HTTP API

ExcaliburDB exposes an HTTP API on port 8080, bound to `127.0.0.1` by default.

### Endpoints

`Auth` is what the route requires **once a token is configured**. With no
`EXCALIBUR_API_AUTH_TOKEN` set — the loopback default — authentication is off everywhere and the
startup log says so. `Budget` names which rate limiter the request is charged against; the two are
separate so a burst of writes cannot starve ordinary reads.

| Method | Path | Auth | Budget | Description |
|--------|------|------|--------|-------------|
| `GET` | `/health` | none | read | Liveness. Always 200 while the process is alive. Unauthenticated because the infrastructure that reads it frequently cannot attach a header, and still rate limited |
| `GET` | `/health/ready` | none | read | Readiness: 200 when this node can serve, 503 when it cannot, so an unmodified `httpGet` probe can act on it. Unauthenticated for the same reason as `/health` |
| `GET` | `/metrics` | bearer | read | Prometheus text exposition. Authenticated, unlike the probes: a scraper is yours to configure and supports a token, and the exposition names disk capacity, corpus size and error rates |
| `GET` | `/v1/stats` | bearer | read | Segment, entry, volume, HNSW, Cuckoo and visibility counters, plus the three partial-failure blocks |
| `GET` | `/v1/version` | bearer | read | Which build and JVM this process is running |
| `POST` | `/v1/drain` | bearer | mutate | Report unready so a load balancer stops choosing this node, while it keeps serving. A mutation: an anonymous caller able to drain a node is a one-request outage |
| `DELETE` | `/v1/drain` | bearer | mutate | Report ready again |
| `GET` | `/v1/query/status` | bearer | read | Queries in flight, longest-running first, with the ids `DELETE /v1/query/{query_id}` takes |
| `POST` | `/v1/query` | bearer | read | Search the corpus: BM25, vector, or the two fused |
| `DELETE` | `/v1/query/{query_id}` | bearer | mutate | Cancel an in-progress query |
| `GET` | `/v1/documents` | bearer | read | Read one chunk's text, named by `document_id` |
| `POST` | `/v1/documents/fetch` | bearer | read | Read the text of a page of chunks in one request |
| `GET` | `/v1/documents/list` | bearer | read | Which documents this node holds, with their chunk ids — paged by `limit` and `cursor`, narrowed by `source_id` |
| `POST` | `/v1/documents` | bearer | mutate | Index documents supplied in the request, for a caller that holds the content rather than a target to crawl |
| `POST` | `/v1/ingest` | bearer | mutate | Register an ingestion source and start indexing it |
| `GET` | `/v1/ingest` | bearer | read | Read back what each source was registered as, or one named source |
| `DELETE` | `/v1/ingest` | bearer | mutate | Stop crawling a source. Its indexed documents are kept |
| `DELETE` | `/v1/ingest/run` | bearer | mutate | Stop the ingestion run in flight and leave the source registered |
| `GET` | `/v1/ingest/status` | bearer | read | Ingestion state per source, or all sources |
| `POST` | `/v1/reindex` | bearer | mutate | Re-index a registered source. A node with no embedding endpoint re-chunks and re-indexes lexically |
| `DELETE` | `/v1/reindex` | bearer | mutate | Ask a running re-index to stop, so a hung one does not hold its source's claim for the life of the process |
| `GET` | `/v1/reindex/status` | bearer | read | Re-index state per source, or all sources. The readback for the 202 `/v1/reindex` returns |
| `POST` | `/v1/erasure` | bearer | mutate | Remove named documents from the corpus: storage, the lexical index, and the registry — and withdraw what was extracted from them into the graph of the namespace they were staged into. 200 when both halves are done, 202 while the graph half is queued, 500 when some content could not be removed |
| `POST` | `/v1/compact` | bearer | mutate | Reclaim the space tombstoned entries still hold, without waiting for the next background sweep. Answers 202 and works behind the response |
| `DELETE` | `/v1/compact` | bearer | mutate | Ask the compaction in progress to stop. 200 either way, saying whether there was one |
| `GET` | `/v1/compact/status` | bearer | read | What the last compaction examined, reclaimed and failed on. The readback for the 202 `/v1/compact` returns |
| `POST` | `/v1/graph/query` | bearer | read | Answer a question from the graph. A walk that returns context, so it is charged as a read |
| `POST` | `/v1/graph/build` | bearer | mutate | Start publishing the facts ingestion has staged as the served graph. Answers `202`; the build runs behind the response |
| `DELETE` | `/v1/graph/build` | bearer | mutate | Stop the build in progress. It publishes nothing, and the namespace keeps serving the graph it had |
| `GET` | `/v1/graph/build/status` | bearer | read | What the build is doing, and what the last one published |
| `POST` | `/v1/graph/erasure` | bearer | mutate | Record an obligation to erase, and compact when it is due |
| `GET` | `/v1/graph/namespaces` | bearer | read | Every namespace on disk, and what each one holds and owes |
| `DELETE` | `/v1/graph/namespaces/{namespace}` | bearer | mutate | Remove a namespace entirely. Irreversible, and `409` for `shared` |

A method a registered path does not serve answers 405, on every path, unauthenticated and charged
against the read budget. Unauthenticated because a 405 is a routing answer rather than a resource:
nothing about the corpus is in it. Charged because leaving it uncharged would let an anonymous
caller spend a routing decision per request at line rate, and charged as a read rather than a
mutation because nothing was mutated. Anything else answers 404. The graph routes are registered on every
node; a node that has never built a graph answers `/v1/graph/query` with 503 rather than 404, because the capability is there
and only the data is missing. `/v1/erasure` is the opposite case: a node built without an erasure
service does not register it at all, so a caller under an obligation gets a 404 they can escalate
rather than a 200 that removed nothing.

Neither erasure route is exposed as an MCP tool. They destroy data to do their job, which makes
them the calls whose cost of being made wrongly is not bounded by retrying, and a model asked to
tidy up a corpus would have every reason to reach for them.

### The graph store

The node builds its own graph from what it ingests. Ingestion extracts facts from each published
document and stages them; `POST /v1/graph/build` turns everything staged into the store and starts
serving it, without a restart. A node that has ingested but never built answers `/v1/graph/query`
with 503 and says so, rather than 404 — the capability is there, the data is not yet.

Each namespace keeps its own store, under the root `excalibur.graph.path` names — the data
directory by default. A node that never sees the `X-Excalibur-Namespace` header uses exactly one,
`shared`, so the default single-tenant store is `<data dir>/shared/graph` and it stages in
`<data dir>/shared/graph-staging`. A store is a directory holding:

| File | What it is |
|------|------------|
| the edge columns | CSR adjacency, its transpose, validity and provenance |
| `documents/` | Lucene index over each entity's text, for the lexical retrieval signal |
| `labels/` | Lucene index over entity names, for entity linking |
| `entity_vectors.vec` | one full-precision vector per entity, for the exact rerank |
| `entities.idx` / `entities.dat` | entity id → name, so answers name people rather than integers |
| `relations.idx` / `relations.dat` | relation id → name |

All of it or none of it, `entity_vectors.vec` excepted: a directory missing any of the others
fails the start rather than serving a pipeline that silently resolves nothing, while absent entity
vectors are a configuration rather than a fault - a graph built without an embedder has none, and
the rerank stage that reads them is skipped. The whole directory is assembled beside the live
one and renamed into place, so a reader sees the previous graph or the new one and never a mixture.

Facts accumulate in `<graph dir>-staging`, beside the store rather than inside it — every build
replaces the store wholesale, and the record the next build reads has to survive that. Entity ids
are assigned once and never renumbered, so the Lucene indexes that store them stay correct across a
rebuild they did not take part in.

A build sorts the namespace's staged facts in memory before writing them: about 48 bytes of heap per
fact at its peak, up to 66 with the buffer's growth slack, and at most 2,147,483,639 facts in one
build — see *How much memory does a build need?* in the FAQ for where those numbers come from.

Each staged fact records the day it was stated: the document's own date where the source gives one,
otherwise the day it was staged. This orders the values of a single-valued relation; it is never
used as the fact's valid time. A staging log written in the earlier record format is upgraded in
place the first time it is opened; its facts carry no stated day and are ordered by the order they
were staged in.

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

A served namespace is around twenty-seven memory mappings - thirteen of them the edge store's
columns, the rest the two dictionaries, the entity vectors and the two Lucene indexes, whose share
moves as segments merge. The served indexes are opened read-only, so on a 64-bit JVM, where Lucene
maps its files, a served namespace holds no file descriptors; on a JVM that cannot map, each index
file is a descriptor instead. A process has a finite number of both, so only
`excalibur.graph.max-open-namespaces` graphs stay open at once and the least recently used is
evicted to make room. Every request holds a lease for its duration: a namespace being read is passed
over by eviction, and an eviction that lands on one is deferred until the last reader is done.

The budget counts the whole process, not only the graphs: before each namespace opens it reads
`/proc/self/maps` and `/proc/self/fd`, falling back to the JVM's own descriptor count, and treats
everything that is not a graph as already spent. Where the process cannot be asked, the headroom
below the limit is the reserve. If the rest of the node alone is past the mark, eviction cannot
help, and the node logs a warning and opens the namespace anyway. `/v1/stats` reports both shares
under `graph.budget`.

A build or an erasure compaction never closes a graph under a query either. A build opens the new
graph beside the old one and switches new queries to it; the old graph closes when the last query
reading it finishes, so a query sees one graph from start to end and there is no moment in which
the namespace answers that no graph is built. A build whose new graph cannot be opened puts the
previous one back and keeps serving it, and a node that crashed mid-publish restores the displaced
graph when it next opens the namespace. A compaction rewrites the served files, so it withdraws the
graph first and waits for running queries to finish before touching them; a query that is still
running thirty seconds later is failed rather than left to hold up the erasure. Builds and
compactions of one namespace are serialised against each other, so a build cannot republish facts
a compaction is removing.

A compaction publishes its rewritten graph by the same two renames a build does, so the same
recovery covers a crash in either. Its obligations are marked discharged only once that publish is
durable, so a crash part-way through leaves them outstanding over a graph that may still hold what
they erase — the original, restored by recovery or never replaced. A namespace that finds signs of
that on open (a restored graph, a compaction's leftover scratch directory, or the
`graph-staging/erasure.interrupted` marker a failed attempt to finish one leaves) does not serve
its graph until the outstanding obligations have been applied to it. That happens as the namespace
opens; if it fails there, the graph stays unserved and the erasure sweep retries it, whatever the
latency policy says, and serves the graph once it gets through.

Recency alone is the wrong rule when namespaces are not interchangeable: the shared index is read by
nearly every query and costs seconds to reopen, so it is pinned. Two keys extend that judgement to
named tenants — `excalibur.graph.hot-namespaces` pins them alongside the shared one, and
`excalibur.graph.cold-namespaces` marks the ones to evict first whatever their recency, which is
what a bulk import or a tenant known to be idle should be. Both are empty by default, so an
unconfigured node evicts by recency and nothing else. A name in both lists, or one that is not a
valid namespace, refuses to start rather than leaving a policy that reads as configured and does
nothing.

**Ingestion is namespace-aware, and a source's namespace is fixed at registration.** `POST
/v1/ingest` reads `X-Excalibur-Namespace` and records it in the source catalog alongside everything
else the registration is rebuilt from, so every document that crawl publishes — including the
documents a re-crawl publishes after a restart — is staged into that namespace's graph, and `POST
/v1/graph/build` against it finds them. A registration that names no namespace stages into
`shared`, which is the whole of what a single-tenant node ever does. Documents supplied through
`POST /v1/documents` are staged into `shared` as well: that route refuses a namespace header, and a
supplied source leaves no registration for one to be recorded in. `POST /v1/erasure` withdraws a
document's facts from the namespace they were staged into — the one its source was registered in,
or `shared` for a supplied document — and, for a source the node can no longer place (deregistered
since, or supplied before a restart), from every namespace on disk, never creating one that does
not exist. A header that is not a legal
namespace id is refused with `400` rather than quietly given `shared`: a caller that asked for
tenant A and was handed tenant B has had its documents cross a tenancy boundary, and nothing
downstream could tell. A legal id is `[a-z0-9][a-z0-9_-]*` up to 64 characters. Naming `shared`
is the same request as naming nothing, on every route. Two names are refused: `chunk-index` and
`lucene-chunks`, which are the node's own lexical index directories. Those two sit beside the namespaces in the graph root — which is the data directory
unless `excalibur.graph.path` moves it — so a namespace of either name would resolve onto the
index rather than onto a tenant of its own, and dropping that "namespace" would delete the corpus's
lexical retrieval path. Staging borrows the tenant for the length of one document, so a source in a
namespace that has been evicted costs a reopen rather than a write to a closed file, and
`excalibur.graph.max-open-namespaces` still bounds how many graphs are open at once.

A namespace can also be listed and removed. `GET /v1/graph/namespaces` reports every namespace on
disk, not only the ones currently resident, with whether each is open, whether it has ever built a
graph, how many facts it has staged and how many erasure obligations it still owes; no graph is
opened to produce it, so an inventory does not evict the working set in order to describe it, and a
count the node could not read is reported as `null` rather than as zero. `DELETE
/v1/graph/namespaces/{namespace}` removes one entirely — its store, its staged facts and its
erasure log — and answers with an exact account of what was destroyed, because nothing restores it.
`shared` cannot be dropped: it is where every unnamespaced request lands and the node holds it open
for the life of the process, so the route answers `409`.

**Every other route refuses a namespace rather than ignoring one.** `X-Excalibur-Namespace` is
read by the `/v1/graph/*` routes and by `POST /v1/ingest`, and by nothing else: a node holds one corpus, so `/v1/query`,
`/v1/query/status`, `/v1/documents`, `/v1/erasure`, `/v1/reindex`, `/v1/compact` and `/v1/stats` have no namespace to
switch to. That includes the rest of `/v1/ingest`, which is the one place the distinction is easy
to miss: registration reads the header because it decides which tenant a source stages into, but
the source registry it writes to is one per node, so `GET` and `DELETE /v1/ingest`,
`DELETE /v1/ingest/run` and `GET /v1/ingest/status` refuse it. Honouring it there would hand a
caller who named tenant A the whole node's sources, and let them deregister a source belonging to
tenant B. Those routes answer `400` when a request names one, instead of serving the shared index
as though the header had been honoured — a caller that asked for tenant A and was handed
tenant-neutral data has no way to tell, which makes a tenancy mistake indistinguishable from a
working request, and on `/v1/erasure` it is not a mistake anything undoes. Naming `shared`
explicitly is accepted there as everywhere, because that is what those routes actually serve. Probes and the node-level
routes — `/health`, `/health/ready`, `/metrics`, `/v1/version`, `/v1/drain` — describe the process
rather than any tenant's data and ignore the header, so a sidecar that stamps it on every request
cannot take a node out of rotation.

A namespace comes into being when something publishes to it — a build, or an erasure obligation
recorded against it. `/v1/graph/query` never creates one: opening a namespace writes its staging
directory, claims one of those open slots and evicts a real tenant to get it, and the name arrives
in a header, so a question that created would let a caller spend all three on any name it chose. A
question about a namespace that has never been built is answered exactly as one about a namespace
that exists and has nothing built in it — the same 503, deliberately, because every tenant on a node
presents the same token and a different answer would say which namespaces the other tenants have.

### Securing the API

Authentication is off until a token is configured, and the startup log warns for as long as it is.
That default is safe only because it is paired with the bind policy below: without a token the
server will not listen anywhere but loopback, so "unauthenticated" and "reachable from the network"
are never true at once.

Once a token is set, every endpoint except `/health` and `/health/ready` requires it. Those two
stay exempt because the infrastructure that reads them - a kubelet, a service mesh, a cloud load
balancer's target-group health check - is frequently not yours to configure and several of those
cannot attach an Authorization header at all. Both are still rate limited. `/metrics` is
deliberately not exempt: a scraper is yours to configure and every scraper supports a bearer
token, so requiring one costs a line of configuration and keeps the node's disk capacity, corpus
size and error rates away from an anonymous caller.

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

A request without a token, or with the wrong one, is `401`. Once a token is configured that
applies to every route except `/health`, `/health/ready` and the 405 a registered path answers a
method it does not serve. The check runs before the handler, so an unauthenticated caller reaches
nothing and learns nothing about the corpus - but it can tell a registered path from an unknown
one by probing with a wrong method and reading 405 against 404. That is not treated as a secret:
the route table is in this README, and the alternative - authenticating the 405 - would make an
anonymous caller's routing decisions free while telling an attacker nothing it cannot read here.

Requests are rate limited with separate budgets for reads and mutations; exceeding either returns
429 with `Retry-After`.

Each caller is metered on its own address — the whole IPv4 address, or the `/64` for IPv6, because a
single routine IPv6 allocation would otherwise supply an endless run of fresh budgets. Behind a
reverse proxy that address is the proxy's, which puts every caller in one bucket; list the proxy in
`excalibur.api.trusted-proxies` and its `X-Forwarded-For` is read instead. The chain is read right
to left and stops at the first hop the deployment has not vouched for, so prepending a name to it
cannot get a caller metered as somebody else, and nothing is believed from a peer that is not on the
list.

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

Send raw query text. On a node with an embedding model the server embeds it using the same model that ingestion uses, guaranteeing the query lives in the same embedding space as the indexed vectors; a node without one answers from its BM25 index:

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
    {"document_id": "my-docs:guide.md:chunk:2", "score": 0.950000, "segment_id": 3}
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
  "page": {"offset": 0, "limit": 10, "returned": 1, "next_offset": null},
  "result_total": {"value": 1, "relation": "exact"},
  "timed_out": false,
  "cancelled": false,
  "from_cache": false,
  "query_time_ms": 42
}
```

`document_id` is always a JSON string, never a bare number: it is the identifier ingestion
assigned, `sourceId:documentId:chunk:N`, which is what a client can actually look something up by.
The HNSW node id is deliberately not exposed — compaction renumbers it — so a vector hit whose
graph node has no document identity, because its segment's mapping is damaged, is dropped rather
than returned under that number, and the answer is marked degraded. `segment_id` says which
segment answered, which is what makes a result traceable back to storage.

Text is not on a result unless the request asked for it: send `"include_text": true` and every
result also carries `text` - `null` for a chunk storage no longer holds - and `text_truncated`,
which says whether the passage was clipped to `excalibur.api.max-result-text-chars`. It is opt-in
because text is the larger half of a response and a caller that does not read it should not pay
for it on every query. `GET /v1/documents` and `POST /v1/documents/fetch` read the same text back
on its own, for a caller holding identifiers from an earlier search, and
`GET /v1/documents/list` produces those identifiers for a caller holding none.

Add `"snippet": true` beside it and `text` becomes the part of the passage the query matched
rather than the passage from its start, with `text_offset` saying where the window begins in the
chunk and `highlights` locating each matched term inside it. The window is a verbatim substring —
no ellipsis, no markup — so those offsets mean something to a caller rendering them.
`snippet_chars` sets the width, between 16 and `excalibur.api.max-result-text-chars`.

Every answer carries `page` (`offset`, `limit`, `returned`, `next_offset`) and `result_total`.
`result_total` is an object, not an integer, because the honest answer has two parts: `{"value":
N, "relation": "exact"}` when the engine came back shallower than the depth it was searched to
*and* the search reached the whole corpus, and `{"value": N, "relation": "at_least"}` — a lower
bound — otherwise. A zero with `exact` means nothing matched; a zero with `at_least` means the
node could not establish that. A single number cannot say both, and guessing which one it meant is
how a caller ends up reporting "no results" for a query that timed out. `offset` deepens the
search rather than resuming a cursor, because a ranked index has none, which is why
`offset + top_k` is bounded by `excalibur.api.max-top-k`.

`"explain": true` says why each result is there: `explain.signals` gives each contributing
signal's own absolute rank and its score *on its own scale*, and `explain.matched_terms` names the
query terms the passage actually contains. The response also carries `query_expansion` once, with
a three-valued `status` — `applied`, `not_applied`, or `unreported` when this node cannot say,
which is a different fact from the query not having been widened.

`retrieval` says which signals produced the answer, and is on every response. On a node holding
both an embedding model and a chunk index it reads `hybrid`, each result additionally carries the
`signals` that returned it, and `score` is a fused rank sum rather than a similarity - see
[USAGE.md](USAGE.md#hybrid-retrieval--both-signals).

`coverage` is the part a client cannot reconstruct from `results`. An empty answer with
`estimated_corpus_coverage: 0.96` means nothing matched; the same empty answer with `degraded: true`
means most of the corpus was never searched. Treating those as the same thing is the mistake the
field exists to prevent.

The server-generated `query_id` can be used to cancel the query mid-flight via
`DELETE /v1/query/{query_id}`. When that call answers `"cancelled": true`, the query answers `200`
with `cancelled: true` and no results, on every route and even if its search had already finished:
a withdrawn question is not answered. `cancelled` is the only thing separating that from a query
that searched everything and matched nothing.

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
      "excluded_directories": [".git", "node_modules"],
      "charset": "windows-1252"
    }
  }'
```

```json
{"source_id": "my-docs", "source_type": "filesystem", "status": "registered"}
```

`registered` means recorded and started, not finished. `root_path` must resolve inside
`excalibur.ingest.allowed-roots`, which is empty until configured — a 403 here is the allow-list,
not the path. The other supported `source_type` is `http`, whose `base_url` is checked against
`excalibur.ingest.allowed-hosts` and refused if it resolves to any private address.

Beyond `source_id` and the target, every field in `config` is optional and left to the source's
own default when absent. Each is bounded, and a value outside its bound is a 400 naming the field
rather than a silent clamp:

| Field | Applies to | Bound | What it does |
|-------|-----------|-------|--------------|
| `included_extensions` | filesystem | — | Only these file extensions are read |
| `excluded_directories` | filesystem | — | Directory names the walk does not descend into |
| `follow_symlinks` | filesystem | — | Whether the walk descends through symbolic links. A link out of the allowed root is still refused: the real path is what is checked |
| `include_hidden` | filesystem | — | Whether dot-files are read |
| `max_file_bytes` | filesystem | 1 – 100 MiB | Largest single file the walk will read; 100 MiB when absent. A larger file is never listed. The ceiling is the largest document the node indexes (see below) |
| `charset` | filesystem | any encoding the JVM supports | The encoding a file without a byte-order mark is read in; UTF-8 when absent. A name the node cannot decode is a 400, not a silent fall-back to UTF-8. Recorded under its canonical name, which is what `GET /v1/ingest` shows. Refused on an HTTP crawl, whose responses each name their own encoding — see [What gets indexed](#what-gets-indexed) |
| `included_path_patterns` | http | — | Substrings a URL path must contain to be crawled |
| `excluded_path_patterns` | http | — | Substrings that keep a URL path out of the crawl |
| `max_documents` | http | 1 – 10000000 | How many pages one crawl fetches |
| `max_response_bytes` | http | 1 – 100 MiB | Largest single response body held; 10 MiB when absent. A larger body fails that document. The ceiling is the largest document the node indexes (see below) |
| `timeout_seconds` | http | 1 – 600 | How long one request may take |
| `user_agent` | http | ≤ 200 chars | What the crawl identifies itself as. Screened for header injection |
| `crawl_delay_ms` | http | 0 – 60000 | The gap this crawl leaves between requests to its host. The node takes the larger of this and `excalibur.ingest.crawl-delay-ms`, so it can ask to be gentler than the deployment requires and never quicker |
| `respect_robots_txt` | http | `true` / `false` | Whether the crawl reads each origin's `robots.txt` and obeys it; `true` when absent, including for a registration recorded before the field existed. `false` is for a site whose owner has agreed to be crawled regardless — see [robots.txt](#robotstxt) |
| `max_depth` | both | 1 – 1000 filesystem, 0 – 1000 http | How far the crawl descends. Zero is a meaningful HTTP crawl — the one page named by `base_url` — and not a meaningful directory walk |

An explicit `null` is the same as leaving the field out: the default applies.

Whatever these say, one document is indexed only up to 100 MiB and 10,000 chunks — at the default
chunking (512 tokens, 64 overlapping) the chunk bound is met first, at about 18 MB. A document past
either is counted in `documents_failed`, on the first crawl and every re-crawl alike, including one
supplied to `POST /v1/documents`. The byte limit is why `max_file_bytes` and `max_response_bytes`
stop at 100 MiB: a larger file or body could only be read to be discarded. A registration recorded
with a larger value by an earlier version is restored at the limit, with a warning, not refused.

`source_id` is bounded at 256 characters. A copy of it is kept in every document's registry record
and printed with every log line about the source, so its length is multiplied by the size of the
corpus rather than by the number of requests. Every route that names a source — deregister,
reindex, erasure, status — refuses the same lengths, since nothing can be registered under one.

### Ingest Status Example

The two forms return different shapes. Named source, bare object, 404 if it was never registered:

```bash
curl 'http://localhost:8080/v1/ingest/status?source_id=my-docs'
```

```json
{
  "source_id": "my-docs",
  "state": "embedding",
  "progress": 0.7300,
  "entries_ingested": 450000,
  "entries_pending": 170000,
  "entries_skipped": 1204,
  "entries_disallowed": 0,
  "last_updated_ms": 1730000042000,
  "last_error": null
}
```

All sources, wrapped in `sources`:

```bash
curl http://localhost:8080/v1/ingest/status
```

```json
{
  "sources": [
    {"source_id": "my-docs", "state": "embedding", "progress": 0.7300,
     "entries_ingested": 450000, "entries_pending": 170000, "entries_skipped": 1204,
     "entries_disallowed": 0, "last_updated_ms": 1730000042000, "last_error": null},
    {"source_id": "docs-site", "state": "idle", "progress": 1.0000,
     "entries_ingested": 12034, "entries_pending": 0, "entries_skipped": 0,
     "entries_disallowed": 37, "last_updated_ms": 1729900000000, "last_error": null}
  ]
}
```

| State | Meaning |
|-------|---------|
| `crawling` | Walking the source, counting what is there |
| `embedding` | Chunking and embedding documents |
| `indexing` | Building index structures |
| `idle` | Nothing in progress; a source that finished sits here |
| `unconfigured` | Registered, but this node's ingestion pipeline failed to wire at startup. The documents were counted into `entries_pending` and none were indexed; `last_error` says where the reason is |

`unconfigured` is deliberately not `idle` at `progress: 1.0`. A node whose pipeline never wired
indexes nothing at all, and reporting that as a completed run would make a broken node look exactly
like a healthy one. A missing API key is *not* this state: that node has a pipeline and indexes
lexically.

The status outlives a restart. Each source's last finished run is kept in
`<data dir>/source-status`, so a source the node brings back reports that run — counts, error and
`last_updated_ms` unchanged — until its next run replaces it. A run the restart interrupted is
never reported as still running: it comes back `idle`, with `last_error` saying the run in
progress when the node stopped did not finish.

| Count | What it counts |
|-------|----------------|
| `entries_ingested` | Chunks written and published by this run |
| `entries_pending` | Documents the crawl listed and has not reached yet |
| `entries_skipped` | Documents the crawl listed that are not text, so nothing was indexed from them. Not a failure, and deliberately not folded into `last_error`: a directory holding photographs beside its documents is the ordinary case, and calling those failures would leave every such source reading as broken. An empty file, or one that decodes to nothing worth a chunk, is not counted here by the first crawl or by any re-crawl |
| `entries_disallowed` | Pages a web crawl left alone because the site's `robots.txt` disallows them — met as links, or reached through a redirect. Counted apart from `entries_skipped` because it answers a different question: a skipped photograph is ordinary, a disallowed page is one the site asked to be left out, which only its owner or a registration with `respect_robots_txt: false` can change. Not an error. Always 0 for a filesystem source. See [robots.txt](#robotstxt) |

### What gets indexed

Text, and only text. A crawl with no `included_extensions` lists **every** file under its root,
and a file's content type is guessed from its extension, so neither its name nor its type says
what it actually holds: a `.txt` can be a core dump and a `.dat` can be YAML. Each document's
bytes are therefore decoded and judged on what comes out.

The encoding is settled the same way for both source types, in this order:

1. **A byte-order mark** at the front of the document — UTF-8, UTF-16LE/BE or UTF-32LE/BE. The
   mark is written by the program that encoded these bytes, while a declared encoding is a claim
   about a whole directory or a whole server made by someone who did not look at this file, so
   when the two disagree the mark wins; it is the order the WHATWG encoding standard sets for the
   web. The mark itself is dropped, so it is not glued to the document's first word.
2. **What the source declares** — the `charset` a filesystem source was registered with, or the
   `charset` parameter of an HTTP response's `Content-Type`.
3. **UTF-8.**

So a directory of windows-1252 exports is registered with `"charset": "windows-1252"`, and the
one file in it that an editor saved as UTF-16 is still read as UTF-16. A UTF-16 file with no mark
needs the declaration (`"UTF-16LE"`), because without one it is read as UTF-8 and fails the text
test below.

The decoded text is then judged:

- Decoded characters containing **U+0000** mean the file is not text. No text format holds one and
  every binary container is full of them, which is the test `git` uses for the same question. It
  is applied to the decoded characters rather than the raw bytes, so a UTF-16 document — all zero
  bytes, no zero characters — is kept when its source declares UTF-16.
- **A third or more replacement characters** means the same. This is the guard for a binary format
  that happens to hold no zero byte. The bar is deliberately not stricter: a UTF-8 crawl reading a
  windows-1252 document sees one U+FFFD per accented character, a few percent of a European text,
  and that document is still worth indexing imperfectly.

A file that fails either test is passed over before it is chunked, so it is never embedded and
never costs an embedding call, and it is counted into `entries_skipped`. The same rule is applied
on every re-crawl, not only the first one, and to documents supplied to `POST /v1/documents` —
there it is a 400 naming the document, because a caller holding its own content can be told what
is wrong with it where a crawl can only pass a file over and count it.

`entries_skipped` is rewritten by every run, including a re-crawl, so it describes the state of
the source's root now rather than the state it was in the first time the node walked it. Every
run counts the same thing - documents that are not text, and nothing else - so an unchanged root
reads the same after a re-crawl as after the crawl that first registered it. A run that passes
over nothing, including one whose root lists no documents at all, sets it back to zero; the rest of `/v1/ingest/status` is left
to the run that wrote it, because a reindex reports how far it got through
`/v1/reindex/status`.

There are no format parsers. A PDF, a `.docx` or an image is skipped, not extracted; the node
indexes documents you can already read as text. `included_extensions` is still worth setting on a
mixed root, because it stops those files being read at all rather than read and discarded.

#### robots.txt

A web crawl obeys each site's `robots.txt` unless it was registered with
`"respect_robots_txt": false`. It follows [RFC 9309](https://www.rfc-editor.org/rfc/rfc9309):

- **Read once per origin per run.** The origin is the scheme, host and port. The file is fetched
  through the same client, host allow-list, public-address check, timeout, pacing and redirect
  handling as any page, so it opens no way around them. The crawl's path filters do not apply to
  it, because `/robots.txt` sits outside the base path of most crawls. The file is re-read by the
  next crawl or re-crawl.
- **Group.** The group is chosen by the product token of `user_agent` (`ExcaliburDB` by default),
  compared case-insensitively. When no group names it, the crawl falls back to `*`.
- **Rules.** Within the group, the longest matching rule wins and `Allow` wins a tie. `*` and a
  trailing `$` are supported, and paths are compared after percent-encoding normalisation.
  `/robots.txt` itself is always allowed.
- **Size.** The first 512 KiB are read, which is more than RFC 9309's minimum of 500 KiB. A rule
  cut in half at that point is dropped.
- **4xx.** Any 4xx answer, 404 included, means nothing on that origin is disallowed. So does a
  redirect chain longer than five hops.
- **5xx, network failure, or a redirect off the allowed hosts.** Any of these stands for a
  complete disallow, as the RFC requires. The crawl **fails**, and `last_error` names the origin
  and the reason. It does not index what it could reach, because a listing with that site's pages
  missing would read to change detection as a site that had deleted them, and they would be
  tombstoned. The registration stays, and the next scheduled re-crawl tries again.
- **Every URL is checked before it is requested.** That covers the base URL, every link the walk
  finds, and every redirect hop, on the first crawl and on every re-crawl, conditional GETs
  included.
  - A URL disallowed during the walk is never listed or fetched.
  - A fetch redirected onto a disallowed URL stops before that request leaves.
  - Either way, the page is counted in `entries_disallowed`, not as a failure.
  - A page that was indexed before the site disallowed it is removed at the next re-crawl, like a
    page the site no longer links to.
- **`Crawl-delay` can only slow a crawl.** Each request waits for the larger of the crawl's own
  gap and the site's `Crawl-delay`. The site's value is capped at 60 seconds, the same ceiling as
  `crawl_delay_ms`, so one absurd line cannot stall a crawl indefinitely.

There is no node-level switch. Every host a crawl can reach is already one the operator put on
`excalibur.ingest.allowed-hosts`, and turning robots.txt off is a decision about one site. So it
belongs to the registration for that site, where it is recorded and shown by `GET /v1/ingest`,
not to every crawl the node runs.

### How a re-crawl finds what changed

A re-crawl lists the source again and asks about each document it already holds rather than
reading it again. An HTTP source asks with a **conditional GET**: every fetch records the `ETag`
and `Last-Modified` the server sent, the document registry keeps both beside the content hash, and
the re-crawl sends them back as `If-None-Match` and `If-Modified-Since`. A `304 Not Modified` is
taken as the server's word that the indexed version is current, so the page's body is not
transferred and nothing about it is re-chunked, re-embedded or re-written. Any other answer is
read in full and compared by content; a changed page is then fetched again and replaced. A server
that ignores the conditions costs a full fetch per page and nothing worse. A `304` to a request
that carried no condition — a page the server never gave validators for, or whose stored tag
cannot travel in a header — names no version at all, so it fails that document's fetch rather
than keeping it unchecked. The link walk still reads every HTML page it follows links from,
because a page's links cannot be read from a `304`.

The validators live in the registry file, not in memory, so this holds across a restart — the
first re-crawl after one is exactly when a node would otherwise transfer the whole site again. A
filesystem source has nothing to ask with and reads each file, and an unchanged one is recognised
by its modification time, length and content hash.

### Reindex Status Example

`POST /v1/reindex` answers 202 and rewrites the source on a background thread, so this is where
the run's verdict surfaces. So does a re-crawl the node started by itself on
`excalibur.ingest.reindex-interval-ms`: the two are triggers for one operation and report through
one readback, because a scheduled run that recorded nothing left an operator with no evidence it
had happened. The two forms mirror `/v1/ingest/status` exactly:

```bash
curl 'http://localhost:8080/v1/reindex/status?source_id=my-docs'
```

```json
{
  "source_id": "my-docs",
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

```bash
curl http://localhost:8080/v1/reindex/status
```

```json
{
  "sources": [
    {"source_id": "my-docs", "state": "idle", "cancelled": false, "progress": 1.0000,
     "documents_processed": 100, "documents_total": 100,
     "started_at_ms": 1730000000000, "finished_at_ms": 1730000042000,
     "last_error": null}
  ]
}
```

The states are the ingest ones — `crawling`, `embedding`, `indexing`, `idle` — plus `cancelled`
and `failed`, which the ingest status has no equivalent of. A run stopped through
`DELETE /v1/reindex` reads as `cancelled`, with the `cancelled` boolean saying so without
parsing `state`: a stop that was asked for is not a crash, and reporting it as one sends an
operator looking for a fault that is not there. `finished_at_ms` is when the run ended, `0`
while one is still going, so how long it took and how stale its result is are both readable.
A run is `failed` only when it indexed nothing; it leaves the old segments serving, and
`last_error` says what kind of failure it was and how many documents it affected. A run that
indexed some documents and failed on others did finish, and reads `idle` at `progress: 1.0` with
`last_error` counting the failures and the documents indexed (`"Reindex completed with 3 errors;
400 documents indexed; ..."`), so a clean run is the one with `last_error: null`. Either way
`last_error` points at the node's log for the reasons themselves — those carry filesystem paths
and embedding endpoint URLs, and this route answers any caller holding a read token. A run that
indexed nothing is not reported as `idle`, because a failed run reported as idle-with-a-note
reads as a run that finished.

A source that has never been reindexed is a 404 rather than an invented idle run: "nothing has
been asked of it" and "it ran and did nothing" are different answers, and only the second means
the corpus was rewritten. `DELETE /v1/ingest` clears a source's entry here along with its
ingestion one.

The counters are `documents_`, not `entries_`. A reindex counts documents where ingestion counts
chunks, and giving the two the same name for different units would be worse than giving them
different names. They are the run's own counts: `documents_total` is how many documents the
change detection found new or changed, `documents_processed` rises by one as each is written,
and `progress` is their ratio.

### Compaction Example

Tombstoned entries — erased documents, replaced versions of re-crawled ones, the losing side of a
split — are invisible to queries and still occupy their bytes, both in the segment store and in the
lexical chunk index. Background sweeps reclaim them on a timer. `POST /v1/compact` brings that
forward for both.

```bash
curl -X POST http://localhost:8080/v1/compact \
  -H 'Content-Type: application/json' \
  -d '{"force": true}'
```

```json
{"state": "running", "force": true, "started_at_ms": 1736899200000}
```

`202`, because compaction on a large corpus is minutes of I/O and the run happens behind the
response. A second request while one is running is `409`. `force` defaults to `false`, which
applies the same thresholds the background sweeps apply — and, for the chunk index, the index's
own `lexical-*` policy — and so only brings forward work that was going to happen; `true` rewrites
every live segment and rebuilds every graph regardless, and rewrites every live segment of the
chunk index, dropping any chunk the store no longer holds. That is what to send after erasing a
large fraction of the corpus, and otherwise a lot of I/O for nothing. Under
`lexical-engine=lucene` a forced run expunges every deletion Lucene is holding.

```bash
curl http://localhost:8080/v1/compact/status
```

```json
{
  "state": "completed",
  "force": true,
  "started_at_ms": 1736899200000,
  "finished_at_ms": 1736899213820,
  "duration_ms": 13820,
  "segments_considered": 64,
  "segments_compacted": 64,
  "segments_failed": 0,
  "segments_skipped": 0,
  "graphs_considered": 64,
  "graphs_compacted": 64,
  "graphs_skipped": 0,
  "graphs_failed": 0,
  "lexical": {
    "performed": true,
    "segments_rewritten": 12,
    "segments_produced": 3,
    "chunks_removed": 1840,
    "groups_abandoned": 0,
    "segments_rebuilt": 0,
    "segments_upgraded": 0,
    "reason": "full rewrite"
  }
}
```

`state` is `idle` before the first run since startup, `running` during one, `completed` once it
finished, `cancelled` when `DELETE /v1/compact` asked it to stop and it did, and `failed` when the
run itself raised an error, in which case `error` carries the reason and there are no counts. A
`cancelled` run carries counts like a completed one: it stops at the next segment boundary, so
everything it had already replaced stays replaced and the segment it was working on is untouched —
which is why stopping one is safe at any point, and loses work rather than data. A `completed` run with a non-zero `segments_failed` is the honest
middle: what it reports was reclaimed, the rest was not, and each reason is in the node's log. A
run is not all-or-nothing and is not collapsed into either extreme. Only the most recent run is
kept.

`lexical` is the chunk index's half of the run, present only on a node that keeps one. The two
stores are compacted one after the other and reported independently, so a failure in one does
not hide what the other did: `state` and `error` describe the segment store, and a chunk index
that failed reads `{"performed": false, "error": "..."}` with no counts. `performed` false with a
`reason` is the index's policy finding nothing due (`"not due: ..."`), a run stopped before it
reached the index, or one stopped during the index's rewrite (`"stopped: ..."`). `segments_rewritten` is how many lexical segments were rewritten,
`segments_produced` how many replaced them, and `chunks_removed` how many tombstoned chunks — and
chunks the store no longer holds — did not survive the rewrite. `groups_abandoned` counts groups
of segments the rewrite had to leave as they were, because reading a surviving chunk's text back out
of storage failed in a way a later attempt may not repeat; those segments keep their dead weight and
any erasure owed in them stays owed for the next compaction. A rewrite that abandoned every group
it selected published nothing and reads `performed: false`, with a `reason` beginning `nothing
published:`; one that published some groups and abandoned others reads `performed: true` with a
`reason` beginning `partial:`. A stop that arrives while the index is being rewritten ends the
rewrite at its next checkpoint — before the next group of segments or the next chunk — rather
than by interrupting it: the index's files are shared with every query, and interrupting a thread
in the middle of file I/O would close them underneath those queries. What the rewrite had built so
far is discarded and nothing is published, so the index is exactly as it was; the run reads
`cancelled`, and `lexical` reads `performed: false` with a `reason` beginning `stopped:`. The next
compaction plans again from what is there. `segments_rebuilt` and `segments_upgraded` are the rewritten segments chosen for what they were rather than for what they
held: damaged ones that are searchable again, and ones written in an older format that are now in
the current one. Every compaction takes these on, whatever its policy says — every rebuildable
damaged segment, and up to the merge fan-in of older-format ones, oldest first — so a run that
reports them can be due for no other reason.

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
    "tombstoned": 2,
    "purged": 0
  },
  "entries": {"total": 15000000},
  "volumes": {"count": 1, "totalBytes": 5368709120, "usedBytes": 3221225472},
  "hnsw": {"queueDepth": 50000, "queueDepthLimit": 100000, "vectorsIntegrated": 14000000},
  "cuckoo": {"pendingDeletions": 0, "totalDeletions": 212,
             "avgFpRate": 0.0000070547, "maxFpRate": 0.0000137324,
             "saturated": false, "saturatedSegments": 0},
  "visibility": {"epoch": 42, "registeredSegments": 1024, "visibleSegments": 1024},
  "coverage": 1.0000,
  "workers": {"indexOrchestratorRunning": true},
  "vectorIndex": {"segmentsWithoutIndex": 0},
  "breakers": {"embedding": "CLOSED", "llmRelevancy": "CLOSED",
               "queryExpansion": "CLOSED", "graphExtraction": "OPEN",
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
               "extractionFailures": 3, "tenantFailures": 0, "sinkRefusals": 0,
               "withdrawalFailures": 0}
  }
}
```

The `lexical` block is present only on a node that keeps a chunk index, and `graph` only on a node
that serves a graph. `breakers` holds one entry per circuit breaker the node has — `embedding`,
`llmRelevancy` for stage-5 re-ranking, `queryExpansion` for the chat call that widens a lexical
query, `graphExtraction` for the chat call that finds a document's facts, `graphRerank` for the
chat call `POST /v1/graph/query` reranks its candidates with, `graphQueryEmbedding` for the
embedding call that lets a graph built with entity vectors rerank them exactly, and
`graphBuildEmbedding` for the embedding call a graph build makes to produce those entity vectors —
and leaves out any component the node does not run, because a missing breaker and a closed one are
different facts. Each is `CLOSED`, `HALF_OPEN` or `OPEN`. The three graph breakers are one each for
the node, not one per namespace: every namespace's graph calls the same endpoint through the same
client, so one state is true for all of them and the block does not grow with the tenants. Each
appears once it is first needed — `graphRerank` with the first graph opened, `graphQueryEmbedding`
only once a graph with entity vectors is, and `graphBuildEmbedding` only once a build has embedded
an entity. The two embedding breakers are separate even though they call the same endpoint and
model: an open build breaker costs a build's entities their vectors until the next build, an open
question breaker costs one query its vector rerank, and neither should decide the other.
`cuckoo.saturated` and `cuckoo.saturatedSegments` say
whether the membership filter still has room to order a query's segments by. `cuckoo.avgFpRate` and `maxFpRate` are
the false-positive rates of the per-segment filters as they stand now, printed as exact decimals;
the deletion figures count filter entries (one per distinct term per segment), not documents. See [the stats section of USAGE.md](USAGE.md#stats) for what
every field means.

`purged` is always `0`. Reclaiming a segment deletes its catalog row rather than keeping a
tombstone of one, so a retired segment is counted under `tombstoned` until it is reclaimed and
then counted nowhere. `coverage` is the share of the live corpus — everything not retired — that
is in a state a query reads, so on a node whose segments are all accounted for it reads `1.0000`.
It is a stranded-segment check, not a warm-up gauge — a segment still absorbing its write log is
searched, so it counts.

### Graph Examples

Publish what ingestion staged, then ask. Both take an optional `X-Excalibur-Namespace` header; a
request without one uses the shared namespace.

```bash
curl -X POST http://localhost:8080/v1/graph/build
```

```json
{"state": "running", "namespace": "shared", "started_at_ms": 1736899200000,
 "extraction": "active"}
```

`extraction` says whether ingestion on this node extracts the facts a build publishes, and is on
every answer from both routes: `active`, `disabled` when `excalibur.graph.extraction-enabled` is
off, `missing_api_key` when it is on but no LLM API key is configured, or `unavailable` when both
are set but the ingestion pipeline could not be set up — its failure is in the startup log. The
last two also carry a `warning`, because nobody chose them and the build they answer publishes
nothing ingestion staged since startup. The status route carries the same fields.

`202`, not `200`. On a real corpus the build takes minutes — it replays every staged fact, embeds
every entity and writes two Lucene indexes — so it runs behind the response and the request does
not hold a connection open for it. Poll for the outcome:

```bash
curl http://localhost:8080/v1/graph/build/status
```

```json
{
  "state": "completed",
  "namespace": "shared",
  "started_at_ms": 1736899200000,
  "finished_at_ms": 1736899261000,
  "published": true,
  "edges": 18432,
  "entities": 5210,
  "relations": 87,
  "entities_with_text": 5210,
  "entities_with_vectors": 5210,
  "staged_facts": 19004
}
```

`state` is `idle`, `running`, `completed`, `cancelled` or `failed`. One build runs at a time across
the node — it saturates the same disk and the same embedding endpoint either way — so a second
`POST` while one is in flight is refused with `409` naming the namespace that holds it.

A node that has staged nothing keeps serving the graph it already had — replacing it with an empty
one would turn every question into a refusal. Its status says so and why, from `extraction`:

```json
{"state": "completed", "namespace": "shared", "extraction": "missing_api_key",
 "warning": "excalibur.graph.extraction-enabled is true, but no LLM API key is configured, ...",
 "message": "Nothing was staged, so nothing was published and this namespace is still serving the graph it had. excalibur.graph.extraction-enabled is true, but no LLM API key is configured, ...",
 "published": false, "edges": 0, "staged_facts": 0, ...}
```

`DELETE /v1/graph/build` stops one started at the wrong moment:

```bash
curl -X DELETE http://localhost:8080/v1/graph/build
```

```json
{"cancelled": true, "message": "The graph build was asked to stop; poll /v1/graph/build/status for when it has. Nothing it had assembled is published."}
```

Cancellation is cooperative, never an interrupt, and it is asymmetric on purpose. The build
assembles everything in a directory beside the served one and publishes by renaming it over the
top; every checkpoint sits *before* that rename, so a stopped build publishes nothing and the
namespace goes on answering from the graph it had. Past the rename the build finishes regardless —
an interrupt inside the close-rename-reopen window would leave a namespace serving nothing over an
intact graph on disk, which is worse than the wait. Worst case between the `DELETE` and the
`cancelled` state is one embedding batch.

```bash
curl -X POST http://localhost:8080/v1/graph/query \
  -H 'Content-Type: application/json' \
  -d '{"query": "who reported the Frankfurt outage?", "as_of": "2019-03-01"}'
```

```json
{
  "status": "answered",
  "context": "...serialised subgraph...",
  "confidence": 0.8140,
  "as_of": "2019-03-01",
  "as_of_source": "request",
  "tokens": 1873,
  "facts_included": 24,
  "facts_dropped": 3,
  "truncated": true,
  "format": "triples-v1",
  "plan": "lookup",
  "timings_ms": {"plan": 0.412, "link": 4.211, "traverse": 12.008,
                 "rank": 26.894, "serialize": 1.077, "total": 44.602}
}
```

Three statuses, and a client has to tell them apart:

| Status | Meaning |
|--------|---------|
| `answered` | Context to put in a prompt, with a calibrated confidence |
| `needs_input` | The question did not say which entity it meant. Carries `reason` — one of `unbound_pronoun`, `ambiguous_pronoun`, `no_entity_mention` or `ambiguous_entity` (a name several entities share, which the evidence could not tell apart) — plus `question` and `candidates`; send the chosen one back in `context_entities` with the same query. Entities that share a name are offered as the name followed by ` #` and the entity id (`Ada #12`), so the one sent back names exactly one of them. Returned as 200 — the request was well-formed and the system did what it should |
| `declined` | The evidence does not support an answer. `reason` is one of `no_match`, `below_confidence_threshold`, `low_provenance_confidence` or `unresolved_reference`. Deliberately not an empty `answered`, which a client would render as "nothing found" |

`tokens`, `facts_included`, `facts_dropped`, `truncated` and `format` come with the serialised
context, so they are absent on an `answered` response that carries none — read `context` and treat
the five as optional rather than assuming a shape. `format` names the serializer that produced the
context, so an A/B test can attribute an outcome to it. `timings_ms` carries only the stages that actually ran, drawn from a fixed set — `plan`, `link`,
`traverse`, `rank`, `serialize` — plus `total`. It comes back on all three
statuses, because a refusal that took two seconds is as much a performance question as an answer
that did.

`context_entities` is the conversation the question is asked in, which is what a pronoun in it
binds to. The node keeps no session, so the client sends it with every question. Two spellings:

```json
{"query": "What did she write?",
 "context_entities": ["Analytical Engine", "Ada Lovelace"]}
```

```json
{"query": "What did she write?",
 "context_entities": [
   {"name": "Charles Babbage",   "grammar": "singular_person", "turn": 0},
   {"name": "Analytical Engine", "grammar": "singular_thing",  "turn": 1},
   {"name": "Ada Lovelace",      "grammar": "singular_person", "turn": 1}]}
```

The first is names alone: every one of unknown shape and all mentioned in the same turn, so two of
them leave a pronoun `ambiguous_pronoun` and the reply asks which was meant. The second says what
the client knows about each, and both fields are optional:

| Field | Values | What it decides |
|-------|--------|-----------------|
| `name` | the entity's name; required | what the pronoun is bound to |
| `grammar` | `singular_person` (he, she, him, her…), `singular_thing` (it, this, that), `plural` (they, them, these…), `unknown` (any); case-insensitive; default `unknown` | which pronouns can refer to it — "she" never binds the engine above, "it" never binds a person |
| `turn` | a whole number from 0, higher being more recent; default the latest turn given | between two entities a pronoun fits, the later turn wins; the question is asked in the turn after the latest one, and a pronoun reaches back three turns including the question's own, so an entity more than two turns before it is not bound to at all |

So in the second example "she" binds Ada Lovelace: the engine is the wrong shape and Charles
Babbage was a turn earlier. The two spellings are not mixed in one array — a client with a shape
for some entities sends the rest as `{"name": ...}`. A shape that is not one of the four, a turn
that is not a whole number in range, and an entry with no `name` are each a 400 naming the entry,
rather than being read as absent: they were sent to rule a binding in or out.

The request is bounded: `query` at 4096 characters, `context_entities` at 128 entries whose names
are at most 256 characters each. Exceeding either is a 400 naming the bound. A context is refused
rather than truncated, because dropping part of one silently changes which entity a pronoun binds
to and the answer would read as though the whole conversation had been given.

A field of the wrong type is a 400 with the reason in the body — `{"query": 7}`,
`{"context_entities": "Ada"}` (a string where an array is expected) or
`{"as_of": 2019}` — rather than being read as though it had been left out; answering a question
the caller did not ask is the worse outcome. An explicit `null` is the same as leaving the field
out, so `{"query": null}` is the missing-`query` 400 and `{"as_of": null}` is answered as though
no instant had been named.

`as_of` names the valid-time instant to answer at. Three spellings:

| `as_of` | Meaning |
|---------|---------|
| `2019-03-01` | An ISO-8601 date: what held on that day |
| `2019-03-01T14:00:00Z` | An ISO-8601 instant, resolved to the UTC day it falls in — valid time is kept to day granularity |
| `all` | The whole history rather than a snapshot of it, so a fact and the one that superseded it both come back. A value superseded on the day it began appears only here |

Anything else is a 400. A value that could not be understood must not quietly become "now": a
request that asked for 2019 and was answered for today is wrong in a way nothing in the response
would show.

Every walk happens at some instant, so every answer reports the one it used and where that instant
came from. `as_of_source` is `request` when the caller named it, `question` when the classifier
read it out of the question's own date words, and `default` when neither did and the walk ran at
today. The pair is what makes the echo honest: an instant nobody asked for is reported, and is
reported as not having been asked for, so "who reported the Frankfurt outage in 2019" cannot come
back answered as of today with nothing in the response to say so. Both fields are absent on
a `needs_input` from planning — a pronoun or a missing name — which stops before the traversal;
one for an ambiguous entity did walk, and reports its instant.

A question asking how many, or which came first or last, is answered with the value as well as the
evidence: `aggregate` carries `function` (`count`, `earliest` or `latest`), `count`, `facts` (how
many it was computed over), `entities`, and, for the two extremes, `valid_from` — the day the fact
began, or null when nothing had a recorded start. A count reads the facts that held at the instant;
an extreme reads every fact that had begun by it, ended or not, because "her first home" is rarely
the current one. The context leads with the same line.

`direction` restricts which way the walk steps from the entities the question names: `forward`
reads the facts they are the subject of, `reverse` the facts they are the object of, `both` (the
default) either. Anything else, or a non-string, is a 400. A one-way walk needs the graph's
relation vocabulary; a graph pipeline embedded without one answers `forward` or `reverse` with a
422 saying to omit `direction` or send `both`, never a 500 — the request is well formed, and a node
with the vocabulary answers the same body.

```bash
curl -X POST http://localhost:8080/v1/graph/erasure \
  -H 'Content-Type: application/json' \
  -d '{"scope": "entity", "entity_id": 42}'
```

```json
{
  "request_id": 7,
  "accepted": true,
  "compacted": false,
  "edges_removed": 0,
  "requests_satisfied": 0,
  "requests_withheld": 0,
  "outstanding": 1,
  "namespace": "shared",
  "detail": "policy declined: 12 of 18432 edge(s) tombstoned, 4 segment(s), oldest request 2026-08-31T14:02:11Z"
}
```

202, not 200: accepting is not removing. The obligation is recorded durably before this answers, so
it survives a restart, and a compaction rewrites the edges out when the ratio or the latency bound
says it is due. `"compact": true` in the request forces it immediately, for a caller under a
deadline that has to be able to say the data is gone. The scopes are
`{"scope":"entity","entity_id":N}`, `{"scope":"entity","entity_name":"Ada Lovelace"}`,
`{"scope":"source","source_type":"DOCUMENT","source_id":N}` and
`{"scope":"extractor","version":N}`.

In the source scope, `N` names one ingested document, not a registered source: it is the XxHash64
(seed 0) of the UTF-8 bytes of the document's key, `<source_id>:<document_id>` — `my-docs:guide.md`
for the document whose chunks come back from `/v1/query` as `my-docs:guide.md:chunk:N`. It is
stamped on every fact and snippet extracted from that document, and a caller reads it back off the
answers: each fact in a graph answer's `context` ends with its provenance, `{document#N conf=0.9
ext=v1}`, and that `N` is the one to send. To withdraw a document from the corpus and the graph at
once, use `POST /v1/erasure` with its `source_id` and `document_id` instead — it removes the chunks
and submits this graph obligation for you, so nobody has to compute the hash.

An entity id is internal, and a graph answer does not carry one: its `answer` and `context` name
entities by label. The node hands an id out in exactly two places, both where a name alone is not
enough to say which entity is meant — a `needs_input` candidate for a name several entities share
(`Ada #12`), and the `409` below. So `entity_name` is how "erase everything about this person" is
ordinarily asked. It is matched exactly, ignoring case and surrounding space, against the served
entity dictionary and the staged labels it is built from - never against the `labels` index, whose
whole value at query time is that "Lovelace" finds "Ada Lovelace" and whose whole danger here is
the same thing. A name no entity has is a `404`; a name two entities share is a `409` listing their
ids in `candidates`, so the caller repeats the request with the `entity_id` of the one they meant.
Resolving a name never creates the namespace it is looked up in: a namespace that does not exist
holds nobody, so the answer is the same `404` a namespace that exists would give, and nothing is
written. A request that records an obligation — a resolved name, an `entity_id`, a source or an
extractor version — creates the namespace if it is new, which is how an erasure can be recorded
against a tenant before its first build.

The compaction rewrites the staged facts as well as the served store. Erasing only the store would
leave every erased fact in the staging log, where the next `POST /v1/graph/build` would replay it
and put back exactly what was removed — and the fact would be sitting on disk in the meantime,
which for an erasure obligation is the point rather than a detail. Entity snippets are rewritten
too: each one records the document it was copied out of, so every scope reaches them. An
entity-scoped erasure additionally replaces the entity's label with a tombstone, because a name is
the thing a person's right to erasure is usually about, while a withdrawn document must not take
the names of everyone it mentioned with it.

`requests_withheld` is what the node could not honour and deliberately left outstanding rather
than reporting as done. It counts requests whose data this pass could not reach — snippets
recorded before origins were kept, which a source- or extractor-scoped request has nothing to
match on, and, on a node with no embedding endpoint, the entity vectors a withdrawn document is
still encoded in. Those requests stay outstanding and are logged at `ERROR`; reporting an erasure
that did not happen is the worst outcome this mechanism has, so it does not.

**The compaction reaches the served directory too, so no rebuild is needed.** The graph is served
out of a directory holding more than the edges: the entity dictionary, a Lucene index over entity
labels, another over each entity's text, and a vector computed from that text. A compaction
assembles a replacement for the whole directory rather than carrying it forward — the dictionary
entry becomes a tombstone, the erased entity's documents are deleted from both Lucene indexes and
every segment that held them is rewritten, and the vector slot is cleared. What a withdrawn
*document* leaves behind cannot be edited out that way, because an entity's text is prose
accumulated from every source that mentioned them; those two artifacts are instead rebuilt from
the staging the erasure has already cleaned, which is why the staging erasure runs first. A node
with no embedding endpoint can rebuild the text and not the vectors, and says so in
`requests_withheld` rather than publishing a graph whose vectors were silently dropped.

## Pointing a model at it

The graph endpoint answers in the shape a model needs - entities and facts to ground on, a
confidence, and an explicit refusal when the evidence is thin - and an MCP server ships alongside
it so a model can reach it without any glue of yours.

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector \
     -cp excalibur.jar com.eonmux.excalibur.api.mcp.ExcaliburMcpServer
```

It speaks MCP over stdin and stdout and talks to a running node over HTTP, so it is configured by
environment rather than by the properties file:

| Variable | Meaning | Default |
|----------|---------|---------|
| `EXCALIBUR_URL` | Base URL of the node | `http://127.0.0.1:8080` |
| `EXCALIBUR_API_AUTH_TOKEN` | Bearer token, when the node requires one | none |
| `EXCALIBUR_NAMESPACE` | Default namespace for the tools that take one: the three graph tools, and `ingest_documents` and `crawl_website`, which fix a source's namespace at registration. A call's own `namespace` argument overrides it. Every other tool serves the whole corpus and sends no namespace, since those routes refuse one | the shared one |
| `EXCALIBUR_MCP_TIMEOUT_SECONDS` | Per-request timeout | 60 |

The flags are the ones the node itself runs with — the server lives in the same jar, and the
start scripts and the container image pass all three.

Registered with an MCP client, for example in `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "excaliburdb": {
      "command": "java",
      "args": ["--enable-preview", "--enable-native-access=ALL-UNNAMED",
               "--add-modules=jdk.incubator.vector",
               "-cp", "/path/to/excalibur.jar", "com.eonmux.excalibur.api.mcp.ExcaliburMcpServer"],
      "env": {"EXCALIBUR_URL": "http://127.0.0.1:8080"}
    }
  }
}
```

### The tools

| Tool | Arguments | What it does |
|------|-----------|--------------|
| `ingest_documents` | **`source_id`**, **`root_path`**, `included_extensions`, `excluded_directories`, `max_depth`, `follow_symlinks`, `include_hidden`, `max_file_bytes`, `charset`, `namespace` | Registers a directory and starts indexing it. Every setting a filesystem registration accepts over HTTP is here, with the same meaning and bounds — see the config fields under [Ingest Example](#ingest-example). An optional `charset` names the encoding its files are read in when they carry no byte-order mark (UTF-8 when omitted); a file with a mark is read in the encoding the mark names, and the node refuses an encoding it does not know |
| `crawl_website` | **`source_id`**, **`base_url`**, `max_depth`, `max_documents`, `max_response_bytes`, `timeout_seconds`, `included_path_patterns`, `excluded_path_patterns`, `user_agent`, `crawl_delay_ms`, `respect_robots_txt`, `namespace` | Registers an HTTP source and starts crawling it. Every setting an HTTP registration accepts is here, with the same meaning and bounds; `crawl_delay_ms` can only slow a crawl below the node's own `excalibur.ingest.crawl-delay-ms` pace, never speed it up, and `respect_robots_txt` (on unless `false`) obeys the site's [robots.txt](#robotstxt) |
| `add_documents` | **`source_id`**, **`documents`** (each **`document_id`**, **`text`**) | Indexes documents sent in the call itself, for content the model is holding |
| `ingestion_status` | `source_id` | How far indexing has got, with `entries_skipped` (not text) and `entries_disallowed` (kept out by robots.txt) counted apart from errors |
| `reindex_source` | **`source_id`** | Re-reads a registered source and replaces what changed |
| `reindex_status` | `source_id` | How far a reindex has got, and whether it finished, failed or was cancelled |
| `deregister_source` | **`source_id`** | Stops a source being crawled again; the documents already indexed stay |
| `search_documents` | **`query`**, `top_k` | Searches the indexed documents and returns the matching passages, best first |
| `build_graph` | `namespace` | Publishes the staged facts as the served graph |
| `graph_build_status` | `namespace` | How far a build has got, and what the last one published |
| `search_graph` | **`query`**, `context_entities`, `as_of`, `direction`, `namespace` | Asks the graph a question, optionally `as_of` a date, an instant or `all` of history, in one `direction`, and in a conversation: `context_entities` names what was mentioned, each with an optional grammatical shape and turn so a pronoun binds the right one |

Arguments in bold are required; every other one is optional and, when left out, is not sent, so
the node's configured default applies rather than one the adapter chose.

Together they are the whole loop, so a model can prepare a corpus as well as query one. The order
matters: `build_graph` has to run after ingestion finishes - it answers as soon as the build
starts, so `graph_build_status` is how a model waits for one - and `search_graph` reports that no
graph is being served until it has.

The two ways in are not alternatives either. `ingest_documents` points the node at a directory,
which has to be inside an allow-listed root - the right policy for a node that goes and reads
things by itself, and one that refuses every model on a node with no allowed roots configured.
`add_documents` sends the text in the call, needs no allow-list because nothing is read on the
model's behalf, and indexes before it returns, so there is no status to poll. `crawl_website` is
the third, and is separate rather than a flag on `ingest_documents` because the two carry
settings the other's registration refuses. They must not share a
`source_id`: a crawl removes every document under its source that the crawl itself did not list, so
each refuses an id the other has taken.

Registering is not a one-way door. `reindex_source` re-reads a source that has already been
registered and replaces what changed - which is what a model reaching for a second
`ingest_documents` under the same id was trying to do, and what that call refuses with a 409.
`deregister_source` is the way back: it stops the source being crawled again and leaves every
document it indexed in place. Neither erases anything, which is why they are here and the
erasure routes are not - `POST /v1/erasure` and `POST /v1/graph/erasure` destroy data and stay
operator-only.

The two searches are not alternatives. `search_documents` returns the passages that match and
needs nothing but an ingestion run - the adapter asks the node for the text, because a model has
no way to resolve a chunk identifier against the store the corpus was ingested from;
`search_graph` returns entities and the facts connecting them
and needs a graph, which in turn needs a chat model and `excalibur.graph.extraction-enabled`, off
by default. A model given only the graph search has nothing to reach the corpus with on an
ordinary node.

`search_graph` returns a `status` the caller has to read. `answered` carries the evidence in
`context` with a confidence score, and an `aggregate` for a how-many, first or latest question;
`needs_input` means the question did not say which entity it meant — a pronoun with nothing to
refer to, or a name several entities share — and carries a question for the user and candidates to
pass back as `context_entities`; `declined` means the graph does not hold enough to answer - a
result, not an error. `direction` takes `forward`, `reverse` or `both`; anything else is a tool
error.

`context_entities` is the conversation so far, in the same vocabulary as the HTTP route: each
element a name, or an object `{"name", "grammar", "turn"}` whose `grammar` is `singular_person`,
`singular_thing`, `plural` or `unknown` and whose `turn` is a whole number from 0, higher being more
recent. Unlike the HTTP route, a model may mix the two - the tool sends every element as an object
when any one is - so it need not rewrite the names it has no shape for. A shape outside the four
(a blank one included), a negative or fractional turn, an object with no `name` or a blank one, a
`null` element, or a field other than those three is a tool error naming the element; the last
because the node ignores a field it does not read, and a misspelt `"gramar"` would otherwise
arrive as an entity of unknown shape. An array of names is sent exactly as written, an empty one
and blank names included; the node reads a blank name as mentioning nobody, so in a mixed array a
blank name is left out rather than sent as an object the node would refuse.

Arguments are checked before anything is sent. One of the wrong type - a number where a string is
declared, a string where an array is - is returned as a tool error naming the argument rather than
coerced or dropped, so a model learns what it got wrong. An integer argument accepts `5`, `5.0` or
`"5"`, and refuses a fraction. A true/false argument accepts `true` or `"true"`, in any case, and
refuses anything else - `1` and `"yes"` included - rather than guess which was meant. An array is
forwarded element for element, exactly as the same body sent over HTTP would be: an empty one is
never dropped, and a blank or space-padded element is neither dropped nor trimmed - what it means
is the node's to decide, and in a list setting the node trims each element and ignores a blank
one, so `[""]` there means what `[]` does. A `null` element is a tool error, as it is a `400` over
HTTP. Supplying a list replaces the node's default rather than adding to it, so an empty one
clears it. An empty
`excluded_directories` on `ingest_documents` walks every directory, `node_modules` and `target`
included; an empty `included_extensions` reads every file, which is also what leaving it out does;
and an empty `excluded_path_patterns` on `crawl_website` clears the crawl's default exclusions.
Leaving a list out keeps the node's default.

### Without MCP

The same catalogue, in the form the OpenAI and Anthropic function-calling APIs take. Name the
shape, and what comes out is what that API accepts, ready to send:

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector \
     -cp excalibur.jar com.eonmux.excalibur.api.mcp.ExcaliburMcpServer --print-schema anthropic
```

| Shape | What it writes |
|-------|----------------|
| `anthropic` | `{"tools": [{"name", "description", "input_schema"}]}` for the Messages API |
| `openai` | `{"tools": [{"type": "function", "function": {"name", "description", "parameters"}}]}` |
| `mcp` | The wire shape `tools/list` returns, with the schema under `inputSchema` |

With no shape named it writes all three under their own keys, `{"mcp": [...], "anthropic": [...],
"openai": [...]}`. The three are not interchangeable - each is a 400 at the other two - so the
flag names one rather than picking a default. `--print-schema=anthropic` works as well as
`--print-schema anthropic`.

### What has to be configured first

Only one thing is required: filesystem ingestion needs the directory listed in
`excalibur.ingest.allowed-roots`, and HTTP ingestion needs the host listed in
`excalibur.ingest.allowed-hosts`. Without a matching entry the registration is refused.

`excalibur.llm.api-key` is optional. Without it the corpus is chunked, stored and term-indexed and
`/v1/query` answers from BM25 with query-side expansion; with it the corpus is also embedded and
`/v1/query` fuses vector search with that same BM25 index, reporting `"retrieval": "hybrid"`. Set
`excalibur.query.hybrid-retrieval=false` for vector-only ranking. Fact extraction needs the same
key plus `excalibur.graph.extraction-enabled=true`.

## Architecture

### Module Structure

| Module | Description |
|--------|-------------|
| `excalibur-core` | Memory-mapped I/O, hash functions, checksums, chunk payload codec, segment VarHandles |
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
| `excalibur-api` | HTTP server, API endpoints and the MCP server |
| `excalibur-fsck` | Offline integrity check and repair for a data directory |
| `excalibur-backup` | Verified snapshot, verify and restore for a data directory |
| `excalibur-metrics` | JMX metrics and observability |
| `excalibur-config` | Configuration loading and validation |
| `excalibur-test` | Test utilities and fixtures |
| `excalibur-test-bootstrap` | Initializes `java.lang.foreign` before the first test in every module |
| `excalibur-integration` | Integration tests |
| `excalibur-bench` | JMH benchmarks |

### Query Pipeline

Queries execute through a 7-stage pipeline:

1. **Epoch Snapshot** - Capture visibility epoch, resolve queryable segments
2. **Segment Prioritisation** - Order the candidate segments by estimated selectivity
3. **Cuckoo Filter Cascade** - Move the segments that hold none of the query text's terms behind the ones that may, checking a corpus-wide filter and then each segment's own. Excludes no segment. There is no level between them: documents are placed by key hash, so a group of segments holds nearly the whole vocabulary and could tell nothing apart
4. **LSH Candidates** - Name each segment's entries that share the query's hash buckets, for stage 5 to measure beside its graph search. Excludes no segment and confines no search
5. **HNSW Vector Search** - Precise ANN search, with stage 4's candidates measured and ranked in, and a tail scan for unindexed entries
6. **Full Vector Rerank** - Exact distance computation on candidates
7. **LLM Relevancy Scoring** - Optional semantic relevancy scoring

Each stage checks for cancellation and timeout, allowing queries to return partial results if time-constrained.

Stage 2 orders and never excludes. The estimate comes from a Count-Min sketch, which over-counts,
and a segment dropped on an over-count loses results that are in the corpus; a segment with no
sketch is given the highest estimate rather than the lowest. What the ordering buys is that the
stage after it, and a query that runs out of time, spend what time there is on the segments most
likely to answer.

Every segment keeps its own sketch. The storage engine counts the terms of each chunk it writes
into a segment, whichever path the write came from (`POST /v1/ingest`, `POST /v1/documents`,
`POST /v1/reindex`, or a split or compaction copying entries into a new segment), using the same
tokenisation as the cuckoo filters, and the query's text is cut the same way. A term's estimate is
its share of all the term occurrences counted in that segment; a query of several terms multiplies
the shares.

- **Deletes and overwrites over-count.** A sketch cannot take a count back, so a deleted or
  overwritten chunk's terms stay counted until the segment is compacted, which builds the
  replacement's sketch from the surviving entries only. The error only ever moves a segment
  earlier.
- **Persistence.** The sketch is written beside the segment as `segment_<N>.tfs` (versioned
  header, CRC32C over header and body, staged and renamed into place) when the engine flushes or
  closes the segment. The file records the write-log sequence number and entry counts it was taken
  at. On start-up, after the write log has been replayed, a sidecar that matches is loaded; a
  missing, damaged or out-of-date one (which is what a crash leaves) is rebuilt from the segment's
  live entries. Deleting the file is always safe; it costs one rebuild. `excalibur-fsck` verifies it
  and reports a damaged one without counting it as damage, and `excalibur-backup` copies it as
  derived state that `--exclude-derived` leaves out.

Stage 3 orders and never excludes, for the same reason stage 4 never does. Every query the
pipeline runs is a nearest-neighbour search over an embedding, and `POST /v1/query` hands it the
question's text beside that embedding. The embedding is there because the words a question is
phrased in need not be the words of the documents that answer it — the semantic expansion example
above turns `out of RAM` into `OOM killer` for exactly that reason — so a segment holding none of
the question's terms can hold its nearest neighbours, and dropping it would lose them without
saying so. What the filter's answer does say is where the question's words are, and a segment
holding them is the likelier one to answer. Stage 3 therefore moves the segments the filter vouches
hold none of the terms behind the others, keeping stage 2's order within each group, and every
segment is searched. As with stage 2, what the ordering buys is that a query which runs out of
time has spent it on the segments most likely to answer. The two signals differ: stage 2's
estimate multiplies each term's share of a segment and over-counts, while the filter asks whether
any one term is present at all.

Stage 3 moves a segment back only where the term filter can speak for it. Both write paths fill it:
`POST /v1/reindex` through the batch publisher, which targets a segment it chose itself, and
`POST /v1/ingest` through the storage engine, which reports the segment each chunk routed to.
Entries also reach a segment without being written to it, and each such segment's level is refilled
from the entries it holds, on a background thread, one segment at a time: every segment when the
node starts (the filter is in memory only), both children of a split, and the replacement a
compaction installs — which also drops the terms of the entries the compaction removed. A level is
marked stale before the segment can take a write that would make it look complete — for a split,
before either child is routed to — and a stale level is not trusted until its refill finishes, so
a segment keeps its place meanwhile rather than being moved back on a partial level. Four things
leave the filter with nothing to say about a segment — the filter holding nothing for it, its
level waiting to be refilled, a level having refused a term for want of room, and no filter being
configured at all — and in each case stage 3 leaves the segment among those searched first. The
filter decides only the order; it never costs a result. `QueryPipelineRecallGateTest` holds the
pipeline to that with query text present.

Stage 4 adds candidates and never removes any. A query asks for the k nearest vectors, and every
non-empty segment has k nearest vectors however far away they are; an LSH bucket says which entries
hashed alongside the query, a likelihood that falls off with distance, and bounds nothing about the
distance of what it leaves out. An empty answer therefore means "nothing within the radius the hash
shape favours", not "no neighbours here", and a short answer is some likely neighbours rather than
the only ones. So stage 4 excludes no segment and confines no search: it hands stage 5, per
segment, the node ids that share the query's buckets, and stage 5 measures those nodes exactly and
ranks them together with what the graph search returned. A named node enters the answer only by
being closer than a result the graph search found, so the answer is never worse than the graph
search alone, and is better wherever the graph's beam walked past a near neighbour the buckets
caught. The quantised search, where it is enabled, runs exactly as it would without the candidates;
its results are rescored on full-precision vectors, so they rank against the measured candidates on
one scale.

The candidate ids are carried on the dispatcher input the segment is searched under, because each
segment has its own index, its own probe and its own node ids. The two stages therefore share one
id space, and it is the graph's: the pre-filter is written by the HNSW integration worker as it
assigns node ids, not during ingestion, which holds the vector but not yet the id it will be
searched by. A pre-filter populated from any other id space would name nothing the graph holds, or
the wrong vectors, and contribute nothing but wasted measurements.

A candidate set names a scattering of nodes drawn from wherever the pre-filter's buckets fell, not a
connected region of the graph, so the graph's edges are used to reach named nodes and never to
decide which of them may be measured. Stage 5 measures a set one of two ways, whichever does less
work: it reads every named node when the set is small, which is exact at one node read per name, or
it runs a second graph search that keeps only named nodes. Reading reads `|C|` nodes; a search that
keeps a `|C| / nodeCount` fraction of what it visits reads on the order of `ef * nodeCount / |C|`
before its beam is full, so reading is both the cheaper and the exact answer while
`|C|² <= ef * nodeCount` - which for a hundred-thousand-node segment at `ef = 200` covers every
candidate set the default shape realistically produces. That is the whole cost of stage 4 to a
query: a probe of each table, plus those reads.

Because it is keyed by node id, a pre-filter is only meaningful beside the graph it was built
against. The storage engine therefore attaches one wherever it attaches a graph - segments it
creates, segments it recovers at start-up, and split children - discards the persisted state a
split child would otherwise inherit under its parent's name, and carries it across a compaction,
which replaces the file but not the graph.

Since the candidates can only add to an answer, an index that disagrees with its graph costs work
and nothing else: one that has not yet seen every node names fewer of them, an id naming no live
node is skipped when it is measured, and no index has ever seen the un-integrated write-log tail,
which stage 5 scans directly from the log regardless. `QueryPipelineRecallGateTest` holds the whole
pipeline to its graphs' recall against brute force - see [Running Benchmarks](#running-benchmarks).

### Segment Identity

A segment has two numbers, and only one of them identifies it to the rest of the system.

- The **directory index** is the routing slot the storage engine holds the segment at. The
  catalog, the visibility tracker, the term filter hierarchy, the graph provider and payload
  resolution are all keyed by it, and it is what a query snapshot carries.
- `Segment.segmentId()` counts segment instances constructed by the process. It identifies an
  instance - a zero-copy slice checks it to confirm the slice came from the segment being asked -
  and nothing files a segment under it.

The two coincide while segments are created in directory order, so they agree on a freshly
started engine and part company as soon as one is created out of it: a compacted replacement, or
a split child. Looking a segment up by the wrong one finds nothing and raises nothing.

### Storage Model

Data is organized into segments, each backed by memory-mapped files:

- Hash-based routing provides O(1) segment lookup
- Append-only write log with CRC32 checksums
- Every stored entry carries a CRC over its key and value, verified on every read
- Background compaction removes tombstones, one segment at a time
- Elastic segment splitting based on load factor thresholds (default 0.7)

Every read that hands entry bytes back checks that entry's CRC first. A damaged entry is
*reported* — never returned as data, and never flattened into "not found", because a corrupt
entry and a missing one call for different actions. Damaged key bytes count too: without the
check, a flipped bit in a key made the entry read as simply absent. A split or a compaction
verifies each entry before copying it, so damage cannot be carried into a fresh file and given a
checksum saying it is sound.

Compaction is not free, and it is worth knowing what it costs. A segment is rewritten under its
own write lock, held for the whole rewrite — every live entry is copied, the replacement is
flushed, and the two are swapped — so for that span both reads *and writes* of that segment
wait. They wait rather than failing or reading stale bytes, which is the point: a rewrite that
let a write through between the copy and the swap would lose it.

One segment is unavailable at a time, for as long as its own rewrite takes, and other segments
are unaffected — so what a query spanning the corpus sees is a latency spike, not a stall. No
setting caps how long one rewrite takes. The scheduled sweep rewrites at most
`excalibur.shard.compaction-max-concurrent` segments per pass, one after another on a single
thread; compaction requested through `POST /v1/compact` is **not** subject to that limit and
walks every live segment. A node whose tail latency matters more than its disk usage should
raise `excalibur.shard.compaction-tombstone-ratio` so fewer segments qualify, and run
`POST /v1/compact` at a chosen hour rather than during traffic.

The slot table that reaches those entries carries a checksum of its own, because an intact,
checksummed entry is worth nothing if the pointer to it is wrong. A flipped bit in a slot's key
hash, data offset or occupancy flag is reported rather than turning a present entry into a
missing one — which an unchecked slot would do silently, since a lookup that probes past a damaged
slot simply never reaches the entry whose CRC would have caught it. A slot's probe-distance byte
is deliberately outside that checksum: Robin Hood displacement rewrites the distance of a slot
whose entry is otherwise untouched, so covering it would invalidate a slot on a write to an
unrelated key. It is not left unguarded, though — the entry record keeps its own copy of the
distance, and the two are written together, so a disagreement between them is damage to one of
them. That comparison costs two reads a point lookup does not otherwise make, so it runs where
the record is being read anyway: a compaction, a split, and `excalibur-fsck`. A flipped distance
byte can still cost a single lookup before one of those finds it.

Segment files written before this existed are read at their own format version and left there.
A checksum written now would vouch for bytes nothing has ever vouched for, so nothing is upgraded
in place: their entries are still verified where the format allows it, their slot tables are
reported as unverifiable rather than as sound, and a file gains protection the first time a
compaction or a split rewrites it. `excalibur-fsck` finds a damaged slot in an older file anyway,
by comparing each slot against the record it names — the record keeps its own copy of the key
hash, under the record's own checksum.

The same standard covers the index files beside the segments: the HNSW graph, its
node-to-chunk map, the PQ codebook and the LSH tables each carry a CRC32C over their payload
plus one over their header, verified when they are opened. A corrupt one is refused by name
rather than loaded, and — the failure that mattered most — a graph file whose magic number had
been zeroed is not mistaken for "never initialised" and silently rebuilt as empty. A file
a writer still holds reports as *unsealed*, which is deliberately not the same statement as
*intact*.

The membership filter in front of the lexical index is the exception, and deliberately so. It is
rebuilt in memory from the terms column every time a segment is opened, so no image of it is ever
written and `excalibur-fsck` has nothing to look for. That is a stronger guarantee than a checksum
would be, not a weaker one: a filter derived from the dictionary it guards on every open cannot
have drifted from it, whereas a persisted copy can be sound, verified, and still describe an
older dictionary.

The BM25 index is covered the same way. A lexical segment's `index.meta` carries a length and a
CRC32C for each of its columns, plus one over itself, and a segment is verified before any column
is mapped — the digests are taken after the columns are forced, so they describe what the device
accepted rather than what the page cache was handed. A damaged segment is quarantined: left on
disk, kept out of every query, and reported — its chunks count as `unsearchableChunks` rather than
`liveChunks` on `/v1/stats`, and the segment is named under `lexical.unreadable` — rather than
allowed to look like a corpus that has quietly shrunk. It is not left that way. A compaction reads
nothing from a segment's postings: it rebuilds a segment from its key table and the chunk text in
storage, so while the key table beside a damaged segment is intact the next compaction rebuilds
it, and the sweep that enforces `lexical-max-latency-seconds` asks for that compaction on a node
that is not ingesting. A segment whose key table is damaged too is reported as not rebuildable,
and only reindexing its documents brings them back. It stays on disk only while no erasure is owed:
its names are the only record of what it holds, so no erasure can be shown to have reached it, and
recording one — or opening with one owed — removes the segment, logs it at ERROR and counts its
chunks in `lexical.unreadableChunksDropped`, rather than leaving every erasure owed for as long as
the damaged files remain. A lexical segment is written once and never
edited — erasure and compaction both produce a new segment — so there is no unsealed state for
this format.

Across all of these, an index written before its format carried checksums is read at its own
version, left byte-for-byte alone, and reported *legacy, unprotected*. There is no migration step
and nothing to undo; a file becomes protected the first time something rewrites it. For the lexical
index that happens without being asked: compaction treats a segment in an older format as owing a
rewrite, and upgrades a few of them on each pass until none are left.

Each segment's graph, `segment_<N>.hnsw`, is accompanied by `segment_<N>.hnsw.docmap`, which
names every graph node by the chunk it embeds — `source:document:chunk:N`, the key the chunk's
text is stored under and the name the lexical index gives it. That is what a vector hit is
returned as, so the two signals of a hybrid answer name one chunk alike and fuse on it, and
`include_text` reads the chunk that matched. It is also what lets a vector be retired precisely:
the integration worker replays each segment's write log in order, and a chunk written again
supersedes the node of its previous version while a deleted chunk loses its node, so a document
posted again or erased leaves nothing of its earlier versions rankable. A document that shrinks has
the vectors of the chunks it no longer has retired as it is replaced, before those chunks are
tombstoned. The mapping is format version 3; the document-keyed versions older builds wrote are
re-keyed by the node itself — see
[Upgrading a data directory](USAGE.md#upgrading-a-data-directory-written-by-an-older-build).

The segment catalog beside them records which segment files this store owns and the lifecycle
state, integrated offset, source and epochs of each. Its header is written to two slots in turn so
a torn write always leaves one intact, and every record carries its own CRC, so a damaged row costs
itself and nothing behind it. A catalog with no usable header and no record that still checksums is
refused at startup rather than replaced with an empty one: the segment files would survive that and
be rediscovered, but the state describing them would be gone and the node would come up looking
exactly like a first start. A file of zeroes is the one exception, being a create that never
reached its first header.

### Chunk index layout

Under `<data dir>/chunk-index` when the native engine is configured:

| Path | Contents |
| --- | --- |
| `manifest` | The segments the store consists of, one per line, with the epoch each became visible at and how many of its documents are tombstoned. A segment exists when this file names it and not before, so publication is one atomic rename |
| `erasure.log` | Obligations recorded but not yet applied to disk, each with the time it was promised. Forced to disk before an erase call returns |
| `seg-N/` | One segment's postings: `index.meta`, `terms.dict`, `terms.hash`, `post.off`, `post.blk`, `post.dat`, `docs.len`, all memory-mapped and read through the page cache |
| `seg-N.keys` | The caller's name for each document, UTF-8, one per line — for ingested text, the chunk id `source:document:chunk:N`, the same name a vector hit on that chunk carries. Text on purpose: this is what an operator opens to see what a segment holds |
| `seg-N.keyidx` | Byte offsets into `seg-N.keys`, so a name is a mapped read rather than heap |
| `seg-N.keymap` | An open-addressed table from the hash of a name to its document, so erasing or republishing a name is a couple of probes rather than a pass over every name in the store. Derived data: rebuilt on open when it is missing or the wrong size |
| `seg-N.tomb` | A bit per document, set when it has been erased and not yet rewritten out |

Anything on disk the manifest does not name is deleted when the store is opened: a segment is
written before it is published, so a crash in between leaves files no reader will ever consult.

Beside it, `<data dir>/document-registry` records which documents this node holds, the chunk ids
each was written under, and the `ETag` and `Last-Modified` an HTTP source reported for it. It is
what change detection compares a re-crawl against and what an erasure
request resolves a document name through, so it is written after every ingestion, reindex and
erasure. A node that lost it would read every document as new — reingesting the corpus beside the
chunks already there and tombstoning none of them — and would report every erasure request as naming
a document it never held. A registry that exists and cannot be read fails the start rather than
being treated as an empty one, and so does one whose record count disagrees with the records it
carries — a registry cut short by a crash is a file of whole records that omits documents, and
loading it would strand their chunks where nothing can name them.

`<data dir>/document-registry.in-flight` is the short log beside it naming documents whose chunks
were being written when the process stopped. A document is marked there before its first chunk is
written and released when it is published or rolled back, so a crash in between leaves the mark
behind and the restarted node keeps those chunks hidden from queries instead of serving half a
document. It is deleted whenever nothing is in flight, which is the steady state.

`<data dir>/source-catalog` holds the other half of what a restart has to carry: the registration
requests behind every crawl this node was asked to keep running. It records the request rather than
the crawler built from it, so a restored source goes back through the same allow-lists a fresh
`POST /v1/ingest` would meet — re-checking the target on the way back in is the point, and recording
an already-approved result would defeat it. A source whose target is no longer permitted is kept on
the catalog and left uncrawled, with a warning naming it, so that narrowing the allow-list pauses a
crawl rather than silently cancelling it. Like the document registry, a catalog that exists and
cannot be read fails the start.

Each registration is forced to disk and then published by an atomic rename, and the file ends with
a `\#end|<count>` marker. Both are there for the same reason: without the force, a crash can leave
a correctly-named catalog whose contents never reached the platter, and without the marker a
catalog cut short is a file of whole, well-formed records — indistinguishable from a catalog those
sources were never registered in, so the node would come back silently having forgotten them. A
catalog written by a build older than the marker fails the start; see
[Upgrading a data directory](USAGE.md#upgrading-a-data-directory-written-by-an-older-build).

`<data dir>/source-status` records how each registered source's last run ended — its state, its
progress and counts, when it finished, and its `last_error` — so `GET /v1/ingest/status` after a
restart reports that run rather than zeros stamped with the moment the node came up. Only finished
runs are written (`idle` or `unconfigured`); a crawl the restart cut short comes back `idle` with
`last_error` saying that the run in progress when the node stopped did not finish, never still
claiming to run. The file is written the way the catalog is — a `\#source-status|1` header, one
line per source, a `\#end|<count>` marker, forced to disk and published by an atomic rename — but
unlike the catalog, one that cannot be read does not fail the start. It is a report, not state the
store depends on: the node logs it, treats the history as empty, and each source reports from its
next run. A data directory from a build without the file needs nothing; it is written when the
first run ends.

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
├── excalibur-backup/       # Offline snapshot, verify and restore command
├── excalibur-core/         # Primitives: memory, hash, checksums
├── excalibur-config/       # Configuration loading
├── excalibur-filter/       # Cuckoo filters, LSH, sketches
├── excalibur-fsck/         # Offline integrity check and repair command
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
├── excalibur-test-bootstrap/  # Foreign-memory warm-up for every test JVM
├── excalibur-vector/       # HNSW, distance, quantization
└── excalibur-bench/        # JMH benchmarks
```

### Running Benchmarks

There is no `jmh` Gradle task — the JMH plugin is not applied. Each benchmark is launched through
a `JavaExec` task in the `benchmark` group, because they need different heaps and JVM flags and a
single task cannot carry all of them (`PointLookupBenchmark` wants transparent huge pages, the
memory-mapped Cuckoo run wants 4G). What they share — `--enable-preview`,
`--enable-native-access=ALL-UNNAMED` and `--add-modules=jdk.incubator.vector` — is stated once
for the tasks and once for the JMH forks the runners start (`ForkedJvm`), so a runner that selects
by pattern cannot fork a JVM that is missing the Vector API its benchmarks call.

Targets, which of them the capture gates, and the measured results of the latest full run are
recorded in [`excalibur-bench/BENCHMARK_TARGETS.md`](excalibur-bench/BENCHMARK_TARGETS.md).

```bash
# See them all
./gradlew :excalibur-bench:tasks --group benchmark

# One benchmark
./gradlew :excalibur-bench:runMappedFileBenchmark

# Everything, captured to JSON, Markdown, CSV and text
./gradlew :excalibur-bench:runBenchmarksWithCapture
./gradlew :excalibur-bench:runBenchmarksWithCapture -PbenchmarkPattern=Cuckoo -PoutputDir=build/bench
```

The captured reports account for every benchmark × `@Param` combination the pattern selects.
JMH's own results omit a combination whose `@Setup` or an iteration threw, or whose forked JVM
died, so the runners watch the run and report each such combination as **errored** — with its
exception and stack trace, an `Errored` count in every format, and an `errored` array in the
JSON. An errored combination fails the capture (exit `1`) even when everything that did run met
its target, and a standard run without `--capture` exits `1` on one too.

| Task | Benchmark | What it measures |
|------|-----------|------------------|
| `runBenchmark` | all benchmarks by default | Takes one optional argument to narrow the pattern — see the note below the table |
| `runBenchmarkComparison` | all benchmarks by default | Records a baseline, or compares a run or a recorded result against one — see below the table |
| `runCuckooFilterBenchmark` | `CuckooFilterBenchmark` | Filter lookup throughput across load factors |
| `runCuckooFilterDeleteBenchmark` | `CuckooFilterDeleteBenchmark` | Deletion throughput across load factors |
| `runCuckooFilterFPRateBenchmark` | `CuckooFilterFPRateBenchmark` | Measured false positive rate (target ≤ 0.1%) |
| `runCuckooFilterMemoryMappedBenchmark` | `CuckooFilterMemoryMappedBenchmark` | Memory-mapped filter read/write throughput |
| `runDistanceBenchmark` | `DistanceBenchmark` | Cosine, L2 and inner product |
| `runDistanceVectorApiBenchmark` | `DistanceVectorApiBenchmark` | Scalar against the SIMD Vector API |
| `runEndToEndQueryBenchmark` | `EndToEndQueryBenchmark` | Full query pipeline p50/p99 |
| `runHashBenchmark` | `HashBenchmark` | XxHash64 throughput |
| `runHnswRecallBenchmark` | `HnswRecallBenchmark` | HNSW recall@10 (target ≥ 99%) |
| `runMappedFileBenchmark` | `MappedFileBenchmark` | Memory-mapped I/O throughput |
| `runPointLookupBenchmark` | `PointLookupBenchmark` | Point lookup throughput (target ≥ 10M ops/sec) |
| `runTailScanBenchmark` | `TailScanBenchmark` | Brute-force scan throughput at varying tail sizes |

To compare two builds rather than measure one, `runBenchmarkComparison` runs
`BenchmarkComparisonRunner`, which records a baseline, reruns against it, or diffs two recorded
runs, and exits non-zero when anything regressed or errored — so a script can gate on it:

```bash
# Record a baseline, on the build you trust
./gradlew :excalibur-bench:runBenchmarkComparison \
  --args='--run --output build/benchmark-results/baseline'

# Later, on the candidate: run again and compare against it
./gradlew :excalibur-bench:runBenchmarkComparison \
  --args='--compare --baseline build/benchmark-results/baseline/benchmark_results.json'

# Or compare two runs already on disk, without running anything
./gradlew :excalibur-bench:runBenchmarkComparison \
  --args='--diff old/benchmark_results.json new/benchmark_results.json'
```

`--pattern` narrows which benchmarks run (default `.*Benchmark.*`). Relative paths resolve against
`excalibur-bench/`, the task's working directory, and results land under
`excalibur-bench/build/benchmark-results` unless `--output` says otherwise. With no arguments it
prints its usage and exits `2`. Exit codes: `0` everything ran and nothing regressed, `1` a
regression, `2` the command could not be carried out (a wrong command line, a missing file, or a
run that measured nothing), `3` a combination errored and so was never measured or compared.
`--run` still writes its reports when a combination errors, so the failure can be read there, and
`--diff` reads a recorded run's errored combinations back and fails on them just the same.
Results are matched to their baseline by benchmark, mode and `@Param` values, so each parameter
combination is compared with its own baseline rather than with a sibling's.

`CuckooFilterInsertBenchmark` has no task of its own; reach it with
`./gradlew :excalibur-bench:runBenchmark --args='cuckoo-insert'` — the runner matches one
argument against `segment`, `cuckoo` and `cuckoo`+`insert`, so the two words have to arrive as one
token.

A selector the runner does not recognise is refused with exit `2`, rather than falling through to
running every benchmark in the module — which, for a typo, is hours of machine time reported as a
successful run. The comparison and capture runners refuse an unknown or valueless option the same
way, so a CI gate reading their exit code cannot mistake "nothing was compared" for "nothing
regressed".

The same targets are also checked by tests tagged `slow` and `benchmark`, which the root build
excludes from `test` because they take minutes. They have their own task, in every module rather
than in one, and it is the only thing that runs them:

```bash
# every module's gates
./gradlew benchmarkTest

# or one module's
./gradlew :excalibur-bench:benchmarkTest
```

The gates are not all in `excalibur-bench`. A Test task only ever runs its own project's classes,
so while the task existed there alone, the tags outside it ran nowhere: the Cuckoo filter's false
positive rate over a million items, and HNSW recall@10 against brute force, are assertions about
this system's stated guarantees that no task in the build could execute.

HNSW recall is measured on the graph alone, which says nothing about the stages in front of it. So
`excalibur-query` gates the answer a caller actually gets: `QueryPipelineRecallGateTest` runs the
whole query pipeline over 20,000 clustered 64-dimensional vectors spread across eight segments,
each with a fully integrated graph, an LSH index of the default shape and a trusted term filter
level, and asserts that its recall@10 against brute force is at least 0.95 and no more than 0.01
below that of the same graphs searched directly and merged. It measures twice: with vector-only
queries, and with every query also carrying text that only one segment's documents share while its
nearest vectors lie in all eight, which is how `POST /v1/query` runs every query. The 0.01 absorbs
ties at the tenth distance and nothing else: the pipeline searches every graph at least as widely,
the term filter only orders the segments, and its LSH candidates can displace a result only with a
closer one. `QueryPipelineVectorRecallTest` makes both comparisons on a smaller corpus in the
ordinary `test` task. The aggregate task is
derived from the module list rather than written out, because a list goes stale the moment a
module is added and a gate run that skips a module reports a pass over assertions it never ran.

Those tests report each measurement against its target and assert a regression floor rather than
the target itself. A rate timed inside an ordinary JVM is only partly a property of the code: on
the development machine the same binary over the same workload measures between 7.9 and 10.9 M
point lookups per second across repeated runs, tracking the host's power and scheduling state and
surviving a fixed core pinning. The 10 M target sits inside that spread, so asserting it reported
a regression on about half of all runs. The floor is a quarter below the slowest reading the host
produces — wide enough that the machine cannot breach it, narrow enough that a lost fast path
cannot survive it. Judging target compliance is the JMH path's job, because JMH forks a fresh
JVM, warms it and repeats the trial.

### Test Coverage

The target is 80% per module, and it is enforced rather than aspirational: JaCoCo verification is
wired into `check`, so `./gradlew build` fails a module that drops below its floor.

Every module meets it. The per-module exception map in `build.gradle.kts` is empty, so there is
one number and no module is held to less than the rest — a floor left where coverage used to be
is what lets it fall back there unnoticed, which is the one thing a floor exists to prevent.

`excalibur-bench` is the permanent outlier. Most of it is JMH state classes, `@Benchmark` methods
and runner `main`s, which exist to be executed by a benchmark run lasting minutes to hours — not by
`check`. Its validators, report writers and result parsing are ordinary code and are tested; the
harness around them deliberately is not, for the same reason JMH's generated classes are excluded
from the reports altogether. Counting machine-written code measures code generation rather than
testing, and including it drags the aggregate from the high seventies to 35%.

```bash
# Per-module reports for every module, plus the aggregate across them
./gradlew coverage

# Per-method gap analysis
./gradlew coverageGaps
```

Reports are generated at:
- `build/reports/jacoco/COVERAGE_SUMMARY.html` - Summary report
- `build/reports/jacoco/aggregate/html/index.html` - Detailed aggregate report

### Flaky tests

A test that fails once in five runs is the hardest kind to catch, because the run that shows it is
usually the one nobody is watching. `analyze-flaky-tests.sh`, at the repository root, runs one
module's suite several times over and reports every test that passed in some runs and failed in
others, with a flakiness score, and every test that failed in all of them:

```bash
./analyze-flaky-tests.sh                           # 5 runs of excalibur-integration
./analyze-flaky-tests.sh 10                        # 10 runs
./analyze-flaky-tests.sh -m excalibur-query -r 3   # 3 runs of another module
./analyze-flaky-tests.sh -y -v                     # no confirmation prompt, Gradle output shown
./analyze-flaky-tests.sh --help
```

Three runs is the minimum — fewer cannot tell a pattern from an accident. Each run is a full
`--rerun` of the module's `test` task, so budget minutes per run; the script asks before starting
unless given `-y` or run without a terminal. The analysis is `FlakyTestAnalyzer` in
`excalibur-test`, which reads the JUnit XML reports Gradle writes for each run rather than scraping
its console. Exit codes: `0` every test passed in every run, `1` flaky or consistently failing
tests were found, `2` the command line was wrong or the analysis could not be carried out.

### Integration Tests

Integration tests verify the core system requirements. They run as part of `./gradlew test` —
anything tagged `slow` or `benchmark` is excluded there and has to be asked for by name:

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
- `TailScanHandoffIntegrationTest` - Chunks written by the real pipeline are answerable from the write-ahead log before the graph absorbs them
- `ConcurrentDeletionIntegrationTest` - Cuckoo filter deletes from several threads, alone and interleaved with inserts, leave exactly the expected set
- `DeletionLifecycleIntegrationTest` - Insert, query, delete and re-query across flat and hierarchical filters, then again at stress volume
- `HierarchicalDeletionPropagationIntegrationTest` - A delete reaches the corpus and segment levels of the hierarchical filter, including under concurrency
- `DocumentErasureIntegrationTest` - An erased document leaves storage, the lexical index's files and vector search, through the node's own erasure wiring
- `GraphRouteIntegrationTest` - A document ingested from a directory, with extraction against a stand-in OpenAI-compatible chat endpoint, is built into the graph and answered from over HTTP as of a date after its fact began, and declined as of one before
- `LshHnswEntryIdContractTest` - The LSH pre-filter returns ids in the HNSW graph's id space, so the candidates stage 5 measures are the nodes the buckets describe
- `SegmentPrefilterLifecycleTest` - A segment's vector pre-filter lives and dies with the graph it is keyed against, including across a split
- `AtomicDocumentWriterTest` - The write-log driver the other suites ingest through records true offsets and keeps each document's chunks contiguous
- `VectorApiResolutionTest` - The test JVM resolves `jdk.incubator.vector`, so the SIMD kernels are what the suites exercise rather than the scalar fallback

```bash
./gradlew :excalibur-integration:test
```

The embedded example in [USAGE.md](USAGE.md#programmatic-usage-jvm-embedded) runs in a source set
of its own, `embeddedExample`. Its only dependencies are the seven coordinates USAGE.md lists, so
it proves that list is enough. `check` runs it:

- `EmbeddedUsageExampleTest` - The example compiles against those seven alone, ingests through a stand-in embeddings endpoint, and answers
- `EmbeddedUsageDocumentationTest` - The code block in USAGE.md matches that test's method and helper token for token, and its imports are exactly the ones the example uses

```bash
./gradlew :excalibur-integration:embeddedExampleTest
```

`RepositoryCompletenessTest` is the odd one out: it asks git rather than the filesystem. Every
other test here runs against the working tree, which holds whatever the last edit left behind
whether or not it was ever committed, and that makes the whole suite blind to a file the build
reads that nobody added. It has happened twice — a `.gitignore` line that matched the Java
package `com.eonmux.excalibur.retrieval.build`, and three modules whose build scripts were never tracked.

### Continuous integration

`.github/workflows/build.yml` runs on every push and pull request, on a Temurin 21 runner, and
runs the commands this README documents rather than a private variant of them. It has three jobs.

**Build.** `./gradlew build` — assemble, the whole test suite, and the per-module coverage floors
`check` enforces. It then builds the three artefacts the README names
(`:excalibur-api:shadowJar`, `:excalibur-fsck:installDist`, `:excalibur-backup:installDist`),
because `installDist` is not part of `build` and a module with no build script would leave `build`
green while those resolved to no such task. Then it smoke-tests the operator tools against a data
directory a real node wrote: it starts the shadow jar on its packaged default (loopback, no
token, no LLM key, which still ingests lexically), indexes two documents through the synchronous
`POST /v1/documents`, and stops it with `SIGTERM` the way a scheduler would. `excalibur-fsck` must
find nothing damaged in what it left; `excalibur-backup` must snapshot it, `verify --deep` the
snapshot with nothing but the report on stdout, and `restore --deep` it into a directory
`excalibur-fsck` passes too. That runs every format checker against the formats a node writes,
through the installed start scripts and the JVM flags they carry. It finishes with
`./gradlew publishToMavenLocal`, the only thing that asks the library modules for a POM Maven can
read.

**Gates.** `./gradlew benchmarkTest`, the task `test` leaves out: the Cuckoo filter's false
positive rate over a million items, HNSW recall@10 against brute force, the query pipeline's
end-to-end recall@10 against brute force and against its own graphs, and the point-lookup
regression floor. It is a job of its own because it takes minutes, and a timing gate going red
should not make the build look as though the code failed to compile or pass. Running it on a
shared runner is sound because of what those tests assert: the recall and false-positive gates are
counts, not timings, and the throughput gates assert the regression floor described under
[Running Benchmarks](#running-benchmarks) — a bound set well below anything a working fast path
measures — rather than the target. Whether a build meets the throughput *targets* is still a JMH
question, answered on a machine whose clock you trust.

**Image.** Builds the container image from the repository's `Dockerfile`, as `docker build .`
does, starts it with the bind address, plaintext opt-in and token the image needs to come up,
waits for `/health/ready`, and stops it the way a scheduler would. An image that builds and never
reports ready fails here. Then it runs the two container commands [In a
container](#in-a-container) documents against the stopped node's volume: an `excalibur-fsck
--repair` into a mounted quarantine volume, after planting a staged file for it to set aside — it
must arrive in the quarantine and be gone from the data directory, which must then check clean —
and an `excalibur-backup snapshot` into a backup volume, verified `--deep` from a second container.

## FAQ

Every answer here was checked against a running node, not against the source alone.

### Setup and startup

**Does it really need Java 21 exactly?**
Yes. The modules compile with `--enable-preview` for the Panama FFM API, and a preview class file
carries a marker that makes any JDK other than the one that produced it refuse to load it. Java 22
will not run this jar. The Gradle toolchain pins 21 for the build; you have to pin it for the
runtime too.

**The process exited with status 1 and printed nothing. What happened?**
It should not. The root log appender is asynchronous, and without a shutdown hook `System.exit()`
tears the JVM down while the fatal error is still queued — a bad `EXCALIBUR_PORT`, for instance,
would kill the process with nothing printed. The shipped `logback.xml` declares a `<shutdownHook/>`,
which drains the queue first. If you replace that file, keep the hook.

**Why won't it start on `0.0.0.0`?**

```
Refusing to bind 0.0.0.0 without authentication. Set excalibur.api.auth-token
(environment: EXCALIBUR_API_AUTH_TOKEN) before binding a non-loopback address...
```

A non-loopback bind needs a token *and* TLS. The two are one decision: a bearer token sent in
cleartext is a credential on the wire in every request. If TLS is genuinely terminated in front of
this process, say so explicitly with `excalibur.api.allow-plaintext=true`.

**Do I need an auth token to run locally?**
No, and that is why the Quick Start has no `Authorization` header. Authentication is off until
`EXCALIBUR_API_AUTH_TOKEN` is set, and the startup log warns for as long as it is. That default is
only safe because it is paired with the bind policy above: without a token the server will not
listen anywhere but loopback.

**Where does it put its data?**
`./data` relative to the working directory, created if absent. `EXCALIBUR_DATA_DIR` moves it. The
graph stores live under that directory too — one per namespace, at `<data dir>/<namespace>/graph` —
unless `excalibur.graph.path` names another root.

### Ingestion

**`POST /v1/ingest` returned 403 and my path is definitely correct.**
The path is not the problem. `excalibur.ingest.allowed-roots` is **empty by default, and empty
means disabled, not unrestricted** — so the first request always fails until you list a root. The
same is true of `excalibur.ingest.allowed-hosts` for URL sources. A path is canonicalised before
it is compared, so symlinks and `..` cannot escape the list.

**Do I need an embedding model at all?**
No. Documents are chunked, stored and lexically indexed without one, and `POST /v1/query`
answers from BM25 with `"retrieval": "lexical"`. An embedding endpoint adds dense retrieval as a
second signal, fused with the first rather than replacing it; it is not what makes the corpus
searchable. This matters most for corpora of
identifiers, titles, DOIs and error codes, where exact terms are what BM25 answers well and what a
dense vector blurs — and where embedding the whole corpus is the most expensive option available.

**How do I get an erased chunk actually off the disk?**
Erasing hides it immediately and records the obligation durably; a compaction rewrites it out.
That compaction is checked at the end of an ingestion run, at the end of a reindex, when the next
erasure arrives, and — on a node doing none of those — by a sweep running every tenth of
`excalibur.ingest.lexical-max-latency-seconds`. Once the oldest obligation is older than that
bound (an hour by default) the compaction takes every segment holding a tombstone, whatever the
volume, so an idle node reclaims on time rather than waiting for something to ingest. The sweep
costs one integer read while nothing is owed. Watch `lexical.oldestErasureAgeMs` and
`lexical.compactionDue` on `/v1/stats`. Until the compaction runs the chunk is unreachable through
every API but its bytes are still in the index files — which is the honest description of a
tombstone, and the reason the two halves are named separately.

**I switched `excalibur.ingest.lexical-engine` and now nothing is found.**
The two engines write to different directories (`chunk-index` and `lucene-chunks`) and neither reads
the other's, so switching starts from an empty index. Reingest the sources. Nothing is lost by
switching — the chunks themselves live in the storage engine, not in either index — but nothing is
carried across either.

**Why is there a lexical index at all if the chunks are already in storage?**
Storage answers "what is chunk X", by key. It cannot answer "which chunks contain these words". The
index is the inverted mapping and holds no text of its own, so the corpus is stored once and indexed
once rather than twice.

**Does re-crawling a source index everything twice?**
No. A chunk identifier names one chunk, so publishing it again retires the copy the store held under
that name, on either engine. A re-crawl of unchanged documents leaves the index the size it was; one
of changed documents replaces their chunks and stops matching the text they used to hold.

**How do I remove one document from the corpus?**
`POST /v1/erasure` with its `source_id` and `document_id`. It removes the document's chunks from
storage, erases them from the lexical index and drops its registry entry, and the reply counts each
of those separately rather than reporting the request as accepted. Add `"compact": true` when you
have to be able to say the data is gone: without it the chunks are hidden immediately and reclaimed
from disk when the compaction policy next fires. On a node that extracts a knowledge graph, the
same request also withdraws the facts extracted from the document, and the entity text copied out
of it, from the namespace they were staged into; the `graph_*` fields report that half, and the
reply is a `202` rather than a `200` until it is done.

**How do I remove everything one source contributed?**
`POST /v1/erasure` with `{"source_id": "...", "scope": "source"}` — no document ids, which the node
looks up itself. It also deregisters the source first — stopping a re-crawl already running and
waiting for it to end — so neither the scheduler nor a run in flight can put back what you just
erased. Each request erases up to a thousand documents and reports `documents_remaining`; repeat
until that is zero — and watch `documents_incomplete` alongside it, because a document the node
could not fully erase is answered with a `500` and stays in the registry, so a loop keyed only on
`documents_remaining` will never finish. The documents may still exist in the source, and nothing
here can change that.

**How do I tell whether an erasure has actually reached disk?**
`GET /v1/stats` carries a `lexical` block. `erasureRequests` is what has been promised and not yet
discharged, and `oldestErasureAgeMs` is how long the oldest of them has waited — compare it against
`excalibur.ingest.lexical-max-latency-seconds`. Both fall to zero once a compaction has rewritten the
segments holding them. `erasedChunks` counts what is hidden from queries but still on disk, which
includes replaced versions as well as erasures.

**`/v1/ingest/status` says `unconfigured`.**
That state means the ingestion pipeline itself failed to wire, not that an API key is missing.
A node with no key reports `idle` and indexes normally.

**Why are the two `/v1/ingest/status` responses shaped differently?**
`?source_id=X` returns a bare status object, and 404 if that source was never registered. Without
the parameter you get every source wrapped in a `sources` array. A caller that asked for one source
should not have to unwrap a collection to find it.

### Querying

**Every query returns 503.**

```
Server is not configured for text queries: this node has neither an embedding client
nor a lexical index, so there is no index to search...
```

A missing API key alone does not cause this. Without an embedding endpoint the node still chunks,
stores and lexically indexes what it ingests, and `/v1/query` answers from BM25 with
`"retrieval": "lexical"` and `"degraded": true`. The 503 means both paths are gone: no embedding
client *and* no chunk lexical index, which is a failure to open the index rather than a missing
key — the startup log names the reason. This is a property of the deployment, not of the request:
the check runs before the body is even read, so a malformed body will not shadow it with a 400.

**Can I send my own vectors?**
No, and this is deliberate. A vector only means something in one model's embedding space. If a
client embedded with a different model than ingestion used, similarity scores would still look
plausible and be meaningless. Owning both sides of the embedding is what makes that impossible.
Point `excalibur.llm.base-url` at any OpenAI-compatible endpoint — Ollama, vLLM, TGI — if you want
a different model.

**Which embedding model should I use, and do I need one?**
You need one for vector retrieval, not to run the server. The server embeds both the documents it
ingests and the query text you send; without an embedding endpoint it falls back to BM25 over the
same chunks, which is a weaker search and a working one. What vector retrieval needs is any
OpenAI-compatible `/v1/embeddings` endpoint — hosted or local.

For a corpus of identifiers, DOIs, error codes and titles, embedding it may be the wrong trade
outright: those are exactly what BM25 answers well and a dense vector blurs. Set
`excalibur.llm.embeddings-enabled=false` with the key still configured and the chat model is spent
on expanding each query rather than on embedding every document — a few dozen generated tokens per
query instead of billions of embedded tokens per corpus.

The default, `text-embedding-3-small` at 1536 dimensions, is a reasonable starting point and needs
no configuration beyond an API key. Four things about this codebase narrow the choice:

- **Chunks are ~512 tokens by default** (`excalibur.ingest.chunk-target-tokens`, with
  `excalibur.ingest.chunk-overlap-tokens` at 64), so at the default a long-context embedding model
  buys nothing and any model with a 512-token window is enough. Raise the target and the window
  has to grow with it.
- **Distance is cosine by default** (`excalibur.query.distance-metric`), under which whether a
  model returns normalised vectors does not matter. `l2` and `inner_product` are there for a model
  whose documentation asks for one of them; a graph records its metric and changing it means
  reindexing.
- **The request sends only `model` and `input`** — no `dimensions` parameter. You get the model's
  native width; you cannot ask `text-embedding-3-large` for shortened 1024-dimension vectors.
- **Ingestion embeds 16 chunks per HTTP request** (`DocumentIngestor.EMBED_BATCH_SIZE`, not
  configurable), so a document of 160 chunks costs ten round trips rather than one or 160.
  Per-call latency still shapes indexing throughput on a document of any size, and a local
  endpoint on the same machine is often faster end-to-end than a hosted one despite the weaker
  model.

Whatever you pick, set **both** width keys to that model's native dimension — `excalibur.query.
vector-dimensions` sizes the index, `excalibur.llm.embeddings-dimensions` is what the client
requires back. They must be equal, and a node whose keys disagree refuses to start rather than
failing later on every document.

**Can I change embedding model on a node that has already indexed something?**
Only by reindexing. Vectors are only comparable within one model's embedding space; the stored ones
are the old model's output and the old width. Change the model and the two width keys together,
clear the data directory or reindex every source, and expect the startup check to stop you if the
widths no longer agree.

**`/v1/stats` reports `cuckoo.saturated` or a growing `cuckoo.saturatedSegments`.**
The membership filter is what tells a query which segments hold its terms, so they are searched
first, and once a level is full it cannot record a new term. A filter holding *some* of a
segment's terms would answer "not here" for the ones it never got, so a level that has refused a
term stops being consulted and its segments are ordered by their term sketches alone. Results are
unaffected; what is lost is the ordering, which matters to queries that run out of time. Raise
`excalibur.filter.corpus-capacity` (or `segment-capacity`) past your term count and restart; the
only cost of over-sizing is memory.

**How do I take a node out of rotation before restarting it?**
`POST /v1/drain`. `/health/ready` then answers 503 for as long as the drain is in force, so a load
balancer stops choosing the node, while the node keeps serving everything it is sent - the traffic
still arriving is the traffic that has not noticed yet. `DELETE /v1/drain` puts it back, and a
restart clears the flag.

**A node answers 200 to everything but its results are worse than its sibling's.**
A circuit breaker has probably opened, and none of them says so in a response. The embedding
breaker drops retrieval to lexical-only; the relevancy breaker bypasses LLM re-ranking and leaves
the answer with the ranking it already had; the query-expansion breaker searches every query as
typed, without the formulations that widen its recall. The one in front of relation
extraction does not touch queries at all: documents keep being indexed and none of them adds to
the graph. On the graph route, the rerank breaker leaves answers in the order BM25 (or the exact
vector rerank) gave them, and the question-embedding breaker skips that vector rerank. The
build-embedding breaker leaves a graph build publishing its entities without vectors, so questions
about them rank without the vector rerank until a later build embeds them. Read
`breakers` on `/v1/stats`, or alert on `excalibur_circuit_breaker_state > 0` from `/metrics` — each
of the seven reports under that one gauge, distinguished by a `breaker` label (`embedding`,
`llm_relevancy`, `query_expansion`, `graph_extraction`, `graph_rerank`, `graph_query_embedding`,
`graph_build_embedding`). The graph three carry no namespace label: every namespace shares them.

**`/health` says `healthy` and `coverage: 1.0000`, but there is no data in the index.**
Correct, if surprising. The coverage on `/health` is the share of this node's live segments that
are in a state a query reads, and an empty segment is read as readily as a full one. It reads
`1.0000` on a node holding nothing, and it does not fall while documents are being ingested — a
segment still absorbing its write log is searched, it just answers from less. So it is not a
cold-start signal: read `entries.total` and `segments.active` from `/v1/stats` for that, and read
`coverage` in a *query* response for how much of the corpus that particular query reached.

**What is `document_id` in a result?**
The identifier ingestion assigned — `sourceId:documentId:chunk:N` — always a JSON string, never a
bare number. The HNSW node id is deliberately not exposed, because compaction renumbers it. If you
are embedding the library rather than calling the API, use `documentIdStr()`, not `documentId()`.

**A query timed out. Why is it a 200?**
Because a partial answer to a bounded question is a result, not a fault. `timed_out` is `true`, the
results gathered so far are returned - fused with BM25 on a hybrid node, or BM25's alone when vector
search produced nothing in time - and `coverage` tells you how much of the corpus they came
from.

### The graph

**Do I have to use it?**
No. Vector search needs nothing from it, and fact extraction is off by default because it is one
chat-model call per ingested document — the most expensive thing ingestion can do.

**`/v1/graph/query` answers 503.**

```
No graph is being served for this namespace; POST /v1/graph/build to build one
from the facts ingestion has staged
```

Nothing has been built yet, or the namespace in the `X-Excalibur-Namespace` header has never been
built at all — one answer covers both, on purpose. It is 503 and not 404 on purpose too: the
capability is there, the data is not, and those need different responses. Note the ordering — turn
on `excalibur.graph.extraction-enabled` *before* ingesting, then build, or there will be nothing
staged to build from.

**`/v1/graph/build` returned `"published": false`.**
Nothing was staged, so the graph you were already serving was left alone. Replacing it with an
empty one would turn every subsequent question into a refusal. The status's `message` says why
nothing was staged, and `extraction` is the first thing to read.

**I set `excalibur.graph.extraction-enabled=true` and nothing is ever staged.**
Extraction is a chat-model call, so it also needs `excalibur.llm.api-key` (or
`EXCALIBUR_LLM_API_KEY`). With the flag and no key the node starts, ingests and searches normally,
and extracts nothing — so it says so everywhere you would look: a WARN at startup naming both
settings, and `"extraction": "missing_api_key"` with a `warning` on the answer to
`POST /v1/graph/build` and on `GET /v1/graph/build/status`. Set the key, restart, and re-ingest:
documents ingested while extraction was inactive were never offered to the extractor, and a build
cannot recover facts nobody extracted. With both set and `"extraction": "unavailable"`, the key is
not the problem: the ingestion pipeline failed to set up at startup, and the WARN logged then says
why.

**How much memory does a build need?**
The edge store is sorted in memory before it is written, so a namespace's build holds every staged
fact of that namespace on the heap at once — 36 bytes per fact in parallel columns (six `int`
fields, the `long` source id, the `short` extractor version and two bytes of source type and
confidence), plus three `int` permutations over all of them while the forward and reverse indexes
are written, for about 48 bytes per fact at the peak. The buffer grows by half again at a time, so
allow up to about 66: roughly 5 to 7 GB of heap for a hundred million facts. One build is capped at
2,147,483,639 facts (2<sup>31</sup> − 9, the largest array a JVM allocates); past that it fails
with a message naming the cap rather than an `OutOfMemoryError`, whatever the heap. The on-disk
format has no such limit. Once the store is written the buffer is released, and the Lucene indexes
are built next from each entity's text — held in memory too, up to 16,000 characters per entity —
so a graph of many heavily-mentioned entities needs room for that as well. Builds are one at a time
per node, so the heap has to cover the largest namespace, not the sum of them.

**Why is erasure a 202 instead of a 200?**
Because accepting is not removing. The obligation is recorded durably before the response is
written, so it survives a restart; a compaction rewrites the edges out when the tombstone ratio or
the latency bound says it is due. Rewriting the whole store per request would make erasure a
denial-of-service. Pass `"compact": true` when you need it done now.

**What makes the latency bound a deadline rather than a hope?**
A sweep, running every tenth of `excalibur.graph.erasure-max-latency-seconds`, that asks every
namespace whether its oldest outstanding obligation is due. Without it the bound would only be
evaluated by the handler of the *next* erasure request, so one request submitted to a node that
then went quiet would wait indefinitely. The sweep costs an integer read per open namespace when
nothing is owed; it closes and reopens the served graph only when a compaction actually runs.

An evicted namespace is swept too, and that is deliberate: an obligation is owed whether or not
anyone is querying the tenant it concerns, so leaving it to be discovered by the next query would
make the deadline conditional on traffic the tenant may never send again. What the sweep reads for
a non-resident namespace is its erasure log alone — a small append-only file, not the memory
mappings of a served store — so asking the question costs almost nothing, and only a namespace
whose oldest obligation has actually passed the bound is reopened. At most four are reopened per
pass, oldest first, so discharging a backlog cannot evict the working set in one sweep; a backlog
longer than one pass is logged at WARN. A log that cannot be read is logged at ERROR and never
treated as nothing outstanding.

### Build and development

**`./gradlew build -x test` — does that work?**
Yes. Coverage verification is wired into `check`, its dependency on the test task is declared, and
verification skips itself when there is no execution data rather than failing everything at 0%.

**Why did coverage verification not fail when I skipped the tests?**
Skipping the tests skips the evidence. Verifying against a missing exec file reports 0% for every
module and fails for a reason that has nothing to do with your code, so `-x test` degrades to "not
verified".

**How do I run the benchmarks? There is no `jmh` task.**
Correct — the JMH Gradle plugin is not applied. Each benchmark has its own `JavaExec` task in the
`benchmark` group, because they need different heaps and JVM flags. Start with
`./gradlew :excalibur-bench:tasks --group benchmark`. The pass/fail gates those benchmarks assert
live in tests tagged `slow` and `benchmark`, which `test` excludes; `./gradlew benchmarkTest` is
the only thing that runs them, and `:<module>:benchmarkTest` runs one module's.

**Can I depend on these modules from Maven?**
Not from Maven Central — nothing is published there. `./gradlew publishToMavenLocal` installs the
sixteen library modules into `~/.m2`. The five that stay out are not libraries: `excalibur-bench`
is a JMH harness whose entry points are benchmark runs, `excalibur-integration` and
`excalibur-test-bootstrap` are test modules with nothing an embedder would depend on, and
`excalibur-fsck` and `excalibur-backup` are commands that run against a stopped data directory —
build them with `installDist` rather than depending on them. A
composite build is the better option while you are also changing ExcaliburDB — see
[USAGE.md](USAGE.md#programmatic-usage-jvm-embedded).

**Is the coverage number in the report the whole project?**
The aggregate is. Per-module floors are enforced by `check`, and JMH's generated harness classes
are excluded everywhere — they are machine-written and counting them measures code generation
rather than testing.

## License

[Apache License 2.0](LICENSE).
