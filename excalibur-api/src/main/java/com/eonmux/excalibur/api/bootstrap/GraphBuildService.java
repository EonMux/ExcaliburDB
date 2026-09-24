package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.PublishFence;
import com.eonmux.excalibur.graph.staging.PublishRefusedException;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;
import com.eonmux.excalibur.retrieval.build.GraphDirectoryBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

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
 * <h2>What a build tells staging</h2>
 * <p>A build reads {@link GraphStagingStore#changeCount()} before it reads staging, and once it
 * has published hands it back to {@link GraphStagingStore#markPublished(long)}, which is what
 * lets the node tell a current graph from one behind its staging. A build with nothing staged
 * publishes nothing, and leaves a graph it would otherwise have replaced where it is - unless
 * staging has changed since that graph was published, in which case the graph holds only what
 * has since been withdrawn, and it is retired: taken out of service and removed, so graph
 * queries answer that nothing is built rather than with the facts of documents that are
 * gone.</p>
 *
 * <h2>What a build leaves out</h2>
 * <p>Staging holds a fact until an erasure compaction rewrites it away, and an obligation to
 * erase is owed from the moment it is recorded - a retraction of an asserted fact, an erasure of
 * a person or of a document - which is usually well before its compaction runs. A build reads
 * staging as it finds it, so each build takes a {@link PublishFence} from the namespace's erasure
 * log first and leaves out of every artifact it publishes whatever an outstanding obligation
 * covers: the facts, the names and the prose. An obligation recorded while the build runs is not
 * one it left out, so the publish goes through the fence, which refuses it; the build then ends
 * with {@link PublishRefusedException}, publishing nothing and leaving the graph marked behind so
 * the next build - automatic or asked for - publishes without it.</p>
 *
 * <p>The build discharges nothing. What it left out is still staged, and may still be in a copy
 * of an earlier graph beside the served one; the compaction removes those and only then records
 * the obligation met. For the same reason the build still tells staging it has published: the
 * graph it served lacks exactly what that compaction will erase, and the compaction erases
 * staging without recording a change for a graph that already agrees with it.</p>
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
     * Hands each build what the namespace's outstanding erasures cover, and the gate its publish
     * passes through. Asked once per build, under the replacement lock.
     */
    private final Supplier<PublishFence> fences;

    /**
     * Creates the service, for staging with no erasure log behind it.
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
     * Creates the service, with an embedder for the vectors a query reranks against, for
     * staging with no erasure log behind it.
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
        this(staging, holder, config, models, singleValuedRelations, embedder,
            embeddingDimension, () -> PublishFence.NONE);
    }

    /**
     * Creates the service over a namespace's staging and the erasure obligations it owes.
     *
     * @param staging               Where ingestion stages the facts it extracts
     * @param holder                The stack to replace once a build has published
     * @param config                Read for the Lucene write buffer and the graph directory
     * @param models                The node's model clients, which the published graph is
     *                              opened to rerank and embed through
     * @param singleValuedRelations Relations that hold one value at a time, by label
     * @param embedder              Embeds each entity's text, or null to build without vectors
     * @param embeddingDimension    The dimension that embedder produces
     * @param fences                Asked once per build, under the replacement lock, for what
     *                              the namespace's outstanding erasures cover and the gate its
     *                              publish passes; normally the erasure log's
     *                              {@code fence()}
     */
    public GraphBuildService(GraphStagingStore staging, GraphRagHolder holder, Config config,
                             GraphModelClients models, List<String> singleValuedRelations,
                             GraphDirectoryBuilder.EntityEmbedder embedder,
                             int embeddingDimension, Supplier<PublishFence> fences) {
        this.staging = Objects.requireNonNull(staging, "staging must not be null");
        this.holder = Objects.requireNonNull(holder, "holder must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.models = Objects.requireNonNull(models, "models must not be null");
        this.singleValuedRelations = List.copyOf(
            Objects.requireNonNull(singleValuedRelations, "singleValuedRelations must not be null"));
        this.embedder = embedder;
        this.embeddingDimension = embeddingDimension;
        this.fences = Objects.requireNonNull(fences, "fences must not be null");
    }

    /**
     * What a build did.
     *
     * @param published whether a new graph was published
     * @param retired   whether, with nothing to publish, the graph it would have replaced was
     *                  taken out of service because everything it held had been withdrawn or
     *                  is owed to an erasure
     * @param edges     facts in the published store
     * @param entities  entity ids it addresses
     * @param relations relations it names
     * @param indexed   entities that had text to rank them by
     * @param embedded  entities that got a vector
     * @param staged    facts staged at the moment of the build
     * @param withheld  staged facts left out because an outstanding erasure obligation covers
     *                  them; still staged, and still owed, until a compaction erases them
     */
    public record BuildOutcome(boolean published, boolean retired, long edges, int entities,
                               int relations, int indexed, long embedded, long staged,
                               long withheld) {
    }

    /**
     * Builds the graph from everything staged that no outstanding erasure covers, and serves it.
     *
     * @return What was built
     * @throws PublishRefusedException if an erasure was recorded while the graph was being built;
     *                                 nothing was published, and the graph stays marked behind
     * @throws IOException if the graph cannot be built or reopened
     */
    public BuildOutcome build() throws IOException {
        ReentrantLock buildLock = holder.replacementLock();
        buildLock.lock();
        try {
            // Before anything reads staging: a change counted after this is one the build may
            // not have seen, and keeps the graph marked behind.
            long asOf = staging.changeCount();
            boolean behind = staging.unpublishedChanges().pending();
            Path target = holder.directory();
            SupersessionPolicy supersession =
                GraphBuilder.singleValued(staging, singleValuedRelations);
            // Under the lock, and before staging is read: every obligation recorded until now is
            // left out, and one recorded after refuses the publish. A compaction cannot run
            // meanwhile, so nothing is discharged under the build either.
            PublishFence fence = fences.get();

            // Opened from the directory that was just published, rather than from the objects
            // the build held: what the node serves has to be what is on disk, or a restart would
            // answer differently from the process that built it. The previous graph goes on
            // serving throughout - the stack opens read-only and takes no lock the replacement
            // needs - and is kept on disk until the new one has opened, so a build that
            // publishes something unopenable leaves the node serving what it served before.
            GraphDirectoryBuilder.Published<GraphRagFactory.GraphRag> built =
                GraphDirectoryBuilder.build(staging, target, supersession,
                    config.graph().getRamBufferMb(), embedder, embeddingDimension,
                    published -> GraphRagFactory.openPublished(config, published, models),
                    fence);
            GraphDirectoryBuilder.BuildResult result = built.result();
            if (!result.published()) {
                // With facts withheld and none left, the graph being served holds nothing that
                // is not withdrawn or owed to an erasure: it was built from staging as it stood,
                // which recording an obligation changes nothing in, or it is behind a staging
                // that has since lost what it held.
                boolean retired = (behind || result.withheld() > 0) && retire(target);
                LOGGER.info("graph build for {} had nothing to publish{}", target,
                    retired ? "; the graph it served held only withdrawn facts or facts owed to "
                        + "an erasure, and was retired" : "");
                published(asOf);
                return new BuildOutcome(false, retired, 0, result.entities(), result.relations(),
                    0, 0, staging.stagedCount(), result.withheld());
            }
            holder.install(built.opened());
            published(asOf);

            LOGGER.info("graph built and served from {}: {} facts over {} entities, {} staged "
                + "fact(s) withheld for {} outstanding erasure(s)", target, result.edges(),
                result.entities(), result.withheld(), fence.obligations());
            return new BuildOutcome(true, false, result.edges(), result.entities(),
                result.relations(), result.indexed(), result.embedded(), staging.stagedCount(),
                result.withheld());
        } finally {
            buildLock.unlock();
        }
    }

    /**
     * Takes a graph out of service and off disk, once everything it was built from is withdrawn.
     *
     * <p>Queries are drained first, then every copy a publish could restore is removed before the
     * served one, so recovery cannot bring back what this retires.</p>
     *
     * @param target Where the graph is served from
     * @return true if there was a graph to retire
     * @throws IOException if it cannot be removed
     */
    private boolean retire(Path target) throws IOException {
        if (!Files.isDirectory(target) && !GraphBuilder.hasDisplacedGraph(target)) {
            return false;
        }
        holder.withdraw();
        GraphBuilder.discard(GraphBuilder.previousFor(target));
        GraphBuilder.discard(GraphBuilder.retiredFor(target));
        GraphBuilder.discard(target);
        return true;
    }

    /**
     * Tells staging a graph built from it as of {@code asOf} is now being served.
     *
     * <p>A marker that cannot be removed does not fail a build that has already published: it is
     * logged, and costs one more build than was needed.</p>
     *
     * @param asOf The change count read before the build read staging
     */
    private void published(long asOf) {
        try {
            if (!staging.markPublished(asOf)) {
                LOGGER.debug("staging changed while the graph at {} was being built; it stays "
                    + "marked behind", holder.directory());
            }
        } catch (IOException e) {
            LOGGER.warn("could not clear the record of unpublished changes for {}; the graph "
                + "will be built once more than it needs to be: {}", holder.directory(),
                e.toString());
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
