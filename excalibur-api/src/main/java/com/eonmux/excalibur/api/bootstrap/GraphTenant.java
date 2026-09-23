package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.build.BuildCancellation;
import com.eonmux.excalibur.graph.erasure.CompactionPolicy;
import com.eonmux.excalibur.graph.erasure.EdgeStoreCompactor;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureRequest;
import com.eonmux.excalibur.graph.erasure.GraphErasureCoordinator;
import com.eonmux.excalibur.graph.erasure.ServedArtifactEraser;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.ReferencedLabels;
import com.eonmux.excalibur.ingest.graph.StagedWithdrawals;
import com.eonmux.excalibur.retrieval.build.GraphDirectoryBuilder;
import com.eonmux.excalibur.retrieval.lexical.DerivedArtifactEraser;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * One namespace's graph: what it has staged, what it serves, and what it owes.
 *
 * <h2>Why a namespace owns all three</h2>
 * <p>Isolation that covered only the served store would be no isolation at all. Facts staged by one
 * tenant must not appear in another's graph, and an erasure obligation is owed to the tenant whose
 * data it concerns - a shared staging log or a shared erasure log would put both of those exactly
 * one bug away from crossing.</p>
 *
 * <h2>What it costs</h2>
 * <p>A served tenant is the edge store's thirteen mappings, two Lucene indexes, two dictionaries
 * and, where the graph was built with an embedder, the entity vectors; the Lucene pair also hold
 * the only file descriptors a tenant keeps, one lock each. The staging and erasure logs are three
 * small append-only files by comparison. That asymmetry is why the mapping budget accounts for
 * tenants by what they serve: it is the served stack that runs a process out of mappings, and the
 * logs are what a tenant must keep even when nothing is querying it.</p>
 *
 * <p>Only the Lucene half moves once a graph is open — segments merge, and the mappings over them
 * change with no one reopening anything — so {@link #cost()} measures rather than declares, and
 * {@code NamespaceRegistry.recost} is what folds a changed figure back into the budget.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. Its services serialise the operations that need it.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphTenant implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphTenant.class);

    /** Suffix of the directory holding a namespace's staged facts and erasure obligations. */
    public static final String STAGING_SUFFIX = "-staging";

    /** File within the staging directory recording erasure obligations. */
    public static final String ERASURE_LOG = "erasure.log";

    /**
     * File within the staging directory whose presence says the graph is withheld until an
     * interrupted erasure has been applied to it; see
     * {@link GraphErasureService#finishInterruptedErasure()}.
     */
    public static final String ERASURE_INTERRUPTED_MARKER = "erasure.interrupted";

    /**
     * Subdirectory of a namespace's directory holding the graph it serves.
     *
     * <p>Named here rather than at the one call site that resolves it, because it is half of an
     * on-disk layout the rest of this package now has to be able to read without opening a
     * tenant: an inventory, a drop and the erasure sweep all reach for these paths, and a layout
     * spelled out in four places is a layout that changes in three.</p>
     */
    public static final String GRAPH_SUBDIRECTORY = "graph";

    private final NamespaceId id;
    private final Path directory;
    private final GraphStagingStore staging;
    private final StagedWithdrawals withdrawals;
    private final ErasureLog erasureLog;
    private final GraphRagHolder holder;
    private final GraphBuildService buildService;
    private final GraphErasureService erasureService;

    private GraphTenant(NamespaceId id, Path directory, GraphStagingStore staging,
                        StagedWithdrawals withdrawals, ErasureLog erasureLog,
                        GraphRagHolder holder, GraphBuildService buildService,
                        GraphErasureService erasureService) {
        this.id = id;
        this.directory = directory;
        this.staging = staging;
        this.withdrawals = withdrawals;
        this.erasureLog = erasureLog;
        this.holder = holder;
        this.buildService = buildService;
        this.erasureService = erasureService;
    }

    /**
     * Opens a namespace's graph, whether or not it has ever built one.
     *
     * <p>Staging and the erasure log are opened either way: a tenant has to be able to accumulate
     * facts and accept obligations before its first build, and both must survive the builds that
     * replace the store directory wholesale - which is why they live beside it rather than in
     * it. So is the record of the withdrawals staging still owes, and whatever it holds is made
     * before the tenant is handed out.</p>
     *
     * @param id        The namespace
     * @param directory Where its graph is served from
     * @param config    Configuration, for the read side and the erasure policy
     * @param models    The node's model clients, which every stack this tenant opens - now,
     *                  after a build and after a compaction - reranks and embeds through
     * @return The tenant
     * @throws IOException if the staging store, the erasure log or an existing graph cannot be
     *                     opened
     */
    public static GraphTenant open(NamespaceId id, Path directory, Config config,
                                   GraphModelClients models) throws IOException {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(models, "models must not be null");

        Path stagingDirectory = stagingDirectoryOf(directory);

        Deque<AutoCloseable> opened = new ArrayDeque<>();
        try {
            GraphStagingStore staging = GraphStagingStore.open(stagingDirectory,
                labelHolders(directory));
            opened.push(staging);

            StagedWithdrawals withdrawals = StagedWithdrawals.open(
                staging, stagingDirectory.resolve(StagedWithdrawals.LOG_FILE));
            opened.push(withdrawals);
            retryWithdrawals(id, withdrawals);

            ErasureLog erasureLog = ErasureLog.open(stagingDirectory.resolve(ERASURE_LOG));
            opened.push(erasureLog);

            // One embedder for the tenant rather than one per collaborator: the two that need
            // it are the build and the erasure rebuild, and they are producing the same vectors
            // from the same endpoint - through the node's client, not one of the tenant's own.
            GraphDirectoryBuilder.EntityEmbedder embedder = entityEmbedder(config, models);
            int dimension = embeddingDimension(config);

            GraphErasureCoordinator coordinator = new GraphErasureCoordinator(
                erasureLog,
                CompactionPolicy.standard(
                    config.graph().getErasureTombstoneRatio(),
                    Duration.ofSeconds(config.graph().getErasureMaxLatencySeconds()),
                    Integer.MAX_VALUE),
                Clock.systemUTC(),
                // Erased alongside the served store. Removing an edge from the graph and
                // leaving the fact it was built from staged means the next build restores
                // exactly what the erasure removed.
                staging,
                servedArtifactErasers(config, staging, embedder, dimension));

            // Before anything can intern a label, and before what is owed decides whether the
            // graph is withheld: a request naming an id nothing was given would otherwise erase
            // whoever is given that id next. See GraphErasureCoordinator#dischargeUnassigned.
            List<ErasureRequest> discharged = coordinator.dischargeUnassigned(directory);
            if (!discharged.isEmpty()) {
                LOGGER.warn("graph tenant {} discharged {} erasure request(s) naming entity ids "
                    + "nothing in the namespace holds", id, discharged.size());
            }

            // A graph an erasure was part-way through replacing is not served until the
            // erasure has been applied to it; see erasureInterrupted. Otherwise openAt opens
            // what is there, and answers null when there is no graph.
            Path withheldMarker = stagingDirectory.resolve(ERASURE_INTERRUPTED_MARKER);
            boolean withhold = erasureInterrupted(directory, withheldMarker, erasureLog);
            GraphRagHolder holder = new GraphRagHolder(
                directory, withhold ? null : GraphRagFactory.openAt(config, directory, models));
            opened.push(holder);

            GraphBuildService buildService = new GraphBuildService(
                staging, holder, config, models, config.graph().getSingleValuedRelations(),
                embedder, dimension);

            GraphErasureService erasureService = new GraphErasureService(
                coordinator, holder, config, models, withheldMarker);

            if (withhold) {
                finishInterruptedErasure(id, erasureService);
            }

            LOGGER.info("graph tenant {} opened at {} ({} facts staged, graph {})",
                id, directory, staging.stagedCount(), holder.isPresent() ? "present"
                    : erasureService.servingWithheld() ? "withheld until an interrupted erasure "
                    + "is applied" : "not built");
            return new GraphTenant(id, directory, staging, withdrawals, erasureLog, holder,
                buildService, erasureService);
        } catch (IOException | RuntimeException e) {
            // Everything opened so far, released in reverse. A half-opened tenant would leave
            // mappings and file handles behind on a failure the caller is going to retry.
            while (!opened.isEmpty()) {
                try {
                    opened.pop().close();
                } catch (Exception suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
    }

    /**
     * Whether the graph on disk may be one an erasure compaction died part-way through replacing.
     *
     * <h3>Why it matters before anything is served</h3>
     * <p>A compaction erases staging, assembles the rewritten graph beside the served one and
     * publishes it by the renames a build uses, and it marks its obligations discharged only
     * after the publish. A process that dies before then leaves them outstanding and the graph
     * on disk still holding what they erase - with staging, the other copy, possibly clean
     * already. Three things on disk say so: crash recovery restoring a graph a publish had set
     * aside; the compaction's scratch directory, which a compaction that finishes or fails
     * never leaves behind; and the marker an earlier attempt to finish such an erasure left
     * because it did not succeed. A restored graph may equally be one a build was replacing, and
     * applying what is outstanding to it then is merely early; the other way round would serve
     * an erased person's facts for as long as the policy took to call the obligation due.</p>
     *
     * <p>Recovery runs here, so the {@link GraphRagFactory#openAt} that follows finds nothing
     * left to recover.</p>
     *
     * <p>A marker with nothing left to guard - no graph, or nothing outstanding - is removed.</p>
     *
     * @param directory      Where the graph is served from
     * @param withheldMarker The marker a failed attempt to finish an erasure leaves
     * @param erasureLog     The obligations this namespace owes
     * @return true when a graph is on disk, an obligation is outstanding, and one of those signs
     *         is present
     * @throws IOException if recovery cannot restore or remove a displaced graph, or a stale
     *                     marker cannot be removed
     */
    private static boolean erasureInterrupted(Path directory, Path withheldMarker,
                                              ErasureLog erasureLog) throws IOException {
        boolean restored = GraphBuilder.recoverInterruptedPublish(directory);
        boolean scratchLeft = Files.isDirectory(EdgeStoreCompactor.scratchFor(directory));
        boolean marked = Files.exists(withheldMarker);
        boolean owed = Files.isDirectory(directory) && erasureLog.outstandingCount() > 0;
        if (marked && !owed) {
            Files.delete(withheldMarker);
        }
        return (restored || scratchLeft || marked) && owed;
    }

    /**
     * Applies what a tenant owes to a graph an erasure was interrupted on, before serving it.
     *
     * <p>A failure does not fail the open. The tenant still has to accept obligations and stage
     * facts, and its graph stays unserved - which the erasure service remembers, so the next
     * compaction, the scheduled sweep's included, applies it and serves the graph.</p>
     *
     * @param id      The namespace, for the log lines
     * @param erasure The tenant's erasure service
     */
    private static void finishInterruptedErasure(NamespaceId id, GraphErasureService erasure) {
        LOGGER.warn("graph tenant {} found an erasure interrupted part-way through; applying {} "
            + "outstanding obligation(s) before its graph is served", id,
            erasure.outstandingCount());
        try {
            GraphErasureService.ErasureOutcome outcome = erasure.finishInterruptedErasure();
            LOGGER.info("graph tenant {} finished the interrupted erasure: {}", id,
                outcome.reason());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("graph tenant {} could not apply its outstanding erasure(s), so its "
                + "graph is not served until a later compaction does: {}", id, e.toString());
        }
    }

    /**
     * Makes the withdrawals a tenant's staging still owes, logging what is left.
     *
     * <p>Never fails the caller: what is not made now stays owed and is tried again.</p>
     *
     * @param id          The namespace, for the log line
     * @param withdrawals What its staging owes
     * @return How many are still owed
     */
    static int retryWithdrawals(NamespaceId id, StagedWithdrawals withdrawals) {
        int owed = withdrawals.retry();
        if (owed > 0) {
            LOGGER.warn("graph tenant {} still owes {} withdrawal(s) of staged facts; they are "
                + "retried by the sweep, before every build and when the tenant next opens",
                id, owed);
        }
        return owed;
    }

    /**
     * Where a namespace's staged facts and erasure obligations live.
     *
     * <p>Beside the graph directory rather than inside it, because a build replaces the graph
     * directory wholesale and neither the staged facts a rebuild reads nor the obligations the
     * node still owes may go with it.</p>
     *
     * @param graphDirectory The namespace's graph directory, served or not yet built
     * @return The staging directory, which may not exist yet
     */
    public static Path stagingDirectoryOf(Path graphDirectory) {
        Objects.requireNonNull(graphDirectory, "graphDirectory must not be null");
        return graphDirectory.resolveSibling(graphDirectory.getFileName() + STAGING_SUFFIX);
    }

    /**
     * Everything outside a namespace's staging directory's own logs that names its label ids.
     *
     * <p>The graph published from it, and the two logs of requests still to be applied to it -
     * erasures and withdrawals - which name entities by id. The staging store asks these before
     * it lets an interner cut damage off and give ids up, so an id any of them holds
     * is never handed to a different label; see {@link ReferencedLabels}. {@code fsck} names the
     * same holders for the same question.</p>
     *
     * @param graphDirectory Where the namespace's graph is served from
     * @return The holders
     */
    static ReferencedLabels labelHolders(Path graphDirectory) {
        Path staging = stagingDirectoryOf(graphDirectory);
        return ReferencedLabels.all(
            ReferencedLabels.publishedIn(graphDirectory),
            ReferencedLabels.owedIn(staging.resolve(ERASURE_LOG)),
            ReferencedLabels.owedIn(staging.resolve(StagedWithdrawals.LOG_FILE)));
    }

    /**
     * Where a namespace's erasure obligations are recorded.
     *
     * @param graphDirectory The namespace's graph directory, served or not yet built
     * @return The erasure log file, which may not exist yet
     */
    public static Path erasureLogOf(Path graphDirectory) {
        return stagingDirectoryOf(graphDirectory).resolve(ERASURE_LOG);
    }

    /**
     * Whether a namespace has ever published a graph, without opening one.
     *
     * <p>{@link #open} answers the same question the same way: the directory is written by the
     * build that publishes into it, so a tenant that has only staged facts has none. A graph an
     * interrupted publish left displaced counts, because opening the namespace restores it.</p>
     *
     * @param graphDirectory The namespace's graph directory
     * @return true if a build has published a graph there
     */
    public static boolean hasBuiltGraph(Path graphDirectory) {
        Objects.requireNonNull(graphDirectory, "graphDirectory must not be null");
        return Files.isDirectory(graphDirectory)
            || GraphBuilder.hasDisplacedGraph(graphDirectory);
    }

    /**
     * How many facts a namespace has staged, read from the log rather than from a store.
     *
     * <h3>What it costs</h3>
     * <p>A {@code stat} and a sixteen-byte read. The triple log is fixed-width records behind a
     * fixed header, which is how {@link GraphStagingStore#open} itself arrives at the count, so
     * the length of the file is the count once the header has said which version - and therefore
     * which width - its records are. Nothing is opened, mapped or replayed, and a log in an
     * earlier version is counted as it stands rather than upgraded by a status read.</p>
     *
     * <h3>What it is not</h3>
     * <p>The answer for a namespace nobody has open. A resident tenant may hold appends that are
     * buffered and not yet in the file, so where one exists its own
     * {@link GraphStagingStore#stagedCount()} is the better number and this is the fallback.</p>
     *
     * @param graphDirectory The namespace's graph directory
     * @return The whole records the log holds, or zero when nothing has been staged
     * @throws IOException if the log exists and cannot be measured
     */
    public static long stagedFactsOnDisk(Path graphDirectory) throws IOException {
        return GraphStagingStore.factsOnDisk(
            stagingDirectoryOf(graphDirectory).resolve(GraphStagingStore.TRIPLE_LOG));
    }

    /**
     * The erasers for the served artifacts {@code excalibur-graph} cannot read itself.
     *
     * <h3>Why a tenant has to supply these</h3>
     * <p>A compaction rewrites the edge store, the entity dictionary and the entity vectors,
     * and those are not where a person is findable. Their name is in the {@code labels} index
     * and up to two kilobytes of every document that mentioned them is in the {@code documents}
     * index, both of them Lucene directories that {@code excalibur-graph} depends on nothing
     * able to open. Supplying nothing here is not a quiet degradation - the compaction reports
     * both indexes as unreached and every request stays outstanding, which is honest and is not
     * an erasure.
     *
     * <h3>Why the staging store is the one handed over</h3>
     * <p>An entity's document is prose accumulated from every source that mentioned them, so a
     * request that withdraws one document cannot be answered by editing it; the only way through
     * is to rebuild the index from staging, which the coordinator has already erased by the time
     * an eraser runs. A tenant always has its staging store, so it can always offer that - which
     * is what makes a withdrawn document reachable rather than permanently owed.
     *
     * @param config    Configuration, for the Lucene write buffer
     * @param staging   The staged facts and prose a rebuild reads
     * @param embedder  Produces entity vectors, or null on a node with no embedding endpoint
     * @param dimension The dimension that embedder produces
     * @return The erasers to hand the coordinator
     */
    private static List<ServedArtifactEraser> servedArtifactErasers(
            Config config, GraphStagingStore staging,
            GraphDirectoryBuilder.EntityEmbedder embedder, int dimension) {
        double ramBufferMb = config.graph().getRamBufferMb();
        return List.of(embedder == null
            ? new DerivedArtifactEraser(staging, ramBufferMb)
            : new DerivedArtifactEraser(staging, ramBufferMb, embedder, dimension));
    }

    /**
     * Embeds entity text at build time, when this node has an embedding endpoint.
     *
     * <p>These vectors are what a query's exact rerank scores against, and producing them is the
     * expensive half of a build: one embedding call per batch of entities. A node with no endpoint
     * builds a graph ranked by term overlap alone, which is a weaker answer rather than a broken
     * one.</p>
     *
     * <p>Embedded through the node's build embedding client in {@link GraphModelClients}, not a
     * client of this tenant's: the endpoint is the node's, so its breaker has to be too - one
     * a provider outage trips once for every namespace, that a reopen does not reset, and that
     * the node reports.</p>
     *
     * @param config Configuration, for whether entity vectors are wanted at all
     * @param models The node's model clients
     * @return The embedder, or null when this node has no endpoint or was told not to
     */
    private static GraphDirectoryBuilder.EntityEmbedder entityEmbedder(Config config,
                                                                      GraphModelClients models) {
        if (!config.graph().isEntityVectorsEnabled()) {
            return null;
        }
        return models.entityEmbedder();
    }

    /**
     * The dimension the embedder produces.
     *
     * @param config Configuration
     * @return The dimension
     */
    private static int embeddingDimension(Config config) {
        return com.eonmux.excalibur.llm.client.LLMClientConfig.fromConfig(config.llm())
            .getEmbeddingDimension();
    }

    /**
     * How many mappings and descriptors this tenant holds, for the budget.
     *
     * <p>Summed over the whole served stack, not just the edge store: the two Lucene indexes, the
     * two dictionaries and the entity vectors are open too, and the Lucene pair also hold the only
     * descriptors a tenant keeps. Charging the edge store alone reported thirteen mappings and no
     * descriptors, which made the descriptor limit unreachable at any number of tenants and let
     * the mapping count run ahead of what the budget believed.</p>
     *
     * <p>A tenant that has never built a graph holds none of it. The budget is told the truth
     * about the present, not the worst case, because a tenant that has staged facts and never
     * built is exactly the cheap case worth keeping open. {@code NamespaceRegistry.recost} is what
     * corrects the total when that stops being true.</p>
     *
     * @return {@code [mappings, descriptors]}
     */
    public long[] cost() {
        return holder.cost();
    }

    /**
     * The namespace this tenant belongs to.
     *
     * @return The namespace id
     */
    public NamespaceId id() {
        return id;
    }

    /**
     * Where this tenant's graph is served from.
     *
     * @return The directory
     */
    public Path directory() {
        return directory;
    }

    /**
     * The stack currently being served, if this tenant has built one.
     *
     * @return The holder
     */
    public GraphRagHolder holder() {
        return holder;
    }

    /**
     * Builds this tenant's graph from what it has staged.
     *
     * @return The service
     */
    public GraphBuildService buildService() {
        return buildService;
    }

    /**
     * Builds this tenant's graph, stopping early if the build is asked to.
     *
     * <h3>Where it stops</h3>
     * <p>At the next staged fact, the next snippet or the next embedded entity - the boundaries
     * {@link BuildCancellation} marks - and never part-way through the publish. Everything up to
     * the publish is assembled in a scratch directory beside the served graph, so a build that
     * stops there costs its own work and nothing else: this tenant goes on serving the graph it
     * had. The publish itself closes the served stack, renames two directories and reopens what
     * it renamed, and a stop landing inside that window would leave a namespace serving nothing
     * over a graph that is intact on disk. So once a build reaches it, it finishes.</p>
     *
     * <h3>What a stopped build reports</h3>
     * <p>Nothing: it raises {@link com.eonmux.excalibur.graph.build.BuildCancelledException}, because a
     * build publishes by a rename or not at all and there is no partial graph to describe. The
     * honest partial result is the one the caller can still read - the graph from before.</p>
     *
     * @param cancelRequested Read at each of those boundaries; true asks the build to stop
     * @return What was built
     * @throws IOException if the graph cannot be built or reopened
     * @throws com.eonmux.excalibur.graph.build.BuildCancelledException if the build was asked to stop
     *                     and reached a boundary before publishing
     * @throws NullPointerException if cancelRequested is null
     */
    public GraphBuildService.BuildOutcome build(BooleanSupplier cancelRequested)
            throws IOException {
        Objects.requireNonNull(cancelRequested, "cancelRequested must not be null");
        // Scoped to this thread and closed here, so the pool thread that runs the next build
        // does not inherit a signal set for this one.
        // First, because a build publishes whatever staging holds: a withdrawal still owed is
        // what a document used to say, and building before it is made serves that for as long
        // as the build does.
        retryWithdrawals(id, withdrawals);
        try (BuildCancellation.Scope ignored = BuildCancellation.scope(cancelRequested)) {
            return buildService.build();
        }
    }

    /**
     * Accepts and applies this tenant's erasure obligations.
     *
     * @return The service
     */
    public GraphErasureService erasureService() {
        return erasureService;
    }

    /**
     * Where this tenant's ingestion stages the facts it extracts.
     *
     * @return The staging store
     */
    public GraphStagingStore staging() {
        return staging;
    }

    /**
     * The withdrawals from this tenant's staging that are owed, and how ingestion makes them.
     *
     * @return The withdrawals, over {@link #staging()}
     */
    public StagedWithdrawals withdrawals() {
        return withdrawals;
    }

    @Override
    public void close() {
        closeQuietly(holder, "graph stack");
        closeQuietly(erasureLog, "erasure log");
        closeQuietly(withdrawals, "record of owed withdrawals");
        closeQuietly(staging, "staging store");

        LOGGER.debug("graph tenant {} closed", id);
    }

    private void closeQuietly(AutoCloseable closeable, String what) {
        try {
            closeable.close();
        } catch (Exception e) {
            LOGGER.warn("failed to close the {} of graph tenant {}: {}", what, id, e.toString());
        }
    }

    @Override
    public String toString() {
        return "GraphTenant[" + id + ", " + directory + ", "
            + (holder.isPresent() ? "serving" : "not built") + "]";
    }
}
