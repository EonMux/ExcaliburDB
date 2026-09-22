package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.query.executor.QueryRegistry;
import com.eonmux.excalibur.metrics.Metrics;
import com.eonmux.excalibur.metrics.PrometheusText;
import com.eonmux.excalibur.metrics.ResponseCounters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP endpoint for {@code GET /metrics}: everything this node measures, for a scraper.
 *
 * <h2>Why this route exists</h2>
 * <p>This is a database that published nothing a monitoring system could read. Seven JMX
 * MBeans were registered, including a query-latency histogram with five percentiles whose
 * accessors had no caller outside the bean itself - and the JMX route is not reachable in a
 * real deployment: no {@code -Dcom.sun.management.jmxremote} flag appears in any build file,
 * script or document here, and RMI's callback address does not survive a container's port
 * mapping even when it does. {@code /v1/stats} carried segments, volumes and filters, and no
 * latency, no throughput and no error rate at all. So the numbers existed, were correct, and
 * were unreachable.</p>
 *
 * <h2>Why the Prometheus text format</h2>
 * <p>It is what a containerised database is scraped in, it needs no client library, and every
 * scraper in the ecosystem reads it - Prometheus, the OpenTelemetry collector, Grafana Agent,
 * and the Datadog and CloudWatch agents through their Prometheus receivers. See
 * {@link PrometheusText}.</p>
 *
 * <h2>Nothing here is invented</h2>
 * <p>Every series is a figure the node already measured. Where a measurement is absent - no
 * embedding client, so no circuit breaker - the series is omitted rather than exported as a
 * zero, because a zero is a measurement and an absence is not.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. One {@link PrometheusText} is built per request, on the thread serving it.</p>
 *
 * @author ExcaliburDB
 */
