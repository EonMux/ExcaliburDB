package com.eonmux.excalibur.query.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What one query execution has left, and what a stage may take of it.
 */
@DisplayName("QueryDeadline")
class QueryDeadlineTest {

    private static final long ONE_MINUTE_MILLIS = 60_000;

    @Test
    @DisplayName("a timeout that is not positive is not a deadline")
    void rejectsANonPositiveBudget() {
        long now = System.nanoTime();

        assertThatThrownBy(() -> QueryDeadline.from(now, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
        assertThatThrownBy(() -> QueryDeadline.from(now, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the time left counts down from the budget")
    void remainingCountsDown() {
        QueryDeadline deadline = QueryDeadline.from(System.nanoTime(), ONE_MINUTE_MILLIS);

        assertThat(deadline.remainingMillis())
            .isGreaterThan(ONE_MINUTE_MILLIS - 5_000)
            .isLessThanOrEqualTo(ONE_MINUTE_MILLIS);
    }

    @Test
    @DisplayName("a deadline already passed has no time left rather than negative time")
    void anExpiredDeadlineHasNoTimeLeft() {
        QueryDeadline deadline = QueryDeadline.from(
            System.nanoTime() - TimeUnit.SECONDS.toNanos(10), 1_000);

        assertThat(deadline.remainingMillis()).isZero();
    }

    @Test
    @DisplayName("an enormous timeout leaves an enormous amount of time, not none")
    void anEnormousTimeoutDoesNotWrapAround() {
        // A deadline held as an instant is startNanos + budget, which wraps for a budget this
        // large and reads back as a query that is already out of time.
        QueryDeadline deadline = QueryDeadline.from(System.nanoTime(), Long.MAX_VALUE);

        assertThat(deadline.remainingMillis()).isGreaterThan(TimeUnit.DAYS.toMillis(365));
        assertThat(deadline.budgetHoldingBack(0.05).toMillis())
            .isGreaterThan(TimeUnit.DAYS.toMillis(365));
    }

    @Test
    @DisplayName("a clock that reads backwards has spent nothing, not less than nothing")
    void aBackwardsClockSpendsNothing() {
        QueryDeadline deadline = QueryDeadline.from(
            System.nanoTime() + TimeUnit.SECONDS.toNanos(10), 1_000);

        assertThat(deadline.remainingMillis()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("a stage is given what is left less the share held back")
    void aStageGetsTheRemainderLessTheShare() {
        QueryDeadline deadline = QueryDeadline.from(System.nanoTime(), ONE_MINUTE_MILLIS);

        Duration budget = deadline.budgetHoldingBack(0.2);

        // The clock moves between the two reads, so the share is bounded rather than exact.
        assertThat(budget.toMillis())
            .isGreaterThan((long) (0.75 * ONE_MINUTE_MILLIS))
            .isLessThan((long) (0.81 * ONE_MINUTE_MILLIS));
        assertThat(budget.toMillis())
            .as("a stage must finish before the query it belongs to is abandoned")
            .isLessThan(deadline.remainingMillis());
    }

    @Test
    @DisplayName("holding back nothing spends everything that is left")
    void holdingBackNothingSpendsTheRemainder() {
        QueryDeadline deadline = QueryDeadline.from(System.nanoTime(), ONE_MINUTE_MILLIS);

        assertThat(deadline.budgetHoldingBack(0.0).toMillis())
            .isGreaterThan(ONE_MINUTE_MILLIS - 5_000);
    }

    @Test
    @DisplayName("a stage asked to run is always given a chance to produce something")
    void anExpiredDeadlineStillGivesAStageAMoment() {
        QueryDeadline deadline = QueryDeadline.from(
            System.nanoTime() - TimeUnit.SECONDS.toNanos(10), 1_000);

        // Zero would cancel the dispatched work before it started, reporting "did not finish
        // in time" for work that was never allowed to begin.
        assertThat(deadline.budgetHoldingBack(0.2)).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    @DisplayName("a share that is not a fraction of what is left is refused")
    void rejectsAShareOutsideTheUnitInterval() {
        QueryDeadline deadline = QueryDeadline.from(System.nanoTime(), ONE_MINUTE_MILLIS);

        assertThatThrownBy(() -> deadline.budgetHoldingBack(1.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("[0, 1)");
        assertThatThrownBy(() -> deadline.budgetHoldingBack(-0.1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deadline.budgetHoldingBack(Double.NaN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("describes itself by the time it has left")
    void describesItselfByWhatIsLeft() {
        QueryDeadline deadline = QueryDeadline.from(System.nanoTime(), ONE_MINUTE_MILLIS);

        assertThat(deadline.toString()).contains("remaining=").endsWith("ms}");
    }
}
