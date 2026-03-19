package io.excalibur.vector.quantization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Trains Product Quantization codebooks from sample vectors.
 *
 * <p>Codebook training is a critical step in Product Quantization. This class handles:</p>
 * <ul>
 *   <li>Splitting vectors into sub-vectors for each subspace</li>
 *   <li>Sampling from large datasets to bound training time</li>
 *   <li>Running K-means clustering independently on each subspace</li>
 *   <li>Producing codebooks suitable for asymmetric distance computation</li>
 * </ul>
 *
 * <h2>Training Process</h2>
 * <ol>
 *   <li>If sample size exceeds maxTrainingSamples, randomly sample the configured amount</li>
 *   <li>Split each vector into {@code numSubspaces} sub-vectors of dimension {@code subvectorDim}</li>
 *   <li>For each subspace, collect all sub-vectors at that position</li>
 *   <li>Run K-means clustering with k={@code numCentroids} on each subspace's sub-vectors</li>
 *   <li>The resulting centroids form the codebook for that subspace</li>
 * </ol>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Sub-vector dimension = original dimension / num subspaces (must divide evenly)</li>
 *   <li>Number of codebooks = number of subspaces</li>
 *   <li>Each codebook has exactly numCentroids centroids</li>
 *   <li>Each centroid has dimension = sub-vector dimension</li>
 *   <li>Centroid values are bounded by input data range</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is NOT thread-safe.</strong> Training is a batch operation that
 * should be performed once. The resulting codebooks are immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class CodebookTrainer {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodebookTrainer.class);

    /**
     * Default number of training samples (100K).
     * Sufficient for most use cases while keeping training time reasonable.
     */
    public static final int DEFAULT_MAX_TRAINING_SAMPLES = 100_000;

    /**
     * Default maximum K-means iterations.
     */
    public static final int DEFAULT_MAX_ITERATIONS = 20;

    /**
     * Default convergence threshold for K-means.
     */
    public static final float DEFAULT_CONVERGENCE_THRESHOLD = 1e-4f;

    private final int maxTrainingSamples;
    private final int maxIterations;
    private final float convergenceThreshold;
    private final long seed;
    private final KMeansClusterer clusterer;
    private final java.util.Random random;

    /**
     * Creates a new CodebookTrainer with default settings.
     */
    public CodebookTrainer() {
        this(DEFAULT_MAX_TRAINING_SAMPLES, DEFAULT_MAX_ITERATIONS, DEFAULT_CONVERGENCE_THRESHOLD, 0);
    }

    /**
     * Creates a new CodebookTrainer with specified settings.
     *
     * @param maxTrainingSamples Maximum number of samples to use for training
     * @param maxIterations Maximum K-means iterations per subspace
     * @param convergenceThreshold Convergence threshold for K-means
     * @param seed Random seed for reproducibility (0 = non-deterministic)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public CodebookTrainer(int maxTrainingSamples, int maxIterations, float convergenceThreshold, long seed) {
        if (maxTrainingSamples < 1) {
            throw new IllegalArgumentException("maxTrainingSamples must be >= 1: " + maxTrainingSamples);
        }
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1: " + maxIterations);
        }
        if (convergenceThreshold < 0) {
            throw new IllegalArgumentException("convergenceThreshold must be >= 0: " + convergenceThreshold);
        }

        this.maxTrainingSamples = maxTrainingSamples;
        this.maxIterations = maxIterations;
        this.convergenceThreshold = convergenceThreshold;
        this.seed = seed;
        this.clusterer = new KMeansClusterer(maxIterations, convergenceThreshold, seed);
        this.random = seed != 0 ? new java.util.Random(seed) : null;

        LOGGER.debug("CodebookTrainer initialized: maxSamples={}, maxIterations={}, convergenceThreshold={}",
            maxTrainingSamples, maxIterations, convergenceThreshold);
    }

    /**
     * Trains Product Quantization codebooks from sample vectors.
     *
     * <p>This method performs the following steps:</p>
     * <ol>
     *   <li>Validate input parameters and vector dimensions</li>
     *   <li>Sample from input vectors if necessary</li>
     *   <li>Split vectors into sub-vectors</li>
     *   <li>Run K-means clustering on each subspace</li>
     *   <li>Return the resulting codebooks</li>
     * </ol>
     *
     * <h3>Dimension Requirements</h3>
     * <p>The original dimension must be evenly divisible by numSubspaces:</p>
     * <pre>
     * originalDim % numSubspaces == 0
     * subvectorDim = originalDim / numSubspaces
     * </pre>
     *
     * <h3>Sample Size Requirements</h3>
     * <p>For good codebook quality, the sample size should be at least:</p>
     * <ul>
     *   <li>numCentroids * 50 points per subspace</li>
     *   <li>More samples = better codebook quality, but longer training time</li>
     * </ul>
     *
     * @param sampleVectors The sample vectors to train on
     * @param numSubspaces Number of subspaces to divide vectors into
     * @param numCentroids Number of centroids (codewords) per subspace (typically 256 for byte encoding)
     * @return Array of codebooks: {@code codebooks[subspace][centroid][dimension]}
     * @throws IllegalArgumentException if parameters are invalid or dimensions don't divide evenly
     * @throws IllegalStateException if training fails
     */
    public float[][][] trainCodebooks(float[][] sampleVectors, int numSubspaces, int numCentroids) {
        Objects.requireNonNull(sampleVectors, "Sample vectors cannot be null");
        if (numSubspaces < 1) {
            throw new IllegalArgumentException("numSubspaces must be >= 1: " + numSubspaces);
        }
        if (numCentroids < 2) {
            throw new IllegalArgumentException("numCentroids must be >= 2: " + numCentroids);
        }
        if (sampleVectors.length == 0) {
            throw new IllegalArgumentException("Sample vectors cannot be empty");
        }

        // Validate dimensions
        int originalDim = sampleVectors[0].length;
        if (originalDim == 0) {
            throw new IllegalArgumentException("Vector dimension cannot be 0");
        }
        if (originalDim % numSubspaces != 0) {
            throw new IllegalArgumentException(
                "Original dimension (" + originalDim + ") must be evenly divisible by numSubspaces (" + numSubspaces + ")");
        }

        int subvectorDim = originalDim / numSubspaces;

        LOGGER.info("Starting codebook training: {} vectors, {} subspaces, {} centroids/subspace, " +
            "subvectorDim={}", sampleVectors.length, numSubspaces, numCentroids, subvectorDim);

        // Validate all vectors have consistent dimension
        for (int i = 0; i < sampleVectors.length; i++) {
            if (sampleVectors[i] == null) {
                throw new IllegalArgumentException("Vector at index " + i + " is null");
            }
            if (sampleVectors[i].length != originalDim) {
                throw new IllegalArgumentException(
                    "Inconsistent vector dimensions: expected " + originalDim +
                    ", got " + sampleVectors[i].length + " at index " + i);
            }
        }

        // Sample if necessary
        float[][] trainingData = sampleVectors;
        if (sampleVectors.length > maxTrainingSamples) {
            trainingData = sampleVectors(sampleVectors, maxTrainingSamples);
            LOGGER.debug("Sampled {} vectors from {} for training", trainingData.length, sampleVectors.length);
        }

        // Check minimum sample size
        int minSamples = numCentroids * 50; // Heuristic: 50x k for good clustering
        if (trainingData.length < minSamples) {
            LOGGER.warn("Training data size ({}) is below recommended minimum ({} = {} * 50). " +
                "Codebook quality may be suboptimal.", trainingData.length, minSamples, numCentroids);
        }

        // Train codebooks for each subspace
        float[][][] codebooks = new float[numSubspaces][numCentroids][subvectorDim];

        for (int s = 0; s < numSubspaces; s++) {
            LOGGER.debug("Training codebook for subspace {}/{}", s + 1, numSubspaces);

            // Extract sub-vectors for this subspace
            float[][] subvectors = extractSubvectors(trainingData, s, numSubspaces, subvectorDim);

            // Run K-means clustering
            float[][] centroids = clusterer.cluster(subvectors, numCentroids);

            // Store centroids as codebook
            if (centroids.length != numCentroids) {
                throw new IllegalStateException(
                    "K-means returned wrong number of centroids: expected " + numCentroids +
                    ", got " + centroids.length);
            }
            codebooks[s] = centroids;
        }

        LOGGER.info("Completed codebook training for {} subspaces", numSubspaces);
        return codebooks;
    }

    /**
     * Trains codebooks with progress callback.
     *
     * <p>This overload provides a callback for monitoring training progress,
     * useful for long-running training jobs.</p>
     *
     * @param sampleVectors The sample vectors to train on
     * @param numSubspaces Number of subspaces
     * @param numCentroids Number of centroids per subspace
     * @param progressCallback Called with (currentSubspace, totalSubspaces) after each subspace
     * @return Array of codebooks
     */
    public float[][][] trainCodebooks(float[][] sampleVectors, int numSubspaces, int numCentroids,
                                      ProgressCallback progressCallback) {
        Objects.requireNonNull(progressCallback, "Progress callback cannot be null");

        float[][][] codebooks = trainCodebooks(sampleVectors, numSubspaces, numCentroids);

        // Notify completion
        progressCallback.onProgress(numSubspaces, numSubspaces);

        return codebooks;
    }

    /**
     * Extracts sub-vectors from the training data for a specific subspace.
     *
     * <p>For subspace s with d = subvectorDim, extracts elements [s*d, (s+1)*d) from each vector.</p>
     *
     * @param vectors Original vectors
     * @param subspaceIndex Which subspace to extract
     * @param numSubspaces Total number of subspaces
     * @param subvectorDim Dimension of each sub-vector
     * @return Array of sub-vectors for this subspace
     */
    private float[][] extractSubvectors(float[][] vectors, int subspaceIndex,
                                        int numSubspaces, int subvectorDim) {
        float[][] subvectors = new float[vectors.length][subvectorDim];
        int startOffset = subspaceIndex * subvectorDim;

        for (int i = 0; i < vectors.length; i++) {
            System.arraycopy(vectors[i], startOffset, subvectors[i], 0, subvectorDim);
        }

        return subvectors;
    }

    /**
     * Samples a subset of vectors for training.
     *
     * <p>Uses reservoir sampling for uniform random selection when the seed is 0,
     * or deterministic sampling for reproducibility when a seed is provided.</p>
     *
     * @param vectors All available vectors
     * @param sampleSize Number of vectors to sample
     * @return Sampled vectors
     */
    private float[][] sampleVectors(float[][] vectors, int sampleSize) {
        if (vectors.length <= sampleSize) {
            return vectors;
        }

        // Create list of indices
        List<Integer> indices = new ArrayList<>(vectors.length);
        for (int i = 0; i < vectors.length; i++) {
            indices.add(i);
        }

        // Shuffle and take first sampleSize
        if (random != null) {
            // Deterministic shuffle using proper Random
            Collections.shuffle(indices, random);
        } else {
            Collections.shuffle(indices, ThreadLocalRandom.current());
        }

        // Extract sampled vectors
        float[][] sampled = new float[sampleSize][];
        for (int i = 0; i < sampleSize; i++) {
            sampled[i] = vectors[indices.get(i)].clone();
        }

        return sampled;
    }

    /**
     * Returns the maximum training samples.
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
     * Returns the convergence threshold.
     */
    public float convergenceThreshold() {
        return convergenceThreshold;
    }

    @Override
    public String toString() {
        return String.format("CodebookTrainer{maxSamples=%d, maxIterations=%d, convergenceThreshold=%f}",
            maxTrainingSamples, maxIterations, convergenceThreshold);
    }

    /**
     * Callback interface for training progress.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * Called after training each subspace.
         *
         * @param currentSubspace The just-completed subspace (1-indexed)
         * @param totalSubspaces Total number of subspaces
         */
        void onProgress(int currentSubspace, int totalSubspaces);
    }
}
