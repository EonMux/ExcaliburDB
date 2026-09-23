package com.eonmux.excalibur.vector.hnsw;

import java.lang.foreign.MemorySegment;

/**
 * One locked operation's read access to the node index and records - a search, or the linking
 * pass of an insert.
 *
 * <h2>Why it is its own class</h2>
 * <p>A traversal reads a few hundred to a few thousand nodes, and for each of them only three
 * things: whether it is active, its neighbour ids at one layer, and its distance to a vector.
 * Going through {@link HnswGraph#readNode(long)} for that costs a graph lock, a view object, a
 * locked {@link com.eonmux.excalibur.core.mapped.MappedFile} read per index field and per vector
 * component, and a fresh copy of the vector - which, measured, was most of the time a search or
 * a build took. This class reads the same bytes straight from the mapping, copies a vector into
 * one reused buffer, and hands neighbour ids back in another.</p>
 *
 * <h2>Why reading the mapping directly is safe</h2>
 * <p>The mapping is captured once, when the traversal is opened, and must be opened with the
 * graph's lock held and used only while it stays held. That lock is what makes the capture
 * stable: every operation that replaces the mapping - growing the file on insert, the swap at
 * the end of a compaction, close - holds the graph's write lock, so while a reader holds the read
 * lock, or an inserter the write lock and past its own growth, the segment it captured is the
 * one the file is mapped to. The records' layout and byte order are those of
 * {@link HnswNodeRecords}, which owns them.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe: the scratch buffers are the point. One traversal belongs to one operation
 * on one thread.</p>
 */
final class HnswTraversal {

    private final HnswGraph graph;
    private final MemorySegment segment;
    private final HnswConfig config;
    private final long dataRegionOffset;
    private final long nodeLimit;
    private final float[] vectorScratch;
    private final int[] neighborScratch;

    private HnswTraversal(HnswGraph graph, MemorySegment segment) {
        this.graph = graph;
        this.segment = segment;
        this.config = graph.config;
        this.dataRegionOffset = graph.dataRegionOffset;
        this.nodeLimit = graph.nodeIdGenerator.get();
        this.vectorScratch = new float[config.dimension()];
        this.neighborScratch = new int[config.maxConnectionsLayer0()];
    }

    /**
     * Opens a traversal over the graph's current mapping. The graph's lock must be held, and
     * stay held for as long as the traversal is used.
     *
     * @param graph The graph to read
     * @return A traversal bound to the graph's current mapping
     */
    static HnswTraversal open(HnswGraph graph) {
        return new HnswTraversal(graph, graph.mappedFile.segment());
    }

    /**
     * How many node ids had been handed out when the traversal was opened; every id a traversal
     * can reach is below it.
     *
     * @return The exclusive upper bound on node ids
     */
    long nodeLimit() {
        return nodeLimit;
    }

    /**
     * Reports whether an id names a written, non-tombstoned node.
     *
     * @param nodeId The id to check
     * @return true if the node exists and is active
     */
    boolean isActive(long nodeId) {
        long indexOffset = entryOffset(nodeId);
        return indexOffset >= 0
            && (HnswNodeRecords.readEntryFlags(segment, indexOffset) & HnswNodeRecords.FLAG_TOMBSTONE) == 0;
    }

    /**
     * Returns a node's highest layer.
     *
     * @param nodeId The node
     * @return The node's level, or -1 if the id names no written node
     */
    int level(long nodeId) {
        long indexOffset = entryOffset(nodeId);
        return indexOffset < 0 ? -1 : HnswNodeRecords.readEntryLevel(segment, indexOffset);
    }

    /**
     * Reads a node's neighbour ids at one layer into the shared buffer.
     *
     * <p>The ids stay valid only until the next call; a caller that needs them longer copies
     * them out.</p>
     *
     * @param nodeId The node whose neighbours are read
     * @param level The layer to read
     * @return How many ids {@link #neighbors()} now holds, 0 for a node absent at that layer
     */
    int readNeighbors(long nodeId, int level) {
        long indexOffset = entryOffset(nodeId);
        if (indexOffset < 0 || level < 0 || HnswNodeRecords.readEntryLevel(segment, indexOffset) < level) {
            return 0;
        }
        return HnswNodeRecords.readNeighbors(
            segment, config, recordAt(indexOffset), level, neighborScratch);
    }

    /**
     * The buffer {@link #readNeighbors} fills.
     *
     * @return The neighbour-id buffer, meaningful up to the count the last read returned
     */
    int[] neighbors() {
        return neighborScratch;
    }

    /**
     * Measures a node from a vector, in the graph's smaller-is-closer ordering.
     *
     * @param vector The vector to measure from
     * @param nodeId A node that {@link #isActive}, or at least exists
     * @return The distance, as {@link HnswGraph#computeDistance} orders it
     * @throws IllegalArgumentException if the id names no written node
     */
    float distance(float[] vector, long nodeId) {
        HnswNodeRecords.readVector(segment, config, writtenRecord(nodeId), vectorScratch);
        return graph.computeDistance(vector, vectorScratch);
    }

    /**
     * Copies a node's vector out, for a caller that keeps it.
     *
     * @param nodeId A node that {@link #isActive}, or at least exists
     * @return A new array holding the node's vector
     * @throws IllegalArgumentException if the id names no written node
     */
    float[] vector(long nodeId) {
        float[] vector = new float[config.dimension()];
        readVector(nodeId, vector);
        return vector;
    }

    /**
     * Copies a node's vector into a caller's buffer, for a caller that reads many vectors and
     * keeps few of them.
     *
     * @param nodeId A node that {@link #isActive}, or at least exists
     * @param into A buffer of the graph's dimension, overwritten
     * @throws IllegalArgumentException if the id names no written node
     */
    void readVector(long nodeId, float[] into) {
        HnswNodeRecords.readVector(segment, config, writtenRecord(nodeId), into);
    }

    /**
     * Returns the absolute offset of a node's record, for a caller about to write to it.
     *
     * @param nodeId The node
     * @return The record's offset in the file, or -1 if the id names no written node
     */
    long recordOffset(long nodeId) {
        long indexOffset = entryOffset(nodeId);
        return indexOffset < 0 ? -1 : recordAt(indexOffset);
    }

    /**
     * Returns the record offset of a node whose vector is about to be read.
     *
     * <p>Without the check, an id that names no node would resolve to an offset of -1 and the
     * read would land wherever that pointed - another node's bytes, or the header - and hand
     * back a vector that belongs to nothing.</p>
     */
    private long writtenRecord(long nodeId) {
        long indexOffset = entryOffset(nodeId);
        if (indexOffset < 0) {
            throw new IllegalArgumentException("node " + nodeId + " is not a written node of this graph");
        }
        return recordAt(indexOffset);
    }

    /**
     * Returns the index-entry offset of a written node, or -1 for an id that names none.
     */
    private long entryOffset(long nodeId) {
        if (nodeId < 0 || nodeId >= nodeLimit) {
            return -1;
        }
        long indexOffset = HnswNodeRecords.indexOffset(nodeId);
        if (indexOffset + HnswNodeRecords.NODE_INDEX_ENTRY_SIZE > dataRegionOffset
                || HnswNodeRecords.readEntryId(segment, indexOffset) != nodeId) {
            return -1;
        }
        return indexOffset;
    }

    /**
     * Returns the absolute record offset an index entry points at.
     */
    private long recordAt(long indexOffset) {
        return dataRegionOffset + HnswNodeRecords.readEntryDataOffset(segment, indexOffset);
    }
}
