package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.core.util.QueryBudget;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.executor.QueryRegistry;

/**
 * The window in which {@code DELETE /v1/query/{id}} can withdraw one running query, and the
 * record of whether it did.
 *
 * <h2>The contract it enforces</h2>
 * <p>The cancel route answers {@code "cancelled": true} exactly when its call is the one that
 * cancelled the registered token. So a query whose token was cancelled that way must answer
 * {@code "cancelled": true} with no results, on every route, however far it had got - and the
 * only place that can be decided without a race is after the registration is gone, because
 * until then another {@code DELETE} can still arrive. {@link #close()} removes it and reports
 * the verdict, and the caller writes its answer only after that.</p>
 *
 * <h2>Why its own token</h2>
 * <p>The token registered here means one thing: the caller withdrew the question. The work a
 * query runs has other reasons to stop - the pipeline cancels its own token when its deadline
 * passes, and the endpoint does when it abandons a pipeline that missed the grace period - and
 * none of those is a withdrawal. Sharing one token would let a missed deadline read as a
 * cancellation, and would make a {@code DELETE} arriving after it answer "already cancelled"
 * for a query nobody had cancelled. Withdrawing stops the work too, through
 * {@link #stops(CancellationToken)}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>One instance per request. The token is thread-safe; {@link #close()} is called once, by the
 * request's own thread.</p>
 */
final class QueryWithdrawal {

    /** The reason recorded on the work a withdrawal stops. */
    static final String WITHDRAWN = "Withdrawn by the caller";

    private final QueryRegistry registry;
    private final String queryId;
    private final CancellationToken token = new CancellationToken();

    private QueryWithdrawal(QueryRegistry registry, String queryId) {
        this.registry = registry;
        this.queryId = queryId;
    }

    /**
     * Makes a query withdrawable.
     *
     * @param registry      Where the cancel route looks a query up, or null on a node that keeps
     *                      none, where nothing can withdraw a query
     * @param queryId       The identifier the query is registered under
     * @param correlationId Correlation ID for the registration
     * @return The open window
     */
    static QueryWithdrawal open(QueryRegistry registry, String queryId, String correlationId) {
        return open(registry, queryId, correlationId, QueryRegistry.Kind.CORPUS);
    }

    /**
     * Makes a query of the given kind withdrawable.
     *
     * @param registry      Where the cancel route looks a query up, or null on a node that keeps
     *                      none, where nothing can withdraw a query
     * @param queryId       The identifier the query is registered under
     * @param correlationId Correlation ID for the registration
     * @param kind          Which kind of search the status route lists it as
     * @return The open window
     */
    static QueryWithdrawal open(QueryRegistry registry, String queryId, String correlationId,
                                QueryRegistry.Kind kind) {
        QueryWithdrawal withdrawal = new QueryWithdrawal(registry, queryId);
        if (registry != null) {
            registry.register(queryId, withdrawal.token, correlationId, kind);
        }
        return withdrawal;
    }

    /**
     * The token a withdrawal cancels, for the work that must stop when it does and that
     * reports "cancelled" when it stopped for this reason alone.
     *
     * @return The token
     */
    CancellationToken token() {
        return token;
    }

    /**
     * Makes a withdrawal stop other work as well.
     *
     * @param work A token the work checks, which other things may also cancel
     * @return This window
     */
    QueryWithdrawal stops(CancellationToken work) {
        token.addListener(() -> work.cancel(WITHDRAWN));
        return this;
    }

    /**
     * Makes a withdrawal end a query that runs within a budget.
     *
     * @param work The budget the query's stages check and wait within
     * @return This window
     */
    QueryWithdrawal stops(QueryBudget work) {
        token.addListener(() -> work.cancel(WITHDRAWN));
        return this;
    }

    /**
     * Ends the window and says whether the query was withdrawn inside it.
     *
     * <p>Once this returns the cancel route answers "not found", so the verdict cannot change
     * afterwards.</p>
     *
     * @return true when a {@code DELETE} withdrew the query
     */
    boolean close() {
        if (registry != null) {
            registry.unregister(queryId);
        }
        return token.isCancelled();
    }
}
