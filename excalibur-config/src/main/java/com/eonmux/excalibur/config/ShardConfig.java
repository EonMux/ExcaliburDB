package com.eonmux.excalibur.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shard management configuration.
 *
 * <p>This class is IMMUTABLE and thread-safe.</p>
 */
public final class ShardConfig {

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

    /** Neighbours an HNSW node keeps above layer 0. */
    public static final int DEFAULT_MAX_CONNECTIONS = 16;

    /** Layer 0 neighbours, which carry the exhaustive part of the walk. Zero means 2*M. */
    public static final int DEFAULT_MAX_CONNECTIONS_LAYER0 = 0;

    /** Most neighbours a node may keep above layer 0, the graph format's own ceiling. */
    public static final int MAX_MAX_CONNECTIONS = 256;

    /** Most neighbours a node may keep at layer 0. */
    public static final int MAX_MAX_CONNECTIONS_LAYER0 = 512;

    /** How often the segment compactor looks for work. */
    public static final long DEFAULT_COMPACTION_INTERVAL_MS = 5000L;

    /**
     * How often the index registry is reconciled with the storage engine's directory.
     *
     * <p>The sweep is what notices a segment the engine created without announcing it, so
     * this period is the longest a new segment can stay unsearchable. The floor the sweep
     * itself enforces is higher than this module's, and is checked where the sweep is
     * built so the refusal can name this key.</p>
     */
    public static final long DEFAULT_SEGMENT_SYNC_INTERVAL_MS = 1000L;

    /** Tombstoned fraction of a segment at which a rewrite repays itself. */
    public static final double DEFAULT_COMPACTION_TOMBSTONE_RATIO = 0.2;

    /** Write log size at which a segment is compacted whatever its tombstone ratio. */
    public static final int DEFAULT_COMPACTION_WRITE_LOG_MB = 32;

    /** Segments compacted at once. */
    public static final int DEFAULT_COMPACTION_MAX_CONCURRENT = 4;

    /** How often the HNSW compactor looks for work. */
    public static final long DEFAULT_HNSW_COMPACTION_INTERVAL_MS = 60000L;

    /** Deleted fraction of a graph at which its nodes are worth renumbering. */
    public static final double DEFAULT_HNSW_COMPACTION_TOMBSTONE_RATIO = 0.2;

    /** Hash tables in a segment's LSH index. */
    public static final int DEFAULT_LSH_HASH_TABLES = 15;

    /** Hash functions concatenated into one LSH bucket key. */
    public static final int DEFAULT_LSH_HASH_FUNCTIONS = 16;

    /** Neighbouring LSH buckets probed beside the query's own. */
    public static final int DEFAULT_LSH_PROBES = 3;

    /**
     * Most hash functions one LSH bucket key may concatenate.
     *
     * <p>The key is packed into a long, one bit per function, which is where the 63 comes
     * from. It is the index format's own ceiling, restated here because this module has no
     * dependency on the one that enforces it - and a value the index will refuse has to be
     * refused at startup, against the property that carried it, rather than turning every
     * segment into a warning about its on-disk shape.</p>
     */
    public static final int MAX_LSH_HASH_FUNCTIONS = 63;

    /**
     * Most hash tables one segment's LSH index may hold.
     *
     * <p>Each table allocates {@code hashFunctions * dimensions} floats of hyperplanes per
     * segment, so this number multiplies the two beside it and then multiplies again by
     * every segment on the node. Unbounded, a mistyped value asks for terabytes and the
     * resulting OutOfMemoryError kills an ingestion thread rather than the startup that
     * caused it.</p>
     */
    public static final int MAX_LSH_HASH_TABLES = 256;

    /** Most neighbouring buckets one query may probe beside its own. */
    public static final int MAX_LSH_PROBES = 64;

    /** Whether a segment's product quantiser answers the first pass of a search. */
    public static final boolean DEFAULT_PQ_SEARCH_ENABLED = false;

    /** Candidates the quantised pass collects per result, for exact rescoring. */
    public static final int DEFAULT_PQ_OVERSAMPLE = 4;

    /** Subspaces a vector is split into before each part is quantised on its own. */
    public static final int DEFAULT_PQ_SUBSPACES = 8;

