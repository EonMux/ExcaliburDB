package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.cache.CorpusVersion;
import com.eonmux.excalibur.query.cache.QueryCacheKey;
import com.eonmux.excalibur.query.cache.QueryResultCache;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invalidation signal, driven through a real storage engine rather than a stand-in.
 *
 * <h2>Why the epoch alone is not the signal</h2>
 * <p>Stage 0 captures a visibility epoch, and the obvious reading is that an answer computed
 * at epoch N is an answer for any query at epoch N. It is not, in either direction.</p>
 * <ul>
 *   <li>{@code EpochTickScheduler} advances the epoch on a timer whether or not anything was
 *       written, so the epoch moves under a corpus that has not changed.</li>
 *   <li>More seriously, the epoch does not freeze what a query reads.
 *       {@code QuerySnapshot} fixes the <em>set</em> of segments, and says so in its own
 *       javadoc; {@code SegmentQueryTask.captureTailRange()} then reads
 *       {@code graph.getLastIntegratedOffset()} and {@code segment.getWriteLogHead()} live at
 *       execution time and brute-force scans between them. Two queries at one epoch return
 *       different results as soon as the write log has grown between them.</li>
 * </ul>
 *
 * <p>So the version this cache keys on is a fingerprint of everything a query actually reads,
 * taken from the very same accessors the pipeline reads it from, and the epoch is no part of
 * it. These tests change the corpus for real - a write, a delete, a graph insertion, a
 * vector retired in the graph alone - and require the fingerprint to move each time and the
 * cache to stop serving; and, for the first bullet above, they advance the epoch by hand and
 * require the fingerprint to stand still.</p>
 */
@DisplayName("Corpus version invalidation")
class CorpusVersionInvalidationTest {

    /** Far longer than any query here takes to finish. */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private static final int DIMENSION = 8;

    /**
     * Everything a test needs to drive a pipeline over a real engine.
     *
     * @param engine     The storage engine holding the segment
     * @param graph      The vector index attached to it
     * @param visibility The tracker whose epoch a test can advance by hand
     * @param pipeline   The pipeline whose fingerprint is under test
     */
    private record Fixture(StorageEngine engine, HnswGraph graph,
                           VisibilityTracker visibility, QueryPipeline pipeline) {
    }

    private static Fixture fixture(Path dataDir, Arena arena) throws Exception {
        StorageEngine engine = new StorageEngine(dataDir, arena);
        engine.start();

        HnswGraph graph = new HnswGraph(dataDir.resolve("probe.hnsw"),
            HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 1024);
        Segment segment = engine.getSegment(0L);
        segment.attachHnswGraph(graph);

        // Every segment, not just the one carrying the graph. A key routes by hash across
        // the whole directory, and in production the registration sweep admits every active
        // segment, so a fixture that made one of them visible would be fingerprinting a
        // corpus that a real write lands outside of almost every time.
        VisibilityTracker visibility = new VisibilityTracker(engine.getCatalog());
        for (long id = 0; id < engine.getSegmentCount(); id++) {
            SegmentCatalog.SegmentMetadata metadata = engine.getCatalog().get(id);
            if (metadata == null) {
                continue;
            }
            metadata.setLifecycleState(LifecycleState.ACTIVE);
            visibility.registerSegment(id, visibility.currentEpoch());
        }
        engine.setVisibilityTracker(visibility);

        QueryPipeline pipeline = new QueryPipeline(engine, visibility,
            id -> {
                Segment routed = engine.getSegment(id);
                return routed == null ? null : routed.getHnswGraph();
            },
            CosineSimilarity.getInstance());

        return new Fixture(engine, graph, visibility, pipeline);
    }

    @Test
    @DisplayName("a vector tombstoned in the graph alone changes the fingerprint")
    void aGraphOnlyTombstoneInvalidatesTheCache(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        try (Arena arena = Arena.ofShared()) {
            Fixture fixture = fixture(dataDir, arena);
            try {
                long nodeId = fixture.graph().insert(unit());

                QueryResultCache cache = new QueryResultCache(16);
                CorpusVersion before = fixture.pipeline().corpusVersion();
                cache.put(key(), before, answer());

                assertThat(cache.get(key(), fixture.pipeline().corpusVersion(), QueryResponse.class))
                    .as("nothing has changed yet, so the entry is still an answer")
                    .isNotNull();

                // The erasure case no other counter sees. DocumentErasureService.tombstone()
                // collects per-chunk StorageEngine.delete failures and carries on, so the
                // graph can be retired while storage is not, and then nothing the engine or
                // the catalog reports has moved: no epoch advances, nodeCount() still counts
                // the tombstoned node, the write log has not grown and no entry was deleted.
                assertThat(fixture.graph().delete(nodeId))
                    .as("the flag actually transitioned, so there is a change to witness")
                    .isTrue();

                CorpusVersion after = fixture.pipeline().corpusVersion();
                assertThat(after)
                    .as("a vector erased under GDPR must not go on being served out of the "
                        + "cache because no cheap counter happened to record it")
                    .isNotEqualTo(before);
                assertThat(cache.get(key(), after, QueryResponse.class))
                    .as("an answer computed while the vector was live is not an answer now")
                    .isNull();
            } finally {
                close(fixture);
            }
        }
    }

