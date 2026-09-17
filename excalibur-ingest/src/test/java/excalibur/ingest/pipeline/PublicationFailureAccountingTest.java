package excalibur.ingest.pipeline;

import excalibur.filter.CuckooFilter;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a run reports when the chunks were all written and the publication was what failed.
 *
 * <p>{@code chunksFailed} is the number an operator reads to size the problem before deciding
 * whether to retry, and it counted every chunk of a document whose publication failed - chunks
 * that had all been written without trouble. The document is still a failure and is still
 * reported as one, in {@code errors}; it is the chunk-level counter that was answering a
 * question nobody asked it.</p>
 */
@DisplayName("Publication failure accounting")
class PublicationFailureAccountingTest {

    private static final String SOURCE = "publish-fails";

    @TempDir
    Path tempDir;

    private DocumentReindexer reindexer;
    private DocumentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        CuckooFilter filter = CuckooFilter.create(1024);
        SegmentCatalog catalog = new SegmentCatalog(tempDir.resolve("catalog"));
        VisibilityTracker visibility = new VisibilityTracker(catalog);
        AcceptingWriter writer = new AcceptingWriter();

        reindexer = new DocumentReindexer(
            new TextChunker(512, 64),
            null,
            new UpsertHandler(registry, filter, writer),
            registry,
            writer,
            new RefusingPublisher(visibility, registry, filter));
    }

    @Test
    @DisplayName("chunks that were written are not counted as chunks that failed to write")
    void doesNotCountWrittenChunksAsFailedWrites() throws SourceException {
        StubSource source = new StubSource();
        source.add("doc-1", "A document that chunks several times over. ".repeat(200));

        DocumentReindexer.ReindexResult result = reindexer.reindexAll(source);

        assertThat(result.getChunksFailed())
            .as("every chunk was written without trouble; the publication is what failed, and "
                + "it is one failure, not one per chunk")
            .isZero();
        assertThat(result.getChunksCreated())
            .as("nothing was published, so nothing may be counted as created either")
            .isZero();
        assertThat(result.hasErrors())
            .as("the document failed and the run has to say so - in errors, which is where a "
                + "document-level failure belongs")
            .isTrue();
        assertThat(result.getDocumentsIndexed())
            .as("a document that did not publish is not an indexed document")
            .isZero();
    }

    @Test
    @DisplayName("a document that failed to publish never reports itself as a success")
    void aFailedDocumentIsNotASuccess() {
        DocumentReindexer.DocumentResult docResult = reindexer.reindexDocument(SOURCE, "doc-1",
            new FetchedDocument("doc-1",
                "A document that chunks several times over. ".repeat(200)
                    .getBytes(StandardCharsets.UTF_8),
                "text/plain", System.currentTimeMillis()));

        assertThat(docResult.hasError()).isTrue();
        assertThat(docResult.getFailedChunks())
            .as("no chunk write failed")
            .isZero();
        assertThat(docResult.isSuccess())
            .as("a result carrying the reason it failed cannot also report success, whatever "
                + "the chunk counters say")
            .isFalse();
    }

    @Test
    @DisplayName("the document is left on its previous version, as before")
    void leavesThePreviousVersionInPlace() throws SourceException {
        StubSource source = new StubSource();
        source.add("doc-1", "A document that chunks several times over. ".repeat(200));

        reindexer.reindexAll(source);

        assertThat(registry.get(SOURCE, "doc-1"))
            .as("nothing was published, so nothing may be registered: the content hash is what "
                + "change detection compares, and recording it would retire the document from "
                + "ever being reindexed again")
            .isNull();
    }

    /** A publisher that refuses everything, as one whose write log will not sync does. */
    private static final class RefusingPublisher extends DocumentBatchPublisher {

        RefusingPublisher(VisibilityTracker visibility, DocumentRegistry registry,
                          CuckooFilter filter) {
            super(visibility, registry, filter, () -> { }, new ReentrantLock());
        }

        @Override
        public PublicationResult publish(DocumentBatchBuffer buffer, DocumentMetadata metadata) {
            throw new PublicationException("write log did not sync");
        }
    }

    /** A storage writer that accepts every chunk, so only the publication can fail. */
    private static final class AcceptingWriter
            implements OffsetAwareStorageWriter, UpsertHandler.StorageAdapter {

        private final Map<String, String> text = new ConcurrentHashMap<>();
        private long nextOffset;

        @Override
        public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
            text.put(chunkId, content);
            return nextOffset++;
        }

        @Override
        public long writeChunkWithOffset(long segmentId, String chunkId, String content,
                                         float[] embedding) {
            return writeChunkWithOffset(chunkId, content, embedding);
        }

        @Override
        public boolean canWriteTo(long segmentId) {
            return true;
        }

        @Override
        public boolean delete(byte[] key) {
            return text.remove(new String(key, StandardCharsets.UTF_8)) != null;
        }
    }

    /** A source whose documents the test decides. */
    private static final class StubSource implements Source {

        private final Map<String, String> documents = new java.util.LinkedHashMap<>();

        void add(String documentId, String content) {
            documents.put(documentId, content);
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
        public boolean hasChanged(Instant since) {
            return true;
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
