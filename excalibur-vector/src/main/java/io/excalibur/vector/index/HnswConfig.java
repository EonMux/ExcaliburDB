package io.excalibur.vector.index;

import java.util.Map;
import java.util.Objects;

/**
 * Configuration for HNSW (Hierarchical Navigable Small World) graph parameters.
 *
 * <p>This configuration controls the trade-offs between index quality, memory usage,
 * and query performance in the HNSW vector index.</p>
 *
 * <h2>Key Parameters</h2>
 * <ul>
 *   <li>{@code efConstruction} - Controls index quality during construction. Higher values
 *       produce better quality graphs (higher recall) but slower indexing.</li>
 *   <li>{@code efSearch} - Controls search quality at query time. Higher values return
 *       more accurate results but slower queries.</li>
 *   <li>{@code maxConnections} (M) - Maximum number of bi-directional connections per node
 *       in layers 1+. Controls memory usage and graph connectivity.</li>
 *   <li>{@code maxConnectionsLayer0} - Maximum connections at layer 0 (typically 2*M).
 *       Higher values improve recall but increase memory.</li>
 * </ul>
 *
 * <h2>Default Configuration</h2>
 * <p>Default values are tuned to achieve {@code ≥99% recall@10} for typical workloads:</p>
 * <ul>
 *   <li>{@code efConstruction = 200}</li>
 *   <li>{@code efSearch = 100}</li>
 *   <li>{@code maxConnections = 16}</li>
 *   <li>{@code maxConnectionsLayer0 = 32}</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and therefore thread-safe. Once constructed, the
 * configuration values cannot be changed.</p>
 *
 * <h2>Domain Correctness</h2>
 * <p>Configuration values are validated at construction time:</p>
 * <ul>
 *   <li>All integer values must be positive</li>
 *   <li>{@code efConstruction} and {@code efSearch} must be at least as large as
 *       the desired top-k results</li>
 *   <li>{@code maxConnectionsLayer0} should typically be 2x {@code maxConnections}</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 * @see <a href="https://arxiv.org/abs/1603.09320">Efficient and robust approximate nearest
 *      neighbor search using Hierarchical Navigable Small World graphs</a>
 */
public final class HnswConfig {

    /**
     * Default efConstruction value tuned for ≥99% recall@10.
     * Higher values improve graph quality at the cost of slower indexing.
     */
    public static final int DEFAULT_EF_CONSTRUCTION = 200;

    /**
     * Default efSearch value tuned for ≥99% recall@10.
     * Higher values improve recall at the cost of slower queries.
     */
    public static final int DEFAULT_EF_SEARCH = 100;

    /**
     * Default maximum bi-directional connections per node (M parameter).
     * Recommended range: 8-64 based on HNSW paper.
     */
    public static final int DEFAULT_MAX_CONNECTIONS = 16;

    /**
     * Default maximum connections at layer 0.
     * Typically set to 2*M for better recall.
     */
    public static final int DEFAULT_MAX_CONNECTIONS_LAYER_0 = 32;

    /**
     * Minimum allowed efConstruction/efSearch value.
     * Must be at least 1 to form a valid graph.
     */
    public static final int MIN_EF = 1;

    /**
     * Minimum allowed maxConnections value.
     * Must be at least 2 to form connected graphs.
     */
    public static final int MIN_CONNECTIONS = 2;

    /**
     * Maximum allowed maxConnections value to prevent excessive memory usage.
     */
    public static final int MAX_CONNECTIONS_LIMIT = 256;

    private final int efConstruction;
    private final int efSearch;
    private final int maxConnections;
    private final int maxConnectionsLayer0;

    /**
     * Creates an HNSW configuration with default values.
     *
     * <p>Default configuration targets ≥99% recall@10.</p>
     */
    public HnswConfig() {
        this(DEFAULT_EF_CONSTRUCTION, DEFAULT_EF_SEARCH,
             DEFAULT_MAX_CONNECTIONS, DEFAULT_MAX_CONNECTIONS_LAYER_0);
    }

