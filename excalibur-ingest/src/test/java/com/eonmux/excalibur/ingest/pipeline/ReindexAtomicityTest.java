package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * A query running while a reindex rewrites a document sees the whole of one version of it.
 *
 * <p>Chunk ids are the document key and an index, so a reindex overwrites the previous version in
 * place, one chunk at a time, and a query's tail scan reads each write the moment it lands. What
 * stands between that query and a document that is half one version and half the other is the
 * document's visibility entry: in flight while its chunks are written, committed once they are
 * all published. These tests stop the reindex at every write, every tombstone and on either side
 * of the publication, and run a query there - one reading at the current epoch, and one that
 * captured its epoch at the previous stop and reads only now, as a query spanning the mutation
 * does.</p>
 *
 * <p>A query here reads what storage holds and passes each chunk through the checker the query
 * pipeline's atomicity filter consults, by the filter's rule: a document the checker has never
 * heard of passes, one it tracks passes only if it is visible at the query's epoch. Every
 * observation must be nothing of the document, the whole previous version, or the whole new
 * one.</p>
 */
@DisplayName("A reindexed document is published atomically")
class ReindexAtomicityTest {

    private static final String SOURCE = "docs";

    /** Small enough that a few dozen words span several chunks. */
    private static final int CHUNK_TOKENS = 8;

    private static final String DONE = "done";

    @TempDir
    Path dataDir;

    /** What storage holds, which is what a tail scan reads. Survives a simulated restart. */
    private final Map<String, String> storage = new ConcurrentHashMap<>();

    private final Stepper stepper = new Stepper();
    private final ExecutorService reindexThread = Executors.newSingleThreadExecutor();

