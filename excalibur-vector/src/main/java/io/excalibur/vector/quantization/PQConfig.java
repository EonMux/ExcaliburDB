package io.excalibur.vector.quantization;

/**
 * Configuration for Product Quantization.
 *
 * <p>This class encapsulates all configuration parameters for Product Quantization,
 * providing sensible defaults and validation. Product Quantization compresses
 * high-dimensional vectors into compact codes for memory-efficient approximate
 * nearest neighbor search.</p>
 *
 * <h2>Default Configuration</h2>
 * <ul>
 *   <li><strong>numSubspaces (M):</strong> 8 - Number of subspaces to divide vectors into</li>
 *   <li><strong>numCentroids (K):</strong> 256 - Number of centroids per subspace (8-bit codes)</li>
 *   <li><strong>maxTrainingSamples:</strong> 100,000 - Maximum samples for training</li>
 *   <li><strong>maxIterations:</strong> 20 - Maximum K-means iterations</li>
 *   <li><strong>convergenceThreshold:</strong> 1e-4 - K-means convergence threshold</li>
 * </ul>
 *
 * <h2>Dimension Requirements</h2>
 * <p>The original vector dimension must be evenly divisible by {@code numSubspaces}:</p>
 * <pre>
 * originalDim % numSubspaces == 0
 * subvectorDim = originalDim / numSubspaces
 * </pre>
 *
 * <h2>Memory Compression</h2>
 * <p>With default settings (M=8, K=256):</p>
 * <ul>
 *   <li>Original vector: 128 dimensions × 4 bytes = 512 bytes</li>
 *   <li>Compressed: 8 bytes (64× compression)</li>
 *   <li>Code range: 0-255 per subspace (fits in 1 byte)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is immutable and thread-safe.</strong></p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class PQConfig {

    /**
     * Default number of subspaces (M=8).
     * Each vector is divided into 8 sub-vectors for independent quantization.
     */
    public static final int DEFAULT_NUM_SUBSPACES = 8;

    /**
     * Default number of centroids per subspace (K=256).
     * With 256 centroids, each code fits in exactly 1 byte.
     */
    public static final int DEFAULT_NUM_CENTROIDS = 256;

    /**
     * Default maximum number of training samples.
     * Sufficient for good codebook quality while bounding training time.
     */
    public static final int DEFAULT_MAX_TRAINING_SAMPLES = 100_000;

    /**
     * Default maximum K-means iterations per subspace.
     */
    public static final int DEFAULT_MAX_ITERATIONS = 20;

    /**
     * Default convergence threshold for K-means.
     */
    public static final float DEFAULT_CONVERGENCE_THRESHOLD = 1e-4f;

    /**
     * Minimum number of subspaces allowed.
     */
    public static final int MIN_NUM_SUBSPACES = 1;

    /**
     * Maximum number of subspaces allowed (practical limit).
     */
    public static final int MAX_NUM_SUBSPACES = 128;

    /**
     * Minimum number of centroids per subspace.
     */
    public static final int MIN_NUM_CENTROIDS = 2;

    /**
     * Maximum number of centroids (limited to byte encoding).
     */
    public static final int MAX_NUM_CENTROIDS = 256;

    private final int numSubspaces;
    private final int numCentroids;
    private final int maxTrainingSamples;
    private final int maxIterations;
    private final float convergenceThreshold;
    private final long seed;

    /**
     * Creates a PQConfig with default settings.
     *
     * <p>Default: M=8 subspaces, K=256 centroids (8-bit codes).</p>
     */
    public PQConfig() {
        this(DEFAULT_NUM_SUBSPACES, DEFAULT_NUM_CENTROIDS);
    }

    /**
     * Creates a PQConfig with specified subspace and centroid counts.
     *
     * @param numSubspaces Number of subspaces (M) - dimension must be divisible by this
     * @param numCentroids Number of centroids per subspace (K), typically 256 for byte encoding
     * @throws IllegalArgumentException if parameters are invalid
     */
    public PQConfig(int numSubspaces, int numCentroids) {
        this(numSubspaces, numCentroids, DEFAULT_MAX_TRAINING_SAMPLES,
             DEFAULT_MAX_ITERATIONS, DEFAULT_CONVERGENCE_THRESHOLD, 0);
    }

    /**
     * Creates a PQConfig with all parameters specified.
     *
     * @param numSubspaces Number of subspaces (M) - dimension must be divisible by this
     * @param numCentroids Number of centroids per subspace (K), typically 256 for byte encoding
     * @param maxTrainingSamples Maximum samples to use for training (0 = use all)
     * @param maxIterations Maximum K-means iterations per subspace
     * @param convergenceThreshold K-means convergence threshold
     * @param seed Random seed for reproducibility (0 = non-deterministic)
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public PQConfig(int numSubspaces, int numCentroids, int maxTrainingSamples,
                    int maxIterations, float convergenceThreshold, long seed) {
        validateNumSubspaces(numSubspaces);
        validateNumCentroids(numCentroids);
        validateMaxTrainingSamples(maxTrainingSamples);
        validateMaxIterations(maxIterations);
        validateConvergenceThreshold(convergenceThreshold);

        this.numSubspaces = numSubspaces;
        this.numCentroids = numCentroids;
        this.maxTrainingSamples = maxTrainingSamples > 0 ? maxTrainingSamples : Integer.MAX_VALUE;
        this.maxIterations = maxIterations;
        this.convergenceThreshold = convergenceThreshold;
        this.seed = seed;
    }

    // ==================== Validation Methods ====================

    private void validateNumSubspaces(int numSubspaces) {
        if (numSubspaces < MIN_NUM_SUBSPACES || numSubspaces > MAX_NUM_SUBSPACES) {
            throw new IllegalArgumentException(
                "numSubspaces must be in [" + MIN_NUM_SUBSPACES + ", " + MAX_NUM_SUBSPACES +
                "]: " + numSubspaces);
        }
    }

    private void validateNumCentroids(int numCentroids) {
        if (numCentroids < MIN_NUM_CENTROIDS || numCentroids > MAX_NUM_CENTROIDS) {
            throw new IllegalArgumentException(
                "numCentroids must be in [" + MIN_NUM_CENTROIDS + ", " + MAX_NUM_CENTROIDS +
                "]: " + numCentroids);
        }
    }

    private void validateMaxTrainingSamples(int maxTrainingSamples) {
        if (maxTrainingSamples < 0) {
            throw new IllegalArgumentException(
                "maxTrainingSamples must be >= 0: " + maxTrainingSamples);
        }
    }

    private void validateMaxIterations(int maxIterations) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException(
                "maxIterations must be >= 1: " + maxIterations);
        }
    }

    private void validateConvergenceThreshold(float convergenceThreshold) {
        if (convergenceThreshold < 0) {
            throw new IllegalArgumentException(
                "convergenceThreshold must be >= 0: " + convergenceThreshold);
        }
    }

    // ==================== Factory Methods ====================

    /**
     * Creates a PQConfig suitable for typical embedding dimensions.
     *
     * <p>Automatically selects M based on dimension:</p>
     * <ul>
     *   <li>dim=384: M=12 (32D subvectors)</li>
     *   <li>dim=768: M=24 (32D subvectors)</li>
     *   <li>dim=1536: M=48 (32D subvectors)</li>
     * </ul>
     *
     * @param dimension The vector dimension
     * @return PQConfig optimized for the given dimension
     * @throws IllegalArgumentException if dimension is invalid
     */
    public static PQConfig forDimension(int dimension) {
        if (dimension < 1) {
            throw new IllegalArgumentException("Dimension must be >= 1: " + dimension);
        }

        // Find a good M that divides the dimension evenly
        // Prefer subvector dimensions around 16-64 for good balance
        int bestM = DEFAULT_NUM_SUBSPACES;
        int bestSubvectorDim = dimension;

        for (int m = 4; m <= Math.min(64, dimension); m++) {
            if (dimension % m == 0) {
                int subvectorDim = dimension / m;
                // Prefer subvector dimensions in the 16-64 range
                if (subvectorDim >= 16 && subvectorDim <= 64) {
                    if (Math.abs(subvectorDim - 32) < Math.abs(bestSubvectorDim - 32)) {
                        bestM = m;
                        bestSubvectorDim = subvectorDim;
                    }
                }
            }
        }

        // If no good divisor found, use the largest divisor <= dimension/8
        if (dimension % bestM != 0) {
            for (int m = dimension / 8; m >= 1; m--) {
                if (dimension % m == 0) {
                    bestM = m;
                    break;
                }
            }
        }

        return new PQConfig(bestM, DEFAULT_NUM_CENTROIDS);
    }

    /**
     * Creates a PQConfig optimized for high compression.
     *
     * <p>Uses more subspaces for higher compression at the cost of accuracy.</p>
     *
     * @param dimension The vector dimension
     * @return PQConfig with higher compression
     */
    public static PQConfig highCompression(int dimension) {
        // Use more subspaces for higher compression
        int m = Math.min(32, findDivisor(dimension, 16));
        return new PQConfig(m, DEFAULT_NUM_CENTROIDS);
    }

    /**
     * Creates a PQConfig optimized for accuracy.
     *
     * <p>Uses fewer subspaces for better accuracy at the cost of compression.</p>
     *
     * @param dimension The vector dimension
     * @return PQConfig with better accuracy
     */
    public static PQConfig highAccuracy(int dimension) {
        // Use fewer subspaces for better accuracy
        int m = Math.max(4, findDivisor(dimension, 4));
        return new PQConfig(m, DEFAULT_NUM_CENTROIDS);
    }

    /**
     * Finds a divisor of dimension close to the target.
     */
    private static int findDivisor(int dimension, int target) {
        // Check if target works
        if (dimension % target == 0) {
            return target;
        }

        // Find closest divisor
        for (int offset = 0; offset < Math.max(target, dimension - target); offset++) {
            if (target + offset <= dimension && dimension % (target + offset) == 0) {
                return target + offset;
            }
            if (target - offset > 0 && dimension % (target - offset) == 0) {
                return target - offset;
            }
        }

        return DEFAULT_NUM_SUBSPACES;
    }

    // ==================== Instance Methods ====================

    /**
     * Validates that the given dimension is compatible with this configuration.
     *
     * @param dimension The vector dimension to validate
     * @throws IllegalArgumentException if dimension is not divisible by numSubspaces
     */
    public void validateDimension(int dimension) {
        if (dimension < 1) {
            throw new IllegalArgumentException("Dimension must be >= 1: " + dimension);
        }
        if (dimension % numSubspaces != 0) {
            throw new IllegalArgumentException(
                "Dimension (" + dimension + ") must be divisible by numSubspaces (" + numSubspaces + ")");
        }
    }

    /**
     * Returns true if the given dimension is compatible with this configuration.
     *
     * @param dimension The vector dimension to check
     * @return true if dimension is divisible by numSubspaces
     */
    public boolean isCompatible(int dimension) {
        return dimension > 0 && dimension % numSubspaces == 0;
    }

    /**
     * Returns the subvector dimension for a given original dimension.
     *
     * @param dimension The original vector dimension
     * @return The subvector dimension
     * @throws IllegalArgumentException if dimension is not compatible
     */
    public int subvectorDim(int dimension) {
        validateDimension(dimension);
        return dimension / numSubspaces;
    }

    /**
     * Returns the compressed code size in bytes.
     *
     * <p>Each vector is compressed to exactly numSubspaces bytes.</p>
     *
     * @return Code size in bytes
     */
    public int codeSize() {
        return numSubspaces;
    }

    /**
     * Returns the compression ratio for a given dimension.
     *
     * <p>Compression ratio = original size / compressed size</p>
     * <p>Original size = dimension * 4 bytes (float)</p>
     * <p>Compressed size = numSubspaces bytes</p>
     *
     * @param dimension The original vector dimension
     * @return Compression ratio
     */
    public float compressionRatio(int dimension) {
        validateDimension(dimension);
        return (float) (dimension * Float.BYTES) / numSubspaces;
    }

    /**
     * Creates a ProductQuantizer with this configuration.
     *
     * @param dimension The vector dimension
     * @return A new ProductQuantizer (not yet trained)
     * @throws IllegalArgumentException if dimension is not compatible
     */
    public ProductQuantizer createQuantizer(int dimension) {
        validateDimension(dimension);
        CodebookTrainer trainer = new CodebookTrainer(
            maxTrainingSamples, maxIterations, convergenceThreshold, seed);
        return new ProductQuantizer(dimension, numSubspaces, numCentroids, trainer);
    }

    /**
     * Creates a CodebookTrainer with this configuration's training parameters.
     *
     * @return A new CodebookTrainer
     */
    public CodebookTrainer createTrainer() {
        return new CodebookTrainer(maxTrainingSamples, maxIterations, convergenceThreshold, seed);
    }

    // ==================== Accessors ====================

    /**
     * Returns the number of subspaces (M).
     */
    public int numSubspaces() {
        return numSubspaces;
    }

    /**
     * Returns the number of centroids per subspace (K).
     */
    public int numCentroids() {
        return numCentroids;
    }

    /**
     * Returns the maximum number of training samples.
     */
    public int maxTrainingSamples() {
        return maxTrainingSamples;
    }

    /**
     * Returns the maximum K-means iterations.
     */
    public int maxIterations() {
        return maxIterations;
    }

    /**
     * Returns the K-means convergence threshold.
     */
    public float convergenceThreshold() {
        return convergenceThreshold;
    }

    /**
     * Returns the random seed (0 = non-deterministic).
     */
    public long seed() {
        return seed;
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PQConfig pqConfig = (PQConfig) o;
        return numSubspaces == pqConfig.numSubspaces &&
               numCentroids == pqConfig.numCentroids &&
               maxTrainingSamples == pqConfig.maxTrainingSamples &&
               maxIterations == pqConfig.maxIterations &&
               Float.compare(pqConfig.convergenceThreshold, convergenceThreshold) == 0 &&
               seed == pqConfig.seed;
    }

    @Override
    public int hashCode() {
        int result = numSubspaces;
        result = 31 * result + numCentroids;
        result = 31 * result + maxTrainingSamples;
        result = 31 * result + maxIterations;
        result = 31 * result + (convergenceThreshold != +0.0f ? Float.floatToIntBits(convergenceThreshold) : 0);
        result = 31 * result + (int) (seed ^ (seed >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return String.format("PQConfig{M=%d, K=%d, maxSamples=%d, maxIter=%d, threshold=%g, seed=%d}",
            numSubspaces, numCentroids, maxTrainingSamples, maxIterations, convergenceThreshold, seed);
    }

    // ==================== Builder ====================

    /**
     * Creates a new builder for PQConfig.
     *
     * @return A new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for PQConfig.
     */
    public static final class Builder {
        private int numSubspaces = DEFAULT_NUM_SUBSPACES;
        private int numCentroids = DEFAULT_NUM_CENTROIDS;
        private int maxTrainingSamples = DEFAULT_MAX_TRAINING_SAMPLES;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private float convergenceThreshold = DEFAULT_CONVERGENCE_THRESHOLD;
        private long seed = 0;

        /**
         * Sets the number of subspaces (M).
         */
        public Builder numSubspaces(int numSubspaces) {
            this.numSubspaces = numSubspaces;
            return this;
        }

        /**
         * Sets the number of centroids per subspace (K).
         */
        public Builder numCentroids(int numCentroids) {
            this.numCentroids = numCentroids;
            return this;
        }

        /**
         * Sets the maximum number of training samples.
         */
        public Builder maxTrainingSamples(int maxTrainingSamples) {
            this.maxTrainingSamples = maxTrainingSamples;
            return this;
        }

        /**
         * Sets the maximum K-means iterations.
         */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets the K-means convergence threshold.
         */
        public Builder convergenceThreshold(float convergenceThreshold) {
            this.convergenceThreshold = convergenceThreshold;
            return this;
        }

        /**
         * Sets the random seed for reproducibility.
         */
        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * Builds the PQConfig.
         *
         * @return A new PQConfig instance
         * @throws IllegalArgumentException if any parameter is invalid
         */
        public PQConfig build() {
            return new PQConfig(numSubspaces, numCentroids, maxTrainingSamples,
                maxIterations, convergenceThreshold, seed);
        }
    }
}
