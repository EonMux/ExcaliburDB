/**
 * Memory layout optimized for JIT auto-vectorization in vector similarity search.
 *
 * <h2>Overview</h2>
 * <p>This package provides data structures and algorithms for storing and
 * computing distances on large collections of vectors with optimal memory
 * layout for SIMD auto-vectorization.</p>
 *
 * <h2>Key Design: Structure of Arrays (SoA)</h2>
 * <p>Traditional vector storage uses Array of Structures (AoS) format:</p>
 * <pre>
 * AoS: [v0_0, v0_1, v0_2, v1_0, v1_1, v1_2, v2_0, v2_1, v2_2, ...]
 * </pre>
 *
 * <p>This package uses Structure of Arrays (SoA) format for optimal SIMD:</p>
 * <pre>
 * SoA: [v0_0, v1_0, v2_0, ...] [v0_1, v1_1, v2_1, ...] [v0_2, v1_2, v2_2, ...]
 *       ^--- Element Block 0 ---^    ^--- Element Block 1 ---^
 * </pre>
 *
 * <h2>Benefits of SoA Layout</h2>
 * <ul>
 *   <li><strong>Contiguous Memory Access:</strong> When computing distance from
 *       query to N vectors, element 0 of all N vectors is stored contiguously</li>
 *   <li><strong>SIMD Efficiency:</strong> Loading 8 element-0s into a vector register
 *       is a single aligned load operation</li>
 *   <li><strong>Cache Optimization:</strong> Each cache line contains homogeneous data
 *       (same element position from different vectors)</li>
 *   <li><strong>Prefetching:</strong> CPU hardware prefetcher can predict sequential access</li>
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 * <table border="1">
 *   <tr><th>Operation</th><th>Complexity</th><th>Memory Pattern</th></tr>
 *   <tr><td>Single vector insert</td><td>O(D)</td><td>Scattered writes</td></tr>
 *   <tr><td>Single vector read</td><td>O(D)</td><td>Scattered reads</td></tr>
 *   <tr><td>Batch distance (N vectors)</td><td>O(N * D)</td><td>Sequential reads</td></tr>
 *   <tr><td>Batch insert (N vectors)</td><td>O(N * D)</td><td>Mixed</td></tr>
 * </table>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a layout for 1000 vectors of dimension 768
 * VectorLayout layout = VectorLayout.create(768, 1000);
 *
 * // Create a vector block
 * VectorBlock block = new VectorBlock(layout);
 *
 * // Insert vectors
 * for (int i = 0; i < vectors.length; i++) {
 *     block.putVector(i, vectors[i]);
 * }
 *
 * // Compute distances from query to all vectors
 * float[] distances = new float[1000];
 * block.computeDistances(queryVector, distances, DistanceType.COSINE);
 *
 * // Find top-k nearest neighbors
 * int[] topK = findTopK(distances, 10);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>{@link com.eonmux.excalibur.vector.layout.VectorLayout} - Immutable, thread-safe</li>
 *   <li>{@link com.eonmux.excalibur.vector.layout.VectorBlock} - Read-safe, writes require synchronization</li>
 *   <li>{@link com.eonmux.excalibur.vector.layout.DistanceType} - Enum, thread-safe</li>
 * </ul>
 *
 * <h2>Memory Alignment</h2>
 * <p>Vector blocks use 64-byte cache line alignment by default:</p>
 * <ul>
 *   <li>Header: 64 bytes (cache-aligned)</li>
 *   <li>Each element block: Padded to 64-byte boundary</li>
 *   <li>Floats per cache line: 16 (64 bytes / 4 bytes)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
package com.eonmux.excalibur.vector.layout;
