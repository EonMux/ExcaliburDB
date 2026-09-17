package io.excalibur.vector.hnsw;

import io.excalibur.config.ShardConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Factory for creating HnswConfig instances from configuration settings.
 *
 * <p>This factory bridges the {@link ShardConfig} configuration properties
 * to the {@link HnswConfig} class, ensuring that property file values for
 * {@code excalibur.shard.default-ef-construction} and
 * {@code excalibur.shard.default-ef-search} are automatically applied
 * when creating HNSW graphs.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create factory from ShardConfig
 * HnswConfigFactory factory = HnswConfigFactory.fromShardConfig(config.shard());
 *
 * // Create HnswConfig for a specific dimension
 * HnswConfig config = factory.create(1536);
 *
 * // Use config to create HNSW graph
 * HnswGraph graph = new HnswGraph(path, config, distanceFunction, maxNodes);
 * }</pre>
 *
 * <h2>Configuration Properties</h2>
 * <ul>
 *   <li>{@code excalibur.shard.default-ef-construction} - efConstruction parameter (default: 128)</li>
 *   <li>{@code excalibur.shard.default-ef-search} - efSearch parameter (default: 64)</li>
 *   <li>{@code excalibur.shard.default-max-connections} - connections above layer 0 (default: 16)</li>
 *   <li>{@code excalibur.shard.default-max-connections-layer0} - layer 0 connections (default: 2x M)</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>efConstruction and efSearch must be at least 1 (enforced by ShardConfig validation)</li>
 *   <li>maxConnections and maxConnectionsLayer0 fix the on-disk adjacency layout, so they
 *       reach a graph only when it is created</li>
 *   <li>mL follows maxConnections rather than being configured on its own</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is immutable and thread-safe.</strong></p>
 *
 * @author ExcaliburDB
 * @since 4.0
 * @see HnswConfig
 * @see ShardConfig
 */
