package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.executor.TailScanner;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.store.WriteLog;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a query can see in the window between a chunk being written and the graph absorbing it.
 *
 * <h2>What this proves that the unit tests cannot</h2>
 * <p>Two halves have to agree for a freshly written chunk to be answerable at all. Ingestion
 * writes each chunk through the storage engine, which appends it to the segment's write-ahead
 * log; the served query reads that same log from the graph's last integrated offset to its head
 * and scores what it finds there. Each half has its own tests, and each stands in for the other -
 * the pipeline is checked against storage with no scanner in sight, the scanner against a log
 * written by hand in the test itself. The encoding, the key format and the byte order are agreed
 * between them by convention and by nothing else.</p>
 *
 * <p>So this drives the real pipeline into a real storage engine and then scans the logs it
 * wrote, with the graph deliberately left at offset zero: the state every query runs in for as
 * long as integration lags ingestion. A chunk that is unreachable in that window is not a slower
 * answer, it is a missing one, and it goes missing for exactly the documents most likely to be
 * asked about.</p>
 */
@DisplayName("Tail-scan hand-off")
class TailScanHandoffIntegrationTest {

    private static final String SOURCE = "docs";
    private static final int DIMENSION = 8;
    private static final int K = 100;

    @TempDir
    Path tempDir;

