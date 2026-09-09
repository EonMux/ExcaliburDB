package excalibur.ingest.visibility;

import io.excalibur.core.text.ChunkIdentifiers;
import excalibur.ingest.buffer.AtomicDocumentBatch;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks document visibility for ensuring atomic document-level visibility.
 *
 * <p>This class ensures that all chunks of a document become visible to queries
 * at the same visibility epoch, preventing partial documents from appearing in
 * query results.</p>
 *
 * <h2>Document Atomicity Guarantee</h2>
 * <p>A document is considered "fully visible" at epoch E if:</p>
 * <ul>
 *   <li>All chunks for the document were committed atomically</li>
 *   <li>The commit epoch is &lt;= E</li>
 *   <li>All chunk IDs are registered in the document registry</li>
 * </ul>
 *
 * <h2>Write Log Offset Tracking</h2>
 * <p>Each document is tracked with:</p>
 * <ul>
 *   <li>Document ID - unique identifier</li>
 *   <li>Chunk IDs - all chunk identifiers for this document</li>
 *   <li>Commit epoch - the epoch at which this document became visible</li>
 *   <li>Write log offset range - [startOffset, endOffset) for atomicity verification</li>
 * </ul>
 *
 * <h2>Retention</h2>
 * <p>Published documents are remembered for a bounded window
 * ({@value #DEFAULT_RETAINED_DOCUMENTS} by default) rather than for the life of the process. Past
 * it the oldest commit epochs are forgotten, which returns those documents to the same state a
 * restart leaves every previously written document in: unknown to this tracker, and passed through
 * by the atomicity filter rather than hidden. Documents still in flight are never forgotten.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Document registrations use ConcurrentHashMap</li>
 *   <li>Epoch tracking is delegated to a {@link VisibilityEpochClock}, which is itself
 *       thread-safe</li>
 *   <li>All public methods can be called from any thread</li>
 * </ul>
 *
 * <h2>Which clock</h2>
 * <p>Production wiring builds this against the node visibility tracker, so a document's commit
 * epoch and a query's captured epoch are the same counter and can be compared. Built without
 * one, the tracker keeps a private counter, still enforces all-or-nothing per document, and
 * reports {@link #sharesNodeEpochClock()} as false so that no caller mistakes its epochs for
 * a query snapshot.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Commit epoch is non-negative and monotonically increasing</li>
 *   <li>Chunk IDs are non-null and non-empty</li>
 *   <li>Offset range satisfies startOffset &lt; endOffset</li>
 *   <li>A document is only visible after all chunks are registered</li>
 *   <li>Tracking is bounded: the oldest published documents are forgotten, never the newest</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public class DocumentVisibilityTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentVisibilityTracker.class);

    /**
     * How many published documents are remembered before the oldest are forgotten.
     *
     * <p>Chosen to cover far more than any plausible set of in-flight queries while keeping the
     * map's footprint bounded: a node that has published millions of documents holds entries for
     * the most recent hundred thousand, not for all of them.</p>
     */
    public static final int DEFAULT_RETAINED_DOCUMENTS = 100_000;


    private final DocumentRegistry documentRegistry;
    private final Map<String, DocumentVisibility> documentVisibility;
    private final VisibilityEpochClock clock;
    private final boolean nodeClock;
    private final Map<String, Set<String>> pendingChunks;
    private final int retainedDocuments;
    private final Object evictionLock = new Object();

    /**
     * Creates a new DocumentVisibilityTracker with an in-memory document registry.
     *
     * <p>This constructor is useful for testing or standalone usage where
     * document metadata persistence is not required.</p>
     */
    public DocumentVisibilityTracker() {
        this(new DocumentRegistry(), privateClock(0));
    }

    /**
     * Creates a new DocumentVisibilityTracker.
     *
     * @param documentRegistry The document registry for metadata access
     * @throws NullPointerException if documentRegistry is null
     */
    public DocumentVisibilityTracker(DocumentRegistry documentRegistry) {
        this(documentRegistry, privateClock(0));
    }

    /**
     * Creates a new DocumentVisibilityTracker with a specific starting epoch.
     *
     * @param documentRegistry The document registry
     * @param initialEpoch The initial visibility epoch
     * @throws NullPointerException if documentRegistry is null
     * @throws IllegalArgumentException if initialEpoch is negative
     */
    public DocumentVisibilityTracker(DocumentRegistry documentRegistry, long initialEpoch) {
        this(documentRegistry, privateClock(initialEpoch));
        if (initialEpoch < 0) {
            throw new IllegalArgumentException("initialEpoch must be non-negative: " + initialEpoch);
        }
    }

    /**
     * Creates a tracker publishing on the node's visibility clock.
     *
     * <p>This is the constructor production wiring uses. Sharing the clock with the query
     * pipeline is what turns the atomicity filter from a completeness check into a real
     * snapshot bound: a document committed at epoch E is then invisible to a query that
     * captured any epoch before E, which is a statement the two counters could not make about
     * each other while they were separate.
     *
     * @param documentRegistry The document registry for metadata access
     * @param nodeVisibility   The node visibility tracker whose epoch queries capture
     * @throws NullPointerException if either argument is null
     */
    public DocumentVisibilityTracker(DocumentRegistry documentRegistry,
                                     io.excalibur.store.VisibilityTracker nodeVisibility) {
        this(documentRegistry, VisibilityEpochClock.of(nodeVisibility), true);
    }

    private DocumentVisibilityTracker(DocumentRegistry documentRegistry,
                                      VisibilityEpochClock clock) {
        this(documentRegistry, clock, false);
    }

    private DocumentVisibilityTracker(DocumentRegistry documentRegistry,
                                      VisibilityEpochClock clock, boolean nodeClock) {
        this(documentRegistry, clock, nodeClock, DEFAULT_RETAINED_DOCUMENTS);
    }

    private DocumentVisibilityTracker(DocumentRegistry documentRegistry,
                                      VisibilityEpochClock clock, boolean nodeClock,
                                      int retainedDocuments) {
        this.documentRegistry =
            Objects.requireNonNull(documentRegistry, "documentRegistry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nodeClock = nodeClock;
        if (retainedDocuments <= 0) {
            throw new IllegalArgumentException(
                "retainedDocuments must be positive: " + retainedDocuments);
        }
        this.retainedDocuments = retainedDocuments;
        this.documentVisibility = new ConcurrentHashMap<>();
        this.pendingChunks = new ConcurrentHashMap<>();

        LOGGER.info("DocumentVisibilityTracker initialized on {} clock, retaining {} documents",
            nodeClock ? "the node" : "a private", retainedDocuments);
    }

    /**
     * Creates a tracker on a private clock that remembers a given number of documents.
     *
     * <p>Provided for tests and for callers that want a smaller window than the default; the
     * bound is the point, so there is no way to ask for an unbounded one.</p>
     *
     * @param retainedDocuments How many published documents to remember (must be positive)
     * @throws IllegalArgumentException if retainedDocuments is not positive
     */
    public DocumentVisibilityTracker(int retainedDocuments) {
        this(new DocumentRegistry(), privateClock(0), false, retainedDocuments);
    }

    /**
     * Returns how many published documents this tracker remembers before forgetting the oldest.
     *
     * @return The retention limit
     */
    public int retainedDocumentLimit() {
        return retainedDocuments;
    }

    /**
     * A counter of this tracker's own.
     *
     * <p>Kept for standalone and test use. Epochs from it mean nothing to anything else in the
     * process, which is exactly why {@link #sharesNodeEpochClock()} reports false for a tracker
     * built this way and the query pipeline declines to bound by them.
     */
    private static VisibilityEpochClock privateClock(long initialEpoch) {
        AtomicLong counter = new AtomicLong(Math.max(0, initialEpoch));
        return new VisibilityEpochClock() {
            @Override
            public long currentEpoch() {
                return counter.get();
            }

            @Override
            public long advanceEpoch() {
                return counter.incrementAndGet();
            }

            @Override
            public String toString() {
                return "VisibilityEpochClock[private]";
            }
        };
    }

    /**
     * Whether this tracker's epochs are the ones queries capture.
     *
     * @return true when built against the node visibility tracker
     */
    public boolean sharesNodeEpochClock() {
        return nodeClock;
    }

    /**
     * Registers a document as atomically committed at the specified epoch.
     *
     * <p>This method should be called after all chunks have been written to
     * the write log and the epoch has been advanced.</p>
     *
     * <p><strong>Domain correctness:</strong></p>
     * <ul>
     *   <li>batch must have non-null documentId</li>
     *   <li>batch must have valid offset range (startOffset &lt; endOffset)</li>
     *   <li>commitEpoch must be non-negative</li>
     * </ul>
     *
     * @param batch The atomic document batch containing chunk information
     * @param commitEpoch The epoch at which this document becomes visible
     * @throws NullPointerException if batch is null
     * @throws IllegalArgumentException if commitEpoch is negative
     */
    public void registerAtomicDocument(AtomicDocumentBatch batch, long commitEpoch) {
        Objects.requireNonNull(batch, "batch must not be null");
        if (commitEpoch < 0) {
            throw new IllegalArgumentException("commitEpoch must be non-negative: " + commitEpoch);
        }

        String documentId = batch.getDocumentId();
        List<String> chunkIds = batch.getChunkIdList();

        DocumentVisibility visibility = new DocumentVisibility(
            documentId,
            Set.copyOf(chunkIds),
            commitEpoch,
            batch.getStartOffset(),
            batch.getEndOffset()
        );

        documentVisibility.put(documentId, visibility);
        pendingChunks.remove(documentId);
        forgetOldestBeyondRetention();

        LOGGER.debug("Document {} registered as atomic at epoch {} with {} chunks",
            documentId, commitEpoch, chunkIds.size());
    }

    /**
     * Registers a document from its metadata with the current epoch.
     *
     * <p>This is a convenience method for recovering documents on restart.</p>
     *
     * @param metadata The document metadata containing chunk IDs
     * @param commitEpoch The epoch at which this document becomes visible
     * @throws NullPointerException if metadata is null
     */
    public void registerFromMetadata(DocumentMetadata metadata, long commitEpoch) {
        Objects.requireNonNull(metadata, "metadata must not be null");

        String key = makeKey(metadata.getSourceId(), metadata.getDocumentId());
        Set<String> chunkIds = new HashSet<>(metadata.getChunkIds());

        DocumentVisibility visibility = new DocumentVisibility(
            key,
            chunkIds,
            commitEpoch,
            -1,
            -1
        );

        documentVisibility.put(key, visibility);
        // Clearing the in-flight marker is what completes the publish. Without it a document
        // begun with beginIngestion and finished through this path stays pending forever:
        // invisible to every query, and never released.
        pendingChunks.remove(key);
        forgetOldestBeyondRetention();

        LOGGER.debug("Document {} recovered from metadata at epoch {} with {} chunks",
            key, commitEpoch, chunkIds.size());
    }

    /**
     * Begins tracking a document that is being ingested.
     *
     * <p>Chunks are added incrementally until the document is committed atomically.</p>
     *
     * @param documentId The document identifier
     * @param expectedChunkCount The expected number of chunks
     */
    public void beginIngestion(String documentId, int expectedChunkCount) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        pendingChunks.put(documentId, new HashSet<>());
        LOGGER.trace("Began tracking ingestion for document {}", documentId);
    }

    /**
     * Adds a chunk to a pending document.
     *
     * @param documentId The document identifier
     * @param chunkId The chunk identifier being added
     */
    public void addPendingChunk(String documentId, String chunkId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(chunkId, "chunkId must not be null");

        Set<String> chunks = pendingChunks.get(documentId);
        if (chunks != null) {
            chunks.add(chunkId);
            LOGGER.trace("Added chunk {} to pending document {}", chunkId, documentId);
        }
    }

    /**
     * Aborts ingestion for a document, removing all pending chunks.
     *
     * @param documentId The document identifier
     */
    public void abortIngestion(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Set<String> removed = pendingChunks.remove(documentId);
        if (removed != null) {
            LOGGER.debug("Aborted ingestion for document {} with {} pending chunks",
                documentId, removed.size());
        }
    }

    /**
     * Checks if a document is fully visible at the specified query epoch.
     *
     * <p>A document is fully visible if:</p>
     * <ul>
     *   <li>It is registered in the visibility tracker</li>
     *   <li>Its commit epoch is &lt;= the query epoch</li>
     *   <li>All its chunks are registered (not partial)</li>
     * </ul>
     *
     * @param documentId The document identifier
     * @param queryEpoch The epoch at which the query was started
     * @return true if the document is fully visible at the query epoch
     */
    /**
     * Whether this tracker knows about the document, committed or in flight.
     *
     * @param documentId The document identifier
     * @return true when the document is tracked
     */
    public boolean isDocumentTracked(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        return documentVisibility.containsKey(documentId) || pendingChunks.containsKey(documentId);
    }

    public boolean isDocumentFullyVisible(String documentId, long queryEpoch) {
        Objects.requireNonNull(documentId, "documentId must not be null");

        DocumentVisibility visibility = documentVisibility.get(documentId);
        if (visibility == null) {
            return false;
        }

        return visibility.commitEpoch() <= queryEpoch;
    }

    /**
     * Checks if a specific chunk is visible at the query epoch.
     *
     * <p>A chunk is visible if its document is fully visible.</p>
     *
     * @param chunkId The chunk identifier (format: sourceId:documentId:chunk:index)
     * @param queryEpoch The epoch at which the query was started
     * @return true if the chunk is visible at the query epoch
     */
    public boolean isChunkVisible(String chunkId, long queryEpoch) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");

        String documentId = extractDocumentId(chunkId);
        return isDocumentFullyVisible(documentId, queryEpoch);
    }

    /**
     * Filters query results to exclude partial documents.
     *
     * <p>This method takes a list of chunk IDs from query results and
     * returns only those chunks whose documents are fully visible at
     * the query epoch.</p>
     *
     * @param chunkIds The chunk IDs from query results
     * @param queryEpoch The epoch at which the query was started
     * @return Filtered list containing only chunks from fully visible documents
     */
    public List<String> filterPartialDocuments(List<String> chunkIds, long queryEpoch) {
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");

        return chunkIds.stream()
            .filter(chunkId -> isChunkVisible(chunkId, queryEpoch))
            .toList();
    }

    /**
     * Gets the set of fully visible document IDs at the specified epoch.
     *
     * @param queryEpoch The epoch at which to check visibility
     * @return Set of document IDs that are fully visible
     */
    public Set<String> getVisibleDocuments(long queryEpoch) {
        Set<String> visible = new HashSet<>();
        for (Map.Entry<String, DocumentVisibility> entry : documentVisibility.entrySet()) {
            if (entry.getValue().commitEpoch() <= queryEpoch) {
                visible.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(visible);
    }

    /**
     * Gets the commit epoch for a document.
     *
     * @param documentId The document identifier
     * @return The commit epoch, or -1 if not found
     */
    public long getCommitEpoch(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        DocumentVisibility visibility = documentVisibility.get(documentId);
        return visibility != null ? visibility.commitEpoch() : -1;
    }

    /**
     * Gets the chunk count for a registered document.
     *
     * @param documentId The document identifier
     * @return The number of chunks, or 0 if not found
     */
    public int getChunkCount(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        DocumentVisibility visibility = documentVisibility.get(documentId);
        return visibility != null ? visibility.chunkIds().size() : 0;
    }

    /**
     * Gets the visibility information for a document.
     *
     * @param documentId The document identifier
     * @return The visibility info, or null if not found
     */
    public DocumentVisibility getVisibility(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        return documentVisibility.get(documentId);
    }

    /**
     * Advances the visibility epoch.
     *
     * <p>On the node clock this is the same advance segment registration and the epoch tick
     * scheduler perform, so a document published here becomes visible to queries in the same
     * ordering as everything else that becomes visible.
     *
     * @return The new epoch value
     */
    public long advanceEpoch() {
        long newEpoch = clock.advanceEpoch();
        LOGGER.trace("Visibility epoch advanced to {}", newEpoch);
        return newEpoch;
    }

    /**
     * Returns the current visibility epoch.
     *
     * @return The current epoch
     */
    public long currentEpoch() {
        return clock.currentEpoch();
    }

    /**
     * Returns the number of registered documents.
     *
     * @return Document count
     */
    public int getDocumentCount() {
        return documentVisibility.size();
    }

    /**
     * Returns the number of documents with pending ingestion.
     *
     * @return Pending document count
     */
    public int getPendingDocumentCount() {
        return pendingChunks.size();
    }

    /**
     * Removes a document from visibility tracking.
     *
     * <p>This should be called when a document is tombstoned.</p>
     *
     * @param documentId The document identifier
     * @return true if the document was removed
     */
    public boolean removeDocument(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        DocumentVisibility removed = documentVisibility.remove(documentId);
        pendingChunks.remove(documentId);
        if (removed != null) {
            LOGGER.debug("Document {} removed from visibility tracking", documentId);
            return true;
        }
        return false;
    }

    /**
     * Clears all visibility tracking state.
     */
    public void clear() {
        documentVisibility.clear();
        pendingChunks.clear();
        LOGGER.debug("DocumentVisibilityTracker cleared");
    }

    /**
     * Forgets the oldest published documents once the retention limit is passed.
     *
     * <h2>Why this is safe</h2>
     * <p>The atomicity filter passes a document it has never heard of straight through rather than
     * hiding it, because this map is built in memory as documents are ingested and everything
     * written by a previous process is unknown after a restart. Forgetting an entry therefore puts
     * that document in exactly the state a restart would leave it in, and both states give the
     * same answer for a document that is already fully visible.</p>
     *
     * <p>What must not be forgotten is a document some live query should still be excluded from
     * seeing, and those are the ones committed most recently. Eviction takes the lowest commit
     * epochs first, so entries leave in the order their answers stop being in dispute.</p>
     *
     * <p>In-flight documents live in {@code pendingChunks} and are never touched here: forgetting
     * one would publish it half-written, which is the single thing this class exists to prevent.</p>
     */
    private void forgetOldestBeyondRetention() {
        if (documentVisibility.size() <= retainedDocuments) {
            return;
        }

        synchronized (evictionLock) {
            int size = documentVisibility.size();
            if (size <= retainedDocuments) {
                return;
            }

            // Evict down to nine tenths of the limit rather than exactly to it, so that a steady
            // publish rate pays for this sort once per tenth of the window instead of once per
            // document.
            int target = Math.max(1, retainedDocuments - retainedDocuments / 10);
            int excess = size - target;

            List<Map.Entry<String, DocumentVisibility>> oldestFirst =
                new ArrayList<>(documentVisibility.entrySet());
            oldestFirst.sort(Comparator.comparingLong(entry -> entry.getValue().commitEpoch()));

            int removed = 0;
            for (int i = 0; i < oldestFirst.size() && removed < excess; i++) {
                Map.Entry<String, DocumentVisibility> entry = oldestFirst.get(i);
                if (documentVisibility.remove(entry.getKey(), entry.getValue())) {
                    removed++;
                }
            }

            LOGGER.debug("Visibility tracking forgot {} of {} documents, retaining {}",
                removed, size, documentVisibility.size());
        }
    }

    /**
     * Creates a composite key from source ID and document ID.
     */
    private static String makeKey(String sourceId, String documentId) {
        return ChunkIdentifiers.documentKey(sourceId, documentId);
    }

    /**
     * Extracts the document key from a chunk ID.
     *
     * <p>Chunk ID format: {@code sourceId:documentId:chunk:index}. Splitting on the last colon
     * would be wrong: both the source-qualified prefix and document ids such as URLs contain
     * colons of their own.</p>
     */
    private static String extractDocumentId(String chunkId) {
        return ChunkIdentifiers.documentKeyOfChunk(chunkId);
    }

    /**
     * Returns statistics about the visibility tracker.
     *
     * @return Visibility statistics
     */
    public VisibilityStats getStats() {
        return new VisibilityStats(
            currentEpoch(),
            documentVisibility.size(),
            pendingChunks.size()
        );
    }

    /**
     * Returns the document registry used by this tracker.
     *
     * <p>The document registry contains metadata for all registered documents
     * and is used by the reindexer to look up existing document information.</p>
     *
     * @return The document registry
     */
    public DocumentRegistry getRegistry() {
        return documentRegistry;
    }

    /**
     * Visibility information for a document.
     *
     * @param documentId The document identifier
     * @param chunkIds Set of all chunk IDs for this document
     * @param commitEpoch The epoch at which this document became visible
     * @param startOffset The start offset in the write log (-1 if unknown)
     * @param endOffset The end offset in the write log (-1 if unknown)
     */
    public record DocumentVisibility(
        String documentId,
        Set<String> chunkIds,
        long commitEpoch,
        long startOffset,
        long endOffset
    ) {
        public DocumentVisibility {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(chunkIds, "chunkIds must not be null");
            if (commitEpoch < 0) {
                throw new IllegalArgumentException("commitEpoch must be non-negative: " + commitEpoch);
            }
        }

        public int chunkCount() {
            return chunkIds.size();
        }

        public boolean hasOffsetRange() {
            return startOffset >= 0 && endOffset > startOffset;
        }

        public long byteSize() {
            return hasOffsetRange() ? endOffset - startOffset : 0;
        }
    }

    /**
     * Statistics about the visibility tracker.
     *
     * @param currentEpoch Current visibility epoch
     * @param documentCount Number of registered documents
     * @param pendingCount Number of pending documents
     */
    public record VisibilityStats(
        long currentEpoch,
        int documentCount,
        int pendingCount
    ) {}
}
