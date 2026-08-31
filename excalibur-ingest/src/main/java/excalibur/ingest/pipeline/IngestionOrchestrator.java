package excalibur.ingest.pipeline;

import io.excalibur.core.text.ChunkIdentifiers;
import excalibur.ingest.buffer.AtomicDocumentBatch;
import excalibur.ingest.buffer.DocumentChunkBuffer;
import excalibur.ingest.processor.TextChunk;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.processor.TermFrequencyTracker;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.ingest.source.DocumentNotFoundException;
import excalibur.ingest.lsh.LshIngestionService;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import excalibur.llm.embedding.EmbeddingClient;
import excalibur.shard.IndexOrchestrator;
import io.excalibur.core.logging.StructuredLoggingHelper;
import io.excalibur.llm.LLMException;
import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * <p>An optional {@link DocumentChunkBuffer} additionally mirrors the document into a
 * write-ahead log as one contiguous batch, which records the offset range on the published
 * batch. Publication does not depend on it.</p>
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

    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    private final StorageEngine storageEngine;
    private final IndexOrchestrator indexOrchestrator;
    private final StatusRegistry statusRegistry;
    private final LshIngestionService lshIngestionService; // optional, nullable
    private final DocumentChunkBuffer documentChunkBuffer; // optional, nullable - WAL offset mirror
    private final DocumentVisibilityTracker visibilityTracker; // optional, nullable - visibility epoch

    /**
     * Registry that records what was ingested so a later reindex can detect changes and
     * tombstone the previous version. Derived from the visibility tracker, which owns it.
     * Null when no visibility tracker is configured.
     */
    private final DocumentRegistry documentRegistry;

    /**
     * Term frequency tracker for selectivity estimation during queries.
     * Tracks term frequencies in segment Count-Min Sketches for query optimization.
     * May be null if selectivity-based optimization is not needed.
     */
    private final TermFrequencyTracker termFrequencyTracker;

    /**
     * Timeout for awaiting backpressure release from the HNSW integration queue.
     * Configured via excalibur.ingest.document-atomicity-timeout-ms (default 2000ms).
     */
    private final int documentTimeoutMs;

    /**
     * Where published documents are reported so the graph can be built from them.
     *
     * <p>Optional and settable after construction: the graph stack is opened by the API layer,
     * which is built after this orchestrator, and a deployment that indexes text without
     * extracting facts never sets one.</p>
     */
    private volatile excalibur.ingest.graph.GraphIngestSink graphSink;

    private final Set<String> activeIngestions;
    private final Map<String, Thread> ingestionThreads;
    private final AtomicBoolean shutdown;

    /**
     * Default document timeout in milliseconds.
     * Used when no config is provided. Matches Config.Ingest.DEFAULT_DOCUMENT_TIMEOUT_MS.
     */
    public static final int DEFAULT_DOCUMENT_TIMEOUT_MS = 2000;

    /**
     * Upper bound on the byte size of a single fetched document.
     *
     * <p>A source can report an arbitrary file size (FilesystemSource allows up to 2GB), and a
     * document is held several times over while it is processed: the fetched byte array, its
     * defensive copy, the decoded String, and one substring per chunk. Every chunk also costs
     * one embedding call. Documents above this bound are skipped with a recorded error rather
     * than being allowed to exhaust the heap. Matches DocumentReindexer's limit.</p>
     */
    public static final int MAX_DOCUMENT_CONTENT_BYTES = 100 * 1024 * 1024;

    /**
     * Upper bound on the number of chunks accepted from a single document.
     *
     * <p>Each chunk is one embedding request and one storage write; a document is only published
     * once all of them succeed. This bounds the work a single document can schedule.</p>
     */
    public static final int MAX_CHUNKS_PER_DOCUMENT = 10_000;

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
        this(textChunker, embeddingClient, storageEngine, indexOrchestrator, statusRegistry, null, null, null, null, DEFAULT_DOCUMENT_TIMEOUT_MS);
    }


    /**
     * Creates a new IngestionOrchestrator with LSH ingestion support.
     *
     * <p>This constructor enables LSH index population during ingestion. When provided,
     * vectors are inserted into their segment's LSH index for efficient
     * approximate nearest neighbor pre-filtering during queries.</p>
     *
     * @param textChunker Text chunker for splitting documents
     * @param embeddingClient Embedding client for generating embeddings
     * @param storageEngine Storage engine for persisting entries
     * @param indexOrchestrator Index orchestrator for coordinating indexing
     * @param statusRegistry Status registry for tracking progress
     * @param lshIngestionService LSH ingestion service (may be null for no LSH integration)
     * @throws NullPointerException if required parameters are null
     */
    public IngestionOrchestrator(TextChunker textChunker,
                                  EmbeddingClient embeddingClient,
                                  StorageEngine storageEngine,
                                  IndexOrchestrator indexOrchestrator,
                                  StatusRegistry statusRegistry,
                                  LshIngestionService lshIngestionService) {
        this(textChunker, embeddingClient, storageEngine, indexOrchestrator, statusRegistry,
             lshIngestionService, null, null, null, DEFAULT_DOCUMENT_TIMEOUT_MS);
    }

    /**
     * Creates a new IngestionOrchestrator with full document atomicity support.
     *
     * <p>This constructor enables document-atomic visibility: all chunks of a document
     * become visible to queries at the same visibility epoch. When provided:</p>
     * <ul>
     *   <li>Chunks are buffered in memory per document</li>
     *   <li>On commit, all chunks are written atomically to the write log</li>
     *   <li>The batch is registered with the visibility tracker</li>
     *   <li>Queries never see partially-ingested documents</li>
     * </ul>
     *
     * <p><strong>Important:</strong> the documentChunkBuffer must own the write log it appends
     * to, so that one document's chunks land at contiguous offsets. The storageEngine remains
     * the source of truth and is still used for LSH index population.</p>
     *
     * @param textChunker Text chunker for splitting documents
     * @param embeddingClient Embedding client for generating embeddings
     * @param storageEngine Storage engine for persisting entries
     * @param indexOrchestrator Index orchestrator for coordinating indexing
     * @param statusRegistry Status registry for tracking progress
     * @param lshIngestionService LSH ingestion service (may be null for no LSH integration)
     * @param documentChunkBuffer Buffer mirroring documents into a write log (may be null)
     * @param visibilityTracker Tracker for document visibility epochs (may be null)
     * @throws NullPointerException if required parameters are null
     */
    public IngestionOrchestrator(TextChunker textChunker,
                                  EmbeddingClient embeddingClient,
                                  StorageEngine storageEngine,
                                  IndexOrchestrator indexOrchestrator,
                                  StatusRegistry statusRegistry,
                                  LshIngestionService lshIngestionService,
                                  DocumentChunkBuffer documentChunkBuffer,
                                  DocumentVisibilityTracker visibilityTracker) {
        this(textChunker, embeddingClient, storageEngine, indexOrchestrator, statusRegistry,
             lshIngestionService, documentChunkBuffer, visibilityTracker, null, DEFAULT_DOCUMENT_TIMEOUT_MS);
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
     * @param lshIngestionService LSH ingestion service (may be null for no LSH integration)
     * @param documentChunkBuffer Buffer mirroring documents into a write log (may be null)
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
                                  LshIngestionService lshIngestionService,
                                  DocumentChunkBuffer documentChunkBuffer,
                                  DocumentVisibilityTracker visibilityTracker,
                                  TermFrequencyTracker termFrequencyTracker,
                                  int documentTimeoutMs) {
        this.textChunker = Objects.requireNonNull(textChunker, "TextChunker cannot be null");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "EmbeddingClient cannot be null");
        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.indexOrchestrator = Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        this.statusRegistry = Objects.requireNonNull(statusRegistry, "StatusRegistry cannot be null");
        this.lshIngestionService = lshIngestionService; // may be null
        this.documentChunkBuffer = documentChunkBuffer; // may be null
        this.visibilityTracker = visibilityTracker; // may be null
        this.documentRegistry = visibilityTracker != null ? visibilityTracker.getRegistry() : null;
        this.termFrequencyTracker = termFrequencyTracker; // may be null
        this.documentTimeoutMs = documentTimeoutMs > 0 ? documentTimeoutMs : DEFAULT_DOCUMENT_TIMEOUT_MS;
        this.activeIngestions = ConcurrentHashMap.newKeySet();
        this.ingestionThreads = new ConcurrentHashMap<>();
        this.shutdown = new AtomicBoolean(false);

        LOGGER.info("IngestionOrchestrator initialized with LSH={}, visibilityPublishing={}, walMirror={}, termFrequencyTracking={}, documentTimeoutMs={}",
            (lshIngestionService != null ? "enabled" : "disabled"),
            (visibilityTracker != null ? "enabled" : "disabled"),
            (documentChunkBuffer != null ? "enabled" : "disabled"),
            (termFrequencyTracker != null ? "enabled" : "disabled"),
            this.documentTimeoutMs);
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
        this.graphSink = graphSink;
        LOGGER.info("Graph ingest sink {}", graphSink == null ? "disabled" : "enabled");
    }

    /**
     * Tells the graph about a document that is now published.
     *
     * <p>After publication, deliberately. A fact staged for a document whose write then failed
     * would be a fact about something the corpus does not contain, and nothing later would
     * retract it. Never throws: the document is already published and counted by the time this
     * runs, so failing here would report a loss that did not happen.</p>
     *
     * @param documentKey The source-qualified document key
     * @param doc         The fetched document, decoded with the encoding its source declared
     */
    private void reportToGraph(String documentKey, FetchedDocument doc) {
        excalibur.ingest.graph.GraphIngestSink sink = this.graphSink;
        if (sink == null) {
            return;
        }
        try {
            sink.onDocumentPublished(documentKey, doc.getText());
        } catch (RuntimeException e) {
            LOGGER.warn("Graph sink failed for {}: {}", documentKey, e.toString());
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

        // Re-check: shutdown() may have flipped the flag between the test above and the claim.
        if (shutdown.get()) {
            LOGGER.warn("Cannot start ingestion: orchestrator is shutting down");
            activeIngestions.remove(sourceId);
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
        ingestionThreads.put(sourceId, worker);
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
    private void ingestSource(Source source, String sourceId, IngestionStatus status) throws SourceException {
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
            return;
        }

        StructuredLoggingHelper.setStage("embedding");
        status.update("embedding", 0.0, 0, totalPending);

        long totalIngested = 0;
        int processedDocs = 0;
        Set<Segment> touchedSegments = new LinkedHashSet<>();

        for (String documentId : documentIds) {
            if (shutdown.get() || Thread.currentThread().isInterrupted()) {
                LOGGER.info("Ingestion interrupted by shutdown");
                status.setError("Ingestion interrupted");
                return;
            }

            awaitBackpressureRelease();

            try {
                StructuredLoggingHelper.setDocumentId(documentId);
                totalIngested += ingestDocument(source, sourceId, documentId, touchedSegments);
            } catch (DocumentNotFoundException e) {
                LOGGER.warn("Document not found: {}", documentId);
            } catch (SourceException e) {
                LOGGER.warn("Failed to fetch document {}: {}", documentId, e.getMessage());
            } catch (Exception e) {
                LOGGER.warn("Unexpected error processing document {}: {}", documentId, e.getMessage(), e);
            } finally {
                StructuredLoggingHelper.clearDocumentId();
            }

            processedDocs++;
            status.update("embedding", (double) processedDocs / totalPending,
                totalIngested, totalPending - processedDocs);
        }

        flushLshIndexes(touchedSegments);

        StructuredLoggingHelper.setStage("indexing");
        status.update("indexing", 0.9, totalIngested, 0);
        awaitBackpressureRelease();

        StructuredLoggingHelper.setStage("idle");
        status.update("idle", 1.0, totalIngested, 0);
        LOGGER.info("Ingestion complete: {} chunks from {} documents", totalIngested, processedDocs);
    }

    /**
     * Ingests one document as an atomic unit.
     *
     * <p>Every chunk is embedded and written before the document is published. A failure at any
     * step rolls back the chunks already written and leaves the document unregistered, so no
     * query can observe a fragment of it.</p>
     *
     * @return The number of chunks published (0 if the document was skipped)
     */
    private int ingestDocument(Source source, String sourceId, String documentId,
                               Set<Segment> touchedSegments) throws SourceException {
        FetchedDocument doc = source.fetch(documentId);

        if (doc.getContentLength() > MAX_DOCUMENT_CONTENT_BYTES) {
            LOGGER.warn("Skipping document {}: {} bytes exceeds the {} byte ingest limit",
                documentId, doc.getContentLength(), MAX_DOCUMENT_CONTENT_BYTES);
            return 0;
        }

        String documentKey = ChunkIdentifiers.documentKey(sourceId, documentId);
        // Decoded with the encoding the source declared, not with an assumed UTF-8: decoding a
        // windows-1252 or UTF-16 document as UTF-8 substitutes U+FFFD silently, and the terms
        // indexed from it are then not the terms it contains.
        List<TextChunk> chunks = textChunker.chunk(documentKey, doc.getText());

        if (chunks.isEmpty()) {
            LOGGER.debug("Document {} produced no chunks", documentId);
            return 0;
        }
        if (chunks.size() > MAX_CHUNKS_PER_DOCUMENT) {
            LOGGER.warn("Skipping document {}: {} chunks exceeds the per-document limit of {}",
                documentId, chunks.size(), MAX_CHUNKS_PER_DOCUMENT);
            return 0;
        }

        Map<Integer, float[]> embeddings = embedAll(chunks, documentId);
        if (embeddings == null) {
            return 0;
        }

        List<String> writtenKeys = new ArrayList<>(chunks.size());
        try {
            // Marked in flight before the first chunk reaches storage. This is what the
            // atomicity filter excludes on: a chunk written but not yet published is reachable
            // by a tail scan, and without the marker the filter has no way to tell that from a
            // document it simply has not heard of, so it either shows half a document or drops
            // whole ones. Publishing clears it; so does rollback.
            beginIngestion(documentKey, chunks);
            writeChunks(documentKey, chunks, embeddings, writtenKeys);
            AtomicDocumentBatch batch = mirrorToWriteLog(documentKey, chunks, embeddings);
            publishDocument(sourceId, documentId, documentKey, doc, chunks, batch);
        } catch (Exception e) {
            rollbackChunks(documentKey, writtenKeys, e);
            return 0;
        }

        indexVectors(documentKey, chunks, embeddings, touchedSegments);
        trackTermFrequencies(documentKey, chunks);
        reportToGraph(documentKey, doc);
        return chunks.size();
    }

    /**
     * Generates embeddings for every chunk.
     *
     * @return The embeddings keyed by chunk index, or null if any chunk failed
     */
    private Map<Integer, float[]> embedAll(List<TextChunk> chunks, String documentId) {
        Map<Integer, float[]> embeddings = new HashMap<>(chunks.size());
        for (TextChunk chunk : chunks) {
            try {
                embeddings.put(chunk.getChunkIndex(), embeddingClient.embed(chunk.getText()));
            } catch (LLMException e) {
                LOGGER.warn("Skipping document {}: failed to embed chunk {}: {}",
                    documentId, chunk.getChunkIndex(), e.getMessage());
                return null;
            }
        }
        return embeddings;
    }

    /**
     * Marks a document in flight, with the chunk ids it is about to write.
     *
     * <p>No-op without a visibility tracker: the atomicity guarantee is the tracker's, and a
     * deployment without one has already opted out of it.
     */
    private void beginIngestion(String documentKey, List<TextChunk> chunks) {
        if (visibilityTracker == null) {
            return;
        }
        visibilityTracker.beginIngestion(documentKey, chunks.size());
        for (TextChunk chunk : chunks) {
            visibilityTracker.addPendingChunk(documentKey,
                ChunkIdentifiers.chunkIdForDocumentKey(documentKey, chunk.getChunkIndex()));
        }
    }

    /**
     * Writes every chunk to the storage engine, recording the keys written so a later failure
     * can undo them.
     */
    private void writeChunks(String documentKey, List<TextChunk> chunks,
                             Map<Integer, float[]> embeddings, List<String> writtenKeys) {
        for (TextChunk chunk : chunks) {
            String chunkId = ChunkIdentifiers.chunkIdForDocumentKey(documentKey, chunk.getChunkIndex());
            byte[] value = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(
                chunk.getText(), embeddings.get(chunk.getChunkIndex()));
            storageEngine.put(chunkId.getBytes(StandardCharsets.UTF_8), value);
            writtenKeys.add(chunkId);
        }
    }

    /**
     * Mirrors the document into the chunk buffer's write log as one contiguous batch.
     *
     * <p>The write log fsyncs each append before it returns, so the batch is already durable
     * when this method hands it back and the document may be published straight away.</p>
     *
     * @return The batch with its write log offset range, or null when no buffer is configured
     * @throws IOException if the write log append fails
     */
    private AtomicDocumentBatch mirrorToWriteLog(String documentKey, List<TextChunk> chunks,
                                                 Map<Integer, float[]> embeddings) throws IOException {
        if (documentChunkBuffer == null) {
            return null;
        }
        for (TextChunk chunk : chunks) {
            documentChunkBuffer.bufferChunk(chunk);
        }
        return documentChunkBuffer.commitDocumentWithEmbeddings(documentKey, embeddings);
    }

    /**
     * Advances the visibility epoch and registers the document so queries can see it.
     *
     * <p>Registration uses the {@code sourceId:documentId} key, which is what excalibur-shard
     * derives from the storage key and what excalibur-query's atomicity filter looks up. It also
     * records the document in the {@link DocumentRegistry} so a later reindex can detect the
     * change and tombstone exactly these chunk ids.</p>
     */
    private void publishDocument(String sourceId, String documentId, String documentKey,
                                 FetchedDocument doc, List<TextChunk> chunks,
                                 AtomicDocumentBatch batch) {
        byte[] content = doc.getContent();
        List<String> chunkIds = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            chunkIds.add(ChunkIdentifiers.chunkIdForDocumentKey(documentKey, chunk.getChunkIndex()));
        }

        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(doc.getLastModifiedMillis())
            .contentHash(DocumentRegistry.computeContentHash(content))
            .contentLength(content.length)
            .chunkIds(chunkIds)
            .indexTimeMillis(System.currentTimeMillis())
            .build();

        if (visibilityTracker != null) {
            long epoch = visibilityTracker.advanceEpoch();
            if (batch != null) {
                visibilityTracker.registerAtomicDocument(batch, epoch);
            } else {
                visibilityTracker.registerFromMetadata(metadata, epoch);
            }
            LOGGER.debug("Document {} published at epoch {} with {} chunks",
                documentKey, epoch, chunkIds.size());
        }

        if (documentRegistry != null) {
            documentRegistry.register(metadata);
        }
    }

    /**
     * Removes chunks written for a document that failed to publish, and discards anything the
     * chunk buffer is still holding for it.
     */
    private void rollbackChunks(String documentKey, List<String> writtenKeys, Exception cause) {
        LOGGER.error("Rolling back document {} after publication failure: {}",
            documentKey, cause.getMessage(), cause);

        if (documentChunkBuffer != null) {
            documentChunkBuffer.abortDocument(documentKey);
        }

        // Clear the in-flight marker. A document left pending is invisible to every query
        // for the life of the process, and never released.
        if (visibilityTracker != null) {
            visibilityTracker.abortIngestion(documentKey);
        }

        for (String chunkId : writtenKeys) {
            try {
                storageEngine.delete(chunkId.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                LOGGER.warn("Failed to roll back chunk {}: {}", chunkId, e.getMessage());
            }
        }
    }

    /**
     * Inserts each chunk's vector into the LSH index of the segment the chunk actually landed in.
     *
     * <p>The segment is resolved after the writes, because a write may have split the segment
     * the key would previously have routed to.</p>
     */
    private void indexVectors(String documentKey, List<TextChunk> chunks,
                              Map<Integer, float[]> embeddings, Set<Segment> touchedSegments) {
        if (lshIngestionService == null) {
            return;
        }

        for (TextChunk chunk : chunks) {
            String chunkId = ChunkIdentifiers.chunkIdForDocumentKey(documentKey, chunk.getChunkIndex());
            try {
                Segment segment = segmentFor(chunkId);
                if (segment == null) {
                    continue;
                }
                long entryId = chunkId.hashCode() & 0xFFFFFFFFL;
                if (lshIngestionService.insertVector(segment, embeddings.get(chunk.getChunkIndex()), entryId)) {
                    touchedSegments.add(segment);
                }
            } catch (Exception e) {
                // LSH is a pre-filtering optimisation; a failure must not fail the document.
                LOGGER.warn("Failed to insert vector into LSH index for chunk {}: {}",
                    chunkId, e.getMessage());
            }
        }
    }

    /**
     * Records the document's terms in the Count-Min Sketch of each chunk's segment, so the query
     * planner can estimate selectivity.
     */
    private void trackTermFrequencies(String documentKey, List<TextChunk> chunks) {
        if (termFrequencyTracker == null) {
            return;
        }

        for (TextChunk chunk : chunks) {
            String chunkId = ChunkIdentifiers.chunkIdForDocumentKey(documentKey, chunk.getChunkIndex());
            try {
                Segment segment = segmentFor(chunkId);
                if (segment != null) {
                    termFrequencyTracker.trackText(segment, chunk.getText());
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to track term frequencies for chunk {}: {}", chunkId, e.getMessage());
            }
        }
    }

    /**
     * Resolves the segment a key routes to, using the same arithmetic as
     * {@code StorageEngine.put} so the LSH index and the sketch land on the segment that
     * actually holds the entry.
     */
    private Segment segmentFor(String chunkId) {
        int segmentCount = storageEngine.getSegmentCount();
        if (segmentCount <= 0) {
            return null;
        }
        long keyHash = io.excalibur.store.hash.XxHash64.hash(chunkId.getBytes(StandardCharsets.UTF_8));
        return storageEngine.getSegment(keyHash & (segmentCount - 1));
    }

    /**
     * Forces every LSH index written during this run to its backing files.
     *
     * <p>Without this the buckets built during ingestion live only in the index's page cache
     * until the segment is closed, so a crash mid-run loses the pre-filter for everything
     * ingested since the last flush while the query pipeline still believes it has one.</p>
     */
    private void flushLshIndexes(Set<Segment> touchedSegments) {
        if (lshIngestionService == null) {
            return;
        }
        for (Segment segment : touchedSegments) {
            lshIngestionService.flush(segment);
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

        if (!clean) {
            LOGGER.warn("Ingestion threads still running after {}ms shutdown timeout: {}",
                SHUTDOWN_AWAIT_MILLIS, activeIngestions);
        }
        return clean;
    }
}
