package excalibur.ingest.pipeline;

import excalibur.filter.CuckooFilter;
import excalibur.ingest.erasure.DocumentErasureService;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code DELETE /v1/reindex} actually stops.
 *
 * <p>The endpoint interrupts the thread running the reindex and tells the caller the run will
 * stop at its next checkpoint. There were no checkpoints: the interrupt surfaced as an exception
 * out of a fetch or a write, the per-document {@code catch (Exception e)} filed it as an ordinary
 * error for that document, and the loop went on to the next one. A cancelled reindex of a large
 * source therefore walked the whole remaining crawl, writing as it went, and was only
 * <em>classified</em> as cancelled if the interrupt flag happened to survive to the end.</p>
 *
 * <p>These tests assert the property an operator cares about - that the documents after the
 * cancellation point are not processed - rather than the flag, which was already right.</p>
 */
@DisplayName("Reindex cancellation")
class ReindexCancellationTest {

    private static final String SOURCE = "cancellable-source";

    private DocumentRegistry registry;
    private RecordingWriter writer;
    private DocumentReindexer reindexer;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        writer = new RecordingWriter();
        reindexer = new DocumentReindexer(new TextChunker(512, 64), null,
            new UpsertHandler(registry, CuckooFilter.create(10_000), writer), registry, writer);
    }

    @AfterEach
    void clearInterrupt() {
        // A test that leaves the flag set fails the next test on the same thread at its first
        // interruptible channel operation, which is a failure nobody can read.
        Thread.interrupted();
    }

    @Test
    @DisplayName("the documents after the cancellation point are never fetched")
    void stopsFetchingOnceCancelled() {
        StubSource source = new StubSource();
        for (int i = 1; i <= 6; i++) {
            source.add("doc-" + i, "Content of document number " + i + ". ".repeat(60));
        }
        // Stands in for the endpoint's DELETE landing while document 2 is being read.
        source.interruptWhileFetching("doc-2");

        assertThatThrownBy(() -> reindexer.reindexAll(source))
            .as("a cancelled run has to end as cancelled, not return as though it finished")
            .isInstanceOf(ReindexCancelledException.class);

        assertThat(source.fetched())
            .as("the run was asked to stop; everything after the checkpoint is work nobody "
                + "asked for, and on a large source it is the whole remaining crawl")
            .containsExactly("doc-1", "doc-2");
    }

    @Test
    @DisplayName("an interrupt arriving as an exception is not filed as a document error")
    void doesNotSwallowAnInterruptThatArrivesAsAnException() {
        StubSource source = new StubSource();
        for (int i = 1; i <= 6; i++) {
            source.add("doc-" + i, "Content of document number " + i + ". ".repeat(60));
        }
        // What a fetch blocked in a socket read does when the canceller interrupts it: the
        // InterruptedException clears the flag on the way out, so the only trace is in the
        // cause chain - and every layer between here and the fetch wraps what it caught.
        source.failOn("doc-2", new SourceException("Read interrupted",
            new IllegalStateException(new InterruptedException("interrupted"))));

        assertThatThrownBy(() -> reindexer.reindexAll(source))
            .isInstanceOf(ReindexCancelledException.class);

        assertThat(source.fetched())
            .as("the interrupt must stop the run where it landed, not be recorded as one "
                + "document's bad luck with the crawl continued")
            .containsExactly("doc-1", "doc-2");
    }

    @Test
    @DisplayName("the interrupt flag is restored, so the caller classifies the run as cancelled")
    void restoresTheInterruptFlag() {
        StubSource source = new StubSource();
        source.add("doc-1", "First. ".repeat(60));
        source.add("doc-2", "Second. ".repeat(60));
        source.failOn("doc-1", new SourceException("Read interrupted",
            new InterruptedException("interrupted")));

        assertThatThrownBy(() -> reindexer.reindexAll(source))
            .isInstanceOf(ReindexCancelledException.class);

        assertThat(Thread.currentThread().isInterrupted())
            .as("ReindexRun reads the flag and the cause chain to tell a stop from a failure; "
                + "an InterruptedException cleared the flag on its way out, so a run that did "
                + "not put it back would have its stop filed as an outage")
            .isTrue();
    }

    @Test
    @DisplayName("a document already under way is finished whole, never split")
    void neverSplitsADocument() {
        StubSource source = new StubSource();
        // Long enough to be several chunks, so a checkpoint inside the write loop would show up
        // as a document with some of its chunks in storage and the rest missing.
        source.add("doc-1", "A document long enough to chunk many times over. ".repeat(400));
        source.add("doc-2", "Never reached. ".repeat(60));
        writer.interruptOnChunk("doc-1", 2);

        assertThatThrownBy(() -> reindexer.reindexAll(source))
            .isInstanceOf(ReindexCancelledException.class);

        DocumentMetadata published = registry.get(SOURCE, "doc-1");
        assertThat(published)
            .as("the interrupt landed inside doc-1, which was already past the point where "
                + "abandoning it would leave chunks that nothing names")
            .isNotNull();
        assertThat(published.getChunkIds())
            .as("a document is written whole or not at all, so the stop cannot take effect "
                + "between two of its chunks")
            .hasSizeGreaterThan(2);
        assertThat(writer.written())
            .as("every chunk the registry names has to be in storage: that is the atomicity "
                + "promise, and a cancellation is not allowed to break it")
            .containsAll(published.getChunkIds());
        assertThat(source.fetched())
            .as("the stop takes effect between documents")
            .containsExactly("doc-1");
    }

    @Test
    @DisplayName("retirement of deleted documents stops at its checkpoint too")
    void stopsRetiringDeletedDocuments() {
        for (int i = 1; i <= 6; i++) {
            registry.register(DocumentMetadata.builder()
                .sourceId(SOURCE)
                .documentId("gone-" + i)
                .lastModifiedMillis(1L)
                .contentHash(i)
                .contentLength(10)
                .chunkIds(List.of(SOURCE + ":gone-" + i + ":chunk:0"))
                .indexTimeMillis(1L)
                .build());
        }
        CountingEraser eraser = new CountingEraser(2);
        reindexer.setVectorEraser(eraser);

        // The source lists nothing, so all six documents read as deleted.
        assertThatThrownBy(() -> reindexer.reindexAll(new StubSource()))
            .isInstanceOf(ReindexCancelledException.class);

        assertThat(eraser.erased())
            .as("a source that dropped a hundred thousand documents is exactly the run an "
                + "operator cancels, so the retirement loop needs a checkpoint of its own")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("what the run did get through is still persisted")
    void persistsWhatItFinished() {
        StubSource source = new StubSource();
        source.add("doc-1", "Indexed before the stop. ".repeat(60));
        source.add("doc-2", "Never reached. ".repeat(60));
        source.interruptWhileFetching("doc-1");

        assertThatThrownBy(() -> reindexer.reindexAll(source))
            .isInstanceOf(ReindexCancelledException.class);

        assertThat(registry.get(SOURCE, "doc-1"))
            .as("the work already committed must survive the stop, or a cancelled run leaves "
                + "chunks in storage that no registry entry names")
            .isNotNull();
    }

    /** A storage writer that accepts everything, and can interrupt the run mid-document. */
    private static final class RecordingWriter
            implements DocumentReindexer.StorageWriter, UpsertHandler.StorageAdapter {

        private final Map<String, String> text = new ConcurrentHashMap<>();
        private final List<String> order = new CopyOnWriteArrayList<>();
        private String interruptDocument;
        private int interruptOnChunk;
        private int chunksSeen;

        void interruptOnChunk(String documentId, int chunkNumber) {
            this.interruptDocument = documentId;
            this.interruptOnChunk = chunkNumber;
        }

        List<String> written() {
            return List.copyOf(order);
        }

        @Override
        public boolean writeChunk(String chunkId, String content, float[] embedding) {
            if (interruptDocument != null && chunkId.contains(":" + interruptDocument + ":")) {
                chunksSeen++;
                if (chunksSeen == interruptOnChunk) {
                    Thread.currentThread().interrupt();
                }
            }
            text.put(chunkId, content);
            order.add(chunkId);
            return true;
        }

        @Override
        public boolean delete(byte[] key) {
            String chunkId = new String(key, StandardCharsets.UTF_8);
            order.remove(chunkId);
            return text.remove(chunkId) != null;
        }
    }

    /** Counts what it was asked to retire, and interrupts the run partway through. */
    private static final class CountingEraser implements DocumentErasureService.VectorEraser {

        private final AtomicInteger erased = new AtomicInteger();
        private final int interruptAfter;

        CountingEraser(int interruptAfter) {
            this.interruptAfter = interruptAfter;
        }

        int erased() {
            return erased.get();
        }

        @Override
        public int erase(String documentKey) {
            int done = erased.incrementAndGet();
            if (done == interruptAfter) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }
    }

    /** A source whose documents, failures and cancellation point the test decides. */
    private static final class StubSource implements Source {

        private final Map<String, String> documents = new LinkedHashMap<>();
        private final Map<String, SourceException> failures = new LinkedHashMap<>();
        private final Set<String> interruptOn = ConcurrentHashMap.newKeySet();
        private final List<String> fetched = new CopyOnWriteArrayList<>();

        void add(String documentId, String content) {
            documents.put(documentId, content);
        }

        void failOn(String documentId, SourceException failure) {
            failures.put(documentId, failure);
        }

        /** Interrupts the run while this document is being read, as the endpoint's DELETE does. */
        void interruptWhileFetching(String documentId) {
            interruptOn.add(documentId);
        }

        List<String> fetched() {
            return List.copyOf(fetched);
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
            return documents.keySet().stream();
        }

        @Override
        public boolean hasChanged(Instant since) {
            return true;
        }

        @Override
        public FetchedDocument fetch(String documentId) throws SourceException {
            fetched.add(documentId);
            SourceException failure = failures.get(documentId);
            if (failure != null) {
                throw failure;
            }
            if (interruptOn.contains(documentId)) {
                Thread.currentThread().interrupt();
            }
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
