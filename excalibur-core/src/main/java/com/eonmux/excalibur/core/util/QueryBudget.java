package com.eonmux.excalibur.core.util;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The time one query may take, and the caller's power to take it back.
 *
 * <p>Handed down through every stage of a query rather than a timeout per stage. A stage given the
 * whole timeout lets a request that has already spent most of it run for a multiple of what the
 * caller asked for; a stage given what is left of one deadline cannot.</p>
 *
 * <h2>Two ways to stop, answered differently</h2>
 * <p>A deadline that passes is an ordinary outcome: the stage that meets it stops, hands on what it
 * has, and says so with {@link #markTimedOut()}, so the answer can be the best one the budget
 * bought and can report that it is partial. A cancellation is the caller withdrawing the question,
 * and nothing is handed on - {@link #throwIfCancelled()} ends the query with a
 * {@link CancellationException} wherever it is checked.</p>
 *
 * <h2>Blocking calls</h2>
 * <p>A model call is the one stage that can wait far longer than the query has: its own client
 * timeout, times its retries. {@link #call(Call)} runs one on a thread of its own and waits for it
 * only as long as the budget allows, interrupting it when the deadline passes or the query is
 * cancelled - which a socket read and a retry back-off both answer at once. Only work that touches
 * no shared {@link java.nio.channels.FileChannel} may be run through it, for the reason
 * {@link StopSignal} gives: an interrupt closes such a channel for every thread using it.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Cancellation is set once and never cleared, and so is the timed-out mark.</p>
 *
 * @author ExcaliburDB
 */
public final class QueryBudget {

    /** Work bounded by the budget, throwing only unchecked exceptions. */
    @FunctionalInterface
    public interface Call<T> {

        /**
         * Runs the work.
         *
         * @return Its result
         */
        T run();
    }

    /** Whether there is a deadline; false for a caller that named none. */
    private final boolean bounded;

    /** {@link System#nanoTime()} value of the deadline; meaningless when unbounded. */
    private final long deadlineNanos;

    /** Run when the query is cancelled; each removes itself once its wait is over. */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean cancelled;

    private volatile String cancellationReason;

    private volatile boolean timedOut;

    private QueryBudget(boolean bounded, long deadlineNanos) {
        this.bounded = bounded;
        this.deadlineNanos = deadlineNanos;
    }

    /**
     * A budget with no deadline.
     *
     * <p>What a caller that names no deadline gets, so every stage can take a budget without a
     * null check. It can still be cancelled, which a stage sees the next time it checks.</p>
     *
     * @return A new budget that never expires
     */
    public static QueryBudget unbounded() {
        return new QueryBudget(false, 0L);
    }

    /**
     * A budget that expires {@code timeoutMillis} from now.
     *
     * @param timeoutMillis How long the query may take, at least 1
     * @return A new budget
     * @throws IllegalArgumentException if the timeout is not positive
     */
    public static QueryBudget ofMillis(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive, got " + timeoutMillis);
        }
        return until(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
    }

    /**
     * A budget that expires at a {@link System#nanoTime()} instant.
     *
     * @param deadlineNanos The instant the query's time is up
     * @return A new budget
     */
    public static QueryBudget until(long deadlineNanos) {
        return new QueryBudget(true, deadlineNanos);
    }

    /** Whether this budget has a deadline at all. */
    public boolean isBounded() {
        return bounded;
    }

    /** Whether the deadline has passed. Never true for an unbounded budget. */
    public boolean isExpired() {
        return isBounded() && System.nanoTime() - deadlineNanos >= 0;
    }

    /** Whether the caller withdrew the query. */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Whether a stage should stop now, for either reason.
     *
     * <p>For loops that check often and decide afterwards which of the two it was.</p>
     *
     * @return true once the query is cancelled or its deadline has passed
     */
    public boolean isExhausted() {
        return cancelled || isExpired();
    }

    /**
     * Nanoseconds left before the deadline; zero once it has passed.
     *
     * @return The remainder, or {@link Long#MAX_VALUE} for an unbounded budget
     */
    public long remainingNanos() {
        if (!isBounded()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    /**
     * Withdraws the query.
     *
     * <p>Idempotent. Every model call waiting on this budget is interrupted and gives up at
     * once; every stage that checks next ends the query.</p>
     *
     * @param reason Why, for the exception a stage throws; may be null
     * @return true when this call is the one that cancelled it
     */
    public boolean cancel(String reason) {
        synchronized (this) {
            if (cancelled) {
                return false;
            }
            cancellationReason = reason;
            cancelled = true;
        }
        for (Runnable listener : listeners) {
            listener.run();
        }
        return true;
    }

    /**
     * Ends the query here if the caller has withdrawn it.
     *
     * @throws CancellationException if the query was cancelled
     */
    public void throwIfCancelled() {
        if (cancelled) {
            throw cancellation();
        }
    }

    /**
     * Records that a stage stopped short because the deadline passed.
     *
     * <p>Called by the stage that gave up, not by whoever notices the time afterwards: an answer
     * that completed every stage just inside its deadline is whole, and reporting it as partial
     * because the clock had moved on by the time it was rendered would be false.</p>
     */
    public void markTimedOut() {
        timedOut = true;
    }

    /** Whether any stage stopped short on the deadline, so the answer is partial. */
    public boolean timedOut() {
        return timedOut;
    }

    /**
     * Runs a blocking call and waits for it no longer than the budget allows.
     *
     * <p>On an unbounded budget the call is simply run on the calling thread, as it would have
     * been without one, and a cancellation is seen once it returns. On a bounded one it runs on a
     * virtual thread of its own, which is interrupted when the deadline passes or the query is
     * cancelled.</p>
     *
     * @param work The call
     * @param <T>  What it returns
     * @return What it returned
     * @throws TimeoutException      if the deadline passed first; the call has been interrupted
     * @throws CancellationException if the query was cancelled, before or during the call
     * @throws RuntimeException      whatever the call threw
     */
    public <T> T call(Call<T> work) throws TimeoutException {
        throwIfCancelled();
        if (!isBounded()) {
            T result = work.run();
            throwIfCancelled();
            return result;
        }
        if (isExpired()) {
            throw new TimeoutException("the query's deadline passed before the call started");
        }
        FutureTask<T> task = new FutureTask<>(work::run);
        Runnable interrupt = () -> task.cancel(true);
        listeners.add(interrupt);
        try {
            // Checked again after the listener is in place: a cancel between the check above and
            // the add would otherwise have run no listener and left this wait to the deadline.
            throwIfCancelled();
            Thread.ofVirtual().name("query-budget-call").start(task);
            return task.get(remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw e;
        } catch (CancellationException e) {
            throw cancellation();
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted while waiting for a call");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("the call failed", cause);
        } finally {
            listeners.remove(interrupt);
        }
    }

    private CancellationException cancellation() {
        String reason = cancellationReason;
        return new CancellationException(reason != null ? reason : "the query was cancelled");
    }

    @Override
    public String toString() {
        String state = cancelled ? "cancelled" : isExpired() ? "expired" : "running";
        return "QueryBudget[" + state + (isBounded()
            ? ", " + TimeUnit.NANOSECONDS.toMillis(remainingNanos()) + "ms left" : "") + "]";
    }
}
