# ExcaliburDB

A disk-backed vector similarity search engine with LLM-based relevancy scoring. ExcaliburDB provides efficient approximate nearest neighbor (ANN) search over large document corpora using memory-mapped files and hierarchical filtering.

## Overview

ExcaliburDB is built on Java 21's Panama Foreign Function & Memory API for zero-copy, memory-mapped file I/O. It handles vector indexes larger than available RAM by keeping data on disk and navigating graphs directly from memory-mapped regions.

### Key Features

- **HNSW (Hierarchical Navigable Small World) graphs** for approximate nearest neighbor search with configurable efConstruction and efSearch parameters
- **Cuckoo filters** for probabilistic set membership testing with deletion support
- **LSH (Locality-Sensitive Hashing)** for coarse vector pre-filtering
- **Epoch-based visibility tracking** for consistent query snapshots during concurrent ingestion
- **LLM integration** for semantic relevancy scoring, with a circuit breaker on each of the chat and embedding paths and a fallback when either is open
- **Queryability guarantee** - the system remains queryable at all times, including during initial indexing and re-indexing operations
- **Memory-mapped CSR graph** alongside the vector index, with a mandatory reverse index, so retrieval can traverse relationships rather than only find similar text. A traversal with no relation filter follows every relation in **both** directions: a question is about an edge, not about the end someone happened to assert it from, so "who did Ada Lovelace collaborate with" and "who did Charles Babbage collaborate with" reach the same fact. A caller wanting one direction asks for it with `RelationSelector.forward` or `reverse`. When the question names a relation the graph holds — "who **collaborated** with Ada Lovelace" against a `collaborated_with` edge — the traversal narrows to it, in both directions, so the model is handed that relation rather than everything the entity touches. The match is by prefix over letters and digits, so the extractor's casing convention does not matter. It stays a hint: if narrowing finds no edge, the traversal is retried across every relation, because the cues are a fixed English vocabulary matched against names an extractor chose and a hint that lands on the wrong relation must not turn an answerable question into a refusal
- **Bitemporal facts** - every edge carries the interval over which it held in the world, kept strictly separate from the epoch that records when the index learned it, and every traversal is filtered by it. `POST /v1/graph/query` takes an `as_of` date, instant or `all`, so a question is asked at an instant the caller states rather than one inferred from its wording. The node's own extractor does not infer dates, so facts it stages are open-ended until something supersedes them; the interval is the store's, and closing one is the graph builder's job
- **Provenance per edge** - source, confidence and extractor version, stored columnar and serialised into the model's context so a curated claim can be told from a guess
- **Staged reranking on the graph route** - BM25 over an embedded Lucene index, then reranked against the entities' uncompressed vectors, then reranked again in one batched call to the chat model; each stage falls back to the ordering beneath it when its endpoint is unavailable. The route runs this one lexical signal through the same fusion machinery `/v1/query` uses, so the reciprocal-rank step preserves the BM25 order rather than combining anything; it is `/v1/query` that actually fuses two signals
- **Abstention rather than a weak answer** - a sealed result type that can answer, decline, or hand back an ambiguity, rather than returning a weak answer as though it were a strong one. The thresholds a running node applies are configuration (`excalibur.graph.min-*`). `AbstentionCalibrator.calibrate` fits all three together instead, from labelled outcomes you supply - together rather than one at a time, because the gates overlap and combining three separate fits gives a node stricter than any of them measured. It is a library API rather than a server route, because the labels have to come from a query set whose answers are known, and a node cannot manufacture one for itself. The fit also reports where the coverage it gave up went, split between queries handed back as ambiguous and queries refused outright, and prefers the former where the two buy the same precision. The confidence attached to an answer is fitted separately and by a different method: `AbstentionCalibrator.fitTemperature` scales the reported confidence so it can be read as a probability, and `expectedCalibrationError` and `reliability` say how far off it was before and after. A fitted temperature goes into `excalibur.graph.confidence-temperature`, which is the whole of what a node needs to apply it; scaling is monotone and fixes the midpoint, so it changes how strongly an answer is claimed and never which answer is given. Which queries to answer and how strongly to claim them fail independently, so neither fit stands in for the other
- **Erasure that removes data** - `POST /v1/graph/erasure` records an obligation durably, and a compaction rewrites the tombstoned edges out of the store and out of the staged facts it was built from, driven by the oldest outstanding request and not only by how much has accumulated. Erasing only the store would leave the next `POST /v1/graph/build` free to replay the staging log and restore exactly what was erased
- **Per-namespace isolation** - one graph directory per tenant, selected by a request header, with mapping-count and descriptor budgets - `vm.max_map_count` and `RLIMIT_NOFILE`, read from the running process - that evict the least recently used graph before either limit is reached; a request holds a lease, so eviction never unmaps a graph out from under a reader. Ingestion routes by the same header: a source registered with it stages its facts into that tenant, one registered without it into `shared`, and the binding is written into the source catalog so a re-crawl after a restart stages where it did before
- **A graph the node builds** - ingestion extracts facts from the documents it publishes and stages them; `POST /v1/graph/build` turns everything staged into the edge store, the dictionaries and the two Lucene indexes, and serves the result without a restart
- **An MCP server** - the ingest, search, build and graph-query calls exposed as tools, so a model can be pointed at a directory and then question what was built from it without any adapter code
- **Retrieval without embedding the corpus** - every ingested chunk is indexed into the engine's own inverted index, and a node with no embedding endpoint answers `POST /v1/query` from BM25 instead of refusing the query
- **Hybrid retrieval on the document route** - a node with an embedding model runs BM25 and vector search concurrently on `POST /v1/query` and fuses their rankings by reciprocal rank, so the inverted index it maintains anyway is read rather than merely paid for; `excalibur.query.hybrid-retrieval=false` restores vector-only ranking
- **Query-side semantic expansion** - a chat model turns one question into the vocabulary a matching document would contain (`out of RAM` becomes `OOM killer`, `ENOMEM`, `SIGKILL`, `cgroup memory limit`) and weights each formulation itself, so the semantic work is paid for once per query instead of once per chunk of the corpus
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
 "entries_ingested": 450, "entries_pending": 170, "last_error": null}
```

`state` runs `crawling` → `embedding` → `indexing` → `idle`. `unconfigured` means this node's
ingestion pipeline failed to wire at startup; `last_error` says where the reason is. Drop
`?source_id=` to get every source instead, wrapped in a `sources` array.

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
java --enable-preview --enable-native-access=ALL-UNNAMED -jar excalibur.jar

# Run with custom configuration file
java --enable-preview --enable-native-access=ALL-UNNAMED -jar excalibur.jar --config /path/to/excalibur.properties

# Set port via environment variable (or excalibur.api.port in the properties file)
EXCALIBUR_PORT=9090 java --enable-preview --enable-native-access=ALL-UNNAMED -jar excalibur.jar

# Put the segments, indexes and graph somewhere other than ./data
EXCALIBUR_DATA_DIR=/var/lib/excalibur java --enable-preview --enable-native-access=ALL-UNNAMED -jar excalibur.jar
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
  -e EXCALIBUR_API_BIND_ADDRESS=0.0.0.0 \
  -e EXCALIBUR_API_AUTH_TOKEN="$TOKEN" \
  -e EXCALIBUR_LLM_API_KEY="$OPENAI_API_KEY" \
  --stop-timeout 40 \
  excalibur
```

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
the page cache turns every search into a disk read.