    @Test
    @DisplayName("the fingerprint is stable across epoch ticks under an unchanged corpus")
    void epochTicksAloneDoNotInvalidateTheCache(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        try (Arena arena = Arena.ofShared()) {
            Fixture fixture = fixture(dataDir, arena);
            try {
                QueryResultCache cache = new QueryResultCache(16);
                CorpusVersion before = fixture.pipeline().corpusVersion();
                cache.put(key(), before, answer());

                // Exactly what EpochTickScheduler.performTick() does, driven by hand rather
                // than by waiting on its timer: it advances the epoch unconditionally, on a
                // corpus to which not one byte has been written.
                fixture.visibility().advanceEpoch();
                fixture.visibility().advanceEpoch();

                CorpusVersion after = fixture.pipeline().corpusVersion();
                assertThat(after)
                    .as("the clock is not a mutation; an entry must outlive a tick or the "
                        + "cache expires everything within one epoch-tick-ms and is useless")
                    .isEqualTo(before);
                assertThat(cache.get(key(), after, QueryResponse.class))
                    .as("nothing changed, so the cached answer is still the answer")
                    .isNotNull();
            } finally {
                close(fixture);
            }
        }
    }

    @Test
    @DisplayName("a corpus nobody has touched fingerprints the same twice")
    void anUnchangedCorpusHasAStableVersion(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        try (Arena arena = Arena.ofShared()) {
            Fixture fixture = fixture(dataDir, arena);
            try {
                CorpusVersion first = fixture.pipeline().corpusVersion();
                CorpusVersion second = fixture.pipeline().corpusVersion();

                assertThat(first.readable())
                    .as("a fingerprint that cannot be read is never served from")
                    .isTrue();
                assertThat(second)
                    .as("reading a corpus must not be a change to it, or nothing ever hits")
                    .isEqualTo(first);
            } finally {
                close(fixture);
            }
        }
    }

    @Test
    @DisplayName("an ingested chunk changes the fingerprint and the cache stops serving")
    void ingestionInvalidatesTheCache(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        try (Arena arena = Arena.ofShared()) {
            Fixture fixture = fixture(dataDir, arena);
            try {
                QueryResultCache cache = new QueryResultCache(16);
                CorpusVersion before = fixture.pipeline().corpusVersion();
                cache.put(key(), before, answer());

                assertThat(cache.get(key(), fixture.pipeline().corpusVersion(), QueryResponse.class))
                    .as("nothing has changed yet, so the entry is still an answer")
                    .isNotNull();

                // A real write, through the engine the pipeline reads: it grows the segment's
                // write log, which is exactly the range a tail scan brute-forces.
                fixture.engine().put(bytes("src:doc-1:chunk:0"), payload());

                CorpusVersion after = fixture.pipeline().corpusVersion();
                assertThat(after)
                    .as("a chunk a query would now find must move the fingerprint")
                    .isNotEqualTo(before);
                assertThat(cache.get(key(), after, QueryResponse.class))
                    .as("an entry computed before the write is not an answer after it")
                    .isNull();
            } finally {
                close(fixture);
            }
        }
    }

    @Test
    @DisplayName("an erased chunk changes the fingerprint and the cache stops serving")
    void erasureInvalidatesTheCache(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        try (Arena arena = Arena.ofShared()) {
            Fixture fixture = fixture(dataDir, arena);
            try {
                byte[] key = bytes("src:doc-1:chunk:0");
                fixture.engine().put(key, payload());

                QueryResultCache cache = new QueryResultCache(16);
                CorpusVersion before = fixture.pipeline().corpusVersion();
                cache.put(key(), before, answer());

                // The path POST /v1/erasure takes into storage: DocumentErasureService
                // tombstones every chunk through StorageEngine.delete.
                assertThat(fixture.engine().delete(key)).isTrue();

                CorpusVersion after = fixture.pipeline().corpusVersion();
                assertThat(after)
                    .as("an erased chunk must move the fingerprint, or it stays searchable "
                        + "out of the cache after it is gone from the corpus")
                    .isNotEqualTo(before);
                assertThat(cache.get(key(), after, QueryResponse.class)).isNull();
            } finally {
                close(fixture);
            }
        }
    }

    @Test
    @DisplayName("integrating a vector into the graph changes the fingerprint")
    void graphIntegrationInvalidatesTheCache(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        try (Arena arena = Arena.ofShared()) {
            Fixture fixture = fixture(dataDir, arena);
            try {
                QueryResultCache cache = new QueryResultCache(16);
                CorpusVersion before = fixture.pipeline().corpusVersion();
                cache.put(key(), before, answer());

                // Integration bumps no epoch anywhere - it moves a vector from the write-log
                // tail into the searchable graph, which changes what a search returns and
                // what a tail scan covers, and nothing else records it.
                fixture.graph().insert(unit());

                CorpusVersion after = fixture.pipeline().corpusVersion();
                assertThat(after)
                    .as("a vector that has become searchable must move the fingerprint")
                    .isNotEqualTo(before);
                assertThat(cache.get(key(), after, QueryResponse.class)).isNull();
            } finally {
                close(fixture);
            }
        }
    }

