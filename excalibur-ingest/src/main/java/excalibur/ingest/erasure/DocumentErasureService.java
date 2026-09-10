package excalibur.ingest.erasure;

import excalibur.ingest.lexical.ChunkIndex;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import io.excalibur.core.text.ChunkIdentifiers;
import io.excalibur.retrieval.lexical.native_.CompactionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Removes a named document from everything this node holds it in.
 *
 * <h2>Why this is not a reindex</h2>
 * <p>A document also disappears when its source stops listing it and the source is reindexed, and
 * that path removes the same things. But it depends on the source: a document erased because
 * someone asked for it to be erased may still be sitting in the source, and waiting for a crawl to
 * notice is not a reply anyone can act on. This is the direct instruction, and it answers with what
 * it actually removed.</p>
 *
 * <h2>Order, and what a crash leaves</h2>
 * <p>Storage first, then the lexical index, then the registry. There is no journal spanning the
 * three, so the order is chosen by what a crash between any two of them leaves behind:</p>
 * <ol>
 *   <li><strong>Storage</strong> holds the chunk's text and its vector. It is the only place the
 *       content itself lives, so it goes first: a crash after this point leaves no content
 *       anywhere, only index entries that resolve to nothing.</li>
 *   <li><strong>The lexical index</strong> holds postings and the chunk's name. What a run has
 *       buffered is published first, so the erasure can reach everything the index holds rather
 *       than everything it happens to have made searchable. Erasing then records a durable
 *       obligation and hides the chunk immediately; a compaction removes it from disk.</li>
 *   <li><strong>The registry</strong> holds the list of chunk ids the first two steps need, so it
 *       is removed last. That is what makes the request re-runnable: until the registry entry is
 *       gone, repeating the request finishes whatever an interruption left undone.</li>
 * </ol>
 *
 * <h2>What it deliberately does not touch</h2>
 * <p>The membership filter. It indexes <em>terms</em>, not chunk ids, and a term is almost always
 * shared with live documents; removing entries for an erased document would make the filter deny
 * terms that other documents still contain, and the query pipeline treats a filter negative as
 * ground truth. A filter entry left behind costs one segment scan that finds nothing, which is the
 * safe direction to be wrong in. Segment filters are rebuilt when the segment is compacted.</p>
 *
 * <h2>What is left until a segment is compacted</h2>
 * <p>Disk, not answers. The graph node of an erased chunk is retired as part of the erasure, so no
 * search traverses it or ranks it; the space it occupies is reclaimed when its graph is compacted.
 * Its entry in the LSH pre-filter is left alone, because that index only narrows which segments
 * and candidates a search looks at - it cannot produce a result of its own, so an entry left
 * behind costs a candidate the graph then skips. Segment filters and LSH tables are rebuilt when
 * the segment is compacted.</p>
 *
 * <h2>What it cannot reach</h2>
 * <p>The source. This removes what the node holds, not what the source serves: a document erased
 * here and still present in the directory or website it was crawled from comes back on the next
 * re-crawl. An obligation about content therefore has two halves, and only one of them is this
 * node's to keep - which is why erasing a whole source is worth pairing with taking the source out
 * of the crawl.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use; the components it calls carry their own guarantees. Two requests for
 * the same document are safe and the second finds nothing left to do.</p>
 */
