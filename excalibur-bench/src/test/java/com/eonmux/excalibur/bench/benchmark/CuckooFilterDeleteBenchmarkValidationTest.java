package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.filter.CuckooFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjdk.jmh.infra.Blackhole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Drives {@link CuckooFilterDeleteBenchmark} through many more invocations than a filter has
 * keys, at every load factor it is run at.
 *
 * <p>The correctness checks inside the benchmark must hold for a correct filter over a whole
 * trial: a check that a fingerprint collision can trip is not a check but a coin flip that a
 * throughput run eventually loses.</p>
 *
 * @author ExcaliburDB
 */
class CuckooFilterDeleteBenchmarkValidationTest {

    private static CuckooFilterDeleteBenchmark setUp(double loadFactor) {
        CuckooFilterDeleteBenchmark benchmark = new CuckooFilterDeleteBenchmark();
        benchmark.targetLoadFactor = loadFactor;
        benchmark.capacity = 20_000;
        benchmark.fingerprintBits = 13;
        benchmark.setup();
        return benchmark;
    }

    @ParameterizedTest(name = "load factor {0}")
    @ValueSource(doubles = {0.25, 0.50, 0.75, 0.95})
    @DisplayName("Deleting absent keys never succeeds, however long the trial")
    void absentDeletesNeverSucceed(double loadFactor) {
        CuckooFilterDeleteBenchmark benchmark = setUp(loadFactor);
        CuckooFilter filter = BenchmarkFields.get(benchmark, "filter");
        long before = filter.count();

        for (int i = 0; i < 200_000; i++) {
            assertThat(benchmark.deleteNonExistent(null)).isFalse();
        }
        for (int i = 0; i < 50_000; i++) {
            assertThat(benchmark.deleteVariedKeySizes(null)).isZero();
        }
        assertThat(filter.count()).isEqualTo(before);
        benchmark.tearDown();
    }

    @ParameterizedTest(name = "load factor {0}")
    @ValueSource(doubles = {0.25, 0.50, 0.75, 0.95})
    @DisplayName("Delete-and-verify completes a trial of several passes over every key")
    void deleteAndVerifyCompletesATrial(double loadFactor) {
        CuckooFilterDeleteBenchmark benchmark = setUp(loadFactor);
        CuckooFilter filter = BenchmarkFields.get(benchmark, "filter");
        long before = filter.count();

        for (long i = 0; i < 3 * before; i++) {
            assertThat(benchmark.deleteAndVerify(null)).isTrue();
        }

        assertThat(filter.count()).isEqualTo(before);
        benchmark.tearDown();
    }

    @ParameterizedTest(name = "load factor {0}")
    @ValueSource(doubles = {0.25, 0.95})
    @DisplayName("Every delete benchmark holds the filter at its load factor")
    void deleteBenchmarksHoldTheLoadFactor(double loadFactor) {
        CuckooFilterDeleteBenchmark benchmark = setUp(loadFactor);
        CuckooFilter filter = BenchmarkFields.get(benchmark, "filter");
        assertThat(filter.loadFactor()).isCloseTo(loadFactor, within(0.001));
        long before = filter.count();

        for (int i = 0; i < 5_000; i++) {
            assertThat(benchmark.singleDelete(null)).isTrue();
            assertThat(benchmark.batchDelete10(null)).isEqualTo(10);
            assertThat(benchmark.batchDelete100(null)).isEqualTo(100);
            assertThat(benchmark.deleteAndReinsert(null)).isTrue();
        }

        assertThat(filter.count()).isEqualTo(before);
        benchmark.tearDown();
    }

    @Test
    @DisplayName("Methods doing several deletes per call report per-delete throughput")
    void multiDeleteMethodsAreNormalised() {
        assertThat(BenchmarkFields.operationsPerInvocation(
            CuckooFilterDeleteBenchmark.class, "batchDelete10", Blackhole.class)).isEqualTo(10);
        assertThat(BenchmarkFields.operationsPerInvocation(
            CuckooFilterDeleteBenchmark.class, "batchDelete100", Blackhole.class)).isEqualTo(100);
        assertThat(BenchmarkFields.operationsPerInvocation(
            CuckooFilterDeleteBenchmark.class, "deleteVariedKeySizes", Blackhole.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("The still-visible budget is the false positive rate of a full filter")
    void stillVisibleBudgetIsTheFullFilterFalsePositiveRate() {
        double perSlot = 1.0 / ((1 << 13) - 1);
        assertThat(CuckooFilterDeleteBenchmark.stillVisibleRate(13))
            .isCloseTo(1.0 - Math.pow(1.0 - perSlot, 8), within(1e-12));
        assertThat(CuckooFilterDeleteBenchmark.stillVisibleRate(8))
            .isGreaterThan(CuckooFilterDeleteBenchmark.stillVisibleRate(16));
    }
}