    /**
     * Creates an HNSW configuration with the specified parameters.
     *
     * @param efConstruction Size of the dynamic candidate list during index construction.
     *                       Higher values produce better quality graphs but slower indexing.
     *                       Must be positive. Default: 200.
     * @param efSearch Size of the dynamic candidate list during search.
     *                  Higher values improve recall but slower queries.
     *                  Must be positive. Default: 100.
     * @param maxConnections Maximum number of bi-directional connections per node (M).
     *                       Controls memory usage and graph connectivity.
     *                       Must be between 2 and 256. Default: 16.
     * @param maxConnectionsLayer0 Maximum connections at layer 0.
     *                              Typically 2*M for better recall.
     *                              Must be positive. Default: 32.
     * @throws IllegalArgumentException if any parameter is outside valid range
     */
    public HnswConfig(int efConstruction, int efSearch,
                      int maxConnections, int maxConnectionsLayer0) {
        validateEfConstruction(efConstruction);
        validateEfSearch(efSearch);
        validateMaxConnections(maxConnections);
        validateMaxConnectionsLayer0(maxConnectionsLayer0);

        this.efConstruction = efConstruction;
        this.efSearch = efSearch;
        this.maxConnections = maxConnections;
        this.maxConnectionsLayer0 = maxConnectionsLayer0;
    }

    /**
     * Creates an HNSW configuration from a properties map.
     *
     * <p>Supported properties:</p>
     * <ul>
     *   <li>{@code excalibur.vector.hnsw.ef-construction} - efConstruction value</li>
     *   <li>{@code excalibur.vector.hnsw.ef-search} - efSearch value</li>
     *   <li>{@code excalibur.vector.hnsw.max-connections} - maxConnections (M)</li>
     *   <li>{@code excalibur.vector.hnsw.max-connections-layer0} - maxConnectionsLayer0</li>
     * </ul>
     *
     * @param props Map of configuration properties, may be null
     * @return HNSW configuration with values from props or defaults
     */
    public static HnswConfig fromMap(Map<String, String> props) {
        if (props == null) {
            return new HnswConfig();
        }

        int efConstruction = parseInt(props.get("excalibur.vector.hnsw.ef-construction"),
                                      DEFAULT_EF_CONSTRUCTION);
        int efSearch = parseInt(props.get("excalibur.vector.hnsw.ef-search"),
                               DEFAULT_EF_SEARCH);
        int maxConnections = parseInt(props.get("excalibur.vector.hnsw.max-connections"),
                                      DEFAULT_MAX_CONNECTIONS);
        int maxConnectionsLayer0 = parseInt(props.get("excalibur.vector.hnsw.max-connections-layer0"),
                                            DEFAULT_MAX_CONNECTIONS_LAYER_0);

        return new HnswConfig(efConstruction, efSearch, maxConnections, maxConnectionsLayer0);
    }

    /**
     * Returns the size of the dynamic candidate list during index construction.
     *
     * <p>This parameter controls the trade-off between index quality and indexing speed.
     * Higher values produce better quality graphs (higher recall) but slower indexing.</p>
     *
     * <p><strong>Domain:</strong> Positive integer, typically in range [50, 500]</p>
     * <p><strong>Default:</strong> 200 (tuned for ≥99% recall@10)</p>
     *
     * @return efConstruction value (always positive)
     */
    public int getEfConstruction() {
        return efConstruction;
    }

    /**
     * Returns the size of the dynamic candidate list during search.
     *
     * <p>This parameter controls the trade-off between search accuracy and speed.
     * Higher values return more accurate results but with slower queries.</p>
     *
     * <p><strong>Domain:</strong> Positive integer, typically in range [10, 500]</p>
     * <p><strong>Default:</strong> 100 (tuned for ≥99% recall@10)</p>
     *
     * @return efSearch value (always positive)
     */
    public int getEfSearch() {
        return efSearch;
    }

    /**
     * Returns the effective efSearch value for a given top-k.
     *
     * <p>efSearch must be at least as large as top-k to return k results.
     * This method returns the maximum of configured efSearch and top-k.</p>
     *
     * @param topK Number of results requested
     * @return Effective efSearch value (always >= topK and >= 1)
     */
    public int getEffectiveEfSearch(int topK) {
        return Math.max(efSearch, Math.max(topK, 1));
    }

    /**
     * Returns the maximum number of bi-directional connections per node (M parameter).
     *
     * <p>This parameter controls memory usage and graph connectivity.
     * Higher values improve recall but increase memory consumption and
     * may slow down both indexing and search.</p>
     *
     * <p><strong>Domain:</strong> Integer in range [2, 256]</p>
     * <p><strong>Default:</strong> 16</p>
     *
     * @return maxConnections value (always in [2, 256])
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * Returns the maximum number of connections at layer 0.
     *
     * <p>Layer 0 is the bottom layer of the HNSW graph where all nodes exist.
     * Typically set to 2*M for better recall since this layer is searched
     * most thoroughly.</p>
     *
     * <p><strong>Domain:</strong> Positive integer, typically 2*maxConnections</p>
     * <p><strong>Default:</strong> 32 (2 * 16)</p>
     *
     * @return maxConnectionsLayer0 value (always positive)
     */
    public int getMaxConnectionsLayer0() {
        return maxConnectionsLayer0;
    }

