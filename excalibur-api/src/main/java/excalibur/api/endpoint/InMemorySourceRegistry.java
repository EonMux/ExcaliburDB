package excalibur.api.endpoint;

import excalibur.api.security.ApiSecurityConfig;
import excalibur.ingest.pipeline.IngestionOrchestrator;
import excalibur.ingest.source.Source;
import excalibur.shard.worker.SegmentReindexScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of SourceRegistry.
 *
 * <p>When an IngestionOrchestrator is configured via
 * {@link #setIngestionOrchestrator(IngestionOrchestrator)}, this registry
 * triggers background ingestion when a source is registered.</p>
 *
 * <p>When an IngestionStatusRegistry is configured via
 * {@link #setStatusRegistry(IngestStatusEndpoint.IngestionStatusRegistry)},
 * status entries are automatically created when sources are registered.</p>
 *
 * <p>When a SegmentReindexScheduler is configured via
 * {@link #setReindexScheduler(SegmentReindexScheduler, long)}, sources
 * are automatically registered for periodic reindexing.</p>
 */
public class InMemorySourceRegistry implements SourceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(InMemorySourceRegistry.class);

    private final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();
    private final int maxSources;
    private volatile IngestionOrchestrator ingestionOrchestrator;
    private volatile IngestStatusEndpoint.IngestionStatusRegistry statusRegistry;
    private volatile SegmentReindexScheduler reindexScheduler;
    private volatile long reindexIntervalMs = SegmentReindexScheduler.DEFAULT_REINDEX_INTERVAL_MS;

    /**
     * Creates a registry bounded by the default source limit.
     */
    public InMemorySourceRegistry() {
        this(ApiSecurityConfig.DEFAULT_MAX_SOURCES);
    }

    /**
     * Creates a registry with an explicit bound.
     *
     * <p>The bound matters: every registered source is retained for the process
     * lifetime and an HTTP source pins a client and its selector thread, so an
     * unbounded registry is a resource leak any caller can drive.</p>
     *
     * @param maxSources Maximum number of concurrently registered sources
     * @throws IllegalArgumentException if maxSources is not positive
     */
    public InMemorySourceRegistry(int maxSources) {
        if (maxSources <= 0) {
            throw new IllegalArgumentException("maxSources must be positive: " + maxSources);
        }
        this.maxSources = maxSources;
    }

    /**
     * Returns the bound on concurrently registered sources.
     *
     * <p>Every registered source is retained for the process lifetime and an HTTP source pins a
     * client and its selector thread, so this is the difference between a bounded registry and a
     * resource leak any caller can drive.</p>
     *
     * @return The maximum number of sources
     */
    public int getMaxSources() {
        return maxSources;
    }

    /**
     * Sets the ingestion orchestrator for background processing.
     *
     * @param orchestrator The orchestrator (may be null to disable)
     */
    public void setIngestionOrchestrator(IngestionOrchestrator orchestrator) {
        this.ingestionOrchestrator = orchestrator;
    }

    /**
     * Sets the status registry for tracking ingestion status.
     *
     * <p>When set, this registry will create initial status entries
     * for registered sources with state "idle" and progress 0.0.</p>
     *
     * @param statusRegistry The status registry (may be null to disable)
     */
    public void setStatusRegistry(IngestStatusEndpoint.IngestionStatusRegistry statusRegistry) {
        this.statusRegistry = statusRegistry;
    }

    /**
     * Sets the reindex scheduler for periodic source reindexing.
     *
     * <p>When set, sources registered via {@link #register(Source)} will
     * be automatically registered with the scheduler for periodic reindexing.
     * The scheduler will trigger reindex operations at the configured interval.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Source IDs must be parseable as long integers for scheduler registration</li>
     *   <li>Non-numeric source IDs will be logged as warnings but won't fail registration</li>
     *   <li>The interval must be within scheduler's valid range (1 min to 24 hours)</li>
     * </ul>
     *
     * @param scheduler The reindex scheduler (may be null to disable scheduled reindexing)
     * @param reindexIntervalMs The reindex interval in milliseconds
     */
    public void setReindexScheduler(SegmentReindexScheduler scheduler, long reindexIntervalMs) {
        this.reindexScheduler = scheduler;
        this.reindexIntervalMs = reindexIntervalMs;
    }

    /**
     * Sets the reindex scheduler with default interval.
     *
     * <p>This is a convenience overload that uses the scheduler's default reindex interval.</p>
     *
     * @param scheduler The reindex scheduler (may be null to disable scheduled reindexing)
     */
    public void setReindexScheduler(SegmentReindexScheduler scheduler) {
        this.reindexScheduler = scheduler;
        this.reindexIntervalMs = scheduler != null ? scheduler.getDefaultReindexIntervalMs() :
            SegmentReindexScheduler.DEFAULT_REINDEX_INTERVAL_MS;
    }

    @Override
    public void register(Source source) {
        Objects.requireNonNull(source, "Source cannot be null");
        String sourceId = source.getSourceId();

        // Claim the ID atomically. A separate isRegistered() check would leave a
        // window in which two requests both believe they are the first.
        if (sources.putIfAbsent(sourceId, source) != null) {
            throw new SourceRegistrationException(
                SourceRegistrationException.Reason.DUPLICATE,
                "Source already registered: " + sourceId);
        }
        if (sources.size() > maxSources) {
            sources.remove(sourceId, source);
            throw new SourceRegistrationException(
                SourceRegistrationException.Reason.CAPACITY,
                "Source registry is full (limit " + maxSources + ")");
        }

        // Create initial status entry if status registry is configured
        // This ensures /v1/ingest/status returns data even without orchestrator
        IngestStatusEndpoint.IngestionStatus initialStatus = null;
        if (statusRegistry != null) {
            initialStatus = statusRegistry.getOrCreate(sourceId);
            // Initialize with idle state, will be updated by orchestrator if present
            initialStatus.update("idle", 0.0, 0, 0);
            LOGGER.debug("Created initial status entry for source: {}", sourceId);
        }

        // Register with reindex scheduler for periodic reindexing
        if (reindexScheduler != null) {
            try {
                long numericSourceId = Long.parseLong(sourceId);
                reindexScheduler.registerSource(numericSourceId, reindexIntervalMs);
                LOGGER.debug("Registered source {} with reindex scheduler (interval={}ms)", sourceId, reindexIntervalMs);
            } catch (NumberFormatException e) {
                LOGGER.warn("Source ID '{}' is not numeric, cannot register with reindex scheduler", sourceId);
            }
        }

        // Trigger background ingestion
        if (ingestionOrchestrator != null) {
            // Full ingestion pipeline with orchestrator
            ingestionOrchestrator.startIngestion(source);
        } else if (initialStatus != null) {
            // No pipeline on this node. The source is still crawled, because whether the root
            // resolves is a separate question from whether this node can index it, and the status
            // has to say which of the two is wrong rather than report an ingestion that never ran.
            // The probe is handed the entry created just above rather than the registry: a lookup
            // on the probe's thread can land after a removal and re-create what removal cleared.
            Thread.ofVirtual()
                .name("source-probe-" + sourceId)
                .start(new UnconfiguredSourceProbe(source, initialStatus));
            LOGGER.debug("No ingestion pipeline configured; probing source: {}", sourceId);
        }
    }

    @Override
    public boolean isRegistered(String sourceId) {
        return sources.containsKey(sourceId);
    }

    @Override
    public Source get(String sourceId) {
        return sources.get(sourceId);
    }

    /**
     * Removes a source and undoes everything registering it started.
     *
     * <p>Symmetric with {@link #register(Source)} on purpose. That method starts three things
     * besides the registry entry - an ingestion run, a status entry and a place in the reindex
     * schedule - and a removal that left them behind would leave the node crawling a source no
     * request can name, reporting it on {@code /v1/ingest/status} forever, and logging a
     * source-not-found warning every scheduling interval for the life of the process.</p>
     *
     * <p>The order matters: ingestion is stopped before the source is closed, because a run still
     * inside {@code source.fetch} holds the very thing being closed.</p>
     *
     * @param sourceId The source ID to remove
     * @return true if the source was registered
     */
    @Override
    public boolean remove(String sourceId) {
        Source removed = sources.remove(sourceId);
        if (removed == null) {
            return false;
        }

        if (ingestionOrchestrator != null) {
            ingestionOrchestrator.stopIngestion(sourceId);
        }
        if (reindexScheduler != null) {
            try {
                reindexScheduler.unregisterSource(Long.parseLong(sourceId));
            } catch (NumberFormatException e) {
                // Never registered with the scheduler either; register() logs that at the time.
                LOGGER.debug("Source ID '{}' is not numeric, nothing to unregister", sourceId);
            }
        }
        if (statusRegistry != null) {
            statusRegistry.remove(sourceId);
        }

        closeSource(sourceId, removed);
        LOGGER.info("Removed source {}: ingestion stopped, status cleared, reindexing cancelled",
            sourceId);
        return true;
    }

    @Override
    public Set<String> getAllSourceIds() {
        return Collections.unmodifiableSet(new HashSet<>(sources.keySet()));
    }

    /**
     * Returns the number of registered sources.
     *
     * @return Registered source count
     */
    public int size() {
        return sources.size();
    }

    /**
     * Returns the configured bound on registered sources.
     *
     * @return Maximum registered sources
     */
    public int maxSources() {
        return maxSources;
    }

    @Override
    public void close() {
        for (Map.Entry<String, Source> entry : sources.entrySet()) {
            closeSource(entry.getKey(), entry.getValue());
        }
        sources.clear();
    }

    private static void closeSource(String sourceId, Source source) {
        try {
            source.close();
        } catch (Exception e) {
            LOGGER.debug("Error closing source {}: {}", sourceId, e.getMessage());
        }
    }
}
