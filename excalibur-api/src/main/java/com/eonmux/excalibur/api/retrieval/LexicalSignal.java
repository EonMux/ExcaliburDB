package com.eonmux.excalibur.api.retrieval;

import com.eonmux.excalibur.core.text.SourceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The BM25 half of a hybrid query: starting it, bounding it, and reading it back.
 *
 * <h2>Why it is a thing rather than a call</h2>
 * <p>The two signals on {@code /v1/query} are independent, and the whole value of that is lost
 * if they run in series. So this one starts before the embedding call and is read back after the
 * vector pipeline returns, which means something has to hold it in between - together with the
 * index that is running it, so that a fallback describes the coverage of the index that actually
 * answered rather than whatever the endpoint's field points at by then.</p>
 *
 * <h2>Degradation is the contract</h2>
 * <p>Every failure here produces null, never an exception: a search that breaks, misses its
 * budget, is refused by the pool, or is shed for want of a permit leaves the caller with the
 * other signal's answer. Null and an empty list are different answers - empty means BM25 ran and
 * matched nothing, which is a fact about the corpus and is reported as one.</p>
 *
 * <p>The exception is an interrupt, which {@link #await} lets through. A handler answers 503 to
 * an interrupted request and writes nothing else; continuing would build a 200 on a thread whose
 * interrupt flag is set, which closes the channel mid-write and drops the very answer the
 * degradation exists to deliver.</p>
 */
public final class LexicalSignal {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexicalSignal.class);

    private final LexicalQueryService service;
    private final Future<LexicalQueryService.Ranking> pending;

    private LexicalSignal(LexicalQueryService service,
                          Future<LexicalQueryService.Ranking> pending) {
        this.service = service;
        this.pending = pending;
    }

    /**
     * Starts a search, or returns null when this node cannot run a second signal right now.
     *
     * <p>Null is the answer for all three reasons a request may have only one signal: there is
     * no lexical index, the pool refused the task, or too many searches are already running. A
     * request that cannot get a permit answers from vector search instead of queueing behind
     * one, because queueing would make the node slower under exactly the load that made the
     * permit scarce.</p>
     *
     * @param service  The index to search, or null when this node keeps none
     * @param executor Where the search runs
     * @param query    The query text
     * @param scope    The sources the search is confined to
     * @param limit    Most hits to return - {@code top_k}, not a multiple of it. A document
     *                 only BM25 returned has to out-score the weakest of the vector signal's
     *                 top-k to enter a top-k fused list, and by the reciprocal-rank arithmetic
     *                 nothing below rank top-k can. Asking deeper buys ranks that cannot be
     *                 returned, and the service already over-fetches internally so the list is
     *                 not thinned by hits whose ids no longer resolve.
     * @param budget   How long the request leaves the search, its query expansion included
     * @return The running search, or null when only the other signal will answer
     */
    public static LexicalSignal start(LexicalQueryService service, ExecutorService executor,
                                      String query, SourceScope scope, int limit,
                                      Duration budget) {
        if (service == null || executor == null) {
            return null;
        }
        if (!LexicalSearchPermits.tryAcquire()) {
            LOGGER.debug("No permit for the BM25 signal; answering from the other signal alone");
            return null;
        }
        try {
            return new LexicalSignal(service, executor.submit(() -> {
                try {
                    return service.retrieve(query, limit, scope, budget);
                } finally {
                    LexicalSearchPermits.release();
                }
            }));
        } catch (RejectedExecutionException e) {
            LexicalSearchPermits.release();
            LOGGER.warn("Could not start the BM25 signal; answering from the other signal "
                + "alone: {}", e.getMessage());
            return null;
        }
    }

    /** The index answering this request, for the coverage a fallback has to report. */
    public LexicalQueryService service() {
        return service;
    }

    /**
     * Abandons the search if it is still running.
     *
     * <p>A no-op once it has finished. On every path that did not read it, this is what
     * releases the request from an abandoned search: the future answers at once as cancelled,
     * and the search ends on its own.</p>
     *
     * <p>Cancelled without interrupting it. A search reads nothing an interrupt could cut
     * short - the native index and Lucene's {@code MMapDirectory} read through mappings, which
     * never look at the flag - and on the one layout that does not map, Lucene's
     * {@code NIOFSDirectory} fallback, an interrupt reaching a read closes the index file for
     * every query that shares it. So the interrupt could only ever do harm.</p>
     */
    public void cancel() {
        pending.cancel(false);
    }

    /**
     * Waits up to {@code budgetMs} for the search, degrading to null rather than throwing.
     *
     * @param budgetMs How long the request has left
     * @return The ranking and the expansion it searched, or null when this signal produced none
     * @throws InterruptedException if the request is interrupted while waiting
     */
    public LexicalQueryService.Ranking await(long budgetMs) throws InterruptedException {
        try {
            return pending.get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOGGER.warn("The BM25 signal did not finish inside the query's budget; "
                + "answering from the other signal alone");
        } catch (CancellationException e) {
            LOGGER.debug("The BM25 signal was cancelled with the query");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.warn("The BM25 signal failed; answering from the other signal alone: {}",
                cause.getMessage(), cause);
        }
        return null;
    }

    /**
     * What the search has already returned, without waiting for it.
     *
     * <p>For the paths where the other signal has failed and the answer is about to be built
     * out of this one. Never blocks, so it cannot be interrupted, and a response is never
     * written on a thread carrying an interrupt it has not answered. A search still running is
     * treated as having produced nothing: the request is already past its deadline, and waiting
     * longer to report a timeout is the wrong trade.</p>
     *
     * @return The ranking and the expansion it searched, or null when this signal has none to
     *         give yet
     */
    public LexicalQueryService.Ranking ready() {
        if (!pending.isDone() || pending.isCancelled()) {
            return null;
        }
        try {
            // Cannot block: the task is done, so get() reports the outcome without waiting,
            // and without consulting the interrupt flag.
            return pending.get();
        } catch (InterruptedException e) {
            // Unreachable for a completed task; restoring the flag is what to do if it ever is.
            Thread.currentThread().interrupt();
            return null;
        } catch (CancellationException | ExecutionException e) {
            LOGGER.warn("The BM25 signal failed; nothing to fall back to: {}", e.getMessage());
            return null;
        }
    }
}
