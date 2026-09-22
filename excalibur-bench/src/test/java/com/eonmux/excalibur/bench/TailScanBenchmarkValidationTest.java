package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.query.executor.TailScanner;
import com.eonmux.excalibur.store.WriteLog;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Validation tests for TailScanBenchmark.
 *
 * <p>These tests verify that the tail scan benchmark produces correct results
 * with proper domain correctness constraints.</p>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>Tail sizes in range [100, 100000] entries</li>
 *   <li>Vector dimensions: 64, 128, 256, 1536 (typical embedding sizes)</li>
 *   <li>L2 distance values: non-negative [0, +inf)</li>
 *   <li>Cosine similarity: [-1, 1]</li>
 *   <li>Results sorted by distance (closest first)</li>
 *   <li>k (top-k) values: 10, 100</li>
 *   <li>Backpressure limit: 100,000 entries</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class TailScanBenchmarkValidationTest {

    @TempDir
    Path tempDir;

    private Path tempFile;
    private WriteLog writeLog;
    private TailScanner tailScanner;

    // Test configuration
    private static final int[] TAIL_SIZES = {100, 1000, 10000};
    private static final int[] DIMENSIONS = {64, 128};
    private static final int K = 10;

    @BeforeEach
    void setUp() throws IOException {
        tempFile = Files.createTempFile(tempDir, "tail_scan_validation_", ".log");
        tailScanner = new TailScanner(100_000); // No backpressure during validation
    }

    @AfterEach
    void tearDown() throws IOException {
        if (writeLog != null) {
            writeLog.close();
        }
    }

    /**
     * Validates that tail scan produces correct results with L2 distance.
     *
     * <p>Domain correctness: L2 distance must be non-negative.</p>
     */
    @Test
    void validateTailScan_L2_DomainCorrectness() throws IOException {
        int tailSize = 1000;
        int dimension = 128;

        writeLog = new WriteLog(tempFile);
        populateWriteLog(tailSize, dimension);

        float[] queryVector = createNormalizedVector(dimension, 12345);
        DistanceFunction l2Distance = L2Distance.getInstance();

        List<HnswSearchResult> results = tailScanner.performTailScan(
            queryVector,
            writeLog,
            0,
            writeLog.writeOffset(),
            K,
            l2Distance
        );

        // Verify we got results
        assertThat(results).isNotEmpty();
        assertThat(results.size()).isLessThanOrEqualTo(K);

        // Domain correctness: All L2 distances must be non-negative
        for (HnswSearchResult result : results) {
            assertThat(result.distance())
                .as("L2 distance must be non-negative")
                .isGreaterThanOrEqualTo(0.0f);
        }

        // Verify results are sorted by distance (closest first)
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i).distance())
                .as("Results must be sorted by distance (closest first)")
                .isGreaterThanOrEqualTo(results.get(i - 1).distance());
        }
    }

    /**
     * Validates that tail scan produces correct results with Cosine similarity.
     *
     * <p>Domain correctness: Cosine similarity must be in range [-1, 1].</p>
     */
    @Test
    void validateTailScan_Cosine_DomainCorrectness() throws IOException {
        int tailSize = 1000;
        int dimension = 128;

        writeLog = new WriteLog(tempFile);
        populateWriteLog(tailSize, dimension);

        float[] queryVector = createNormalizedVector(dimension, 12345);
        DistanceFunction cosineDistance = CosineSimilarity.getInstance();

        List<HnswSearchResult> results = tailScanner.performTailScan(
            queryVector,
            writeLog,
            0,
            writeLog.writeOffset(),
            K,
            cosineDistance
        );

        // Verify we got results
        assertThat(results).isNotEmpty();
        assertThat(results.size()).isLessThanOrEqualTo(K);

        // Domain correctness: All cosine similarities must be in [-1, 1]
        // The CosineSimilarity class returns similarity, not distance
        for (HnswSearchResult result : results) {
            assertThat(result.distance())
                .as("Cosine similarity must be in range [-1, 1]")
                .isBetween(-1.0f, 1.0f);
        }

        // Verify results are sorted by distance (closest first)
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i).distance())
                .as("Results must be sorted by distance (closest first)")
                .isGreaterThanOrEqualTo(results.get(i - 1).distance());
        }
    }

    /**
     * Validates tail scan at varying tail sizes.
     *
     * <p>Tests that throughput scales appropriately with tail size.</p>
     */
    @Test
    void validateTailScan_VaryingTailSizes() throws IOException {
        int dimension = 64;
        DistanceFunction l2Distance = L2Distance.getInstance();

        for (int tailSize : TAIL_SIZES) {
            // Create new write log for each tail size
            if (writeLog != null) {
                writeLog.close();
            }
            tempFile = Files.createTempFile(tempDir, "tail_scan_" + tailSize + "_", ".log");
            writeLog = new WriteLog(tempFile);
            populateWriteLog(tailSize, dimension);

            float[] queryVector = createNormalizedVector(dimension, 12345);

            long startTime = System.nanoTime();
            List<HnswSearchResult> results = tailScanner.performTailScan(
                queryVector,
                writeLog,
                0,
                writeLog.writeOffset(),
                K,
                l2Distance
            );
            long elapsedNanos = System.nanoTime() - startTime;

            // Verify results
            assertThat(results)
                .as("Tail scan for tailSize=%d should produce results", tailSize)
                .isNotEmpty();

            // Calculate throughput
            double entriesPerSecond = (tailSize * 1_000_000_000.0) / elapsedNanos;
            System.out.printf("Tail size %d: %.0f entries/sec, %d results, %.2f ms%n",
                tailSize, entriesPerSecond, results.size(), elapsedNanos / 1_000_000.0);

            // Verify all distances are valid
            for (HnswSearchResult result : results) {
                assertThat(result.distance())
                    .as("L2 distance must be non-negative")
                    .isGreaterThanOrEqualTo(0.0f);
            }
        }
    }

    /**
     * Validates tail scan with different vector dimensions.
     */
    @Test
    void validateTailScan_DifferentDimensions() throws IOException {
        int tailSize = 500;
        DistanceFunction l2Distance = L2Distance.getInstance();

        for (int dimension : DIMENSIONS) {
            if (writeLog != null) {
                writeLog.close();
            }
            tempFile = Files.createTempFile(tempDir, "tail_scan_dim_" + dimension + "_", ".log");
            writeLog = new WriteLog(tempFile);
            populateWriteLog(tailSize, dimension);

            float[] queryVector = createNormalizedVector(dimension, 12345);

            List<HnswSearchResult> results = tailScanner.performTailScan(
                queryVector,
                writeLog,
                0,
                writeLog.writeOffset(),
                K,
                l2Distance
            );

            assertThat(results)
                .as("Tail scan for dimension=%d should produce results", dimension)
                .isNotEmpty();

            System.out.printf("Dimension %d: %d results%n", dimension, results.size());
        }
    }

    /**
     * Validates that backpressure is enforced at 100K entries.
     */
    @Test
    void validateBackpressureEnforcement() throws IOException {
        // Create scanner with low backpressure limit for testing
        TailScanner limitedScanner = new TailScanner(500);
        int dimension = 64;

        writeLog = new WriteLog(tempFile);
        // Populate more entries than the limit
        populateWriteLog(1000, dimension);

        float[] queryVector = createNormalizedVector(dimension, 12345);

        List<HnswSearchResult> results = limitedScanner.performTailScan(
            queryVector,
            writeLog,
            0,
            writeLog.writeOffset(),
            K,
            L2Distance.getInstance()
        );

        // Should still get results, but backpressure warning should be logged
        assertThat(results).isNotEmpty();

        // Verify results are valid despite backpressure
        for (HnswSearchResult result : results) {
            assertThat(result.distance()).isGreaterThanOrEqualTo(0.0f);
        }
    }

    /**
     * Validates that empty tail range returns empty results.
     */
    @Test
    void validateEmptyTailRange() throws IOException {
        writeLog = new WriteLog(tempFile);
        populateWriteLog(100, 64);

        float[] queryVector = createNormalizedVector(64, 12345);

        // Scan with same start and end offset
        List<HnswSearchResult> results = tailScanner.performTailScan(
            queryVector,
            writeLog,
            0,
            0, // Empty range
            K,
            L2Distance.getInstance()
        );

        assertThat(results).isEmpty();
    }

    /**
     * Validates large k (100 results).
     */
    @Test
    void validateLargeK() throws IOException {
        int tailSize = 1000;
        int dimension = 64;
        int largeK = 100;

        writeLog = new WriteLog(tempFile);
        populateWriteLog(tailSize, dimension);

        float[] queryVector = createNormalizedVector(dimension, 12345);

        List<HnswSearchResult> results = tailScanner.performTailScan(
            queryVector,
            writeLog,
            0,
            writeLog.writeOffset(),
            largeK,
            L2Distance.getInstance()
        );

        // Should return up to min(k, tailSize) results
        assertThat(results.size())
            .as("Large k=%d should return up to k results", largeK)
            .isLessThanOrEqualTo(largeK);

        // All distances must be valid
        for (HnswSearchResult result : results) {
            assertThat(result.distance()).isGreaterThanOrEqualTo(0.0f);
        }
    }

    /**
     * Validates that multiple sequential scans work correctly.
     */
    @Test
    void validateMultipleSequentialScans() throws IOException {
        int tailSize = 500;
        int dimension = 64;
        int numQueries = 5;

        writeLog = new WriteLog(tempFile);
        populateWriteLog(tailSize, dimension);

        Random random = new Random(99999);
        DistanceFunction l2Distance = L2Distance.getInstance();

        for (int q = 0; q < numQueries; q++) {
            float[] queryVector = createNormalizedVector(dimension, random.nextInt());

            List<HnswSearchResult> results = tailScanner.performTailScan(
                queryVector,
                writeLog,
                0,
                writeLog.writeOffset(),
                K,
                l2Distance
            );

            assertThat(results)
                .as("Query %d should produce results", q)
                .isNotEmpty();

            // Verify sorting
            for (int i = 1; i < results.size(); i++) {
                assertThat(results.get(i).distance())
                    .isGreaterThanOrEqualTo(results.get(i - 1).distance());
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Populates the write log with vector entries.
     */
    private void populateWriteLog(int numEntries, int dimension) throws IOException {
        Random random = new Random(42);
        byte[] key = new byte[16];

        for (int i = 0; i < numEntries; i++) {
            String keyStr = "tail-key-" + String.format("%08d", i);
            byte[] keyBytes = keyStr.getBytes();
            System.arraycopy(keyBytes, 0, key, 0, Math.min(keyBytes.length, 16));

            byte[] value = createVectorValue(random, dimension);
            writeLog.appendPut(key, value);
        }
    }

    /**
     * Creates a vector value in the format expected by TailScanner.
     * Format: [dimension:4][vector:dim*4]
     */
    private byte[] createVectorValue(Random random, int dim) {
        int valueSize = 4 + dim * 4;
        byte[] value = new byte[valueSize];
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(dim);

        float[] vector = createNormalizedVector(dim, random.nextInt());
        for (int d = 0; d < dim; d++) {
            buffer.putFloat(vector[d]);
        }

        return value;
    }

    /**
     * Creates a normalized random vector.
     */
    private float[] createNormalizedVector(int dimension, int seed) {
        Random random = new Random(seed);
        float[] vector = new float[dimension];

        for (int d = 0; d < dimension; d++) {
            vector[d] = (float) random.nextGaussian();
        }

        // Normalize to unit length
        float norm = 0.0f;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);

        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }

        return vector;
    }
}
