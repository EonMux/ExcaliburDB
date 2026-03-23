package excalibur.bench;

import excalibur.bench.benchmark.MappedFileBenchmark;
import io.excalibur.core.mapped.MappedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Quick validation test for MappedFileBenchmark.
 * Verifies that the benchmark can be instantiated and executed.
 */
class MappedFileBenchmarkValidationTest {

    @TempDir
    Path tempDir;

    @Test
    void benchmarkClassExists() {
        assertThat(MappedFileBenchmark.class).isNotNull();
    }

    @Test
    void benchmarkHasRequiredMethods() {
        // Verify key benchmark methods exist
        assertThatCode(() -> MappedFileBenchmark.class.getMethod("sequentialRead_bytes", org.openjdk.jmh.infra.Blackhole.class))
            .doesNotThrowAnyException();
        assertThatCode(() -> MappedFileBenchmark.class.getMethod("sequentialWrite_bytes", org.openjdk.jmh.infra.Blackhole.class))
            .doesNotThrowAnyException();
        assertThatCode(() -> MappedFileBenchmark.class.getMethod("randomRead_bytes", org.openjdk.jmh.infra.Blackhole.class))
            .doesNotThrowAnyException();
        assertThatCode(() -> MappedFileBenchmark.class.getMethod("randomWrite_bytes", org.openjdk.jmh.infra.Blackhole.class))
            .doesNotThrowAnyException();
        assertThatCode(() -> MappedFileBenchmark.class.getMethod("randomRead_int"))
            .doesNotThrowAnyException();
        assertThatCode(() -> MappedFileBenchmark.class.getMethod("randomRead_long"))
            .doesNotThrowAnyException();
    }

    @Test
    void benchmarkSetupAndTeardownWork() throws Exception {
        MappedFileBenchmark benchmark = new MappedFileBenchmark();

        // Set test parameters (small values for quick test)
        setField(benchmark, "fileSize", 65536L); // 64KB
        setField(benchmark, "bufferSize", 1024); // 1KB

        // Setup should work
        assertThatCode(() -> {
            Method setup = MappedFileBenchmark.class.getDeclaredMethod("setup");
            setup.setAccessible(true);
            setup.invoke(benchmark);
        }).doesNotThrowAnyException();

        // Verify setup created the mapped file
        MappedFile mappedFile = getField(benchmark, "mappedFile");
        assertThat(mappedFile).isNotNull();
        assertThat(mappedFile.size()).isGreaterThanOrEqualTo(65536L);

        // Teardown should work
        assertThatCode(() -> {
            Method teardown = MappedFileBenchmark.class.getDeclaredMethod("tearDown");
            teardown.setAccessible(true);
            teardown.invoke(benchmark);
        }).doesNotThrowAnyException();
    }

    @Test
    void benchmarkOperationsProduceValidResults() throws Exception {
        MappedFileBenchmark benchmark = new MappedFileBenchmark();
        setField(benchmark, "fileSize", 65536L);
        setField(benchmark, "bufferSize", 1024);

        // Setup
        Method setup = MappedFileBenchmark.class.getDeclaredMethod("setup");
        setup.setAccessible(true);
        setup.invoke(benchmark);

        try {
            MappedFile mappedFile = getField(benchmark, "mappedFile");
            byte[] writeBuffer = getField(benchmark, "writeBuffer");
            byte[] readBuffer = getField(benchmark, "readBuffer");

            // Verify read/write operations work
            assertThatCode(() -> {
                // Write some data
                writeBuffer[0] = 42;
                mappedFile.write(0, writeBuffer);

                // Read it back
                mappedFile.read(0, readBuffer);
            }).doesNotThrowAnyException();

            // Verify data integrity
            assertThat(readBuffer[0]).isEqualTo((byte) 42);

        } finally {
            // Teardown
            Method teardown = MappedFileBenchmark.class.getDeclaredMethod("tearDown");
            teardown.setAccessible(true);
            teardown.invoke(benchmark);
        }
    }

    @Test
    void benchmarkRunnerClassExists() {
        assertThat(MappedFileBenchmarkRunner.class).isNotNull();
        assertThat(MappedFileBenchmarkRunner.TARGET_GB_PER_SEC).isEqualTo(2.0);
        assertThat(MappedFileBenchmarkRunner.TARGET_RANDOM_OPS_PER_SEC).isEqualTo(10_000_000.0);
    }

    @Test
    void benchmarkMeasuresThroughput() throws Exception {
        // Verify the benchmark has methods that measure throughput
        Method[] methods = MappedFileBenchmark.class.getDeclaredMethods();

        // Count benchmark methods
        int benchmarkCount = 0;
        for (Method method : methods) {
            if (method.isAnnotationPresent(org.openjdk.jmh.annotations.Benchmark.class)) {
                benchmarkCount++;
            }
        }

        // Should have multiple benchmark methods for different operations
        assertThat(benchmarkCount).isGreaterThanOrEqualTo(10);

        System.out.println("MappedFileBenchmark has " + benchmarkCount + " benchmark methods:");
        for (Method method : methods) {
            if (method.isAnnotationPresent(org.openjdk.jmh.annotations.Benchmark.class)) {
                System.out.println("  - " + method.getName());
            }
        }
    }

    // Helper methods using reflection
    private void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = MappedFileBenchmark.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(Object obj, String fieldName) throws Exception {
        Field field = MappedFileBenchmark.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(obj);
    }
}
