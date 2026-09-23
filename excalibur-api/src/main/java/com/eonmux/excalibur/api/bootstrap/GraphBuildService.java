package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;
import com.eonmux.excalibur.retrieval.build.GraphDirectoryBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Turns what ingestion has staged into the graph the node serves.
 *
 * <h2>Why this is a step and not a consequence of ingesting</h2>
 * <p>The edge store is a sorted, contiguous CSR - the property that makes traversal a bounded walk
 * rather than a search - and nothing can be appended to it. Ingestion therefore stages facts and a
 * build produces a whole store from them. Making that a step someone takes, rather than something
 * that happens after every document, is deliberate: a build is proportional to the whole graph, and
 * doing one per document would spend all of a node's time rebuilding and none of it ingesting.</p>
 *
 * <h2>One build at a time</h2>
 * <p>Builds are serialised, and serialised against erasure compaction, by the holder's
 * {@link GraphRagHolder#replacementLock() replacement lock}. Two builds at once would race over the
 * same scratch directory, and a build beside a compaction could republish facts the compaction
 * had just erased from staging.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use; a build in progress blocks another rather than overlapping it.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphBuildService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphBuildService.class);

    private final GraphStagingStore staging;
    private final GraphRagHolder holder;
    private final Config config;
    private final GraphModelClients models;
    private final List<String> singleValuedRelations;

    /**
     * Embeds each entity's text at build time, when this node has an embedding endpoint.
     *
     * <p>These are what a query's exact rerank scores against. Null is a node with no endpoint,
     * which builds a graph that is ranked by term overlap alone.</p>
     */
    private final GraphDirectoryBuilder.EntityEmbedder embedder;

    /** Dimension of the vectors {@link #embedder} produces. */
    private final int embeddingDimension;

    /**
     * Creates the service.
     *
     * @param staging               Where ingestion stages the facts it extracts
     * @param holder                The stack to replace once a build has published
     * @param config                Read for the Lucene write buffer and the graph directory
     * @param models                The node's model clients, which the published graph is
     *                              opened to rerank and embed through
     * @param singleValuedRelations Relations that hold one value at a time, by label
     */
    public GraphBuildService(GraphStagingStore staging, GraphRagHolder holder, Config config,
                             GraphModelClients models, List<String> singleValuedRelations) {
        this(staging, holder, config, models, singleValuedRelations, null, 0);
    }

    /**
     * Creates the service, with an embedder for the vectors a query reranks against.
     *
     * @param staging               Where ingestion stages the facts it extracts
     * @param holder                The stack to replace once a build has published
     * @param config                Read for the Lucene write buffer and the graph directory
     * @param models                The node's model clients, which the published graph is
     *                              opened to rerank and embed through
     * @param singleValuedRelations Relations that hold one value at a time, by label
     * @param embedder              Embeds each entity's text, or null to build without vectors
     * @param embeddingDimension    The dimension that embedder produces
     */
    public GraphBuildService(GraphStagingStore staging, GraphRagHolder holder, Config config,
                             GraphModelClients models, List<String> singleValuedRelations,
                             GraphDirectoryBuilder.EntityEmbedder embedder,
                             int embeddingDimension) {
        this.staging = Objects.requireNonNull(staging, "staging must not be null");
        this.holder = Objects.requireNonNull(holder, "holder must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.models = Objects.requireNonNull(models, "models must not be null");
        this.singleValuedRelations = List.copyOf(
            Objects.requireNonNull(singleValuedRelations, "singleValuedRelations must not be null"));
        this.embedder = embedder;
        this.embeddingDimension = embeddingDimension;
    }

    /**
     * What a build did.
     *
     * @param published whether a new graph was published
     * @param edges     facts in the published store
     * @param entities  entity ids it addresses
     * @param relations relations it names
     * @param indexed   entities that had text to rank them by
     * @param staged    facts staged at the moment of the build
     */
    public record BuildOutcome(boolean published, long edges, int entities, int relations,
                               int indexed, long embedded, long staged) {
    }

    /**
     * Builds the graph from everything staged, and serves it.
     *
     * @return What was built
     * @throws IOException if the graph cannot be built or reopened
     */
    public BuildOutcome build() throws IOException {
        ReentrantLock buildLock = holder.replacementLock();
        buildLock.lock();
        try {
            Path target = holder.directory();
            SupersessionPolicy supersession =
                GraphBuilder.singleValued(staging, singleValuedRelations);

            // Opened from the directory that was just published, rather than from the objects
            // the build held: what the node serves has to be what is on disk, or a restart would
            // answer differently from the process that built it. The previous graph goes on
            // serving throughout - the stack opens read-only and takes no lock the replacement
            // needs - and is kept on disk until the new one has opened, so a build that
            // publishes something unopenable leaves the node serving what it served before.
            GraphDirectoryBuilder.Published<GraphRagFactory.GraphRag> built =
                GraphDirectoryBuilder.build(staging, target, supersession,
                    config.graph().getRamBufferMb(), embedder, embeddingDimension,
                    published -> GraphRagFactory.openPublished(config, published, models));
            GraphDirectoryBuilder.BuildResult result = built.result();
            if (!result.published()) {
                LOGGER.info("graph build for {} had nothing to publish", target);
                return new BuildOutcome(false, 0, result.entities(), result.relations(), 0,
                    0, staging.stagedCount());
            }
            holder.install(built.opened());

            LOGGER.info("graph built and served from {}: {} facts over {} entities",
                target, result.edges(), result.entities());
            return new BuildOutcome(true, result.edges(), result.entities(), result.relations(),
                result.indexed(), result.embedded(), staging.stagedCount());
        } finally {
            buildLock.unlock();
        }
    }

    /**
     * How many facts are staged and waiting for a build.
     *
     * @return The count
     */
    public long stagedCount() {
        return staging.stagedCount();
    }

    /**
     * The staging store, for a caller that wants to record facts directly.
     *
     * @return The store
     */
    public GraphStagingStore staging() {
        return staging;
    }
}
