package excalibur.api.endpoint;

import java.util.concurrent.TimeUnit;

/**
 * What is left of a request's time budget.
 *
 * <p>Every stage of a query that can block is given the remainder rather than the original
 * timeout. Handing each stage the full budget lets a request that has already spent most of it
 * run for a multiple of what the caller asked for.</p>
 *
 * @author ExcaliburDB
 */
final class Deadline {

    private Deadline() {
    }

    /**
     * Returns the milliseconds left before a deadline, never less than one.
     *
     * <p>Never zero: a zero timeout means "do not wait at all" to most blocking calls, which
     * would abandon work that has already been started rather than collect it.</p>
     *
     * @param deadlineNanos {@link System#nanoTime()} value of the deadline
     * @return Remaining budget in milliseconds
     */
    static long remainingMillis(long deadlineNanos) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return Math.max(1L, remaining);
    }
}
