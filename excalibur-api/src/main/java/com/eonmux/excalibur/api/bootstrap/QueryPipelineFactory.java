package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.query.executor.PqSearchSettings;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.query.scorer.LlmScorerFactory;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.DistanceFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Builds the query pipeline with every filter the node is able to give it.
 *
 * <p>Kept out of the composition root because the pipeline's correctness is mostly in what is
 * passed to it rather than in the call itself. Each of the last four arguments is a filter that
 * is inert when omitted and silently wrong when omitted by accident: without the atomicity
 * checker a query can see half an ingested document, without the membership filter Stage 1
 * cannot put the segments holding a query's terms first, and without the epoch validator a long-running query
 * can return graph nodes written after the snapshot it captured. None of those has a symptom an
 * operator would recognise as a missing argument, so the assembly is written down in one place
 * where it can be read against that list.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless. The pipeline it returns carries its own guarantees.</p>
 *
 * @author ExcaliburDB
 */
public final class QueryPipelineFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryPipelineFactory.class);

    private QueryPipelineFactory() {
        // Utility class
    }

    /**
     * Assembles the pipeline {@code POST /v1/query} answers from.
     *
     * @param config                    The node's configuration
     * @param storageEngine             Storage holding the segments and their HNSW graphs
     * @param indexOrchestrator         Supplies the visibility clock and the epoch validator
     * @param documentVisibilityChecker The document atomicity filter, shared with ingestion
     * @param hierarchicalFilter        The membership filter Stage 1 orders segments by
     * @return A pipeline ready to answer queries
     * @throws NullPointerException if any argument is null
     */
    public static QueryPipeline create(Config config,
                                       StorageEngine storageEngine,
                                       IndexOrchestrator indexOrchestrator,
                                       DocumentVisibilityChecker documentVisibilityChecker,
                                       HierarchicalCuckooFilter hierarchicalFilter) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        Objects.requireNonNull(indexOrchestrator, "indexOrchestrator cannot be null");
        Objects.requireNonNull(documentVisibilityChecker,
            "documentVisibilityChecker cannot be null");
        Objects.requireNonNull(hierarchicalFilter, "hierarchicalFilter cannot be null");

        LOGGER.info("Initializing query pipeline...");

        // The metric the corpus was embedded for. It is recorded in each graph's header when
        // the graph is created, so a node pointed at a corpus built under another metric is
        // refused at open rather than answering with a ranking nobody chose.
        DistanceFunction distanceFunction =
            DistanceFunctions.byName(config.query().getDistanceMetric());
        LOGGER.info("Vector distance metric: {}", distanceFunction.name());

        QueryPipeline pipeline = new QueryPipeline(
            storageEngine,
            indexOrchestrator.getVisibilityTracker(),
            documentVisibilityChecker,  // Document atomicity filter for query pipeline
            graphProvider(storageEngine),
            distanceFunction,
            llmScorer(config),  // LLM scorer with query-specific timeout
            QueryPipeline.DEFAULT_COVERAGE_THRESHOLD,
            hierarchicalFilter,  // Stage 1 searches the segments holding the query's terms first
            // Node-level epoch filtering. Omitting it leaves the pipeline unable to exclude graph
            // nodes written after the query's snapshot epoch, so a long-running query can return
            // vectors that did not exist when it started. The orchestrator maintains the
            // segment-scoped records this reads, and they were inert while nothing consumed them.
            indexOrchestrator.getEpochIsolationValidator(),
            // Quantised search, off by default. On, each segment's beam runs against product
            // quantisation codes and the widened candidate set is then re-measured against the
            // full-precision vectors, so the ranking is exact and only the beam is approximate.
            pqSearchSettings(config)
        );

        LOGGER.info("Query pipeline initialized with document atomicity, hierarchical Cuckoo "
            + "filter and node-level epoch isolation enabled");
        return pipeline;
    }

    /**
     * Resolves a segment's vector index at query time rather than at assembly time.
     *
     * <p>Each segment has an HNSW graph built incrementally during ingestion, so the set of
     * graphs is not known when the pipeline is created and a segment that has been tombstoned
     * since must resolve to nothing rather than to a stale mapping. Returning null for an unknown
     * segment is how the pipeline is told to skip it.</p>
     *
     * @param storageEngine Storage the segments are looked up in
     * @return A provider the pipeline calls once per candidate segment
     */
    private static QueryPipeline.HnswGraphProvider graphProvider(StorageEngine storageEngine) {
        return segmentId -> {
            Segment segment = storageEngine.getSegment(segmentId);
            if (segment != null) {
                return segment.getHnswGraph();
            }
            return null;
        };
    }

    /**
     * Builds the relevancy scorer, on the query timeout rather than the ingestion one.
     *
     * <p>{@code excalibur.query.llm-timeout-ms} exists because a scoring call sits inside a
     * request a client is waiting on, and the ingestion timeout is sized for a background pass
     * that nobody is holding a socket open for. A failure to construct it is not fatal: scoring
     * is a re-ranking of results the pipeline already has, so the node answers without it.</p>
     *
     * @param config The node's configuration
     * @return A scorer, or null when this node has no chat model or could not build one
     */
    private static QueryPipeline.LlmScorer llmScorer(Config config) {
        if (!RetrievalCapabilities.from(config.llm()).chatModel()) {
            LOGGER.info("LLM API key not configured - LLM scoring disabled");
            return null;
        }
        try {
            int queryLlmTimeoutMs = config.query().getLlmTimeoutMs();
            QueryPipeline.LlmScorer scorer = LlmScorerFactory.fromConfigWithQueryTimeout(
                config.llm(), queryLlmTimeoutMs);
            LOGGER.info("LLM scorer created with query-specific timeout: {}ms", queryLlmTimeoutMs);
            return scorer;
        } catch (Exception e) {
            LOGGER.warn("Failed to create LLM scorer: {}. LLM scoring will be disabled.",
                e.getMessage());
            return null;
        }
    }

    /**
     * Reads the quantised-search policy out of configuration.
     *
     * <p>Logged when it is on, because it changes which candidates a segment's beam visits and
     * an operator debugging recall needs to know it is in play. Silence means off, which is the
     * default.</p>
     *
     * @param config The node's configuration
     * @return The policy the query pipeline applies
     */
    static PqSearchSettings pqSearchSettings(Config config) {
        if (!config.shard().isPqSearchEnabled()) {
            return PqSearchSettings.DISABLED;
        }
        int oversample = config.shard().getPqOversample();
        LOGGER.info("Quantised vector search enabled, over-fetching {}x before re-measuring "
            + "candidates against their full-precision vectors", oversample);
        return new PqSearchSettings(true, oversample);
    }
}
