package com.eonmux.excalibur.api.retrieval;

import java.util.concurrent.Semaphore;

/**
 * The node-wide bound on how many BM25 searches may be running at once.
 *
 * <p>The vector half of a query stops when its deadline passes, because every stage of the
 * pipeline polls the cancellation token. The lexical half does not: it is a search inside the
 * lexical engine, and interrupting it is neither reliable nor safe. So a caller sending
 * {@code timeout_ms: 1} gets an immediate answer and leaves a full search running, and at the
 * request rate the guard allows, that work accumulates faster than it drains.</p>
 *
 * <p>This is the bound on what can accumulate, and it belongs to the node rather than to
 * either route that searches. Both reach BM25 - the second signal of a hybrid query, and the
 * whole answer on a node that does not embed its corpus - and a bound held by only one of them
 * is no bound at all on a deployment that uses the other.</p>
 *
 * <p>Sized to the cores available because the search is CPU-bound: more concurrent searches
 * than cores finish no sooner and only hold memory.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe; the permits are a semaphore.</p>
 */
public final class LexicalSearchPermits {

    /** How many BM25 searches may be in flight across all requests at once. */
    public static final int MAX_CONCURRENT =
        Math.max(2, Runtime.getRuntime().availableProcessors());

    private static final Semaphore PERMITS = new Semaphore(MAX_CONCURRENT);

    private LexicalSearchPermits() {
        throw new AssertionError("No instances");
    }

    /**
     * Claims a permit if one is free, without waiting.
     *
     * <p>Never blocks. A request that cannot get a permit is better answered - from the other
     * signal, or with a refusal - than queued behind one, because queueing makes the node
     * slower under exactly the load that made the permit scarce.</p>
     *
     * @return true if a permit was claimed and must later be released
     */
    public static boolean tryAcquire() {
        return PERMITS.tryAcquire();
    }

    /**
     * Returns a permit.
     *
     * <p>Release when the search stops, not when the request stops waiting for it. Releasing
     * at the call site would let abandoned searches pile up behind a permit count that says
     * they are not there.</p>
     */
    public static void release() {
        PERMITS.release();
    }

    /**
     * How many searches could start right now.
     *
     * @return The free permits
     */
    public static int available() {
        return PERMITS.availablePermits();
    }
}
