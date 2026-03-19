/**
 * Vector index implementations for approximate nearest neighbor search.
 *
 * <p>This package provides HNSW (Hierarchical Navigable Small World) graph-based
 * vector index implementations for efficient approximate nearest neighbor (ANN)
 * search on high-dimensional vectors.</p>
 *
 * <h2>HNSW Algorithm</h2>
 * <p>HNSW builds a multi-layer graph structure where:</p>
 * <ul>
 *   <li>Layer 0 contains all vectors with the most connections</li>
 *   <li>Higher layers contain exponentially fewer nodes for fast traversal</li>
 *   <li>Search starts at the top layer and descends, narrowing candidates</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>The {@link io.excalibur.vector.index.HnswConfig} class controls key parameters:</p>
 * <ul>
 *   <li>{@code efConstruction} - Index quality during construction (default: 200)</li>
 *   <li>{@code efSearch} - Search quality at query time (default: 100)</li>
 *   <li>{@code maxConnections} - Connections per node in upper layers (default: 16)</li>
 *   <li>{@code maxConnectionsLayer0} - Connections at layer 0 (default: 32)</li>
 * </ul>
 *
 * <p>Default configuration targets ≥99% recall@10.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Classes in this package document their thread-safety guarantees:</p>
 * <ul>
 *   <li>{@link io.excalibur.vector.index.HnswConfig} - Immutable, thread-safe</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <p>Parameters are validated to ensure sensible values:</p>
 * <ul>
 *   <li>All connection counts must be positive integers</li>
 *   <li>ef parameters must be at least 1</li>
 *   <li>Max connections are capped to prevent excessive memory usage</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 * @see <a href="https://arxiv.org/abs/1603.09320">Efficient and robust approximate nearest
 *      neighbor search using Hierarchical Navigable Small World graphs</a>
 */
package io.excalibur.vector.index;
