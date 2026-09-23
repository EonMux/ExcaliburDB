package com.eonmux.excalibur.bench.benchmark;

import com.eonmux.excalibur.filter.CuckooFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every {@link CuckooFilterMemoryMappedBenchmark} method once against a small filter, so a
 * benchmark that cannot complete a single invocation fails here rather than minutes into a
 * capture run.
 *
 * @author ExcaliburDB
 */
class CuckooFilterMemoryMappedBenchmarkValidationTest {

    private CuckooFilterMemoryMappedBenchmark benchmark;
    private Blackhole bh;

    @BeforeEach
    void setUp() throws IOException {
        benchmark = new CuckooFilterMemoryMappedBenchmark();
        benchmark.filterCapacity = 10_000;
        benchmark.loadFactor = 0.75;
        benchmark.fingerprintBits = 13;
        benchmark.setup();
        bh = BenchmarkFields.blackhole();
    }

    @AfterEach
    void tearDown() throws IOException {
        benchmark.tearDown();
    }

    @Test
    @DisplayName("A filter written at a page offset reads back with every key present")
    void offsetWriteAndReadRoundTrip() {
        benchmark.memorySegmentWriteWithOffset(bh);
        CuckooFilter loaded = benchmark.memorySegmentReadWithOffset(bh);

        byte[][] keys = BenchmarkFields.get(benchmark, "insertedKeys");
        for (byte[] key : keys) {
            assertThat(loaded.contains(key)).isTrue();
        }
    }

    @Test
    @DisplayName("Every offset-zero memory segment benchmark completes")
    void segmentBenchmarksComplete() throws IOException {
        benchmark.memorySegmentWrite(bh);
        assertThat(benchmark.memorySegmentRead(bh).count()).isPositive();
        assertThat(benchmark.memorySegmentRoundTrip(bh).count()).isPositive();
        assertThat(benchmark.memorySegmentBatchWrite(bh)).isPositive();
        assertThat(benchmark.fileBackedReadWarm(bh).count()).isPositive();
    }

    @Test
    @DisplayName("Every file-backed benchmark completes")
    void fileBenchmarksComplete() throws IOException {
        CuckooFilterMemoryMappedBenchmark.FileState state =
            new CuckooFilterMemoryMappedBenchmark.FileState();
        state.setup();
        try {
            assertThat(benchmark.fileBackedWrite(state)).isEqualTo(state.filterSize);
            assertThat(benchmark.fileBackedReader(state).count()).isPositive();
            assertThat(benchmark.fileBackedRoundTrip(state).count()).isPositive();
        } finally {
            state.tearDown();
        }
    }
}
