package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.core.util.StopSignal;
import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
 * What {@code DELETE /v1/reindex} actually stops, and what it must never break.
 *
 * <p>Two properties. The documents after the cancellation point are not processed: a stop that
 * was filed as one document's error let a cancelled reindex of a large source walk the whole
 * remaining crawl. And no write the run makes is carried out with its thread interrupted: an
 * interrupt reaching a storage write closes the channel it writes through for every writer of
 * that segment, so a cancel that interrupted the run broke storage for the rest of the
 * process.</p>
 *
 * <p>The stop is asked for through the run's {@link StopSignal}. An interrupt from elsewhere is
 * still honoured as a stop, which several tests here use to stand in for one.</p>
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
    @DisplayName("a stopped run hands its thread back uninterrupted, with the stop recorded")
    void leavesTheInterruptFlagClear() throws Exception {
        StubSource source = new StubSource();
        source.add("doc-1", "First. ".repeat(60));
        source.add("doc-2", "Second. ".repeat(60));
        source.failOn("doc-1", new SourceException("Read interrupted",
            new InterruptedException("interrupted")));
        StopSignal stop = new StopSignal();

        assertThatThrownBy(() -> reindex(source, stop))
            .isInstanceOf(ReindexCancelledException.class);

        assertThat(Thread.currentThread().isInterrupted())
            .as("a scheduled or pooled thread carries the flag into the next run it serves, "
                + "whose first channel write it would close")
            .isFalse();
        assertThat(stop.isRequested())
            .as("the caller classifies the run from the signal and the exception type")
            .isTrue();
    }

    @Test
    @DisplayName("a stop requested during a fetch never reaches the writes after it")
    void aStopDuringAFetchLeavesTheWritesUninterrupted() {
        StubSource source = new StubSource();
        source.add("doc-1", "A document long enough to chunk several times. ".repeat(200));
        source.add("doc-2", "Never reached. ".repeat(60));
        StopSignal stop = new StopSignal();
        // What the endpoint's DELETE does when it lands while the source is being read: the
        // fetch is inside the signal's window, so the request interrupts it there.
        source.requestWhileFetching("doc-1", stop);

        assertThatThrownBy(() -> reindex(source, stop))
            .isInstanceOf(ReindexCancelledException.class);

        assertThat(writer.written())
            .as("the fetched document is finished whole")
            .isNotEmpty();
        assertThat(writer.writesUnderInterrupt())
            .as("an interrupt reaching a storage write closes the channel every writer of that "
                + "segment shares")
            .isZero();
        assertThat(source.fetched()).containsExactly("doc-1");
    }

    @Test
    @DisplayName("a stop requested between two chunks lets the document finish, uninterrupted")
    void aStopMidDocumentFinishesTheDocument() {
        StubSource source = new StubSource();
        source.add("doc-1", "A document long enough to chunk many times over. ".repeat(400));
        source.add("doc-2", "Never reached. ".repeat(60));
        StopSignal stop = new StopSignal();
        writer.requestOnChunk("doc-1", 2, stop);

        assertThatThrownBy(() -> reindex(source, stop))
            .isInstanceOf(ReindexCancelledException.class);

        DocumentMetadata published = registry.get(SOURCE, "doc-1");
        assertThat(published).isNotNull();
        assertThat(published.getChunkIds()).hasSizeGreaterThan(2);
        assertThat(writer.written()).containsAll(published.getChunkIds());
        assertThat(writer.writesUnderInterrupt()).isZero();
        assertThat(source.fetched()).containsExactly("doc-1");
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
        assertThat(writer.writesUnderInterrupt())
            .as("the interrupt that landed during the fetch is taken as the stop there, not "
                + "carried into the document's writes, where it would close the channel")
            .isZero();
    }

    /** Runs the reindex with a stop signal the test holds. */
    private DocumentReindexer.ReindexResult reindex(StubSource source, StopSignal stop)
            throws SourceException {
        return reindexer.reindexAll(source, DocumentReindexer.ReindexProgress.NONE, stop);
    }

    /** A storage writer that accepts everything, and can interrupt the run mid-document. */
    private static final class RecordingWriter
            implements DocumentReindexer.StorageWriter, UpsertHandler.StorageAdapter {

        private final Map<String, String> text = new ConcurrentHashMap<>();
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final AtomicInteger writesUnderInterrupt = new AtomicInteger();
        private String interruptDocument;
        private int interruptOnChunk;
        private StopSignal stopOnChunk;
        private int chunksSeen;

        void interruptOnChunk(String documentId, int chunkNumber) {
            this.interruptDocument = documentId;
            this.interruptOnChunk = chunkNumber;
        }

        void requestOnChunk(String documentId, int chunkNumber, StopSignal stop) {
            interruptOnChunk(documentId, chunkNumber);
            this.stopOnChunk = stop;
        }

        List<String> written() {
            return List.copyOf(order);
        }

        /** Writes made while the writing thread's interrupt flag was set. */
        int writesUnderInterrupt() {
            return writesUnderInterrupt.get();
        }

        @Override
        public boolean writeChunk(String chunkId, String content, float[] embedding) {
            if (Thread.currentThread().isInterrupted()) {
                writesUnderInterrupt.incrementAndGet();
            }
            if (interruptDocument != null && chunkId.contains(":" + interruptDocument + ":")) {
                chunksSeen++;
                if (chunksSeen == interruptOnChunk) {
                    if (stopOnChunk != null) {
                        stopOnChunk.request();
                    } else {
                        Thread.currentThread().interrupt();
                    }
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
        private final Map<String, StopSignal> requestOn = new ConcurrentHashMap<>();
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

        /** Requests the stop while this document is being read, as the endpoint's DELETE does. */
        void requestWhileFetching(String documentId, StopSignal stop) {
            requestOn.put(documentId, stop);
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
        public FetchedDocument fetch(String documentId) throws SourceException {
            fetched.add(documentId);
            SourceException failure = failures.get(documentId);
            if (failure != null) {
                throw failure;
            }
            if (interruptOn.contains(documentId)) {
                Thread.currentThread().interrupt();
            }
            StopSignal stop = requestOn.get(documentId);
            if (stop != null) {
                stop.request();
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
