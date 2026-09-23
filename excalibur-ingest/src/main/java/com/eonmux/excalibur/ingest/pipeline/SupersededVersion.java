package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.registry.UpsertHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Retires what a document's previous version held once its replacement has been published.
 *
 * <h2>Why only after publication</h2>
 * <p>Retiring first is what makes a failed replacement destructive: the old chunks are gone and
 * nothing re-inserts them. Retiring after means a replacement that never publishes simply leaves
 * the previous version serving.</p>
 *
 * <h2>Why only the surplus</h2>
 * <p>Chunk ids are the document key and an index, so the new version has already overwritten
 * every id it shares with the old one. What is left is the tail a shorter version no longer
 * reaches. The registry entry now names the new version, and it is the only map from a document
 * to its chunk ids, so that tail is named by nothing: left in storage and in the lexical index it
 * goes on matching queries, and no later erasure can find it.</p>
 *
 * <h2>Why the lexical index first</h2>
 * <p>A chunk that has lost its text but is still ranked answers hits nothing can resolve, where
 * one still in storage and no longer ranked is simply unreachable.</p>
 *
 * <p>Graph nodes are not retired here. A node is attributed to a document rather than to a
 * chunk, so the only retirement available would take the replacement's nodes too, and nothing
 * would insert them again. A vector hit resolves its text from the document's chunks in storage,
 * which after this hold only the new version.</p>
 */
final class SupersededVersion {

    private SupersededVersion() {
    }

    /**
     * Retires the previous version's chunks that the published version did not overwrite.
     *
     * @param previousChunkIds The chunk ids the previous version was registered with
     * @param currentChunkIds  The chunk ids the published version consists of
     * @param lexical          Takes chunks out of the lexical index
     * @param upsertHandler    Tombstones chunks in storage
     * @return How many surplus chunks storage released
     * @throws NullPointerException if any argument is null
     */
    static int retire(List<String> previousChunkIds, Collection<String> currentChunkIds,
                      ReindexRollback.LexicalRetirement lexical, UpsertHandler upsertHandler) {
        Objects.requireNonNull(previousChunkIds, "previousChunkIds must not be null");
        Objects.requireNonNull(currentChunkIds, "currentChunkIds must not be null");
        Objects.requireNonNull(lexical, "lexical must not be null");
        Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");

        Set<String> current = new HashSet<>(currentChunkIds);
        List<String> surplus = new ArrayList<>();
        for (String chunkId : previousChunkIds) {
            if (!current.contains(chunkId)) {
                surplus.add(chunkId);
            }
        }
        if (surplus.isEmpty()) {
            return 0;
        }
        lexical.retire(surplus);
        return upsertHandler.tombstoneObsoleteChunks(previousChunkIds, current);
    }
}
