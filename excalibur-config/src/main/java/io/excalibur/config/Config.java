package io.excalibur.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration data class representing all ExcaliburDB settings.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is IMMUTABLE and thread-safe. It is designed for use
 * as a configuration holder that will not change during the application's lifetime.
 * All fields are final and set only at construction time.</p>
 *
 * <h2>Configuration Sections</h2>
 * <p>The Config class assembles all configuration sections defined in the
 * properties file and environment variables. Each section is a class of its own in this
 * package, holding the keys it owns and the bounds they have to satisfy:</p>
 * <ul>
 *   <li>{@link VisibilityConfig} - Visibility epoch and query settings</li>
 *   <li>{@link IngestConfig} - Ingestion pipeline settings</li>
 *   <li>{@link HealthConfig} - Health check thresholds</li>
 *   <li>{@link QueryConfig} - Query parameters</li>
 *   <li>{@link LlmConfig} - LLM client settings</li>
 *   <li>{@link ShardConfig} - Shard management settings</li>
 *   <li>{@link GraphConfig} - Graph-RAG settings</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class Config {

    // Configuration sections - all final for immutability
    private final VisibilityConfig visibility;
    private final IngestConfig ingest;
    private final HealthConfig health;
    private final QueryConfig query;
    private final LlmConfig llm;
    private final ShardConfig shard;
    private final GraphConfig graph;

    // Application settings - final for immutability
    private final String configPath;
    private final Map<String, String> envOverrides;

    /** The file's properties with the environment applied; see {@link #getEffectiveProperties()}. */
    private final Map<String, String> effectiveProperties;

    /**
     * Creates a new Config instance from a properties map.
     *
     * @param props Map of property key-value pairs
     */
    public Config(Map<String, String> props) {
        this("excalibur.properties", Collections.emptyMap(), props);
    }

    /**
     * Creates a new Config instance with specified config path and environment overrides.
     *
     * @param configPath Path to the properties file
     * @param envOverrides Environment variables, keyed by variable name (EXCALIBUR_*)
     */
    public Config(String configPath, Map<String, String> envOverrides) {
        this(configPath, envOverrides, new HashMap<>());
    }

    /**
     * Creates a new Config instance with all parameters.
     *
     * <p>Environment variables take precedence over the properties map, so a Config
     * built directly honours <code>EXCALIBUR_*</code> exactly as one loaded through
     * {@link ConfigLoader} does.</p>
     *
     * @param configPath Path to the properties file
     * @param envOverrides Environment variables, keyed by variable name (EXCALIBUR_*)
     * @param props Map of property key-value pairs (from file)
     * @throws IllegalArgumentException if any property value cannot be parsed or
     *         violates a domain constraint
     */
    public Config(String configPath, Map<String, String> envOverrides, Map<String, String> props) {
        this.configPath = Objects.requireNonNull(configPath, "configPath cannot be null");
        this.envOverrides = Collections.unmodifiableMap(
            new HashMap<>(envOverrides != null ? envOverrides : Collections.emptyMap())
        );

        // Initialize configuration sections with the file values overlaid by the
        // environment, so no construction path can silently discard an override.
        Map<String, String> effectiveProps = EnvOverrides.apply(props, this.envOverrides);
        this.effectiveProperties = Collections.unmodifiableMap(new HashMap<>(effectiveProps));
        this.visibility = new VisibilityConfig(effectiveProps);
        this.ingest = new IngestConfig(effectiveProps);
        this.health = new HealthConfig(effectiveProps);
        this.query = new QueryConfig(effectiveProps);
        this.llm = new LlmConfig(effectiveProps);
        this.shard = new ShardConfig(effectiveProps);
        this.graph = new GraphConfig(effectiveProps);
    }

    /**
     * Gets the visibility configuration.
     */
    public VisibilityConfig visibility() {
        return visibility;
    }

    /**
     * Gets the ingest configuration.
     */
    public IngestConfig ingest() {
        return ingest;
    }

    /**
     * Gets the health configuration.
     */
    public HealthConfig health() {
        return health;
    }

    /**
     * Gets the query configuration.
     */
    public QueryConfig query() {
        return query;
    }

    /**
     * Gets the LLM configuration.
     */
    public LlmConfig llm() {
        return llm;
    }

    /**
     * Gets the shard configuration.
     */
    public ShardConfig shard() {
        return shard;
    }

    /**
     * Gets the graph-RAG configuration.
     */
    public GraphConfig graph() {
        return graph;
    }

    /**
     * Gets the path to the configuration file.
     */
    public String getConfigPath() {
        return configPath;
    }

    /**
     * Every property in effect: the file's values with {@code EXCALIBUR_*} overrides applied.
     *
     * <p>The typed sections below cover what this module knows about, but a module that owns its
     * own keys — the API layer and its auth, bind and rate-limit settings — has nowhere else to
     * read them from. Without this it has to reopen the file and reimplement the environment
     * transform, which is a second parser that can disagree with this one about precedence.
     *
     * <p>Prefer a typed section when one exists. This is for keys this module does not model.
     *
     * @return an unmodifiable view of the merged properties
     */
    public Map<String, String> getEffectiveProperties() {
        return effectiveProperties;
    }

    /**
     * Gets the environment variable overrides applied to configuration.
     *
     * @return an unmodifiable view of the {@code EXCALIBUR_*} variables this Config was built
     *         with, keyed by variable name
     */
    public Map<String, String> getEnvOverrides() {
        return envOverrides;
    }

    @Override
    public String toString() {
        return "Config{" +
                "configPath=\"" + configPath + "\"," +
                "visibility=" + visibility +
                ", ingest=" + ingest +
                ", health=" + health +
                ", query=" + query +
                ", llm=" + llm +
                ", shard=" + shard +
                ", graph=" + graph +
                '}';
    }

    /**
     * Compares two configurations by the settings they carry.
     *
     * <p>The seven sections are the value; {@code configPath}, the environment overrides and
     * the effective property map are how this instance came to hold them. Two nodes given the
     * same settings are configured the same way whether one read a file and the other read the
     * environment, so provenance is deliberately left out of the comparison.</p>
     *
     * <p>This is a real value comparison rather than an identity check: every section
     * implements {@code equals} over all of its parsed fields.</p>
     *
     * @param o The object to compare against
     * @return true if both carry the same settings
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Config config = (Config) o;
        return Objects.equals(visibility, config.visibility) &&
               Objects.equals(ingest, config.ingest) &&
               Objects.equals(health, config.health) &&
               Objects.equals(query, config.query) &&
               Objects.equals(llm, config.llm) &&
               Objects.equals(shard, config.shard) &&
               Objects.equals(graph, config.graph);
    }

    @Override
    public int hashCode() {
        return Objects.hash(visibility, ingest, health, query, llm, shard, graph);
    }
}