    @Test
    @DisplayName("a document going in flight or being published changes the fingerprint")
    void documentVisibilityInvalidatesTheCache(@TempDir Path tempDir) throws Exception {
        // Neither writes a byte: a mark comes before the first chunk and a publication after
        // the last. An answer computed while a document was withheld would otherwise go on
        // withholding it after its publication, for as long as nothing else moved.
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        try (Arena arena = Arena.ofShared()) {
            Fixture fixture = fixture(dataDir, arena);
            try {
                DocumentVisibilityTracker documents =
                    new DocumentVisibilityTracker(new DocumentRegistry(), fixture.visibility());
                QueryPipeline pipeline = fixture.pipeline()
                    .withVisibilityChecker(new DocumentVisibilityTrackerAdapter(documents));
                QueryResultCache cache = new QueryResultCache(16);
                CorpusVersion before = pipeline.corpusVersion();
                cache.put(key(), before, answer());

                documents.beginIngestion("src:doc", 1);
                CorpusVersion inFlight = pipeline.corpusVersion();
                assertThat(inFlight).as("a document withheld from now on moves the fingerprint")
                    .isNotEqualTo(before);
                assertThat(cache.get(key(), inFlight, QueryResponse.class)).isNull();

                documents.registerDocument("src:doc", Set.of("src:doc:chunk:0"),
                    documents.advanceEpoch());
                CorpusVersion published = pipeline.corpusVersion();
                assertThat(published).as("a document served from now on moves the fingerprint")
                    .isNotEqualTo(inFlight).isNotEqualTo(before);
                assertThat(pipeline.corpusVersion())
                    .as("and nothing further moves it").isEqualTo(published);
            } finally {
                close(fixture);
            }
        }
    }

    @Test
    @DisplayName("a segment leaving the visible set changes the fingerprint")
    void visibilityChangeInvalidatesTheCache(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        try (Arena arena = Arena.ofShared()) {
            StorageEngine engine = new StorageEngine(dataDir, arena);
            engine.start();
            HnswGraph graph = new HnswGraph(dataDir.resolve("probe.hnsw"),
                HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 1024);
            try {
                engine.getSegment(0L).attachHnswGraph(graph);
                engine.getCatalog().get(0L).setLifecycleState(LifecycleState.ACTIVE);
                VisibilityTracker visibility = new VisibilityTracker(engine.getCatalog());
                visibility.registerSegment(0L, visibility.currentEpoch());
                engine.setVisibilityTracker(visibility);
                QueryPipeline pipeline = new QueryPipeline(engine, visibility,
                    id -> {
                        Segment routed = engine.getSegment(id);
                        return routed == null ? null : routed.getHnswGraph();
                    },
                    CosineSimilarity.getInstance());

                CorpusVersion before = pipeline.corpusVersion();
                visibility.markTombstoned(0L, visibility.currentEpoch());

                assertThat(pipeline.corpusVersion())
                    .as("a segment a query can no longer read must move the fingerprint")
                    .isNotEqualTo(before);
                pipeline.shutdown(SHUTDOWN_WAIT);
            } finally {
                graph.close();
                engine.close();
            }
        }
    }

    private static void close(Fixture fixture) throws Exception {
        fixture.pipeline().shutdown(SHUTDOWN_WAIT);
        fixture.graph().close();
        fixture.engine().close();
    }

    private static QueryCacheKey key() {
        return QueryCacheKey.builder()
            .namespace("default")
            .securityContext("token")
            .retrievalMode("vector")
            .queryText("anything at all")
            .topK(10)
            .offset(0)
            .threshold(0.0f)
            .includePartialSegments(true)
            .enableLlmScoring(false)
            .llmThreshold(0.3f)
            .oversamplingFactor(2)
            .includeText(false)
            .snippet(false)
            .snippetChars(320)
            .explain(false)
            .build();
    }

    private static QueryResponse answer() {
        IndexCoverage coverage = IndexCoverage.builder()
            .totalSegments(1)
            .activeSegments(1)
            .segmentsSearched(1)
            .estimatedCorpusCoverage(1.0)
            .degraded(false)
            .build();
        return new QueryResponse("q", List.of(), coverage, 1L, false, false);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static float[] unit() {
        float[] vector = new float[DIMENSION];
        vector[0] = 1.0f;
        return vector;
    }

    /** A chunk value shaped the way ingestion writes one: dimension header, then the vector. */
    private static byte[] payload() {
        byte[] value = new byte[4 + DIMENSION * 4];
        java.nio.ByteBuffer buffer =
            java.nio.ByteBuffer.wrap(value).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(DIMENSION);
        for (int i = 0; i < DIMENSION; i++) {
            buffer.putFloat(i == 0 ? 1.0f : 0.0f);
        }
        return value;
    }
}
