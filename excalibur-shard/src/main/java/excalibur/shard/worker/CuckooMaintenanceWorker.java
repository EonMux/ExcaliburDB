package excalibur.shard.worker;


import excalibur.filter.CuckooFilter;
import excalibur.filter.HierarchicalCuckooFilter;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.store.format.SegmentEntry;
import io.excalibur.core.text.TermExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
 * <p>This worker performs background maintenance on Cuckoo filters associated
 * segments, including:</p>
 * <ul>
 *   <li>Deleting filter entries for tombstoned segments</li>
 *   <li>Propagating removals up the filter hierarchy</li>
 *   <li>Monitoring filter health and false positive rates</li>
 * </ul>
 *
 * <h2>Hierarchical Filter Maintenance</h2>
 * <p>When a segment is tombstoned:</p>
 * <ol>
 *   <li>Entries are deleted from the segment-level filter</li>
 *   <li>Deletions are propagated to parent filters in the hierarchy</li>
 *   <li>False positive rate is recalculated and logged</li>
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
 *   <li>Max deletions per cycle: 1000 to 10000 (default 1000)</li>
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
     * Default batch size for hierarchy propagation.
     */
    private static final int DEFAULT_HIERARCHY_BATCH_SIZE = 100;

    /**
     * Largest embedding dimension accepted when decoding a stored chunk value.
     * Guards against treating an unrelated value's leading bytes as a length.
     */
    private static final int MAX_VECTOR_DIMENSION = 65536;

    // ==================== Instance Fields ====================

    private final SegmentCatalog catalog;
    private final StorageEngine storageEngine;
    private final MaintenanceListener listener;
    private final CopyOnWriteArrayList<FilterEntry> pendingDeletions;
    private final CopyOnWriteArrayList<SegmentFilter> segmentFilters;

    /**
     * Segments whose filter entries have already been removed.
     * A Cuckoo delete is not idempotent, so each tombstoned segment is cleaned once.
     */
    private final Set<Long> cleanedTombstonedSegments;

    /**
     * Hierarchical Cuckoo filter for corpus → cluster → segment deletion propagation.
     * May be null if hierarchy is not configured.
     */
    private volatile HierarchicalCuckooFilter hierarchicalFilter;

    private final AtomicBoolean running;
    private final AtomicBoolean stopped;

    private final AtomicLong totalDeletions;
    private final AtomicLong totalFailedDeletions;
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
        this.pendingDeletions = new CopyOnWriteArrayList<>();
        this.segmentFilters = new CopyOnWriteArrayList<>();
        this.cleanedTombstonedSegments = ConcurrentHashMap.newKeySet();
        this.running = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);
        this.totalDeletions = new AtomicLong(0);
        this.totalFailedDeletions = new AtomicLong(0);
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
     * segment → cluster → corpus. This ensures no stale false positives
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
     * <p>After starting, the worker will periodically check for:
     * <ul>
     *   <li>Tombstoned segments that need filter cleanup</li>
     *   <li>Pending deletions from the queue</li>
     *   <li>Filter health (false positive rates)</li>
     * </ul>
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
     * Stops the background maintenance worker.
     *
     * <p>This method signals the worker to stop and waits for it to complete
     * any in-progress maintenance operations.</p>
     */
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            running.set(false);

            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            LOGGER.info("Cuckoo maintenance worker stopped. Total deletions: {}, failed: {}",
                totalDeletions.get(), totalFailedDeletions.get());
        }
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
     * Queues a deletion for the next maintenance cycle.
     *
     * <p>This is typically called when a segment is tombstoned.
     * All keys in the segment should be deleted from the filter.</p>
     *
     * @param segmentId The segment ID
     * @param keys      The keys to delete from the filter
     */
    public void queueDeletions(long segmentId, Iterable<byte[]> keys) {
        int count = 0;
        for (byte[] key : keys) {
            pendingDeletions.add(new FilterEntry(segmentId, key));
            count++;
        }
        if (count > 0) {
            LOGGER.debug("Queued {} deletions for segment {}", count, segmentId);
        }
    }

    /**
     * Queues a single deletion for the next maintenance cycle.
     *
     * @param segmentId The segment ID
     * @param key       The key to delete from the filter
     */
    public void queueDeletion(long segmentId, byte[] key) {
        if (key != null) {
            pendingDeletions.add(new FilterEntry(segmentId, key));
        }
    }

    /**
     * Returns the number of pending deletions in the queue.
     *
     * @return Pending deletion count
     */
    public int getPendingDeletionCount() {
        return pendingDeletions.size();
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
     * <p>This method is called periodically by the scheduler and:</p>
     * <ol>
     *   <li>Processes tombstoned segments</li>
     *   <li>Executes pending deletions</li>
     *   <li>Propagates deletions up the hierarchy</li>
     * </ol>
     */
    private void performMaintenanceCycle() {
        if (!running.get() || stopped.get()) {
            return;
        }

        long startTime = System.currentTimeMillis();
        int deletionsThisCycle = 0;
        int failedThisCycle = 0;

        try {
            // Step 1: Process tombstoned segments
            deletionsThisCycle += processTombstonedSegments();

            // Step 2: Process pending deletions queue
            int processed = processPendingDeletions(maxDeletionsPerCycle - deletionsThisCycle);
            deletionsThisCycle += processed;

            // Step 3: Propagate deletions up the hierarchy
            propagateDeletionsUpHierarchy();

            // Step 4: Check filter health
            checkFilterHealth();

            // Update statistics
            totalDeletions.addAndGet(deletionsThisCycle);
            lastMaintenanceTime.set(System.currentTimeMillis());

            long duration = System.currentTimeMillis() - startTime;
            if (deletionsThisCycle > 0 || LOGGER.isDebugEnabled()) {
                LOGGER.debug("Maintenance cycle complete: {} deletions in {}ms",
                    deletionsThisCycle, duration);
            }

        } catch (Exception e) {
            LOGGER.error("Error during maintenance cycle", e);
        }
    }

    /**
     * Processes tombstoned segments and deletes their filter entries.
     *
     * <p>Each tombstoned segment is cleaned <strong>once</strong>: the catalog keeps a
     * segment in TOMBSTONED until it is purged, so without the processed set below the
     * same segment would be re-scanned and re-deleted on every cycle for the rest of the
     * process's life.</p>
     *
     * <p>What is deleted are the same items that were inserted. The hierarchy is
     * populated with extracted terms (the ingestion pipeline inserts
     * {@code TermExtractor.extractTerms} of each chunk's text and the query pipeline looks
     * the same terms up), not with storage keys, so the terms are re-derived from each
     * live entry's chunk text.</p>
     *
     * @return Number of deletions performed
     */
    private int processTombstonedSegments() {
        int totalDeletions = 0;

        List<Long> tombstoned = catalog.getSegmentsByState(LifecycleState.TOMBSTONED);
        for (Long segmentId : tombstoned) {
            if (!cleanedTombstonedSegments.add(segmentId)) {
                continue; // Already cleaned; a Cuckoo delete is not idempotent.
            }

            int segmentDeletions = cleanTombstonedSegment(segmentId);

            // Unregister the filter after cleanup
            unregisterFilter(segmentId);
            tombstonedSegmentsProcessed.incrementAndGet();
            totalDeletions += segmentDeletions;

            LOGGER.info("Processed tombstoned segment {} - {} terms deleted from filter",
                segmentId, segmentDeletions);
            listener.onSegmentFilterCleaned(segmentId);
        }

        return totalDeletions;
    }

    /**
     * Removes one tombstoned segment's terms from the filters.
     *
     * @param segmentId The tombstoned segment
     * @return Number of terms removed
     */
    private int cleanTombstonedSegment(long segmentId) {
        Segment segment = storageEngine.getSegment(segmentId);
        if (segment == null) {
            return 0;
        }

        try {
            List<byte[]> termsToDelete = collectSegmentTerms(segment);
            if (termsToDelete.isEmpty()) {
                return 0;
            }

            if (hierarchicalFilter != null) {
                // deleteFromHierarchy removes a term from the cluster and corpus filters
                // only when this segment's own filter held it, and returns the count of
                // such terms. A term still held by another segment's filter stays in the
                // shared levels, which is what keeps live segments queryable.
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
            return 0;
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
     * Processes pending deletions from the queue.
     *
     * @param maxDeletions Maximum deletions to process
     * @return Number of deletions performed
     */
    private int processPendingDeletions(int maxDeletions) {
        if (maxDeletions <= 0 || pendingDeletions.isEmpty()) {
            return 0;
        }

        int processed = 0;
        int failed = 0;

        // Process up to maxDeletions entries
        while (processed < maxDeletions && !pendingDeletions.isEmpty()) {
            FilterEntry entry = pendingDeletions.remove(0);
            if (entry == null) {
                break;
            }

            SegmentFilter segmentFilter = findSegmentFilter(entry.segmentId);
            if (segmentFilter == null) {
                // Filter no longer exists, skip
                continue;
            }

            boolean deleted = segmentFilter.filter.delete(entry.key);
            if (deleted) {
                processed++;
            } else {
                failed++;
                LOGGER.trace("Failed to delete entry from filter for segment {}", entry.segmentId);
            }
        }

        if (failed > 0) {
            totalFailedDeletions.addAndGet(failed);
        }

        return processed;
    }

    /**
     * Propagates deletions up the filter hierarchy.
     *
     * <p>This method processes pending deletions through the hierarchical Cuckoo filter,
     * propagating from segment level to cluster level to corpus level.</p>
     *
     * <p>If no hierarchical filter is configured, this method does nothing.</p>
     */
    private void propagateDeletionsUpHierarchy() {
        if (hierarchicalFilter == null) {
            // No hierarchy configured, nothing to propagate
            return;
        }

        if (pendingDeletions.isEmpty()) {
            return;
        }

        // Process pending deletions through hierarchy
        int remaining = pendingDeletions.size();
        int batchSize = Math.min(DEFAULT_HIERARCHY_BATCH_SIZE, remaining);
        int processed = 0;

        for (int i = 0; i < batchSize && !pendingDeletions.isEmpty(); i++) {
            FilterEntry entry = pendingDeletions.remove(0);
            if (entry == null || entry.key == null) {
                continue;
            }

            boolean deleted = hierarchicalFilter.deleteFromHierarchy(
                entry.key, String.valueOf(entry.segmentId));
            if (deleted) {
                processed++;
            }
        }

        if (processed > 0) {
            LOGGER.debug("Propagated {} deletions through hierarchy, {} pending",
                processed, pendingDeletions.size());
        }
    }

    /**
     * Checks filter health by monitoring false positive rates.
     */
    private void checkFilterHealth() {
        for (SegmentFilter sf : segmentFilters) {
            // Get filter statistics
            long count = sf.filter.count();
            if (count <= 0) {
                continue;
            }

            // Note: Actual FP rate measurement would require sampling
            // For now, we log filter size for monitoring
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Filter health check: segment {} has {} entries",
                    sf.segmentId, count);
            }
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
     * Returns the total number of failed deletions.
     *
     * @return Total failed deletions
     */
    public long getTotalFailedDeletions() {
        return totalFailedDeletions.get();
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
     * Holder for a filter entry in the deletion queue.
     */
    private static class FilterEntry {

        final long segmentId;
        final byte[] key;

        FilterEntry(long segmentId, byte[] key) {
            this.segmentId = segmentId;
            this.key = key;
        }
    }

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
            "CuckooMaintenanceWorker{running=%s, filters=%d, pendingDeletions=%d, " +
            "totalDeletions=%d, failedDeletions=%d}",
            running.get(), segmentFilters.size(), pendingDeletions.size(),
            totalDeletions.get(), totalFailedDeletions.get());
    }
}
