package com.eonmux.excalibur.shard.worker;

import com.eonmux.excalibur.shard.visibility.EpochIsolationValidator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.WriteLog;
import com.eonmux.excalibur.store.format.LogEntry;
import com.eonmux.excalibur.metrics.Metrics;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.IncrementalInsertResult;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual thread worker that integrates write log entries into the HNSW graph.
 *
 * <p>This worker implements the background integration workflow described in
 * {@link TailOffsetTracker}:</p>
 * <ol>
 *   <li>Write log receives new vector entries</li>
 *   <li>Background worker reads entries from write log</li>
 *   <li>Worker inserts entries into HNSW graph</li>
 *   <li>On success, worker updates lastIntegratedOffset</li>
 * </ol>
 *
 * <h2>Backpressure Mechanism</h2>
 * <p>Every cycle the worker measures the integration queue (entries between
 * writeLogHead and lastIntegratedOffset) and raises {@link #isBackpressured()} once it
 * exceeds the configured depth limit (default 100K entries), clearing it again when the
 * queue drains below half the limit. Producers - the ingestion pipeline - consult that
 * flag and park; the integration worker itself never parks, because it is the thread
 * that drains the queue.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>Runs in a dedicated virtual thread</li>
 *   <li>Uses atomic operations for state management</li>
 *   <li>Integration operations do not block segment reads or writes</li>
 * </ul>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Integration interval: 10ms minimum (configurable)</li>
 *   <li>Queue depth limit: 10K to 1M entries (default 100K)</li>
 *   <li>Batch size: 1 to 1000 entries (default 100)</li>
 *   <li>Backpressure threshold: 50% of queue depth limit</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 5.0
 * @see TailOffsetTracker
 * @see HnswGraph
 */
public class HnswIntegrationWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswIntegrationWorker.class);

    /**
     * How many batches {@link #drainPendingIntegration} will read before giving up.
     *
     * <p>A bound rather than a loop until empty, so a log the worker cannot make progress on
     * ends the drain instead of holding the compaction thread indefinitely.</p>
     */
    private static final int MAX_DRAIN_PASSES = 10_000;

    // ==================== Configuration Constants ====================

    /**
     * Default interval between integration cycles in milliseconds.
     * Domain constraint: Must be at least 10ms to avoid excessive CPU usage.
     */
    public static final long DEFAULT_INTEGRATION_INTERVAL_MS = 100;

    /**
     * Minimum allowed integration interval in milliseconds.
     */
    public static final long MIN_INTEGRATION_INTERVAL_MS = 10;

    /**
     * Default queue depth limit for backpressure.
     * When entries pending integration exceed this count, backpressure is applied.
     * Domain constraint: 10K to 1M entries.
     */
    public static final int DEFAULT_QUEUE_DEPTH_LIMIT = 100_000;

    /**
     * Minimum queue depth limit.
     */
    public static final int MIN_QUEUE_DEPTH_LIMIT = 10_000;

    /**
     * Maximum queue depth limit.
     */
    public static final int MAX_QUEUE_DEPTH_LIMIT = 1_000_000;

    /**
     * Default batch size for integration.
     * Number of entries to integrate per cycle.
     */
    public static final int DEFAULT_BATCH_SIZE = 100;

    /**
     * Minimum batch size.
     */
    public static final int MIN_BATCH_SIZE = 1;

    /**
     * Maximum batch size.
     */
    public static final int MAX_BATCH_SIZE = 1000;

    /**
     * Backpressure threshold ratio.
     * Backpressure is released when queue depth falls below this ratio of the limit.
     */
    public static final double BACKPRESSURE_RELEASE_THRESHOLD = 0.5;

    /**
     * Maximum write log entries walked per segment when measuring queue depth.
     * Beyond this the remaining tail is estimated from the average entry size.
     */
    private static final int QUEUE_DEPTH_SCAN_LIMIT = 4096;

    /**
     * Assumed entry size when a segment exposes no write log to measure.
     */
    private static final int ESTIMATED_ENTRY_BYTES = 100;

    // ==================== Instance Fields ====================

    private final SegmentCatalog catalog;
    private final IntegrationListener listener;
    private final CopyOnWriteArrayList<IndexableSegment> registeredSegments;

    /**
     * Epoch isolation validator for tracking node visibility.
     * May be null if epoch isolation is not configured.
     */
    private final EpochIsolationValidator epochIsolationValidator;

    /**
     * Visibility tracker for getting current epoch.
     * May be null if epoch isolation is not configured.
     */
    private final VisibilityTracker visibilityTracker;

    private final AtomicBoolean running;
    private final AtomicBoolean stopped;
    private final AtomicBoolean backpressured;

    private final AtomicLong totalIntegrated;
    private final AtomicLong totalBatches;
    private final AtomicLong lastIntegrationTime;
    private final AtomicLong currentQueueDepth;

    private volatile long integrationIntervalMs;
    private volatile int queueDepthLimit;
    private volatile int batchSize;

    /**
     * The segment pre-filter mirrored with each integrated vector, or null when none is wired.
     *
     * <p>Set after construction because the pre-filter belongs to the ingestion side of the
     * node, which is assembled later than the index orchestrator that owns this worker.</p>
     */
    private volatile com.eonmux.excalibur.store.VectorPrefilter vectorPrefilter;

    private volatile Thread workerThread;

    /**
     * The offset each stalled segment's tail is stuck at, keyed by segment file.
     *
     * <p>Only ever holds segments the worker cannot read past. An entry is removed the moment
     * the tail moves again, so the map is empty on a healthy node.</p>
     */
    private final java.util.Map<java.nio.file.Path, Long> stalledTails =
        new java.util.concurrent.ConcurrentHashMap<>();

    // ==================== Constructors ====================

    /**
     * Creates a new HnswIntegrationWorker with default configuration.
     *
     * @param catalog  The segment catalog for metadata access
     * @param listener Callback for integration events
     * @throws NullPointerException if catalog or listener is null
     */
    public HnswIntegrationWorker(SegmentCatalog catalog, IntegrationListener listener) {
        this(catalog, listener, null, null, DEFAULT_INTEGRATION_INTERVAL_MS,
             DEFAULT_QUEUE_DEPTH_LIMIT, DEFAULT_BATCH_SIZE);
    }

    /**
     * Creates a new HnswIntegrationWorker with default configuration and epoch isolation.
     *
     * @param catalog                  The segment catalog for metadata access
     * @param listener                 Callback for integration events
     * @param epochIsolationValidator  Validator for tracking node visibility (may be null)
     * @param visibilityTracker        Tracker for getting current epoch (may be null)
     * @throws NullPointerException    if catalog or listener is null
     */
    public HnswIntegrationWorker(SegmentCatalog catalog, IntegrationListener listener,
                                  EpochIsolationValidator epochIsolationValidator,
                                  VisibilityTracker visibilityTracker) {
        this(catalog, listener, epochIsolationValidator, visibilityTracker,
             DEFAULT_INTEGRATION_INTERVAL_MS, DEFAULT_QUEUE_DEPTH_LIMIT, DEFAULT_BATCH_SIZE);
    }

    /**
     * Creates a new HnswIntegrationWorker with custom configuration.
     *
     * @param catalog                  The segment catalog
     * @param listener                 Callback for integration events
     * @param integrationIntervalMs    Interval between integration cycles
     * @param queueDepthLimit          Maximum pending entries before backpressure
     * @param batchSize                Number of entries to integrate per cycle
     * @throws NullPointerException    if catalog or listener is null
     * @throws IllegalArgumentException if configuration values are invalid
     */
    public HnswIntegrationWorker(SegmentCatalog catalog, IntegrationListener listener,
                                  long integrationIntervalMs, int queueDepthLimit, int batchSize) {
        this(catalog, listener, null, null, integrationIntervalMs, queueDepthLimit, batchSize);
    }

    /**
     * Creates a new HnswIntegrationWorker with full configuration including epoch isolation.
     *
     * @param catalog                  The segment catalog
     * @param listener                 Callback for integration events
     * @param epochIsolationValidator  Validator for tracking node visibility (may be null)
     * @param visibilityTracker        Tracker for getting current epoch (may be null)
     * @param integrationIntervalMs    Interval between integration cycles
     * @param queueDepthLimit          Maximum pending entries before backpressure
     * @param batchSize                Number of entries to integrate per cycle
     * @throws NullPointerException    if catalog or listener is null
     * @throws IllegalArgumentException if configuration values are invalid
     */
    public HnswIntegrationWorker(SegmentCatalog catalog, IntegrationListener listener,
                                  EpochIsolationValidator epochIsolationValidator,
                                  VisibilityTracker visibilityTracker,
                                  long integrationIntervalMs, int queueDepthLimit, int batchSize) {
        if (catalog == null) {
            throw new NullPointerException("Catalog cannot be null");
        }
        if (listener == null) {
            throw new NullPointerException("Listener cannot be null");
        }

        validateInterval(integrationIntervalMs);
        validateQueueDepthLimit(queueDepthLimit);
        validateBatchSize(batchSize);

        this.catalog = catalog;
        this.listener = listener;
        this.epochIsolationValidator = epochIsolationValidator;
        this.visibilityTracker = visibilityTracker;
        this.registeredSegments = new CopyOnWriteArrayList<>();

        this.running = new AtomicBoolean(false);
        this.stopped = new AtomicBoolean(false);
        this.backpressured = new AtomicBoolean(false);

        this.totalIntegrated = new AtomicLong(0);
        this.totalBatches = new AtomicLong(0);
        this.lastIntegrationTime = new AtomicLong(0);
        this.currentQueueDepth = new AtomicLong(0);

        this.integrationIntervalMs = integrationIntervalMs;
        this.queueDepthLimit = queueDepthLimit;
        this.batchSize = batchSize;

        if (epochIsolationValidator != null && visibilityTracker != null) {
            LOGGER.info("HnswIntegrationWorker created with epoch isolation enabled, interval={}ms, queueDepthLimit={}, batchSize={}",
                integrationIntervalMs, queueDepthLimit, batchSize);
        } else {
            LOGGER.info("HnswIntegrationWorker created without epoch isolation, interval={}ms, queueDepthLimit={}, batchSize={}",
                integrationIntervalMs, queueDepthLimit, batchSize);
        }
    }

    // ==================== Configuration Validation ====================

    private void validateInterval(long intervalMs) {
        if (intervalMs < MIN_INTEGRATION_INTERVAL_MS) {
            throw new IllegalArgumentException(
                "Integration interval must be at least " + MIN_INTEGRATION_INTERVAL_MS + "ms, got: " + intervalMs);
        }
    }

    private void validateQueueDepthLimit(int limit) {
        if (limit < MIN_QUEUE_DEPTH_LIMIT || limit > MAX_QUEUE_DEPTH_LIMIT) {
            throw new IllegalArgumentException(
                "Queue depth limit must be between " + MIN_QUEUE_DEPTH_LIMIT +
                " and " + MAX_QUEUE_DEPTH_LIMIT + ", got: " + limit);
        }
    }

    private void validateBatchSize(int size) {
        if (size < MIN_BATCH_SIZE || size > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                "Batch size must be between " + MIN_BATCH_SIZE +
                " and " + MAX_BATCH_SIZE + ", got: " + size);
        }
    }

    // ==================== Lifecycle Management ====================

    /**
     * Starts the background integration worker.
     *
     * <p>This method creates a virtual thread that continuously monitors
     * registered segments and integrates write log entries into the HNSW graph.</p>
     *
     * @return true if the worker was started, false if already running
     */
    public boolean start() {
        if (running.compareAndSet(false, true)) {
            stopped.set(false);

            workerThread = Thread.ofVirtual()
                .name("excalibur-hnsw-integration-worker")
                .start(this::runIntegrationLoop);

            LOGGER.info("HnswIntegrationWorker started");
            return true;
        }
        return false;
    }

    /**
     * Stops the background integration worker.
     *
     * <p>This method signals the worker to stop and waits for it to complete
     * any in-progress integration operations.</p>
     */
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            running.set(false);

            if (workerThread != null) {
                workerThread.interrupt();
                try {
                    workerThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            LOGGER.info("HnswIntegrationWorker stopped. Total integrated: {}, batches: {}",
                totalIntegrated.get(), totalBatches.get());
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

    /**
     * Checks if backpressure is currently being applied.
     *
     * @return true if backpressured, false otherwise
     */
    public boolean isBackpressured() {
        return backpressured.get();
    }

    // ==================== Segment Registration ====================

    /**
     * Registers a segment for HNSW integration monitoring.
     *
     * <p>The segment must have an attached HNSW graph and write log.</p>
     *
     * @param segment    The segment to integrate
     * @param graph      The HNSW graph for the segment
     * @param tracker    The tail offset tracker for the graph
     * @throws NullPointerException if any parameter is null
     */
    public void registerSegment(Segment segment, HnswGraph graph, TailOffsetTracker tracker) {
        if (segment == null) {
            throw new NullPointerException("Segment cannot be null");
        }
        if (graph == null) {
            throw new NullPointerException("Graph cannot be null");
        }
        if (tracker == null) {
            throw new NullPointerException("Tracker cannot be null");
        }

        IndexableSegment indexable = new IndexableSegment(segment, graph, tracker);
        if (!registeredSegments.contains(indexable)) {
            registeredSegments.add(indexable);
            LOGGER.debug("Registered segment for HNSW integration: {}", segment.path());
        }
    }

    /**
     * Unregisters a segment from HNSW integration.
     *
     * @param segment The segment to unregister
     */
    public void unregisterSegment(Segment segment) {
        if (segment != null) {
            registeredSegments.removeIf(s -> s.segment == segment);
            LOGGER.debug("Unregistered segment from HNSW integration: {}", segment.path());
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

    // ==================== Main Integration Loop ====================

    /**
     * Main integration loop that runs in the background virtual thread.
     */
    private void runIntegrationLoop() {
        while (running.get() && !stopped.get()) {
            try {
                // Maintain the backpressure flag and the depth metric. The flag throttles
                // PRODUCERS; this worker never parks on it, because it is the thread that
                // drains the queue -- parking here would deadlock ingestion against the
                // very worker whose progress releases it.
                updateBackpressureState();

                // Integrate entries from all registered segments
                int totalIntegratedThisCycle = 0;
                for (IndexableSegment indexable : registeredSegments) {
                    if (!running.get() || stopped.get()) {
                        break;
                    }

                    // Skip segments that are not in INDEXING or WARMING state
                    LifecycleState state = getSegmentState(indexable.segment);
                    if (state != LifecycleState.INDEXING && state != LifecycleState.WARMING && state != LifecycleState.ACTIVE) {
                        continue;
                    }

                    int integrated = integrateSegmentBatch(indexable);
                    totalIntegratedThisCycle += integrated;
                }

                if (totalIntegratedThisCycle > 0) {
                    totalIntegrated.addAndGet(totalIntegratedThisCycle);
                    totalBatches.incrementAndGet();
                    lastIntegrationTime.set(System.currentTimeMillis());
                }

                // Sleep until next cycle
                Thread.sleep(integrationIntervalMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.error("Error in HNSW integration loop", e);
                // Continue running despite errors
            }
        }

        LOGGER.info("HNSW integration loop terminated");
    }

    // ==================== Backpressure Management ====================

    /**
     * Measures the integration queue and updates the backpressure flag and metric.
     *
     * <p>The flag is what producers park on, so it must be refreshed every cycle. This
     * method never blocks: the drain thread must keep running while producers wait.</p>
     */
    private void updateBackpressureState() {
        long queueDepth = calculateTotalQueueDepth();
        currentQueueDepth.set(queueDepth);

        // Record HNSW queue depth in JMX metrics for observability
        Metrics.recordHnswDepth(queueDepth);

        if (queueDepth > queueDepthLimit) {
            if (backpressured.compareAndSet(false, true)) {
                LOGGER.warn("Backpressure triggered: queue depth {} exceeds limit {}",
                    queueDepth, queueDepthLimit);
            }
            return;
        }

        // Release backpressure when queue drains below threshold
        if (backpressured.get() && queueDepth < queueDepthLimit * BACKPRESSURE_RELEASE_THRESHOLD) {
            LOGGER.info("Backpressure released: queue depth {} below release threshold {}",
                queueDepth, (long) (queueDepthLimit * BACKPRESSURE_RELEASE_THRESHOLD));
            backpressured.set(false);
        }
    }

    /**
     * Calculates the total queue depth across all registered segments.
     *
     * <p>This method counts the number of entries pending integration, not the byte
     * offset difference. Each entry in the write log is counted as one entry toward the
     * queue depth limit.</p>
     *
     * <p><strong>Bounded cost:</strong> a cycle drains at most {@code batchSize} entries
     * per segment, so an exact count of an arbitrarily deep backlog would make the cycle
     * cost grow with the backlog it is trying to drain. At most
     * {@link #QUEUE_DEPTH_SCAN_LIMIT} entries are walked per segment; beyond that the
     * remaining bytes are converted with the average entry size measured during the
     * walk. The count is therefore exact where it matters (a nearly drained queue) and a
     * proportional estimate where only the order of magnitude matters (a deep backlog
     * that must trigger backpressure).</p>
     *
     * <p><strong>Domain Correctness:</strong> The queue depth limit (default 100,000)
     * is specified in entries, not bytes. This ensures consistent backpressure
     * behavior regardless of entry size variations.</p>
     *
     * @return Total pending entries (not bytes)
     */
    private long calculateTotalQueueDepth() {
        long totalEntries = 0;
        for (IndexableSegment indexable : registeredSegments) {
            WriteLog writeLog = indexable.segment.writeLog();
            long lastIntegrated = indexable.tracker.getLastIntegratedOffset();

            if (writeLog == null) {
                // Fallback: use byte offset difference if writeLog not available
                long writeLogHead = indexable.segment.getWriteLogHead();
                if (writeLogHead > lastIntegrated) {
                    totalEntries += (writeLogHead - lastIntegrated) / ESTIMATED_ENTRY_BYTES;
                }
                continue;
            }

            totalEntries += pendingEntries(writeLog, lastIntegrated, writeLog.writeOffset());
        }
        return totalEntries;
    }

    /**
     * Counts the entries between two write log offsets, capped at
     * {@link #QUEUE_DEPTH_SCAN_LIMIT} scanned entries.
     *
     * @param writeLog     The segment's write log
     * @param fromOffset   First un-integrated offset
     * @param writeLogHead Current head of the write log
     * @return Exact count when the tail fits within the scan limit, otherwise an estimate
     */
    private long pendingEntries(WriteLog writeLog, long fromOffset, long writeLogHead) {
        if (writeLogHead <= fromOffset) {
            return 0;
        }

        long counted = 0;
        long offset = fromOffset;
        while (offset < writeLogHead && counted < QUEUE_DEPTH_SCAN_LIMIT) {
            int entrySize = writeLog.entrySizeAt(offset);
            if (entrySize <= 0) {
                return counted; // Invalid entry or end of log
            }
            counted++;
            offset += entrySize;
        }

        if (offset >= writeLogHead) {
            return counted;
        }

        // Scan limit reached: extrapolate the remainder from the observed entry size.
        long averageEntryBytes = Math.max(1, (offset - fromOffset) / counted);
        return counted + (writeLogHead - offset) / averageEntryBytes;
    }

    // ==================== Integration Operations ====================

    /**
     * Integrates everything a segment's write log still holds, right now, on the calling thread.
     *
     * <p>For the moment a segment is about to lose the log its checkpoint was measured against.
     * Compaction copies the live entries into a new file and deletes the log; the entries between
     * the checkpoint and the head were copied as data and never indexed, and nothing ever reads a
     * segment's data file into a graph - so without this they stay retrievable by key and vanish
     * from vector search, silently, for good. Under sustained ingest the integration worker is
     * always behind, which makes that the ordinary outcome of a compaction rather than a rare
     * one.</p>
     *
     * <p>The caller must have unregistered the segment first: this writes to the same graph and
     * checkpoint a background cycle would.</p>
     *
     * @param segment The segment whose log to drain
     * @param graph   Its graph
     * @param tracker Its checkpoint
     * @return The number of vectors integrated
     * @throws NullPointerException if any argument is null
     */
    public int drainPendingIntegration(Segment segment, HnswGraph graph, TailOffsetTracker tracker) {
        java.util.Objects.requireNonNull(segment, "segment must not be null");
        java.util.Objects.requireNonNull(graph, "graph must not be null");
        java.util.Objects.requireNonNull(tracker, "tracker must not be null");

        IndexableSegment indexable = new IndexableSegment(segment, graph, tracker);
        int total = 0;

        // Bounded: each pass either integrates something or advances past entries it cannot use,
        // so a log that cannot be drained ends the loop rather than spinning on it.
        for (int pass = 0; pass < MAX_DRAIN_PASSES; pass++) {
            long before = tracker.getLastIntegratedOffset();
            total += integrateSegmentBatch(indexable);
            if (tracker.getLastIntegratedOffset() <= before) {
                break;
            }
        }

        if (total > 0) {
            LOGGER.info("Drained {} pending vectors from the write log of {} before it was "
                + "replaced", total, segment.path());
        }
        return total;
    }

    /**
     * Reports a write log the worker cannot read past, once per place it gets stuck.
     *
     * <p>The tail is read one entry at a time from the checkpoint, and an entry that will not
     * parse - a failed CRC, most of all - stops the read where it is. The checkpoint cannot be
     * moved past it without declaring entries integrated that are not, so it stays, and every
     * cycle from then on reads the same byte and gets the same answer. Nothing above debug is
     * written by the read itself, so the effect is a segment whose vectors quietly stop
     * becoming searchable while ingestion keeps reporting success.</p>
     *
     * <p>Reported once per offset rather than once per cycle: the offset does not move while
     * the tail is stuck, so this is one message per stall, and a new one if it ever moves and
     * sticks again.</p>
     *
     * @param segment      The segment whose tail is stuck
     * @param offset       The offset that will not parse
     * @param writeLogHead The head the tail should have reached
     */
    private void reportStalledTail(Segment segment, long offset, long writeLogHead) {
        Long reported = stalledTails.put(segment.path(), offset);
        if (reported != null && reported == offset) {
            return;
        }
        LOGGER.error("HNSW integration for {} cannot read the write log entry at offset {}. The "
            + "{} bytes from there to the head will not be indexed and their vectors will not be "
            + "searchable, on this run or any later one, until the segment is reindexed.",
            segment.path(), offset, writeLogHead - offset);
    }

    /** Forgets a stall once the segment's tail moves again. */
    private void tailMoved(Segment segment) {
        if (!stalledTails.isEmpty()) {
            stalledTails.remove(segment.path());
        }
    }

    /**
     * The offset each segment's tail is stuck at, for segments the worker cannot read past.
     *
     * <p>Empty on a healthy node. A segment listed here is one whose newer vectors are not
     * reaching its graph - see {@link #reportStalledTail}.</p>
     *
     * @return An immutable snapshot, keyed by segment file
     */
    public java.util.Map<java.nio.file.Path, Long> stalledTails() {
        return java.util.Map.copyOf(stalledTails);
    }

    /**
     * Integrates a batch of entries from a segment's write log into the HNSW graph.
     *
     * @param indexable The segment with its graph and tracker
     * @return Number of entries integrated
     */
    private int integrateSegmentBatch(IndexableSegment indexable) {
        Segment segment = indexable.segment;
        HnswGraph graph = indexable.graph;
        TailOffsetTracker tracker = indexable.tracker;
        WriteLog writeLog = segment.writeLog();

        if (writeLog == null) {
            return 0;
        }

        long writeLogHead = writeLog.writeOffset();
        long lastIntegrated = tracker.getLastIntegratedOffset();

        if (writeLogHead <= lastIntegrated) {
            return 0; // No entries to integrate
        }

        // Collect vectors, offsets, and document IDs for batch integration
        List<float[]> vectors = new ArrayList<>(batchSize);
        List<Long> offsets = new ArrayList<>(batchSize);
        List<String> documentIds = new ArrayList<>(batchSize);

        long currentOffset = readBatch(writeLog, graph, lastIntegrated, writeLogHead,
            vectors, offsets, documentIds);

        if (vectors.isEmpty()) {
            // No usable vectors found; advance past the entries that were skipped so a
            // malformed or wrongly dimensioned entry cannot stall the log tail forever.
            if (currentOffset > lastIntegrated) {
                tailMoved(segment);
                tracker.compareAndSetOffset(currentOffset);
            } else {
                reportStalledTail(segment, lastIntegrated, writeLogHead);
            }
            return 0;
        }

        tailMoved(segment);

        // Integrate batch into HNSW graph
        try {
            long batchEpoch = registerBatchVisibility(segment, graph);

            List<IncrementalInsertResult> results = tracker.integrateBatch(vectors, offsets);

            int successCount = recordInsertResults(graph, results, documentIds);

            mirrorIntoPrefilter(segment, results, vectors);

            if (successCount > 0) {
                LOGGER.debug("Integrated {}/{} vectors from segment {} at epoch {}",
                    successCount, vectors.size(), segment.path(), batchEpoch);

                // Checkpoint durably at the batch boundary. flush() writes the offset and
                // forces the graph's mapping, so the node pages the checkpoint vouches for
                // reach disk with it. A checkpoint that survived a crash without its nodes
                // would silently lose vectors; one that was lost would re-insert the whole
                // batch as duplicate nodes on restart.
                tracker.flush();

                // The pre-filter is made durable with the checkpoint that vouches for the nodes
                // it names, so a restart cannot come back holding candidates for nodes the graph
                // no longer has.
                flushPrefilter(segment);

                // Notify listener
                listener.onVectorsIntegrated(segment, successCount);
            }

            return successCount;

        } catch (Exception e) {
            LOGGER.error("Failed to integrate batch for segment {}: {}",
                segment.path(), e.getMessage());
            return 0;
        }
    }

    /**
     * Reads up to {@code batchSize} integrable entries from the write log tail.
     *
     * <p>Entries that carry no vector, or a vector whose dimension does not match the
     * graph, are skipped rather than collected: passing one to the graph throws out of the
     * batch insert, which would leave the offset unadvanced and re-insert every preceding
     * vector of the batch on the next cycle, forever.</p>
     *
     * @param writeLog     The segment's write log
     * @param graph        The segment's graph, whose dimension the vectors must match
     * @param fromOffset   First un-integrated offset
     * @param writeLogHead Current head of the write log
     * @param vectors      Collects the vectors to insert
     * @param offsets      Collects the END offset of each collected vector's entry
     * @param documentIds  Collects the document ID of each collected vector's entry
     * @return The offset immediately after the last entry examined
     */
    private long readBatch(WriteLog writeLog, HnswGraph graph, long fromOffset, long writeLogHead,
                           List<float[]> vectors, List<Long> offsets, List<String> documentIds) {
        int expectedDimension = graph.config().dimension();
        long currentOffset = fromOffset;
        int entriesRead = 0;

        while (currentOffset < writeLogHead && entriesRead < batchSize) {
            LogEntry entry = writeLog.readEntryAt(currentOffset);
            if (entry == null) {
                break;
            }

            // Calculate entry size and end offset ONCE to ensure consistency
            int entrySize = writeLog.entrySizeAt(currentOffset);
            long entryEndOffset = currentOffset + entrySize;

            // Only integrate PUT operations with vector data
            if (entry.isPut() && entry.value().length > 0) {
                float[] vector = extractVector(entry.value());
                if (vector != null && vector.length == expectedDimension) {
                    vectors.add(vector);
                    // Pass END offset (not START offset) so tracker knows where next entry begins
                    offsets.add(entryEndOffset);
                    // Extract document ID from entry key (format: documentId:chunkIndex)
                    documentIds.add(extractDocumentId(entry.key()));
                    entriesRead++;
                } else if (vector != null) {
                    LOGGER.warn("Skipping write log entry at offset {}: vector dimension {} does "
                        + "not match graph dimension {}", currentOffset, vector.length,
                        expectedDimension);
                }
            }

            // Move to next entry using pre-calculated entrySize
            currentOffset = entryEndOffset;
            if (currentOffset < 0) {
                break; // Safety check
            }
        }

        return currentOffset;
    }

    /**
     * Publishes the visibility epoch of the batch about to be inserted.
     *
     * <p>This runs <strong>before</strong> the insert. The graph links a node into the
     * searchable structure as it is written, so a node registered afterwards is reachable
     * by concurrent queries during the gap - and an unknown node reads as visible. The
     * checkpoint covers every node ID from the graph's next ID upward, which is exactly
     * the range this batch will occupy, so no node is ever searchable before its epoch is
     * known.</p>
     *
     * @param segment The segment being integrated
     * @param graph   The segment's graph
     * @return The epoch recorded for the batch, or -1 if epoch isolation is not configured
     */
    private long registerBatchVisibility(Segment segment, HnswGraph graph) {
        if (epochIsolationValidator == null || visibilityTracker == null) {
            return -1;
        }

        long segmentId = getSegmentId(segment);
        if (segmentId < 0) {
            LOGGER.debug("Cannot scope node visibility: segment ID unavailable for {}", segment.path());
            return -1;
        }

        long batchEpoch = visibilityTracker.currentEpoch();
        epochIsolationValidator.registerNodeRange(segmentId, graph.nodeCount(), batchEpoch);
        epochIsolationValidator.registerSegmentUpdate(segmentId, batchEpoch);
        return batchEpoch;
    }

    /**
     * Mirrors each integrated vector into the segment's pre-filter under its new node id.
     *
     * <p>This is what keeps the pre-filter and the graph speaking the same id space. The query
     * pipeline intersects the pre-filter's answer with the graph's node ids, so a pre-filter
     * populated from any other id space excludes every result instead of narrowing the search.</p>
     *
     * <p>Best-effort throughout: the pre-filter is an optimisation, and no failure here may cost
     * a vector its place in the graph, which is already durable by this point.</p>
     */
    private void mirrorIntoPrefilter(Segment segment, List<IncrementalInsertResult> results,
                                     List<float[]> vectors) {
        com.eonmux.excalibur.store.VectorPrefilter prefilter = this.vectorPrefilter;
        if (prefilter == null) {
            return;
        }

        for (int i = 0; i < results.size() && i < vectors.size(); i++) {
            IncrementalInsertResult result = results.get(i);
            if (!result.isSuccess()) {
                continue;
            }
            try {
                prefilter.indexVector(segment, vectors.get(i), result.nodeId());
            } catch (Exception e) {
                LOGGER.warn("Failed to mirror node {} of segment {} into the vector pre-filter: {}",
                    result.nodeId(), segment.path(), e.getMessage());
            }
        }
    }

    /**
     * Forces the segment's pre-filter, ignoring failures for the same reason inserts ignore them.
     */
    private void flushPrefilter(Segment segment) {
        com.eonmux.excalibur.store.VectorPrefilter prefilter = this.vectorPrefilter;
        if (prefilter == null) {
            return;
        }
        try {
            prefilter.flush(segment);
        } catch (Exception e) {
            LOGGER.warn("Failed to flush the vector pre-filter of segment {}: {}",
                segment.path(), e.getMessage());
        }
    }

    /**
     * Records the node-to-document mapping for the nodes a batch inserted.
     *
     * @param graph       The segment's graph
     * @param results     The batch insertion results
     * @param documentIds The document IDs, positionally aligned with the results
     * @return Number of successful insertions
     */
    private int recordInsertResults(HnswGraph graph, List<IncrementalInsertResult> results,
                                    List<String> documentIds) {
        int successCount = 0;
        for (int i = 0; i < results.size(); i++) {
            IncrementalInsertResult result = results.get(i);
            if (!result.isSuccess()) {
                LOGGER.debug("Vector integration failed at offset {} (nodeId={})",
                    result.writeLogOffset(), result.nodeId());
                continue;
            }

            successCount++;
            // Register the node-to-document mapping for atomicity filtering.
            // This maps the HNSW node ID to the actual document ID string.
            String documentId = i < documentIds.size() ? documentIds.get(i) : null;
            if (documentId != null) {
                graph.registerDocumentId(result.nodeId(), documentId);
                LOGGER.trace("Registered nodeId={} -> documentId={}", result.nodeId(), documentId);
            }
        }
        return successCount;
    }

    /**
     * Extracts a float vector from entry value bytes.
     *
     * <p>The layout belongs to {@link ChunkPayloadCodec}. This worker is the reader that decides
     * whether a written vector ever reaches the HNSW graph, so a decoder here that disagrees
     * with the writer does not fail loudly - it silently leaves vectors unindexed.</p>
     *
     * @param value The entry value bytes
     * @return The float array, or null if the value carries no vector
     */
    private float[] extractVector(byte[] value) {
        return ChunkPayloadCodec.embeddingOf(value);
    }

    /**
     * Extracts the document key from an entry key.
     *
     * <p>Delegates to {@link ChunkIdentifiers}, which is the single definition of this
     * convention. The document key registered here is what excalibur-query's atomicity filter
     * later looks up in the visibility tracker, and what {@code SegmentGraphRebuilder} registers
     * when the same segment's graph is rebuilt instead of integrated incrementally. Deriving it
     * here independently is how those three drift apart, and every way they can disagree is
     * silent: the node is registered under a key nothing asks about, so the document is dropped
     * from results rather than reported as an error.</p>
     *
     * @param key The entry key bytes (UTF-8 encoded)
     * @return The document key, or null if the entry carries no key
     */
    private String extractDocumentId(byte[] key) {
        if (key == null || key.length == 0) {
            return null;
        }
        return ChunkIdentifiers.documentKeyOfChunk(
            new String(key, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Gets the lifecycle state for a segment from the catalog.
     *
     * @param segment The segment
     * @return The lifecycle state, or null if not found
     */
    private LifecycleState getSegmentState(Segment segment) {
        long segmentId = getSegmentId(segment);
        if (segmentId < 0) {
            return null;
        }

        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
        return metadata != null ? metadata.getLifecycleState() : null;
    }

    /**
     * Derives a segment's ID from its canonical {@code segment_N.dat} file name.
     *
     * @param segment The segment
     * @return The segment ID, or -1 if the name does not carry one
     */
    private long getSegmentId(Segment segment) {
        try {
            String filename = segment.path().getFileName().toString();
            String idStr = filename.replace("segment_", "").replace(".dat", "");
            return Long.parseLong(idStr);
        } catch (Exception e) {
            return -1;
        }
    }

    // ==================== Statistics ====================

    /**
     * Returns the total number of vectors integrated.
     *
     * @return Total vectors integrated
     */
    public long getTotalIntegrated() {
        return totalIntegrated.get();
    }

    /**
     * Returns the total number of integration batches.
     *
     * @return Total batches
     */
    public long getTotalBatches() {
        return totalBatches.get();
    }

    /**
     * Returns the timestamp of the last integration.
     *
     * @return Last integration time in epoch milliseconds, or 0 if never
     */
    public long getLastIntegrationTime() {
        return lastIntegrationTime.get();
    }

    /**
     * Returns the current queue depth.
     *
     * @return Current pending entries
     */
    public long getCurrentQueueDepth() {
        return currentQueueDepth.get();
    }

    // ==================== Configuration Getters/Setters ====================

    /**
     * Returns the integration interval in milliseconds.
     *
     * @return Integration interval
     */
    public long getIntegrationIntervalMs() {
        return integrationIntervalMs;
    }

    /**
     * Sets the integration interval in milliseconds.
     *
     * @param intervalMs The new interval
     * @throws IllegalArgumentException if interval is invalid
     */
    public void setIntegrationIntervalMs(long intervalMs) {
        validateInterval(intervalMs);
        this.integrationIntervalMs = intervalMs;
    }

    /**
     * Returns the queue depth limit.
     *
     * @return Queue depth limit
     */
    public int getQueueDepthLimit() {
        return queueDepthLimit;
    }

    /**
     * Sets the queue depth limit.
     *
     * @param limit The new limit
     * @throws IllegalArgumentException if limit is invalid
     */
    public void setQueueDepthLimit(int limit) {
        validateQueueDepthLimit(limit);
        this.queueDepthLimit = limit;
    }

    /**
     * Returns the batch size.
     *
     * @return Batch size
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the batch size.
     *
     * @param size The new batch size
     * @throws IllegalArgumentException if size is invalid
     */
    public void setBatchSize(int size) {
        validateBatchSize(size);
        this.batchSize = size;
    }

    /**
     * Sets the vector pre-filter mirrored as vectors are integrated.
     *
     * <p>The pre-filter is keyed by HNSW node id, and node ids are assigned here, so this worker
     * is the only place that can populate it consistently with the graph the query pipeline
     * searches. Leaving it unset simply means no pre-filter is maintained.</p>
     *
     * @param vectorPrefilter The pre-filter to mirror into, or null to maintain none
     */
    public void setVectorPrefilter(com.eonmux.excalibur.store.VectorPrefilter vectorPrefilter) {
        this.vectorPrefilter = vectorPrefilter;
        LOGGER.info("HNSW integration {} mirror integrated vectors into a pre-filter",
            vectorPrefilter == null ? "will not" : "will");
    }

    // ==================== Listener Interface ====================

    /**
     * Callback interface for integration events.
     */
    @FunctionalInterface
    public interface IntegrationListener {

        /**
         * Called when vectors have been integrated into the HNSW graph.
         *
         * @param segment The segment that was integrated
         * @param count   The number of vectors integrated
         */
        void onVectorsIntegrated(Segment segment, int count);
    }

    // ==================== Internal Classes ====================

    /**
     * Holder for a segment with its HNSW graph and tracker.
     */
    private static class IndexableSegment {
        final Segment segment;
        final HnswGraph graph;
        final TailOffsetTracker tracker;

        IndexableSegment(Segment segment, HnswGraph graph, TailOffsetTracker tracker) {
            this.segment = segment;
            this.graph = graph;
            this.tracker = tracker;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof IndexableSegment other)) return false;
            return segment == other.segment;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(segment);
        }
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
            "HnswIntegrationWorker{running=%s, backpressured=%s, segments=%d, " +
            "integrated=%d, batches=%d, queueDepth=%d, intervalMs=%d, batchSize=%d}",
            running.get(), backpressured.get(), registeredSegments.size(),
            totalIntegrated.get(), totalBatches.get(), currentQueueDepth.get(),
            integrationIntervalMs, batchSize);
    }
}
