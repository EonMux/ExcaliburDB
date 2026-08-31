package excalibur.query.payload;

import excalibur.query.QueryResultItem;
import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that query results are resolved back to the text they were ingested from.
 *
 * <p>These write chunks under the ingestion pipeline's real key format
 * ({@code sourceId:documentId:chunk:index}) and value format
 * ({@code [dim][embedding][text]}), because the resolver's whole job is to bridge the gap
 * between what a search result knows (a document key) and what storage holds (chunk
 * keys).</p>
 */
class ResultPayloadResolverTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private TestStorageEngine storageEngine;
    private Segment segment;
    private ResultPayloadResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        storageEngine = new TestStorageEngine(dataDir, arena);
        segment = storageEngine.createSegment();
        resolver = new ResultPayloadResolver(storageEngine);
    }

    @AfterEach
    void tearDown() {
        storageEngine.closeSegments();
        arena.close();
    }

    // ==================== Fixture helpers ====================

    private static byte[] encodeChunk(float[] vector, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer
            .allocate(Integer.BYTES + vector.length * Float.BYTES + textBytes.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(vector.length);
        for (float component : vector) {
            buffer.putFloat(component);
        }
        buffer.put(textBytes);
        return buffer.array();
    }

    private void putChunk(String documentKey, int chunkIndex, String text) {
        segment.put(
            (documentKey + ":chunk:" + chunkIndex).getBytes(StandardCharsets.UTF_8),
            encodeChunk(new float[]{1.0f, 0.0f}, text));
    }

    private QueryResultItem resultFor(String documentKey) {
        return new QueryResultItem(7L, documentKey, 0.9f, null, segment.segmentId(), 7L, 3L);
    }

    private static String textOf(QueryResultItem item) {
        return item.hasPayload() ? new String(item.payload(), StandardCharsets.UTF_8) : null;
    }

    // ==================== Tests ====================

    /**
     * The HNSW integration worker registers only {@code sourceId:documentId} against a
     * node, so the resolver has to expand that back into the chunk keys storage actually
     * holds. Without this the LLM receives an empty candidate for every result.
     */
    @Test
    @DisplayName("resolves a document key into the chunk text stored under it")
    void resolvesDocumentKeyIntoChunkText() {
        putChunk("source1:doc1", 0, "the stored chunk body");

        List<QueryResultItem> resolved = resolver.attachPayloads(List.of(resultFor("source1:doc1")));

        assertThat(textOf(resolved.get(0))).isEqualTo("the stored chunk body");
    }

    @Test
    @DisplayName("concatenates every chunk of a multi-chunk document, in index order")
    void concatenatesAllChunksOfADocument() {
        putChunk("source1:doc1", 0, "first");
        putChunk("source1:doc1", 1, "second");
        putChunk("source1:doc1", 2, "third");

        List<QueryResultItem> resolved = resolver.attachPayloads(List.of(resultFor("source1:doc1")));

        assertThat(textOf(resolved.get(0))).isEqualTo("first\nsecond\nthird");
    }

    @Test
    @DisplayName("stops at the first missing chunk index")
    void stopsAtTheFirstGapInChunkIndexes() {
        putChunk("source1:doc1", 0, "first");
        putChunk("source1:doc1", 2, "unreachable");

        List<QueryResultItem> resolved = resolver.attachPayloads(List.of(resultFor("source1:doc1")));

        assertThat(textOf(resolved.get(0))).isEqualTo("first");
    }

    @Test
    @DisplayName("resolves a result whose id is already a chunk key")
    void resolvesAnExactChunkKey() {
        putChunk("source1:doc1", 0, "exact chunk");

        List<QueryResultItem> resolved =
            resolver.attachPayloads(List.of(resultFor("source1:doc1:chunk:0")));

        assertThat(textOf(resolved.get(0))).isEqualTo("exact chunk");
    }

    /**
     * A tail-scan hit already carries the value the scan read, and that text is the exact
     * chunk rather than the whole document. Re-resolving it would replace the precise text
     * with a document-level approximation.
     */
    @Test
    @DisplayName("leaves an existing payload untouched")
    void leavesExistingPayloadsAlone() {
        putChunk("source1:doc1", 0, "text from storage");
        QueryResultItem tailHit = resultFor("source1:doc1")
            .withPayload("text from the write log".getBytes(StandardCharsets.UTF_8));

        List<QueryResultItem> resolved = resolver.attachPayloads(List.of(tailHit));

        assertThat(textOf(resolved.get(0))).isEqualTo("text from the write log");
    }

    @Test
    @DisplayName("returns the input unchanged when nothing can be resolved")
    void returnsInputUnchangedWhenNothingResolves() {
        List<QueryResultItem> input = List.of(resultFor("source1:absent"));

        assertThat(resolver.attachPayloads(input)).isSameAs(input);
    }

    @Test
    @DisplayName("leaves results with no document identity alone")
    void leavesResultsWithoutDocumentIdsAlone() {
        List<QueryResultItem> input = List.of(
            new QueryResultItem(7L, null, 0.9f, null, segment.segmentId(), 7L, 3L));

        assertThat(resolver.attachPayloads(input)).isSameAs(input);
    }

    @Test
    @DisplayName("attaches nothing for a vector-only chunk")
    void attachesNothingForAVectorOnlyChunk() {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + 2 * Float.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(2);
        buffer.putFloat(1.0f);
        buffer.putFloat(0.0f);
        segment.put("source1:doc1:chunk:0".getBytes(StandardCharsets.UTF_8), buffer.array());

        List<QueryResultItem> resolved = resolver.attachPayloads(List.of(resultFor("source1:doc1")));

        assertThat(resolved.get(0).hasPayload()).isFalse();
    }

    @Test
    @DisplayName("truncates an oversized document to the payload budget")
    void truncatesOversizedDocuments() {
        String body = "x".repeat(6_000);
        putChunk("source1:doc1", 0, body);
        putChunk("source1:doc1", 1, body);

        List<QueryResultItem> resolved = resolver.attachPayloads(List.of(resultFor("source1:doc1")));

        assertThat(resolved.get(0).payload().length).isLessThanOrEqualTo(ChunkValueCodec.MAX_TEXT_BYTES);
        assertThat(textOf(resolved.get(0))).startsWith("xxx");
    }

    @Test
    @DisplayName("returns an empty list unchanged")
    void handlesAnEmptyResultList() {
        assertThat(resolver.attachPayloads(List.of())).isEmpty();
    }

    // ==================== Test infrastructure ====================

    /**
     * A storage engine exposing hand-created segments, without starting the engine: the
     * resolver must read the segment the result came from and must not fail when the
     * engine-wide routed lookup is unavailable.
     */
    private static final class TestStorageEngine extends StorageEngine {
        private final Path dataDir;
        private final Arena arena;
        private final Map<Long, Segment> segments = new ConcurrentHashMap<>();
        private int counter;

        TestStorageEngine(Path dataDir, Arena arena) {
            super(dataDir, arena);
            this.dataDir = dataDir;
            this.arena = arena;
        }

        Segment createSegment() throws IOException {
            Segment created = new Segment(dataDir.resolve("segment-" + counter++ + ".seg"),
                4 * 1024 * 1024, arena);
            segments.put(created.segmentId(), created);
            return created;
        }

        @Override
        public Segment getSegment(long segmentId) {
            return segments.get(segmentId);
        }

        @Override
        public Map<Long, Segment> getSegments() {
            return Collections.unmodifiableMap(segments);
        }

        void closeSegments() {
            segments.values().forEach(Segment::close);
            segments.clear();
        }
    }
}
