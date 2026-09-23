package com.eonmux.excalibur.ingest.erasure;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.registry.DocumentLocks;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;

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
 *       is removed last, and only for a document whose chunks the first two steps actually
 *       released. That is what makes the request re-runnable: until the registry entry is gone,
 *       repeating the request finishes whatever an interruption left undone. Dropping the entry
 *       over content that is still there would do the opposite - the entry is the only map from
 *       a document to its chunk ids, so what remains would become content no request can name.
 *       </li>
 * </ol>
 *
 * <h2>What is left until a segment is compacted</h2>
 * <p>Disk, not answers. The graph node of an erased chunk is retired as part of the erasure, so no
 * search traverses it or ranks it; the space it occupies is reclaimed when its graph is compacted.
 * Its entry in the LSH index is left alone, because that index only names extra nodes for a search
 * to measure beside its graph walk - it cannot produce a result of its own, and a named node that
 * is no longer active is skipped when measured, so an entry left behind costs one node read.
 * LSH tables are rebuilt when the segment is compacted.</p>
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
 * the same document are safe and the second finds nothing left to do. Each document is removed
 * under its {@link DocumentLocks} entry, which is also what ingestion publishes under: without
 * that, a publication landing in the middle of an erasure either restores the registry and
 * visibility entries for chunks that are gone, or leaves its own chunks in storage with no entry
 * naming them.</p>
 */
