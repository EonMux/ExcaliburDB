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
 * Drives {@link SegmentBenchmark} the way JMH does and checks that each method measures what
 * its name and its target claim: per-lookup scores, batches that walk the key stream, and a
 * miss path that does not build its key inside the timed call.
 *
 * @author ExcaliburDB
 */
class SegmentBenchmarkValidationTest {

    private SegmentBenchmark benchmark;
    private Blackhole bh;

    @BeforeEach
    void setUp() throws IOException {
        benchmark = new SegmentBenchmark();
        benchmark.entryCount = 10_000;
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
        assertThat(ops("batchLookup")).isEqualTo(100);
        assertThat(ops("lookupWithManualLock")).isEqualTo(10);
        assertThat(ops("sequentialLookup")).isEqualTo(1);
        assertThat(ops("randomLookup")).isEqualTo(1);
        assertThat(ops("lookupWithEpoch")).isEqualTo(1);
        assertThat(ops("lookupMiss")).isEqualTo(1);
    }

    @Test
    @DisplayName("Batches find every key and walk the random stream")
    void batchesFindEveryKeyAndAdvance() {
        int mask = SegmentBenchmark.LOOKUP_INDEX_COUNT - 1;
        int expected = 0;
        for (int i = 0; i < 300; i++) {
            assertThat(benchmark.batchLookup(bh)).isEqualTo(ops("batchLookup"));
            expected = (expected + 100) & mask;
            assertThat(benchmark.lookupWithManualLock(bh)).isEqualTo(ops("lookupWithManualLock"));
            expected = (expected + 10) & mask;
            assertThat((int) BenchmarkFields.get(benchmark, "lookupCursor")).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("Single lookups hit on every call, across the wrap of both cursors")
    void singleLookupsHitAcrossTheWrap() {
        for (int i = 0; i < SegmentBenchmark.LOOKUP_INDEX_COUNT + 7; i++) {
            assertThat(benchmark.sequentialLookup(bh)).isNotNull();
            assertThat(benchmark.randomLookup(bh)).isNotNull();
            assertThat(benchmark.lookupWithEpoch(bh)).isNotNull();
        }
    }

    @Test
    @DisplayName("The miss path cycles through distinct pre-built absent keys")
    void missesUsePrebuiltAbsentKeys() {
        for (int i = 0; i < SegmentBenchmark.MISS_KEY_COUNT + 3; i++) {
            assertThat(benchmark.lookupMiss(bh)).isNull();
        }
        byte[][] missKeys = BenchmarkFields.get(benchmark, "missKeys");
        Set<ByteBuffer> distinct = new HashSet<>();
        for (byte[] key : missKeys) {
            distinct.add(ByteBuffer.wrap(key));
        }
        assertThat(distinct).hasSize(SegmentBenchmark.MISS_KEY_COUNT);
    }

    private static int ops(String method) {
        return BenchmarkFields.operationsPerInvocation(SegmentBenchmark.class, method,
            Blackhole.class);
    }
}
