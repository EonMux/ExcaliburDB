package excalibur.ingest.pipeline;

import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.processor.TermFrequencyTracker;
import excalibur.ingest.erasure.DocumentErasureService;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.ingest.source.DocumentNotFoundException;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import excalibur.llm.embedding.EmbeddingClient;
import excalibur.shard.IndexOrchestrator;
import io.excalibur.core.logging.StructuredLoggingHelper;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Orchestrates background ingestion of documents from registered sources.
 *
 * <p>The IngestionOrchestrator manages the complete ingestion pipeline:</p>
 * <ol>
 *   <li>Crawling documents from the source</li>
 *   <li>Fetching document content</li>
 *   <li>Chunking text into semantic units</li>
 *   <li>Generating embeddings via LLM client</li>
 *   <li>Writing to storage engine</li>
 *   <li>Publishing the document at a visibility epoch</li>
 * </ol>
 *
 * <h2>Document Atomicity</h2>
 * <p>A document is published as a unit. Every chunk is embedded and written before the
 * visibility epoch is advanced, and the document is registered with the
 * {@link DocumentVisibilityTracker} under its {@code sourceId:documentId} key - the same key
 * excalibur-shard derives from the storage key and excalibur-query's atomicity filter asks
 * about. A document whose chunks could not all be embedded or written is rolled back and never
 * registered, so a query can never observe half of it.</p>
 *
 * <h2>Source Ownership</h2>
 * <p>The orchestrator does <strong>not</strong> own the {@link Source} it is handed and never
 * closes it. The same instance is held by the API layer's source registry and reused by the
 * reindexer; closing it here would break every later crawl.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Source registration uses concurrent collections</li>
 *   <li>Each ingestion runs in its own virtual thread</li>
 *   <li>Status updates are atomic</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Progress values are clamped to [0.0, 1.0]</li>
 *   <li>Entry counts are non-negative</li>
 *   <li>Errors are captured and reported in status</li>
 *   <li>Resources are cleaned up on completion or error</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public class IngestionOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionOrchestrator.class);

    /**
     * Interface for status registry callbacks.
     * Decouples the orchestrator from the API layer's status registry.
     */
    public interface StatusRegistry {
        /**
         * Gets or creates status for a source.
         *
         * @param sourceId The source ID
         * @return The status object
         */
        IngestionStatus getOrCreate(String sourceId);
    }

    /**
     * Interface for ingestion status updates.
     * Matches the IngestStatusEndpoint.IngestionStatus API.
     */
    public interface IngestionStatus {
        /**
         * Updates the ingestion status.
         *
         * @param state Current state (crawling, embedding, indexing, idle)
         * @param progress Progress in range [0.0, 1.0]
         * @param entriesIngested Number of entries successfully ingested
         * @param entriesPending Number of entries waiting to be processed
         */
        void update(String state, double progress, long entriesIngested, long entriesPending);

        /**
         * Sets an error state.
         *
         * @param error Error message
         */
        void setError(String error);
    }

    /** The BM25 index kept beside the chunks written here, if the node keeps one. */
    private final LexicalSideIndex lexicalSideIndex;

    /** Files written terms under their segment, so queries can prune segments that cannot match. */
    private final SegmentTermRecorder termRecorder = new SegmentTermRecorder();

    /** Reports published documents onwards, so a graph can be built from them. */
    private final GraphReporter graphReporter = new GraphReporter();

    /** Writes and publishes one document at a time, atomically. */
    private final DocumentIngestor documentIngestor;

    private final IndexOrchestrator indexOrchestrator;
    private final StatusRegistry statusRegistry;

    /**
     * Registry that records what was ingested so a later reindex can detect changes and
     * tombstone the previous version. Derived from the visibility tracker, which owns it.
     * Null when no visibility tracker is configured.
     */
    private final DocumentRegistry documentRegistry;

    /**
     * Timeout for awaiting backpressure release from the HNSW integration queue.
     * Configured via excalibur.ingest.document-atomicity-timeout-ms (default 2000ms).
     */
    private final int documentTimeoutMs;

    private final Set<String> activeIngestions;
    private final Map<String, Thread> ingestionThreads;

    /**
     * The threads running {@link #ingestNow}, by source.
     *
     * <p>Kept apart from {@link #ingestionThreads} because these are not threads this class
     * owns - they are request threads that go back to serving requests afterwards. Shutdown may
     * interrupt one, to break it out of a blocking write, but must never join one: a pooled
     * thread does not die when the run it is executing ends, so a join would wait forever. So
     * shutdown waits for the entry to be removed instead of for the thread to stop.</p>
     */
    private final Map<String, Thread> synchronousIngestions = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown;

    /**
     * Default document timeout in milliseconds.
     * Used when no config is provided. Matches Config.Ingest.DEFAULT_DOCUMENT_TIMEOUT_MS.
     */
    public static final int DEFAULT_DOCUMENT_TIMEOUT_MS = 2000;

    /** How often shutdown re-checks whether a synchronous run has finished. */
    private static final long SHUTDOWN_POLL_MILLIS = 5;

    /**
     * How long {@link #shutdown()} waits for in-flight ingestion threads to unwind.
     */
    private static final long SHUTDOWN_AWAIT_MILLIS = 10_000;

    /**
     * Creates a new IngestionOrchestrator with required dependencies.
     *
     * @param textChunker Text chunker for splitting documents
     * @param embeddingClient Embedding client for generating embeddings
     * @param storageEngine Storage engine for persisting entries
     * @param indexOrchestrator Index orchestrator for coordinating indexing
     * @param statusRegistry Status registry for tracking progress
     * @throws NullPointerException if any parameter is null
     */
    public IngestionOrchestrator(TextChunker textChunker,
                                  EmbeddingClient embeddingClient,
                                  StorageEngine storageEngine,
                                  IndexOrchestrator indexOrchestrator,
                                  StatusRegistry statusRegistry) {
        this(textChunker, embeddingClient, storageEngine, indexOrchestrator, statusRegistry,
             null, null, DEFAULT_DOCUMENT_TIMEOUT_MS);
    }

    /**
     * Creates a new IngestionOrchestrator that publishes documents atomically.
     *
     * <p>Every chunk of a document is embedded and written before the visibility epoch is
     * advanced, so a query sees all of a document or none of it.</p>
     *
     * @param textChunker Text chunker for splitting documents
     * @param embeddingClient Embedding client for generating embeddings
     * @param storageEngine Storage engine for persisting entries
     * @param indexOrchestrator Index orchestrator for coordinating indexing
     * @param statusRegistry Status registry for tracking progress
     * @param visibilityTracker Tracker for document visibility epochs (may be null)
     * @throws NullPointerException if required parameters are null
     */
    public IngestionOrchestrator(TextChunker textChunker,
                                  EmbeddingClient embeddingClient,
                                  StorageEngine storageEngine,
                                  IndexOrchestrator indexOrchestrator,
                                  StatusRegistry statusRegistry,
                                  DocumentVisibilityTracker visibilityTracker) {
        this(textChunker, embeddingClient, storageEngine, indexOrchestrator, statusRegistry,
             visibilityTracker, null, DEFAULT_DOCUMENT_TIMEOUT_MS);
    }

    /**
     * Creates a new IngestionOrchestrator with full configuration including timeout.
     *
     * <p>This is the most complete constructor, allowing all parameters to be configured.</p>
     *
     * @param textChunker Text chunker for splitting documents
     * @param embeddingClient Embedding client for generating embeddings
     * @param storageEngine Storage engine for persisting entries
     * @param indexOrchestrator Index orchestrator for coordinating indexing
     * @param statusRegistry Status registry for tracking progress
     * @param visibilityTracker Tracker for document visibility epochs (may be null)
     * @param termFrequencyTracker Tracker for term frequencies in segment sketches (may be null to disable selectivity estimation)
     * @param documentTimeoutMs Timeout for awaiting backpressure release in milliseconds
     * @throws NullPointerException if required parameters are null
     */
    public IngestionOrchestrator(TextChunker textChunker,
                                  EmbeddingClient embeddingClient,
                                  StorageEngine storageEngine,
                                  IndexOrchestrator indexOrchestrator,
                                  StatusRegistry statusRegistry,
                                  DocumentVisibilityTracker visibilityTracker,
                                  TermFrequencyTracker termFrequencyTracker,
                                  int documentTimeoutMs) {
        // The collaborators below re-check what they are handed, but they are built in
        // dependency order rather than parameter order. Checking here first means a caller that
        // omitted two dependencies is told about the earlier one in this signature, which is the
        // one it is most likely to have got wrong.
        Objects.requireNonNull(textChunker, "TextChunker cannot be null");
        Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.indexOrchestrator = Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        this.statusRegistry = Objects.requireNonNull(statusRegistry, "StatusRegistry cannot be null");
        this.documentRegistry = visibilityTracker != null ? visibilityTracker.getRegistry() : null;
        this.lexicalSideIndex = new LexicalSideIndex(storageEngine);
        this.documentIngestor = new DocumentIngestor(textChunker, embeddingClient, storageEngine,
            visibilityTracker, this.documentRegistry, termFrequencyTracker,
            this.lexicalSideIndex, this.termRecorder, this.graphReporter);
        this.documentTimeoutMs = documentTimeoutMs > 0 ? documentTimeoutMs : DEFAULT_DOCUMENT_TIMEOUT_MS;
        this.activeIngestions = ConcurrentHashMap.newKeySet();
        this.ingestionThreads = new ConcurrentHashMap<>();
        this.shutdown = new AtomicBoolean(false);

        LOGGER.info("IngestionOrchestrator initialized with visibilityPublishing={}, termFrequencyTracking={}, documentTimeoutMs={}",
            (visibilityTracker != null ? "enabled" : "disabled"),
            (termFrequencyTracker != null ? "enabled" : "disabled"),
            this.documentTimeoutMs);
    }

    /**
     * Points ingestion at a lexical index, so stored chunks are also BM25-searchable.
     *
     * @param lexicalIndex The index, or null to keep none
     */
    public void setLexicalIndex(excalibur.ingest.lexical.ChunkIndex lexicalIndex) {
        this.lexicalSideIndex.setIndex(lexicalIndex);
    }

    /**
     * Points ingestion at the membership filter the query pipeline prunes with.
     *
     * <p>Set after construction, like the lexical index: the filter is built by the API layer's
     * bootstrap alongside the index orchestrator. Without one, stage 1 of the query pipeline
     * has nothing to say about anything this node crawled and every query reads every
     * segment.</p>
     *
     * @param membershipFilter The filter, or null to keep none
     */
    public void setMembershipFilter(excalibur.filter.HierarchicalCuckooFilter membershipFilter) {
        this.termRecorder.setFilter(membershipFilter);
    }

    /**
     * Sets where published documents are reported so a graph can be built from them.
     *
     * <p>Set after construction because the graph stack belongs to the API layer, which is
     * assembled after this orchestrator. Null disables it, which is the state of any deployment
     * that indexes text and does not extract facts.</p>
     *
     * @param graphSink The sink, or null for none
     */
    public void setGraphSink(excalibur.ingest.graph.GraphIngestSink graphSink) {
        this.graphReporter.setSink(graphSink);
    }

    /**
     * Wires the eraser that retires a rolled back document's graph nodes.
     *
     * <p>Set after construction, like the lexical index and the membership filter: the vector
     * stack belongs to the API layer, which is assembled after this orchestrator.</p>
     *
     * @param vectorEraser The eraser, or null on a node that keeps no vector index
     */
    public void setVectorEraser(DocumentErasureService.VectorEraser vectorEraser) {
        this.documentIngestor.setVectorEraser(vectorEraser);
    }

    /**
     * Ingests a source on the calling thread, returning when its documents are searchable.
     *
     * <p>The counterpart to {@link #startIngestion(Source)}, and the right one for documents a
     * client handed in directly. A crawl is of unknown size and unknown duration, so it is
     * answered with an accepted-and-in-progress and a status to poll. A batch the caller is
     * holding in its hand is neither: it can be ingested inside the request that carried it, and
     * a caller that has to poll to find out whether the documents it just sent were accepted has
     * been given a worse answer than the one that was available.</p>
     *
     * @param source The source to ingest
     * @return What the run did
     * @throws NullPointerException  if source is null
     * @throws IllegalStateException if the node is shutting down, or this source is already
     *                               being ingested
     * @throws IngestionFailedException if the run could not complete
     */
    public IngestionOutcome ingestNow(Source source) {
        Objects.requireNonNull(source, "Source cannot be null");

        String sourceId = source.getSourceId();
        if (shutdown.get()) {
            throw new IllegalStateException("The node is shutting down and is not ingesting");
        }
        // The same claim a background run takes, for the same reason: two runs over one source
        // id would race on the same document identifiers. Refusing lets the caller retry, where
        // returning quietly - which is right for a scheduled crawl that will come round again -
        // would report documents as ingested that were never looked at.
        if (!activeIngestions.add(sourceId)) {
            throw new IllegalStateException("Ingestion is already running for source: " + sourceId);
        }
        // Published before the flag is read again, not after. shutdown() waits on this map and
        // on nothing else, so a run that has passed its last check and is not yet in the map is
        // a run shutdown reports a clean stop over - and the caller then closes the storage
        // engine's Arena under a thread that is about to write into a memory mapping, which
        // takes the JVM down rather than throwing. Publishing first makes the two orders the
        // only two outcomes: either shutdown sees this run and waits for it, or this run sees
        // the flag and backs out.
        synchronousIngestions.put(sourceId, Thread.currentThread());
        if (shutdown.get()) {
            synchronousIngestions.remove(sourceId, Thread.currentThread());
            activeIngestions.remove(sourceId);
            throw new IllegalStateException("The node is shutting down and is not ingesting");
        }

        IngestionStatus status = statusRegistry.getOrCreate(sourceId);
        try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                .withSourceId(sourceId)
                .build()) {
            return ingestSource(source, sourceId, status);
        } catch (SourceException e) {
            String error = "Ingestion failed: " + e.getMessage();
            LOGGER.error("{}", error, e);
            status.setError(error);
            // Wrapped rather than swallowed: this caller is a request that has to answer for
            // what it was handed, and a run that failed outright is not a run that ingested
            // nothing - the difference is the whole content of the reply.
            throw new IngestionFailedException(error, e);
        } catch (RuntimeException e) {
            // Everything below this call is internal - storage writes, the lexical index, the
            // embedding client - and those failures are not this caller's two expected states.
            // Left to propagate, an internal IllegalStateException reaches a request handler
            // that reads it as "already running" and answers 409 with the message, which
            // carries filesystem paths and endpoint URLs the caller has no business seeing.
            String error = "Ingestion failed: " + e.getMessage();
            LOGGER.error("{}", error, e);
            status.setError(error);
            throw new IngestionFailedException(error, e);
        } finally {
            synchronousIngestions.remove(sourceId, Thread.currentThread());
            activeIngestions.remove(sourceId);
            // Cleared before the thread goes back to whatever it was doing. The interrupt was
            // addressed to this run - by shutdown(), or by a stop of this source - and this is a
            // request thread from a pool: carried away, the flag fails the next request served
            // on it at its first interruptible channel operation with ClosedByInterruptException,
            // which closes that channel for good.
            Thread.interrupted();
        }
    }

    /**
     * What one ingestion run did.
     *
     * @param documentsIngested Documents written and published
     * @param chunksIngested    Chunks those documents produced
     * @param documentsFailed   Documents that were listed and could not be ingested
     * @param stopped           Whether the run was cut short by shutdown or interruption
     */
    public record IngestionOutcome(int documentsIngested, long chunksIngested,
                                   int documentsFailed, boolean stopped) {

        /** A run over a source that listed no documents. */
        static final IngestionOutcome NOTHING = new IngestionOutcome(0, 0, 0, false);
    }

    /** A synchronous ingestion run that could not complete. */
    public static final class IngestionFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message What went wrong
         * @param cause   The failure underneath
         */
        public IngestionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Starts background ingestion for a source.
     *
     * <p>This method returns immediately. The ingestion runs in a virtual thread.
     * Progress can be tracked via the status registry.</p>
     *
     * <p>If an ingestion is already active for this source, this method does nothing
     * and returns immediately.</p>
     *
     * @param source The source to ingest
     * @throws NullPointerException if source is null
     */
    public void startIngestion(Source source) {
        Objects.requireNonNull(source, "Source cannot be null");

        String sourceId = source.getSourceId();

        // Refuse before claiming the slot, so a shutdown concurrent with this call cannot be
        // overtaken by a newly started ingestion.
        if (shutdown.get()) {
            LOGGER.warn("Cannot start ingestion: orchestrator is shutting down");
            return;
        }

        if (!activeIngestions.add(sourceId)) {
            LOGGER.debug("Ingestion already active for source: {}", sourceId);
            return;
        }

        LOGGER.info("Starting background ingestion for source: {}", sourceId);

        Thread worker = Thread.ofVirtual().name("ingestion-" + sourceId).unstarted(() -> {
            try {
                runIngestion(source);
            } finally {
                ingestionThreads.remove(sourceId);
                activeIngestions.remove(sourceId);
            }
        });
        // Published before the flag is read, for the reason ingestNow gives: shutdown()
        // interrupts and joins what is in this map, so a worker that enters it afterwards is one
        // nothing waits for. Registered unstarted, so backing out here costs no thread.
        ingestionThreads.put(sourceId, worker);
        if (shutdown.get()) {
            LOGGER.warn("Cannot start ingestion: orchestrator is shutting down");
            ingestionThreads.remove(sourceId, worker);
            activeIngestions.remove(sourceId);
            return;
        }
        worker.start();
    }

    /**
     * Runs the complete ingestion pipeline for a source.
     *
     * @param source The source to ingest
     */
    private void runIngestion(Source source) {
        String sourceId = source.getSourceId();
        IngestionStatus status = statusRegistry.getOrCreate(sourceId);

        // Use MDC for structured logging - context is automatically cleaned up in finally block
        try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                .withSourceId(sourceId)
                .build()) {
            try {
                ingestSource(source, sourceId, status);
            } catch (SourceException e) {
                String error = "Crawl failed: " + e.getMessage();
                LOGGER.error("{}", error, e);
                status.setError(error);
            } catch (Exception e) {
                String error = "Ingestion failed: " + e.getMessage();
                LOGGER.error("{}", error, e);
                status.setError(error);
            }
        }
        // The Source is owned by whoever registered it; it is deliberately not closed here.
    }

    /**
     * Crawls a source and ingests every document it yields.
     */
    private IngestionOutcome ingestSource(Source source, String sourceId, IngestionStatus status)
            throws SourceException {
        status.update("crawling", 0.0, 0, 0);
        StructuredLoggingHelper.setStage("crawling");
        LOGGER.info("Phase 1: Starting crawl");

        List<String> documentIds;
        try (Stream<String> crawlStream = source.crawl()) {
            documentIds = crawlStream.toList();
        }

        long totalPending = documentIds.size();
        LOGGER.info("Discovered {} documents", totalPending);

        if (totalPending == 0) {
            status.update("idle", 1.0, 0, 0);
            LOGGER.info("No documents to ingest");
            return IngestionOutcome.NOTHING;
        }

        StructuredLoggingHelper.setStage("embedding");
        status.update("embedding", 0.0, 0, totalPending);

        long totalIngested = 0;
        int processedDocs = 0;
        int failedDocs = 0;

        for (String documentId : documentIds) {
            if (shutdown.get() || Thread.currentThread().isInterrupted()) {
                // Either the node is stopping or this source alone was stopped. Checked per
                // document rather than per chunk: a document is written whole or not at all, and
                // abandoning one halfway is the state the rollback exists to avoid.
                LOGGER.info("Ingestion of {} stopped after {} of {} documents",
                    sourceId, processedDocs, totalPending);
                status.setError("Ingestion stopped");
                return new IngestionOutcome(processedDocs - failedDocs, totalIngested,
                    failedDocs, true);
            }

            awaitBackpressureRelease();

            try {
                StructuredLoggingHelper.setDocumentId(documentId);
                DocumentIngestor.DocumentOutcome outcome =
                    documentIngestor.ingest(source, sourceId, documentId);
                totalIngested += outcome.chunks();
                // Counted here and not only in the catch arms below. Most of the ways a document
                // fails to be ingested do not throw: too large, too many chunks, unembeddable,
                // rolled back. Left uncounted, the run reported them to the caller as ingested,
                // which told someone their document was stored when nothing of it was.
                if (outcome.dropped()) {
                    failedDocs++;
                }
            } catch (DocumentNotFoundException e) {
                failedDocs++;
                LOGGER.warn("Document not found: {}", documentId);
            } catch (SourceException e) {
                failedDocs++;
                LOGGER.warn("Failed to fetch document {}: {}", documentId, e.getMessage());
            } catch (Exception e) {
                failedDocs++;
                LOGGER.warn("Unexpected error processing document {}: {}", documentId, e.getMessage(), e);
            } finally {
                StructuredLoggingHelper.clearDocumentId();
            }

            processedDocs++;
            status.update("embedding", (double) processedDocs / totalPending,
                totalIngested, totalPending - processedDocs);
        }

        StructuredLoggingHelper.setStage("indexing");
        status.update("indexing", 0.9, totalIngested, 0);
        awaitBackpressureRelease();

        StructuredLoggingHelper.setStage("idle");
        // Lucene writes are invisible to search until a reader is reopened. Doing it once per
        // run rather than per document keeps the cost off the write path, at the price of a
        // source's chunks becoming searchable together at the end of its run.
        lexicalSideIndex.refresh();
        lexicalSideIndex.compactIfDue();
        persistRegistry();

        status.update("idle", 1.0, totalIngested, 0);
        reportDroppedDocuments(sourceId, status, failedDocs, totalPending);
        LOGGER.info("Ingestion complete: {} chunks from {} of {} documents",
            totalIngested, processedDocs - failedDocs, totalPending);
        return new IngestionOutcome(processedDocs - failedDocs, totalIngested, failedDocs, false);
    }

    /**
     * Records on the source's status that the run did not ingest everything it listed.
     *
     * <p>A document that could not be fetched is skipped rather than fatal, so the run reaches
     * {@code idle} at full progress either way. Left at that, a run in which every document
     * failed reports exactly what a source with nothing to ingest reports - idle, complete,
     * nothing ingested, no error - and the log is the only place the difference exists. An
     * operator polling the status endpoint has no reason to read it.</p>
     *
     * @param sourceId     The source that was ingested
     * @param status       The status to report on
     * @param failedDocs   Documents that were listed but not ingested
     * @param totalPending Documents the crawl listed
     */
    private void reportDroppedDocuments(String sourceId, IngestionStatus status,
                                        int failedDocs, long totalPending) {
        if (failedDocs <= 0) {
            return;
        }
        LOGGER.warn("Ingestion of {} did not ingest {} of {} documents", sourceId, failedDocs, totalPending);
        status.setError(failedDocs + " of " + totalPending
            + " documents were not ingested; each failure is logged separately");
    }

    /**
     * Writes the registry, so this run's documents survive a restart.
     *
     * <p>The registry is what change detection compares against. Lost, every document this run
     * indexed reads as new to the next re-crawl, which reingests it beside the chunks already
     * there and tombstones none of them.</p>
     *
     * <p>No-op without a registry: a node that records nothing has nothing to write, and it has
     * already opted out of change detection by not keeping one.</p>
     */
    private void persistRegistry() {
        if (documentRegistry == null) {
            return;
        }
        try {
            documentRegistry.persist();
        } catch (IOException e) {
            LOGGER.warn("Document registry not persisted; a restart would make this run's "
                + "documents look new to the next re-crawl: {}", e.getMessage());
        }
    }

    /**
     * Throttles the embedding stage while the HNSW integration queue is over its limit.
     */
    private void awaitBackpressureRelease() {
        if (!indexOrchestrator.isBackpressured()) {
            return;
        }
        LOGGER.info("Backpressure detected, waiting for HNSW queue to drain");
        if (!indexOrchestrator.awaitBackpressureRelease(documentTimeoutMs)) {
            LOGGER.warn("Backpressure timeout after {}ms, continuing ingestion", documentTimeoutMs);
        }
    }

    /**
     * Checks if ingestion is currently active for a source.
     *
     * @param sourceId The source ID
     * @return true if ingestion is active
     */
    public boolean isIngestionActive(String sourceId) {
        return activeIngestions.contains(sourceId);
    }

    /**
     * Returns the number of currently active ingestions.
     *
     * @return Active ingestion count
     */
    public int getActiveIngestionCount() {
        return activeIngestions.size();
    }

    /**
     * Stops the ingestion of one source, without touching the others.
     *
     * <p>What a deregistration needs. A source taken out of the registry while a run of it is in
     * flight would go on writing documents nobody can reach it through - and, when the
     * deregistration is part of erasing the source, would go on writing the documents being
     * erased.</p>
     *
     * <p>Stops at the next document boundary rather than immediately: the run checks for it
     * between documents, so what is already half-written is finished or rolled back rather than
     * abandoned. Returns once the stop has been asked for, not once it has happened.</p>
     *
     * @param sourceId The source whose ingestion should stop
     * @return true if a run was in flight and has been asked to stop
     */
    public boolean stopIngestion(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId cannot be null");
        // Either kind of run. A source can be in flight on a background thread or on the request
        // thread that supplied its documents, and an operator asking for one to stop does not
        // know or care which - looking only at the background threads meant a synchronous run
        // could not be stopped at all.
        Thread worker = ingestionThreads.get(sourceId);
        if (worker == null) {
            worker = synchronousIngestions.get(sourceId);
        }
        if (worker == null) {
            return false;
        }
        LOGGER.info("Stopping ingestion of source {}", sourceId);
        worker.interrupt();
        return true;
    }

    /**
     * Shuts down the orchestrator, interrupting active ingestions and waiting for them to finish.
     *
     * <p>This <strong>must</strong> be called - and must return - before the StorageEngine and
     * its Arena are closed. An ingestion thread still inside {@code storageEngine.put} writes
     * into a memory-mapped region; closing the Arena underneath it is undefined behaviour, not
     * a caught exception.</p>
     *
     * @return true if all ingestion threads finished within the shutdown timeout
     */
    public boolean shutdown() {
        LOGGER.info("Shutting down IngestionOrchestrator with {} active ingestions",
            activeIngestions.size());
        shutdown.set(true);

        for (Thread worker : ingestionThreads.values()) {
            worker.interrupt();
        }
        // Interrupted for the same reason - to break a blocking write - but never joined: these
        // are request threads, and a pooled thread does not die when its run ends.
        for (Thread caller : synchronousIngestions.values()) {
            caller.interrupt();
        }

        long deadline = System.nanoTime() + SHUTDOWN_AWAIT_MILLIS * 1_000_000L;
        boolean clean = true;
        for (Thread worker : ingestionThreads.values()) {
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMillis <= 0) {
                clean = false;
                break;
            }
            try {
                worker.join(remainingMillis);
                clean &= !worker.isAlive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        // A synchronous run ends by removing its own entry, so an empty map is what says the
        // run has left the storage engine alone - the question the caller is actually asking
        // before it closes the Arena.
        while (!synchronousIngestions.isEmpty()) {
            if (System.nanoTime() >= deadline) {
                clean = false;
                break;
            }
            try {
                Thread.sleep(SHUTDOWN_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        if (!clean) {
            LOGGER.warn("Ingestion threads still running after {}ms shutdown timeout: {}",
                SHUTDOWN_AWAIT_MILLIS, activeIngestions);
        }
        return clean;
    }
}
