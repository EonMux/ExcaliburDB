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
            // A counter, because each drop recurs on every query that reaches the same graph
            // nodes until their segments' graphs are restored or reindexed; any increase means a
            // node-to-document mapping is missing entries.
            .counter("excalibur_query_unnamed_results_dropped_total",
                "Query results dropped because they carried no document identity",
                Metrics.getUnnamedResultsDropped())
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
        appendGraph(text, stats.graph());
    }

    /**
     * The graph tenants, when this node builds its own graph.
     *
     * <p>Totals only. A label per namespace would be a series for every name a caller ever put
     * in the namespace header, which is the one kind of cardinality a scraper cannot be
     * configured around.</p>
     *
     * @param text  The scrape being built
     * @param graph The graph's figures, or null where there is no graph
     */
    private static void appendGraph(PrometheusText text, GraphStats graph) {
        if (graph == null) {
            return;
        }
        GraphStats.NamespaceStats namespaces = graph.namespaces();
        GraphStats.BuildStats builds = graph.builds();
        String buildsHelp = "Graph builds since startup, by how they ended";
        text.gauge("excalibur_graph_namespaces_open", "Graph namespaces open now",
                namespaces.open())
            .gauge("excalibur_graph_namespaces_open_limit",
                "Most graph namespaces kept open at once", namespaces.maxOpen())
            .counter("excalibur_graph_namespace_evictions_total",
                "Graph namespaces closed to stay within the open bound or the mapping budget",
                namespaces.evictions())
            .gauge("excalibur_graph_build_running", "1 while a graph build is in progress",
                builds.running() ? 1 : 0)
            .counter("excalibur_graph_builds_total", buildsHelp,
                Map.of("outcome", "completed"), builds.completed())
            .counter("excalibur_graph_builds_total", buildsHelp,
                Map.of("outcome", "failed"), builds.failed())
            .counter("excalibur_graph_builds_total", buildsHelp,
                Map.of("outcome", "cancelled"), builds.cancelled());
        appendBudget(text, graph.budget());
        appendGraphErasure(text, graph.erasure());
        appendGraphIngest(text, graph.ingest());
    }

    /**
     * Mappings and descriptors against the process limits the tenant budget defends.
     *
     * <p>One family per resource, split by a {@code scope} label, so the alert that matters is
     * a single ratio - {@code excalibur_mappings{scope="process"} / excalibur_mappings_limit} -
     * and the graphs' share sits beside it to say how much of that eviction could give back.
     * The process series is omitted where the platform cannot be asked, and the descriptor
     * limit and high-water mark where no limit is enforced, rather than exported as zeros an
     * alert would read as "nothing in use" or "no room at all".</p>
     *
     * @param text   The scrape being built
     * @param budget The budget's figures
     */
    private static void appendBudget(PrometheusText text, GraphStats.BudgetStats budget) {
        String mappings = "Memory mappings, the graphs' accounted share and the whole process";
        String descriptors = "File descriptors, the graphs' accounted share and the whole process";
        text.gauge("excalibur_mappings", mappings, Map.of("scope", "graphs"),
                budget.graphMappings())
            .gauge("excalibur_mappings_high_water",
                "Mappings above which graph namespaces are evicted", budget.mappingHighWater())
            .gauge("excalibur_mappings_limit",
                "vm.max_map_count, or the assumed default where it could not be read",
                budget.mappingLimit());
        budget.processMappings().ifPresent(count ->
            text.gauge("excalibur_mappings", mappings, Map.of("scope", "process"), count));
        text.gauge("excalibur_descriptors", descriptors, Map.of("scope", "graphs"),
            budget.graphDescriptors());
        budget.processDescriptors().ifPresent(count ->
            text.gauge("excalibur_descriptors", descriptors, Map.of("scope", "process"), count));
        if (budget.descriptorLimit() > 0) {
            text.gauge("excalibur_descriptors_high_water",
                    "Descriptors above which graph namespaces are evicted",
                    budget.descriptorHighWater())
                .gauge("excalibur_descriptors_limit", "The soft RLIMIT_NOFILE",
                    budget.descriptorLimit());
        }
    }

    /**
     * Erasure obligations across every namespace, omitted where the root could not be listed.
     *
     * @param text    The scrape being built
     * @param erasure The backlog, or null
     */
    private static void appendGraphErasure(PrometheusText text, GraphStats.ErasureStats erasure) {
        if (erasure == null) {
            return;
        }
        String namespaces = "Graph namespaces by the state of their erasure obligations";
        text.gauge("excalibur_graph_erasure_obligations",
                "Graph erasure obligations accepted and not yet discharged",
                erasure.outstanding())
            .gauge("excalibur_graph_erasure_namespaces", namespaces, Map.of("state", "owing"),
                erasure.namespacesOwing())
            .gauge("excalibur_graph_erasure_namespaces", namespaces, Map.of("state", "overdue"),
                erasure.overdueNamespaces())
            .gauge("excalibur_graph_erasure_namespaces", namespaces,
                Map.of("state", "unreadable"), erasure.unreadableNamespaces())
            .gauge("excalibur_graph_erasure_oldest_age_seconds",
                "Age of the oldest graph erasure obligation, the figure "
                    + "excalibur.graph.erasure-max-latency-seconds is measured against",
                erasure.oldestAgeMs().isPresent() ? erasure.oldestAgeMs().getAsLong() / 1000.0
                    : 0.0);
    }

    /**
     * What ingestion has staged into the graph, omitted on a node whose ingestion stages none.
     *
     * <p>The failures are one family split by {@code reason}, so an alert on any of them is
     * one expression and a new reason does not need a new alert.</p>
     *
     * @param text   The scrape being built
     * @param ingest The sink's counts, or null
     */
    private static void appendGraphIngest(PrometheusText text, GraphStats.IngestStats ingest) {
        if (ingest == null) {
            return;
        }
        String failures = "Published documents whose facts did not reach the graph, by reason";
        text.counter("excalibur_graph_ingest_documents_total",
                "Published documents offered to the graph", ingest.documentsSeen())
            .counter("excalibur_graph_ingest_facts_staged_total",
                "Facts staged into the graph across every namespace", ingest.factsStaged())
            .counter("excalibur_graph_ingest_failures_total", failures,
                Map.of("reason", "unresolved_source"), ingest.unresolvedDocuments())
            .counter("excalibur_graph_ingest_failures_total", failures,
                Map.of("reason", "extraction"), ingest.extractionFailures())
            .counter("excalibur_graph_ingest_failures_total", failures,
                Map.of("reason", "namespace_unavailable"), ingest.tenantFailures())
            .counter("excalibur_graph_ingest_failures_total", failures,
                Map.of("reason", "sink_refused"), ingest.sinkRefusals())
            .counter("excalibur_graph_ingest_withdrawal_failures_total",
                "Replaced documents whose earlier version's staged facts could not be withdrawn",
                ingest.withdrawalFailures());
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
                "Filter entries of tombstoned segments not yet removed from the membership filters",
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
     * <p>Each breaker is exported as a number because an alert compares numbers: 0 closed,
     * 1 half-open, 2 open, so {@code > 0} is "the node is not using that part of its
     * provider". The {@code breaker} label names which: {@code embedding},
     * {@code llm_relevancy}, {@code query_expansion}, {@code graph_extraction},
     * {@code graph_rerank}, {@code graph_query_embedding} or {@code graph_build_embedding}. A
     * breaker is omitted entirely where there is no such component, which is a different fact
     * from a closed one.</p>
     *
     * <p>The label takes those seven values and no others. The graph breakers in particular carry
     * no namespace label: every tenant's graph reranks, embeds and builds through one client
     * each, so one series is the whole truth, and a label per tenant would be a series per
     * tenant for a state they all share.</p>
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
        if (stats.queryExpansionBreaker() != null) {
            text.gauge("excalibur_circuit_breaker_state",
                "0 closed, 1 half-open, 2 open", Map.of("breaker", "query_expansion"),
                breakerLevel(stats.queryExpansionBreaker()));
        }
        if (stats.graphExtractionBreaker() != null) {
            text.gauge("excalibur_circuit_breaker_state",
                "0 closed, 1 half-open, 2 open", Map.of("breaker", "graph_extraction"),
                breakerLevel(stats.graphExtractionBreaker()));
        }
        if (stats.graphRerankBreaker() != null) {
            text.gauge("excalibur_circuit_breaker_state",
                "0 closed, 1 half-open, 2 open", Map.of("breaker", "graph_rerank"),
                breakerLevel(stats.graphRerankBreaker()));
        }
        if (stats.graphQueryEmbeddingBreaker() != null) {
            text.gauge("excalibur_circuit_breaker_state",
                "0 closed, 1 half-open, 2 open", Map.of("breaker", "graph_query_embedding"),
                breakerLevel(stats.graphQueryEmbeddingBreaker()));
        }
        if (stats.graphBuildEmbeddingBreaker() != null) {
            text.gauge("excalibur_circuit_breaker_state",
                "0 closed, 1 half-open, 2 open", Map.of("breaker", "graph_build_embedding"),
                breakerLevel(stats.graphBuildEmbeddingBreaker()));
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
            // Held on disk in a segment that could not be opened, so matched by no query: the
            // three states sum to everything the index holds.
            .gauge("excalibur_lexical_chunks", help, Map.of("state", "unsearchable"),
                lexical.unsearchableDocuments())
            .gauge("excalibur_lexical_segments", "Segments in the lexical index",
                lexical.segments())
            .gauge("excalibur_lexical_unreadable_segments",
                "Lexical segments that could not be opened and are kept out of every query",
                lexical.unreadableSegments().size())
            .gauge("excalibur_lexical_outdated_segments",
                "Lexical segments in an older format, awaiting rewrite by compaction",
                lexical.outdatedSegments())
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
                lexical.compactionDue() ? 1 : 0)
            // A counter, because each drop is permanent: the chunk is gone from keyword search
            // until its document is reindexed, and any increase means storage holds damage.
            .counter("excalibur_lexical_unreadable_chunks_dropped_total",
                "Chunks the lexical index dropped because their stored text or their segment was "
                    + "damaged beyond recovery",
                lexical.unreadableChunksDropped());
    }
}
