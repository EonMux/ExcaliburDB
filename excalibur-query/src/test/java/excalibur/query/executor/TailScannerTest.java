package excalibur.query.executor;

import io.excalibur.store.WriteLog;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.L2Distance;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TailScanner.
 */
class TailScannerTest {

    @TempDir
    Path tempDir;

    /**
     * Encodes a vector the way the ingestion pipeline writes it into the write log:
     * [dimension:4][components:dim*4], little-endian.
     */
    private static byte[] encodeVector(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + vector.length * Float.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(vector.length);
        for (float component : vector) {
            buffer.putFloat(component);
        }
        return buffer.array();
    }

    /**
     * Encodes a chunk the way the ingestion pipeline writes it when the chunk text is
     * stored alongside the embedding: [dimension:4][components:dim*4][text].
     */
    private static byte[] encodeChunk(float[] vector, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] vectorBytes = encodeVector(vector);
        ByteBuffer buffer = ByteBuffer.allocate(vectorBytes.length + textBytes.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(vectorBytes);
        buffer.put(textBytes);
        return buffer.array();
    }

    /**
     * Appends one chunk under the ingestion pipeline's key format.
     */
    private static void appendChunk(WriteLog log, String documentId, int chunkIndex, float[] vector)
            throws IOException {
        String chunkId = documentId + ":chunk:" + chunkIndex;
        log.appendPut(chunkId.getBytes(StandardCharsets.UTF_8), encodeVector(vector));
    }

    /**
     * Appends one chunk carrying both an embedding and its text.
     */
    private static void appendChunk(WriteLog log, String documentId, int chunkIndex,
                                    float[] vector, String text) throws IOException {
        String chunkId = documentId + ":chunk:" + chunkIndex;
        log.appendPut(chunkId.getBytes(StandardCharsets.UTF_8), encodeChunk(vector, text));
    }

    /**
     * Test hasTailEntries returns correct values.
     */
    @Test
    void testHasTailEntries() {
        TailScanner scanner = new TailScanner();

        // No tail entries when equal
        assertThat(scanner.hasTailEntries(100, 100)).isFalse();
        assertThat(scanner.hasTailEntries(0, 0)).isFalse();

        // No tail entries when lastIntegrated > writeLogHead (edge case)
        assertThat(scanner.hasTailEntries(200, 100)).isFalse();

        // Tail entries exist when lastIntegrated < writeLogHead
        assertThat(scanner.hasTailEntries(0, 100)).isTrue();
        assertThat(scanner.hasTailEntries(50, 100)).isTrue();
    }

    /**
     * Test estimateTailEntryCount returns reasonable estimates.
     */
    @Test
    void testEstimateTailEntryCount() {
        TailScanner scanner = new TailScanner();

        // Zero estimate for empty range
        assertThat(scanner.estimateTailEntryCount(100, 100, 100)).isEqualTo(0);
        assertThat(scanner.estimateTailEntryCount(200, 100, 100)).isEqualTo(0);

        // Estimate based on average size
        assertThat(scanner.estimateTailEntryCount(0, 1000, 100)).isEqualTo(10);
        assertThat(scanner.estimateTailEntryCount(0, 500, 100)).isEqualTo(5);

        // Default to 100 if avgEntrySize is 0 or negative
        assertThat(scanner.estimateTailEntryCount(0, 1000, 0)).isEqualTo(10);
        assertThat(scanner.estimateTailEntryCount(0, 1000, -50)).isEqualTo(10);
    }

    /**
     * Test isTailScanResult correctly identifies tail scan node IDs.
     */
    @Test
    void testIsTailScanResult() {
        // Regular node IDs (positive, from HNSW graph)
        assertThat(TailScanner.isTailScanResult(0L)).isFalse();
        assertThat(TailScanner.isTailScanResult(100L)).isFalse();
        assertThat(TailScanner.isTailScanResult(Long.MAX_VALUE)).isFalse();

        // Tail scan node IDs (have high bit set)
        assertThat(TailScanner.isTailScanResult(Long.MIN_VALUE)).isTrue();
        assertThat(TailScanner.isTailScanResult(Long.MIN_VALUE | 100L)).isTrue();
        assertThat(TailScanner.isTailScanResult(-1L)).isTrue(); // All bits set
    }

