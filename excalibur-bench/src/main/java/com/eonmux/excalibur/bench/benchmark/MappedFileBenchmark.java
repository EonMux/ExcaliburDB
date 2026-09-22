package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.core.mapped.MappedFile;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for MappedFile read/write throughput.
 *
 * <p>Measures throughput of memory-mapped I/O operations using Panama FFM
 * to validate the Phase 1 requirement for high-performance memory-mapped access.</p>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>Sequential reads: GB/sec throughput (memory bandwidth limited)</li>
 *   <li>Sequential writes: GB/sec throughput</li>
 *   <li>Random access: Millions of ops/sec</li>
 *   <li>Primitive operations (byte/int/long): Tens of millions of ops/sec</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>File sizes in range [4KB, 256MB] (realistic segment sizes)</li>
 *   <li>Access patterns: sequential and random (both common in database workloads)</li>
 *   <li>Buffer sizes: 64B to 64KB (covering cache line to page-aligned transfers)</li>
 *   <li>Throughput values should be in GB/sec range for sequential operations</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This is a JMH benchmark class. JMH handles all thread safety for benchmark
 * execution. The benchmark state is managed by JMH's @State annotations which
 * provide proper isolation between threads. MappedFile itself is thread-safe.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--enable-native-access=ALL-UNNAMED"})
public class MappedFileBenchmark {

    /**
     * File sizes to test (realistic segment sizes).
     */
    @Param({"65536", "1048576", "16777216"})  // 64KB, 1MB, 16MB
    public long fileSize;

    /**
     * Buffer sizes for bulk operations.
     */
    @Param({"64", "1024", "4096", "65536"})  // 64B, 1KB, 4KB, 64KB
    public int bufferSize;

    // Test file path
    private Path tempFile;

    // MappedFile instance
    private MappedFile mappedFile;

    // Test data
    private byte[] writeBuffer;
    private byte[] readBuffer;

    // Random offsets for random access tests
    private long[] randomOffsets;
    private int offsetIndex;

    // Test values for primitive operations
    private int testIntValue;
    private long testLongValue;
    private float testFloatValue;
    private float[] testFloatArray;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        // Create temp file
        tempFile = Files.createTempFile("mappedfile_bench_", ".bin");
        tempFile.toFile().deleteOnExit();

        // Create MappedFile with specified size
        mappedFile = MappedFile.create(tempFile, fileSize);

        // Initialize buffers
        Random random = new Random(42);
        writeBuffer = new byte[bufferSize];
        random.nextBytes(writeBuffer);
        readBuffer = new byte[bufferSize];

        // Pre-populate file with data for read tests
        long bytesWritten = 0;
        while (bytesWritten < fileSize) {
            int toWrite = (int) Math.min(bufferSize, fileSize - bytesWritten);
            mappedFile.write(bytesWritten, writeBuffer, 0, toWrite);
            bytesWritten += toWrite;
        }
        mappedFile.flush();

        // Generate random offsets for random access tests
        // Ensure we have enough for the benchmark iterations
        int numOffsets = 10000;
        randomOffsets = new long[numOffsets];
        long maxOffset = fileSize - bufferSize;
        for (int i = 0; i < numOffsets; i++) {
            randomOffsets[i] = Math.abs(random.nextLong() % maxOffset);
        }
        offsetIndex = 0;

        // Initialize test values
        testIntValue = random.nextInt();
        testLongValue = random.nextLong();
        testFloatValue = random.nextFloat();
        testFloatArray = new float[256];  // 1KB of floats
        for (int i = 0; i < testFloatArray.length; i++) {
            testFloatArray[i] = random.nextFloat();
        }

