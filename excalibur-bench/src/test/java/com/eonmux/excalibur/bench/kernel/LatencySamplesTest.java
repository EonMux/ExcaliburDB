package com.eonmux.excalibur.bench.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the percentile computation over collected latency samples.
 *
 * <p>The p50/p99 targets in the registry are compared against these numbers, so an
 * off-by-one in the index, or a percentile taken over unsorted samples, reports a
 * latency the system never had.</p>
 */
class LatencySamplesTest {

    @Test
    @DisplayName("percentiles are taken over the sorted samples, whatever order they arrived in")
    void percentilesSortTheSamples() {
        LatencySamples samples = new LatencySamples();
        // Deliberately out of order.
        for (double ms : new double[]{100, 1, 50, 2, 3}) {
            samples.record(ms);
        }

        assertThat(samples.percentile(0)).isEqualTo(1.0);
        assertThat(samples.percentile(100)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("p50 is the median and p99 the tail of a 100-sample distribution")
    void medianAndTailOfAHundredSamples() {
        LatencySamples samples = new LatencySamples();
        for (int i = 1; i <= 100; i++) {
            samples.record(i);
        }

        assertThat(samples.percentile(50)).isEqualTo(50.0);
        assertThat(samples.percentile(99)).isEqualTo(99.0);
        assertThat(samples.p50()).isEqualTo(samples.percentile(50));
        assertThat(samples.p99()).isEqualTo(samples.percentile(99));
    }

    @Test
    @DisplayName("percentiles are monotone non-decreasing")
    void percentilesAreMonotone() {
        LatencySamples samples = new LatencySamples();
        for (int i = 0; i < 1000; i++) {
            samples.record((i * 7919) % 1000);
        }

        double previous = Double.NEGATIVE_INFINITY;
        for (int p = 0; p <= 100; p++) {
            double value = samples.percentile(p);
            assertThat(value).as("p%d", p).isGreaterThanOrEqualTo(previous);
            previous = value;
        }
    }

    @Test
    @DisplayName("a single sample is every percentile")
    void singleSampleIsEveryPercentile() {
        LatencySamples samples = new LatencySamples();
        samples.record(7.5);

        assertThat(samples.percentile(0)).isEqualTo(7.5);
        assertThat(samples.percentile(50)).isEqualTo(7.5);
        assertThat(samples.percentile(100)).isEqualTo(7.5);
    }

    @Test
    @DisplayName("an empty sample set reports 0 rather than throwing")
    void emptySamplesReportZero() {
        LatencySamples samples = new LatencySamples();

        assertThat(samples.count()).isZero();
        assertThat(samples.p50()).isEqualTo(0.0);
        assertThat(samples.p99()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("the sample count tracks what was recorded")
    void countTracksRecordedSamples() {
        LatencySamples samples = new LatencySamples();
        samples.record(1.0);
        samples.record(2.0);

        assertThat(samples.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a percentile outside 0..100 is rejected")
    void outOfRangePercentileIsRejected() {
        LatencySamples samples = new LatencySamples();
        samples.record(1.0);

        assertThatThrownBy(() -> samples.percentile(-1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> samples.percentile(101))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("samples recorded concurrently are all kept")
    void concurrentRecordingLosesNothing() throws Exception {
        // A JMH @State(Scope.Benchmark) object is shared by every benchmark thread, so
        // this is the access pattern a multi-threaded run actually produces.
        LatencySamples samples = new LatencySamples();
        int threads = 4;
        int perThread = 2_000;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    samples.record(1.0);
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        assertThat(samples.count()).isEqualTo(threads * perThread);
    }

    @Test
    @DisplayName("a non-finite or negative latency is rejected at the point of recording")
    void invalidSamplesAreRejected() {
        LatencySamples samples = new LatencySamples();

        assertThatThrownBy(() -> samples.record(Double.NaN))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> samples.record(-0.5))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
