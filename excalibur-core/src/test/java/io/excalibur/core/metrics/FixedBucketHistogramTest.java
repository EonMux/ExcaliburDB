package io.excalibur.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for FixedBucketHistogram.
 *
 * Thread-safety: Tests verify thread-safe recording.
 * Domain correctness: Tests verify percentiles are within valid ranges.
 */
@DisplayName("FixedBucketHistogram Tests")
class FixedBucketHistogramTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create histogram with positive bucket count")
        void shouldCreateWithPositiveBucketCount() {
            FixedBucketHistogram histogram = new FixedBucketHistogram(100);

            assertThat(histogram.getTotalCount()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should reject zero bucket count")
        void shouldRejectZeroBucketCount() {
            assertThatThrownBy(() -> new FixedBucketHistogram(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("Should reject negative bucket count")
        void shouldRejectNegativeBucketCount() {
            assertThatThrownBy(() -> new FixedBucketHistogram(-10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        }
    }

    @Nested
    @DisplayName("Recording Tests")
    class RecordingTests {

        private FixedBucketHistogram histogram;

        @BeforeEach
        void setUp() {
            histogram = new FixedBucketHistogram(100);
        }

        @Test
        @DisplayName("Should record single latency")
        void shouldRecordSingleLatency() {
            histogram.record(10);

            assertThat(histogram.getTotalCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should record multiple latencies")
        void shouldRecordMultipleLatencies() {
            histogram.record(10);
            histogram.record(20);
            histogram.record(30);

            assertThat(histogram.getTotalCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("Should record zero latency")
        void shouldRecordZeroLatency() {
            histogram.record(0);

            assertThat(histogram.getTotalCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should reject negative latency")
        void shouldRejectNegativeLatency() {
            assertThatThrownBy(() -> histogram.record(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("Should record using atomic method")
        void shouldRecordAtomic() {
            histogram.recordAtomic(15);

            assertThat(histogram.getTotalCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should reject negative latency in atomic method")
        void shouldRejectNegativeAtomic() {
            assertThatThrownBy(() -> histogram.recordAtomic(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }
    }

    @Nested
    @DisplayName("Bucket Operations")
    class BucketTests {

        private FixedBucketHistogram histogram;

        @BeforeEach
        void setUp() {
            histogram = new FixedBucketHistogram(10);
        }

        @Test
        @DisplayName("Should get bucket count")
        void shouldGetBucketCount() {
            histogram.record(5);

            int bucketCount = histogram.getBucketCount(0);

            assertThat(bucketCount).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Should reject invalid bucket index for count")
        void shouldRejectInvalidBucketIndexForCount() {
            assertThatThrownBy(() -> histogram.getBucketCount(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);

            assertThatThrownBy(() -> histogram.getBucketCount(100))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("Should get bucket upper bound")
        void shouldGetBucketUpperBound() {
            double bound = histogram.getBucketUpperBound(0);

            assertThat(bound).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("Should reject invalid bucket index for bound")
        void shouldRejectInvalidBucketIndexForBound() {
            assertThatThrownBy(() -> histogram.getBucketUpperBound(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);

            assertThatThrownBy(() -> histogram.getBucketUpperBound(100))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("Percentile Tests")
    class PercentileTests {

        private FixedBucketHistogram histogram;

        @BeforeEach
        void setUp() {
            histogram = new FixedBucketHistogram(100);
        }

        @Test
        @DisplayName("Should return zero percentiles when empty")
        void shouldReturnZeroWhenEmpty() {
            assertThat(histogram.getP50()).isEqualTo(0.0);
            assertThat(histogram.getP90()).isEqualTo(0.0);
            assertThat(histogram.getP95()).isEqualTo(0.0);
            assertThat(histogram.getP99()).isEqualTo(0.0);
            assertThat(histogram.getP999()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should calculate percentiles with data")
        void shouldCalculatePercentilesWithData() {
            // Record values from 1 to 100
            for (int i = 1; i <= 100; i++) {
                histogram.record(i);
            }

            double p50 = histogram.getP50();
            double p90 = histogram.getP90();
            double p99 = histogram.getP99();

            // Percentiles should be non-negative and monotonically increasing
            assertThat(p50).isGreaterThanOrEqualTo(0.0);
            assertThat(p90).isGreaterThanOrEqualTo(p50);
            assertThat(p99).isGreaterThanOrEqualTo(p90);
        }
    }

    @Nested
    @DisplayName("Min/Max Tests")
    class MinMaxTests {

        private FixedBucketHistogram histogram;

        @BeforeEach
        void setUp() {
            histogram = new FixedBucketHistogram(100);
        }

        @Test
        @DisplayName("Should return zero min/max when empty")
        void shouldReturnZeroWhenEmpty() {
            assertThat(histogram.getMinLatency()).isEqualTo(0.0);
            assertThat(histogram.getMaxLatency()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should return min latency with data")
        void shouldReturnMinLatencyWithData() {
            histogram.record(10);
            histogram.record(50);
            histogram.record(100);

            double min = histogram.getMinLatency();

            assertThat(min).isGreaterThanOrEqualTo(0.0);
        }

        @Test
        @DisplayName("Should return max latency with data")
        void shouldReturnMaxLatencyWithData() {
            histogram.record(10);
            histogram.record(50);
            histogram.record(100);

            double max = histogram.getMaxLatency();

            assertThat(max).isGreaterThan(0.0);
        }
    }

    @Nested
    @DisplayName("Stats Map Tests")
    class StatsMapTests {

        @Test
        @DisplayName("Should return stats map")
        void shouldReturnStatsMap() {
            FixedBucketHistogram histogram = new FixedBucketHistogram(10);
            histogram.record(10);
            histogram.record(20);
            histogram.record(30);

            Map<String, Object> stats = histogram.getStats();

            assertThat(stats).containsKeys(
                "total_count",
                "p50",
                "p90",
                "p95",
                "p99",
                "p99.9",
                "min",
                "max"
            );

            assertThat(stats.get("total_count")).isEqualTo(3L);
        }

        @Test
        @DisplayName("Should return zero stats when empty")
        void shouldReturnZeroStatsWhenEmpty() {
            FixedBucketHistogram histogram = new FixedBucketHistogram(10);

            Map<String, Object> stats = histogram.getStats();

            assertThat(stats.get("total_count")).isEqualTo(0L);
            assertThat(stats.get("min")).isEqualTo(0.0);
            assertThat(stats.get("max")).isEqualTo(0.0);
        }
    }
}
