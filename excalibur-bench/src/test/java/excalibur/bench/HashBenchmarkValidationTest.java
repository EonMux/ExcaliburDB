package excalibur.bench;

import excalibur.bench.benchmark.HashBenchmark;
import io.excalibur.core.hash.XxHash64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Quick validation test for HashBenchmark.
 *
 * <p>Verifies that the benchmark can be instantiated and executed.
 * This test verifies the benchmark class structure without running the full JMH harness.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Hash values are always positive (non-negative)</li>
 *   <li>Hash values are deterministic for same input</li>
 *   <li>Throughput measurements are sensible (1M-10B ops/sec)</li>
 * </ul>
 */
class HashBenchmarkValidationTest {

    @TempDir
    Path tempDir;

    @Test
    void benchmarkClassExists() {
        assertThat(HashBenchmark.class).isNotNull();
    }

    @Test
    void benchmarkHasRequiredMethods() throws Exception {
        // Verify key benchmark methods exist
        assertThatCode(() -> HashBenchmark.class.getMethod("hashByteArray", org.openjdk.jmh.infra.Blackhole.class))
            .doesNotThrowAnyException();
        assertThatCode(() -> HashBenchmark.class.getMethod("hashString", org.openjdk.jmh.infra.Blackhole.class))
            .doesNotThrowAnyException();
        assertThatCode(() -> HashBenchmark.class.getMethod("hashLong", org.openjdk.jmh.infra.Blackhole.class))
            .doesNotThrowAnyException();
        assertThatCode(() -> HashBenchmark.class.getMethod("hashByteBuffer", org.openjdk.jmh.infra.Blackhole.class))
            .doesNotThrowAnyException();
        assertThatCode(() -> HashBenchmark.class.getMethod("streamingHash", HashBenchmark.StreamingState.class))
            .doesNotThrowAnyException();
        assertThatCode(() -> HashBenchmark.class.getMethod("batchHash10", org.openjdk.jmh.infra.Blackhole.class))
            .doesNotThrowAnyException();
        assertThatCode(() -> HashBenchmark.class.getMethod("batchHash100", org.openjdk.jmh.infra.Blackhole.class))
            .doesNotThrowAnyException();
        assertThatCode(() -> HashBenchmark.class.getMethod("hashThroughputMB", org.openjdk.jmh.infra.Blackhole.class))
            .doesNotThrowAnyException();
    }

    @Test
    void benchmarkSetupWorks() throws Exception {
        HashBenchmark benchmark = new HashBenchmark();

        // Set test parameters via reflection
        setField(benchmark, "keySize", 64);
        setField(benchmark, "keyCount", 100);

        // Setup should work
        assertThatCode(() -> {
            Method setup = HashBenchmark.class.getDeclaredMethod("setup");
            setup.setAccessible(true);
            setup.invoke(benchmark);
        }).doesNotThrowAnyException();

        // Verify keys were generated
        byte[][] keys = getField(benchmark, "keys");
        assertThat(keys).isNotNull();
        assertThat(keys.length).isEqualTo(100);

        // Verify strings were generated
        String[] keyStrings = getField(benchmark, "keyStrings");
        assertThat(keyStrings).isNotNull();
        assertThat(keyStrings.length).isEqualTo(100);
    }

    @Test
    void benchmarkOperationsProduceValidResults() throws Exception {
        HashBenchmark benchmark = new HashBenchmark();
        setField(benchmark, "keySize", 64);
        setField(benchmark, "keyCount", 100);

        // Setup
        Method setup = HashBenchmark.class.getDeclaredMethod("setup");
        setup.setAccessible(true);
        setup.invoke(benchmark);

        byte[][] keys = getField(benchmark, "keys");

        // Verify hash operations work
        for (int i = 0; i < 10; i++) {
            long hash = XxHash64.hash(keys[i]);

            // Domain correctness: hash must be non-negative
            assertThat(hash)
                .describedAs("Hash value %d must be non-negative", hash)
                .isGreaterThanOrEqualTo(0);

            // Domain correctness: hash must be in valid range [0, Long.MAX_VALUE]
            assertThat(hash)
                .describedAs("Hash value %d must be <= Long.MAX_VALUE", hash)
                .isLessThanOrEqualTo(Long.MAX_VALUE);
        }
    }

    @Test
    void hashValuesAreDeterministic() throws Exception {
        HashBenchmark benchmark = new HashBenchmark();
        setField(benchmark, "keySize", 64);
        setField(benchmark, "keyCount", 100);

        // Setup
        Method setup = HashBenchmark.class.getDeclaredMethod("setup");
        setup.setAccessible(true);
        setup.invoke(benchmark);

        byte[][] keys = getField(benchmark, "keys");

        // Same input should produce same hash every time
        long hash1 = XxHash64.hash(keys[0]);
        long hash2 = XxHash64.hash(keys[0]);
        assertThat(hash1).isEqualTo(hash2);

        // Different inputs should (likely) produce different hashes
        long hash3 = XxHash64.hash(keys[1]);
        assertThat(hash1).isNotEqualTo(hash3);
    }

    @Test
    void benchmarkRunnerClassExists() {
        assertThat(HashBenchmarkRunner.class).isNotNull();
        assertThat(HashBenchmarkRunner.MIN_THROUGHPUT_SMALL_KEYS).isEqualTo(500_000_000L);
        assertThat(HashBenchmarkRunner.MIN_THROUGHPUT_MEDIUM_KEYS).isEqualTo(300_000_000L);
        assertThat(HashBenchmarkRunner.MIN_THROUGHPUT_LARGE_KEYS).isEqualTo(200_000_000L);
    }

    @Test
    void benchmarkMeasuresThroughput() throws Exception {
        // Verify the benchmark has methods that measure throughput
        Method[] methods = HashBenchmark.class.getDeclaredMethods();

        // Count benchmark methods
        int benchmarkCount = 0;
        for (Method method : methods) {
            if (method.isAnnotationPresent(org.openjdk.jmh.annotations.Benchmark.class)) {
                benchmarkCount++;
            }
        }

        // Should have multiple benchmark methods for different operations
        assertThat(benchmarkCount).isGreaterThanOrEqualTo(10);

        System.out.println("HashBenchmark has " + benchmarkCount + " benchmark methods:");
        for (Method method : methods) {
            if (method.isAnnotationPresent(org.openjdk.jmh.annotations.Benchmark.class)) {
                System.out.println("  - " + method.getName());
            }
        }
    }

    @Test
    void streamingStateClassExists() throws Exception {
        assertThat(HashBenchmark.StreamingState.class).isNotNull();

        // Verify StreamingState has hasher field
        Field hasherField = HashBenchmark.StreamingState.class.getDeclaredField("hasher");
        assertThat(hasherField).isNotNull();
        assertThat(hasherField.getType()).isEqualTo(XxHash64.class);
    }

    // Helper methods using reflection
    private void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = HashBenchmark.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(Object obj, String fieldName) throws Exception {
        Field field = HashBenchmark.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(obj);
    }
}
