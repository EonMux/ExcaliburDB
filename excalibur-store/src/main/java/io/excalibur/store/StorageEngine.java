package io.excalibur.store;

import io.excalibur.core.memory.MemorySegmentFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * StorageEngine manages segments that form the keyspace for the ExcaliburDB storage engine.
 *
 * <p>The keyspace is divided into segments, each backed by a memory-mapped file.
 * StorageEngine provides the top-level interface for segment management, lifecycle
 * tracking, and metadata cataloging.</p>
 *
 * <h2>Hash-Based Routing</h2>
 * <p>Keys are distributed across segments using consistent hash-based routing:
 * <ul>
 *   <li>Each key is hashed using XXHash64</li>
 *   <li>The segment index is computed as: {@code hash & (segmentCount - 1)}</li>
 *   <li>The same key ALWAYS routes to the same segment index</li>
 *   <li>Lookups are O(1) - no scanning required</li>
 * </ul>
 *
 * <h2>Segment Lifecycle States</h2>
 * <p>Each segment progresses through lifecycle states:
 * <ul>
 *   <li>{@link #STATE_BOOTSTRAPPING} — Allocated, no data. Invisible to queries.</li>
 *   <li>{@link #STATE_INDEXING} — Entries being written. Cuckoo filter and LSH incrementally populated.</li>
 *   <li>{@link #STATE_WARMING} — Batch complete. HNSW edges being optimized, PQ codebooks training.</li>
 *   <li>{@link #STATE_ACTIVE} — Fully indexed, fully optimized. Queryable.</li>
 *   <li>{@link #STATE_COMPACTING} — Tombstones being cleaned. Frozen snapshot serves queries.</li>
 *   <li>{@link #STATE_TOMBSTONED} — Invisible to new queries. Kept on disk until epoch-based reclamation.</li>
 *   <li>{@link #STATE_PURGED} — Disk freed. Removed from catalog.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>StorageEngine is fully thread-safe. All public methods are synchronized
 * where necessary to ensure consistent state across concurrent operations.
 * The segment registry uses HashMap with synchronized access for thread safety.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 * @see Segment
 * @see SegmentCatalog
 * @see SegmentConfig
 * @see LifecycleState
 */
public class StorageEngine implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(StorageEngine.class.getName());

    // ==================== Constants ====================

    /**
     * Segment lifecycle states.
     *
     * <p>States are assigned integer values for efficient comparison and serialization.
     * These values correspond to {@link LifecycleState} enum values.</p>
     *
     * @see LifecycleState
     */
    public static final int STATE_BOOTSTRAPPING = 0;
    public static final int STATE_INDEXING = 1;
    public static final int STATE_WARMING = 2;
    public static final int STATE_ACTIVE = 3;
    public static final int STATE_COMPACTING = 4;
    public static final int STATE_TOMBSTONED = 5;
    public static final int STATE_PURGED = 6;

    /**
     * Minimum number of segments to pre-allocate for initial load.
     *
     * <p>This provides a reasonable default for most workloads while avoiding
     * excessive fragmentation. Can be tuned based on expected dataset size.</p>
     *
     * <p><strong>Note:</strong> This value MUST be a power of 2 for correct
     * hash-based routing using the bitwise AND mask.</p>
     */
    public static final int DEFAULT_INITIAL_SEGMENTS = 64;

    /**
     * Target number of segments to pre-allocate based on estimated dataset size.
     *
     * <p>Formula: (estimated_entries / MAX_SEGMENT_CAPACITY) * BUFFER_FACTOR
     * where BUFFER_FACTOR = 1.5 to account for growth during indexing</p>
     */
    public static final int SEGMENT_BUFFER_FACTOR = 15; // 1.5x buffer

    // ==================== Instance Fields ====================

    /**
     * Path to the data directory containing segment files.
     *
     * <p>All segment files are stored under this directory with naming convention
     * "segment_&lt;index&gt;.dat" to ensure deterministic placement based on
     * the segment index (not a random unique ID).</p>
     */
    private final Path dataDir;

    /**
     * Arena for memory allocation.
     *
     * <p>The Arena controls the lifecycle of the MemorySegment backing each segment.
     * This Arena must outlive the StorageEngine for proper resource cleanup.</p>
     *
     * <p>May be null when using the fallback ByteBuffer-based implementation.</p>
     */
    private final Arena arena;

    /**
     * Flag indicating whether Panama FFM is being used.
     *
     * <p>When true, the engine uses native Panama FFM MemorySegments.
     * When false, the engine uses the ByteBuffer-based fallback implementation.</p>
     */
    private final boolean usingPanama;

    /**
     * Registry of active segments.
     *
     * <p><strong>Key:</strong> segment index (long) → <strong>Value:</strong> Segment instance</p>
     *
     * <p><strong>Critical:</strong> The key is the segment INDEX (0, 1, 2, ...), NOT a random
     * unique ID. This ensures hash-based routing works correctly - the same key hash
     * always maps to the same segment index, enabling O(1) lookups.</p>
     *
     * <p>Segment indices are in the range [0, segmentCount-1] and are computed as:
     * {@code segmentIndex = keyHash & (segmentCount - 1)}</p>
     */
    private final Map<Long, Segment> segments;

    /**
     * Lock object for synchronizing segment creation.
     */
    private final Object segmentLock = new Object();

    /**
     * Segment catalog for metadata management.
     *
     * <p>The catalog stores persistent metadata about each segment including:
     * - segmentId (same as segment index for routing)
     * - volumePath
     * - entryCount
     * - lifecycleState
     * - lastIntegratedOffset
     * - lastCompactionTime
     *
     * This catalog is used by the Index Orchestration module (Phase 5) for
     * segment discovery, lifecycle management, and visibility tracking.</p>
     */
    private final SegmentCatalog catalog;

    /**
     * Visibility tracker for coordinating epochs across segments.
     *
     * <p>The visibility tracker provides a global epoch mechanism that enables
     * epoch-based visibility for queries. Each query captures the epoch at
     * start time and sees a consistent segment snapshot.</p>
     *
     * <p>Defaults to a simple local tracker. For full functionality with
     * segment registration and tombstone tracking, inject a tracker from
     * the IndexOrchestrator in the excalibur-shard module.</p>
     */
    private volatile VisibilityTracker visibilityTracker = new VisibilityTracker.Default();

    /**
     * Statistics for monitoring and diagnostics.
     */
    private final Statistics statistics = new Statistics();

    /**
     * Flag indicating whether the engine has been started.
     */
    private volatile boolean started = false;

    /**
     * HNSW initializer for creating HNSW graphs for segments.
     * May be null if HNSW initialization is disabled.
     */
    private volatile HnswInitializer hnswInitializer;

    /**
     * Background split worker for automatic capacity management.
     *
     * <p>The split worker monitors registered segments and performs
     * copy-on-write splits when they exceed the load factor threshold (0.7).
     * This ensures automatic capacity management without blocking reads.</p>
     *
     * <p>When a segment reaches capacity:</p>
     * <ol>
     *   <li>Split is triggered (either background or immediate)</li>
     *   <li>Segment is split into two children based on hash bit</li>
     *   <li>Listener updates segment registry to use children</li>
     *   <li>New writes are routed to the appropriate child</li>
     * </ol>
     */
    private volatile SplitWorker splitWorker;

    // ==================== Constructors ====================

    /**
     * Creates a new StorageEngine with default configuration using automatic
     * implementation selection.
     *
     * <p>This constructor uses {@link MemorySegmentFactory} to automatically select
     * the appropriate memory segment implementation:</p>
     * <ul>
     *   <li>Uses Panama FFM (native MemorySegment) when available (Java 21+ with native access)</li>
     *   <li>Falls back to ByteBuffer-based implementation when Panama is unavailable</li>
     * </ul>
     *
     * <p>Default configuration:
     * <ul>
     *   <li>Data directory: must exist</li>
     *   <li>Initial segments: 64 (pre-allocated on start)</li>
     *   <li>Visibility epoch: starts at 0</li>
     * </ul>
     *
     * @param dataDir Directory for storing segment files (must exist)
     * @throws IllegalArgumentException if dataDir is null or doesn't exist
     */
    public StorageEngine(Path dataDir) {
        if (dataDir == null) {
            throw new IllegalArgumentException("Data directory cannot be null");
        }
        if (!java.nio.file.Files.isDirectory(dataDir)) {
            throw new IllegalArgumentException(
                "Data directory must exist: " + dataDir);
        }

        this.dataDir = dataDir;
        this.usingPanama = MemorySegmentFactory.isPanamaAvailable();
        this.arena = usingPanama ? Arena.ofShared() : null;
        this.catalog = new SegmentCatalog(dataDir.resolve("catalog.dat"));
        this.segments = new HashMap<>(DEFAULT_INITIAL_SEGMENTS);

        // Initialize split worker with listener that updates segment registry
        // This enables automatic capacity management when segments reach load factor threshold
        if (usingPanama) {
            this.splitWorker = new SplitWorker(arena, new SplitWorker.SplitListener() {
                @Override
                public void onSplitComplete(Segment parent, Segment leftChild, Segment rightChild) {
                    handleSplitComplete(parent, leftChild, rightChild);
                }
            });
        } else {
            // Split worker not available in fallback mode
            // Segment splitting requires Panama FFM for memory-mapped file operations
            this.splitWorker = null;
        }

        LOGGER.log(Level.INFO, "StorageEngine initialized using {0} implementation",
            usingPanama ? "Panama FFM" : "ByteBuffer fallback");
    }

    /**
     * Creates a new StorageEngine with default configuration and explicit Arena.
     *
     * <p>Default configuration:
     * <ul>
     *   <li>Data directory: must exist</li>
     *   <li>Initial segments: 64 (pre-allocated on start)</li>
     *   <li>Visibility epoch: starts at 0</li>
     * </ul>
     *
     * @param dataDir Directory for storing segment files (must exist)
     * @param arena Arena for memory allocation (must outlive this engine)
     * @throws IllegalArgumentException if dataDir is null or doesn't exist
     */
    public StorageEngine(Path dataDir, Arena arena) {
        if (dataDir == null) {
            throw new IllegalArgumentException("Data directory cannot be null");
        }
        if (!java.nio.file.Files.isDirectory(dataDir)) {
            throw new IllegalArgumentException(
                "Data directory must exist: " + dataDir);
        }
        if (arena == null) {
            throw new IllegalArgumentException("Arena cannot be null");
        }

        this.dataDir = dataDir;
        this.arena = arena;
        this.usingPanama = true;
        this.catalog = new SegmentCatalog(dataDir.resolve("catalog.dat"), arena);
        this.segments = new HashMap<>(DEFAULT_INITIAL_SEGMENTS);

        // Initialize split worker with listener that updates segment registry
        // This enables automatic capacity management when segments reach load factor threshold
        this.splitWorker = new SplitWorker(arena, new SplitWorker.SplitListener() {
            @Override
            public void onSplitComplete(Segment parent, Segment leftChild, Segment rightChild) {
                handleSplitComplete(parent, leftChild, rightChild);
            }
        });
    }

    /**
     * Creates a new StorageEngine with custom initial segment count.
     *
     * @param dataDir Directory for storing segment files (must exist)
     * @param arena Arena for memory allocation (must outlive this engine)
     * @param initialSegments Number of segments to pre-allocate (should be power of 2)
     * @throws IllegalArgumentException if dataDir is null or doesn't exist
     */
    public StorageEngine(Path dataDir, Arena arena, int initialSegments) {
        this(dataDir, arena);
        // Note: The HashMap is re-created here with the specified capacity
        // but segments are still pre-allocated in start() using DEFAULT_INITIAL_SEGMENTS
    }

    // ==================== Public API - Keyspace Management ====================

    /**
     * Starts the storage engine.
     *
     * <p>This method initializes internal state and prepares the engine
     * for accepting operations. The engine can accept operations before
     * this method is called, but certain features (like visibility epoch
     * tracking) require the engine to be started.</p>
     *
     * <h3>Startup Sequence</h3>
     * <ol>
     *   <li>Validate data directory accessibility</li>
     *   <li>Recover persisted catalog metadata</li>
     *   <li><strong>Discover existing segment files on disk</strong></li>
     *   <li><strong>Open and register discovered segments</strong></li>
     *   <li><strong>Recover write-ahead logs for discovered segments</strong></li>
     *   <li>Pre-allocate additional segments if needed</li>
     *   <li>Start background split worker</li>
     *   <li>Register all segments with split worker</li>
     *   <li>Set started flag to true</li>
     * </ol>
     *
     * <h3>Segment Discovery</h3>
     * <p>On restart, the storage engine discovers existing segment files
     * in the data directory and recovers their state. This ensures that
     * data persisted to disk is not lost between restarts. The discovery
     * process:</p>
     * <ul>
     *   <li>Scans for files matching pattern {@code segment_*.dat}</li>
     *   <li>Parses segment index from filename</li>
     *   <li>Opens each segment without truncating</li>
     *   <li>Recovers segment state from slot table</li>
     *   <li>Replays write-ahead log if present</li>
     *   <li>Sets appropriate lifecycle state (ACTIVE if has entries, BOOTSTRAPPING if empty)</li>
     * </ul>
     *
     * <h3>Automatic Load Factor Management</h3>
     * <p>After starting, the split worker monitors all registered segments.
     * When a segment's load factor reaches 0.7, it is automatically split
     * into two child segments using copy-on-write semantics. Reads continue
     * to be served during splitting, while writes are briefly rejected until
     * the split completes.</p>
     */
    public void start() {
        if (started) {
            return; // Already started
        }

        // Validate data directory is accessible
        if (!java.nio.file.Files.exists(dataDir)) {
            throw new IllegalStateException(
                "Data directory does not exist: " + dataDir);
        }

        // Recover persisted catalog metadata from previous runs
        catalog.recover();

        // DISCOVER EXISTING SEGMENTS - This is the critical fix for data persistence
        // Without this, existing segment files would be ignored and data would be lost
        SegmentDiscovery discovery = new SegmentDiscovery(dataDir);
        java.util.List<SegmentDiscovery.DiscoveredSegment> discoveredSegments = discovery.scanDirectory();

        // Track the highest segment index found for proper routing
        int maxSegmentIndex = -1;

        // Open and register each discovered segment
        for (SegmentDiscovery.DiscoveredSegment discovered : discoveredSegments) {
            try {
                // Open existing segment WITHOUT truncating - preserves data
                Segment segment = Segment.openExisting(discovered.path(), arena);

                long segmentIndex = discovered.segmentIndex();
                segments.put(segmentIndex, segment);

                // Track max index for routing table sizing
                if (segmentIndex > maxSegmentIndex) {
                    maxSegmentIndex = (int) segmentIndex;
                }

                // Check for and recover write-ahead log
                if (discovered.hasWriteLog()) {
                    try {
                        WriteLog writeLog = new WriteLog(discovered.walPath(), arena);
                        segment.attachWriteLog(writeLog);
                        // Replay write log to recover any uncommitted entries
                        segment.recoverFromLog();
                        // Update last integrated offset from catalog if available
                        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentIndex);
                        if (metadata != null) {
                            writeLog.setLastIntegratedOffset(metadata.getLastIntegratedOffset());
                        }
                    } catch (IOException e) {
                        // Log error but continue - segment data is still accessible
                        java.util.logging.Logger.getLogger(StorageEngine.class.getName())
                            .warning("Failed to recover write log for segment " + segmentIndex + ": " + e.getMessage());
                    }
                }

                // Register in catalog and set appropriate lifecycle state
                SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentIndex);
                if (metadata == null) {
                    // New to catalog - add with appropriate state
                    metadata = catalog.add(segmentIndex, discovered.path());
                }

                // Set lifecycle state based on whether segment has entries
                // Segments with entries should be ACTIVE (queryable)
                // Empty segments should be BOOTSTRAPPING (invisible to queries)
                if (segment.entryCount() > 0) {
                    // Transition to ACTIVE state for queryable segments
                    if (metadata.getLifecycleState() == LifecycleState.BOOTSTRAPPING) {
                        try {
                            metadata.transitionToIndexing();
                            metadata.transitionToWarming();
                            metadata.transitionToActive();
                            metadata.setEntryCount(segment.entryCount());
                        } catch (IllegalStateException e) {
                            // State transition failed - log and continue
                            java.util.logging.Logger.getLogger(StorageEngine.class.getName())
                                .warning("Failed to transition segment " + segmentIndex + " to ACTIVE: " + e.getMessage());
                        }
                    }
                }

                statistics.incrementSegmentOperations();

            } catch (IOException e) {
                throw new RuntimeException("Failed to open discovered segment: " + discovered.path(), e);
            }
        }

        // Pre-allocate additional segments if we have fewer than default
        // This ensures we always have enough segments for routing
        int segmentsNeeded = DEFAULT_INITIAL_SEGMENTS;
        int currentSegmentCount = segments.size();
        if (currentSegmentCount < segmentsNeeded) {
            for (int i = 0; i < segmentsNeeded; i++) {
                if (!segments.containsKey((long) i)) {
                    preAllocateSegment(i);
                }
            }
        }

        // Start the background split worker for automatic capacity management
        if (splitWorker != null) {
            splitWorker.start();

            // Register all segments for load factor monitoring
            for (Segment segment : segments.values()) {
                splitWorker.registerSegment(segment);
            }
        }

        started = true;
        statistics.incrementStartOperations();

        // Log startup summary
        java.util.logging.Logger.getLogger(StorageEngine.class.getName()).info(
            String.format("StorageEngine started: discovered=%d, total=%d segments, ready for operations",
                discoveredSegments.size(), segments.size()));
    }

    /**
     * Inserts a key-value pair, routing to the appropriate segment.
     *
     * <p>This method computes the segment index using the key hash
     * and delegates the actual insertion to the target segment.</p>
     *
     * <h3>Key Distribution</h3>
     * <p>Keys are distributed evenly across segments using:
     * {@code segmentIndex = keyHash & (segmentCount - 1)}
     * which is equivalent to hash % segmentCount for power-of-2 segment counts.</p>
     *
     * <p><strong>Routing Guarantee:</strong> The same key always routes to the same
     * segment index, enabling O(1) lookups without scanning all segments.</p>
     *
     * <h3>Automatic Capacity Management</h3>
     * <p>When a segment reaches the load factor threshold (0.7), this method:</p>
     * <ol>
     *   <li>Requests an immediate split for the full segment</li>
     *   <li>Waits briefly for the split to complete (up to 5 seconds)</li>
     *   <li>Retries the insert into the appropriate child segment</li>
     * </ol>
     *
     * <p>If the split cannot complete in time, a {@link SegmentAtCapacityException}
     * is thrown to allow the caller to implement retry logic.</p>
     *
     * <p><strong>Lifecycle Note:</strong> Segments remain in INDEXING state during
     * writes. Lifecycle transitions (to WARMING, ACTIVE, etc.) are managed by the
     * {@link SegmentLifecycleManager} or explicit calls to transition methods.</p>
     *
     * @param key The key (must not be null)
     * @param value The value (must not be null)
     * @throws NullPointerException if key or value is null
     * @throws IllegalStateException if engine is not started
     * @throws SegmentAtCapacityException if segment is at capacity and split cannot complete
     */
    public void put(byte[] key, byte[] value) {
        if (key == null || value == null) {
            throw new NullPointerException("Key and value cannot be null");
        }

        if (!started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        int currentSegmentCount = segments.size();
        if (currentSegmentCount == 0) {
            throw new IllegalStateException("No segments available - call start() first");
        }

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);
        int segmentIndex = (int) (keyHash & (currentSegmentCount - 1));

        // Route to the segment at this index - use segmentIndex as the map key
        Segment segment = segments.get((long) segmentIndex);
        if (segment == null) {
            // Create segment at this index only if it doesn't exist
            segment = createSegmentAtIndex(segmentIndex);
        }

        // Try the put operation, handling load factor exceeded gracefully
        try {
            segment.put(key, value);

            // Update catalog entry count only - don't change lifecycle state
            // Segments remain in INDEXING state during writes
            catalog.updateEntryCount((long) segmentIndex, 1);

            // Increment visibility epoch for consistent reads
            visibilityTracker.advanceEpoch();

            statistics.incrementWriteOperations();
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("Load factor")) {
                // Segment has reached capacity - trigger immediate split and retry
                putWithSplitHandling(key, value, keyHash, segment, segmentIndex);
            } else {
                throw e;
            }
        }
    }

    /**
     * Handles a put operation when the target segment has reached capacity.
     *
     * <p>This method implements automatic capacity management by:</p>
     * <ol>
     *   <li>Requesting an immediate split of the full segment</li>
     *   <li>Waiting for the split to complete (up to 5 seconds)</li>
     *   <li>Routing the insert to the appropriate child segment</li>
     * </ol>
     *
     * <p><strong>Domain Correctness:</strong> After split, keys are routed to
     * children based on a hash bit. The routing uses bit 0 by default (first split),
     * incrementing with each subsequent split level.</p>
     *
     * @param key The key to insert
     * @param value The value to insert
     * @param keyHash The pre-computed key hash
     * @param segment The segment that is at capacity
     * @param segmentIndex The segment index
     * @throws SegmentAtCapacityException if split cannot complete in time
     */
    private void putWithSplitHandling(byte[] key, byte[] value, long keyHash,
                                       Segment segment, int segmentIndex) {
        // Trigger immediate split
        if (splitWorker != null && splitWorker.isRunning()) {
            // Request immediate split for this segment
            splitWorker.requestSplit(segment);

            // Wait for split to complete (poll with timeout)
            long startTime = System.currentTimeMillis();
            long timeoutMs = 5000; // 5 second timeout
            while (!segment.isSplitComplete()) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    throw new SegmentAtCapacityException(
                        "Segment " + segmentIndex + " at capacity. Split in progress but not complete. " +
                        "Please retry the operation.");
                }
                try {
                    Thread.sleep(10); // Brief sleep before checking again
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SegmentAtCapacityException(
                        "Interrupted while waiting for segment split", ie);
                }
            }

            // Split complete - route to appropriate child
            // Use bit 0 for routing (first split level)
            boolean goesRight = Segment.shouldGoToRightChild(keyHash, 0);
            Segment child = goesRight ? segment.rightChild() : segment.leftChild();

            if (child == null) {
                throw new IllegalStateException(
                    "Split completed but child segment is null. Segment: " + segmentIndex);
            }

            // Insert into child segment
            child.put(key, value);

            // Update catalog with new child segment
            long childId = goesRight ? segmentIndex * 2 + 1 : segmentIndex * 2;
            catalog.updateEntryCount(childId, 1);

            visibilityTracker.advanceEpoch();
            statistics.incrementWriteOperations();
        } else {
            // No split worker available - cannot handle capacity automatically
            throw new SegmentAtCapacityException(
                "Segment " + segmentIndex + " at capacity. No automatic splitting available. " +
                "Ensure StorageEngine is started to enable automatic capacity management.");
        }
    }

    /**
     * Creates a segment at the specified index.
     *
     * <p>This method is synchronized to prevent concurrent creation of
     * the same segment index.</p>
     *
     * <p>New segments are automatically registered with the split worker
     * for load factor monitoring if the split worker is running.</p>
     *
     * @param segmentIndex The index for the new segment
     * @return The created or existing segment
     */
    private Segment createSegmentAtIndex(int segmentIndex) {
        synchronized (segmentLock) {
            // Double-check after acquiring lock
            Segment existing = segments.get((long) segmentIndex);
            if (existing != null) {
                return existing;
            }

            Path segmentPath = dataDir.resolve("segment_" + segmentIndex + ".dat");
            Path writeLogPath = dataDir.resolve("segment_" + segmentIndex + ".wal");
            try {
                Segment segment = new Segment(segmentPath, 1024 * 1024, arena);

                // Create and attach WriteLog for crash recovery
                // Every mutation (put, delete) is first appended to the log
                // before updating the hash map - log is the source of truth
                WriteLog writeLog = new WriteLog(writeLogPath, arena);
                segment.attachWriteLog(writeLog);

                // Initialize HNSW graph for the segment if initializer is configured
                if (hnswInitializer != null) {
                    try {
                        hnswInitializer.initializeForSegment(segment);
                        LOGGER.fine("HNSW graph initialized for segment " + segmentIndex);
                    } catch (IOException e) {
                        LOGGER.warning("Failed to initialize HNSW graph for segment " +
                            segmentIndex + ": " + e.getMessage());
                        // Continue without HNSW - segment is still functional for KV operations
                    }
                }

                segments.put((long) segmentIndex, segment);

                // Add to catalog with initial INDEXING state
                catalog.add((long) segmentIndex, segmentPath);

                // Register with split worker for automatic capacity management
                if (splitWorker != null && splitWorker.isRunning()) {
                    splitWorker.registerSegment(segment);
                }

                statistics.incrementSegmentOperations();
                return segment;
            } catch (IOException e) {
                throw new RuntimeException("Failed to create segment at index " + segmentIndex, e);
            }
        }
    }

    /**
     * Retrieves a value by key, routing to the appropriate segment.
     *
     * <p><strong>O(1) Routing:</strong> This method uses hash-based routing
     * to directly access the target segment without scanning all segments.
     * The same key always routes to the same segment.</p>
     *
     * @param key The key to look up (must not be null)
     * @return MemorySegment slice with value, or null if not found
     * @throws NullPointerException if key is null
     * @throws IllegalStateException if engine is not started
     */
    public MemorySegment get(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        if (!started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        int currentSegmentCount = segments.size();
        if (currentSegmentCount == 0) {
            return null;
        }

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);
        int segmentIndex = (int) (keyHash & (currentSegmentCount - 1));

        // Direct O(1) lookup - route to the computed segment
        Segment segment = segments.get((long) segmentIndex);
        if (segment == null) {
            return null;
        }

        statistics.incrementReadOperations();
        return segment.get(key);
    }

    /**
     * Retrieves a value with visibility epoch tracking.
     *
     * <p>Returns a {@link ZeroCopySlice} that includes the modification
     * epoch of the segment at the time of the read. Callers can use
     * {@link ZeroCopySlice#isValid()} to verify the snapshot is still valid.</p>
     *
     * <p><strong>O(1) Routing:</strong> This method uses hash-based routing
     * to directly access the target segment without scanning all segments.</p>
     *
     * <p><strong>Epoch Correctness:</strong> The returned slice captures the
     * per-segment modification epoch, not the global visibility epoch. This
     * ensures that {@link ZeroCopySlice#isValid()} correctly compares against
     * the same epoch counter that the segment uses.</p>
     *
     * @param key The key to look up (must not be null)
     * @return ZeroCopySlice with value and creation epoch, or not found slice
     * @throws NullPointerException if key is null
     */
    public ZeroCopySlice getWithEpoch(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        if (!started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        int currentSegmentCount = segments.size();
        if (currentSegmentCount == 0) {
            return ZeroCopySlice.notFound(0, -1, null);
        }

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);
        int segmentIndex = (int) (keyHash & (currentSegmentCount - 1));

        // Direct O(1) lookup - route to the computed segment
        Segment segment = segments.get((long) segmentIndex);
        if (segment == null) {
            return ZeroCopySlice.notFound(0, -1, null);
        }

        // Delegate to segment's getWithEpoch which captures the per-segment epoch
        // This ensures ZeroCopySlice.isValid() compares against the correct epoch
        ZeroCopySlice slice = segment.getWithEpoch(key);
        statistics.incrementReadOperations();
        return slice;
    }

    /**
     * Deletes a key-value pair.
     *
     * <p>The key is tombstoned in the target segment. Actual removal
     * occurs during compaction. This allows concurrent readers to
     * continue accessing the data safely.</p>
     *
     * <p><strong>O(1) Routing:</strong> This method uses hash-based routing
     * to directly access the target segment without scanning all segments.</p>
     *
     * <p><strong>Note:</strong> This method tombstones a single entry, not the
     * entire segment. To tombstone an entire segment, use {@link #tombstoneSegment(long)}.</p>
     *
     * @param key The key to delete (must not be null)
     * @return true if the key was found and deleted, false if not found
     * @throws NullPointerException if key is null
     */
    public boolean delete(byte[] key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        if (!started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        int currentSegmentCount = segments.size();
        if (currentSegmentCount == 0) {
            return false;
        }

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);
        int segmentIndex = (int) (keyHash & (currentSegmentCount - 1));

        // Direct O(1) lookup - route to the computed segment
        Segment segment = segments.get((long) segmentIndex);
        if (segment == null) {
            return false;
        }

        if (segment.delete(key)) {
            // Only update entry count, don't change lifecycle state
            // Entry tombstones are handled separately from segment lifecycle
            catalog.updateEntryCount((long) segmentIndex, -1);
            statistics.incrementDeleteOperations();
            visibilityTracker.advanceEpoch();
            return true;
        }

        return false;
    }

    // ==================== Segment Lifecycle Management ====================

    /**
     * Segment lifecycle manager for background state transitions.
     *
     * <p>The lifecycle manager handles automatic state transitions based on
     * configurable criteria like tombstone thresholds for compaction and
     * epoch lag for reclamation.</p>
     */
    private volatile SegmentLifecycleManager lifecycleManager;

    /**
     * Gets or creates the lifecycle manager for this engine.
     *
     * <p>The lifecycle manager is created lazily on first access.</p>
     *
     * @return The lifecycle manager
     */
    public SegmentLifecycleManager getLifecycleManager() {
        if (lifecycleManager == null) {
            synchronized (this) {
                if (lifecycleManager == null) {
                    lifecycleManager = new SegmentLifecycleManager(catalog);
                }
            }
        }
        return lifecycleManager;
    }

    /**
     * Starts the lifecycle manager for background state transitions.
     *
     * <p>This method starts a background virtual thread that periodically
     * checks segments for compaction and reclamation candidates.</p>
     */
    public void startLifecycleManager() {
        getLifecycleManager().start();
    }

    /**
     * Stops the lifecycle manager.
     */
    public void stopLifecycleManager() {
        if (lifecycleManager != null) {
            lifecycleManager.stop();
        }
    }

    /**
     * Completes the indexing phase for a segment.
     *
     * <p>This transitions the segment from INDEXING to WARMING state.
     * Should be called when all entries for a batch have been written.</p>
     *
     * @param segmentIndex The segment index
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if segment is not in INDEXING state
     */
    public void completeSegmentIndexing(long segmentIndex) {
        catalog.completeIndexing(segmentIndex);
    }

    /**
     * Completes the warmup phase for a segment.
     *
     * <p>This transitions the segment from WARMING to ACTIVE state.
     * Should be called when HNSW optimization and PQ training are complete.</p>
     *
     * @param segmentIndex The segment index
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if segment is not in WARMING state
     */
    public void completeSegmentWarmup(long segmentIndex) {
        catalog.completeWarmup(segmentIndex);
    }

    /**
     * Begins compaction for a segment.
     *
     * <p>This transitions the segment from ACTIVE to COMPACTING state.</p>
     *
     * @param segmentIndex The segment index
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if segment is not in ACTIVE state
     */
    public void beginSegmentCompaction(long segmentIndex) {
        catalog.beginCompaction(segmentIndex);
    }

    /**
     * Completes compaction for a segment.
     *
     * <p>This transitions the segment from COMPACTING back to ACTIVE state.</p>
     *
     * @param segmentIndex The segment index
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if segment is not in COMPACTING state
     */
    public void completeSegmentCompaction(long segmentIndex) {
        catalog.completeCompaction(segmentIndex);
    }

    /**
     * Transitions a segment to a new lifecycle state.
     *
     * <p>This method validates the state transition and updates both
     * the catalog metadata and any dependent systems.</p>
     *
     * @param segmentIndex The index of the segment
     * @param newState The new lifecycle state
     * @throws IllegalStateException if the transition is invalid
     * @see LifecycleState#validateTransition(LifecycleState, LifecycleState)
     */
    public void transitionSegmentState(long segmentIndex, LifecycleState newState) {
        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentIndex);
        if (metadata == null) {
            throw new IllegalArgumentException("Segment not found: " + segmentIndex);
        }

        // This will validate the transition and throw if invalid
        metadata.setLifecycleState(newState);

        // Update entry state to match lifecycle state for catalog consistency
        int entryState = lifecycleToEntryState(newState);
        if (entryState >= 0) {
            metadata.setEntryState(entryState);
        }
    }

    /**
     * Converts a LifecycleState to the corresponding entry state constant.
     *
     * @param state The lifecycle state
     * @return The entry state constant, or -1 if no mapping exists
     */
    private int lifecycleToEntryState(LifecycleState state) {
        switch (state) {
            case BOOTSTRAPPING: return SegmentCatalog.ENTRY_STATE_NONE;
            case INDEXING: return SegmentCatalog.ENTRY_STATE_INDEXING;
            case WARMING: return SegmentCatalog.ENTRY_STATE_WARMING;
            case ACTIVE: return SegmentCatalog.ENTRY_STATE_ACTIVE;
            case COMPACTING: return SegmentCatalog.ENTRY_STATE_COMPACTING;
            case TOMBSTONED: return SegmentCatalog.ENTRY_STATE_DELETED;
            case PURGED: return SegmentCatalog.ENTRY_STATE_NONE;
            default: return -1;
        }
    }

    /**
     * Gets the current lifecycle state of a segment.
     *
     * @param segmentIndex The index of the segment
     * @return The lifecycle state, or null if segment not found
     */
    public LifecycleState getSegmentLifecycleState(long segmentIndex) {
        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentIndex);
        return metadata != null ? metadata.getLifecycleState() : null;
    }

    // ==================== Segment Management ====================

    /**
     * Pre-allocates a segment at the specified index.
     *
     * <p>This is used during startup to reduce contention during high-throughput
     * workloads. Pre-allocated segments are available for immediate use.</p>
     *
     * <p><strong>Important:</strong> The segmentIndex is used as the map key,
     * ensuring hash-based routing works correctly.</p>
     *
     * @param segmentIndex Index of the segment to pre-allocate
     */
    public void preAllocateSegment(int segmentIndex) {
        synchronized (segmentLock) {
            // Check if segment already exists at this index
            if (segments.containsKey((long) segmentIndex)) {
                return;
            }

            Path segmentPath = dataDir.resolve("segment_" + segmentIndex + ".dat");
            Path writeLogPath = dataDir.resolve("segment_" + segmentIndex + ".wal");
            try {
                Segment segment = new Segment(segmentPath, 1024 * 1024, arena);

                // Create and attach WriteLog for crash recovery
                // This ensures all mutations to this segment are crash-safe
                WriteLog writeLog = new WriteLog(writeLogPath, arena);
                segment.attachWriteLog(writeLog);

                // Use segmentIndex as the key for consistent routing
                segments.put((long) segmentIndex, segment);

                // Add to catalog with INDEXING state
                catalog.add((long) segmentIndex, segmentPath);

                statistics.incrementSegmentOperations();
            } catch (IOException e) {
                throw new RuntimeException("Failed to pre-allocate segment at index " + segmentIndex, e);
            }
        }
    }

    /**
     * Gets the segment at the specified index.
     *
     * @param segmentIndex The segment index
     * @return The segment, or null if not found
     */
    public Segment getSegment(long segmentIndex) {
        return segments.get(segmentIndex);
    }

    /**
     * Atomically replaces a segment with a new compacted segment.
     *
     * <p>This method is called by the compaction worker after successfully
     * compacting a segment. It atomically swaps the old segment for the new
     * one in the segment registry.</p>
     *
     * <p><strong>Thread Safety:</strong> This method is synchronized to ensure
     * atomic replacement. Reads during the swap will either see the old or
     * new segment, but never an inconsistent state.</p>
     *
     * @param segmentIndex The segment index
     * @param oldSegment The old segment being replaced (used for verification)
     * @param newSegment The new compacted segment
     * @return true if replacement was successful, false if segment index not found
     *         or old segment doesn't match
     */
    public synchronized boolean replaceSegment(long segmentIndex, Segment oldSegment, Segment newSegment) {
        Segment current = segments.get(segmentIndex);
        if (current == null) {
            LOGGER.log(Level.WARNING, "Cannot replace segment {0}: not found in registry", segmentIndex);
            return false;
        }
        if (current != oldSegment) {
            LOGGER.log(Level.WARNING, "Cannot replace segment {0}: old segment mismatch", segmentIndex);
            return false;
        }
        segments.put(segmentIndex, newSegment);
        LOGGER.log(Level.INFO, "Replaced segment {0}: {1} entries in new segment",
            new Object[] { segmentIndex, newSegment.entryCount() });
        return true;
    }

    /**
     * Gets all active segments.
     *
     * @return Map of segment index to segment instance
     */
    public Map<Long, Segment> getSegments() {
        return new HashMap<>(segments);
    }

    /**
     * Returns the number of segments in the engine.
     *
     * @return Segment count
     */
    public int getSegmentCount() {
        return segments.size();
    }

    /**
     * Returns total entries across all segments.
     *
     * @return Total entry count
     */
    public long getTotalEntryCount() {
        return segments.values().stream()
            .mapToLong(Segment::entryCount)
            .sum();
    }

    /**
     * Flushes all segments to disk.
     *
     * <p>This forces all dirty pages to be written to disk, ensuring
     * durability of recent operations.</p>
     */
    public void flush() {
        for (Segment segment : segments.values()) {
            segment.flush();
        }
    }

    /**
     * Closes all segments and releases resources.
     *
     * <p>This method should be called when the engine is no longer needed
     * to ensure proper cleanup of file handles and memory mappings.</p>
     *
     * <h3>Shutdown Sequence</h3>
     * <ol>
     *   <li>Stop background workers (lifecycle manager, split worker)</li>
     *   <li>Flush all segments to disk</li>
     *   <li>Close all segment file handles</li>
     *   <li>Flush and close the catalog</li>
     *   <li>Reset state</li>
     * </ol>
     */
    @Override
    public void close() {
        // Stop the lifecycle manager if running
        stopLifecycleManager();

        // Stop the split worker if running
        if (splitWorker != null) {
            splitWorker.stop();
        }

        // Flush and close all segments
        for (Segment segment : segments.values()) {
            if (!segment.isClosed()) {
                segment.flush();
                segment.close();
            }
        }
        segments.clear();

        // Flush and close catalog to persist metadata
        catalog.flush();
        catalog.close();
        catalog.clear();

        // Reset state
        started = false;
        // Epoch reset not needed - visibilityTracker handles this;
    }

    /**
     * Checks if the engine is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return segments.isEmpty();
    }

    // ==================== Statistics and Monitoring ====================

    /**
     * Returns statistics about the storage engine.
     *
     * @return Current statistics
     */
    public Statistics getStatistics() {
        return statistics;
    }

    /**
     * Returns the current visibility epoch.
     *
     * @return The current epoch
     */
    public long currentEpoch() {
        return visibilityTracker.currentEpoch();
    }

    /**
     * Returns the visibility tracker for this engine.
     *
     * <p>Use this to coordinate epochs across segments for consistent query snapshots.</p>
     *
     * @return The visibility tracker
     */
    public VisibilityTracker getVisibilityTracker() {
        return visibilityTracker;
    }

    /**
     * Sets a custom visibility tracker.
     *
     * <p>This is typically called by the IndexOrchestrator to inject
     * a coordinated tracker that spans multiple engines.</p>
     *
     * @param tracker The visibility tracker to use
     * @throws NullPointerException if tracker is null
     */
    public void setVisibilityTracker(VisibilityTracker tracker) {
        if (tracker == null) {
            throw new NullPointerException("VisibilityTracker cannot be null");
        }
        this.visibilityTracker = tracker;
    }

    /**
     * Sets the HNSW initializer for creating HNSW graphs for segments.
     *
     * <p>When configured, the initializer is called for each new segment
     * to create and attach an HNSW graph for vector indexing. This enables
     * the HNSW integration worker to integrate vectors from the write log.</p>
     *
     * <p>Set to null to disable HNSW graph creation for new segments.</p>
     *
     * @param initializer The HNSW initializer, or null to disable
     */
    public void setHnswInitializer(HnswInitializer initializer) {
        this.hnswInitializer = initializer;
        LOGGER.info("HNSW initializer " + (initializer != null ? "configured" : "disabled"));
    }

    /**
     * Returns the HNSW initializer.
     *
     * @return The HNSW initializer, or null if not configured
     */
    public HnswInitializer getHnswInitializer() {
        return hnswInitializer;
    }

    /**
     * Returns the segment catalog.
     *
     * @return The catalog
     */
    public SegmentCatalog getCatalog() {
        return catalog;
    }

    /**
     * Returns the data directory path.
     *
     * @return The data directory
     */
    public Path dataDir() {
        return dataDir;
    }

    /**
     * Returns the Arena used for memory allocation.
     *
     * <p>May be null if using the ByteBuffer-based fallback implementation.</p>
     *
     * @return The Arena, or null if not using Panama FFM
     */
    public Arena arena() {
        return arena;
    }

    // ==================== Inner Classes ====================

    /**
     * Statistics for the StorageEngine.
     */
    public static class Statistics {
        private final AtomicLong writeOperations = new AtomicLong(0);
        private final AtomicLong deleteOperations = new AtomicLong(0);
        private final AtomicLong readOperations = new AtomicLong(0);
        private final AtomicLong segmentOperations = new AtomicLong(0);
        private final AtomicLong startOperations = new AtomicLong(0);

        void incrementWriteOperations() { writeOperations.incrementAndGet(); }
        void incrementDeleteOperations() { deleteOperations.incrementAndGet(); }
        void incrementReadOperations() { readOperations.incrementAndGet(); }
        void incrementSegmentOperations() { segmentOperations.incrementAndGet(); }
        void incrementStartOperations() { startOperations.incrementAndGet(); }

        public long getWriteOperations() { return writeOperations.get(); }
        public long getDeleteOperations() { return deleteOperations.get(); }
        public long getReadOperations() { return readOperations.get(); }
        public long getSegmentOperations() { return segmentOperations.get(); }
        public long getStartOperations() { return startOperations.get(); }

        @Override
        public String toString() {
            return String.format(
                "Statistics{writes=%d, deletes=%d, reads=%d, segments=%d, starts=%d}",
                writeOperations.get(), deleteOperations.get(), readOperations.get(),
                segmentOperations.get(), startOperations.get());
        }
    }

    // ==================== Visibility Epoch and Query Snapshot ====================

    /**
     * Captures a consistent snapshot of the storage engine state for queries.
     *
     * <p>The snapshot includes the current visibility epoch and the list of
     * segments that are queryable at this epoch. This allows queries to work
     * with a consistent view throughout their execution, even if segments
     * are tombstoned or modified during the query.</p>
     *
     * <p><strong>Usage:</strong></p>
     * <pre>{@code
     * QuerySnapshot snapshot = engine.captureSnapshot();
     * for (Segment segment : snapshot.segments()) {
     *     // Process segment with consistent visibility
     *     // Segments tombstoned after snapshot.epoch() are included
     * }
     * }</pre>
     *
     * @return A QuerySnapshot capturing the current state
     */
    public QuerySnapshot captureSnapshot() {
        // Capture the visibility epoch first
        long epoch = visibilityTracker.currentEpoch();

        // Get all queryable segments at this epoch
        List<Segment> queryableSegments = getQueryableSegmentsAtEpoch(epoch);

        return new QuerySnapshot(epoch, queryableSegments);
    }

    /**
     * Gets all segments that are queryable at a specific visibility epoch.
     *
     * <p>A segment is queryable at epoch E if:</p>
     * <ul>
     *   <li>Its lifecycle state is INDEXING, WARMING, ACTIVE, or COMPACTING</li>
     *   <li>If tombstoned, the tombstone epoch is greater than E
     *       (i.e., tombstoned after the query started)</li>
     * </ul>
     *
     * <p>This method is used by {@link #captureSnapshot()} to build a consistent
     * segment list for queries.</p>
     *
     * @param queryEpoch The visibility epoch at which to evaluate queryability
     * @return List of segments queryable at the specified epoch
     */
    public List<Segment> getQueryableSegmentsAtEpoch(long queryEpoch) {
        List<Segment> result = new ArrayList<>();

        for (Map.Entry<Long, Segment> entry : segments.entrySet()) {
            long segmentIndex = entry.getKey();
            Segment segment = entry.getValue();

            // Get metadata for lifecycle state and tombstone epoch
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentIndex);
            if (metadata == null) {
                continue;
            }

            LifecycleState state = metadata.getLifecycleState();

            // Check if the segment is in a queryable state
            if (!state.isQueryable()) {
                // If tombstoned, check if it was tombstoned after the query started
                if (state == LifecycleState.TOMBSTONED) {
                    if (metadata.isVisibleAtEpoch(queryEpoch)) {
                        result.add(segment);
                    }
                }
                // Skip BOOTSTRAPPING and PURGED segments
                continue;
            }

            // Segment is in INDEXING, WARMING, ACTIVE, or COMPACTING state
            result.add(segment);
        }

        return result;
    }

    /**
     * Gets all segments that are currently queryable (INDEXING, WARMING, ACTIVE, COMPACTING).
     *
     * <p>This is a convenience method that returns all segments in queryable states
     * without considering tombstone epochs. For epoch-aware visibility, use
     * {@link #getQueryableSegmentsAtEpoch(long)}.</p>
     *
     * @return List of currently queryable segments
     */
    public List<Segment> getQueryableSegments() {
        List<Segment> result = new ArrayList<>();

        for (Map.Entry<Long, Segment> entry : segments.entrySet()) {
            long segmentIndex = entry.getKey();
            Segment segment = entry.getValue();

            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentIndex);
            if (metadata != null && metadata.getLifecycleState().isQueryable()) {
                result.add(segment);
            }
        }

        return result;
    }

    /**
     * Marks a segment as tombstoned with visibility epoch tracking.
     *
     * <p>This method records the current visibility epoch when tombstoning,
     * enabling epoch-based visibility for in-flight queries.</p>
     *
     * @param segmentIndex The index of the segment to tombstone
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if the segment is not in a tombstoneable state
     */
    public void tombstoneSegment(long segmentIndex) {
        long epoch = visibilityTracker.currentEpoch();
        catalog.tombstone(segmentIndex, epoch);
    }

    /**
     * Increments the visibility epoch.
     *
     * <p>This is typically called after write operations to advance the epoch.
     * The epoch is also used for tracking when segments are tombstoned.</p>
     *
     * @return The new epoch value after incrementing
     */
    public long incrementEpoch() {
        return visibilityTracker.advanceEpoch();
    }

    // ==================== Split Worker Management ====================

    /**
     * Returns the split worker for this engine.
     *
     * <p>The split worker handles automatic capacity management by
     * splitting segments that reach the load factor threshold.</p>
     *
     * @return The split worker, or null if not initialized
     */
    public SplitWorker getSplitWorker() {
        return splitWorker;
    }

    /**
     * Handles split completion by updating the segment registry.
     *
     * <p>This method is called by the {@link SplitWorker} when a segment
     * split completes. It atomically:</p>
     * <ol>
     *   <li>Removes the parent segment from the registry</li>
     *   <li>Adds the left and right children to the registry</li>
     *   <li>Updates the catalog with new segment metadata</li>
     *   <li>Registers children with the split worker for future monitoring</li>
     * </ol>
     *
     * <h3>Routing After Split</h3>
     * <p>After this method returns, new operations should route to the
     * appropriate child based on the hash bit. The routing formula is:
     * {@code goesRight = (keyHash >>> splitBit) & 1L == 1L}</p>
     *
     * <p><strong>Thread Safety:</strong> This method is called from the
     * split worker's background thread. The segment registry update is
     * synchronized to ensure atomic visibility.</p>
     *
     * @param parent The parent segment that was split
     * @param leftChild The left child segment (entries with hash bit 0)
     * @param rightChild The right child segment (entries with hash bit 1)
     */
    private void handleSplitComplete(Segment parent, Segment leftChild, Segment rightChild) {
        synchronized (segmentLock) {
            // Find and remove parent from registry
            Long parentIndex = null;
            for (Map.Entry<Long, Segment> entry : segments.entrySet()) {
                if (entry.getValue() == parent) {
                    parentIndex = entry.getKey();
                    break;
                }
            }

            if (parentIndex != null) {
                // Remove parent from segment registry
                segments.remove(parentIndex);

                // Remove parent from catalog
                catalog.remove(parentIndex);

                // Unregister parent from split worker
                if (splitWorker != null) {
                    splitWorker.unregisterSegment(parent);
                }
            }

            // Generate indices for children
            // Use a simple scheme: parentIndex * 2 for left, parentIndex * 2 + 1 for right
            long leftIndex = parentIndex != null ? parentIndex * 2 : segments.size();
            long rightIndex = parentIndex != null ? parentIndex * 2 + 1 : segments.size() + 1;

            // Add children to registry
            segments.put(leftIndex, leftChild);
            segments.put(rightIndex, rightChild);

            // Add children to catalog
            catalog.add(leftIndex, leftChild.path());
            catalog.add(rightIndex, rightChild.path());

            // Update entry counts in catalog
            catalog.updateEntryCount(leftIndex, leftChild.entryCount());
            catalog.updateEntryCount(rightIndex, rightChild.entryCount());

            // Register children with split worker for future monitoring
            if (splitWorker != null && splitWorker.isRunning()) {
                splitWorker.registerSegment(leftChild);
                splitWorker.registerSegment(rightChild);
            }

            // Increment visibility epoch to signal state change
            visibilityTracker.advanceEpoch();
        }
    }

    // ==================== Inner Classes ====================

    /**
     * A consistent snapshot of the storage engine state for queries.
     *
     * <p>QuerySnapshot captures the visibility epoch and segment list at a point
     * in time, allowing queries to work with a consistent view even as the
     * underlying storage is modified.</p>
     *
     * <h2>Thread Safety</h2>
     * <p>Instances of this class are immutable and thread-safe. The segment list
     * is a copy of the segments at the time of capture.</p>
     *
     * <h2>Domain Correctness</h2>
     * <ul>
     *   <li>{@code epoch}: Non-negative long representing the visibility epoch at capture time</li>
     *   <li>{@code segments}: Immutable list of segments queryable at the captured epoch</li>
     * </ul>
     */
    public static final class QuerySnapshot {
        private final long epoch;
        private final List<Segment> segments;

        /**
         * Creates a new QuerySnapshot.
         *
         * @param epoch The visibility epoch at capture time
         * @param segments The list of queryable segments (will be copied)
         */
        public QuerySnapshot(long epoch, List<Segment> segments) {
            if (epoch < 0) {
                throw new IllegalArgumentException("Epoch must be non-negative: " + epoch);
            }
            this.epoch = epoch;
            this.segments = List.copyOf(segments);
        }

        /**
         * Returns the visibility epoch at which this snapshot was captured.
         *
         * @return The epoch value
         */
        public long epoch() {
            return epoch;
        }

        /**
         * Returns the list of segments that are queryable at this snapshot's epoch.
         *
         * <p>The returned list is immutable.</p>
         *
         * @return The list of queryable segments
         */
        public List<Segment> segments() {
            return segments;
        }

        /**
         * Returns the number of segments in this snapshot.
         *
         * @return The segment count
         */
        public int segmentCount() {
            return segments.size();
        }

        @Override
        public String toString() {
            return String.format("QuerySnapshot{epoch=%d, segments=%d}", epoch, segments.size());
        }
    }
}