public final class HnswConfigFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswConfigFactory.class);

    /**
     * Creates a factory with default values.
     *
     * <p>This uses the ShardConfig defaults: efConstruction=128, efSearch=64.</p>
     *
     * @return A new HnswConfigFactory with default values
     */
    public static HnswConfigFactory withDefaults() {
        return new HnswConfigFactory(ShardConfig.DEFAULT_EF_CONSTRUCTION, ShardConfig.DEFAULT_EF_SEARCH);
    }

    /**
     * The efConstruction parameter from configuration.
     * Controls the size of the dynamic candidate list during graph construction.
     * Higher values produce better recall at the cost of slower construction.
     */
    private final int efConstruction;

    /**
     * The efSearch parameter from configuration.
     * Controls the size of the dynamic candidate list during search.
     * Higher values produce better recall at the cost of slower search.
     */
    private final int efSearch;

    /**
     * Neighbours a node keeps above layer 0, the graph's {@code M}.
     * Fixes the adjacency layout of every graph this factory creates.
     */
    private final int maxConnections;

    /**
     * Neighbours a node keeps at layer 0, where the walk becomes exhaustive.
     */
    private final int maxConnectionsLayer0;

    /**
     * Creates a new HnswConfigFactory with the HNSW paper's connection bounds.
     *
     * @param efConstruction The efConstruction parameter
     * @param efSearch The efSearch parameter
     * @throws IllegalArgumentException if parameters are invalid
     */
    public HnswConfigFactory(int efConstruction, int efSearch) {
        this(efConstruction, efSearch,
            HnswConfig.DEFAULT_MAX_CONNECTIONS, HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0);
    }

    /**
     * Creates a new HnswConfigFactory with the specified parameters.
     *
     * @param efConstruction The efConstruction parameter
     * @param efSearch The efSearch parameter
     * @param maxConnections Neighbours per node above layer 0
     * @param maxConnectionsLayer0 Neighbours per node at layer 0
     * @throws IllegalArgumentException if parameters are invalid
     */
    public HnswConfigFactory(int efConstruction, int efSearch,
                             int maxConnections, int maxConnectionsLayer0) {
        if (efConstruction < 1) {
            throw new IllegalArgumentException(
                "efConstruction must be at least 1, got: " + efConstruction);
        }
        if (efSearch < 1) {
            throw new IllegalArgumentException(
                "efSearch must be at least 1, got: " + efSearch);
        }

        this.efConstruction = efConstruction;
        this.efSearch = efSearch;
        this.maxConnections = maxConnections;
        this.maxConnectionsLayer0 = maxConnectionsLayer0;

        LOGGER.debug("HnswConfigFactory created with efConstruction={}, efSearch={}, "
            + "maxConnections={}, maxConnectionsLayer0={}",
            efConstruction, efSearch, maxConnections, maxConnectionsLayer0);
    }

    /**
     * Creates an HnswConfigFactory from ShardConfig settings.
     *
     * <p>This factory method is the primary integration point between
     * the configuration system and the HNSW graph configuration. It reads
     * the efConstruction and efSearch values from the shard configuration
     * and makes them available for creating HnswConfig instances.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>efConstruction and efSearch are validated by ShardConfig</li>
     *   <li>Values are used directly from configuration (no transformation)</li>
     * </ul>
     *
     * @param shard The shard configuration from ShardConfig
     * @return A new HnswConfigFactory configured with shard settings
     * @throws NullPointerException if shard is null
     */
    public static HnswConfigFactory fromShardConfig(ShardConfig shard) {
        Objects.requireNonNull(shard, "shard config cannot be null");

        LOGGER.info("Creating HnswConfigFactory from ShardConfig: efConstruction={}, efSearch={}, "
            + "maxConnections={}, maxConnectionsLayer0={}",
            shard.getEfConstruction(), shard.getEfSearch(),
            shard.getMaxConnections(), shard.getMaxConnectionsLayer0());

        return new HnswConfigFactory(
            shard.getEfConstruction(),
            shard.getEfSearch(),
            shard.getMaxConnections(),
            shard.getMaxConnectionsLayer0()
        );
    }

    /**
     * Creates an HnswConfigFactory with default values.
     *
     * <p>Uses the default efConstruction (128) and efSearch (64) from
     * ShardConfig defaults.</p>
     *
     * @return A new HnswConfigFactory with default settings
     */
    public static HnswConfigFactory defaults() {
        return new HnswConfigFactory(
            ShardConfig.DEFAULT_EF_CONSTRUCTION,
            ShardConfig.DEFAULT_EF_SEARCH
        );
    }

    /**
     * Creates an HnswConfig for the specified dimension using the factory settings.
     *
     * <p>The created config uses:</p>
     * <ul>
     *   <li>maxConnections = from this factory (from ShardConfig)</li>
     *   <li>maxConnectionsLayer0 = from this factory (from ShardConfig)</li>
     *   <li>efConstruction = from this factory (from ShardConfig)</li>
     *   <li>efSearch = from this factory (from ShardConfig)</li>
     *   <li>mL = 1/ln(M), derived from the configured maxConnections</li>
     * </ul>
     *
     * @param dimension The vector dimension (must be in range [1, 4096])
     * @return A new HnswConfig configured with factory settings
     * @throws IllegalArgumentException if dimension is invalid
     */
    public HnswConfig create(int dimension) {
        LOGGER.debug("Creating HnswConfig: dimension={}, efConstruction={}, efSearch={}",
            dimension, efConstruction, efSearch);

        return new HnswConfig(
            maxConnections,
            maxConnectionsLayer0,
            efConstruction,
            efSearch,
            HnswConfig.mLFor(maxConnections),   // mL follows M rather than being its own key
            dimension
        );
    }

    /**
     * Creates an HnswConfig with a custom efSearch override.
     *
     * <p>This is useful when a specific query needs higher or lower recall
     * than the default configured value.</p>
     *
     * @param dimension The vector dimension
     * @param customEfSearch The custom efSearch value to use
     * @return A new HnswConfig with the custom efSearch
     * @throws IllegalArgumentException if parameters are invalid
     */
    public HnswConfig createWithEfSearch(int dimension, int customEfSearch) {
        if (customEfSearch < 1) {
            throw new IllegalArgumentException(
                "customEfSearch must be at least 1, got: " + customEfSearch);
        }

        LOGGER.debug("Creating HnswConfig with custom efSearch: dimension={}, efConstruction={}, efSearch={}",
            dimension, efConstruction, customEfSearch);

        return new HnswConfig(
            maxConnections,
            maxConnectionsLayer0,
            efConstruction,
            customEfSearch,
            HnswConfig.mLFor(maxConnections),
            dimension
        );
    }

    /**
     * Creates an HnswConfig with a custom efConstruction override.
     *
     * <p>This is useful when building a graph that needs different
     * construction quality than the default.</p>
     *
     * @param dimension The vector dimension
     * @param customEfConstruction The custom efConstruction value to use
     * @return A new HnswConfig with the custom efConstruction
     * @throws IllegalArgumentException if parameters are invalid
     */
    public HnswConfig createWithEfConstruction(int dimension, int customEfConstruction) {
        if (customEfConstruction < 1) {
            throw new IllegalArgumentException(
                "customEfConstruction must be at least 1, got: " + customEfConstruction);
        }

        LOGGER.debug("Creating HnswConfig with custom efConstruction: dimension={}, efConstruction={}, efSearch={}",
            dimension, customEfConstruction, efSearch);

        return new HnswConfig(
            maxConnections,
            maxConnectionsLayer0,
            customEfConstruction,
            efSearch,
            HnswConfig.mLFor(maxConnections),
            dimension
        );
    }

    /**
     * Returns the efConstruction value used by this factory.
     *
     * @return The efConstruction parameter
     */
    public int getEfConstruction() {
        return efConstruction;
    }

    /**
     * Returns the efSearch value used by this factory.
     *
     * @return The efSearch parameter
     */
    public int getEfSearch() {
        return efSearch;
    }

    /**
     * Returns the connection bound above layer 0 used by this factory.
     *
     * @return The graph's {@code M}
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * Returns the layer 0 connection bound used by this factory.
     *
     * @return The layer 0 connection bound
     */
    public int getMaxConnectionsLayer0() {
        return maxConnectionsLayer0;
    }

    @Override
    public String toString() {
        return "HnswConfigFactory{" +
            "efConstruction=" + efConstruction +
            ", efSearch=" + efSearch +
            ", maxConnections=" + maxConnections +
            ", maxConnectionsLayer0=" + maxConnectionsLayer0 +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HnswConfigFactory that = (HnswConfigFactory) o;
        return efConstruction == that.efConstruction &&
               efSearch == that.efSearch &&
               maxConnections == that.maxConnections &&
               maxConnectionsLayer0 == that.maxConnectionsLayer0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(efConstruction, efSearch, maxConnections, maxConnectionsLayer0);
    }
}
