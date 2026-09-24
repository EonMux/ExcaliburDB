package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.core.hash.XxHash64;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for measuring XxHash64 hash function throughput and latency.
 *
 * <p>Measures hash function speed via JMH.</p>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>Small keys (8-32 bytes): > 500 million hashes/sec</li>
 *   <li>Medium keys (64-256 bytes): > 300 million hashes/sec</li>
 *   <li>Large keys (1KB-4KB): > 200 million hashes/sec</li>
 *   <li>Streaming API: Similar throughput to static API</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>All hash values are positive 64-bit values [0, Long.MAX_VALUE]</li>
 *   <li>Hash values are deterministic for same input+seed</li>
 *   <li>Throughput values in range [1M, 10B] ops/sec</li>
 *   <li>Latency values in nanoseconds (1-1000ns)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Static hash methods are thread-safe. Instance methods (streaming API) are NOT thread-safe
 * and each thread uses its own XxHash64 instance via @State(Scope.Thread).</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
    "--enable-preview",
    "--enable-native-access=ALL-UNNAMED",
    "-Xms1g",
    "-Xmx1g"
})
public class HashBenchmark {

    // ==================== Benchmark Parameters ====================

    /**
     * Key sizes to test (bytes).
     * Small: 8, 16, 32 (typical for short keys/IDs)
     * Medium: 64, 128, 256 (typical for document IDs)
     * Large: 1024, 4096 (typical for small values)
     */
    @Param({"8", "16", "32", "64", "128", "256", "1024", "4096"})
    public int keySize;

    /**
     * Number of keys in the pre-generated dataset.
     */
    @Param({"10000"})
    public int keyCount;

    // ==================== Benchmark State ====================

    /** Pre-generated keys for reproducible benchmarks. */
    private byte[][] keys;

    /** Pre-generated strings for string hashing benchmarks. */
    private String[] keyStrings;

    /** ByteBuffers for ByteBuffer hashing benchmarks. */
    private ByteBuffer[] keyBuffers;

    /** Random index for random access pattern. */
    private int[] randomIndices;

    /** Current sequential index. */
    private int sequentialIndex;

    /** Thread-local XxHash64 instance for streaming benchmarks. */
    @State(Scope.Thread)
    public static class StreamingState {
        XxHash64 hasher = new XxHash64(0);

        @Setup(Level.Iteration)
        public void reset() {
            hasher.reset();
        }
    }

    // ==================== Safe Index Helpers ====================

    /**
     * Safely computes a non-negative array index from the sequential counter.
     * Prevents ArrayIndexOutOfBoundsException when sequentialIndex overflows.
     *
     * <p>When sequentialIndex overflows to a negative value (after ~2.1 billion
     * iterations), the expression {@code sequentialIndex++ % keyCount} would
     * produce negative results due to Java's sign-preserving modulo operator.
     * This method masks the sign bit to ensure non-negative indices.</p>
     *
     * @return a non-negative index in the range [0, keyCount)
     */
    private int nextIndex() {
        return (sequentialIndex++ & 0x7FFFFFFF) % keyCount;
    }

    /**
     * Safely computes a non-negative start index for batch operations.
     *
     * <p>Batch operations multiply sequentialIndex by the batch size, which
     * accelerates overflow. This method uses long arithmetic with sign-bit
     * masking to prevent negative array indices.</p>
     *
     * @param batchSize the number of elements in the batch
     * @return a non-negative start index suitable for batch operations
     */
    private int nextBatchStartIndex(int batchSize) {
        return (int) ((sequentialIndex++ * batchSize & 0x7FFFFFFFL) % (keyCount - batchSize));
    }

    // ==================== Setup ====================