        // Log benchmark setup
        System.out.println("MappedFile Benchmark Setup:");
        System.out.println("  File path: " + tempFile);
        System.out.println("  File size: " + (fileSize / 1024) + " KB");
        System.out.println("  Buffer size: " + bufferSize + " bytes");
        System.out.println("  Mapped size: " + mappedFile.size() + " bytes");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (mappedFile != null) {
            mappedFile.close();
        }
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        }
    }

    // ==================== SEQUENTIAL READ BENCHMARKS ====================

    /**
     * Benchmark sequential byte array reads.
     * Measures throughput in bytes/second.
     */
    @Benchmark
    public int sequentialRead_bytes(Blackhole bh) {
        long offset = 0;
        int totalBytes = 0;

        while (offset + bufferSize <= fileSize) {
            int read = mappedFile.read(offset, readBuffer);
            bh.consume(readBuffer);
            totalBytes += read;
            offset += bufferSize;
        }

        return totalBytes;
    }

    /**
     * Benchmark sequential int reads.
     * Measures ops/sec reading 4-byte integers sequentially.
     */
    @Benchmark
    public int sequentialRead_ints(Blackhole bh) {
        int count = 0;
        long maxOffset = fileSize - 4;

        // Read 1000 ints per iteration
        for (int i = 0; i < 1000; i++) {
            long offset = (i * 4L) % maxOffset;
            int value = mappedFile.readInt(offset);
            bh.consume(value);
            count++;
        }

        return count;
    }

    /**
     * Benchmark sequential long reads.
     * Measures ops/sec reading 8-byte longs sequentially.
     */
    @Benchmark
    public int sequentialRead_longs(Blackhole bh) {
        int count = 0;
        long maxOffset = fileSize - 8;

        // Read 1000 longs per iteration
        for (int i = 0; i < 1000; i++) {
            long offset = (i * 8L) % maxOffset;
            long value = mappedFile.readLong(offset);
            bh.consume(value);
            count++;
        }

        return count;
    }

    /**
     * Benchmark sequential float array reads.
     * Measures throughput reading float vectors (common for embeddings).
     */
    @Benchmark
    public int sequentialRead_floatArray(Blackhole bh) {
        int count = 0;
        long offset = 0;
        int floatCount = 256;  // 1KB of floats
        long stride = floatCount * 4L;
        long maxOffset = fileSize - stride;

        // Read 100 float arrays per iteration
        for (int i = 0; i < 100; i++) {
            offset = (i * stride) % maxOffset;
            float[] values = mappedFile.readFloatArray(offset, floatCount);
            bh.consume(values);
            count++;
        }

        return count;
    }

    // ==================== SEQUENTIAL WRITE BENCHMARKS ====================

    /**
     * Benchmark sequential byte array writes.
     * Measures throughput in bytes/second.
     */
    @Benchmark
    public int sequentialWrite_bytes(Blackhole bh) {
        long offset = 0;
        int totalBytes = 0;

        while (offset + bufferSize <= fileSize) {
            int written = mappedFile.write(offset, writeBuffer);
            bh.consume(written);
            totalBytes += written;
            offset += bufferSize;
        }

        return totalBytes;
    }

    /**
     * Benchmark sequential int writes.
     * Measures ops/sec writing 4-byte integers sequentially.
     */
    @Benchmark
    public int sequentialWrite_ints(Blackhole bh) {
        int count = 0;
        long maxOffset = fileSize - 4;

        // Write 1000 ints per iteration
        for (int i = 0; i < 1000; i++) {
            long offset = (i * 4L) % maxOffset;
            mappedFile.writeInt(offset, testIntValue);
            count++;
        }

        return count;
    }

    /**
     * Benchmark sequential long writes.
     * Measures ops/sec writing 8-byte longs sequentially.
     */
    @Benchmark
    public int sequentialWrite_longs(Blackhole bh) {
        int count = 0;
        long maxOffset = fileSize - 8;

        // Write 1000 longs per iteration
        for (int i = 0; i < 1000; i++) {
            long offset = (i * 8L) % maxOffset;
            mappedFile.writeLong(offset, testLongValue);
            count++;
        }

        return count;
    }

    /**
     * Benchmark sequential float array writes.
     * Measures throughput writing float vectors (common for embeddings).
     */
    @Benchmark
    public int sequentialWrite_floatArray(Blackhole bh) {
        int count = 0;
        long offset = 0;
        long stride = testFloatArray.length * 4L;
        long maxOffset = fileSize - stride;

        // Write 100 float arrays per iteration
        for (int i = 0; i < 100; i++) {
            offset = (i * stride) % maxOffset;
            mappedFile.writeFloatArray(offset, testFloatArray);
            count++;
        }

        return count;
    }

    // ==================== RANDOM ACCESS BENCHMARKS ====================

    /**
     * Benchmark random byte array reads.
     * Simulates random lookups in a segment.
     */
    @Benchmark
    public int randomRead_bytes(Blackhole bh) {
        int idx = offsetIndex++;
        if (idx >= randomOffsets.length) {
            offsetIndex = 0;
            idx = 0;
        }

        long offset = randomOffsets[idx];
        int read = mappedFile.read(offset, readBuffer, 0, Math.min(bufferSize, (int)(fileSize - offset)));
        bh.consume(readBuffer);
        return read;
    }

    /**
     * Benchmark random int reads.
     * Simulates random 4-byte value lookups (e.g., hash values).
     */
    @Benchmark
    public int randomRead_int() {
        int idx = offsetIndex++;
        if (idx >= randomOffsets.length) {
            offsetIndex = 0;
            idx = 0;
        }

        long offset = randomOffsets[idx] % (fileSize - 4);
        return mappedFile.readInt(offset);
    }

    /**
     * Benchmark random long reads.
     * Simulates random 8-byte value lookups (e.g., keys, pointers).
     */
    @Benchmark
    public long randomRead_long() {
        int idx = offsetIndex++;
        if (idx >= randomOffsets.length) {
            offsetIndex = 0;
            idx = 0;
        }

        long offset = randomOffsets[idx] % (fileSize - 8);
        return mappedFile.readLong(offset);
    }

    /**
     * Benchmark random byte array writes.
     * Simulates random updates in a segment.
     */
    @Benchmark
    public int randomWrite_bytes(Blackhole bh) {
        int idx = offsetIndex++;
        if (idx >= randomOffsets.length) {
            offsetIndex = 0;
            idx = 0;
        }

        long offset = randomOffsets[idx];
        int written = mappedFile.write(offset, writeBuffer, 0, Math.min(bufferSize, (int)(fileSize - offset)));
        bh.consume(written);
        return written;
    }

    /**
     * Benchmark random int writes.
     * Simulates random 4-byte value updates.
     */
    @Benchmark
    public int randomWrite_int() {
        int idx = offsetIndex++;
        if (idx >= randomOffsets.length) {
            offsetIndex = 0;
            idx = 0;
        }

        long offset = randomOffsets[idx] % (fileSize - 4);
        mappedFile.writeInt(offset, testIntValue);
        return testIntValue;
    }

    /**
     * Benchmark random long writes.
     * Simulates random 8-byte value updates.
     */
    @Benchmark
    public long randomWrite_long() {
        int idx = offsetIndex++;
        if (idx >= randomOffsets.length) {
            offsetIndex = 0;
            idx = 0;
        }

        long offset = randomOffsets[idx] % (fileSize - 8);
        mappedFile.writeLong(offset, testLongValue);
        return testLongValue;
    }

    // ==================== MIXED WORKLOAD BENCHMARKS ====================

    /**
     * Benchmark mixed read/write operations.
     * Simulates realistic database workload with 80% reads, 20% writes.
     */
    @Benchmark
    public int mixedReadWrite(Blackhole bh) {
        int idx = offsetIndex++;
        if (idx >= randomOffsets.length) {
            offsetIndex = 0;
            idx = 0;
        }

        long offset = randomOffsets[idx] % (fileSize - bufferSize);

        // 80% reads, 20% writes
        if (idx % 5 != 0) {
            // Read
            int read = mappedFile.read(offset, readBuffer, 0, bufferSize);
            bh.consume(readBuffer);
            return read;
        } else {
            // Write
            int written = mappedFile.write(offset, writeBuffer);
            bh.consume(written);
            return written;
        }
    }

    /**
     * Benchmark round-trip: write then read at same location.
     * Measures latency of write-read cycle.
     */
    @Benchmark
    public byte[] roundTrip_writeRead() {
        int idx = offsetIndex++;
        if (idx >= randomOffsets.length) {
            offsetIndex = 0;
            idx = 0;
        }

        long offset = randomOffsets[idx] % (fileSize - bufferSize);

        // Write
        mappedFile.write(offset, writeBuffer);

        // Read back
        mappedFile.read(offset, readBuffer);

        return readBuffer;
    }

    // ==================== SLICE OPERATIONS ====================

    /**
     * Benchmark slice creation (zero-copy view).
     * Measures overhead of creating MemorySegment slices.
     */
    @Benchmark
    public Object slice_create(Blackhole bh) {
        int idx = offsetIndex++;
        if (idx >= randomOffsets.length) {
            offsetIndex = 0;
            idx = 0;
        }

        long offset = randomOffsets[idx] % (fileSize - bufferSize);
        var slice = mappedFile.slice(offset, bufferSize);
        bh.consume(slice);
        return slice;
    }

    // ==================== STATISTICS OPERATIONS ====================

    /**
     * Benchmark statistics retrieval.
     * Measures overhead of tracking read/write statistics.
     */
    @Benchmark
    public long statistics_bytesTransferred() {
        return mappedFile.bytesRead() + mappedFile.bytesWritten();
    }
}
