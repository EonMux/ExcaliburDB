package io.excalibur.vector.hnsw;

import java.util.Collections;
import java.util.List;

/**
 * Result of an incremental vector insertion into the HNSW graph.
 *
 * <p>This class captures the metadata needed for write log integration
 * and crash recovery during incremental indexing operations.</p>
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code nodeId} - The ID assigned to the inserted node</li>
 *   <li>{@code level} - The randomly generated level for this node</li>
 *   <li>{@code dataOffset} - The offset in the data region where node is stored</li>
 *   <li>{@code connectionsMade} - Number of bidirectional connections established</li>
 *   <li>{@code writeLogOffset} - The write log offset this insertion corresponds to (for recovery)</li>
 *   <li>{@code documentId} - The document ID this vector belongs to</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class IncrementalInsertResult {

    /**
     * Empty result for failed insertions.
     */
    public static final IncrementalInsertResult FAILED = new IncrementalInsertResult(
        -1, -1, -1, 0, -1, List.of(), null
    );

    private final long nodeId;
    private final int level;
    private final long dataOffset;
    private final int connectionsMade;
    private final long writeLogOffset;
    private final List<Long> connectedNodes;
    private final String documentId;

    /**
     * Creates a new incremental insert result.
     *
     * @param nodeId The ID of the inserted node
     * @param level The level of the inserted node
     * @param dataOffset The data region offset
     * @param connectionsMade Number of connections established
     * @param writeLogOffset The corresponding write log offset (-1 if not from write log)
     * @param connectedNodes List of node IDs this node connected to
     */
    public IncrementalInsertResult(long nodeId, int level, long dataOffset,
                                   int connectionsMade, long writeLogOffset,
                                   List<Long> connectedNodes) {
        this(nodeId, level, dataOffset, connectionsMade, writeLogOffset, connectedNodes, null);
    }

    /**
     * Creates a new incremental insert result with document ID.
     *
     * @param nodeId The ID of the inserted node
     * @param level The level of the inserted node
     * @param dataOffset The data region offset
     * @param connectionsMade Number of connections established
     * @param writeLogOffset The corresponding write log offset (-1 if not from write log)
     * @param connectedNodes List of node IDs this node connected to
     * @param documentId The document ID this vector belongs to (may be null)
     */
    public IncrementalInsertResult(long nodeId, int level, long dataOffset,
                                   int connectionsMade, long writeLogOffset,
                                   List<Long> connectedNodes, String documentId) {
        this.nodeId = nodeId;
        this.level = level;
        this.dataOffset = dataOffset;
        this.connectionsMade = connectionsMade;
        this.writeLogOffset = writeLogOffset;
        this.connectedNodes = connectedNodes != null
            ? Collections.unmodifiableList(connectedNodes)
            : Collections.emptyList();
        this.documentId = documentId;
    }

    /**
     * Returns the ID of the inserted node.
     *
     * @return Node ID, or -1 if insertion failed
     */
    public long nodeId() {
        return nodeId;
    }

    /**
     * Returns the level of the inserted node in the HNSW hierarchy.
     *
     * @return Node level (0 to maxLevel)
     */
    public int level() {
        return level;
    }

    /**
     * Returns the offset in the data region where this node's data is stored.
     *
     * @return Data offset in bytes
     */
    public long dataOffset() {
        return dataOffset;
    }

    /**
     * Returns the number of bidirectional connections made during insertion.
     *
     * @return Number of connections established
     */
    public int connectionsMade() {
        return connectionsMade;
    }

    /**
     * Returns the write log offset this insertion corresponds to.
     *
     * <p>Used for crash recovery to track which entries have been integrated
     * into the graph. Returns -1 if this insertion was not from a write log.</p>
     *
     * @return Write log offset, or -1 if not applicable
     */
    public long writeLogOffset() {
        return writeLogOffset;
    }

    /**
     * Returns the list of node IDs this node connected to.
     *
     * @return Unmodifiable list of connected node IDs
     */
    public List<Long> connectedNodes() {
        return connectedNodes;
    }

    /**
     * Returns the document ID this node belongs to.
     *
     * <p>The document ID is the actual identifier from the source document,
     * such as "doc1" or "source1:file1". This is used for document atomicity
     * filtering in the query pipeline.</p>
     *
     * @return The document ID, or null if not available
     */
    public String documentId() {
        return documentId;
    }

    /**
     * Returns true if this insertion was successful.
     *
     * @return true if successful, false if failed
     */
    public boolean isSuccess() {
        return nodeId >= 0;
    }

    /**
     * Creates a new result with an updated write log offset.
     *
     * @param newWriteLogOffset The new write log offset
     * @return New IncrementalInsertResult with updated offset
     */
    public IncrementalInsertResult withWriteLogOffset(long newWriteLogOffset) {
        return new IncrementalInsertResult(
            nodeId, level, dataOffset, connectionsMade, newWriteLogOffset, connectedNodes, documentId
        );
    }

    /**
     * Creates a new result with a document ID.
     *
     * @param newDocumentId The new document ID
     * @return New IncrementalInsertResult with the document ID
     */
    public IncrementalInsertResult withDocumentId(String newDocumentId) {
        return new IncrementalInsertResult(
            nodeId, level, dataOffset, connectionsMade, writeLogOffset, connectedNodes, newDocumentId
        );
    }

    @Override
    public String toString() {
        return String.format("IncrementalInsertResult{nodeId=%d, level=%d, " +
            "dataOffset=%d, connections=%d, writeLogOffset=%d, documentId=%s}",
            nodeId, level, dataOffset, connectionsMade, writeLogOffset,
            documentId != null ? "\"" + documentId + "\"" : "null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IncrementalInsertResult that = (IncrementalInsertResult) o;
        return nodeId == that.nodeId &&
            level == that.level &&
            dataOffset == that.dataOffset &&
            connectionsMade == that.connectionsMade &&
            writeLogOffset == that.writeLogOffset &&
            connectedNodes.equals(that.connectedNodes) &&
            java.util.Objects.equals(documentId, that.documentId);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(nodeId);
        result = 31 * result + level;
        result = 31 * result + Long.hashCode(dataOffset);
        result = 31 * result + connectionsMade;
        result = 31 * result + Long.hashCode(writeLogOffset);
        result = 31 * result + connectedNodes.hashCode();
        result = 31 * result + (documentId != null ? documentId.hashCode() : 0);
        return result;
    }
}
