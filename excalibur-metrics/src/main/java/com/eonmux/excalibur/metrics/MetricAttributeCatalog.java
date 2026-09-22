package com.eonmux.excalibur.metrics;

/**
 * Everything this node publishes over JMX: for each metrics bean, the attributes it offers,
 * the words a console shows beside them, and what answers each one.
 *
 * <h2>Why it is one catalogue</h2>
 * <p>An attribute name is an interface with no compiler behind it. A dashboard panel, an alert
 * rule and a runbook each hold a copy, and renaming one breaks an alert that then never fires
 * - silently, because a query for a metric that no longer exists returns no data rather than
 * an error. Keeping the whole published vocabulary in one file makes it something a reviewer
 * can read end to end and a diff can show whole, instead of forty-four literals scattered
 * through seven class bodies where a rename reads as a local edit.</p>
 *
 * <p>It also keeps the beans in {@link Metrics} down to what they are: seven names that exist
 * because callers and dashboards already know them. The beans hold no state and now hold no
 * vocabulary either, so nothing about a metric has to be understood twice.</p>
 *
 * @author ExcaliburDB
 */
final class MetricAttributeCatalog {

    /**
     * What a percentile means once the measurement leaves the histogram's range.
     *
     * <p>Appended to every percentile's description because a percentile pinned at the range's
     * edge looks like a stable measurement and is not one; the reader has to be sent to
     * {@code overflowCount} at the point of reading, not in a runbook.</p>
     */
    private static final String PERCENTILE_CEILING =
        " (ms); reads " + FixedBucketHistogram.RANGE_MS + " when it falls among samples "
            + "past the histogram's range - see overflowCount";

    /** Not instantiable: this is a vocabulary, not a participant. */
    private MetricAttributeCatalog() {
    }

    /**
     * The attributes of {@code com.eonmux.excalibur:type=QueryLatency}.
     *
     * @return The query latency attribute table
     */
    static MetricAttributeTable queryLatency() {
        return MetricAttributeTable.builder()
            .read("count", "long", "Total count",
                () -> Metrics.QueryLatency().getTotalCount())
            .read("p50", "long", "50th percentile latency" + PERCENTILE_CEILING,
                () -> Metrics.QueryLatency().getPercentile(50.0))
            .read("p90", "long", "90th percentile latency" + PERCENTILE_CEILING,
                () -> Metrics.QueryLatency().getPercentile(90.0))
            .read("p95", "long", "95th percentile latency" + PERCENTILE_CEILING,
                () -> Metrics.QueryLatency().getPercentile(95.0))
            .read("p99", "long", "99th percentile latency" + PERCENTILE_CEILING,
                () -> Metrics.QueryLatency().getPercentile(99.0))
            .read("p99.9", "long", "99.9th percentile latency" + PERCENTILE_CEILING,
                () -> Metrics.QueryLatency().getPercentile(99.9))
            .read("average", "double",
                "Average latency (ms), counting samples past the range at their real size",
                () -> Metrics.QueryLatency().getAverage())
            .read("minimum", "long", "Minimum latency (ms)",
                () -> Metrics.QueryLatency().getMinimum())
            .read("maximum", "long", "Maximum latency (ms), exact for samples past the range",
                () -> Metrics.QueryLatency().getMaximum())
            .read("overflowCount", "long",
                "Queries slower than " + FixedBucketHistogram.RANGE_MS + "ms. The percentiles "
                    + "cannot resolve these and report the range's edge for them, so a flat "
                    + "percentile with this above zero is a ceiling, not a measurement",
                () -> Metrics.QueryLatency().getOverflowCount())
            .build();
    }

    /**
     * The attributes of {@code com.eonmux.excalibur:type=SystemHealth}.
     *
     * @return The system health attribute table
     */
    static MetricAttributeTable systemHealth() {
        return MetricAttributeTable.builder()
            .read("health", "double", "System health score (0.0-1.0)",
                Metrics::getHealthScore)
            .read("isHealthy", "boolean", "Whether system is healthy",
                Metrics::isHealthy)
            .read("ready", "boolean", "Whether system is ready (health >= 0.5)",
                () -> Metrics.getHealthScore() >= HealthState.HEALTHY_SCORE_THRESHOLD)
            .read("coverage", "double", "Estimated corpus coverage (0.0-1.0)",
                Metrics::getEstimatedCoverage)
            .build();
    }

