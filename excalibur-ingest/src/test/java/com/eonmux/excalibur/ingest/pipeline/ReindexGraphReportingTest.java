package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.graph.GraphIngestSink;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentLocks;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a re-crawl tells the graph, and how it removes a document a client may be re-posting.
 *
 * <p>A re-crawl publishes new versions and removes dropped documents exactly as ingestion and
 * erasure do, so the graph has to hear of both: a changed page left unreported goes on being
 * answered for with what it used to say, and a dropped one with what it said before it went.
 * And a removal acts on a registry entry read at the start of the pass, so it must not undo a
 * re-post of the same document that landed since.</p>
 */
@DisplayName("Reindex and the graph")
class ReindexGraphReportingTest {

    private static final String SOURCE = "graph-source";
    private static final String KEY = SOURCE + ":doc-a";

    private DocumentRegistry registry;
    private RecordingWriter writer;
    private UpsertHandler upsertHandler;
    private RecordingSink sink;
    private DocumentReindexer reindexer;
    private StubSource source;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        writer = new RecordingWriter();
        upsertHandler = new UpsertHandler(registry, writer);
        sink = new RecordingSink();
        GraphReporter reporter = new GraphReporter();
        reporter.setSink(sink);
        reindexer = new DocumentReindexer(new TextChunker(512, 64), null, upsertHandler,
            registry, writer);
        reindexer.reportGraphThrough(reporter);
        source = new StubSource();
    }

    @Nested
    @DisplayName("reporting")
    class Reporting {

        @Test
        @DisplayName("a document a re-crawl finds new is reported as published, with its text")
        void aNewDocumentIsPublished() throws SourceException {
            source.put("doc-a", "Ada runs Acme.");

            reindexer.reindexAll(source);

            assertThat(sink.calls).containsExactly("publish " + KEY + " Ada runs Acme.", "sync");
        }

        @Test
        @DisplayName("a document a re-crawl finds changed is reported as replaced")
        void aChangedDocumentIsReplaced() throws SourceException {
            source.put("doc-a", "Ada runs Acme.");
            reindexer.reindexAll(source);

            source.put("doc-a", "Ada left Acme.");
            reindexer.reindexAll(source);

            assertThat(sink.calls).containsExactly(
                "publish " + KEY + " Ada runs Acme.", "sync",
                "replace " + KEY + " Ada left Acme.", "sync");
        }

        @Test
        @DisplayName("a document its source stopped listing is reported as removed")
        void aDroppedDocumentIsRemoved() throws SourceException {
            source.put("doc-a", "Ada runs Acme.");
            source.put("doc-b", "Babbage runs Initech.");
            reindexer.reindexAll(source);

            source.drop("doc-a");
            DocumentReindexer.ReindexResult result = reindexer.reindexAll(source);

            assertThat(result.getDocumentsTombstoned()).isEqualTo(1);
            assertThat(result.getChunksTombstoned()).isEqualTo(1);
            assertThat(sink.calls).endsWith("remove " + KEY, "sync");
            assertThat(registry.contains(SOURCE, "doc-a")).isFalse();
            assertThat(writer.textOf(KEY + ":chunk:0")).isNull();
        }

        @Test
        @DisplayName("a single document reindexed on its own is reported too")
        void aSingleDocumentIsReported() {
            reindexer.reindexDocument(SOURCE, "doc-a", document("doc-a", "Ada runs Acme."));
            reindexer.reindexDocument(SOURCE, "doc-a", document("doc-a", "Ada left Acme."));

            assertThat(sink.calls).containsExactly(
                "publish " + KEY + " Ada runs Acme.", "replace " + KEY + " Ada left Acme.");
        }

        @Test
        @DisplayName("a version that did not publish is not reported")
        void anUnpublishedVersionIsNotReported() throws SourceException {
            source.put("doc-a", "Ada runs Acme.");
            writer.refuseWrites = true;

            reindexer.reindexAll(source);

            assertThat(sink.calls).containsExactly("sync");
            assertThat(registry.contains(SOURCE, "doc-a")).isFalse();
        }
    }

    @Nested
    @DisplayName("removal against a concurrent re-post")
    class ConcurrentRepost {

        @Test
        @DisplayName("waits for the document's lock, then leaves the re-posted version alone")
        void aRemovalWaitsForTheLockAndSparesTheRepost() throws Exception {
            source.put("doc-a", "Ada runs Acme.");
            reindexer.reindexAll(source);
            sink.calls.clear();
            source.drop("doc-a");

            DocumentMetadata reposted = DocumentMetadata.builder()
                .sourceId(SOURCE)
                .documentId("doc-a")
                .contentHash(DocumentRegistry.computeContentHash(
                    "Ada left Acme.".getBytes(StandardCharsets.UTF_8)))
                .contentLength(14)
                .chunkIds(List.of(KEY + ":chunk:0"))
                .indexTimeMillis(registry.get(SOURCE, "doc-a").getIndexTimeMillis() + 1)
                .build();

            // The re-post holds the document's lock across its write and its registration, as
            // ingestion does. The pass is started inside it, so its change detection reads the
            // registry entry the re-post is about to replace, and it reaches the removal while
            // the re-post is halfway through: the new chunk written under the old chunk's id,
            // and not yet registered.
            CompletableFuture<DocumentReindexer.ReindexResult> pass =
                DocumentLocks.shared().inDocument(KEY, () -> {
                    writer.writeChunk(KEY + ":chunk:0", "Ada left Acme.", null);
                    CompletableFuture<DocumentReindexer.ReindexResult> started =
                        new CompletableFuture<>();
                    Thread runner = Thread.ofPlatform().start(() -> {
                        try {
                            started.complete(reindexer.reindexAll(source));
                        } catch (Throwable e) {
                            started.completeExceptionally(e);
                        }
                    });
                    awaitParkedOnALockOrDone(runner);
                    registry.register(reposted);
                    return started;
                });

            DocumentReindexer.ReindexResult result = pass.get(30, TimeUnit.SECONDS);

            assertThat(writer.textOf(KEY + ":chunk:0"))
                .as("the removal must not tombstone the chunk the re-post wrote under its id")
                .isEqualTo("Ada left Acme.");
            assertThat(registry.get(SOURCE, "doc-a"))
                .as("nor remove the entry the re-post registered")
                .isEqualTo(reposted);
            assertThat(result.getDocumentsTombstoned()).isZero();
            assertThat(sink.calls)
                .as("and the graph keeps what the re-posted version's own report stages")
                .doesNotContain("remove " + KEY);
        }

        @Test
        @DisplayName("leaves a document that was erased since the listing to the erasure")
        void anErasedDocumentIsPassedOver() throws Exception {
            source.put("doc-a", "Ada runs Acme.");
            reindexer.reindexAll(source);
            sink.calls.clear();
            source.drop("doc-a");

            DocumentReindexer.ReindexResult result = DocumentLocks.shared().inDocument(KEY, () -> {
                CompletableFuture<DocumentReindexer.ReindexResult> started =
                    new CompletableFuture<>();
                Thread runner = Thread.ofPlatform().start(() -> {
                    try {
                        started.complete(reindexer.reindexAll(source));
                    } catch (Throwable e) {
                        started.completeExceptionally(e);
                    }
                });
                awaitParkedOnALockOrDone(runner);
                registry.remove(SOURCE, "doc-a");
                return started;
            }).get(30, TimeUnit.SECONDS);

            assertThat(result.getDocumentsTombstoned()).isZero();
            assertThat(sink.calls).containsExactly("sync");
        }

        /**
         * Waits until the thread is parked on a lock, or has finished.
         *
         * <p>Finished is what a removal that ignored the document's lock does, and the assertions
         * that follow then catch it; waiting on a lock is the only thing the pass can be doing
         * past its change detection with this source and this writer.</p>
         */
        private void awaitParkedOnALockOrDone(Thread runner) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                Thread.State state = runner.getState();
                if (state == Thread.State.TERMINATED) {
                    return;
                }
                if (state == Thread.State.WAITING
                        && LockSupport.getBlocker(runner) instanceof AbstractQueuedSynchronizer) {
                    return;
                }
                Thread.onSpinWait();
            }
            throw new AssertionError("the pass neither finished nor waited for the lock");
        }
    }

    private static FetchedDocument document(String id, String text) {
        return new FetchedDocument(id, text.getBytes(StandardCharsets.UTF_8), "text/plain", 0L);
    }

    /** A sink that records what it was told, in order. */
    private static final class RecordingSink implements GraphIngestSink {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void onDocumentPublished(String documentKey, String text,
                                        long lastModifiedMillis) {
            calls.add("publish " + documentKey + " " + text);
        }

        @Override
        public void onDocumentReplaced(String documentKey, String text,
                                       long lastModifiedMillis) {
            calls.add("replace " + documentKey + " " + text);
        }

        @Override
        public void onDocumentRemoved(String documentKey) {
            calls.add("remove " + documentKey);
        }

        @Override
        public void sync() {
            calls.add("sync");
        }
    }

    /** A storage writer that keeps what it was handed, and can be told to refuse. */
    private static final class RecordingWriter
            implements DocumentReindexer.StorageWriter, UpsertHandler.StorageAdapter {

        private final Map<String, String> text = new ConcurrentHashMap<>();
        volatile boolean refuseWrites;

        String textOf(String chunkId) {
            return text.get(chunkId);
        }

        @Override
        public boolean writeChunk(String chunkId, String content, float[] embedding) {
            if (refuseWrites) {
                return false;
            }
            text.put(chunkId, content);
            return true;
        }

        @Override
        public boolean delete(byte[] key) {
            return text.remove(new String(key, StandardCharsets.UTF_8)) != null;
        }
    }

    /** A source whose documents are whatever the test last put in it. */
    private static final class StubSource implements Source {

        private final Map<String, String> documents = new LinkedHashMap<>();

        void put(String documentId, String content) {
            documents.put(documentId, content);
        }

        void drop(String documentId) {
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
            return document(documentId, content);
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