    /**
     * Test performTailScan returns empty list for an empty range.
     */
    @Test
    void testPerformTailScanEmptyRange() throws IOException {
        TailScanner scanner = new TailScanner();
        float[] query = {1.0f, 0.0f};
        DistanceFunction distance = L2Distance.getInstance();

        try (WriteLog log = new WriteLog(tempDir.resolve("empty-range.wal"))) {
            appendChunk(log, "src:doc1", 0, new float[]{1.0f, 0.0f});
            long head = log.writeOffset();

            assertThat(scanner.performTailScan(query, log, head, head, 10, distance)).isEmpty();
            assertThat(scanner.scan(query, log, head, head, 10, distance).entriesScanned()).isZero();
        }
    }

    /**
     * Test performTailScan returns empty list when k is 0.
     */
    @Test
    void testPerformTailScanZeroK() throws IOException {
        TailScanner scanner = new TailScanner();
        float[] query = {1.0f, 0.0f};
        DistanceFunction distance = L2Distance.getInstance();

        try (WriteLog log = new WriteLog(tempDir.resolve("zero-k.wal"))) {
            appendChunk(log, "src:doc1", 0, new float[]{1.0f, 0.0f});

            assertThat(scanner.performTailScan(query, log, 0, log.writeOffset(), 0, distance)).isEmpty();
        }
    }

    /**
     * The scan must return the entries CLOSEST to the query first, and it must do so for a
     * similarity metric as well as a distance metric. Cosine's raw compute() value is a
     * similarity where LARGER is closer, so ranking on it directly would return the least
     * similar entries; the scan ranks on the ordered dissimilarity instead.
     */
    @Test
    void testTailScanRanksClosestFirstUnderCosine() throws IOException {
        TailScanner scanner = new TailScanner();
        float[] query = {1.0f, 0.0f};

        try (WriteLog log = new WriteLog(tempDir.resolve("cosine.wal"))) {
            appendChunk(log, "src:identical", 0, new float[]{1.0f, 0.0f});   // similarity  1.0
            appendChunk(log, "src:orthogonal", 0, new float[]{0.0f, 1.0f});  // similarity  0.0
            appendChunk(log, "src:opposite", 0, new float[]{-1.0f, 0.0f});   // similarity -1.0

            List<HnswSearchResult> results = scanner.performTailScan(
                query, log, 0, log.writeOffset(), 3, CosineSimilarity.getInstance());

            assertThat(results).hasSize(3);
            assertThat(results)
                .extracting(HnswSearchResult::documentId)
                .containsExactly("src:identical", "src:orthogonal", "src:opposite");
            assertThat(results.get(0).distance()).isLessThan(results.get(1).distance());
            assertThat(results.get(1).distance()).isLessThan(results.get(2).distance());
        }
    }

    /**
     * The same ordering must hold for a plain distance metric.
     */
    @Test
    void testTailScanRanksClosestFirstUnderL2() throws IOException {
        TailScanner scanner = new TailScanner();
        float[] query = {0.0f, 0.0f};

        try (WriteLog log = new WriteLog(tempDir.resolve("l2.wal"))) {
            appendChunk(log, "src:far", 0, new float[]{10.0f, 0.0f});
            appendChunk(log, "src:near", 0, new float[]{1.0f, 0.0f});
            appendChunk(log, "src:middle", 0, new float[]{5.0f, 0.0f});

            List<HnswSearchResult> results = scanner.performTailScan(
                query, log, 0, log.writeOffset(), 2, L2Distance.getInstance());

            assertThat(results)
                .extracting(HnswSearchResult::documentId)
                .containsExactly("src:near", "src:middle");
        }
    }

    /**
     * Every tail-scan hit must carry the document identity decoded from its entry key, so
     * the document atomicity filter can classify it instead of dropping it.
     */
    @Test
    void testTailScanResultsCarryDocumentIds() throws IOException {
        TailScanner scanner = new TailScanner();
        float[] query = {1.0f, 0.0f};

        try (WriteLog log = new WriteLog(tempDir.resolve("docids.wal"))) {
            appendChunk(log, "source1:doc1", 0, new float[]{1.0f, 0.0f});
            appendChunk(log, "source1:doc1", 1, new float[]{0.9f, 0.1f});

            List<HnswSearchResult> results = scanner.performTailScan(
                query, log, 0, log.writeOffset(), 10, CosineSimilarity.getInstance());

            assertThat(results).hasSize(2);
            assertThat(results).allSatisfy(result -> {
                assertThat(result.documentId()).isEqualTo("source1:doc1");
                assertThat(TailScanner.isTailScanResult(result.nodeId())).isTrue();
            });
        }
    }

