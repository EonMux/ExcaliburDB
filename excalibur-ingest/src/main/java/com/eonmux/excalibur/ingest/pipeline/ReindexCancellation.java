package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.core.util.StopSignal;

import java.nio.channels.ClosedByInterruptException;

/**
 * How a reindex in progress is told to stop, and where it is allowed to.
 *
 * <h2>Why the signal is a {@link StopSignal} and not the thread's interrupt</h2>
 * <p>A reindex spends most of its time writing: chunks through the storage engine, whose write
 * log forces a channel every writer of the segment shares, tombstones through the same log, the
 * lexical index and the registry. An interrupt that reaches the run's thread inside - or on its
 * way into - any of those closes the channel with {@link ClosedByInterruptException}, and closed
 * means closed for every thread for the life of the process: cancelling one reindex would break
 * ingestion, erasure and every later reindex of each segment it happened to be writing. So the
 * canceller requests the run's signal, and the run reads it at the checkpoints below. The signal
 * interrupts only the waits that touch no shared file - the source fetch and the embedding
 * request - and leaves the flag clear when they end, so a stop that lands during a fetch never
 * reaches the write after it.</p>
 *
 * <h2>Interrupts from elsewhere, and the two shapes they take</h2>
 * <p>An interrupt the run did not ask for still means "stop", and is honoured as one: a
 * checkpoint that finds the flag set clears it into the signal before the run writes anything
 * else. Within a document, a blocking call reports an interrupt in its own way. A call that threw
 * {@link InterruptedException} cleared the flag on the way out, and by the time the reindex sees
 * it the only trace is in the cause chain, usually several wrappers deep. A call interrupted
 * inside a channel of its own - a source reading a file it opened - throws
 * {@link ClosedByInterruptException}, an {@code IOException} that no test for
 * {@code InterruptedException} would find. Both are read as a stop.</p>
 *
 * <h2>Where the checkpoints are, and why not deeper</h2>
 * <p>Between documents, and nowhere inside one. A document is published whole or not at all, and
 * that promise is kept by writing every chunk, then sealing, then publishing under the
 * document's lock. A checkpoint anywhere in that sequence could leave chunks in storage that no
 * registry entry names, which is the state the rollback exists to undo and the atomicity filter
 * exists to hide. So a document already under way is finished, and the wait between asking a
 * reindex to stop and its stopping is one document.</p>
 *
 * @see ReindexCancelledException
 */
final class ReindexCancellation {

    private ReindexCancellation() {
        throw new AssertionError("Utility class");
    }

    /**
     * Whether the run has been asked to stop.
     *
     * <p>An interrupt found on this thread is cleared into the signal rather than left set: it
     * asks for the same thing, and left set it would close the next channel the run writes
     * through. The signal is never cleared, so asking in turn from several checkpoints gives the
     * same answer.</p>
     *
     * @param stop The run's stop signal
     * @return true once the run has been asked to stop
     */
    static boolean requested(StopSignal stop) {
        if (Thread.interrupted()) {
            stop.request();
        }
        return stop.isRequested();
    }

    /**
     * Ends the run here if it has been asked to stop.
     *
     * <p>Call it between documents, never inside one.</p>
     *
     * @param stop           The run's stop signal
     * @param sourceId       The source being reindexed, for the log
     * @param documentsDone  How many documents the run finished before this point
     * @param documentsTotal How many it would have finished
     * @throws ReindexCancelledException if the run has been asked to stop
     */
    static void checkpoint(StopSignal stop, String sourceId, long documentsDone,
                           long documentsTotal) {
        if (requested(stop)) {
            throw stopped(sourceId, documentsDone, documentsTotal);
        }
    }

    /**
     * Ends the run here if this failure is the stop arriving as an exception.
     *
     * <p>This is the guard that belongs in front of every {@code catch} broad enough to hold an
     * interrupted fetch. Without it the stop is filed as one document's bad luck and the run
     * walks the rest of the crawl - the whole of it, on the large source that is the one anybody
     * cancels.</p>
     *
     * @param stop           The run's stop signal
     * @param sourceId       The source being reindexed, for the log
     * @param documentsDone  How many documents the run finished before this point
     * @param documentsTotal How many it would have finished
     * @param failure        What the document's attempt threw
     * @throws ReindexCancelledException if the failure is a stop rather than a fault
     */
    static void stopIfCancelled(StopSignal stop, String sourceId, long documentsDone,
                                long documentsTotal, Throwable failure) {
        if (arrivedAs(stop, failure)) {
            stop.request();
            throw stopped(sourceId, documentsDone, documentsTotal);
        }
    }

    /**
     * Whether this failure is a stop rather than a fault of the document.
     *
     * @param stop    The run's stop signal
     * @param failure What the document's attempt threw
     * @return true if the run was asked to stop, or the failure is an interrupt
     */
    static boolean arrivedAs(StopSignal stop, Throwable failure) {
        if (requested(stop)) {
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
     * @param sourceId       The source being reindexed, for the log
     * @param documentsDone  How many documents the run finished before this point
     * @param documentsTotal How many it would have finished
     * @return The signal to throw
     */
    static ReindexCancelledException stopped(String sourceId, long documentsDone,
                                             long documentsTotal) {
        return new ReindexCancelledException("Reindex of " + sourceId + " was asked to stop "
            + "after " + documentsDone + " of " + documentsTotal + " document(s)");
    }
}
