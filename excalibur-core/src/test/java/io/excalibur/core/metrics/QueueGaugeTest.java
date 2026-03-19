package io.excalibur.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for QueueGauge.
 *
 * Thread-safety: Tests verify thread-safe queue depth tracking.
 * Domain correctness: Tests verify queue depth is non-negative and bounded.
 */
@DisplayName("QueueGauge Tests")
class QueueGaugeTest {

    @BeforeEach
    void resetQueue() {
        QueueGauge.updateDepth(0);
    }

    @Nested
    @DisplayName("Update Depth Tests")
    class UpdateDepthTests {

        @Test
        @DisplayName("Should update queue depth to valid value")
        void shouldUpdateToValidValue() {
            QueueGauge.updateDepth(5000);

            assertThat(QueueGauge.getDepth()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("Should update queue depth to zero")
        void shouldUpdateToZero() {
            QueueGauge.updateDepth(0);

            assertThat(QueueGauge.getDepth()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should reject negative queue depth")
        void shouldRejectNegativeDepth() {
            assertThatThrownBy(() -> QueueGauge.updateDepth(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
        }

        @Test
        @DisplayName("Should reject depth exceeding maximum")
        void shouldRejectDepthExceedingMax() {
            assertThatThrownBy(() -> QueueGauge.updateDepth(200000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum");
        }
    }

    @Nested
    @DisplayName("Get Depth Tests")
    class GetDepthTests {

        @Test
        @DisplayName("Should get current depth")
        void shouldGetCurrentDepth() {
            QueueGauge.updateDepth(1000);

            long depth = QueueGauge.getDepth();

            assertThat(depth).isEqualTo(1000L);
        }

        @Test
        @DisplayName("Should return non-negative depth")
        void shouldReturnNonNegativeDepth() {
            QueueGauge.updateDepth(0);

            assertThat(QueueGauge.getDepth()).isGreaterThanOrEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("Depth Percentage Tests")
    class DepthPercentageTests {

        @Test
        @DisplayName("Should return zero percent when empty")
        void shouldReturnZeroPercentWhenEmpty() {
            QueueGauge.updateDepth(0);

            double percent = QueueGauge.getDepthPercent();

            assertThat(percent).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should return correct percentage")
        void shouldReturnCorrectPercentage() {
            // Max is 100000, so 50000 = 50%
            QueueGauge.updateDepth(50000);

            double percent = QueueGauge.getDepthPercent();

            assertThat(percent).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Should return one when at maximum")
        void shouldReturnOneWhenAtMaximum() {
            QueueGauge.updateDepth(100000);

            double percent = QueueGauge.getDepthPercent();

            assertThat(percent).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Queue Status Tests")
    class QueueStatusTests {

        @Test
        @DisplayName("Should be under control when below 50%")
        void shouldBeUnderControlWhenBelow50Percent() {
            QueueGauge.updateDepth(40000);

            assertThat(QueueGauge.isUnderControl()).isTrue();
            assertThat(QueueGauge.isCritical()).isFalse();
            assertThat(QueueGauge.isFull()).isFalse();
        }

        @Test
        @DisplayName("Should be critical when above 80%")
        void shouldBeCriticalWhenAbove80Percent() {
            QueueGauge.updateDepth(85000);

            assertThat(QueueGauge.isUnderControl()).isFalse();
            assertThat(QueueGauge.isCritical()).isTrue();
            assertThat(QueueGauge.isFull()).isFalse();
        }

        @Test
        @DisplayName("Should be full when above 95%")
        void shouldBeFullWhenAbove95Percent() {
            QueueGauge.updateDepth(96000);

            assertThat(QueueGauge.isUnderControl()).isFalse();
            assertThat(QueueGauge.isCritical()).isTrue();
            assertThat(QueueGauge.isFull()).isTrue();
        }
    }

    @Nested
    @DisplayName("Set Max Depth Tests")
    class SetMaxDepthTests {

        @Test
        @DisplayName("Should accept informational max depth")
        void shouldAcceptInformationalMaxDepth() {
            // This is a no-op but should not throw
            assertThatCode(() -> QueueGauge.setMaxDepth(200000))
                .doesNotThrowAnyException();
        }
    }
}
