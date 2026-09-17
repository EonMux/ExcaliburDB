package excalibur.ingest.visibility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Tracks document visibility epochs to ensure queries never see partially-ingested documents.
 *
 * <p>This class is the core of the document-atomic visibility guarantee. When a document's
 * chunks are published to the visibility epoch, this registry records the epoch at which
 * the entire document becomes visible. Queries can then check if a document is fully
 * visible at their captured epoch.</p>
 *
 * <h2>Within one process</h2>
 * <p>Every map here is in memory, so the guarantee is a guarantee for as long as the process
 * lives and no longer: a document this registry was holding back when the node stopped comes
 * back unknown, and unknown is passed through. Nothing is written to disk and nothing is read
 * from it at startup. {@link DocumentVisibilityTracker} is the implementation that carries its
 * in-flight set across a restart, and is the one production wiring uses for that reason.</p>
 *
 * <h2>Document Atomicity</h2>
 * <p>A document is considered "atomic" when:</p>
 * <ul>
 *   <li>All its chunks have been written to the write log</li>
 *   <li>All chunks have been inserted into the Cuckoo filter hierarchy</li>
 *   <li>The write log has been synced (fsync)</li>
 *   <li>The visibility epoch has been advanced</li>
 *   <li>The document's visible epoch is recorded in this registry</li>
 * </ul>
 *
 * <h2>Query Integration</h2>
 * <p>When a query captures epoch E:</p>
 * <ol>
 *   <li>Query reads entries from write log up to some offset</li>
 *   <li>For each chunk found, query extracts the document ID</li>
 *   <li>Query checks if document is visible at epoch E via {@link #isVisibleAt(String, long)}</li>
 *   <li>If document is visible, all its chunks are included; if not, all chunks are excluded</li>
 * </ol>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Visible epochs are monotonically increasing per document</li>
 *   <li>A document can only have one visible epoch at a time</li>
 *   <li>When a document is re-indexed, its old chunks become invisible and new chunks become visible at a new epoch</li>
 *   <li>Document IDs are unique: "sourceId:documentId" format recommended</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>All operations use ConcurrentHashMap for concurrent access</li>
 *   <li>Read operations are lock-free</li>
 *   <li>Write operations are atomic per document</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public final class DocumentVisibilityRegistry implements DocumentVisibilityChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentVisibilityRegistry.class);

    /**
     * Map of document ID to the epoch at which the document becomes visible.
     * A document is visible to queries with epoch >= this value.
     */
    private final ConcurrentHashMap<String, Long> visibleEpochs;

    /**
     * Map of document ID to the set of chunk IDs for that document.
     * Used to verify chunk ownership and detect partial visibility.
     */
    private final ConcurrentHashMap<String, Set<String>> documentChunks;

    /**
     * Map of chunk ID to its owning document ID.
     * Used to quickly find the document for a given chunk.
     */
    private final ConcurrentHashMap<String, String> chunkToDocument;

    /**
     * Documents that were tombstoned, and the chunk ids they owned.
     *
     * <p>A tombstone is a fact, not an absence. Erasing every trace of a deleted document makes
     * it indistinguishable from one this registry never saw - and a caller that passes unknown
     * documents through, which is the only safe reading of unknown, would then show deleted
     * content again. Remembering costs an id per deletion and is what makes the deletion
     * enforceable.</p>
     *
     * <p>The set grows with deletions and is not reclaimed here; erasure and compaction are what
     * eventually retire a document's chunks, and a registry rebuilt from them starts clean.</p>
     */
    private final Set<String> tombstoned;

    /**
     * Serializes every write that spans more than one of the three maps.
     *
     * <p>The maps are individually concurrent, but a document's epoch, its chunk set and the
     * reverse chunk index only make sense together. Two writers touching the same document
     * without this lock interleave their updates: a register racing a tombstone can leave the
     * epoch removed while the chunk index still points at the document, or the chunk set
     * replaced while the epoch is the old one. Reads stay lock-free.</p>
     */
    private final ReentrantLock mutationLock;

    /**
     * Creates a new empty DocumentVisibilityRegistry.
     */
    public DocumentVisibilityRegistry() {
        this.visibleEpochs = new ConcurrentHashMap<>();
        this.tombstoned = ConcurrentHashMap.newKeySet();
        this.documentChunks = new ConcurrentHashMap<>();
        this.chunkToDocument = new ConcurrentHashMap<>();
        this.mutationLock = new ReentrantLock();
        
        LOGGER.debug("DocumentVisibilityRegistry created");
    }

    /**
     * Always true: every epoch this registry holds was handed to it by
     * {@code DocumentBatchPublisher}, which reads it from the node visibility tracker.
     *
     * @return true
     */
    @Override
    public boolean sharesNodeEpochClock() {
        return true;
    }

    /**
     * The highest epoch any registered document became visible at.
     *
     * <p>This registry keeps no separate epoch counter — it records a per-document epoch as each is
     * published — so the current epoch is the maximum of those. An empty registry reports 0, which
     * is the epoch before anything was committed and correctly makes every document invisible.
     */
    @Override
    public long currentEpoch() {
        long highest = 0L;
        for (Long epoch : visibleEpochs.values()) {
            if (epoch != null && epoch > highest) {
                highest = epoch;
            }
        }
        return highest;
    }

    /**
     * Registers a document as visible at the specified epoch.
     *
     * <p>This method should be called by {@code DocumentBatchPublisher} after
     * successfully publishing all chunks of a document to the write log and
     * advancing the visibility epoch.</p>
     *
     * <p><strong>Atomicity:</strong> This operation atomically makes the document
     * visible at the specified epoch. Queries with epoch >= visibleEpoch will see
     * the document; queries with epoch &lt; visibleEpoch will not.</p>
     *
     * @param documentId The document ID (must not be null)
     * @param chunkIds The set of chunk IDs belonging to this document (must not be null)
     * @param visibleEpoch The epoch at which the document becomes visible (must be >= 0)
     * @throws NullPointerException if documentId or chunkIds is null
     * @throws IllegalArgumentException if visibleEpoch is negative
     */
    public void registerDocument(String documentId, Set<String> chunkIds, long visibleEpoch) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        
        if (visibleEpoch < 0) {
            throw new IllegalArgumentException("visibleEpoch must be non-negative: " + visibleEpoch);
        }

        Set<String> immutableChunks = Collections.unmodifiableSet(new HashSet<>(chunkIds));

        mutationLock.lock();
        try {
            visibleEpochs.put(documentId, visibleEpoch);
            documentChunks.put(documentId, immutableChunks);
            // Re-registering revives a tombstoned id. Leaving the tombstone would make the
            // document permanently invisible however many times it is written again.
            tombstoned.remove(documentId);

            for (String chunkId : chunkIds) {
                chunkToDocument.put(chunkId, documentId);
            }
        } finally {
            mutationLock.unlock();
        }

        LOGGER.debug("Document '{}' registered with {} chunks at epoch {}",
            documentId, chunkIds.size(), visibleEpoch);
    }

    /**
     * Registers a document as visible at the specified epoch with a list of chunk IDs.
     *
     * @param documentId The document ID
     * @param chunkIds The list of chunk IDs belonging to this document
     * @param visibleEpoch The epoch at which the document becomes visible
     */
    public void registerDocument(String documentId, Collection<String> chunkIds, long visibleEpoch) {
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        registerDocument(documentId, new HashSet<>(chunkIds), visibleEpoch);
    }

    /**
     * Checks if a document is fully visible at the specified query epoch.
     *
     * <p>A document is visible at epoch E if:</p>
     * <ul>
     *   <li>The document is registered in this registry</li>
     *   <li>The document's visibleEpoch &lt;= E</li>
     * </ul>
     *
     * <p><strong>Atomicity guarantee:</strong> If this method returns true, the query
     * will see ALL chunks of the document. If it returns false, the query will see
     * NONE of the chunks (no partial documents).</p>
     *
     * @param documentId The document ID to check
     * @param queryEpoch The epoch at which the query was captured
     * @return true if the document is fully visible at the query epoch
     */
    public boolean isVisibleAt(String documentId, long queryEpoch) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        
        Long visibleEpoch = visibleEpochs.get(documentId);
        if (visibleEpoch == null) {
            return false;
        }
        
        return visibleEpoch <= queryEpoch;
    }

    /**
     * Checks if a chunk is part of a fully visible document at the specified epoch.
     *
     * <p>This is a convenience method that:</p>
     * <ol>
     *   <li>Looks up the document that owns this chunk</li>
     *   <li>Checks if that document is visible at the query epoch</li>
     * </ol>
     *
     * @param chunkId The chunk ID to check
     * @param queryEpoch The epoch at which the query was captured
     * @return true if the chunk belongs to a fully visible document
     */
    public boolean isChunkVisibleAt(String chunkId, long queryEpoch) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        
        String documentId = chunkToDocument.get(chunkId);
        if (documentId == null) {
            return false;
        }
        
        return isVisibleAt(documentId, queryEpoch);
    }

    /**
     * Gets the document ID that owns a specific chunk.
     *
     * @param chunkId The chunk ID
     * @return The owning document ID, or null if chunk is not registered
     */
    public String getDocumentForChunk(String chunkId) {
        return chunkToDocument.get(chunkId);
    }

    /**
     * Gets the epoch at which a document becomes visible.
     *
     * @param documentId The document ID
     * @return The visible epoch, or -1 if document is not registered
     */
    public long getVisibleEpoch(String documentId) {
        Long epoch = visibleEpochs.get(documentId);
        return epoch != null ? epoch : -1;
    }

    /**
     * Gets all chunk IDs belonging to a document.
     *
     * @param documentId The document ID
     * @return Immutable set of chunk IDs, or empty set if document not found
     */
    public Set<String> getChunksForDocument(String documentId) {
        Set<String> chunks = documentChunks.get(documentId);
        return chunks != null ? chunks : Collections.emptySet();
    }

    /**
     * Filters a set of chunk IDs to only include those belonging to fully visible documents.
     *
     * <p>This method is used by the query pipeline to ensure only chunks from
     * fully-visible documents are included in results.</p>
     *
     * @param chunkIds The chunk IDs to filter
     * @param queryEpoch The epoch at which the query was captured
     * @return Set of chunk IDs from fully visible documents only
     */
    public Set<String> filterVisibleChunks(Set<String> chunkIds, long queryEpoch) {
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        
        return chunkIds.stream()
            .filter(chunkId -> isChunkVisibleAt(chunkId, queryEpoch))
            .collect(Collectors.toSet());
    }

    /**
     * Filters a set of document IDs to only include fully visible documents.
     *
     * @param documentIds The document IDs to filter
     * @param queryEpoch The epoch at which the query was captured
     * @return Set of document IDs that are fully visible
     */
    public Set<String> filterVisibleDocuments(Set<String> documentIds, long queryEpoch) {
        Objects.requireNonNull(documentIds, "documentIds must not be null");
        
        return documentIds.stream()
            .filter(docId -> isVisibleAt(docId, queryEpoch))
            .collect(Collectors.toSet());
    }

    /**
     * Marks a document as tombstoned (invisible to new queries).
     *
     * <p>This method is called when a document is deleted or re-indexed. The document
     * is removed from the visibility registry, making it invisible to future queries.
     * Queries that captured an epoch before this call will still see the document
     * (epoch isolation).</p>
     *
     * @param documentId The document ID to tombstone
     * @return true if the document was found and removed
     */
    public boolean tombstoneDocument(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");

        Long removed;
        mutationLock.lock();
        try {
            Set<String> chunks = documentChunks.remove(documentId);
            if (chunks != null) {
                for (String chunkId : chunks) {
                    chunkToDocument.remove(chunkId);
                }
            }

            // The chunk ids go, the document id stays. At chunk level an unrecognised id is
            // already treated as invisible, so dropping the chunks still enforces the deletion;
            // at document level the opposite holds -- an unrecognised document is passed
            // through -- so forgetting the document would put deleted content back into results.
            removed = visibleEpochs.remove(documentId);
            tombstoned.add(documentId);
        } finally {
            mutationLock.unlock();
        }

        if (removed != null) {
            LOGGER.debug("Document '{}' tombstoned (was visible at epoch {})", 
                documentId, removed);
            return true;
        }
        
        return false;
    }

    /**
     * Atomically replaces a document's chunks with a new set at a new epoch.
     *
     * <p>This method is used during re-indexing when a document is updated.
     * The old chunks are removed and new chunks are registered atomically.</p>
     *
     * <p><strong>Atomicity:</strong> This operation ensures that queries see either
     * the old version or the new version, never a mix of both.</p>
     *
     * @param documentId The document ID
     * @param newChunkIds The new set of chunk IDs
     * @param newVisibleEpoch The new visible epoch
     */
    public void replaceDocument(String documentId, Set<String> newChunkIds, long newVisibleEpoch) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(newChunkIds, "newChunkIds must not be null");
        
        if (newVisibleEpoch < 0) {
            throw new IllegalArgumentException("newVisibleEpoch must be non-negative: " + newVisibleEpoch);
        }

        Set<String> immutableChunks = Collections.unmodifiableSet(new HashSet<>(newChunkIds));
        
        mutationLock.lock();
        try {
            Set<String> oldChunks = documentChunks.get(documentId);
            if (oldChunks != null) {
                for (String oldChunkId : oldChunks) {
                    chunkToDocument.remove(oldChunkId);
                }
            }
            
            visibleEpochs.put(documentId, newVisibleEpoch);
            documentChunks.put(documentId, immutableChunks);
            
            for (String newChunkId : newChunkIds) {
                chunkToDocument.put(newChunkId, documentId);
            }
        } finally {
            mutationLock.unlock();
        }

        LOGGER.debug("Document '{}' replaced with {} chunks at epoch {}",
            documentId, newChunkIds.size(), newVisibleEpoch);
    }

    /**
     * Checks if a document is registered in this registry.
     *
     * @param documentId The document ID
     * @return true if the document is registered
     */
    public boolean isRegistered(String documentId) {
        return visibleEpochs.containsKey(documentId);
    }

    /**
     * Whether this registry knows about the document.
     *
     * <p>It holds no in-flight state - a document appears here only once it is published - so
     * tracked and registered are the same question.
     *
     * @param documentId The document ID
     * @return true if the document is registered
     */
    @Override
    public boolean isDocumentTracked(String documentId) {
        return isRegistered(documentId) || tombstoned.contains(documentId);
    }

    /**
     * Whether a document has been tombstoned.
     *
     * @param documentId The document ID
     * @return true if the document was deleted
     */
    public boolean isTombstoned(String documentId) {
        return tombstoned.contains(documentId);
    }

    /**
     * Returns the total number of registered documents.
     *
     * @return Document count
     */
    @Override
    public int getDocumentCount() {
        return visibleEpochs.size();
    }

    /**
     * Returns the total number of registered chunks across all documents.
     *
     * @return Total chunk count
     */
    public int getTotalChunkCount() {
        return chunkToDocument.size();
    }

    /**
     * Returns all registered document IDs.
     *
     * @return Immutable set of document IDs
     */
    public Set<String> getAllDocumentIds() {
        return Collections.unmodifiableSet(new HashSet<>(visibleEpochs.keySet()));
    }

    /**
     * Clears all registered documents and chunks.
     *
     * <p>This is typically only used during testing or reset scenarios.</p>
     */
    public void clear() {
        mutationLock.lock();
        try {
            visibleEpochs.clear();
            documentChunks.clear();
            chunkToDocument.clear();
            tombstoned.clear();
        } finally {
            mutationLock.unlock();
        }
        LOGGER.debug("DocumentVisibilityRegistry cleared");
    }

    /**
     * Returns statistics about this registry.
     *
     * @return Statistics snapshot
     */
    public VisibilityStats getStats() {
        return new VisibilityStats(
            visibleEpochs.size(),
            chunkToDocument.size(),
            calculateAverageChunksPerDocument()
        );
    }

    private double calculateAverageChunksPerDocument() {
        if (documentChunks.isEmpty()) {
            return 0.0;
        }
        int totalChunks = documentChunks.values().stream()
            .mapToInt(Set::size)
            .sum();
        return (double) totalChunks / documentChunks.size();
    }

    /**
     * Statistics about the visibility registry.
     *
     * @param documentCount Number of registered documents
     * @param chunkCount Number of registered chunks
     * @param avgChunksPerDocument Average chunks per document
     */
    public record VisibilityStats(
        int documentCount,
        int chunkCount,
        double avgChunksPerDocument
    ) {
        @Override
        public String toString() {
            return String.format("VisibilityStats{documents=%d, chunks=%d, avgChunks=%.2f}",
                documentCount, chunkCount, avgChunksPerDocument);
        }
    }

    @Override
    public String toString() {
        return String.format("DocumentVisibilityRegistry{documents=%d, chunks=%d}",
            visibleEpochs.size(), chunkToDocument.size());
    }

    // ==================== DocumentVisibilityChecker Interface Implementation ====================

    @Override
    public boolean isDocumentVisible(String documentId, long queryEpoch) {
        return isVisibleAt(documentId, queryEpoch);
    }

    @Override
    public boolean isChunkVisible(String chunkId, long queryEpoch) {
        return isChunkVisibleAt(chunkId, queryEpoch);
    }

    @Override
    public List<String> filterPartialDocuments(List<String> chunkIds, long queryEpoch) {
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        
        return chunkIds.stream()
            .filter(chunkId -> isChunkVisible(chunkId, queryEpoch))
            .toList();
    }

    @Override
    public Set<String> getVisibleDocuments(long queryEpoch) {
        Set<String> visible = new HashSet<>();
        for (Map.Entry<String, Long> entry : visibleEpochs.entrySet()) {
            if (entry.getValue() <= queryEpoch) {
                visible.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(visible);
    }

    @Override
    public long getCommitEpoch(String documentId) {
        return getVisibleEpoch(documentId);
    }

    @Override
    public int getChunkCount(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Set<String> chunks = documentChunks.get(documentId);
        return chunks != null ? chunks.size() : 0;
    }
}