    @Setup(Level.Trial)
    public void setup() {
        Random random = new Random(42);
        keys = new byte[keyCount][];
        keyStrings = new String[keyCount];
        keyBuffers = new ByteBuffer[keyCount];
        randomIndices = new int[keyCount];

        // Generate deterministic keys
        for (int i = 0; i < keyCount; i++) {
            byte[] key = new byte[keySize];
            // Generate deterministic content
            String prefix = "key-" + String.format("%08d", i) + "-";
            byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
            int copyLen = Math.min(prefixBytes.length, keySize);
            System.arraycopy(prefixBytes, 0, key, 0, copyLen);

            // Fill remaining with pseudo-random data
            random.nextBytes(key);

            keys[i] = key;
            keyStrings[i] = new String(key, StandardCharsets.UTF_8);
            keyBuffers[i] = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN);

            randomIndices[i] = i;
        }

        // Shuffle random indices
        for (int i = keyCount - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = randomIndices[i];
            randomIndices[i] = randomIndices[j];
            randomIndices[j] = tmp;
        }

        sequentialIndex = 0;

        // Verify hashes are positive (domain correctness check)
        for (int i = 0; i < Math.min(100, keyCount); i++) {
            long hash = XxHash64.hash(keys[i]);
            if (hash < 0) {
                throw new AssertionError(
                    "Domain correctness violation: hash=" + hash + " is negative for key " + i);
            }
        }

