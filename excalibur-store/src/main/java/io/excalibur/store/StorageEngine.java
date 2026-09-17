package io.excalibur.store;

import io.excalibur.core.memory.MemorySegmentFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * point at the same {@link Segment}, and the <em>canonical index</em> of a segment - the
 * smallest index pointing at it - is the one that names its file and its catalog entry.
 * Enumerations ({@link #getSegments()}, {@link #getQueryableSegments()},
 * {@link #getTotalEntryCount()}) are therefore de-duplicated by segment identity.
 * {@link EngineRouting} owns that directory and carries the rules it has to obey.</p>
 *
 * <h2>How this class is put together</h2>
 * <p>This is the published surface; the work behind it is divided by seam, and each
 * collaborator's own documentation carries the reasoning for its part:</p>
 * <ul>
 *   <li>{@link EngineRouting} - the directory: rebuilding, publishing, persisting it</li>
 *   <li>{@link RoutingSnapshot} - the width and the slots, published as one value</li>
 *   <li>{@link EngineLifecycle} - {@link #start()}, {@link #close()}, and the volume set</li>
 *   <li>{@link SegmentRegistry} - the one path a segment is created and reclaimed through</li>
 *   <li>{@link SegmentIndexAttachment} - attaching and discarding vector index structures</li>
 *   <li>{@link EngineWrites} - the routed read, write and delete paths</li>
 *   <li>{@link SplitPublication} - turning two built children into two routed segments</li>
 * </ul>
 *
 * <p>Shared mutable state stays here, package-private, and is written through directly by
 * those collaborators. That is deliberate: {@code this.x = v} becoming {@code engine.x = v}
 * is the same memory operation, whereas routing it through an accessor is a second chance
 * to lose a volatile read or take the wrong lock.</p>
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

    static final Logger LOGGER = Logger.getLogger(StorageEngine.class.getName());

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
     * Path to the data directory containing the catalog and the routing directory.
     *
     * <p>Segment files are named "segment_&lt;index&gt;.dat" so that placement follows the
     * segment index rather than a random unique ID. Which directory holds each of them is
     * {@link #placement}'s answer, and its answer is this directory for every segment until a
     * deployment names volumes to spread them over.</p>
     */
    final Path dataDir;

    /**
     * Which directory each segment's files live in, and everywhere a restart has to look.
     *
     * <p>Defaults to the data directory alone, which is what a store spanning one disk wants
     * and what every path in this engine resolved to before placement existed. Replaced only
     * by {@link #withSegmentPlacement(SegmentPlacement)}, and only before {@link #start()}.</p>
     */
    volatile SegmentPlacement placement;

    /**
     * Where each segment index's files actually are, which is not always where the placement
     * would put them: the volume set is configuration, and it can change between runs.
     */
    final SegmentLocations locations = new SegmentLocations();

    /**
     * Arena for memory allocation.
     *
     * <p>The Arena controls the lifecycle of the MemorySegment backing each segment.
     * This Arena must outlive the StorageEngine for proper resource cleanup.</p>
     *
     * <p>May be null when using the fallback ByteBuffer-based implementation.</p>
     */
    final Arena arena;

    /**
     * Flag indicating whether Panama FFM is being used.
     *
     * <p>When true, the engine uses native Panama FFM MemorySegments.
     * When false, the engine uses the ByteBuffer-based fallback implementation.</p>
     */
    final boolean usingPanama;

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
    final Map<Long, Segment> segments;

    /**
     * Effective segment count for hash-based routing.
     * MUST always be a power of 2. This is separate from segments.size() because
     * splits can temporarily make the map size non-power-of-2. Routing always
     * uses this value: segmentIndex = keyHash & (routingSegmentCount - 1).
     */
    volatile int routingSegmentCount;

    /**
     * The routing directory and the mask that indexes it, as one value.
     *
     * <p>Read once per operation, so the width a key is masked with and the slot it lands in
     * always come from the same moment. Reading them separately let an operation mask with the
     * pre-split width and then look up a slot the split had already re-pointed, which sends a
     * key whose split bit is 1 to the left child - a lookup that answers "absent" for a key that
     * exists, and a write that lands in the wrong half.</p>
     *
     * <p>Rebuilt by {@link EngineRouting#publishRouting()} after every change to the directory, under the
     * same lock that made the change.</p>
     */
    volatile RoutingSnapshot routing = new RoutingSnapshot(0, new Segment[0]);

    /**
     * Whether this engine created {@link #arena} and must therefore close it.
     *
     * <p>The single-argument constructor creates its own shared Arena; the Arena-taking
     * constructors borrow one owned by the caller and must not close it.</p>
     */
    final boolean ownsArena;

    /**
     * Canonical directory index of each live segment.
     *
     * <p>Keyed by identity (Segment does not override equals/hashCode). Buddy pointers
     * mean a segment can be reachable from several directory indices; this map records
     * the one that names its file and its catalog entry, so catalog updates never
     * create a second entry for a segment that already has one.</p>
     */
    final Map<Segment, Long> canonicalIndex = new ConcurrentHashMap<>();

    /**
     * Lock object for synchronizing segment creation and directory updates.
     */
    final Object segmentLock = new Object();

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
    final SegmentCatalog catalog;

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
    volatile VisibilityTracker visibilityTracker = new VisibilityTracker.Default();

    /**
     * Statistics for monitoring and diagnostics.
     */
    final Statistics statistics = new Statistics();

    /**
     * Flag indicating whether the engine has been started.
     */
    volatile boolean started = false;

    /**
     * HNSW initializer for creating HNSW graphs for segments.
     * May be null if HNSW initialization is disabled.
     */
    volatile HnswInitializer hnswInitializer;

    /**
     * Vector pre-filter attached to every queryable segment alongside its HNSW graph.
     * May be null if no pre-filter is configured.
     */
    volatile VectorPrefilter vectorPrefilter;

    /**
     * Threshold new segments are created with, from {@code excalibur.shard.segment-load-factor}.
     * Defaults to {@link Segment#LOAD_FACTOR_THRESHOLD}.
     */
    volatile double loadFactorThreshold = Segment.LOAD_FACTOR_THRESHOLD;

    /**
     * The extensible-hashing directory: how it is rebuilt, published, persisted and read back.
     */
    final EngineRouting routingTable = new EngineRouting(this);

    /** The single path a segment is created, registered and reclaimed through. */
    final SegmentRegistry registry = new SegmentRegistry(this);

    /** Where a segment's HNSW graph and vector pre-filter are attached and discarded. */
    final SegmentIndexAttachment indexes = new SegmentIndexAttachment(this);

    /** The routed read, write and delete paths. */
    final EngineWrites writes = new EngineWrites(this);

    /** Publication of a completed split into the routing directory. */
    final SplitPublication splits = new SplitPublication(this);

    /** Bringing the engine up, and taking it down. */
    final EngineLifecycle lifecycle = new EngineLifecycle(this);

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
    volatile SplitWorker splitWorker;

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
        this.placement = SegmentPlacement.singleDirectory(dataDir);
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
        routingTable.publishRouting();

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
        this.placement = SegmentPlacement.singleDirectory(dataDir);
        this.arena = arena;
        this.ownsArena = false;
        this.usingPanama = true;
        this.catalog = new SegmentCatalog(dataDir.resolve("catalog.dat"), arena);
        this.segments = new ConcurrentHashMap<>(DEFAULT_INITIAL_SEGMENTS);
        this.routingSegmentCount = DEFAULT_INITIAL_SEGMENTS;
        routingTable.publishRouting();

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
            splits::handleSplitComplete,
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
        lifecycle.start();
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
        return writes.putReportingSegment(key, value);
    }

    /**
     * Returns the canonical file path for a segment index.
     *
     * <p>Every segment file this engine creates, renames onto or looks for goes through here,
     * so the location decision is made in exactly one place. For a segment that already has a
     * file, that decision is the file: see {@link SegmentLocations} for why a placement cannot
     * be trusted to name it once the volume set has changed.</p>
     *
     * @param segmentIndex The directory index
     * @return The {@code segment_<index>.dat} path, in the directory the segment occupies
     * @throws IllegalStateException if the index is new and another volume already holds a
     *                               file under that name
     */
    Path segmentPath(long segmentIndex) {
        return locations.segmentFile(segmentIndex, placement);
    }

    /**
     * Returns the canonical write log path for a segment index.
     *
     * <p>Beside the segment file, wherever that turned out to be - a log on a different volume
     * from the segment it logs is lost to the segment the moment either volume moves.</p>
     *
     * @param segmentIndex The directory index
     * @return The {@code segment_<index>.wal} path beside the segment's own file
     */
    Path writeLogPath(long segmentIndex) {
        return walPathFor(segmentPath(segmentIndex));
    }

    /**
     * Returns the write log path that belongs beside a segment file.
     *
     * <p>Derived from the file rather than from the index, so a segment that ended up
     * somewhere other than its placement directory still keeps its log beside it.</p>
     *
     * @param segmentFile The segment file
     * @return The {@code segment_<index>.wal} path in the same directory
     */
    static Path walPathFor(Path segmentFile) {
        String name = segmentFile.getFileName().toString();
        String base = name.endsWith(".dat") ? name.substring(0, name.length() - 4) : name;
        return segmentFile.resolveSibling(base + ".wal");
    }

    /**
     * Returns the placement deciding where this engine writes its segments.
     *
     * @return The placement, never null
     */
    public SegmentPlacement segmentPlacement() {
        return placement;
    }

    /**
     * Spreads this engine's segments over more than one directory.
     *
     * <p>Must be called before {@link #start()}. Start is where the engine discovers what is
     * already on disk and creates whatever the base keyspace is missing, and both of those
     * answers depend on the placement: installing one afterwards would mean a restart had
     * already scanned the wrong set of directories and created empty files over the segments it
     * did not find.</p>
     *
     * <p>This module has no configuration dependency; the volume set comes from
     * {@code excalibur.shard.volumes} via whoever constructs the engine.</p>
     *
     * @param placement Where each segment belongs, and every directory a restart must scan
     * @return this engine, for chaining
     * @throws NullPointerException     if placement is null
     * @throws IllegalStateException    if the engine has already started
     * @throws IllegalArgumentException if the placement omits the data directory or names a
     *                                  directory that is not there
     */
    public StorageEngine withSegmentPlacement(SegmentPlacement placement) {
        return lifecycle.installPlacement(placement);
    }

    /**
     * Returns how many segments are serving without a vector index.
     *
     * <p>Zero is the only healthy value. Anything above it means a vector search over this
     * engine is answering from part of its keyspace: the result is not an error and not empty,
     * it is short, which is why this is worth alerting on rather than only logging. See
     * {@link SegmentIndexAttachment} for why such a segment is kept in service.</p>
     *
     * @return Number of segments whose HNSW graph could not be attached
     */
    public int segmentsWithoutVectorIndexCount() {
        return indexes.failureCount();
    }

    /**
     * Returns each segment serving without a vector index, and why.
     *
     * <p>Keyed by directory index, valued by the failure that kept the graph from attaching -
     * a dimension mismatch and an unreadable file need different answers from an operator, so
     * the count alone is not enough to act on.</p>
     *
     * @return An immutable snapshot, empty when every segment has its graph
     */
    public Map<Long, String> segmentsWithoutVectorIndex() {
        return indexes.failures();
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
        return writes.get(key);
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
        return writes.getWithEpoch(key);
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
        return writes.delete(key);
    }

    // ==================== Segment Lifecycle Management ====================

    /**
     * Segment lifecycle manager for background state transitions.
     *
     * <p>The lifecycle manager handles automatic state transitions based on
     * configurable criteria like tombstone thresholds for compaction and
     * epoch lag for reclamation.</p>
     */
    volatile SegmentLifecycleManager lifecycleManager;

    /**
     * Gets or creates the lifecycle manager for this engine.
     *
     * <p>The lifecycle manager is created lazily on first access.</p>
     *
     * @return The lifecycle manager
     */
    public SegmentLifecycleManager getLifecycleManager() {
        return registry.lifecycleManager();
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
        int entryState = EntryStateMapping.toEntryState(newState);
        if (entryState >= 0) {
            metadata.setEntryState(entryState);
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
        registry.createSegment(segmentIndex);
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
        return routingTable.replaceSegment(segmentIndex, oldSegment, newSegment);
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
        return Map.copyOf(routingTable.distinctSegments());
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
        return routingTable.distinctSegments().values().stream()
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
        for (Segment segment : routingTable.distinctSegments().values()) {
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
        lifecycle.close();
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
        indexes.setHnswInitializer(initializer);
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
        indexes.setVectorPrefilter(prefilter);
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

        for (Map.Entry<Long, Segment> entry : routingTable.distinctSegments().entrySet()) {
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

        for (Map.Entry<Long, Segment> entry : routingTable.distinctSegments().entrySet()) {
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
        splits.addObserver(observer);
    }

    /** Removes a previously registered split observer. */
    public boolean removeSplitObserver(SplitWorker.SplitListener observer) {
        return splits.removeObserver(observer);
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
