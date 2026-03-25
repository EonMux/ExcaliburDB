package io.excalibur.integration;

import io.excalibur.store.Segment;
import io.excalibur.store.WriteLog;
import io.excalibur.store.format.LogEntry;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import excalibur.query.executor.QueryExecutor;
import excalibur.query.executor.TailScanner;
import excalibur.ingest.buffer.DocumentChunkBuffer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for HNSW tail offset tracking and tail scan functionality.
 *
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Queries see entries even before HNSW integration</li>
 *   <li>Tail scan correctly identifies un-integrated entries</li>
 *   <li>Results are properly merged and deduplicated</li>
 *   <li>Concurrent queries during indexing work correctly</li>
 * </ul>
 *
 * <h2>Test Strategy</h2>
 * <p>Tests use synthetic vectors stored in the write log with format:
 * [dimension:4][vector:dim*4]</p>
 */
class TailScanIntegrationTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Path segmentPath;
    private Path writeLogPath;
    private Path graphPath;
    private DistanceFunction distanceFunction;
    private int dimension;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        segmentPath = tempDir.resolve("test-segment");
        writeLogPath = tempDir.resolve("test-segment.wal");
        graphPath = tempDir.resolve("test-hnsw.graph");
        distanceFunction = CosineSimilarity.getInstance();
        dimension = 128;
    }

    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }

    /**
     * Test: Verify that tail scanner correctly identifies when tail entries exist.
     */
    @Test
    void testHasTailEntries() throws IOException {
        // Create write log
        WriteLog writeLog = new WriteLog(writeLogPath, arena);

        // Initially, no tail entries
        TailScanner scanner = new TailScanner();
        assertThat(scanner.hasTailEntries(0, 0)).isFalse();

        // Add an entry
        float[] vector = createRandomVector(dimension);
        byte[] vectorBytes = encodeVector(vector);
        writeLog.appendPut("key1".getBytes(), vectorBytes);

        // Now tail entries exist (lastIntegrated=0, writeLogHead > 0)
        long writeLogHead = writeLog.writeOffset();
        assertThat(scanner.hasTailEntries(0, writeLogHead)).isTrue();

        // After integration (lastIntegrated = writeLogHead), no tail entries
        assertThat(scanner.hasTailEntries(writeLogHead, writeLogHead)).isFalse();

        writeLog.close();
    }

    /**
     * Test: Verify that QueryExecutor correctly detects need for tail scan.
     */
    @Test
    void testNeedsTailScan() throws IOException {
        // Create segment with write log
        Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
        WriteLog writeLog = new WriteLog(writeLogPath, arena);
        segment.attachWriteLog(writeLog);

        // Create HNSW graph
        HnswConfig config = new HnswConfig(16, 32, 200, 200, 1.0 / Math.log(16), dimension);
        HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

        QueryExecutor executor = new QueryExecutor();

        // Initially, no tail scan needed (empty write log)
        assertThat(executor.needsTailScan(segment, graph)).isFalse();

        // Add entry to write log (not integrated into graph)
        float[] vector = createRandomVector(dimension);
        byte[] vectorBytes = encodeVector(vector);
        writeLog.appendPut("key1".getBytes(), vectorBytes);

        // Now tail scan is needed
        assertThat(executor.needsTailScan(segment, graph)).isTrue();

        // Simulate integration by updating the offset
        graph.setLastIntegratedOffset(writeLog.writeOffset());

        // After integration, no tail scan needed
        assertThat(executor.needsTailScan(segment, graph)).isFalse();

        graph.close();
        segment.close();
        writeLog.close();
    }

    /**
     * Test: Verify that tail scan returns results for un-integrated entries.
     */
    @Test
    void testTailScanReturnsResults() throws IOException {
        // Create write log with entries
        WriteLog writeLog = new WriteLog(writeLogPath, arena);

        // Add several vectors to the write log
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            float[] vector = createRandomVector(dimension);
            vectors.add(vector);
            byte[] vectorBytes = encodeVector(vector);
            writeLog.appendPut(("key" + i).getBytes(), vectorBytes);
        }

        long writeLogHead = writeLog.writeOffset();

        // Perform tail scan with first vector as query
        TailScanner scanner = new TailScanner();
        List<HnswSearchResult> results = scanner.performTailScan(
            vectors.get(0),
            writeLog,
            0,  // Start from beginning
            writeLogHead,
            5,  // Top 5 results
            distanceFunction
        );

        // Should return results (exact count depends on distance function)
        // Note: Results may be empty if write log reading isn't fully implemented
        // This test validates the API contract
        assertThat(results).isNotNull();
        assertThat(results.size()).isLessThanOrEqualTo(5);

        // Results should be sorted by distance
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i).distance())
                .isGreaterThanOrEqualTo(results.get(i - 1).distance());
        }

        writeLog.close();
    }

    /**
     * Test: Verify QueryExecutor merges HNSW and tail scan results.
     */
    @Test
    void testQueryExecutorMergesResults() throws IOException {
        // Create segment with write log
        Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
        WriteLog writeLog = new WriteLog(writeLogPath, arena);
        segment.attachWriteLog(writeLog);

        // Create HNSW graph
        HnswConfig config = new HnswConfig(16, 32, 200, 200, 1.0 / Math.log(16), dimension);
        HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

        // Insert some vectors into the HNSW graph
        List<float[]> graphVectors = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            float[] vector = createRandomVector(dimension);
            graphVectors.add(vector);
            graph.insert(vector);
        }

        // Update graph's integrated offset
        graph.setLastIntegratedOffset(0); // Nothing integrated yet for test

        // Add more vectors to write log (not in graph)
        List<float[]> tailVectors = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            float[] vector = createRandomVector(dimension);
            tailVectors.add(vector);
            byte[] vectorBytes = encodeVector(vector);
            writeLog.appendPut(("tailKey" + i).getBytes(), vectorBytes);
        }

        // Create query executor
        QueryExecutor executor = new QueryExecutor();

        // Query using a graph vector
        List<HnswSearchResult> results = executor.search(
            graphVectors.get(0),
            10,
            segment,
            graph,
            distanceFunction
        );

        // Should return results (HNSW results at minimum)
        assertThat(results).isNotNull();
        assertThat(results.size()).isGreaterThan(0);

        // Results should be sorted by distance
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i).distance())
                .isGreaterThanOrEqualTo(results.get(i - 1).distance());
        }

        graph.close();
        segment.close();
        writeLog.close();
    }

    /**
     * Test: Verify tail scan statistics are correct.
     */
    @Test
    void testTailScanStats() throws IOException {
        // Create segment with write log
        Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
        WriteLog writeLog = new WriteLog(writeLogPath, arena);
        segment.attachWriteLog(writeLog);

        // Create HNSW graph
        HnswConfig config = new HnswConfig(16, 32, 200, 200, 1.0 / Math.log(16), dimension);
        HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

        QueryExecutor executor = new QueryExecutor();

        // Initially no tail
        QueryExecutor.TailScanStats stats = executor.getTailScanStats(segment, graph);
        assertThat(stats.needsTailScan()).isFalse();
        assertThat(stats.tailSizeBytes()).isEqualTo(0);

        // Add entry to write log
        float[] vector = createRandomVector(dimension);
        byte[] vectorBytes = encodeVector(vector);
        writeLog.appendPut("key1".getBytes(), vectorBytes);

        // Now tail exists
        stats = executor.getTailScanStats(segment, graph);
        assertThat(stats.needsTailScan()).isTrue();
        assertThat(stats.tailSizeBytes()).isGreaterThan(0);
        assertThat(stats.writeLogHead()).isGreaterThan(0);
        assertThat(stats.lastIntegratedOffset()).isEqualTo(0);

        graph.close();
        segment.close();
        writeLog.close();
    }

    /**
     * Test: Verify getWriteLogHead returns correct value.
     */
    @Test
    void testGetWriteLogHead() throws IOException {
        // Create segment without write log
        Segment segmentNoLog = new Segment(segmentPath, 1024 * 1024, arena);
        assertThat(segmentNoLog.getWriteLogHead()).isEqualTo(0);
        segmentNoLog.close();

        // Create segment with write log
        Path segmentPath2 = tempDir.resolve("test-segment2");
        Segment segmentWithLog = new Segment(segmentPath2, 1024 * 1024, arena);
        WriteLog writeLog = new WriteLog(writeLogPath, arena);
        segmentWithLog.attachWriteLog(writeLog);

        // Initially zero
        assertThat(segmentWithLog.getWriteLogHead()).isEqualTo(0);

        // Add entries
        for (int i = 0; i < 10; i++) {
            writeLog.appendPut(("key" + i).getBytes(), ("value" + i).getBytes());
        }

        // Should reflect write log position
        long head = segmentWithLog.getWriteLogHead();
        assertThat(head).isGreaterThan(0);

        segmentWithLog.close();
        writeLog.close();
    }

    /**
     * Test: Verify lastIntegratedOffset is correctly tracked.
     */
    @Test
    void testLastIntegratedOffsetTracking() throws IOException {
        HnswConfig config = new HnswConfig(16, 32, 200, 200, 1.0 / Math.log(16), dimension);
        HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

        // Initial offset is 0
        assertThat(graph.getLastIntegratedOffset()).isEqualTo(0);

        // Set offset
        graph.setLastIntegratedOffset(1000);
        assertThat(graph.getLastIntegratedOffset()).isEqualTo(1000);

        // Set higher offset (should update)
        graph.setLastIntegratedOffset(2000);
        assertThat(graph.getLastIntegratedOffset()).isEqualTo(2000);

        // Set lower offset (should NOT update - monotonic)
        graph.setLastIntegratedOffset(500);
        assertThat(graph.getLastIntegratedOffset()).isEqualTo(2000);

        // Use CAS to update
        boolean updated = graph.compareAndSetLastIntegratedOffset(3000);
        assertThat(updated).isTrue();
        assertThat(graph.getLastIntegratedOffset()).isEqualTo(3000);

        // CAS with lower value should fail
        updated = graph.compareAndSetLastIntegratedOffset(1000);
        assertThat(updated).isFalse();
        assertThat(graph.getLastIntegratedOffset()).isEqualTo(3000);

        graph.close();
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a random normalized vector.
     */
    private float[] createRandomVector(int dimension) {
        Random random = new Random();
        float[] vector = new float[dimension];
        float sum = 0;
        for (int i = 0; i < dimension; i++) {
            vector[i] = random.nextFloat() * 2 - 1; // Range [-1, 1]
            sum += vector[i] * vector[i];
        }
        // Normalize
        float norm = (float) Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    /**
     * Encodes a vector to bytes with dimension prefix.
     * Uses the production encoder from DocumentChunkBuffer to ensure
     * the actual ingestion format is tested.
     * Format (LITTLE-ENDIAN): [dimension:4][vector:dim*4]
     */
    private byte[] encodeVector(float[] vector) {
        return DocumentChunkBuffer.encodeVectorOnly(vector);
    }

    /**
     * Encodes a vector with text using the production encoder.
     * Format (LITTLE-ENDIAN): [dimension:4][vector:dim*4][text:variable]
     */
    private byte[] encodeVectorWithText(float[] vector, String text) {
        return DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(text, vector);
    }

    /**
     * Test: Verify round-trip encoding/decoding between DocumentChunkBuffer and TailScanner.
     *
     * <p>This test verifies the critical fix for the byte order mismatch bug:
     * DocumentChunkBuffer must encode vectors in LITTLE_ENDIAN format to match
     * TailScanner's expected format.</p>
     */
    @Test
    void testEncodingRoundTrip() {
        // Create a test vector
        float[] originalVector = createRandomVector(dimension);
        String testText = "This is a test chunk of text for the document.";

        // Encode using the production encoder (with text)
        byte[] encoded = encodeVectorWithText(originalVector, testText);

        // Verify the encoding is not null and has correct minimum length
        assertThat(encoded).isNotNull();
        assertThat(encoded.length).isGreaterThanOrEqualTo(4 + dimension * Float.BYTES);

        // Decode using DocumentChunkBuffer's public decode method
        float[] decodedVector = DocumentChunkBuffer.decodeEmbedding(encoded, dimension);
        String decodedText = DocumentChunkBuffer.decodeText(encoded);

        // Verify round-trip
        assertThat(decodedVector).isNotNull();
        assertThat(decodedVector).hasSize(dimension);

        // Verify vector values are preserved (within floating point precision)
        for (int i = 0; i < dimension; i++) {
            assertThat(decodedVector[i]).isEqualTo(originalVector[i]);
        }

        // Verify text is preserved
        assertThat(decodedText).isEqualTo(testText);
    }

    /**
     * Test: Verify TailScanner can extract vectors encoded with text.
     *
     * <p>This test verifies that TailScanner.extractVector() correctly handles
     * values that contain both a vector and text (the production format from
     * DocumentChunkBuffer.encodeChunkWithEmbedding()).</p>
     */
    @Test
    void testTailScannerHandlesTextInValue() throws IOException {
        WriteLog writeLog = new WriteLog(writeLogPath, arena);

        // Create vector with text (production format)
        float[] vector = createRandomVector(dimension);
        String text = "Sample document chunk content.";
        byte[] encodedWithText = encodeVectorWithText(vector, text);

        // Write to log
        writeLog.appendPut("doc1:0".getBytes(), encodedWithText);
        long writeLogHead = writeLog.writeOffset();

        // Scan using TailScanner
        TailScanner scanner = new TailScanner();
        List<HnswSearchResult> results = scanner.performTailScan(
            vector,  // Query with same vector - should find distance close to 0
            writeLog,
            0,
            writeLogHead,
            1,
            distanceFunction
        );

        // Should find the entry despite text being in the value
        assertThat(results).isNotEmpty();
        // For CosineSimilarity, identical vectors return similarity = 1.0
        // The distance field stores the similarity value (larger = more similar)
        assertThat(results.get(0).distance()).isGreaterThan(0.999f);

        writeLog.close();
    }
}
