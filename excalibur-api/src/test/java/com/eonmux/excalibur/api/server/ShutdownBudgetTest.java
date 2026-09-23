package com.eonmux.excalibur.api.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One deadline for the whole of a node's shutdown, with the durable writes' share held back.
 *
 * <p>The waits a shutdown makes used to be fixed per step and added up: a listener's grace, then
 * a minute each for a reindex, a compaction and a graph build, then the request drain - several
 * minutes in the worst case, against a supervisor documented to send SIGKILL after forty
 * seconds. The kill then landed before the step the whole shutdown exists for, the publish of
 * the chunk index's buffer. The budget is what makes the worst case a number an operator can
 * set a stop timeout from.</p>
 */
@DisplayName("Shutdown budget")
class ShutdownBudgetTest {

    private static final Duration TOTAL = Duration.ofSeconds(30);

    private final AtomicLong nanos = new AtomicLong(1_000L);

    private ShutdownBudget budget() {
        return new ShutdownBudget(TOTAL, nanos::get);
    }

    private void advance(Duration by) {
        nanos.addAndGet(by.toNanos());
    }

    @Nested
    @DisplayName("sharing out the time")
    class Sharing {

        @Test
        @DisplayName("the drains get the total less the durable writes' reserve")
        void drainsGetTheTotalLessTheReserve() {
            assertThat(budget().forDrains())
                .isEqualTo(TOTAL.minus(ShutdownBudget.DURABILITY_RESERVE));
        }

        @Test
        @DisplayName("time spent comes out of what is left, for the drains and for the whole")
        void timeSpentIsDeducted() {
            ShutdownBudget budget = budget();
            advance(Duration.ofSeconds(7));

            assertThat(budget.forDrains()).isEqualTo(Duration.ofSeconds(13));
            assertThat(budget.remaining()).isEqualTo(Duration.ofSeconds(23));
        }

        @Test
        @DisplayName("the drains' share runs out while the reserve is still whole")
        void theReserveOutlivesTheDrains() {
            ShutdownBudget budget = budget();
            advance(Duration.ofSeconds(25));

            assertThat(budget.forDrains()).isZero();
            assertThat(budget.remaining()).isEqualTo(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("nothing left is zero, never negative")
        void exhaustedIsZero() {
            ShutdownBudget budget = budget();
            advance(Duration.ofMinutes(5));

            assertThat(budget.forDrains()).isZero();
            assertThat(budget.remaining()).isZero();
        }

        @Test
        @DisplayName("the listener is given half the drains' share, in the whole seconds it takes")
        void theListenerGetsHalf() {
            ShutdownBudget budget = budget();
            assertThat(budget.listenerGraceSeconds()).isEqualTo(10);

            advance(Duration.ofMillis(19_500));
            assertThat(budget.listenerGraceSeconds())
                .as("rounded down: the listener would otherwise wait past the drains' share")
                .isZero();
        }

        @Test
        @DisplayName("a total no larger than the reserve is refused")
        void aTotalInsideTheReserveIsRefused() {
            assertThatThrownBy(() -> new ShutdownBudget(ShutdownBudget.DURABILITY_RESERVE,
                nanos::get))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("reading the configured total")
    class Configured {

        @Test
        @DisplayName("nothing configured is the default")
        void theDefault() {
            assertThat(ShutdownBudget.configuredTimeout(Map.of()))
                .isEqualTo(Duration.ofSeconds(ShutdownBudget.DEFAULT_TIMEOUT_SECONDS));
        }

        @Test
        @DisplayName("the key is read, surrounding space tolerated")
        void theKeyIsRead() {
            assertThat(ShutdownBudget.configuredTimeout(
                Map.of(ShutdownBudget.KEY_TIMEOUT, " 55 ")))
                .isEqualTo(Duration.ofSeconds(55));
        }

        @Test
        @DisplayName("a blank value is not a configured value")
        void blankIsTheDefault() {
            assertThat(ShutdownBudget.configuredTimeout(Map.of(ShutdownBudget.KEY_TIMEOUT, " ")))
                .isEqualTo(Duration.ofSeconds(ShutdownBudget.DEFAULT_TIMEOUT_SECONDS));
        }

        @Test
        @DisplayName("a value that is not a number stops startup rather than defaulting")
        void aNonNumberIsFatal() {
            assertThatThrownBy(() -> ShutdownBudget.configuredTimeout(
                Map.of(ShutdownBudget.KEY_TIMEOUT, "30s")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ShutdownBudget.KEY_TIMEOUT)
                .hasMessageContaining("30s");
        }

        @Test
        @DisplayName("a value outside the range stops startup, naming the range")
        void outOfRangeIsFatal() {
            assertThatThrownBy(() -> ShutdownBudget.configuredTimeout(Map.of(
                ShutdownBudget.KEY_TIMEOUT, String.valueOf(ShutdownBudget.MIN_TIMEOUT_SECONDS - 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(ShutdownBudget.MIN_TIMEOUT_SECONDS));
            assertThatThrownBy(() -> ShutdownBudget.configuredTimeout(Map.of(
                ShutdownBudget.KEY_TIMEOUT, String.valueOf(ShutdownBudget.MAX_TIMEOUT_SECONDS + 1))))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the bounds of the range are usable")
        void theBoundsAreAccepted() {
            assertThat(ShutdownBudget.configuredTimeout(Map.of(ShutdownBudget.KEY_TIMEOUT,
                String.valueOf(ShutdownBudget.MIN_TIMEOUT_SECONDS))))
                .isEqualTo(Duration.ofSeconds(ShutdownBudget.MIN_TIMEOUT_SECONDS));
            assertThat(ShutdownBudget.configuredTimeout(Map.of(ShutdownBudget.KEY_TIMEOUT,
                String.valueOf(ShutdownBudget.MAX_TIMEOUT_SECONDS))))
                .isEqualTo(Duration.ofSeconds(ShutdownBudget.MAX_TIMEOUT_SECONDS));
        }
    }
}
