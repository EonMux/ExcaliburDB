package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.mapped.MappedFile;
import com.eonmux.excalibur.core.memory.Segment;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.quantization.PQCodebookFile;
import com.eonmux.excalibur.vector.quantization.PQDistanceTable;
import com.eonmux.excalibur.vector.quantization.ProductQuantizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Disk-backed HNSW (Hierarchical Navigable Small World) graph for approximate nearest neighbor search.
 *
 * <p>This implementation stores nodes and adjacency lists in memory-mapped files,
 * making the graph navigable directly from disk without requiring full loading into memory.
 * This is critical for handling large-scale vector indexes that exceed available RAM.</p>
 *
 * <h2>Disk Layout</h2>
 * <p>A 4 KiB header, then a fixed-width node index table, then a data region of variable-length
 * node records. The header's fields are defined and documented on {@link HnswGraphHeader}, the
 * index entries and node records on {@link HnswNodeRecords}, and the header's checksums on
 * {@link HnswGraphIntegrity}. Those three classes are the definition of the on-disk format;
 * nothing here duplicates an offset.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Direct disk navigation:</strong> Graph traversal reads directly from memory-mapped file</li>
 *   <li><strong>Incremental insertion:</strong> Vectors can be added without full rebuild</li>
 *   <li><strong>Soft-delete:</strong> Tombstoned nodes are skipped during traversal</li>
 *   <li><strong>Tail offset tracking:</strong> Tracks integration progress for live indexing</li>
 *   <li><strong>Thread-safe:</strong> Read-write locks for concurrent access</li>
 * </ul>
 *
 * <h2>How the work is divided</h2>
 * <p>This class is the published surface and the owner of the graph's mutable state. The work
 * behind each method belongs to a package-private collaborator, one per concern:
 * {@link HnswGraphOpener} attaches and creates files, {@link HnswLayerSearch} and
 * {@link HnswQuantizedSearch} answer queries, {@link HnswInserter} and
 * {@link HnswNeighborLinker} add nodes and edges, {@link HnswTombstones} retires them,
 * {@link HnswCompactor} rebuilds the file without them, {@link HnswQuantization} holds the
 * product quantizer, {@link HnswIntegrationCheckpoint} holds the write-log checkpoint, and
 * {@link HnswSegmentCodec} moves records in and out of a {@link Segment}.</p>
 *
 * <p>The collaborators read and write this graph's state directly, which is why the fields
 * below are package-private rather than private. They are a single object's state split across
 * files for legibility, not independent components: the locking discipline stated next applies
 * to all of them, and each one documents where it expects to be called from.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Read operations (search) acquire read lock - concurrent reads allowed</li>
 *   <li>Write operations (insert, delete) acquire write lock - exclusive access</li>
 *   <li>lastIntegratedOffset updates are atomic</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public class HnswGraph implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswGraph.class);

    /** Suffix of the file holding a graph's node-to-document mapping. */
    public static final String DOCUMENT_MAPPING_SUFFIX = ".docmap";

    /** Suffix of the file holding a graph's trained product quantizer codebook. */
    public static final String PRODUCT_QUANTIZER_SUFFIX = PQCodebookFile.SUFFIX;

    // ==================== Shared state ====================
    //
    // Everything below is read, and most of it written, by the collaborators listed in this
    // class's javadoc. The modifiers are load-bearing:
    //
    //   - The reference fields are reassigned by compaction while lock-free readers
    //     (readNode / NodeView) may be dereferencing them, so they must be volatile: a plain
    //     field gives no safe-publication guarantee for the replacement.
    //   - The counters are volatile for visibility to readers that hold no lock, and are only
    //     ever advanced under the write lock.
    //   - The lock is the single monitor for all of it. A collaborator that takes a lock takes
    //     this one, so nested acquisitions re-enter rather than deadlock.

    /** Configuration. Replaced when an existing file's layout is adopted. */
    volatile HnswConfig config;

    /** The metric this graph's edges were chosen under. */
    volatile DistanceFunction distanceFunction;

    /** The backing file. Reassigned when compaction swaps a rebuilt file into place. */
    volatile Path path;

    /** Arena owning the mapping. Replaced alongside {@link #mappedFile}. */
    volatile Arena arena;

    /** The mapping reads and writes go through. Replaced by compaction. */
    volatile MappedFile mappedFile;

    /** The graph's single monitor. */
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Number of nodes in the index, tombstoned ones included. */
    volatile long nodeCount = 0;

    /** Node the descent starts from, or -1 when the graph has no active node. */
    volatile long entryPointId = -1;

    /** Highest occupied layer, or -1 when the graph has no active node. */
    volatile int maxLevel = -1;

    /** Whether this graph has been closed, or is mid-swap during a compaction. */
    volatile boolean closed = false;

    /** Hands out node ids; also restored to the node count on attach and after compaction. */
    final AtomicLong nodeIdGenerator = new AtomicLong(0);

    /** Data region start offset (after header and node index). */
    volatile long dataRegionOffset;

    /** Current append position within the data region. */
    volatile long dataWriteOffset;

    /** Maximum number of nodes this graph can hold. */
    volatile long maxNodes;

    /**
     * The format version of the backing file. A file written before version 3 carries no
     * integrity block, and none is written for it: a checksum computed now would vouch for
     * bytes that were never covered by one. It gains protection when it is next rebuilt.
     */
    volatile int formatVersion = HnswGraphHeader.VERSION;

    /**
     * Generation counter, incremented every time compaction replaces the backing
     * file. Compaction renumbers node IDs, so a node ID is only meaningful within
     * the generation it was obtained from. Callers that hold a node ID across a
     * possible compaction must pass the generation they captured to
     * {@link #readNode(long, long)} so a renumbered ID is rejected instead of silently
     * resolving to a different vector.
     */
    volatile long generation = 0;

    /**
     * Maps internal HNSW node IDs to the chunk each is the vector of
     * ({@code sourceId:documentId:chunk:N}). This is what names a result, lets document-level
     * filtering derive the document from it, and lets a chunk or a document be retired.
     */
    volatile NodeDocumentMapping nodeDocumentMapping;

    // ==================== Collaborators ====================
    // Declared in dependency order; each stores only the reference and touches no state until
    // it is called, so constructing them before the file is attached is safe.

    /** The write-log integration checkpoint. */
    final HnswIntegrationCheckpoint checkpoint = new HnswIntegrationCheckpoint(this);

    /** The product quantizer and its codes, if this graph has any. */
    final HnswQuantization quantization = new HnswQuantization(this);

    /** Visited sets for graph walks, reused across walks instead of allocated per walk. */
    final HnswVisitedPool visitedPool = new HnswVisitedPool();

    /** Flushing, sealing and closing. */
    final HnswGraphLifecycle lifecycle = new HnswGraphLifecycle(this);

    private final HnswLayerSearch layerSearch = new HnswLayerSearch(this);
    private final HnswNeighborLinker neighborLinker = new HnswNeighborLinker(this);
    private final HnswInserter inserter = new HnswInserter(this, layerSearch, neighborLinker);
    private final HnswTombstones tombstones = new HnswTombstones(this);
    private final HnswQuantizedSearch quantizedSearch =
        new HnswQuantizedSearch(this, quantization);
    private final HnswCompactor compactor = new HnswCompactor(this);
    private final HnswSegmentCodec segmentCodec = new HnswSegmentCodec(this);

    /**
     * Creates a new HNSW graph backed by a memory-mapped file.
     *
     * @param path Path to the backing file
     * @param config HNSW configuration
     * @param distanceFunction Distance function for vector comparison
     * @param maxNodes Maximum number of nodes the graph can hold
     * @throws IOException if file cannot be created or mapped
     * @throws IllegalArgumentException if parameters are invalid
     */
    public HnswGraph(Path path, HnswConfig config, DistanceFunction distanceFunction, long maxNodes)
            throws IOException {
        HnswGraphOpener.attach(this, path, config, distanceFunction, maxNodes);
    }

    /**
     * Constructor for opening an existing graph without reinitializing the header.
     *
     * @param path The backing file
     * @param config The layout read from the file
     * @param distanceFunction The metric the file was built under
     * @param maxNodes The capacity read from the file
     * @param mappedFile The mapping already made over the file
     * @param arena The arena owning that mapping
     * @param dataRegionOffset Where the node records start
     */
    HnswGraph(Path path, HnswConfig config, DistanceFunction distanceFunction,
              long maxNodes, MappedFile mappedFile, Arena arena,
              long dataRegionOffset) {
        this.path = path;
        this.config = config;
        this.distanceFunction = distanceFunction;
        this.maxNodes = maxNodes;
        this.mappedFile = mappedFile;
        this.arena = arena;
        this.dataRegionOffset = dataRegionOffset;
        this.dataWriteOffset = 0; // Will be set by caller if needed
        this.nodeDocumentMapping = HnswGraphOpener.openDocumentMapping(path);
    }

    /**
     * Opens an existing HNSW graph from a file.
     *
     * @param path Path to the existing graph file
     * @param distanceFunction Distance function for vector comparison
     * @return The opened HNSW graph
     * @throws IOException if file cannot be opened or is invalid
     */
    public static HnswGraph open(Path path, DistanceFunction distanceFunction) throws IOException {
        return HnswGraphOpener.open(path, distanceFunction);
    }

    /**
     * Opens an existing HNSW graph from a Segment abstraction.
     *
     * <p>This method provides an alternative way to open a graph using the
     * {@link Segment} memory abstraction layer. The Segment abstraction allows
     * the graph to work with either Panama FFM or fallback ByteBuffer implementations
     * depending on runtime availability.</p>
     *
     * <p><strong>Note:</strong> This method creates a MappedFile internally from
     * the Segment's path. If the Segment is not backed by a file, use
     * {@link #open(Path, DistanceFunction)} instead.</p>
     *
     * @param segment The Segment containing the graph data (must be file-backed)
     * @param distanceFunction Distance function for vector comparison
     * @return The opened HNSW graph
     * @throws IOException if the segment cannot be opened or is invalid
     * @throws UnsupportedOperationException if the segment is not file-backed
     * @throws NullPointerException if segment or distanceFunction is null
     * @since 1.1
     */
    public static HnswGraph openFromSegment(Segment segment, DistanceFunction distanceFunction) throws IOException {
        return HnswSegmentCodec.open(segment, distanceFunction);
    }

    /**
     * Creates a new HNSW graph using the Segment memory abstraction.
     *
     * <p>This method creates a graph using the {@link Segment} abstraction layer,
     * which automatically selects the appropriate implementation (Panama FFM or
     * ByteBuffer fallback) based on runtime capabilities.</p>
     *
     * @param path Path to the backing file
     * @param config HNSW configuration
     * @param distanceFunction Distance function for vector comparison
     * @param maxNodes Maximum number of nodes the graph can hold
     * @return A new HNSW graph
     * @throws IOException if the graph cannot be created
     * @throws NullPointerException if any parameter is null
     * @since 1.1
     */
    public static HnswGraph createWithSegment(Path path, HnswConfig config,
                                               DistanceFunction distanceFunction, long maxNodes) throws IOException {
        return HnswSegmentCodec.create(path, config, distanceFunction, maxNodes);
    }

    /**
     * Reads header information from a Segment without fully opening the graph.
     *
     * <p>This utility method allows inspecting a graph file's metadata without
     * the overhead of creating a full HnswGraph instance. Useful for validation
     * and metadata extraction.</p>
     *
     * @param segment The Segment to read header from
     * @return HeaderInfo containing metadata, or null if invalid
     * @throws NullPointerException if segment is null
     * @since 1.1
     */
    public static HeaderInfo readHeaderInfo(Segment segment) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        return HnswGraphHeader.readInfo(segment);
    }

    /**
     * Immutable record containing HNSW graph header information.
     *
     * @param version The format version the file is at
     * @param dimension The vector dimension
     * @param nodeCount The node count the header records
     * @param entryPoint The entry point node id
     * @param maxLevel The highest occupied layer
     * @param lastIntegratedOffset The write-log integration checkpoint
     * @param maxConnections The per-layer connection limit
     * @param maxConnectionsLayer0 The layer-0 connection limit
     * @param efConstruction The construction beam width
     * @param efSearch The search beam width
     * @param mL The level-assignment constant
     * @param distanceMetric The canonical name of the metric the graph was built under
     * @since 1.1
     */
   public record HeaderInfo(
        int version,
        int dimension,
        long nodeCount,
        long entryPoint,
        int maxLevel,
        long lastIntegratedOffset,
        int maxConnections,
        int maxConnectionsLayer0,
        int efConstruction,
        int efSearch,
        double mL,
        String distanceMetric
    ) {
        /**
         * Creates an HnswConfig from this header info.
         *
         * @return A new HnswConfig with the header values
         */
        public HnswConfig toConfig() {
            return new HnswConfig(maxConnections, maxConnectionsLayer0,
                efConstruction, efSearch, mL, dimension);
        }
    }

    // ==================== Public API - Search ====================

    /**
     * Searches for the k nearest neighbors to the query vector.
     *
     * <p>This method performs hierarchical search starting from the entry point,
     * navigating through the graph layers to find approximate nearest neighbors.
     * The search is performed by reading directly from the memory-mapped file,
     * without loading the entire graph into memory.</p>
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Start at entry point at the highest layer</li>
     *   <li>Greedy search at each layer to find closest node</li>
     *   <li>At layer 0, perform beam search with efSearch candidates</li>
     *   <li>Return top-k results</li>
     * </ol>
     *
     * @param query Query vector
     * @param k Number of neighbors to return
     * @return List of search results sorted by distance (closest first)
     * @throws IllegalStateException if graph is closed or empty
     * @throws IllegalArgumentException if query is null or wrong dimension
     */
    public List<HnswSearchResult> search(float[] query, int k) {
        return layerSearch.search(query, k);
    }

    /**
     * Searches for the k nearest neighbors to the query vector within a candidate set.
     *
     * <p>A pre-filter names the nodes worth measuring, and this method answers with the nearest of
     * them. The names are a scattering of ids drawn from wherever the filter's buckets happened to
     * fall, not a connected region of the graph, so the graph's edges are used to <em>reach</em>
     * named nodes and never to decide which of them may be returned. A walk permitted to step only
     * from one named node to another is walking an edgeless subgraph, and comes back with whatever
     * it started on.</p>
     *
     * <h3>Algorithm</h3>
     * <p>Two ways to answer, chosen by which does less work:</p>
     * <ul>
     *   <li><strong>Measure every named node</strong>, when the set is small. Exact, at one node
     *       read per name.</li>
     *   <li><strong>Walk the graph</strong> otherwise: the ordinary descent and layer-0 beam over
     *       the whole graph, admitting only named nodes to the results.</li>
     * </ul>
     *
     * <p>The line between them follows from what each costs. Measuring reads {@code |C|} nodes. A
     * walk keeps exploring until it holds {@code ef} admitted results and admits a
     * {@code |C| / nodeCount} fraction of what it visits, so it reads on the order of
     * {@code ef * nodeCount / |C|}. Measuring is the cheaper of the two while
     * {@code |C|² <= ef * nodeCount}, and it is also the exact one, so that is where the line
     * sits.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>A null candidate set means "no restriction" and searches the whole graph; an empty one
     *       means "nothing is admissible" and returns nothing</li>
     *   <li>Only nodes in the candidate set are returned</li>
     *   <li>Results are sorted by distance (closest first)</li>
     *   <li>Tombstoned nodes, and ids naming no node, are skipped</li>
     * </ul>
     *
     * @param query Query vector
     * @param k Number of neighbors to return
     * @param candidateIds Set of node IDs to search within (null = full search)
     * @return List of search results sorted by distance (closest first)
     * @throws IllegalStateException if graph is closed
     * @throws IllegalArgumentException if query is null or wrong dimension, or {@code k <= 0}
     * @since 1.2
     */
    public List<HnswSearchResult> search(float[] query, int k, Set<Long> candidateIds) {
        return layerSearch.search(query, k, candidateIds);
    }

    // ==================== Public API - Insertion ====================

    /**
     * Inserts a vector into the HNSW graph.
     *
     * <p>This method performs incremental insertion without requiring a full rebuild.
     * The algorithm:</p>
     * <ol>
     *   <li>Generates a random level for the new node based on mL parameter</li>
     *   <li>Finds entry points at each layer using greedy search</li>
     *   <li>Connects the new node to its nearest neighbors at each layer</li>
     *   <li>Updates neighbor connections to maintain max connection limits</li>
     * </ol>
     *
     * @param vector The vector to insert
     * @return The ID of the inserted node
     * @throws IllegalStateException if graph is closed or full
     * @throws IllegalArgumentException if vector is null or wrong dimension
     */
    public long insert(float[] vector) {
        return inserter.insert(vector);
    }

    /**
     * Inserts a vector with full result metadata for write log integration.
     *
     * <p>This method provides detailed information about the insertion for
     * integration with the write log and crash recovery system. It returns
     * an {@link IncrementalInsertResult} containing:</p>
     * <ul>
     *   <li>The node ID assigned</li>
     *   <li>The level in the hierarchy</li>
     *   <li>The data offset for potential recovery</li>
     *   <li>Number of connections made</li>
     *   <li>List of connected nodes</li>
     * </ul>
     *
     * @param vector The vector to insert
     * @return IncrementalInsertResult with insertion metadata
     * @throws IllegalStateException if graph is closed or full
     * @throws IllegalArgumentException if vector is null or wrong dimension
     */
    public IncrementalInsertResult insertWithResult(float[] vector) {
        return insertWithResult(vector, -1);
    }

    /**
     * Inserts a vector with write log offset for crash recovery.
     *
     * <p>This overload allows associating the insertion with a specific
     * write log offset, which is used for recovery after crashes.</p>
     *
     * @param vector The vector to insert
     * @param writeLogOffset The write log offset this insertion corresponds to (-1 if not from write log)
     * @return IncrementalInsertResult with insertion metadata
     * @throws IllegalStateException if graph is closed or full
     * @throws IllegalArgumentException if vector is null or wrong dimension
     */
    public IncrementalInsertResult insertWithResult(float[] vector, long writeLogOffset) {
        return inserter.insertWithResult(vector, writeLogOffset);
    }

    /**
     * Inserts multiple vectors in batch for efficient bulk loading.
     *
     * <p>Batch insertion is more efficient than individual insertions because:
     * <ul>
     *   <li>Write lock is acquired once for the entire batch</li>
     *   <li>Vector data is written sequentially for better I/O patterns</li>
     *   <li>Graph structure is built incrementally within the batch</li>
     * </ul>
     *
     * <p><strong>Note:</strong> This does NOT perform batch optimization like
     * bulk loading algorithms. Each vector is inserted incrementally, but
     * with reduced locking overhead.</p>
     *
     * @param vectors List of vectors to insert
     * @return List of node IDs for inserted vectors (in order)
     * @throws IllegalStateException if graph is closed or full
     * @throws IllegalArgumentException if vectors is null or contains wrong dimensions
     */
    public List<Long> insertBatch(List<float[]> vectors) {
        return inserter.insertBatch(vectors);
    }

    /**
     * Inserts multiple vectors with full result metadata for write log integration.
     *
     * <p>Combines batch insertion efficiency with detailed result tracking
     * for each inserted vector.</p>
     *
     * @param vectors List of vectors to insert
     * @param writeLogOffsets Corresponding write log offsets (must match vectors size, or null)
     * @return List of IncrementalInsertResult for each insertion
     * @throws IllegalStateException if graph is closed or full
     * @throws IllegalArgumentException if vectors is null or sizes don't match
     */
    public List<IncrementalInsertResult> insertBatchWithResults(
            List<float[]> vectors, List<Long> writeLogOffsets) {
        return inserter.insertBatchWithResults(vectors, writeLogOffsets);
    }

    // ==================== Public API - Deletion ====================

    /**
     * Marks a node as deleted (soft-delete with tombstone).
     *
     * <p>The node is marked with a tombstone flag and will be skipped during
     * search operations. The actual data is not removed from disk until
     * compaction is performed.</p>
     *
     * <h3>Soft-Delete Behavior</h3>
     * <ul>
     *   <li>The node remains in the graph structure but is marked as deleted</li>
     *   <li>All traversal operations skip tombstoned nodes</li>
     *   <li>Search results never include tombstoned nodes</li>
     *   <li>The node's connections are preserved (for potential future compaction)</li>
     *   <li>If the entry point is deleted, a new active entry point is found</li>
     * </ul>
     *
     * @param nodeId The ID of the node to delete
     * @return true if the node was found and deleted, false otherwise
     * @throws IllegalStateException if graph is closed
     */
    public boolean delete(long nodeId) {
        return tombstones.delete(nodeId);
    }

    /**
     * Retires every node a named document contributed.
     *
     * <p>A node carries no text, so an erased document's vectors look like harmless residue. They
     * are not: a search that ranks one names its chunk - with a distance, and with the document's
     * name inside the identifier - whether or not there is any text left to show for it. This is
     * what makes an erasure reach the vector index.</p>
     *
     * <p>Nodes are attributed to chunks, and every chunk id carries its document key, so the
     * document's nodes are the ones whose chunk belongs to it; a mapping still keyed by document,
     * read from a data directory an older build wrote, names the document directly and is answered
     * the same way. Each node is retired by {@link #delete(long)}, so the tombstones are the same
     * ones every traversal skips, a restart reads back, and
     * {@link #compact(java.nio.file.Path)} reclaims. A graph with no mapping has nothing to name
     * and retires nothing.</p>
     *
     * @param documentId The document key, {@code sourceId:documentId}
     * @return The number of nodes newly tombstoned; zero when the graph holds none of them, or
     *         when they were already retired
     * @throws IllegalStateException if graph is closed
     */
    public int deleteDocument(String documentId) {
        return tombstones.deleteDocument(documentId);
    }

    /**
     * Retires every node attributed to the named chunks.
     *
     * <p>For a replacement that is shorter than the version it replaces: the chunk ids past its
     * end leave storage and the lexical index, and this is what takes their vectors with them.
     * Only chunks the replacement does not hold may be named here - a chunk id the new version
     * reuses has its old vector retired by {@link #attributeChunk(long, String)} when the new one
     * arrives, and naming it here would retire the new one too if it had already arrived.</p>
     *
     * @param chunkIds The chunk ids, {@code sourceId:documentId:chunk:N}
     * @return The number of nodes newly tombstoned
     * @throws NullPointerException if chunkIds is null
     * @throws IllegalStateException if graph is closed
     */
    public int deleteChunks(java.util.Collection<String> chunkIds) {
        return tombstones.deleteChunks(chunkIds);
    }

    /**
     * Checks if a node is tombstoned (soft-deleted).
     *
     * <p>This method provides a way to check the deletion status of a node
     * without needing to obtain a NodeView.</p>
     *
     * @param nodeId The ID of the node to check
     * @return true if the node is tombstoned, false if active or not found
     * @throws IllegalStateException if graph is closed
     */
    public boolean isTombstoned(long nodeId) {
        return tombstones.isTombstoned(nodeId);
    }

    /**
     * Returns the number of tombstoned (soft-deleted) nodes in the graph.
     *
     * <p>This method iterates through all nodes to count tombstoned entries.
     * The result is not cached, so this is an O(n) operation.</p>
     *
     * @return The number of tombstoned nodes
     * @throws IllegalStateException if graph is closed
     */
    public long tombstoneCount() {
        return tombstones.count();
    }

    /**
     * Returns how many times a node in this graph has been tombstoned.
     *
     * <p>The O(1) counterpart to {@link #tombstoneCount()}. A caller that needs to know
     * <em>whether</em> a retirement has happened since it last looked - a query result cache
     * deciding whether a cached answer is still an answer, for one - cannot afford
     * {@code tombstoneCount()}'s scan of every node on every call, and does not need the
     * count itself. Two samples of this value being equal is evidence that no node's
     * tombstone flag moved between them.</p>
     *
     * <p>It counts transitions, not calls: {@link #delete(long)} returns early without
     * touching it for a node that is already tombstoned, absent, or out of range, so a
     * repeated erasure of the same document leaves it where it was.</p>
     *
     * <p><strong>In-memory, and deliberately not persisted.</strong> It starts at zero for
     * every {@code HnswGraph} instance and is not written to the mapped file, so a graph
     * reopened after a restart reports zero again even though its tombstones are still on
     * disk. That is not a gap in the two properties above, which are both scoped to one
     * instance: a reopen produces a new {@code HnswGraph}, attached to a new {@code Segment}
     * instance, and any caller comparing samples across a reopen is comparing across a
     * restart of the process that owns them. Callers that fingerprint a corpus therefore
     * fold in instance-scoped quantities of their own - {@code Segment.segmentId()} is the
     * process-local instance counter, not the directory index - and a reopened graph is a
     * different corpus to them for that reason, not this one.</p>
     *
     * @return The number of tombstone transitions since this instance was opened
     */
    public long tombstoneGeneration() {
        return tombstones.generation();
    }

    /**
     * Returns the number of active (non-tombstoned) nodes in the graph.
     *
     * @return The number of active nodes
     * @throws IllegalStateException if graph is closed
     */
    public long activeNodeCount() {
        validateNotClosed();
        return nodeCount - tombstoneCount();
    }

    // ==================== Public API - Compaction ====================

    /**
     * Compacts the graph by removing tombstoned nodes and re-optimizing edges.
     *
     * <p>This method performs the following operations:</p>
     * <ol>
     *   <li>Creates a new graph file with only active (non-tombstoned) nodes</li>
     *   <li>Rebuilds adjacency lists to exclude tombstoned neighbors</li>
     *   <li>Atomically swaps the old graph file for the new one</li>
     *   <li>Reclaims disk space from the old file</li>
     * </ol>
     *
     * <p><strong>Thread Safety:</strong> This method acquires a write lock and
     * blocks both reads and writes during compaction. For non-blocking compaction,
     * use {@link #compactTo(Path)} to create a compacted copy.</p>
     *
     * <h3>Domain Constraints</h3>
     * <ul>
     *   <li>Compaction is only performed when tombstones exist</li>
     *   <li>Document ID mappings are preserved across compaction</li>
     *   <li>PQ codes are re-encoded for active nodes if PQ is enabled</li>
     *   <li>Entry point is updated to the highest-level active node</li>
     * </ul>
     *
     * @param tempDir Retained for API compatibility; the compacted graph is written
     *                next to the live file so the final swap is an atomic rename
     *                within a single filesystem
     * @return The number of tombstoned nodes removed
     * @throws IOException if compaction fails
     * @throws IllegalStateException if graph is closed
     */
    public long compact(Path tempDir) throws IOException {
        validateNotClosed();
        Objects.requireNonNull(tempDir, "tempDir cannot be null");
        return compactor.compact();
    }

    /**
     * Creates a compacted copy of the graph at the specified path.
     *
     * <p>This method does not block reads or writes to the current graph,
     * making it suitable for creating backup copies or performing online
     * compaction with minimal disruption.</p>
     *
     * <h3>Domain Constraints</h3>
     * <ul>
     *   <li>Destination path must not exist or will be overwritten</li>
     *   <li>Only active (non-tombstoned) nodes are copied</li>
     *   <li>Document ID mappings are carried across, re-keyed to the copy's node IDs</li>
     * </ul>
     *
     * @param destPath Destination path for the compacted graph
     * @return The number of tombstoned nodes that were skipped
     * @throws IOException if compaction fails
     * @throws IllegalStateException if graph is closed
     */
    public long compactTo(Path destPath) throws IOException {
        return compactor.compactTo(destPath);
    }

    // ==================== Public API - Tail Offset Tracking ====================

    /**
     * Returns the last integrated offset.
     *
     * <p>This offset tracks the write log offset up to which all entries
     * have been linked into the HNSW graph. Entries beyond this offset
     * exist in the write log but are not yet in the graph.</p>
     *
     * <p><strong>Atomicity guarantee:</strong> The returned value is always
     * greater than or equal to the persisted value. This ensures crash recovery
     * correctness - any entries between the persisted value and the returned value
     * will be replayed from the write log.</p>
     *
     * @return The last integrated offset
     */
    public long getLastIntegratedOffset() {
        return checkpoint.get();
    }

    /**
     * Atomically sets the last integrated offset with persistence guarantee.
     *
     * <p><strong>Atomicity and persistence guarantee:</strong> This method ensures
     * that the offset is both atomically updated in memory AND persisted to disk
     * before returning. The implementation uses the following ordering:</p>
     * <ol>
     *   <li>Acquire write lock to prevent concurrent updates</li>
     *   <li>Check monotonicity (new offset must be greater than current)</li>
     *   <li>Write new offset to memory-mapped file (persists to page cache)</li>
     *   <li>Update in-memory atomic value</li>
     *   <li>Release write lock</li>
     * </ol>
     *
     * <p>This ordering ensures that the persisted value is never greater than the
     * value visible to readers. It does <strong>not</strong> make the offset durable:
     * the write goes to the page cache and is not forced, nor ordered against the
     * node index and data pages. Use
     * {@link #compareAndSetLastIntegratedOffsetSync(long)} when durability matters.</p>
     *
     * <p>This method is called by the integration worker after successfully
     * integrating entries from the write log into the graph.</p>
     *
     * @param offset The new last integrated offset (must be >= 0)
     * @throws IllegalArgumentException if offset is negative
     */
    public void setLastIntegratedOffset(long offset) {
        checkpoint.set(offset);
    }

    /**
     * Atomically updates the last integrated offset if the new value is greater,
     * with persistence guarantee.
     *
     * <p>The CAS and the mapped-file write are performed under the read lock so the
     * pair cannot straddle a compaction, which closes and replaces the mapping. If
     * the write fails the in-memory value is rolled back, so the two never diverge.</p>
     *
     * <p><strong>Durability:</strong> the write lands in the page cache only. It is
     * neither forced nor ordered against the node index and data pages, which the
     * kernel may write back in any order. After a crash the persisted offset may
     * therefore be ahead of, behind, or interleaved with the node records it refers
     * to. Use {@link #compareAndSetLastIntegratedOffsetSync(long)} at checkpoints
     * where the offset must actually be durable.</p>
     *
     * <p>Note: multiple concurrent writers retry the CAS; the read lock does not
     * serialise them against each other, only against compaction.</p>
     *
     * @param newOffset The new offset value (must be >= 0)
     * @return true if the offset was updated
     */
    public boolean compareAndSetLastIntegratedOffset(long newOffset) {
        return checkpoint.compareAndSet(newOffset);
    }

    /**
     * Rebases the integration checkpoint onto a different write log, ignoring monotonicity.
     *
     * <p>Every other setter here refuses to go backwards, and that is right while the log is the
     * same one: a checkpoint that moves down would re-integrate entries already in the graph.
     * But compaction <em>replaces</em> the segment's write log with an empty file, and a byte
     * offset into the discarded log means nothing in the new one. The carried checkpoint then sits
     * above the new log's head, so every entry appended after the compaction falls below it and is
     * never integrated — the segment looks idle while silently accepting vectors that no query can
     * ever find.
     *
     * <p>So this is deliberately not monotone, and deliberately narrow: it exists for the
     * replace-the-log case and nothing else. Calling it while the log is unchanged discards
     * integration work and re-does it.
     *
     * <p>Forced to disk rather than left to the page cache. The offset now disagrees with what a
     * crash would otherwise restore, and restoring the old value would point at a log that no
     * longer exists.
     *
     * @param offset the new checkpoint, normally the origin of the replacement log
     * @throws IllegalArgumentException if offset is negative
     */
    public void resetLastIntegratedOffset(long offset) {
        checkpoint.reset(offset);
    }

    /**
     * Atomically updates the last integrated offset with persistence and explicit sync.
     *
     * <p>This method provides stronger durability guarantees by explicitly flushing
     * the offset to durable storage. Use this for critical checkpoints where
     * data loss is unacceptable.</p>
     *
     * <p><strong>Performance note:</strong> This method calls {@link #flush()},
     * which may block on I/O. Use sparingly.</p>
     *
     * @param newOffset The new offset value
     * @return true if the offset was updated
     */
    public boolean compareAndSetLastIntegratedOffsetSync(long newOffset) {
        return checkpoint.compareAndSetSync(newOffset);
    }

    // ==================== Node I/O Operations ====================

    /**
     * Reads a node from disk.
     *
     * <p>This method provides zero-copy access to node data by reading directly
     * from the memory-mapped file. The returned NodeView is a view into the
     * underlying memory, so it should not be used after the graph is closed.</p>
     *
     * <p><strong>Node IDs are generation-scoped.</strong> Compaction renumbers them,
     * so an ID captured before a compaction may name a different vector afterwards.
     * Callers that keep an ID across a possible compaction must use
     * {@link #readNode(long, long)} with the {@link #generation()} the ID came
     * from.</p>
     *
     * @param nodeId The node ID to read
     * @return NodeView for the node, or null if not found
     */
    public NodeView readNode(long nodeId) {
        // The read lock is what makes compaction's write lock exclude this method:
        // compact() closes the current mapping and installs a new one, and reading
        // through the old MappedFile would throw "MappedFile is closed".
        lock.readLock().lock();
        try {
            return readNodeUnderLock(nodeId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a node, rejecting IDs that belong to a superseded graph generation.
     *
     * @param nodeId The node ID to read
     * @param expectedGeneration The generation the node ID was obtained from, or
     *                           {@link HnswSearchResult#GENERATION_UNKNOWN} to skip
     *                           the check
     * @return NodeView for the node, or null if not found or the generation no
     *         longer matches
     * @since 1.3
     */
    public NodeView readNode(long nodeId, long expectedGeneration) {
        lock.readLock().lock();
        try {
            if (expectedGeneration >= 0 && expectedGeneration != generation) {
                LOGGER.debug("Rejecting node {} from superseded graph generation {} (current {})",
                    nodeId, expectedGeneration, generation);
                return null;
            }
            return readNodeUnderLock(nodeId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a node. Must be called with at least the read lock held.
     *
     * @param nodeId The node ID to read
     * @return NodeView for the node, or null if the id names no written node
     */
    NodeView readNodeUnderLock(long nodeId) {
        if (nodeId < 0 || nodeId >= nodeIdGenerator.get()) {
            return null;
        }

        long indexOffset = HnswNodeRecords.indexOffset(nodeId);
        if (indexOffset + HnswNodeRecords.NODE_INDEX_ENTRY_SIZE > dataRegionOffset) {
            return null;
        }

        long storedId = HnswNodeRecords.readEntryId(mappedFile, indexOffset);
        if (storedId != nodeId) {
            return null;
        }

        long dataOffset = HnswNodeRecords.readEntryDataOffset(mappedFile, indexOffset);
        int level = HnswNodeRecords.readEntryLevel(mappedFile, indexOffset);
        int flags = HnswNodeRecords.readEntryFlags(mappedFile, indexOffset);

        long absoluteOffset = dataRegionOffset + dataOffset;

        return new NodeView(nodeId, level, absoluteOffset, flags, generation);
    }

    /**
     * Returns the current graph generation.
     *
     * <p>The generation is incremented every time compaction swaps in a rebuilt
     * file. Because compaction renumbers node IDs, an ID is only meaningful within
     * the generation it was obtained from.</p>
     *
     * @return The current generation (starts at 0)
     * @since 1.3
     */
    public long generation() {
        return generation;
    }

    // ==================== Utility Methods ====================

    /**
     * Computes the ordering distance between a query and a vector.
     *
     * <p>Every comparison in this class - greedy descent, beam admission, neighbour
     * pruning and final sorting - treats the returned value as a distance where
     * <strong>smaller is closer</strong>. {@link DistanceFunction#compute} does not
     * guarantee that direction (cosine and inner product are similarities), so this
     * method must go through {@link DistanceFunction#distance(float[], float[])},
     * which normalizes the direction using the function's declared
     * {@link DistanceFunction.Ordering}.</p>
     *
     * @param query The query vector
     * @param vector The stored vector
     * @return The distance, where smaller is closer
     */
    float computeDistance(float[] query, float[] vector) {
        return distanceFunction.distance(query, vector);
    }

    void validateNotClosed() {
        if (closed) {
            throw new IllegalStateException("HNSW graph is closed: " + path);
        }
    }

    void validateQuery(float[] query) {
        if (query == null) {
            throw new IllegalArgumentException("Query vector cannot be null");
        }
        if (query.length != config.dimension()) {
            throw new IllegalArgumentException(
                "Query dimension mismatch: expected " + config.dimension() +
                ", got " + query.length);
        }
    }

    void validateVector(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("Vector cannot be null");
        }
        if (vector.length != config.dimension()) {
            throw new IllegalArgumentException(
                "Vector dimension mismatch: expected " + config.dimension() +
                ", got " + vector.length);
        }
    }

    // ==================== Accessors ====================

    /** @return the number of nodes in the index, tombstoned ones included */
    public long nodeCount() {
        return nodeCount;
    }

    /** @return the node searches descend from, or -1 if the graph has no active node */
    public long entryPoint() {
        return entryPointId;
    }

    /** @return the highest occupied layer, or -1 if the graph has no active node */
    public int maxLevel() {
        return maxLevel;
    }

    /** @return the configuration in force; on an existing file, the one it was created with */
    public HnswConfig config() {
        return config;
    }

    /** @return the backing file's path */
    public Path path() {
        return path;
    }

    /** @return true if this graph has been closed */
    public boolean isClosed() {
        return closed;
    }

    // ==================== Product Quantization Support ====================

    /**
     * Returns the ProductQuantizer associated with this graph, if any.
     *
     * <p>Product Quantization enables compressed vector storage and asymmetric
     * distance computation for memory-efficient approximate nearest neighbor search.</p>
     *
     * @return The ProductQuantizer, or null if not configured
     */
    public ProductQuantizer getProductQuantizer() {
        return quantization.quantizer();
    }

    /**
     * Sets a ProductQuantizer for this graph.
     *
     * <p>After setting a trained quantizer, subsequent searches can optionally
     * use asymmetric distance computation via the PQ codes.</p>
     *
     * <p><strong>Graceful Fallback:</strong> If the quantizer is not yet trained,
     * searches will use full-precision distance computation until training completes.
     * This allows queries on cold segments during the indexing phase.</p>
     *
     * <p><strong>Note:</strong> The quantizer's dimension must match this graph's dimension.</p>
     *
     * @param quantizer A ProductQuantizer (must not be null, may be untrained)
     * @throws IllegalArgumentException if quantizer dimension doesn't match
     */
    public void setProductQuantizer(ProductQuantizer quantizer) {
        quantization.set(quantizer);
    }

    /** Adopts the codebook sitting beside this graph's file, if there is one. */
    void restorePersistedQuantizer() {
        quantization.restorePersisted();
    }

    /**
     * Trains a ProductQuantizer on a sample of vectors from this graph.
     *
     * <p>This method extracts sample vectors from the graph and trains
     * a new ProductQuantizer. After training, vectors are encoded and
     * stored as PQ codes for efficient distance computation.</p>
     *
     * <p><strong>Note:</strong> Training requires reading vectors from disk,
     * which may be slow for large graphs. Use a representative sample size.</p>
     *
     * @param sampleSize Number of vectors to sample for training (0 = use all)
     * @param numSubspaces Number of subspaces for PQ
     * @param numCentroids Number of centroids per subspace (typically 256)
     * @return The trained ProductQuantizer
     * @throws IllegalStateException if graph is empty or closed
     */
    public ProductQuantizer trainProductQuantizer(int sampleSize, int numSubspaces, int numCentroids) {
        return quantization.train(sampleSize, numSubspaces, numCentroids);
    }

    /**
     * Encodes all vectors in the graph using the configured ProductQuantizer.
     *
     * <p>After encoding, searches can use asymmetric distance computation
     * via {@link #searchWithPQ(float[], int, PQDistanceTable)}.</p>
     *
     * <p><strong>Note:</strong> This is a one-time operation that reads all
     * vectors from disk. Subsequent insertions should call
     * {@link #encodeAndStorePQCode(long, float[])} to maintain consistency.</p>
     *
     * @throws IllegalStateException if ProductQuantizer is not set
     */
    public void encodeAllVectors() {
        quantization.encodeAll();
    }

    /**
     * Encodes and stores PQ codes for a single vector.
     *
     * <p>Call this after inserting a vector to maintain PQ consistency.</p>
     *
     * @param nodeId The node ID
     * @param vector The vector to encode
     * @throws IllegalStateException if ProductQuantizer is not set
     */
    public void encodeAndStorePQCode(long nodeId, float[] vector) {
        quantization.encodeAndStore(nodeId, vector);
    }

    /**
     * Searches for k nearest neighbors using Product Quantization for distance computation.
     *
     * <p>This method uses asymmetric distance computation: the query is kept at full
     * precision while database vectors are represented by their PQ codes. This provides
     * good accuracy with significantly reduced memory usage.</p>
     *
     * <h3>Fallback Behavior</h3>
     * <ul>
     *   <li>If ProductQuantizer is not set, falls back to full-precision search</li>
     *   <li>If a node has no PQ codes, uses full-precision distance</li>
     * </ul>
     *
     * @param query Query vector
     * @param k Number of neighbors to return
     * @return List of search results sorted by distance (closest first)
     */
    public List<HnswSearchResult> searchWithPQ(float[] query, int k) {
        return quantizedSearch.search(query, k);
    }

    /**
     * Searches for k nearest neighbors using a precomputed PQ distance table.
     *
     * <p>Use this when performing multiple searches with the same query,
     * as it reuses the precomputed distance table.</p>
     *
     * @param query Query vector
     * @param k Number of neighbors to return
     * @param distanceTable Precomputed distance table for the query
     * @return List of search results sorted by distance (closest first)
     */
    public List<HnswSearchResult> searchWithPQ(float[] query, int k, PQDistanceTable distanceTable) {
        return quantizedSearch.search(query, k, distanceTable);
    }

    /**
     * Searches with the quantised beam and returns the top k scored on full-precision vectors.
     *
     * <p>The quantised pass is a cheap way to decide which nodes are worth measuring, and
     * nothing more. Its lookups are sums of query-to-centroid terms - near the true distance,
     * but off by an amount that varies per node - so a caller handed them would be ranking on a
     * scale that is not the metric's. A segment's answer is this list merged with the write-log
     * tail scan's, and the tail scan measures exactly; mixing the two scales would let the
     * ranking turn on which half a result came from. Every candidate is therefore re-measured
     * against its stored vector before it is returned, which makes these results
     * indistinguishable in kind from {@link #search(float[], int)}'s.</p>
     *
     * <p>Recall is bought with {@code oversample}: the beam collects {@code oversample * k}
     * candidates and the exact pass picks the k best of them, so a node the quantisation
     * mis-ranks is still recovered as long as it lands inside the wider set.</p>
     *
     * @param query Query vector
     * @param k Number of neighbours to return
     * @param oversample How many candidates to measure per result, at least 1
     * @return The k nearest, closest first, scored exactly
     * @throws IllegalStateException if the graph is closed
     * @throws IllegalArgumentException if the query is null or wrong dimension, k is not
     *         positive, or oversample is below 1
     */
    public List<HnswSearchResult> searchWithPQThenRerank(float[] query, int k, int oversample) {
        return quantizedSearch.searchThenRerank(query, k, oversample);
    }

    /** @return true if a trained product quantizer is configured for this graph */
    public boolean isPQEnabled() {
        return quantization.isEnabled();
    }

    /** @return the number of vectors that have been encoded with PQ */
    public int encodedVectorCount() {
        return quantization.encodedCount();
    }

    // ==================== Lifecycle ====================

    /**
     * Flushes all changes to disk.
     */
    public void flush() {
        lifecycle.flush();
    }

    /**
     * Verifies a graph file on disk without opening the graph.
     *
     * <p>Reads the header and streams the payload past a digest. Nothing is mapped and no
     * graph is put into service, so a file too damaged to serve can still be judged - which
     * is what a checking tool needs and what an open cannot offer.</p>
     *
     * @param path The graph file to verify
     * @return What the check concluded, including whether the file carries a checksum at all
     * @throws NullPointerException if path is null
     */
    public static IntegrityReport verifyIntegrity(Path path) {
        return HnswGraphIntegrity.verify(path);
    }

    // ==================== Segment-based Serialization ====================

    /**
     * Serializes a node's data to a Segment.
     *
     * <p>This method writes the complete node data (ID, level, vector, and all
     * adjacency lists) to the provided Segment at the specified offset. This is
     * useful for exporting individual nodes or creating backups.</p>
     *
     * <p><strong>Format:</strong></p>
     * <pre>
     * [nodeId:8][level:4][vectorLen:4][vector:dim*4]
     * For each level 0..level: [connCount:4][neighbors:maxConn*4]
     * </pre>
     *
     * @param nodeId The node ID to serialize
     * @param segment The target Segment to write to
     * @param offset The offset within the Segment to start writing
     * @return The number of bytes written
     * @throws IllegalStateException if graph is closed
     * @throws NullPointerException if segment is null
     * @throws IllegalArgumentException if nodeId is invalid or segment is too small
     * @since 1.1
     */
    public long serializeNodeToSegment(long nodeId, Segment segment, long offset) {
        return segmentCodec.serializeNode(nodeId, segment, offset);
    }

    /**
     * Deserializes a node from a Segment and inserts it into the graph.
     *
     * <p>This method reads node data from a Segment (in the format written by
     * {@link #serializeNodeToSegment}) and inserts it as a new node. The node
     * is assigned a new ID and connected to the graph structure.</p>
     *
     * <p><strong>Note:</strong> The neighbor IDs in the serialized data are
     * informational only. The new node will be connected using the standard
     * HNSW insertion algorithm.</p>
     *
     * @param segment The source Segment to read from
     * @param offset The offset within the Segment to start reading
     * @return The new node ID assigned to the deserialized node
     * @throws IllegalStateException if graph is closed or full
     * @throws NullPointerException if segment is null
     * @throws IllegalArgumentException if segment data is invalid
     * @since 1.1
     */
    public long deserializeNodeFromSegment(Segment segment, long offset) {
        return segmentCodec.deserializeNode(segment, offset);
    }

    /**
     * Exports the graph header to a Segment.
     *
     * <p>This method writes the complete graph header (first 4KB) to the provided
     * Segment. Useful for creating snapshots or validating graph files.</p>
     *
     * @param segment The target Segment (must have at least HEADER_SIZE bytes)
     * @return The number of bytes written
     * @throws IllegalStateException if graph is closed
     * @throws NullPointerException if segment is null
     * @since 1.1
     */
    public long exportHeaderToSegment(Segment segment) {
        return segmentCodec.exportHeader(segment);
    }

    /**
     * Returns the native MemorySegment if Panama FFM is available.
     *
     * <p>This method provides escape-hatch access to the underlying native
     * MemorySegment for advanced operations. Returns null if Panama FFM is
     * not available or if the graph is not backed by a native segment.</p>
     *
     * <p><strong>Warning:</strong> Direct access to the native MemorySegment
     * bypasses the Segment abstraction. Use with caution.</p>
     *
     * @return The underlying java.lang.foreign.MemorySegment, or null
     * @throws IllegalStateException if graph is closed
     * @since 1.1
     */
    public java.lang.foreign.MemorySegment getNativeMemorySegment() {
        validateNotClosed();
        // MappedFile uses Panama FFM internally, but doesn't expose the segment directly
        // This method is provided for API consistency
        return null;
    }

    // ==================== Node-to-Document Mapping ====================

    /**
     * Attributes a node to the identifier it will be reported under, and nothing more.
     *
     * <p>The identifier is the chunk id the node is the vector of. This registers it without
     * retiring anything, which is right where the caller knows no other node holds the same chunk
     * - a graph rebuilt from a segment's live entries, one per key, or a compaction copying
     * nodes that were already attributed. The integration worker, which inserts whatever the
     * write log holds including a chunk's later values, uses
     * {@link #attributeChunk(long, String)} instead.</p>
     *
     * @param nodeId The HNSW node ID
     * @param documentId The chunk id, {@code sourceId:documentId:chunk:N}
     * @throws IllegalArgumentException if nodeId is negative or documentId is null/empty
     */
    public void registerDocumentId(long nodeId, String documentId) {
        if (nodeDocumentMapping != null) {
            nodeDocumentMapping.register(nodeId, documentId);
            LOGGER.trace("Registered key={} for nodeId={}", documentId, nodeId);
        }
    }

    /**
     * Attributes a node to a chunk as that chunk's current vector, retiring the nodes that held
     * its earlier values.
     *
     * <p>A chunk id is reused whenever a document is posted or re-crawled again, and the chunk's
     * new value becomes a new node. Storage keeps only the newest value under the id; the graph
     * keeps every node until something retires it, and an old one left alone goes on ranking the
     * old text under the reused name - a query about wording the document no longer has finds it
     * anyway, and every re-post grows the graph by a document's worth of dead vectors.</p>
     *
     * <p>Retiring here, as the new value is attributed, rather than when the replacement is
     * published, is what keeps the chunk from ever being without a vector: the old node stays
     * searchable until the new one is in the graph, and the two are ordered by the write log the
     * worker reads rather than by a race between the worker and the ingesting thread. Called in
     * log order, the last value written is the one left standing.</p>
     *
     * @param nodeId  The node just inserted for the chunk
     * @param chunkId The chunk id, {@code sourceId:documentId:chunk:N}
     * @return How many earlier nodes of the chunk were newly retired
     * @throws IllegalArgumentException if nodeId is negative or chunkId is null/empty
     * @throws IllegalStateException if graph is closed
     */
    public int attributeChunk(long nodeId, String chunkId) {
        return tombstones.attributeChunk(nodeId, chunkId);
    }

    /**
     * Gets the identifier a node is reported under.
     *
     * @param nodeId The HNSW node ID
     * @return The chunk id - or, in a mapping an older build wrote and the node has not re-keyed
     *         yet, the document key - or null if the node is not attributed
     */
    public String getDocumentId(long nodeId) {
        return nodeDocumentMapping != null ? nodeDocumentMapping.getDocumentId(nodeId) : null;
    }

    /**
     * What this graph's node attribution names.
     *
     * @return {@link NodeDocumentMapping.Keying#CHUNK} for every graph this build writes, and
     *         {@link NodeDocumentMapping.Keying#DOCUMENT} for one opened from a mapping an older
     *         build wrote that has not been re-keyed yet; see {@link ChunkAttributionUpgrade}
     */
    public NodeDocumentMapping.Keying attributionKeying() {
        NodeDocumentMapping mapping = nodeDocumentMapping;
        return mapping == null ? NodeDocumentMapping.Keying.CHUNK : mapping.keying();
    }

    /**
     * Sets the node-to-document mapping for this graph.
     *
     * <p>This allows using a persistent mapping that can survive restarts.</p>
     *
     * @param mapping The mapping to use
     */
    public void setNodeDocumentMapping(NodeDocumentMapping mapping) {
        this.nodeDocumentMapping = Objects.requireNonNull(mapping, "mapping cannot be null");
    }

    /**
     * Gets the current node-to-document mapping.
     *
     * @return The current mapping, or null if not set
     */
    public NodeDocumentMapping getNodeDocumentMapping() {
        return nodeDocumentMapping;
    }

    /**
     * Creates a search result with the document ID populated from the mapping.
     *
     * @param nodeId The node ID
     * @param distance The distance value
     * @return An HnswSearchResult with the document ID included
     */
    HnswSearchResult createSearchResult(long nodeId, float distance) {
        String documentId = getDocumentId(nodeId);
        // Stamp the generation so a caller that re-reads this node later can have a
        // renumbered ID rejected rather than silently resolved to another vector.
        return new HnswSearchResult(nodeId, distance, documentId, generation);
    }

    @Override
    public void close() {
        lifecycle.close();
    }

    @Override
    public String toString() {
        return String.format("HnswGraph{path=%s, nodes=%d, dimension=%d, maxLevel=%d}",
            path, nodeCount, config.dimension(), maxLevel);
    }

    // ==================== Inner Classes ====================

    /**
     * View of a node stored in the memory-mapped file.
     *
     * <p>This class provides zero-copy access to node data by reading
     * directly from the memory-mapped file. It does not load the entire
     * node into memory, making it suitable for large graphs.</p>
     */
    public final class NodeView {
        private final long nodeId;
        private final int level;
        private final long dataOffset;
        private final int flags;
        private final long viewGeneration;

        NodeView(long nodeId, int level, long dataOffset, int flags, long viewGeneration) {
            this.nodeId = nodeId;
            this.level = level;
            this.dataOffset = dataOffset;
            this.flags = flags;
            this.viewGeneration = viewGeneration;
        }

        /** @return the graph generation this view was created in */
        public long generation() {
            return viewGeneration;
        }

        /** @return the absolute offset of this node's record within the file */
        long dataOffset() {
            return dataOffset;
        }

        /**
         * Fails fast if compaction replaced the backing file since this view was
         * created: the recorded offset then points into a file that no longer
         * describes this node.
         */
        private void validateGeneration() {
            if (viewGeneration != generation) {
                throw new IllegalStateException(
                    "NodeView for node " + nodeId + " belongs to graph generation " +
                        viewGeneration + " but the graph is now at generation " + generation);
            }
        }

        /** @return this node's id */
        public long nodeId() {
            return nodeId;
        }

        /** @return the highest layer this node occupies */
        public int level() {
            return level;
        }

        /** @return true if this node was tombstoned as of when the view was taken */
        public boolean isTombstoned() {
            return (flags & HnswNodeRecords.FLAG_TOMBSTONE) != 0;
        }

        /**
         * Reads the vector data for this node.
         *
         * @return The vector (a new array is allocated each time)
         */
        public float[] vector() {
            lock.readLock().lock();
            try {
                validateGeneration();
                return HnswNodeRecords.readVector(mappedFile, config, dataOffset);
            } finally {
                lock.readLock().unlock();
            }
        }

        /**
         * Reads the neighbor list for a specific level.
         *
         * <p><strong>Note:</strong> This returns ALL neighbors, including potentially
         * tombstoned ones. Use {@link #getActiveNeighbors(int)} to get only active neighbors.</p>
         *
         * @param level The level to read (0 to this.level)
         * @return List of neighbor node IDs (may include tombstoned nodes)
         */
        public List<Long> getNeighbors(int level) {
            if (level > this.level || level < 0) {
                return new ArrayList<>();
            }

            lock.readLock().lock();
            try {
                validateGeneration();
                return HnswNodeRecords.readNeighbors(mappedFile, config, dataOffset, level);
            } finally {
                lock.readLock().unlock();
            }
        }

        /**
         * Reads the neighbor list for a specific level, filtering out tombstoned nodes.
         *
         * <p>This method is used during graph traversal to skip deleted nodes.
         * Tombstoned nodes are never returned in the result list.</p>
         *
         * @param level The level to read (0 to this.level)
         * @return List of active (non-tombstoned) neighbor node IDs
         */
        public List<Long> getActiveNeighbors(int level) {
            lock.readLock().lock();
            try {
                List<Long> allNeighbors = getNeighbors(level);
                List<Long> activeNeighbors = new ArrayList<>(allNeighbors.size());

                for (Long neighborId : allNeighbors) {
                    NodeView neighbor = readNodeUnderLock(neighborId);
                    if (neighbor != null && !neighbor.isTombstoned()) {
                        activeNeighbors.add(neighborId);
                    }
                }

                return activeNeighbors;
            } finally {
                lock.readLock().unlock();
            }
        }
    }
}