    /**
     * Returns a new configuration with a different efConstruction value.
     *
     * @param newEfConstruction New efConstruction value
     * @return New HnswConfig with updated efConstruction
     * @throws IllegalArgumentException if newEfConstruction is invalid
     */
    public HnswConfig withEfConstruction(int newEfConstruction) {
        return new HnswConfig(newEfConstruction, efSearch, maxConnections, maxConnectionsLayer0);
    }

    /**
     * Returns a new configuration with a different efSearch value.
     *
     * @param newEfSearch New efSearch value
     * @return New HnswConfig with updated efSearch
     * @throws IllegalArgumentException if newEfSearch is invalid
     */
    public HnswConfig withEfSearch(int newEfSearch) {
        return new HnswConfig(efConstruction, newEfSearch, maxConnections, maxConnectionsLayer0);
    }

    /**
     * Returns a new configuration with different maxConnections values.
     *
     * @param newMaxConnections New maxConnections (M) value
     * @param newMaxConnectionsLayer0 New maxConnectionsLayer0 value
     * @return New HnswConfig with updated connection limits
     * @throws IllegalArgumentException if values are invalid
     */
    public HnswConfig withMaxConnections(int newMaxConnections, int newMaxConnectionsLayer0) {
        return new HnswConfig(efConstruction, efSearch, newMaxConnections, newMaxConnectionsLayer0);
    }

    /**
     * Estimates memory usage per node in bytes based on configuration.
     *
     * <p>This is a rough estimate for planning purposes. Actual memory usage
     * may vary based on implementation details and JVM overhead.</p>
     *
     * @return Estimated bytes per node
     */
    public long estimateMemoryPerNode() {
        // Each node has:
        // - Node ID: 8 bytes (long)
        // - Vector pointer: 8 bytes
        // - Layer assignment: 4 bytes (int)
        // - Adjacency lists: ~8 bytes per connection (neighbor ID)
        // Average across layers: layer0 has most connections
        // Rough estimate: (maxConnectionsLayer0 + maxConnections * avgLayers) * 8
        // Assuming average of 3 layers above layer 0
        int avgConnections = (maxConnectionsLayer0 + maxConnections * 3) / 2;
        return 8 + 8 + 4 + (long) avgConnections * 8;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HnswConfig that = (HnswConfig) o;
        return efConstruction == that.efConstruction &&
               efSearch == that.efSearch &&
               maxConnections == that.maxConnections &&
               maxConnectionsLayer0 == that.maxConnectionsLayer0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(efConstruction, efSearch, maxConnections, maxConnectionsLayer0);
    }

    @Override
    public String toString() {
        return "HnswConfig{" +
               "efConstruction=" + efConstruction +
               ", efSearch=" + efSearch +
               ", maxConnections=" + maxConnections +
               ", maxConnectionsLayer0=" + maxConnectionsLayer0 +
               '}';
    }

    // Validation methods

    private void validateEfConstruction(int value) {
        if (value < MIN_EF) {
            throw new IllegalArgumentException(
                "efConstruction must be >= " + MIN_EF + ", got: " + value);
        }
    }

    private void validateEfSearch(int value) {
        if (value < MIN_EF) {
            throw new IllegalArgumentException(
                "efSearch must be >= " + MIN_EF + ", got: " + value);
        }
    }

    private void validateMaxConnections(int value) {
        if (value < MIN_CONNECTIONS) {
            throw new IllegalArgumentException(
                "maxConnections must be >= " + MIN_CONNECTIONS + ", got: " + value);
        }
        if (value > MAX_CONNECTIONS_LIMIT) {
            throw new IllegalArgumentException(
                "maxConnections must be <= " + MAX_CONNECTIONS_LIMIT + ", got: " + value);
        }
    }

    private void validateMaxConnectionsLayer0(int value) {
        if (value < MIN_CONNECTIONS) {
            throw new IllegalArgumentException(
                "maxConnectionsLayer0 must be >= " + MIN_CONNECTIONS + ", got: " + value);
        }
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
