package com.eonmux.excalibur.ingest.pipeline;

import java.nio.channels.ClosedByInterruptException;

/**
 * How a reindex in progress is told to stop, and where it is allowed to.
 *
 * <h2>Why the signal is the thread's interrupt flag</h2>
 * <p>Because that is the signal that already exists. {@code DELETE /v1/reindex} registers
 * {@code runner::interrupt} on the run's lease, so by the time anything here could read a flag
 * of its own the thread has already been interrupted. A second channel would have to be set by
 * the same caller and would be one more thing to get out of step; worse, the endpoint decides
 * whether a finished run was cancelled or broken by reading the interrupt flag and the cause
 * chain, so a run that stopped on a private flag would be filed as an outage.</p>
 *
 * <h2>The two shapes an interrupt takes, and why both are checked</h2>
 * <p>A run spends its time in calls that block - a source fetch over a socket, an embedding
 * request, a storage write - and each reports an interrupt in its own way. A call that threw
 * {@link InterruptedException} cleared the flag on the way out, and by the time the reindex sees
 * it the only trace is in the cause chain, usually several wrappers deep because every layer
 * between the fetch and here wraps what it caught. A call interrupted inside a channel throws
 * {@link ClosedByInterruptException}, an {@code IOException} that no test for
 * {@code InterruptedException} would find. A call that noticed and returned normally leaves only
 * the flag. {@code ReindexRun} in excalibur-api tests all three to classify the outcome, and
 * this tests all three to decide whether to keep going - the two have to agree, or a run stops
 * and is then reported as having failed.</p>
 *
 * <h2>Where the checkpoints are, and why not deeper</h2>
 * <p>Between documents, and nowhere inside one. A document is published whole or not at all, and
 * that promise is kept by writing every chunk, then sealing, then publishing under the
 * document's lock. A checkpoint anywhere in that sequence could leave chunks in storage that no
 * registry entry names, which is the state the rollback exists to undo and the atomicity filter
 * exists to hide. So a document already under way is finished, and the wait between asking a
 * reindex to stop and its stopping is one document.</p>
 *
 * <h2>Who owns the flag</h2>
 * <p>{@link SourceReindexPass}, and only it. The flag is cleared while the pass writes out what
 * it finished - a channel write on an interrupted thread throws
 * {@link ClosedByInterruptException} and closes that channel for good, which would lose the
 * registry the run had already earned - and set again immediately before the signal leaves, so
 * the endpoint reads what it expects. Nothing here touches the flag, so a caller can test for a
 * stop without changing whether one is in progress.</p>
 *
 * @see ReindexCancelledException
 */
final class ReindexCancellation {

    private ReindexCancellation() {
        throw new AssertionError("Utility class");
    }

    /**
     * Whether the run on this thread has been asked to stop.
     *
     * <p>Reads the flag without clearing it: several checkpoints ask in turn, and one that
     * consumed the answer would let the run continue past the next.</p>
     *
     * @return true if this thread has been interrupted
     */
    static boolean requested() {
        return Thread.currentThread().isInterrupted();
    }

    /**
     * Ends the run here if it has been asked to stop.
     *
     * <p>Call it between documents, never inside one.</p>
     *
     * @param sourceId       The source being reindexed, for the log
     * @param documentsDone  How many documents the run finished before this point
     * @param documentsTotal How many it would have finished
     * @throws ReindexCancelledException if the run on this thread has been asked to stop
     */
    static void checkpoint(String sourceId, long documentsDone, long documentsTotal) {
        if (requested()) {
            throw stopped(sourceId, documentsDone, documentsTotal);
        }
    }

    /**
     * Ends the run here if this failure is the interrupt arriving as an exception.
     *
     * <p>This is the guard that belongs in front of every {@code catch} broad enough to hold an
     * interrupt. Without it the stop is filed as one document's bad luck and the run walks the
     * rest of the crawl - the whole of it, on the large source that is the one anybody
     * cancels.</p>
     *
     * @param sourceId       The source being reindexed, for the log
     * @param documentsDone  How many documents the run finished before this point
     * @param documentsTotal How many it would have finished
     * @param failure        What the document's attempt threw
     * @throws ReindexCancelledException if the failure is a stop rather than a fault
     */
    static void stopIfCancelled(String sourceId, long documentsDone, long documentsTotal,
                                Throwable failure) {
        if (arrivedAs(failure)) {
            throw stopped(sourceId, documentsDone, documentsTotal);
        }
    }

    /**
     * Whether this failure is an interrupt rather than a fault of the document.
     *
     * @param failure What the document's attempt threw
     * @return true if the run was asked to stop
     */
    static boolean arrivedAs(Throwable failure) {
        if (requested()) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException
                    || cause instanceof ClosedByInterruptException) {
                return true;
            }
            if (cause.getCause() == cause) {
                // A self-referencing cause is a malformed exception chain, not a cancellation,
                // and walking it would not terminate.
                break;
            }
        }
        return false;
    }

    /**
     * Builds the signal that ends the run.
     *
     * <p>Its cause is an {@link InterruptedException} whether or not one was thrown: the
     * endpoint classifies a run from the flag and the cause chain, and an interrupt that
     * arrived as the flag alone would otherwise reach it as an exception chain saying
     * nothing.</p>
     *
     * <p>Does not touch the interrupt flag. {@link SourceReindexPass} owns it, because it is
     * the only place that knows when the run has finished writing out what it got through.</p>
     *
     * @param sourceId       The source being reindexed, for the log
     * @param documentsDone  How many documents the run finished before this point
     * @param documentsTotal How many it would have finished
     * @return The signal to throw
     */
    static ReindexCancelledException stopped(String sourceId, long documentsDone,
                                             long documentsTotal) {
        String message = "Reindex of " + sourceId + " was asked to stop after " + documentsDone
            + " of " + documentsTotal + " document(s)";
        return new ReindexCancelledException(message, new InterruptedException(message));
    }
}
