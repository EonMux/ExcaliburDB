package io.excalibur.vector.quantization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * K-means clustering algorithm with K-means++ initialization.
 *
 * <p>This implementation provides K-means clustering optimized for Product Quantization
 * codebook generation. Key features:</p>
 *
 * <ul>
 *   <li><strong>K-means++ initialization:</strong> Selects initial centroids with probability
 *       proportional to squared distance from existing centroids, leading to better convergence</li>
 *   <li><strong>L2 distance metric:</strong> Uses Euclidean distance, which is optimal for PQ</li>
 *   <li><strong>Configurable convergence:</strong> Stops when centroid movement falls below threshold
 *       or max iterations reached</li>
 *   <li><strong>Numerical stability:</strong> Uses double precision for accumulation</li>
 * </ul>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><strong>Initialization (K-means++):</strong>
 *     <ul>
 *       <li>Choose first centroid uniformly at random</li>
 *       <li>For each subsequent centroid, choose with probability proportional to D(x)²,
 *           where D(x) is the distance to the nearest existing centroid</li>
 *     </ul>
 *   </li>
 *   <li><strong>Assignment:</strong> Assign each point to the nearest centroid</li>
 *   <li><strong>Update:</strong> Recompute centroids as the mean of assigned points</li>
 *   <li><strong>Convergence check:</strong> If total centroid movement &lt; threshold, stop</li>
 *   <li><strong>Repeat:</strong> Go to step 2 until max iterations or convergence</li>
 * </ol>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Centroid dimensions always match input dimension</li>
 *   <li>Number of centroids exactly equals k (or fewer if k > data points)</li>
 *   <li>All centroid values are within the range of input data</li>
 *   <li>Empty clusters are handled by keeping their previous centroid</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is NOT thread-safe.</strong> Create a new instance per thread
 * or synchronize externally. This is by design - clustering is typically a batch operation.</p>
 *
 * <h2>Performance</h2>
 * <p>Time complexity: O(n * k * d * i) where n=points, k=clusters, d=dimension, i=iterations</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class KMeansClusterer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KMeansClusterer.class);

    /**
     * Default maximum number of iterations.
     */
    public static final int DEFAULT_MAX_ITERATIONS = 100;

    /**
     * Default convergence threshold (sum of squared centroid movements).
     */
    public static final float DEFAULT_CONVERGENCE_THRESHOLD = 1e-4f;

    /**
     * Minimum number of points required to perform clustering.
     */
    private static final int MIN_POINTS_FOR_CLUSTERING = 2;

    private final int maxIterations;
    private final float convergenceThreshold;
    private final long seed;
    private final Random random;

    /**
     * Creates a new K-means clusterer with default settings.
     */
    public KMeansClusterer() {
        this(DEFAULT_MAX_ITERATIONS, DEFAULT_CONVERGENCE_THRESHOLD, 0);
    }

    /**
     * Creates a new K-means clusterer with specified parameters.
     *
     * @param maxIterations Maximum number of iterations (must be &gt;= 1)
     * @param convergenceThreshold Convergence threshold for centroid movement (must be &gt;= 0)
     * @param seed Random seed for reproducibility (0 means use ThreadLocalRandom)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public KMeansClusterer(int maxIterations, float convergenceThreshold, long seed) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1: " + maxIterations);
        }
        if (convergenceThreshold < 0) {
            throw new IllegalArgumentException("convergenceThreshold must be >= 0: " + convergenceThreshold);
        }

        this.maxIterations = maxIterations;
        this.convergenceThreshold = convergenceThreshold;
        this.seed = seed;
        this.random = seed != 0 ? new Random(seed) : null;

        LOGGER.debug("KMeansClusterer initialized: maxIterations={}, convergenceThreshold={}, seed={}",
            maxIterations, convergenceThreshold, seed);
    }

    /**
     * Performs K-means clustering on the given data.
     *
     * <p>This method implements the K-means++ algorithm for improved initial centroid
     * selection, followed by standard Lloyd's algorithm for refinement.</p>
     *
     * <h3>Edge Cases</h3>
     * <ul>
     *   <li>If data is empty, returns empty array</li>
     *   <li>If k &gt;= number of data points, returns the data points themselves</li>
     *   <li>If k == 1, returns the mean of all data points</li>
     * </ul>
     *
     * @param data The data points to cluster (each row is a point)
     * @param k The number of clusters (centroids) to generate
     * @return Array of k centroids, each with the same dimension as input data
     * @throws IllegalArgumentException if data is null or k is invalid
     */
    public float[][] cluster(float[][] data, int k) {
        Objects.requireNonNull(data, "Data cannot be null");
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1: " + k);
        }

        // Handle empty data
        if (data.length == 0) {
            LOGGER.warn("Empty data provided, returning empty centroids array");
            return new float[0][];
        }

        // Validate all data points have consistent dimension
        int dimension = data[0].length;
        if (dimension == 0) {
            throw new IllegalArgumentException("Data points must have non-zero dimension");
        }
        for (int i = 0; i < data.length; i++) {
            if (data[i] == null) {
                throw new IllegalArgumentException("Data point at index " + i + " is null");
            }
            if (data[i].length != dimension) {
                throw new IllegalArgumentException(
                    "Inconsistent dimensions: expected " + dimension + ", got " + data[i].length + " at index " + i);
            }
        }

        // Handle edge case: k >= number of data points
        if (k >= data.length) {
            LOGGER.debug("k ({}) >= data length ({}), returning data points as centroids", k, data.length);
            // Return copy of data, padded with duplicates if necessary
            float[][] centroids = new float[k][];
            for (int i = 0; i < k; i++) {
                centroids[i] = data[i % data.length].clone();
            }
            return centroids;
        }

        // Handle edge case: single cluster
        if (k == 1) {
            LOGGER.debug("k=1, returning mean of all data points");
            return new float[][] { computeMean(data, dimension) };
        }

        LOGGER.debug("Starting K-means clustering: {} points, {} clusters, {} dimensions",
            data.length, k, dimension);

        // Step 1: K-means++ initialization
        float[][] centroids = initializeCentroidsKMeansPlusPlus(data, k, dimension);

        // Step 2: Lloyd's algorithm iterations
        int[] assignments = new int[data.length];
        float totalMovement;

        for (int iter = 0; iter < maxIterations; iter++) {
            // Assignment step
            assignToCentroids(data, centroids, assignments);

            // Update step - compute new centroids and track movement
            float[][] newCentroids = new float[k][dimension];
            totalMovement = 0.0f;

            for (int c = 0; c < k; c++) {
                // Collect all points assigned to this cluster
                int count = 0;
                double[] sum = new double[dimension];

                for (int i = 0; i < data.length; i++) {
                    if (assignments[i] == c) {
                        count++;
                        for (int d = 0; d < dimension; d++) {
                            sum[d] += data[i][d];
                        }
                    }
                }

                if (count > 0) {
                    // Compute mean
                    for (int d = 0; d < dimension; d++) {
                        newCentroids[c][d] = (float) (sum[d] / count);
                    }
                } else {
                    // Empty cluster - keep previous centroid
                    newCentroids[c] = centroids[c].clone();
                }

                // Accumulate movement
                totalMovement += squaredDistance(centroids[c], newCentroids[c]);
            }

            // Check convergence
            if (totalMovement < convergenceThreshold) {
                LOGGER.debug("Converged after {} iterations (movement: {})", iter + 1, totalMovement);
                centroids = newCentroids;
                break;
            }

            centroids = newCentroids;

            if (iter == maxIterations - 1) {
                LOGGER.debug("Reached max iterations ({}) with movement: {}", maxIterations, totalMovement);
            }
        }

        return centroids;
    }

    /**
     * Initializes centroids using K-means++ algorithm.
     *
     * <p>The algorithm selects initial centroids with probability proportional to
     * the squared distance from the nearest existing centroid. This spreads out
     * the initial centroids and leads to better convergence.</p>
     *
     * @param data The data points
     * @param k Number of centroids to select
     * @param dimension Dimension of each point
     * @return Array of k initial centroids
     */
    private float[][] initializeCentroidsKMeansPlusPlus(float[][] data, int k, int dimension) {
        float[][] centroids = new float[k][dimension];
        double[] distances = new double[data.length];
        double totalDistance;

        // Choose first centroid uniformly at random
        int firstIdx = randomIndex(data.length);
        centroids[0] = data[firstIdx].clone();

        LOGGER.trace("K-means++: initial centroid at index {}", firstIdx);

        // Choose remaining centroids with probability proportional to D(x)^2
        for (int c = 1; c < k; c++) {
            totalDistance = 0.0;

            // Compute squared distance to nearest centroid for each point
            for (int i = 0; i < data.length; i++) {
                double minDist = Double.MAX_VALUE;
                for (int j = 0; j < c; j++) {
                    double dist = squaredDistance(data[i], centroids[j]);
                    minDist = Math.min(minDist, dist);
                }
                distances[i] = minDist;
                totalDistance += minDist;
            }

            // Handle edge case: every remaining point coincides with a chosen centroid.
            // The guard below is exactly the condition isDuplicate() tests, summed over
            // all points, so rejection-sampling for a non-duplicate point would spin
            // forever. Fill the remaining centroids with the points we have instead:
            // duplicate centroids are harmless (encode() takes the first minimum and
            // lookup() still sums valid sub-distances).
            if (totalDistance < 1e-10) {
                int distinctIdx = findNonDuplicateIndex(data, centroids, c);
                if (distinctIdx < 0) {
                    for (int remaining = c; remaining < k; remaining++) {
                        centroids[remaining] = centroids[0].clone();
                    }
                    LOGGER.debug("K-means++: all points coincide with existing centroids; " +
                        "filling centroids {}..{} with duplicates", c, k - 1);
                    break;
                }
                centroids[c] = data[distinctIdx].clone();
                LOGGER.trace("K-means++: centroid {} at index {} (degenerate spread)", c, distinctIdx);
                continue;
            }

            // Select next centroid with probability proportional to distance
            double threshold = randomDouble() * totalDistance;
            double cumulative = 0.0;
            int selectedIdx = data.length - 1; // fallback

            for (int i = 0; i < data.length; i++) {
                cumulative += distances[i];
                if (cumulative >= threshold) {
                    selectedIdx = i;
                    break;
                }
            }

            centroids[c] = data[selectedIdx].clone();
            LOGGER.trace("K-means++: centroid {} at index {} (distance contribution: {})",
                c, selectedIdx, distances[selectedIdx]);
        }

        return centroids;
    }

    /**
     * Assigns each data point to its nearest centroid.
     *
     * @param data The data points
     * @param centroids The current centroids
     * @param assignments Output array for cluster assignments
     */
    private void assignToCentroids(float[][] data, float[][] centroids, int[] assignments) {
        for (int i = 0; i < data.length; i++) {
            double minDist = Double.MAX_VALUE;
            int minIdx = 0;

            for (int c = 0; c < centroids.length; c++) {
                double dist = squaredDistance(data[i], centroids[c]);
                if (dist < minDist) {
                    minDist = dist;
                    minIdx = c;
                }
            }

            assignments[i] = minIdx;
        }
    }

    /**
     * Computes the squared Euclidean distance between two vectors.
     *
     * <p>Uses double precision for accumulation to maintain numerical stability.</p>
     *
     * @param v1 First vector
     * @param v2 Second vector
     * @return Squared Euclidean distance
     */
    private double squaredDistance(float[] v1, float[] v2) {
        double sum = 0.0;
        for (int i = 0; i < v1.length; i++) {
            double diff = (double) v1[i] - (double) v2[i];
            sum += diff * diff;
        }
        return sum;
    }

    /**
     * Computes the mean of a set of data points.
     *
     * @param data The data points
     * @param dimension Dimension of each point
     * @return The mean vector
     */
    private float[] computeMean(float[][] data, int dimension) {
        double[] sum = new double[dimension];
        for (float[] point : data) {
            for (int d = 0; d < dimension; d++) {
                sum[d] += point[d];
            }
        }

        float[] mean = new float[dimension];
        for (int d = 0; d < dimension; d++) {
            mean[d] = (float) (sum[d] / data.length);
        }
        return mean;
    }

    /**
     * Checks if a point is a duplicate of any existing centroids.
     */
    /**
     * Finds the first data point that is not already used as a centroid.
     *
     * @param data The data points
     * @param centroids The centroids chosen so far
     * @param count Number of valid entries in {@code centroids}
     * @return The index of the first non-duplicate point, or -1 if every point
     *         coincides with an existing centroid
     */
    private int findNonDuplicateIndex(float[][] data, float[][] centroids, int count) {
        for (int i = 0; i < data.length; i++) {
            if (!isDuplicate(data[i], centroids, count)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isDuplicate(float[] point, float[][] centroids, int count) {
        for (int i = 0; i < count; i++) {
            if (squaredDistance(point, centroids[i]) < 1e-10) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generates a random index in [0, bound).
     */
    private int randomIndex(int bound) {
        if (random != null) {
            return random.nextInt(bound);
        }
        return ThreadLocalRandom.current().nextInt(bound);
    }

    /**
     * Generates a random double in [0, 1).
     */
    private double randomDouble() {
        if (random != null) {
            return random.nextDouble();
        }
        return ThreadLocalRandom.current().nextDouble();
    }

    /**
     * Returns the maximum number of iterations.
     *
     * @return Maximum iterations
     */
    public int maxIterations() {
        return maxIterations;
    }

    /**
     * Returns the convergence threshold.
     *
     * @return Convergence threshold
     */
    public float convergenceThreshold() {
        return convergenceThreshold;
    }

    @Override
    public String toString() {
        return String.format("KMeansClusterer{maxIterations=%d, convergenceThreshold=%f}",
            maxIterations, convergenceThreshold);
    }
}
