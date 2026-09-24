package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.core.util.StopSignal;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.ChangeDetector;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.ListingGaps;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.VisibilityTracker;
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
 *   <li>Upserts the new chunks into storage, with the document marked in flight</li>
 *   <li>Tombstones the old chunks the new version no longer reaches via the UpsertHandler</li>
 *   <li>Updates the document registry with new metadata</li>
 *   <li>Tells the graph, so the facts staged from the document's earlier version are withdrawn
 *       and the new version's staged; a document the source no longer lists has its facts
 *       withdrawn as it is removed</li>
 * </ol>
 *
 * <h2>Atomic Publication</h2>
 * <p>When a {@link DocumentBatchPublisher} is configured, documents are published
 * atomically to the visibility epoch, through the document visibility tracker the ingestion
 * pipeline publishes through. This ensures:</p>
 * <ul>
 *   <li>No partial documents in query results: a document is hidden from before its first chunk
 *       is written until its new version is published whole</li>
 *   <li>No mix of versions: a query sees the whole previous version, nothing of the document
 *       while it is rewritten, or the whole new version</li>
 *   <li>Consistent snapshots across concurrent queries</li>
 *   <li>Epoch isolation for in-flight ingestion</li>
 * </ul>
 * <p>{@link DocumentReindexStep} sets out the order that achieves it.</p>
 *
 * <h2>What this class itself does</h2>
 * <p>It owns the wiring and the entry points, and hands each phase to the collaborator that
 * owns it: {@link SourceReindexPass} for one pass over a source's change set,
 * {@link DocumentReindexStep} for one document end to end, {@link RollingReplacement} for the
 * request for a replacement segment, and {@link ReindexLexicalIndex} and
 * {@link ReindexRollback} for the two things a reindex has to be able to undo. Each of those carries the reasoning for its own
 * phase, and this one carries none of it twice.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Chunk token counts: 512±10% tokens as per TextChunker specification</li>
 *   <li>Embedding dimensions: configured dimension (typically 1536 for OpenAI ada-002), or
 *       none at all on a lexical-only node</li>
 *   <li>Chunk IDs: unique, format "{documentId}:chunk:{index}"</li>
 *   <li>Old chunks are tombstoned before new chunks are visible, while the document is
 *       hidden</li>
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
     * <p>The storage tombstones retire the document's nodes too, once the integration worker
     * replays them; until then a query sets the nodes aside only as far as its bounded tail scan
     * reaches. Retiring here is what makes the removal hold however far integration has fallen
     * behind.</p>
     */
    private volatile DocumentErasureService.VectorEraser vectorEraser;

    /**
     * Tells the graph about every version this reindex publishes and every document it removes.
     *
     * <p>Its own until {@link IngestionOrchestrator#shareGraphReporting} hands over the
     * orchestrator's, which it must before any run whose publications a graph should see: a
     * reporter of its own has no sink, so nothing is reported through it, and a reporter of its
     * own beside the orchestrator's could not order a re-crawl's version of a document against a
     * re-post's. See {@link GraphReporter}.</p>
     */
    private volatile GraphReporter graphReporter = new GraphReporter();

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

        /**
         * Reports what the run has counted against its documents so far.
         *
         * <p>Called once before the first document, when the documents that could not be
         * checked are known, and once after each document with {@link #documentsDone}. So a run
         * that is stopped, or breaks, partway has already said what it failed on and passed over
         * up to then, and a caller does not have to wait for a result the run never
         * returns.</p>
         *
         * <p>Must not throw, and must not block, for the reason {@link #documentsDone}
         * gives.</p>
         *
         * @param documentsFailed  Documents the run could not check, fetch, read or write
         * @param documentsNotText Documents it passed over because they are not text
         */
        default void documentsCounted(long documentsFailed, long documentsNotText) {
        }

        /**
         * Reports the documents the run's source could not say had changed or not.
         *
         * <p>Called once, before the first document. Each is kept as it is indexed and counted
         * among the failures {@link #documentsCounted} reports.</p>
         *
         * <p>Must not throw, and must not block, for the reason {@link #documentsDone}
         * gives.</p>
         *
         * @param documentsUnchecked How many there were
         * @param sample             The first of them, at most
         *                           {@link ReindexResult#UNCHECKED_SAMPLE_SIZE}, in crawl order
         */
        default void documentsUnchecked(long documentsUnchecked, List<String> sample) {
        }

        /**
         * Reports that the run's listing of its source had gaps, and what that kept.
         *
         * <p>Called once, before the first document, and only for a listing with gaps; see
         * {@link Source#getListingGaps()}. The documents the listing missed are kept as they are
         * indexed rather than removed, and are not failures: nothing was asked of them.</p>
         *
         * <p>Must not throw, and must not block, for the reason {@link #documentsDone}
         * gives.</p>
         *
         * @param gaps                 Where the listing could not see
         * @param documentsKeptUnlisted Registered documents it did not list, kept
         */
        default void listingIncomplete(ListingGaps gaps, long documentsKeptUnlisted) {
        }
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
     *   <li>Every chunk durable in the write log before the epoch that publishes it</li>
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
     * Creates a new DocumentReindexer that can be asked for a rolling replacement.
     *
     * <p>The catalog is what a rolling run asks for a replacement segment, and the visibility
     * tracker is what publishes the run's writes. What a run does with them - and why on a
     * hash-routed engine it always rebuilds in place - is set out on {@link RollingReplacement}.</p>
     *
     * @param chunker Text chunker for splitting documents
     * @param llmClient Embeds the chunks this reindexer writes, or null on a node that
     *                  indexes its corpus lexically and has no embedding endpoint
     * @param upsertHandler Handler for tombstoning old chunks
     * @param registry Document registry for metadata tracking
     * @param offsetAwareWriter Storage writer that returns write log offsets
     * @param batchPublisher Publisher for atomic batch publication
     * @param segmentCatalog Segment catalog for managing segment metadata
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
                            VisibilityTracker visibilityTracker) {
        this.chunker = Objects.requireNonNull(chunker, "chunker must not be null");
        this.llmClient = llmClient;
        this.upsertHandler = Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.storageWriter = (chunkId, content, embedding) -> offsetAwareWriter.writeChunkWithOffset(chunkId, content, embedding) >= 0;
        this.offsetAwareWriter = Objects.requireNonNull(offsetAwareWriter, "offsetAwareWriter must not be null");
        this.batchPublisher = Objects.requireNonNull(batchPublisher, "batchPublisher must not be null");
        this.segmentCatalog = Objects.requireNonNull(segmentCatalog, "segmentCatalog must not be null");
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
     * Sets how long a document a reindex in progress has republished may wait to become
     * lexically searchable.
     *
     * <p>The interval ingestion refreshes on, {@code excalibur.ingest.lexical-refresh-interval-ms},
     * so a re-crawl publishes its documents to BM25 on the same schedule as the crawl that first
     * indexed them; {@link IngestionOrchestrator#DEFAULT_LEXICAL_REFRESH_INTERVAL_MS} until it is
     * set. A run also refreshes when it ends or is stopped.</p>
     *
     * @param intervalMs The interval in milliseconds
     * @throws IllegalArgumentException if the interval is not positive
     */
    public void setLexicalRefreshIntervalMs(long intervalMs) {
        lexical.refreshEvery(intervalMs);
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
     * Reports through the given reporter from now on.
     *
     * @param graphReporter The reporter ingestion reports through
     * @throws NullPointerException if graphReporter is null
     */
    void reportGraphThrough(GraphReporter graphReporter) {
        this.graphReporter = Objects.requireNonNull(graphReporter, "graphReporter must not be null");
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
     * <p>That is, whether it has a segment catalog to ask for a replacement segment with. What
     * comes of asking - and why the answer is always to rebuild in place - is set out on
     * {@link RollingReplacement}.</p>
     *
     * @return true if rolling replacement is enabled
     */
    public boolean hasRollingReplacement() {
        return segmentCatalog != null && visibilityTracker != null;
    }

    /**
     * Re-indexes all documents from a source with zero-downtime rolling replacement.
     *
     * <p>Why a hash-routed engine offers no writable target, what the run does instead, and why
     * a writable one would be refused rather than filled, are all set out on
     * {@link RollingReplacement}. In short: the run rebuilds in place, the old segments stay
     * exactly as they were, and the returned segment ID is {@code -1}.</p>
     *
     * <p><strong>Prerequisites:</strong> This method requires rolling replacement
     * support to be enabled (see
     * {@link #DocumentReindexer(TextChunker, LLMHttpClient, UpsertHandler, DocumentRegistry, OffsetAwareStorageWriter, DocumentBatchPublisher, SegmentCatalog, VisibilityTracker)}).
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
     * <p>The progress a caller sees is the ordinary reindex's, reported as it goes.</p>
     *
     * @param source     The source to re-index
     * @param volumePath Where the new segment file should be created
     * @param progress   Told how many documents the run has finished with
     * @return Result containing counts, new segment ID, and any errors
     * @throws SourceException       if crawling or fetching fails catastrophically
     * @throws IllegalStateException if the writer reports the created segment writable
     * @throws NullPointerException  if any argument is null
     * @see RollingReplacement
     */
    public RollingReindexResult reindexWithRollingReplacement(Source source, Path volumePath,
                                                              ReindexProgress progress)
            throws SourceException {
        return reindexWithRollingReplacement(source, volumePath, progress, new StopSignal());
    }

    /**
     * Re-indexes by rolling replacement, reporting progress as it goes, until asked to stop.
     *
     * <p>How the stop is read, and why it is never delivered as an interrupt, is set out on
     * {@link ReindexCancellation}.</p>
     *
     * @param source     The source to re-index
     * @param volumePath Where the new segment file should be created
     * @param progress   Told how many documents the run has finished with
     * @param stop       Asks the run to stop at its next document boundary
     * @return Result containing counts, new segment ID, and any errors
     * @throws SourceException           if crawling or fetching fails catastrophically
     * @throws IllegalStateException     if the writer reports the created segment writable
     * @throws NullPointerException      if any argument is null
     * @throws ReindexCancelledException if the run was asked to stop
     * @see RollingReplacement
     */
    public RollingReindexResult reindexWithRollingReplacement(Source source, Path volumePath,
                                                              ReindexProgress progress,
                                                              StopSignal stop)
            throws SourceException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(volumePath, "volumePath must not be null");
        Objects.requireNonNull(progress, "progress must not be null");
        Objects.requireNonNull(stop, "stop must not be null");

        // Fall back to regular reindex if rolling replacement not configured
        if (!hasRollingReplacement()) {
            LOG.warn("Rolling replacement not configured for source {}, falling back to regular reindex",
                source.getSourceId());
            ReindexResult basicResult = reindexAll(source, progress, stop);
            return new RollingReindexResult(basicResult, -1, List.of());
        }

        return new RollingReplacement(segmentCatalog, visibilityTracker, offsetAwareWriter,
            (routedSource, routedProgress) -> reindexAll(routedSource, routedProgress, stop))
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
        return reindexAll(source, progress, new StopSignal());
    }

    /**
     * Re-indexes all changed and new documents from a source until asked to stop.
     *
     * <p>The stop is read between documents, never inside one, and is never delivered as an
     * interrupt; see {@link ReindexCancellation}. An interrupt that reaches the run's thread
     * anyway is taken as a stop at the next checkpoint - but one that lands during a write has
     * already closed the channel it was writing through, which is why a caller that means to
     * cancel the run requests {@code stop} instead.</p>
     *
     * @param source   The source to re-index
     * @param progress Told how many documents the run has finished with, as it finishes with
     *                 them
     * @param stop     Asks the run to stop at its next document boundary
     * @return Result containing counts and any errors
     * @throws SourceException           if crawling or fetching fails catastrophically
     * @throws NullPointerException      if any argument is null
     * @throws ReindexCancelledException if the run was asked to stop; what it finished before
     *                                   then is written out first
     * @see SourceReindexPass
     */
    public ReindexResult reindexAll(Source source, ReindexProgress progress, StopSignal stop)
            throws SourceException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(progress, "progress must not be null");
        Objects.requireNonNull(stop, "stop must not be null");

        return pass(this::reindexDocument).run(source, progress, stop);
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
     * </ul>
     *
     * @param sourceId Source identifier
     * @param documentId Document identifier
     * @param fetched The fetched document content
     * @return Result of re-indexing this document
     * @throws IllegalArgumentException if the document exceeds the per-document limits
     * @see DocumentReindexStep
     */
    public DocumentResult reindexDocument(String sourceId, String documentId, FetchedDocument fetched) {
        return reindexDocument(sourceId, documentId, fetched, new StopSignal());
    }

    /**
     * As {@link #reindexDocument(String, String, FetchedDocument)}, within a run that may be
     * asked to stop: the stop may cut the document's embedding requests short, which leaves it
     * on its previous version, and never interrupts its writes.
     */
    private DocumentResult reindexDocument(String sourceId, String documentId,
                                           FetchedDocument fetched, StopSignal stop) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(fetched, "fetched must not be null");

        DocumentReindexStep step = hasAtomicPublication()
            ? step(new DocumentReindexStep.AtomicTarget(offsetAwareWriter, batchPublisher))
            : DocumentReindexStep.writingDirectly(chunker, llmClient, registry, upsertHandler,
                lexical, vectorEraser, graphReporter, storageWriter);
        return step.reindex(sourceId, documentId, fetched, stop);
    }

    /**
     * Builds the pass that walks a source's change set.
     *
     * <p>Built per run rather than held as a field, for the reason the rollback is: the vector
     * eraser, the lexical index and the graph reporter are wired after construction, and a pass
     * built once at construction would go on reindexing as though none had ever been
     * attached.</p>
     */
    private SourceReindexPass pass(SourceReindexPass.DocumentStep documentStep) {
        return new SourceReindexPass(registry, upsertHandler, lexical, vectorEraser,
            graphReporter, batchPublisher, documentStep);
    }

    /** Builds the per-document step for a run that publishes atomically. */
    private DocumentReindexStep step(DocumentReindexStep.AtomicTarget target) {
        return DocumentReindexStep.publishing(chunker, llmClient, registry, upsertHandler, lexical,
            vectorEraser, graphReporter, target);
    }

    /**
     * Result of re-indexing a single document.
     */
    public static final class DocumentResult {

        /**
         * Why a document produced nothing without having failed.
         *
         * <p>Typed rather than read back out of the message, because the reasons are counted
         * differently. Only {@link #NOT_TEXT} is what {@code entries_skipped} reports, on a
         * re-crawl exactly as on the first crawl: a directory holding photographs beside its
         * documents is worth being told about, and an empty file is not - the first crawl never
         * counted one, and a count that moved between the two runs of the same unchanged source
         * would say the source had changed when it had not. Matching on the wording instead would
         * let a reworded message silently change what an operator is shown.</p>
         */
        public enum SkipReason {

            /** The bytes are not text in the encoding the source declared them to be in. */
            NOT_TEXT("Not text"),

            /** The source handed back no bytes at all. */
            EMPTY_CONTENT("Empty content"),

            /** The document decoded to text the chunker found nothing to index in. */
            NO_CHUNKS("No chunks produced");

            private final String description;

            SkipReason(String description) {
                this.description = description;
            }

            /**
             * The reason as a person reads it in a log line.
             *
             * @return A short phrase
             */
            public String description() {
                return description;
            }
        }

        private final String documentId;
        private final int totalChunks;
        private final int successfulChunks;
        private final int failedChunks;
        private final long elapsedMillis;
        private final String error;
        private final List<String> chunkIds;
        private final SkipReason skipReason;
        private final int chunksRetired;
        private final boolean keyTaken;

        public DocumentResult(String documentId, int totalChunks, int successfulChunks, 
                            int failedChunks, long elapsedMillis, String error) {
            this(documentId, totalChunks, successfulChunks, failedChunks, elapsedMillis, error, List.of());
        }

        public DocumentResult(String documentId, int totalChunks, int successfulChunks,
                            int failedChunks, long elapsedMillis, String error, List<String> chunkIds) {
            this(documentId, totalChunks, successfulChunks, failedChunks, elapsedMillis, error,
                chunkIds, null, 0, false);
        }

        private DocumentResult(String documentId, int totalChunks, int successfulChunks,
                               int failedChunks, long elapsedMillis, String error,
                               List<String> chunkIds, SkipReason skipReason,
                               int chunksRetired, boolean keyTaken) {
            this.documentId = documentId;
            this.totalChunks = totalChunks;
            this.successfulChunks = successfulChunks;
            this.failedChunks = failedChunks;
            this.elapsedMillis = elapsedMillis;
            this.error = error;
            this.chunkIds = chunkIds != null ? List.copyOf(chunkIds) : List.of();
            this.skipReason = skipReason;
            this.chunksRetired = chunksRetired;
            this.keyTaken = keyTaken;
        }

        /**
         * A document that produced nothing and failed nothing.
         *
         * <p>It carries its reason's description as its message, so a log line about it says
         * why, and the reason itself, so the run can count it without reading that message.</p>
         *
         * @param documentId    The document
         * @param reason        Why it produced nothing
         * @param elapsedMillis How long deciding that took
         * @return The result
         * @throws NullPointerException if reason is null
         */
        public static DocumentResult skipped(String documentId, SkipReason reason,
                                             long elapsedMillis) {
            Objects.requireNonNull(reason, "reason must not be null");
            return new DocumentResult(documentId, 0, 0, 0, elapsedMillis, reason.description(),
                List.of(), reason, 0, false);
        }

        /**
         * Why this document was skipped.
         *
         * @return The reason, or null when it was not skipped
         */
        public SkipReason getSkipReason() {
            return skipReason;
        }

        /**
         * A copy of this result recording how many chunks of the document's previous version were
         * retired when this version was published.
         *
         * @param retired How many chunks storage released
         * @return The copy
         * @throws IllegalArgumentException if retired is negative
         */
        public DocumentResult withChunksRetired(int retired) {
            if (retired < 0) {
                throw new IllegalArgumentException("retired must be non-negative: " + retired);
            }
            return new DocumentResult(documentId, totalChunks, successfulChunks, failedChunks,
                elapsedMillis, error, chunkIds, skipReason, retired, keyTaken);
        }

        /**
         * A copy of this failure marking it as refused because a document of another source
         * holds the document's key.
         *
         * @return The copy
         */
        public DocumentResult asKeyTaken() {
            return new DocumentResult(documentId, totalChunks, successfulChunks, failedChunks,
                elapsedMillis, error, chunkIds, skipReason, chunksRetired, true);
        }

        /**
         * Whether the document was refused because a document of another source holds its key -
         * a failure no retry cures, since the cure is a source id that does not collide.
         *
         * @return true if it was
         */
        public boolean isKeyTaken() {
            return keyTaken;
        }

        /**
         * How many chunks of the document's previous version its publication retired: those the
         * previous version held past the end of this one.
         *
         * @return The count, zero when there was no previous version or it was no longer
         */
        public int getChunksRetired() {
            return chunksRetired;
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
         * <p>An empty file, one the chunker finds no text in, or one that is not text at all
         * carries a reason so the run can account for it - and that reason is not a failure.
         * Folding it in with the failures makes a run that indexed everything it could report
         * itself as failed, and puts "Empty content" in front of an operator as the thing to
         * investigate.</p>
         *
         * @return true if the document was skipped rather than failed
         */
        public boolean wasSkipped() {
            return skipReason != null;
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

        /**
         * How many of the documents that could not be checked a result names.
         *
         * <p>Enough to tell one host that is down from a scatter of timeouts, and to try one
         * of them by hand; bounded because a source that is down entirely fails every document
         * it lists. The count is always whole, and each failure is in the log with its
         * reason.</p>
         */
        public static final int UNCHECKED_SAMPLE_SIZE = 10;

        private final String sourceId;
        private final int documentsIndexed;
        private final int documentsTombstoned;
        private final int chunksCreated;
        private final int chunksTombstoned;
        private final int chunksFailed;
        private final int documentsSkipped;
        private final int documentsNotText;
        private final int documentsFailed;
        private final int documentsKeyTaken;
        private final int documentsUnchecked;
        private final List<String> uncheckedSample;
        private final int documentsUnchanged;
        private final int documentsKeptUnlisted;
        private final ListingGaps listingGaps;
        private final long elapsedMillis;
        private final List<OperationError> errors;

        private ReindexResult(Builder builder) {
            this.sourceId = builder.sourceId;
            this.documentsIndexed = builder.documentsIndexed;
            this.documentsSkipped = builder.documentsSkipped;
            this.documentsNotText = builder.documentsNotText;
            this.documentsFailed = builder.documentsFailed;
            this.documentsKeyTaken = builder.documentsKeyTaken;
            this.documentsUnchecked = builder.documentsUnchecked;
            this.uncheckedSample = List.copyOf(builder.uncheckedSample);
            this.documentsUnchanged = builder.documentsUnchanged;
            this.documentsKeptUnlisted = builder.documentsKeptUnlisted;
            this.listingGaps = builder.listingGaps;
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

        /**
         * Documents skipped because they are not text.
         *
         * <p>The subset of {@link #getDocumentsSkipped()} that {@code entries_skipped} reports,
         * which is the same set the first crawl of a source counts there. Empty documents are
         * skipped too and are not in it; see {@link DocumentResult.SkipReason}.</p>
         *
         * @return How many documents were passed over as not text
         */
        public int getDocumentsNotText() {
            return documentsNotText;
        }

        /**
         * Documents the run could not index or could not check.
         *
         * <p>Each is one document, however many chunks it has: one whose fetch or read failed,
         * one over the per-document limits, one whose chunks could not all be embedded and
         * written, and one whose source could not say whether it had changed. Every such
         * document is also among {@link #getErrors()}, which may hold more - a removal that
         * could not tombstone a chunk is an error of the run and not a document it failed
         * on.</p>
         *
         * @return How many documents failed
         */
        public int getDocumentsFailed() {
            return documentsFailed;
        }

        /**
         * Of the documents that failed, those refused because a document of another source holds
         * their key; see {@link DocumentResult#isKeyTaken()}.
         *
         * @return How many
         */
        public int getDocumentsKeyTaken() {
            return documentsKeyTaken;
        }

        /**
         * Documents whose source could not say whether they had changed.
         *
         * <p>A subset of {@link #getDocumentsFailed()}. Their indexed versions were kept.</p>
         *
         * @return How many documents could not be checked
         */
        public int getDocumentsUnchecked() {
            return documentsUnchecked;
        }

        /**
         * The first documents that could not be checked, by id.
         *
         * @return At most {@link #UNCHECKED_SAMPLE_SIZE} ids, in crawl order
         */
        public List<String> getUncheckedSample() {
            return uncheckedSample;
        }

        /**
         * Registered documents the source confirmed are still current.
         *
         * <p>Neither indexed nor failed, and still documents the run accounted for: a re-crawl
         * of a source that has not moved finds every document here, and that is a run that
         * worked.</p>
         *
         * @return How many documents were confirmed unchanged
         */
        public int getDocumentsUnchanged() {
            return documentsUnchanged;
        }

        /**
         * Registered documents the run's listing did not include, kept because it had gaps.
         *
         * <p>Neither failed nor unchanged - nothing was asked of them - and never removed: a
         * listing that could not see everything cannot say they are gone. Zero whenever
         * {@link #getListingGaps()} is complete.</p>
         *
         * @return How many documents were kept unlisted
         */
        public int getDocumentsKeptUnlisted() {
            return documentsKeptUnlisted;
        }

        /**
         * Where the run's listing of its source could not see.
         *
         * @return The gaps, {@link ListingGaps#NONE} for a complete listing
         */
        public ListingGaps getListingGaps() {
            return listingGaps;
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
            return String.format("ReindexResult[source=%s, indexed=%d, skipped=%d, failed=%d, " +
                "unchecked=%d, unchanged=%d, keptUnlisted=%d, tombstoned=%d, chunksCreated=%d, " +
                "chunksTombstoned=%d, chunksFailed=%d, errors=%d, elapsed=%dms]",
                sourceId, documentsIndexed, documentsSkipped, documentsFailed, documentsUnchecked,
                documentsUnchanged, documentsKeptUnlisted, documentsTombstoned, chunksCreated,
                chunksTombstoned, chunksFailed, errors.size(), elapsedMillis);
        }

        public static Builder builder(String sourceId) {
            return new Builder(sourceId);
        }

        public static final class Builder {
            private final String sourceId;
            private final long startTime = System.currentTimeMillis();
            private int documentsSkipped = 0;
            private int documentsNotText = 0;
            private int documentsIndexed = 0;
            private int documentsTombstoned = 0;
            private int chunksCreated = 0;
            private int chunksTombstoned = 0;
            private int chunksFailed = 0;
            private int documentsFailed = 0;
            private int documentsKeyTaken = 0;
            private int documentsUnchecked = 0;
            private final List<String> uncheckedSample = new ArrayList<>();
            private int documentsUnchanged = 0;
            private int documentsKeptUnlisted = 0;
            private ListingGaps listingGaps = ListingGaps.NONE;
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
             * Sets how many registered documents the source confirmed are current.
             *
             * @param count The count
             * @return This builder
             */
            public Builder documentsUnchanged(int count) {
                this.documentsUnchanged = count;
                return this;
            }

            /**
             * Sets where the run's listing could not see, and how many registered documents it
             * missed and kept for that reason.
             *
             * @param gaps         What the source reported
             * @param keptUnlisted The documents kept
             * @return This builder
             * @throws NullPointerException if gaps is null
             */
            public Builder listingIncomplete(ListingGaps gaps, int keptUnlisted) {
                this.listingGaps = Objects.requireNonNull(gaps, "gaps must not be null");
                this.documentsKeptUnlisted = keptUnlisted;
                return this;
            }

            /**
             * How many documents have failed so far; see
             * {@link ReindexResult#getDocumentsFailed()}.
             *
             * @return The count
             */
            public int getDocumentsFailed() {
                return documentsFailed;
            }

            /**
             * How many documents have been passed over as not text so far.
             *
             * @return The count
             */
            public int getDocumentsNotText() {
                return documentsNotText;
            }

            /**
             * How many documents could not be checked.
             *
             * @return The count
             */
            public int getDocumentsUnchecked() {
                return documentsUnchecked;
            }

            /**
             * The first documents that could not be checked.
             *
             * @return An unmodifiable copy, at most {@link #UNCHECKED_SAMPLE_SIZE} ids
             */
            public List<String> getUncheckedSample() {
                return List.copyOf(uncheckedSample);
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
                    if (docResult.getSkipReason() == DocumentResult.SkipReason.NOT_TEXT) {
                        documentsNotText++;
                    }
                } else if (docResult.hasError()) {
                    if (docResult.isKeyTaken()) {
                        documentsKeyTaken++;
                    }
                    addDocumentFailure("document", docResult.getDocumentId(), docResult.getError());
                }
                return this;
            }

            /**
             * Records a document the run could not index: its fetch, its read or its write
             * failed.
             *
             * @param component Where it failed, for the log
             * @param id        The document
             * @param message   Why, for the log
             * @return This builder
             */
            public Builder addDocumentFailure(String component, String id, String message) {
                documentsFailed++;
                return addError(component, id, message);
            }

            /**
             * Records a registered document its source could not say had changed or not.
             *
             * <p>A failure like any fetch that failed, and one that leaves the indexed version
             * where it was. Only the first {@link #UNCHECKED_SAMPLE_SIZE} are named.</p>
             *
             * @param id The document
             * @return This builder
             */
            public Builder addUnchecked(String id) {
                documentsUnchecked++;
                if (uncheckedSample.size() < UNCHECKED_SAMPLE_SIZE) {
                    uncheckedSample.add(id);
                }
                return addDocumentFailure("check", id, "Could not check whether document " + id
                    + " changed; its indexed version is kept");
            }

            /**
             * Records an error of the run that is not a document it failed on, such as a chunk
             * a removal could not tombstone.
             *
             * @param component Where it failed, for the log
             * @param id        What failed
             * @param message   Why, for the log
             * @return This builder
             */
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