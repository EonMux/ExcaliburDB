package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Re-keys a graph whose node attribution an older build wrote by document into one keyed by
 * chunk.
 *
 * <h2>Why the graph cannot simply be read as it is</h2>
 * <p>A data directory written before nodes were attributed to chunks holds mappings that name
 * only a document for every node. Served as they are, a vector hit names a document whose text
 * cannot be read back as one passage, never fuses with the lexical hit for the same chunk, and -
 * worst - may be the embedding of a version of the document that was replaced long ago: those
 * builds had no way to retire a superseded chunk's vector, so every re-post left one behind.
 * Nothing in the mapping tells the current node from the dead ones; they all name the same
 * document.</p>
 *
 * <h2>How the chunk is recovered</h2>
 * <p>From the segment the graph indexes. Every node is the embedding stored in some chunk's value,
 * copied bit for bit, and the segment holds each chunk's current value under its chunk id. So a
 * node whose vector equals the embedding a live chunk of its document holds <em>is</em> that
 * chunk's vector, and is re-keyed to that chunk id. A node that equals no live chunk of its
 * document is the embedding of a value storage no longer holds - a superseded version, a surplus
 * chunk a shorter replacement dropped, or a document erased before erasure reached vectors - and
 * is retired: there is nothing it could be named as that would be true.</p>
 *
 * <p>Matching is by exact equality of the stored floats, not by distance. Two chunks with
 * identical text have identical embeddings and either may take either node, which is harmless
 * because the vectors are the same; any other match would be a guess. Each live chunk claims at
 * most one node, so a chunk re-posted with unchanged text - two identical nodes, one value - keeps
 * one vector and retires the duplicate.</p>
 *
 * <h2>Cost and ordering</h2>
 * <p>One pass over the segment's live entries and one read of each attributed node's vector,
 * bucketed by a hash of its floats so each entry is compared against the handful of nodes that
 * could equal it rather than every node of its document. No graph insert, so it is far cheaper
 * than the rebuild a compaction or a split performs.</p>
 *
 * <p>The caller must run it before anything else attributes a node in the graph - the integration
 * worker runs it as the first step for a segment, on its own thread - because a mapping mixing
 * both kinds of key could be persisted as neither. A graph compaction renumbers nodes, so one that
 * lands while the entries are being read makes the numbering this pass collected meaningless; the
 * pass then changes nothing and reports that it should be run again. Everything it does change,
 * it changes under the graph's write lock, and the retirements reach storage before the re-keyed
 * mapping does: a crash in between leaves a document-keyed mapping over a graph with some dead
 * nodes already retired, and the next open repeats the pass to the same result.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless. Safe to run concurrently with queries and with erasures on the same graph; not with
 * another upgrade or with the integration worker's inserts, which the caller rules out.</p>
 */
