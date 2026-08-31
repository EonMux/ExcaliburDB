package io.excalibur.vector.quantization;

import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.L2Distance;

import java.util.Objects;

/**
 * Static utility methods for asymmetric Product Quantization distance computation.
 *
 * <p>Asymmetric distance computation (ADC) is the key technique that makes PQ efficient
 * for nearest neighbor search. It computes approximate distances between a full-precision
 * query vector and PQ-encoded database vectors using precomputed distance tables.</p>
 *
 * <h2>How Asymmetric Distance Works</h2>
 * <ol>
 *   <li><strong>Precomputation:</strong> Build a distance table by computing distances
 *       from each query sub-vector to all centroids in the corresponding codebook</li>
 *   <li><strong>Lookup:</strong> For each database vector, look up the precomputed
 *       distances using its PQ codes and sum them</li>
 * </ol>
 *
 * <h2>Complexity Analysis</h2>
 * <ul>
 *   <li>Table construction: O(numSubspaces × numCentroids × subvectorDim)</li>
 *   <li>Per-vector distance: O(numSubspaces) - just lookups and addition</li>
 *   <li>Memory per table: numSubspaces × numCentroids × 4 bytes</li>
 * </ul>
 *
 * <h2>Accuracy vs Efficiency</h2>
 * <p>Asymmetric distance provides better accuracy than symmetric distance (where both
 * query and database vectors are quantized) because the query remains at full precision.
 * The approximation error comes only from the database vectors' quantization.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>All methods in this class are thread-safe. Distance tables are immutable once created.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class AsymmetricDistance {

    /**
     * Default distance function for building tables (L2 squared distance).
     */
    private static final L2Distance DEFAULT_DISTANCE = L2Distance.getInstance();

    // Private constructor - utility class
    private AsymmetricDistance() {}

    /**
     * Computes asymmetric distance between a query and a PQ-encoded database vector.
     *
     * <p>This is the fast O(numSubspaces) lookup operation that makes PQ efficient.
     * It sums the precomputed distances for each subspace based on the PQ codes.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Result is always non-negative (sum of squared distances)</li>
     *   <li>Result approximates the true squared L2 distance</li>
     *   <li>Higher quantization error → larger deviation from true distance</li>
     * </ul>
     *
     * @param distanceTable Precomputed distance table [numSubspaces × numCentroids]
     * @param pqCodes PQ codes for the database vector (one byte per subspace)
     * @return Approximate squared L2 distance
     * @throws IllegalArgumentException if inputs are invalid or have mismatched dimensions
     * @throws NullPointerException if any argument is null
     */
    public static float compute(float[][] distanceTable, byte[] pqCodes) {
        Objects.requireNonNull(distanceTable, "Distance table cannot be null");
        Objects.requireNonNull(pqCodes, "PQ codes cannot be null");

        if (distanceTable.length == 0) {
            throw new IllegalArgumentException("Distance table cannot be empty");
        }
        if (pqCodes.length != distanceTable.length) {
            throw new IllegalArgumentException(
                "PQ codes length (" + pqCodes.length + ") must match number of subspaces (" +
                distanceTable.length + ")");
        }

        float totalDistance = 0.0f;

        for (int s = 0; s < pqCodes.length; s++) {
            // Convert unsigned byte to centroid index
            int centroidIdx = pqCodes[s] & 0xFF;

            if (centroidIdx >= distanceTable[s].length) {
                throw new IllegalArgumentException(
                    "Invalid centroid index " + centroidIdx + " in subspace " + s +
                    " (max: " + (distanceTable[s].length - 1) + ")");
            }

            totalDistance += distanceTable[s][centroidIdx];
        }

        return totalDistance;
    }

    /**
     * Builds a precomputed asymmetric distance table for a query vector.
     *
     * <p>This method precomputes distances from the query's sub-vectors to all
     * centroids in each codebook. The resulting table enables O(numSubspaces)
     * distance lookups against PQ-encoded database vectors.</p>
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Split query into sub-vectors</li>
     *   <li>For each subspace s:
     *     <ul>
     *       <li>For each centroid c in codebook[s]:
     *         <ul>
     *           <li>Compute the metric's per-subspace term from query sub-vector to centroid</li>
     *           <li>Store in table[s][c]</li>
     *         </ul>
     *       </li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <h3>Which metrics work, and why the table is always smaller-is-closer</h3>
     * <p>The technique needs a metric whose value is the sum of independent per-subspace terms,
     * because {@link #compute} adds one table entry per subspace. Squared L2 decomposes exactly.
     * So does the inner product. Cosine does not in general — its normalisation is global — but for
     * unit-norm vectors, which is what embedding models produce, cosine equals the inner product
     * and decomposes with it.</p>
     *
     * <p>Entries for the larger-is-closer metrics are <strong>negated</strong>, so an accumulated
     * table value is always a dissimilarity where smaller means nearer. That matches
     * {@link DistanceFunction.Ordering#toDistance}, which is what puts a PQ result on the same
     * scale as an exact one — previously this always computed squared L2 whatever metric the graph
     * was built with, so a cosine graph's PQ results were normalised against a range they were
     * never on.</p>
     *
     * @param query Full-precision query vector
     * @param codebooks PQ codebooks [numSubspaces × numCentroids × subvectorDim]
     * @param distFn Metric the table is built for; null means L2. Must be decomposable across
     *               subspaces — see the note above on which metrics qualify.
     * @return Precomputed distance table [numSubspaces × numCentroids]
     * @throws IllegalArgumentException if dimensions are invalid
     * @throws NullPointerException if query or codebooks is null
     */
    public static float[][] buildTable(float[] query, float[][][] codebooks, DistanceFunction distFn) {
        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(codebooks, "Codebooks cannot be null");

        if (codebooks.length == 0) {
            throw new IllegalArgumentException("Codebooks cannot be empty");
        }

        int numSubspaces = codebooks.length;
        int numCentroids = codebooks[0].length;
        int subvectorDim = codebooks[0][0].length;

        // Validate query dimension
        if (query.length != numSubspaces * subvectorDim) {
            throw new IllegalArgumentException(
                "Query dimension (" + query.length + ") must match " +
                "numSubspaces × subvectorDim (" + numSubspaces + " × " + subvectorDim + " = " +
                (numSubspaces * subvectorDim) + ")");
        }

        // Validate codebook structure
        for (int s = 0; s < numSubspaces; s++) {
            if (codebooks[s] == null) {
                throw new IllegalArgumentException("Codebook " + s + " is null");
            }
            if (codebooks[s].length != numCentroids) {
                throw new IllegalArgumentException(
                    "Codebook " + s + " has " + codebooks[s].length +
                    " centroids, expected " + numCentroids);
            }
            for (int c = 0; c < numCentroids; c++) {
                if (codebooks[s][c] == null || codebooks[s][c].length != subvectorDim) {
                    throw new IllegalArgumentException(
                        "Invalid centroid " + c + " in codebook " + s);
                }
            }
        }

        float[][] distanceTable = new float[numSubspaces][numCentroids];

        boolean largerIsCloser = distFn != null
            && distFn.ordering() == DistanceFunction.Ordering.LARGER_IS_CLOSER;

        for (int s = 0; s < numSubspaces; s++) {
            int startOffset = s * subvectorDim;

            for (int c = 0; c < numCentroids; c++) {
                double term = 0.0;
                if (largerIsCloser) {
                    // Inner product, negated so the accumulated table stays smaller-is-closer.
                    for (int d = 0; d < subvectorDim; d++) {
                        term += (double) query[startOffset + d] * (double) codebooks[s][c][d];
                    }
                    term = -term;
                } else {
                    for (int d = 0; d < subvectorDim; d++) {
                        double diff = (double) query[startOffset + d] - (double) codebooks[s][c][d];
                        term += diff * diff;
                    }
                }
                distanceTable[s][c] = (float) term;
            }
        }

        return distanceTable;
    }

    /**
     * Builds a precomputed asymmetric distance table using L2 squared distance.
     *
     * <p>Convenience overload that uses the default L2 distance function.</p>
     *
     * @param query Full-precision query vector
     * @param codebooks PQ codebooks [numSubspaces × numCentroids × subvectorDim]
     * @return Precomputed distance table [numSubspaces × numCentroids]
     */
    public static float[][] buildTable(float[] query, float[][][] codebooks) {
        return buildTable(query, codebooks, DEFAULT_DISTANCE);
    }

    /**
     * Computes asymmetric distances for multiple database vectors in batch.
     *
     * <p>More efficient than individual lookups due to better cache locality
     * and reduced bounds checking overhead.</p>
     *
     * @param distanceTable Precomputed distance table
     * @param pqCodesBatch Array of PQ codes for multiple database vectors
     * @return Approximate dissimilarities on the table's metric, smaller being nearer
     * @throws IllegalArgumentException if inputs are invalid
     */
    public static float[] computeBatch(float[][] distanceTable, byte[][] pqCodesBatch) {
        Objects.requireNonNull(distanceTable, "Distance table cannot be null");
        Objects.requireNonNull(pqCodesBatch, "PQ codes batch cannot be null");

        float[] distances = new float[pqCodesBatch.length];
        for (int i = 0; i < pqCodesBatch.length; i++) {
            distances[i] = compute(distanceTable, pqCodesBatch[i]);
        }
        return distances;
    }

    /**
     * Estimates the quantization error for a vector given its PQ codes.
     *
     * <p>The quantization error is the squared L2 distance between the original
     * vector and its reconstructed (decoded) approximation. Lower values indicate
     * better codebook quality.</p>
     *
     * @param vector Original full-precision vector
     * @param codebooks PQ codebooks
     * @param pqCodes PQ codes for the vector
     * @return Quantization error (squared L2 distance)
     */
    public static float quantizationError(float[] vector, float[][][] codebooks, byte[] pqCodes) {
        Objects.requireNonNull(vector, "Vector cannot be null");
        Objects.requireNonNull(codebooks, "Codebooks cannot be null");
        Objects.requireNonNull(pqCodes, "PQ codes cannot be null");

        int numSubspaces = codebooks.length;
        int subvectorDim = codebooks[0][0].length;

        if (pqCodes.length != numSubspaces) {
            throw new IllegalArgumentException(
                "PQ codes length must match number of subspaces");
        }
        if (vector.length != numSubspaces * subvectorDim) {
            throw new IllegalArgumentException(
                "Vector dimension must match codebook dimensions");
        }

        double totalError = 0.0;

        for (int s = 0; s < numSubspaces; s++) {
            int centroidIdx = pqCodes[s] & 0xFF;
            int startOffset = s * subvectorDim;

            for (int d = 0; d < subvectorDim; d++) {
                double diff = (double) vector[startOffset + d] - (double) codebooks[s][centroidIdx][d];
                totalError += diff * diff;
            }
        }

        return (float) totalError;
    }

    /**
     * Returns the memory size of a distance table in bytes.
     *
     * @param numSubspaces Number of subspaces
     * @param numCentroids Number of centroids per subspace
     * @return Memory size in bytes
     */
    public static long tableMemorySize(int numSubspaces, int numCentroids) {
        if (numSubspaces < 1 || numCentroids < 1) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        return (long) numSubspaces * numCentroids * Float.BYTES;
    }
}