    /**
     * Bounds the second validation pass holds the shard section to.
     *
     * <p>Narrower than the parse-time checks on purpose. The constructor refuses a value the
     * type cannot carry - a load factor outside {@code (0, 1]} is not a load factor at all -
     * while these are the range the engine is known to behave in. They live here, beside the
     * keys they govern, because a bound written as a literal at the validator is a bound no
     * test can name and no reader can find.</p>
     */
    public static final double MIN_LOAD_FACTOR = 0.5;

    /** @see #MIN_LOAD_FACTOR */
    public static final double MAX_LOAD_FACTOR = 0.9;

    /** @see #MIN_LOAD_FACTOR */
    public static final int MIN_EF_CONSTRUCTION = 16;

    /** @see #MIN_LOAD_FACTOR */
    public static final int MAX_EF_CONSTRUCTION = 1024;

    /** @see #MIN_LOAD_FACTOR */
    public static final int MIN_EF_SEARCH = 8;

    /** @see #MIN_LOAD_FACTOR */
    public static final int MAX_EF_SEARCH = 1024;

    /**
     * Fewest entries the HNSW integration queue may be allowed to hold.
     *
     * <p>Mirrors the window {@code HnswIntegrationWorker} enforces. Checking it here turns a
     * misconfiguration into a startup failure naming the property, rather than a refusal from
     * a worker constructor several frames down.</p>
     */
    public static final int MIN_HNSW_QUEUE_DEPTH_LIMIT = 10_000;

    /** @see #MIN_HNSW_QUEUE_DEPTH_LIMIT */
    public static final int MAX_HNSW_QUEUE_DEPTH_LIMIT = 1_000_000;

    /** Fewest subspaces the quantiser encodes: the whole vector as one part. */
    public static final int MIN_PQ_SUBSPACES = 1;

    /** Most subspaces the quantiser encodes. */
    public static final int MAX_PQ_SUBSPACES = 128;

    /** Centroids each subspace is clustered to, one code byte per subspace. */
    public static final int DEFAULT_PQ_CENTROIDS = 256;

    /** Fewest centroids worth clustering to: below two every vector encodes alike. */
    public static final int MIN_PQ_CENTROIDS = 2;

    /** Most centroids a one-byte code can address. */
    public static final int MAX_PQ_CENTROIDS = 256;

    /** Vectors the codebook training samples from a segment, or 0 for all of them. */
    public static final int DEFAULT_PQ_TRAINING_SAMPLES = 100_000;

    // All fields final for immutability
    private final double loadFactor;
    private final int efConstruction;
    private final int efSearch;
    private final int compactDelayMs;
    private final int hnswQueueLimit;
    private final int maxConnections;
    private final int maxConnectionsLayer0;
    private final long compactionIntervalMs;
    private final long segmentSyncIntervalMs;
    private final double compactionTombstoneRatio;
    private final int compactionWriteLogMb;
    private final int compactionMaxConcurrent;
    private final long hnswCompactionIntervalMs;
    private final double hnswCompactionTombstoneRatio;
    private final int lshHashTables;
    private final int lshHashFunctions;
    private final int lshProbes;
    private final List<String> volumes;
    private final boolean pqSearchEnabled;
    private final int pqOversample;
    private final int pqSubspaces;
    private final int pqCentroids;
    private final int pqTrainingSamples;