Nothing is baked in. The API binds `127.0.0.1` by default and binding anything else requires a
bearer token and either TLS or an explicit `EXCALIBUR_API_ALLOW_PLAINTEXT=true` where something in
front of the process terminates it — so the container ships with none of those set, because a
default that listened on every interface unauthenticated is the one mistake that policy exists to
prevent. `HEALTHCHECK` points at `/health/ready`, not `/health`: readiness answers 200 or 503 on
whether this node can serve a query, where liveness answers 200 while it is still opening
segments. Allow at least 30 seconds to stop — the shutdown hook closes the storage engine and the
chunk index, which is what publishes the tail of an ingestion run.

## Operator tools

Two commands ship beside the server. Both run against a data directory rather than a running
node, both run and exit, and neither opens a port.

```bash
./gradlew :excalibur-fsck:installDist :excalibur-backup:installDist
```

### Checking a data directory — `excalibur-fsck`

```bash
excalibur-fsck /var/lib/excalibur              # report only; nothing is touched
excalibur-fsck /var/lib/excalibur --strict     # also fail on anything unverifiable
excalibur-fsck /var/lib/excalibur --repair     # apply only the repairs shown to be safe
excalibur-fsck /var/lib/excalibur --json
excalibur-fsck --help                          # the flags and the exit codes
```

It never opens a file for writing: most are mapped read-only — enforced by the kernel, not by
intention, because several of the engine's own open paths stamp headers or migrate formats on the
way in — and the rest are streamed past a digest without being mapped at all. It reports each
file as one of `INTACT`, `CORRUPT`, `UNREADABLE`, `MISSING`, `UNSEALED`, `LEGACY_UNPROTECTED` or
`UNCHECKED`. Only `INTACT` means bytes were weighed against a stored checksum. The last three
are reported as *not shown to be damaged*, which is deliberately not the same claim as *sound*.
A file can also be `INTACT` and only partly verified — an older segment whose entries carry
checksums but whose slot table does not — which is counted on its own axis rather than being
mislabelled either way. `--strict` exits non-zero on all of these, which is how an operator asks
for proof rather than the absence of doubt. It also checks
across files — a catalog row naming a segment that is not there, an index with no segment, a
staged file a rename never published.

Repair is off by default: without `--repair` the plan is printed and nothing is touched. **No
repair ever deletes anything.** A damaged file is moved into a quarantine directory byte for
byte, which is reversible by hand. What it will not attempt matters as much as what it will: a
damaged `catalog.dat`, a damaged `routing.dir`, a routing slot pointing at a missing segment, or
a write-ahead log damaged in the middle are all reported under *needs an operator* and left
alone. Which hash slot belongs to which segment after a split is not recoverable from file
names — that is why the directory is written down — and the records behind damage in a log were
fsynced and acknowledged, so trimming past them would destroy committed writes.

Exit codes: `0` no damage, `1` damage found, `2` the command line was wrong.

### Snapshots — `excalibur-backup`

```bash
excalibur-backup snapshot --data-dir /var/lib/excalibur --out /backups/2026-09-14
excalibur-backup verify   --snapshot /backups/2026-09-14 --deep
excalibur-backup restore  --snapshot /backups/2026-09-14 --target /var/lib/excalibur
```

A snapshot is a self-describing image: the payload, plus a manifest naming every file with its
length, its CRC32C and its SHA-256, the format versions, and the epoch the image is coherent at.
`verify` checks an image against that manifest in both directions — every named file must be
present and match, and every present file must be named. `--deep` also hands each file to the
engine's own integrity checker for its format, so a snapshot can be shown to hold openable
segments and graphs rather than merely unaltered bytes. `restore` verifies in full first, builds
the new tree beside the target, and renames it on in one move; an image that does not verify is
refused with the target untouched, and a target that already holds something needs `--force`.

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
built edge store, the quantiser codebooks. The HNSW graphs and their document mappings are never
left out, derived though they are, because **nothing rebuilds them**: deleting a `.hnsw` gives an
empty graph attached to a populated segment, not a repopulated one. The write-ahead logs are
never left out either — a segment's data file is forced only on flush, every log append is
fsynced before it is acknowledged, and the graph integration cursor points into the log.

Snapshots preserve sparseness. The engine maps write-ahead logs at 64 MiB and graphs at 755 MiB
apiece, so a node holding 50 MB of data occupies 50 GB apparent; a copy that did not preserve
holes would turn that into a 50 GB backup. Checksums still cover every logical byte.

Exit codes: `0` done, `2` bad command line, `3` the image did not verify and nothing was
restored, `4` the target is not empty, `5` the data directory moved while being copied, `6` a
file could not be read or written.

## Configuration

Configuration is loaded from `excalibur.properties` in the working directory by default, or from
the path given to `--config` (or `--config=<path>`); an argument the server does not recognise,
or a `--config` with no path after it, refuses to start rather than quietly using the defaults,
and `--help` prints the arguments without starting anything.
Copy `excalibur.properties.example` to start; the working copy is
gitignored, because it is the file a key ends up in.

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
| `EXCALIBUR_DATA_DIR` | Where segments, indexes and the graph live. Default `./data`, created if absent |
| `EXCALIBUR_API_AUTH_TOKEN` | Bearer token. Setting it turns authentication on |

### Configuration Options

