package excalibur.filter.bloom;

/**
 * Bloom filter package for ExcaliburDB.
 *
 * <p>This package provides a space-efficient probabilistic data structure for set membership testing.
 * Bloom filters allow false positives but never produce false negatives.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Configurable false positive rate</li>
 *   <li>Memory-mappable serialization via Panama FFM</li>
 *   <li>Thread-safe operations with ReentrantReadWriteLock</li>
 *   <li>Double-hashing technique using XXHash64</li>
 * </ul>
 *
 * <h2>Main Classes</h2>
 * <ul>
 *   <li>{@link BloomFilter} - The main Bloom filter implementation</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @see BloomFilter
 */