    private SegmentCatalog catalog;
    private Node node;

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog(dataDir.resolve("catalog"));
        node = new Node();
    }

    @AfterEach
    void tearDown() {
        reindexThread.shutdownNow();
    }

    @Test
    @DisplayName("a document that grows is never seen as a mix or as part of its new version")
    void growingDocument() throws Exception {
        StubSource source = new StubSource();
        source.put("a.txt", version("olda", 3));
        node.reindexer.reindexAll(source);
        Map<String, String> old = storedVersionOf("a.txt");

        source.put("a.txt", version("newa", 7));
        List<Observation> observations = observeWhile(() -> node.reindexer.reindexAll(source));
        Map<String, String> replacement = storedVersionOf("a.txt");

        assertThat(replacement.size()).as("precondition: the new version is longer")
            .isGreaterThan(old.size());
        assertAtomic(observations, "a.txt", old, replacement);
    }

    @Test
    @DisplayName("a document that shrinks never shows its new version beside the old tail")
    void shrinkingDocument() throws Exception {
        StubSource source = new StubSource();
        source.put("a.txt", version("olda", 7));
        node.reindexer.reindexAll(source);
        Map<String, String> old = storedVersionOf("a.txt");

        source.put("a.txt", version("newa", 2));
        List<Observation> observations = observeWhile(() -> node.reindexer.reindexAll(source));
        Map<String, String> replacement = storedVersionOf("a.txt");

        assertThat(replacement.size()).as("precondition: the new version is shorter")
            .isLessThan(old.size());
        assertAtomic(observations, "a.txt", old, replacement);
    }

    @Test
    @DisplayName("documents published at successive epochs in one pass are each seen whole")
    void severalDocumentsAcrossEpochs() throws Exception {
        StubSource source = new StubSource();
        source.put("a.txt", version("olda", 3));
        source.put("b.txt", version("oldb", 6));
        node.reindexer.reindexAll(source);
        Map<String, String> oldA = storedVersionOf("a.txt");
        Map<String, String> oldB = storedVersionOf("b.txt");

        source.put("a.txt", version("newa", 5));
        source.put("b.txt", version("newb", 2));
        source.put("c.txt", version("newc", 4));
        List<Observation> observations = observeWhile(() -> node.reindexer.reindexAll(source));

        assertAtomic(observations, "a.txt", oldA, storedVersionOf("a.txt"));
        assertAtomic(observations, "b.txt", oldB, storedVersionOf("b.txt"));
        assertAtomic(observations, "c.txt", Map.of(), storedVersionOf("c.txt"));
        assertThat(node.tracker.getPendingDocumentCount())
            .as("every document published, so none is left in flight").isZero();
    }

    @Test
    @DisplayName("a rolling replacement publishes through the same atomic step")
    void rollingReplacement() throws Exception {
        StubSource source = new StubSource();
        source.put("a.txt", version("olda", 3));
        node.reindexer.reindexAll(source);
        Map<String, String> old = storedVersionOf("a.txt");

        source.put("a.txt", version("newa", 6));
        List<Observation> observations = observeWhile(() ->
            node.reindexer.reindexWithRollingReplacement(source, dataDir.resolve("volume")));

        assertAtomic(observations, "a.txt", old, storedVersionOf("a.txt"));
    }

    @Test
    @DisplayName("a reindex interrupted mid-document stays hidden across a restart until it is "
        + "reindexed whole")
    void restartAfterAnInterruptedReindex() throws Exception {
        StubSource source = new StubSource();
        source.put("a.txt", version("olda", 3));
        node.reindexer.reindexAll(source);
        Map<String, String> old = storedVersionOf("a.txt");

        source.put("a.txt", version("newa", 6));
        node.writer.crashAfterWrites = 2;
        assertThatThrownBy(() -> node.reindexer.reindexAll(source))
            .isInstanceOf(SimulatedCrash.class);
        assertThat(storedVersionOf("a.txt"))
            .as("precondition: storage holds part of each version")
            .containsValues(old.get(chunkIdOf("a.txt", 2)))
            .doesNotContainValue(old.get(chunkIdOf("a.txt", 0)));

        node = new Node();

        Map<String, String> afterRestart = visibleVersionOf("a.txt", node.checker.currentEpoch());
        assertThat(afterRestart)
            .as("the restarted node holds a document that is neither version, and must hide it")
            .isEmpty();

        node.reindexer.reindexAll(source);
        Map<String, String> replacement = storedVersionOf("a.txt");
        assertThat(visibleVersionOf("a.txt", node.checker.currentEpoch()))
            .as("reindexed whole, the new version is served whole")
            .isEqualTo(replacement)
            .hasSizeGreaterThan(old.size());
        assertThat(node.tracker.getPendingDocumentCount()).isZero();

        node = new Node();
        assertThat(node.tracker.getPendingDocumentCount())
            .as("the published document leaves no mark for the next restart to hide it by")
            .isZero();
        assertThat(visibleVersionOf("a.txt", node.checker.currentEpoch())).isEqualTo(replacement);
    }

    @Test
    @DisplayName("a rewrite whose write fails partway stays hidden until a later pass publishes "
        + "it whole")
    void failedWriteKeepsTheTornDocumentHidden() throws Exception {
        StubSource source = new StubSource();
        source.put("a.txt", version("olda", 3));
        node.reindexer.reindexAll(source);
        Map<String, String> old = storedVersionOf("a.txt");

        source.put("a.txt", version("newa", 5));
        node.writer.refuse = chunkIdOf("a.txt", 3);
        DocumentReindexer.ReindexResult failed = node.reindexer.reindexAll(source);

        assertThat(failed.hasErrors()).isTrue();
        assertThat(storedVersionOf("a.txt")).as("precondition: part of each version is stored")
            .isNotEqualTo(old)
            .containsValue(old.get(chunkIdOf("a.txt", 3)));
        assertThat(visibleVersionOf("a.txt", node.checker.currentEpoch()))
            .as("a document storage holds part of each version of is not served").isEmpty();
        assertThat(node.registry.get(SOURCE, "a.txt").getChunkIds())
            .as("the registry still describes the previous version, so the next run retries")
            .containsExactlyElementsOf(old.keySet().stream()
                .sorted(java.util.Comparator.comparingInt(ReindexAtomicityTest::indexOf))
                .toList());

        node.writer.refuse = null;
        node.reindexer.reindexAll(source);

        Map<String, String> replacement = storedVersionOf("a.txt");
        assertThat(replacement).doesNotContainValue(old.get(chunkIdOf("a.txt", 0)));
        assertThat(visibleVersionOf("a.txt", node.checker.currentEpoch()))
            .isEqualTo(replacement);
        assertThat(node.tracker.getPendingDocumentCount()).isZero();
    }

    @Test
    @DisplayName("a new document whose write fails leaves nothing behind and no mark")
    void failedNewDocumentIsReleased() throws Exception {
        StubSource source = new StubSource();
        source.put("a.txt", version("newa", 3));
        node.writer.refuse = chunkIdOf("a.txt", 2);

        DocumentReindexer.ReindexResult failed = node.reindexer.reindexAll(source);

        assertThat(failed.hasErrors()).isTrue();
        assertThat(storedVersionOf("a.txt")).as("every chunk the attempt wrote is removed")
            .isEmpty();
        assertThat(node.tracker.isDocumentTracked(ChunkIdentifiers.documentKey(SOURCE, "a.txt")))
            .as("nothing is left for a mark to hide, so none is kept").isFalse();
    }

    @Test
    @DisplayName("a chunk that cannot be embedded leaves the published version untouched and "
        + "served")
    void embeddingFailureWritesNothing() throws Exception {
        RefusingEmbeddings embeddings = new RefusingEmbeddings();
        node = new Node(embeddings, false);
        StubSource source = new StubSource();
        source.put("a.txt", version("olda", 3));
        node.reindexer.reindexAll(source);
        Map<String, String> old = storedVersionOf("a.txt");

        source.put("a.txt", version("newa", 5));
        embeddings.refuse = "newaba";
        DocumentReindexer.ReindexResult failed = node.reindexer.reindexAll(source);

        assertThat(failed.hasErrors()).isTrue();
        assertThat(failed.getChunksFailed()).isOne();
        assertThat(storedVersionOf("a.txt"))
            .as("every chunk is embedded before any is written, so nothing was overwritten")
            .isEqualTo(old);
        assertThat(visibleVersionOf("a.txt", node.checker.currentEpoch())).isEqualTo(old);
        assertThat(node.tracker.getPendingDocumentCount()).isZero();
    }

    @Test
    @DisplayName("a document that cannot be marked in flight is not written")
    void unmarkableDocumentIsNotWritten() throws Exception {
        StubSource source = new StubSource();
        source.put("a.txt", version("olda", 3));
        node.reindexer.reindexAll(source);
        Map<String, String> old = storedVersionOf("a.txt");

        node = new Node(null, true);
        source.put("a.txt", version("newa", 5));
        DocumentReindexer.ReindexResult failed = node.reindexer.reindexAll(source);

        assertThat(failed.hasErrors()).isTrue();
        assertThat(failed.getErrors().get(0).getMessage()).contains("marked in flight");
        assertThat(storedVersionOf("a.txt"))
            .as("nothing may be written that no mark could hide").isEqualTo(old);
        assertThat(visibleVersionOf("a.txt", node.checker.currentEpoch())).isEqualTo(old);
    }

    @Test
    @DisplayName("a document the source drops leaves no visibility state behind")
    void removedDocumentIsForgotten() throws Exception {
        StubSource source = new StubSource();
        source.put("a.txt", version("olda", 3));
        node.reindexer.reindexAll(source);
        String documentKey = ChunkIdentifiers.documentKey(SOURCE, "a.txt");
        assertThat(node.tracker.isDocumentTracked(documentKey)).isTrue();

        source.remove("a.txt");
        node.reindexer.reindexAll(source);

        assertThat(storedVersionOf("a.txt")).isEmpty();
        assertThat(node.tracker.isDocumentTracked(documentKey)).isFalse();
    }

    // ---------------------------------------------------------------------------------------

    /** One node: the registry on disk, its visibility tracker, and a reindexer over both. */
    private final class Node {
        final DocumentRegistry registry;
        final DocumentVisibilityTracker tracker;
        final DocumentVisibilityChecker checker;
        final SteppingWriter writer;
        final DocumentReindexer reindexer;

        Node() {
            this(null, false);
        }

        /**
         * @param embeddings  Embeds the chunks, or null for a node that indexes lexically
         * @param refuseMarks Whether the tracker refuses to mark a document in flight, as one
         *                    whose journal cannot be forced does
         */
        Node(LLMHttpClient embeddings, boolean refuseMarks) {
            registry = new DocumentRegistry(dataDir.resolve("document-registry"));
            VisibilityTracker clock = new VisibilityTracker(catalog);
            tracker = new DocumentVisibilityTracker(registry, clock) {
                @Override
                public void beginIngestion(String documentId, int expectedChunkCount) {
                    if (refuseMarks) {
                        throw new UncheckedIOException(new IOException("journal not forced"));
                    }
                    super.beginIngestion(documentId, expectedChunkCount);
                }
            };
            checker = new DocumentVisibilityTrackerAdapter(tracker);
            writer = new SteppingWriter();
            reindexer = new DocumentReindexer(new TextChunker(CHUNK_TOKENS, 0), embeddings,
                new UpsertHandler(registry, writer), registry, writer,
                new SteppingPublisher(tracker), catalog, clock);
        }
    }

    /** Embeds every chunk, until told to refuse the ones naming a given word. */
    private static final class RefusingEmbeddings extends LLMHttpClient {
        volatile String refuse;

        RefusingEmbeddings() {
            super(LLMClientConfig.builder().apiKey("test-key").baseUrl("http://localhost:1")
                .build());
        }

        @Override
        public float[] generateEmbedding(String text, String correlationId) throws LLMException {
            if (refuse != null && text.contains(refuse)) {
                throw new LLMException("endpoint down", LLMException.ErrorType.NETWORK);
            }
            return new float[] {1.0f, 0.0f};
        }
    }

    /** A query and when it ran. */
    private record Observation(String point, String reading, Map<String, String> visible) {
    }

    /**
     * Runs the reindex on its own thread and queries at every point it stops at.
     *
     * @return Every observation made, two per stop
     */
    private List<Observation> observeWhile(Callable<?> reindex) throws Exception {
        stepper.armed = true;
        Future<?> run = reindexThread.submit(() -> {
            try {
                return reindex.call();
            } finally {
                stepper.finish();
            }
        });

        List<Observation> observations = new ArrayList<>();
        long previousEpoch = node.checker.currentEpoch();
        try {
            while (true) {
                String point = stepper.awaitStop();
                long epoch = node.checker.currentEpoch();
                if (!DONE.equals(point)) {
                    observations.add(new Observation(point, "at its own epoch",
                        visibleChunks(epoch)));
                }
                observations.add(new Observation(point, "at the epoch of the stop before",
                    visibleChunks(previousEpoch)));
                previousEpoch = epoch;
                if (DONE.equals(point)) {
                    break;
                }
                stepper.resume();
            }
        } finally {
            stepper.armed = false;
        }
        try {
            run.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            fail("the reindex failed", e.getCause());
        }
        assertThat(observations).as("the reindex stopped where a query could land").hasSizeGreaterThan(4);
        return observations;
    }

    /** Every observation shows nothing of the document, all of the old version, or all of the new. */
    private static void assertAtomic(List<Observation> observations, String documentId,
                                     Map<String, String> old, Map<String, String> replacement) {
        String documentKey = ChunkIdentifiers.documentKey(SOURCE, documentId);
        for (Observation observation : observations) {
            Map<String, String> seen = new TreeMap<>();
            observation.visible().forEach((chunkId, text) -> {
                if (ChunkIdentifiers.documentKeyOfChunk(chunkId).equals(documentKey)) {
                    seen.put(chunkId, text);
                }
            });
            if (!seen.isEmpty() && !seen.equals(old) && !seen.equals(replacement)) {
                fail("A query %s at '%s' saw %s as neither version:%n  seen: %s%n  old: %s%n"
                        + "  new: %s", observation.reading(), observation.point(), documentId,
                    seen, new TreeMap<>(old), new TreeMap<>(replacement));
            }
        }
    }

    /** What a query at the given epoch returns, across every document storage holds. */
    private Map<String, String> visibleChunks(long queryEpoch) {
        Map<String, String> visible = new TreeMap<>();
        storage.forEach((chunkId, text) -> {
            String documentKey = ChunkIdentifiers.documentKeyOfChunk(chunkId);
            if (!node.checker.isDocumentTracked(documentKey)
                    || node.checker.isDocumentVisible(documentKey, queryEpoch)) {
                visible.put(chunkId, text);
            }
        });
        return visible;
    }

    private Map<String, String> visibleVersionOf(String documentId, long queryEpoch) {
        return versionOf(documentId, visibleChunks(queryEpoch));
    }

    private Map<String, String> storedVersionOf(String documentId) {
        return versionOf(documentId, storage);
    }

    private static Map<String, String> versionOf(String documentId, Map<String, String> chunks) {
        String documentKey = ChunkIdentifiers.documentKey(SOURCE, documentId);
        Map<String, String> version = new TreeMap<>();
        chunks.forEach((chunkId, text) -> {
            if (ChunkIdentifiers.documentKeyOfChunk(chunkId).equals(documentKey)) {
                version.put(chunkId, text);
            }
        });
        return version;
    }

    private static String chunkIdOf(String documentId, int index) {
        return ChunkIdentifiers.chunkId(SOURCE, documentId, index);
    }

    private static int indexOf(String chunkId) {
        return Integer.parseInt(chunkId.substring(chunkId.lastIndexOf(':') + 1));
    }

    /**
     * Text of {@code chunks} times the chunk size in words, every word of it naming its version.
     * The chunker counts tokens rather than words, so it spans more chunks than that; a test
     * compares versions by what was stored, never by a count it assumes.
     */
    private static String version(String prefix, int chunks) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < chunks * CHUNK_TOKENS; i++) {
            text.append(prefix).append((char) ('a' + i / 26)).append((char) ('a' + i % 26))
                .append(' ');
        }
        return text.toString().trim();
    }

    /**
     * Stops the reindex thread at a point and lets the query thread look, one stop at a time.
     *
     * <p>Two rendezvous per stop, so the reindex cannot move on until the query has read what it
     * left behind, and the query cannot read until the reindex has reached the stop.</p>
     */
    private static final class Stepper {
        private final SynchronousQueue<String> stops = new SynchronousQueue<>();
        private final SynchronousQueue<Boolean> resumes = new SynchronousQueue<>();
        volatile boolean armed;

        void stop(String point) {
            if (!armed) {
                return;
            }
            try {
                if (!stops.offer(point, 30, TimeUnit.SECONDS)
                        || resumes.poll(30, TimeUnit.SECONDS) == null) {
                    throw new IllegalStateException("no query came to look at " + point);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        void finish() {
            if (armed) {
                try {
                    stops.offer(DONE, 30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        String awaitStop() throws InterruptedException {
            String point = stops.poll(30, TimeUnit.SECONDS);
            if (point == null) {
                fail("the reindex neither stopped nor finished");
            }
            return point;
        }

        void resume() throws InterruptedException {
            if (!resumes.offer(Boolean.TRUE, 30, TimeUnit.SECONDS)) {
                fail("the reindex did not wait to be resumed");
            }
        }
    }

    /** Thrown by the writer to stop a run the way a process death would. */
    private static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("simulated crash");
        }
    }

    /** Writes to {@link #storage}, stopping after every write and every tombstone. */
    private final class SteppingWriter implements OffsetAwareStorageWriter, UpsertHandler.StorageAdapter {
        private long nextOffset;
        int crashAfterWrites = -1;
        String refuse;

        @Override
        public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
            if (chunkId.equals(refuse)) {
                return -1;
            }
            storage.put(chunkId, content);
            if (crashAfterWrites > 0 && --crashAfterWrites == 0) {
                throw new SimulatedCrash();
            }
            stepper.stop("after writing " + chunkId);
            return nextOffset++;
        }

        @Override
        public boolean canWriteTo(long segmentId) {
            return false;
        }

        @Override
        public boolean delete(byte[] key) {
            String chunkId = new String(key, StandardCharsets.UTF_8);
            boolean removed = storage.remove(chunkId) != null;
            stepper.stop("after tombstoning " + chunkId);
            return removed;
        }
    }

    /** Publishes through the tracker, stopping on either side of the publication. */
    private final class SteppingPublisher extends DocumentBatchPublisher {

        SteppingPublisher(DocumentVisibilityTracker documents) {
            super(documents);
        }

        @Override
        public PublicationResult publish(DocumentBatchBuffer buffer, DocumentMetadata metadata) {
            stepper.stop("before publishing " + buffer.getDocumentId());
            PublicationResult published = super.publish(buffer, metadata);
            stepper.stop("after publishing " + buffer.getDocumentId());
            return published;
        }
    }

    /** A source whose documents the test decides. */
    private static final class StubSource implements Source {

        private final Map<String, String> documents = new LinkedHashMap<>();

        void put(String documentId, String content) {
            documents.put(documentId, content);
        }

        void remove(String documentId) {
            documents.remove(documentId);
        }

        @Override
        public String getSourceId() {
            return SOURCE;
        }

        @Override
        public String getSourceType() {
            return "stub";
        }

        @Override
        public Stream<String> crawl() {
            return List.copyOf(documents.keySet()).stream();
        }

        @Override
        public FetchedDocument fetch(String documentId) throws SourceException {
            String content = documents.get(documentId);
            if (content == null) {
                throw new SourceException("Document not found: " + documentId);
            }
            return new FetchedDocument(documentId, content.getBytes(StandardCharsets.UTF_8),
                "text/plain", System.currentTimeMillis());
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
