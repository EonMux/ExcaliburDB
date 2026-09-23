package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Everything this node knows about itself at one instant.
 *
 * <p>One snapshot, two renderings. {@code GET /v1/stats} serves it as JSON for a person or a
 * script, and {@code GET /metrics} serves it in the Prometheus text exposition format for a
 * scraper. They must not disagree, and the way two views of one system come to disagree is by
 * each gathering its own - so neither does: {@link NodeStatsCollector} gathers, and the two
 * endpoints only render.</p>
 *
 * <h2>What is optional, and what an absent block means</h2>
 * <p>Eleven blocks are nullable, and in each case absent is a different statement from zero.
 * A node with no lexical index is not a node whose index holds nothing; a node with no
 * embedding client has no circuit breaker rather than a closed one; a node that does no
 * relevancy scoring, no query expansion or no relation extraction has no breaker for it
 * rather than a healthy one, and neither does one that serves no graph reranked or embedded
 * through a model, or has built none whose entities were embedded; a storage engine
 * that could not be asked about its vector indexes has not reported that every segment has
 * one; a node running without a query result cache is not one whose cache has never been
 * hit; and a node that serves no graph of its own is not one whose tenants are all idle.</p>
 *
 * @param segments          Segment counts by lifecycle state
 * @param entries           How much the corpus holds
 * @param volumes           Disk, as the volume manager or the catalog sees it
 * @param hnsw              The vector integration queue
 * @param cuckoo            The membership filters
 * @param visibility        The epoch and what is visible in it
 * @param coverage          Share of live segments a query reads, in [0, 1]
 * @param lexical           The chunk lexical index, or null where this node keeps none
 * @param vectorIndex       Segments serving without a vector index, or null if unreadable
 * @param indexerRunning    Whether the worker that drains the HNSW queue is alive
 * @param embeddingBreaker  The embedding circuit breaker's state, or null where there is none
 * @param llmRelevancyBreaker The stage-5 relevancy breaker's state, or null where there is
 *                          no LLM scorer on this node
 * @param queryCache        What the query result cache has done, or null where this node
 *                          runs without one
 * @param graph             The graph tenants, their budget, builds, erasure backlog and what
 *                          ingestion stages into them, or null where this node builds no graph
 * @param queryExpansionBreaker  The query-expansion breaker's state, or null where this node
 *                               does not expand queries with a chat model
 * @param graphExtractionBreaker The relation-extraction breaker's state, or null where this
 *                               node does not extract facts with a chat model
 * @param graphRerankBreaker     The state of the breaker every graph on this node reranks
 *                               through, or null where no graph it has served reranks with a
 *                               chat model
 * @param graphQueryEmbeddingBreaker The state of the breaker every graph on this node embeds
 *                               questions through, or null where no graph it has served has
 *                               entity vectors to compare a question against
 * @param graphBuildEmbeddingBreaker The state of the breaker every graph build on this node
 *                               embeds entities through, or null where no build it has run has
 *                               embedded an entity
 * @author ExcaliburDB
 */
