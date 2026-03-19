package excalibur.query;

import excalibur.query.executor.ParallelQueryDispatcher;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public API entry point for executing ANN queries.
 *
 * <p>QueryService provides the primary interface for querying the vector index.
 * It coordinates parallel query dispatch, result aggregation, and coverage
 * computation to return unified results from multiple segments.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create query service
 * QueryService queryService = new QueryService(storageEngine, distanceFunction);
 *
 * // Execute a query
 * float[] queryVector = generateQueryVector();
 * QueryOptions options = QueryOptions.builder()
 *     .topK(10)
 *     .threshold(0.5f)
 *     .includePartialSegments(true)
 *     .timeout(Duration.ofSeconds(5))
 *     .build();
 *
 * QueryResponse response = queryService.query(queryVector, options);
 *
 * // Check results
 * if (!response.isDegraded()) {
 *     for (QueryResultItem result : response.results()) {
 *         System.out.println("Document " + result.documentId() + ": " + result.score());
 *     }
 * }
 * }</pre>
 *
 * <h2>Segment-to-Graph Mapping</h2>
 * <p>QueryService requires a mapping between segments and their HNSW graphs.
 * This is provided via the {@link HnswGraphRegistry} interface. Implementations
 * should load graphs on demand or cache them for performance.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Multiple queries can be executed concurrently.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public class QueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryService.class);

    private final StorageEngine storageEngine;
    private final DistanceFunction defaultDistanceFunction;
    private final HnswGraphRegistry graphRegistry;
    private final ParallelQueryDispatcher dispatcher;
    private final Map<Long, HnswGraph> graphCache;

    /**
     * Creates a new QueryService.
     *
     * @param storageEngine The storage engine providing segments
     * @param defaultDistanceFunction The default distance function for queries
     * @param graphRegistry Registry for loading HNSW graphs
     * @throws NullPointerException if any parameter is null
     */
    public QueryService(
            StorageEngine storageEngine,
            DistanceFunction defaultDistanceFunction,
            HnswGraphRegistry graphRegistry) {

        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.defaultDistanceFunction = Objects.requireNonNull(defaultDistanceFunction, "DistanceFunction cannot be null");
        this.graphRegistry = Objects.requireNonNull(graphRegistry, "HnswGraphRegistry cannot be null");
        this.graphCache = new ConcurrentHashMap<>();

        // Create dispatcher with graph provider
        this.dispatcher = new ParallelQueryDispatcher(
            storageEngine.getCatalog(),
            storageEngine.getSegments(),
            this::getGraph
        );

        LOGGER.info("QueryService initialized with {} segments", storageEngine.getSegmentCount());
    }

    /**
     * Creates a new QueryService with a simple graph registry.
     *
     * <p>This constructor uses a simple in-memory registry where graphs
     * must be pre-registered via {@link #registerGraph(long, HnswGraph)}.</p>
     *
     * @param storageEngine The storage engine providing segments
     * @param defaultDistanceFunction The default distance function
     */
    public QueryService(
            StorageEngine storageEngine,
            DistanceFunction defaultDistanceFunction) {

        this(storageEngine, defaultDistanceFunction, new SimpleHnswGraphRegistry());
    }

    /**
     * Executes an ANN query with default options.
     *
     * @param queryVector The query vector
     * @return Query response with results and coverage
     */
    public QueryResponse query(float[] queryVector) {
        return query(queryVector, QueryOptions.defaults());
    }

    /**
     * Executes an ANN query with specified top-k.
     *
     * @param queryVector The query vector
     * @param k The number of results to return
     * @return Query response with results and coverage
     */
    public QueryResponse query(float[] queryVector, int k) {
        return query(queryVector, QueryOptions.builder().topK(k).build());
    }

    /**
     * Executes an ANN query with specified options.
     *
     * <p>This is the primary query method. It:</p>
     * <ol>
     *   <li>Captures a visibility epoch snapshot</li>
     *   <li>Dispatches the query to all queryable segments in parallel</li>
     *   <li>Aggregates results using merge-sort</li>
     *   <li>Returns unified results with coverage metadata</li>
     * </ol>
     *
     * @param queryVector The query vector (must be non-null and match vector dimension)
     * @param options Query options controlling behavior
     * @return QueryResponse with results and coverage
     * @throws NullPointerException if queryVector or options is null
     * @throws IllegalArgumentException if queryVector is empty
     */
    public QueryResponse query(float[] queryVector, QueryOptions options) {
        Objects.requireNonNull(queryVector, "Query vector cannot be null");
        Objects.requireNonNull(options, "Query options cannot be null");

        if (queryVector.length == 0) {
            throw new IllegalArgumentException("Query vector cannot be empty");
        }

        LOGGER.trace("Executing query: topK={}, threshold={}, timeout={}",
            options.topK(), options.threshold(), options.timeout());

        // Dispatch query using the dispatcher
        return dispatcher.dispatch(queryVector, options, defaultDistanceFunction);
    }

    /**
     * Executes an ANN query with a custom distance function.
     *
     * @param queryVector The query vector
     * @param k The number of results
     * @param distanceFunction The distance function to use
     * @return Query response
     */
    public QueryResponse query(float[] queryVector, int k, DistanceFunction distanceFunction) {
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        QueryOptions options = QueryOptions.builder()
            .topK(k)
            .build();

        return dispatcher.dispatch(queryVector, options, distanceFunction);
    }

    /**
     * Executes an ANN query with full options and custom distance function.
     *
     * @param queryVector The query vector
     * @param options Query options
     * @param distanceFunction The distance function to use
     * @return Query response
     */
    public QueryResponse query(float[] queryVector, QueryOptions options, DistanceFunction distanceFunction) {
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");
        return dispatcher.dispatch(queryVector, options, distanceFunction);
    }

    /**
     * Registers an HNSW graph for a segment.
     *
     * <p>Graphs can be pre-loaded and registered for faster query execution.
     * If a graph is not registered, the graph registry will be consulted.</p>
     *
     * @param segmentId The segment ID
     * @param graph The HNSW graph
     */
    public void registerGraph(long segmentId, HnswGraph graph) {
        Objects.requireNonNull(graph, "Graph cannot be null");
        graphCache.put(segmentId, graph);
        LOGGER.debug("Registered graph for segment {}", segmentId);
    }

    /**
     * Unregisters an HNSW graph.
     *
     * @param segmentId The segment ID
     */
    public void unregisterGraph(long segmentId) {
        graphCache.remove(segmentId);
        LOGGER.debug("Unregistered graph for segment {}", segmentId);
    }

    /**
     * Gets the HNSW graph for a segment.
     *
     * <p>First checks the cache, then consults the registry if not found.</p>
     *
     * @param segmentId The segment ID
     * @return The HNSW graph, or null if not available
     */
    private HnswGraph getGraph(long segmentId) {
        // Check cache first
        HnswGraph graph = graphCache.get(segmentId);
        if (graph != null) {
            return graph;
        }

        // Consult registry
        graph = graphRegistry.getGraph(segmentId);
        if (graph != null) {
            graphCache.put(segmentId, graph);
        }

        return graph;
    }

    /**
     * Returns statistics about the query service.
     *
     * @return Query service statistics
     */
    public QueryServiceStats getStats() {
        SegmentCatalog catalog = storageEngine.getCatalog();
        SegmentCatalog.CatalogMetadata metadata = catalog.getCatalogMetadata();

        return new QueryServiceStats(
            storageEngine.getSegmentCount(),
            metadata.getActiveSegments(),
            metadata.getIndexingSegments(),
            metadata.getWarmSegments(),
            metadata.getCompactingSegments(),
            graphCache.size(),
            storageEngine.currentEpoch()
        );
    }

    /**
     * Shuts down the query service.
     */
    public void shutdown() {
        dispatcher.shutdown();
        graphCache.clear();
        LOGGER.info("QueryService shut down");
    }

    /**
     * Returns the storage engine.
     */
    public StorageEngine getStorageEngine() {
        return storageEngine;
    }

    /**
     * Returns the default distance function.
     */
    public DistanceFunction getDefaultDistanceFunction() {
        return defaultDistanceFunction;
    }

    /**
     * Registry interface for loading HNSW graphs.
     */
    @FunctionalInterface
    public interface HnswGraphRegistry {
        /**
         * Returns the HNSW graph for a segment.
         *
         * @param segmentId The segment ID
         * @return The HNSW graph, or null if not available
         */
        HnswGraph getGraph(long segmentId);
    }

    /**
     * Simple in-memory graph registry.
     */
    private static class SimpleHnswGraphRegistry implements HnswGraphRegistry {
        private final Map<Long, HnswGraph> graphs = new ConcurrentHashMap<>();

        @Override
        public HnswGraph getGraph(long segmentId) {
            return graphs.get(segmentId);
        }

        public void register(long segmentId, HnswGraph graph) {
            graphs.put(segmentId, graph);
        }
    }

    /**
     * Statistics about the query service.
     */
    public record QueryServiceStats(
        long totalSegments,
        long activeSegments,
        long indexingSegments,
        long warmingSegments,
        long compactingSegments,
        int graphsLoaded,
        long currentEpoch
    ) {
        /**
         * Returns the number of queryable segments.
         */
        public long queryableSegments() {
            return activeSegments + indexingSegments + warmingSegments + compactingSegments;
        }

        /**
         * Returns the estimated coverage.
         */
        public double estimatedCoverage() {
            if (totalSegments == 0) return 0.0;
            return (double) queryableSegments() / totalSegments;
        }
    }
}
