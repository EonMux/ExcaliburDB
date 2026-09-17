package excalibur.ingest.pipeline;

import excalibur.ingest.erasure.DocumentErasureService;
import excalibur.ingest.lexical.ChunkIndex;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.ChangeDetector;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import io.excalibur.llm.client.LLMHttpClient;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.SegmentLifecycleManager;
import io.excalibur.store.VisibilityTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Coordinates re-indexing of changed documents: re-chunking, re-embedding where this node
 * embeds at all, and upserting with tombstoning of old versions.
 *
 * <h2>Re-indexing Process</h2>
 * <p>For each changed document, this coordinator:
 * <ol>
 *   <li>Fetches the updated document content from the source</li>
 *   <li>Re-chunks the document using the configured chunker (512 tokens / 64 overlap)</li>
 *   <li>Generates an embedding for each chunk, on a node configured with an embedding
 *       endpoint; a node that indexes its corpus lexically skips this step and stores the
 *       chunk with no vector, exactly as its ingestion did</li>
 *   <li>Upserts the new chunks into storage</li>
 *   <li>Tombstones old chunks via the UpsertHandler</li>
 *   <li>Removes old chunks from the Cuckoo filter hierarchy</li>
 *   <li>Updates the document registry with new metadata</li>
 * </ol>
 *
 * <h2>Atomic Publication</h2>
 * <p>When a {@link DocumentBatchPublisher} is configured, documents are published
 * atomically to the visibility epoch. This ensures:</p>
 * <ul>
 *   <li>No partial documents in query results</li>
 *   <li>Consistent snapshots across concurrent queries</li>
 *   <li>Epoch isolation for in-flight ingestion</li>
 *   <li>Cuckoo filter insertion for chunk membership</li>
 * </ul>
 *
 * <h2>What this class itself does</h2>
 * <p>It owns the wiring and the entry points, and hands each phase to the collaborator that
 * owns it: {@link SourceReindexPass} for one pass over a source's change set,
 * {@link DocumentReindexStep} for one document end to end, {@link RollingReplacement} for the
 * segment choreography, and {@link ReindexLexicalIndex} and {@link ReindexRollback} for the two
 * things a reindex has to be able to undo. Each of those carries the reasoning for its own
 * phase, and this one carries none of it twice.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Chunk token counts: 512±10% tokens as per TextChunker specification</li>
 *   <li>Embedding dimensions: configured dimension (typically 1536 for OpenAI ada-002), or
 *       none at all on a lexical-only node</li>
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
 * @see DocumentBatchPublisher
 */
