package excalibur.ingest.pipeline;

import io.excalibur.core.text.ChunkIdentifiers;
import excalibur.ingest.lexical.ChunkIndex;
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
import io.excalibur.store.LifecycleState;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.SegmentLifecycleManager;
import io.excalibur.store.VisibilityTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
 * @see DocumentBatchPublisher
 */
public class DocumentReindexer {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentReindexer.class);
    private static final int MAX_CONTENT_LENGTH = 100 * 1024 * 1024;

    private final TextChunker chunker;
    private final LLMHttpClient llmClient;
    private final UpsertHandler upsertHandler;
    private final DocumentRegistry registry;
    private final StorageWriter storageWriter;

    // Optional atomic publication support
    private final DocumentBatchPublisher batchPublisher;
    private final OffsetAwareStorageWriter offsetAwareWriter;

    // Optional segment lifecycle management for rolling replacement
    private final SegmentCatalog segmentCatalog;
    private final SegmentLifecycleManager lifecycleManager;
    private final VisibilityTracker visibilityTracker;

    /**
     * The lexical index a reindex has to keep in step, or null when this node keeps none.
     *
     * <p>A reindex is the one operation that removes content a query could still be matching:
     * a deleted document's chunks, and the tail chunks a shortened document no longer has. Left
     * out of this index they stay searchable under identifiers whose storage entries are gone, so
     * BM25 goes on returning documents the corpus no longer contains.</p>
     */
    private volatile ChunkIndex lexicalIndex;

    /**
     * Reads a surviving chunk's text back out of storage for the lexical index's compaction.
     *
     * <p>The index stores postings and not text, so reclaiming an erased chunk means rewriting the
     * segments around it from the text storage still holds. A reindexer that could erase but not
     * compact would meet the visibility half of the promise and never the disk half.</p>
     */
    private volatile Function<String, String> storedText;

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
     * Creates a new DocumentReindexer with basic storage writer (no atomic publication).
     *
     * <p>When using this constructor, documents are written directly to storage
     * without atomic visibility epoch advancement. Use
     * {@link #DocumentReindexer(TextChunker, LLMHttpClient, UpsertHandler, DocumentRegistry, OffsetAwareStorageWriter, DocumentBatchPublisher)}
     * for full atomic publication support.</p>
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
        this.batchPublisher = null;
        this.offsetAwareWriter = null;
        this.segmentCatalog = null;
        this.lifecycleManager = null;
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
     * @param llmClient LLM client for generating embeddings
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
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.upsertHandler = Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.storageWriter = (chunkId, content, embedding) -> offsetAwareWriter.writeChunkWithOffset(chunkId, content, embedding) >= 0;
        this.offsetAwareWriter = Objects.requireNonNull(offsetAwareWriter, "offsetAwareWriter must not be null");
        this.batchPublisher = Objects.requireNonNull(batchPublisher, "batchPublisher must not be null");
        this.segmentCatalog = null;
        this.lifecycleManager = null;
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
     * @param llmClient LLM client for generating embeddings
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
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.upsertHandler = Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.storageWriter = (chunkId, content, embedding) -> offsetAwareWriter.writeChunkWithOffset(chunkId, content, embedding) >= 0;
        this.offsetAwareWriter = Objects.requireNonNull(offsetAwareWriter, "offsetAwareWriter must not be null");
        this.batchPublisher = Objects.requireNonNull(batchPublisher, "batchPublisher must not be null");
        this.segmentCatalog = Objects.requireNonNull(segmentCatalog, "segmentCatalog must not be null");
        this.lifecycleManager = Objects.requireNonNull(lifecycleManager, "lifecycleManager must not be null");
        this.visibilityTracker = Objects.requireNonNull(visibilityTracker, "visibilityTracker must not be null");

        LOG.info("DocumentReindexer configured with rolling replacement support (segment lifecycle enabled)");
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
     *                     compaction rebuilds surviving segments from. May be null, in which case
     *                     erased chunks stay on disk until something that can supply text compacts
     * @throws IllegalArgumentException if an index is given without a way to read text back
     */
    public void setLexicalIndex(ChunkIndex lexicalIndex, Function<String, String> storedText) {
        if (lexicalIndex != null && storedText == null) {
            throw new IllegalArgumentException(
                "A lexical index needs a text supplier: without one its erased chunks can never "
                    + "be reclaimed, which is the half of erasure that is a promise");
        }
        this.lexicalIndex = lexicalIndex;
        this.storedText = storedText;
        LOG.info("Reindex lexical index {}", lexicalIndex == null
            ? "disabled" : "enabled (" + lexicalIndex.engine() + " engine)");
    }

    /**
     * Indexes a published document's chunks.
     *
     * <p>Called once the document is published rather than as each chunk is written: a reindex
     * that fails partway leaves the previous version in place, and indexing the fragments it did
     * write would make half of the new version searchable beside all of the old one.</p>
     */
    private void indexLexically(List<String> chunkIds, List<TextChunk> chunks) {
        ChunkIndex target = this.lexicalIndex;
        if (target == null || chunkIds.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunkIds.size() && i < chunks.size(); i++) {
            try {
                target.add(chunkIds.get(i), chunks.get(i).getText());
            } catch (IOException e) {
                // The chunk is in storage and the document is published; losing one chunk from
                // BM25 costs recall on that chunk, where failing here would cost the document.
                LOG.warn("Chunk {} reindexed but not lexically indexed: {}",
                    chunkIds.get(i), e.getMessage());
            }
        }
    }

    /**
     * Takes retired chunks out of the lexical index.
     *
     * <p>Storage has tombstoned them, so a search still returning them would report hits whose
     * text nothing can resolve - and for a deleted document, would go on answering with content
     * its owner asked to have removed.</p>
     *
     * @return How many the index found and removed
     */
    private int unindexLexically(Collection<String> chunkIds) {
        ChunkIndex target = this.lexicalIndex;
        if (target == null || chunkIds.isEmpty()) {
            return 0;
        }
        try {
            return target.erase(new LinkedHashSet<>(chunkIds));
        } catch (IOException e) {
            LOG.warn("{} retired chunk(s) remain lexically searchable: {}",
                chunkIds.size(), e.getMessage());
            return 0;
        }
    }

    /** The chunk ids of every document this change set says has been deleted outright. */
    private static Set<String> deletedChunkIds(ChangeSet changeSet) {
        Set<String> ids = new LinkedHashSet<>();
        for (DocumentMetadata deleted : changeSet.getDeletedDocuments()) {
            ids.addAll(deleted.getChunkIds());
        }
        return ids;
    }

    /**
     * Writes the registry, so what this reindex changed survives a restart.
     *
     * <p>A reindex is the operation whose whole input is the registry's previous contents: it
     * compares what a source lists now against what was recorded last time. Not writing the result
     * would make the next run repeat this one.</p>
     */
    private void persistRegistry() {
        try {
            registry.persist();
        } catch (IOException e) {
            LOG.warn("Document registry not persisted; the next reindex would repeat this one: {}",
                e.getMessage());
        }
    }

    /**
     * Publishes this run's lexical writes and reclaims what it erased, if that is now due.
     *
     * <p>Both at the end of the run for the same reason the ingestion pipeline does it there: a
     * refresh is what makes a batch searchable at one instant, and a run that has just erased
     * documents is the moment there is something to reclaim.</p>
     */
    private void settleLexicalIndex() {
        ChunkIndex target = this.lexicalIndex;
        if (target == null) {
            return;
        }
        try {
            target.refresh();
        } catch (IOException e) {
            LOG.warn("Lexical index not refreshed; this reindex stays unsearchable until the "
                + "next refresh: {}", e.getMessage());
            return;
        }
        Function<String, String> text = this.storedText;
        if (text == null) {
            return;
        }
        try {
            long started = System.currentTimeMillis();
            io.excalibur.retrieval.lexical.native_.CompactionResult result =
                target.compactIfDue(text);
            if (result.performed()) {
                LOG.info("Lexical compaction after reindex: {} segment(s) rewritten to {}, {} "
                    + "chunk(s) physically removed in {}ms ({})", result.segmentsRewritten(),
                    result.segmentsProduced(), result.documentsRemoved(),
                    System.currentTimeMillis() - started, result.reason());
            }
        } catch (IOException e) {
            LOG.warn("Lexical compaction did not run; erased chunks stay on disk until the next "
                + "attempt: {}", e.getMessage());
        }
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
        return segmentCatalog != null && lifecycleManager != null && visibilityTracker != null;
    }

    /**
     * Re-indexes all documents from a source with zero-downtime rolling replacement.
     *
     * <p>This method implements the complete rolling replacement workflow:</p>
     * <ol>
     *   <li>Creates a new segment in BOOTSTRAPPING state for the source</li>
     *   <li>Transitions the segment to INDEXING (becomes queryable)</li>
     *   <li>Re-indexes all documents into the new segment</li>
     *   <li>Transitions the segment through WARMING → ACTIVE</li>
     *   <li>Tombstones old segments for the same source</li>
     * </ol>
     *
     * <p><strong>Zero-Downtime:</strong> Old segments stay queryable for the whole run and are
     * tombstoned once the new segment reaches WARMING, not ACTIVE - the WARMING to ACTIVE
     * transition is made asynchronously by the warming phase worker after PQ training, and this
     * method does not wait for it. WARMING is a queryable state, so a query still reaches the new
     * data; a query that opts out of partial segments does not, and sees neither the old segments
     * nor the new one until training finishes.</p>
     *
     * <p><strong>Requires a writable target, and a hash-routed engine never offers one.</strong>
     * The segment created here is a catalog entry, and the catalog draws IDs from a counter it
     * advances past every ID it issues - the indices a split registers included - so a created
     * entry always names an index the engine holds no segment of its own at. Before the first
     * split that index is past the routing directory and resolves to nothing; after one it
     * resolves to a buddy pointer, and filling it would write this source's data into a segment
     * routing unrelated keys and then retire the source's real segments in favour of it. Neither
     * is a target, so the steps above do not currently run on this engine.</p>
     *
     * <p>That is detected before any document is fetched, and the run falls back to re-indexing
     * through the routing directory: the documents are re-indexed, the created entry is
     * tombstoned, no segment is replaced and the returned segment ID is {@code -1}. Rolling
     * replacement becomes reachable when the engine can hand out a segment that is not a routing
     * target, which it cannot today.</p>
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
     */
    public RollingReindexResult reindexWithRollingReplacement(Source source, Path volumePath) throws SourceException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(volumePath, "volumePath must not be null");

        String sourceId = source.getSourceId();
        long startTime = System.currentTimeMillis();

        // Fall back to regular reindex if rolling replacement not configured
        if (!hasRollingReplacement()) {
            LOG.warn("Rolling replacement not configured for source {}, falling back to regular reindex", sourceId);
            ReindexResult basicResult = reindexAll(source);
            return new RollingReindexResult(basicResult, -1, List.of());
        }

        LOG.info("Starting rolling reindex for source: {} with volume: {}", sourceId, volumePath);

        // Step 1: Create new segment in BOOTSTRAPPING state
        SegmentCatalog.SegmentMetadata newSegment;
        try {
            newSegment = segmentCatalog.create(sourceId, volumePath);
            LOG.info("Created new segment {} in BOOTSTRAPPING state for source {}", newSegment.getSegmentId(), sourceId);
        } catch (Exception e) {
            LOG.error("Failed to create new segment for source {}: {}", sourceId, e.getMessage());
            throw new SourceException("Failed to create new segment: " + e.getMessage(), e);
        }

        long newSegmentId = newSegment.getSegmentId();

        List<Long> oldSegmentIds = new ArrayList<>();

        try {
            // Step 2: Find old segments for this source (to tombstone later)
            List<SegmentCatalog.SegmentMetadata> existingSegments = segmentCatalog.getSegmentsBySource(sourceId);
            for (SegmentCatalog.SegmentMetadata seg : existingSegments) {
                if (seg.getSegmentId() != newSegmentId &&
                    seg.getLifecycleState() != LifecycleState.TOMBSTONED &&
                    seg.getLifecycleState() != LifecycleState.PURGED) {
                    oldSegmentIds.add(seg.getSegmentId());
                }
            }
            LOG.info("Found {} existing segments for source {} to tombstone after reindex", oldSegmentIds.size(), sourceId);

            // Step 3: Transition new segment to INDEXING (makes it queryable)
            segmentCatalog.beginIndexing(newSegmentId);
            LOG.info("Segment {} transitioned from BOOTSTRAPPING to INDEXING", newSegmentId);

            // The catalog mints IDs from its own counter, which continues past the storage
            // engine's routing width, so a created segment routinely names no segment the engine
            // can write to. Discovering that one chunk at a time costs a crawl, a chunking pass
            // and an embedding call for every chunk of every document in the source before the
            // first write is even attempted, and the run then ends having written nothing. Ask
            // once, and re-index the way the engine supports rather than an expensive way it
            // does not. Checked after the segment is INDEXING because that is the state it has
            // to be in to be discarded.
            if (!offsetAwareWriter.canWriteTo(newSegmentId)) {
                LOG.warn("Segment {} created for source {} is not a write target for the storage "
                    + "engine; re-indexing through the routing directory instead of replacing "
                    + "segments. No old segment is retired.", newSegmentId, sourceId);
                segmentCatalog.tombstone(newSegmentId, visibilityTracker.currentEpoch());
                ReindexResult basicResult = reindexAll(source);
                return new RollingReindexResult(basicResult, -1, List.of());
            }

            // Step 4: Create segment-targeted writers for the new segment
            // This is CRITICAL: all reindexed data must go to the NEW segment, not the old one
            OffsetAwareStorageWriter segmentWriter = offsetAwareWriter.forSegment(newSegmentId);
            DocumentBatchPublisher segmentPublisher = batchPublisher.withTargetSegment(newSegmentId);
            LOG.info("Created segment-targeted writers for segment {}", newSegmentId);

            // Step 5: Perform the actual reindexing with segment-targeted writers
            ReindexResult reindexResult = reindexAllWithSegmentWriter(source, segmentWriter, segmentPublisher);

            // The new segment is only usable if something was actually written to it. Guarding on
            // the document count alone lets a run in which every document failed fall through and
            // tombstone every populated old segment.
            if (reindexResult.hasErrors() && reindexResult.getChunksCreated() == 0) {
                // Complete failure - mark segment for cleanup
                LOG.error("Reindex failed completely for source {}, tombstoning new segment {}", sourceId, newSegmentId);
                segmentCatalog.tombstone(newSegmentId, visibilityTracker.currentEpoch());
                return new RollingReindexResult(reindexResult, newSegmentId, List.of());
            }

            // Step 6: Transition new segment to WARMING state
            // WarmingPhaseWorker will handle PQ training and the WARMING → ACTIVE transition asynchronously
            segmentCatalog.completeIndexing(newSegmentId);
            LOG.info("Segment {} transitioned from INDEXING to WARMING, awaiting PQ training by WarmingPhaseWorker", newSegmentId);

            // Note: completeWarmup() is called by WarmingPhaseWorker after PQ training completes
            // This ensures PQ codebooks are trained before the segment becomes ACTIVE

            // Step 7: Tombstone old segments for this source
            List<Long> tombstonedSegments = new ArrayList<>();
            long currentEpoch = visibilityTracker.currentEpoch();

            for (Long oldSegmentId : oldSegmentIds) {
                try {
                    segmentCatalog.tombstone(oldSegmentId, currentEpoch);
                    tombstonedSegments.add(oldSegmentId);
                    LOG.info("Tombstoned old segment {} for source {} at epoch {}", oldSegmentId, sourceId, currentEpoch);
                } catch (Exception e) {
                    LOG.warn("Failed to tombstone old segment {} for source {}: {}", oldSegmentId, sourceId, e.getMessage());
                }
            }

            // Step 8: Advance epoch to make changes visible
            visibilityTracker.advanceEpoch();

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("Rolling reindex complete for source {}: new segment={}, {} docs indexed, {} old segments tombstoned, {}ms",
                sourceId, newSegmentId, reindexResult.getDocumentsIndexed(), tombstonedSegments.size(), elapsed);

            return new RollingReindexResult(reindexResult, newSegmentId, tombstonedSegments);

        } catch (Exception e) {
            LOG.error("Rolling reindex failed for source {}: {}", sourceId, e.getMessage(), e);
            // Try to tombstone the new segment if it was created
            try {
                segmentCatalog.tombstone(newSegmentId, visibilityTracker.currentEpoch());
            } catch (Exception cleanupError) {
                LOG.warn("Failed to tombstone segment {} during cleanup: {}", newSegmentId, cleanupError.getMessage());
            }
            throw new SourceException("Rolling reindex failed: " + e.getMessage(), e);
        }
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
        Objects.requireNonNull(source, "source must not be null");
        
        String sourceId = source.getSourceId();
        LOG.info("Starting re-index for source: {}", sourceId);
        
        ChangeDetector detector = new ChangeDetector(registry);
        ChangeSet changeSet = detector.detectChanges(source);
        
        LOG.info("Change detection complete for {}: new={}, changed={}, deleted={}", 
                sourceId, changeSet.getNewCount(), changeSet.getChangedCount(), 
                changeSet.getDeletedCount());
        
        ReindexResult.Builder resultBuilder = ReindexResult.builder(sourceId);

        // Only removals are safe to apply before the replacement content exists; see
        // UpsertHandler.processDeletions. The lexical index is erased first: a document that has
        // been deleted should stop being findable before its bytes go, not after.
        unindexLexically(deletedChunkIds(changeSet));
        UpsertHandler.UpsertResult tombstoneResult = upsertHandler.processDeletions(changeSet);
        resultBuilder.documentsTombstoned(tombstoneResult.getDocumentsDeleted());
        int chunksTombstoned = tombstoneResult.getChunksTombstoned();
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
                chunksTombstoned += retireReplacedVersion(changed, docResult);
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

        resultBuilder.chunksTombstoned(chunksTombstoned);
        settleLexicalIndex();
        persistRegistry();
        ReindexResult result = resultBuilder.build();
        LOG.info("Re-index complete for {}: {} docs indexed, {} chunks created, {} errors",
                sourceId, result.getDocumentsIndexed(), result.getChunksCreated(), result.getErrorCount());

        return result;
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

        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(segmentWriter, "segmentWriter must not be null");
        Objects.requireNonNull(segmentPublisher, "segmentPublisher must not be null");

        String sourceId = source.getSourceId();
        LOG.info("Starting segment-targeted re-index for source: {}", sourceId);

        ChangeDetector detector = new ChangeDetector(registry);
        ChangeSet changeSet = detector.detectChanges(source);

        LOG.info("Change detection complete for {}: new={}, changed={}, deleted={}",
                sourceId, changeSet.getNewCount(), changeSet.getChangedCount(),
                changeSet.getDeletedCount());

        ReindexResult.Builder resultBuilder = ReindexResult.builder(sourceId);

        // Only removals are safe to apply before the replacement content exists; see
        // UpsertHandler.processDeletions. The lexical index is erased first: a document that has
        // been deleted should stop being findable before its bytes go, not after.
        unindexLexically(deletedChunkIds(changeSet));
        UpsertHandler.UpsertResult tombstoneResult = upsertHandler.processDeletions(changeSet);
        resultBuilder.documentsTombstoned(tombstoneResult.getDocumentsDeleted());
        int chunksTombstoned = tombstoneResult.getChunksTombstoned();
        tombstoneResult.getErrors().forEach(error ->
            resultBuilder.addError("tombstone", error.getChunkId(), error.getMessage()));

        // Index new documents using segment-targeted writers
        for (String newDocId : changeSet.getNewDocuments()) {
            try {
                FetchedDocument fetched = source.fetch(newDocId);
                DocumentResult docResult = reindexDocumentWithSegmentWriter(
                    sourceId, newDocId, fetched, segmentWriter, segmentPublisher);
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

        // Index changed documents using segment-targeted writers
        for (ChangeSet.ChangedDocument changed : changeSet.getChangedDocuments()) {
            try {
                FetchedDocument fetched = source.fetch(changed.getDocumentId());
                DocumentResult docResult = reindexDocumentWithSegmentWriter(
                    sourceId, changed.getDocumentId(), fetched, segmentWriter, segmentPublisher);
                resultBuilder.addDocumentResult(docResult);
                chunksTombstoned += retireReplacedVersion(changed, docResult);
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

        resultBuilder.chunksTombstoned(chunksTombstoned);
        settleLexicalIndex();
        persistRegistry();
        ReindexResult result = resultBuilder.build();
        LOG.info("Segment-targeted re-index complete for {}: {} docs indexed, {} chunks created, {} errors",
                sourceId, result.getDocumentsIndexed(), result.getChunksCreated(), result.getErrorCount());

        return result;
    }

    /**
     * Tombstones whatever remains of a changed document's previous version once its replacement
     * has been published.
     *
     * <p>Tombstoning before the replacement exists is what makes a failed re-embed destructive:
     * the old chunks are gone and nothing re-inserts them. Doing it here, after publication, means
     * a failed document simply keeps serving its previous version. Chunk ids are deterministic, so
     * the new version has already overwritten the ids it reuses; only the surplus tail is
     * removed.</p>
     *
     * @param changed   The changed document, carrying the previous version's metadata
     * @param docResult The outcome of re-indexing it
     * @return The number of obsolete chunks tombstoned
     */
    private int retireReplacedVersion(ChangeSet.ChangedDocument changed, DocumentResult docResult) {
        if (docResult.getSuccessfulChunks() == 0) {
            LOG.warn("Keeping previous version of document {}: replacement produced no chunks",
                changed.getDocumentId());
            return 0;
        }
        List<String> obsolete = new ArrayList<>(changed.getOldMetadata().getChunkIds());
        obsolete.removeAll(docResult.getChunkIds());
        unindexLexically(obsolete);
        return upsertHandler.tombstoneObsoleteChunks(
            changed.getOldMetadata().getChunkIds(), docResult.getChunkIds());
    }

    /**
     * Re-indexes a single document using segment-targeted writers.
     *
     * <p>This method is similar to {@link #reindexDocumentWithAtomicPublication} but
     * accepts segment-targeted writers, ensuring all data is written to the correct
     * segment during rolling replacement.</p>
     *
     * @param sourceId Source identifier
     * @param documentId Document identifier
     * @param fetched The fetched document content
     * @param segmentWriter Storage writer bound to the target segment
     * @param segmentPublisher Publisher bound to the target segment
     * @return Result of re-indexing this document
     */
    private DocumentResult reindexDocumentWithSegmentWriter(
            String sourceId,
            String documentId,
            FetchedDocument fetched,
            OffsetAwareStorageWriter segmentWriter,
            DocumentBatchPublisher segmentPublisher) {

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

        String text = fetched.getText();

        List<TextChunk> chunks = chunker.chunk(documentId, text);
        if (chunks.isEmpty()) {
            return new DocumentResult(documentId, 0, 0, 0,
                System.currentTimeMillis() - startTime, "No chunks produced");
        }

        DocumentBatchBuffer batchBuffer = new DocumentBatchBuffer(documentId, sourceId);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>(chunks.size());

        // Write all chunks to the TARGET SEGMENT and accumulate in batch buffer
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String chunkId = generateChunkId(sourceId, documentId, i);

            try {
                float[] embedding = llmClient.generateEmbedding(chunk.getText(),
                    "reindex:" + sourceId + ":" + documentId);

                // Write to the TARGET SEGMENT via segmentWriter
                long writeLogOffset = segmentWriter.writeChunkWithOffset(chunkId, chunk.getText(), embedding);

                if (writeLogOffset >= 0) {
                    // Add to batch buffer for atomic publication
                    batchBuffer.addChunk(chunkId, chunk, writeLogOffset, embedding);
                    chunkIds.add(chunkId);
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                    errors.add("Storage write failed for chunk " + i);
                    LOG.warn("Failed to write chunk {} for document {} to target segment", i, documentId);
                }
            } catch (LLMException e) {
                failureCount.incrementAndGet();
                errors.add("Embedding failed for chunk " + i + ": " + e.getMessage());
                LOG.error("Failed to generate embedding for chunk {} of document {}: {}",
                    i, documentId, e.getMessage());
            }
        }

        // A document is indexed whole or not at all. Publishing the chunks that survived would
        // register the new content hash beside a partial chunk list, and change detection compares
        // exactly that hash: the document would then read as unchanged forever, so the chunks that
        // failed would never be retried and their content would be permanently missing. Leaving
        // the previous registry entry alone keeps the document classified as changed, so the next
        // run picks it up again.
        if (failureCount.get() > 0) {
            return new DocumentResult(documentId, chunks.size(), 0, failureCount.get(),
                System.currentTimeMillis() - startTime,
                "Document left unchanged, " + failureCount.get() + " of " + chunks.size()
                    + " chunks failed: " + String.join("; ", errors));
        }

        // Seal the buffer for atomic publication
        batchBuffer.seal();

        // Build document metadata
        long contentHash = DocumentRegistry.computeContentHash(content);
        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(fetched.getLastModifiedMillis())
            .contentHash(contentHash)
            .contentLength(content.length)
            .chunkIds(chunkIds)
            .indexTimeMillis(System.currentTimeMillis())
            .build();

        // Publish atomically to the TARGET SEGMENT
        try {
            DocumentBatchPublisher.PublicationResult result = segmentPublisher.publish(batchBuffer, metadata);

            long elapsed = System.currentTimeMillis() - startTime;
            String errorMsg = errors.isEmpty() ? null : String.join("; ", errors);

            LOG.info("Document {} published atomically to target segment at epoch {}: {} chunks ({} successful, {} failed) in {}ms",
                documentId, result.epoch(), chunks.size(), successCount.get(), failureCount.get(), elapsed);

            indexLexically(chunkIds, chunks);
            return new DocumentResult(documentId, chunks.size(), successCount.get(),
                failureCount.get(), elapsed, errorMsg, chunkIds);

        } catch (DocumentBatchPublisher.PublicationException e) {
            // Publication failed - document should not be visible
            String errorMsg = "Atomic publication to target segment failed: " + e.getMessage();
            if (!errors.isEmpty()) {
                errorMsg += "; " + String.join("; ", errors);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.error("Atomic publication to target segment failed for document {}: {}", documentId, e.getMessage());

            return new DocumentResult(documentId, chunks.size(), 0, chunks.size(),
                elapsed, errorMsg);
        }
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

        String text = fetched.getText();

        List<TextChunk> chunks = chunker.chunk(documentId, text);
        if (chunks.isEmpty()) {
            return new DocumentResult(documentId, 0, 0, 0,
                System.currentTimeMillis() - startTime, "No chunks produced");
        }

        // Use atomic publication if configured
        if (hasAtomicPublication()) {
            return reindexDocumentWithAtomicPublication(sourceId, documentId, content, text, chunks,
                fetched.getLastModifiedMillis(), startTime);
        }

        // Fall back to legacy direct-write path
        return reindexDocumentDirect(sourceId, documentId, content, chunks, fetched.getLastModifiedMillis(), startTime);
    }

    /**
     * Re-indexes using atomic batch publication for document-atomic visibility.
     *
     * <p>This method writes all chunks to storage, accumulates them in a
     * {@link DocumentBatchBuffer}, and then publishes them atomically via
     * {@link DocumentBatchPublisher}.</p>
     */
    private DocumentResult reindexDocumentWithAtomicPublication(
            String sourceId,
            String documentId,
            byte[] content,
            String text,
            List<TextChunk> chunks,
            long lastModifiedMillis,
            long startTime) {

        DocumentBatchBuffer batchBuffer = new DocumentBatchBuffer(documentId, sourceId);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>(chunks.size());

        // Write all chunks to storage and accumulate in batch buffer
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String chunkId = generateChunkId(sourceId, documentId, i);

            try {
                float[] embedding = llmClient.generateEmbedding(chunk.getText(),
                    "reindex:" + sourceId + ":" + documentId);

                // Write to storage and get offset
                long writeLogOffset = offsetAwareWriter.writeChunkWithOffset(chunkId, chunk.getText(), embedding);

                if (writeLogOffset >= 0) {
                    // Add to batch buffer for atomic publication
                    batchBuffer.addChunk(chunkId, chunk, writeLogOffset, embedding);
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

        // A document is indexed whole or not at all. Publishing the chunks that survived would
        // register the new content hash beside a partial chunk list, and change detection compares
        // exactly that hash: the document would then read as unchanged forever, so the chunks that
        // failed would never be retried and their content would be permanently missing. Leaving
        // the previous registry entry alone keeps the document classified as changed, so the next
        // run picks it up again.
        if (failureCount.get() > 0) {
            return new DocumentResult(documentId, chunks.size(), 0, failureCount.get(),
                System.currentTimeMillis() - startTime,
                "Document left unchanged, " + failureCount.get() + " of " + chunks.size()
                    + " chunks failed: " + String.join("; ", errors));
        }

        // Seal the buffer for atomic publication
        batchBuffer.seal();

        // Build document metadata
        long contentHash = DocumentRegistry.computeContentHash(content);
        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(lastModifiedMillis)
            .contentHash(contentHash)
            .contentLength(content.length)
            .chunkIds(chunkIds)
            .indexTimeMillis(System.currentTimeMillis())
            .build();

        // Publish atomically - advances visibility epoch, inserts to Cuckoo filter, registers document
        try {
            DocumentBatchPublisher.PublicationResult result = batchPublisher.publish(batchBuffer, metadata);

            long elapsed = System.currentTimeMillis() - startTime;
            String errorMsg = errors.isEmpty() ? null : String.join("; ", errors);

            LOG.info("Document {} published atomically at epoch {}: {} chunks ({} successful, {} failed) in {}ms",
                documentId, result.epoch(), chunks.size(), successCount.get(), failureCount.get(), elapsed);

            indexLexically(chunkIds, chunks);
            return new DocumentResult(documentId, chunks.size(), successCount.get(),
                failureCount.get(), elapsed, errorMsg, chunkIds);

        } catch (DocumentBatchPublisher.PublicationException e) {
            // Publication failed - document should not be visible
            String errorMsg = "Atomic publication failed: " + e.getMessage();
            if (!errors.isEmpty()) {
                errorMsg += "; " + String.join("; ", errors);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.error("Atomic publication failed for document {}: {}", documentId, e.getMessage());

            return new DocumentResult(documentId, chunks.size(), 0, chunks.size(),
                elapsed, errorMsg);
        }
    }

    /**
     * Re-indexes using direct writes without atomic publication (legacy path).
     *
     * <p>This method writes chunks directly to storage without epoch-based
     * visibility management. Used when no {@link DocumentBatchPublisher} is configured.</p>
     */
    private DocumentResult reindexDocumentDirect(
            String sourceId,
            String documentId,
            byte[] content,
            List<TextChunk> chunks,
            long lastModifiedMillis,
            long startTime) {

        List<String> chunkIds = new ArrayList<>(chunks.size());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String chunkId = generateChunkId(sourceId, documentId, i);

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

        // As in the atomic paths: the registry entry is what change detection reads, so recording
        // the new hash for anything less than a complete document retires it from ever being
        // reindexed again. This path had no such guard at all - a run with the embedding endpoint
        // down replaced the entry with one naming no chunks, leaving the previous chunks in
        // storage with nothing recording their ids.
        if (failureCount.get() > 0) {
            long failedElapsed = System.currentTimeMillis() - startTime;
            LOG.warn("Document {} left unchanged: {} of {} chunks failed",
                documentId, failureCount.get(), chunks.size());
            return new DocumentResult(documentId, chunks.size(), 0, failureCount.get(),
                failedElapsed,
                "Document left unchanged, " + failureCount.get() + " of " + chunks.size()
                    + " chunks failed: " + String.join("; ", errors));
        }

        long contentHash = DocumentRegistry.computeContentHash(content);
        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(lastModifiedMillis)
            .contentHash(contentHash)
            .contentLength(content.length)
            .chunkIds(chunkIds)
            .indexTimeMillis(System.currentTimeMillis())
            .build();

        registry.register(metadata);

        long elapsed = System.currentTimeMillis() - startTime;
        String errorMsg = errors.isEmpty() ? null : String.join("; ", errors);

        LOG.debug("Re-indexed document {}: {} chunks ({} successful, {} failed) in {}ms",
            documentId, chunks.size(), successCount.get(), failureCount.get(), elapsed);

        indexLexically(chunkIds, chunks);
        return new DocumentResult(documentId, chunks.size(), successCount.get(),
            failureCount.get(), elapsed, errorMsg, chunkIds);
    }

    /**
     * Generates a unique chunk ID from a source ID, document ID and chunk index.
     *
     * <p>Format: {@code sourceId:documentId:chunk:index}. The source-qualified form is what
     * excalibur-shard parses back into a document id when it registers an HNSW node, and what
     * the ingestion pipeline writes, so a reindex overwrites and tombstones exactly the keys the
     * initial ingest created. See {@link ChunkIdentifiers}.</p>
     *
     * <p>Domain correctness: chunk IDs are unique, deterministic, and URL-safe.</p>
     *
     * @param sourceId The source ID
     * @param documentId The document ID
     * @param chunkIndex The chunk index (0-based)
     * @return Unique chunk identifier
     */
    private String generateChunkId(String sourceId, String documentId, int chunkIndex) {
        return ChunkIdentifiers.chunkId(sourceId, documentId, chunkIndex);
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

            /**
             * Folds one document's outcome into the result.
             *
             * <p>{@code documentsIndexed} counts documents that actually produced data. A
             * document whose every chunk failed is not "indexed": rolling replacement uses this
             * count to decide whether the new segment is usable before it tombstones the old
             * ones, and counting total failures there would retire a populated segment in favour
             * of an empty one.</p>
             */
            public Builder addDocumentResult(DocumentResult docResult) {
                if (docResult.getSuccessfulChunks() > 0) {
                    documentsIndexed++;
                }
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