    private StorageEngine storage;
    private EmbeddingClient embeddings;
    private IngestionOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws IOException {
        storage = new StorageEngine(Files.createDirectories(tempDir.resolve("data")));
        storage.start();

        embeddings = mock(EmbeddingClient.class);
        when(embeddings.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(vectorFor(text));
            }
            return vectors;
        });

        // Never integrated, never backpressured: the graph is the half of the hand-off this test
        // is deliberately doing without, so its offset stays at zero and the whole log is tail.
        IndexOrchestrator index = mock(IndexOrchestrator.class);
        when(index.isBackpressured()).thenReturn(false);
        when(index.awaitBackpressureRelease(anyInt())).thenReturn(true);

        orchestrator = new IngestionOrchestrator(new TextChunker(), embeddings, storage, index,
            sourceId -> new IngestionOrchestrator.IngestionStatus() {
                @Override
                public void update(String state, double progress, long ingested, long pending) {
                }

                @Override
                public void setSkipped(long skipped) {
                }

                @Override
                public void setDisallowed(long disallowed) {
                }

                @Override
                public void setError(String error) {
                }
            });
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception ignored) {
                // Tidy-up only; the assertions have already run.
            }
        }
    }

    @Test
    @DisplayName("a chunk is answerable from the write log before the graph has absorbed it")
    void anUnintegratedChunkIsAnswerableFromTheWriteLog() {
        ingest(corpus());

        List<Hit> tail = scanEverySegment(vectorFor("anything at all"));

        assertThat(documentIds(tail))
            .as("the graph has absorbed nothing, so the write log is the only place these "
                + "chunks exist to be scored from")
            .contains(SOURCE + ":alpha.md", SOURCE + ":beta.md");
        assertThat(tail)
            .as("each hit names the chunk it scored, as a graph hit does")
            .allSatisfy(hit -> assertThat(hit.result().documentId())
                .startsWith(SOURCE + ":").contains(":chunk:"));
    }

    @Test
    @DisplayName("the chunk text and its vector survive the hand-off intact")
    void theChunkTextAndVectorSurviveTheHandOff() {
        ingest(corpus());

        Hit alpha = scanEverySegment(vectorFor("anything at all")).stream()
            .filter(hit -> (SOURCE + ":alpha.md").equals(documentOf(hit)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no tail hit for alpha.md"));
        String text = alpha.text();
        assertThat(text).isNotEmpty();

        // Asking with the vector that chunk was embedded as. It can only come back nearest, at
        // the distance the metric gives a vector against itself, if the floats read out of the
        // log are the floats that went in - same count, same order, same byte order.
        float[] itsOwnVector = vectorFor(text);
        Hit nearest = scanEverySegment(itsOwnVector).get(0);

        assertThat(nearest.text()).isEqualTo(text);
        assertThat(nearest.result().distance())
            .isCloseTo(CosineSimilarity.getInstance().distance(itsOwnVector, itsOwnVector),
                within(1.0e-6f));
    }

    @Test
    @DisplayName("each chunk is in the log exactly once")
    void eachChunkIsLoggedExactlyOnce() {
        ingest(corpus());

        List<Hit> tail = scanEverySegment(vectorFor("anything at all"));

        // A second writer mirroring the same chunks into the same log would double every
        // document's weight in a tail-scanned result set, which reads as relevance.
        assertThat(hitsFor(tail, SOURCE + ":alpha.md"))
            .isEqualTo(storedChunkCount(SOURCE + ":alpha.md"));
        assertThat(hitsFor(tail, SOURCE + ":beta.md"))
            .isEqualTo(storedChunkCount(SOURCE + ":beta.md"));
    }

    /** One tail-scan hit, carrying the chunk text the scan of its own segment read for it. */
    private record Hit(HnswSearchResult result, byte[] payload) {

        String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    /**
     * Every tail-scan hit on the node, nearest first.
     *
     * <p>Scanned and paired one segment at a time. A tail hit is numbered from the log sequence
     * number, which restarts at zero in every write log, so the same synthetic node ID names a
     * different chunk in a different segment. Production never crosses that line - each hit's
     * text is resolved from its own segment's map, and results are deduplicated on a key that
     * carries the segment ID - and neither does this.</p>
     */
    private List<Hit> scanEverySegment(float[] query) {
        TailScanner scanner = new TailScanner();
        List<Hit> hits = new ArrayList<>();
        for (Segment segment : storage.getSegments().values()) {
            WriteLog log = segment.writeLog();
            if (log == null) {
                continue;
            }
            TailScanner.TailScanOutcome outcome = scanner.scan(
                query, log, 0, segment.getWriteLogHead(), K, CosineSimilarity.getInstance());
            for (HnswSearchResult result : outcome.results()) {
                hits.add(new Hit(result, outcome.payloads().get(result.nodeId())));
            }
        }
        hits.sort(Comparator.comparingDouble(hit -> hit.result().distance()));
        return hits;
    }

    /** The document a hit's chunk belongs to. */
    private static String documentOf(Hit hit) {
        return ChunkIdentifiers.documentKeyOfChunk(hit.result().documentId());
    }

    private static List<String> documentIds(List<Hit> tail) {
        return tail.stream().map(TailScanHandoffIntegrationTest::documentOf).toList();
    }

    private static long hitsFor(List<Hit> tail, String documentKey) {
        return tail.stream()
            .filter(hit -> documentKey.equals(documentOf(hit)))
            .count();
    }

    /** Counts the chunks a document actually left in storage, index by index. */
    private long storedChunkCount(String documentKey) {
        long count = 0;
        while (storage.get(ChunkIdentifiers.chunkIdForDocumentKey(documentKey, (int) count)
                .getBytes(StandardCharsets.UTF_8)) != null) {
            count++;
        }
        assertThat(count).as("nothing was ingested for %s", documentKey).isPositive();
        return count;
    }

    private void ingest(Map<String, String> documents) {
        orchestrator.startIngestion(new MapSource(SOURCE, documents));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (orchestrator.isIngestionActive(SOURCE)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("ingestion did not finish in 30s");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while ingesting", e);
            }
        }
    }

    private static Map<String, String> corpus() {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put("alpha.md", "the write log is where a chunk lives until the graph absorbs it");
        documents.put("beta.md", "capacity planning notes for the following quarter");
        return documents;
    }

    /** Deterministic per-text vector, so a mispaired or byte-swapped vector is detectable. */
    private static float[] vectorFor(String text) {
        float[] vector = new float[DIMENSION];
        int hash = text.hashCode();
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = ((hash >> i) & 1) == 0 ? 0.25f : 0.75f;
        }
        return vector;
    }

    /** A source serving a fixed map of documents. */
    private record MapSource(String sourceId, Map<String, String> documents) implements Source {

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getSourceType() {
            return "map";
        }

        @Override
        public Stream<String> crawl() {
            return documents.keySet().stream();
        }

        @Override
        public FetchedDocument fetch(String documentId) throws SourceException {
            String text = documents.get(documentId);
            if (text == null) {
                throw new SourceException("no such document: " + documentId);
            }
            return new FetchedDocument(documentId, text.getBytes(StandardCharsets.UTF_8),
                "text/plain", System.currentTimeMillis());
        }

        @Override
        public void close() {
        }
    }
}
