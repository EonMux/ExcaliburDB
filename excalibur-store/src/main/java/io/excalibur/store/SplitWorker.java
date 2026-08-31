package io.excalibur.store;

import io.excalibur.store.Segment.SegmentSplitSnapshot;
import io.excalibur.store.format.SegmentEntry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background split worker for the ExcaliburDB storage engine.
 *
 * <p>This class implements a virtual-thread-based background worker that performs
 * copy-on-write segment splitting when segments exceed the load factor threshold.
 * Splitting runs continuously in the background without blocking reads.</p>
 *
 * <h2>Copy-on-Write Split Strategy</h2>
 * <p>The worker uses a copy-on-write approach to ensure non-blocking operation:</p>
 * <ol>
 *   <li>Identify segments that need splitting based on load factor</li>
 *   <li>Call {@link Segment#beginSplit()} to create an immutable snapshot</li>
 *   <li>Create two new child segment files (left and right)</li>
 *   <li>Partition entries from the snapshot into children based on hash bit</li>
 *   <li>Call {@link Segment#completeSplit(Segment, Segment)} to commit the split</li>
 *   <li>Notify listener to update routing to use the new children</li>
 * </ol>
 *
 * <h2>Read Consistency During Split</h2>
 * <p>During a split:</p>
 * <ul>
 *   <li>Reads are served from the pre-split snapshot (consistent view)</li>
 *   <li>Writes are rejected until split completes or aborts</li>
 *   <li>After completion, reads/writes go to the appropriate child</li>
 * </ul>
 *
 * <h2>When Split is Triggered</h2>
 * <p>A segment is a candidate for splitting when:</p>
 * <ul>
 *   <li>Load factor exceeds {@link Segment#LOAD_FACTOR_THRESHOLD} (0.7)</li>
 *   <li>Segment is not already being split or compacted</li>
 *   <li>Explicitly requested via {@link #requestSplit(Segment)}</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe:</p>
 * <ul>
 *   <li>Runs in a dedicated virtual thread</li>
 *   <li>Uses atomic operations for state management</li>
 *   <li>Split operations do not block segment reads (reads use snapshot)</li>
 *   <li>Child registration is atomic via {@link SplitListener}</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Split interval: {@value #DEFAULT_SPLIT_INTERVAL_MS} ms (configurable, min 100ms)</li>
 *   <li>Load factor threshold: Uses Segment's LOAD_FACTOR_THRESHOLD (0.7)</li>
 *   <li>Split bit: supplied by the owning routing table, never derived here. It is the
 *       bit the router will newly consult once the routing table doubles, i.e.
 *       {@code Integer.numberOfTrailingZeros(routingSegmentCount)}. Partitioning on any
 *       other bit produces children the router cannot address.</li>
 *   <li>Child segment capacity: Same as parent's slot count</li>
 * </ul>
 *
 * <h2>Split Serialization</h2>
 * <p>All splits - background and explicitly requested - run one at a time. The split bit
 * is read from the supplier inside that critical section and stays valid until the
 * listener has published the children, so the partitioning and the routing table can
 * never disagree.</p>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create a split worker
 * SplitWorker worker = new SplitWorker(arena, (parent, leftChild, rightChild) -> {
 *     // Called to publish the split: rename the children into their canonical
 *     // segment_&lt;index&gt;.dat names and install them in the routing table.
 * }, () -> Integer.numberOfTrailingZeros(routingSegmentCount));
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
 * @see Segment#beginSplit()
 * @see Segment#completeSplit(Segment, Segment)
 */
public class SplitWorker {

    private static final Logger LOGGER = Logger.getLogger(SplitWorker.class.getName());

    // ==================== Configuration Constants ====================

    /**
     * Default interval between split checks in milliseconds.
     *
     * <p>Domain constraint: Must be at least 100ms to avoid excessive CPU usage.
     * The interval balances responsiveness with resource efficiency.</p>
     */
    public static final long DEFAULT_SPLIT_INTERVAL_MS = 1000; // 1 second

    /**
     * Minimum allowed split interval in milliseconds.
     *
     * <p>Domain constraint: 100ms minimum prevents busy-waiting behavior.</p>
     */
    public static final long MIN_SPLIT_INTERVAL_MS = 100;

    /**
     * Default maximum number of concurrent split operations.
     *
     * <p>Domain constraint: 1 to 8 (2 default). Splits are memory-intensive
     * operations that create two new segments, so we limit concurrency.</p>
     */
    public static final int DEFAULT_MAX_CONCURRENT_SPLITS = 2;

    /**
     * Maximum number of concurrent splits allowed.
     */
    public static final int MAX_CONCURRENT_SPLITS_LIMIT = 8;

    // ==================== Instance Fields ====================

    private final Arena arena;
    private final SplitListener listener;

    /**
     * Supplies the hash bit to partition on, owned by the routing table.
     *
     * <p>This is an explicit collaborator rather than a global read: the worker has no
     * knowledge of the routing table and must never invent a split bit of its own.</p>
     */
    private final IntSupplier splitBitSupplier;

    /** Serializes splits so a split bit cannot go stale before publication. */
    private final ReentrantLock splitExecutionLock = new ReentrantLock();

    /** Threads started by {@link #requestSplit(Segment, int)}, awaited by {@link #stop()}. */
    private final CopyOnWriteArrayList<Thread> requestedSplitThreads = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<Segment> registeredSegments;
    private final AtomicBoolean running;
    private final AtomicBoolean stopped;
    private final AtomicLong splitCount;
    private final AtomicLong lastSplitTime;
    private final AtomicLong entriesSplit;

    private volatile long splitIntervalMs;
    private volatile int maxConcurrentSplits;

    private volatile Thread workerThread;

    // ==================== Constructors ====================

    /**
     * Creates a new SplitWorker with default configuration.
     *
     * @param arena            The Arena for allocating new segment memory (must not be null)
     * @param listener         Callback for split completion events (must not be null)
     * @param splitBitSupplier Supplies the hash bit to partition on (must not be null)
     * @throws NullPointerException if any argument is null
     */
    public SplitWorker(Arena arena, SplitListener listener, IntSupplier splitBitSupplier) {
        this(arena, listener, splitBitSupplier, DEFAULT_SPLIT_INTERVAL_MS, DEFAULT_MAX_CONCURRENT_SPLITS);
    }

    /**
     * Creates a new SplitWorker with custom configuration.
     *
     * @param arena                The Arena for allocating new segment memory
     * @param listener             Callback for split completion events
     * @param splitBitSupplier     Supplies the hash bit to partition on
     * @param splitIntervalMs      Interval between split checks (ms)
     * @param maxConcurrentSplits  Maximum concurrent split operations
     * @throws NullPointerException if arena, listener or splitBitSupplier is null
     * @throws IllegalArgumentException if configuration values are out of range
     */
    public SplitWorker(Arena arena, SplitListener listener, IntSupplier splitBitSupplier,
                       long splitIntervalMs, int maxConcurrentSplits) {
        if (arena == null) {
            throw new NullPointerException("Arena cannot be null");
        }
        if (listener == null) {
            throw new NullPointerException("Listener cannot be null");
        }
        if (splitBitSupplier == null) {
            throw new NullPointerException("Split bit supplier cannot be null");
        }

        // Validate configuration
        validateSplitInterval(splitIntervalMs);
        validateMaxConcurrentSplits(maxConcurrentSplits);

        this.arena = arena;
        this.listener = listener;
        this.splitBitSupplier = splitBitSupplier;
        this.registeredSegments = new CopyOnWriteArrayList<>();
        this.running = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);
        this.splitCount = new AtomicLong(0);
        this.lastSplitTime = new AtomicLong(0);
        this.entriesSplit = new AtomicLong(0);

        this.splitIntervalMs = splitIntervalMs;
        this.maxConcurrentSplits = maxConcurrentSplits;

        LOGGER.log(Level.INFO, "SplitWorker created with interval={0}ms, maxConcurrent={1}",
            new Object[] { splitIntervalMs, maxConcurrentSplits });
    }

    // ==================== Configuration Validation ====================

    private void validateSplitInterval(long intervalMs) {
        if (intervalMs < MIN_SPLIT_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "Split interval must be at least " + MIN_SPLIT_INTERVAL_MS + "ms, got: " + intervalMs);
        }
    }

    private void validateMaxConcurrentSplits(int max) {
        if (max < 1 || max > MAX_CONCURRENT_SPLITS_LIMIT) {
            throw new IllegalArgumentException(
                "Max concurrent splits must be between 1 and " + MAX_CONCURRENT_SPLITS_LIMIT + ", got: " + max);
        }
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts the background split worker.
     *
     * <p>This method creates a virtual thread that continuously monitors
     * registered segments and performs splits as needed. The worker
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
            workerThread = Thread.ofVirtual().name("excalibur-split-worker").start(() -> {
                LOGGER.log(Level.INFO, "Split worker started");
                runSplitLoop();
            });

            return true;
        }
        return false;
    }

    /**
     * Stops the background split worker.
     *
     * <p>This method signals the worker to stop and waits for it to complete
     * any in-progress split operations. After stopping, the worker
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

            // Await splits started by requestSplit so no split can publish children
            // into a routing table that is being torn down.
            for (Thread thread : requestedSplitThreads) {
                try {
                    thread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            requestedSplitThreads.clear();

            LOGGER.log(Level.INFO, "Split worker stopped. Total splits: {0}, entries split: {1}",
                new Object[] { splitCount.get(), entriesSplit.get() });
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
     * Registers a segment for split monitoring.
     *
     * <p>Registered segments are periodically checked for split eligibility
     * based on load factor threshold.</p>
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
            LOGGER.log(Level.FINE, "Registered segment for splitting: {0}", segment.path());
        }
    }

    /**
     * Unregisters a segment from split monitoring.
     *
     * @param segment The segment to unregister
     */
    public void unregisterSegment(Segment segment) {
        if (segment != null && registeredSegments.remove(segment)) {
            LOGGER.log(Level.FINE, "Unregistered segment from splitting: {0}", segment.path());
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

    // ==================== Manual Split ====================

    /**
     * Requests immediate split of a specific segment, partitioning on the current
     * split bit reported by the routing table.
     *
     * @param segment The segment to split
     * @throws NullPointerException if segment is null
     */
    public void requestSplit(Segment segment) {
        requestSplit(segment, splitBitSupplier.getAsInt());
    }

    /**
     * Requests immediate split of a specific segment on an explicit hash bit.
     *
     * <p>This method queues the segment for splitting regardless of whether it meets
     * the normal threshold. Use sparingly as it may cause unnecessary I/O if the
     * segment doesn't need splitting.</p>
     *
     * <p>The requested bit is advisory: if the routing table has moved on by the time
     * the split actually runs, the split uses the routing table's current bit so that
     * the partitioning always matches the routing that will address the children.</p>
     *
     * @param segment  The segment to split
     * @param splitBit The hash bit the caller expects to partition on
     * @throws NullPointerException if segment is null
     */
    public void requestSplit(Segment segment, int splitBit) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }

        Thread thread = Thread.ofVirtual().name("excalibur-split-request").unstarted(() -> {
            try {
                splitSegment(segment, splitBit);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Manual split failed for segment: " + segment.path(), e);
            } finally {
                requestedSplitThreads.remove(Thread.currentThread());
            }
        });
        requestedSplitThreads.add(thread);
        thread.start();
    }

    // ==================== Main Split Loop ====================

    /**
     * Main split loop that runs in the background virtual thread.
     */
    private void runSplitLoop() {
        while (running.get() && !stopped.get()) {
            try {
                // Find and split eligible segments
                List<Segment> candidates = findSplitCandidates();

                if (!candidates.isEmpty()) {
                    LOGGER.log(Level.FINE, "Found {0} segments eligible for splitting", candidates.size());

                    // Split up to maxConcurrentSplits at a time
                    int splitCount = 0;
                    for (Segment segment : candidates) {
                        if (!running.get() || stopped.get()) {
                            break;
                        }
                        if (splitCount >= maxConcurrentSplits) {
                            break;
                        }

                        try {
                            splitSegment(segment, splitBitSupplier.getAsInt());
                            splitCount++;
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Split failed for segment: " + segment.path(), e);
                        }
                    }
                }

                // Sleep until next check
                Thread.sleep(splitIntervalMs);

            } catch (InterruptedException e) {
                // Expected during shutdown
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in split loop", e);
                // Continue running despite errors
            }
        }

        LOGGER.log(Level.INFO, "Split loop terminated");
    }

    // ==================== Split Candidate Detection ====================

    /**
     * Finds segments that are eligible for splitting.
     *
     * <p>A segment is eligible if:</p>
     * <ul>
     *   <li>It is not closed</li>
     *   <li>It is not currently being split</li>
     *   <li>Its load factor exceeds the threshold (0.7)</li>
     * </ul>
     *
     * @return List of segments eligible for splitting
     */
    private List<Segment> findSplitCandidates() {
        List<Segment> candidates = new ArrayList<>();

        for (Segment segment : registeredSegments) {
            if (segment.isClosed()) {
                continue;
            }
            if (segment.isSplitting() || segment.isSplitComplete()) {
                continue;
            }

            if (segment.shouldSplit()) {
                candidates.add(segment);
            }
        }

        // Sort by load factor (descending) to prioritize most full segments
        candidates.sort((a, b) -> Double.compare(b.loadFactor(), a.loadFactor()));

        return candidates;
    }

    // ==================== Core Split Logic ====================

    /**
     * Splits a single segment using copy-on-write semantics.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Calls {@link Segment#beginSplit()} to create an immutable snapshot</li>
     *   <li>Creates two new child segments under temporary names</li>
     *   <li>Partitions entries from the snapshot on {@code splitBit}</li>
     *   <li>Notifies the listener, which renames the children into their canonical
     *       {@code segment_<index>.dat} names and publishes them in the routing table</li>
     *   <li>Calls {@link Segment#completeSplit(Segment, Segment, int)} to retire the parent</li>
     * </ol>
     *
     * <p>Splits are serialized: the split bit is read from the supplier inside the
     * critical section and cannot change before the listener has published, so the
     * partitioning always matches the routing that addresses the children.</p>
     *
     * <h3>Read Consistency Guarantee</h3>
     * <p>During the split, reads continue to be served from the pre-split snapshot.
     * After the split completes, reads are routed to the appropriate child.</p>
     *
     * @param segment           The segment to split
     * @param requestedSplitBit The bit the caller expected to partition on
     * @throws IOException if split fails
     * @throws IllegalStateException if segment cannot be split
     */
    private void splitSegment(Segment segment, int requestedSplitBit) throws IOException {
        splitExecutionLock.lock();
        try {
            runSplit(segment, requestedSplitBit);
        } finally {
            splitExecutionLock.unlock();
        }
    }

    /**
     * Performs one split. The caller must hold {@link #splitExecutionLock}.
     *
     * @param segment           The segment to split
     * @param requestedSplitBit The bit the caller expected to partition on
     * @throws IOException if split fails
     */
    private void runSplit(Segment segment, int requestedSplitBit) throws IOException {
        if (segment.isClosed()) {
            LOGGER.log(Level.FINE, "Skipping closed segment: {0}", segment.path());
            return;
        }
        if (segment.isSplitting() || segment.isSplitComplete()) {
            LOGGER.log(Level.FINE, "Segment already split or splitting: {0}", segment.path());
            return;
        }

        // The routing table is authoritative for the split bit. A requested bit that
        // has gone stale is discarded rather than used.
        int splitBit = splitBitSupplier.getAsInt();
        if (splitBit != requestedSplitBit) {
            LOGGER.log(Level.FINE,
                "Requested split bit {0} superseded by routing table bit {1} for {2}",
                new Object[] { requestedSplitBit, splitBit, segment.path() });
        }

        long startTime = System.currentTimeMillis();
        Path parentPath = segment.path();
        int parentEntryCount = segment.entryCount();

        LOGGER.log(Level.INFO, "Starting split for segment: {0} (load factor: {1}, split bit: {2})",
            new Object[] { parentPath, String.format("%.3f", segment.loadFactor()), splitBit });

        // Step 1: Begin the split - creates immutable snapshot, rejects writes
        SegmentSplitSnapshot snapshot;
        try {
            snapshot = segment.beginSplit();
            LOGGER.log(Level.FINE, "Split snapshot created with {0} entries", snapshot.entryCount());
        } catch (IllegalStateException e) {
            LOGGER.log(Level.WARNING, "Cannot begin split for segment: " + parentPath, e);
            throw e;
        }

        // Step 2: Children are built under temporary names. The listener moves them to
        // their canonical segment_<index>.dat names as part of publishing the split.
        Path leftChildPath = Path.of(parentPath.toString() + ".left");
        Path rightChildPath = Path.of(parentPath.toString() + ".right");
        Files.deleteIfExists(leftChildPath);
        Files.deleteIfExists(rightChildPath);

        // Slot table + 1MB data region, matching the parent's slot count
        long childCapacity = (long) segment.slotCount() * 24L * 2 + 1024L * 1024;

        Segment leftChild = null;
        Segment rightChild = null;
        boolean published = false;
        try {
            // The parent's threshold, not the default: a child created with the default
            // silently discards the engine's configured load factor, and every segment in the
            // store descends from a split.
            double childThreshold = segment.loadFactorThreshold();
            leftChild = new Segment(leftChildPath, childCapacity, arena, childThreshold);
            rightChild = new Segment(rightChildPath, childCapacity, arena, childThreshold);

            int[] counts = partitionEntries(snapshot, leftChild, rightChild, splitBit);

            leftChild.flush();
            rightChild.flush();

            // Step 3: Publish. The listener owns the rename into the canonical names
            // and the routing table update; it throws if it cannot publish.
            listener.onSplitComplete(segment, leftChild, rightChild);
            published = true;

            // Step 4: Retire the parent, recording the bit the children were built on
            segment.completeSplit(leftChild, rightChild, splitBit);

            this.splitCount.incrementAndGet();
            lastSplitTime.set(System.currentTimeMillis());
            entriesSplit.addAndGet(parentEntryCount);

            long duration = System.currentTimeMillis() - startTime;
            LOGGER.log(Level.INFO, "Split complete for {0}: {1} entries -> left={2}, right={3} in {4}ms",
                new Object[] { parentPath, parentEntryCount, counts[0], counts[1], duration });
        } catch (Exception e) {
            if (!published) {
                discardChildren(leftChild, rightChild, leftChildPath, rightChildPath);
                if (segment.isSplitting()) {
                    segment.abortSplit();
                }
            }
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Split failed for segment: " + parentPath, e);
        }
    }

    /**
     * Partitions the snapshot's live entries into the two children on {@code splitBit}.
     *
     * @param snapshot   The pre-split snapshot
     * @param leftChild  Receives entries whose split bit is 0
     * @param rightChild Receives entries whose split bit is 1
     * @param splitBit   The hash bit to partition on
     * @return A two-element array of {left count, right count}
     */
    private int[] partitionEntries(SegmentSplitSnapshot snapshot, Segment leftChild,
                                   Segment rightChild, int splitBit) {
        int leftCount = 0;
        int rightCount = 0;

        for (SegmentEntry entry : snapshot.entries(false)) { // Exclude tombstones
            if (Segment.shouldGoToRightChild(entry.keyHash(), splitBit)) {
                rightChild.putWithoutLock(entry.key(), entry.value());
                rightCount++;
            } else {
                leftChild.putWithoutLock(entry.key(), entry.value());
                leftCount++;
            }
        }

        return new int[] { leftCount, rightCount };
    }

    /**
     * Closes and removes children that were built but never published.
     *
     * @param leftChild      The left child, or null if it was never created
     * @param rightChild     The right child, or null if it was never created
     * @param leftChildPath  Temporary path of the left child
     * @param rightChildPath Temporary path of the right child
     */
    private void discardChildren(Segment leftChild, Segment rightChild,
                                 Path leftChildPath, Path rightChildPath) {
        if (leftChild != null && !leftChild.isClosed()) {
            leftChild.close();
        }
        if (rightChild != null && !rightChild.isClosed()) {
            rightChild.close();
        }
        try {
            Files.deleteIfExists(leftChildPath);
            Files.deleteIfExists(rightChildPath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to remove abandoned split children", e);
        }
    }

    // ==================== Statistics ====================

    /**
     * Returns the total number of split operations performed.
     *
     * @return Split count
     */
    public long getSplitCount() {
        return splitCount.get();
    }

    /**
     * Returns the timestamp of the last split.
     *
     * @return Last split time in epoch milliseconds, or 0 if never
     */
    public long getLastSplitTime() {
        return lastSplitTime.get();
    }

    /**
     * Returns the total entries that have been split across all operations.
     *
     * @return Entries split
     */
    public long getEntriesSplit() {
        return entriesSplit.get();
    }

    // ==================== Configuration Getters/Setters ====================

    /**
     * Returns the split interval in milliseconds.
     *
     * @return Split interval
     */
    public long getSplitIntervalMs() {
        return splitIntervalMs;
    }

    /**
     * Sets the split interval in milliseconds.
     *
     * @param intervalMs The new interval (must be at least {@value #MIN_SPLIT_INTERVAL_MS})
     * @throws IllegalArgumentException if interval is invalid
     */
    public void setSplitIntervalMs(long intervalMs) {
        validateSplitInterval(intervalMs);
        this.splitIntervalMs = intervalMs;
    }

    /**
     * Returns the maximum number of concurrent splits.
     *
     * @return Max concurrent splits
     */
    public int getMaxConcurrentSplits() {
        return maxConcurrentSplits;
    }

    /**
     * Sets the maximum number of concurrent splits.
     *
     * @param max The new maximum (1 to {@value #MAX_CONCURRENT_SPLITS_LIMIT})
     * @throws IllegalArgumentException if max is invalid
     */
    public void setMaxConcurrentSplits(int max) {
        validateMaxConcurrentSplits(max);
        this.maxConcurrentSplits = max;
    }

    // ==================== Listener Interface ====================

    /**
     * Callback interface that publishes a completed split.
     *
     * <p>The listener owns two things the worker cannot do itself, because only the
     * routing table knows the children's indices:</p>
     * <ol>
     *   <li>Moving each child from its temporary build name to the canonical
     *       {@code segment_<index>.dat} name that segment discovery matches.</li>
     *   <li>Installing the children in the routing table and widening it.</li>
     * </ol>
     */
    @FunctionalInterface
    public interface SplitListener {

        /**
         * Called when the children are built and must be published.
         *
         * <p><strong>Important:</strong> the implementation must rename the children
         * into their canonical names and update the routing table so operations reach
         * the appropriate child. The parent is still in SPLITTING state when this is
         * called and is retired only after this method returns normally.</p>
         *
         * <p>Throwing from this method aborts the split: the worker discards the
         * children and returns the parent to service. Implementations must therefore
         * publish atomically or not at all.</p>
         *
         * <h3>Routing Logic</h3>
         * <p>After a split, routing uses the bit the children were partitioned on,
         * which the parent records and exposes as {@link Segment#splitBit()}:</p>
         * <pre>{@code
         * boolean goesRight = Segment.shouldGoToRightChild(keyHash, parent.splitBit());
         * Segment target = goesRight ? rightChild : leftChild;
         * }</pre>
         *
         * @param parent     The parent segment being split
         * @param leftChild  The left child segment (entries whose split bit is 0)
         * @param rightChild The right child segment (entries whose split bit is 1)
         */
        void onSplitComplete(Segment parent, Segment leftChild, Segment rightChild);
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
            "SplitWorker{running=%s, registeredSegments=%d, splits=%d, entriesSplit=%d, intervalMs=%d}",
            running.get(), registeredSegments.size(), splitCount.get(),
            entriesSplit.get(), splitIntervalMs);
    }
}
