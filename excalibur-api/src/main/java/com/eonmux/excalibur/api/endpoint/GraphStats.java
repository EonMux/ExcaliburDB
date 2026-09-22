package com.eonmux.excalibur.api.endpoint;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * The graph half of a node, as {@code /v1/stats} and {@code /metrics} report it.
 *
 * <p>Every figure here was already counted somewhere - the tenant registry counts its evictions,
 * the mapping budget its mappings, the ingest sink every document it dropped - and none of it
 * reached either route. A node whose graph had quietly stopped growing, whose tenants were being
 * evicted on every request, or whose erasure obligations were past their deadline looked, from
 * outside, exactly like a healthy one.</p>
 *
 * <h2>Totals, never a row per namespace</h2>
 * <p>Every figure is summed across namespaces. A series per namespace is a series for every name
 * a caller ever put in the {@code X-Excalibur-Namespace} header, which is an unbounded number of
 * them on a scraper that bills by series; which namespace owes what is
 * {@code GET /v1/graph/namespaces}'s job.</p>
 *
 * <h2>What an absent block means</h2>
 * <p>{@code ingest} is null on a node that does not stage facts from ingestion - no chat model,
 * so no extractor - which is a different statement from a sink that has seen no documents.
 * {@code erasure} is null when the graph root could not be listed, which is a different statement
 * from a node that owes nothing.</p>
 *
 * @param namespaces The tenant registry: what is open and how often it evicts
 * @param budget     Mappings and descriptors against the process limits eviction defends
 * @param builds     Graph builds since startup
 * @param erasure    Erasure obligations across every namespace, or null if unreadable
 * @param ingest     What ingestion has staged into the graph, or null where it stages nothing
 * @author ExcaliburDB
 */
public record GraphStats(NamespaceStats namespaces, BudgetStats budget, BuildStats builds,
                         ErasureStats erasure, IngestStats ingest) {

    /**
     * Validates that the always-present blocks are present.
     *
     * @throws NullPointerException if namespaces, budget or builds is null
     */
    public GraphStats {
        Objects.requireNonNull(namespaces, "namespaces cannot be null");
        Objects.requireNonNull(budget, "budget cannot be null");
        Objects.requireNonNull(builds, "builds cannot be null");
    }

    /**
     * The tenant registry.
     *
     * @param open      Graphs open now
     * @param maxOpen   {@code excalibur.graph.max-open-namespaces}
     * @param evictions Graphs evicted since startup to stay within the bound or the budget
     */
    public record NamespaceStats(int open, int maxOpen, long evictions) {

        /** Validates that no figure is negative. */
        public NamespaceStats {
            if (open < 0 || maxOpen < 0 || evictions < 0) {
                throw new IllegalArgumentException("namespace counts must not be negative");
            }
        }
    }

    /**
     * Mappings and descriptors, the graphs' share and the whole process's, against the limits.
     *
     * <p>Both shares are reported because they answer different questions. The graphs' share is
     * what eviction can give back; the process figure is what the kernel's limit is actually
     * measured against, and the one to alert on.</p>
     *
     * @param graphMappings       Mappings the open graphs are accounted at
     * @param processMappings     Mappings the whole process holds, or empty where unmeasurable
     * @param mappingHighWater    Where eviction starts
     * @param mappingLimit        {@code vm.max_map_count}, or the assumed default
     * @param limitRead           Whether the mapping limit was read from the platform
     * @param graphDescriptors    Descriptors the open graphs are accounted at
     * @param processDescriptors  Descriptors the whole process holds, or empty
     * @param descriptorHighWater Where eviction starts, or 0 where no limit is enforced
     * @param descriptorLimit     {@code RLIMIT_NOFILE}, or 0 where none is enforced
     */
    public record BudgetStats(long graphMappings, OptionalLong processMappings,
                              long mappingHighWater, long mappingLimit, boolean limitRead,
                              long graphDescriptors, OptionalLong processDescriptors,
                              long descriptorHighWater, long descriptorLimit) {

        /** Validates the figures. */
        public BudgetStats {
            Objects.requireNonNull(processMappings, "processMappings cannot be null");
            Objects.requireNonNull(processDescriptors, "processDescriptors cannot be null");
            if (graphMappings < 0 || mappingHighWater < 0 || mappingLimit < 0
                    || graphDescriptors < 0 || descriptorHighWater < 0 || descriptorLimit < 0) {
                throw new IllegalArgumentException("budget figures must not be negative");
            }
        }
    }

    /**
     * Graph builds since startup, across every namespace.
     *
     * @param running   Whether one is in progress now
     * @param completed Builds that published a graph
     * @param failed    Builds that raised an error and published nothing
     * @param cancelled Builds that were asked to stop and stopped before publishing
     */
    public record BuildStats(boolean running, long completed, long failed, long cancelled) {

        /** Validates that no count is negative. */
        public BuildStats {
            if (completed < 0 || failed < 0 || cancelled < 0) {
                throw new IllegalArgumentException("build counts must not be negative");
            }
        }
    }

    /**
     * Erasure obligations across every namespace on disk.
     *
     * @param namespacesOwing      Namespaces with something outstanding
     * @param outstanding          Obligations outstanding across all of them
     * @param overdueNamespaces    Namespaces whose oldest obligation is past
     *                             {@code excalibur.graph.erasure-max-latency-seconds}
     * @param unreadableNamespaces Namespaces whose erasure log could not be read, and so are in
     *                             none of the other figures
     * @param oldestAgeMs          How long the longest-waiting obligation has waited, or empty
     *                             when nothing is owed
     */
    public record ErasureStats(int namespacesOwing, long outstanding, int overdueNamespaces,
                               int unreadableNamespaces, OptionalLong oldestAgeMs) {

        /** Validates the figures. */
        public ErasureStats {
            Objects.requireNonNull(oldestAgeMs, "oldestAgeMs cannot be null");
            if (namespacesOwing < 0 || outstanding < 0 || overdueNamespaces < 0
                    || unreadableNamespaces < 0) {
                throw new IllegalArgumentException("erasure counts must not be negative");
            }
        }
    }

    /**
     * What ingestion has staged into the graph since startup.
     *
     * <p>The three failure counts are the ones that matter, and each of them is invisible
     * anywhere else: the document they belong to is published and searchable, and only the
     * graph is missing what it said.</p>
     *
     * @param documentsSeen       Published documents offered to the graph
     * @param factsStaged         Facts staged, across every namespace
     * @param unresolvedDocuments Documents whose source this node no longer holds, so there was
     *                            no namespace to stage them into
     * @param extractionFailures  Documents the relation extractor failed on
     * @param tenantFailures      Documents whose namespace's graph could not be opened
     */
    public record IngestStats(long documentsSeen, long factsStaged, long unresolvedDocuments,
                              long extractionFailures, long tenantFailures) {

        /** Validates that no count is negative. */
        public IngestStats {
            if (documentsSeen < 0 || factsStaged < 0 || unresolvedDocuments < 0
                    || extractionFailures < 0 || tenantFailures < 0) {
                throw new IllegalArgumentException("ingest counts must not be negative");
            }
        }
    }
}
