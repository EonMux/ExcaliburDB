package excalibur.api.bootstrap;

import io.excalibur.config.Config;
import io.excalibur.graph.erasure.CompactionPolicy;
import io.excalibur.graph.erasure.ErasureLog;
import io.excalibur.graph.erasure.GraphErasureCoordinator;
import io.excalibur.graph.staging.GraphStagingStore;
import io.excalibur.graph.store.MappedEdgeStore;
import io.excalibur.tenancy.NamespaceId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

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
 * <p>A served store is thirteen memory mappings; the staging and erasure logs are three small
 * append-only files. That asymmetry is why the mapping budget accounts for tenants by their store:
 * it is the store that runs a process out of mappings, and the logs are what a tenant must keep
 * even when nothing is querying it.</p>
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

    private final NamespaceId id;
    private final Path directory;
    private final GraphStagingStore staging;
    private final ErasureLog erasureLog;
    private final GraphRagHolder holder;
    private final GraphBuildService buildService;
    private final GraphErasureService erasureService;

    private GraphTenant(NamespaceId id, Path directory, GraphStagingStore staging,
                        ErasureLog erasureLog, GraphRagHolder holder,
                        GraphBuildService buildService, GraphErasureService erasureService) {
        this.id = id;
        this.directory = directory;
        this.staging = staging;
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
     * it.</p>
     *
     * @param id        The namespace
     * @param directory Where its graph is served from
     * @param config    Configuration, for the read side and the erasure policy
     * @return The tenant
     * @throws IOException if the staging store, the erasure log or an existing graph cannot be
     *                     opened
     */
    public static GraphTenant open(NamespaceId id, Path directory, Config config)
            throws IOException {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(config, "config must not be null");

        Path stagingDirectory = directory.resolveSibling(
            directory.getFileName() + STAGING_SUFFIX);

        Deque<AutoCloseable> opened = new ArrayDeque<>();
        try {
            GraphStagingStore staging = GraphStagingStore.open(stagingDirectory);
            opened.push(staging);

            ErasureLog erasureLog = ErasureLog.open(stagingDirectory.resolve(ERASURE_LOG));
            opened.push(erasureLog);

            GraphRagHolder holder = new GraphRagHolder(directory,
                Files.isDirectory(directory) ? GraphRagFactory.openAt(config, directory) : null);
            opened.push(holder);

            GraphBuildService buildService = new GraphBuildService(
                staging, holder, config, config.graph().getSingleValuedRelations(),
                entityEmbedder(config), embeddingDimension(config));

            GraphErasureService erasureService = new GraphErasureService(
                new GraphErasureCoordinator(
                    erasureLog,
                    CompactionPolicy.standard(
                        config.graph().getErasureTombstoneRatio(),
                        Duration.ofSeconds(config.graph().getErasureMaxLatencySeconds()),
                        Integer.MAX_VALUE),
                    Clock.systemUTC()),
                holder, config);

            LOGGER.info("graph tenant {} opened at {} ({} facts staged, graph {})",
                id, directory, staging.stagedCount(), holder.isPresent() ? "present" : "not built");
            return new GraphTenant(id, directory, staging, erasureLog, holder, buildService,
                erasureService);
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
     * Embeds entity text at build time, when this node has an embedding endpoint.
     *
     * <p>These vectors are what a query's exact rerank scores against, and producing them is the
     * expensive half of a build: one embedding call per batch of entities. A node with no endpoint
     * builds a graph ranked by term overlap alone, which is a weaker answer rather than a broken
     * one.</p>
     *
     * @param config Configuration, for the LLM settings
     * @return The embedder, or null when this node has no endpoint or was told not to
     */
    private static io.excalibur.retrieval.build.GraphDirectoryBuilder.EntityEmbedder
            entityEmbedder(Config config) {
        String apiKey = config.llm().getApiKey();
        if (!config.graph().isEntityVectorsEnabled() || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        excalibur.llm.embedding.EmbeddingClient client = new excalibur.llm.embedding.EmbeddingClient(
            excalibur.llm.client.LLMClientConfig.fromConfig(config.llm()));
        return client::embedBatch;
    }

    /**
     * The dimension the embedder produces.
     *
     * @param config Configuration
     * @return The dimension
     */
    private static int embeddingDimension(Config config) {
        return excalibur.llm.client.LLMClientConfig.fromConfig(config.llm())
            .getEmbeddingDimension();
    }

    /**
     * How many mappings and descriptors this tenant holds, for the budget.
     *
     * <p>A served store is thirteen mappings; a tenant that has never built one holds none. The
     * budget is told the truth about the present, not the worst case, because a tenant that has
     * staged facts and never built is exactly the cheap case worth keeping open.</p>
     *
     * @return {@code [mappings, descriptors]}
     */
    public long[] cost() {
        return holder.isPresent()
            ? new long[] { MappedEdgeStore.mappingCount(), MappedEdgeStore.descriptorCount() }
            : new long[] { 0L, 0L };
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

    @Override
    public void close() {
        closeQuietly(holder, "graph stack");
        closeQuietly(erasureLog, "erasure log");
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
