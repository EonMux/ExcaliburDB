package io.excalibur.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
 * <p>The Config class contains all configuration sections defined in the
 * properties file and environment variables:</p>
 * <ul>
 *   <li><code>visibility</code> - Visibility epoch and query settings</li>
 *   <li><code>ingest</code> - Ingestion pipeline settings</li>
 *   <li><code>health</code> - Health check thresholds</li>
 *   <li><code>query</code> - Query parameters</li>
 *   <li><code>llm</code> - LLM client settings</li>
 *   <li><code>shard</code> - Shard management settings</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class Config {

    // Configuration sections - all final for immutability
    private final Visibility visibility;
    private final Ingest ingest;
    private final Health health;
    private final Query query;
    private final Llm llm;
    private final Shard shard;
    private final Graph graph;

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
        this.visibility = new Visibility(effectiveProps);
        this.ingest = new Ingest(effectiveProps);
        this.health = new Health(effectiveProps);
        this.query = new Query(effectiveProps);
        this.llm = new Llm(effectiveProps);
        this.shard = new Shard(effectiveProps);
        this.graph = new Graph(effectiveProps);
    }

    /**
     * Gets the visibility configuration.
     */
    public Visibility visibility() {
        return visibility;
    }

    /**
     * Gets the ingest configuration.
     */
    public Ingest ingest() {
        return ingest;
    }

    /**
     * Gets the health configuration.
     */
    public Health health() {
        return health;
    }

    /**
     * Gets the query configuration.
     */
    public Query query() {
        return query;
    }

    /**
     * Gets the LLM configuration.
     */
    public Llm llm() {
        return llm;
    }

    /**
     * Gets the shard configuration.
     */
    public Shard shard() {
        return shard;
    }

    /**
     * Gets the graph-RAG configuration.
     */
    public Graph graph() {
        return graph;
    }

    /**
     * Gets the path to the configuration file.
     */
    public String getConfigPath() {
        return configPath;
    }

    /**
     * Gets the environment variable overrides applied to configuration.
     */
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

    public Map<String, String> getEnvOverrides() {
        return envOverrides;
    }

    // ============================================
    // Visibility Configuration
    // ============================================

    /**
     * Visibility configuration for epoch-based query isolation.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     */
    public static final class Visibility {

        // Default epoch tick interval in milliseconds
        public static final long DEFAULT_EPOCH_TICK_MS = 100;

        // Query timeout in milliseconds
        public static final int DEFAULT_QUERY_TIMEOUT_MS = 30000;

        // Minimum and maximum values for validation (used by ConfigValidation)
        public static final long MIN_EPOCH_TICK_MS = 10;
        public static final long MAX_EPOCH_TICK_MS = 10000;
        public static final int MIN_QUERY_TIMEOUT_MS = 1000;
        public static final int MAX_QUERY_TIMEOUT_MS = 300000;

        // All fields final for immutability
        private final long epochTickMs;
        private final int queryTimeoutMs;

        /**
         * Creates a Visibility config from properties.
         *
         * @param props The properties map
         */
        public Visibility(Map<String, String> props) {
            this.epochTickMs = PropertyParser.parseLong(props, "excalibur.visibility.epoch-tick-ms", DEFAULT_EPOCH_TICK_MS);
            this.queryTimeoutMs = PropertyParser.parseInt(props, "excalibur.visibility.query-timeout-ms", DEFAULT_QUERY_TIMEOUT_MS);
        }

        /**
         * Gets the epoch tick interval in milliseconds.
         *
         * @return The interval between visibility epoch ticks
         */
        public long getEpochTickMs() {
            return epochTickMs;
        }

        /**
         * Gets the visibility epoch number.
         *
         * @return Current epoch number
         */
        public long getEpoch() {
            return System.currentTimeMillis() / epochTickMs;
        }

        /**
         * Gets the time until the next epoch tick in milliseconds.
         *
         * @return Time remaining until next epoch
         */
        public long timeUntilEpochTick() {
            long now = System.currentTimeMillis();
            long elapsed = now % epochTickMs;
            return epochTickMs - elapsed;
        }

        /**
         * Gets the query timeout in milliseconds.
         *
         * @return Query timeout in milliseconds
         */
        public int getQueryTimeoutMs() {
            return queryTimeoutMs;
        }

        @Override
        public String toString() {
            return "Visibility{epochTickMs=" + epochTickMs + ", queryTimeoutMs=" + queryTimeoutMs + '}';
        }
    }

    // ============================================
    // Ingest Configuration
    // ============================================

    /**
     * Ingestion pipeline configuration.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     */
    public static final class Ingest {

        // HNSW integration queue depth limit

        // Document atomicity timeout in milliseconds
        public static final int DEFAULT_DOCUMENT_TIMEOUT_MS = 2000;

        // Default for document atomicity enabled
        public static final boolean DEFAULT_DOCUMENT_ATOMICITY = true;

        // Default reindex interval in milliseconds (1 hour)
        public static final long DEFAULT_REINDEX_INTERVAL_MS = 3600000L;

        // Minimum and maximum values for validation (used by ConfigValidation)
        public static final long MIN_QUEUE_DEPTH_LIMIT = 10000L;
        public static final long MAX_QUEUE_DEPTH_LIMIT = 1000000L;
        public static final int MIN_DOCUMENT_TIMEOUT_MS = 1000;
        public static final int MAX_DOCUMENT_TIMEOUT_MS = 30000;

        // Reindex interval bounds. These mirror the window the shard module's
        // SegmentReindexScheduler enforces (1 minute to 24 hours); validating them
        // here turns a misconfiguration into a startup failure instead of a
        // half-completed source registration inside the /v1/ingest request path.
        public static final long MIN_REINDEX_INTERVAL_MS = 60000L;
        public static final long MAX_REINDEX_INTERVAL_MS = 86400000L;

        /**
         * Which engine indexes chunk text.
         *
         * <p>"native" is the engine's own inverted index, which stores postings and reads a
         * chunk's text back from storage when it needs it. "lucene" keeps a second copy of every
         * chunk's text in a Lucene index. They are alternatives, not layers: running both would
         * write the corpus twice.</p>
         */
        public static final String LEXICAL_ENGINE_NATIVE = "native";

        /** The Lucene-backed chunk index. */
        public static final String LEXICAL_ENGINE_LUCENE = "lucene";

        /** The default engine. */
        public static final String DEFAULT_LEXICAL_ENGINE = LEXICAL_ENGINE_NATIVE;

        /** Tombstoned fraction of the chunk index at which a compaction repays its rewrite. */
        public static final double DEFAULT_LEXICAL_TOMBSTONE_RATIO = 0.2;

        /** How long an erased chunk may remain on disk before cost stops being a consideration. */
        public static final long DEFAULT_LEXICAL_MAX_LATENCY_SECONDS = 3600L;

        /** Chunk index segments tolerated before the smallest are merged. */
        public static final int DEFAULT_LEXICAL_MAX_SEGMENTS = 16;

        // All fields final for immutability
        private final int documentTimeoutMs;
        private final boolean documentAtomicity;
        private final long reindexIntervalMs;
        private final String lexicalEngine;
        private final double lexicalTombstoneRatio;
        private final long lexicalMaxLatencySeconds;
        private final int lexicalMaxSegments;

        /**
         * Creates an Ingest config from properties.
         *
         * @param props The properties map
         */
        public Ingest(Map<String, String> props) {
            this.documentTimeoutMs = PropertyParser.parseInt(props, "excalibur.ingest.document-atomicity-timeout-ms", DEFAULT_DOCUMENT_TIMEOUT_MS);
            this.documentAtomicity = PropertyParser.parseBoolean(props, "excalibur.ingest.document-atomicity", DEFAULT_DOCUMENT_ATOMICITY);
            this.reindexIntervalMs = PropertyParser.parseLong(props, "excalibur.ingest.reindex-interval-ms", DEFAULT_REINDEX_INTERVAL_MS);
            this.lexicalEngine = PropertyParser.parseString(props,
                "excalibur.ingest.lexical-engine", DEFAULT_LEXICAL_ENGINE)
                .trim().toLowerCase(java.util.Locale.ROOT);
            if (!LEXICAL_ENGINE_NATIVE.equals(lexicalEngine)
                    && !LEXICAL_ENGINE_LUCENE.equals(lexicalEngine)) {
                // Refused at startup rather than fallen back from. An unrecognised engine name is
                // an operator expecting one index and getting another, which they would only find
                // out by noticing their queries answered from a corpus they did not build.
                throw new IllegalArgumentException("excalibur.ingest.lexical-engine must be "
                    + LEXICAL_ENGINE_NATIVE + " or " + LEXICAL_ENGINE_LUCENE
                    + ", got: " + lexicalEngine);
            }
            this.lexicalTombstoneRatio = PropertyParser.parseDouble(props,
                "excalibur.ingest.lexical-tombstone-ratio", DEFAULT_LEXICAL_TOMBSTONE_RATIO);
            if (lexicalTombstoneRatio <= 0 || lexicalTombstoneRatio > 1) {
                throw new IllegalArgumentException("excalibur.ingest.lexical-tombstone-ratio must "
                    + "be in (0, 1], got: " + lexicalTombstoneRatio);
            }
            this.lexicalMaxLatencySeconds = PropertyParser.parseLong(props,
                "excalibur.ingest.lexical-max-latency-seconds",
                DEFAULT_LEXICAL_MAX_LATENCY_SECONDS);
            if (lexicalMaxLatencySeconds <= 0) {
                throw new IllegalArgumentException("excalibur.ingest.lexical-max-latency-seconds "
                    + "must be positive, got: " + lexicalMaxLatencySeconds);
            }
            this.lexicalMaxSegments = PropertyParser.parseInt(props,
                "excalibur.ingest.lexical-max-segments", DEFAULT_LEXICAL_MAX_SEGMENTS);
            if (lexicalMaxSegments < 1) {
                throw new IllegalArgumentException("excalibur.ingest.lexical-max-segments must be "
                    + "at least 1, got: " + lexicalMaxSegments);
            }
        }

        /** Which engine indexes chunk text: {@code native} or {@code lucene}. */
        public String getLexicalEngine() {
            return lexicalEngine;
        }

        /** Tombstoned fraction at which a chunk-index compaction repays its rewrite. */
        public double getLexicalTombstoneRatio() {
            return lexicalTombstoneRatio;
        }

        /** How long an erased chunk may remain on disk. */
        public long getLexicalMaxLatencySeconds() {
            return lexicalMaxLatencySeconds;
        }

        /** Chunk index segments tolerated before merging. */
        public int getLexicalMaxSegments() {
            return lexicalMaxSegments;
        }

        /**
         * Gets the document atomicity timeout.
         */
        public int getDocumentTimeoutMs() {
            return documentTimeoutMs;
        }

        /**
         * Checks if document atomicity is enabled.
         *
         * <p>When enabled, a document's chunks are buffered and published
         * to the visibility epoch as a batch, ensuring queries never see
         * partially-ingested documents.</p>
         *
         * @return true if document atomicity is enabled
         */
        public boolean isDocumentAtomicityEnabled() {
            return documentAtomicity;
        }

        /**
         * Gets the reindex interval in milliseconds.
         *
         * <p>This is the default interval between automatic reindex operations
         * for sources registered via the /v1/ingest endpoint.</p>
         *
         * @return Reindex interval in milliseconds
         */
        public long getReindexIntervalMs() {
            return reindexIntervalMs;
        }

        @Override
        public String toString() {
            return "Ingest{documentTimeoutMs=" + documentTimeoutMs +
                   ", documentAtomicity=" + documentAtomicity +
                   ", reindexIntervalMs=" + reindexIntervalMs + '}';
        }
    }

    // ============================================
    // Health Configuration
    // ============================================

    /**
     * Health check configuration.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     */
    public static final class Health {

        // Readiness threshold for health endpoint
        public static final double DEFAULT_READINESS_THRESHOLD = 0.5;

        // Healthy threshold (higher coverage required)
        public static final double DEFAULT_HEALTHY_THRESHOLD = 0.9;

        // Maximum query latency in milliseconds
        public static final int DEFAULT_MAX_LATENCY_MS = 5000;

        // Minimum and maximum values for validation (used by ConfigValidation)
        public static final double MIN_THRESHOLD = 0.0;
        public static final double MAX_THRESHOLD = 1.0;
        public static final int MIN_MAX_LATENCY_MS = 100;
        public static final int MAX_MAX_LATENCY_MS = 60000;

        // All fields final for immutability
        private final double readinessThreshold;
        private final double healthyThreshold;
        private final int maxLatencyMs;

        /**
         * Creates a Health config from properties.
         *
         * @param props The properties map
         */
        public Health(Map<String, String> props) {
            this.readinessThreshold = PropertyParser.parseDouble(props, "excalibur.health.readiness-threshold", DEFAULT_READINESS_THRESHOLD);
            this.healthyThreshold = PropertyParser.parseDouble(props, "excalibur.health.healthy-threshold", DEFAULT_HEALTHY_THRESHOLD);
            this.maxLatencyMs = PropertyParser.parseInt(props, "excalibur.health.max-latency-ms", DEFAULT_MAX_LATENCY_MS);
        }

        /**
         * Gets the readiness threshold.
         *
         * @return Threshold below which application is considered not ready
         */
        public double getReadinessThreshold() {
            return readinessThreshold;
        }

        /**
         * Gets the healthy threshold.
         */
        public double getHealthyThreshold() {
            return healthyThreshold;
        }

        /**
         * Gets the maximum query latency.
         */
        public int getMaxLatencyMs() {
            return maxLatencyMs;
        }

        @Override
        public String toString() {
            return "Health{readinessThreshold=" + readinessThreshold + ", healthyThreshold=" + healthyThreshold + ", maxLatencyMs=" + maxLatencyMs + '}';
        }
    }

    // ============================================
    // Query Configuration
    // ============================================

    /**
     * Query configuration.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     *
     * <h2>Configuration Properties</h2>
     * <ul>
     *   <li><code>excalibur.query.top-k-default</code> - Results returned when a request names
     *       no {@code top_k} (default: 100)</li>
     *   <li><code>excalibur.query.threshold</code> - Score floor for vector search
     *       (default: 0.0, meaning no floor)</li>
     *   <li><code>excalibur.query.vector-dimensions</code> - Dimensionality of the index
     *       (default: 1536)</li>
     *   <li><code>excalibur.query.llm-timeout-ms</code> - Budget for the relevancy-scoring call
     *       (default: 60000)</li>
     *   <li><code>excalibur.query.include-partial-segments</code> - Whether a segment still being
     *       built may contribute results (default: true)</li>
     *   <li><code>excalibur.query.hybrid-retrieval</code> - Whether a node holding both signals
     *       fuses them on {@code /v1/query} (default: true)</li>
     * </ul>
     */
    public static final class Query {

        // Default top-k value
        public static final int DEFAULT_TOP_K = 100;

        // Default score floor for queries that name none. Zero means no filtering: a query
        // that returns nothing because of a floor nobody chose is harder to diagnose than one
        // that returns weak results.
        public static final double DEFAULT_THRESHOLD = 0.0;

        // Whether a node that has both an embedding model and a lexical index answers
        // /v1/query from both. On by default: the BM25 index over the chunks is written,
        // compacted and erasure-swept whether or not a query reads it, so a node holding
        // both signals and using one is paying for an index it never consults. Off restores
        // vector-only ranking, which is what a deployment tuned against cosine scores wants.
        public static final boolean DEFAULT_HYBRID_RETRIEVAL = true;

        // Default vector dimensions
        public static final int DEFAULT_VECTOR_DIMENSIONS = 1536;

        // Default LLM timeout
        public static final int DEFAULT_LLM_TIMEOUT_MS = 60000;

        // Minimum and maximum values for validation (used by ConfigValidation)
        public static final int MIN_TOP_K = 1;
        public static final int MAX_TOP_K = 10000;
        public static final double MIN_PROBABILITY = 0.0;
        public static final double MAX_PROBABILITY = 1.0;
        public static final int MIN_VECTOR_DIMENSIONS = 128;
        public static final int MAX_VECTOR_DIMENSIONS = 4096;
        public static final int MIN_LLM_TIMEOUT_MS = 5000;
        public static final int MAX_LLM_TIMEOUT_MS = 300000;

        // All fields final for immutability
        private final int defaultTopK;
        private final double threshold;
        private final int vectorDimensions;
        private final int llmTimeoutMs;
        private final boolean includePartialSegments;
        private final boolean hybridRetrieval;

        /**
         * Creates a Query config from properties.
         *
         * @param props The properties map
         */
        public Query(Map<String, String> props) {
            this.defaultTopK = PropertyParser.parseInt(props, "excalibur.query.top-k-default", DEFAULT_TOP_K);
            this.threshold = PropertyParser.parseDouble(props, "excalibur.query.threshold", DEFAULT_THRESHOLD);
            this.vectorDimensions = PropertyParser.parseInt(props, "excalibur.query.vector-dimensions", DEFAULT_VECTOR_DIMENSIONS);
            this.llmTimeoutMs = PropertyParser.parseInt(props, "excalibur.query.llm-timeout-ms", DEFAULT_LLM_TIMEOUT_MS);
            this.includePartialSegments = PropertyParser.parseBoolean(props, "excalibur.query.include-partial-segments", true);
            this.hybridRetrieval = PropertyParser.parseBoolean(props, "excalibur.query.hybrid-retrieval", DEFAULT_HYBRID_RETRIEVAL);
        }

        /**
         * Gets the default top-k value.
         */
        public int getDefaultTopK() {
            return defaultTopK;
        }

        /**
         * Gets the threshold.
         */
        public double getThreshold() {
            return threshold;
        }

        /**
         * Gets the vector dimensions.
         */
        public int getVectorDimensions() {
            return vectorDimensions;
        }

        /**
         * Gets the LLM timeout.
         */
        public int getLlmTimeoutMs() {
            return llmTimeoutMs;
        }

        /**
         * Gets whether to include partial segments.
         */
        public boolean isIncludePartialSegments() {
            return includePartialSegments;
        }

        /**
         * Whether {@code /v1/query} fuses BM25 with vector search on a node that has both.
         *
         * <p>Has no effect on a node that holds only one of them: with no embedding model the
         * route answers from BM25, and with no lexical index it answers from vectors, whichever
         * way this is set.</p>
         *
         * @return true when both signals should answer, false for vector-only ranking
         */
        public boolean isHybridRetrieval() {
            return hybridRetrieval;
        }

        @Override
        public String toString() {
            return "Query{defaultTopK=" + defaultTopK +
                   ", threshold=" + threshold + ", vectorDimensions=" + vectorDimensions +
                   ", llmTimeoutMs=" + llmTimeoutMs + ", includePartialSegments=" + includePartialSegments +
                   ", hybridRetrieval=" + hybridRetrieval + '}';
        }
    }

    // ============================================
    // LLM Configuration
    // ============================================

    /**
     * LLM client configuration.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     *
     * <h2>Configuration Properties</h2>
     * <ul>
     *   <li><code>excalibur.llm.base-url</code> - Base URL for the LLM API (default: OpenAI)</li>
     *   <li><code>excalibur.llm.api-key</code> - API key for authentication (default: empty)</li>
     *   <li><code>excalibur.llm.chat-model</code> - Model for chat completions (default: gpt-4o-mini)</li>
     *   <li><code>excalibur.llm.embedding-model</code> - Model for embeddings (default: text-embedding-3-small)</li>
     *   <li><code>excalibur.llm.embeddings-enabled</code> - Whether the corpus and queries are embedded (default: true)</li>
     *   <li><code>excalibur.llm.embeddings-dimensions</code> - Embedding vector dimensions (default: 1536)</li>
     *   <li><code>excalibur.llm.timeout-ms</code> - HTTP request timeout (default: 60000)</li>
     *   <li><code>excalibur.llm.max-retries</code> - Maximum retry attempts (default: 3)</li>
     *   <li><code>excalibur.llm.failure-threshold</code> - Failures before circuit opens (default: 5)</li>
     *   <li><code>excalibur.llm.cooldown-period-ms</code> - Circuit breaker cooldown (default: 60000)</li>
     *   <li><code>excalibur.llm.max-prompts-per-query</code> - Max prompts for relevancy scoring (default: 10)</li>
     *   <li><code>excalibur.llm.relevancy-threshold</code> - Minimum relevancy score (default: 0.3)</li>
     *   <li><code>excalibur.llm.max-batch-tokens</code> - Max tokens per batch (default: 4000)</li>
     *   <li><code>excalibur.llm.retry-base-delay-ms</code> - Base delay for exponential backoff (default: 500)</li>
     * </ul>
     */
    public static final class Llm {

        // Default OpenAI base URL
        public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

        // Default chat model
        public static final String DEFAULT_CHAT_MODEL = "gpt-4o-mini";

        // Default embedding model
        public static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";

        // Default API key (empty for optional)
        public static final String DEFAULT_API_KEY = "";

        // Default embeddings dimensions
        public static final int DEFAULT_EMBEDDINGS_DIMENSIONS = 1536;

        // Whether a configured key is spent on embedding the corpus. On by default: a key is
        // normally set because vector retrieval is wanted. Turning it off keeps the chat model
        // and drops the embedding pass, which is the lexical-only deployment - BM25 over the
        // chunks with the model spent on expanding the query instead.
        public static final boolean DEFAULT_EMBEDDINGS_ENABLED = true;

        // Default timeout in milliseconds
        public static final int DEFAULT_TIMEOUT_MS = 60000;

        // Default max retries
        public static final int DEFAULT_MAX_RETRIES = 3;

        // Default circuit breaker failure threshold
        public static final int DEFAULT_FAILURE_THRESHOLD = 5;

        // Default circuit breaker cooldown period in milliseconds
        public static final long DEFAULT_COOLDOWN_PERIOD_MS = 60000L;

        // Default max prompts per query for relevancy scoring
        public static final int DEFAULT_MAX_PROMPTS_PER_QUERY = 10;

        // Default relevancy threshold (0.0 - 1.0)
        public static final double DEFAULT_RELEVANCY_THRESHOLD = 0.3;

        // Default max batch tokens
        public static final int DEFAULT_MAX_BATCH_TOKENS = 4000;

        // Default retry base delay in milliseconds
        public static final long DEFAULT_RETRY_BASE_DELAY_MS = 500L;

        // Minimum and maximum values for validation
        public static final int MIN_EMBEDDINGS_DIMENSIONS = 128;
        public static final int MAX_EMBEDDINGS_DIMENSIONS = 4096;
        public static final int MIN_TIMEOUT_MS = 1000;
        public static final int MAX_TIMEOUT_MS = 300000;
        public static final int MIN_MAX_RETRIES = 0;
        public static final int MAX_MAX_RETRIES = 10;
        public static final int MIN_FAILURE_THRESHOLD = 1;
        public static final int MAX_FAILURE_THRESHOLD = 100;
        public static final long MIN_COOLDOWN_PERIOD_MS = 1000L;
        public static final long MAX_COOLDOWN_PERIOD_MS = 600000L;
        public static final int MIN_MAX_PROMPTS_PER_QUERY = 1;
        public static final int MAX_MAX_PROMPTS_PER_QUERY = 100;
        public static final double MIN_RELEVANCY_THRESHOLD = 0.0;
        public static final double MAX_RELEVANCY_THRESHOLD = 1.0;
        public static final int MIN_MAX_BATCH_TOKENS = 100;
        public static final int MAX_MAX_BATCH_TOKENS = 100000;
        public static final long MIN_RETRY_BASE_DELAY_MS = 10L;
        public static final long MAX_RETRY_BASE_DELAY_MS = 30000L;

        // All fields final for immutability
        private final String baseUrl;
        private final String apiKey;
        private final String chatModel;
        private final String embeddingModel;
        private final int embeddingsDimensions;
        private final boolean embeddingsEnabled;
        private final int timeoutMs;
        private final int maxRetries;
        private final int failureThreshold;
        private final long cooldownPeriodMs;
        private final int maxPromptsPerQuery;
        private final double relevancyThreshold;
        private final int maxBatchTokens;
        private final long retryBaseDelayMs;

        /**
         * Creates an Llm config from properties.
         *
         * @param props The properties map
         */
        public Llm(Map<String, String> props) {
            this.baseUrl = PropertyParser.parseString(props, "excalibur.llm.base-url", DEFAULT_BASE_URL);
            this.apiKey = PropertyParser.parseString(props, "excalibur.llm.api-key", DEFAULT_API_KEY);
            this.chatModel = PropertyParser.parseString(props, "excalibur.llm.chat-model", DEFAULT_CHAT_MODEL);
            this.embeddingModel = PropertyParser.parseString(props, "excalibur.llm.embedding-model", DEFAULT_EMBEDDING_MODEL);
            this.embeddingsDimensions = PropertyParser.parseInt(props, "excalibur.llm.embeddings-dimensions", DEFAULT_EMBEDDINGS_DIMENSIONS);
            this.embeddingsEnabled = PropertyParser.parseBoolean(props, "excalibur.llm.embeddings-enabled", DEFAULT_EMBEDDINGS_ENABLED);
            this.timeoutMs = PropertyParser.parseInt(props, "excalibur.llm.timeout-ms", DEFAULT_TIMEOUT_MS);
            this.maxRetries = PropertyParser.parseInt(props, "excalibur.llm.max-retries", DEFAULT_MAX_RETRIES);
            this.failureThreshold = PropertyParser.parseInt(props, "excalibur.llm.failure-threshold", DEFAULT_FAILURE_THRESHOLD);
            this.cooldownPeriodMs = PropertyParser.parseLong(props, "excalibur.llm.cooldown-period-ms", DEFAULT_COOLDOWN_PERIOD_MS);
            this.maxPromptsPerQuery = PropertyParser.parseInt(props, "excalibur.llm.max-prompts-per-query", DEFAULT_MAX_PROMPTS_PER_QUERY);
            this.relevancyThreshold = PropertyParser.parseDouble(props, "excalibur.llm.relevancy-threshold", DEFAULT_RELEVANCY_THRESHOLD);
            this.maxBatchTokens = PropertyParser.parseInt(props, "excalibur.llm.max-batch-tokens", DEFAULT_MAX_BATCH_TOKENS);
            this.retryBaseDelayMs = PropertyParser.parseLong(props, "excalibur.llm.retry-base-delay-ms", DEFAULT_RETRY_BASE_DELAY_MS);
        }

        /**
         * Gets the base URL.
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * Gets the API key.
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * Gets the chat model for completions/relevancy scoring.
         */
        public String getChatModel() {
            return chatModel;
        }

        /**
         * Gets the embedding model.
         */
        public String getEmbeddingModel() {
            return embeddingModel;
        }

        /**
         * Whether this node embeds its corpus and its queries.
         *
         * <p>False leaves the key configured and the chat model reachable while producing no
         * vectors, so retrieval is BM25 over the chunk index with the query expanded by the
         * chat model. Meaningless without a key: nothing is embedded either way.</p>
         *
         * @return true if documents and query text are embedded
         */
        public boolean isEmbeddingsEnabled() {
            return embeddingsEnabled;
        }

        /**
         * Gets the embeddings dimensions.
         */
        public int getEmbeddingsDimensions() {
            return embeddingsDimensions;
        }

        /**
         * Gets the timeout in milliseconds.
         */
        public int getTimeoutMs() {
            return timeoutMs;
        }

        /**
         * Gets the maximum retry attempts.
         */
        public int getMaxRetries() {
            return maxRetries;
        }

        /**
         * Gets the circuit breaker failure threshold.
         * Number of consecutive failures before the circuit opens.
         */
        public int getFailureThreshold() {
            return failureThreshold;
        }

        /**
         * Gets the circuit breaker cooldown period in milliseconds.
         * Time to wait before attempting to close the circuit after it opens.
         */
        public long getCooldownPeriodMs() {
            return cooldownPeriodMs;
        }

        /**
         * Gets the maximum number of prompts per query for relevancy scoring.
         */
        public int getMaxPromptsPerQuery() {
            return maxPromptsPerQuery;
        }

        /**
         * Gets the relevancy threshold (0.0 - 1.0).
         * Results below this threshold are discarded.
         */
        public double getRelevancyThreshold() {
            return relevancyThreshold;
        }

        /**
         * Gets the maximum tokens per batch for relevancy scoring requests.
         */
        public int getMaxBatchTokens() {
            return maxBatchTokens;
        }

        /**
         * Gets the base delay for exponential backoff retries in milliseconds.
         * This is the initial delay before the first retry, which doubles with each
         * subsequent retry attempt.
         *
         * @return Retry base delay in milliseconds
         */
        public long getRetryBaseDelayMs() {
            return retryBaseDelayMs;
        }

        @Override
        public String toString() {
            return "Llm{baseUrl='" + baseUrl + "', apiKey='***', chatModel='" + chatModel +
                   "', embeddingModel='" + embeddingModel + "', embeddingsDimensions=" + embeddingsDimensions +
                   ", timeoutMs=" + timeoutMs + ", maxRetries=" + maxRetries +
                   ", failureThreshold=" + failureThreshold + ", cooldownPeriodMs=" + cooldownPeriodMs +
                   ", maxPromptsPerQuery=" + maxPromptsPerQuery + ", relevancyThreshold=" + relevancyThreshold +
                   ", maxBatchTokens=" + maxBatchTokens + ", retryBaseDelayMs=" + retryBaseDelayMs + '}';
        }
    }

    // ============================================
    // Shard Configuration
    // ============================================

    /**
     * Shard management configuration.
     *
     * <p>This class is IMMUTABLE and thread-safe.</p>
     */
    public static final class Shard {

        // Default segment load factor for splitting
        public static final double DEFAULT_LOAD_FACTOR = 0.7;

        // Default HNSW efConstruction
        public static final int DEFAULT_EF_CONSTRUCTION = 128;

        // Default HNSW efSearch
        public static final int DEFAULT_EF_SEARCH = 64;

        // Default compaction delay
        public static final int DEFAULT_COMPACT_DELAY_MS = 10000;

        // Default HNSW integration queue depth limit
        public static final int DEFAULT_HNSW_QUEUE_LIMIT = 100000;

        // All fields final for immutability
        private final double loadFactor;
        private final int efConstruction;
        private final int efSearch;
        private final int compactDelayMs;
        private final int hnswQueueLimit;

        /**
         * Creates a Shard config from properties.
         *
         * @param props The properties map
         * @throws IllegalArgumentException if loadFactor is not in (0.0, 1.0]
         */
        public Shard(Map<String, String> props) {
            double parsedLoadFactor = PropertyParser.parseDouble(props, "excalibur.shard.segment-load-factor", DEFAULT_LOAD_FACTOR);
            // Domain correctness: load factor must be in (0.0, 1.0]
            // - 0 or negative: table would never split (broken behavior)
            // - > 1.0: impossible for open addressing (can't exceed 100% fill)
            if (Double.isNaN(parsedLoadFactor) || parsedLoadFactor <= 0.0 || parsedLoadFactor > 1.0) {
                throw new IllegalArgumentException(
                    "loadFactor must be in (0.0, 1.0], got: " + parsedLoadFactor);
            }
            this.loadFactor = parsedLoadFactor;

            int parsedEfConstruction = PropertyParser.parseInt(props, "excalibur.shard.default-ef-construction", DEFAULT_EF_CONSTRUCTION);
            if (parsedEfConstruction < 1) {
                throw new IllegalArgumentException(
                    "efConstruction must be at least 1, got: " + parsedEfConstruction);
            }
            this.efConstruction = parsedEfConstruction;

            int parsedEfSearch = PropertyParser.parseInt(props, "excalibur.shard.default-ef-search", DEFAULT_EF_SEARCH);
            if (parsedEfSearch < 1) {
                throw new IllegalArgumentException(
                    "efSearch must be at least 1, got: " + parsedEfSearch);
            }
            this.efSearch = parsedEfSearch;

            int parsedCompactDelayMs = PropertyParser.parseInt(props, "excalibur.shard.compaction-delay-ms", DEFAULT_COMPACT_DELAY_MS);
            if (parsedCompactDelayMs < 0) {
                throw new IllegalArgumentException(
                    "compactDelayMs cannot be negative, got: " + parsedCompactDelayMs);
            }
            this.compactDelayMs = parsedCompactDelayMs;

            int parsedHnswQueueLimit = PropertyParser.parseInt(props, "excalibur.shard.hnsw-integration-queue-depth-limit", DEFAULT_HNSW_QUEUE_LIMIT);
            if (parsedHnswQueueLimit < 1) {
                throw new IllegalArgumentException(
                    "hnswQueueLimit must be at least 1, got: " + parsedHnswQueueLimit);
            }
            this.hnswQueueLimit = parsedHnswQueueLimit;
        }

        /**
         * Gets the segment load factor.
         */
        public double getLoadFactor() {
            return loadFactor;
        }

        /**
         * Gets the efConstruction parameter.
         */
        public int getEfConstruction() {
            return efConstruction;
        }

        /**
         * Gets the efSearch parameter.
         */
        public int getEfSearch() {
            return efSearch;
        }

        /**
         * Gets the compaction delay.
         */
        public int getCompactDelayMs() {
            return compactDelayMs;
        }

        /**
         * Gets the HNSW queue limit.
         */
        public int getHnswQueueLimit() {
            return hnswQueueLimit;
        }

        @Override
        public String toString() {
            return "Shard{loadFactor=" + loadFactor + ", efConstruction=" + efConstruction +
                   ", efSearch=" + efSearch + ", compactDelayMs=" + compactDelayMs +
                   ", hnswQueueLimit=" + hnswQueueLimit + '}';
        }
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

    /**
     * Graph-RAG configuration.
     *
     * <p>Everything {@code POST /v1/graph/query} needs that is not on disk beside the graph
     * itself: where to find it, how much evidence an answer requires, and how much context the
     * model is given.
     *
     * <h2>Thread Safety</h2>
     * <p>Immutable and thread-safe.</p>
     */
    public static final class Graph {

        /** Smallest fused score any candidate must reach before an answer is attempted. */
        public static final double DEFAULT_MIN_SCORE = 0.005;

        /** Smallest gap between the best and second candidate before the result is ambiguous. */
        public static final double DEFAULT_MIN_SEPARATION = 0.001;

        /** Smallest mean confidence across the surviving evidence. */
        public static final double DEFAULT_MIN_MEAN_CONFIDENCE = 0.3;

        /** How many equally-plausible candidates are reported back rather than chosen between. */
        public static final int DEFAULT_MAX_AMBIGUOUS_CANDIDATES = 5;

        /** Tokens of serialised subgraph an answer may carry. */
        public static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 2000;

        /** Documents each retrieval signal contributes before fusion. */
        public static final int DEFAULT_TOP_K = 10;

        /** Lucene RAM buffer, in megabytes, for the read-only indexes. */
        public static final double DEFAULT_RAM_BUFFER_MB = 16.0;

        /**
         * Whether ingestion extracts facts by default.
         *
         * <p>Off. Extraction is one chat-model call per document, which is the most expensive
         * thing ingestion can do, and a deployment that wants vector search should not start
         * paying for a graph it never asked for.
         */
        public static final boolean DEFAULT_EXTRACTION_ENABLED = false;

        /** Extractor version recorded on facts when none is configured. */
        public static final int DEFAULT_EXTRACTOR_VERSION = 1;

        /**
         * Whether a build embeds each entity's text.
         *
         * <p>On, because it is what lets retrieval rerank against real vectors rather than stop at
         * term overlap - and it only happens at all when an embedding endpoint is configured. It
         * costs one embedding call per batch of entities at build time, which is why it can be
         * turned off for a deployment that wants the graph and not that cost.
         */
        public static final boolean DEFAULT_ENTITY_VECTORS_ENABLED = true;

        /**
         * How many namespaces' graphs stay open at once.
         *
         * <p>A served store is thirteen memory mappings. The mapping budget is the hard bound;
         * this is the count bound beside it, so a node with many small tenants does not hold every
         * one of them open merely because none is individually expensive.
         */
        public static final int DEFAULT_MAX_OPEN_NAMESPACES = 16;

        /**
         * Tombstoned fraction of the store at which erasure compaction becomes worthwhile.
         *
         * <p>A compaction rewrites the whole store, so running one per request would make erasure
         * a way to keep a node permanently busy. Waiting until a fifth of the edges are tombstoned
         * amortises it.
         */
        public static final double DEFAULT_ERASURE_TOMBSTONE_RATIO = 0.2;

        /**
         * Longest an erasure obligation may wait, whatever has accumulated.
         *
         * <p>The ratio alone would let a single request sit indefinitely on a large store that
         * never reaches the threshold. An obligation to erase is not a performance tuning knob;
         * this is the bound that makes it a promise.
         */
        public static final long DEFAULT_ERASURE_MAX_LATENCY_SECONDS = 3600L;

        private final String path;
        private final double minScore;
        private final double minSeparation;
        private final double minMeanConfidence;
        private final int maxAmbiguousCandidates;
        private final int contextTokenBudget;
        private final int topK;
        private final double ramBufferMb;
        private final boolean extractionEnabled;
        private final int extractorVersion;
        private final List<String> singleValuedRelations;
        private final boolean entityVectorsEnabled;
        private final int maxOpenNamespaces;
        private final List<String> hotNamespaces;
        private final List<String> coldNamespaces;
        private final double erasureTombstoneRatio;
        private final long erasureMaxLatencySeconds;

        /** A comma-separated key read as a list, trimmed, with empty entries dropped. */
        private static List<String> parseList(Map<String, String> props, String key) {
            return Arrays.stream(PropertyParser.parseString(props, key, "").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        }

        /**
         * Creates a Graph config from properties.
         *
         * @param props The properties map
         * @throws IllegalArgumentException if a threshold is outside its domain
         */
        public Graph(Map<String, String> props) {
            this.path = PropertyParser.parseString(props, "excalibur.graph.path", null);
            this.extractionEnabled = PropertyParser.parseBoolean(
                props, "excalibur.graph.extraction-enabled", DEFAULT_EXTRACTION_ENABLED);
            this.extractorVersion = PropertyParser.parseInt(
                props, "excalibur.graph.extractor-version", DEFAULT_EXTRACTOR_VERSION);
            this.entityVectorsEnabled = PropertyParser.parseBoolean(
                props, "excalibur.graph.entity-vectors-enabled", DEFAULT_ENTITY_VECTORS_ENABLED);
            this.maxOpenNamespaces = requirePositive(PropertyParser.parseInt(
                props, "excalibur.graph.max-open-namespaces", DEFAULT_MAX_OPEN_NAMESPACES),
                "max-open-namespaces");
            this.erasureTombstoneRatio = requireInUnitRange(PropertyParser.parseDouble(
                props, "excalibur.graph.erasure-tombstone-ratio",
                DEFAULT_ERASURE_TOMBSTONE_RATIO), "erasure-tombstone-ratio");
            this.erasureMaxLatencySeconds = PropertyParser.parseLong(
                props, "excalibur.graph.erasure-max-latency-seconds",
                DEFAULT_ERASURE_MAX_LATENCY_SECONDS);
            if (erasureMaxLatencySeconds <= 0) {
                throw new IllegalArgumentException(
                    "excalibur.graph.erasure-max-latency-seconds must be positive, got: "
                        + erasureMaxLatencySeconds);
            }
            if (extractorVersion < Short.MIN_VALUE || extractorVersion > Short.MAX_VALUE) {
                throw new IllegalArgumentException(
                    "excalibur.graph.extractor-version must fit in a short, got "
                        + extractorVersion);
            }
            // Relations that hold one value at a time. Empty by default, because closing an
            // interval destroys information: a relation wrongly left multi-valued returns a stale
            // extra answer, while one wrongly superseded silently deletes a true fact.
            this.singleValuedRelations =
                parseList(props, "excalibur.graph.single-valued-relations");
            // Which namespaces resist eviction and which give way first. Both empty by default,
            // which is the shipped policy: the shared index pinned, everything else evicted by
            // recency. The names are validated where they become namespace identifiers, not
            // here - this section does not depend on the tenancy module.
            this.hotNamespaces = parseList(props, "excalibur.graph.hot-namespaces");
            this.coldNamespaces = parseList(props, "excalibur.graph.cold-namespaces");
            this.minScore = requireInUnitRange(PropertyParser.parseDouble(
                props, "excalibur.graph.min-score", DEFAULT_MIN_SCORE), "min-score");
            this.minSeparation = requireInUnitRange(PropertyParser.parseDouble(
                props, "excalibur.graph.min-separation", DEFAULT_MIN_SEPARATION),
                "min-separation");
            this.minMeanConfidence = requireInUnitRange(PropertyParser.parseDouble(
                props, "excalibur.graph.min-mean-confidence", DEFAULT_MIN_MEAN_CONFIDENCE),
                "min-mean-confidence");
            // At least two, not at least one: the abstention policy calls a result ambiguous by
            // comparing competing readings, so one candidate is not a value it can work with and
            // it refuses it. Accepting it here deferred the refusal to the graph open, where the
            // same mistake reads as a broken store rather than a setting to correct.
            this.maxAmbiguousCandidates = requireAtLeast(PropertyParser.parseInt(
                props, "excalibur.graph.max-ambiguous-candidates",
                DEFAULT_MAX_AMBIGUOUS_CANDIDATES), 2, "max-ambiguous-candidates");
            this.contextTokenBudget = requirePositive(PropertyParser.parseInt(
                props, "excalibur.graph.context-token-budget", DEFAULT_CONTEXT_TOKEN_BUDGET),
                "context-token-budget");
            this.topK = requirePositive(PropertyParser.parseInt(
                props, "excalibur.graph.top-k", DEFAULT_TOP_K), "top-k");
            double buffer = PropertyParser.parseDouble(
                props, "excalibur.graph.ram-buffer-mb", DEFAULT_RAM_BUFFER_MB);
            if (Double.isNaN(buffer) || buffer <= 0) {
                throw new IllegalArgumentException(
                    "excalibur.graph.ram-buffer-mb must be positive, got: " + buffer);
            }
            this.ramBufferMb = buffer;
        }

        private static double requireInUnitRange(double value, String name) {
            if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException(
                    "excalibur.graph." + name + " must be in [0.0, 1.0], got: " + value);
            }
            return value;
        }

        private static int requirePositive(int value, String name) {
            return requireAtLeast(value, 1, name);
        }

        private static int requireAtLeast(int value, int minimum, String name) {
            if (value < minimum) {
                throw new IllegalArgumentException(
                    "excalibur.graph." + name + " must be at least " + minimum
                        + ", got: " + value);
            }
            return value;
        }

        /** @return the configured graph directory, or null to use the data directory default */
        public String getPath() {
            return path;
        }

        /** @return smallest fused score a candidate must reach */
        public double getMinScore() {
            return minScore;
        }

        /** @return smallest gap between the best two candidates */
        public double getMinSeparation() {
            return minSeparation;
        }

        /** @return smallest mean confidence across the surviving evidence */
        public double getMinMeanConfidence() {
            return minMeanConfidence;
        }

        /** @return how many candidates are reported back when the answer is ambiguous */
        public int getMaxAmbiguousCandidates() {
            return maxAmbiguousCandidates;
        }

        /** @return tokens of serialised subgraph an answer may carry */
        public int getContextTokenBudget() {
            return contextTokenBudget;
        }

        /** @return documents each retrieval signal contributes before fusion */
        public int getTopK() {
            return topK;
        }

        /** @return relations that hold one value at a time, whose new values close the old */
        public List<String> getSingleValuedRelations() {
            return singleValuedRelations;
        }

        /**
         * Namespaces pinned against eviction, alongside the shared index.
         *
         * @return Their names, in the order configured; empty by default
         */
        public List<String> getHotNamespaces() {
            return hotNamespaces;
        }

        /**
         * Namespaces evicted before any other, whatever their recency.
         *
         * @return Their names, in the order configured; empty by default
         */
        public List<String> getColdNamespaces() {
            return coldNamespaces;
        }

        /**
         * Whether ingestion should extract facts from the documents it publishes.
         *
         * @return true when the graph is built from ingested documents
         */
        public boolean isEntityVectorsEnabled() {
            return entityVectorsEnabled;
        }

        /**
         * How many namespaces' graphs stay open at once.
         *
         * @return The bound
         */
        public int getMaxOpenNamespaces() {
            return maxOpenNamespaces;
        }

        /**
         * Tombstoned fraction of the store at which an erasure compaction becomes worthwhile.
         *
         * @return The ratio, in [0, 1]
         */
        public double getErasureTombstoneRatio() {
            return erasureTombstoneRatio;
        }

        /**
         * Longest an erasure obligation may wait before a compaction runs regardless.
         *
         * @return The bound in seconds
         */
        public long getErasureMaxLatencySeconds() {
            return erasureMaxLatencySeconds;
        }

        /**
         * Tombstoned fraction of the store at which an erasure compaction becomes worthwhile.
         *
         * @return The ratio, in [0, 1]
         */
        public boolean isExtractionEnabled() {
            return extractionEnabled;
        }

        /**
         * The version recorded on every fact this node extracts.
         *
         * @return The extractor version
         */
        public int getExtractorVersion() {
            return extractorVersion;
        }

        public double getRamBufferMb() {
            return ramBufferMb;
        }

        @Override
        public String toString() {
            return "Graph{path=" + (path != null ? path : "<data dir>/graph")
                + ", minScore=" + minScore
                + ", minSeparation=" + minSeparation
                + ", minMeanConfidence=" + minMeanConfidence
                + ", maxAmbiguousCandidates=" + maxAmbiguousCandidates
                + ", contextTokenBudget=" + contextTokenBudget
                + ", topK=" + topK + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Graph other = (Graph) o;
            return Double.compare(minScore, other.minScore) == 0
                && Double.compare(minSeparation, other.minSeparation) == 0
                && Double.compare(minMeanConfidence, other.minMeanConfidence) == 0
                && maxAmbiguousCandidates == other.maxAmbiguousCandidates
                && contextTokenBudget == other.contextTokenBudget
                && topK == other.topK
                && Double.compare(ramBufferMb, other.ramBufferMb) == 0
                && extractionEnabled == other.extractionEnabled
                && extractorVersion == other.extractorVersion
                && entityVectorsEnabled == other.entityVectorsEnabled
                && maxOpenNamespaces == other.maxOpenNamespaces
                && Double.compare(erasureTombstoneRatio, other.erasureTombstoneRatio) == 0
                && erasureMaxLatencySeconds == other.erasureMaxLatencySeconds
                && Objects.equals(singleValuedRelations, other.singleValuedRelations)
                && Objects.equals(hotNamespaces, other.hotNamespaces)
                && Objects.equals(coldNamespaces, other.coldNamespaces)
                && Objects.equals(path, other.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, minScore, minSeparation, minMeanConfidence,
                maxAmbiguousCandidates, contextTokenBudget, topK, ramBufferMb,
                extractionEnabled, extractorVersion, entityVectorsEnabled, maxOpenNamespaces,
                erasureTombstoneRatio, erasureMaxLatencySeconds, singleValuedRelations,
                hotNamespaces, coldNamespaces);
        }
    }
}
