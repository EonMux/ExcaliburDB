package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.query.executor.TailScanner;
import com.eonmux.excalibur.store.WriteLog;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for tail scan brute-force throughput.
 *
 * <p>Measures the throughput of brute-force scanning over write log entries
 * that haven't yet been integrated into the HNSW graph.</p>
 *
 * <h2>Performance Target</h2>
 * <p>Tail scan should process entries at high throughput (millions/sec) since
 * it's a simple linear scan with distance computation. One operation is one scan of the
 * whole tail, so the per-entry rate is the score times {@code tailSize}; the target registry
 * gates it in entries/sec on that basis.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Tail sizes in range [100, 100000] entries</li>
 *   <li>Vector dimensions: 64, 128, 256 (typical embedding sizes)</li>
 *   <li>Distance values in valid ranges (cosine ∈ [-1,1], L2 ∈ [0,∞))</li>
 *   <li>k (top-k) values: 10, 100</li>
 *   <li>Backpressure limit: 100,000 entries</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>JMH handles thread safety for benchmark execution. The TailScanner being tested
 * is thread-safe for read operations.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
    "--enable-preview",
    "--enable-native-access=ALL-UNNAMED",
    "--add-modules=jdk.incubator.vector",
    "-Xms2g",
    "-Xmx2g"
})
public class TailScanBenchmark {

    /**
     * Number of entries in the tail to scan.
     * Range covers small to large tail sizes.
     */
    @Param({"100", "1000", "10000", "50000"})
    public int tailSize;

    /**
     * Vector dimensions to test (typical embedding sizes).
     */
    @Param({"64", "128"})
    public int dimension;

    /**
     * Top-k results to retrieve.
     */
    @Param({"10"})
    public int k;

    // Benchmark state
    private Path tempFile;
    private WriteLog writeLog;
    private TailScanner tailScanner;
    private DistanceFunction distanceFunction;
    private float[] queryVector;
    private long startOffset;
    private long endOffset;

    @Setup(Level.Trial)
    public void setup() throws IOException {

        // Create temp file for write log
        tempFile = Files.createTempFile("tail_scan_bench_", ".log");

        // Create write log
        // Each entry: ~50 bytes header + key + vector (4 + dim*4)
        // The WriteLog will automatically grow as needed
        writeLog = new WriteLog(tempFile);

        // Create tail scanner
        tailScanner = new TailScanner(tailSize * 2); // No backpressure during benchmark

        // Use L2 distance
        distanceFunction = L2Distance.getInstance();

        // Populate write log with entries
        Random random = new Random(42);
        byte[] key = new byte[16];

        System.out.println("Populating write log with " + tailSize + " entries...");

        for (int i = 0; i < tailSize; i++) {
            // Create key
            String keyStr = "tail-key-" + String.format("%08d", i);
            byte[] keyBytes = keyStr.getBytes();
            System.arraycopy(keyBytes, 0, key, 0, Math.min(keyBytes.length, 16));

            // Create vector value: [dimension:4][vector:dim*4]
            byte[] value = createVectorValue(random, dimension);

            // Append to write log
            writeLog.appendPut(key, value);
        }

        // Record offsets for tail scan
        startOffset = 0;
        endOffset = writeLog.writeOffset();

        // Create query vector
        queryVector = new float[dimension];
        random = new Random(123); // Different seed for query
        for (int d = 0; d < dimension; d++) {
            queryVector[d] = (float) random.nextGaussian();
        }
        normalizeVector(queryVector);

        System.out.println("Tail Scan Benchmark Setup:");
        System.out.println("  Tail size: " + tailSize + " entries");
        System.out.println("  Dimension: " + dimension);
        System.out.println("  Top-k: " + k);
        System.out.println("  Write log size: " + (endOffset / 1024) + " KB");
        System.out.println("  Start offset: " + startOffset);
        System.out.println("  End offset: " + endOffset);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (writeLog != null) {
            writeLog.close();
        }
        if (tempFile != null) {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Benchmark: Full tail scan with L2 distance.
     * Measures throughput of scanning all entries and computing distances.
     */
    @Benchmark
    public List<HnswSearchResult> tailScan_l2(Blackhole bh) {
        return tailScanner.performTailScan(
            queryVector,
            writeLog,
            startOffset,
            endOffset,
            k,
            distanceFunction
        );
    }

    /**
     * Benchmark: Full tail scan with cosine similarity.
     * Measures throughput with cosine distance function.
     */
    @Benchmark
    public List<HnswSearchResult> tailScan_cosine(Blackhole bh) {
        return tailScanner.performTailScan(
            queryVector,
            writeLog,
            startOffset,
            endOffset,
            k,
            CosineSimilarity.getInstance()
        );
    }

    /**
     * Benchmark: Tail scan with larger k (100 results).
     * Measures throughput when retrieving more results.
     */
    @Benchmark
    @OperationsPerInvocation(10)
    public int tailScan_largeK(Blackhole bh) {
        int totalResults = 0;
        for (int i = 0; i < 10; i++) {
            List<HnswSearchResult> results = tailScanner.performTailScan(
                queryVector,
                writeLog,
                startOffset,
                endOffset,
                100, // Larger k
                distanceFunction
            );
            totalResults += results.size();
        }
        return totalResults;
    }

    /**
     * Benchmark: Multiple sequential tail scans.
     * Simulates the scenario of multiple queries scanning the same tail.
     */
    @Benchmark
    @OperationsPerInvocation(5)
    public int tailScan_multipleQueries(Blackhole bh) {
        int totalResults = 0;
        Random random = new Random(456);

        for (int q = 0; q < 5; q++) {
            // Create different query vector each time
            float[] query = new float[dimension];
            for (int d = 0; d < dimension; d++) {
                query[d] = (float) random.nextGaussian();
            }
            normalizeVector(query);

            List<HnswSearchResult> results = tailScanner.performTailScan(
                query,
                writeLog,
                startOffset,
                endOffset,
                k,
                distanceFunction
            );
            totalResults += results.size();
        }
        return totalResults;
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a vector value in the format expected by TailScanner.
     * Format: [dimension:4][vector:dim*4]
     */
    private byte[] createVectorValue(Random random, int dim) {
        int valueSize = 4 + dim * 4; // dimension field + vector data
        byte[] value = new byte[valueSize];
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);

        // Write dimension
        buffer.putInt(dim);

        // Write normalized random vector
        float[] vector = new float[dim];
        for (int d = 0; d < dim; d++) {
            vector[d] = (float) random.nextGaussian();
        }
        normalizeVector(vector);

        for (int d = 0; d < dim; d++) {
            buffer.putFloat(vector[d]);
        }

        return value;
    }

    /**
     * Normalizes a vector to unit length.
     */
    private void normalizeVector(float[] vector) {
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
    }
}