public final class MetricsEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsEndpoint.class);

    /** What a scraper negotiates on; the version is the exposition format's, not the node's. */
    private static final String EXPOSITION_CONTENT_TYPE =
        "text/plain; version=0.0.4; charset=utf-8";

    private final NodeStatsCollector collector;
    private final QueryRegistry queryRegistry;
    private final ServiceReadiness readiness;

    /**
     * Creates the endpoint over the three sources a scrape reads.
     *
     * @param collector     The same collector {@code /v1/stats} renders, so the two agree
     * @param queryRegistry What is running right now
     * @param readiness     Whether an operator has taken this node out of rotation
     * @throws NullPointerException if any collaborator is null
     */
    public MetricsEndpoint(NodeStatsCollector collector, QueryRegistry queryRegistry,
                           ServiceReadiness readiness) {
        this.collector = Objects.requireNonNull(collector, "collector cannot be null");
        this.queryRegistry =
            Objects.requireNonNull(queryRegistry, "queryRegistry cannot be null");
        this.readiness = Objects.requireNonNull(readiness, "readiness cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            ResponseBody.sendError(exchange, 405, "Method Not Allowed - Use GET");
            return;
        }
        try {
            byte[] body = render().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", EXPOSITION_CONTENT_TYPE);
            ResponseBody.send(exchange, 200, body);
        } catch (RuntimeException e) {
            LOGGER.error("Error rendering metrics: {}", e.getMessage(), e);
            ResponseBody.sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * Builds one scrape.
     *
     * @return The exposition body
     */
    private String render() {
        PrometheusText text = new PrometheusText();
        appendBuild(text);
        appendTraffic(text);
        appendQueries(text);
        appendCorpus(text, collector.collect());
        return text.render();
    }

    /** Which build is answering, so a scrape can be attributed to a version. */
    private static void appendBuild(PrometheusText text) {
        text.gauge("excalibur_build_info", "Which build this node is running, always 1",
                Map.of("version", BuildInfo.version(), "jvm", BuildInfo.jvm()), 1)
            .gauge("excalibur_uptime_seconds", "Seconds since this process started",
                BuildInfo.uptimeMs() / 1000.0);
    }

    /**
     * The request rate, the error rate and the latency distribution.
     *
     * <p>The three signals a monitoring system asks for first, and the three the node had
     * none of.</p>
     *
     * @param text The scrape being built
     */
    private static void appendTraffic(PrometheusText text) {
        Map<String, Double> quantiles = new LinkedHashMap<>();
        quantiles.put("0.5", (double) Metrics.getP50());
        quantiles.put("0.9", (double) Metrics.getP90());
        quantiles.put("0.95", (double) Metrics.getP95());
        quantiles.put("0.99", (double) Metrics.getP99());
        quantiles.put("0.999", (double) Metrics.getP999());
        text.summary("excalibur_query_latency_milliseconds",
            "Query latency, from the fixed-bucket histogram", quantiles,
            Metrics.QueryLatency().getTotalCount());

        for (ResponseCounters.StatusClass statusClass : ResponseCounters.StatusClass.values()) {
            text.counter("excalibur_http_responses_total", "Responses written, by status class",
                Map.of("class", statusClass.label()), ResponseCounters.count(statusClass));
        }
        for (ResponseCounters.Rejection rejection : ResponseCounters.Rejection.values()) {
            text.counter("excalibur_http_rejections_total",
                "Requests refused, broken out of the 4xx class",
                Map.of("reason", rejection.label()), ResponseCounters.rejections(rejection));
        }
        text.counter("excalibur_queries_executed_total", "Queries executed",
                Metrics.getQueriesExecuted())
            .counter("excalibur_entries_ingested_total", "Entries ingested",
                Metrics.getEntriesIngested())
            .gauge("excalibur_ingestion_rate_entries_per_second",
                "Entries ingested per second over the last sample window",
                Metrics.sampledIngestionRate());
    }

    /** What is running now, and whether this node is asking for traffic. */
    private void appendQueries(PrometheusText text) {
        text.gauge("excalibur_queries_active", "Queries in flight",
                queryRegistry.getActiveQueryCount())
            .counter("excalibur_queries_registered_total", "Queries registered since startup",
                queryRegistry.getTotalQueriesRegistered())
            .counter("excalibur_queries_cancelled_total", "Queries cancelled since startup",
                queryRegistry.getTotalQueriesCancelled())
            .gauge("excalibur_node_draining",
                "1 when an operator has taken this node out of rotation",
                readiness.isDraining() ? 1 : 0);
    }

    /**
     * The corpus, the indexes that serve it and the disks it sits on.
     *
     * @param text  The scrape being built
     * @param stats The snapshot, read once
     */
    private static void appendCorpus(PrometheusText text, NodeStats stats) {
        appendSegments(text, stats);
        appendIndexes(text, stats);
        appendVolumes(text, stats.volumes());
        appendDegradation(text, stats);
        appendLexical(text, stats.lexical());
        appendQueryCache(text, stats.queryCache());
    }

    /**
     * The query result cache, on a node that has one.
     *
     * <p>Hits and misses are one counter family split by a {@code result} label rather than
     * two families: a hit ratio is the only thing either number is read for, and a scrape that
     * can compute it from one family cannot pair a hit count with a stale miss count.</p>
     *
     * <p>Invalidations are separate from evictions on purpose. An eviction means the cache is
     * too small for the traffic and the answer is a larger {@code max-entries}; an
     * invalidation means the corpus moved and no size would have helped. Reported as one
     * number they are a figure nobody can act on.</p>
     *
     * @param text  The scrape being built
     * @param cache The cache's own account of itself, or null when there is none
     */
    private static void appendQueryCache(PrometheusText text,
                                         com.eonmux.excalibur.query.cache.QueryCacheStats cache) {
        if (cache == null) {
            return;
        }
        String lookups = "Query cache lookups since startup";
        text.gauge("excalibur_query_cache_capacity",
                "Answers the query cache may hold", cache.capacity())
            .gauge("excalibur_query_cache_entries",
                "Answers the query cache is holding", cache.size())
            .counter("excalibur_query_cache_lookups_total", lookups,
                Map.of("result", "hit"), cache.hits())
            .counter("excalibur_query_cache_lookups_total", lookups,
                Map.of("result", "miss"), cache.misses())
            .counter("excalibur_query_cache_evictions_total",
                "Answers dropped to stay within capacity", cache.evictions())
            .counter("excalibur_query_cache_invalidations_total",
                "Answers discarded because the corpus changed under them",
                cache.invalidations());
    }

    /** Segment lifecycle, entries and the epoch they are visible in. */
    private static void appendSegments(PrometheusText text, NodeStats stats) {
        NodeStats.SegmentStats segments = stats.segments();
        String help = "Segments in each lifecycle state";
        text.gauge("excalibur_segments", help, Map.of("state", "active"), segments.active())
            .gauge("excalibur_segments", help, Map.of("state", "indexing"), segments.indexing())
            .gauge("excalibur_segments", help, Map.of("state", "warming"), segments.warming())
            .gauge("excalibur_segments", help, Map.of("state", "compacting"),
                segments.compacting())
            .gauge("excalibur_segments", help, Map.of("state", "tombstoned"),
                segments.tombstoned())
            .gauge("excalibur_segments", help, Map.of("state", "purged"), segments.purged())
            .gauge("excalibur_segments_total", "Segments the catalog holds", segments.total())
            .gauge("excalibur_entries_total", "Entries across every segment",
                stats.entries().total())
            .gauge("excalibur_visibility_epoch", "The current visibility epoch",
                stats.visibility().epoch())
            .gauge("excalibur_visible_segments",
                "Segments a query in the current epoch can read",
                stats.visibility().visibleSegments())
            .gauge("excalibur_coverage_ratio",
                "Share of live segments a query reads, 0 to 1", stats.coverage());
    }

    /** The vector integration queue and the membership filters. */
    private static void appendIndexes(PrometheusText text, NodeStats stats) {
        NodeStats.HnswStats hnsw = stats.hnsw();
        NodeStats.CuckooStats cuckoo = stats.cuckoo();
        text.gauge("excalibur_hnsw_queue_depth", "Vectors waiting to be integrated",
                hnsw.queueDepth())
            .gauge("excalibur_hnsw_queue_depth_limit",
                "Depth at which the integration queue refuses more", hnsw.queueDepthLimit())
            .counter("excalibur_hnsw_vectors_integrated_total",
                "Vectors integrated since startup", hnsw.vectorsIntegrated())
            .gauge("excalibur_cuckoo_pending_deletions",
                "Deletions not yet applied to the membership filters",
                cuckoo.pendingDeletions())
            .counter("excalibur_cuckoo_deletions_total",
                "Deletions applied to the membership filters", cuckoo.totalDeletions())
            .gauge("excalibur_cuckoo_fp_rate_average",
                "Mean false-positive rate across segments", cuckoo.avgFpRate())
            .gauge("excalibur_cuckoo_fp_rate_max",
                "Worst false-positive rate of any segment", cuckoo.maxFpRate())
            .gauge("excalibur_cuckoo_saturated",
                "1 when a membership filter can take no more", cuckoo.saturated() ? 1 : 0)
            .gauge("excalibur_cuckoo_saturated_segments",
                "Segments whose membership filter is saturated", cuckoo.saturatedSegments());
    }

    /** Disk, including what is left of it, which is what readiness is decided on. */
    private static void appendVolumes(PrometheusText text, NodeStats.VolumeStats volumes) {
        text.gauge("excalibur_volume_count", "Registered volumes", volumes.count())
            .gauge("excalibur_volume_bytes_total", "Combined capacity of every volume",
                volumes.totalBytes())
            .gauge("excalibur_volume_bytes_used", "Bytes in use across every volume",
                volumes.usedBytes())
            .gauge("excalibur_volume_bytes_available", "Bytes free across every volume",
                volumes.availableBytes());
    }

    /**
     * The states this node keeps serving through, each of which it answers 200 during.
     *
     * <p>The breaker is exported as a number because an alert compares numbers: 0 closed,
     * 1 half-open, 2 open, so {@code > 0} is "the node is not using its embedding provider".
     * It is omitted entirely where there is no breaker, which is a different fact from a
     * closed one.</p>
     *
     * @param text  The scrape being built
     * @param stats The snapshot
     */
    private static void appendDegradation(PrometheusText text, NodeStats stats) {
        text.gauge("excalibur_index_orchestrator_running",
            "1 when the worker that drains the HNSW queue is alive",
            stats.indexerRunning() ? 1 : 0);
        if (stats.vectorIndex() != null) {
            text.gauge("excalibur_segments_without_vector_index",
                "Segments serving with no vector index; zero is the only healthy value",
                stats.vectorIndex().segmentsWithoutIndex());
        }
        if (stats.embeddingBreaker() != null) {
            text.gauge("excalibur_circuit_breaker_state",
                "0 closed, 1 half-open, 2 open", Map.of("breaker", "embedding"),
                breakerLevel(stats.embeddingBreaker()));
        }
        // The same gauge under a second label rather than a metric of its own: an alert on
        // "any breaker is open" should not have to be rewritten each time a breaker is added.
        if (stats.llmRelevancyBreaker() != null) {
            text.gauge("excalibur_circuit_breaker_state",
                "0 closed, 1 half-open, 2 open", Map.of("breaker", "llm_relevancy"),
                breakerLevel(stats.llmRelevancyBreaker()));
        }
    }

    /**
     * A circuit breaker state as a number an alert can order.
     *
     * @param state The state's name
     * @return 0 closed, 1 half-open, 2 open, -1 for a state this build does not know
     */
    private static double breakerLevel(String state) {
        return switch (state.toUpperCase(java.util.Locale.ROOT)) {
            case "CLOSED" -> 0;
            case "HALF_OPEN" -> 1;
            case "OPEN" -> 2;
            // Not folded into "open": a name this build does not recognise means the breaker
            // gained a state, and reporting it as the worst one would fire a false alarm.
            default -> -1;
        };
    }

    /** The chunk lexical index, and the erasure backlog it owes. */
    private static void appendLexical(PrometheusText text,
                                      com.eonmux.excalibur.ingest.lexical.ChunkIndexStats lexical) {
        if (lexical == null) {
            return;
        }
        String help = "Chunks in the lexical index";
        text.gauge("excalibur_lexical_chunks", help, Map.of("state", "live"),
                lexical.liveDocuments())
            .gauge("excalibur_lexical_chunks", help, Map.of("state", "erased"),
                lexical.erasedDocuments())
            .gauge("excalibur_lexical_segments", "Segments in the lexical index",
                lexical.segments())
            .gauge("excalibur_lexical_erasure_requests",
                "Erasure requests the index has not yet met", lexical.erasureRequests())
            .gauge("excalibur_lexical_oldest_erasure_age_seconds",
                "Age of the oldest unmet erasure request, the figure a retention "
                    + "obligation is measured against",
                lexical.oldestErasureRequest()
                    .map(at -> Math.max(0, System.currentTimeMillis() - at.toEpochMilli()) / 1000.0)
                    .orElse(0.0))
            .gauge("excalibur_lexical_compaction_due",
                "1 when the lexical index is due a compaction",
                lexical.compactionDue() ? 1 : 0);
    }
}
