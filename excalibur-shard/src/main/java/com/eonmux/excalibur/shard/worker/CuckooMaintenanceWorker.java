package com.eonmux.excalibur.shard.worker;


import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.store.format.SegmentEntry;
import com.eonmux.excalibur.core.text.TermExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual thread worker for hierarchical Cuckoo filter maintenance.
 *
 * <p>This worker removes a tombstoned segment's entries from the Cuckoo filters, the segment's
 * own level and - where no other segment still holds the term - the corpus level above it.
 * Tombstoning the segment in the {@link SegmentCatalog} is what schedules the work; there is no
 * separate queue to feed, and {@link #getPendingDeletionCount()} reads the backlog from the
 * catalog.</p>
 *
 * <p>False positive rates are not measured here: {@link CuckooMetricsWorker} samples them, and
 * two workers reporting the same number on different schedules is how they come to disagree.</p>
 *
 * <h2>Hierarchical Filter Maintenance</h2>
 * <p>When a segment is tombstoned:</p>
 * <ol>
 *   <li>Entries are deleted from the segment-level filter</li>
 *   <li>Deletions are propagated to parent filters in the hierarchy</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Runs in a dedicated virtual thread scheduler</li>
 *   <li>Uses atomic operations for state management</li>
 *   <li>Maintenance operations do not block segment reads or writes</li>
 * </ul>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Maintenance interval: 1 second to 1 minute (default 5 seconds)</li>
 *   <li>Max deletions per cycle: 100 to 10000 (default 1000); bounds when a cycle stops
 *       starting segments, see {@link #processTombstonedSegments(int)}</li>
 *   <li>False positive rate threshold for warnings: 0.1% (default)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 5.0
 * @see CuckooFilter
 * @see SegmentCatalog
 */
public class CuckooMaintenanceWorker {


    private static final Logger LOGGER = LoggerFactory.getLogger(CuckooMaintenanceWorker.class);

    // ==================== Configuration Constants ====================

    /**
     * Default interval between maintenance cycles in milliseconds.
     * Domain constraint: Must be at least 1000ms (1 second).
     */
    public static final long DEFAULT_MAINTENANCE_INTERVAL_MS = 5000; // 5 seconds

    /**
     * Minimum allowed maintenance interval in milliseconds.
     */
    public static final long MIN_MAINTENANCE_INTERVAL_MS = 1000; // 1 second

    /**
     * Maximum allowed maintenance interval in milliseconds.
     */
    public static final long MAX_MAINTENANCE_INTERVAL_MS = 60000; // 1 minute

    /**
     * Default maximum deletions per maintenance cycle.
     * Domain constraint: 1000 to 10000 entries.
     */
    public static final int DEFAULT_MAX_DELETIONS_PER_CYCLE = 1000;

    /**
     * Minimum deletions per cycle.
     */
    public static final int MIN_DELETIONS_PER_CYCLE = 100;

    /**
     * Maximum deletions per cycle.
     */
    public static final int MAX_DELETIONS_PER_CYCLE = 10000;

    /**
     * Default false positive rate threshold for warnings.
     * Domain constraint: 0.001 to 0.1 (0.1% default).
     */
    public static final double DEFAULT_FP_RATE_THRESHOLD = 0.001; // 0.1%

    /**
     * Largest embedding dimension accepted when decoding a stored chunk value.
     * Guards against treating an unrelated value's leading bytes as a length.
     */
    private static final int MAX_VECTOR_DIMENSION = 65536;

    // ==================== Instance Fields ====================

    private final SegmentCatalog catalog;
    private final StorageEngine storageEngine;
    private final MaintenanceListener listener;
    private final CopyOnWriteArrayList<SegmentFilter> segmentFilters;

    /**
     * Segments whose filter entries have already been removed.
     * A Cuckoo delete is not idempotent, so each tombstoned segment is cleaned once.
     */
    private final Set<Long> cleanedTombstonedSegments;

    /**
     * Tombstoned segments the engine has not loaded, so their terms could not be removed.
     *
     * <p>Retried every cycle - nothing was deleted, so a retry cannot double-delete - and
     * reported once, so a segment that never loads does not fill the log.</p>
     */
    private final Set<Long> unreachableTombstonedSegments = ConcurrentHashMap.newKeySet();

    /**
     * Hierarchical Cuckoo filter for corpus → segment deletion propagation.
     * May be null if hierarchy is not configured.
     */
    private volatile HierarchicalCuckooFilter hierarchicalFilter;

    private final AtomicBoolean running;
    private final AtomicBoolean stopped;

    private final AtomicLong totalDeletions;
    private final AtomicLong lastMaintenanceTime;
    private final AtomicLong tombstonedSegmentsProcessed;

    private volatile long maintenanceIntervalMs;
    private volatile int maxDeletionsPerCycle;
    private volatile double fpRateThreshold;

    private volatile ScheduledExecutorService scheduler;

    // ==================== Constructors ====================

    /**
     * Creates a new CuckooMaintenanceWorker with default configuration.
     *
     * @param catalog       The segment catalog for metadata access
     * @param storageEngine The storage engine for segment access
     * @param listener      Callback for maintenance events
     * @throws NullPointerException if catalog, storageEngine, or listener is null
     */
    public CuckooMaintenanceWorker(SegmentCatalog catalog, StorageEngine storageEngine,
                                     MaintenanceListener listener) {
        this(catalog, storageEngine, listener, DEFAULT_MAINTENANCE_INTERVAL_MS,
             DEFAULT_MAX_DELETIONS_PER_CYCLE, DEFAULT_FP_RATE_THRESHOLD);
    }

    /**
     * Creates a new CuckooMaintenanceWorker with custom configuration.
     *
     * @param catalog                  The segment catalog for metadata access
     * @param storageEngine            The storage engine for segment access
     * @param listener                 Callback for maintenance events
     * @param maintenanceIntervalMs    Interval between maintenance cycles (ms)
     * @param maxDeletionsPerCycle     Maximum deletions per maintenance cycle
     * @param fpRateThreshold          False positive rate threshold for warnings
     * @throws NullPointerException     if catalog, storageEngine, or listener is null
     * @throws IllegalArgumentException if configuration values are invalid
     */
    public CuckooMaintenanceWorker(SegmentCatalog catalog, StorageEngine storageEngine,
                                     MaintenanceListener listener,
                                     long maintenanceIntervalMs, int maxDeletionsPerCycle,
                                     double fpRateThreshold) {
        if (catalog == null) {
            throw new NullPointerException("Catalog cannot be null");
        }
        if (storageEngine == null) {
            throw new NullPointerException("StorageEngine cannot be null");
        }
        if (listener == null) {
            throw new NullPointerException("Listener cannot be null");
        }

        validateMaintenanceInterval(maintenanceIntervalMs);
        validateMaxDeletions(maxDeletionsPerCycle);
        validateFpRateThreshold(fpRateThreshold);

        this.catalog = catalog;
        this.storageEngine = storageEngine;
        this.listener = listener;
        this.segmentFilters = new CopyOnWriteArrayList<>();
        this.cleanedTombstonedSegments = ConcurrentHashMap.newKeySet();
        this.running = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);
        this.totalDeletions = new AtomicLong(0);
        this.lastMaintenanceTime = new AtomicLong(0);
        this.tombstonedSegmentsProcessed = new AtomicLong(0);

        this.maintenanceIntervalMs = maintenanceIntervalMs;
        this.maxDeletionsPerCycle = maxDeletionsPerCycle;
        this.fpRateThreshold = fpRateThreshold;

        // Use virtual thread executor
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("excalibur-cuckoo-maintenance-scheduler").factory());

        LOGGER.info("CuckooMaintenanceWorker created with interval={}ms, maxDeletions={}, fpThreshold={}",
            maintenanceIntervalMs, maxDeletionsPerCycle, fpRateThreshold);
    }

    /**
     * Sets the hierarchical Cuckoo filter for deletion propagation.
     *
     * <p>When set, deletions will be propagated through the hierarchy:
     * segment → corpus. This ensures no stale false positives
     * from tombstoned entries.</p>
     *
     * @param filter The hierarchical Cuckoo filter (may be null)
     */
    public void setHierarchicalFilter(HierarchicalCuckooFilter filter) {
        this.hierarchicalFilter = filter;
        LOGGER.info("Hierarchical Cuckoo filter {} for CuckooMaintenanceWorker",
            filter != null ? "configured" : "cleared");
    }

    /**
     * Returns the hierarchical Cuckoo filter, if configured.
     *
     * @return The hierarchical filter, or null if not configured
     */
    public HierarchicalCuckooFilter getHierarchicalFilter() {
        return hierarchicalFilter;
    }

    // ==================== Configuration Validation ====================

    private void validateMaintenanceInterval(long intervalMs) {
        if (intervalMs < MIN_MAINTENANCE_INTERVAL_MS || intervalMs > MAX_MAINTENANCE_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "Maintenance interval must be between " + MIN_MAINTENANCE_INTERVAL_MS +
                " and " + MAX_MAINTENANCE_INTERVAL_MS + "ms, got: " + intervalMs);
        }
    }

    private void validateMaxDeletions(int max) {
        if (max < MIN_DELETIONS_PER_CYCLE || max > MAX_DELETIONS_PER_CYCLE) {
            throw new IllegalArgumentException(
                "Max deletions per cycle must be between " + MIN_DELETIONS_PER_CYCLE +
                " and " + MAX_DELETIONS_PER_CYCLE + ", got: " + max);
        }
    }

    private void validateFpRateThreshold(double threshold) {
        if (threshold < 0.0 || threshold > 0.1) {
            throw new IllegalArgumentException(
                "FP rate threshold must be between 0.0 and 0.1, got: " + threshold);
        }
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts the background maintenance worker.
     *
     * <p>After starting, the worker periodically cleans tombstoned segments out of the
     * filters.</p>
     *
     * <p>If the worker is already running, this method does nothing.</p>
     *
     * @return true if the worker was started, false if already running
     */
    public boolean start() {
        if (running.compareAndSet(false, true)) {
            stopped.set(false);

            // CRITICAL FIX: Create new scheduler if the previous one was terminated
            // Must check both isShutdown() and isTerminated() to handle race conditions
            // where shutdown() was called but termination is still in progress
            if (scheduler.isShutdown() || scheduler.isTerminated()) {
                this.scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("excalibur-cuckoo-maintenance-scheduler").factory());
            }

            scheduler.scheduleAtFixedRate(
                this::performMaintenanceCycle,
                maintenanceIntervalMs,
                maintenanceIntervalMs,
                TimeUnit.MILLISECONDS
            );

            LOGGER.info("Cuckoo maintenance worker started");
            return true;
        }
        return false;
    }

    /**
     * Stops the background maintenance worker and says whether it actually ended.
     *
     * <p>A cycle in progress is waited for and never interrupted. It reads the entries of
     * tombstoned segments through their mappings, so the answer matters to the caller, which
     * closes the arena those mappings live in next; and it cleans a segment whole or not at all,
     * so there is no point inside one at which it could honour an interrupt anyway. A cycle
     * that outlives the wait is reported rather than abandoned to a closing arena.</p>
     *
     * <p>How long to wait is the caller's: a node's shutdown passes what its budget leaves the
     * drains. A later call waits again for a cycle an earlier one left running.</p>
     *
     * @param wait How long to wait for a maintenance cycle in progress
     * @return true if no maintenance cycle is still running
     * @throws NullPointerException if wait is null
     */
    public boolean stop(Duration wait) {
        Objects.requireNonNull(wait, "wait cannot be null");
        if (stopped.compareAndSet(false, true)) {
            running.set(false);
            scheduler.shutdown();
            LOGGER.info("Cuckoo maintenance worker asked to stop. Total deletions: {}",
                totalDeletions.get());
        }
        try {
            if (!scheduler.awaitTermination(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                LOGGER.warn("A Cuckoo maintenance cycle was still cleaning tombstoned segments "
                    + "{}ms after being asked to stop", wait.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return scheduler.isTerminated();
    }

    /**
     * Checks if the worker is currently running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Filter Registration ====================

    /**
     * Registers a segment's Cuckoo filter for maintenance.
     *
     * @param segmentId The segment ID
     * @param filter   The Cuckoo filter for the segment
     */
    public void registerFilter(long segmentId, CuckooFilter filter) {
        if (filter == null) {
            return;
        }
        // Check if filter already registered
        boolean alreadyRegistered = false;
        for (SegmentFilter sf : segmentFilters) {
            if (sf.segmentId == segmentId) {
                alreadyRegistered = true;
                break;
            }
        }
        if (!alreadyRegistered) {
            segmentFilters.add(new SegmentFilter(segmentId, filter));
            LOGGER.debug("Registered Cuckoo filter for segment {}", segmentId);
        }
    }

    /**
     * Unregisters a segment's Cuckoo filter from maintenance.
     *
     * @param segmentId The segment ID
     */
    public void unregisterFilter(long segmentId) {
        segmentFilters.removeIf(sf -> sf.segmentId == segmentId);
        LOGGER.debug("Unregistered Cuckoo filter for segment {}", segmentId);
    }

    /**
     * Returns how many filter entries are waiting to be removed.
     *
     * <p>Tombstoning a segment in the catalog is what schedules its removal, so the backlog is
     * read from there: every entry still held by a tombstoned segment this worker has not yet
     * cleaned. That is the same unit {@link #getTotalDeletions()} counts once they are gone -
     * distinct terms, since each filter level holds a term at most once - so the two figures
     * move against each other as a sweep runs.</p>
     *
     * <p>There is deliberately no way to queue an individual deletion. A document can be
     * deleted or replaced without its segment being tombstoned, but the terms it contributed
     * are usually shared with other documents in the same segment, and nothing counts how many
     * of them still hold each one. Deleting a term on that basis would make the filter deny a
     * segment that still contains it, and Stage 1 of the query pipeline takes a denial from a
     * trusted level as the segment lacking the term. A whole tombstoned segment has no such problem: nothing in it is live.</p>
     *
     * @return Entries held by tombstoned segments awaiting cleanup; 0 when none are waiting
     */
    public int getPendingDeletionCount() {
        long pending = 0;
        for (Long segmentId : catalog.getSegmentsByState(LifecycleState.TOMBSTONED)) {
            if (cleanedTombstonedSegments.contains(segmentId)) {
                continue;
            }
            CuckooFilter filter = filterOf(segmentId);
            if (filter != null) {
                pending += filter.count();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, pending);
    }

    /**
     * Resolves the filter holding a segment's entries.
     *
     * <p>The hierarchy level when a hierarchy is configured, because that is where ingestion
     * records terms and where {@link #processTombstonedSegments()} deletes them; otherwise the
     * standalone filter registered for the segment.</p>
     *
     * @param segmentId The segment ID
     * @return The filter, or null if the segment has none
     */
    private CuckooFilter filterOf(long segmentId) {
        HierarchicalCuckooFilter hierarchy = hierarchicalFilter;
        if (hierarchy != null) {
            CuckooFilter level = hierarchy.getSegmentFilter(String.valueOf(segmentId));
            if (level != null) {
                return level;
            }
        }
        SegmentFilter registered = findSegmentFilter(segmentId);
        return registered == null ? null : registered.filter;
    }

    /**
     * Returns the number of registered segment filters.
     *
     * @return Registered filter count
     */
    public int getRegisteredFilterCount() {
        return segmentFilters.size();
    }

    // ==================== Maintenance Cycle ====================

    /**
     * Performs a maintenance cycle.
     *
     * <p>Called periodically by the scheduler: cleans tombstoned segments out of the filters,
     * up to {@link #getMaxDeletionsPerCycle()} entries' worth, and leaves the rest for the next
     * cycle.</p>
     */
    private void performMaintenanceCycle() {
        if (!running.get() || stopped.get()) {
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            int deletionsThisCycle = processTombstonedSegments(maxDeletionsPerCycle);
            lastMaintenanceTime.set(System.currentTimeMillis());

            if (deletionsThisCycle > 0) {
                LOGGER.debug("Maintenance cycle complete: {} deletions in {}ms",
                    deletionsThisCycle, System.currentTimeMillis() - startTime);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Error during maintenance cycle", e);
        }
    }

    /**
     * Cleans every tombstoned segment not yet cleaned, however many entries that removes.
     *
     * <p>Package-visible so one sweep can be driven directly, without a scheduler.</p>
     *
     * @return Number of deletions performed
     * @see #processTombstonedSegments(int)
     */
    int processTombstonedSegments() {
        return processTombstonedSegments(Integer.MAX_VALUE);
    }

    /**
     * Processes tombstoned segments and deletes their filter entries.
     *
     * <p>Each tombstoned segment is cleaned <strong>once</strong>: the catalog keeps a
     * segment in TOMBSTONED until it is purged, so without the processed set below the
     * same segment would be re-scanned and re-deleted on every cycle for the rest of the
     * process's life.</p>
     *
     * <p>Marked once the attempt is over, not before it starts. A segment marked up front is
     * marked whether or not anything was deleted, so a segment the engine had not loaded yet
     * kept its terms in the filter for good - and was reported as processed with nothing
     * removed, which reads like a segment that had nothing to remove. An attempt that threw is
     * still marked: it may have deleted part of the segment's terms, and a Cuckoo delete
     * removes the first slot whose fingerprint matches, so repeating it would evict a live
     * term that happened to collide.</p>
     *
     * <p>What is deleted are the same items that were inserted. The hierarchy is
     * populated with extracted terms (the ingestion pipeline inserts
     * {@code TermExtractor.extractTerms} of each chunk's text and the query pipeline looks
     * the same terms up), not with storage keys, so the terms are re-derived from each
     * live entry's chunk text.</p>
     *
     * <h3>The per-cycle budget</h3>
     * <p>A segment is cleaned whole or not at all within a sweep, because a Cuckoo delete is not
     * idempotent and a segment left half-cleaned could not safely be resumed. So the budget
     * bounds when a sweep stops <em>starting</em> segments: once the entries removed reach it,
     * the remaining segments wait for the next sweep. One large segment can take a sweep past
     * the budget; what the budget prevents is a sweep that walks every tombstoned segment on
     * the node while holding the hierarchy's write lock over and over.</p>
     *
     * @param maxDeletions Entries after which no further segment is started
     * @return Number of deletions performed
     */
    int processTombstonedSegments(int maxDeletions) {
        int deleted = 0;

        List<Long> tombstoned = catalog.getSegmentsByState(LifecycleState.TOMBSTONED);
        for (Long segmentId : tombstoned) {
            if (deleted >= maxDeletions) {
                break;
            }
            if (cleanedTombstonedSegments.contains(segmentId)) {
                continue; // Already cleaned; a Cuckoo delete is not idempotent.
            }

            Segment segment = storageEngine.getSegment(segmentId);
            if (segment == null) {
                // Nothing was deleted, so retrying is safe, and the terms are still in the
                // filter until it is. Left marked, the segment would be reported as processed
                // having removed nothing.
                if (unreachableTombstonedSegments.add(segmentId)) {
                    LOGGER.warn("Tombstoned segment {} is not loaded, so its terms are still in "
                        + "the filter and every query they match still reaches it. The next "
                        + "cycle will try again.", segmentId);
                }
                continue;
            }
            unreachableTombstonedSegments.remove(segmentId);

            int segmentDeletions = cleanTombstonedSegment(segment, segmentId);
            cleanedTombstonedSegments.add(segmentId);

            // Unregister the filter after cleanup
            unregisterFilter(segmentId);
            tombstonedSegmentsProcessed.incrementAndGet();
            deleted += Math.max(0, segmentDeletions);

            if (segmentDeletions < 0) {
                LOGGER.error("Cleaning tombstoned segment {} failed partway. Its remaining terms "
                    + "stay in the filter, because a Cuckoo delete repeated over terms already "
                    + "removed would evict live ones that collide with them.", segmentId);
            } else {
                LOGGER.info("Processed tombstoned segment {} - {} terms deleted from filter",
                    segmentId, segmentDeletions);
            }
            listener.onSegmentFilterCleaned(segmentId);
        }

        totalDeletions.addAndGet(deleted);
        return deleted;
    }

    /**
     * Removes one tombstoned segment's terms from the filters.
     *
     * @param segment   The tombstoned segment, already resolved by the caller
     * @param segmentId The tombstoned segment's id
     * @return Number of terms removed, or -1 if the attempt failed partway
     */
    private int cleanTombstonedSegment(Segment segment, long segmentId) {
        try {
            List<byte[]> termsToDelete = collectSegmentTerms(segment);
            if (termsToDelete.isEmpty()) {
                return 0;
            }

            if (hierarchicalFilter != null) {
                // deleteFromHierarchy removes a term from the corpus filter only when this
                // segment's own filter held it, and returns the count of such terms. A term
                // still held by another segment's filter stays in the corpus level, which is
                // what keeps live segments queryable.
                int deleted = hierarchicalFilter.deleteFromHierarchy(
                    termsToDelete, String.valueOf(segmentId));
                LOGGER.debug("Deleted {} of {} terms from hierarchical filter for segment {}",
                    deleted, termsToDelete.size(), segmentId);
                return deleted;
            }

            SegmentFilter segmentFilter = findSegmentFilter(segmentId);
            if (segmentFilter == null) {
                return 0;
            }

            int deleted = 0;
            for (byte[] term : termsToDelete) {
                if (segmentFilter.filter.delete(term)) {
                    deleted++;
                }
            }
            LOGGER.debug("Deleted {} terms from segment filter for segment {}", deleted, segmentId);
            return deleted;

        } catch (Exception e) {
            LOGGER.warn("Error iterating segment {} for filter cleanup: {}",
                segmentId, e.getMessage());
            return -1;
        }
    }

    /**
     * Collects the distinct filter terms a segment contributed.
     *
     * <p>Terms are re-derived from each entry's chunk text with the same
     * {@link TermExtractor} the ingestion pipeline used to insert them. Deleting anything
     * else - a storage key, for instance - would be a delete of an item that was never
     * inserted, and a Cuckoo delete removes the first slot whose fingerprint matches, so
     * it would evict a colliding live term instead.</p>
     *
     * @param segment The segment to scan
     * @return Distinct term bytes, empty if the segment holds no decodable chunk text
     */
    private List<byte[]> collectSegmentTerms(Segment segment) {
        Set<String> terms = new HashSet<>();
        try (Segment.EntryIterator iter = segment.liveEntries()) {
            while (iter.hasNext()) {
                SegmentEntry entry = iter.next();
                String text = decodeChunkText(entry.value());
                if (text != null && !text.isEmpty()) {
                    terms.addAll(TermExtractor.extractTerms(text));
                }
            }
        }

        List<byte[]> termBytes = new ArrayList<>(terms.size());
        for (String term : terms) {
            termBytes.add(term.getBytes(StandardCharsets.UTF_8));
        }
        return termBytes;
    }

    /**
     * Decodes the chunk text from a stored entry value.
     *
     * <p>Value format (little-endian), as written by the ingestion pipeline:
     * {@code [dimension:4][vector:dimension*4][text:remaining bytes]}.</p>
     *
     * @param value The stored value
     * @return The chunk text, or null if the value carries none
     */
    private String decodeChunkText(byte[] value) {
        if (value == null || value.length < Integer.BYTES) {
            return null;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            int dimension = buffer.getInt();
            if (dimension <= 0 || dimension > MAX_VECTOR_DIMENSION) {
                return null;
            }

            int textOffset = Integer.BYTES + dimension * Float.BYTES;
            if (textOffset >= value.length) {
                return null; // Vector-only value, no text to extract terms from
            }

            return new String(value, textOffset, value.length - textOffset,
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.trace("Could not decode chunk text from entry value: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Finds a segment filter by segment ID.
     */
    private SegmentFilter findSegmentFilter(long segmentId) {
        for (SegmentFilter sf : segmentFilters) {
            if (sf.segmentId == segmentId) {
                return sf;
            }
        }
        return null;
    }

    // ==================== Statistics ====================

    /**
     * Returns the total number of deletions performed.
     *
     * @return Total deletions
     */
    public long getTotalDeletions() {
        return totalDeletions.get();
    }

    /**
     * Returns the timestamp of the last maintenance cycle.
     *
     * @return Last maintenance time in epoch milliseconds
     */
    public long getLastMaintenanceTime() {
        return lastMaintenanceTime.get();
    }

    /**
     * Returns the total number of tombstoned segments processed.
     *
     * @return Tombstoned segments processed
     */
    public long getTombstonedSegmentsProcessed() {
        return tombstonedSegmentsProcessed.get();
    }

    // ==================== Configuration Getters/Setters ====================

    /**
     * Returns the maintenance interval in milliseconds.
     *
     * @return Maintenance interval
     */
    public long getMaintenanceIntervalMs() {
        return maintenanceIntervalMs;
    }

    /**
     * Sets the maintenance interval in milliseconds.
     *
     * @param intervalMs The new interval
     * @throws IllegalArgumentException if interval is invalid
     */
    public void setMaintenanceIntervalMs(long intervalMs) {
        validateMaintenanceInterval(intervalMs);
        this.maintenanceIntervalMs = intervalMs;
    }

    /**
     * Returns the maximum deletions per cycle.
     *
     * @return Max deletions per cycle
     */
    public int getMaxDeletionsPerCycle() {
        return maxDeletionsPerCycle;
    }

    /**
     * Sets the maximum deletions per cycle.
     *
     * @param max The new maximum
     * @throws IllegalArgumentException if max is invalid
     */
    public void setMaxDeletionsPerCycle(int max) {
        validateMaxDeletions(max);
        this.maxDeletionsPerCycle = max;
    }

    /**
     * Returns the false positive rate threshold.
     *
     * @return FP rate threshold
     */
    public double getFpRateThreshold() {
        return fpRateThreshold;
    }

    /**
     * Sets the false positive rate threshold.
     *
     * @param threshold The new threshold
     * @throws IllegalArgumentException if threshold is invalid
     */
    public void setFpRateThreshold(double threshold) {
        validateFpRateThreshold(threshold);
        this.fpRateThreshold = threshold;
    }

    // ==================== Listener Interface ====================

    /**
     * Callback interface for maintenance events.
     */
    @FunctionalInterface
    public interface MaintenanceListener {

        /**
         * Called when a segment's filter has been cleaned after tombstoning.
         *
         * @param segmentId The segment ID
         */
        void onSegmentFilterCleaned(long segmentId);
    }

    // ==================== Internal Classes ====================

    /**
     * Holder for a segment's Cuckoo filter.
     */
    private static class SegmentFilter {

        final long segmentId;
        final CuckooFilter filter;

        SegmentFilter(long segmentId, CuckooFilter filter) {
            this.segmentId = segmentId;
            this.filter = filter;
        }
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
            "CuckooMaintenanceWorker{running=%s, filters=%d, tombstonedSegmentsProcessed=%d, " +
            "totalDeletions=%d}",
            running.get(), segmentFilters.size(), tombstonedSegmentsProcessed.get(),
            totalDeletions.get());
    }
}