public final class DocumentErasureService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentErasureService.class);

    /**
     * What an erasure removed.
     *
     * @param erasedDocumentIds   The documents this node held and no longer holds any part of,
     *                            by their id within the source. Named and not merely counted
     *                            because the erasure of a document has a second half this
     *                            service does not perform: the facts extracted from it are in
     *                            the graph, addressed by a provenance id derived from the
     *                            document's key, and a caller withdrawing those has to know
     *                            which documents actually went. A source-scoped request never
     *                            knew - the node chose the batch
     * @param documentsIncomplete Documents this node still holds part of: something refused to
     *                            release their content, so their registry entry was kept and the
     *                            request can be repeated
     * @param documentsNotFound   Documents this node has no record of
     * @param documentsRemaining  Documents of this source still held, for a request that was
     *                            bounded rather than exhaustive
     * @param chunksTombstoned    Chunks removed from storage
     * @param chunksUnindexed     Chunks removed from the lexical index
     * @param chunksNotRemoved    Chunks storage would not release, so their content is still
     *                            on disk
     * @param chunksStillSearchable Chunks the lexical index still matches. Counted apart from
     *                            {@code chunksNotRemoved} rather than folded into it, because a
     *                            chunk can be in both states at once and one number for the two
     *                            reports twice as much content left behind as there is
     * @param vectorsHidden       Graph nodes retired, so no search ranks the document again
     * @param compacted           Whether the chunks are off disk: true exactly when the index
     *                            owes no erasure at all and every chunk this request reached
     *                            has left it, whether or not this request's compaction was the
     *                            one that reclaimed them; a compaction that ran and abandoned
     *                            the group holding them is not this, and nor is an index that
     *                            refused the erasure outright, which leaves nothing owed because
     *                            it recorded nothing. Always false on a node with no lexical
     *                            index
     * @param chunksReclaimed     Chunks physically removed by that rewrite
     * @param outstanding         Erasure requests this node still owes, including this one
     * @param detail              Why the compaction ran, or why it did not
     */
    public record Outcome(List<String> erasedDocumentIds, int documentsIncomplete,
                          int documentsNotFound, int documentsRemaining, int chunksTombstoned,
                          int chunksUnindexed, int chunksNotRemoved, int chunksStillSearchable,
                          int vectorsHidden, boolean compacted, int chunksReclaimed,
                          int outstanding, String detail) {

        public Outcome {
            Objects.requireNonNull(detail, "detail must not be null");
            erasedDocumentIds = List.copyOf(erasedDocumentIds);
        }

        /**
         * How many documents this node held and no longer holds any part of.
         *
         * @return The count, which is exactly what {@link #erasedDocumentIds()} lists
         */
        public int documentsErased() {
            return erasedDocumentIds.size();
        }
    }

    /**
     * Retires graph nodes, by document or by chunk.
     *
     * <p>A document is erased by document rather than by its chunks: a node is attributed to its
     * chunk, but the registry names only the chunks of the current version, and a node of an
     * earlier version the integration worker has not yet superseded belongs to the document all
     * the same. Every graph indexes its nodes by document for exactly this call. Retiring by chunk
     * is for the chunks a replacement no longer reaches, where the document itself stays.</p>
     */
    public interface VectorEraser {

        /**
         * Retires every graph node attributed to a chunk of a document.
         *
         * @param documentKey The document, as {@link ChunkIdentifiers#documentKey(String, String)}
         *                    forms it
         * @return The number of nodes retired
         */
        int erase(String documentKey);

        /**
         * Retires every graph node attributed to one of the named chunks.
         *
         * <p>Only for chunks that are about to be tombstoned in storage, and called before they
         * are. A value written under one of these ids before the call is deleted by that tombstone
         * too, and one written after it is a node this call never sees; either way the graph ends
         * up holding what storage does. Called for an id that stays in storage, it would retire a
         * vector nothing inserts again.</p>
         *
         * @param chunkIds The chunk ids, as {@link ChunkIdentifiers#chunkId} forms them
         * @return The number of nodes retired
         */
        int eraseChunks(Collection<String> chunkIds);
    }

    /**
     * Where a document's erasure is serialised against its publication.
     *
     * <p>The table is the process's, because so is what it guards: one storage engine, one
     * registry, one visibility tracker.</p>
     */
    private final DocumentLocks locks = DocumentLocks.shared();

    private final DocumentRegistry registry;
    private final UpsertHandler.StorageAdapter storage;
    private final ChunkIndex lexicalIndex;
    private final Function<String, String> storedText;

    /**
     * Set by {@link #requestStop()}, and read by a compaction this service is running at each of
     * its boundaries.
     */
    private volatile boolean stopRequested;

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
     * is the fact the request asked to remove. The chunk tombstones reach the graphs too, but only
     * once the integration worker replays them, which is after the caller has been answered.</p>
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

    /**
     * Removes documents the registry is known to hold, in the order the class javadoc gives.
     *
     * <p>Document by document, each under its own lock rather than as one batch of storage
     * deletes. The registry entry a document is dropped from is also the list this reads to know
     * what to delete, so the read and the removal have to be one step that no publication can
     * land in the middle of.</p>
     */
    private Outcome remove(String sourceId, List<String> documentIds, int notFound, int remaining,
                           boolean compactNow) {
        // Published once for the whole request, before anything is removed. A chunk still sitting
        // in a run's buffer is in no segment yet, and an erasure that could not see it would
        // report the document gone while the next refresh made it searchable again.
        boolean indexReachable = publishBufferedChunks();

        Removed removed = Removed.NOTHING;
        for (String documentId : documentIds) {
            removed = removed.and(removeOne(sourceId, documentId, indexReachable));
        }
        persistRegistry();

        CompactionResult compaction = reclaim(compactNow);
        report(sourceId, removed, notFound, remaining);
        // Whether the chunks are off disk is what the index still owes, not whether a compaction
        // ran: one can run and publish a rewrite that abandoned the very group holding these
        // chunks, and one can decline because an earlier pass already reclaimed everything.
        // Nor is nothing owed on its own enough. An index whose erase threw recorded no
        // obligation for those chunks, so it owes nothing for them and still matches every one:
        // reporting them compacted would tell the caller the one thing that is not true.
        int outstanding = outstanding();
        boolean compacted = lexicalIndex != null && outstanding == 0
            && removed.stillSearchable() == 0;
        return new Outcome(removed.erasedIds(), removed.incomplete(),
            notFound + removed.notFound(), remaining, removed.tombstoned(), removed.unindexed(),
            removed.notRemoved(), removed.stillSearchable(), removed.vectorsHidden(),
            compacted, compaction.documentsRemoved(), outstanding, compaction.reason());
    }

    /**
     * Removes one document, with nothing else able to rewrite it while this runs.
     *
     * <p>The registry and visibility entries go only for a document whose content actually left
     * storage and the index. Removing them over chunks that are still there does not erase those
     * chunks - it makes them unreachable, because the entry is the only thing that names them -
     * and the reply would have counted the document as erased.</p>
     */
    private Removed removeOne(String sourceId, String documentId, boolean indexReachable) {
        String documentKey = ChunkIdentifiers.documentKey(sourceId, documentId);
        return locks.inDocument(documentKey, () -> {
            DocumentMetadata metadata = registry.get(sourceId, documentId);
            if (metadata == null) {
                // Removed by a request that overlapped this one. Counted as never held rather
                // than as erased, so two concurrent requests cannot both claim the same document.
                return Removed.NOT_FOUND;
            }

            List<String> chunkIds = metadata.getChunkIds();
            Tombstoned stored = tombstone(chunkIds);
            Unindexed lexical = unindex(chunkIds, indexReachable);
            int vectors = hideVectors(documentKey);

            if (!stored.failed().isEmpty() || !lexical.complete()) {
                // The two counts measure different things and are deliberately not added up: a
                // chunk storage released but the index could not is out of storage and still
                // searchable, so it belongs in the second and not the first. Folding them into
                // one number made a 12-chunk document report 24 chunks left behind.
                int held = stored.failed().size();
                int searchable = lexical.complete() ? 0 : chunkIds.size();
                LOGGER.error("Document {} has not been erased: of its {} chunk(s), {} are still "
                        + "in storage and {} are still searchable, so its registry entry is kept "
                        + "and the request can be repeated",
                    documentKey, chunkIds.size(), held, searchable);
                return new Removed(List.of(), 1, 0, stored.removed(), lexical.erased(), held,
                    searchable, vectors);
            }

            registry.remove(sourceId, documentId);
            if (visibility != null) {
                visibility.removeDocument(documentKey);
            }
            return Removed.erased(documentId, stored.removed(), lexical.erased(), vectors);
        });
    }

    /** Says what left, and as plainly what did not. */
    private void report(String sourceId, Removed removed, int notFound, int remaining) {
        LOGGER.info("Erased {} document(s) of source {}: {} chunk(s) removed from storage, {} from "
                + "the lexical index, {} graph node(s) retired, {} not found, {} still held",
            removed.documents(), sourceId, removed.tombstoned(), removed.unindexed(),
            removed.vectorsHidden(), notFound + removed.notFound(), remaining);
        if (removed.incomplete() > 0) {
            LOGGER.error("{} document(s) of source {} were not erased: {} chunk(s) remain in "
                    + "storage and {} remain searchable, and their registry entries are kept so "
                    + "the request can be repeated",
                removed.incomplete(), sourceId, removed.notRemoved(), removed.stillSearchable());
        }
    }

    /**
     * What removing documents achieved, so the reply is assembled from what happened rather than
     * from what was asked for.
     *
     * @param erasedIds     Documents no part of which this node still holds, by id
     * @param incomplete    Documents something refused to release, whose entries were kept
     * @param notFound      Documents that were gone before this request reached them
     * @param tombstoned    Chunks storage had and no longer has
     * @param unindexed     Chunks the lexical index found and removed
     * @param notRemoved    Chunks storage would not release, so their content is still on disk
     * @param stillSearchable Chunks the lexical index still matches, whether or not storage
     *                      released them. Counted apart from {@code notRemoved} because a chunk
     *                      can be in both states and adding the two would double it
     * @param vectorsHidden Graph nodes retired
     */
    private record Removed(List<String> erasedIds, int incomplete, int notFound, int tombstoned,
                           int unindexed, int notRemoved, int stillSearchable,
                           int vectorsHidden) {

        static final Removed NOTHING = new Removed(List.of(), 0, 0, 0, 0, 0, 0, 0);
        static final Removed NOT_FOUND = new Removed(List.of(), 0, 1, 0, 0, 0, 0, 0);

        /** One document that came out whole. */
        static Removed erased(String documentId, int tombstoned, int unindexed,
                              int vectorsHidden) {
            return new Removed(List.of(documentId), 0, 0, tombstoned, unindexed, 0, 0,
                vectorsHidden);
        }

        int documents() {
            return erasedIds.size();
        }

        Removed and(Removed other) {
            List<String> ids = new ArrayList<>(erasedIds.size() + other.erasedIds.size());
            ids.addAll(erasedIds);
            ids.addAll(other.erasedIds);
            return new Removed(ids, incomplete + other.incomplete,
                notFound + other.notFound, tombstoned + other.tombstoned,
                unindexed + other.unindexed, notRemoved + other.notRemoved,
                stillSearchable + other.stillSearchable,
                vectorsHidden + other.vectorsHidden);
        }
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
            // Reported by the count rather than thrown, and deliberately not a reason to keep the
            // registry entry: the chunk tombstones reach the graph through the write log whatever
            // happens here, so keeping it would not help a later attempt. The content leaving
            // storage is the half of the promise this node can always keep.
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

    /**
     * What removing a document's chunks from storage achieved.
     *
     * @param removed Chunks storage had and no longer has
     * @param failed  Chunks storage refused to release, which are therefore still there
     */
    private record Tombstoned(int removed, Set<String> failed) {}

    /**
     * Removes each chunk from storage, which is the only place its content lives.
     *
     * <p>A delete that reports nothing to delete counts as success: the chunk is not in storage,
     * which is the state being asked for, and that is what a repeat of a request that got partway
     * looks like. Only a delete that threw leaves the chunk where it was.</p>
     */
    private Tombstoned tombstone(List<String> chunkIds) {
        int removed = 0;
        Set<String> failed = new LinkedHashSet<>();
        for (String chunkId : chunkIds) {
            try {
                if (storage.delete(chunkId.getBytes(StandardCharsets.UTF_8))) {
                    removed++;
                }
            } catch (RuntimeException e) {
                // Collected rather than thrown: one chunk that will not delete must not stop the
                // rest of the document, and the document's entry is kept so the rest of the work
                // can be finished by repeating the request.
                LOGGER.warn("Chunk {} could not be removed from storage: {}",
                    chunkId, e.getMessage());
                failed.add(chunkId);
            }
        }
        return new Tombstoned(removed, failed);
    }

    /**
     * What removing a document's chunks from the lexical index achieved.
     *
     * @param erased   Chunks the index found and removed
     * @param complete Whether the index accepted the erasure at all. Nothing erased is not the
     *                 same answer: the index holds only what a refresh published, so a chunk it
     *                 never had is already in the state being asked for
     */
    private record Unindexed(int erased, boolean complete) {}

    /** Records the obligation and hides the chunks from search. */
    private Unindexed unindex(List<String> chunkIds, boolean indexReachable) {
        if (lexicalIndex == null || chunkIds.isEmpty()) {
            return new Unindexed(0, true);
        }
        if (!indexReachable) {
            return new Unindexed(0, false);
        }
        try {
            return new Unindexed(lexicalIndex.erase(new LinkedHashSet<>(chunkIds)), true);
        } catch (IOException e) {
            LOGGER.error("{} chunk(s) of an erased document remain lexically searchable: {}",
                chunkIds.size(), e.getMessage());
            return new Unindexed(0, false);
        }
    }

    /**
     * Makes everything the index has taken in reachable, so an erasure can remove all of it.
     *
     * @return false when the index could not be published, in which case nothing in this request
     *         can be said to have left it
     */
    private boolean publishBufferedChunks() {
        if (lexicalIndex == null) {
            return true;
        }
        try {
            lexicalIndex.refresh();
            return true;
        } catch (IOException e) {
            LOGGER.error("The lexical index could not be published, so this erasure cannot reach "
                + "what it has buffered: {}", e.getMessage());
            return false;
        }
    }

    /** Rewrites the index so the erased chunks leave disk, now or when the policy says so. */
    private CompactionResult reclaim(boolean now) {
        if (lexicalIndex == null) {
            return CompactionResult.declined("this node keeps no lexical index");
        }
        try {
            return now
                ? lexicalIndex.compactNow(storedText, () -> stopRequested)
                : lexicalIndex.compactIfDue(storedText, () -> stopRequested);
        } catch (IOException e) {
            // Logged with the cause, reported without it. This text is the erasure response's
            // "detail" field, and an IOException from the index is usually an absolute path -
            // the layout of the node's disk, which a client asking for its own document to be
            // removed has no business being told.
            LOGGER.warn("Erased chunks stay on disk until the next compaction", e);
            return CompactionResult.declined(
                "compaction failed; see the node's log for the reason");
        }
    }

    /**
     * Asks a compaction this service is running, and every one it would start, to end early.
     *
     * <h3>Why a shutdown needs this</h3>
     * <p>An erasure made with {@code compact: true} rewrites the lexical index on the request's
     * own thread, reading every surviving chunk's text back out of the storage engine, and on a
     * large index that outlasts any grace period a listener gives its requests. A shutdown that
     * closed storage underneath it would crash the process. So the shutdown asks first: the
     * rewrite ends at its next segment group or chunk, publishes nothing, and the request answers
     * with its erasure still owed - which a later compaction discharges. Hiding and removing the
     * documents is unaffected; only the reclaim is cut short.</p>
     *
     * <p>Cooperative rather than an interrupt, because an interrupt delivered inside file I/O
     * closes a channel the index shares with every query. Permanent: a service asked to stop is
     * one whose node is going away.</p>
     */
    public void requestStop() {
        stopRequested = true;
    }

    /** Erasure requests accepted and not yet discharged. */
    private int outstanding() {
        return lexicalIndex == null ? 0 : lexicalIndex.stats().erasureRequests();
    }
}
