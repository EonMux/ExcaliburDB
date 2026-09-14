package io.excalibur.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background compaction worker for the ExcaliburDB storage engine.
 *
 * <p>This class implements a virtual-thread-based background worker that merges
 * write log entries into segment files and removes tombstoned entries. Compaction
 * runs continuously in the background without blocking reads or writes.</p>
 *
 * <h2>Compaction Strategy</h2>
 * <p>The worker uses a copy-on-write approach to ensure non-blocking operation:</p>
 * <ol>
 *   <li>Identify segments that need compaction based on tombstone ratio</li>
 *   <li>Build the compacted segment at {@code <segment>.dat.compact}</li>
 *   <li>Copy all live (non-tombstoned) entries into it</li>
 *   <li>Rename it onto the canonical {@code segment_&lt;index&gt;.dat} with a single
 *       atomic move, which both installs the new file and removes the old one</li>
 *   <li>Swap the registry entry, then retire the old segment</li>
 * </ol>
 *
 * <h2>When Compaction is Triggered</h2>
 * <p>A segment is a candidate for compaction when:</p>
 * <ul>
 *   <li>Tombstone ratio reaches the configured threshold, {@value #DEFAULT_TOMBSTONE_RATIO_THRESHOLD}
 *       unless the caller says otherwise</li>
 *   <li>Write log size reaches the configured threshold, 32 MB unless the caller says otherwise</li>
 *   <li>Explicitly requested via {@link #requestCompaction(Segment)} or
 *       {@link #compactNow(Segment, boolean)}</li>
 * </ul>
 *
 * <p>The four policy values are constructor arguments rather than compile-time constants.
 * This module has no configuration dependency, so whoever builds the worker is the one that
 * reads {@code excalibur.shard.compaction-*} and hands the numbers over.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe:</p>
 * <ul>
 *   <li>Runs in a dedicated virtual thread</li>
 *   <li>Uses atomic operations for state management</li>
 *   <li>Compaction operations do not block segment reads or writes</li>
 *   <li>Segment swap is atomic via {@link CompactionListener#onCompactionComplete(Segment, Segment)}</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Compaction interval: {@value #DEFAULT_COMPACTION_INTERVAL_MS} ms (configurable, min 100ms)</li>
 *   <li>Tombstone threshold: 0.0 to 1.0 (default 0.2)</li>
 *   <li>Write log threshold: 1 MB to 1 GB (default 32 MB)</li>
 *   <li>Maximum concurrent compactions: 1 to 16 (default 4)</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create a compaction worker
 * CompactionWorker worker = new CompactionWorker(arena, (oldSegment, newSegment) -> {
 *     // Called when compaction completes, to move the registry onto the replacement
 *     segments.put(index, newSegment);
 *     return CompactionOutcome.INSTALLED;
 * });
 *
 * // Register segments for monitoring
 * worker.registerSegment(segment1);
 * worker.registerSegment(segment2);
 *
 * // Start the background worker
 * worker.start();
 *
 * // Later, shut down
 * worker.stop();
 * }</pre>
 *
 * @author ExcaliburDB
 * @since 2.0
 * @see Segment
 * @see WriteLog
 */
public class CompactionWorker {

    private static final Logger LOGGER = Logger.getLogger(CompactionWorker.class.getName());

    // ==================== Configuration Constants ====================

    /**
     * Default interval between compaction checks in milliseconds.
     *
     * <p>Domain constraint: Must be at least 100ms to avoid excessive CPU usage.
     * The interval balances responsiveness with resource efficiency.</p>
     */
    public static final long DEFAULT_COMPACTION_INTERVAL_MS = 5000; // 5 seconds

    /**
     * Minimum allowed compaction interval in milliseconds.
     *
     * <p>Domain constraint: 100ms minimum prevents busy-waiting behavior.</p>
     */
    public static final long MIN_COMPACTION_INTERVAL_MS = 100;

    /**
     * Default tombstone ratio threshold for triggering compaction.
     *
     * <p>When the ratio of tombstoned entries to total entries exceeds this
     * threshold, the segment becomes a candidate for compaction.</p>
     *
     * <p>Domain constraint: 0.0 to 1.0 (20% default)</p>
     */
    public static final double DEFAULT_TOMBSTONE_RATIO_THRESHOLD = 0.20;

    /**
     * Default write log size threshold in bytes for triggering compaction.
     *
     * <p>When the write log file size exceeds this threshold, the segment
     * becomes a candidate for compaction.</p>
     *
     * <p>Domain constraint: 1 MB to 1 GB (32 MB default)</p>
     */
    public static final long DEFAULT_WRITE_LOG_SIZE_THRESHOLD = 32L * 1024 * 1024; // 32 MB

    /**
     * Maximum number of concurrent compaction operations.
     *
     * <p>Domain constraint: 1 to 16 (4 default). Too many concurrent
     * compactions can cause memory pressure and I/O contention.</p>
     */
    public static final int DEFAULT_MAX_CONCURRENT_COMPACTIONS = 4;

    /**
     * Fewest segment rewrites that may run in one sweep.
     */
    public static final int MIN_CONCURRENT_COMPACTIONS = 1;

    /**
     * Most segment rewrites that may run in one sweep.
     *
     * <p>Every concurrent rewrite maps a whole replacement segment out of the engine's arena
     * and copies every live entry into it, so the ceiling bounds memory pressure and disk
     * contention rather than CPU. It is public because the configuration layer accepts this
     * value from an operator and has to be able to refuse it against the property name.</p>
     */
    public static final int MAX_CONCURRENT_COMPACTIONS = 16;

    /**
     * Minimum write log size threshold in bytes.
     */
    public static final long MIN_WRITE_LOG_SIZE_THRESHOLD = 1024 * 1024; // 1 MB

    /**
     * Maximum write log size threshold in bytes.
     */
    public static final long MAX_WRITE_LOG_SIZE_THRESHOLD = 1024L * 1024 * 1024; // 1 GB

    // ==================== Instance Fields ====================

    private final SegmentRewriter rewriter;
    private final CopyOnWriteArrayList<Segment> registeredSegments;
    private final AtomicBoolean running;
    private final AtomicBoolean stopped;
    private final AtomicLong compactionCount;
    private final AtomicLong lastCompactionTime;
    private final AtomicLong bytesReclaimed;

    private volatile long compactionIntervalMs;

    /**
     * How long the loop waits before its first check.
     *
     * <p>Zero unless configured. Set from {@code excalibur.shard.compaction-delay-ms}, which was
     * documented, shipped, validated and logged at startup while reaching no compaction code -
     * an operator who set it saw it echoed back and got the same schedule as before.</p>
     */
    private volatile long startDelayMs;
    private volatile double tombstoneRatioThreshold;
    private volatile long writeLogSizeThreshold;
    private volatile int maxConcurrentCompactions;

    private volatile Thread workerThread;

    /**
     * Sets how long the loop waits before its first compaction check.
     *
     * <p>Takes effect on the next {@link #start()}.</p>
     *
     * @param startDelayMs Delay in milliseconds; negative is treated as none
     * @return this worker, for chaining
     */
    public CompactionWorker withStartDelayMs(long startDelayMs) {
        this.startDelayMs = Math.max(0, startDelayMs);
        return this;
    }

    /**
     * Returns how long the loop waits before its first compaction check.
     *
     * @return The delay in milliseconds
     */
    public long getStartDelayMs() {
        return startDelayMs;
    }

    // ==================== Constructors ====================

    /**
     * Creates a new CompactionWorker with default configuration.
     *
     * @param arena    The Arena for allocating new segment memory (must not be null)
     * @param listener Callback for compaction completion events (must not be null)
     * @throws NullPointerException if arena or listener is null
     */
    public CompactionWorker(Arena arena, CompactionListener listener) {
        this(arena, listener, DEFAULT_COMPACTION_INTERVAL_MS,
             DEFAULT_TOMBSTONE_RATIO_THRESHOLD, DEFAULT_WRITE_LOG_SIZE_THRESHOLD,
             DEFAULT_MAX_CONCURRENT_COMPACTIONS);
    }

    /**
     * Creates a new CompactionWorker with custom configuration.
     *
     * @param arena                     The Arena for allocating new segment memory
     * @param listener                  Callback for compaction completion events
     * @param compactionIntervalMs      Interval between compaction checks (ms)
     * @param tombstoneRatioThreshold   Tombstone ratio threshold (0.0 to 1.0)
     * @param writeLogSizeThreshold     Write log size threshold in bytes
     * @param maxConcurrentCompactions  Maximum concurrent compaction operations
     * @throws NullPointerException     if arena or listener is null
     * @throws IllegalArgumentException if configuration values are out of range
     */
    public CompactionWorker(Arena arena, CompactionListener listener,
                            long compactionIntervalMs, double tombstoneRatioThreshold,
                            long writeLogSizeThreshold, int maxConcurrentCompactions) {
        if (arena == null) {
            throw new NullPointerException("Arena cannot be null");
        }
        if (listener == null) {
            throw new NullPointerException("Listener cannot be null");
        }

        // Validate configuration
        validateCompactionInterval(compactionIntervalMs);
        validateTombstoneRatioThreshold(tombstoneRatioThreshold);
        validateWriteLogSizeThreshold(writeLogSizeThreshold);
        validateMaxConcurrentCompactions(maxConcurrentCompactions);

        this.rewriter = new SegmentRewriter(arena, listener);
        this.registeredSegments = new CopyOnWriteArrayList<>();
        this.running = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);
        this.compactionCount = new AtomicLong(0);
        this.lastCompactionTime = new AtomicLong(0);
        this.bytesReclaimed = new AtomicLong(0);

        this.compactionIntervalMs = compactionIntervalMs;
        this.tombstoneRatioThreshold = tombstoneRatioThreshold;
        this.writeLogSizeThreshold = writeLogSizeThreshold;
        this.maxConcurrentCompactions = maxConcurrentCompactions;

        LOGGER.log(Level.INFO, "CompactionWorker created with interval={0}ms, tombstoneThreshold={1}, " +
            "writeLogThreshold={2}MB, maxConcurrent={3}",
            new Object[] { compactionIntervalMs, tombstoneRatioThreshold,
                writeLogSizeThreshold / (1024 * 1024), maxConcurrentCompactions });
    }

    // ==================== Configuration Validation ====================

    private void validateCompactionInterval(long intervalMs) {
        if (intervalMs < MIN_COMPACTION_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "Compaction interval must be at least " + MIN_COMPACTION_INTERVAL_MS + "ms, got: " + intervalMs);
        }
    }

    private void validateTombstoneRatioThreshold(double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException(
                "Tombstone ratio threshold must be between 0.0 and 1.0, got: " + threshold);
        }
    }

    private void validateWriteLogSizeThreshold(long threshold) {
        if (threshold < MIN_WRITE_LOG_SIZE_THRESHOLD || threshold > MAX_WRITE_LOG_SIZE_THRESHOLD) {
            throw new IllegalArgumentException(
                "Write log size threshold must be between " + MIN_WRITE_LOG_SIZE_THRESHOLD +
                " and " + MAX_WRITE_LOG_SIZE_THRESHOLD + " bytes, got: " + threshold);
        }
    }

    private void validateMaxConcurrentCompactions(int max) {
        if (max < MIN_CONCURRENT_COMPACTIONS || max > MAX_CONCURRENT_COMPACTIONS) {
            throw new IllegalArgumentException(
                "Max concurrent compactions must be between " + MIN_CONCURRENT_COMPACTIONS
                    + " and " + MAX_CONCURRENT_COMPACTIONS + ", got: " + max);
        }
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts the background compaction worker.
     *
     * <p>This method creates a virtual thread that continuously monitors
     * registered segments and performs compaction as needed. The worker
     * runs until {@link #stop()} is called.</p>
     *
     * <p>If the worker is already running, this method does nothing.</p>
     *
     * @return true if the worker was started, false if already running
     */
    public boolean start() {
        if (running.compareAndSet(false, true)) {
            stopped.set(false);

            // Create and start a virtual thread
            workerThread = Thread.ofVirtual().name("excalibur-compaction-worker").start(() -> {
                LOGGER.log(Level.INFO, "Compaction worker started");
                runCompactionLoop();
            });

            return true;
        }
        return false;
    }

    /**
     * Stops the background compaction worker.
     *
     * <p>This method signals the worker to stop and waits for it to complete
     * any in-progress compaction operations. After stopping, the worker
     * cannot be restarted.</p>
     *
     * <p>If the worker is not running, this method does nothing.</p>
     */
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            running.set(false);

            if (workerThread != null) {
                workerThread.interrupt();
                try {
                    workerThread.join(5000); // Wait up to 5 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            LOGGER.log(Level.INFO, "Compaction worker stopped. Total compactions: {0}, bytes reclaimed: {1}",
                new Object[] { compactionCount.get(), bytesReclaimed.get() });
        }
    }

    /**
     * Checks if the worker is currently running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Segment Registration ====================

    /**
     * Registers a segment for compaction monitoring.
     *
     * <p>Registered segments are periodically checked for compaction
     * eligibility based on tombstone ratio and write log size.</p>
     *
     * @param segment The segment to register
     * @throws NullPointerException if segment is null
     */
    public void registerSegment(Segment segment) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        if (!registeredSegments.contains(segment)) {
            registeredSegments.add(segment);
            LOGGER.log(Level.FINE, "Registered segment for compaction: {0}", segment.path());
        }
    }

    /**
     * Unregisters a segment from compaction monitoring.
     *
     * @param segment The segment to unregister
     */
    public void unregisterSegment(Segment segment) {
        if (segment != null && registeredSegments.remove(segment)) {
            LOGGER.log(Level.FINE, "Unregistered segment from compaction: {0}", segment.path());
        }
    }

    /**
     * Returns the number of registered segments.
     *
     * @return Registered segment count
     */
    public int getRegisteredSegmentCount() {
        return registeredSegments.size();
    }

    // ==================== Manual Compaction ====================

    /**
     * Requests immediate compaction of a specific segment.
     *
     * <p>This method queues the segment for compaction regardless of
     * whether it meets the normal thresholds. Use sparingly as it
     * may cause unnecessary I/O if the segment doesn't need compaction.</p>
     *
     * @param segment The segment to compact
     * @throws NullPointerException if segment is null
     */
    public void requestCompaction(Segment segment) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }

        // Run compaction in a virtual thread
        Thread.startVirtualThread(() -> {
            try {
                compactNow(segment);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Manual compaction failed for segment: " + segment.path(), e);
            }
        });
    }

    /**
     * Compacts a segment on the calling thread and returns once it is done.
     *
     * <p>The same work {@link #requestCompaction(Segment)} hands to a virtual thread, for callers
     * that need the segment compacted before they continue rather than merely scheduled. The
     * listener is notified before this returns.</p>
     *
     * @param segment The segment to compact
     * @throws NullPointerException if segment is null
     * @throws IOException if the compaction fails
     */
    public void compactNow(Segment segment) throws IOException {
        compactNow(segment, true);
    }

    /**
     * Compacts a segment on the calling thread, optionally only if the policy agrees.
     *
     * <p>Safe to call while the background loop is running. Both paths take the segment's write
     * lock for the whole compaction and re-check under it that the segment is still open and not
     * splitting, so two compactions of one segment serialise and the second finds a segment the
     * first has already closed and declines. Nothing here is a second guard: it is the same one
     * the loop uses.</p>
     *
     * @param segment The segment to compact
     * @param force   true to compact regardless of the thresholds, false to compact only a
     *                segment {@link #isCompactionWorthwhile(Segment)} accepts
     * @return true if the segment was actually rewritten, false if it was skipped
     * @throws NullPointerException if segment is null
     * @throws IOException if the compaction fails
     */
    public boolean compactNow(Segment segment, boolean force) throws IOException {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        if (!force && !isCompactionWorthwhile(segment)) {
            LOGGER.log(Level.FINE, "Segment {0} is below the compaction thresholds; skipping",
                segment.path());
            return false;
        }
        return compactSegment(segment);
    }

    /**
     * Whether the configured thresholds say a segment is worth rewriting.
     *
     * <p>A compaction copies every live entry, so it only repays itself once there is enough
     * dead weight to remove. This is the same judgement the background loop makes, exposed so a
     * caller asking for compaction on demand can honour the deployment's policy rather than
     * inventing one of its own.</p>
     *
     * @param segment The segment to judge
     * @return true if the segment meets the tombstone ratio or write log size threshold
     * @throws NullPointerException if segment is null
     */
    public boolean isCompactionWorthwhile(Segment segment) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        return !segment.isClosed() && shouldCompact(segment);
    }

    // ==================== Main Compaction Loop ====================

    /**
     * Main compaction loop that runs in the background virtual thread.
     */
    private void runCompactionLoop() {
        // The configured delay before the first check. It exists so an operator can keep
        // compaction off the back of a bulk load: the work is not urgent, and contending with a
        // load for the same disk makes both slower.
        if (startDelayMs > 0) {
            try {
                Thread.sleep(startDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        while (running.get() && !stopped.get()) {
            try {
                // Find and compact eligible segments
                List<Segment> candidates = findCompactionCandidates();

                if (!candidates.isEmpty()) {
                    LOGGER.log(Level.FINE, "Found {0} segments eligible for compaction", candidates.size());

                    // Compact up to maxConcurrentCompactions at a time
                    int compacted = 0;
                    for (Segment segment : candidates) {
                        if (!running.get() || stopped.get()) {
                            break;
                        }
                        if (compacted >= maxConcurrentCompactions) {
                            break;
                        }

                        try {
                            compactSegment(segment);
                            compacted++;
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Compaction failed for segment: " + segment.path(), e);
                        }
                    }
                }

                // Sleep until next check
                Thread.sleep(compactionIntervalMs);

            } catch (InterruptedException e) {
                // Expected during shutdown
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in compaction loop", e);
                // Continue running despite errors
            }
        }

        LOGGER.log(Level.INFO, "Compaction loop terminated");
    }

    // ==================== Compaction Candidate Detection ====================

    /**
     * Finds segments that are eligible for compaction.
     *
     * <p>A segment is eligible if:</p>
     * <ul>
     *   <li>It is not closed</li>
     *   <li>It has tombstoned entries exceeding the threshold ratio, OR</li>
     *   <li>It has a write log exceeding the size threshold</li>
     * </ul>
     *
     * @return List of segments eligible for compaction
     */
    private List<Segment> findCompactionCandidates() {
        List<Segment> candidates = new ArrayList<>();

        for (Segment segment : registeredSegments) {
            if (segment.isClosed()) {
                continue;
            }

            if (shouldCompact(segment)) {
                candidates.add(segment);
            }
        }

        // Sort by estimated reclaimable bytes (descending) to prioritize segments
        // that will free the most space
        candidates.sort((a, b) -> Long.compare(
            b.estimatedReclaimableBytes(),
            a.estimatedReclaimableBytes()
        ));

        return candidates;
    }

    /**
     * Determines if a segment should be compacted.
     *
     * @param segment The segment to check
     * @return true if the segment should be compacted
     */
    private boolean shouldCompact(Segment segment) {
        // Check tombstone ratio
        int entryCount = segment.entryCount();
        if (entryCount > 0) {
            int tombstoneCount = segment.tombstoneCount();
            double tombstoneRatio = (double) tombstoneCount / entryCount;
            if (tombstoneRatio >= tombstoneRatioThreshold) {
                LOGGER.log(Level.FINE, "Segment {0} exceeds tombstone ratio: {1} >= {2}",
                    new Object[] { segment.path(), tombstoneRatio, tombstoneRatioThreshold });
                return true;
            }
        }

        // Check write log size. The logical write offset is used, not the file size:
        // the log file is pre-allocated to its maximum size and is sparse, so its
        // on-disk size says nothing about how much log there is to reclaim.
        long writeLogSize = writeLogSize(segment);
        if (writeLogSize >= writeLogSizeThreshold) {
            LOGGER.log(Level.FINE, "Segment {0} write log exceeds size: {1} >= {2}",
                new Object[] { segment.path(), writeLogSize, writeLogSizeThreshold });
            return true;
        }

        return false;
    }

    /**
     * Returns how many bytes of write log a segment has accumulated.
     *
     * @param segment The segment
     * @return Logical write log size in bytes, or 0 if the segment has no log
     */
    private long writeLogSize(Segment segment) {
        WriteLog writeLog = segment.writeLog();
        return writeLog != null ? writeLog.writeOffset() : 0L;
    }

    // ==================== Core Compaction Logic ====================

    /**
     * Compacts a single segment and credits the run's statistics with what it reclaimed.
     *
     * <p>The rewrite itself belongs to {@link SegmentRewriter}; what is here is the counting,
     * which only a completed and installed rewrite is entitled to. A skipped or refused one
     * reclaimed nothing, whatever work it did on the way to finding that out.</p>
     *
     * @param segment The segment to compact
     * @return true if the segment was rewritten and the replacement installed, false if the
     *         segment was skipped as not compactable or the listener did not install the swap
     * @throws IOException if compaction fails
     */
    private boolean compactSegment(Segment segment) throws IOException {
        SegmentRewriter.Result result = rewriter.rewrite(segment);
        if (!result.installed()) {
            return false;
        }

        compactionCount.incrementAndGet();
        lastCompactionTime.set(System.currentTimeMillis());
        bytesReclaimed.addAndGet(result.bytesReclaimed());
        return true;
    }


    // ==================== Statistics ====================

    /**
     * Returns the total number of compaction operations performed.
     *
     * @return Compaction count
     */
    public long getCompactionCount() {
        return compactionCount.get();
    }

    /**
     * Returns the timestamp of the last compaction.
     *
     * @return Last compaction time in epoch milliseconds, or 0 if never
     */
    public long getLastCompactionTime() {
        return lastCompactionTime.get();
    }

    /**
     * Returns the total bytes reclaimed by compaction.
     *
     * @return Bytes reclaimed
     */
    public long getBytesReclaimed() {
        return bytesReclaimed.get();
    }

    // ==================== Configuration Getters/Setters ====================

    /**
     * Returns the compaction interval in milliseconds.
     *
     * @return Compaction interval
     */
    public long getCompactionIntervalMs() {
        return compactionIntervalMs;
    }

    /**
     * Sets the compaction interval in milliseconds.
     *
     * @param intervalMs The new interval (must be at least {@value #MIN_COMPACTION_INTERVAL_MS})
     * @throws IllegalArgumentException if interval is invalid
     */
    public void setCompactionIntervalMs(long intervalMs) {
        validateCompactionInterval(intervalMs);
        this.compactionIntervalMs = intervalMs;
    }

    /**
     * Returns the tombstone ratio threshold.
     *
     * @return Tombstone ratio threshold (0.0 to 1.0)
     */
    public double getTombstoneRatioThreshold() {
        return tombstoneRatioThreshold;
    }

    /**
     * Sets the tombstone ratio threshold.
     *
     * @param threshold The new threshold (0.0 to 1.0)
     * @throws IllegalArgumentException if threshold is invalid
     */
    public void setTombstoneRatioThreshold(double threshold) {
        validateTombstoneRatioThreshold(threshold);
        this.tombstoneRatioThreshold = threshold;
    }

    /**
     * Returns the write log size threshold in bytes.
     *
     * @return Write log size threshold
     */
    public long getWriteLogSizeThreshold() {
        return writeLogSizeThreshold;
    }

    /**
     * Sets the write log size threshold in bytes.
     *
     * @param threshold The new threshold
     * @throws IllegalArgumentException if threshold is invalid
     */
    public void setWriteLogSizeThreshold(long threshold) {
        validateWriteLogSizeThreshold(threshold);
        this.writeLogSizeThreshold = threshold;
    }

    /**
     * Returns the maximum number of concurrent compactions.
     *
     * @return Max concurrent compactions
     */
    public int getMaxConcurrentCompactions() {
        return maxConcurrentCompactions;
    }

    /**
     * Sets the maximum number of concurrent compactions.
     *
     * @param max The new maximum (1 to 16)
     * @throws IllegalArgumentException if max is invalid
     */
    public void setMaxConcurrentCompactions(int max) {
        validateMaxConcurrentCompactions(max);
        this.maxConcurrentCompactions = max;
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
            "CompactionWorker{running=%s, registeredSegments=%d, compactions=%d, " +
            "bytesReclaimed=%d, intervalMs=%d, tombstoneThreshold=%.2f, writeLogThreshold=%dMB}",
            running.get(), registeredSegments.size(), compactionCount.get(),
            bytesReclaimed.get(), compactionIntervalMs, tombstoneRatioThreshold,
            writeLogSizeThreshold / (1024 * 1024));
    }
}
