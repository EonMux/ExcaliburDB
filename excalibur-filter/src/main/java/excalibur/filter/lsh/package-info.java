/**
 * Locality-Sensitive Hashing (LSH) for approximate nearest neighbor search.
 *
 * <h2>Overview</h2>
 * <p>This package provides multi-probe random hyperplane LSH implementation for
 * approximate nearest neighbor pre-filtering on float vectors. LSH is used in
 * ExcaliburDB as a coarse vector similarity filter to reduce candidates before
 * precise HNSW search.</p>
 *
 * <h2>Components</h2>
 * <ul>
 *   <li>{@link excalibur.filter.lsh.LshConfig} - Configuration parameters</li>
 *   <li>{@link excalibur.filter.lsh.RandomHyperplaneHash} - Random hyperplane hash function</li>
 *   <li>{@link excalibur.filter.lsh.MultiProbeGenerator} - Multi-probe sequence generator</li>
 *   <li>{@link excalibur.filter.lsh.LshHashTable} - Single hash table with buckets</li>
 *   <li>{@link excalibur.filter.lsh.LshIndex} - Main entry point combining multiple tables</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create index for 768-dimensional vectors
 * LshIndex index = new LshIndex(new LshConfig(768));
 *
 * // Insert vectors
 * float[] vector1 = ...;
 * index.insert(vector1, 1L);
 *
 * // Query for similar vectors
 * float[] query = ...;
 * Set<Long> candidates = index.query(query);
 *
 * // Delete when entry is removed
 * index.delete(1L);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>All classes in this package are thread-safe for both read and write operations.</p>
 *
 * @author ExcaliburDB
 */
package excalibur.filter.lsh;
