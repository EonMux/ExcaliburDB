package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * to its chunk ids, so that tail is named by nothing: left in storage, in the lexical index or in
 * a graph it goes on matching queries, and no later erasure can find it by name.</p>
 *
 * <h2>Why the indexes before storage</h2>
 * <p>A chunk that has lost its text but is still ranked answers hits nothing can resolve, where
 * one still in storage and no longer ranked is simply unreachable. For the graph there is a second
 * reason, which is what makes the eager retirement safe to run beside the integration worker:
 * every value written under a surplus id before the retirement is also written before the storage
 * tombstone, so the tombstone's DELETE, replayed after that value's PUT, retires whatever node the
 * worker gives it later; a value written after the retirement is a node it never touches, and the
 * tombstone - or its absence - decides that node's fate exactly as it decides the chunk's.</p>
 *
 * <h2>The ids the replacement overwrote</h2>
 * <p>Their old nodes are not retired here: an id the replacement shares is live in storage, and
 * retiring by id would take the replacement's node as well if the worker had already inserted it.
 * The worker supersedes each of them instead, in log order, when it attributes the replacement's
 * node to the same id; until it does, a query's tail scan reads the replacement's PUT and sets the
 * graph's older node aside.</p>
 */
final class SupersededVersion {

    private static final Logger LOGGER = LoggerFactory.getLogger(SupersededVersion.class);

    private SupersededVersion() {
    }

    /**
     * Retires the previous version's chunks that the published version did not overwrite.
     *
     * @param previousChunkIds The chunk ids the previous version was registered with
     * @param currentChunkIds  The chunk ids the published version consists of
     * @param lexical          Takes chunks out of the lexical index
     * @param vectors          Retires the chunks' graph nodes, or null on a node that keeps no
     *                         vector index
     * @param upsertHandler    Tombstones chunks in storage
     * @return How many surplus chunks storage released
     * @throws NullPointerException if any argument but {@code vectors} is null
     */
    static int retire(List<String> previousChunkIds, Collection<String> currentChunkIds,
                      ReindexRollback.LexicalRetirement lexical,
                      DocumentErasureService.VectorEraser vectors,
                      UpsertHandler upsertHandler) {
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
        retireVectors(surplus, vectors);
        return upsertHandler.tombstoneObsoleteChunks(previousChunkIds, current);
    }

    /**
     * Retires the surplus chunks' graph nodes, reporting rather than throwing a refusal.
     *
     * <p>A graph that will not take the write costs nothing permanent: the storage tombstones
     * still reach its write log, and the integration worker retires the same nodes when it
     * replays them. Failing the retirement over it would leave the surplus in storage instead,
     * where nothing retires it.</p>
     */
    private static void retireVectors(List<String> surplus,
                                      DocumentErasureService.VectorEraser vectors) {
        if (vectors == null) {
            return;
        }
        try {
            vectors.eraseChunks(surplus);
        } catch (RuntimeException e) {
            LOGGER.warn("{} superseded chunk(s) stay rankable by vector search until the write "
                + "log retires them: {}", surplus.size(), e.getMessage());
        }
    }
}