| Property | Default | Description |
|----------|---------|-------------|
| `excalibur.api.port` | 8080 | Port to listen on; `EXCALIBUR_PORT` overrides it |
| `excalibur.visibility.epoch-tick-ms` | 100 | Visibility epoch tick interval (ms) |
| `excalibur.visibility.query-timeout-ms` | 30000 | Query timeout (ms) |
| `excalibur.query.top-k-default` | 100 | Default number of results |
| `excalibur.query.threshold` | 0.0 | Score floor for requests that omit `threshold`; a request's own `threshold` overrides it. 0 disables filtering |
| `excalibur.query.vector-dimensions` | 1536 | Vector dimensionality |
| `excalibur.query.include-partial-segments` | true | Include indexing segments in queries |
| `excalibur.query.llm-timeout-ms` | 60000 | LLM scoring timeout (ms) |
| `excalibur.query.hybrid-retrieval` | true | Fuse BM25 with vector search on a node that holds both. `false` restores vector-only ranking, and with it a `score` that is a similarity rather than a fused rank sum |
| `excalibur.query.distance-metric` | cosine | Which distance the vector index is built and searched with: `cosine`, `l2` or `inner_product`. The choice belongs to the embedding model. It is written into each graph's header at creation, and a graph built under one metric **refuses to open** under another rather than returning a quietly worse ranking — the edges encode the neighbourhood the old metric saw. Changing it means reindexing |
| `excalibur.query.rrf-k` | 60 | Reciprocal rank fusion's rank offset. Higher flattens the weight curve, so a document ranked tenth by one signal counts nearly as much as one ranked first; lower lets the top of each list dominate |
| `excalibur.query.cache.enabled` | false | Whether a repeated identical query is answered without searching again. Every lookup re-reads a fingerprint of the live corpus and an entry is served only to a lookup presenting the identical one, so an entry is discarded the moment anything it was computed against changes |
| `excalibur.query.cache.max-entries` | 1024 | How many answers the cache may hold. Entries, not bytes: one entry is one answer, whose result list is bounded by `excalibur.api.max-top-k`. 1–1000000, refused rather than clamped |
| `excalibur.query.expand-hybrid-queries` | false | Whether a node that embeds its corpus also expands the BM25 half of a hybrid query with the chat model. Off, the vector signal carries the meaning and BM25 matches the words as typed; on, the lexical half contributes the recall it would on a node with no embeddings, at one extra chat call per query |
| `excalibur.query.per-signal-over-fetch` | 3 | How many times `top_k` each signal contributes before fusion. A document has to be in a signal's list to be fused at all, so this is what lets a result that is mediocre on one signal and excellent on the other survive to be ranked |
| `excalibur.shard.segment-load-factor` | 0.7 | Fraction of slots a segment may fill before it splits |
| `excalibur.shard.default-ef-construction` | 128 | HNSW construction candidate list size |
| `excalibur.shard.default-ef-search` | 64 | HNSW search candidate list size |
| `excalibur.shard.hnsw-integration-queue-depth-limit` | 100000 | Max vectors in HNSW integration queue |
| `excalibur.shard.compaction-delay-ms` | 10000 | Delay before compaction starts (ms) |
| `excalibur.shard.default-max-connections` | 16 | Neighbours an HNSW node keeps above layer 0, the graph's `M`. More means better recall, a larger file and a slower build. Written into a graph's header at creation, so it governs graphs built from here on; 2 to 256 |
| `excalibur.shard.default-max-connections-layer0` | _(twice `M`)_ | Neighbours kept at layer 0, where the walk stops descending and becomes exhaustive. `0` means twice the value above; it may not be narrower than it |
| `excalibur.shard.compaction-interval-ms` | 5000 | How often the segment compactor looks for work |
| `excalibur.shard.segment-sync-interval-ms` | 1000 | How often the index registry is reconciled with the storage engine's directory. The sweep is what notices a segment the engine created without announcing it, so this is the longest a new segment can stay unsearchable. Refused rather than clamped below the sweep's own floor |
| `excalibur.shard.compaction-tombstone-ratio` | 0.2 | Tombstoned fraction of a segment at which a rewrite repays itself |
| `excalibur.shard.compaction-write-log-mb` | 32 | Write-log size that compacts a segment whatever its tombstone ratio — what keeps a segment that is appended to but never deleted from accumulating an unbounded log |
| `excalibur.shard.compaction-max-concurrent` | 4 | Segments the scheduled sweep will rewrite in one pass, so a burst of eligible segments cannot spend the whole machine on rewrites. They are rewritten one after another, not at once, and `POST /v1/compact` is not subject to this. 1 to 16; a value outside that is refused at startup against the property name |
| `excalibur.shard.hnsw-compaction-interval-ms` | 60000 | The same sweep for the vector graph, which reclaims deleted nodes by renumbering rather than by rewriting a log |
| `excalibur.shard.hnsw-compaction-tombstone-ratio` | 0.2 | Deleted fraction of a graph at which its nodes are worth renumbering |
| `excalibur.shard.lsh-hash-tables` | 15 | Hash tables in a segment's LSH index. More finds more of the true neighbours and reads more buckets. At most 256: each table allocates hash-functions × dimensions floats, per segment |
| `excalibur.shard.lsh-hash-functions` | 16 | Hash functions concatenated into one bucket key. More makes each bucket narrower. At most 63, because the key is packed into a long |
| `excalibur.shard.lsh-probes` | 3 | Neighbouring buckets probed beside the query's own. At most 64 |
| `excalibur.shard.volumes` | _(empty)_ | Directories segments are placed across, beyond the data directory. Empty keeps everything in the data directory. Named volumes are spread over by a consistent hash of the segment id, so adding one later moves as few existing segments as possible — and each must already exist as a directory and stay mounted. A volume the node cannot find is refused rather than created, and a restart that cannot see a segment its catalog records refuses to start rather than quietly serving a partial corpus. Two entries that resolve to one directory — a symlink, a relative path, a `..` that walks out and back in — are refused at startup naming both spellings, since the ring would give that directory two shares. Changing the set re-weights the ring for **new** segments only: what is on disk stays where it is, write-ahead logs and split children included |
| `excalibur.shard.pq-search-enabled` | false | Whether a segment's product quantiser answers the first pass of a vector search. On, candidates are scored from their quantised codes and the survivors rescored against their full-precision vectors, so the ranking returned is still exact; what the approximation can cost is a document the first pass never shortlisted |
| `excalibur.shard.pq-oversample` | 4 | Candidates the quantised pass collects per result asked for — what buys back the recall the approximation costs |
| `excalibur.shard.pq-subspaces` | 8 | Parts a vector is split into before each is quantised on its own, one code byte per part. More means better recall and a larger index; high-dimensional embeddings need more of them. 1–128, and must divide `excalibur.query.vector-dimensions` — a split that does not is replaced by one that does, with a warning naming this key |
| `excalibur.shard.pq-centroids` | 256 | Centroids each subspace is clustered to — the alphabet a code is written in. 2–256 |
| `excalibur.shard.pq-training-samples` | 100000 | Vectors the codebook training samples from a segment, or 0 for all of them. Training runs while a segment is warming and unqueryable, so this bounds how long that lasts. Must be at least `pq-centroids` |
| `excalibur.ingest.document-atomicity-timeout-ms` | 2000 | Document atomicity commit timeout (ms) |
| `excalibur.ingest.document-atomicity` | true | Whether a document's chunks become visible together. Turning it off removes the guarantee listed under [Visibility and Consistency](#visibility-and-consistency): a query can then see half a document |
| `excalibur.ingest.reindex-interval-ms` | 3600000 | How often a registered source is re-crawled on its own (1 hour). A node with no embedding endpoint re-crawls too, re-chunking and re-indexing lexically |
| `excalibur.ingest.chunk-target-tokens` | 512 | Tokens one chunk aims for. Larger chunks carry more context into a single result; smaller ones rank more precisely, because a chunk's score is diluted by whatever in it the question did not ask about |
| `excalibur.ingest.chunk-overlap-tokens` | 64 | Tokens a chunk repeats from the one before it, so an answer that straddles a boundary is not split across two chunks holding half of it each. Must be less than the target, or chunking would never advance; the node refuses the pair at startup rather than failing on the first document |
| `excalibur.ingest.bm25-k1` | 1.2 | Where a repeated term stops earning more score. 0 reduces term frequency to a yes-or-no. Under `lexical-engine=native` this is **recorded in the index** and an index built under one value refuses to open under another — not because the bytes depend on it, but because changing it changes every score the corpus can produce, and with it every ranking and every threshold a deployment has calibrated. Changing it means reingesting. Under `lexical-engine=lucene` it is applied at query time and nothing is recorded, so a change takes effect on the next query |
| `excalibur.ingest.bm25-b` | 0.75 | How much a match is discounted for the length of the chunk it was found in. 1 normalises fully by length, 0 not at all. Recorded, or not, on the same terms as `bm25-k1` |
| `excalibur.filter.corpus-capacity` | 10000000 | Entries the membership filter is sized for. **Size this to your corpus.** A filter with no room left cannot record a term, and a level that has refused one stops being trusted to rule anything out: pruning switches off, `/v1/stats` reports `cuckoo.saturated`, and every query scans every segment. Results stay correct; an undersized value costs latency, not answers. The filter allocates about twice this many slots, rounded up to a power of two: a cuckoo filter refuses inserts well before its slots are full, so one sized to exactly its capacity would saturate at the corpus it was configured for |
| `excalibur.filter.segment-capacity` | 10000 | Distinct terms one segment's level of the membership filter is sized for. A segment that outgrows it stops being pruned rather than answering wrongly; `/v1/stats` counts those under `cuckoo.saturatedSegments` |
| `excalibur.filter.fingerprint-bits` | 13 | Bits of each term the filter keeps, 4 to 16. Each extra bit halves the chance two unrelated terms look alike — and so halves the segments a query opens for nothing — at one more bit per slot |
| `excalibur.llm.api-key` | _(none)_ | Key for the OpenAI-compatible endpoint. Supply as `EXCALIBUR_LLM_API_KEY`, not in this file. Without it the corpus is still chunked, stored and term-indexed; only the vector signal is missing |
| `excalibur.llm.base-url` | https://api.openai.com/v1 | API root. The clients append `/v1/embeddings` and `/v1/chat/completions` themselves; a trailing `/v1` is stripped, so `https://api.openai.com` and `https://api.openai.com/v1` mean the same thing |
| `excalibur.llm.chat-model` | gpt-4o-mini | Chat model for relevancy scoring |
| `excalibur.llm.embedding-model` | text-embedding-3-small | Embedding model name |
| `excalibur.llm.embeddings-dimensions` | 1536 | Embedding vector dimensions |
| `excalibur.llm.embeddings-enabled` | true | Whether the key is spent on embedding the corpus. Set `false` to keep the chat model and skip the embedding pass: retrieval is then BM25 over the chunk index with the chat model expanding the query. Inert without a key |
| `excalibur.llm.relevancy-threshold` | 0.3 | Minimum relevancy score (0.0-1.0) |
| `excalibur.llm.timeout-ms` | 60000 | LLM request timeout (ms) |
| `excalibur.llm.max-retries` | 3 | Maximum retry attempts |
| `excalibur.llm.retry-base-delay-ms` | 500 | Base delay for exponential backoff (ms) |
| `excalibur.llm.failure-threshold` | 5 | Consecutive failures before a circuit opens. Chat and embeddings have **one breaker each** — they are usually different endpoints and can fail alone, and one breaker for both would make a dead reranker stop ingestion |
| `excalibur.llm.cooldown-period-ms` | 60000 | How long an open circuit stays open before it half-opens (ms). A half-open circuit admits **one** probe; everything else keeps failing fast until that probe settles the state, which is the stampede the breaker exists to prevent |
| `excalibur.llm.max-prompts-per-query` | 10 | Max LLM prompts per query |
| `excalibur.llm.max-batch-tokens` | 4000 | Max tokens per LLM batch |
| `excalibur.health.readiness-threshold` | 0.5 | `/health` reports `ready`, and `/health/ready` answers 200, at or above this share of live segments being readable |
| `excalibur.health.healthy-threshold` | 0.9 | The same check at a higher bar, reported as `healthy` |
| `excalibur.health.max-latency-ms` | 5000 | A `/health` response slower than this reports `healthy: false` |
| `excalibur.health.min-free-disk-ratio` | 0.05 | `/health/ready` answers 503 with `disk` in `failed` below this share of volume capacity being free — the point past which a compaction's replacement segment can no longer be relied on to fit |
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
| `excalibur.api.read-rate-per-second` | 50 | Sustained read requests per client per second |
| `excalibur.api.read-burst` | 200 | Read burst allowance |
| `excalibur.api.mutate-rate-per-second` | 5 | Sustained mutating requests per client per second |
| `excalibur.api.mutate-burst` | 20 | Mutation burst allowance |
| `excalibur.api.rate-limit-max-clients` | 10000 | Clients tracked before eviction |
| `excalibur.api.trusted-proxies` | _(empty)_ | Addresses and CIDR ranges whose `X-Forwarded-For` is believed, so callers behind a proxy are rate-limited individually instead of sharing the proxy's budget. Empty means only the peer address is ever metered |
| `excalibur.api.max-top-k` | 1000 | Largest `top_k` a query may request |
| `excalibur.api.max-query-timeout-ms` | 60000 | Largest query timeout a request may ask for |
| `excalibur.api.max-query-chars` | 8192 | Longest `query` text a request may send. It is embedded, and on the lexical path expanded, so its size is an outbound call the caller picks |
| `excalibur.api.max-oversampling-factor` | 16 | Largest `oversampling_factor` a request may ask for. It multiplies `top_k` into the HNSW beam width, so it is what decides how much of a segment a search walks |
| `excalibur.api.max-result-text-chars` | 4096 | Longest text returned for one chunk, by `include_text` on a query or by `/v1/documents`. Past it the text is clipped and `text_truncated` says so |
| `excalibur.ingest.allowed-roots` | _(empty)_ | Filesystem roots ingest may read. Empty means filesystem ingest is **disabled** |
| `excalibur.ingest.allowed-hosts` | _(empty)_ | Hosts ingest may crawl. Empty means URL ingest is **disabled** |
| `excalibur.ingest.max-sources` | 256 | Registered ingestion sources before new ones are refused. Bounds the source ids documents may be supplied under too, since a supplied id is one no crawl can be registered under |
| `excalibur.graph.path` | `<data dir>` | Root the per-namespace graphs live under. Namespace `n` serves `<root>/n/graph` and stages in `<root>/n/graph-staging`; a node that never sees the namespace header uses `shared` |
| `excalibur.graph.extraction-enabled` | false | Extract facts from ingested documents with the chat model |
| `excalibur.graph.extractor-version` | 1 | Recorded on every extracted fact, so one extractor's output can be erased by version |
| `excalibur.graph.entity-vectors-enabled` | true | Embed each entity's text at build time, so retrieval can rerank against real vectors |
| `excalibur.graph.single-valued-relations` | *(empty)* | Comma-separated relations that hold one value at a time, whose new values close the previous interval |
| `excalibur.graph.max-open-namespaces` | 16 | Graphs held open at once before the least recently used is evicted |
| `excalibur.graph.hot-namespaces` | *(empty)* | Comma-separated namespaces never evicted, alongside the shared index. Each one spends a slot permanently |
| `excalibur.graph.cold-namespaces` | *(empty)* | Comma-separated namespaces evicted before any other, whatever their recency — for a bulk import or an idle tenant. Naming one in both lists refuses to start |
| `excalibur.graph.erasure-tombstone-ratio` | 0.2 | Tombstoned fraction of the store at which a compaction becomes worthwhile |
| `excalibur.graph.erasure-max-latency-seconds` | 3600 | Longest an erasure obligation waits before a compaction runs regardless. Checked every tenth of it by a sweep over **every namespace on disk**, so the bound holds on an idle node and on an evicted tenant nobody is querying. The sweep reads each non-resident namespace's erasure log — a small append-only file, not a served store — and reopens only the tenants whose oldest obligation has passed the bound, at most four per pass, oldest first; a longer backlog is logged at WARN, because the node is then not meeting its deadline |
| `excalibur.graph.top-k` | 10 | Documents each retrieval signal contributes before fusion |
| `excalibur.graph.rerank-over-fetch` | 4 | Candidates the full-precision reranker reads per result it returns. Reranking can only reorder what it is given, so this is what lets an entity that term overlap ranked poorly be rescued by its vector |
| `excalibur.graph.context-token-budget` | 2000 | Tokens of serialised subgraph an answer may carry |
| `excalibur.graph.min-score` | 0.005 | Smallest fused score a candidate must reach to be answered |
| `excalibur.graph.min-separation` | 0.001 | Smallest gap between the top two candidates before the result is ambiguous |
| `excalibur.graph.min-mean-confidence` | 0.3 | Smallest mean provenance confidence across the surviving evidence |
| `excalibur.graph.max-ambiguous-candidates` | 5 | Candidates returned when the answer is ambiguous |
| `excalibur.graph.confidence-temperature` | 1.0 | Divisor on the log-odds of the confidence an answer reports, in `(0.0, 20.0]`. The default leaves it untouched, which is honest: the reported number is arithmetic over a fused score until labelled outcomes say otherwise. `AbstentionCalibrator.fitTemperature` fits it and reports the calibration error before and after — above 1.0 softens overconfident claims, below 1.0 sharpens underconfident ones. Scaling is monotone and fixes 0.5, so it changes how strongly an answer is claimed and never which answer is given or whether one is given at all |
| `excalibur.graph.ram-buffer-mb` | 16 | Lucene RAM buffer per index. Named for the graph, but it sizes the ingestion pipeline's chunk index too when that runs on the `lucene` engine — a node with no graph still pays it, once per open index |

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
before any configuration is loaded. Pass them with `-D`.