public class DocumentReindexer {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentReindexer.class);

    private final TextChunker chunker;
    /** Embeds the chunks this reindexer writes, or null on a node that indexes lexically. */
    private final LLMHttpClient llmClient;
    private final UpsertHandler upsertHandler;
    private final DocumentRegistry registry;
    private final StorageWriter storageWriter;

    // Optional atomic publication support
    private final DocumentBatchPublisher batchPublisher;
    private final OffsetAwareStorageWriter offsetAwareWriter;

    // Optional segment lifecycle management for rolling replacement
    private final SegmentCatalog segmentCatalog;
    private final VisibilityTracker visibilityTracker;

    /** The lexical index this reindex keeps in step with storage, if the node has one. */
    private final ReindexLexicalIndex lexical = new ReindexLexicalIndex();

    /**
     * Retires the graph nodes of every document this reindex removes, or null for no vector
     * index.
     *
     * <p>A node is attributed to a document rather than to a chunk, so nothing the storage
     * tombstone or the registry removal touches can reach it: without this the document stays
     * rankable by vector search - reported by name, with a distance - after the reindex has
     * removed everything else of it.</p>
     */
    private volatile DocumentErasureService.VectorEraser vectorEraser;

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
     * Told how far a reindex has got, as it gets there.
     *
     * <p>A reindex answers 202 and runs on a background thread, so the only thing anyone can
     * learn about it afterwards is what it published while it ran. Publishing a constant - which
     * is what the reindex endpoint did - makes a run that is working and a run that is wedged
     * report the same thing for as long as either lasts.</p>
     *
     * <p>The unit is documents, deliberately. Chunks are an implementation detail of how a
     * document is indexed, they vary by an order of magnitude between documents, and a total
     * expressed in them cannot be compared with anything a caller knows about its own
     * corpus.</p>
     */
    @FunctionalInterface
    public interface ReindexProgress {

        /** A run whose progress nobody is watching. */
        ReindexProgress NONE = (processed, total) -> { };

        /**
         * Reports that the run has finished with a document, successfully or not.
         *
         * <p>Called once before the first document with a processed count of zero, so a caller
         * learns the size of the run before it learns anything else, and once after each
         * document thereafter. A document that could not be fetched counts as processed: the
         * run has moved past it, and a counter that stalls on a failure reads as a hang.</p>
         *
         * <p>Must not throw, and must not block: it is called from the reindex thread between
         * documents.</p>
         *
         * @param documentsProcessed How many documents the run has finished with
         * @param documentsTotal     How many it will finish with, new and changed together
         */
        void documentsDone(long documentsProcessed, long documentsTotal);
    }

    /**
     * Creates a new DocumentReindexer with basic storage writer (no atomic publication).
     *
     * <p>When using this constructor, documents are written directly to storage
     * without atomic visibility epoch advancement. Use
     * {@link #DocumentReindexer(TextChunker, LLMHttpClient, UpsertHandler, DocumentRegistry, OffsetAwareStorageWriter, DocumentBatchPublisher)}
     * for full atomic publication support.</p>
     *
     * @param chunker Text chunker for splitting documents
     * @param llmClient Embeds the chunks this reindexer writes, or null on a node that
     *                  indexes its corpus lexically and has no embedding endpoint
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
        this.llmClient = llmClient;
        this.upsertHandler = Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.storageWriter = Objects.requireNonNull(storageWriter, "storageWriter must not be null");
        this.batchPublisher = null;
        this.offsetAwareWriter = null;
        this.segmentCatalog = null;
        this.visibilityTracker = null;
    }

    /**
     * Creates a new DocumentReindexer with atomic batch publication support.
     *
     * <p>When using this constructor, documents are published atomically to the
     * visibility epoch via {@link DocumentBatchPublisher}. This ensures:</p>
     * <ul>
     *   <li>All chunks for a document become visible at the same epoch</li>
     *   <li>No partial documents in query results</li>
     *   <li>Cuckoo filter insertion for chunk membership</li>
     *   <li>Write log sync before epoch advancement</li>
     * </ul>
     *
     * @param chunker Text chunker for splitting documents
     * @param llmClient Embeds the chunks this reindexer writes, or null on a node that
     *                  indexes its corpus lexically and has no embedding endpoint
     * @param upsertHandler Handler for tombstoning old chunks
     * @param registry Document registry for metadata tracking
     * @param offsetAwareWriter Storage writer that returns write log offsets
     * @param batchPublisher Publisher for atomic batch publication
     * @throws NullPointerException if any required parameter is null
     */
    public DocumentReindexer(TextChunker chunker,
                            LLMHttpClient llmClient,
                            UpsertHandler upsertHandler,
                            DocumentRegistry registry,
                            OffsetAwareStorageWriter offsetAwareWriter,
                            DocumentBatchPublisher batchPublisher) {
        this.chunker = Objects.requireNonNull(chunker, "chunker must not be null");
        this.llmClient = llmClient;
        this.upsertHandler = Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.storageWriter = (chunkId, content, embedding) -> offsetAwareWriter.writeChunkWithOffset(chunkId, content, embedding) >= 0;
        this.offsetAwareWriter = Objects.requireNonNull(offsetAwareWriter, "offsetAwareWriter must not be null");
        this.batchPublisher = Objects.requireNonNull(batchPublisher, "batchPublisher must not be null");
        this.segmentCatalog = null;
        this.visibilityTracker = null;

        LOG.info("DocumentReindexer configured with atomic batch publication support");
    }

    /**
     * Creates a new DocumentReindexer with full segment lifecycle management for
     * zero-downtime rolling replacement.
     *
     * <p>This constructor enables the complete rolling replacement workflow:</p>
     * <ol>
     *   <li>Creates new segments in BOOTSTRAPPING state</li>
     *   <li>Old segments continue serving queries during reindexing</li>
     *   <li>New segments progress through INDEXING → WARMING → ACTIVE</li>
     *   <li>Old segments are tombstoned after new segments become ACTIVE</li>
     * </ol>
     *
     * <p><strong>Domain Correctness:</strong></p>
     * <ul>
     *   <li>Segments are created with unique IDs and associated with sourceId</li>
     *   <li>Segments start in BOOTSTRAPPING state (invisible to queries)</li>
     *   <li>State transitions follow the lifecycle state machine</li>
     *   <li>Old segments are tombstoned with proper epoch tracking</li>
     * </ul>
     *
     * @param chunker Text chunker for splitting documents
     * @param llmClient Embeds the chunks this reindexer writes, or null on a node that
     *                  indexes its corpus lexically and has no embedding endpoint
     * @param upsertHandler Handler for tombstoning old chunks
     * @param registry Document registry for metadata tracking
     * @param offsetAwareWriter Storage writer that returns write log offsets
     * @param batchPublisher Publisher for atomic batch publication
     * @param segmentCatalog Segment catalog for managing segment metadata
     * @param lifecycleManager Lifecycle manager for state transitions
     * @param visibilityTracker Visibility tracker for epoch management
     * @throws NullPointerException if any required parameter is null
     */
    public DocumentReindexer(TextChunker chunker,
                            LLMHttpClient llmClient,
                            UpsertHandler upsertHandler,
                            DocumentRegistry registry,
                            OffsetAwareStorageWriter offsetAwareWriter,
                            DocumentBatchPublisher batchPublisher,
                            SegmentCatalog segmentCatalog,
                            SegmentLifecycleManager lifecycleManager,
                            VisibilityTracker visibilityTracker) {
        this.chunker = Objects.requireNonNull(chunker, "chunker must not be null");
        this.llmClient = llmClient;
        this.upsertHandler = Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.storageWriter = (chunkId, content, embedding) -> offsetAwareWriter.writeChunkWithOffset(chunkId, content, embedding) >= 0;
        this.offsetAwareWriter = Objects.requireNonNull(offsetAwareWriter, "offsetAwareWriter must not be null");
        this.batchPublisher = Objects.requireNonNull(batchPublisher, "batchPublisher must not be null");
        this.segmentCatalog = Objects.requireNonNull(segmentCatalog, "segmentCatalog must not be null");
        // Validated but not held. The rolling replacement drives every transition through
        // the catalog itself - beginIndexing, completeIndexing, tombstone - so a manager was
        // never read after construction. The parameter and its contract stay: excalibur-api
        // builds this reindexer by this signature, and a caller that has not got one has not
        // got a node configured for rolling replacement either.
        Objects.requireNonNull(lifecycleManager, "lifecycleManager must not be null");
        this.visibilityTracker = Objects.requireNonNull(visibilityTracker, "visibilityTracker must not be null");

        LOG.info("DocumentReindexer configured with rolling replacement support (segment lifecycle enabled)");
    }

    /**
     * Whether this reindexer turns the chunks it writes into vectors.
     *
     * @return true when an embedding client is configured
     */
    public boolean embedsChunks() {
        return llmClient != null;
    }

    /**
     * Wires the lexical index a reindex must keep in step with storage.
     *
     * <p>Optional: a node with no lexical index reindexes exactly as before. When one is present,
     * every chunk this reindexer writes is indexed and every chunk it retires is erased, so the
     * two never disagree about what the corpus contains.</p>
     *
     * @param lexicalIndex The index, or null to keep none
     * @param storedText   Reads a chunk's text back from storage, which is what the index's
     *                     compaction rebuilds surviving segments from
     * @throws IllegalArgumentException if an index is given without a way to read text back
     */
    public void setLexicalIndex(ChunkIndex lexicalIndex, Function<String, String> storedText) {
        lexical.use(lexicalIndex, storedText);
    }

    /**
     * Wires the eraser that retires a removed document's graph nodes.
     *
     * @param vectorEraser The eraser, or null on a node that keeps no vector index
     */
    public void setVectorEraser(DocumentErasureService.VectorEraser vectorEraser) {
        this.vectorEraser = vectorEraser;
        LOG.info("Reindex vector retirement {}", vectorEraser == null ? "disabled" : "enabled");
    }

    /**
     * Returns true if this reindexer is configured with atomic batch publication.
     *
     * @return true if atomic publication is enabled
     */
    public boolean hasAtomicPublication() {
        return batchPublisher != null && offsetAwareWriter != null;
    }

    /**
     * Returns true if this reindexer is configured with rolling replacement support.
     *
     * <p>Rolling replacement enables zero-downtime reindexing by:</p>
     * <ul>
     *   <li>Creating new segments in BOOTSTRAPPING state</li>
     *   <li>Keeping old segments queryable during reindexing</li>
     *   <li>Tombstoning old segments after new segments become ACTIVE</li>
     * </ul>
     *
     * @return true if rolling replacement is enabled
     */
    public boolean hasRollingReplacement() {
        return segmentCatalog != null && visibilityTracker != null;
    }

    /**
     * Re-indexes all documents from a source with zero-downtime rolling replacement.
     *
     * <p>The workflow, the reason a hash-routed engine offers no writable target, and what the
     * run does instead when it does not, are all set out on {@link RollingReplacement}. In
     * short: the old segments stay queryable for the whole run, and the returned segment ID is
     * {@code -1} when no segment was replaced.</p>
     *
     * <p><strong>Prerequisites:</strong> This method requires rolling replacement
     * support to be enabled (see
     * {@link #DocumentReindexer(TextChunker, LLMHttpClient, UpsertHandler, DocumentRegistry, OffsetAwareStorageWriter, DocumentBatchPublisher, SegmentCatalog, SegmentLifecycleManager, VisibilityTracker)}).
     * If rolling replacement is not configured, falls back to regular reindexing.</p>
     *
     * @param source The source to re-index
     * @param volumePath The path where the new segment file should be created
     * @return Result containing counts, new segment ID, and any errors
     * @throws SourceException if crawling or fetching fails catastrophically
     * @throws NullPointerException if source or volumePath is null
     * @see RollingReplacement
     */
    public RollingReindexResult reindexWithRollingReplacement(Source source, Path volumePath) throws SourceException {
        return reindexWithRollingReplacement(source, volumePath, ReindexProgress.NONE);
    }

    /**
     * Re-indexes by rolling replacement, reporting progress as it goes.
     *
     * <p>The progress a caller sees is the same whichever branch this run takes - a real
     * replacement, or the fall back to re-indexing through the routing directory. Which of the
     * two happens is a property of the storage engine rather than of anything the caller asked
     * for, and a status that went silent on one of them would be reporting on the deployment
     * rather than on the run.</p>
     *
     * @param source     The source to re-index
     * @param volumePath Where the new segment file should be created
     * @param progress   Told how many documents the run has finished with
     * @return Result containing counts, new segment ID, and any errors
     * @throws SourceException      if crawling or fetching fails catastrophically
     * @throws NullPointerException if any argument is null
     * @see RollingReplacement
     */
    public RollingReindexResult reindexWithRollingReplacement(Source source, Path volumePath,
                                                              ReindexProgress progress)
            throws SourceException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(volumePath, "volumePath must not be null");
        Objects.requireNonNull(progress, "progress must not be null");

        // Fall back to regular reindex if rolling replacement not configured
        if (!hasRollingReplacement()) {
            LOG.warn("Rolling replacement not configured for source {}, falling back to regular reindex",
                source.getSourceId());
            ReindexResult basicResult = reindexAll(source, progress);
            return new RollingReindexResult(basicResult, -1, List.of());
        }

        return new RollingReplacement(segmentCatalog, visibilityTracker, offsetAwareWriter,
            batchPublisher, this::reindexAll, this::reindexAllWithSegmentWriter)
            .run(source, volumePath, progress);
    }

    /**
     * Result of a rolling reindex operation.
     *
     * <p>Contains the basic reindex result plus segment lifecycle information
     * for tracking the rolling replacement process.</p>
     */
    public static final class RollingReindexResult {
        private final ReindexResult basicResult;
        private final long newSegmentId;
        private final List<Long> tombstonedSegmentIds;

        public RollingReindexResult(ReindexResult basicResult, long newSegmentId, List<Long> tombstonedSegmentIds) {
            this.basicResult = Objects.requireNonNull(basicResult);
            this.newSegmentId = newSegmentId;
            this.tombstonedSegmentIds = List.copyOf(tombstonedSegmentIds != null ? tombstonedSegmentIds : List.of());
        }

        /**
         * Returns the basic reindex result.
         */
        public ReindexResult getBasicResult() {
            return basicResult;
        }

        /**
         * Returns the ID of the new segment created during rolling replacement.
         *
         * @return New segment ID, or -1 if rolling replacement was not used
         */
        public long getNewSegmentId() {
            return newSegmentId;
        }

        /**
         * Returns the IDs of old segments that were tombstoned.
         *
         * @return List of tombstoned segment IDs (may be empty)
         */
        public List<Long> getTombstonedSegmentIds() {
            return tombstonedSegmentIds;
        }

        /**
         * Returns the source ID from the basic result.
         */
        public String getSourceId() {
            return basicResult.getSourceId();
        }

        /**
         * Returns the number of documents indexed.
         */
        public int getDocumentsIndexed() {
            return basicResult.getDocumentsIndexed();
        }

        /**
         * Returns the number of chunks created.
         */
        public int getChunksCreated() {
            return basicResult.getChunksCreated();
        }

        /**
         * Returns true if there were any errors.
         */
        public boolean hasErrors() {
            return basicResult.hasErrors();
        }

        /**
         * Returns the number of errors.
         */
        public int getErrorCount() {
            return basicResult.getErrorCount();
        }

        @Override
        public String toString() {
            return String.format("RollingReindexResult[source=%s, newSegment=%d, indexed=%d, tombstoned=%d, errors=%d]",
                getSourceId(), newSegmentId, getDocumentsIndexed(), tombstonedSegmentIds.size(), getErrorCount());
        }
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
        return reindexAll(source, ReindexProgress.NONE);
    }

    /**
     * Re-indexes all changed and new documents from a source, reporting progress as it goes.
     *
     * @param source   The source to re-index
     * @param progress Told how many documents the run has finished with, as it finishes with
     *                 them
     * @return Result containing counts and any errors
     * @throws SourceException      if crawling or fetching fails catastrophically
     * @throws NullPointerException if either argument is null
     * @see SourceReindexPass
     */
    public ReindexResult reindexAll(Source source, ReindexProgress progress)
            throws SourceException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(progress, "progress must not be null");

        return pass(this::reindexDocument, SourceReindexPass.Route.THROUGH_ROUTING_DIRECTORY)
            .run(source, progress);
    }

    /**
     * Re-indexes all changed and new documents from a source using segment-targeted writers.
     *
     * <p>This method is used during rolling replacement reindexing to ensure all
     * reindexed data is written to the newly created segment. The segment-targeted
     * writers route all writes to the specified segment instead of the default
     * location.</p>
     *
     * <p><strong>Domain Correctness:</strong></p>
     * <ul>
     *   <li>All chunks are written to the target segment specified by the writer</li>
     *   <li>Cuckoo filter insertions are scoped to the target segment</li>
     *   <li>Document metadata correctly tracks the segment association</li>
     *   <li>Visibility epoch advances after all documents are indexed</li>
     * </ul>
     *
     * @param source The source to re-index
     * @param segmentWriter Storage writer bound to the target segment
     * @param segmentPublisher Publisher bound to the target segment
     * @return Result containing counts and any errors
     * @throws SourceException if crawling or fetching fails catastrophically
     * @throws NullPointerException if any parameter is null
     */
    public ReindexResult reindexAllWithSegmentWriter(
            Source source,
            OffsetAwareStorageWriter segmentWriter,
            DocumentBatchPublisher segmentPublisher) throws SourceException {
        return reindexAllWithSegmentWriter(source, segmentWriter, segmentPublisher,
            ReindexProgress.NONE);
    }

    /**
     * Re-indexes into a target segment, reporting progress as it goes.
     *
     * @param source           The source to re-index
     * @param segmentWriter    Storage writer bound to the target segment
     * @param segmentPublisher Publisher bound to the target segment
     * @param progress         Told how many documents the run has finished with
     * @return Result containing counts and any errors
     * @throws SourceException      if crawling or fetching fails catastrophically
     * @throws NullPointerException if any parameter is null
     * @see SourceReindexPass
     */
    public ReindexResult reindexAllWithSegmentWriter(
            Source source,
            OffsetAwareStorageWriter segmentWriter,
            DocumentBatchPublisher segmentPublisher,
            ReindexProgress progress) throws SourceException {

        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(segmentWriter, "segmentWriter must not be null");
        Objects.requireNonNull(segmentPublisher, "segmentPublisher must not be null");
        Objects.requireNonNull(progress, "progress must not be null");

        DocumentReindexStep step =
            step(new DocumentReindexStep.AtomicTarget(segmentWriter, segmentPublisher, true));
        return pass(step::reindex, SourceReindexPass.Route.INTO_TARGET_SEGMENT)
            .run(source, progress);
    }

    /**
     * Re-indexes a single document: fetches content, chunks, embeds, and upserts.
     *
     * <p>This method performs the complete re-indexing pipeline for one document:
     * <ol>
     *   <li>Validates content length is within bounds</li>
     *   <li>Decodes content as UTF-8 text</li>
     *   <li>Splits text into overlapping chunks</li>
     *   <li>Generates an embedding for each chunk, where this node embeds its corpus</li>
     *   <li>Writes chunks to storage</li>
     *   <li>Updates document registry with new metadata</li>
     * </ol>
     *
     * <h3>Atomic Publication</h3>
     * <p>If a {@link DocumentBatchPublisher} is configured, this method uses
     * {@link DocumentBatchBuffer} to accumulate all chunks and then publishes
     * them atomically via the publisher. This ensures:</p>
     * <ul>
     *   <li>All chunks become visible at the same visibility epoch</li>
     *   <li>No partial documents in query results</li>
     *   <li>Cuckoo filter insertion for chunk membership</li>
     * </ul>
     *
     * @param sourceId Source identifier
     * @param documentId Document identifier
     * @param fetched The fetched document content
     * @return Result of re-indexing this document
     * @throws IllegalArgumentException if content exceeds maximum length
     * @see DocumentReindexStep
     */
    public DocumentResult reindexDocument(String sourceId, String documentId, FetchedDocument fetched) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(fetched, "fetched must not be null");

        DocumentReindexStep step = hasAtomicPublication()
            ? step(new DocumentReindexStep.AtomicTarget(offsetAwareWriter, batchPublisher, false))
            : DocumentReindexStep.writingDirectly(chunker, llmClient, registry, upsertHandler,
                lexical, storageWriter);
        return step.reindex(sourceId, documentId, fetched);
    }

    /**
     * Builds the pass that walks a source's change set.
     *
     * <p>Built per run rather than held as a field, for the reason the rollback is: the vector
     * eraser and the lexical index are wired after construction, and a pass built once at
     * construction would go on reindexing as though neither had ever been attached.</p>
     */
    private SourceReindexPass pass(SourceReindexPass.DocumentStep documentStep,
                                   SourceReindexPass.Route route) {
        return new SourceReindexPass(registry, upsertHandler, lexical, vectorEraser, documentStep,
            route);
    }

    /** Builds the per-document step for a run that publishes atomically. */
    private DocumentReindexStep step(DocumentReindexStep.AtomicTarget target) {
        return DocumentReindexStep.publishing(chunker, llmClient, registry, upsertHandler, lexical,
            target);
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
        private final List<String> chunkIds;

        public DocumentResult(String documentId, int totalChunks, int successfulChunks, 
                            int failedChunks, long elapsedMillis, String error) {
            this(documentId, totalChunks, successfulChunks, failedChunks, elapsedMillis, error, List.of());
        }

        public DocumentResult(String documentId, int totalChunks, int successfulChunks,
                            int failedChunks, long elapsedMillis, String error, List<String> chunkIds) {
            this.documentId = documentId;
            this.totalChunks = totalChunks;
            this.successfulChunks = successfulChunks;
            this.failedChunks = failedChunks;
            this.elapsedMillis = elapsedMillis;
            this.error = error;
            this.chunkIds = chunkIds != null ? List.copyOf(chunkIds) : List.of();
        }

        /**
         * Returns the chunk ids that were written for this document's new version.
         *
         * <p>Used to work out which chunk ids of the previous version the new one did not
         * overwrite, and therefore have to be tombstoned.</p>
         *
         * @return Immutable list of chunk ids (empty when nothing was written)
         */
        public List<String> getChunkIds() {
            return chunkIds;
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

        /**
         * Whether this document produced nothing and failed nothing.
         *
         * <p>An empty file, or one the chunker finds no text in, carries a reason so the run
         * can account for it - and that reason is not a failure. Folding it in with the
         * failures makes a run that indexed everything it could report itself as failed, and
         * puts "Empty content" in front of an operator as the thing to investigate.</p>
         *
         * @return true if the document was skipped rather than failed
         */
        public boolean wasSkipped() {
            return hasError() && totalChunks == 0 && failedChunks == 0;
        }

        public boolean isSuccess() {
            // The reason has to be consulted, not just the counters. A document whose chunks
            // all wrote and whose publication then failed has no failed chunks and is not a
            // success: nothing of it reached the corpus, and it carries the reason why.
            return !hasError() && failedChunks == 0 && totalChunks > 0;
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
        private final int documentsSkipped;
        private final long elapsedMillis;
        private final List<OperationError> errors;

        private ReindexResult(Builder builder) {
            this.sourceId = builder.sourceId;
            this.documentsIndexed = builder.documentsIndexed;
            this.documentsSkipped = builder.documentsSkipped;
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

        /**
         * Documents that produced nothing and failed nothing.
         *
         * <p>Reported separately from {@link #getErrorCount()} so a run that met an empty file
         * is not a failed run. See {@link DocumentResult#wasSkipped()}.</p>
         *
         * @return How many documents were skipped
         */
        public int getDocumentsSkipped() {
            return documentsSkipped;
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
            return String.format("ReindexResult[source=%s, indexed=%d, skipped=%d, tombstoned=%d, " +
                "chunksCreated=%d, chunksTombstoned=%d, chunksFailed=%d, errors=%d, elapsed=%dms]",
                sourceId, documentsIndexed, documentsSkipped, documentsTombstoned, chunksCreated,
                chunksTombstoned, chunksFailed, errors.size(), elapsedMillis);
        }

        public static Builder builder(String sourceId) {
            return new Builder(sourceId);
        }

        public static final class Builder {
            private final String sourceId;
            private final long startTime = System.currentTimeMillis();
            private int documentsSkipped = 0;
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

            /**
             * Folds one document's outcome into the result.
             *
             * <p>{@code documentsIndexed} counts documents that actually produced data. A
             * document whose every chunk failed is not "indexed": rolling replacement uses this
             * count to decide whether the new segment is usable before it tombstones the old
             * ones, and counting total failures there would retire a populated segment in favour
             * of an empty one.</p>
             *
             * <p>A document that produced nothing and failed nothing is counted as skipped
             * rather than as an error - see {@link DocumentResult#wasSkipped()}. The run still
             * accounts for it; it just is not a run that failed.</p>
             */
            public Builder addDocumentResult(DocumentResult docResult) {
                if (docResult.getSuccessfulChunks() > 0) {
                    documentsIndexed++;
                }
                chunksCreated += docResult.getSuccessfulChunks();
                chunksFailed += docResult.getFailedChunks();
                if (docResult.wasSkipped()) {
                    documentsSkipped++;
                } else if (docResult.hasError()) {
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