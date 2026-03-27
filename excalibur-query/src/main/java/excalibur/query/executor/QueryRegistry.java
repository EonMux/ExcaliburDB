package excalibur.query.executor;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Registry for tracking active queries and enabling user-initiated cancellation.
 *
 * <p>QueryRegistry maintains a mapping of query IDs to their cancellation tokens,
 * allowing external cancellation of in-progress queries via the API.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Register a new query
 * String queryId = UUID.randomUUID().toString();
 * CancellationToken token = new CancellationToken();
 * queryRegistry.register(queryId, token);
 *
 * // Later, user cancels the query
 * boolean cancelled = queryRegistry.cancel(queryId);
 *
 * // When query completes, unregister
 * queryRegistry.unregister(queryId);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Uses ConcurrentHashMap for thread-safe query tracking</li>
 *   <li>All operations are atomic</li>
 *   <li>Safe for concurrent access from multiple threads</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Query IDs must be unique - re-registration of same ID fails</li>
 *   <li>Unregistering non-existent queries is a no-op</li>
 *   <li>Cancelled queries remain in registry until unregistered</li>
 *   <li>Active query list reflects queries currently being executed</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class QueryRegistry {

    /**
     * Internal record for tracking active query information.
     */
    public record ActiveQuery(
        String queryId,
        CancellationToken token,
        Instant startTime,
        String correlationId
    ) {
        /**
         * Returns the duration in milliseconds since the query started.
         */
        public long durationMs() {
            return Instant.now().toEpochMilli() - startTime.toEpochMilli();
        }
    }

    /**
     * Information about an active query for status endpoints.
     */
    public record ActiveQueryInfo(
        String queryId,
        String correlationId,
        long startTimeMs,
        long durationMs,
        boolean cancelled
    ) {
        /**
         * Creates ActiveQueryInfo from an ActiveQuery.
         */
        public static ActiveQueryInfo from(ActiveQuery query) {
            return new ActiveQueryInfo(
                query.queryId(),
                query.correlationId(),
                query.startTime().toEpochMilli(),
                query.durationMs(),
                query.token().isCancelled()
            );
        }
    }

    /**
     * Map of query ID to active query information.
     * ConcurrentHashMap ensures thread-safe access.
     */
    private final ConcurrentHashMap<String, ActiveQuery> activeQueries;

    /**
     * Counter for tracking total queries processed.
     */
    private final AtomicLong totalQueriesRegistered;

    /**
     * Counter for tracking total queries cancelled.
     */
    private final AtomicLong totalQueriesCancelled;

    /**
     * Creates a new QueryRegistry.
     */
    public QueryRegistry() {
        this.activeQueries = new ConcurrentHashMap<>();
        this.totalQueriesRegistered = new AtomicLong(0);
        this.totalQueriesCancelled = new AtomicLong(0);
    }

    // ==================== Core Operations ====================

    /**
     * Registers a new query with its cancellation token.
     *
     * <p>The query will be tracked until {@link #unregister(String)} is called.
     * If a query with the same ID already exists, this method returns false
     * and does not overwrite the existing entry.</p>
     *
     * @param queryId The unique query identifier
     * @param token The cancellation token for this query
     * @return true if registration succeeded, false if query ID already exists
     * @throws NullPointerException if queryId or token is null
     */
    public boolean register(String queryId, CancellationToken token) {
        return register(queryId, token, null);
    }

    /**
     * Registers a new query with its cancellation token and correlation ID.
     *
     * <p>The query will be tracked until {@link #unregister(String)} is called.
     * If a query with the same ID already exists, this method returns false
     * and does not overwrite the existing entry.</p>
     *
     * @param queryId The unique query identifier
     * @param token The cancellation token for this query
     * @param correlationId Optional correlation ID for logging/tracing
     * @return true if registration succeeded, false if query ID already exists
     * @throws NullPointerException if queryId or token is null
     */
    public boolean register(String queryId, CancellationToken token, String correlationId) {
        Objects.requireNonNull(queryId, "queryId cannot be null");
        Objects.requireNonNull(token, "token cannot be null");

        ActiveQuery query = new ActiveQuery(
            queryId,
            token,
            Instant.now(),
            correlationId != null ? correlationId : queryId.substring(0, Math.min(8, queryId.length()))
        );

        ActiveQuery existing = activeQueries.putIfAbsent(queryId, query);

        if (existing == null) {
            totalQueriesRegistered.incrementAndGet();
            return true;
        } else {
            return false; // Query ID already registered
        }
    }

    /**
     * Cancels a query by its ID.
     *
     * <p>This method finds the query in the registry and cancels its token.
     * The query remains in the registry until explicitly unregistered.</p>
     *
     * @param queryId The query ID to cancel
     * @return true if the query was found and cancelled, false if not found or already cancelled
     * @throws NullPointerException if queryId is null
     */
    public boolean cancel(String queryId) {
        return cancel(queryId, "User-initiated cancellation");
    }

    /**
     * Cancels a query by its ID with a custom reason.
     *
     * @param queryId The query ID to cancel
     * @param reason The cancellation reason
     * @return true if the query was found and cancelled, false if not found or already cancelled
     * @throws NullPointerException if queryId is null
     */
    public boolean cancel(String queryId, String reason) {
        Objects.requireNonNull(queryId, "queryId cannot be null");

        ActiveQuery query = activeQueries.get(queryId);

        if (query == null) {
            return false; // Query not found
        }

        boolean cancelled = query.token().cancel(reason);

        if (cancelled) {
            totalQueriesCancelled.incrementAndGet();
        }

        return cancelled;
    }

    /**
     * Unregisters a query, removing it from the active query list.
     *
     * <p>This should be called when a query completes (successfully or with error)
     * to clean up the registry entry.</p>
     *
     * @param queryId The query ID to unregister
     * @return true if the query was found and removed, false if not found
     * @throws NullPointerException if queryId is null
     */
    public boolean unregister(String queryId) {
        Objects.requireNonNull(queryId, "queryId cannot be null");

        ActiveQuery removed = activeQueries.remove(queryId);
        return removed != null;
    }

    /**
     * Checks if a query is currently registered.
     *
     * @param queryId The query ID to check
     * @return true if the query is registered, false otherwise
     * @throws NullPointerException if queryId is null
     */
    public boolean isRegistered(String queryId) {
        Objects.requireNonNull(queryId, "queryId cannot be null");
        return activeQueries.containsKey(queryId);
    }

    /**
     * Checks if a registered query has been cancelled.
     *
     * @param queryId The query ID to check
     * @return true if the query exists and is cancelled, false if not found or not cancelled
     * @throws NullPointerException if queryId is null
     */
    public boolean isCancelled(String queryId) {
        Objects.requireNonNull(queryId, "queryId cannot be null");

        ActiveQuery query = activeQueries.get(queryId);
        return query != null && query.token().isCancelled();
    }

    /**
     * Gets the cancellation token for a query.
     *
     * <p>This is useful for checking cancellation status directly.</p>
     *
     * @param queryId The query ID
     * @return The cancellation token, or null if not found
     * @throws NullPointerException if queryId is null
     */
    public CancellationToken getToken(String queryId) {
        Objects.requireNonNull(queryId, "queryId cannot be null");

        ActiveQuery query = activeQueries.get(queryId);
        return query != null ? query.token() : null;
    }

    // ==================== Status and Metrics ====================

    /**
     * Gets information about an active query.
     *
     * @param queryId The query ID
     * @return Query information, or null if not found
     * @throws NullPointerException if queryId is null
     */
    public ActiveQueryInfo getQueryInfo(String queryId) {
        Objects.requireNonNull(queryId, "queryId cannot be null");

        ActiveQuery query = activeQueries.get(queryId);
        return query != null ? ActiveQueryInfo.from(query) : null;
    }

    /**
     * Gets a list of all currently active queries.
     *
     * <p>This is useful for status endpoints and monitoring.</p>
     *
     * @return List of active query information (may be empty, never null)
     */
    public List<ActiveQueryInfo> getActiveQueries() {
        return activeQueries.values().stream()
            .map(ActiveQueryInfo::from)
            .collect(Collectors.toList());
    }

    /**
     * Returns the number of currently active queries.
     *
     * @return Active query count
     */
    public int getActiveQueryCount() {
        return activeQueries.size();
    }

    /**
     * Returns the total number of queries ever registered.
     *
     * @return Total queries registered since registry creation
     */
    public long getTotalQueriesRegistered() {
        return totalQueriesRegistered.get();
    }

    /**
     * Returns the total number of queries cancelled.
     *
     * @return Total queries cancelled since registry creation
     */
    public long getTotalQueriesCancelled() {
        return totalQueriesCancelled.get();
    }

    /**
     * Clears all queries from the registry.
     *
     * <p>This should only be used during shutdown.</p>
     */
    public void clear() {
        activeQueries.clear();
    }

    @Override
    public String toString() {
        return String.format(
            "QueryRegistry{active=%d, totalRegistered=%d, totalCancelled=%d}",
            activeQueries.size(),
            totalQueriesRegistered.get(),
            totalQueriesCancelled.get()
        );
    }
}
