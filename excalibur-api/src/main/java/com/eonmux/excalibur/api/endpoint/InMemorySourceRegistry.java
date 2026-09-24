package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.reindex.ReindexCoordinator;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;
import com.eonmux.excalibur.shard.worker.SegmentReindexScheduler;
import com.eonmux.excalibur.tenancy.NamespaceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 *
 * <p>In memory is where the sources live, not where the fact of their registration is kept. When
 * a {@link SourceCatalog} is configured via {@link #setSourceCatalog(SourceCatalog)}, every
 * registration and replacement is written to it and every removal taken back out, and {@link #restore(Source)}
 * brings a recorded one back at startup. Without a catalog the registrations last as long as the
 * process.</p>
 */
public class InMemorySourceRegistry implements SourceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(InMemorySourceRegistry.class);

    private final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();

    /**
     * Which namespace each registered source's documents belong to.
     *
     * <p>Beside the sources rather than inside them, because a {@link Source} is a crawler and
     * knows nothing about tenancy - and because the answer is wanted once per published
     * document, on the ingestion thread, which is no place to be reading a file. It is populated
     * from the descriptor a registration or a restore carries, so a source brought back from the
     * catalog stages where it staged before the restart.</p>
     */
    private final ConcurrentHashMap<String, NamespaceId> namespaces = new ConcurrentHashMap<>();

    /**
     * Held while a registration, a replacement or a removal changes which source an id names.
     *
     * <p>Each of them writes the catalog and then the live map, and a replacement also decides
     * from the live map that nothing is crawling the source it swaps. Interleaved, a removal
     * could forget the record a replacement then writes back, or a registration could start a
     * crawl through a source a replacement has just closed. Lookups never take it.</p>
     */
    private final Object registrations = new Object();

    /**
     * How long a removal waits, by default, for a reindex of the source to stop.
     *
     * <p>A stopped reindex finishes the document it is writing, so this bounds one document's
     * fetch, embedding and writes rather than the rest of the crawl - and a removal arrives on a
     * request thread whose caller is waiting for the answer.</p>
     */
    public static final long DEFAULT_REINDEX_STOP_WAIT_MS = 30_000;

    private final int maxSources;
    private volatile IngestionOrchestrator ingestionOrchestrator;
    private volatile IngestStatusEndpoint.IngestionStatusRegistry statusRegistry;
    private volatile ReindexEndpoint.ReindexStatusRegistry reindexStatusRegistry;
    private volatile SegmentReindexScheduler reindexScheduler;
    private volatile long reindexIntervalMs = SegmentReindexScheduler.DEFAULT_REINDEX_INTERVAL_MS;
    private volatile SourceCatalog sourceCatalog;
    private volatile ReindexCoordinator reindexCoordinator;
    private volatile long reindexStopWaitMs = DEFAULT_REINDEX_STOP_WAIT_MS;

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
     * Sets the registry holding what each source's reindex runs did.
     *
     * <p>Only so that deregistering a source forgets its reindex status too. The status
     * otherwise outlives the source it describes - {@code GET /v1/reindex/status} keeps
     * answering for something that is gone - and the map grows for the life of the
     * process.</p>
     *
     * @param reindexStatusRegistry The registry (may be null to disable)
     */
    public void setReindexStatusRegistry(
            ReindexEndpoint.ReindexStatusRegistry reindexStatusRegistry) {
        this.reindexStatusRegistry = reindexStatusRegistry;
    }

    /**
     * Sets the claim every reindex of a source holds while it runs, so a removal can stop one.
     *
     * <p>Removing a source stops everything registering it started, and a reindex is one of
     * them. It is not enough to take the source off the schedule: a re-crawl already running
     * goes on writing the source's documents - which, when the removal is the first step of
     * erasing the source, puts back what the erasure is about to remove - reads through a source
     * that removal closes, and records a status for a source that no longer exists. The
     * coordinator is the one place that sees every running reindex, whichever trigger started it,
     * and so the one place a removal can stop it through.</p>
     *
     * @param coordinator The coordinator both reindex triggers take their claim from, or null
     *                    on a node that does not reindex
     */
    public void setReindexCoordinator(ReindexCoordinator coordinator) {
        setReindexCoordinator(coordinator, DEFAULT_REINDEX_STOP_WAIT_MS);
    }

    /**
     * As {@link #setReindexCoordinator(ReindexCoordinator)}, with an explicit bound on how long
     * a removal waits for a reindex to stop.
     *
     * @param coordinator  The coordinator, or null on a node that does not reindex
     * @param stopWaitMs   How long a removal waits for the source's reindex to end
     * @throws IllegalArgumentException if the wait is negative
     */
    public void setReindexCoordinator(ReindexCoordinator coordinator, long stopWaitMs) {
        if (stopWaitMs < 0) {
            throw new IllegalArgumentException("stopWaitMs must not be negative: " + stopWaitMs);
        }
        this.reindexCoordinator = coordinator;
        this.reindexStopWaitMs = stopWaitMs;
    }

    /**
     * Sets the reindex scheduler for periodic source reindexing.
     *
     * <p>When set, sources registered via {@link #register(Source)} will
     * be automatically registered with the scheduler for periodic reindexing.
     * The scheduler will trigger reindex operations at the configured interval.</p>
     *
     * <p>Sources are scheduled under the id they were registered with, whatever it is: the
     * schedule has to be addressable by the same name {@code /v1/ingest} accepted, or a
     * deregistration could not find what to cancel.</p>
     *
     * <p>The interval must be within the scheduler's valid range (1 minute to 24 hours).</p>
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

    /**
     * Sets the catalog that makes registrations outlast the process.
     *
     * <p>With one attached, {@link #register(Source, SourceDescriptor)} writes the registration
     * down before starting anything and {@link #remove(String)} takes it back out, so the set of
     * sources a node crawls is the set an operator asked for rather than the set that happens to
     * have been registered since the last restart.</p>
     *
     * @param catalog The source catalog (may be null to keep registrations in memory only)
     */
    public void setSourceCatalog(SourceCatalog catalog) {
        this.sourceCatalog = catalog;
    }

    @Override
    public void register(Source source) {
        admit(source, NamespaceId.SHARED, null, Crawl.YES);
    }

    @Override
    public void register(Source source, SourceDescriptor descriptor) {
        requireMatching(source, descriptor);
        admit(source, descriptor.namespace(), descriptor, Crawl.YES);
    }

    /**
     * Checks that a descriptor describes the source it arrived with.
     *
     * <p>The catalog is addressed by the same id as the registry and the re-crawl schedule. A
     * record filed under a different one names a source no removal can cancel and no restart can
     * match to a crawler - and would bind a namespace to an id nothing ever crawls.</p>
     *
     * @param source     The crawler
     * @param descriptor What it was registered as
     * @throws NullPointerException     if either is null
     * @throws IllegalArgumentException if they name different sources
     */
    private static void requireMatching(Source source, SourceDescriptor descriptor) {
        Objects.requireNonNull(source, "Source cannot be null");
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        if (!descriptor.sourceId().equals(source.getSourceId())) {
            throw new IllegalArgumentException("Descriptor names source "
                + descriptor.sourceId() + " but the source is " + source.getSourceId());
        }
    }

    /**
     * Brings back a source a previous run registered.
     *
     * <p>Everything registration starts except the crawl. The source takes its place in the
     * registry, in {@code /v1/ingest/status} - reporting the last run it finished before the
     * restart, where the status registry keeps a history - and in the re-crawl schedule, and the
     * schedule is what crawls it next - one interval from now, which is what a source registered
     * before the restart was already waiting for.</p>
     *
     * <p>Re-ingesting on the way up would be the wrong default: it re-fetches and re-hashes the
     * whole corpus every time the node is bounced, to discover what the next scheduled re-crawl
     * would have discovered anyway. {@code POST /v1/reindex} is there for an operator who wants
     * it sooner.</p>
     *
     * <p>Nothing is written to the catalog: the registration being restored is already in it.</p>
     *
     * @param source The source to bring back
     * @throws NullPointerException        if the source is null
     * @throws SourceRegistrationException if the ID is taken or the registry is full
     */
    public void restore(Source source) {
        admit(source, NamespaceId.SHARED, null, Crawl.NO);
        LOGGER.info("Restored source {} from the catalog; next re-crawl in {}ms",
            source.getSourceId(), reindexIntervalMs);
    }

    /**
     * Brings back a source a previous run registered, into the namespace it was registered in.
     *
     * <p>Everything {@link #restore(Source)} does, plus the binding that makes the restoration
     * faithful. A source's namespace decides which tenant's graph its documents are staged into,
     * and it is settled once, by the caller that registered it; a restore that dropped it would
     * leave the same source staging into the shared namespace from the next restart on, moving
     * one tenant's facts into the index every other tenant reads - a boundary crossed by a
     * reboot, with nothing in the node reporting it.</p>
     *
     * <p>Nothing is written to the catalog: the registration being restored came out of it.</p>
     *
     * @param source     The source to bring back
     * @param descriptor The record it was rebuilt from
     * @throws NullPointerException        if either argument is null
     * @throws IllegalArgumentException    if the descriptor names a different source
     * @throws SourceRegistrationException if the ID is taken or the registry is full
     */
    public void restore(Source source, SourceDescriptor descriptor) {
        requireMatching(source, descriptor);
        admit(source, descriptor.namespace(), null, Crawl.NO);
        LOGGER.info("Restored source {} in namespace {} from the catalog; next re-crawl in {}ms",
            source.getSourceId(), descriptor.namespace(), reindexIntervalMs);
    }

    /** Whether admitting a source starts a crawl, or only takes its place in the schedule. */
    private enum Crawl { YES, NO }

    /**
     * Takes a source into the registry and starts whatever that entails.
     *
     * <p>The catalog is written before anything else is started, so a registration that cannot be
     * recorded leaves nothing behind to undo: no status entry, no schedule, no crawl. The
     * namespace is bound after that write for the same reason - a source that is not registered
     * must not be left behind in the map the graph sink routes on.</p>
     *
     * @param source     The crawler
     * @param namespace  The namespace its documents belong to
     * @param toRecord   What to write to the catalog, or null for a registration already in it
     * @param crawl      Whether to start crawling now
     */
    private void admit(Source source, NamespaceId namespace, SourceDescriptor toRecord,
                       Crawl crawl) {
        Objects.requireNonNull(source, "Source cannot be null");
        Objects.requireNonNull(namespace, "namespace cannot be null");

        synchronized (registrations) {
            claim(source);
            if (toRecord != null) {
                record(source, toRecord);
            }
            namespaces.put(source.getSourceId(), namespace);
            start(source, crawl);
        }
    }

    /**
     * Replaces what a registered source is configured as, keeping its id, namespace, status
     * history, place in the re-crawl schedule and indexed documents.
     *
     * <p>Refused while the source is being crawled or reindexed: a run already reading through
     * the old source would go on with the old configuration and then have that source closed
     * under it. The reindex claim is held for the whole swap, so no scheduled or requested
     * reindex can start half-way through it, and no crawl can start either - the only thing that
     * starts one is a registration, which cannot claim an id that is taken.</p>
     *
     * <p>The catalog is written before the live source is swapped, and a write that fails leaves
     * both the record and the live source as they were. The old source is closed once nothing
     * can reach it any more.</p>
     *
     * @param source     The crawler built from the new configuration
     * @param descriptor What the source is now registered as
     * @throws NullPointerException        if either argument is null
     * @throws IllegalArgumentException    if the descriptor names a different source
     * @throws SourceRegistrationException if no source is registered under the id, the
     *                                     namespace differs, a crawl or reindex of it is running,
     *                                     or the node is shutting down
     * @throws SourceCatalogException      if the new configuration cannot be recorded
     */
    @Override
    public void replace(Source source, SourceDescriptor descriptor) {
        requireMatching(source, descriptor);
        String sourceId = descriptor.sourceId();
        Source previous;
        synchronized (registrations) {
            previous = sources.get(sourceId);
            if (previous == null) {
                throw new SourceRegistrationException(SourceRegistrationException.Reason.UNKNOWN,
                    "No source is registered under '" + sourceId + "'. Register it with POST.");
            }
            NamespaceId current = namespaces.getOrDefault(sourceId, NamespaceId.SHARED);
            if (!current.equals(descriptor.namespace())) {
                throw new SourceRegistrationException(
                    SourceRegistrationException.Reason.NAMESPACE_MISMATCH,
                    "Source '" + sourceId + "' belongs to namespace '" + current.value()
                        + "'. A replacement keeps the namespace its documents were staged into; "
                        + "send the same X-Excalibur-Namespace, or deregister the source and "
                        + "register it again in the other namespace.");
            }
            ReindexCoordinator.Lease lease = claimReindex(sourceId);
            try {
                IngestionOrchestrator orchestrator = this.ingestionOrchestrator;
                if (orchestrator != null && orchestrator.isIngestionActive(sourceId)) {
                    throw inFlight(sourceId, "crawl");
                }
                rerecord(descriptor);
                sources.put(sourceId, source);
            } finally {
                if (lease != null) {
                    lease.release();
                }
            }
        }
        closeSource(sourceId, previous);
        LOGGER.info("Replaced the configuration of source {}; its next crawl uses it", sourceId);
    }

    /**
     * Takes the reindex claim on a source for the length of a replacement.
     *
     * @param sourceId The source being replaced
     * @return The claim, or null on a node that does not reindex
     * @throws SourceRegistrationException if a reindex of the source is running, or the node is
     *                                     shutting down
     */
    private ReindexCoordinator.Lease claimReindex(String sourceId) {
        ReindexCoordinator coordinator = this.reindexCoordinator;
        if (coordinator == null) {
            return null;
        }
        ReindexCoordinator.Lease lease = coordinator.tryAcquire(sourceId);
        if (lease != null) {
            return lease;
        }
        if (!coordinator.isAccepting()) {
            throw new SourceRegistrationException(
                SourceRegistrationException.Reason.SHUTTING_DOWN,
                "The node is shutting down and replaces no source configuration");
        }
        throw inFlight(sourceId, "reindex");
    }

    private static SourceRegistrationException inFlight(String sourceId, String run) {
        return new SourceRegistrationException(SourceRegistrationException.Reason.IN_FLIGHT,
            "A " + run + " of source '" + sourceId + "' is running with its current "
                + "configuration. Send the replacement again once it has finished.");
    }

    /**
     * Writes a replacement registration over the one the catalog holds.
     *
     * <p>Unlike {@link #record(Source, SourceDescriptor)}, a failure gives nothing up: the source
     * was registered before the replacement was asked for and stays registered as it was.</p>
     */
    private void rerecord(SourceDescriptor descriptor) {
        SourceCatalog catalog = this.sourceCatalog;
        if (catalog == null) {
            return;
        }
        try {
            catalog.record(descriptor);
        } catch (IOException e) {
            throw new SourceCatalogException(
                "Could not record the new configuration of source " + descriptor.sourceId(), e);
        }
    }

    /**
     * Takes the source id, or refuses because someone else has it or the registry is full.
     *
     * <p>Atomically. A separate {@link #isRegistered(String)} check would leave a window in
     * which two requests both believe they are the first, and the loser's crawler is started
     * and then leaked - nothing else closes a source the registry does not hold.</p>
     *
     * @param source The crawler claiming its id
     * @throws SourceRegistrationException if the id is taken or the registry is full
     */
    private void claim(Source source) {
        String sourceId = source.getSourceId();
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
    }

    /**
     * Starts everything a registered source is entitled to: a status entry, a place in the
     * re-crawl schedule, and - unless it is being restored - a crawl.
     *
     * @param source The crawler, already claimed and recorded
     * @param crawl  Whether to crawl now, or only take a place in the schedule
     */
    private void start(Source source, Crawl crawl) {
        String sourceId = source.getSourceId();

        // A status entry either way, so /v1/ingest/status answers for the source even on a node
        // with no ingestion pipeline. A fresh registration starts idle and empty; a restored one
        // reports the last run it finished before the restart, because zeros stamped with the
        // moment the node came up would say the source had never been crawled.
        IngestStatusEndpoint.IngestionStatus initialStatus = null;
        if (statusRegistry != null && crawl == Crawl.NO) {
            initialStatus = statusRegistry.restore(sourceId);
            LOGGER.debug("Restored the status entry for source: {}", sourceId);
        } else if (statusRegistry != null) {
            initialStatus = statusRegistry.getOrCreate(sourceId);
            initialStatus.update("idle", 0.0, 0, 0);
            LOGGER.debug("Created initial status entry for source: {}", sourceId);
        }

        // Register with reindex scheduler for periodic reindexing
        if (reindexScheduler != null) {
            reindexScheduler.registerSource(sourceId, reindexIntervalMs);
            LOGGER.debug("Registered source {} with reindex scheduler (interval={}ms)",
                sourceId, reindexIntervalMs);
        }

        if (crawl == Crawl.NO) {
            return;
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

    /**
     * Writes a registration to the catalog, giving up the claimed id if it cannot be written.
     *
     * <p>Answering "registered" for a source that was never recorded is the failure this whole
     * catalog exists to prevent, so it is refused rather than logged.</p>
     */
    private void record(Source source, SourceDescriptor descriptor) {
        SourceCatalog catalog = this.sourceCatalog;
        if (catalog == null) {
            return;
        }
        try {
            catalog.record(descriptor);
        } catch (IOException e) {
            sources.remove(descriptor.sourceId(), source);
            throw new SourceCatalogException(
                "Could not record source " + descriptor.sourceId(), e);
        }
    }

    /**
     * Takes a registration out of the catalog.
     *
     * <p>Runs before the live source is touched: a removal the catalog will not accept has to
     * leave the node crawling what it was crawling, rather than stop and start again at the next
     * restart.</p>
     *
     * @return true if the catalog held a record under that id
     */
    private boolean forget(String sourceId) {
        SourceCatalog catalog = this.sourceCatalog;
        if (catalog == null) {
            return false;
        }
        try {
            return catalog.forget(sourceId);
        } catch (IOException e) {
            throw new SourceCatalogException("Could not deregister source " + sourceId, e);
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
     * source-not-found warning every scheduling interval for the life of the process. The
     * schedule is also how a re-crawl gets started, so a re-crawl already running is stopped
     * too.</p>
     *
     * <p>The order matters three times. The catalog record goes first, because a crawl stopped
     * in memory and still recorded on disk restarts itself at the next boot. A running reindex is
     * stopped and waited for before the status entries are cleared, because until it has ended
     * it is still recording what it did. And both runs are stopped before the source is closed,
     * because a run still inside {@code source.fetch} holds the very thing being closed.</p>
     *
     * <p>The reindex is waited for, within a bound, where the ingestion run is only asked: a
     * caller erasing the source goes on to remove its documents the moment this returns, and a
     * reindex that finished the document it was on after that would put it back. A reindex that
     * outlives the bound is logged and left to stop on its own; it can no longer open a status
     * for the source, because both reindex triggers check that the source is still registered
     * once they hold their claim.</p>
     *
     * @param sourceId The source ID to remove
     * @return true if the source was registered, in this process or in the catalog
     * @throws SourceCatalogException if the removal cannot be recorded
     */
    @Override
    public boolean remove(String sourceId) {
        // The catalog first. A removal it will not accept leaves the source registered and the
        // caller told so, which is recoverable; stopping the crawl and keeping the record is not -
        // the source comes back at the next restart with nothing to say why.
        boolean recorded;
        Source removed;
        synchronized (registrations) {
            recorded = forget(sourceId);
            removed = sources.remove(sourceId);
            namespaces.remove(sourceId);
        }
        if (removed == null) {
            // A record with no live source behind it is still a registration someone made: the
            // node could not rebuild the crawler at startup, and this is what cancels it.
            return recorded;
        }

        if (ingestionOrchestrator != null) {
            ingestionOrchestrator.stopIngestion(sourceId);
        }
        if (reindexScheduler != null) {
            reindexScheduler.unregisterSource(sourceId);
        }
        stopReindex(sourceId);
        if (statusRegistry != null) {
            statusRegistry.remove(sourceId);
        }
        if (reindexStatusRegistry != null) {
            reindexStatusRegistry.remove(sourceId);
        }

        closeSource(sourceId, removed);
        LOGGER.info("Removed source {}: ingestion stopped, reindexing stopped and unscheduled, "
            + "status cleared", sourceId);
        return true;
    }

    /**
     * Stops a reindex of a source being removed, and waits for it within the configured bound.
     *
     * <p>An interrupt ends the wait but not the removal: the source is already out of the
     * registry and the catalog, and leaving the rest of it half-undone would be worse than not
     * waiting. The interrupt is kept on the thread for its owner to act on.</p>
     *
     * @param sourceId The source being removed
     */
    private void stopReindex(String sourceId) {
        ReindexCoordinator coordinator = this.reindexCoordinator;
        if (coordinator == null || !coordinator.isRunning(sourceId)) {
            return;
        }
        LOGGER.info("Stopping the reindex of source {} before removing it", sourceId);
        try {
            if (!coordinator.cancelAndAwait(sourceId, reindexStopWaitMs)) {
                LOGGER.warn("The reindex of source {} had not stopped {}ms after being asked; "
                    + "removing the source anyway. It stops at its next document and records "
                    + "nothing further for the source", sourceId, reindexStopWaitMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for the reindex of source {} to stop; "
                + "removing the source anyway", sourceId);
        }
    }

    /**
     * Asks the run over one source to stop, and leaves everything else about it alone.
     *
     * <p>Deliberately not symmetric with {@link #remove(String)}: the catalog record, the
     * status entry and the place in the re-crawl schedule all stay, because the source is
     * still registered and is still going to be crawled again at its next interval. What
     * stops is the run that is in flight right now.</p>
     *
     * @param sourceId The source whose run should stop
     * @return true if a run was in flight and has been asked to stop
     */
    @Override
    public boolean stopIngestion(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId cannot be null");
        IngestionOrchestrator orchestrator = this.ingestionOrchestrator;
        if (orchestrator == null || !sources.containsKey(sourceId)) {
            return false;
        }
        boolean stopping = orchestrator.stopIngestion(sourceId);
        if (stopping) {
            LOGGER.info("Ingestion of source {} was asked to stop; the source stays registered "
                + "and will be crawled again at its next scheduled interval", sourceId);
        }
        return stopping;
    }

    /**
     * The namespace a registered source's documents belong to.
     *
     * <p>Keyed off the live registry rather than off the namespace map alone, so that a source
     * that has been removed answers empty even if the two maps were momentarily out of step. The
     * caller is the graph sink, and an answer of "shared" for a source it should not have is
     * worse than no answer at all: facts would be staged into the index every tenant reads.</p>
     *
     * <p>A source admitted before this registry tracked namespaces - or by the plain
     * {@link #register(Source)}, which names none - is in the shared namespace, which is what a
     * registration that named none has always meant.</p>
     *
     * @param sourceId The source id
     * @return Its namespace, or empty if nothing is registered under that id
     */
    @Override
    public Optional<NamespaceId> namespaceOf(String sourceId) {
        if (sourceId == null || !sources.containsKey(sourceId)) {
            return Optional.empty();
        }
        return Optional.of(namespaces.getOrDefault(sourceId, NamespaceId.SHARED));
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
        namespaces.clear();
    }

    private static void closeSource(String sourceId, Source source) {
        try {
            source.close();
        } catch (Exception e) {
            LOGGER.debug("Error closing source {}: {}", sourceId, e.getMessage());
        }
    }
}
