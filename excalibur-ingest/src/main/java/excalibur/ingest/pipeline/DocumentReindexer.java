package excalibur.ingest.pipeline;

import excalibur.ingest.processor.TextChunk;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.ChangeDetector;
import excalibur.ingest.registry.ChangeSet;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import io.excalibur.llm.client.LLMHttpClient;
import io.excalibur.llm.LLMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates re-indexing of changed documents: re-chunking, re-embedding via Phase 7, 
 * and upserting with tombstoning of old versions.
 *
 * <h2>Re-indexing Process</h2>
 * <p>For each changed document, this coordinator:
 * <ol>
 *   <li>Fetches the updated document content from the source</li>
 *   <li>Re-chunks the document using the configured chunker (512 tokens / 64 overlap)</li>
 *   <li>Generates embeddings for each chunk via the Phase 7 LLM client</li>
 *   <li>Upserts the new chunks into storage</li>
 *   <li>Tombstones old chunks via the UpsertHandler</li>
 *   <li>Removes old chunks from the Cuckoo filter hierarchy</li>
 *   <li>Updates the document registry with new metadata</li>
 * </ol>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Chunk token counts: 512±10% tokens as per TextChunker specification</li>
 *   <li>Embedding dimensions: configured dimension (typically 1536 for OpenAI ada-002)</li>
 *   <li>Chunk IDs: unique, format "{documentId}:chunk:{index}"</li>
 *   <li>Old chunks are tombstoned atomically before new chunks are visible</li>
 *   <li>Errors are collected and reported, not thrown, to continue processing other documents</li>
 *   <li>Content hash: 64-bit xxHash64 for change detection</li>
 *   <li>Timestamps: non-negative epoch milliseconds</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The reindexOne method can be called concurrently
 * from multiple threads for parallel processing. All state mutations use thread-safe
 * data structures or are delegated to thread-safe collaborators.</p>
 *
 * <h2>Error Handling</h2>
 * <p>Errors during re-indexing are collected and returned in the result object,
 * not thrown as exceptions. This allows processing to continue for other documents
 * even if one document fails. Callers should check {@link ReindexResult#hasErrors()}
 * after re-indexing completes.</p>
 *
 * @see ChangeDetector
 * @see UpsertHandler
 * @see TextChunker
 * @see LLMHttpClient
 */
public class DocumentReindexer {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentReindexer.class);
    private static final int MAX_CONTENT_LENGTH = 100 * 1024 * 1024;

    private final TextChunker chunker;
    private final LLMHttpClient llmClient;
    private final UpsertHandler upsertHandler;
    private final DocumentRegistry registry;
    private final StorageWriter storageWriter;

    /**
     * Functional interface for writing chunks to storage.
     * Allows decoupling from the actual storage implementation.
     */
    @FunctionalInterface
    public interface StorageWriter {
        /**
         * Writes a chunk with its embedding to storage.
         *
         * @param chunkId Unique chunk identifier
         * @param content Chunk text content
         * @param embedding Embedding vector
         * @return true if write succeeded
         */
        boolean writeChunk(String chunkId, String content, float[] embedding);
    }

    /**
     * Creates a new DocumentReindexer.
     *
     * @param chunker Text chunker for splitting documents
     * @param llmClient LLM client for generating embeddings
     * @param upsertHandler Handler for tombstoning old chunks
     * @param registry Document registry for metadata tracking
     * @param storageWriter Storage writer for persisting chunks
     * @throws NullPointerException if any parameter is null
     */
    public DocumentReindexer(TextChunker chunker, 
                            LLMHttpClient llmClient,
                            UpsertHandler upsertHandler,
                            DocumentRegistry registry,
                            StorageWriter storageWriter) {
        this.chunker = Objects.requireNonNull(chunker, "chunker must not be null");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.upsertHandler = Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.storageWriter = Objects.requireNonNull(storageWriter, "storageWriter must not be null");
    }

    /**
     * Re-indexes all changed and new documents from a source.
     *
     * <p>This method performs a full change detection cycle and re-indexes
     * all documents that have changed or are new. Deleted documents are
     * tombstoned.</p>
     *
     * @param source The source to re-index
     * @return Result containing counts and any errors
     * @throws SourceException if crawling or fetching fails catastrophically
     * @throws NullPointerException if source is null
     */
    public ReindexResult reindexAll(Source source) throws SourceException {
        Objects.requireNonNull(source, "source must not be null");
        
        String sourceId = source.getSourceId();
        LOG.info("Starting re-index for source: {}", sourceId);
        
        ChangeDetector detector = new ChangeDetector(registry);
        ChangeSet changeSet = detector.detectChanges(source);
        
        LOG.info("Change detection complete for {}: new={}, changed={}, deleted={}", 
                sourceId, changeSet.getNewCount(), changeSet.getChangedCount(), 
                changeSet.getDeletedCount());
        
        ReindexResult.Builder resultBuilder = ReindexResult.builder(sourceId);
        
        UpsertHandler.UpsertResult tombstoneResult = upsertHandler.processChanges(changeSet);
        resultBuilder.documentsTombstoned(tombstoneResult.getDocumentsDeleted());
        resultBuilder.chunksTombstoned(tombstoneResult.getChunksTombstoned());
        tombstoneResult.getErrors().forEach(error -> 
            resultBuilder.addError("tombstone", error.getChunkId(), error.getMessage()));
        
        for (String newDocId : changeSet.getNewDocuments()) {
            try {
                FetchedDocument fetched = source.fetch(newDocId);
                DocumentResult docResult = reindexDocument(sourceId, newDocId, fetched);
                resultBuilder.addDocumentResult(docResult);
            } catch (SourceException e) {
                String msg = "Failed to fetch new document " + newDocId + ": " + e.getMessage();
                LOG.error(msg, e);
                resultBuilder.addError("fetch", newDocId, msg);
            } catch (Exception e) {
                String msg = "Failed to re-index new document " + newDocId + ": " + e.getMessage();
                LOG.error(msg, e);
                resultBuilder.addError("reindex", newDocId, msg);
            }
        }
        
        for (ChangeSet.ChangedDocument changed : changeSet.getChangedDocuments()) {
            try {
                FetchedDocument fetched = source.fetch(changed.getDocumentId());
                DocumentResult docResult = reindexDocument(sourceId, changed.getDocumentId(), fetched);
                resultBuilder.addDocumentResult(docResult);
            } catch (SourceException e) {
                String msg = "Failed to fetch changed document " + changed.getDocumentId() + ": " + e.getMessage();
                LOG.error(msg, e);
                resultBuilder.addError("fetch", changed.getDocumentId(), msg);
            } catch (Exception e) {
                String msg = "Failed to re-index changed document " + changed.getDocumentId() + ": " + e.getMessage();
                LOG.error(msg, e);
                resultBuilder.addError("reindex", changed.getDocumentId(), msg);
            }
        }
        
        ReindexResult result = resultBuilder.build();
        LOG.info("Re-index complete for {}: {} docs indexed, {} chunks created, {} errors", 
                sourceId, result.getDocumentsIndexed(), result.getChunksCreated(), result.getErrorCount());
        
        return result;
    }

    /**
     * Re-indexes a single document: fetches content, chunks, embeds, and upserts.
     *
     * <p>This method performs the complete re-indexing pipeline for one document:
     * <ol>
     *   <li>Validates content length is within bounds</li>
     *   <li>Decodes content as UTF-8 text</li>
     *   <li>Splits text into overlapping chunks</li>
     *   <li>Generates embeddings for each chunk via LLM</li>
     *   <li>Writes chunks to storage</li>
     *   <li>Updates document registry with new metadata</li>
     * </ol>
     *
     * @param sourceId Source identifier
     * @param documentId Document identifier
     * @param fetched The fetched document content
     * @return Result of re-indexing this document
     * @throws IllegalArgumentException if content exceeds maximum length
     */
    public DocumentResult reindexDocument(String sourceId, String documentId, FetchedDocument fetched) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(fetched, "fetched must not be null");
        
        long startTime = System.currentTimeMillis();
        
        byte[] content = fetched.getContent();
        if (content == null || content.length == 0) {
            return new DocumentResult(documentId, 0, 0, 0, 
                System.currentTimeMillis() - startTime, "Empty content");
        }
        
        if (content.length > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                "Content length " + content.length + " exceeds maximum " + MAX_CONTENT_LENGTH);
        }
        
        String text = new String(content, StandardCharsets.UTF_8);
        
        List<TextChunk> chunks = chunker.chunk(documentId, text);
        if (chunks.isEmpty()) {
            return new DocumentResult(documentId, 0, 0, 0,
                System.currentTimeMillis() - startTime, "No chunks produced");
        }
        
        List<String> chunkIds = new ArrayList<>(chunks.size());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String chunkId = generateChunkId(documentId, i);
            
            try {
                float[] embedding = llmClient.generateEmbedding(chunk.getText(), 
                    "reindex:" + sourceId + ":" + documentId);
                
                boolean written = storageWriter.writeChunk(chunkId, chunk.getText(), embedding);
                if (written) {
                    chunkIds.add(chunkId);
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                    errors.add("Storage write failed for chunk " + i);
                    LOG.warn("Failed to write chunk {} for document {}", i, documentId);
                }
            } catch (LLMException e) {
                failureCount.incrementAndGet();
                errors.add("Embedding failed for chunk " + i + ": " + e.getMessage());
                LOG.error("Failed to generate embedding for chunk {} of document {}: {}", 
                    i, documentId, e.getMessage());
            }
        }
        
        long contentHash = DocumentRegistry.computeContentHash(content);
        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(fetched.getLastModifiedMillis())
            .contentHash(contentHash)
            .contentLength(fetched.getContentLength())
            .chunkIds(chunkIds)
            .indexTimeMillis(System.currentTimeMillis())
            .build();
        
        registry.register(metadata);
        
        long elapsed = System.currentTimeMillis() - startTime;
        String errorMsg = errors.isEmpty() ? null : String.join("; ", errors);
        
        LOG.debug("Re-indexed document {}: {} chunks ({} successful, {} failed) in {}ms",
            documentId, chunks.size(), successCount.get(), failureCount.get(), elapsed);
        
        return new DocumentResult(documentId, chunks.size(), successCount.get(), 
            failureCount.get(), elapsed, errorMsg);
    }

    /**
     * Generates a unique chunk ID from a document ID and chunk index.
     *
     * <p>Format: "{documentId}:chunk:{index}"</p>
     *
     * <p>Domain correctness: chunk IDs are unique, deterministic, and URL-safe.</p>
     *
     * @param documentId The document ID
     * @param chunkIndex The chunk index (0-based)
     * @return Unique chunk identifier
     */
    private String generateChunkId(String documentId, int chunkIndex) {
        return documentId + ":chunk:" + chunkIndex;
    }

    /**
     * Result of re-indexing a single document.
     */
    public static final class DocumentResult {
        private final String documentId;
        private final int totalChunks;
        private final int successfulChunks;
        private final int failedChunks;
        private final long elapsedMillis;
        private final String error;

        public DocumentResult(String documentId, int totalChunks, int successfulChunks, 
                            int failedChunks, long elapsedMillis, String error) {
            this.documentId = documentId;
            this.totalChunks = totalChunks;
            this.successfulChunks = successfulChunks;
            this.failedChunks = failedChunks;
            this.elapsedMillis = elapsedMillis;
            this.error = error;
        }

        public String getDocumentId() {
            return documentId;
        }

        public int getTotalChunks() {
            return totalChunks;
        }

        public int getSuccessfulChunks() {
            return successfulChunks;
        }

        public int getFailedChunks() {
            return failedChunks;
        }

        public long getElapsedMillis() {
            return elapsedMillis;
        }

        public String getError() {
            return error;
        }

        public boolean hasError() {
            return error != null && !error.isEmpty();
        }

        public boolean isSuccess() {
            return failedChunks == 0 && totalChunks > 0;
        }

        @Override
        public String toString() {
            return String.format("DocumentResult[id=%s, chunks=%d, success=%d, failed=%d, elapsed=%dms]",
                documentId, totalChunks, successfulChunks, failedChunks, elapsedMillis);
        }
    }

    /**
     * Result of re-indexing all documents from a source.
     */
    public static final class ReindexResult {
        private final String sourceId;
        private final int documentsIndexed;
        private final int documentsTombstoned;
        private final int chunksCreated;
        private final int chunksTombstoned;
        private final int chunksFailed;
        private final long elapsedMillis;
        private final List<OperationError> errors;

        private ReindexResult(Builder builder) {
            this.sourceId = builder.sourceId;
            this.documentsIndexed = builder.documentsIndexed;
            this.documentsTombstoned = builder.documentsTombstoned;
            this.chunksCreated = builder.chunksCreated;
            this.chunksTombstoned = builder.chunksTombstoned;
            this.chunksFailed = builder.chunksFailed;
            this.elapsedMillis = System.currentTimeMillis() - builder.startTime;
            this.errors = List.copyOf(builder.errors);
        }

        public String getSourceId() {
            return sourceId;
        }

        public int getDocumentsIndexed() {
            return documentsIndexed;
        }

        public int getDocumentsTombstoned() {
            return documentsTombstoned;
        }

        public int getChunksCreated() {
            return chunksCreated;
        }

        public int getChunksTombstoned() {
            return chunksTombstoned;
        }

        public int getChunksFailed() {
            return chunksFailed;
        }

        public long getElapsedMillis() {
            return elapsedMillis;
        }

        public List<OperationError> getErrors() {
            return errors;
        }

        public int getErrorCount() {
            return errors.size();
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean isSuccess() {
            return errors.isEmpty() && documentsIndexed > 0;
        }

        @Override
        public String toString() {
            return String.format("ReindexResult[source=%s, indexed=%d, tombstoned=%d, " +
                "chunksCreated=%d, chunksTombstoned=%d, chunksFailed=%d, errors=%d, elapsed=%dms]",
                sourceId, documentsIndexed, documentsTombstoned, chunksCreated, 
                chunksTombstoned, chunksFailed, errors.size(), elapsedMillis);
        }

        public static Builder builder(String sourceId) {
            return new Builder(sourceId);
        }

        public static final class Builder {
            private final String sourceId;
            private final long startTime = System.currentTimeMillis();
            private int documentsIndexed = 0;
            private int documentsTombstoned = 0;
            private int chunksCreated = 0;
            private int chunksTombstoned = 0;
            private int chunksFailed = 0;
            private final List<OperationError> errors = new ArrayList<>();

            public Builder(String sourceId) {
                this.sourceId = Objects.requireNonNull(sourceId);
            }

            public Builder documentsIndexed(int count) {
                this.documentsIndexed = count;
                return this;
            }

            public Builder documentsTombstoned(int count) {
                this.documentsTombstoned = count;
                return this;
            }

            public Builder chunksCreated(int count) {
                this.chunksCreated = count;
                return this;
            }

            public Builder chunksTombstoned(int count) {
                this.chunksTombstoned = count;
                return this;
            }

            public Builder chunksFailed(int count) {
                this.chunksFailed = count;
                return this;
            }

            public Builder addDocumentResult(DocumentResult docResult) {
                documentsIndexed++;
                chunksCreated += docResult.getSuccessfulChunks();
                chunksFailed += docResult.getFailedChunks();
                if (docResult.hasError()) {
                    errors.add(new OperationError("document", docResult.getDocumentId(), 
                        docResult.getError()));
                }
                return this;
            }

            public Builder addError(String component, String id, String message) {
                errors.add(new OperationError(component, id, message));
                return this;
            }

            public ReindexResult build() {
                return new ReindexResult(this);
            }
        }

        public static final class OperationError {
            private final String component;
            private final String id;
            private final String message;

            public OperationError(String component, String id, String message) {
                this.component = component;
                this.id = id;
                this.message = message;
            }

            public String getComponent() {
                return component;
            }

            public String getId() {
                return id;
            }

            public String getMessage() {
                return message;
            }

            @Override
            public String toString() {
                return String.format("OperationError[%s: %s - %s]", component, id, message);
            }
        }
    }
}
