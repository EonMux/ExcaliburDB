package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRoutingGraphIngestSink;
import com.eonmux.excalibur.tenancy.MappingBudget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Reads the graph half of a node into one {@link GraphStats}.
 *
 * <p>Three owners hold the figures and none of them is a stats component: the tenant registry,
 * the build runner and the ingest sink. This is the one place that knows where each lives, so
 * the collector asks one question and the renderers stay ignorant of the graph's structure.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Every source is read through its own thread-safe accessor.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphStatsReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphStatsReader.class);

    private final GraphTenants tenants;
    private final GraphBuildRunner builds;
    private final NamespaceRoutingGraphIngestSink ingest;

    /**
     * Creates a reader over a node's graph.
     *
     * @param tenants Every namespace's graph
     * @param builds  The runner every build goes through
     * @param ingest  The sink ingestion stages facts through, or null on a node with none
     * @throws NullPointerException if tenants or builds is null
     */
    public GraphStatsReader(GraphTenants tenants, GraphBuildRunner builds,
                            NamespaceRoutingGraphIngestSink ingest) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
        this.builds = Objects.requireNonNull(builds, "builds cannot be null");
        this.ingest = ingest;
    }

    /**
     * Reads everything, now.
     *
     * @return The snapshot
     */
    public GraphStats read() {
        GraphBuildRunner.Counts buildCounts = builds.counts();
        return new GraphStats(
            new GraphStats.NamespaceStats(tenants.openCount(), tenants.maxOpenNamespaces(),
                tenants.evictionCount()),
            budgetStats(tenants.budget().usage()),
            new GraphStats.BuildStats(buildCounts.running(), buildCounts.completed(),
                buildCounts.failed(), buildCounts.cancelled()),
            erasureStats(),
            ingestStats());
    }

    /**
     * The budget as it stands, with the process measured now.
     *
     * @param usage What the budget reports
     * @return The block
     */
    private static GraphStats.BudgetStats budgetStats(MappingBudget.Usage usage) {
        return new GraphStats.BudgetStats(usage.graphMappings(), usage.processMappings(),
            usage.mappingHighWater(), usage.maxMappings(), usage.limitWasRead(),
            usage.graphDescriptors(), usage.processDescriptors(), usage.descriptorHighWater(),
            usage.maxDescriptors());
    }

    /**
     * Erasure obligations across every namespace, or null when the root cannot be listed.
     *
     * <p>Absent rather than zero on failure: a node that could not look has not reported that
     * it owes nothing, and that is the one answer an erasure figure must never give by
     * default.</p>
     *
     * @return The block, or null
     */
    private GraphStats.ErasureStats erasureStats() {
        GraphTenants.ErasureBacklog backlog;
        try {
            backlog = tenants.erasureBacklog();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Graph erasure backlog unavailable: {}", e.toString());
            return null;
        }
        OptionalLong oldestAgeMs = backlog.oldestAge(Instant.now())
            .map(age -> OptionalLong.of(age.toMillis()))
            .orElse(OptionalLong.empty());
        return new GraphStats.ErasureStats(backlog.namespacesOwing(), backlog.obligations(),
            backlog.overdueNamespaces(), backlog.unreadableNamespaces(), oldestAgeMs);
    }

    /**
     * What ingestion has staged, or null on a node whose ingestion stages nothing.
     *
     * @return The block, or null
     */
    private GraphStats.IngestStats ingestStats() {
        if (ingest == null) {
            return null;
        }
        return new GraphStats.IngestStats(ingest.documentsSeen(), ingest.factsStaged(),
            ingest.unresolvedDocuments(), ingest.extractionFailures(), ingest.tenantFailures());
    }
}