public final class DocumentErasureService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentErasureService.class);

    /**
     * What an erasure removed.
     *
     * @param documentsErased    Documents found and removed
     * @param documentsNotFound  Documents this node has no record of
     * @param documentsRemaining Documents of this source still held, for a request that was
     *                           bounded rather than exhaustive
     * @param chunksTombstoned   Chunks removed from storage
     * @param chunksUnindexed    Chunks removed from the lexical index
     * @param vectorsHidden      Graph nodes retired, so no search ranks the document again
     * @param compacted          Whether the index was rewritten, so the chunks are off disk
     * @param chunksReclaimed    Chunks physically removed by that rewrite
     * @param outstanding        Erasure requests this node still owes, including this one
     * @param detail             Why the compaction ran, or why it did not
     */
    public record Outcome(int documentsErased, int documentsNotFound, int documentsRemaining,
                          int chunksTombstoned, int chunksUnindexed, int vectorsHidden,
                          boolean compacted, int chunksReclaimed, int outstanding, String detail) {

        public Outcome {
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }

    /**
     * Retires the graph nodes of one document.
     *
     * <p>Named by document rather than by chunk because that is how a node is attributed: the
     * integration worker records the document a node came from as it inserts, and nothing maps a
     * node back to the chunk id the registry holds.</p>
     */
    @FunctionalInterface
    public interface VectorEraser {

        /**
         * Retires every graph node attributed to a document.
         *
         * @param documentKey The document, as {@link ChunkIdentifiers#documentKey(String, String)}
         *                    forms it
         * @return The number of nodes retired
         */
        int erase(String documentKey);
    }

    private final DocumentRegistry registry;
    private final UpsertHandler.StorageAdapter storage;
    private final ChunkIndex lexicalIndex;
    private final Function<String, String> storedText;

    /**
     * Where a document's publication epoch is remembered, or null when this node tracks none.
     *
     * <p>An entry for a document whose chunks are gone describes nothing. It cannot make an erased
     * chunk reappear - the query pipeline resolves a result's text from storage, and there is none
     * - but it is state the erasure should be taking with it rather than leaving to accumulate.</p>
     */
    private final DocumentVisibilityTracker visibility;

    /**
     * Retires the erased document's graph nodes, or null on a node that keeps no vector index.
     *
     * <p>A node carries no text, so leaving one behind reads as harmless. It is not: a search that
     * ranks it says the document matched the query, with a distance and the document's name, which
     * is the fact the request asked to remove. Nothing else can reach the graphs - the registry
     * names chunks, and a node is attributed to a document.</p>
     */
    private final VectorEraser vectorEraser;

    /**
     * Creates the service.
     *
     * @param registry     Where a document's chunk ids are recorded
     * @param storage      Removes a chunk from storage by key
     * @param lexicalIndex The lexical index, or null on a node that keeps none
     * @param storedText   Reads a surviving chunk's text back for the index's compaction. Required
     *                     whenever an index is given: without it an erasure could be hidden and
     *                     never reclaimed, which is the half of the promise that is a promise
     * @throws NullPointerException     if registry or storage is null
     * @throws IllegalArgumentException if an index is given without a text supplier
     */
    public DocumentErasureService(DocumentRegistry registry,
                                  UpsertHandler.StorageAdapter storage,
                                  ChunkIndex lexicalIndex,
                                  Function<String, String> storedText) {
        this(registry, storage, lexicalIndex, storedText, null, null);
    }

    /**
     * Creates the service, also clearing an erased document's visibility state.
     *
     * @param registry   Where a document's chunk ids are recorded
     * @param storage    Removes a chunk from storage by key
     * @param lexicalIndex The lexical index, or null on a node that keeps none
     * @param storedText Reads a surviving chunk's text back for the index's compaction
     * @param visibility Where documents' publication epochs are tracked, or null for none
     * @throws NullPointerException     if registry or storage is null
     * @throws IllegalArgumentException if an index is given without a text supplier
     */
    public DocumentErasureService(DocumentRegistry registry,
                                  UpsertHandler.StorageAdapter storage,
                                  ChunkIndex lexicalIndex,
                                  Function<String, String> storedText,
                                  DocumentVisibilityTracker visibility) {
        this(registry, storage, lexicalIndex, storedText, visibility, null);
    }

    /**
     * Creates the service, also retiring an erased document's vectors.
     *
     * @param registry     Where a document's chunk ids are recorded
     * @param storage      Removes a chunk from storage by key
     * @param lexicalIndex The lexical index, or null on a node that keeps none
     * @param storedText   Reads a surviving chunk's text back for the index's compaction
     * @param visibility   Where documents' publication epochs are tracked, or null for none
     * @param vectorEraser Retires the document's graph nodes, or null on a node that keeps no
     *                     vector index. Without one a vector query still ranks the erased
     *                     document: no text, but its name and a distance
     * @throws NullPointerException     if registry or storage is null
     * @throws IllegalArgumentException if an index is given without a text supplier
     */
    public DocumentErasureService(DocumentRegistry registry,
                                  UpsertHandler.StorageAdapter storage,
                                  ChunkIndex lexicalIndex,
                                  Function<String, String> storedText,
                                  DocumentVisibilityTracker visibility,
                                  VectorEraser vectorEraser) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        if (lexicalIndex != null && storedText == null) {
            throw new IllegalArgumentException(
                "A lexical index needs a text supplier: without one an erased chunk can be hidden "
                    + "but never reclaimed");
        }
        this.lexicalIndex = lexicalIndex;
        this.storedText = storedText;
        this.visibility = visibility;
        this.vectorEraser = vectorEraser;
    }

    /**
     * Erases documents of one source.
     *
     * @param sourceId    The source they were ingested under
     * @param documentIds The documents to erase
     * @param compactNow  Rewrite the index immediately rather than leaving it to the policy. For a
     *                    caller who has to be able to say the data is gone rather than that its
     *                    removal is scheduled; it costs a rewrite of every segment holding a
     *                    tombstone
     * @return What was removed
     * @throws NullPointerException if any argument is null
     */
    public Outcome erase(String sourceId, Collection<String> documentIds, boolean compactNow) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentIds, "documentIds must not be null");

        List<String> named = new ArrayList<>();
        int notFound = 0;
        for (String documentId : new LinkedHashSet<>(documentIds)) {
            if (registry.contains(sourceId, documentId)) {
                named.add(documentId);
            } else {
                // Not an error, and not silence either. A caller under an obligation needs to know
                // this node never held the document rather than assume it was removed.
                notFound++;
            }
        }
        return remove(sourceId, named, notFound, 0, compactNow);
    }

    /**
     * Erases what this node holds of a whole source, up to a bound.
     *
     * <p>For a caller who wants everything a source contributed and has no list of its documents -
     * which is the usual shape of an obligation about a person rather than a file. The node has the
     * list; requiring the caller to supply it would mean asking them to know something only the
     * node knows.</p>
     *
     * <p>Bounded, and the outcome says how many are left. The work is a storage delete and an index
     * probe per chunk, so a source of ten million documents is not an HTTP request - it is a loop
     * of them, and reporting the remainder is what lets a caller run that loop and know when it is
     * finished. Nothing is lost by stopping early: what was erased is erased.</p>
     *
     * @param sourceId   The source to erase
     * @param limit      Most documents to erase in this call
     * @param compactNow Rewrite the index immediately rather than leaving it to the policy
     * @return What was removed, and how much of the source is still held
     * @throws NullPointerException     if sourceId is null
     * @throws IllegalArgumentException if limit is not positive
     */
    public Outcome eraseSource(String sourceId, int limit, boolean compactNow) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        List<String> batch = new ArrayList<>(limit);
        for (String documentId : registry.getDocumentIds(sourceId)) {
            if (batch.size() == limit) {
                break;
            }
            batch.add(documentId);
        }
        int remaining = Math.max(0, registry.size(sourceId) - batch.size());
        return remove(sourceId, batch, 0, remaining, compactNow);
    }

    /** Removes documents the registry is known to hold, in the order the class javadoc gives. */
    private Outcome remove(String sourceId, List<String> documentIds, int notFound, int remaining,
                           boolean compactNow) {
        List<String> chunkIds = new ArrayList<>();
        for (String documentId : documentIds) {
            DocumentMetadata metadata = registry.get(sourceId, documentId);
            if (metadata != null) {
                chunkIds.addAll(metadata.getChunkIds());
            }
        }

        int tombstoned = tombstone(chunkIds);
        int unindexed = unindex(chunkIds);
        int hidden = 0;
        for (String documentId : documentIds) {
            String documentKey = ChunkIdentifiers.documentKey(sourceId, documentId);
            hidden += hideVectors(documentKey);
            registry.remove(sourceId, documentId);
            if (visibility != null) {
                visibility.removeDocument(documentKey);
            }
        }
        persistRegistry();

        CompactionResult compaction = reclaim(compactNow);
        LOGGER.info("Erased {} document(s) of source {}: {} chunk(s) removed from storage, {} from "
                + "the lexical index, {} graph node(s) retired, {} not found, {} still held",
            documentIds.size(), sourceId, tombstoned, unindexed, hidden, notFound, remaining);
        return new Outcome(documentIds.size(), notFound, remaining, tombstoned, unindexed, hidden,
            compaction.performed(), compaction.documentsRemoved(), outstanding(),
            compaction.reason());
    }

    /**
     * Retires the document's graph nodes, so no search ranks it again.
     *
     * @param documentKey The document, as node attribution names it
     * @return The number of nodes retired
     */
    private int hideVectors(String documentKey) {
        if (vectorEraser == null) {
            return 0;
        }
        try {
            return vectorEraser.erase(documentKey);
        } catch (RuntimeException e) {
            // Reported by the count rather than thrown, for the same reason a chunk that will not
            // delete does not stop the rest: the content leaving storage is the half of the
            // promise this node can always keep, and losing it here would be the worse outcome.
            LOGGER.warn("Vectors of document {} stay searchable: {}", documentKey, e.getMessage());
            return 0;
        }
    }

    /**
     * Writes the registry, so the removal survives a restart.
     *
     * <p>The chunks are gone from storage either way; what a lost write costs is an entry naming
     * them, which would read as a document this node still holds and could not produce.</p>
     */
    private void persistRegistry() {
        try {
            registry.persist();
        } catch (IOException e) {
            LOGGER.warn("Document registry not persisted; the erased document would still be "
                + "listed after a restart, naming chunks that are gone: {}", e.getMessage());
        }
    }

    /** Removes each chunk from storage, which is the only place its content lives. */
    private int tombstone(List<String> chunkIds) {
        int removed = 0;
        for (String chunkId : chunkIds) {
            try {
                if (storage.delete(chunkId.getBytes(StandardCharsets.UTF_8))) {
                    removed++;
                }
            } catch (RuntimeException e) {
                // Reported by the shortfall between the counts rather than thrown: one chunk that
                // will not delete must not stop the rest of the document from being erased.
                LOGGER.warn("Chunk {} could not be removed from storage: {}",
                    chunkId, e.getMessage());
            }
        }
        return removed;
    }

    /** Records the obligation and hides the chunks from search. */
    private int unindex(List<String> chunkIds) {
        if (lexicalIndex == null || chunkIds.isEmpty()) {
            return 0;
        }
        try {
            // Published first, so everything the index holds can be reached. A chunk still sitting
            // in a run's buffer is not in any segment yet, and an erasure that could not see it
            // would report the document gone while the next refresh made it searchable.
            lexicalIndex.refresh();
            Set<String> keys = new LinkedHashSet<>(chunkIds);
            return lexicalIndex.erase(keys);
        } catch (IOException e) {
            LOGGER.error("{} erased chunk(s) remain lexically searchable: {}",
                chunkIds.size(), e.getMessage());
            return 0;
        }
    }

    /** Rewrites the index so the erased chunks leave disk, now or when the policy says so. */
    private CompactionResult reclaim(boolean now) {
        if (lexicalIndex == null) {
            return CompactionResult.declined("this node keeps no lexical index");
        }
        try {
            return now
                ? lexicalIndex.compactNow(storedText)
                : lexicalIndex.compactIfDue(storedText);
        } catch (IOException e) {
            LOGGER.warn("Erased chunks stay on disk until the next compaction: {}", e.getMessage());
            return CompactionResult.declined("compaction failed: " + e.getMessage());
        }
    }

    /** Erasure requests accepted and not yet discharged. */
    private int outstanding() {
        return lexicalIndex == null ? 0 : lexicalIndex.stats().erasureRequests();
    }
}
