package com.eonmux.excalibur.filter.lsh;

/**
 * Configuration for Locality-Sensitive Hashing (LSH) index.
 *
 * <h2>Overview</h2>
 * <p>LSH is used for approximate nearest neighbor search. The configuration
 * parameters control the trade-off between recall (finding true neighbors)
 * and precision (avoiding false positives).</p>
 *
 * <h2>Parameter Guidelines</h2>
 * <ul>
 *   <li><strong>dimensions</strong>: Vector dimensionality (e.g., 768 for OpenAI embeddings,
 *       384 for sentence-transformers/all-MiniLM)</li>
 *   <li><strong>numHashTables</strong>: More tables = higher recall but more memory.
 *       Typical: 10-20 for production, 5-10 for memory-constrained</li>
 *   <li><strong>numHashFunctions</strong>: More functions per table = higher precision but
 *       fewer candidates. Typical: 12-20 bits per hash</li>
 *   <li><strong>numProbes</strong>: Multi-probe LSH checks nearby buckets to improve recall.
 *       Typical: 2-5 probes, higher for higher recall requirements</li>
 * </ul>
 *
 * <h2>Memory Estimation</h2>
 * <p>Memory for hyperplanes: numHashTables * numHashFunctions * dimensions * 4 bytes</p>
 * <p>Example: 15 tables * 16 functions * 768 dimensions * 4 bytes = ~720 KB</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class LshConfig {

    /** Default number of hash tables (good balance of recall/precision). */
    public static final int DEFAULT_NUM_HASH_TABLES = 15;

    /** Default number of hash functions per table (16 bits per hash). */
    public static final int DEFAULT_NUM_HASH_FUNCTIONS = 16;

    /** Default number of multi-probe candidates. */
    public static final int DEFAULT_NUM_PROBES = 3;

    /** Maximum supported number of hash functions (limited to 63 bits for long hash). */
    public static final int MAX_HASH_FUNCTIONS = 63;

    /** Minimum number of hash functions. */
    public static final int MIN_HASH_FUNCTIONS = 1;

    /** Minimum number of dimensions. */
    public static final int MIN_DIMENSIONS = 1;

    // Configuration fields
    private final int dimensions;
    private final int numHashTables;
    private final int numHashFunctions;
    private final long seed;
    private final int numProbes;

    /**
     * Creates a new LshConfig with the specified dimensions and defaults for other parameters.
     *
     * @param dimensions Vector dimensionality (must be positive)
     * @throws IllegalArgumentException if dimensions is not positive
     */
    public LshConfig(int dimensions) {
        this(dimensions, DEFAULT_NUM_HASH_TABLES, DEFAULT_NUM_HASH_FUNCTIONS,
             System.nanoTime(), DEFAULT_NUM_PROBES);
    }

    /**
     * Creates a new LshConfig with the specified parameters.
     *
     * @param dimensions       Vector dimensionality (e.g., 768 for embeddings)
     * @param numHashTables    Number of hash tables (default: 15)
     * @param numHashFunctions Number of hash functions per table (default: 16)
     * @param seed             Random seed for reproducible hyperplane generation
     * @param numProbes        Number of multi-probe candidates (default: 3)
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public LshConfig(int dimensions, int numHashTables, int numHashFunctions,
                     long seed, int numProbes) {
        validateParameters(dimensions, numHashTables, numHashFunctions, numProbes);

        this.dimensions = dimensions;
        this.numHashTables = numHashTables;
        this.numHashFunctions = numHashFunctions;
        this.seed = seed;
        this.numProbes = numProbes;
    }

    /**
     * Creates a new LshConfig with the specified parameters and default seed.
     *
     * @param dimensions       Vector dimensionality
     * @param numHashTables    Number of hash tables
     * @param numHashFunctions Number of hash functions per table
     * @param numProbes        Number of multi-probe candidates
     * @return A new LshConfig instance
     */
    public static LshConfig create(int dimensions, int numHashTables,
                                   int numHashFunctions, int numProbes) {
        return new LshConfig(dimensions, numHashTables, numHashFunctions,
                            System.nanoTime(), numProbes);
    }

    /**
     * Creates a new LshConfig for a specific seed (useful for testing/reproducibility).
     *
     * @param dimensions       Vector dimensionality
     * @param numHashTables    Number of hash tables
     * @param numHashFunctions Number of hash functions per table
     * @param seed             Random seed
     * @return A new LshConfig instance
     */
    public static LshConfig createWithSeed(int dimensions, int numHashTables,
                                           int numHashFunctions, long seed) {
        return new LshConfig(dimensions, numHashTables, numHashFunctions,
                            seed, DEFAULT_NUM_PROBES);
    }

    private static void validateParameters(int dimensions, int numHashTables,
                                           int numHashFunctions, int numProbes) {
        if (dimensions < MIN_DIMENSIONS) {
            throw new IllegalArgumentException(
                "dimensions must be >= " + MIN_DIMENSIONS + ", got: " + dimensions);
        }
        if (numHashTables < 1) {
            throw new IllegalArgumentException(
                "numHashTables must be >= 1, got: " + numHashTables);
        }
        if (numHashFunctions < MIN_HASH_FUNCTIONS || numHashFunctions > MAX_HASH_FUNCTIONS) {
            throw new IllegalArgumentException(
                "numHashFunctions must be in range [" + MIN_HASH_FUNCTIONS + ", " +
                MAX_HASH_FUNCTIONS + "], got: " + numHashFunctions);
        }
        if (numProbes < 1) {
            throw new IllegalArgumentException(
                "numProbes must be >= 1, got: " + numProbes);
        }
    }

    /**
     * Returns the vector dimensionality.
     *
     * @return The number of dimensions
     */
    public int dimensions() {
        return dimensions;
    }

    /**
     * Returns the number of hash tables.
     *
     * @return The number of hash tables
     */
    public int numHashTables() {
        return numHashTables;
    }

    /**
     * Returns the number of hash functions per table.
     *
     * @return The number of hash functions
     */
    public int numHashFunctions() {
        return numHashFunctions;
    }

    /**
     * Returns the random seed for hyperplane generation.
     *
     * @return The seed value
     */
    public long seed() {
        return seed;
    }

    /**
     * Returns the number of multi-probe candidates.
     *
     * @return The number of probes
     */
    public int numProbes() {
        return numProbes;
    }

    /**
     * Estimates the memory usage for hyperplanes in bytes.
     *
     * @return Estimated memory in bytes
     */
    public long estimatedHyperplaneMemory() {
        return (long) numHashTables * numHashFunctions * dimensions * Float.BYTES;
    }

    /**
     * Creates a builder for constructing LshConfig instances.
     *
     * @param dimensions The vector dimensionality
     * @return A new builder
     */
    public static Builder builder(int dimensions) {
        return new Builder(dimensions);
    }

    @Override
    public String toString() {
        return String.format("LshConfig[dimensions=%d, tables=%d, functions=%d, probes=%d, seed=%d]",
            dimensions, numHashTables, numHashFunctions, numProbes, seed);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LshConfig that = (LshConfig) o;
        return dimensions == that.dimensions &&
               numHashTables == that.numHashTables &&
               numHashFunctions == that.numHashFunctions &&
               seed == that.seed &&
               numProbes == that.numProbes;
    }

    @Override
    public int hashCode() {
        int result = dimensions;
        result = 31 * result + numHashTables;
        result = 31 * result + numHashFunctions;
        result = 31 * result + Long.hashCode(seed);
        result = 31 * result + numProbes;
        return result;
    }

    /**
     * Builder for LshConfig.
     */
    public static final class Builder {
        private final int dimensions;
        private int numHashTables = DEFAULT_NUM_HASH_TABLES;
        private int numHashFunctions = DEFAULT_NUM_HASH_FUNCTIONS;
        private long seed = System.nanoTime();
        private int numProbes = DEFAULT_NUM_PROBES;

        private Builder(int dimensions) {
            if (dimensions < MIN_DIMENSIONS) {
                throw new IllegalArgumentException(
                    "dimensions must be >= " + MIN_DIMENSIONS + ", got: " + dimensions);
            }
            this.dimensions = dimensions;
        }

        /**
         * Sets the number of hash tables.
         *
         * @param numHashTables Number of hash tables
         * @return This builder
         */
        public Builder numHashTables(int numHashTables) {
            if (numHashTables < 1) {
                throw new IllegalArgumentException("numHashTables must be >= 1");
            }
            this.numHashTables = numHashTables;
            return this;
        }

        /**
         * Sets the number of hash functions per table.
         *
         * @param numHashFunctions Number of hash functions
         * @return This builder
         */
        public Builder numHashFunctions(int numHashFunctions) {
            if (numHashFunctions < MIN_HASH_FUNCTIONS || numHashFunctions > MAX_HASH_FUNCTIONS) {
                throw new IllegalArgumentException(
                    "numHashFunctions must be in range [" + MIN_HASH_FUNCTIONS + ", " +
                    MAX_HASH_FUNCTIONS + "]");
            }
            this.numHashFunctions = numHashFunctions;
            return this;
        }

        /**
         * Sets the random seed.
         *
         * @param seed The seed value
         * @return This builder
         */
        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * Sets the number of multi-probe candidates.
         *
         * @param numProbes Number of probes
         * @return This builder
         */
        public Builder numProbes(int numProbes) {
            if (numProbes < 1) {
                throw new IllegalArgumentException("numProbes must be >= 1");
            }
            this.numProbes = numProbes;
            return this;
        }

        /**
         * Builds the LshConfig instance.
         *
         * @return A new LshConfig
         */
        public LshConfig build() {
            return new LshConfig(dimensions, numHashTables, numHashFunctions, seed, numProbes);
        }
    }
}
