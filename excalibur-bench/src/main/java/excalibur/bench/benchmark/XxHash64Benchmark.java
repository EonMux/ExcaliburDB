package excalibur.bench.benchmark;

import io.excalibur.core.hash.XxHash64;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for XxHash64 hash function performance.
 *
 * <p>Measures throughput of the xxHash64 algorithm across various input sizes
 * to validate the Phase 1 requirement for high-performance hashing.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--release", "21"})
public class XxHash64Benchmark {

    @Param({"8", "32", "64", "256", "1024", "4096", "16384", "65536"})
    public int size;

    private byte[] data;
    private byte[] alignedData;

    @Setup(Level.Trial)
    public void setup() {
        Random random = new Random(42);
        data = new byte[size];
        random.nextBytes(data);

        // Aligned data for better memory access patterns
        alignedData = new byte[size + 16];
        random.nextBytes(alignedData);
    }

    /**
     * Benchmark static hash method with byte array.
     */
    @Benchmark
    public long hashByteArray(Blackhole bh) {
        return XxHash64.hash(data);
    }

    /**
     * Benchmark static hash method with byte array and seed.
     */
    @Benchmark
    public long hashByteArrayWithSeed(Blackhole bh) {
        return XxHash64.hash(data, 42L);
    }

    /**
     * Benchmark streaming API with single update.
     */
    @Benchmark
    public long hashStreaming(Blackhole bh) {
        XxHash64 hasher = new XxHash64(0);
        hasher.update(data);
        return hasher.getValue();
    }

    /**
     * Benchmark streaming API with chunked updates (4KB chunks).
     */
    @Benchmark
    public long hashStreamingChunked(Blackhole bh) {
        XxHash64 hasher = new XxHash64(0);
        int chunkSize = 4096;
        int offset = 0;
        while (offset < data.length) {
            int len = Math.min(chunkSize, data.length - offset);
            hasher.update(data, offset, len);
            offset += len;
        }
        return hasher.getValue();
    }

    /**
     * Benchmark hash of string (UTF-8 encoded).
     */
    @Benchmark
    public long hashString(Blackhole bh) {
        return XxHash64.hash(new String(data, StandardCharsets.ISO_8859_1));
    }

    /**
     * Benchmark hash of long value.
     */
    @Benchmark
    public long hashLong(Blackhole bh) {
        return XxHash64.hash(0x123456789ABCDEF0L);
    }
}
