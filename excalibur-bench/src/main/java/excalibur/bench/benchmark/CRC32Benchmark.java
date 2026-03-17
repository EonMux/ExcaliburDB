package excalibur.bench.benchmark;

import io.excalibur.core.checksum.CRC32Checksum;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for CRC32 checksum performance.
 *
 * <p>Measures throughput of CRC32 computation for write-log entry validation.</p>
 *
 * @author ExcaliburDB
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--release", "21"})
public class CRC32Benchmark {

    @Param({"64", "256", "1024", "4096", "16384"})
    public int size;

    private byte[] data;
    private byte[] chunk1;
    private byte[] chunk2;

    @Setup(Level.Trial)
    public void setup() {
        Random random = new Random(42);
        data = new byte[size];
        random.nextBytes(data);

        // Split into two chunks for incremental benchmark
        int halfSize = size / 2;
        chunk1 = new byte[halfSize];
        chunk2 = new byte[size - halfSize];
        System.arraycopy(data, 0, chunk1, 0, halfSize);
        System.arraycopy(data, halfSize, chunk2, 0, size - halfSize);
    }

    /**
     * Benchmark one-shot CRC32 computation.
     */
    @Benchmark
    public long compute(Blackhole bh) {
        return CRC32Checksum.compute(data);
    }

    /**
     * Benchmark CRC32 with offset and length.
     */
    @Benchmark
    public long computeWithOffset(Blackhole bh) {
        return CRC32Checksum.compute(data, 0, data.length);
    }

    /**
     * Benchmark incremental CRC32 computation.
     */
    @Benchmark
    public long computeIncremental(Blackhole bh) {
        long crc = CRC32Checksum.compute(chunk1);
        return CRC32Checksum.update(crc, chunk2);
    }

    /**
     * Benchmark CRC32 verification.
     */
    @Benchmark
    public boolean verify(Blackhole bh) {
        return CRC32Checksum.verify(data, CRC32Checksum.compute(data));
    }
}
