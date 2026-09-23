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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Validates the setup of {@link CuckooFilterBenchmark}, whose trials must reach the load factor
 * they are named for before any lookup is timed.
 *
 * @author ExcaliburDB
 */
class CuckooFilterBenchmarkValidationTest {

    private static CuckooFilterBenchmark setUp(double loadFactor) {
        CuckooFilterBenchmark benchmark = new CuckooFilterBenchmark();
        benchmark.targetLoadFactor = loadFactor;
        benchmark.capacity = 20_000;
        benchmark.fingerprintBits = 13;
        benchmark.setup();
        return benchmark;
    }

    @ParameterizedTest(name = "load factor {0}")
    @ValueSource(doubles = {0.50, 0.75, 0.90, 0.95})
    @DisplayName("Setup fills the filter's real capacity to the target load factor")
    void setupReachesTheTargetLoadFactor(double loadFactor) {
        CuckooFilterBenchmark benchmark = setUp(loadFactor);
        CuckooFilter filter = BenchmarkFields.get(benchmark, "filter");

        assertThat(filter.loadFactor())
            .as("the requested capacity is rounded up to a power-of-two bucket count, so the "
                + "fill must be sized from the filter's capacity, not the parameter")
            .isCloseTo(loadFactor, within(0.001));
    }

    @ParameterizedTest(name = "load factor {0}")
    @ValueSource(doubles = {0.50, 0.95})
    @DisplayName("Miss keys are disjoint from the inserted keys")
    void missKeysWereNeverInserted(double loadFactor) {
        CuckooFilterBenchmark benchmark = setUp(loadFactor);
        byte[][] inserted = BenchmarkFields.get(benchmark, "insertedKeys");
        byte[][] missing = BenchmarkFields.get(benchmark, "nonInsertedKeys");

        Set<ByteBuffer> insertedSet = new HashSet<>();
        for (byte[] key : inserted) {
            insertedSet.add(ByteBuffer.wrap(key));
        }
        for (byte[] key : missing) {
            assertThat(insertedSet)
                .as("a miss key that was inserted turns the miss path into the hit path")
                .doesNotContain(ByteBuffer.wrap(key));
        }
    }

    @Test
    @DisplayName("Every hit lookup finds its key")
    void hitLookupsFindEveryKey() {
        CuckooFilterBenchmark benchmark = setUp(0.95);
        byte[][] inserted = BenchmarkFields.get(benchmark, "insertedKeys");
        Blackhole bh = BenchmarkFields.blackhole();

        for (int i = 0; i < inserted.length; i++) {
            assertThat(benchmark.lookupHit(bh)).isTrue();
        }
        assertThat(benchmark.batchLookupHit(bh)).isEqualTo(10);
    }

    @Test
    @DisplayName("Methods doing several lookups per call report per-lookup throughput")
    void multiLookupMethodsAreNormalised() {
        assertThat(BenchmarkFields.operationsPerInvocation(
            CuckooFilterBenchmark.class, "batchLookupHit", Blackhole.class)).isEqualTo(10);
        assertThat(BenchmarkFields.operationsPerInvocation(
            CuckooFilterBenchmark.class, "lookupMixed", Blackhole.class)).isEqualTo(2);
        assertThat(BenchmarkFields.operationsPerInvocation(
            CuckooFilterBenchmark.class, "lookupHit", Blackhole.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("The mixed workload never fills its filter with duplicates")
    void mixedWorkloadStaysWithinItsDistinctKeys() {
        CuckooFilterBenchmark benchmark = setUp(0.50);
        CuckooFilterBenchmark.MixedWorkloadState state = new CuckooFilterBenchmark.MixedWorkloadState();
        state.setup();
        byte[][] keys = BenchmarkFields.get(state, "keys");

        // Twenty passes over the key cycle: re-inserting each key on every pass would put
        // twenty copies of it in the filter, and it only has room for eight.
        for (int i = 0; i < keys.length * 20; i++) {
            benchmark.mixedInsertContains(state);
        }

        CuckooFilter filter = BenchmarkFields.get(state, "mixedFilter");
        assertThat(filter.count())
            .as("each key the workload inserts is recorded once, however often it recurs")
            .isLessThanOrEqualTo(keys.length / 10);
    }
}