    /**
     * Creates a Shard config from properties.
     *
     * @param props The properties map
     * @throws IllegalArgumentException if loadFactor is not in (0.0, 1.0]
     */
    public ShardConfig(Map<String, String> props) {
        double parsedLoadFactor = PropertyParser.parseDouble(props, "excalibur.shard.segment-load-factor", DEFAULT_LOAD_FACTOR);
        // Domain correctness: load factor must be in (0.0, 1.0]
        // - 0 or negative: table would never split (broken behavior)
        // - > 1.0: impossible for open addressing (can't exceed 100% fill)
        if (parsedLoadFactor <= 0.0 || parsedLoadFactor > 1.0) {
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

        // The ceilings are the graph format's, not a preference: a wider node than this is
        // refused when the first graph is built. Checked here so that refusal is a startup
        // message about a setting rather than a failure on the first document indexed.
        this.maxConnections = ConfigBounds.inRange(props,
            "excalibur.shard.default-max-connections",
            DEFAULT_MAX_CONNECTIONS, 2, MAX_MAX_CONNECTIONS);
        int parsedLayer0 = PropertyParser.parseInt(props,
            "excalibur.shard.default-max-connections-layer0",
            DEFAULT_MAX_CONNECTIONS_LAYER0);
        if (parsedLayer0 > MAX_MAX_CONNECTIONS_LAYER0) {
            throw new IllegalArgumentException(
                "excalibur.shard.default-max-connections-layer0 must be at most "
                    + MAX_MAX_CONNECTIONS_LAYER0 + ", got: " + parsedLayer0);
        }
        if (parsedLayer0 != 0 && parsedLayer0 < maxConnections) {
            // Layer 0 is where the walk becomes exhaustive, so it is given at least as many
            // neighbours as the layers above it. A smaller value is a typo rather than a
            // tuning choice, and it degrades recall at the layer that decides the answer.
            throw new IllegalArgumentException(
                "excalibur.shard.default-max-connections-layer0 must be 0, meaning twice "
                    + "excalibur.shard.default-max-connections, or at least that value ("
                    + maxConnections + "), got: " + parsedLayer0);
        }
        this.maxConnectionsLayer0 = parsedLayer0 == 0
            ? Math.min(maxConnections * 2, MAX_MAX_CONNECTIONS_LAYER0)
            : parsedLayer0;

        this.compactionIntervalMs = ConfigBounds.atLeastLong(props,
            "excalibur.shard.compaction-interval-ms", DEFAULT_COMPACTION_INTERVAL_MS, 100L);
        this.segmentSyncIntervalMs = ConfigBounds.atLeastLong(props,
            "excalibur.shard.segment-sync-interval-ms",
            DEFAULT_SEGMENT_SYNC_INTERVAL_MS, 1L);
        this.compactionTombstoneRatio = ConfigBounds.unitRatio(props,
            "excalibur.shard.compaction-tombstone-ratio",
            DEFAULT_COMPACTION_TOMBSTONE_RATIO);
        this.compactionWriteLogMb = ConfigBounds.atLeast(props,
            "excalibur.shard.compaction-write-log-mb", DEFAULT_COMPACTION_WRITE_LOG_MB, 1);
        this.compactionMaxConcurrent = ConfigBounds.atLeast(props,
            "excalibur.shard.compaction-max-concurrent",
            DEFAULT_COMPACTION_MAX_CONCURRENT, 1);
        this.hnswCompactionIntervalMs = ConfigBounds.atLeastLong(props,
            "excalibur.shard.hnsw-compaction-interval-ms",
            DEFAULT_HNSW_COMPACTION_INTERVAL_MS, 100L);
        this.hnswCompactionTombstoneRatio = ConfigBounds.unitRatio(props,
            "excalibur.shard.hnsw-compaction-tombstone-ratio",
            DEFAULT_HNSW_COMPACTION_TOMBSTONE_RATIO);

        this.lshHashTables = ConfigBounds.inRange(props,
            "excalibur.shard.lsh-hash-tables",
            DEFAULT_LSH_HASH_TABLES, 1, MAX_LSH_HASH_TABLES);
        this.lshHashFunctions = ConfigBounds.inRange(props,
            "excalibur.shard.lsh-hash-functions",
            DEFAULT_LSH_HASH_FUNCTIONS, 1, MAX_LSH_HASH_FUNCTIONS);
        this.lshProbes = ConfigBounds.inRange(props, "excalibur.shard.lsh-probes",
            DEFAULT_LSH_PROBES, 1, MAX_LSH_PROBES);

        this.volumes = ConfigBounds.parseList(props, "excalibur.shard.volumes");

        this.pqSearchEnabled = PropertyParser.parseBoolean(props,
            "excalibur.shard.pq-search-enabled", DEFAULT_PQ_SEARCH_ENABLED);
        this.pqOversample = ConfigBounds.atLeast(props, "excalibur.shard.pq-oversample",
            DEFAULT_PQ_OVERSAMPLE, 1);
        this.pqSubspaces = ConfigBounds.inRange(props, "excalibur.shard.pq-subspaces",
            DEFAULT_PQ_SUBSPACES, MIN_PQ_SUBSPACES, MAX_PQ_SUBSPACES);
        this.pqCentroids = ConfigBounds.inRange(props, "excalibur.shard.pq-centroids",
            DEFAULT_PQ_CENTROIDS, MIN_PQ_CENTROIDS, MAX_PQ_CENTROIDS);
        this.pqTrainingSamples = ConfigBounds.atLeast(props,
            "excalibur.shard.pq-training-samples", DEFAULT_PQ_TRAINING_SAMPLES, 0);

        // K-means cannot produce more clusters than it has points. A ceiling below the
        // codebook size leaves centroids that no vector ever lands on, and every code
        // that points at one is a distance the search computes against nothing.
        if (pqTrainingSamples > 0 && pqTrainingSamples < pqCentroids) {
            throw new IllegalArgumentException(
                "excalibur.shard.pq-training-samples is " + pqTrainingSamples
                    + " and must be at least excalibur.shard.pq-centroids ("
                    + pqCentroids + "), or 0 to train on every vector: k-means cannot fill "
                    + "a codebook from fewer samples than it has centroids");
        }
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

    /**
     * Neighbours an HNSW node keeps above layer 0, the graph's {@code M}.
     *
     * <p>More neighbours mean better recall, a larger graph file and a slower build. The
     * value is written into each graph's header when it is created, and a graph already on
     * disk keeps the value it was built with: changing this affects new graphs only.</p>
     *
     * @return the connection bound, at least 2
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * Neighbours an HNSW node keeps at layer 0, where the walk becomes exhaustive.
     *
     * @return the layer 0 bound, resolved to twice {@link #getMaxConnections()} by default
     */
    public int getMaxConnectionsLayer0() {
        return maxConnectionsLayer0;
    }

    /** @return how often the segment compactor looks for work, in milliseconds */
    public long getCompactionIntervalMs() {
        return compactionIntervalMs;
    }

    /** @return how often the index registry is reconciled with disk, in milliseconds */
    public long getSegmentSyncIntervalMs() {
        return segmentSyncIntervalMs;
    }

    /** @return tombstoned fraction of a segment at which a rewrite repays itself */
    public double getCompactionTombstoneRatio() {
        return compactionTombstoneRatio;
    }

    /** @return write log size, in MiB, that triggers a compaction on its own */
    public int getCompactionWriteLogMb() {
        return compactionWriteLogMb;
    }

    /** @return how many segments are compacted at once */
    public int getCompactionMaxConcurrent() {
        return compactionMaxConcurrent;
    }

    /** @return how often the HNSW compactor looks for work, in milliseconds */
    public long getHnswCompactionIntervalMs() {
        return hnswCompactionIntervalMs;
    }

    /** @return deleted fraction of a graph at which its nodes are worth renumbering */
    public double getHnswCompactionTombstoneRatio() {
        return hnswCompactionTombstoneRatio;
    }

    /** @return hash tables in a segment's LSH index */
    public int getLshHashTables() {
        return lshHashTables;
    }

    /** @return hash functions concatenated into one LSH bucket key */
    public int getLshHashFunctions() {
        return lshHashFunctions;
    }

    /** @return neighbouring LSH buckets probed beside the query's own */
    public int getLshProbes() {
        return lshProbes;
    }

    /**
     * Directories segments may be placed across, beyond the data directory.
     *
     * <p>Empty, the default, keeps every segment in the data directory. Naming volumes
     * spreads new segments over them and the data directory together, by a consistent hash
     * of the segment id, so a volume added later moves as few existing segments as
     * possible. Every named volume has to be present at startup, and stay present: a
     * segment placed on a volume that is not mounted on the next start is a segment the
     * node cannot read.</p>
     *
     * @return the configured volume paths, in the order given, possibly empty
     */
    public List<String> getVolumes() {
        return volumes;
    }

    /**
     * Whether a segment's product quantiser answers the first pass of a vector search.
     *
     * <p>Off by default. On, a search scores candidates from their quantised codes, which
     * is several times cheaper per candidate and less accurate, then rescores the
     * survivors against their full-precision vectors so the ranking it returns is exact.
     * What the approximation can still cost is a document the first pass never shortlisted;
     * {@link #getPqOversample()} is what buys that back.</p>
     *
     * @return true when the quantised first pass is used
     */
    public boolean isPqSearchEnabled() {
        return pqSearchEnabled;
    }

    /** @return candidates the quantised pass collects per result it is asked for */
    public int getPqOversample() {
        return pqOversample;
    }

    /**
     * Subspaces a vector is split into before each part is quantised on its own.
     *
     * <p>The vector's dimensions are divided evenly between the subspaces, and each one
     * contributes a byte to the code. More subspaces means a longer code, a larger
     * codebook and better recall; fewer means a smaller index and a faster first pass.
     * High-dimensional embeddings need more of them to keep each subspace small enough
     * for {@link #getPqCentroids()} clusters to describe.</p>
     *
     * @return how many parts each vector is quantised in
     */
    public int getPqSubspaces() {
        return pqSubspaces;
    }

    /**
     * Centroids each subspace is clustered to.
     *
     * <p>A subspace's code is the index of its nearest centroid, so this is the alphabet
     * the code is written in. Fewer centroids quantise more coarsely and train faster.</p>
     *
     * @return the codebook size per subspace
     */
    public int getPqCentroids() {
        return pqCentroids;
    }

    /**
     * Vectors the codebook training samples from a segment, or 0 for all of them.
     *
     * <p>Training runs while a segment is warming and nothing may query it, so this is the
     * knob that bounds how long that lasts. Sampling costs recall only where the sample
     * stops representing the segment.</p>
     *
     * @return the training sample ceiling, 0 meaning every vector
     */
    public int getPqTrainingSamples() {
        return pqTrainingSamples;
    }

    @Override
    public String toString() {
        return "Shard{loadFactor=" + loadFactor + ", efConstruction=" + efConstruction +
               ", efSearch=" + efSearch + ", compactDelayMs=" + compactDelayMs +
               ", hnswQueueLimit=" + hnswQueueLimit + ", maxConnections=" + maxConnections +
               ", maxConnectionsLayer0=" + maxConnectionsLayer0 +
               ", compactionIntervalMs=" + compactionIntervalMs +
               ", segmentSyncIntervalMs=" + segmentSyncIntervalMs +
               ", compactionTombstoneRatio=" + compactionTombstoneRatio +
               ", compactionWriteLogMb=" + compactionWriteLogMb +
               ", compactionMaxConcurrent=" + compactionMaxConcurrent +
               ", hnswCompactionIntervalMs=" + hnswCompactionIntervalMs +
               ", hnswCompactionTombstoneRatio=" + hnswCompactionTombstoneRatio +
               ", lshHashTables=" + lshHashTables +
               ", lshHashFunctions=" + lshHashFunctions + ", lshProbes=" + lshProbes +
               ", volumes=" + volumes + ", pqSearchEnabled=" + pqSearchEnabled +
               ", pqOversample=" + pqOversample +
               ", pqSubspaces=" + pqSubspaces + ", pqCentroids=" + pqCentroids +
               ", pqTrainingSamples=" + pqTrainingSamples + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ShardConfig other = (ShardConfig) o;
        return Double.compare(loadFactor, other.loadFactor) == 0
            && efConstruction == other.efConstruction
            && efSearch == other.efSearch
            && compactDelayMs == other.compactDelayMs
            && hnswQueueLimit == other.hnswQueueLimit
            && maxConnections == other.maxConnections
            && maxConnectionsLayer0 == other.maxConnectionsLayer0
            && compactionIntervalMs == other.compactionIntervalMs
            && segmentSyncIntervalMs == other.segmentSyncIntervalMs
            && Double.compare(compactionTombstoneRatio, other.compactionTombstoneRatio) == 0
            && compactionWriteLogMb == other.compactionWriteLogMb
            && compactionMaxConcurrent == other.compactionMaxConcurrent
            && hnswCompactionIntervalMs == other.hnswCompactionIntervalMs
            && Double.compare(hnswCompactionTombstoneRatio,
                other.hnswCompactionTombstoneRatio) == 0
            && lshHashTables == other.lshHashTables
            && lshHashFunctions == other.lshHashFunctions
            && lshProbes == other.lshProbes
            && pqSearchEnabled == other.pqSearchEnabled
            && pqOversample == other.pqOversample
            && pqSubspaces == other.pqSubspaces
            && pqCentroids == other.pqCentroids
            && pqTrainingSamples == other.pqTrainingSamples
            && Objects.equals(volumes, other.volumes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(loadFactor, efConstruction, efSearch, compactDelayMs,
            hnswQueueLimit, maxConnections, maxConnectionsLayer0, compactionIntervalMs,
            segmentSyncIntervalMs, compactionTombstoneRatio, compactionWriteLogMb,
            compactionMaxConcurrent, hnswCompactionIntervalMs, hnswCompactionTombstoneRatio,
            lshHashTables, lshHashFunctions, lshProbes, volumes, pqSearchEnabled, pqOversample,
            pqSubspaces, pqCentroids, pqTrainingSamples);
    }
}
