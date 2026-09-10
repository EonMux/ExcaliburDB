package excalibur.ingest.pipeline;

import io.excalibur.core.codec.ChunkPayloadCodec;
import io.excalibur.core.text.ChunkIdentifiers;
import excalibur.ingest.processor.TextChunk;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.processor.TermFrequencyTracker;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.ingest.source.DocumentNotFoundException;
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
     * Chunks sent to the embedding endpoint in one request.
     *
     * <p>Sixteen 512-token chunks is roughly 8k tokens, which sits inside a typical server's
     * physical batch, keeps a failed request cheap to retry, and keeps the request body small
     * enough to be unremarkable.</p>
     */
    static final int EMBED_BATCH_SIZE = 16;

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

    /**
     * BM25 index over the chunks this orchestrator writes, or null when the node keeps none.
     *
     * <p>Written inside the same try that writes the chunks, so a document that fails and rolls
     * back does not leave its text searchable under a key nothing resolves.</p>
     */
    private volatile excalibur.ingest.lexical.ChunkIndex lexicalIndex;

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
        this.textChunker = Objects.requireNonNull(textChunker, "TextChunker cannot be null");
        // Nullable, deliberately. A null client is lexical-only ingestion: chunks are written,
        // term-indexed and published with no vector, which is what lets Lucene and the graph
        // serve retrieval without a corpus-wide embedding pass first. Vector search is then
        // simply a signal this deployment does not have.
        this.embeddingClient = embeddingClient;
        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.indexOrchestrator = Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        this.statusRegistry = Objects.requireNonNull(statusRegistry, "StatusRegistry cannot be null");
        this.visibilityTracker = visibilityTracker; // may be null
        this.documentRegistry = visibilityTracker != null ? visibilityTracker.getRegistry() : null;
        this.termFrequencyTracker = termFrequencyTracker; // may be null
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
     * Sets where published documents are reported so a graph can be built from them.
     *
     * <p>Set after construction because the graph stack belongs to the API layer, which is
     * assembled after this orchestrator. Null disables it, which is the state of any deployment
     * that indexes text and does not extract facts.</p>
     *
     * @param graphSink The sink, or null for none
     */
    /**
     * Points ingestion at a lexical index, so stored chunks are also BM25-searchable.
     *
     * @param lexicalIndex The index, or null to keep none
     */
    public void setLexicalIndex(excalibur.ingest.lexical.ChunkIndex lexicalIndex) {
        this.lexicalIndex = lexicalIndex;
        LOGGER.info("Lexical index {}", lexicalIndex == null
            ? "disabled" : "enabled (" + lexicalIndex.engine() + " engine)");
    }

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

        for (String documentId : documentIds) {
            if (shutdown.get() || Thread.currentThread().isInterrupted()) {
                // Either the node is stopping or this source alone was stopped. Checked per
                // document rather than per chunk: a document is written whole or not at all, and
                // abandoning one halfway is the state the rollback exists to avoid.
                LOGGER.info("Ingestion of {} stopped after {} of {} documents",
                    sourceId, processedDocs, totalPending);
                status.setError("Ingestion stopped");
                return;
            }

            awaitBackpressureRelease();

            try {
                StructuredLoggingHelper.setDocumentId(documentId);
                totalIngested += ingestDocument(source, sourceId, documentId);
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

        StructuredLoggingHelper.setStage("indexing");
        status.update("indexing", 0.9, totalIngested, 0);
        awaitBackpressureRelease();

        StructuredLoggingHelper.setStage("idle");
        // Lucene writes are invisible to search until a reader is reopened. Doing it once per
        // run rather than per document keeps the cost off the write path, at the price of a
        // source's chunks becoming searchable together at the end of its run.
        refreshLexicalIndex();
        compactLexicalIndex();
        persistRegistry();

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
    private int ingestDocument(Source source, String sourceId, String documentId)
            throws SourceException {
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

        // Null means this node embeds nothing at all; a null return from embedAll means this
        // document could not be embedded and must not be published in part.
        Map<Integer, float[]> embeddings = null;
        if (embeddingClient != null) {
            embeddings = embedAll(chunks, documentId);
            if (embeddings == null) {
                return 0;
            }
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
            publishDocument(sourceId, documentId, documentKey, doc, chunks);
        } catch (Exception e) {
            rollbackChunks(documentKey, writtenKeys, e);
            return 0;
        }

        trackTermFrequencies(documentKey, chunks);
        reportToGraph(documentKey, doc);
        return chunks.size();
    }

    /**
     * Generates embeddings for every chunk, a batch of {@link #EMBED_BATCH_SIZE} at a time.
     *
     * <p>One request per chunk was one HTTP round trip, one queue wait and one scheduler pass
     * per 512 tokens of work. Measured against a local GPU server, request overhead rather than
     * the model was the ceiling: three different sub-1B models landed within 6% of each other,
     * which is the signature of a bottleneck outside the weights. Batching amortises that
     * overhead over a whole document, and it is also what makes a document's chunks visible to
     * the embedding endpoint as a unit, which is the precondition for any context-aware
     * embedding model to be able to see across chunk boundaries at all.</p>
     *
     * <p>Bounded rather than one request per document: a document may hold up to
     * {@code MAX_CHUNKS_PER_DOCUMENT} chunks, and a single request carrying all of them would be
     * a multi-megabyte body whose failure costs the whole document.</p>
     *
     * @return The embeddings keyed by chunk index, or null if any chunk failed
     */
    private Map<Integer, float[]> embedAll(List<TextChunk> chunks, String documentId) {
        Map<Integer, float[]> embeddings = new HashMap<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += EMBED_BATCH_SIZE) {
            List<TextChunk> batch =
                chunks.subList(start, Math.min(start + EMBED_BATCH_SIZE, chunks.size()));
            List<String> texts = new ArrayList<>(batch.size());
            for (TextChunk chunk : batch) {
                texts.add(chunk.getText());
            }
            try {
                List<float[]> vectors = embeddingClient.embedBatch(texts);
                if (vectors.size() != batch.size()) {
                    // The client already checks this, so reaching here means the contract moved.
                    // Pairing vectors with the wrong chunks is undetectable downstream, so the
                    // document is abandoned rather than written from a guess.
                    LOGGER.warn("Skipping document {}: embedding endpoint returned {} vectors "
                        + "for {} chunks", documentId, vectors.size(), batch.size());
                    return null;
                }
                for (int i = 0; i < batch.size(); i++) {
                    embeddings.put(batch.get(i).getChunkIndex(), vectors.get(i));
                }
            } catch (LLMException e) {
                LOGGER.warn("Skipping document {}: failed to embed chunks {}-{}: {}",
                    documentId, batch.get(0).getChunkIndex(),
                    batch.get(batch.size() - 1).getChunkIndex(), e.getMessage());
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
            float[] vector = embeddings == null ? null : embeddings.get(chunk.getChunkIndex());
            byte[] value = vector == null
                ? ChunkPayloadCodec.encodeTextOnly(chunk.getText())
                : ChunkPayloadCodec.encode(chunk.getText(), vector);
            storageEngine.put(chunkId.getBytes(StandardCharsets.UTF_8), value);
            indexLexically(chunkId, chunk.getText());
            writtenKeys.add(chunkId);
        }
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
                                 FetchedDocument doc, List<TextChunk> chunks) {
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
            visibilityTracker.registerFromMetadata(metadata, epoch);
            LOGGER.debug("Document {} published at epoch {} with {} chunks",
                documentKey, epoch, chunkIds.size());
        }

        if (documentRegistry != null) {
            documentRegistry.register(metadata);
        }
    }

    /**
     * Removes chunks written for a document that failed to publish.
     */
    private void rollbackChunks(String documentKey, List<String> writtenKeys, Exception cause) {
        LOGGER.error("Rolling back document {} after publication failure: {}",
            documentKey, cause.getMessage(), cause);

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
        unindexLexically(writtenKeys);
    }

    /**
     * Takes rolled-back chunks back out of the lexical index.
     *
     * <p>Storage no longer has them, so a search that still returned them would report hits whose
     * text nothing can resolve - a chunk id pointing at a document that was never published. The
     * chunks were indexed as they were written, so undoing the write has to undo that too.</p>
     */
    private void unindexLexically(List<String> chunkIds) {
        excalibur.ingest.lexical.ChunkIndex target = this.lexicalIndex;
        if (target == null || chunkIds.isEmpty()) {
            return;
        }
        try {
            target.erase(new java.util.LinkedHashSet<>(chunkIds));
        } catch (IOException e) {
            LOGGER.warn("Rolled-back chunks of a failed document remain lexically searchable: {}",
                e.getMessage());
        }
    }

    /**
     * Reclaims erased chunks if the index's policy says it is due.
     *
     * <p>Run at the end of an ingestion run rather than on a timer of its own: this is the moment
     * the index has just changed, and a node that is not ingesting is not accumulating anything to
     * reclaim. The usual outcome is that nothing is due, which costs a comparison of four
     * numbers.</p>
     */
    private void compactLexicalIndex() {
        excalibur.ingest.lexical.ChunkIndex target = this.lexicalIndex;
        if (target == null) {
            return;
        }
        try {
            long started = System.currentTimeMillis();
            io.excalibur.retrieval.lexical.native_.CompactionResult result =
                target.compactIfDue(excalibur.ingest.lexical.StoredChunkText.of(storageEngine));
            if (result.performed()) {
                // The elapsed time is reported because queries wait on it: a compaction holds the
                // index exclusively while it rewrites. Merges pick the smallest segments and stay
                // brief; a compaction driven by an overdue erasure takes every segment holding a
                // tombstone and can take much longer, which is the deadline being met rather than
                // a fault, but is not something an operator should have to infer.
                LOGGER.info("Lexical compaction: {} segment(s) rewritten to {}, {} chunk(s) "
                    + "physically removed in {}ms ({})", result.segmentsRewritten(),
                    result.segmentsProduced(), result.documentsRemoved(),
                    System.currentTimeMillis() - started, result.reason());
            }
        } catch (IOException e) {
            LOGGER.warn("Lexical compaction did not run; erased chunks stay on disk "
                + "until the next attempt: {}", e.getMessage());
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

    /** Makes this run's lexical writes searchable. */
    private void refreshLexicalIndex() {
        excalibur.ingest.lexical.ChunkIndex target = this.lexicalIndex;
        if (target == null) {
            return;
        }
        try {
            target.refresh();
        } catch (IOException e) {
            LOGGER.warn("Lexical index not refreshed; this run's chunks stay unsearchable "
                + "until the next refresh: {}", e.getMessage());
        }
    }

    /**
     * Adds one chunk to the lexical index, if this node keeps one.
     *
     * <p>A failure here is logged and not rethrown. The chunk is already in storage and the
     * document is still worth publishing: losing one chunk from BM25 costs recall on that chunk,
     * while failing the document costs the document. The two are not the same size of loss.</p>
     */
    private void indexLexically(String chunkId, String text) {
        excalibur.ingest.lexical.ChunkIndex target = this.lexicalIndex;
        if (target == null) {
            return;
        }
        try {
            target.add(chunkId, text);
        } catch (IOException e) {
            LOGGER.warn("Chunk {} stored but not lexically indexed: {}", chunkId, e.getMessage());
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
        Thread worker = ingestionThreads.get(sourceId);
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
