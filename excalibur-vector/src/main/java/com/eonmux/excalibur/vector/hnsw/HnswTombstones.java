package com.eonmux.excalibur.vector.hnsw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Soft deletion: the tombstone flag, what it costs to observe, and what happens to the entry
 * point when the node carrying it is retired.
 *
 * <h2>Why it is its own class</h2>
 * <p>A tombstone is one bit in a node index entry, but retiring a node is not one write. If the
 * node being retired is the entry point, the graph has to find a new one at the highest
 * remaining layer before anything else reads the header, or the next query descends from a
 * deleted node. Keeping the flag, the entry-point repair and the two ways of counting
 * tombstones in one place is what keeps that repair from being forgotten by a second caller
 * that only meant to set a bit.</p>
 *
 * <h2>Two ways to ask</h2>
 * <p>{@link #count()} scans every index entry, which is what a compaction planner wants and
 * what a per-query caller cannot afford. {@link #generation()} is the O(1) counterpart: it
 * counts flag <em>transitions</em>, so two equal samples are evidence that nothing was retired
 * between them. It lives in memory only - see {@link HnswGraph#tombstoneGeneration()} for
 * exactly what that does and does not promise.</p>
 *
 * <h2>Locking</h2>
 * <p>Retirement takes the graph's write lock, because it both edits the index and may move the
 * entry point; the observers take the read lock. The generation counter is an
 * {@link AtomicLong} incremented under the write lock, so it is also readable without any lock
 * at all.</p>
 */
final class HnswTombstones {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswTombstones.class);

    private final HnswGraph graph;

    /**
     * Counts tombstone flag transitions, so a reader can witness a retirement in O(1) rather
     * than through the O(node count) scan {@link #count()} performs.
     */
    private final AtomicLong tombstoneGeneration = new AtomicLong(0);

    /**
     * Creates the soft-delete collaborator for one graph.
     *
     * @param graph The graph whose node index carries the flags
     */
    HnswTombstones(HnswGraph graph) {
        this.graph = graph;
    }

    /**
     * Marks a node as deleted, repairing the entry point if the node was it.
     *
     * @param nodeId The ID of the node to delete
     * @return true if the node was found and the flag transitioned, false otherwise
     */
    boolean delete(long nodeId) {
        graph.validateNotClosed();

        graph.lock.writeLock().lock();
        try {
            // A negative id would compute an offset inside the header and the
            // tombstone write below would corrupt header fields.
            if (nodeId < 0 || nodeId >= graph.nodeIdGenerator.get()) {
                return false;
            }

            long indexOffset = HnswNodeRecords.indexOffset(nodeId);
            if (indexOffset + HnswNodeRecords.NODE_INDEX_ENTRY_SIZE > graph.dataRegionOffset) {
                return false;
            }

            int flags = HnswNodeRecords.readEntryFlags(graph.mappedFile, indexOffset);
            if ((flags & HnswNodeRecords.FLAG_TOMBSTONE) != 0) {
                return false; // Already tombstoned
            }

            // Set tombstone flag. The early return above means control only reaches here
            // when the flag actually transitions, which is what makes the generation below
            // a count of states rather than of calls.
            flags |= HnswNodeRecords.FLAG_TOMBSTONE;
            HnswNodeRecords.writeEntryFlags(graph.mappedFile, indexOffset, flags);
            tombstoneGeneration.incrementAndGet();

            // If we deleted the entry point, find a new one at the highest level
            // This maintains the HNSW invariant: entry point must be at the highest layer
            if (nodeId == graph.entryPointId) {
                long newEntryPoint = HnswNodeRecords.findHighestLevelActiveNode(
                    graph.mappedFile, graph.nodeCount);
                graph.entryPointId = newEntryPoint;
                if (newEntryPoint >= 0) {
                    HnswGraph.NodeView newEntry = graph.readNode(newEntryPoint);
                    if (newEntry != null) {
                        graph.maxLevel = newEntry.level();
                    }
                } else {
                    graph.maxLevel = -1;
                }
                HnswGraphHeader.writeEntryPoint(
                    graph.mappedFile, graph.entryPointId, graph.maxLevel);
            }

            LOGGER.debug("Tombstoned node {}", nodeId);
            return true;
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    /**
     * Retires every node a named document contributed.
     *
     * @param documentId The document key
     * @return The number of nodes newly tombstoned
     */
    int deleteDocument(String documentId) {
        graph.validateNotClosed();

        NodeDocumentMapping mapping = graph.nodeDocumentMapping;
        if (documentId == null || mapping == null) {
            return 0;
        }

        int retired = retireAll(mapping.getNodesForDocument(documentId), -1);
        if (retired > 0) {
            LOGGER.info("Retired {} node(s) of document {}", retired, documentId);
        }
        return retired;
    }

    /**
     * Retires every node attributed to any of the named chunks.
     *
     * @param chunkIds The chunk ids
     * @return The number of nodes newly tombstoned
     */
    int deleteChunks(Collection<String> chunkIds) {
        Objects.requireNonNull(chunkIds, "chunkIds cannot be null");
        graph.validateNotClosed();

        NodeDocumentMapping mapping = graph.nodeDocumentMapping;
        if (mapping == null) {
            return 0;
        }

        int retired = 0;
        for (String chunkId : chunkIds) {
            retired += retireAll(mapping.getNodesForChunk(chunkId), -1);
        }
        if (retired > 0) {
            LOGGER.debug("Retired {} node(s) of {} chunk(s)", retired, chunkIds.size());
        }
        return retired;
    }

    /**
     * Attributes a node to a chunk and retires every other node attributed to it.
     *
     * <p>Registered before the others are retired, so a reader that looks the chunk up in
     * between finds the new node among its nodes rather than none.</p>
     *
     * @param nodeId  The chunk's newest node
     * @param chunkId The chunk id
     * @return How many earlier nodes were newly retired
     */
    int attributeChunk(long nodeId, String chunkId) {
        graph.validateNotClosed();

        NodeDocumentMapping mapping = graph.nodeDocumentMapping;
        if (mapping == null) {
            return 0;
        }
        mapping.register(nodeId, chunkId);

        int retired = retireAll(mapping.getNodesForChunk(chunkId), nodeId);
        if (retired > 0) {
            LOGGER.debug("Node {} supersedes {} earlier node(s) of chunk {}",
                nodeId, retired, chunkId);
        }
        return retired;
    }

    /**
     * Tombstones every node of a set except one.
     *
     * @param nodeIds The nodes
     * @param keep    A node to leave alone, or -1 for none
     * @return How many were newly tombstoned
     */
    private int retireAll(Set<Long> nodeIds, long keep) {
        int retired = 0;
        for (long nodeId : nodeIds) {
            if (nodeId != keep && graph.delete(nodeId)) {
                retired++;
            }
        }
        return retired;
    }

    /**
     * Reports whether a node carries the tombstone flag.
     *
     * @param nodeId The ID of the node to check
     * @return true if the node is tombstoned, false if active or not found
     */
    boolean isTombstoned(long nodeId) {
        graph.validateNotClosed();

        graph.lock.readLock().lock();
        try {
            if (nodeId < 0 || nodeId >= graph.nodeIdGenerator.get()) {
                return false;
            }

            long indexOffset = HnswNodeRecords.indexOffset(nodeId);
            if (indexOffset + HnswNodeRecords.NODE_INDEX_ENTRY_SIZE > graph.dataRegionOffset) {
                return false;
            }

            int flags = HnswNodeRecords.readEntryFlags(graph.mappedFile, indexOffset);
            return (flags & HnswNodeRecords.FLAG_TOMBSTONE) != 0;
        } finally {
            graph.lock.readLock().unlock();
        }
    }

    /**
     * Counts the tombstoned nodes by scanning the node index.
     *
     * @return The number of tombstoned nodes
     */
    long count() {
        graph.validateNotClosed();

        graph.lock.readLock().lock();
        try {
            return HnswNodeRecords.countTombstoned(
                graph.mappedFile, graph.nodeCount, graph.dataRegionOffset);
        } finally {
            graph.lock.readLock().unlock();
        }
    }

    /**
     * Returns how many times a node in this graph has been tombstoned.
     *
     * @return The number of tombstone transitions since this instance was opened
     */
    long generation() {
        return tombstoneGeneration.get();
    }
}
