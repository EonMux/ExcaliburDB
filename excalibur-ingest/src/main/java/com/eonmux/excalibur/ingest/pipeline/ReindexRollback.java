package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.registry.DocumentLocks;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Removes the chunks a reindex wrote for a document it could not go on to publish.
 *
 * <h2>Why leaving them is not the safe option</h2>
 * <p>A reindex writes a document's chunks one at a time and publishes at the end, and a failure
 * partway leaves the document on its previous version - the right answer, but not a complete one
 * on its own. Chunk ids are deterministic, so the writes that landed overwrote the previous
 * version's chunks in place and then wrote further ids past them. Those further ids are named by
 * nothing: the registry entry that survives is the previous one, and it is the only map from a
 * document to its chunks. The document's key is visibility-tracked, so the atomicity filter lets
 * such a chunk through and a read returns content from a version that was never published; and a
 * later erasure works from the registry, so it never learns the chunk exists.</p>
 *
 * <h2>Why only the surplus</h2>
 * <p>The ids the surviving entry still names hold content the corpus is expected to have. They
 * hold the new version's text rather than the old, which is the unavoidable cost of writing in
 * place and is exactly why the document stays classified as changed for the next run to replace
 * properly. Deleting them would take the document's content away while its entry went on
 * promising it, which is worse than the state being undone.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless beyond the collaborators it is given, each of which is itself thread-safe.</p>
 */
final class ReindexRollback {

    private static final Logger LOG = LoggerFactory.getLogger(ReindexRollback.class);

    /** Takes retired chunk ids out of the lexical index, on a node that keeps one. */
    @FunctionalInterface
    interface LexicalRetirement {

        /**
         * Stops the index returning these chunks.
         *
         * @param chunkIds The chunks to retire
         * @return How many the index found and removed
         */
        int retire(Collection<String> chunkIds);
    }

    private final DocumentRegistry registry;
    private final UpsertHandler upsertHandler;
    private final LexicalRetirement lexical;

    /**
     * @param registry      Where the surviving entry says which chunk ids are still accounted for
     * @param upsertHandler Removes a chunk from storage
     * @param lexical       Removes a chunk from the lexical index
     * @throws NullPointerException if any argument is null
     */
    ReindexRollback(DocumentRegistry registry, UpsertHandler upsertHandler,
                    LexicalRetirement lexical) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.upsertHandler =
            Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");
        this.lexical = Objects.requireNonNull(lexical, "lexical must not be null");
    }

    /**
     * Removes every chunk this attempt wrote that no registry entry names.
     *
     * @param sourceId        The source the document belongs to
     * @param documentId      The document that could not be published
     * @param writtenChunkIds The chunk ids this attempt wrote
     * @return How many chunks were removed
     * @throws NullPointerException if any argument is null
     */
    int undo(String sourceId, String documentId, List<String> writtenChunkIds) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(writtenChunkIds, "writtenChunkIds must not be null");

        // Which ids are orphans is decided by the registry entry, and that entry can be
        // rewritten by a publication of the same document, so the read and the deletes are one
        // step under the document's lock. Without it this could delete an id a publication had
        // just claimed.
        return DocumentLocks.shared().inDocument(
            ChunkIdentifiers.documentKey(sourceId, documentId),
            () -> removeOrphans(sourceId, documentId, writtenChunkIds));
    }

    /** Removes the written ids no entry accounts for, with the document's lock held. */
    private int removeOrphans(String sourceId, String documentId, List<String> writtenChunkIds) {
        List<String> orphans = orphansOf(sourceId, documentId, writtenChunkIds);
        if (orphans.isEmpty()) {
            return 0;
        }

        // Out of the index before out of storage: a chunk that has lost its text but is still
        // ranked answers hits nothing can resolve, where one still in storage and no longer
        // ranked is simply unreachable.
        lexical.retire(orphans);
        int removed = upsertHandler.tombstoneChunks(orphans);
        LOG.warn("Replacing {}:{} failed after writing {} chunk(s); {} of them were named by no "
                + "registry entry and have been removed", sourceId, documentId,
            writtenChunkIds.size(), removed);
        return removed;
    }

    /** The written ids the document's surviving registry entry does not account for. */
    private List<String> orphansOf(String sourceId, String documentId,
                                   List<String> writtenChunkIds) {
        DocumentMetadata surviving = registry.get(sourceId, documentId);
        Set<String> named = surviving == null
            ? Set.of()
            : new HashSet<>(surviving.getChunkIds());

        List<String> orphans = new ArrayList<>();
        for (String chunkId : writtenChunkIds) {
            if (!named.contains(chunkId)) {
                orphans.add(chunkId);
            }
        }
        return orphans;
    }
}
