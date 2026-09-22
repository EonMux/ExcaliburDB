package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.core.mapped.MappedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The node index table and the node records it points at, as they are laid out on disk.
 *
 * <h2>Why it is its own class</h2>
 * <p>A node record is variable length: its adjacency section holds one list per layer, and layer
 * 0 is allowed more connections than the layers above it, so finding a node's neighbours at
 * layer <em>l</em> means re-deriving the widths of every layer below it. That arithmetic used to
 * be written out by hand at each place that needed it - the writer, the reader, the neighbour
 * editor, the segment exporter and the size estimator - and five copies of an offset calculation
 * is five chances for the file to be read one way and written another. It lives here once.</p>
 *
 * <h2>Node index table</h2>
 * <p>Fixed-width entries, one per node id, starting immediately after the header. The id is
 * stored in its own entry so that an entry which was never written - zeros - can be told apart
 * from one that was, which is what lets a torn tail be recovered rather than believed.</p>
 * <pre>
 *   +0   nodeId     (8 bytes)
 *   +8   dataOffset (8 bytes, relative to the start of the data region)
 *   +16  level      (4 bytes)
 *   +20  flags      (4 bytes)
 * </pre>
 *
 * <h2>Node record</h2>
 * <pre>
 *   [nodeId:8][level:4][vectorLen:4][vector:dim*4]
 *   for each layer 0..level: [connCount:4][neighbours:maxConnAtLayer*4]
 * </pre>
 * <p>The neighbour slots are reserved in full at write time, so a node's record never has to
 * move when an edge is added and the offsets above stay valid for the life of the file.
 * Neighbour ids are stored as 4-byte ints, which is why a graph's capacity cannot exceed
 * {@link Integer#MAX_VALUE}.</p>
 *
 * <h2>Locking</h2>
 * <p>Nothing here takes a lock or holds any state. Every method is given the mapping and the
 * configuration to read the file through, because both are replaced wholesale when a compaction
 * swaps the backing file: a collaborator that cached either would go on reading a file that has
 * been unmapped. Callers hold the graph's read lock for reads and its write lock for writes.</p>
 */
final class HnswNodeRecords {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswNodeRecords.class);

    /** Node index entry size: nodeId(8) + dataOffset(8) + level(4) + flags(4). */
    static final int NODE_INDEX_ENTRY_SIZE = 24;

    /** Flag bit marking a node as soft-deleted. */
    static final int FLAG_TOMBSTONE = 0x01;

    /** Offset of the data offset within a node index entry. */
    private static final int ENTRY_DATA_OFFSET = 8;

    /** Offset of the level within a node index entry. */
    private static final int ENTRY_LEVEL = 16;

    /** Offset of the flags within a node index entry. */
    private static final int ENTRY_FLAGS = 20;

    /** Bytes preceding the vector in a node record: nodeId(8) + level(4) + vectorLen(4). */
    private static final int RECORD_PREFIX = 16;

    private HnswNodeRecords() {
    }

    /**
     * Returns the absolute file offset of a node's index entry.
     *
     * @param nodeId The node id
     * @return The byte offset of the entry within the file
     */
    static long indexOffset(long nodeId) {
        return HnswGraphHeader.HEADER_SIZE + nodeId * NODE_INDEX_ENTRY_SIZE;
    }

    /**
     * Returns the number of connections a node may hold at a layer.
     *
     * @param config The graph's configuration
     * @param level The layer
     * @return The connection limit, which is wider at layer 0
     */
    static int maxConnectionsAt(HnswConfig config, int level) {
        return level == 0 ? config.maxConnectionsLayer0() : config.maxConnections();
    }

    /**
     * Returns the size of a node record whose vector has the configured dimension.
     *
     * @param config The graph's configuration
     * @param level The node's highest layer
     * @return The record's size in bytes
     */
    static int recordSize(HnswConfig config, int level) {
        return recordSize(config, level, config.vectorByteSize());
    }

    /**
     * Returns the size of a node record holding a vector of the given byte length.
     *
     * @param config The graph's configuration
     * @param level The node's highest layer
     * @param vectorBytes The vector's size in bytes
     * @return The record's size in bytes
     */
    static int recordSize(HnswConfig config, int level, int vectorBytes) {
        int adjacencyBytes = 0;
        for (int l = 0; l <= level; l++) {
            adjacencyBytes += 4 + (maxConnectionsAt(config, l) * Integer.BYTES);
        }
        return RECORD_PREFIX + vectorBytes + adjacencyBytes;
    }

    /**
     * Returns the offset of a layer's adjacency section within a node record.
     *
     * @param config The graph's configuration
     * @param recordOffset The absolute offset of the node record
     * @param level The layer whose section is wanted
     * @return The absolute offset of that layer's connection count
     */
    static long adjacencyOffset(HnswConfig config, long recordOffset, int level) {
        long offset = recordOffset + RECORD_PREFIX + (long) config.dimension() * Float.BYTES;
        for (int l = 0; l < level; l++) {
            offset += 4 + ((long) maxConnectionsAt(config, l) * Integer.BYTES);
        }
        return offset;
    }

    /**
     * Writes a node record, reserving the full adjacency space for every layer it spans.
     *
     * @param file The mapping to write into
     * @param config The graph's configuration
     * @param recordOffset The absolute offset to write the record at
     * @param nodeId The node's id
     * @param level The node's highest layer
     * @param vector The node's vector
     * @return The number of bytes the record occupies
     */
    static int writeRecord(MappedFile file, HnswConfig config, long recordOffset,
                           long nodeId, int level, float[] vector) {
        int vectorBytes = vector.length * Float.BYTES;
        int nodeSize = recordSize(config, level, vectorBytes);

        file.writeLong(recordOffset, nodeId);
        file.writeInt(recordOffset + 8, level);
        file.writeInt(recordOffset + 12, vector.length);

        long vecOffset = recordOffset + RECORD_PREFIX;
        for (int i = 0; i < vector.length; i++) {
            file.writeFloat(vecOffset + (long) i * 4, vector[i]);
        }

        // Initialize all level adjacency lists with count=0
        long adjOffset = recordOffset + RECORD_PREFIX + vectorBytes;
        for (int l = 0; l <= level; l++) {
            file.writeInt(adjOffset, 0);
            adjOffset += 4 + ((long) maxConnectionsAt(config, l) * Integer.BYTES);
        }

        return nodeSize;
    }

    /**
     * Reads a node's vector out of its record.
     *
     * @param file The mapping to read from
     * @param config The graph's configuration
     * @param recordOffset The absolute offset of the node record
     * @return A newly allocated copy of the vector
     */
    static float[] readVector(MappedFile file, HnswConfig config, long recordOffset) {
        int dim = config.dimension();
        float[] vec = new float[dim];
        long vecOffset = recordOffset + RECORD_PREFIX;
        for (int i = 0; i < dim; i++) {
            vec[i] = file.readFloat(vecOffset + (long) i * 4);
        }
        return vec;
    }

    /**
     * Reads a node's neighbour list at one layer, tombstoned neighbours included.
     *
     * @param file The mapping to read from
     * @param config The graph's configuration
     * @param recordOffset The absolute offset of the node record
     * @param level The layer to read
     * @return The neighbour ids, in stored order
     */
    static List<Long> readNeighbors(MappedFile file, HnswConfig config,
                                    long recordOffset, int level) {
        long connOffset = adjacencyOffset(config, recordOffset, level);
        int connCount = file.readInt(connOffset);

        List<Long> neighbors = new ArrayList<>(connCount);
        for (int i = 0; i < connCount; i++) {
            neighbors.add((long) file.readInt(connOffset + 4 + (long) i * 4));
        }
        return neighbors;
    }

    /**
     * Replaces a node's neighbour list at one layer, truncating to the layer's limit.
     *
     * @param file The mapping to write into
     * @param config The graph's configuration
     * @param recordOffset The absolute offset of the node record
     * @param level The layer to write
     * @param neighborIds The neighbours to store
     */
    static void writeNeighbors(MappedFile file, HnswConfig config, long recordOffset,
                               int level, List<Long> neighborIds) {
        long connOffset = adjacencyOffset(config, recordOffset, level);

        int count = Math.min(neighborIds.size(), maxConnectionsAt(config, level));
        file.writeInt(connOffset, count);

        for (int i = 0; i < count; i++) {
            file.writeInt(connOffset + 4 + (long) i * 4, neighborIds.get(i).intValue());
        }
    }

    /**
     * Writes a node's index entry.
     *
     * @param file The mapping to write into
     * @param nodeId The node's id
     * @param dataOffset The node record's offset relative to the data region
     * @param level The node's highest layer
     * @param tombstoned Whether the node starts out soft-deleted
     */
    static void writeIndexEntry(MappedFile file, long nodeId, long dataOffset,
                                int level, boolean tombstoned) {
        long indexOffset = indexOffset(nodeId);
        file.writeLong(indexOffset, nodeId);
        file.writeLong(indexOffset + ENTRY_DATA_OFFSET, dataOffset);
        file.writeInt(indexOffset + ENTRY_LEVEL, level);
        file.writeInt(indexOffset + ENTRY_FLAGS, tombstoned ? FLAG_TOMBSTONE : 0);
    }

    /**
     * Reads the id stored in a node index entry.
     *
     * @param file The mapping to read from
     * @param indexOffset The entry's absolute offset
     * @return The stored id, which is zero for an entry that was never written
     */
    static long readEntryId(MappedFile file, long indexOffset) {
        return file.readLong(indexOffset);
    }

    /**
     * Reads the data-region offset stored in a node index entry.
     *
     * @param file The mapping to read from
     * @param indexOffset The entry's absolute offset
     * @return The node record's offset relative to the data region
     */
    static long readEntryDataOffset(MappedFile file, long indexOffset) {
        return file.readLong(indexOffset + ENTRY_DATA_OFFSET);
    }

    /**
     * Reads the level stored in a node index entry.
     *
     * @param file The mapping to read from
     * @param indexOffset The entry's absolute offset
     * @return The node's highest layer
     */
    static int readEntryLevel(MappedFile file, long indexOffset) {
        return file.readInt(indexOffset + ENTRY_LEVEL);
    }

    /**
     * Reads the flag word stored in a node index entry.
     *
     * @param file The mapping to read from
     * @param indexOffset The entry's absolute offset
     * @return The flags, of which {@link #FLAG_TOMBSTONE} is currently the only bit
     */
    static int readEntryFlags(MappedFile file, long indexOffset) {
        return file.readInt(indexOffset + ENTRY_FLAGS);
    }

    /**
     * Writes the flag word of a node index entry.
     *
     * @param file The mapping to write into
     * @param indexOffset The entry's absolute offset
     * @param flags The flags to store
     */
    static void writeEntryFlags(MappedFile file, long indexOffset, int flags) {
        file.writeInt(indexOffset + ENTRY_FLAGS, flags);
    }

    /**
     * Grows the backing file if the next record would not fit.
     *
     * @param file The mapping to extend
     * @param required The absolute offset one past the record about to be written
     * @throws IllegalStateException if the file cannot be extended
     */
    static void ensureCapacity(MappedFile file, long required) {
        if (required > file.size()) {
            try {
                // Extend by at least 1MB or 50%, whichever is larger
                long extension = Math.max(1024 * 1024, file.size() / 2);
                file.extend(extension);
                LOGGER.debug("Extended HNSW file to {} bytes", file.size());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to extend HNSW file", e);
            }
        }
    }

    /**
     * Finds an active (non-tombstoned) node.
     *
     * @param file The mapping to read from
     * @param nodeCount How many index entries to consider
     * @return The lowest active node id, or -1 if every node is tombstoned
     */
    static long findActiveNode(MappedFile file, long nodeCount) {
        for (long id = 0; id < nodeCount; id++) {
            int flags = readEntryFlags(file, indexOffset(id));
            if ((flags & FLAG_TOMBSTONE) == 0) {
                return id;
            }
        }
        return -1;
    }

    /**
     * Finds the active node with the highest level in the graph.
     *
     * <p>Used when the entry point is deleted, to keep the invariant that the entry point
     * sits at the graph's highest layer.</p>
     *
     * @param file The mapping to read from
     * @param nodeCount How many index entries to consider
     * @return The node id with the highest level among active nodes, or -1 if no active
     *         nodes exist
     */
    static long findHighestLevelActiveNode(MappedFile file, long nodeCount) {
        long highestLevelNode = -1;
        int highestLevel = -1;

        for (long id = 0; id < nodeCount; id++) {
            long indexOffset = indexOffset(id);
            int flags = readEntryFlags(file, indexOffset);
            if ((flags & FLAG_TOMBSTONE) != 0) {
                continue;
            }

            int level = readEntryLevel(file, indexOffset);
            if (level > highestLevel) {
                highestLevel = level;
                highestLevelNode = id;
            }
        }

        return highestLevelNode;
    }

    /**
     * Counts the tombstoned entries in the node index.
     *
     * @param file The mapping to read from
     * @param nodeCount How many index entries to consider
     * @param dataRegionOffset Where the index table ends, so a short file stops the scan
     * @return The number of tombstoned nodes
     */
    static long countTombstoned(MappedFile file, long nodeCount, long dataRegionOffset) {
        long count = 0;
        for (long id = 0; id < nodeCount; id++) {
            long indexOffset = indexOffset(id);
            if (indexOffset + NODE_INDEX_ENTRY_SIZE > dataRegionOffset) {
                break;
            }
            int flags = readEntryFlags(file, indexOffset);
            if ((flags & FLAG_TOMBSTONE) != 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * The number of nodes whose index entries actually reached the file.
     *
     * <p>Each entry starts with the id of the node it describes, so a slot that has not been
     * written reads as zeros and does not match its own index. Walking back from the header's
     * count to the highest slot that does match is what keeps a torn tail from being believed:
     * deriving the append position from an unwritten slot yields a data offset of zero, and the
     * next insert then writes over node 0 - which is usually the entry point, so the graph
     * navigates from a corrupted vector and answers with the wrong neighbours.</p>
     *
     * @param file The mapping to read from
     * @param persistedNodeCount The count the header claims
     * @param maxNodes The graph's capacity, which bounds the index table
     * @param dataRegionOffset Where the index table ends
     * @return The largest count whose every index entry is present
     */
    static long highestWrittenNodeCount(MappedFile file, long persistedNodeCount,
                                        long maxNodes, long dataRegionOffset) {
        long count = Math.min(persistedNodeCount, maxNodes);
        while (count > 0) {
            long indexOffset = indexOffset(count - 1);
            if (indexOffset + NODE_INDEX_ENTRY_SIZE <= dataRegionOffset
                && readEntryId(file, indexOffset) == count - 1) {
                return count;
            }
            count--;
        }
        return 0;
    }

    /**
     * Recovers the append position in the data region from the last node index entry.
     *
     * <p>Nodes are appended in ID order, so the highest ID's record is the last one
     * written and its size is fully determined by its level. The entry is known to be present:
     * {@link #highestWrittenNodeCount} has already established that.</p>
     *
     * @param file The mapping to read from
     * @param config The graph's configuration
     * @param persistedNodeCount The recovered node count
     * @return The offset (relative to the data region) at which the next node goes
     */
    static long recoverDataWriteOffset(MappedFile file, HnswConfig config,
                                       long persistedNodeCount) {
        if (persistedNodeCount <= 0) {
            return 0;
        }
        long lastIndexOffset = indexOffset(persistedNodeCount - 1);
        long lastDataOffset = readEntryDataOffset(file, lastIndexOffset);
        int lastLevel = readEntryLevel(file, lastIndexOffset);
        return lastDataOffset + recordSize(config, lastLevel);
    }
}
