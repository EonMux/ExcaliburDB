package com.eonmux.excalibur.api.embedded;

import com.eonmux.excalibur.api.bootstrap.ApiConfigKeys;
import com.eonmux.excalibur.api.bootstrap.NodeAssembly;
import com.eonmux.excalibur.api.retrieval.VisibleChunkText;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.server.InProcessServices;
import com.eonmux.excalibur.api.server.ShutdownBudget;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.ConfigLoader;
import com.eonmux.excalibur.config.ConfigValidation;
import com.eonmux.excalibur.core.memory.ForeignMemoryBootstrap;
import com.eonmux.excalibur.core.text.SourceScope;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.lexical.StoredChunkText;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.source.InlineSource;
import com.eonmux.excalibur.ingest.source.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * ExcaliburDB in-process: a node over one data directory, called directly instead of over HTTP.
 *
 * <h2>The same node the server runs</h2>
 * <p>Opened through the assembly the server uses ({@link NodeAssembly}), so an embedded node and
 * a served one over the same directory are the same node: the document registry persists, a
 * supplied document replaces its previous version, erasure reaches storage, the lexical index
 * and the vectors, and queries take the route {@code POST /v1/query} would take. What it does not
 * have is the listener, and with it the HTTP surface around the corpus - crawl registration,
 * re-indexing on request, graph building and querying, the result cache and relevancy scoring by
 * the chat model. A directory can be served and embedded in turn, never at once.</p>
 *
 * <h2>Use</h2>
 * <pre>{@code
 * try (ExcaliburEmbedded db = ExcaliburEmbedded.builder(Path.of("data"))
 *         .property("excalibur.llm.embedding-base-url", "http://localhost:11434")
 *         .property("excalibur.llm.embedding-model", "nomic-embed-text")
 *         .property("excalibur.llm.embeddings-dimensions", "768")
 *         .property("excalibur.query.vector-dimensions", "768")
 *         .open()) {
 *     db.supply("handbook", List.of(EmbeddedDocument.of("tiering.txt", "...")));
 *     SearchAnswer answer = db.query("which policy moves cold segments", 3);
 * }
 * }</pre>
 *
 * <p>Configuration is the server's: the same keys, the same defaults and the same validation,
 * with {@code EXCALIBUR_*} variables applied only when {@link Builder#environment} is given
 * them. With no model configured the node indexes and answers lexically, exactly as a server
 * does.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>{@link Builder#open()} opens the directory and starts the background workers;
 * {@link #close()} stops them in the order the server's shutdown does, persists the registry and
 * releases the directory, within {@code excalibur.api.shutdown-timeout-seconds}. Every other
 * method refuses once {@code close()} has begun.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Every method may be called from any number of threads at once. Supplying two batches under
 * one source id at the same time is refused by the second, as the HTTP route refuses it.</p>
 */
public final class ExcaliburEmbedded implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExcaliburEmbedded.class);

    /** How often a supply looks at the epoch while it waits for the next one. */
    private static final long EPOCH_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    /** Ticks a supply waits for before it leaves the epoch to catch up on its own. */
    private static final int EPOCH_WAIT_TICKS = 50;

    /** The name the configuration reports its source under when no file was read. */
    private static final String IN_PROCESS_CONFIG = "embedded";

    private final NodeAssembly node;
    private final Duration shutdownTimeout;
    private final IngestionOrchestrator ingestion;
    private final DocumentErasureService erasure;
    private final DocumentRegistry registry;
    private final SourceCatalog sourceCatalog;
    private final Function<String, String> chunkText;
    private final EmbeddedRetrieval retrieval;
    private final long defaultTimeoutMs;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** How long a supply waits for the epoch its documents become visible at. */
    private final long epochWaitNanos;

    private ExcaliburEmbedded(NodeAssembly node, InProcessServices services,
                              ApiSecurityConfig limits, Duration shutdownTimeout) {
        this.node = node;
        this.shutdownTimeout = shutdownTimeout;
        this.ingestion = services.ingestionOrchestrator();
        this.erasure = services.documentErasure();
        this.registry = services.documentRegistry();
        this.sourceCatalog = services.sourceCatalog();
        this.chunkText = VisibleChunkText.over(StoredChunkText.of(services.storageEngine()),
            services.queryPipeline().getVisibilityChecker());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.retrieval = new EmbeddedRetrieval(services, limits, chunkText, executor);
        this.defaultTimeoutMs = Math.min(services.config().visibility().getQueryTimeoutMs(),
            limits.maxQueryTimeoutMs());
        this.epochWaitNanos = TimeUnit.MILLISECONDS.toNanos(
            services.config().visibility().getEpochTickMs() * EPOCH_WAIT_TICKS);
    }

    /**
     * Starts describing a node over a data directory.
     *
     * @param dataDirectory Where the node keeps its files; created if it does not exist
     * @return The builder
     * @throws NullPointerException if dataDirectory is null
     */
    public static Builder builder(Path dataDirectory) {
        return new Builder(dataDirectory);
    }

    /**
     * Prepares {@code java.lang.foreign} before any thread can reach it.
     *
     * <p>{@link Builder#open()} does this first, so an application whose first use of the
     * library is opening a node needs nothing else. One that starts threads of its own that
     * might touch {@code java.lang.foreign} before then calls this first: two threads initializing
     * it at once deadlock the JVM in a way no timeout can break. Idempotent.</p>
     */
    public static void prepare() {
        ForeignMemoryBootstrap.ensureInitialized();
    }

    /** @return The data directory this node keeps its files in */
    public Path dataDirectory() {
        return node.dataDirectory();
    }

    /**
     * The route a search takes when both of this node's signals are healthy.
     *
     * @return The route, or empty on a node with neither an embedding endpoint nor a chunk index,
     *         which answers every query with {@link IllegalStateException}
     */
    public Optional<SearchRoute> route() {
        return retrieval.route();
    }

    /**
     * Supplies documents under a source id, and returns once they are searchable.
     *
     * <p>{@code POST /v1/documents} in-process: each document is chunked, embedded and indexed
     * before this returns. Supplying a document id again replaces what the node holds under it,
     * the new version published before the old one's surplus is removed, so a query sees one
     * version or the other and never neither.</p>
     *
     * @param sourceId  What the documents are recorded under, and erased by; at most
     *                  {@value Source#MAX_ID_LENGTH} characters
     * @param documents At least one document
     * @return What was ingested
     * @throws IllegalArgumentException if the source id is blank, too long or belongs to a crawl
     *                                  registered in this data directory, or no document is given
     * @throws IllegalStateException    if the node is closed, closing, or already ingesting this
     *                                  source
     * @throws IngestionOrchestrator.IngestionFailedException if the run failed outright
     */
    public SupplyOutcome supply(String sourceId, List<EmbeddedDocument> documents) {
        requireOpen();
        requireSuppliableSource(sourceId);
        Objects.requireNonNull(documents, "documents cannot be null");
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("Supply at least one document");
        }
        List<InlineSource.Document> batch = new ArrayList<>(documents.size());
        for (EmbeddedDocument document : documents) {
            Objects.requireNonNull(document, "documents cannot contain null");
            batch.add(new InlineSource.Document(document.documentId(),
                document.text().getBytes(StandardCharsets.UTF_8), document.contentType(),
                document.lastModifiedMillis()));
        }
        IngestionOrchestrator.IngestionOutcome outcome =
            ingestion.ingestNow(InlineSource.of(sourceId, batch));
        awaitNextEpoch();
        return new SupplyOutcome(sourceId, outcome.documentsIngested(), outcome.chunksIngested(),
            outcome.documentsFailed(), outcome.stopped());
    }

    /**
     * Searches the corpus within the node's default query timeout.
     *
     * @param text The query text, embedded by the node with the model the corpus was embedded
     *             with when it has one
     * @param topK Most hits to return, at most {@code excalibur.api.max-top-k}
     * @return The hits, best first, and the route that produced them
     * @throws IllegalArgumentException if the text is blank or a bound is violated
     * @throws IllegalStateException    if the node is closed, has no signal to search, or every
     *                                  signal it has failed
     * @throws InterruptedException     if the calling thread is interrupted while it waits
     * @see #query(String, int, Duration)
     */
    public SearchAnswer query(String text, int topK) throws InterruptedException {
        return query(text, topK, Duration.ofMillis(defaultTimeoutMs));
    }

    /**
     * Searches the corpus.
     *
     * <p>The route is the one {@code POST /v1/query} takes on the same node: both signals fused
     * where the node has an embedding endpoint and a chunk index, whichever one it has otherwise,
     * and the surviving signal alone when the other fails. The answer says which.</p>
     *
     * @param text    The query text
     * @param topK    Most hits to return, at most {@code excalibur.api.max-top-k}
     * @param timeout The search's budget, at most {@code excalibur.api.max-query-timeout-ms}
     * @return The hits, best first, and the route that produced them
     * @throws IllegalArgumentException if the text is blank or a bound is violated
     * @throws IllegalStateException    if the node is closed, has no signal to search, or every
     *                                  signal it has failed
     * @throws InterruptedException     if the calling thread is interrupted while it waits
     */
    public SearchAnswer query(String text, int topK, Duration timeout) throws InterruptedException {
        requireOpen();
        Objects.requireNonNull(timeout, "timeout cannot be null");
        return retrieval.search(text, topK, timeout.toMillis(), SourceScope.ALL);
    }

    /**
     * Searches the documents of some sources.
     *
     * <p>Ranked as {@link #query(String, int, Duration)} ranks, with every hit from one of the
     * named sources. A source is told from another whose id extends it - {@code team} from
     * {@code team:wiki} - by the source each document was supplied under, not by its id. An id no
     * source is registered under contributes nothing rather than failing.</p>
     *
     * @param text      The query text
     * @param topK      Most hits to return, at most {@code excalibur.api.max-top-k}
     * @param timeout   The search's budget, at most {@code excalibur.api.max-query-timeout-ms}
     * @param sourceIds The sources to answer from; at least one
     * @return The hits, best first, and the route that produced them
     * @throws IllegalArgumentException if the text is blank, a bound is violated, or sourceIds
     *                                  is empty, names a blank id or names more than
     *                                  {@link SourceScope#MAX_SOURCES}
     * @throws IllegalStateException    if the node is closed, has no signal to search, or every
     *                                  signal it has failed
     * @throws InterruptedException     if the calling thread is interrupted while it waits
     */
    public SearchAnswer query(String text, int topK, Duration timeout,
                              Collection<String> sourceIds) throws InterruptedException {
        requireOpen();
        Objects.requireNonNull(timeout, "timeout cannot be null");
        return retrieval.search(text, topK, timeout.toMillis(), SourceScope.of(sourceIds));
    }

    /**
     * Reads back the text of a chunk a search returned.
     *
     * @param chunkId The identifier a {@link SearchHit} or {@link StoredDocument} names
     * @return The chunk's text, or empty when the node does not hold it
     * @throws IllegalStateException if the node is closed
     */
    public Optional<String> fetch(String chunkId) {
        requireOpen();
        Objects.requireNonNull(chunkId, "chunkId cannot be null");
        return Optional.ofNullable(chunkText.apply(chunkId));
    }

    /**
     * Every document the node holds, ordered by source and then by document id.
     *
     * @return The documents
     * @throws IllegalStateException if the node is closed
     */
    public List<StoredDocument> documents() {
        requireOpen();
        return listed(null);
    }

    /**
     * The documents the node holds under one source, ordered by document id.
     *
     * @param sourceId The source
     * @return Its documents, empty when it has none
     * @throws IllegalStateException if the node is closed
     */
    public List<StoredDocument> documents(String sourceId) {
        requireOpen();
        return listed(Objects.requireNonNull(sourceId, "sourceId cannot be null"));
    }

    /**
     * Erases documents from storage, from the lexical index and from the vectors.
     *
     * <p>{@code POST /v1/erasure} for named documents, in-process. Space is reclaimed by the
     * compaction policy; nothing is searchable once this returns.</p>
     *
     * @param sourceId    The source they were supplied or crawled under
     * @param documentIds The documents to erase
     * @return What was removed, and what was not found
     * @throws IllegalStateException if the node is closed
     */
    public ErasureOutcome erase(String sourceId, Collection<String> documentIds) {
        requireOpen();
        Objects.requireNonNull(sourceId, "sourceId cannot be null");
        Objects.requireNonNull(documentIds, "documentIds cannot be null");
        DocumentErasureService.Outcome outcome = erasure.erase(sourceId, documentIds, false);
        return new ErasureOutcome(outcome.erasedDocumentIds(), outcome.documentsNotFound(),
            outcome.documentsIncomplete(), 0, outcome.chunksNotRemoved(), false);
    }

    /**
     * Erases everything the node holds of a source, and the source's registration with it.
     *
     * <p>{@code POST /v1/erasure} with {@code "scope": "source"}, in-process: for a caller that
     * wants everything a source contributed and holds no list of its documents. A crawl
     * registered under the id in this data directory is deregistered first, as the route does,
     * because a node serving the directory would otherwise crawl the documents straight back.</p>
     *
     * <p>Unlike the route it is not a batch of a thousand: the route bounds a request so that one
     * caller cannot hold the node's mutate budget, and a call made in-process holds nothing but
     * its own thread. The documents are the node's list as the call begins, each erased once;
     * any supplied under the source while it runs are counted in
     * {@link ErasureOutcome#documentsRemaining()} rather than chased.</p>
     *
     * @param sourceId The source to erase
     * @return What was removed, what could not be, and whether a registration was dropped
     * @throws IllegalStateException if the node is closed
     * @throws IOException           if the source's registration could not be dropped; nothing
     *                               has been erased then, and the call can be repeated
     */
    public ErasureOutcome eraseSource(String sourceId) throws IOException {
        requireOpen();
        Objects.requireNonNull(sourceId, "sourceId cannot be null");
        boolean deregistered = sourceCatalog != null && sourceCatalog.forget(sourceId);
        DocumentErasureService.Outcome outcome =
            erasure.erase(sourceId, registry.getDocumentIds(sourceId), false);
        // The incomplete documents keep their registry entries so they can be erased again;
        // they were reached, so only what is left beyond them was supplied after the list.
        int remaining = Math.max(0, registry.size(sourceId) - outcome.documentsIncomplete());
        return new ErasureOutcome(outcome.erasedDocumentIds(), 0, outcome.documentsIncomplete(),
            remaining, outcome.chunksNotRemoved(), deregistered);
    }

    /**
     * Stops the node and releases its data directory.
     *
     * <p>The server's shutdown sequence: ingestion and the query pipeline drained, the index
     * orchestrator and the storage engine's background work stopped, the chunk index published
     * and closed, the document registry persisted, the graphs and the storage engine closed.
     * Idempotent; a second call returns at once.</p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("Closing the embedded node over {}", node.dataDirectory());
        try {
            if (node.shutdown(shutdownTimeout).run()) {
                node.releaseDataDirectory();
            } else {
                LOGGER.error("The embedded node over {} did not shut down cleanly; its data "
                    + "directory stays claimed until this process exits, so nothing opens it "
                    + "while it may still be written", node.dataDirectory());
            }
        } catch (IOException e) {
            LOGGER.error("Could not release the claim on {}: {}", node.dataDirectory(),
                e.toString());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Waits for the visibility epoch to pass the one ingestion published at.
     *
     * <p>A published chunk becomes visible at the epoch after the one it was published in, and
     * the epoch advances on the index orchestrator's tick. Returning before that tick would make
     * "searchable when this returns" true only most of the time. Bounded, so a tick scheduler
     * that has stopped cannot hang the caller: past the bound the documents are published and
     * will become visible whenever the clock next moves. An interrupt ends the wait and is left
     * set for the caller.</p>
     */
    private void awaitNextEpoch() {
        long published = node.indexOrchestrator().getVisibilityEpoch();
        long deadline = System.nanoTime() + epochWaitNanos;
        while (node.indexOrchestrator().getVisibilityEpoch() <= published) {
            if (System.nanoTime() >= deadline) {
                LOGGER.warn("The visibility epoch did not advance within {}ms of a supply; the "
                    + "documents are published and become searchable at the next tick",
                    TimeUnit.NANOSECONDS.toMillis(epochWaitNanos));
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            LockSupport.parkNanos(EPOCH_POLL_NANOS);
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("The embedded node over " + node.dataDirectory()
                + " is closed");
        }
    }

    /**
     * Refuses a source id supplied documents cannot go under.
     *
     * <p>A crawl registered in this data directory owns its source id: its next pass removes
     * every document under that id it does not find at its target, which would silently undo a
     * supply made here.</p>
     */
    private void requireSuppliableSource(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("A source id is required");
        }
        if (sourceId.length() > Source.MAX_ID_LENGTH) {
            throw new IllegalArgumentException("A source id must be at most "
                + Source.MAX_ID_LENGTH + " characters: " + sourceId.length());
        }
        if (sourceCatalog != null && sourceCatalog.all().stream()
                .anyMatch(descriptor -> sourceId.equals(descriptor.sourceId()))) {
            throw new IllegalArgumentException("Source '" + sourceId + "' is a crawl registered "
                + "in this data directory; supply documents under a source id of their own");
        }
    }

    private List<StoredDocument> listed(String sourceId) {
        List<StoredDocument> documents = new ArrayList<>();
        for (DocumentMetadata metadata : registry.getAll()) {
            if (sourceId == null || sourceId.equals(metadata.getSourceId())) {
                documents.add(new StoredDocument(metadata.getSourceId(), metadata.getDocumentId(),
                    metadata.getChunkIds(), metadata.getContentLength(), metadata.getIndexTime()));
            }
        }
        documents.sort(Comparator.comparing(StoredDocument::sourceId)
            .thenComparing(StoredDocument::documentId));
        return documents;
    }

    /**
     * Describes a node to open: its data directory and its configuration.
     *
     * <p>Precedence is the server's, lowest first: the compiled-in defaults, the properties file,
     * the properties set here, then the environment - which is empty unless
     * {@link #environment} is given one.</p>
     */
    public static final class Builder {

        private final Path dataDirectory;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private Path configFile;
        private Map<String, String> environment = Map.of();

        private Builder(Path dataDirectory) {
            this.dataDirectory =
                Objects.requireNonNull(dataDirectory, "dataDirectory cannot be null");
        }

        /**
         * Sets one configuration property, as it would be spelled in {@code excalibur.properties}.
         *
         * @param key   The key, such as {@code excalibur.llm.embedding-base-url}
         * @param value Its value
         * @return This builder
         * @throws NullPointerException if either is null
         */
        public Builder property(String key, String value) {
            properties.put(Objects.requireNonNull(key, "key cannot be null"),
                Objects.requireNonNull(value, "value cannot be null"));
            return this;
        }

        /**
         * Sets several configuration properties.
         *
         * @param values Keys to values
         * @return This builder
         * @throws NullPointerException if the map, a key or a value is null
         */
        public Builder properties(Map<String, String> values) {
            Objects.requireNonNull(values, "values cannot be null").forEach(this::property);
            return this;
        }

        /**
         * Reads configuration from a properties file first, as the server's {@code --config} does.
         *
         * @param file The file
         * @return This builder
         * @throws NullPointerException if file is null
         */
        public Builder configFile(Path file) {
            this.configFile = Objects.requireNonNull(file, "file cannot be null");
            return this;
        }

        /**
         * Applies {@code EXCALIBUR_*} variables over every other source, as the server does.
         *
         * <p>Pass {@code System.getenv()} for a node configured the way a server on this host
         * would be. Not applied by default: a library that reads the process environment
         * unasked configures itself from whatever happens to be exported.</p>
         *
         * @param variables Variable names to values
         * @return This builder
         * @throws NullPointerException if variables is null
         */
        public Builder environment(Map<String, String> variables) {
            this.environment =
                Map.copyOf(Objects.requireNonNull(variables, "variables cannot be null"));
            return this;
        }

        /**
         * Opens the data directory and starts the node.
         *
         * @return The running node
         * @throws IOException              if the data directory or the properties file cannot be
         *                                  read or created
         * @throws IllegalStateException    if the configuration is invalid, asks for graph
         *                                  extraction, or a component cannot be opened
         */
        public ExcaliburEmbedded open() throws IOException {
            prepare();
            Files.createDirectories(dataDirectory);
            Config config = configuration();
            if (config.graph().isExtractionEnabled()) {
                throw new IllegalStateException("excalibur.graph.extraction-enabled is a server "
                    + "setting: the graph is built and queried over HTTP, and an embedded node "
                    + "has nowhere to stage the facts it would extract");
            }
            ApiSecurityConfig limits = ApiSecurityConfig.fromConfig(config);
            Duration shutdownTimeout =
                ShutdownBudget.configuredTimeout(config.getEffectiveProperties());

            NodeAssembly node =
                NodeAssembly.open(config, dataDirectory.toAbsolutePath(), limits, 0,
                    NodeAssembly.EMBEDDED_LOCK_HOLDER);
            InProcessServices services;
            try {
                services = node.server().startInProcess();
            } catch (RuntimeException e) {
                try {
                    if (node.shutdown(shutdownTimeout).run()) {
                        node.releaseDataDirectory();
                    }
                } catch (IOException | RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
            LOGGER.info("Embedded node open over {}", node.dataDirectory());
            return new ExcaliburEmbedded(node, services, limits, shutdownTimeout);
        }

        /**
         * The configuration, merged and validated as the server's is.
         *
         * @throws IOException if the properties file cannot be read
         */
        private Config configuration() throws IOException {
            Map<String, String> merged = new HashMap<>();
            String source = IN_PROCESS_CONFIG;
            if (configFile != null) {
                source = configFile.toString();
                // Loaded without the environment, which is applied once, last, below.
                merged.putAll(ConfigLoader.load(source, Map.of()).getEffectiveProperties());
            }
            merged.putAll(properties);
            // The file's own keys were reported as it was read; these are the ones set here.
            ConfigLoader.reportUnrecognisedKeys(source, properties);
            ApiConfigKeys.reportUnrecognised(merged);
            Config config = new Config(source, environment, merged);
            ConfigValidation.validate(config);
            return config;
        }
    }
}
