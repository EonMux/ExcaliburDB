/**
 * Locality-Sensitive Hashing (LSH) for approximate nearest neighbor search.
 *
 * <h2>Overview</h2>
 * <p>This package provides multi-probe random hyperplane LSH implementation for
 * approximate nearest neighbor candidate generation on float vectors. ExcaliburDB
 * uses it beside HNSW search, not in front of it: the entries sharing a query's
 * buckets are measured exactly and ranked in with the graph's results, which
 * recovers near neighbours the graph walk passed over. A bucket bounds nothing
 * about the distance of what it leaves out, so its answer is never used to
 * exclude or confine a search.</p>
 *
 * <h2>Components</h2>
 * <ul>
 *   <li>{@link com.eonmux.excalibur.filter.lsh.LshConfig} - Configuration parameters</li>
 *   <li>{@link com.eonmux.excalibur.filter.lsh.RandomHyperplaneHash} - Random hyperplane hash function</li>
 *   <li>{@link com.eonmux.excalibur.filter.lsh.MultiProbeGenerator} - Multi-probe sequence generator</li>
 *   <li>{@link com.eonmux.excalibur.filter.lsh.LshHashTable} - Single hash table with buckets</li>
 *   <li>{@link com.eonmux.excalibur.filter.lsh.LshIndex} - Main entry point combining multiple tables</li>
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
package com.eonmux.excalibur.filter.lsh;