public final class ChunkAttributionUpgrade {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkAttributionUpgrade.class);

    private ChunkAttributionUpgrade() {
        throw new AssertionError("Utility class");
    }

    /**
     * One live chunk of the segment a graph indexes: its id and the embedding its value holds.
     *
     * @param chunkId The storage key, {@code sourceId:documentId:chunk:N}
     * @param vector  The embedding stored with it
     */
    public record LiveChunk(String chunkId, float[] vector) {

        /**
         * @throws NullPointerException if either component is null
         */
        public LiveChunk {
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(vector, "vector must not be null");
        }
    }

    /**
     * What an upgrade did.
     *
     * @param ran       Whether the graph needed one and it completed; false both for a graph that
     *                  was already chunk-keyed and for a pass a compaction interrupted - see
     *                  {@link #retry}
     * @param retry     Whether the pass was abandoned because the graph was renumbered under it,
     *                  and should be run again
     * @param rekeyed   Nodes now attributed to a chunk
     * @param retired   Nodes retired because no live chunk holds their vector
     */
    public record Outcome(boolean ran, boolean retry, int rekeyed, int retired) {

        /** A graph whose attribution already names chunks. */
        static final Outcome NOT_NEEDED = new Outcome(false, false, 0, 0);

        /** A pass abandoned because a compaction renumbered the graph during it. */
        static final Outcome RENUMBERED = new Outcome(false, true, 0, 0);
    }

    /**
     * Re-keys a document-keyed graph from the live chunks of its segment.
     *
     * @param graph      The graph; left alone unless its mapping is document-keyed
     * @param liveChunks Every live entry of the segment that carries an embedding, in any order
     * @return What was done
     * @throws NullPointerException if either argument is null
     */
    public static Outcome run(HnswGraph graph, Iterator<LiveChunk> liveChunks) {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(liveChunks, "liveChunks must not be null");

        NodeDocumentMapping mapping = graph.getNodeDocumentMapping();
        if (mapping == null || mapping.keying() == NodeDocumentMapping.Keying.CHUNK) {
            return Outcome.NOT_NEEDED;
        }

        long generation = graph.generation();
        Map<Long, String> legacy = mapping.getAllMappings();
        Map<String, Map<Integer, List<Long>>> candidates = candidatesByDocument(graph, legacy,
            generation);
        if (candidates == null) {
            return Outcome.RENUMBERED;
        }

        Map<Long, String> rekeyed = new HashMap<>(legacy.size() * 2);
        while (liveChunks.hasNext()) {
            LiveChunk chunk = liveChunks.next();
            Map<Integer, List<Long>> byHash =
                candidates.get(ChunkIdentifiers.documentKeyOfChunk(chunk.chunkId()));
            if (byHash == null) {
                continue;
            }
            Long node = claim(graph, byHash.get(Arrays.hashCode(chunk.vector())), chunk.vector(),
                generation);
            if (node != null) {
                rekeyed.put(node, chunk.chunkId());
            }
        }

        return apply(graph, mapping, legacy, rekeyed, generation);
    }

    /**
     * Groups the live attributed nodes by document, and within one by a hash of their vector.
     *
     * @return The groups, or null if the graph was renumbered while they were being read
     */
    private static Map<String, Map<Integer, List<Long>>> candidatesByDocument(
            HnswGraph graph, Map<Long, String> legacy, long generation) {
        Map<String, Map<Integer, List<Long>>> candidates = new HashMap<>();
        for (Map.Entry<Long, String> entry : legacy.entrySet()) {
            float[] vector = liveVector(graph, entry.getKey(), generation);
            if (graph.generation() != generation) {
                return null;
            }
            if (vector == null) {
                continue;
            }
            candidates.computeIfAbsent(entry.getValue(), k -> new HashMap<>())
                .computeIfAbsent(Arrays.hashCode(vector), k -> new ArrayList<>(1))
                .add(entry.getKey());
        }
        return candidates;
    }

    /**
     * Takes the first node of a bucket whose vector is exactly the chunk's, removing it so no
     * other chunk can claim it.
     *
     * @return The node, or null if none in the bucket matches
     */
    private static Long claim(HnswGraph graph, List<Long> bucket, float[] vector, long generation) {
        if (bucket == null) {
            return null;
        }
        for (Iterator<Long> it = bucket.iterator(); it.hasNext(); ) {
            long node = it.next();
            if (Arrays.equals(liveVector(graph, node, generation), vector)) {
                it.remove();
                return node;
            }
        }
        return null;
    }

    /**
     * Reads a node's vector if the node is live and the graph is still in the given generation.
     *
     * @return The vector, or null for a tombstoned node, an unknown one, or a renumbered graph
     */
    private static float[] liveVector(HnswGraph graph, long nodeId, long generation) {
        graph.lock.readLock().lock();
        try {
            if (graph.generation != generation) {
                return null;
            }
            HnswGraph.NodeView node = graph.readNodeUnderLock(nodeId);
            return node == null || node.isTombstoned() ? null : node.vector();
        } finally {
            graph.lock.readLock().unlock();
        }
    }

    /**
     * Retires what matched nothing and installs the chunk-keyed mapping.
     *
     * @return What was done, or {@link Outcome#RENUMBERED} if a compaction got there first
     */
    private static Outcome apply(HnswGraph graph, NodeDocumentMapping mapping,
                                 Map<Long, String> legacy, Map<Long, String> rekeyed,
                                 long generation) {
        int retired = 0;
        graph.lock.writeLock().lock();
        try {
            if (graph.generation != generation || graph.getNodeDocumentMapping() != mapping) {
                return Outcome.RENUMBERED;
            }
            for (Long node : legacy.keySet()) {
                if (!rekeyed.containsKey(node) && graph.delete(node)) {
                    retired++;
                }
            }
            // The retirements are made durable while the mapping on disk still names documents,
            // so no crash can leave a chunk-keyed mapping over nodes it no longer names that are
            // still live - which queries would drop as unnamed rather than as retired.
            graph.flush();
            mapping.rekeyToChunks(rekeyed);
            graph.lifecycle.persistDocumentMapping();
        } finally {
            graph.lock.writeLock().unlock();
        }

        LOGGER.info("Re-keyed the node attribution of {} from documents to chunks: {} node(s) "
            + "attributed to their chunk, {} retired because no live chunk holds their vector",
            graph.path(), rekeyed.size(), retired);
        return new Outcome(true, false, rekeyed.size(), retired);
    }
}