public record NodeStats(
    SegmentStats segments,
    EntryStats entries,
    VolumeStats volumes,
    HnswStats hnsw,
    CuckooStats cuckoo,
    VisibilityStats visibility,
    double coverage,
    ChunkIndexStats lexical,
    VectorIndexStats vectorIndex,
    boolean indexerRunning,
    String embeddingBreaker,
    String llmRelevancyBreaker,
    com.eonmux.excalibur.query.cache.QueryCacheStats queryCache,
    GraphStats graph,
    String queryExpansionBreaker,
    String graphExtractionBreaker,
    String graphRerankBreaker,
    String graphQueryEmbeddingBreaker,
    String graphBuildEmbeddingBreaker
) {

    /**
     * A snapshot of a node whose graphs rerank and embed through no model.
     *
     * <p>Such a node has none of the three graph model breakers, and says so by omission - the same statement
     * {@code collect()} makes for it.</p>
     *
     * @param segments            Segment counts by lifecycle state
     * @param entries             How much the corpus holds
     * @param volumes             Disk, as the volume manager or the catalog sees it
     * @param hnsw                The vector integration queue
     * @param cuckoo              The membership filters
     * @param visibility          The epoch and what is visible in it
     * @param coverage            Share of live segments a query reads, in [0, 1]
     * @param lexical             The chunk lexical index, or null where this node keeps none
     * @param vectorIndex         Segments serving without a vector index, or null if unreadable
     * @param indexerRunning      Whether the worker that drains the HNSW queue is alive
     * @param embeddingBreaker    The embedding breaker's state, or null where there is none
     * @param llmRelevancyBreaker The relevancy breaker's state, or null where there is none
     * @param queryCache          What the query cache has done, or null where there is none
     * @param graph               The graph half of the node, or null where it builds none
     * @param queryExpansionBreaker  The query-expansion breaker's state, or null where there is
     *                               none
     * @param graphExtractionBreaker The relation-extraction breaker's state, or null where there
     *                               is none
     */
    public NodeStats(SegmentStats segments, EntryStats entries, VolumeStats volumes,
                     HnswStats hnsw, CuckooStats cuckoo, VisibilityStats visibility,
                     double coverage, ChunkIndexStats lexical, VectorIndexStats vectorIndex,
                     boolean indexerRunning, String embeddingBreaker,
                     String llmRelevancyBreaker,
                     com.eonmux.excalibur.query.cache.QueryCacheStats queryCache,
                     GraphStats graph, String queryExpansionBreaker,
                     String graphExtractionBreaker) {
        this(segments, entries, volumes, hnsw, cuckoo, visibility, coverage, lexical,
            vectorIndex, indexerRunning, embeddingBreaker, llmRelevancyBreaker, queryCache, graph,
            queryExpansionBreaker, graphExtractionBreaker, null, null, null);
    }

    /**
     * A snapshot of a node with no chat model behind query expansion or relation extraction.
     *
     * <p>Such a node has neither breaker, and says so by omission - the same statement
     * {@code collect()} makes for it.</p>
     *
     * @param segments            Segment counts by lifecycle state
     * @param entries             How much the corpus holds
     * @param volumes             Disk, as the volume manager or the catalog sees it
     * @param hnsw                The vector integration queue
     * @param cuckoo              The membership filters
     * @param visibility          The epoch and what is visible in it
     * @param coverage            Share of live segments a query reads, in [0, 1]
     * @param lexical             The chunk lexical index, or null where this node keeps none
     * @param vectorIndex         Segments serving without a vector index, or null if unreadable
     * @param indexerRunning      Whether the worker that drains the HNSW queue is alive
     * @param embeddingBreaker    The embedding breaker's state, or null where there is none
     * @param llmRelevancyBreaker The relevancy breaker's state, or null where there is none
     * @param queryCache          What the query cache has done, or null where there is none
     * @param graph               The graph half of the node, or null where it builds none
     */
    public NodeStats(SegmentStats segments, EntryStats entries, VolumeStats volumes,
                     HnswStats hnsw, CuckooStats cuckoo, VisibilityStats visibility,
                     double coverage, ChunkIndexStats lexical, VectorIndexStats vectorIndex,
                     boolean indexerRunning, String embeddingBreaker,
                     String llmRelevancyBreaker,
                     com.eonmux.excalibur.query.cache.QueryCacheStats queryCache,
                     GraphStats graph) {
        this(segments, entries, volumes, hnsw, cuckoo, visibility, coverage, lexical,
            vectorIndex, indexerRunning, embeddingBreaker, llmRelevancyBreaker, queryCache, graph,
            null, null);
    }

    /**
     * A snapshot of a node that serves no graph of its own.
     *
     * <p>Absent is what such a node reports, for the reason every optional block here is
     * absent rather than zero: a node with no tenants and a node whose tenants are all idle
     * would otherwise read the same.</p>
     *
     * @param segments            Segment counts by lifecycle state
     * @param entries             How much the corpus holds
     * @param volumes             Disk, as the volume manager or the catalog sees it
     * @param hnsw                The vector integration queue
     * @param cuckoo              The membership filters
     * @param visibility          The epoch and what is visible in it
     * @param coverage            Share of live segments a query reads, in [0, 1]
     * @param lexical             The chunk lexical index, or null where this node keeps none
     * @param vectorIndex         Segments serving without a vector index, or null if unreadable
     * @param indexerRunning      Whether the worker that drains the HNSW queue is alive
     * @param embeddingBreaker    The embedding breaker's state, or null where there is none
     * @param llmRelevancyBreaker The relevancy breaker's state, or null where there is none
     * @param queryCache          What the query cache has done, or null where there is none
     */
    public NodeStats(SegmentStats segments, EntryStats entries, VolumeStats volumes,
                     HnswStats hnsw, CuckooStats cuckoo, VisibilityStats visibility,
                     double coverage, ChunkIndexStats lexical, VectorIndexStats vectorIndex,
                     boolean indexerRunning, String embeddingBreaker,
                     String llmRelevancyBreaker,
                     com.eonmux.excalibur.query.cache.QueryCacheStats queryCache) {
        this(segments, entries, volumes, hnsw, cuckoo, visibility, coverage, lexical,
            vectorIndex, indexerRunning, embeddingBreaker, llmRelevancyBreaker, queryCache, null);
    }

    /**
     * A snapshot of a node that runs no query result cache.
     *
     * <p>Kept for the callers that predate the cache. Absent is the statement such a node
     * makes: a cache that has never been asked anything and a node with no cache at all
     * report the same zeros, and only one of them is worth an operator's attention.</p>
     *
     * @param segments            Segment counts by lifecycle state
     * @param entries             How much the corpus holds
     * @param volumes             Disk, as the volume manager or the catalog sees it
     * @param hnsw                The vector integration queue
     * @param cuckoo              The membership filters
     * @param visibility          The epoch and what is visible in it
     * @param coverage            Share of live segments a query reads, in [0, 1]
     * @param lexical             The chunk lexical index, or null where this node keeps none
     * @param vectorIndex         Segments serving without a vector index, or null if unreadable
     * @param indexerRunning      Whether the worker that drains the HNSW queue is alive
     * @param embeddingBreaker    The embedding breaker's state, or null where there is none
     * @param llmRelevancyBreaker The relevancy breaker's state, or null where there is none
     */
    public NodeStats(SegmentStats segments, EntryStats entries, VolumeStats volumes,
                     HnswStats hnsw, CuckooStats cuckoo, VisibilityStats visibility,
                     double coverage, ChunkIndexStats lexical, VectorIndexStats vectorIndex,
                     boolean indexerRunning, String embeddingBreaker,
                     String llmRelevancyBreaker) {
        this(segments, entries, volumes, hnsw, cuckoo, visibility, coverage, lexical,
            vectorIndex, indexerRunning, embeddingBreaker, llmRelevancyBreaker, null, null);
    }

    /**
     * A snapshot of a node that reports no relevancy breaker.
     *
     * <p>Kept so that a caller which never had one - a renderer's fixture, a node assembled
     * without an LLM scorer - states that by omission rather than by passing a null it had to
     * be told about. It is the same statement {@code collect()} makes on such a node.</p>
     *
     * @param segments       Segment counts by lifecycle state
     * @param entries        How much the corpus holds
     * @param volumes        Disk, as the volume manager or the catalog sees it
     * @param hnsw           The vector integration queue
     * @param cuckoo         The membership filters
     * @param visibility     The epoch and what is visible in it
     * @param coverage       Share of live segments a query reads, in [0, 1]
     * @param lexical        The chunk lexical index, or null where this node keeps none
     * @param vectorIndex    Segments serving without a vector index, or null if unreadable
     * @param indexerRunning Whether the worker that drains the HNSW queue is alive
     * @param embeddingBreaker The embedding breaker's state, or null where there is none
     */
    public NodeStats(SegmentStats segments, EntryStats entries, VolumeStats volumes,
                     HnswStats hnsw, CuckooStats cuckoo, VisibilityStats visibility,
                     double coverage, ChunkIndexStats lexical, VectorIndexStats vectorIndex,
                     boolean indexerRunning, String embeddingBreaker) {
        this(segments, entries, volumes, hnsw, cuckoo, visibility, coverage, lexical,
            vectorIndex, indexerRunning, embeddingBreaker, null, null, null);
    }

    /**
     * Validates domain constraints on construction.
     */
    public NodeStats {
        Objects.requireNonNull(segments, "segments cannot be null");
        Objects.requireNonNull(entries, "entries cannot be null");
        Objects.requireNonNull(volumes, "volumes cannot be null");
        Objects.requireNonNull(hnsw, "hnsw cannot be null");
        Objects.requireNonNull(cuckoo, "cuckoo cannot be null");
        Objects.requireNonNull(visibility, "visibility cannot be null");

        if (Double.isNaN(coverage) || coverage < 0.0 || coverage > 1.0) {
            throw new IllegalArgumentException("Coverage must be in range [0.0, 1.0]: " + coverage);
        }
    }

    /**
     * Segment lifecycle statistics.
     *
     * @param total      Every segment the catalog holds
     * @param active     Segments fully in service
     * @param indexing   Segments absorbing their write log
     * @param warming    Segments training their quantizer
     * @param compacting Segments being rewritten
     * @param tombstoned Segments removed from service but not yet purged
     * @param purged     Segments whose files are gone
     */
    public record SegmentStats(
        long total,
        long active,
        long indexing,
        long warming,
        long compacting,
        long tombstoned,
        long purged
    ) {
        /** Validates that no count is negative. */
        public SegmentStats {
            if (total < 0 || active < 0 || indexing < 0 || warming < 0
                || compacting < 0 || tombstoned < 0 || purged < 0) {
                throw new IllegalArgumentException("All segment counts must be non-negative");
            }
        }
    }

    /**
     * Entry statistics.
     *
     * @param total How many entries the corpus holds
     */
    public record EntryStats(long total) {
        /** Validates that the count is not negative. */
        public EntryStats {
            if (total < 0) {
                throw new IllegalArgumentException("Entry count must be non-negative: " + total);
            }
        }
    }

    /**
     * Volume statistics.
     *
     * @param count      How many volumes are registered
     * @param totalBytes Their combined capacity
     * @param usedBytes  How much of that is in use
     */
    public record VolumeStats(
        int count,
        long totalBytes,
        long usedBytes
    ) {
        /** Validates that no figure is negative. */
        public VolumeStats {
            if (count < 0) {
                throw new IllegalArgumentException("Volume count must be non-negative: " + count);
            }
            if (totalBytes < 0) {
                throw new IllegalArgumentException("Total bytes must be non-negative: " + totalBytes);
            }
            if (usedBytes < 0) {
                throw new IllegalArgumentException("Used bytes must be non-negative: " + usedBytes);
            }
        }

        /**
         * How many bytes are left across every volume.
         *
         * <p>The figure readiness is decided on: a node whose volumes are full cannot absorb
         * an ingestion, compact a segment or roll a reindex, and will start failing writes
         * whatever its coverage says.</p>
         *
         * @return A non-negative byte count
         */
        public long availableBytes() {
            return Math.max(0, totalBytes - usedBytes);
        }

        /**
         * The share of capacity still free, or 1.0 where capacity is unknown.
         *
         * <p>Unknown capacity reads as free rather than as full, because the fallback that
         * produces a zero capacity is a node with no volume manager - which is a node that
         * has not been told where its disks are, not one that has run out.</p>
         *
         * @return A ratio in [0, 1]
         */
        public double availableRatio() {
            return totalBytes <= 0 ? 1.0 : (double) availableBytes() / totalBytes;
        }
    }

    /**
     * HNSW graph statistics.
     *
     * @param queueDepth        Vectors waiting to be integrated
     * @param queueDepthLimit   The depth at which the queue refuses more
     * @param vectorsIntegrated How many have been integrated since startup
     */
    public record HnswStats(
        long queueDepth,
        int queueDepthLimit,
        long vectorsIntegrated
    ) {
        /** Validates that no figure is negative. */
        public HnswStats {
            if (queueDepth < 0) {
                throw new IllegalArgumentException("Queue depth must be non-negative: " + queueDepth);
            }
            if (queueDepthLimit < 0) {
                throw new IllegalArgumentException(
                    "Queue depth limit must be non-negative: " + queueDepthLimit);
            }
            if (vectorsIntegrated < 0) {
                throw new IllegalArgumentException(
                    "Vectors integrated must be non-negative: " + vectorsIntegrated);
            }
        }
    }

    /**
     * Cuckoo filter statistics.
     *
     * <p>Deletions are counted in filter entries - distinct terms, since a filter holds each
     * term once. A segment's entries become pending when it is tombstoned and are removed by
     * the maintenance worker's next sweep, which moves them from one count to the other. The
     * false-positive rates are estimated from each segment filter's load at the time of the
     * request, over the segments whose filters hold anything; both are 0 when none do.</p>
     *
     * @param pendingDeletions  Entries of tombstoned segments not yet removed from the filters
     * @param totalDeletions    Entries removed since startup
     * @param avgFpRate         Mean estimated false-positive rate across non-empty segment filters
     * @param maxFpRate         Worst estimated false-positive rate of any segment filter
     * @param saturated         Whether any filter can take no more
     * @param saturatedSegments How many are in that state
     */
    public record CuckooStats(
        int pendingDeletions,
        long totalDeletions,
        double avgFpRate,
        double maxFpRate,
        boolean saturated,
        int saturatedSegments
    ) {
        /** Validates the counts and the two rates. */
        public CuckooStats {
            if (pendingDeletions < 0) {
                throw new IllegalArgumentException(
                    "Pending deletions must be non-negative: " + pendingDeletions);
            }
            if (totalDeletions < 0) {
                throw new IllegalArgumentException(
                    "Total deletions must be non-negative: " + totalDeletions);
            }
            if (saturatedSegments < 0) {
                throw new IllegalArgumentException(
                    "Saturated segments must be non-negative: " + saturatedSegments);
            }
            if (Double.isNaN(avgFpRate) || avgFpRate < 0.0 || avgFpRate > 1.0) {
                throw new IllegalArgumentException(
                    "Average FP rate must be in range [0.0, 1.0]: " + avgFpRate);
            }
            if (Double.isNaN(maxFpRate) || maxFpRate < 0.0 || maxFpRate > 1.0) {
                throw new IllegalArgumentException(
                    "Max FP rate must be in range [0.0, 1.0]: " + maxFpRate);
            }
        }
    }

    /**
     * Visibility tracking statistics.
     *
     * @param epoch              The current visibility epoch
     * @param registeredSegments Segments the tracker knows about
     * @param visibleSegments    Segments a query in this epoch can read
     */
    public record VisibilityStats(
        long epoch,
        int registeredSegments,
        int visibleSegments
    ) {
        /** Validates that no figure is negative. */
        public VisibilityStats {
            if (epoch < 0) {
                throw new IllegalArgumentException("Epoch must be non-negative: " + epoch);
            }
            if (registeredSegments < 0) {
                throw new IllegalArgumentException(
                    "Registered segments must be non-negative: " + registeredSegments);
            }
            if (visibleSegments < 0) {
                throw new IllegalArgumentException(
                    "Visible segments must be non-negative: " + visibleSegments);
            }
        }
    }

    /**
     * Segments that came up without a vector index.
     *
     * <p>Zero is the only healthy value. Such a segment is kept in service - its key-value
     * data is intact - so a vector search over it is neither an error nor empty: it is short
     * by whatever that segment held. That is the failure mode nobody notices, which is why
     * the reason is carried alongside the count: a dimension mismatch and an unreadable file
     * need different answers from an operator.</p>
     *
     * @param segmentsWithoutIndex How many segments are serving without their graph
     * @param reasons              Why, keyed by segment index and sorted by it
     */
    public record VectorIndexStats(int segmentsWithoutIndex, Map<Long, String> reasons) {

        /** Validates the count and takes an ordered, immutable copy of the reasons. */
        public VectorIndexStats {
            if (segmentsWithoutIndex < 0) {
                throw new IllegalArgumentException(
                    "Segments without a vector index must be non-negative: " + segmentsWithoutIndex);
            }
            Objects.requireNonNull(reasons, "reasons cannot be null");
            // Sorted, so two reads of the same state render identically and a diff between
            // them shows what changed on the node rather than a hash order.
            reasons = Map.copyOf(new TreeMap<>(reasons));
        }

        /**
         * The reasons in segment-index order.
         *
         * @return An ordered view, empty when every segment has its graph
         */
        public Map<Long, String> orderedReasons() {
            return new TreeMap<>(reasons);
        }
    }
}
