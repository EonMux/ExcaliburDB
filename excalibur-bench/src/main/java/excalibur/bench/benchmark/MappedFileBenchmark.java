package excalibur.bench.benchmark;

import io.excalibur.core.mapped.MappedFile;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for MappedFile I/O performance.
 *
 * <p>Measures throughput of memory-mapped file operations to validate
 * the Phase 1 requirement for high-performance I/O using Panama FFM.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--release", "21"})
public class MappedFileBenchmark {

    @Param({"1024", "4096", "16384", "65536", "262144", "1048576"})
    public int fileSize;

    private Path tempFile;
    private MappedFile mappedFile;
    private byte[] writeData;
    private byte[] readBuffer;
    private long[] offsets;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        // Create temp file
        tempFile = Files.createTempFile("mappedfile_bench_", ".bin");

        // Write initial data
        Random random = new Random(42);
        writeData = new byte[fileSize];
        random.nextBytes(writeData);
        Files.write(tempFile, writeData);

        // Open mapped file
        mappedFile = MappedFile.openReadWrite(tempFile);

        // Prepare read buffer
        readBuffer = new byte[1024];

        // Generate random offsets for non-sequential access
        int numOffsets = 1000;
        offsets = new long[numOffsets];
        for (int i = 0; i < numOffsets; i++) {
            offsets[i] = Math.abs(random.nextLong() % (fileSize - 1024));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (mappedFile != null) {
            mappedFile.close();
        }
        if (tempFile != null) {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Benchmark sequential byte reads.
     */
    @Benchmark
    public byte readByteSequential(Blackhole bh) {
        byte result = 0;
        for (long i = 0; i < Math.min(fileSize, 10000); i++) {
            result = mappedFile.readByte(i);
        }
        return result;
    }

    /**
     * Benchmark random byte reads.
     */
    @Benchmark
    public byte readByteRandom(Blackhole bh) {
        byte result = 0;
        for (int i = 0; i < offsets.length; i++) {
            result = mappedFile.readByte(offsets[i]);
        }
        return result;
    }

    /**
     * Benchmark sequential long reads.
     */
    @Benchmark
    public long readLongSequential(Blackhole bh) {
        long result = 0;
        long maxOffset = Math.min(fileSize - 8, 10000);
        for (long i = 0; i < maxOffset; i += 8) {
            result = mappedFile.readLong(i);
        }
        return result;
    }

    /**
     * Benchmark random long reads.
     */
    @Benchmark
    public long readLongRandom(Blackhole bh) {
        long result = 0;
        for (int i = 0; i < offsets.length; i++) {
            result = mappedFile.readLong(offsets[i]);
        }
        return result;
    }

    /**
     * Benchmark byte array reads.
     */
    @Benchmark
    public int readByteArray(Blackhole bh) {
        int bytesRead = 0;
        long offset = 0;
        while (offset + readBuffer.length <= fileSize) {
            mappedFile.read(offset, readBuffer);
            bytesRead += readBuffer.length;
            offset += readBuffer.length;
        }
        return bytesRead;
    }

    /**
     * Benchmark sequential byte writes.
     */
    @Benchmark
    public void writeByteSequential(Blackhole bh) {
        for (long i = 0; i < Math.min(fileSize, 10000); i++) {
            mappedFile.writeByte(i, (byte) i);
        }
    }

    /**
     * Benchmark sequential long writes.
     */
    @Benchmark
    public void writeLongSequential(Blackhole bh) {
        long maxOffset = Math.min(fileSize - 8, 10000);
        for (long i = 0; i < maxOffset; i += 8) {
            mappedFile.writeLong(i, i);
        }
    }

    /**
     * Benchmark zero-copy slice read.
     */
    @Benchmark
    public Object sliceRead(Blackhole bh) {
        return mappedFile.slice(0, Math.min(4096, fileSize));
    }
}
