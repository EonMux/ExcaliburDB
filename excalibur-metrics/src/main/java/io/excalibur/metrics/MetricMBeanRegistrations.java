package io.excalibur.metrics;

/**
 * The JMX object names this node publishes its metrics under, and the act of publishing them.
 *
 * <h2>Why the names are gathered here</h2>
 * <p>An object name is the address a dashboard, an alert rule and a JMX console each hold a
 * copy of, so it is a published interface with no compiler behind it: renaming one breaks an
 * alert that then never fires, and nothing in a build says so. Gathering the seven in one
 * place makes that surface something a reader can see whole and a reviewer can guard, rather
 * than seven string literals scattered through registration calls.</p>
 *
 * @author ExcaliburDB
 */
final class MetricMBeanRegistrations {

    /** JMX object name of the query latency bean. */
    private static final String QUERY_LATENCY = "io.excalibur:type=QueryLatency";

    /** JMX object name of the system health bean. */
    private static final String SYSTEM_HEALTH = "io.excalibur:type=SystemHealth";

    /** JMX object name of the ingestion queue bean, an alias of {@link #HNSW_QUEUE_DEPTH}. */
    private static final String INGESTION_QUEUE = "io.excalibur:type=IngestionQueue";

    /** JMX object name of the visibility epoch bean. */
    private static final String VISIBILITY_EPOCH = "io.excalibur:type=VisibilityEpoch";

    /** JMX object name of the Cuckoo filter false-positive rate bean. */
    private static final String CUCKOO_FP_RATE = "io.excalibur:type=CuckooFpRate";

    /** JMX object name of the HNSW integration backlog bean. */
    private static final String HNSW_QUEUE_DEPTH = "io.excalibur:type=HnswQueueDepth";

    /** JMX object name of the ingestion throughput bean. */
    private static final String INGESTION_THROUGHPUT = "io.excalibur:type=IngestionThroughput";

    /** Not instantiable: this is a naming contract and the one act that applies it. */
    private MetricMBeanRegistrations() {
    }

    /**
     * Registers every metrics MBean with the given registry, under the names above.
     *
     * @param registry Metrics registry instance
     * @throws Exception if registration fails
     */
    static void registerAll(MetricsRegistry registry) throws Exception {
        registry.registerMBean(Metrics.getQueryLatencyMBean(), QUERY_LATENCY);
        registry.registerMBean(Metrics.getSystemHealthMBean(), SYSTEM_HEALTH);
        registry.registerMBean(Metrics.getIngestionQueueMBean(), INGESTION_QUEUE);
        registry.registerMBean(Metrics.getVisibilityEpochMBean(), VISIBILITY_EPOCH);
        registry.registerMBean(Metrics.getCuckooFpRateMBean(), CUCKOO_FP_RATE);
        registry.registerMBean(Metrics.getHnswQueueDepthMBean(), HNSW_QUEUE_DEPTH);
        registry.registerMBean(Metrics.getIngestionThroughputMBean(), INGESTION_THROUGHPUT);
    }
}
