/**
 * Disk-backed HNSW (Hierarchical Navigable Small World) graph implementation
 * for approximate nearest neighbor search.
 *
 * <h2>Overview</h2>
 * <p>This package provides an HNSW graph index that stores nodes and adjacency
 * lists in memory-mapped files, making the graph navigable directly from disk
 * without requiring full loading into memory. This is critical for handling
 * large-scale vector indexes that exceed available RAM.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Direct disk navigation:</strong> Graph traversal reads directly
 *       from memory-mapped file without loading into heap</li>
 *   <li><strong>Incremental insertion:</strong> Vectors can be added without
 *       requiring a full rebuild</li>
 *   <li><strong>Soft-delete:</strong> Tombstoned nodes are skipped during
 *       traversal but remain on disk until compaction</li>
 *   <li><strong>Tail offset tracking:</strong> Tracks integration progress via
 *       lastIntegratedOffset for live indexing</li>
 *   <li><strong>Thread-safe:</strong> Read-write locks allow concurrent reads
 *       while ensuring exclusive writes</li>
 * </ul>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link com.eonmux.excalibur.vector.hnsw.HnswGraph} - Main graph implementation</li>
 *   <li>{@link com.eonmux.excalibur.vector.hnsw.HnswConfig} - Configuration parameters</li>
 *   <li>{@link com.eonmux.excalibur.vector.hnsw.HnswSearchResult} - Search result container</li>
 * </ul>
 *
 * <h2>Disk Layout</h2>
 * <pre>
 * +------------------------+
 * | Metadata Header (4KB)  |
 * +------------------------+
 * | Node Index Table       |
 * +------------------------+
 * | Node Data Region       |
 * +------------------------+
 * </pre>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create configuration
 * HnswConfig config = HnswConfig.create(128); // 128-dimensional vectors
 *
 * // Create graph
 * try (HnswGraph graph = new HnswGraph(
 *         Path.of("vectors.hnsw"),
 *         config,
 *         CosineSimilarity.getInstance(),
 *         1_000_000)) {
 *
 *     // Insert vectors
 *     float[] vec1 = generateVector(128);
 *     long id1 = graph.insert(vec1);
 *
 *     // Search for nearest neighbors
 *     float[] query = generateVector(128);
 *     List<HnswSearchResult> results = graph.search(query, 10);
 *
 *     // Process results
 *     for (HnswSearchResult result : results) {
 *         System.out.println("Node " + result.nodeId() +
 *             " at distance " + result.distance());
 *     }
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>All classes in this package are thread-safe:</p>
 * <ul>
 *   <li>Read operations (search) allow concurrent access</li>
 *   <li>Write operations (insert, delete) require exclusive access</li>
 *   <li>Configuration objects are immutable</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 * @see <a href="https://arxiv.org/abs/1603.09320">Efficient and robust approximate nearest neighbor search
 *      using Hierarchical Navigable Small World graphs</a>
 */
package com.eonmux.excalibur.vector.hnsw;