| Property | Default | Effect |
| --- | --- | --- |
| `excalibur.useFallback` | false | Forces the ByteBuffer implementation instead of Panama FFM. For a JVM or a sandbox where native access is unavailable; slower, and the node says so at startup |
| `excalibur.enableNativeAccessLog` | true | Whether the Panama detection result is logged. Affects logging only |
| `io.excalibur.graph.pageSize` | 4096 | Page size the graph's `madvise` hints are aligned to. Set it where the platform's pages are not 4 KiB |

They respond to neither the properties file nor the `EXCALIBUR_*` environment variables.

### Chunk lexical index

| Key | Default | Meaning |
| --- | --- | --- |
| `excalibur.ingest.lexical-engine` | `native` | Which engine indexes chunk text: `native` (the engine's own inverted index) or `lucene` |
| `excalibur.ingest.lexical-tombstone-ratio` | 0.2 | Tombstoned fraction at which reclaiming repays the rewrite |
| `excalibur.ingest.lexical-max-latency-seconds` | 3600 | How long an erased chunk may remain on disk before cost stops being a consideration. Checked every tenth of it by a sweep, so the bound holds on an idle node |
| `excalibur.ingest.lexical-max-segments` | 16 | Segments tolerated before the smallest are merged |

The two engines are alternatives, not layers. The native one stores postings and reads a chunk's
text back from storage when a compaction needs it, so the corpus exists once; Lucene keeps a second
full copy of every chunk's text. Only the configured engine is written, so switching engines leaves
the other's directory behind unread and the corpus has to be reingested.

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
signal where it genuinely improves recall; it is no longer the price of admission.

Concretely, on a 165 GiB corpus this was the difference between a multi-month GPU pass and an index
that is searchable as it is written.

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
staged, and every segment a run produced is published together in a single manifest replacement, an
atomic rename. So a run's chunks appear at one instant however many times the buffer spilled, which
matters because a spill lands in the middle of a document and half a document is a wrong answer
rather than a partial one. Publication is at the epoch *after* the current one, so a query already
running does not acquire results halfway through.

**Reingestion.** A chunk identifier names one chunk, so indexing it again is a new version rather
than a second copy: publication retires the copy the store already held under that name. Without
that, a source re-crawled on its timer would return the same chunk twice, count it twice in fusion
and grow the index without bound — and worse, go on matching the words the chunk used to hold while
its storage entry says otherwise, which is a wrong answer rather than a stale one. Each segment
carries a table from the hash of a name to its document, so a batch of a thousand chunks costs a
few thousand probes rather than a pass over every identifier in the store. A replaced copy is
counted as dead weight for compaction but is owed to nobody: it is a previous version, not a
promise, and giving every re-crawl an erasure deadline would make routine indexing urgent.

Retiring a copy is not epoch-scoped, because the mechanism it shares with erasure is deliberately
not: hiding has to be immediate or erasure is not erasure. So a query already running when a chunk
is republished sees neither version of it — the old one is retired and the new one is published at a
later epoch than the query is reading at. That costs one chunk from one in-flight query for the
length of that query, which is the price of erasure taking effect the moment it is asked for.

**Erasure.** Two parts, deliberately. The obligation is appended to a log and forced to disk before
the call returns, and the chunks are tombstoned so no query returns them. Physical removal happens
in a compaction. Hiding alone would not be erasure; rewriting the store on every request would make
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
through the same removal, which stops an ingestion run in flight, clears the source's
`/v1/ingest/status` entry and cancels its place in the re-crawl schedule.

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
of its run rather than leaving them for whatever ingests next. A node that only ever reindexes still
meets the erasure deadline.

**Compaction.** Checked at the end of an ingestion run, at the end of a reindex, when an erasure
arrives, and by a sweep every tenth of `lexical-max-latency-seconds` — which is what holds the
deadline on a node doing none of the first three. It is due when any of three conditions holds,
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
holds one.

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
and not the query. A query longer than `excalibur.api.max-query-chars` is refused outright; one
longer than the expansion bound is searched as plain BM25 and the answer names `query_expansion` in
`ignored_fields`, because two retrieval strategies behind one endpoint is only honest if the
response says which one ran.

Expansion belongs to the lexical path: it rewrites the terms BM25 is searched with. The deployment
it is for by default has a chat model and no embedding pass — `excalibur.llm.api-key` set,
`excalibur.llm.embeddings-enabled=false` — and spends the model on each query instead of on the
whole corpus.

A node that does embed its corpus leaves expansion off, because the vector signal is what carries
the meaning of the question there. That makes the BM25 half of a hybrid query plain BM25 — weaker
than the same node would run with no embeddings at all — and whether the trade pays depends on the
corpus, so `excalibur.query.expand-hybrid-queries=true` turns expansion on for the hybrid case as
well, at one extra chat call per query. `excalibur-bench` scores both.

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

The vector ranking is what is cached. BM25 is not — a lexical answer can change with no mutation
to observe, purely because the query epoch later crosses a manifest entry — so the lexical half
of a hybrid query runs fresh every time and is fused with the cached vector half. Result text,
snippets and explanations are read from storage on every answer, so quoted text can never be
stale, and degraded, timed-out and cancelled answers are never stored at all, so one moment's
trouble cannot be replayed as a persistent fault.

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
the response reports `"vector"`, because that is what ran.

### Evaluating it

`excalibur-bench` carries a harness that scores the retrieval configurations independently — BM25;
BM25 + expansion, with and without a vocabulary gate; BM25 + graph; BM25 + vector; and the
combinations — on recall@k, precision@k, MRR, nDCG@k and latency. Strategies a deployment cannot run
are reported as skipped rather than scored zero, so an unavailable signal cannot flatter the ones
that are present, and the reason names the engine when the engine is why.

The question it exists to answer is whether query-side expansion over a lexical index reaches the
same documents as embedding the corpus, at a fraction of the indexing cost — and that comparison is
only meaningful with the indexing cost reported beside the quality number. So the harness reports
the index it built as well as the quality it reached: chunks per second, and the bytes on disk as a
multiple of the text indexed.

It measures the engine a node ships with. `native` is the default because a benchmark of an engine
the product does not use is a number about nothing; `both` indexes the same corpus with the same
chunking on each in turn, which is the only way to read the two side by side:

```
./gradlew :excalibur-bench:runRetrievalEvaluation \
    -PevalArgs="<corpusDir> <indexDir> <qrels.tsv> 10 - - both"
```

The optional arguments are positional, so `-` stands in for one being skipped.

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
| `POST` | `/v1/erasure` | bearer | mutate | Remove named documents from the corpus: storage, the lexical index, and the registry |
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

A method a registered path does not serve answers 405. On `/health`, `/v1/query` and
`/v1/ingest` - the paths that serve more than one method - the 405 is unauthenticated and charged
against the read budget, because leaving it uncharged would let an anonymous caller spend a
routing decision per request at line rate. Every other path serves one method and refuses the
rest from behind its own guard, so a wrong method there is authenticated first and charged to
that path's own budget: on a mutating route, the mutate one. Anything else answers 404. The graph routes are registered on every
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
`shared`, which is the whole of what a single-tenant node ever does. A header that is not a legal
namespace id is refused with `400` rather than quietly given `shared`: a caller that asked for
tenant A and was handed tenant B has had its documents cross a tenancy boundary, and nothing
downstream could tell. Staging borrows the tenant for the length of one document, so a source in a
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
`/v1/documents`, `/v1/erasure`, `/v1/reindex`, `/v1/compact` and `/v1/stats` have no namespace to
switch to. Those routes answer `400` when a request names one, instead of serving the shared index
as though the header had been honoured — a caller that asked for tenant A and was handed
tenant-neutral data has no way to tell, which makes a tenancy mistake indistinguishable from a
working request, and on `/v1/erasure` it is not a mistake anything undoes. Naming `shared`
explicitly is accepted, because that is what those routes actually serve. Probes and the node-level
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
applies to every route except `/health` and `/health/ready`, and the check runs before the route
decides anything else, so an unauthenticated caller learns nothing about which routes exist.

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
    {"document_id": "my-docs:guide.md:chunk:2", "score": 0.950000, "segment_id": 3}
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

`document_id` is always a JSON string, never a bare number: it is the identifier ingestion
assigned, `sourceId:documentId:chunk:N`, which is what a client can actually look something up by.
The HNSW node id is deliberately not exposed — compaction renumbers it. `segment_id` says which
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
`DELETE /v1/query/{query_id}`. A cancelled query answers `200` with whatever it had reached and
`cancelled: true` - which is the only thing separating it from a query that searched everything and
matched nothing.

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

```json
{"source_id": "my-docs", "source_type": "filesystem", "status": "registered"}
```

`registered` means recorded and started, not finished. `root_path` must resolve inside
`excalibur.ingest.allowed-roots`, which is empty until configured — a 403 here is the allow-list,
not the path. The other supported `source_type` is `http`, whose `base_url` is checked against
`excalibur.ingest.allowed-hosts` and refused if it resolves to any private address.

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
     "entries_ingested": 450000, "entries_pending": 170000,
     "last_updated_ms": 1730000042000, "last_error": null},
    {"source_id": "docs-site", "state": "idle", "progress": 1.0000,
     "entries_ingested": 12034, "entries_pending": 0,
     "last_updated_ms": 1729900000000, "last_error": null}
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

