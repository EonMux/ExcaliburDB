package excalibur.shard;

import io.excalibur.config.ShardConfig;
import io.excalibur.vector.quantization.PQConfig;

import java.util.List;
import java.util.Objects;

/**
 * Everything a shard's index orchestration reads out of configuration, in one piece.
 *
 * <p>The translation lives beside {@link CompactionSettings} and for the same reason: the
 * modules that consume these values - {@code excalibur-store}, {@code excalibur-vector} -
 * have no configuration dependency by design, so somebody in {@code excalibur-shard} has to
 * turn property keys into constructor arguments. Doing it in a record rather than inline in a
 * factory method means a key that is read but never delivered is visible as an unused
 * component rather than invisible as a line that was never written.</p>
 *
 * <p>That is not hypothetical. {@code compact-delay-ms} was parsed, range-checked, echoed back
 * at startup and delivered nowhere, so an operator who set it to keep compaction off the back
 * of a bulk load got the default schedule and a log line agreeing with them.</p>
 *
 * @param hnswQueueLimit         entries the HNSW integration queue holds before it
 *                               backpressures
 * @param compactionStartDelayMs how long the compaction loop waits before its first check
 * @param segmentSyncIntervalMs  how often the registry is reconciled with the segment directory
 * @param volumes                the directories segments are spread across, possibly empty
 * @param compaction             when compaction runs and what it considers worth rewriting
 * @param pqTraining             the codebook shape every segment on this node trains
 */
record ShardIndexSettings(int hnswQueueLimit,
                          long compactionStartDelayMs,
                          long segmentSyncIntervalMs,
                          List<String> volumes,
                          CompactionSettings compaction,
                          PQConfig pqTraining) {

    /**
     * Reads the settings out of a shard configuration.
     *
     * <p>Compaction is resolved first, because it is the only part that range-checks values
     * against the workers that enforce them and its diagnostics name the property that carried
     * the bad value.</p>
     *
     * @param shard The shard configuration section
     * @return The settings the orchestrator should be built with
     * @throws NullPointerException     if shard is null
     * @throws IllegalArgumentException if a configured value is outside the range its worker
     *                                  accepts
     */
    static ShardIndexSettings fromConfig(ShardConfig shard) {
        Objects.requireNonNull(shard, "shard config cannot be null");

        CompactionSettings compaction = CompactionSettings.fromConfig(shard);
        PQConfig pqTraining = pqTrainingShape(shard);

        return new ShardIndexSettings(
            shard.getHnswQueueLimit(),
            shard.getCompactDelayMs(),
            segmentSyncIntervalMs(shard),
            shard.getVolumes(),
            compaction,
            pqTraining);
    }

    /**
     * Reads how often the registry is reconciled with the storage engine's directory.
     *
     * <p>The configuration layer range-checks this only for shape; the floor that matters is
     * the sweep's own, and it is enforced here so the refusal can name the property that
     * carried the value. "excalibur.shard.segment-sync-interval-ms is below the 250ms minimum"
     * is actionable; the same complaint raised from a scheduler constructor is not.</p>
     *
     * @param shard The shard configuration section
     * @return The period between reconciliation sweeps, in milliseconds
     * @throws IllegalArgumentException if the configured period is below the sweep's minimum
     */
    private static long segmentSyncIntervalMs(ShardConfig shard) {
        long intervalMs = shard.getSegmentSyncIntervalMs();
        if (intervalMs < SegmentSyncScheduler.MIN_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "excalibur.shard.segment-sync-interval-ms must be at least "
                    + SegmentSyncScheduler.MIN_INTERVAL_MS + "ms, got: " + intervalMs);
        }
        return intervalMs;
    }

    /**
     * Translates the configured quantiser shape into the form the warming worker trains to.
     *
     * <p>{@code excalibur-config} cannot see {@link PQConfig}, so it range-checks the three
     * keys against constants of its own; {@code IndexOrchestratorPqConfigTest} is what keeps
     * the two sets from drifting apart.</p>
     *
     * @param shard The shard configuration section
     * @return The codebook shape every segment on this node trains
     */
    private static PQConfig pqTrainingShape(ShardConfig shard) {
        return PQConfig.builder()
            .numSubspaces(shard.getPqSubspaces())
            .numCentroids(shard.getPqCentroids())
            .maxTrainingSamples(shard.getPqTrainingSamples())
            .build();
    }
}