    /**
     * The attributes of {@code com.eonmux.excalibur:type=IngestionQueue}.
     *
     * <p>Every one reads the HNSW integration backlog: the bean is an alias, and each
     * description says so rather than leaving a reader to discover that two beans in trouble
     * are one number.</p>
     *
     * @return The ingestion queue attribute table
     */
    static MetricAttributeTable ingestionQueue() {
        return MetricAttributeTable.builder()
            .read("depth", "long",
                "HNSW integration backlog: entries not yet absorbed into the graph. Same value "
                    + "as HnswQueueDepth.depth",
                Metrics::getHnswQueueDepth)
            .read("percent", "double",
                "HNSW integration backlog as a percentage of its limit. Same value as "
                    + "HnswQueueDepth.percent",
                () -> Metrics.getHnswQueueDepthPercent(Metrics.getHnswQueueLimit()))
            .read("isUnderControl", "boolean",
                "Whether the HNSW integration backlog is under control (< 50%). Same value as "
                    + "HnswQueueDepth.isUnderControl",
                () -> Metrics.isHnswUnderControl(Metrics.getHnswQueueLimit()))
            .read("isCritical", "boolean",
                "Whether the HNSW integration backlog is critical (> 80%). Same value as "
                    + "HnswQueueDepth.isCritical",
                () -> Metrics.isHnswCritical(Metrics.getHnswQueueLimit()))
            .read("isFull", "boolean",
                "Whether the HNSW integration backlog is full (> 100%). Same value as "
                    + "HnswQueueDepth.isFull",
                () -> Metrics.isHnswFull(Metrics.getHnswQueueLimit()))
            .build();
    }

    /**
     * The attributes of {@code com.eonmux.excalibur:type=VisibilityEpoch}.
     *
     * @return The visibility epoch attribute table
     */
    static MetricAttributeTable visibilityEpoch() {
        return MetricAttributeTable.builder()
            .read("epoch", "long", "Current visibility epoch", Metrics::getCurrentEpoch)
            .build();
    }

    /**
     * The attributes of {@code com.eonmux.excalibur:type=CuckooFpRate}.
     *
     * @return The Cuckoo filter false-positive rate attribute table
     */
    static MetricAttributeTable cuckooFpRate() {
        return MetricAttributeTable.builder()
            .read("fpRate", "double", "Current false positive rate",
                () -> FixedBucketHistogram.getInstance().getAverageFpRate())
            .read("fpRateEpoch", "long", "Epoch of last FP rate measurement",
                () -> FixedBucketHistogram.getInstance().getFpRateEpoch())
            .read("fpRateCount", "long", "Count of FP rate measurements",
                () -> FixedBucketHistogram.getInstance().getFpRateCount())
            .read("avgFpRate", "double", "Average false positive rate",
                () -> FixedBucketHistogram.getInstance().getAverageFpRate())
            .read("maxFpRate", "double", "Maximum false positive rate",
                () -> FixedBucketHistogram.getInstance().getMaxFpRate())
            // The node's visibility epoch, read from the one store that owns it, so this and
            // VisibilityEpoch.epoch cannot disagree. The histogram keeps an epoch of its own
            // to stamp samples with, and Metrics.recordQueryLatency(ms, epoch) moves it, so
            // reading it here would publish a query's read-epoch as the node's.
            .read("currentEpoch", "long", "Current epoch", Metrics::getCurrentEpoch)
            .build();
    }

    /**
     * The attributes of {@code com.eonmux.excalibur:type=HnswQueueDepth}.
     *
     * @return The HNSW queue depth attribute table
     */
    static MetricAttributeTable hnswQueueDepth() {
        return MetricAttributeTable.builder()
            .read("depth", "long", "Current HNSW queue depth",
                Metrics::getHnswQueueDepth)
            .read("percent", "double", "Queue depth as percentage of limit",
                () -> Metrics.getHnswQueueDepthPercent(Metrics.getHnswQueueLimit()))
            .read("isUnderControl", "boolean", "Whether queue is under control (< 50%)",
                () -> Metrics.isHnswUnderControl(Metrics.getHnswQueueLimit()))
            .read("isCritical", "boolean", "Whether queue is critical (> 80%)",
                () -> Metrics.isHnswCritical(Metrics.getHnswQueueLimit()))
            .read("isFull", "boolean", "Whether queue is full (> 100%)",
                () -> Metrics.isHnswFull(Metrics.getHnswQueueLimit()))
            .read("averageTailScan", "long", "Average tail scan entries",
                Metrics::getAverageTailScanEntries)
            .read("maxTailScan", "long", "Maximum tail scan entries",
                Metrics::getMaxTailScanEntries)
            .read("totalTailScanned", "long", "Total tail scan entries",
                Metrics::getTotalTailScanned)
            .read("totalVectorEntriesFound", "long",
                "Total tail scan entries with a valid vector",
                Metrics::getTotalVectorEntriesFound)
            .build();
    }

    /**
     * The attributes of {@code com.eonmux.excalibur:type=IngestionThroughput}.
     *
     * @return The ingestion throughput attribute table
     */
    static MetricAttributeTable ingestionThroughput() {
        return MetricAttributeTable.builder()
            .read("entriesIngested", "long", "Total entries ingested",
                Metrics::getEntriesIngested)
            .read("queriesExecuted", "long", "Total queries executed",
                Metrics::getQueriesExecuted)
            // Sampled, not recomputed: a read must not consume the interval it measures.
            .read("ingestionRate", "double", "Current ingestion rate (entries/sec)",
                Metrics::sampledIngestionRate)
            .read("peakIngestionRate", "double", "Peak ingestion rate (entries/sec)",
                Metrics::getPeakIngestionRate)
            .build();
    }
}