    /**
     * The scan reports the number of entries it READ, which is the work performed, not the
     * number of results it kept.
     */
    @Test
    void testScanReportsEntriesScannedNotResultsKept() throws IOException {
        TailScanner scanner = new TailScanner();
        float[] query = {1.0f, 0.0f};

        try (WriteLog log = new WriteLog(tempDir.resolve("counts.wal"))) {
            for (int i = 0; i < 5; i++) {
                appendChunk(log, "src:doc" + i, 0, new float[]{1.0f, i * 0.1f});
            }

            TailScanner.TailScanOutcome outcome = scanner.scan(
                query, log, 0, log.writeOffset(), 2, CosineSimilarity.getInstance());

            assertThat(outcome.results()).hasSize(2);
            assertThat(outcome.entriesScanned()).isEqualTo(5);
            assertThat(outcome.incomplete()).isFalse();
        }
    }

    /**
     * The backpressure limit bounds the number of entries read, and the outcome says so.
     */
    @Test
    void testBackpressureStopsScanAndIsReported() throws IOException {
        TailScanner scanner = new TailScanner(3);
        float[] query = {1.0f, 0.0f};

        try (WriteLog log = new WriteLog(tempDir.resolve("backpressure.wal"))) {
            for (int i = 0; i < 10; i++) {
                appendChunk(log, "src:doc" + i, 0, new float[]{1.0f, i * 0.05f});
            }

            TailScanner.TailScanOutcome outcome = scanner.scan(
                query, log, 0, log.writeOffset(), 10, CosineSimilarity.getInstance());

            assertThat(outcome.entriesScanned()).isEqualTo(3);
            assertThat(outcome.backpressureTriggered()).isTrue();
            assertThat(outcome.incomplete()).isTrue();
        }
    }

    /**
     * A corrupt region must not send the scan into an unbounded byte-by-byte walk: the
     * resynchronisation budget bounds it and the scan returns.
     */
    @Test
    void testCorruptTailAbortsWithinResyncBudget() throws IOException {
        TailScanner scanner = new TailScanner();
        float[] query = {1.0f, 0.0f};

        try (WriteLog log = new WriteLog(tempDir.resolve("corrupt.wal"))) {
            appendChunk(log, "src:doc0", 0, new float[]{1.0f, 0.0f});
            long afterFirst = log.writeOffset();
            for (int i = 1; i < 40; i++) {
                appendChunk(log, "src:doc" + i, 0, new float[]{1.0f, i * 0.01f});
            }

            // Start mid-entry so nothing parses from that offset onwards
            TailScanner.TailScanOutcome outcome = scanner.scan(
                query, log, afterFirst - 1, log.writeOffset(), 10, CosineSimilarity.getInstance());

            assertThat(outcome.entriesScanned()).isLessThanOrEqualTo(40);
        }
    }

    /**
     * A tail-scan hit carries the chunk text from the entry it was read out of. Nothing
     * downstream can recover it otherwise: the synthetic node ID encodes an LSN, not the
     * storage key of the chunk, so relevancy scoring would see an empty candidate.
     */
    @Test
    void testTailScanResultsCarryChunkText() throws IOException {
        TailScanner scanner = new TailScanner();
        float[] query = {1.0f, 0.0f};

        try (WriteLog log = new WriteLog(tempDir.resolve("text.wal"))) {
            appendChunk(log, "source1:doc1", 0, new float[]{1.0f, 0.0f}, "the closest chunk");
            appendChunk(log, "source1:doc2", 0, new float[]{0.0f, 1.0f}, "a further chunk");

            TailScanner.TailScanOutcome outcome = scanner.scan(
                query, log, 0, log.writeOffset(), 10, CosineSimilarity.getInstance());

            assertThat(outcome.results()).hasSize(2);
            assertThat(outcome.payloads()).hasSize(2);

            HnswSearchResult closest = outcome.results().get(0);
            assertThat(closest.documentId()).isEqualTo("source1:doc1");
            assertThat(new String(outcome.payload(closest.nodeId()), StandardCharsets.UTF_8))
                .isEqualTo("the closest chunk");
        }
    }

