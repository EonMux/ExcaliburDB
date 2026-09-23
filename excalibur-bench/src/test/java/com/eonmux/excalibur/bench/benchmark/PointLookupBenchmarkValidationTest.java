package com.eonmux.excalibur.bench.benchmark;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link PointLookupBenchmark} the way JMH does and checks that each method measures
 * what its name and its target claim.
 *
 * <p>The registry holds these benchmarks to per-lookup targets, so a method that makes several
 * lookups per call has to say so, or its score is a batch rate compared against a lookup
 * rate. A batch that replays the same first keys on every call measures a working set that
 * never leaves L1; and a miss benchmark that builds and hashes its key inside the timed call
 * measures string formatting as much as the lookup.</p>
 *
 * @author ExcaliburDB
 */
class PointLookupBenchmarkValidationTest {

    private PointLookupBenchmark benchmark;
    private Blackhole bh;

    @BeforeEach
    void setUp() throws IOException {
        benchmark = new PointLookupBenchmark();
        benchmark.entryCount = 5000;
        benchmark.keySize = 16;
        benchmark.valueSize = 64;
        benchmark.setup();
        bh = BenchmarkFields.blackhole();
    }

    @AfterEach
    void tearDown() throws IOException {
        benchmark.tearDown();
    }

    @Test
    @DisplayName("Every score is per lookup")
    void everyScoreIsPerLookup() {
        assertThat(ops("batchLookup10")).isEqualTo(10);
        assertThat(ops("batchLookup100")).isEqualTo(100);
        assertThat(ops("batchLookupApi")).isEqualTo(10);
        assertThat(ops("sequentialLookupFast")).isEqualTo(1);
        assertThat(ops("randomLookupFast")).isEqualTo(1);
        assertThat(ops("sequentialLookupWithLock")).isEqualTo(1);
        assertThat(ops("lookupMiss")).isEqualTo(1);
    }

    @Test
    @DisplayName("Every batch lookup finds its key, and each call returns as many hits as it declares")
    void batchesFindEveryKey() {
        for (int i = 0; i < 1000; i++) {
            assertThat(benchmark.batchLookup10(bh)).isEqualTo(ops("batchLookup10"));
            assertThat(benchmark.batchLookup100(bh)).isEqualTo(ops("batchLookup100"));
            assertThat(benchmark.batchLookupApi(bh)).isEqualTo(ops("batchLookupApi"));
        }
    }

    @Test
    @DisplayName("Successive batches walk the random stream instead of replaying its first keys")
    void batchesAdvanceThroughTheRandomStream() {
        int mask = PointLookupBenchmark.RANDOM_INDEX_COUNT - 1;
        int expected = 0;
        for (int i = 0; i < 500; i++) {
            benchmark.batchLookup100(bh);
            expected = (expected + 100) & mask;
            assertThat((int) BenchmarkFields.get(benchmark, "randomCursor")).isEqualTo(expected);
        }
        int[] stream = BenchmarkFields.get(benchmark, "randomIndices");
        Set<Integer> distinct = new HashSet<>();
        for (int index : stream) {
            distinct.add(index);
        }
        assertThat(distinct.size())
            .as("the stream should reach most of the 5000 entries")
            .isGreaterThan(4500);
    }

    @Test
    @DisplayName("Single lookups hit on every call, across the wrap of both cursors")
    void singleLookupsHitAcrossTheWrap() {
        for (int i = 0; i < PointLookupBenchmark.RANDOM_INDEX_COUNT + 7; i++) {
            assertThat(benchmark.sequentialLookupFast(bh)).isNotNull();
            assertThat(benchmark.randomLookupFast(bh)).isNotNull();
            assertThat(benchmark.sequentialLookupWithLock(bh)).isNotNull();
        }
    }

    @Test
    @DisplayName("The miss path cycles through distinct pre-built absent keys")
    void missesUsePrebuiltAbsentKeys() {
        for (int i = 0; i < PointLookupBenchmark.MISS_KEY_COUNT + 3; i++) {
            assertThat(benchmark.lookupMiss(bh)).isNull();
        }
        byte[][] missKeys = BenchmarkFields.get(benchmark, "missKeys");
        Set<ByteBuffer> distinct = new HashSet<>();
        for (byte[] key : missKeys) {
            assertThat(key).hasSize(benchmark.keySize);
            distinct.add(ByteBuffer.wrap(key));
        }
        assertThat(distinct).hasSize(PointLookupBenchmark.MISS_KEY_COUNT);
    }

    private static int ops(String method) {
        return BenchmarkFields.operationsPerInvocation(PointLookupBenchmark.class, method,
            Blackhole.class);
    }
}
