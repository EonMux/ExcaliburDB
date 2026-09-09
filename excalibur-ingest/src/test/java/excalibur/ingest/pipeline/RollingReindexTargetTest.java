package excalibur.ingest.pipeline;

import excalibur.filter.CuckooFilter;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.shard.VisibilityTracker;
import io.excalibur.llm.LLMException;
import io.excalibur.llm.client.LLMHttpClient;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.SegmentLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a rolling re-index does when the segment it was told to fill cannot be written to.
 *
 * <p>{@link SegmentCatalog#create} draws IDs from its own counter, which continues past the
 * storage engine's routing width, so the segment it hands back routinely names metadata with no
 * storage behind it. Every write aimed there fails.</p>
 *
 * <p>Discovering that by writing is expensive and quiet: the run crawls the source, chunks every
 * document and pays for an embedding per chunk, and only then fails each write in turn. It ends
 * having indexed nothing, and the only trace is a warning per chunk.</p>
 */
@DisplayName("Rolling re-index write target")
class RollingReindexTargetTest {

    private static final int DIMENSIONS = 8;

    @TempDir
    Path tempDir;

    private SegmentCatalog catalog;
    private CountingLlmClient llmClient;
    private RecordingWriter writer;
    private DocumentReindexer reindexer;

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog(tempDir.resolve("catalog"));
        llmClient = new CountingLlmClient();
        writer = new RecordingWriter();

        DocumentRegistry registry = new DocumentRegistry();
        CuckooFilter filter = CuckooFilter.create(1024);
        VisibilityTracker visibility = new VisibilityTracker(catalog);

        reindexer = new DocumentReindexer(
            new TextChunker(512, 64),
            llmClient,
            new UpsertHandler(registry, filter, writer),
            registry,
            writer,
            new DocumentBatchPublisher(visibility, registry, filter,
                () -> { }, new ReentrantLock()),
            catalog,
            new SegmentLifecycleManager(catalog),
            visibility);
    }

    @Test
    @DisplayName("an unwritable target costs one lookup, not one embedding per chunk")
    void anUnwritableTargetIsDetectedBeforeAnyWork() throws SourceException {
        DocumentReindexer.RollingReindexResult result =
            reindexer.reindexWithRollingReplacement(new OneDocumentSource(), tempDir);

        assertThat(result.getNewSegmentId())
            .as("no segment was replaced, and the result must not claim one was")
            .isEqualTo(-1L);
        assertThat(result.getTombstonedSegmentIds())
            .as("nothing may be retired in favour of a segment that received no data")
            .isEmpty();

        // The segment the catalog minted is unusable, so it must not be left in the catalog
        // occupying a state a worker will later try to advance.
        assertThat(catalog.getSegmentsBySource("docs"))
            .allSatisfy(segment -> assertThat(segment.getLifecycleState())
                .isEqualTo(LifecycleState.TOMBSTONED));

        // The documents still have to be indexed - falling back is not the same as giving up.
        assertThat(result.getDocumentsIndexed())
            .as("the re-index must still happen, by the route the engine supports")
            .isEqualTo(1);
    }

    /** Reports its target unwritable, exactly as a catalog-created segment is. */
    private static final class RecordingWriter
            implements OffsetAwareStorageWriter, UpsertHandler.StorageAdapter {
        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public boolean canWriteTo(long segmentId) {
            return false;
        }

        @Override
        public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
            return writes.getAndIncrement();
        }

        @Override
        public long writeChunkWithOffset(long segmentId, String chunkId, String content,
                                         float[] embedding) {
            // What the storage engine does with an ID that names no segment.
            return -1L;
        }

        @Override
        public boolean delete(byte[] key) {
            return true;
        }
    }

    /** Counts what a run costs before it discovers it cannot write. */
    private static final class CountingLlmClient extends LLMHttpClient {
        private final AtomicInteger embeddings = new AtomicInteger();

        CountingLlmClient() {
            super(excalibur.llm.client.LLMClientConfig.builder()
                .apiKey("test-key")
                .baseUrl("http://localhost:1")
                .build());
        }

        public float[] generateEmbedding(String text, String correlationId) throws LLMException {
            embeddings.incrementAndGet();
            float[] vector = new float[DIMENSIONS];
            vector[0] = 1.0f;
            return vector;
        }
    }

    private static final class OneDocumentSource implements Source {
        @Override
        public String getSourceId() {
            return "docs";
        }

        @Override
        public String getSourceType() {
            return "test";
        }

        @Override
        public Stream<String> crawl() {
            return Stream.of("doc-1");
        }

        @Override
        public boolean hasChanged(java.time.Instant since) {
            return true;
        }

        @Override
        public FetchedDocument fetch(String documentId) {
            byte[] content = "some text to index".getBytes(StandardCharsets.UTF_8);
            return new FetchedDocument(documentId, content, "text/plain", System.currentTimeMillis());
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
