package excalibur.query.pipeline;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The wall clock one query execution has left.
 *
 * <p>The pipeline arms its deadline once, over the whole run. A stage that dispatches work with
 * a timeout of its own therefore cannot be handed the request's timeout: measured from when that
 * stage starts, such a deadline necessarily falls <em>after</em> the pipeline's, so the pipeline
 * aborts first and everything the stage had already collected is discarded. What a stage may
 * spend is the time still left, less a share held back for the stages that follow it.</p>
 *
 * <h2>Why the start and the budget are kept apart</h2>
 * <p>Holding a precomputed deadline instant would overflow for a large timeout, and
 * {@link System#nanoTime()} readings are only meaningful as differences - the origin is
 * arbitrary and may be negative. Elapsed time is the difference, which is correct across the
 * counter's wrap; the budget is then a plain subtraction that cannot overflow.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable. The remaining time is read from the clock on each call, so it is safe to ask
 * from any thread and answers differently each time.</p>
 */
final class QueryDeadline {

    /**
     * The least a stage is ever given. Dispatching with a zero timeout cancels the work before
     * it can produce anything, which is a different outcome from failing to finish in time.
     */
    private static final long MINIMUM_STAGE_MILLIS = 1;

    private final long startNanos;

    private final long budgetNanos;

    private QueryDeadline(long startNanos, long budgetNanos) {
        this.startNanos = startNanos;
        this.budgetNanos = budgetNanos;
    }

    /**
     * A deadline of {@code budgetMillis} measured from {@code startNanos}.
     *
     * @param startNanos   A {@link System#nanoTime()} reading taken as the query began
     * @param budgetMillis The request's timeout in milliseconds, which is positive
     * @return The deadline
     * @throws IllegalArgumentException if budgetMillis is not positive
     */
    static QueryDeadline from(long startNanos, long budgetMillis) {
        if (budgetMillis <= 0) {
            throw new IllegalArgumentException("Timeout must be positive: " + budgetMillis);
        }
        // Saturates rather than wraps for an absurd timeout, which reads as "effectively
        // forever" - the meaning the caller asked for.
        return new QueryDeadline(startNanos, TimeUnit.MILLISECONDS.toNanos(budgetMillis));
    }

    /**
     * The time left before the query is abandoned.
     *
     * @return Milliseconds remaining, or zero once the deadline has passed
     */
    long remainingMillis() {
        // A clock that appears to run backwards has measured no progress, not negative progress.
        long elapsedNanos = Math.max(0, System.nanoTime() - startNanos);
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, budgetNanos - elapsedNanos));
    }

    /**
     * What a stage may spend, keeping back a share of the remaining time for the stages after it.
     *
     * <p>The share is a fraction rather than a fixed margin because the stages that follow are
     * bounded work over a bounded candidate set: what they need scales with the query, not with
     * the clock. The result is always positive - a stage asked to run is given the chance to
     * produce something, even when the query is already out of time.</p>
     *
     * @param fractionHeldBack The share reserved for later stages, in [0, 1)
     * @return The budget for this stage, never longer than the time remaining
     * @throws IllegalArgumentException if the fraction is outside [0, 1)
     */
    Duration budgetHoldingBack(double fractionHeldBack) {
        if (!(fractionHeldBack >= 0.0) || fractionHeldBack >= 1.0) {
            throw new IllegalArgumentException(
                "Fraction held back must be in [0, 1): " + fractionHeldBack);
        }
        long remaining = remainingMillis();
        long spendable = remaining - Math.round(remaining * fractionHeldBack);
        return Duration.ofMillis(Math.max(MINIMUM_STAGE_MILLIS, spendable));
    }

    @Override
    public String toString() {
        return "QueryDeadline{remaining=" + remainingMillis() + "ms}";
    }
}
