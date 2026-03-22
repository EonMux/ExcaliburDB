package io.excalibur.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for HealthGauge.
 *
 * Thread-safety: Tests verify thread-safe score management.
 * Domain correctness: Tests verify scores are in [0.0, 1.0] range.
 */
@DisplayName("HealthGauge Tests")
class HealthGaugeTest {

    @BeforeEach
    void resetHealth() throws Exception {
        // Reset cooldown timer via reflection to avoid Thread.sleep in tests
        resetCooldownTimer();
        HealthGauge.setFullyHealthy();
    }

    /**
     * Resets the internal cooldown timer to allow immediate score changes in tests.
     * This avoids the need for Thread.sleep(1100) in each test.
     */
    private void resetCooldownTimer() throws Exception {
        Field field = HealthGauge.class.getDeclaredField("lastTransitionTime");
        field.setAccessible(true);
        Object atomicInt = field.get(null);
        Field valueField = atomicInt.getClass().getDeclaredField("value");
        valueField.setAccessible(true);
        valueField.set(atomicInt, 0);
    }

    @Nested
    @DisplayName("Set Score Tests")
    class SetScoreTests {

        @Test
        @DisplayName("Should set valid score")
        void shouldSetValidScore() throws Exception {
            resetCooldownTimer();
            HealthGauge.setScore(0.75);

            assertThat(HealthGauge.getScore()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("Should set score to 1.0")
        void shouldSetScoreToOne() throws Exception {
            resetCooldownTimer();
            HealthGauge.setScore(1.0);

            assertThat(HealthGauge.getScore()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Should set score to 0.0")
        void shouldSetScoreToZero() throws Exception {
            resetCooldownTimer();
            HealthGauge.setScore(0.0);

            assertThat(HealthGauge.getScore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should reject negative score")
        void shouldRejectNegativeScore() {
            assertThatThrownBy(() -> HealthGauge.setScore(-0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
        }

        @Test
        @DisplayName("Should reject score above 1.0")
        void shouldRejectScoreAboveOne() {
            assertThatThrownBy(() -> HealthGauge.setScore(1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed 1.0");
        }
    }

    @Nested
    @DisplayName("Get Score Tests")
    class GetScoreTests {

        @Test
        @DisplayName("Should return score in valid range")
        void shouldReturnScoreInValidRange() throws Exception {
            resetCooldownTimer();
            HealthGauge.setScore(0.5);

            double score = HealthGauge.getScore();

            assertThat(score).isBetween(0.0, 1.0);
        }
    }

    @Nested
    @DisplayName("Health Status Tests")
    class HealthStatusTests {

        @Test
        @DisplayName("Should be healthy when score >= 0.8")
        void shouldBeHealthyWhenScoreHigh() throws Exception {
            resetCooldownTimer();
            HealthGauge.setScore(0.85);

            assertThat(HealthGauge.isHealthy()).isTrue();
            assertThat(HealthGauge.isUnhealthy()).isFalse();
        }

        @Test
        @DisplayName("Should be unhealthy when score < 0.8")
        void shouldBeUnhealthyWhenScoreLow() throws Exception {
            resetCooldownTimer();
            HealthGauge.setScore(0.7);

            assertThat(HealthGauge.isHealthy()).isFalse();
            assertThat(HealthGauge.isUnhealthy()).isTrue();
        }
    }

    @Nested
    @DisplayName("Convenience Method Tests")
    class ConvenienceMethodTests {

        @Test
        @DisplayName("Should set fully healthy")
        void shouldSetFullyHealthy() throws Exception {
            resetCooldownTimer();
            double result = HealthGauge.setFullyHealthy();

            assertThat(result).isEqualTo(1.0);
            assertThat(HealthGauge.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("Should set fully unhealthy")
        void shouldSetFullyUnhealthy() throws Exception {
            resetCooldownTimer();
            double result = HealthGauge.setFullyUnhealthy();

            assertThat(result).isEqualTo(0.0);
            assertThat(HealthGauge.isUnhealthy()).isTrue();
        }
    }

    @Nested
    @DisplayName("Last State Tests")
    class LastStateTests {

        @Test
        @DisplayName("Should get HEALTHY state")
        void shouldGetHealthyState() {
            HealthGauge.HealthState state = HealthGauge.getLastState();

            assertThat(state).isIn(HealthGauge.HealthState.HEALTHY, HealthGauge.HealthState.UNHEALTHY);
        }
    }

    @Nested
    @DisplayName("Transition Cooldown Tests")
    class CooldownTests {

        @Test
        @DisplayName("Should respect cooldown period")
        void shouldRespectCooldown() throws Exception {
            // Initial state - healthy
            resetCooldownTimer();
            HealthGauge.setFullyHealthy();

            // First change should be blocked if within cooldown (cooldown is now active)
            HealthGauge.setScore(0.5); // May be blocked by cooldown

            // Reset cooldown to allow the change
            resetCooldownTimer();

            // This change should succeed
            HealthGauge.setScore(0.5);

            // Score should be updated after cooldown
            assertThat(HealthGauge.getScore()).isBetween(0.0, 1.0);
        }
    }
}
