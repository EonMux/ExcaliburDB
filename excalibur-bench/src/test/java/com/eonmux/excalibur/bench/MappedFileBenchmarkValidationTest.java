package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.bench.benchmark.MappedFileBenchmark;
import com.eonmux.excalibur.core.mapped.MappedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * Runs every benchmark method once under every combination of the declared parameters.
     *
     * <p>JMH sweeps the full cross product of {@code @Param} values, and a combination whose
     * setup throws is dropped from the run: the other combinations still report, so a gated
     * benchmark looks healthy while one of its parameter sets never produced a number. The
     * combinations are read from the annotations rather than restated here, so a value added
     * to the sweep is covered by this test the moment it is declared.</p>
     */
    @Test
    void everyBenchmarkRunsUnderEveryDeclaredParameterCombination() throws Exception {
        String[] fileSizes = MappedFileBenchmark.class.getField("fileSize").getAnnotation(Param.class).value();
        String[] bufferSizes = MappedFileBenchmark.class.getField("bufferSize").getAnnotation(Param.class).value();
        Blackhole blackhole = new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        List<String> failures = new ArrayList<>();

        try {
            for (String fileSize : fileSizes) {
                for (String bufferSize : bufferSizes) {
                    String combination = "fileSize=" + fileSize + ", bufferSize=" + bufferSize;
                    MappedFileBenchmark benchmark = new MappedFileBenchmark();
                    benchmark.fileSize = Long.parseLong(fileSize);
                    benchmark.bufferSize = Integer.parseInt(bufferSize);
                    try {
                        benchmark.setup();
                    } catch (RuntimeException e) {
                        failures.add("setup (" + combination + "): " + e);
                        continue;
                    }
                    try {
                        for (Method method : MappedFileBenchmark.class.getDeclaredMethods()) {
                            if (!method.isAnnotationPresent(Benchmark.class)) {
                                continue;
                            }
                            try {
                                invokeRepeatedly(benchmark, method, blackhole);
                            } catch (InvocationTargetException e) {
                                failures.add(method.getName() + " (" + combination + "): " + e.getCause());
                            }
                        }
                    } finally {
                        benchmark.tearDown();
                    }
                }
            }
        } finally {
            blackhole.evaporate(
                "Yes, I am Stephen Hawking, and know a thing or two about black holes.");
        }

        assertThat(failures)
            .as("every parameter combination JMH will run must set up and execute")
            .isEmpty();
    }

    @Test
    void setupRejectsABufferThatCannotFitTheFile() {
        MappedFileBenchmark benchmark = new MappedFileBenchmark();
        benchmark.fileSize = 1024;
        benchmark.bufferSize = 4096;

        assertThatThrownBy(benchmark::setup)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bufferSize=4096");
    }

    /**
     * Invokes a benchmark enough times to wrap its random-offset cursor, so every
     * pre-generated offset is exercised and not only the first.
     */
    private static void invokeRepeatedly(MappedFileBenchmark benchmark, Method method, Blackhole blackhole)
            throws IllegalAccessException, InvocationTargetException {
        int invocations = method.getName().startsWith("sequential") ? 1 : 10_001;
        for (int i = 0; i < invocations; i++) {
            if (method.getParameterCount() == 0) {
                method.invoke(benchmark);
            } else {
                method.invoke(benchmark, blackhole);
            }
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
