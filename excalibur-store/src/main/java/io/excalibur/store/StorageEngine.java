package io.excalibur.store;

import io.excalibur.core.memory.MemorySegmentFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>The segment index is computed as: {@code hash & (routingSegmentCount - 1)}</li>
 *   <li>The same key ALWAYS routes to the same segment index</li>
 *   <li>Lookups are O(1) - no scanning required</li>
 * </ul>
 *
 * <h2>Extensible Hashing Directory</h2>
 * <p>{@code segments} is a <em>directory</em>, not a set: several directory indices may
 * point at the same {@link Segment}. When one segment splits, the directory doubles and
 * every index {@code i} that did not split gets a buddy pointer at {@code i + oldCount}
 * aimed at the same segment, so widening the routing mask never orphans another
 * segment's keys. Only the segment that split gets two distinct children.</p>
 *
 * <p>The <em>canonical index</em> of a segment is the smallest directory index that
 * points at it. It is the index used for its file name and its catalog entry.
 * Enumerations ({@link #getSegments()}, {@link #getQueryableSegments()},
 * {@link #getTotalEntryCount()}) are de-duplicated by segment identity.</p>
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

    /**
     * How long a write blocked on capacity waits for a split to publish, in ms.
     */
    private static final long SPLIT_WAIT_TIMEOUT_MS = 5000;

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
     * Effective segment count for hash-based routing.
     * MUST always be a power of 2. This is separate from segments.size() because
     * splits can temporarily make the map size non-power-of-2. Routing always
     * uses this value: segmentIndex = keyHash & (routingSegmentCount - 1).
     */
    private volatile int routingSegmentCount;

    /**
     * How many times a routed operation may re-resolve before giving up.
     *
     * <p>Each retry follows a split that has already published, so one is normally enough;
     * the bound is there so a routing bug surfaces as an error rather than a hang.</p>
     */
    private static final int MAX_ROUTING_RETRIES = 8;

    /**
     * The routing directory and the mask that indexes it, as one value.
     *
     * <p>Read once per operation, so the width a key is masked with and the slot it lands in
     * always come from the same moment. Reading them separately let an operation mask with the
     * pre-split width and then look up a slot the split had already re-pointed, which sends a
     * key whose split bit is 1 to the left child - a lookup that answers "absent" for a key that
     * exists, and a write that lands in the wrong half.</p>
     *
     * <p>Rebuilt by {@link #publishRouting()} after every change to the directory, under the
     * same lock that made the change.</p>
     */
    private volatile Routing routing = new Routing(0, new Segment[0]);

    /**
     * Whether this engine created {@link #arena} and must therefore close it.
     *
     * <p>The single-argument constructor creates its own shared Arena; the Arena-taking
     * constructors borrow one owned by the caller and must not close it.</p>
     */
    private final boolean ownsArena;

    /**
     * Canonical directory index of each live segment.
     *
     * <p>Keyed by identity (Segment does not override equals/hashCode). Buddy pointers
     * mean a segment can be reachable from several directory indices; this map records
     * the one that names its file and its catalog entry, so catalog updates never
     * create a second entry for a segment that already has one.</p>
     */
    private final Map<Segment, Long> canonicalIndex = new ConcurrentHashMap<>();

    /**
     * Lock object for synchronizing segment creation and directory updates.
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
     * Vector pre-filter attached to every queryable segment alongside its HNSW graph.
     * May be null if no pre-filter is configured.
     */
    private volatile VectorPrefilter vectorPrefilter;

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
     * <p>The engine creates and owns a shared {@link Arena} for all segment and write
     * log mappings; {@link #close()} closes it. Panama FFM is required - {@link Segment}
     * and {@link WriteLog} both reject a null Arena, so there is no working
     * ByteBuffer fallback and this constructor fails fast when Panama is unavailable.</p>
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
        if (!usingPanama) {
            throw new IllegalStateException(
                "StorageEngine requires Panama FFM (Java 21+ with native access enabled). "
                + "The ByteBuffer fallback cannot back Segment or WriteLog mappings.");
        }
        this.arena = Arena.ofShared();
        this.ownsArena = true;
        this.catalog = new SegmentCatalog(dataDir.resolve("catalog.dat"));
        this.segments = new ConcurrentHashMap<>(DEFAULT_INITIAL_SEGMENTS);
        this.routingSegmentCount = DEFAULT_INITIAL_SEGMENTS;
        publishRouting();

        // Initialize split worker with listener that publishes the split into the
        // routing directory. This enables automatic capacity management when segments
        // reach the load factor threshold or exhaust their data region.
        this.splitWorker = newSplitWorker();

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
        this.ownsArena = false;
        this.usingPanama = true;
        this.catalog = new SegmentCatalog(dataDir.resolve("catalog.dat"), arena);
        this.segments = new ConcurrentHashMap<>(DEFAULT_INITIAL_SEGMENTS);
        this.routingSegmentCount = DEFAULT_INITIAL_SEGMENTS;
        publishRouting();

        // Initialize split worker with listener that publishes the split into the
        // routing directory. This enables automatic capacity management when segments
        // reach the load factor threshold or exhaust their data region.
        this.splitWorker = newSplitWorker();
    }

    /**
     * Creates the split worker for this engine.
     *
     * <p>The worker is given the split bit explicitly rather than deriving one: the
     * bit that a doubling of the routing table newly exposes is
     * {@code Integer.numberOfTrailingZeros(routingSegmentCount)}, and partitioning on
     * any other bit produces children the router cannot address.</p>
     *
     * @return A split worker wired to this engine's routing table
     */
    private SplitWorker newSplitWorker() {
        return new SplitWorker(
            arena,
            this::handleSplitComplete,
            () -> Integer.numberOfTrailingZeros(routingSegmentCount));
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

    /**
     * Sets the load-factor threshold new segments are created with.
     *
     * <p>Must be called before {@link #start()}. Segments already open keep the threshold they
     * were built with, so changing it afterwards would leave the engine applying two different
     * policies at once, which is harder to reason about than requiring it up front.
     *
     * <p>This module has no config dependency; the value comes from
     * {@code excalibur.shard.segment-load-factor} via whoever constructs the engine.
     *
     * @param threshold fraction of slots that may be occupied before a segment splits, in (0, 1)
     */
    public StorageEngine withLoadFactorThreshold(double threshold) {
        if (threshold <= 0.0 || threshold >= 1.0 || Double.isNaN(threshold)) {
            throw new IllegalArgumentException(
                "segment load factor must be in (0, 1), got " + threshold);
        }
        if (started) {
            throw new IllegalStateException(
                "load factor must be set before start(); segments are pre-allocated there");
        }
        this.loadFactorThreshold = threshold;
        return this;
    }

    /** The load-factor threshold new segments are created with. */
    public double loadFactorThreshold() {
        return loadFactorThreshold;
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
                // Open existing segment WITHOUT truncating - preserves data.
                // With the configured threshold, not the compile-time default: a segment
                // filled under a larger configured factor and reopened at the default rejects
                // replay of entries it had already acknowledged, and the startup fails every
                // time from then on.
                Segment segment =
                    Segment.openExisting(discovered.path(), arena, loadFactorThreshold);

                long segmentIndex = discovered.segmentIndex();
                segments.put(segmentIndex, segment);
                canonicalIndex.put(segment, segmentIndex);

                // Recovered segments are queryable, so they need a graph too.
                initializeHnswGraph(segment, segmentIndex);
                initializeVectorPrefilter(segment, segmentIndex);

                // Track max index for routing table sizing
                if (segmentIndex > maxSegmentIndex) {
                    maxSegmentIndex = (int) segmentIndex;
                }

                // Every segment gets a write log. A missing or empty WAL used to mean "attach
                // nothing", and Segment.appendPutToLog quietly returns when there is no log -
                // so the segment served reads and writes normally while nothing it accepted was
                // logged, durable only if the OS happened to write the mapping back. The window
                // is real: compaction unlinks a WAL and then creates its replacement, and a
                // crash in between leaves an absent or zero-length file.
                Path walPath = discovered.walPath() != null
                    ? discovered.walPath()
                    : writeLogPath(segmentIndex);
                WriteLog writeLog = new WriteLog(walPath);
                segment.attachWriteLog(writeLog);

                if (discovered.hasWriteLog()) {
                    try {
                        // Replay write log to recover any uncommitted entries
                        segment.recoverFromLog();
                    } catch (IOException | RuntimeException e) {
                        // A replay that cannot be completed means the segment's committed
                        // state is unknown. Refuse to start rather than serving a segment
                        // that silently lost the tail of its log.
                        throw new IllegalStateException(
                            "Write-ahead log replay failed for segment " + segmentIndex
                            + " (" + walPath + "). Refusing to start with "
                            + "partially recovered state.", e);
                    }
                } else {
                    LOGGER.log(Level.WARNING,
                        "Segment {0} had no usable write-ahead log at {1}; a fresh one was "
                        + "created. Anything it accepted since the log was lost is not "
                        + "recoverable.",
                        new Object[] { segmentIndex, walPath });
                }

                // Update last integrated offset from catalog if available
                SegmentCatalog.SegmentMetadata walMetadata = catalog.get(segmentIndex);
                if (walMetadata != null) {
                    writeLog.setLastIntegratedOffset(walMetadata.getLastIntegratedOffset());
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

        rebuildRoutingDirectory(maxSegmentIndex);

        // Start the background split worker for automatic capacity management
        if (splitWorker != null) {
            splitWorker.start();

            // Register each distinct segment once for load factor monitoring
            for (Segment segment : distinctSegments().values()) {
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
     * Rebuilds the routing directory so it covers exactly {@code [0, routingSegmentCount)}.
     *
     * <p>The directory width is the smallest power of two that both covers every
     * discovered segment index and is at least {@link #DEFAULT_INITIAL_SEGMENTS}. The
     * base indices below {@code DEFAULT_INITIAL_SEGMENTS} are created if missing;
     * every higher index without a file of its own is a buddy pointer to the segment it
     * was split from, exactly as it was before the restart.</p>
     *
     * <p>Creating a fresh empty segment for such an index instead would orphan half of
     * the keys of the segment it buddies with, which is why only base indices are
     * created here.</p>
     *
     * @param maxSegmentIndex Highest segment index found on disk, or -1 if none
     */
    private void rebuildRoutingDirectory(int maxSegmentIndex) {
        int width = DEFAULT_INITIAL_SEGMENTS;
        while (width <= maxSegmentIndex) {
            width <<= 1;
        }

        // Base indices always have their own file.
        for (int i = 0; i < DEFAULT_INITIAL_SEGMENTS; i++) {
            if (!segments.containsKey((long) i)) {
                createSegment(i);
            }
        }

        // The directory as it actually was, when the previous run wrote it down.
        if (applyPersistedRouting(width)) {
            return;
        }

        // Otherwise the buddy heuristic, which is exact only while at most one segment has ever
        // split. Beyond that it cannot be exact -- two segments that split from a common
        // ancestor look the same by file name as two that did not -- so it is a best effort and
        // says so. See RoutingDirectory for the worked example.
        if (maxSegmentIndex < 0) {
            // A store with no segments has no directory because it has never needed one. What is
            // built below is the initial directory rather than a reconstruction of a lost one,
            // and nothing has split for it to get wrong - so warning here would only teach an
            // operator that the first line of every new deployment is noise.
            LOGGER.log(Level.INFO, "New store: routing directory created with {0} slots", width);
        } else {
            LOGGER.log(Level.WARNING,
                "No routing directory at {0}; reconstructing it from segment file names. This is "
                    + "exact only if at most one segment has ever split.",
                dataDir.resolve(RoutingDirectory.FILE_NAME));
        }

        for (int i = DEFAULT_INITIAL_SEGMENTS; i < width; i++) {
            if (segments.containsKey((long) i)) {
                continue;
            }
            Segment buddy = segments.get((long) (i - Integer.highestOneBit(i)));
            if (buddy != null) {
                segments.put((long) i, buddy);
            } else {
                LOGGER.log(Level.WARNING,
                    "No buddy segment for routing index {0}; creating an empty segment", i);
                createSegment(i);
            }
        }

        routingSegmentCount = width;
        publishRouting();
        persistRoutingDirectory();
    }

    /**
     * Restores the directory the previous run wrote, if it is there and still makes sense.
     *
     * <p>Rejected rather than partially applied when it does not: a directory naming a width
     * that disagrees with the files on disk, or pointing at a segment that is no longer there,
     * describes a store this file was not written for.
     *
     * @param width The width implied by the segment files found
     * @return true when the persisted directory was applied
     */
    private boolean applyPersistedRouting(int width) {
        int[] canonicalPerSlot = RoutingDirectory.read(dataDir);
        if (canonicalPerSlot == null) {
            return false;
        }
        if (canonicalPerSlot.length < width) {
            LOGGER.log(Level.WARNING,
                "Routing directory covers {0} slots but the segment files need {1}; "
                    + "reconstructing instead",
                new Object[] { canonicalPerSlot.length, width });
            return false;
        }

        Map<Long, Segment> resolved = new HashMap<>(canonicalPerSlot.length);
        for (int slot = 0; slot < canonicalPerSlot.length; slot++) {
            Segment segment = segments.get((long) canonicalPerSlot[slot]);
            if (segment == null) {
                LOGGER.log(Level.WARNING,
                    "Routing directory sends slot {0} to segment {1}, which is not on disk; "
                        + "reconstructing instead",
                    new Object[] { slot, canonicalPerSlot[slot] });
                return false;
            }
            resolved.put((long) slot, segment);
        }

        segments.putAll(resolved);
        routingSegmentCount = canonicalPerSlot.length;
        publishRouting();
        LOGGER.log(Level.INFO, "Routing directory restored: {0} slots",
            canonicalPerSlot.length);
        return true;
    }

    /**
     * Writes the current directory so the next start does not have to guess it.
     *
     * <p>Failure is logged, not thrown. Losing the file costs accuracy on the next restart,
     * which is recoverable; failing the split that just succeeded is not.
     */
    private void persistRoutingDirectory() {
        try {
            int width = routingSegmentCount;
            Map<Segment, Long> canonical = new IdentityHashMap<>();
            for (Map.Entry<Long, Segment> entry : segments.entrySet()) {
                canonical.merge(entry.getValue(), entry.getKey(), Math::min);
            }

            int[] canonicalPerSlot = new int[width];
            for (int slot = 0; slot < width; slot++) {
                Segment segment = segments.get((long) slot);
                Long index = segment == null ? null : canonical.get(segment);
                canonicalPerSlot[slot] = index == null ? slot : index.intValue();
            }
            RoutingDirectory.write(dataDir, canonicalPerSlot);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING,
                "Failed to persist the routing directory; the next start will reconstruct it", e);
        }
    }

    /**
     * Returns each distinct segment keyed by its canonical (smallest) directory index.
     *
     * <p>The directory can point several indices at the same segment, so any traversal
     * that must visit a segment once - counting entries, flushing, closing, building a
     * query snapshot - has to go through this de-duplication.</p>
     *
     * @return Canonical index to segment
     */
    private Map<Long, Segment> distinctSegments() {
        IdentityHashMap<Segment, Long> canonical = new IdentityHashMap<>();
        for (Map.Entry<Long, Segment> entry : segments.entrySet()) {
            canonical.merge(entry.getValue(), entry.getKey(), Math::min);
        }
        Map<Long, Segment> result = new HashMap<>(canonical.size());
        canonical.forEach((segment, index) -> result.put(index, segment));
        return result;
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
        putReportingSegment(key, value);
    }

    /**
     * Writes a key and says which segment it landed in.
     *
     * <p>Identical to {@link #put(byte[], byte[])} in every effect; it differs only in telling
     * the caller where the write went. The engine routes by key hash and may split the target
     * mid-write, so a caller that keeps anything per segment - a membership filter, a
     * per-segment index - cannot work that out for itself, and guessing would file the fact
     * under a segment that does not hold the key.</p>
     *
     * @param key The key (must not be null)
     * @param value The value (must not be null)
     * @return The id of the segment the key was written to
     * @throws NullPointerException if key or value is null
     * @throws IllegalStateException if engine is not started
     * @throws SegmentAtCapacityException if segment is at capacity and split cannot complete
     */
    public long putReportingSegment(byte[] key, byte[] value) {
        if (key == null || value == null) {
            throw new NullPointerException("Key and value cannot be null");
        }

        if (!started) {
            throw new IllegalStateException("StorageEngine must be started before operations");
        }

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);

        // Bounded, because each retry follows a publication that has already happened: a
        // segment that reports itself retired has been replaced in the directory this loop
        // re-reads, so a second pass resolves the child. Looping without a bound would turn a
        // routing bug into a hang.
        for (int attempt = 0; attempt < MAX_ROUTING_RETRIES; attempt++) {
            Routing snapshot = routing;
            int segmentIndex = snapshot.slotFor(keyHash);
            if (segmentIndex < 0) {
                throw new IllegalStateException("No segments available - call start() first");
            }

            Segment segment = snapshot.segmentFor(keyHash);
            if (segment == null) {
                // Create segment at this index only if it doesn't exist
                segment = createSegment(segmentIndex);
            }

            try {
                segment.put(key, value);

                // Update catalog entry count only - don't change lifecycle state
                // Segments remain in INDEXING state during writes
                catalog.updateEntryCount(catalogIndexOf(segment, segmentIndex), 1);

                // Increment visibility epoch for consistent reads
                visibilityTracker.advanceEpoch();

                statistics.incrementWriteOperations();
                return segment.segmentId();
            } catch (SegmentCapacityExceededException e) {
                // Every capacity signal - load factor, exhausted data region, or an
                // unsatisfiable probe chain - is handled the same way: split and retry.
                // Classifying these by exception message would silently miss the data
                // region case, which is the one vector payloads hit first.
                return putWithSplitHandling(key, value, keyHash, segment, segmentIndex);
            } catch (SegmentRetiredException e) {
                // The split published while this write was in flight. The key belongs to one of
                // the children now; writing it here would land in a file nothing can reach.
                LOGGER.log(Level.FINE,
                    "Re-routing a write after the target segment was retired by a split");
            }
        }

        throw new IllegalStateException(
            "Could not route a write after " + MAX_ROUTING_RETRIES
                + " attempts: the directory kept changing underneath it");
    }

    /**
     * Returns the catalog index for a segment.
     *
     * <p>Buddy pointers make a segment reachable from several directory indices; its
     * catalog entry lives under the canonical (smallest) one. Falls back to the routed
     * index for a segment that is not in the directory.</p>
     *
     * @param segment     The segment
     * @param routedIndex The directory index the key routed to
     * @return The catalog index to update
     */
    private long catalogIndexOf(Segment segment, long routedIndex) {
        Long canonical = canonicalIndex.get(segment);
        return canonical != null ? canonical : routedIndex;
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
     * <p><strong>Domain Correctness:</strong> the child is selected with the very bit
     * the split partitioned on, read back from the parent via {@link Segment#splitBit()}.
     * Deriving a bit independently here is what makes a split lose half its keys.</p>
     *
     * @param key The key to insert
     * @param value The value to insert
     * @param keyHash The pre-computed key hash
     * @param segment The segment that is at capacity
     * @param segmentIndex The segment index
     * @throws SegmentAtCapacityException if split cannot complete in time
     */
    private long putWithSplitHandling(byte[] key, byte[] value, long keyHash,
                                       Segment segment, int segmentIndex) {
        if (splitWorker == null || !splitWorker.isRunning()) {
            // No split worker available - cannot handle capacity automatically
            throw new SegmentAtCapacityException(
                "Segment " + segmentIndex + " at capacity. No automatic splitting available. " +
                "Ensure StorageEngine is started to enable automatic capacity management.");
        }

        // The bit a doubling of the routing table newly exposes. The worker uses the
        // routing table's current value, so this is advisory only.
        int expectedSplitBit = Integer.numberOfTrailingZeros(routingSegmentCount);
        splitWorker.requestSplit(segment, expectedSplitBit);

        awaitSplit(segment, segmentIndex);

        // Route to the child using the bit the split actually partitioned on.
        int splitBit = segment.splitBit();
        boolean goesRight = Segment.shouldGoToRightChild(keyHash, splitBit);
        Segment child = goesRight ? segment.rightChild() : segment.leftChild();

        if (child == null) {
            throw new IllegalStateException(
                "Split completed but child segment is null. Segment: " + segmentIndex);
        }

        // Insert into child segment
        child.put(key, value);

        catalog.updateEntryCount(catalogIndexOf(child, segmentIndex), 1);

        visibilityTracker.advanceEpoch();
        statistics.incrementWriteOperations();
        return child.segmentId();
    }

    /**
     * Waits for an in-progress split of the given segment to publish its children.
     *
     * @param segment      The segment being split
     * @param segmentIndex The segment's directory index, for error messages
     * @throws SegmentAtCapacityException if the split does not complete in time
     */
    private void awaitSplit(Segment segment, int segmentIndex) {
        long deadline = System.currentTimeMillis() + SPLIT_WAIT_TIMEOUT_MS;
        while (!segment.isSplitComplete()) {
            if (System.currentTimeMillis() > deadline) {
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
        return createSegment(segmentIndex);
    }

    /**
     * Creates, registers and fully initializes the segment at the given directory index.
     *
     * <p>This is the single creation path. Both eager pre-allocation and lazy creation
     * on first write go through it, so a segment can never be created without its write
     * log, its catalog entry or its HNSW graph.</p>
     *
     * @param segmentIndex The directory index for the new segment
     * @return The created or already-present segment
     * @throws RuntimeException if the segment or its write log cannot be created
     */
    private Segment createSegment(int segmentIndex) {
        synchronized (segmentLock) {
            // Double-check after acquiring lock
            Segment existing = segments.get((long) segmentIndex);
            if (existing != null) {
                return existing;
            }

            Path segmentPath = segmentPath(segmentIndex);
            try {
                Segment segment = new Segment(segmentPath, 1024 * 1024, arena, loadFactorThreshold);

                // Create and attach WriteLog for crash recovery. Every mutation is
                // recorded in the log so a crash does not lose committed writes.
                segment.attachWriteLog(new WriteLog(writeLogPath(segmentIndex)));

                initializeHnswGraph(segment, segmentIndex);
                initializeVectorPrefilter(segment, segmentIndex);

                segments.put((long) segmentIndex, segment);
                canonicalIndex.put(segment, (long) segmentIndex);
                publishRouting();

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
     * Returns the canonical file path for a segment index.
     *
     * @param segmentIndex The directory index
     * @return {@code <dataDir>/segment_<index>.dat}
     */
    private Path segmentPath(long segmentIndex) {
        return dataDir.resolve("segment_" + segmentIndex + ".dat");
    }

    /**
     * Returns the canonical write log path for a segment index.
     *
     * @param segmentIndex The directory index
     * @return {@code <dataDir>/segment_<index>.wal}
     */
    private Path writeLogPath(long segmentIndex) {
        return dataDir.resolve("segment_" + segmentIndex + ".wal");
    }

    /**
     * Attaches an HNSW graph to a segment if an initializer is configured.
     *
     * <p>Every segment that can be queried goes through this method - segments created
     * lazily, segments pre-allocated at startup, segments recovered by discovery and
     * split children alike - so vector search can never be silently disabled for part
     * of the keyspace.</p>
     *
     * @param segment      The segment to initialize
     * @param segmentIndex The segment's directory index, for logging
     */
    private void initializeHnswGraph(Segment segment, long segmentIndex) {
        HnswInitializer initializer = this.hnswInitializer;
        if (initializer == null || segment.getHnswGraph() != null) {
            return;
        }
        try {
            initializer.initializeForSegment(segment);
            LOGGER.fine("HNSW graph initialized for segment " + segmentIndex);
        } catch (IOException e) {
            LOGGER.warning("Failed to initialize HNSW graph for segment "
                + segmentIndex + ": " + e.getMessage());
            // Continue without HNSW - segment is still functional for KV operations
        }
    }

    /**
     * Attaches a vector pre-filter to a segment if one is configured.
     *
     * <p>Paired with {@link #initializeHnswGraph(Segment, long)} and called from the same places,
     * because the pre-filter is keyed by the node ids that graph hands out. A segment with a
     * graph and no pre-filter is merely slower to search; a segment whose pre-filter belongs to
     * different data answers with the wrong nodes, which is why the split path discards the
     * inherited state before this runs.</p>
     *
     * @param segment      The segment to initialize
     * @param segmentIndex The segment's directory index, for logging
     */
    private void initializeVectorPrefilter(Segment segment, long segmentIndex) {
        VectorPrefilter prefilter = this.vectorPrefilter;
        if (prefilter == null || segment.getLshIndex() != null) {
            return;
        }
        try {
            prefilter.initialize(segment);
            LOGGER.fine("Vector pre-filter initialized for segment " + segmentIndex);
        } catch (RuntimeException e) {
            LOGGER.warning("Failed to initialize vector pre-filter for segment "
                + segmentIndex + ": " + e.getMessage());
            // Continue without a pre-filter - the query pipeline falls back to a full search
        }
    }

    /**
     * Removes the persisted pre-filter state a split child would otherwise inherit.
     *
     * <p>The counterpart of {@link #discardInheritedGraph(Segment, long)}, and needed for the
     * same reason: the pre-filter's files are derived from the segment's canonical name, the left
     * child takes its parent's name, and the graph the child starts is a new one whose node ids
     * begin again at zero. The inherited index holds its parent's ids, so leaving it would have
     * the query pipeline restrict the child's search to nodes that are either absent or hold
     * entirely different vectors.</p>
     *
     * @param child        The child being prepared
     * @param segmentIndex The child's canonical index
     */
    private void discardInheritedPrefilter(Segment child, long segmentIndex) {
        VectorPrefilter prefilter = this.vectorPrefilter;
        if (prefilter == null || child.getLshIndex() != null) {
            return;
        }
        try {
            prefilter.discardPersistedState(child);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                "Could not remove the vector pre-filter a split child would inherit (segment "
                    + segmentIndex + ")", e);
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

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);

        // One read of the directory, so the mask and the slot agree.
        Segment segment = routing.segmentFor(keyHash);
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

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);

        Segment segment = routing.segmentFor(keyHash);
        if (segment == null) {
            return ZeroCopySlice.notFound(0, 0, null);
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

        long keyHash = io.excalibur.store.hash.XxHash64.hash(key);

        for (int attempt = 0; attempt < MAX_ROUTING_RETRIES; attempt++) {
            Routing snapshot = routing;
            int segmentIndex = snapshot.slotFor(keyHash);
            if (segmentIndex < 0) {
                return false;
            }

            Segment segment = snapshot.segmentFor(keyHash);
            if (segment == null) {
                return false;
            }

            try {
                if (segment.delete(key)) {
                    // Only update entry count, don't change lifecycle state
                    // Entry tombstones are handled separately from segment lifecycle
                    long catalogIndex = catalogIndexOf(segment, segmentIndex);
                    catalog.updateEntryCount(catalogIndex, -1);
                    catalog.recordTombstone(catalogIndex);
                    statistics.incrementDeleteOperations();
                    visibilityTracker.advanceEpoch();
                    return true;
                }
                return false;
            } catch (SegmentRetiredException e) {
                // Tombstoning the retired parent reported success for a key that stays live in
                // whichever child now owns it.
                LOGGER.log(Level.FINE,
                    "Re-routing a delete after the target segment was retired by a split");
            }
        }

        throw new IllegalStateException(
            "Could not route a delete after " + MAX_ROUTING_RETRIES
                + " attempts: the directory kept changing underneath it");
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
     * Segment indices whose reclamation has already been refused and logged.
     *
     * <p>A refusal is a standing condition, not an event: the lifecycle sweep offers the same
     * segment on every tick for as long as it stays tombstoned. Recording it keeps the warning
     * to once per segment instead of once per tick.</p>
     */
    private final java.util.Set<Long> refusedReclamations = ConcurrentHashMap.newKeySet();

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
                    SegmentLifecycleManager manager = new SegmentLifecycleManager(catalog);
                    // Reclamation must be gated on the visibility epoch, not wall clock.
                    manager.setCurrentEpochSupplier(() -> visibilityTracker.currentEpoch());
                    // Nothing else moves a segment out of INDEXING: every later transition is
                    // driven by a worker that selects on a later state.
                    manager.setIndexingCompletionProbe(this::isIndexingComplete);
                    // Only the engine knows which indices the directory still routes to, and
                    // reclaiming one of those is undone by the next start.
                    manager.setReclamationCallback(segmentId -> reclaim(manager, segmentId));
                    lifecycleManager = manager;
                }
            }
        }
        return lifecycleManager;
    }

    /**
     * Reclaims a tombstoned segment, or refuses one the directory still routes to.
     *
     * <p>A segment index is a slot in a hash directory: every key whose hash lands on it is
     * written there, and a slot cannot be vacated - there is no operation that takes a segment
     * out of the directory, only ones that re-point a slot at a different segment. Purging such
     * a segment removes its catalog row and leaves the file and the writes behind. From that
     * moment {@link #getQueryableSegments()} skips the slot, because it has no metadata to
     * judge, so everything routed there stops being answerable; and the next {@link #start()}
     * rediscovers the file, finds no row naming it and adds one back as a live segment, so the
     * tombstone a rolling reindex placed is undone by a bounce and the superseded copy of every
     * document it retired answers queries again.</p>
     *
     * <p>Refusing leaves the segment TOMBSTONED. That is durable, it is not queryable, and it
     * means the same thing after a restart as before one - which is the whole point.</p>
     *
     * @param manager      The lifecycle manager that offered the segment
     * @param segmentIndex The segment offered for reclamation
     * @return true if the catalog row was removed
     */
    private boolean reclaim(SegmentLifecycleManager manager, long segmentIndex) {
        if (segments.get(segmentIndex) != null) {
            if (refusedReclamations.add(segmentIndex)) {
                LOGGER.log(Level.WARNING,
                    "Segment {0} is tombstoned but the routing directory still writes to it, so "
                    + "it is kept tombstoned rather than purged: a purged row is recreated as a "
                    + "live segment by the next start",
                    segmentIndex);
            }
            return false;
        }

        try {
            manager.purge(segmentIndex);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                "Failed to purge segment " + segmentIndex + "; it stays tombstoned", e);
            return false;
        }

        refusedReclamations.remove(segmentIndex);
        return true;
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
     * Reports whether a segment's index has absorbed everything written to the segment.
     *
     * <p>This is the evidence the {@link SegmentLifecycleManager} cannot gather for itself: the
     * catalog records how many entries a segment holds, not how much of its write log the HNSW
     * graph has taken in. The engine owns both the log and the integration checkpoint, so it
     * answers the question and the manager acts on the answer.</p>
     *
     * <p>A segment with no checkpoint is not caught up. It has no integration machinery
     * attached, so nothing will ever advance a checkpoint for it and nothing downstream is
     * registered to warm it either - advancing it on the absence of evidence would move the
     * stall one state along rather than clear it.</p>
     *
     * @param segmentIndex The segment index
     * @return true if the segment's index speaks for every entry it holds
     */
    public boolean isIndexingComplete(long segmentIndex) {
        Segment segment = segments.get(segmentIndex);
        if (segment == null) {
            return false;
        }
        io.excalibur.vector.hnsw.TailOffsetTracker tracker = segment.getTailOffsetTracker();
        if (tracker == null) {
            return false;
        }
        return IndexingProgress.isComplete(segment.entryCount(),
            tracker.getLastIntegratedOffset(), segment.getWriteLogHead());
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
     * <p><strong>Important:</strong> The segmentIndex is used as the directory key,
     * ensuring hash-based routing works correctly. Pre-allocation shares the single
     * creation path with lazy creation, so a pre-allocated segment gets its write log,
     * its catalog entry and its HNSW graph exactly like any other.</p>
     *
     * @param segmentIndex Index of the segment to pre-allocate
     */
    public void preAllocateSegment(int segmentIndex) {
        createSegment(segmentIndex);
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
     * <p>The directory may point several indices at the same segment, so every slot
     * holding {@code oldSegment} is repointed, not just {@code segmentIndex}.</p>
     *
     * @param segmentIndex The segment index
     * @param oldSegment The old segment being replaced (used for verification)
     * @param newSegment The new compacted segment
     * @return true if replacement was successful, false if segment index not found
     *         or old segment doesn't match
     */
    public boolean replaceSegment(long segmentIndex, Segment oldSegment, Segment newSegment) {
        // Guarded by the same monitor as every other directory mutation, so the
        // compare-and-set cannot interleave with a split publishing its children.
        synchronized (segmentLock) {
            Segment current = segments.get(segmentIndex);
            if (current == null) {
                LOGGER.log(Level.WARNING, "Cannot replace segment {0}: not found in registry", segmentIndex);
                return false;
            }
            if (current != oldSegment) {
                LOGGER.log(Level.WARNING, "Cannot replace segment {0}: old segment mismatch", segmentIndex);
                return false;
            }

            for (long slot : directorySlotsOf(oldSegment)) {
                segments.put(slot, newSegment);
            }

            Long canonical = canonicalIndex.remove(oldSegment);
            canonicalIndex.put(newSegment, canonical != null ? canonical : segmentIndex);
            publishRouting();

            LOGGER.log(Level.INFO, "Replaced segment {0}: {1} entries in new segment",
                new Object[] { segmentIndex, newSegment.entryCount() });
            return true;
        }
    }

    /**
     * Gets all distinct segments, keyed by canonical directory index.
     *
     * <p>Buddy pointers can make one segment reachable from several routing indices;
     * this map contains each segment exactly once so callers cannot query, register or
     * count a segment twice. Use {@link #getSegment(long)} to resolve a routed index.</p>
     *
     * @return Map of canonical segment index to segment instance
     */
    public Map<Long, Segment> getSegments() {
        return Map.copyOf(distinctSegments());
    }

    /**
     * Returns the number of segments in the engine.
     *
     * @return Segment count
     */
    public int getSegmentCount() {
        return routingSegmentCount;
    }

    /**
     * Returns total entries across all segments.
     *
     * @return Total entry count
     */
    public long getTotalEntryCount() {
        return distinctSegments().values().stream()
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
        for (Segment segment : distinctSegments().values()) {
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

        // The directory, before the segments go. Splits already persist it; this covers a
        // directory that was reconstructed at start and never split afterwards.
        persistRoutingDirectory();

        // Flush and close each distinct segment once. Segment.close() also closes the
        // write log it owns, so no descriptor survives this.
        for (Segment segment : distinctSegments().values()) {
            if (!segment.isClosed()) {
                segment.flush();
                segment.close();
            }
        }
        segments.clear();
        canonicalIndex.clear();
        publishRouting();

        // Flush and close catalog to persist metadata. A failed flush must not abort the rest of
        // this method: the mappings released below are still held by this process, and an arena
        // left open keeps them held for the life of the JVM. So record it and carry on - the
        // catalog's own close reports whether its metadata reached the device.
        try {
            catalog.flush();
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to flush the catalog while closing", e);
        }
        catalog.close();
        catalog.clear();

        // Release the mappings. Only safe once every segment and write log is closed,
        // and only for the Arena this engine created - a caller-supplied Arena stays
        // the caller's to close.
        if (ownsArena && arena != null) {
            try {
                arena.close();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to close storage arena", e);
            }
        }

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
     * <p>Installing the initializer before {@link #start()} is preferred, because
     * startup creates the whole base keyspace. When it is installed afterwards, every
     * segment that already exists and has no graph is back-filled here, so late wiring
     * cannot silently leave vector search disabled.</p>
     *
     * @param initializer The HNSW initializer, or null to disable
     */
    public void setHnswInitializer(HnswInitializer initializer) {
        this.hnswInitializer = initializer;
        LOGGER.info("HNSW initializer " + (initializer != null ? "configured" : "disabled"));

        if (initializer == null) {
            return;
        }

        int initialized = 0;
        for (Map.Entry<Long, Segment> entry : distinctSegments().entrySet()) {
            Segment segment = entry.getValue();
            if (segment.getHnswGraph() == null && !segment.isClosed()) {
                initializeHnswGraph(segment, entry.getKey());
                if (segment.getHnswGraph() != null) {
                    initialized++;
                }
            }
        }
        if (initialized > 0) {
            LOGGER.info("Back-filled HNSW graphs for " + initialized + " existing segment(s)");
        }
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
     * Sets the vector pre-filter attached to segments alongside their HNSW graphs.
     *
     * <p>The pre-filter is keyed by HNSW node id and is read by the query pipeline off the
     * segment, so it has to be attached wherever a graph is: created segments, segments recovered
     * at start-up, and split children. Leaving it to be created by the first write that reaches a
     * segment means a segment that is only read never has one - which after a restart is every
     * segment, however much persisted pre-filter state is sitting beside it on disk.</p>
     *
     * <p>As with {@link #setHnswInitializer(HnswInitializer)}, installing this after
     * {@link #start()} back-fills the segments that already exist, because the ingestion side
     * that owns the pre-filter is wired after the engine is running.</p>
     *
     * @param prefilter The vector pre-filter, or null to disable
     */
    public void setVectorPrefilter(VectorPrefilter prefilter) {
        this.vectorPrefilter = prefilter;
        LOGGER.info("Vector pre-filter " + (prefilter != null ? "configured" : "disabled"));

        if (prefilter == null) {
            return;
        }

        int initialized = 0;
        for (Map.Entry<Long, Segment> entry : distinctSegments().entrySet()) {
            Segment segment = entry.getValue();
            if (segment.getLshIndex() == null && !segment.isClosed()) {
                initializeVectorPrefilter(segment, entry.getKey());
                if (segment.getLshIndex() != null) {
                    initialized++;
                }
            }
        }
        if (initialized > 0) {
            LOGGER.info("Back-filled vector pre-filters for " + initialized + " existing segment(s)");
        }
    }

    /**
     * Returns the vector pre-filter.
     *
     * @return The vector pre-filter, or null if not configured
     */
    public VectorPrefilter getVectorPrefilter() {
        return vectorPrefilter;
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

        for (Map.Entry<Long, Segment> entry : distinctSegments().entrySet()) {
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

        for (Map.Entry<Long, Segment> entry : distinctSegments().entrySet()) {
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
     * Publishes a completed split into the routing directory.
     *
     * <p>Called by the {@link SplitWorker} once both children are built. This method
     * owns everything the worker cannot do, because only the directory knows the
     * children's indices:</p>
     * <ol>
     *   <li>Move each child from its temporary build name to the canonical
     *       {@code segment_<index>.dat} name that {@link SegmentDiscovery} matches,
     *       and give it a write log and an HNSW graph under that name</li>
     *   <li>Double the directory, installing buddy pointers so that no unsplit
     *       segment's keys are orphaned by the wider routing mask</li>
     *   <li>Install the children and widen {@code routingSegmentCount}</li>
     * </ol>
     *
     * <h3>Extensible Hashing</h3>
     * <p>The parent may be reachable from several directory indices (it has a smaller
     * local depth than the directory's global depth). Every one of those indices
     * {@code j} must end up pointing at the left child, and {@code j + oldCount} at the
     * right child, because {@code j + oldCount} is exactly the index whose split bit is
     * 1. Every other index {@code i} gets a buddy pointer at {@code i + oldCount} to the
     * same segment, so its keys keep resolving after the mask widens.</p>
     *
     * <h3>Publication Order</h3>
     * <p>The new upper half of the directory is filled first, then
     * {@code routingSegmentCount} is widened, and only then are the parent's own slots
     * repointed at the left child. At every instant a reader - using either the old or
     * the new mask - reaches a segment that holds its key.</p>
     *
     * <p>If publication fails the directory is left untouched and the exception
     * propagates, which makes the worker discard the children and return the parent to
     * service.</p>
     *
     * @param parent The parent segment that was split
     * @param leftChild The left child segment (entries whose split bit is 0)
     * @param rightChild The right child segment (entries whose split bit is 1)
     */
    /**
     * Observers notified after a split is published. Copy-on-write: splits are rare and
     * notification happens while readers may be registering, so a snapshot iterator is worth more
     * than write throughput here.
     */
    /**
     * Threshold new segments are created with, from {@code excalibur.shard.segment-load-factor}.
     * Defaults to {@link Segment#LOAD_FACTOR_THRESHOLD}.
     */
    private volatile double loadFactorThreshold = Segment.LOAD_FACTOR_THRESHOLD;

    private final java.util.List<SplitWorker.SplitListener> splitObservers =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    private void handleSplitComplete(Segment parent, Segment leftChild, Segment rightChild) {
        synchronized (segmentLock) {
            int oldRouting = routingSegmentCount;

            List<Long> parentSlots = directorySlotsOf(parent);
            if (parentSlots.isEmpty()) {
                throw new IllegalStateException(
                    "Parent segment not found in routing directory during split publication: "
                    + parent.path());
            }

            long leftIndex = parentSlots.get(0);
            long rightIndex = leftIndex + oldRouting;

            // Step 1: give the children discoverable identities before publishing them.
            // The right child goes first: its target name is unused, so a failure there
            // leaves everything untouched. The left child's target is the parent's own
            // file, so that move is done last - and even if the process dies between the
            // two, the parent file still holds every key both children were built from.
            prepareSplitChild(rightChild, rightIndex);

            // The left child is about to take over the parent's file names, the HNSW graph
            // file among them. Two graphs mapping one file is not survivable: the parent's
            // close() forces its own stale pages over whatever the child has written since.
            // Releasing the parent's graph here, before the child opens one, is what keeps the
            // retirement below from corrupting the child.
            io.excalibur.vector.hnsw.HnswGraph parentGraph = parent.detachHnswGraph();
            if (parentGraph != null) {
                try {
                    parentGraph.close();
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.WARNING,
                        "Failed to close the parent's HNSW graph during split of "
                            + parent.path(), e);
                }
            }

            prepareSplitChild(leftChild, leftIndex);

            // Step 2: fill the new upper half. These indices are unreachable under the
            // old mask, so this is invisible to concurrent readers.
            for (int i = 0; i < oldRouting; i++) {
                Segment current = segments.get((long) i);
                if (current == null) {
                    continue;
                }
                segments.put(i + (long) oldRouting, current == parent ? rightChild : current);
            }

            // Step 3: widen the mask. The parent still serves its own slots and holds
            // every key that maps to them, so this is safe to publish now.
            routingSegmentCount = oldRouting * 2;

            // Step 4: retire the parent from the lower half.
            for (long slot : parentSlots) {
                segments.put(slot, leftChild);
            }

            canonicalIndex.remove(parent);
            canonicalIndex.put(leftChild, leftIndex);
            canonicalIndex.put(rightChild, rightIndex);

            if (splitWorker != null) {
                splitWorker.unregisterSegment(parent);
            }

            // The left child inherits the parent's catalog entry (same index), which may
            // be in any lifecycle state, so it is re-pointed rather than re-added.
            catalog.registerFile(leftIndex, leftChild.path()).setEntryCount(leftChild.entryCount());
            catalog.add(rightIndex, rightChild.path()).setEntryCount(rightChild.entryCount());

            if (splitWorker != null && splitWorker.isRunning()) {
                splitWorker.registerSegment(leftChild);
                splitWorker.registerSegment(rightChild);
            }

            // Increment visibility epoch to signal state change
            visibilityTracker.advanceEpoch();

            publishRouting();

            LOGGER.info(String.format(
                "Split published: parent slots=%s -> left=%d, right=%d, routingCount=%d",
                parentSlots, leftIndex, rightIndex, routingSegmentCount));
        }

        // The directory just changed shape, and the shape is not recoverable from the segment
        // file names. Written before the observers run, so a crash during that work still leaves
        // a directory describing the split that is already durable.
        persistRoutingDirectory();

        // Outside the lock, and only after publication. Observers re-register index structures
        // against the children, which means opening graphs and filters -- work that must not run
        // while every routing decision in the engine is blocked behind segmentLock.
        notifySplitObservers(parent, leftChild, rightChild);

        // The routing table no longer references the parent, so nothing can newly resolve it and
        // it can be retired -- the same point compaction retires the segment it replaced. Until
        // this was here the parent was simply dropped: its descriptor, its mapping and its write
        // log stayed open for the life of the process, and because the left child's move
        // unlinked the parent's file, so did the disk blocks behind it. One of each, per split,
        // forever. Its graph was already released above.
        try {
            parent.close();
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING,
                "Failed to retire the parent segment after splitting " + parent.path(), e);
        }
    }

    /**
     * Tells observers a split has been published.
     *
     * <p>An observer that throws is logged and skipped rather than allowed to propagate. By this
     * point the split is already durable and the routing table already points at the children;
     * unwinding is not possible, and letting one observer's failure abort the notification would
     * leave the remaining observers permanently unaware of a split that did happen.
     */
    private void notifySplitObservers(Segment parent, Segment leftChild, Segment rightChild) {
        for (SplitWorker.SplitListener observer : splitObservers) {
            try {
                observer.onSplitComplete(parent, leftChild, rightChild);
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.SEVERE,
                    "Split observer failed for parent " + parent.path()
                        + "; the split stands and other observers still run", e);
            }
        }
    }

    /**
     * Registers an observer notified after each split is published.
     *
     * <p>Separate from the single {@link SplitWorker.SplitListener} the worker holds, which is this
     * engine itself and is responsible for actually publishing the children. Observers are told
     * afterwards so they can re-point whatever they key by {@link Segment} identity.
     *
     * <p>Without this the shard layer can only discover splits by polling, which means a window
     * where queries route to a child that has no index structures registered against it.
     */
    public void addSplitObserver(SplitWorker.SplitListener observer) {
        if (observer == null) {
            throw new IllegalArgumentException("observer must not be null");
        }
        splitObservers.add(observer);
    }

    /** Removes a previously registered split observer. */
    public boolean removeSplitObserver(SplitWorker.SplitListener observer) {
        return splitObservers.remove(observer);
    }

    /**
     * Returns every directory index pointing at a segment, smallest first.
     *
     * @param segment The segment to locate
     * @return Sorted directory indices, empty if the segment is not in the directory
     */
    private List<Long> directorySlotsOf(Segment segment) {
        List<Long> slots = new ArrayList<>();
        for (Map.Entry<Long, Segment> entry : segments.entrySet()) {
            if (entry.getValue() == segment) {
                slots.add(entry.getKey());
            }
        }
        Collections.sort(slots);
        return slots;
    }

    /**
     * Moves a split child into its canonical name and completes its initialization.
     *
     * <p>A child left under its temporary build name would be invisible to
     * {@link SegmentDiscovery} on the next start, so every write routed to it after the
     * split would be lost. The rename happens while the child stays mapped: the mapping
     * and descriptor follow the inode.</p>
     *
     * @param child        The child segment to publish
     * @param segmentIndex The directory index the child will occupy
     * @throws IllegalStateException if the child cannot be moved into place
     */
    private void prepareSplitChild(Segment child, long segmentIndex) {
        Path target = segmentPath(segmentIndex);
        Path source = child.path();
        if (!source.equals(target)) {
            try {
                java.nio.file.Files.move(source, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new IllegalStateException(
                    "Failed to move split child " + source + " to " + target, e);
            }
            child.renamedTo(target);
        }

        if (child.writeLog() == null) {
            try {
                // The parent's log is fully integrated into the children's files, so the
                // child starts from an empty log at its own canonical name.
                Path logPath = writeLogPath(segmentIndex);
                java.nio.file.Files.deleteIfExists(logPath);
                child.attachWriteLog(new WriteLog(logPath));
            } catch (IOException e) {
                throw new IllegalStateException(
                    "Failed to create write log for split child at index " + segmentIndex, e);
            }
        }

        discardInheritedGraph(child, segmentIndex);
        discardInheritedPrefilter(child, segmentIndex);
        initializeHnswGraph(child, segmentIndex);
        initializeVectorPrefilter(child, segmentIndex);
        alignCheckpointToLog(child, segmentIndex);
    }

    /**
     * Removes the graph file the child would otherwise inherit.
     *
     * <p>The left child takes the parent's canonical name, and the graph file is derived from
     * that name - so it opened the parent's graph. That graph describes the parent's contents:
     * it holds nodes for every entry that moved to the right child, and its header carries a
     * checkpoint into a write log that no longer exists. Half of the split's own point is that
     * the children hold different entries, so there is nothing in that graph worth keeping.</p>
     *
     * @param child The child being prepared
     * @param segmentIndex The child's canonical index
     */
    private void discardInheritedGraph(Segment child, long segmentIndex) {
        HnswInitializer initializer = this.hnswInitializer;
        if (initializer == null || child.getHnswGraph() != null) {
            return;
        }
        Path graphPath = initializer.computeGraphPath(child.path());
        try {
            java.nio.file.Files.deleteIfExists(graphPath);
            java.nio.file.Files.deleteIfExists(Path.of(graphPath.toString()
                + io.excalibur.vector.hnsw.HnswGraph.DOCUMENT_MAPPING_SUFFIX));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                "Could not remove the graph a split child would inherit at "
                    + graphPath + " (segment " + segmentIndex + ")", e);
        }
    }

    /**
     * Brings a child's integration checkpoint back within its own write log.
     *
     * <p>The checkpoint says how far the integration worker has read; the child's log starts
     * empty. A checkpoint left pointing into the parent's discarded log is ahead of the child's
     * head, and the integration worker returns early whenever the head is at or behind it - so
     * every vector written to the child afterwards is stored, found by key, and never enters the
     * graph, which is to say never returned by a vector search. Compaction guards against
     * exactly this; the split path did not.</p>
     *
     * @param child The child being prepared
     * @param segmentIndex The child's canonical index
     */
    private void alignCheckpointToLog(Segment child, long segmentIndex) {
        long head = child.getWriteLogHead();

        io.excalibur.vector.hnsw.HnswGraph graph = child.getHnswGraph();
        if (graph != null && graph.getLastIntegratedOffset() > head) {
            LOGGER.log(Level.INFO,
                "Rebasing the HNSW checkpoint of split child {0} from {1} to {2}",
                new Object[] { segmentIndex, graph.getLastIntegratedOffset(), head });
            graph.setLastIntegratedOffset(head);
        }

        io.excalibur.vector.hnsw.TailOffsetTracker tracker = child.getTailOffsetTracker();
        if (tracker != null && tracker.getLastIntegratedOffset() > head) {
            tracker.resetTo(head);
        }
    }

    /**
     * Rebuilds the immutable routing snapshot from the current directory.
     *
     * <p>Must be called with {@code segmentLock} held, or from a context where no other thread
     * can be changing the directory - start-up and construction.</p>
     */
    private void publishRouting() {
        int width = routingSegmentCount;
        Segment[] slots = new Segment[width];
        for (int slot = 0; slot < width; slot++) {
            slots[slot] = segments.get((long) slot);
        }
        this.routing = new Routing(width, slots);
    }

    /**
     * A directory and the mask that indexes it, published together.
     *
     * @param width The number of slots; always a power of two
     * @param slots The segment serving each slot, indexed by slot
     */
    private record Routing(int width, Segment[] slots) {

        /**
         * The segment a hash routes to, or null when the slot is empty.
         *
         * @param keyHash The key's hash
         * @return The segment, or null
         */
        Segment segmentFor(long keyHash) {
            if (width == 0) {
                return null;
            }
            return slots[(int) (keyHash & (width - 1))];
        }

        /**
         * The slot a hash routes to.
         *
         * @param keyHash The key's hash
         * @return The slot index, or -1 when the directory is empty
         */
        int slotFor(long keyHash) {
            return width == 0 ? -1 : (int) (keyHash & (width - 1));
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
