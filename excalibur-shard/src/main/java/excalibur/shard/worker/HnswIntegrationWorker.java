package excalibur.shard.worker;

import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.WriteLog;
import io.excalibur.store.format.LogEntry;
import io.excalibur.metrics.Metrics;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.IncrementalInsertResult;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

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
 * <p>When the integration queue (entries between writeLogHead and lastIntegratedOffset)
 * exceeds the configured depth limit (default 100K entries), the worker applies
 * backpressure by parking the virtual thread. This prevents unbounded memory growth
 * during high-throughput ingestion.</p>
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

    // ==================== Instance Fields ====================

    private final SegmentCatalog catalog;
    private final IntegrationListener listener;
    private final CopyOnWriteArrayList<IndexableSegment> registeredSegments;

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

    private volatile Thread workerThread;

    // ==================== Constructors ====================

    /**
     * Creates a new HnswIntegrationWorker with default configuration.
     *
     * @param catalog  The segment catalog for metadata access
     * @param listener Callback for integration events
     * @throws NullPointerException if catalog or listener is null
     */
    public HnswIntegrationWorker(SegmentCatalog catalog, IntegrationListener listener) {
        this(catalog, listener, DEFAULT_INTEGRATION_INTERVAL_MS,
             DEFAULT_QUEUE_DEPTH_LIMIT, DEFAULT_BATCH_SIZE);
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

        LOGGER.info("HnswIntegrationWorker created with interval={}ms, queueDepthLimit={}, batchSize={}",
            integrationIntervalMs, queueDepthLimit, batchSize);
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
                // Check for backpressure
                if (checkBackpressure()) {
                    applyBackpressure();
                    continue;
                }

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
     * Checks if backpressure should be applied.
     *
     * @return true if backpressure is needed
     */
    private boolean checkBackpressure() {
        long queueDepth = calculateTotalQueueDepth();
        currentQueueDepth.set(queueDepth);

        // Record HNSW queue depth in JMX metrics for observability
        Metrics.recordHnswDepth(queueDepth);

        if (queueDepth > queueDepthLimit) {
            if (!backpressured.get()) {
                LOGGER.warn("Backpressure triggered: queue depth {} exceeds limit {}",
                    queueDepth, queueDepthLimit);
            }
            backpressured.set(true);
            return true;
        }

        // Release backpressure when queue drains below threshold
        if (backpressured.get() && queueDepth < queueDepthLimit * BACKPRESSURE_RELEASE_THRESHOLD) {
            LOGGER.info("Backpressure released: queue depth {} below release threshold {}",
                queueDepth, (long)(queueDepthLimit * BACKPRESSURE_RELEASE_THRESHOLD));
            backpressured.set(false);
        }

        return false;
    }

    /**
     * Applies backpressure by parking the virtual thread.
     */
    private void applyBackpressure() {
        // Park for a short duration to allow queue to drain
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(integrationIntervalMs * 2));
    }

    /**
     * Calculates the total queue depth across all registered segments.
     *
     * <p>This method counts the actual number of entries pending integration,
     * not the byte offset difference. Each entry in the write log is counted
     * as one entry toward the queue depth limit.</p>
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
            if (writeLog == null) {
                // Fallback: use byte offset difference if writeLog not available
                long writeLogHead = indexable.segment.getWriteLogHead();
                long lastIntegrated = indexable.tracker.getLastIntegratedOffset();
                if (writeLogHead > lastIntegrated) {
                    // Estimate ~100 bytes per entry as rough approximation
                    totalEntries += ((writeLogHead - lastIntegrated) / 100);
                }
                continue;
            }

            long writeLogHead = writeLog.writeOffset();
            long lastIntegrated = indexable.tracker.getLastIntegratedOffset();

            if (writeLogHead > lastIntegrated) {
                // Count actual entries in the tail region by scanning
                long offset = lastIntegrated;
                while (offset < writeLogHead) {
                    int entrySize = writeLog.entrySizeAt(offset);
                    if (entrySize <= 0) {
                        break; // Invalid entry or end of log
                    }
                    totalEntries++;
                    offset += entrySize;
                }
            }
        }
        return totalEntries;
    }

    // ==================== Integration Operations ====================

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

        long currentOffset = lastIntegrated;
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
                if (vector != null && vector.length > 0) {
                    vectors.add(vector);
                    // Pass END offset (not START offset) so tracker knows where next entry begins
                    offsets.add(entryEndOffset);
                    // Extract document ID from entry key (format: documentId:chunkIndex)
                    String documentId = extractDocumentId(entry.key());
                    documentIds.add(documentId);
                    entriesRead++;
                }
            }

            // Move to next entry using pre-calculated entrySize
            currentOffset = entryEndOffset;
            if (currentOffset < 0) {
                break; // Safety check
            }
        }

        if (vectors.isEmpty()) {
            // No valid vectors found, but update offset to skip non-vector entries
            if (currentOffset > lastIntegrated) {
                tracker.compareAndSetOffset(currentOffset);
            }
            return 0;
        }

        // Integrate batch into HNSW graph
        try {
            List<IncrementalInsertResult> results = tracker.integrateBatch(vectors, offsets);

            int successCount = 0;
            for (int i = 0; i < results.size(); i++) {
                IncrementalInsertResult result = results.get(i);
                if (result.isSuccess()) {
                    successCount++;
                    // Register the node-to-document mapping for atomicity filtering
                    // This maps the HNSW node ID to the actual document ID string
                    String documentId = documentIds.get(i);
                    if (documentId != null) {
                        graph.registerDocumentId(result.nodeId(), documentId);
                        LOGGER.trace("Registered nodeId={} -> documentId={}",
                            result.nodeId(), documentId);
                    }
                } else {
                    LOGGER.debug("Vector integration failed at offset {} (nodeId={})",
                        result.writeLogOffset(), result.nodeId());
                }
            }

            if (successCount > 0) {
                LOGGER.debug("Integrated {}/{} vectors from segment {}",
                    successCount, vectors.size(), segment.path());

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
     * Extracts a float vector from entry value bytes.
     *
     * <p>The value is expected to contain a serialized float array.
     * Format: [dimension(4 bytes, little-endian)][float values...]</p>
     *
     * @param value The entry value bytes
     * @return The float array, or null if extraction fails
     */
    private float[] extractVector(byte[] value) {
        if (value == null || value.length < 4) {
            return null;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            int dimension = buffer.getInt();

            if (dimension <= 0 || dimension > 65536) {
                return null; // Invalid dimension
            }

            int expectedBytes = 4 + dimension * 4;
            if (value.length < expectedBytes) {
                return null; // Not enough bytes
            }

            float[] vector = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                vector[i] = buffer.getFloat();
            }

            return vector;

        } catch (Exception e) {
            LOGGER.debug("Failed to extract vector from value: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the document ID from an entry key.
     *
     * <p>The entry key format is {@code documentId:chunkIndex}, where:</p>
     * <ul>
     *   <li>{@code documentId} - The actual document identifier (e.g., "doc1", "source1:file1")</li>
     *   <li>{@code chunkIndex} - The chunk index within the document (e.g., "0", "1", "2")</li>
     * </ul>
     *
     * <p>This method extracts just the document ID portion for use in the
     * node-to-document mapping, which is required for document atomicity filtering.</p>
     *
     * @param key The entry key bytes (UTF-8 encoded)
     * @return The document ID string, or null if extraction fails
     */
    private String extractDocumentId(byte[] key) {
        if (key == null || key.length == 0) {
            return null;
        }

        try {
            String keyStr = new String(key, java.nio.charset.StandardCharsets.UTF_8);
            // Key format: documentId:chunkIndex
            // For documentId like "doc1" with chunkIndex "0", key would be "doc1:0"
            // For documentId like "source1:file1" with chunkIndex "2", key would be "source1:file1:2"
            // So we need to find the LAST colon and strip the chunk index
            int lastColon = keyStr.lastIndexOf(':');
            if (lastColon > 0) {
                return keyStr.substring(0, lastColon);
            }
            // No colon found - return the whole key as document ID
            return keyStr;
        } catch (Exception e) {
            LOGGER.debug("Failed to extract document ID from key: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Gets the lifecycle state for a segment from the catalog.
     *
     * @param segment The segment
     * @return The lifecycle state, or null if not found
     */
    private LifecycleState getSegmentState(Segment segment) {
        try {
            // Get segment ID from path (assumes format segment_N.dat)
            String filename = segment.path().getFileName().toString();
            String idStr = filename.replace("segment_", "").replace(".dat", "");
            long segmentId = Long.parseLong(idStr);

            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            return metadata != null ? metadata.getLifecycleState() : null;

        } catch (Exception e) {
            return null;
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
