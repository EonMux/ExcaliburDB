package com.eonmux.excalibur.vector.quantization;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a single Product Quantization codebook for one subspace.
 *
 * <p>A codebook contains a set of centroids (codewords) that define the quantization
 * for a subspace. Each sub-vector in that subspace is mapped to the nearest centroid,
 * and the centroid index becomes the PQ code for that subspace.</p>
 *
 * <h2>Structure</h2>
 * <pre>
 * Codebook for subspace s:
 * - centroid[0]: [v0, v1, ..., v_{d-1}]  // d = subvectorDim
 * - centroid[1]: [v0, v1, ..., v_{d-1}]
 * - ...
 * - centroid[k-1]: [v0, v1, ..., v_{d-1}]  // k = numCentroids
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>
 * PQCodebook codebook = new PQCodebook(centroids);
 * int code = codebook.findNearestCentroid(subvector);
 * float[] centroid = codebook.getCentroid(code);
 * </pre>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>All centroids have the same dimension</li>
 *   <li>Centroid indices are in [0, numCentroids-1]</li>
 *   <li>findNearestCentroid always returns a valid index</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is thread-safe.</strong> Once created, a codebook is immutable
 * and can be safely shared across threads.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class PQCodebook {

    /**
     * The centroids for this codebook: centroids[centroidIndex][dimension]
     */
    private final float[][] centroids;

    /**
     * The dimension of each centroid (subvector dimension).
     */
    private final int dimension;

    /**
     * The number of centroids in this codebook.
     */
    private final int numCentroids;

    /**
     * Creates a new codebook from pre-trained centroids.
     *
     * <p>The centroids array is deep-copied to ensure immutability.</p>
     *
     * @param centroids The centroids array [numCentroids][dimension]
     * @throws IllegalArgumentException if centroids array is invalid
     * @throws NullPointerException if centroids is null
     */
    public PQCodebook(float[][] centroids) {
        Objects.requireNonNull(centroids, "Centroids cannot be null");

        if (centroids.length == 0) {
            throw new IllegalArgumentException("Centroids cannot be empty");
        }

        this.numCentroids = centroids.length;
        this.dimension = centroids[0].length;

        if (dimension == 0) {
            throw new IllegalArgumentException("Centroid dimension cannot be zero");
        }

        // Deep copy and validate
        this.centroids = new float[numCentroids][dimension];
        for (int c = 0; c < numCentroids; c++) {
            if (centroids[c] == null) {
                throw new IllegalArgumentException("Centroid " + c + " is null");
            }
            if (centroids[c].length != dimension) {
                throw new IllegalArgumentException(
                    "Centroid " + c + " has dimension " + centroids[c].length +
                    ", expected " + dimension);
            }
            this.centroids[c] = centroids[c].clone();
        }
    }

    /**
     * Finds the index of the nearest centroid for a sub-vector.
     *
     * <p>Uses squared Euclidean distance to find the closest centroid.
     * This is the core quantization operation for encoding.</p>
     *
     * <h3>Algorithm</h3>
     * <p>Brute-force search with O(numCentroids × dimension) complexity.
     * For typical PQ settings (256 centroids, 4-16 dimensions), this is very fast.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Always returns a valid index in [0, numCentroids-1]</li>
     *   <li>Ties are broken by choosing the lower index</li>
     * </ul>
     *
     * @param subvector The sub-vector to quantize
     * @return Index of the nearest centroid
     * @throws IllegalArgumentException if subvector dimension doesn't match
     * @throws NullPointerException if subvector is null
     */
    public int findNearestCentroid(float[] subvector) {
        Objects.requireNonNull(subvector, "Subvector cannot be null");

        if (subvector.length != dimension) {
            throw new IllegalArgumentException(
                "Subvector dimension (" + subvector.length + ") must match " +
                "codebook dimension (" + dimension + ")");
        }

        double minDist = Double.MAX_VALUE;
        int minIdx = 0;

        for (int c = 0; c < numCentroids; c++) {
            double dist = 0.0;
            for (int d = 0; d < dimension; d++) {
                double diff = (double) subvector[d] - (double) centroids[c][d];
                dist += diff * diff;
            }

            if (dist < minDist) {
                minDist = dist;
                minIdx = c;
            }
        }

        return minIdx;
    }

    /**
     * Finds the k nearest centroids for a sub-vector.
     *
     * <p>Useful for multi-probe or candidate expansion strategies.</p>
     *
     * @param subvector The sub-vector to quantize
     * @param k Number of nearest centroids to return
     * @return Array of centroid indices sorted by distance (closest first)
     * @throws IllegalArgumentException if k is invalid
     */
    public int[] findKNearestCentroids(float[] subvector, int k) {
        Objects.requireNonNull(subvector, "Subvector cannot be null");

        if (k <= 0 || k > numCentroids) {
            throw new IllegalArgumentException(
                "k must be in [1, " + numCentroids + "]: " + k);
        }
        if (subvector.length != dimension) {
            throw new IllegalArgumentException(
                "Subvector dimension (" + subvector.length + ") must match " +
                "codebook dimension (" + dimension + ")");
        }

        // Compute all distances
        double[] distances = new double[numCentroids];
        for (int c = 0; c < numCentroids; c++) {
            double dist = 0.0;
            for (int d = 0; d < dimension; d++) {
                double diff = (double) subvector[d] - (double) centroids[c][d];
                dist += diff * diff;
            }
            distances[c] = dist;
        }

        // Find k smallest using partial sort
        int[] indices = new int[k];
        double[] kDistances = new double[k];
        Arrays.fill(kDistances, Double.MAX_VALUE);

        for (int c = 0; c < numCentroids; c++) {
            double dist = distances[c];

            // Check if this distance belongs in top-k
            if (dist < kDistances[k - 1]) {
                // Find insertion point
                int insertPos = k - 1;
                while (insertPos > 0 && dist < kDistances[insertPos - 1]) {
                    kDistances[insertPos] = kDistances[insertPos - 1];
                    indices[insertPos] = indices[insertPos - 1];
                    insertPos--;
                }
                kDistances[insertPos] = dist;
                indices[insertPos] = c;
            }
        }

        return indices;
    }

    /**
     * Returns the centroid at the specified index.
     *
     * <p><strong>Note:</strong> Returns a copy of the centroid array to maintain
     * immutability. For performance-critical code, use {@link #getCentroidRef(int)}
     * but do not modify the returned array.</p>
     *
     * @param index The centroid index (0 to numCentroids-1)
     * @return Copy of the centroid vector
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public float[] getCentroid(int index) {
        if (index < 0 || index >= numCentroids) {
            throw new IndexOutOfBoundsException(
                "Centroid index " + index + " out of range [0, " + (numCentroids - 1) + "]");
        }
        return centroids[index].clone();
    }

    /**
     * Returns a reference to the centroid at the specified index.
     *
     * <p><strong>Warning:</strong> Do not modify the returned array!
     * This method exists for performance-critical paths where copying
     * would be prohibitive.</p>
     *
     * @param index The centroid index
     * @return Reference to the centroid vector (do not modify!)
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public float[] getCentroidRef(int index) {
        if (index < 0 || index >= numCentroids) {
            throw new IndexOutOfBoundsException(
                "Centroid index " + index + " out of range [0, " + (numCentroids - 1) + "]");
        }
        return centroids[index];
    }

    /**
     * Computes the squared Euclidean distance from a sub-vector to a specific centroid.
     *
     * @param subvector The sub-vector
     * @param centroidIndex The centroid index
     * @return Squared Euclidean distance
     * @throws IllegalArgumentException if dimensions don't match
     * @throws IndexOutOfBoundsException if centroid index is invalid
     */
    public float distanceToCentroid(float[] subvector, int centroidIndex) {
        Objects.requireNonNull(subvector, "Subvector cannot be null");

        if (centroidIndex < 0 || centroidIndex >= numCentroids) {
            throw new IndexOutOfBoundsException(
                "Centroid index " + centroidIndex + " out of range");
        }
        if (subvector.length != dimension) {
            throw new IllegalArgumentException(
                "Subvector dimension (" + subvector.length + ") must match " +
                "codebook dimension (" + dimension + ")");
        }

        double dist = 0.0;
        for (int d = 0; d < dimension; d++) {
            double diff = (double) subvector[d] - (double) centroids[centroidIndex][d];
            dist += diff * diff;
        }

        return (float) dist;
    }

    /**
     * Returns the dimension of each centroid (subvector dimension).
     */
    public int dimension() {
        return dimension;
    }

    /**
     * Returns the number of centroids in this codebook.
     */
    public int numCentroids() {
        return numCentroids;
    }

    /**
     * Returns the memory size of this codebook in bytes.
     */
    public long memorySize() {
        return (long) numCentroids * dimension * Float.BYTES;
    }

    /**
     * Returns a deep copy of all centroids.
     *
     * @return Copy of centroids array
     */
    public float[][] getCentroids() {
        float[][] copy = new float[numCentroids][dimension];
        for (int c = 0; c < numCentroids; c++) {
            copy[c] = centroids[c].clone();
        }
        return copy;
    }

    @Override
    public String toString() {
        return String.format("PQCodebook{numCentroids=%d, dimension=%d, memory=%d bytes}",
            numCentroids, dimension, memorySize());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PQCodebook that = (PQCodebook) o;
        return dimension == that.dimension &&
               numCentroids == that.numCentroids &&
               Arrays.deepEquals(centroids, that.centroids);
    }

    @Override
    public int hashCode() {
        int result = dimension;
        result = 31 * result + numCentroids;
        result = 31 * result + Arrays.deepHashCode(centroids);
        return result;
    }
}
