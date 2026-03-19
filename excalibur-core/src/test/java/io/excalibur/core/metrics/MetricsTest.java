package io.excalibur.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for Metrics class.
 */
@DisplayName("Metrics Tests")
class MetricsTest {

    @BeforeEach
    void resetMetrics() {
        Metrics.Counter.set(0);
        Metrics.QueueDepth.set(0);
        Metrics.EntriesIngested.set(0);
        Metrics.QueriesExecuted.set(0);
        Metrics.CuckooFPRateCount.set(0);
        Metrics.CuckooFNRateCount.set(0);
    }

    @Nested
    @DisplayName("Counter Operations")
    class CounterTests {

        @Test
        @DisplayName("Should get queue depth")
        void shouldGetQueueDepth() {
            long depth = Metrics.getQueueDepth();
            assertThat(depth).isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("Should get entries ingested")
        void shouldGetEntriesIngested() {
            Metrics.EntriesIngested.add(100);
            assertThat(Metrics.getEntriesIngested()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Should get queries executed")
        void shouldGetQueriesExecuted() {
            Metrics.QueriesExecuted.add(50);
            assertThat(Metrics.getQueriesExecuted()).isEqualTo(50L);
        }
    }

    @Nested
    @DisplayName("Health Score Management")
    class HealthScoreTests {

        @Test
        @DisplayName("Should set and get health score")
        void shouldSetAndGetHealthScore() {
            Metrics.setHealthScore(0.75);
            double score = Metrics.getHealthScore();
            assertThat(score).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("Should clamp health score below 0.0")
        void shouldClampBelowZero() {
            Metrics.setHealthScore(-0.5);
            assertThat(Metrics.getHealthScore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should clamp health score above 1.0")
        void shouldClampAboveOne() {
            Metrics.setHealthScore(1.5);
            assertThat(Metrics.getHealthScore()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Cuckoo Filter Rate Tracking")
    class CuckooFilterRateTests {

        @Test
        @DisplayName("Should record false positive count")
        void shouldRecordFalsePositive() {
            Metrics.Counter.set(100);
            Metrics.recordCuckooFalsePositive(5);
            assertThat(Metrics.CuckooFPRateCount.get()).isEqualTo(5L);
        }

        @Test
        @DisplayName("Should record false negative count")
        void shouldRecordFalseNegative() {
            Metrics.Counter.set(100);
            Metrics.recordCuckooFalseNegative(2);
            assertThat(Metrics.CuckooFNRateCount.get()).isEqualTo(2L);
        }

        @Test
        @DisplayName("Should reject negative false positive count")
        void shouldRejectNegativeFPCount() {
            assertThatThrownBy(() -> Metrics.recordCuckooFalsePositive(-1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject negative false negative count")
        void shouldRejectNegativeFNCount() {
            assertThatThrownBy(() -> Metrics.recordCuckooFalseNegative(-1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should return zero rate when no operations")
        void shouldReturnZeroRateWhenNoOps() {
            Metrics.Counter.set(0);
            assertThat(Metrics.getCuckooFPRate()).isEqualTo(0.0);
            assertThat(Metrics.getCuckooFNRate()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("All Metrics Retrieval")
    class GetAllMetricsTests {

        @Test
        @DisplayName("Should return all metrics as map")
        void shouldReturnAllMetrics() {
            Map<String, Object> metrics = Metrics.getAllMetrics();

            assertThat(metrics).containsKeys(
                "query_latency",
                "health_score",
                "queue_depth",
                "entries_ingested",
                "queries_executed",
                "cuckoo_fp_rate",
                "cuckoo_fn_rate"
            );
        }
    }
}
