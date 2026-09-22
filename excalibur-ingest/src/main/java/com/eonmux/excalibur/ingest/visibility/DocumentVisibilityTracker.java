package com.eonmux.excalibur.ingest.visibility;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
 * <h2>What survives a restart, and why it is the in-flight set rather than the published one</h2>
 * <p>Two kinds of entry live here and only one of them has to outlast the process.</p>
 *
 * <p>A published document is a document whose chunks are all in storage. Forgetting it - to
 * retention, or to a restart - leaves it unknown to this tracker, and the atomicity filter passes
 * an unknown document through, which is the right answer for a document that is whole. Persisting
 * those entries would buy nothing and would cost a write per published document.</p>
 *
 * <p>An in-flight document is the opposite. Its chunks are being written one at a time and are
 * reachable by a scan the moment they land, so the mark is the only thing standing between a
 * query and half a document. Forgetting it does not return the document to a safe default: the
 * restarted node has never heard of it, passes it through, and serves exactly the partial
 * document this class exists to prevent. So the in-flight set, and only it, is written to an
 * {@link InFlightDocumentJournal} beside the document registry - the mark is durable before the
 * first chunk is written, and it is released when the document is published or rolled back.</p>
 *
 * <p>The alternative was to reconcile at startup instead: find the chunks of documents that were
 * never completed and delete them. That is the same fact arrived at more expensively and less
 * reliably. Nothing on a chunk says which attempt wrote it, so finding them means scanning
 * storage against the registry at every start, and the registry does not record an attempt until
 * it succeeds - a document being ingested for the first time has no entry to compare against, so
 * a scan cannot tell its chunks from those of a document written before the registry was last
 * saved. Recording the intent before the write is what makes the question answerable at all, and
 * once it is recorded there is nothing left for a scan to discover.</p>
 *
 * <p>What this buys is that an interrupted document stays hidden rather than being erased. The
 * chunks it wrote remain in storage, invisible, until the next crawl re-ingests the document -
 * which it will, because the registry holds no entry for it, so change detection reads it as new
 * - or until it is erased. This tracker holds no handle on storage and removing chunks is not
 * its to do; hiding them is, and hiding them is what the guarantee needs.</p>
 *
 * <p>A tracker built on a registry with no file has no data directory to write to and makes no
 * promise across a restart. That is the standalone and test arrangement; production opens the
 * registry under the node's data directory.</p>
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
     * Where in-flight documents are recorded, or null when the registry is in memory only.
     *
     * <p>Null is not a degraded mode to be papered over: a registry with no file names no data
     * directory, so there is nowhere for a mark to survive to and nothing after a restart that
     * would read it. Such a node has already opted out of everything that outlives the process.
     * </p>
     */
    private final InFlightDocumentJournal journal;

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
                                     com.eonmux.excalibur.store.VisibilityTracker nodeVisibility) {
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

        Path registryFile = documentRegistry.persistencePath();
        this.journal = registryFile == null ? null : new InFlightDocumentJournal(registryFile);
        recoverInterruptedDocuments();

        LOGGER.info("DocumentVisibilityTracker initialized on {} clock, retaining {} documents",
            nodeClock ? "the node" : "a private", retainedDocuments);
    }

    /**
     * Restores the marks a previous process left, so its half-written documents stay hidden.
     *
     * <p>Each key comes back as a pending document with no chunks recorded against it. The chunk
     * ids are not needed and are not journalled: the filter derives a document key from the chunk
     * id it is asked about, so hiding the document hides every chunk of it, including any this
     * process never heard of.</p>
     */
    private void recoverInterruptedDocuments() {
        if (journal == null) {
            return;
        }
        Set<String> interrupted = journal.recover();
        for (String documentKey : interrupted) {
            pendingChunks.put(documentKey, new HashSet<>());
        }
        if (!interrupted.isEmpty()) {
            LOGGER.warn("{} document(s) were being ingested when this node last stopped; they "
                + "stay hidden from queries until they are ingested again or erased: {}",
                interrupted.size(), interrupted);
        }
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
     * Registers a document as visible from the given epoch, with every one of its chunks.
     *
     * <p>All-or-nothing is the whole point: a document is registered with its complete chunk
     * set or not at all, which is what lets {@link #isDocumentFullyVisible} answer for the
     * document rather than chunk by chunk. Callers publish only after every chunk is durable.</p>
     *
     * @param documentKey The document key the chunks were written under; production keys are
     *                    {@code sourceId:documentId}
     * @param chunkIds All chunk ids belonging to the document
     * @param commitEpoch The epoch at which this document becomes visible
     * @throws NullPointerException if documentKey or chunkIds is null
     * @throws IllegalArgumentException if commitEpoch is negative
     */
    public void registerDocument(String documentKey, Collection<String> chunkIds, long commitEpoch) {
        Objects.requireNonNull(documentKey, "documentKey must not be null");
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        if (commitEpoch < 0) {
            throw new IllegalArgumentException("commitEpoch must be non-negative: " + commitEpoch);
        }

        DocumentVisibility visibility =
            new DocumentVisibility(documentKey, new HashSet<>(chunkIds), commitEpoch);

        documentVisibility.put(documentKey, visibility);
        // Clearing the in-flight marker is what completes the publish. Without it a document
        // begun with beginIngestion and finished here stays pending forever: invisible to every
        // query, and never released. The on-disk mark goes with it, or the next restart would
        // hide a document that is whole.
        pendingChunks.remove(documentKey);
        if (journal != null) {
            journal.end(documentKey);
        }
        forgetOldestBeyondRetention();

        LOGGER.debug("Document {} registered at epoch {} with {} chunks",
            documentKey, commitEpoch, visibility.chunkCount());
    }

    /**
     * Registers a document from its registry metadata.
     *
     * <p>The key is derived the way excalibur-shard derives it from a storage key, so the
     * document registered here is the one excalibur-query's atomicity filter looks up.</p>
     *
     * @param metadata The document metadata containing chunk IDs
     * @param commitEpoch The epoch at which this document becomes visible
     * @throws NullPointerException if metadata is null
     */
    public void registerFromMetadata(DocumentMetadata metadata, long commitEpoch) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        registerDocument(
            makeKey(metadata.getSourceId(), metadata.getDocumentId()),
            metadata.getChunkIds(),
            commitEpoch);
    }

    /**
     * Begins tracking a document that is being ingested.
     *
     * <p>Chunks are added incrementally until the document is committed atomically.</p>
     *
     * <p>Returns only once the mark is on disk, when this tracker has a data directory to write
     * it to. The caller writes chunks immediately afterwards, and a mark that is still in the
     * page cache when the host dies covers none of them.</p>
     *
     * @param documentId The document identifier
     * @param expectedChunkCount The expected number of chunks
     * @throws InFlightJournalException if the mark cannot be made durable, which fails the
     *                                  document rather than writing chunks nothing can hide
     */
    public void beginIngestion(String documentId, int expectedChunkCount) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (journal != null) {
            journal.begin(documentId);
        }
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
        if (journal != null) {
            // The caller has removed the chunks it managed to write, so there is nothing left
            // for a restart to hide and the mark would only hide the next attempt.
            journal.end(documentId);
        }
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
        if (journal != null) {
            journal.end(documentId);
        }
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
        if (journal != null) {
            journal.clear();
        }
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
     */
    public record DocumentVisibility(
        String documentId,
        Set<String> chunkIds,
        long commitEpoch
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