### Reindex Status Example

`POST /v1/reindex` answers 202 and rewrites the source on a background thread, so this is where
the run's verdict surfaces. The two forms mirror `/v1/ingest/status` exactly:

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
A reindex that stops early leaves the old segments
serving, and `last_error` says what kind of failure it was and how many documents it affected,
pointing at the node's log for the reason itself — those reasons carry filesystem paths and
embedding endpoint URLs, and this route answers any caller holding a read token. It is not
reported as `idle`, because a failed run reported as idle-with-a-note reads as a run that
finished.

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
split — are invisible to queries and still occupy their bytes. Two background sweeps reclaim them
on a timer. `POST /v1/compact` brings that forward.

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
applies the same thresholds the background sweeps apply and so only brings forward work that was
going to happen; `true` rewrites every live segment and rebuilds every graph regardless, which is
what to send after erasing a large fraction of the corpus and otherwise a lot of I/O for nothing.

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
  "graphs_failed": 0
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

The `lexical` block is present only on a node that keeps a chunk index; `cuckoo.saturated` and
`cuckoo.saturatedSegments` say whether the membership filter still has room to prune with. See
[the stats section of USAGE.md](USAGE.md#stats) for what every field means.

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
{"state": "running", "namespace": "shared", "started_at_ms": 1736899200000}
```

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
one would turn every question into a refusal.

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
| `needs_input` | A mention could not be bound. Carries `reason` — one of `unbound_pronoun`, `ambiguous_pronoun` or `no_entity_mention` — plus `question` and `candidates`; pass one back as `context_entities`. Returned as 200 — the request was well-formed and the system did what it should |
| `declined` | The evidence does not support an answer. `reason` is one of `no_match`, `below_confidence_threshold`, `low_provenance_confidence`, `unresolved_reference` or `ambiguous`. Deliberately not an empty `answered`, which a client would render as "nothing found" |

`tokens`, `facts_included`, `facts_dropped`, `truncated` and `format` come with the serialised
context, so they are absent on an `answered` response that carries none — read `context` and treat
the five as optional rather than assuming a shape. `format` names the serializer that produced the
context, so an A/B test can attribute an outcome to it. `timings_ms` carries only the stages that actually ran, drawn from a fixed set — `plan`, `link`,
`traverse`, `rank`, `serialize`, `prefill`, `decode` — plus `total`. It comes back on all three
statuses, because a refusal that took two seconds is as much a performance question as an answer
that did.

The request is bounded: `query` at 4096 characters, `context_entities` at 128 entries of at most 256
characters each. Exceeding either is a 400 naming the bound. A context is refused rather than
truncated, because dropping part of one silently changes which entity a pronoun binds to and the
answer would read as though the whole conversation had been given.

`as_of` names the valid-time instant to answer at, and the response echoes back the one that was
used. Without it the instant comes from date words the classifier finds in the question — a guess
at what the asker meant, which is the wrong mechanism for a client that already knows the date it
wants. Three spellings:

| `as_of` | Meaning |
|---------|---------|
| `2019-03-01` | An ISO-8601 date: what held on that day |
| `2019-03-01T14:00:00Z` | An ISO-8601 instant, resolved to the UTC day it falls in — valid time is kept to day granularity |
| `all` | The whole history rather than a snapshot of it, so a fact and the one that superseded it both come back |

Anything else is a 400. A value that could not be understood must not quietly become "now": a
request that asked for 2019 and was answered for today is wrong in a way nothing in the response
would show.

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

An entity id is internal and nothing this node serves hands one out, so `entity_name` is how
"erase everything about this person" is actually asked. It is matched exactly, ignoring case and
surrounding space, against the served entity dictionary and the staged labels it is built from -
never against the `labels` index, whose whole value at query time is that "Lovelace" finds "Ada
Lovelace" and whose whole danger here is the same thing. A name no entity has is a `404`; a name
two entities share is a `409` listing their ids, so the caller repeats the request with the
`entity_id` of the one they meant.

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
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -cp excalibur.jar excalibur.api.mcp.ExcaliburMcpServer
```

It speaks MCP over stdin and stdout and talks to a running node over HTTP, so it is configured by
environment rather than by the properties file:

| Variable | Meaning | Default |
|----------|---------|---------|
| `EXCALIBUR_URL` | Base URL of the node | `http://127.0.0.1:8080` |
| `EXCALIBUR_API_AUTH_TOKEN` | Bearer token, when the node requires one | none |
| `EXCALIBUR_NAMESPACE` | Default namespace for graph calls | the shared one |
| `EXCALIBUR_MCP_TIMEOUT_SECONDS` | Per-request timeout | 60 |

Registered with an MCP client, for example in `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "excaliburdb": {
      "command": "java",
      "args": ["--enable-preview", "--enable-native-access=ALL-UNNAMED",
               "-cp", "/path/to/excalibur.jar", "excalibur.api.mcp.ExcaliburMcpServer"],
      "env": {"EXCALIBUR_URL": "http://127.0.0.1:8080"}
    }
  }
}
```

### The tools

| Tool | What it does |
|------|--------------|
| `ingest_documents` | Registers a directory and starts indexing it |
| `crawl_website` | Registers an HTTP source and starts crawling it |
| `add_documents` | Indexes documents sent in the call itself, for content the model is holding |
| `ingestion_status` | How far indexing has got |
| `reindex_source` | Re-reads a registered source and replaces what changed |
| `reindex_status` | How far a reindex has got, and whether it finished, failed or was cancelled |
| `deregister_source` | Stops a source being crawled again; the documents already indexed stay |
| `search_documents` | Searches the indexed documents and returns the matching passages, best first |
| `build_graph` | Publishes the staged facts as the served graph |
| `search_graph` | Asks the graph a question, optionally `as_of` a date, an instant or `all` of history |

Together they are the whole loop, so a model can prepare a corpus as well as query one. The order
matters: `build_graph` has to run after ingestion finishes, and `search_graph` reports that no
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
erasure routes are not - `DELETE /v1/documents` and `DELETE /v1/graph/entity` destroy data and
stay operator-only.

The two searches are not alternatives. `search_documents` returns the passages that match and
needs nothing but an ingestion run - the adapter asks the node for the text, because a model has
no way to resolve a chunk identifier against the store the corpus was ingested from;
`search_graph` returns entities and the facts connecting them
and needs a graph, which in turn needs a chat model and `excalibur.graph.extraction-enabled`, off
by default. A model given only the graph search has nothing to reach the corpus with on an
ordinary node.

`search_graph` returns a `status` the caller has to read. `answered` carries the evidence in
`context` with a confidence score; `needs_input` means an entity in the question was ambiguous and
lists candidates to pass back as `context_entities`; `declined` means the graph does not hold
enough to answer - a result, not an error.

### Without MCP

The same catalogue, in the form the OpenAI and Anthropic function-calling APIs take. Name the
shape, and what comes out is what that API accepts, ready to send:

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -cp excalibur.jar excalibur.api.mcp.ExcaliburMcpServer --print-schema anthropic
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
| `excalibur-api` | HTTP server, API endpoints and the MCP server |
| `excalibur-metrics` | JMX metrics and observability |
| `excalibur-config` | Configuration loading and validation |
| `excalibur-test` | Test utilities and fixtures |
| `excalibur-test-bootstrap` | Initializes `java.lang.foreign` before the first test in every module |
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

Stage 2 prunes only where the term filter can speak for a segment. Both write paths fill it:
`POST /v1/reindex` through the batch publisher, which targets a segment it chose itself, and
`POST /v1/ingest` through the storage engine, which reports the segment each chunk routed to. The
filter is in memory only and is not reloaded at startup, so a restarted node prunes nothing until
something is written again. Three things make a segment unprunable rather than excluded — the
filter holding nothing for it, a level having refused a term for want of room, and no filter being
configured at all — and in each case stage 2 passes the segment through. Pruning is an
optimisation; the filter never costs a result.

The pre-filter in stage 3 hands stage 4 a set of candidate ids per segment, and stage 4 answers
with the nearest members of that set rather than the nearest of the whole graph. The set is carried
on the dispatcher input the segment is searched under, because each segment has its own index, its
own probe and its own node ids; a segment the pre-filter had no opinion about is handed no set and
searched in full. The two stages
therefore share one id space, and it is the graph's: the pre-filter is written by the HNSW
integration worker as it assigns node ids, not during ingestion, which holds the vector but not yet
the id it will be searched by. A pre-filter populated from any other id space would not narrow a
search but empty it, since every candidate would name a node the graph does not have.

A candidate set names a scattering of nodes drawn from wherever the pre-filter's buckets fell, not a
connected region of the graph, so the graph's edges are used to reach named nodes and never to
decide which of them may be returned. A walk permitted to step only from one named node to another
would be walking an edgeless subgraph and would come back with whatever it started on. Stage 4
therefore answers a set one of two ways, whichever does less work: it measures every named node
when the set is small, which is exact at one node read per name, or it runs the ordinary graph
search and admits only named nodes to its results. Measuring reads `|C|` nodes; a search that
admits a `|C| / nodeCount` fraction of what it visits reads on the order of `ef * nodeCount / |C|`
before its beam is full, so measuring is both the cheaper and the exact answer while
`|C|² <= ef * nodeCount` - which for a hundred-thousand-node segment at `ef = 200` covers every
candidate set a pre-filter realistically produces.

Because it is keyed by node id, a pre-filter is only meaningful beside the graph it was built
against. The storage engine therefore attaches one wherever it attaches a graph - segments it
creates, segments it recovers at start-up, and split children - discards the persisted state a
split child would otherwise inherit under its parent's name, and carries it across a compaction,
which replaces the file but not the graph.

Either answer is acted on only where the index has seen what it is speaking about. An index
holding fewer entries than its graph has nodes is ignored outright and the segment is searched in
full, because the nodes it missed are exactly the ones its answer cannot mention - narrowing a
search to such an answer drops matches just as surely as excluding the segment would, only fewer
of them. An index that does cover the graph narrows what the search may return; excluding the
segment needs more than that, because it skips the tail scan too, so it also needs the graph to have absorbed the
whole write log. No index has ever seen that un-integrated tail, which stage 4 scans directly from
the log.

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
missing one — which is what it used to do, silently, since a lookup that probes past a damaged
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
node-to-document map, the PQ codebook and the LSH tables each carry a CRC32C over their payload
plus one over their header, verified when they are opened. A corrupt one is refused by name
rather than loaded, and — the failure that mattered most — a graph file whose magic number had
been zeroed is no longer mistaken for "never initialised" and silently rebuilt as empty. A file
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
disk, named in the store's unreadable list, kept out of every query, and counted, rather than
allowed to look like a corpus that has quietly shrunk. A lexical segment is written once and
never edited — erasure and compaction both produce a new segment — so there is no unsealed state
for this format.

Across all of these, an index written before its format carried checksums is read at its own
version, left byte-for-byte alone, and reported *legacy, unprotected*. There is no migration step
and nothing to undo; a file becomes protected the first time something rewrites it.

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
| `seg-N.keys` | The caller's name for each document, UTF-8, one per line. Text on purpose: this is what an operator opens to see what a segment holds |
| `seg-N.keyidx` | Byte offsets into `seg-N.keys`, so a name is a mapped read rather than heap |
| `seg-N.keymap` | An open-addressed table from the hash of a name to its document, so erasing or republishing a name is a couple of probes rather than a pass over every name in the store. Derived data: rebuilt on open when it is missing or the wrong size |
| `seg-N.tomb` | A bit per document, set when it has been erased and not yet rewritten out |

Anything on disk the manifest does not name is deleted when the store is opened: a segment is
written before it is published, so a crash in between leaves files no reader will ever consult.

Beside it, `<data dir>/document-registry` records which documents this node holds and the chunk ids
each was written under. It is what change detection compares a re-crawl against and what an erasure
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
├── excalibur-test-bootstrap/  # Foreign-memory warm-up for every test JVM
├── excalibur-vector/       # HNSW, distance, quantization
└── excalibur-bench/        # JMH benchmarks
```

### Running Benchmarks

There is no `jmh` Gradle task — the JMH plugin is not applied. Each benchmark is launched through
a `JavaExec` task in the `benchmark` group, because they need different heaps and JVM flags and a
single task cannot carry all of them (`PointLookupBenchmark` wants transparent huge pages, the
SIMD ones want `--add-modules=jdk.incubator.vector`, the memory-mapped Cuckoo run wants 4G).

```bash
# See them all
./gradlew :excalibur-bench:tasks --group benchmark

# One benchmark
./gradlew :excalibur-bench:runMappedFileBenchmark

# Everything, captured to JSON, Markdown, CSV and text
./gradlew :excalibur-bench:runBenchmarksWithCapture
./gradlew :excalibur-bench:runBenchmarksWithCapture -PbenchmarkPattern=Cuckoo -PoutputDir=build/bench
```

| Task | Benchmark | What it measures |
|------|-----------|------------------|
| `runBenchmark` | all benchmarks by default | Takes one optional argument to narrow the pattern — see the note below the table |
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
| `runPointLookupBenchmark` | `PointLookupBenchmark` | Point lookup throughput (target ≥ 1B ops/sec) |
| `runTailScanBenchmark` | `TailScanBenchmark` | Brute-force scan throughput at varying tail sizes |
| `runVectorLayoutBenchmark` | `VectorLayoutBenchmark` | Struct-of-arrays against array-of-structs |

`CuckooFilterInsertBenchmark` has no task of its own; reach it with
`./gradlew :excalibur-bench:runBenchmark --args='cuckoo-insert'` — the runner matches one
argument against `segment`, `cuckoo` and `cuckoo`+`insert`, so the two words have to arrive as one
token.

A selector the runner does not recognise is refused with exit `2`, rather than falling through to
running every benchmark in the module — which, for a typo, is hours of machine time reported as a
successful run. The comparison and capture runners refuse an unknown or valueless option the same
way, so a CI gate reading their exit code cannot mistake "nothing was compared" for "nothing
regressed".

The targets those benchmarks assert are enforced separately by tests tagged `slow` and
`benchmark`, which the root build excludes from `test` because they take minutes. They have their
own task, and it is the only thing that runs them:

```bash
./gradlew :excalibur-bench:benchmarkTest
```

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

```bash
./gradlew :excalibur-integration:test
```

## FAQ

Every answer here was checked against a running node, not against the source alone.

### Setup and startup

**Does it really need Java 21 exactly?**
Yes. The modules compile with `--enable-preview` for the Panama FFM API, and a preview class file
carries a marker that makes any JDK other than the one that produced it refuse to load it. Java 22
will not run this jar. The Gradle toolchain pins 21 for the build; you have to pin it for the
runtime too.

**The process exited with status 1 and printed nothing. What happened?**
It shouldn't any more. The root log appender is asynchronous, and `System.exit()` used to tear the
JVM down while the fatal error was still queued — so a bad `EXCALIBUR_PORT`, for instance, killed
the process silently while the bind-policy check on the next line of the same constructor happened
to win the race and print. `logback.xml` now declares a `<shutdownHook/>`, which drains the queue
first. If you replace that file, keep the hook.

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
from disk when the compaction policy next fires.

**How do I remove everything one source contributed?**
`POST /v1/erasure` with `{"source_id": "...", "scope": "source"}` — no document ids, which the node
looks up itself. It also deregisters the source, so the scheduler cannot re-crawl what you just
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
That state now means the ingestion pipeline itself failed to wire, not that an API key is missing.
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

- **Chunks are ~512 tokens** (`TextChunker`, 512 target with 64 overlap, not configurable), so a
  long-context embedding model buys nothing. Any model with a 512-token window is enough.
- **Distance is cosine everywhere**, so whether a model returns normalised vectors does not matter.
- **The request sends only `model` and `input`** — no `dimensions` parameter. You get the model's
  native width; you cannot ask `text-embedding-3-large` for shortened 1024-dimension vectors.
- **Ingestion embeds 16 chunks per HTTP request** (`IngestionOrchestrator.EMBED_BATCH_SIZE`, not
  configurable), so a document of 160 chunks costs ten round trips rather than one or 160.
  Per-call latency still shapes indexing throughput on a document of any size, and a local
  endpoint on the same machine is often faster end-to-end than a hosted one despite the weaker
  model.

Whatever you pick, set **both** width keys to that model's native dimension — `excalibur.query.
vector-dimensions` sizes the index, `excalibur.llm.embeddings-dimensions` is what the client
requires back. They must be equal, and a node whose keys disagree now refuses to start rather than
failing later on every document.

**Can I change embedding model on a node that has already indexed something?**
Only by reindexing. Vectors are only comparable within one model's embedding space; the stored ones
are the old model's output and the old width. Change the model and the two width keys together,
clear the data directory or reindex every source, and expect the startup check to stop you if the
widths no longer agree.

**Queries got much slower after a large ingestion.**
Read `cuckoo.saturated` and `cuckoo.saturatedSegments` on `/v1/stats`. The membership filter is
what prunes segments that cannot hold a query's terms, and once a level is full it cannot record a
new term. A filter holding *some* of a segment's terms would answer "not here" for the ones it
never got, so a level that has refused a term stops being consulted and its scope is searched in
full. Raise `excalibur.filter.corpus-capacity` past your term count and restart; the only cost of
over-sizing is memory.

**How do I take a node out of rotation before restarting it?**
`POST /v1/drain`. `/health/ready` then answers 503 for as long as the drain is in force, so a load
balancer stops choosing the node, while the node keeps serving everything it is sent - the traffic
still arriving is the traffic that has not noticed yet. `DELETE /v1/drain` puts it back, and a
restart clears the flag.

**A node answers 200 to everything but its results are worse than its sibling's.**
A circuit breaker has probably opened, and neither one says so anywhere a caller could see. The
embedding breaker drops retrieval to lexical-only; the relevancy breaker bypasses stage-5
re-ranking and leaves the answer with the ranking it already had. Read `breakers` on `/v1/stats`,
or alert on `excalibur_circuit_breaker_state > 0` from `/metrics` — both report under that one
gauge, distinguished by a `breaker` label.

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
results gathered so far are returned, and `coverage` tells you how much of the corpus they came
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
empty one would turn every subsequent question into a refusal.

**Why is erasure a 202 instead of a 200?**
Because accepting is not removing. The obligation is recorded durably before the response is
written, so it survives a restart; a compaction rewrites the edges out when the tombstone ratio or
the latency bound says it is due. Rewriting the whole store per request would make erasure a
denial-of-service. Pass `"compact": true` when you need it done now.

**What makes the latency bound a deadline rather than a hope?**
A sweep, running every tenth of `excalibur.graph.erasure-max-latency-seconds`, that asks every
namespace whether its oldest outstanding obligation is due. Without it the bound was only ever
evaluated by the handler of the *next* erasure request, so one request submitted to a node that
then went quiet would wait indefinitely. The sweep costs an integer read per open namespace when
nothing is owed; it closes and reopens the served graph only when a compaction actually runs.

An evicted namespace is swept too, and that is deliberate: an obligation is owed whether or not
anyone is querying the tenant it concerns, so leaving it to be discovered by the next query would
make the deadline conditional on traffic the tenant may never send again. What the sweep reads for
a non-resident namespace is its erasure log alone — a small append-only file, not the thirteen
mappings of a served store — so asking the question costs almost nothing, and only a namespace
whose oldest obligation has actually passed the bound is reopened. At most four are reopened per
pass, oldest first, so discharging a backlog cannot evict the working set in one sweep; a backlog
longer than one pass is logged at WARN. A log that cannot be read is logged at ERROR and never
treated as nothing outstanding.

### Build and development

**`./gradlew build -x test` — does that work?**
Yes. Coverage verification is wired into `check`, and excluding tests used to break the build
outright on an undeclared task dependency. The dependency is now declared, and verification skips
itself when there is no execution data rather than failing everything at 0%.

**Why did coverage verification not fail when I skipped the tests?**
Skipping the tests skips the evidence. Verifying against a missing exec file reports 0% for every
module and fails for a reason that has nothing to do with your code, so `-x test` degrades to "not
verified".

**How do I run the benchmarks? There is no `jmh` task.**
Correct — the JMH Gradle plugin is not applied. Each benchmark has its own `JavaExec` task in the
`benchmark` group, because they need different heaps and JVM flags. Start with
`./gradlew :excalibur-bench:tasks --group benchmark`. The pass/fail gates those benchmarks assert
live in tests tagged `slow` and `benchmark`, which `test` excludes; `:excalibur-bench:benchmarkTest`
is the only thing that runs them.

**Can I depend on these modules from Maven?**
Not from Maven Central — nothing is published there. `./gradlew publishToMavenLocal` installs the
sixteen library modules into `~/.m2`, which is every module except `excalibur-bench`,
`excalibur-integration` and `excalibur-test-bootstrap` — a benchmark harness and two test
modules with nothing to publish. A
composite build is the better option while you are also changing ExcaliburDB — see
[USAGE.md](USAGE.md#programmatic-usage-jvm-embedded).

**Is the coverage number in the report the whole project?**
The aggregate is. Per-module floors are enforced by `check`, and JMH's generated harness classes
are excluded everywhere — they are machine-written and counting them measures code generation
rather than testing.

## License

[Apache License 2.0](LICENSE).
