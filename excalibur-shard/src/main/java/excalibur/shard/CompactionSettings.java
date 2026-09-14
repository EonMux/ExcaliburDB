package excalibur.shard;

import excalibur.shard.worker.HnswCompactionWorker;
import io.excalibur.config.Config;
import io.excalibur.store.CompactionWorker;

import java.util.Objects;

/**
 * When compaction runs and what it considers worth rewriting.
 *
 * <p>Two workers reclaim dead space: {@link CompactionWorker} rewrites segment files to drop
 * tombstoned entries, and {@link HnswCompactionWorker} rebuilds vector graphs to drop tombstoned
 * nodes. Both are built once and never reconfigured, so their policy belongs in the arguments
 * they are constructed from rather than in setters called afterwards. This record is that
 * policy, carried from configuration to construction in one piece.</p>
 *
 * <p>{@code excalibur-store} has no configuration dependency by design, which is why the
 * translation from property keys to worker arguments lives here.</p>
 *
 * @param segmentIntervalMs            how long the segment compaction loop sleeps between sweeps
 * @param segmentTombstoneRatio        tombstoned fraction of a segment at which a rewrite repays
 *                                     itself
 * @param segmentWriteLogBytes         write log size at which a segment is rewritten regardless
 *                                     of its tombstone ratio
 * @param maxConcurrentSegmentCompactions how many segment rewrites may run in one sweep
 * @param graphIntervalMs              how long the HNSW compaction loop sleeps between sweeps
 * @param graphTombstoneRatio          tombstoned fraction of a graph at which it is rebuilt
 */
public record CompactionSettings(
    long segmentIntervalMs,
    double segmentTombstoneRatio,
    long segmentWriteLogBytes,
    int maxConcurrentSegmentCompactions,
    long graphIntervalMs,
    double graphTombstoneRatio
) {

    /** Bytes in a mebibyte, the unit the write log threshold is configured in. */
    private static final long BYTES_PER_MIB = 1024L * 1024L;

    /**
     * The policy both workers have always applied when nothing says otherwise.
     */
    public static final CompactionSettings DEFAULTS = new CompactionSettings(
        CompactionWorker.DEFAULT_COMPACTION_INTERVAL_MS,
        CompactionWorker.DEFAULT_TOMBSTONE_RATIO_THRESHOLD,
        CompactionWorker.DEFAULT_WRITE_LOG_SIZE_THRESHOLD,
        CompactionWorker.DEFAULT_MAX_CONCURRENT_COMPACTIONS,
        HnswCompactionWorker.DEFAULT_INTERVAL_MS,
        HnswCompactionWorker.DEFAULT_TOMBSTONE_THRESHOLD);

    /**
     * Reads the policy out of a shard configuration.
     *
     * <p>The configuration layer accepts a wider range than either worker does - it knows the
     * keys are intervals and ratios, not which worker enforces them. A value the worker will
     * refuse is reported here against the property name that carried it, because
     * "hnsw-compaction-interval-ms is below the 10s minimum" is actionable and
     * "Interval must be at least 10000ms" from a constructor three frames down is not.</p>
     *
     * @param shard The shard configuration section
     * @return The policy the workers should be built with
     * @throws NullPointerException     if shard is null
     * @throws IllegalArgumentException if a configured value is outside the range its worker
     *                                  accepts
     */
    public static CompactionSettings fromConfig(Config.Shard shard) {
        Objects.requireNonNull(shard, "shard config cannot be null");

        long graphIntervalMs = shard.getHnswCompactionIntervalMs();
        if (graphIntervalMs < HnswCompactionWorker.MIN_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "excalibur.shard.hnsw-compaction-interval-ms must be at least "
                    + HnswCompactionWorker.MIN_INTERVAL_MS + "ms, got: " + graphIntervalMs);
        }

        double graphRatio = shard.getHnswCompactionTombstoneRatio();
        if (graphRatio < HnswCompactionWorker.MIN_TOMBSTONE_THRESHOLD
            || graphRatio > HnswCompactionWorker.MAX_TOMBSTONE_THRESHOLD) {
            throw new IllegalArgumentException(
                "excalibur.shard.hnsw-compaction-tombstone-ratio must be between "
                    + HnswCompactionWorker.MIN_TOMBSTONE_THRESHOLD + " and "
                    + HnswCompactionWorker.MAX_TOMBSTONE_THRESHOLD + ", got: " + graphRatio);
        }

        long writeLogBytes = shard.getCompactionWriteLogMb() * BYTES_PER_MIB;
        if (writeLogBytes > CompactionWorker.MAX_WRITE_LOG_SIZE_THRESHOLD) {
            throw new IllegalArgumentException(
                "excalibur.shard.compaction-write-log-mb must be at most "
                    + (CompactionWorker.MAX_WRITE_LOG_SIZE_THRESHOLD / BYTES_PER_MIB)
                    + ", got: " + shard.getCompactionWriteLogMb());
        }

        int maxConcurrent = shard.getCompactionMaxConcurrent();
        if (maxConcurrent > CompactionWorker.MAX_CONCURRENT_COMPACTIONS) {
            throw new IllegalArgumentException(
                "excalibur.shard.compaction-max-concurrent must be at most "
                    + CompactionWorker.MAX_CONCURRENT_COMPACTIONS + ", got: " + maxConcurrent);
        }

        return new CompactionSettings(
            shard.getCompactionIntervalMs(),
            shard.getCompactionTombstoneRatio(),
            writeLogBytes,
            maxConcurrent,
            graphIntervalMs,
            graphRatio);
    }

    /**
     * Builds an HNSW compaction worker applying this policy.
     *
     * @return A worker configured with the graph interval and ratio
     */
    public HnswCompactionWorker newGraphWorker() {
        return new HnswCompactionWorker(graphIntervalMs, graphTombstoneRatio);
    }
}
