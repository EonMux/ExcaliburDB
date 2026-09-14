package excalibur.api.server;

import excalibur.api.reindex.ReindexCoordinator;
import excalibur.ingest.pipeline.DocumentReindexer;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.ingest.source.SourceResolver;
import excalibur.shard.ReindexOperationHandler;
import excalibur.shard.allocator.VolumeManager;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Executes one scheduled reindex under the shared reindex claim.
 *
 * <p>The scheduler and {@code POST /v1/reindex} are two independent triggers for
 * the same operation. Both take the same per-source claim from
 * {@link ReindexCoordinator}, because two rolling replacements of one source run
 * concurrently will each tombstone the segment the other has just published.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Every field is final and the mutual exclusion that matters - one reindex per
 * source - is the coordinator's, which the scheduler may call this from any thread against.</p>
 *
 * @author ExcaliburDB
 */
final class ScheduledReindexRunner implements ReindexOperationHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledReindexRunner.class);

    private final ReindexCoordinator coordinator;
    private final VolumeManager volumeManager;
    private final StorageEngine storageEngine;
    private final DocumentReindexer reindexer;
    private final SourceResolver sourceResolver;

    /**
     * Creates a runner for the scheduler to call.
     *
     * @param coordinator    The claim both reindex triggers take
     * @param volumeManager  Where a new segment is placed, or null when this node has no
     *                       volume manager and rolling replacement is therefore unavailable
     * @param storageEngine  Holds the segment catalog rolling replacement needs
     * @param reindexer      The reindexer to run
     * @param sourceResolver Resolver for the source instance
     * @throws NullPointerException if any parameter other than volumeManager is null
     */
    ScheduledReindexRunner(ReindexCoordinator coordinator,
                           VolumeManager volumeManager,
                           StorageEngine storageEngine,
                           DocumentReindexer reindexer,
                           SourceResolver sourceResolver) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator cannot be null");
        this.volumeManager = volumeManager;
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        this.reindexer = Objects.requireNonNull(reindexer, "reindexer cannot be null");
        this.sourceResolver =
            Objects.requireNonNull(sourceResolver, "sourceResolver cannot be null");
    }

    @Override
    public boolean executeReindex(String sourceId) {
        ReindexCoordinator.Lease lease = coordinator.tryAcquire(sourceId);
        if (lease == null) {
            LOGGER.info("Scheduled reindex skipped: source {} is already being reindexed", sourceId);
            return false;
        }
        try {
            Source source = sourceResolver.resolve(sourceId);
            if (source == null) {
                LOGGER.warn("ReindexOperationHandler: Source not found: {}", sourceId);
                return false;
            }
            return reindex(sourceId, source);
        } catch (Exception e) {
            LOGGER.error("ReindexOperationHandler failed for source {}: {}", sourceId, e.getMessage());
            return false;
        } finally {
            lease.release();
        }
    }

    /**
     * Reindexes one resolved source, rolling the segments where this node can.
     *
     * @param sourceId The source being reindexed, for the log
     * @param source   The resolved source
     * @return true when the reindex made progress
     * @throws SourceException if the source cannot be read
     */
    private boolean reindex(String sourceId, Source source) throws SourceException {
        // Use rolling replacement if available
        boolean useRollingReplacement = volumeManager != null
            && storageEngine.getCatalog() != null
            && reindexer.hasRollingReplacement();

        if (useRollingReplacement) {
            // Select a volume for the new segment
            Path volumePath = volumeManager.getVolumeWithMostSpace();
            if (volumePath == null) {
                LOGGER.warn("ReindexOperationHandler: No volume available for source: {}", sourceId);
                return false;
            }
            DocumentReindexer.RollingReindexResult rolling =
                reindexer.reindexWithRollingReplacement(source, volumePath);
            // Success if no errors OR if some documents were indexed (partial success)
            return !rolling.hasErrors() || rolling.getDocumentsIndexed() > 0;
        }

        // Fall back to standard reindex
        DocumentReindexer.ReindexResult result = reindexer.reindexAll(source);
        // Success if no errors OR if some documents were indexed (partial success)
        return !result.hasErrors() || result.getDocumentsIndexed() > 0;
    }
}
