package io.excalibur.vector.quantization;

import io.excalibur.vector.distance.DistanceFunction;

import java.util.Arrays;
import java.util.Objects;

/**
 * Precomputed distance lookup table for asymmetric Product Quantization distance computation.
 *
 * <p>This class provides O(numSubspaces) distance computation between a query vector
 * and any PQ-encoded database vector by precomputing all query-to-centroid distances.</p>
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li><strong>Precomputation:</strong> For each subspace, compute distances from the
 *       query sub-vector to ALL centroids in that codebook</li>
 *   <li><strong>Lookup:</strong> For a PQ-encoded database vector, look up the precomputed
 *       distance for each code and sum them</li>
 * </ol>
 *
 * <h2>Performance</h2>
 * <ul>
 *   <li>Precomputation: O(numSubspaces * numCentroids * subvectorDim)</li>
 *   <li>Per-vector distance: O(numSubspaces) - just lookups and addition</li>
 *   <li>Memory: numSubspaces * numCentroids floats</li>
 * </ul>
 *
 * <h2>Use Case</h2>
 * <p>This is ideal when computing distances from ONE query to MANY database vectors:</p>
 * <pre>
 * PQDistanceTable table = pq.createDistanceTable(query);
 * for (byte[] codes : databaseVectors) {
 *     float dist = table.lookup(codes);
 * }
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is thread-safe.</strong> Once created, the distance table is
 * immutable and can be safely shared across threads.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class PQDistanceTable {

    /**
     * Precomputed per-subspace terms: {@code distances[subspace][centroid]}.
     *
     * <p>Always smaller-is-closer, whatever the metric. {@link AsymmetricDistance#buildTable}
     * negates the larger-is-closer metrics so that a summed lookup is a dissimilarity on the
     * same scale an exact distance would be on.</p>
     */
    private final float[][] distances;

    /** The metric this table was built for. */
    private final DistanceFunction distanceFunction;

    /**
     * Reference to the ProductQuantizer that created this table.
     */
    private final ProductQuantizer pq;

    /**
     * The query vector this table was created for (for reference/debugging).
     */
    private final float[] query;

    /**
     * Creates a new distance table for the given query.
     *
     * <p>This constructor precomputes all query-to-centroid distances.
     * It is called by {@link ProductQuantizer#createDistanceTable(float[])}.</p>
     *
     * @param query The query vector
     * @param pq The product quantizer
     * @throws IllegalArgumentException if query dimension doesn't match
     */
    PQDistanceTable(float[] query, ProductQuantizer pq) {
        this(query, pq, null);
    }

    /**
     * Creates a table for a specific metric.
     *
     * <p>The metric is not optional in practice. A table built for squared L2 and consulted by
     * a cosine graph ranks by a different geometry than the graph was built with and produces
     * values on a range the caller's normalisation never sees - which is silent: the search
     * returns results, just the wrong ones.
     *
     * @param query  The query vector
     * @param pq     The product quantizer
     * @param distFn The metric; null means squared L2
     * @throws IllegalArgumentException if query dimension does not match
     */
    PQDistanceTable(float[] query, ProductQuantizer pq, DistanceFunction distFn) {
        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(pq, "ProductQuantizer cannot be null");

        if (!pq.isTrained()) {
            throw new IllegalStateException("ProductQuantizer must be trained");
        }
        if (query.length != pq.originalDim()) {
            throw new IllegalArgumentException(
                "Query dimension (" + query.length + ") must match ProductQuantizer dimension (" + pq.originalDim() + ")");
        }

        this.pq = pq;
        this.query = query.clone(); // Defensive copy
        this.distanceFunction = distFn;
        this.distances = AsymmetricDistance.buildTable(this.query, pq.getCodebooks(), distFn);
    }

    /** The metric this table was built for, or null for squared L2. */
    public DistanceFunction distanceFunction() {
        return distanceFunction;
    }

    /**
     * Computes the approximate distance to a PQ-encoded vector.
     *
     * <p>This is the fast path - just table lookups and addition.
     * Time complexity: O(numSubspaces)</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Smaller is nearer, whatever the metric</li>
     *   <li>Result approximates the true distance under {@link #distanceFunction()}; for a
     *       larger-is-closer metric it is the negated similarity, which is negative</li>
     * </ul>
     *
     * @param codes PQ codes for the database vector (length must match numSubspaces)
     * @return Approximate dissimilarity, smaller being nearer
     * @throws IllegalArgumentException if codes are invalid
     */
    public float lookup(byte[] codes) {
        Objects.requireNonNull(codes, "Codes cannot be null");

        if (codes.length != pq.numSubspaces()) {
            throw new IllegalArgumentException(
                "Codes length (" + codes.length + ") must match numSubspaces (" + pq.numSubspaces() + ")");
        }

        float totalDistance = 0.0f;

        for (int s = 0; s < codes.length; s++) {
            // Convert unsigned byte to centroid index
            int centroidIdx = codes[s] & 0xFF;
            if (centroidIdx >= pq.numCentroids()) {
                throw new IllegalArgumentException(
                    "Invalid centroid index " + centroidIdx + " in subspace " + s +
                    " (max: " + (pq.numCentroids() - 1) + ")");
            }
            totalDistance += distances[s][centroidIdx];
        }

        return totalDistance;
    }

    /**
     * Computes distances to multiple PQ-encoded vectors in batch.
     *
     * <p>More efficient than individual lookups due to better cache locality.</p>
     *
     * @param codesBatch Array of PQ codes
     * @return Array of distances
     */
    public float[] lookupBatch(byte[][] codesBatch) {
        Objects.requireNonNull(codesBatch, "Codes batch cannot be null");

        float[] results = new float[codesBatch.length];
        for (int i = 0; i < codesBatch.length; i++) {
            results[i] = lookup(codesBatch[i]);
        }
        return results;
    }

    /**
     * Returns the precomputed distance for a specific subspace and centroid.
     *
     * <p>Useful for debugging or custom distance computations.</p>
     *
     * @param subspace Subspace index (0 to numSubspaces-1)
     * @param centroid Centroid index (0 to numCentroids-1)
     * @return Precomputed squared distance
     * @throws IndexOutOfBoundsException if indices are out of range
     */
    public float getDistance(int subspace, int centroid) {
        if (subspace < 0 || subspace >= distances.length) {
            throw new IndexOutOfBoundsException("Invalid subspace: " + subspace);
        }
        if (centroid < 0 || centroid >= distances[subspace].length) {
            throw new IndexOutOfBoundsException("Invalid centroid: " + centroid);
        }
        return distances[subspace][centroid];
    }

    /**
     * Returns the number of subspaces.
     */
    public int numSubspaces() {
        return pq.numSubspaces();
    }

    /**
     * Returns the number of centroids per subspace.
     */
    public int numCentroids() {
        return pq.numCentroids();
    }

    /**
     * Returns the ProductQuantizer that created this table.
     */
    public ProductQuantizer getQuantizer() {
        return pq;
    }

    /**
     * Returns a copy of the query vector.
     */
    public float[] getQuery() {
        return query.clone();
    }

    /**
     * Returns the memory size of this distance table in bytes.
     */
    public int memorySize() {
        return distances.length * distances[0].length * Float.BYTES;
    }

    @Override
    public String toString() {
        return String.format("PQDistanceTable{subspaces=%d, centroids=%d, memory=%d bytes}",
            numSubspaces(), numCentroids(), memorySize());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PQDistanceTable that = (PQDistanceTable) o;
        return Arrays.deepEquals(distances, that.distances) &&
               Arrays.equals(query, that.query);
    }

    @Override
    public int hashCode() {
        int result = Arrays.deepHashCode(distances);
        result = 31 * result + Arrays.hashCode(query);
        return result;
    }
}