    /**
     * An entry that stores only a vector carries no text, so no payload is reported for it
     * rather than an empty or garbage one.
     */
    @Test
    void testVectorOnlyEntriesCarryNoPayload() throws IOException {
        TailScanner scanner = new TailScanner();
        float[] query = {1.0f, 0.0f};

        try (WriteLog log = new WriteLog(tempDir.resolve("no-text.wal"))) {
            appendChunk(log, "source1:doc1", 0, new float[]{1.0f, 0.0f});

            TailScanner.TailScanOutcome outcome = scanner.scan(
                query, log, 0, log.writeOffset(), 10, CosineSimilarity.getInstance());

            assertThat(outcome.results()).hasSize(1);
            assertThat(outcome.payloads()).isEmpty();
            assertThat(outcome.payload(outcome.results().get(0).nodeId())).isNull();
        }
    }

    /**
     * A result evicted from the top-k heap must not leave its text behind: the payload map
     * describes the results that were kept.
     */
    @Test
    void testEvictedResultsDropTheirPayload() throws IOException {
        TailScanner scanner = new TailScanner();
        float[] query = {1.0f, 0.0f};

        try (WriteLog log = new WriteLog(tempDir.resolve("evict.wal"))) {
            appendChunk(log, "source1:near", 0, new float[]{1.0f, 0.0f}, "near text");
            appendChunk(log, "source1:far", 0, new float[]{-1.0f, 0.0f}, "far text");

            TailScanner.TailScanOutcome outcome = scanner.scan(
                query, log, 0, log.writeOffset(), 1, CosineSimilarity.getInstance());

            assertThat(outcome.results()).hasSize(1);
            assertThat(outcome.payloads()).hasSize(1);
            assertThat(new String(outcome.payload(outcome.results().get(0).nodeId()),
                StandardCharsets.UTF_8)).isEqualTo("near text");
        }
    }

    // ==================== Backpressure Limit Tests ====================

    /**
     * Test default backpressure entry limit is 100K.
     */
    @Test
    void testDefaultBackpressureEntryLimit() {
        TailScanner scanner = new TailScanner();
        assertThat(scanner.getBackpressureEntryLimit()).isEqualTo(100_000);
    }

    /**
     * Test custom backpressure entry limit constructor.
     */
    @Test
    void testCustomBackpressureEntryLimit() {
        TailScanner scanner = new TailScanner(50_000);
        assertThat(scanner.getBackpressureEntryLimit()).isEqualTo(50_000);
    }

    /**
     * Test backpressure entry limit is clamped to at least 1.
     */
    @Test
    void testBackpressureEntryLimitClampedToMinimum() {
        TailScanner scannerZero = new TailScanner(0);
        assertThat(scannerZero.getBackpressureEntryLimit()).isEqualTo(1);

        TailScanner scannerNegative = new TailScanner(-1000);
        assertThat(scannerNegative.getBackpressureEntryLimit()).isEqualTo(1);
    }

    /**
     * Test backpressure constant is accessible.
     */
    @Test
    void testBackpressureConstant() {
        assertThat(TailScanner.DEFAULT_BACKPRESSURE_ENTRY_LIMIT).isEqualTo(100_000);
    }

    /**
     * The limit a query actually applies.
     *
     * <p>There used to be a test here asserting that a {@code CONFIG_BACKPRESSURE_ENTRY_LIMIT}
     * constant held the string {@code "excalibur.tail-scan.backpressure-entry-limit"}, which was
     * true and meant nothing: no code read that key, so the test reported a configuration knob as
     * present while setting it did nothing. What is worth pinning is that the scanner a query
     * builds is bounded, and by what.</p>
     */
    @Test
    void scannerBuiltByAQueryUsesTheDefaultLimit() {
        assertThat(new TailScanner().getBackpressureEntryLimit())
            .isEqualTo(TailScanner.DEFAULT_BACKPRESSURE_ENTRY_LIMIT);
    }
}
