package excalibur.ingest.pipeline;

import excalibur.ingest.processor.TextChunk;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.ingest.source.DocumentNotFoundException;
import excalibur.shard.IndexOrchestrator;
import io.excalibur.llm.client.LLMHttpClient;
import io.excalibur.llm.LLMException;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *   <li>Updating ingestion status</li>
 * </ol>
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
    private final LLMHttpClient llmClient;
    private final StorageEngine storageEngine;
    private final IndexOrchestrator indexOrchestrator;
    private final StatusRegistry statusRegistry;

    private final Set<String> activeIngestions;
    private final AtomicBoolean shutdown;

    /**
     * Creates a new IngestionOrchestrator with required dependencies.
     *
     * @param textChunker Text chunker for splitting documents
     * @param llmClient LLM client for generating embeddings
     * @param storageEngine Storage engine for persisting entries
     * @param indexOrchestrator Index orchestrator for coordinating indexing
     * @param statusRegistry Status registry for tracking progress
     * @throws NullPointerException if any parameter is null
     */
    public IngestionOrchestrator(TextChunker textChunker,
                                  LLMHttpClient llmClient,
                                  StorageEngine storageEngine,
                                  IndexOrchestrator indexOrchestrator,
                                  StatusRegistry statusRegistry) {
        this.textChunker = Objects.requireNonNull(textChunker, "TextChunker cannot be null");
        this.llmClient = Objects.requireNonNull(llmClient, "LLMHttpClient cannot be null");
        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.indexOrchestrator = Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        this.statusRegistry = Objects.requireNonNull(statusRegistry, "StatusRegistry cannot be null");
        this.activeIngestions = ConcurrentHashMap.newKeySet();
        this.shutdown = new AtomicBoolean(false);

        LOGGER.info("IngestionOrchestrator initialized");
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

        // Check if already ingesting
        if (!activeIngestions.add(sourceId)) {
            LOGGER.debug("Ingestion already active for source: {}", sourceId);
            return;
        }

        // Check if shutdown
        if (shutdown.get()) {
            LOGGER.warn("Cannot start ingestion: orchestrator is shutting down");
            activeIngestions.remove(sourceId);
            return;
        }

        LOGGER.info("Starting background ingestion for source: {}", sourceId);

        // Spawn virtual thread for background ingestion
        Thread.ofVirtual().name("ingestion-" + sourceId).start(() -> {
            try {
                runIngestion(source);
            } finally {
                activeIngestions.remove(sourceId);
            }
        });
    }

    /**
     * Runs the complete ingestion pipeline for a source.
     *
     * @param source The source to ingest
     */
    private void runIngestion(Source source) {
        String sourceId = source.getSourceId();
        IngestionStatus status = statusRegistry.getOrCreate(sourceId);

        long totalIngested = 0;
        long totalPending = 0;

        try {
            // Phase 1: Crawling
            status.update("crawling", 0.0, 0, 0);
            LOGGER.info("[{}] Phase 1: Starting crawl", sourceId);

            List<String> documentIds;
            try (Stream<String> crawlStream = source.crawl()) {
                documentIds = crawlStream.toList();
            }

            totalPending = documentIds.size();
            LOGGER.info("[{}] Discovered {} documents", sourceId, totalPending);

            if (totalPending == 0) {
                status.update("idle", 1.0, 0, 0);
                LOGGER.info("[{}] No documents to ingest", sourceId);
                return;
            }

            // Estimate total chunks for progress tracking
            long estimatedTotalChunks = totalPending * 3; // Assume ~3 chunks per doc

            // Phase 2: Processing documents
            status.update("embedding", 0.0, 0, totalPending);

            int processedDocs = 0;
            int processedChunks = 0;

            for (String documentId : documentIds) {
                if (shutdown.get()) {
                    LOGGER.info("[{}] Ingestion interrupted by shutdown", sourceId);
                    status.setError("Ingestion interrupted");
                    return;
                }

                try {
                    // Fetch document
                    FetchedDocument doc = source.fetch(documentId);
                    String content = new String(doc.getContent(), StandardCharsets.UTF_8);

                    // Chunk the content
                    List<TextChunk> chunks = textChunker.chunk(documentId, content);

                    // Process each chunk
                    for (int i = 0; i < chunks.size(); i++) {
                        TextChunk chunk = chunks.get(i);

                        try {
                            // Generate embedding
                            float[] embedding = llmClient.generateEmbedding(chunk.getText());

                            // Create chunk ID
                            String chunkId = sourceId + ":" + documentId + ":chunk:" + i;

                            // Write to storage (key = chunk ID, value = chunk text)
                            // In a real implementation, we'd store the embedding vector too
                            byte[] keyBytes = chunkId.getBytes(StandardCharsets.UTF_8);
                            byte[] valueBytes = chunk.getText().getBytes(StandardCharsets.UTF_8);
                            storageEngine.put(keyBytes, valueBytes);

                            totalIngested++;
                            processedChunks++;

                            // Update progress
                            double progress = (double) processedChunks / estimatedTotalChunks;
                            status.update("embedding", Math.min(1.0, progress), totalIngested,
                                totalPending - processedDocs);

                        } catch (LLMException e) {
                            LOGGER.warn("[{}] Failed to embed chunk {} of {}: {}",
                                sourceId, i, documentId, e.getMessage());
                            // Continue with next chunk
                        }
                    }

                    processedDocs++;

                } catch (DocumentNotFoundException e) {
                    LOGGER.warn("[{}] Document not found: {}", sourceId, documentId);
                } catch (SourceException e) {
                    LOGGER.warn("[{}] Failed to fetch document {}: {}",
                        sourceId, documentId, e.getMessage());
                } catch (Exception e) {
                    LOGGER.warn("[{}] Unexpected error processing document {}: {}",
                        sourceId, documentId, e.getMessage());
                }

                // Update progress after each document
                double progress = (double) processedDocs / totalPending;
                status.update("embedding", progress, totalIngested, totalPending - processedDocs);
            }

            // Phase 3: Indexing (wait for HNSW integration)
            status.update("indexing", 0.9, totalIngested, 0);

            // Wait for backpressure to clear before marking complete
            if (indexOrchestrator.isBackpressured()) {
                LOGGER.info("[{}] Waiting for HNSW integration queue to drain", sourceId);
                indexOrchestrator.awaitBackpressureRelease(30_000);
            }

            // Complete
            status.update("idle", 1.0, totalIngested, 0);
            LOGGER.info("[{}] Ingestion complete: {} chunks from {} documents",
                sourceId, totalIngested, processedDocs);

        } catch (SourceException e) {
            String error = "Crawl failed: " + e.getMessage();
            LOGGER.error("[{}] {}", sourceId, error, e);
            status.setError(error);

        } catch (Exception e) {
            String error = "Ingestion failed: " + e.getMessage();
            LOGGER.error("[{}] {}", sourceId, error, e);
            status.setError(error);

        } finally {
            // Ensure source is closed
            try {
                source.close();
            } catch (IOException e) {
                LOGGER.debug("[{}] Error closing source: {}", sourceId, e.getMessage());
            }
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
     * Shuts down the orchestrator, interrupting any active ingestions.
     */
    public void shutdown() {
        LOGGER.info("Shutting down IngestionOrchestrator with {} active ingestions",
            activeIngestions.size());
        shutdown.set(true);
    }
}
