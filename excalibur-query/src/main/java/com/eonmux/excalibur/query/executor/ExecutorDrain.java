package com.eonmux.excalibur.query.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stops an executor that query work runs on without interrupting that work, and says whether
 * it stopped.
 *
 * <h2>Why no {@code shutdownNow}</h2>
 * <p>Interrupting one query is safe, and the pipeline does it to abandon a query that failed or
 * ran out of time: a segment search reads graphs, write logs and segments through memory
 * mappings, which an interrupt cannot close, and the only thing it blocks on is the scoring
 * model's network call. Interrupting every query at shutdown buys nothing on top of that. It
 * does not make a thread that is inside a mapped read leave any sooner - nothing in such a read
 * looks at the flag - so it cannot shorten the wait, and a shutdown that stopped waiting on the
 * strength of it would let its caller close the storage engine, and the Arena those mappings
 * belong to, under reads that were still running. Unmapping memory a thread is reading is not an
 * exception anybody can catch; it ends the process.</p>
 *
 * <p>So the executor is told to take no more work, the work it has is given a bounded time to
 * finish, and the answer - finished or not - goes back to the caller, whose job it is to leave
 * the storage open when it is not.</p>
 *
 * @author ExcaliburDB
 */
public final class ExecutorDrain {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutorDrain.class);

    private ExecutorDrain() {
    }

    /**
     * Refuses new work to the executor and waits for the work it has.
     *
     * <p>Idempotent: an executor that has already terminated answers true at once, and one still
     * running after an earlier call is waited for again.</p>
     *
     * @param executor    The executor to stop
     * @param waitMillis  How long to wait for running work
     * @param description What the executor runs, for the warning when it outlasts the wait
     * @return true if the executor has terminated, false if work was still running when the wait
     *         ran out or the waiting thread was interrupted
     */
    public static boolean drain(ExecutorService executor, long waitMillis, String description) {
        Objects.requireNonNull(executor, "executor cannot be null");
        executor.shutdown();
        try {
            if (executor.awaitTermination(Math.max(0, waitMillis), TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            // Restore the flag: swallowing it leaves the thread that is shutting the node down
            // unable to observe that it was itself interrupted.
            Thread.currentThread().interrupt();
        }
        // Said out loud: the caller is about to decide whether to close resources these
        // threads are still reading through, and silence here is what makes a wrong decision
        // look like a storage bug.
        LOGGER.warn("{} still running {}ms after shutdown was requested; the storage they read "
            + "through must stay open", description, waitMillis);
        return false;
    }
}
