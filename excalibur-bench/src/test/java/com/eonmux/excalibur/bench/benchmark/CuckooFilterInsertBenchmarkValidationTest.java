package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.filter.CuckooFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Drives {@link CuckooFilterInsertBenchmark} the way JMH does - benchmark method, then the
 * invocation-level restore - for many more invocations than the filter has free slots.
 *
 * <p>An insert benchmark that never gives back what it inserted is measuring a filter that fills
 * up during the run, and then one that is full: every refused insert walks a whole displacement
 * chain and rolls it back, which is orders of magnitude slower than an insert and is what the
 * trial ends up timing. The load factor the benchmark is named for has to be the one every
 * timed insert sees.</p>
 *
 * @author ExcaliburDB
 */
class CuckooFilterInsertBenchmarkValidationTest {

    private static CuckooFilterInsertBenchmark setUp(double loadFactor) {
        CuckooFilterInsertBenchmark benchmark = new CuckooFilterInsertBenchmark();
        benchmark.targetLoadFactor = loadFactor;
        benchmark.capacity = 20_000;
        benchmark.setup();
        return benchmark;
    }

    @ParameterizedTest(name = "load factor {0}")
    @ValueSource(doubles = {0.25, 0.50, 0.75, 0.95})
    @DisplayName("Every insert benchmark sees the target load factor on every invocation")
    void everyInvocationStartsAtTheTargetLoadFactor(double loadFactor) {
        CuckooFilterInsertBenchmark benchmark = setUp(loadFactor);
        CuckooFilter filter = BenchmarkFields.get(benchmark, "filter");
        assertThat(filter.loadFactor()).isCloseTo(loadFactor, within(0.001));
        long resident = filter.count();

        drive(benchmark, "singleInsert", b -> b.singleInsert(null) ? 1 : 0, 50_000, resident);
        drive(benchmark, "insertAndVerify", b -> b.insertAndVerify(null) ? 1 : 0, 50_000, resident);
        drive(benchmark, "batchInsert100", b -> b.batchInsert100(null), 500, resident);
        drive(benchmark, "batchInsert1000", b -> b.batchInsert1000(null), 50, resident);
        drive(benchmark, "insertVariedKeySizes", b -> b.insertVariedKeySizes(null), 12_500, resident);

        benchmark.tearDown();
    }

    @Test
    @DisplayName("Varied-size keys are distinct and cover every size")
    void variedKeysAreDistinct() {
        CuckooFilterInsertBenchmark benchmark = setUp(0.25);
        byte[][] varied = BenchmarkFields.get(benchmark, "variedKeys");

        Set<ByteBuffer> distinct = new HashSet<>();
        Set<Integer> sizes = new HashSet<>();
        for (byte[] key : varied) {
            distinct.add(ByteBuffer.wrap(key));
            sizes.add(key.length);
        }
        assertThat(distinct)
            .as("a repeated key is a duplicate insert, which fills its two buckets and then "
                + "fails on every attempt")
            .hasSize(varied.length);
        assertThat(sizes).containsExactlyInAnyOrder(8, 16, 32, 64);
    }

    /**
     * Invokes one benchmark method the way JMH does and checks what each invocation did.
     *
     * @param benchmark   The benchmark under test
     * @param method      The method's name, for its {@code @OperationsPerInvocation}
     * @param invocation  Calls the method and returns how many of its inserts succeeded
     * @param invocations How many invocations to drive
     * @param resident    The entry count every invocation must start from
     */
    private static void drive(CuckooFilterInsertBenchmark benchmark, String method,
                              ToIntFunction<CuckooFilterInsertBenchmark> invocation,
                              int invocations, long resident) {
        CuckooFilter filter = BenchmarkFields.get(benchmark, "filter");
        int declared = BenchmarkFields.operationsPerInvocation(
            CuckooFilterInsertBenchmark.class, method, Blackhole.class);

        for (int i = 0; i < invocations; i++) {
            long attemptedBefore = attempted(benchmark);
            int succeeded = invocation.applyAsInt(benchmark);

            assertThat(attempted(benchmark) - attemptedBefore)
                .as("%s must attempt exactly the %d inserts JMH divides its time by",
                    method, declared)
                .isEqualTo(declared);
            assertThat(filter.count()).isEqualTo(resident + succeeded);

            benchmark.restoreLoadFactor();
            assertThat(filter.count())
                .as("%s invocation %d must hand back every slot it took", method, i)
                .isEqualTo(resident);
        }
    }

    private static long attempted(CuckooFilterInsertBenchmark benchmark) {
        long success = BenchmarkFields.get(benchmark, "successCount");
        long fail = BenchmarkFields.get(benchmark, "failCount");
        return success + fail;
    }
}
