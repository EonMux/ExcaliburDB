package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.ingest.source.DocumentNotFoundException;
import com.eonmux.excalibur.ingest.source.FetchDisallowedException;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.core.logging.StructuredLoggingHelper;
import com.eonmux.excalibur.core.util.StopSignal;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
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
 * {@link DocumentVisibilityTracker} under its {@code sourceId:documentId} key - the key
 * excalibur-query's atomicity filter derives from a result's chunk id and asks about. A document whose chunks could not all be embedded or written is rolled back and never
 * registered, so a query can never observe half of it.</p>
 *
 * <h2>Stopping a run</h2>
 * <p>A run is stopped through its {@link StopSignal}, read between documents, and never by
 * interrupting its thread outright. The thread goes from each fetch straight into storage writes,
 * and every append to a segment's write log forces a channel that all writers of that segment
 * share: an interrupt still set when it gets there closes the channel for the life of the
 * process, and every later write to that segment - by any run, reindex or erasure - fails.
 * What the signal does interrupt is the part of a document that touches no shared file: the
 * crawl, the fetch, the embedding request and the backpressure wait, each of which may block for
 * as long as a remote end chooses. So a stop is prompt where waiting is, and cooperative where
 * the thread is writing.</p>
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
         * Records how many of this run's documents were passed over because they are not text.
         *
         * <p>Apart from {@link #update}, because it is not progress: it is a property of the
         * run as a whole, reported once when the run starts and once when it ends, and a value
         * carried on every progress update would have to be recomputed by every caller that
         * only has a count of entries.</p>
         *
         * @param entriesSkipped Documents listed whose bytes are not text; zero clears it
         */
        void setSkipped(long entriesSkipped);

        /**
         * Records how many documents this run left alone because their publisher forbids
         * fetching them - the URLs an HTTP source's robots.txt disallows.
         *
         * <p>Apart from {@link #setSkipped}, which counts documents that were read and are not
         * text: these were never read, and what an operator does about the two is different.
         * Reported like it, when the crawl has listed the source and again when the run ends.</p>
         *
         * @param entriesDisallowed What {@link Source#getDisallowedCount()} reports for the run;
         *                          zero clears it
         */
        void setDisallowed(long entriesDisallowed);

        /**
         * Sets an error state.
         *
         * @param error Error message
         */
        void setError(String error);
    }

    /** The BM25 index kept beside the chunks written here, if the node keeps one. */
    private final LexicalSideIndex lexicalSideIndex;

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

    /** What a run waits between lexical refreshes when nobody has configured it. */
    public static final long DEFAULT_LEXICAL_REFRESH_INTERVAL_MS = 30_000L;

    /**
     * Longest a document published by a run in progress waits to become lexically searchable.
     *
     * <p>A refresh publishes a segment on the native engine and reopens a reader on Lucene, so a
     * run paces them rather than refreshing per document; it also refreshes when it ends or is
     * stopped. Without the pacing a run's documents were stored and visible to every other signal
     * and absent from BM25 until the whole run finished - on a lexical-only node, for the length
     * of a crawl that can run for days, the documents were not searchable at all.</p>
     */
    private volatile long lexicalRefreshIntervalMs = DEFAULT_LEXICAL_REFRESH_INTERVAL_MS;

    private final Set<String> activeIngestions;

    /** The background runs in flight, by source: the thread each owns, and how it is stopped. */
    private final Map<String, Run> ingestionThreads;

    /**
     * The runs of {@link #ingestNow} in flight, by source.
     *
     * <p>Kept apart from {@link #ingestionThreads} because their threads are not this class's -
     * they are request threads that go back to serving requests afterwards. Shutdown asks one to
     * stop but must never join its thread: a pooled thread does not die when the run it is
     * executing ends, so a join would wait forever. So shutdown waits for the entry to be removed
     * instead of for the thread to stop.</p>
     */
    private final Map<String, Run> synchronousIngestions = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown;

    /**
     * One run in flight.
     *
     * @param thread The thread carrying it out
     * @param stop   How it is asked to stop
     */
    private record Run(Thread thread, StopSignal stop) {
    }

    /**
     * Default document timeout in milliseconds.
     * Used when no config is provided. Matches IngestConfig.DEFAULT_DOCUMENT_TIMEOUT_MS.
     */
    public static final int DEFAULT_DOCUMENT_TIMEOUT_MS = 2000;

    /** How often shutdown re-checks whether a synchronous run has finished. */
    private static final long SHUTDOWN_POLL_MILLIS = 5;

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
             null, DEFAULT_DOCUMENT_TIMEOUT_MS);
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
             visibilityTracker, DEFAULT_DOCUMENT_TIMEOUT_MS);
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
     * @param documentTimeoutMs Timeout for awaiting backpressure release in milliseconds
     * @throws NullPointerException if required parameters are null
     */
    public IngestionOrchestrator(TextChunker textChunker,
                                  EmbeddingClient embeddingClient,
                                  StorageEngine storageEngine,
                                  IndexOrchestrator indexOrchestrator,
                                  StatusRegistry statusRegistry,
                                  DocumentVisibilityTracker visibilityTracker,
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
            visibilityTracker, this.documentRegistry,
            this.lexicalSideIndex, this.graphReporter);
        this.documentTimeoutMs = documentTimeoutMs > 0 ? documentTimeoutMs : DEFAULT_DOCUMENT_TIMEOUT_MS;
        this.activeIngestions = ConcurrentHashMap.newKeySet();
        this.ingestionThreads = new ConcurrentHashMap<>();
        this.shutdown = new AtomicBoolean(false);

        LOGGER.info("IngestionOrchestrator initialized with visibilityPublishing={}, documentTimeoutMs={}",
            (visibilityTracker != null ? "enabled" : "disabled"),
            this.documentTimeoutMs);
    }

    /**
     * Points ingestion at a lexical index, so stored chunks are also BM25-searchable.
     *
     * @param lexicalIndex The index, or null to keep none
     */
    public void setLexicalIndex(com.eonmux.excalibur.ingest.lexical.ChunkIndex lexicalIndex) {
        this.lexicalSideIndex.setIndex(lexicalIndex);
    }

    /**
     * Sets how long a document published by a run in progress may wait to become lexically
     * searchable.
     *
     * @param intervalMs The interval in milliseconds
     * @throws IllegalArgumentException if the interval is not positive
     */
    public void setLexicalRefreshIntervalMs(long intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException(
                "lexical refresh interval must be positive: " + intervalMs);
        }
        this.lexicalRefreshIntervalMs = intervalMs;
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
    public void setGraphSink(com.eonmux.excalibur.ingest.graph.GraphIngestSink graphSink) {
        this.graphReporter.setSink(graphSink);
    }

    /**
     * Has a reindexer report to the graph through this orchestrator.
     *
     * <p>A re-crawl publishes new versions of the documents this orchestrator ingested and
     * removes the ones their source dropped, and the graph has to hear of both, through the sink
     * set by {@link #setGraphSink} or whichever replaces it. Through this orchestrator's reporter
     * rather than one of the reindexer's own, because the reporter is what orders one document's
     * reports: a re-crawl and a client's re-post of the same document can publish moments apart,
     * and two reporters would each take their own version for the latest.</p>
     *
     * @param reindexer The reindexer serving the same corpus
     * @throws NullPointerException if reindexer is null
     */
    public void shareGraphReporting(DocumentReindexer reindexer) {
        Objects.requireNonNull(reindexer, "reindexer must not be null")
            .reportGraphThrough(graphReporter);
    }

    /**
     * How many changes the graph sink refused outright.
     *
     * <p>Beside whatever the sink counts itself: this is what a sink that threw - rather than
     * logging and counting as it is meant to - cost. A document published but refused will not be
     * offered to the graph again unless it changes, and one removed but refused keeps its facts
     * staged until it is erased. A reindexer that shares this orchestrator's reporting is counted
     * here too.</p>
     *
     * @return The count since this orchestrator was created
     */
    public long graphReportFailures() {
        return graphReporter.failures();
    }

    /**
     * Wires the eraser that retires a rolled back document's graph nodes.
     *
     * <p>Set after construction, like the lexical index: the vector
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
        Run run = new Run(Thread.currentThread(), new StopSignal());
        synchronousIngestions.put(sourceId, run);
        if (shutdown.get()) {
            synchronousIngestions.remove(sourceId, run);
            activeIngestions.remove(sourceId);
            throw new IllegalStateException("The node is shutting down and is not ingesting");
        }

        IngestionStatus status = statusRegistry.getOrCreate(sourceId);
        try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                .withSourceId(sourceId)
                .build()) {
            return ingestSource(source, sourceId, status, run.stop());
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
            synchronousIngestions.remove(sourceId, run);
            activeIngestions.remove(sourceId);
            // Cleared before the thread goes back to whatever it was doing. Nothing this class
            // delivers is still set by now, but an interrupt from elsewhere that arrived after the
            // run's last check was addressed to this run, and this is a request thread from a
            // pool: carried away, the flag fails the next request served on it at its first
            // interruptible channel operation with ClosedByInterruptException, which closes that
            // channel for good.
            Thread.interrupted();
        }
    }

    /**
     * What one ingestion run did.
     *
     * @param documentsIngested Documents written and published
     * @param chunksIngested    Chunks those documents produced
     * @param documentsFailed   Documents that were listed and could not be ingested
     * @param documentsSkipped  Documents that were listed and are not text
     * @param stopped           Whether the run was cut short by shutdown or interruption
     */
    public record IngestionOutcome(int documentsIngested, long chunksIngested,
                                   int documentsFailed, int documentsSkipped, boolean stopped) {

        /** A run over a source that listed no documents. */
        static final IngestionOutcome NOTHING = new IngestionOutcome(0, 0, 0, 0, false);
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

        StopSignal stop = new StopSignal();
        Thread worker = Thread.ofVirtual().name("ingestion-" + sourceId).unstarted(() -> {
            try {
                runIngestion(source, stop);
            } finally {
                ingestionThreads.remove(sourceId);
                activeIngestions.remove(sourceId);
            }
        });
        // Published before the flag is read, for the reason ingestNow gives: shutdown() stops
        // and joins what is in this map, so a worker that enters it afterwards is one nothing
        // waits for. Registered unstarted, so backing out here costs no thread.
        Run run = new Run(worker, stop);
        ingestionThreads.put(sourceId, run);
        if (shutdown.get()) {
            LOGGER.warn("Cannot start ingestion: orchestrator is shutting down");
            ingestionThreads.remove(sourceId, run);
            activeIngestions.remove(sourceId);
            return;
        }
        worker.start();
    }

    /**
     * Runs the complete ingestion pipeline for a source.
     *
     * @param source The source to ingest
     * @param stop   How the run is asked to stop
     */
    private void runIngestion(Source source, StopSignal stop) {
        String sourceId = source.getSourceId();
        IngestionStatus status = statusRegistry.getOrCreate(sourceId);

        // Use MDC for structured logging - context is automatically cleaned up in finally block
        try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                .withSourceId(sourceId)
                .build()) {
            try {
                ingestSource(source, sourceId, status, stop);
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
     *
     * <p>The crawl may be interrupted by a stop: listing a source reads the source and nothing
     * this node shares.</p>
     */
    private IngestionOutcome ingestSource(Source source, String sourceId, IngestionStatus status,
                                          StopSignal stop)
            throws SourceException {
        status.update("crawling", 0.0, 0, 0);
        StructuredLoggingHelper.setStage("crawling");
        LOGGER.info("Phase 1: Starting crawl");

        List<String> documentIds = stop.interruptibly(() -> {
            try (Stream<String> crawlStream = source.crawl()) {
                return crawlStream.toList();
            }
        });

        long totalPending = documentIds.size();
        LOGGER.info("Discovered {} documents", totalPending);

        // Zeroed at the start of the run rather than carried: a re-crawl that skips nothing must
        // not go on reporting what the crawl before it skipped - and that includes a crawl that
        // lists nothing at all, which is where a root emptied since the last one ends up.
        status.setSkipped(0);
        // What the crawl was forbidden is known now, and is the whole answer for a crawl that
        // listed nothing because its site disallowed everything.
        status.setDisallowed(source.getDisallowedCount());

        if (totalPending == 0) {
            status.update("idle", 1.0, 0, 0);
            LOGGER.info("No documents to ingest");
            return IngestionOutcome.NOTHING;
        }

        StructuredLoggingHelper.setStage("embedding");
        status.update("embedding", 0.0, 0, totalPending);

        try {
            return ingestDocuments(source, sourceId, status, documentIds, stop);
        } catch (RuntimeException e) {
            finishFailedRun(stop);
            throw e;
        }
    }

    /**
     * Ingests the documents a crawl listed, refreshing the lexical index as it goes.
     */
    private IngestionOutcome ingestDocuments(Source source, String sourceId,
                                             IngestionStatus status, List<String> documentIds,
                                             StopSignal stop) {
        long totalPending = documentIds.size();
        long lastLexicalRefresh = System.nanoTime();
        long totalIngested = 0;
        int processedDocs = 0;
        int failedDocs = 0;
        int skippedDocs = 0;
        int disallowedDocs = 0;
        int keysTaken = 0;

        for (String documentId : documentIds) {
            if (stopRequested(stop)) {
                // Either the node is stopping or this source alone was stopped. Checked per
                // document rather than per chunk: a document is written whole or not at all, and
                // abandoning one halfway is the state the rollback exists to avoid.
                LOGGER.info("Ingestion of {} stopped after {} of {} documents",
                    sourceId, processedDocs, totalPending);
                finishStoppedRun(stop);
                status.setSkipped(skippedDocs);
                status.setDisallowed(source.getDisallowedCount());
                status.setError("Ingestion stopped");
                return new IngestionOutcome(
                    processedDocs - failedDocs - skippedDocs - disallowedDocs,
                    totalIngested, failedDocs, skippedDocs, true);
            }

            if (System.nanoTime() - lastLexicalRefresh
                    >= lexicalRefreshIntervalMs * 1_000_000L) {
                refreshLexicalIndexMidRun(sourceId, stop);
                lastLexicalRefresh = System.nanoTime();
            }

            awaitBackpressureRelease(stop);

            try {
                StructuredLoggingHelper.setDocumentId(documentId);
                DocumentIngestor.DocumentOutcome outcome =
                    documentIngestor.ingest(source, sourceId, documentId, stop);
                totalIngested += outcome.chunks();
                // Counted here and not only in the catch arms below. Most of the ways a document
                // fails to be ingested do not throw: too large, too many chunks, unembeddable,
                // rolled back. Left uncounted, the run reported them to the caller as ingested,
                // which told someone their document was stored when nothing of it was.
                if (outcome.dropped()) {
                    failedDocs++;
                } else if (outcome.keyTaken()) {
                    // A failure no retry cures, counted apart so the status can say what does.
                    failedDocs++;
                    keysTaken++;
                } else if (outcome.notText()) {
                    // Counted apart from the failures. A photograph under the crawl root is not
                    // a document this run failed to ingest, and reporting it as one would make
                    // every crawl of an ordinary directory look broken.
                    skippedDocs++;
                }
            } catch (FetchDisallowedException e) {
                // Not a failure: the publisher said no. The source counts it, and the count is
                // reported below with what the crawl itself left out.
                disallowedDocs++;
                LOGGER.info("Not ingesting {}: {}", documentId, e.getMessage());
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
        awaitBackpressureRelease(stop);

        StructuredLoggingHelper.setStage("idle");
        // The documents published since the last paced refresh become searchable here, rather
        // than waiting out the rest of the interval with nothing left to publish after them.
        setAsideInterrupt(stop);
        refreshLexicalIndex(stop);
        persistRegistry();

        // The counts and the error first, and the idle that ends the run last: a caller polling
        // the status stops at the first idle reading at full progress, and what is recorded
        // after it is never read.
        status.setSkipped(skippedDocs);
        status.setDisallowed(source.getDisallowedCount());
        reportSkippedDocuments(sourceId, skippedDocs, totalPending);
        reportDroppedDocuments(sourceId, status, failedDocs, keysTaken, totalPending);
        status.update("idle", 1.0, totalIngested, 0);
        int ingestedDocs = processedDocs - failedDocs - skippedDocs - disallowedDocs;
        LOGGER.info("Ingestion complete: {} chunks from {} of {} documents",
            totalIngested, ingestedDocs, totalPending);
        return new IngestionOutcome(ingestedDocs, totalIngested, failedDocs, skippedDocs, false);
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
     * <p>A document refused because another source's document holds its key is named apart from
     * the rest. Any other failure may pass on the next run; that one recurs on every run until
     * one of the two sources is registered under an id that does not collide, and only the log
     * would otherwise say so.</p>
     *
     * @param sourceId     The source that was ingested
     * @param status       The status to report on
     * @param failedDocs   Documents that were listed but not ingested
     * @param keysTaken    Of those, documents whose key another source's document holds
     * @param totalPending Documents the crawl listed
     */
    private void reportDroppedDocuments(String sourceId, IngestionStatus status,
                                        int failedDocs, int keysTaken, long totalPending) {
        if (failedDocs <= 0) {
            return;
        }
        LOGGER.warn("Ingestion of {} did not ingest {} of {} documents", sourceId, failedDocs, totalPending);
        String error = failedDocs + " of " + totalPending
            + " documents were not ingested; each failure is logged separately";
        if (keysTaken > 0) {
            error = error + "; " + keysTaken + " of them because a document of another source "
                + "already holds the same key (source id, colon, document id)";
        }
        status.setError(error);
    }

    /**
     * Logs the files the run passed over because they are not text.
     *
     * <p>Not an error, and deliberately not reported as one: a crawl root that holds images,
     * archives or compiled output alongside its documents is the ordinary case, and a status
     * that called those failures would leave every such source looking broken. The count reaches
     * the status endpoint as {@code entries_skipped}, where it answers the question an operator
     * actually has - why a directory of 620 files indexed 120 of them.</p>
     *
     * @param sourceId     The source that was ingested
     * @param skippedDocs  Documents whose bytes are not text
     * @param totalPending Documents the crawl listed
     */
    private void reportSkippedDocuments(String sourceId, int skippedDocs, long totalPending) {
        if (skippedDocs <= 0) {
            return;
        }
        LOGGER.info("Ingestion of {} skipped {} of {} documents as not text",
            sourceId, skippedDocs, totalPending);
    }

    /**
     * Whether the run should end before its next document.
     *
     * <p>An interrupt found on the thread counts as a stop and is cleared into the run's signal
     * rather than left set, for the reason {@link #setAsideInterrupt(StopSignal)} gives.</p>
     *
     * @param stop The run's stop signal
     * @return true if the run has been asked to stop, or the node is shutting down
     */
    private boolean stopRequested(StopSignal stop) {
        setAsideInterrupt(stop);
        return stop.isRequested() || shutdown.get();
    }

    /**
     * Clears an interrupt from the run's thread before it writes, keeping it as a stop request.
     *
     * <p>Nothing in this class interrupts a run outside the parts of a document that touch no
     * shared file, and those clear the flag on the way out. An interrupt from anywhere else still
     * means "stop", and is honoured as one - but carried into the writes that follow, it would
     * close the channel the write log, the lexical index or the registry is written through,
     * which the JDK does rather than fail the write.</p>
     *
     * @param stop The run's stop signal
     */
    private static void setAsideInterrupt(StopSignal stop) {
        if (Thread.interrupted()) {
            stop.request();
        }
    }

    /**
     * Publishes the lexical writes so far and merges segments if that is now due.
     *
     * <p>Compaction is asked each time rather than only at a run's end, because every refresh
     * on the native engine adds a segment and a long run would otherwise leave queries visiting
     * one per interval until it finished. The usual answer is that nothing is due. A compaction
     * reads the run's stop signal between groups of segments and between chunks, so stopping
     * the run - or shutting the node down - ends one in progress without publishing it, rather
     * than waiting out a rewrite of the whole index.</p>
     *
     * @param stop The run's stop signal
     */
    private void refreshLexicalIndex(StopSignal stop) {
        lexicalSideIndex.refresh();
        lexicalSideIndex.compactIfDue(() -> stop.isRequested() || shutdown.get());
    }

    /**
     * Publishes the lexical writes so far without letting a stop or a failure spoil the run.
     *
     * <p>A stop that lands during the refresh is seen by the loop's check before the next
     * document. A refresh that fails is logged and the run goes on: the documents stay published
     * and become searchable at the next refresh, which is a delay, whereas failing the run would
     * abandon the documents it had not reached for the sake of ones it already had.</p>
     */
    private void refreshLexicalIndexMidRun(String sourceId, StopSignal stop) {
        setAsideInterrupt(stop);
        try {
            refreshLexicalIndex(stop);
        } catch (RuntimeException e) {
            LOGGER.warn("Lexical index not refreshed partway through ingesting {}; the documents "
                + "published so far become searchable by BM25 at the next refresh: {}",
                sourceId, e.getMessage());
        }
    }

    /**
     * Makes what a failed run published searchable, and records it, before the failure goes on.
     *
     * <p>A run that fails partway has still published every document it finished, and those are
     * visible to every signal but BM25 until a refresh. Best effort: the run is already failing,
     * and a refresh that fails as well must not replace the error the caller is owed.</p>
     */
    private void finishFailedRun(StopSignal stop) {
        setAsideInterrupt(stop);
        try {
            lexicalSideIndex.refresh();
        } catch (RuntimeException e) {
            LOGGER.warn("Lexical index not refreshed after a failed run; the documents it "
                + "published become searchable by BM25 at the next refresh: {}", e.getMessage());
        } finally {
            try {
                persistRegistry();
            } catch (RuntimeException e) {
                LOGGER.warn("Document registry not persisted after a failed run: {}",
                    e.getMessage());
            }
        }
    }

    /**
     * Makes what a stopped run published searchable, and records it.
     *
     * <p>A stopped run's documents are published in storage: stopping abandons the documents it
     * had not reached, not the ones it had finished. Left unrefreshed they are visible to every
     * signal but BM25, and unrecorded they look new to the next re-crawl after a restart.</p>
     */
    private void finishStoppedRun(StopSignal stop) {
        setAsideInterrupt(stop);
        lexicalSideIndex.refresh();
        persistRegistry();
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
        // The graph first. The registry is forced to the device when it is written and is what
        // stops these documents being extracted again, so writing it ahead of their staged facts
        // leaves, after a crash, a registry that says the facts were taken and no facts.
        graphReporter.sync();
        try {
            documentRegistry.persist();
        } catch (IOException e) {
            LOGGER.warn("Document registry not persisted; a restart would make this run's "
                + "documents look new to the next re-crawl: {}", e.getMessage());
        }
    }

    /**
     * Throttles the embedding stage while the HNSW integration queue is over its limit.
     *
     * <p>A wait on the integration queue and nothing else, so a stop may interrupt it.</p>
     *
     * @param stop The run's stop signal
     */
    private void awaitBackpressureRelease(StopSignal stop) {
        if (!indexOrchestrator.isBackpressured()) {
            return;
        }
        LOGGER.info("Backpressure detected, waiting for HNSW queue to drain");
        boolean released = stop.interruptibly(
            () -> indexOrchestrator.awaitBackpressureRelease(documentTimeoutMs));
        if (!released && !stop.isRequested()) {
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
     * abandoned. A run blocked on its source or its embedding endpoint is interrupted out of the
     * wait; one writing is left to finish the document. Returns once the stop has been asked for,
     * not once it has happened.</p>
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
        Run run = ingestionThreads.get(sourceId);
        if (run == null) {
            run = synchronousIngestions.get(sourceId);
        }
        if (run == null) {
            return false;
        }
        LOGGER.info("Stopping ingestion of source {}", sourceId);
        run.stop().request();
        return true;
    }

    /**
     * Shuts down the orchestrator, asking active ingestions to stop and waiting for them to end.
     *
     * <p>This <strong>must</strong> be called - and must return - before the StorageEngine and
     * its Arena are closed. An ingestion thread still inside {@code storageEngine.put} writes
     * into a memory-mapped region; closing the Arena underneath it is undefined behaviour, not
     * a caught exception. False is the answer that has to be acted on: a run that did not reach
     * its next document boundary in time is still writing, and what it writes must stay
     * open.</p>
     *
     * <p>How long to wait is the caller's: a node's shutdown passes what its budget leaves the
     * drains, and an embedded caller passes what its own process can spare. A run stops at its
     * next document boundary, so a stopped run needs about one document's fetch, embedding and
     * writes.</p>
     *
     * @param wait How long to wait for the runs asked to stop to end
     * @return true if all ingestion runs finished within the wait
     * @throws NullPointerException if wait is null
     */
    public boolean shutdown(Duration wait) {
        Objects.requireNonNull(wait, "wait must not be null");
        LOGGER.info("Shutting down IngestionOrchestrator with {} active ingestions",
            activeIngestions.size());
        shutdown.set(true);

        // Asked, never interrupted outright: see the class documentation. Synchronous runs are
        // asked the same way but never joined, because their threads are request threads, and a
        // pooled thread does not die when its run ends.
        for (Run run : ingestionThreads.values()) {
            run.stop().request();
        }
        for (Run run : synchronousIngestions.values()) {
            run.stop().request();
        }

        // Wraps rather than saturates for a wait near Long.MAX_VALUE nanoseconds; every use
        // below subtracts the current time first, which undoes it.
        long deadline = System.nanoTime() + wait.toNanos();
        boolean clean = true;
        for (Run run : ingestionThreads.values()) {
            Thread worker = run.thread();
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                clean = false;
                break;
            }
            try {
                worker.join(Duration.ofNanos(remainingNanos));
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
            if (deadline - System.nanoTime() <= 0) {
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
            LOGGER.warn("Ingestion threads still running {}ms after being asked to stop: {}",
                wait.toMillis(), activeIngestions);
        }
        return clean;
    }
}