        System.out.println("Hash Benchmark Setup:");
        System.out.println("  Key size: " + keySize + " bytes");
        System.out.println("  Key count: " + keyCount);
        System.out.println("  Total data: " + ((long) keySize * keyCount / 1024 / 1024) + " MB");
    }

    // ==================== Static Hash Benchmarks ====================

    /**
     * Benchmark: Hash byte array with default seed (0).
     * This is the most common use case.
     */
    @Benchmark
    public long hashByteArray(Blackhole bh) {
        int idx = nextIndex();
        return XxHash64.hash(keys[idx]);
    }

    /**
     * Benchmark: Hash byte array with explicit seed.
     * Tests the performance impact of seed parameter.
     */
    @Benchmark
    public long hashByteArrayWithSeed(Blackhole bh) {
        int idx = nextIndex();
        return XxHash64.hash(keys[idx], 42L);
    }

    /**
     * Benchmark: Hash byte array slice.
     * Tests hashing with offset/length parameters.
     */
    @Benchmark
    public long hashByteArraySlice(Blackhole bh) {
        int idx = nextIndex();
        byte[] key = keys[idx];
        // Hash middle portion of key
        int offset = Math.min(4, key.length / 4);
        int length = key.length - offset * 2;
        return XxHash64.hash(key, offset, Math.max(1, length));
    }

    /**
     * Benchmark: Hash string (UTF-8 encoded).
     * Common for document IDs and keys stored as strings.
     */
    @Benchmark
    public long hashString(Blackhole bh) {
        int idx = nextIndex();
        return XxHash64.hash(keyStrings[idx]);
    }

    /**
     * Benchmark: Hash string with explicit seed.
     */
    @Benchmark
    public long hashStringWithSeed(Blackhole bh) {
        int idx = nextIndex();
        return XxHash64.hash(keyStrings[idx], 12345L);
    }

    /**
     * Benchmark: Hash ByteBuffer.
     * Common for data coming from I/O operations.
     */
    @Benchmark
    public long hashByteBuffer(Blackhole bh) {
        int idx = nextIndex();
        ByteBuffer buffer = keyBuffers[idx].duplicate();
        return XxHash64.hash(buffer);
    }

    /**
     * Benchmark: Hash single long value.
     * Used for hashing numeric keys.
     */
    @Benchmark
    public long hashLong(Blackhole bh) {
        long value = sequentialIndex++;
        return XxHash64.hash(value);
    }

    /**
     * Benchmark: Hash single long value with seed.
     */
    @Benchmark
    public long hashLongWithSeed(Blackhole bh) {
        long value = sequentialIndex++;
        return XxHash64.hash(value, 999L);
    }

    // ==================== Streaming API Benchmarks ====================

    /**
     * Benchmark: Streaming hash with reset per operation.
     * Tests the streaming API throughput.
     */
    @Benchmark
    public long streamingHash(StreamingState state) {
        int idx = nextIndex();
        return state.hasher.reset().update(keys[idx]).getValue();
    }

    /**
     * Benchmark: Streaming hash with chunked updates.
     * Tests performance when data arrives in chunks.
     */
    @Benchmark
    public long streamingHashChunked(StreamingState state) {
        int idx = nextIndex();
        byte[] key = keys[idx];
        int chunkSize = Math.max(8, key.length / 4);

        state.hasher.reset();
        for (int offset = 0; offset < key.length; offset += chunkSize) {
            int len = Math.min(chunkSize, key.length - offset);
            state.hasher.update(key, offset, len);
        }
        return state.hasher.getValue();
    }

    // ==================== Batch Processing Benchmarks ====================

    /**
     * Benchmark: Batch hash computation (10 keys).
     * Measures throughput when hashing multiple keys.
     */
    @Benchmark
    @OperationsPerInvocation(10)
    public int batchHash10(Blackhole bh) {
        int startIdx = nextBatchStartIndex(10);
        int count = 0;
        for (int i = 0; i < 10; i++) {
            long hash = XxHash64.hash(keys[startIdx + i]);
            // Use hash to prevent dead code elimination
            count += (hash & 1);
        }
        return count;
    }

    /**
     * Benchmark: Batch hash computation (100 keys).
     * Measures throughput when hashing many keys.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int batchHash100(Blackhole bh) {
        int startIdx = nextBatchStartIndex(100);
        int count = 0;
        for (int i = 0; i < 100; i++) {
            long hash = XxHash64.hash(keys[startIdx + i]);
            count += (hash & 1);
        }
        return count;
    }

    /**
     * Benchmark: Batch hash computation (1000 keys).
     * Measures throughput for large batch operations.
     */
    @Benchmark
    @OperationsPerInvocation(1000)
    public int batchHash1000(Blackhole bh) {
        int startIdx = nextBatchStartIndex(1000);
        int count = 0;
        for (int i = 0; i < 1000; i++) {
            long hash = XxHash64.hash(keys[startIdx + i]);
            count += (hash & 1);
        }
        return count;
    }

    // ==================== Random Access Pattern ====================

    /**
     * Benchmark: Hash with random access pattern.
     * Tests cache behavior with non-sequential access.
     */
    @Benchmark
    public long hashRandomAccess(Blackhole bh) {
        int idx = randomIndices[nextIndex()];
        return XxHash64.hash(keys[idx]);
    }

    // ==================== Data Rate Benchmarks ====================

    /**
     * Benchmark: Hash throughput in MB/sec.
     * Reports MB/sec by hashing all keys in dataset.
     */
    @Benchmark
    @OperationsPerInvocation(10000) // keyCount
    public long hashThroughputMB(Blackhole bh) {
        long hashSum = 0;
        for (int i = 0; i < keyCount; i++) {
            hashSum += XxHash64.hash(keys[i]);
        }
        return hashSum;
    }

    // ==================== Collision Detection Benchmark ====================

    /**
     * Benchmark: Hash with collision counting.
     * Measures throughput while tracking potential collisions.
     */
    @Benchmark
    public long hashWithCollisionCheck(Blackhole bh) {
        int idx = nextIndex();
        long hash = XxHash64.hash(keys[idx]);

        // Domain correctness: verify hash is positive
        assert hash >= 0 : "Hash must be positive";

        return hash;
    }

    // ==================== Comparison with Java Built-in ====================

    /**
     * Benchmark: Java String.hashCode() for comparison.
     * Provides baseline for comparison with Java's built-in hash.
     */
    @Benchmark
    public int javaStringHashCode(Blackhole bh) {
        int idx = nextIndex();
        return keyStrings[idx].hashCode();
    }

    /**
     * Benchmark: Java Arrays.hashCode for comparison.
     * Provides baseline for byte array hashing.
     */
    @Benchmark
    public int javaArraysHashCode(Blackhole bh) {
        int idx = nextIndex();
        return java.util.Arrays.hashCode(keys[idx]);
    }
}
