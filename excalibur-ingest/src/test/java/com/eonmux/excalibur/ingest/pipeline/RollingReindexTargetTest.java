package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.SegmentLifecycleManager;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("a writable target is refused before anything is fetched, and nothing is retired")
    void aWritableTargetIsRefused() {
        // A pass fills its segment from change detection: only what is new or changed is
        // fetched and written, and a document the source still lists unchanged - a 304 above
        // all - is never read again. Retiring the old segments behind such a fill would take
        // every unchanged document out of service. No storage engine offers a created segment
        // as a write target; a writer that claims to is refused rather than trusted.
        writer.writable = true;
        SegmentCatalog.SegmentMetadata serving = catalog.create("docs", tempDir);
        catalog.beginIndexing(serving.getSegmentId());
        catalog.completeIndexing(serving.getSegmentId());
        OneDocumentSource source = new OneDocumentSource();

        assertThatThrownBy(() -> reindexer.reindexWithRollingReplacement(source, tempDir))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unchanged");

        assertThat(catalog.get(serving.getSegmentId()).getLifecycleState())
            .as("the segment serving the source is left serving it")
            .isEqualTo(LifecycleState.WARMING);
        assertThat(catalog.getSegmentsBySource("docs"))
            .filteredOn(segment -> segment.getSegmentId() != serving.getSegmentId())
            .as("the segment created for the run is retired, not left for a worker to advance")
            .singleElement()
            .satisfies(segment -> assertThat(segment.getLifecycleState())
                .isEqualTo(LifecycleState.TOMBSTONED));
        assertThat(source.fetches).as("refused before the first fetch").hasValue(0);
        assertThat(llmClient.embeddings).hasValue(0);
    }

    /** Reports its target unwritable, exactly as a catalog-created segment is, unless told not to. */
    private static final class RecordingWriter
            implements OffsetAwareStorageWriter, UpsertHandler.StorageAdapter {
        private final AtomicInteger writes = new AtomicInteger();
        private volatile boolean writable;

        @Override
        public boolean canWriteTo(long segmentId) {
            return writable;
        }

        @Override
        public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
            return writes.getAndIncrement();
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
            super(com.eonmux.excalibur.llm.client.LLMClientConfig.builder()
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
        private final AtomicInteger fetches = new AtomicInteger();

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
        public FetchedDocument fetch(String documentId) {
            fetches.incrementAndGet();
            byte[] content = "some text to index".getBytes(StandardCharsets.UTF_8);
            return new FetchedDocument(documentId, content, "text/plain", System.currentTimeMillis());
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
