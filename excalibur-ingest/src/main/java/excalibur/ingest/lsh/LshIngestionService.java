package excalibur.ingest.lsh;

import excalibur.filter.lsh.LshConfig;
import excalibur.filter.lsh.LshIndex;
import io.excalibur.store.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The LSH implementation of a segment's {@link io.excalibur.store.VectorPrefilter}.
 *
 * <h2>Purpose</h2>
 * <p>Maintains one locality-sensitive hash index per segment, keyed by HNSW node id, which
 * stage 2 of the query pipeline uses to narrow a vector search before the graph is walked.</p>
 *
 * <h2>Integration Points</h2>
 * <ul>
 *   <li><strong>Storage engine:</strong> attaches and discards indexes over a segment's
 *       lifetime - creation, recovery at start-up, and split children - through
 *       {@link #initialize(Segment)} and {@link #discardPersistedState(Segment)}</li>
 *   <li><strong>HNSW integration worker:</strong> records each vector through
 *       {@link #indexVector(Segment, float[], long)} as it assigns the node id that keys it.
 *       Ingestion does not write here: it holds the vector before any node id exists</li>
 * </ul>
 *
 * <h2>Tombstones</h2>
 * <p>Deleting an entry does not touch the index. A tombstoned node stays a candidate, the graph
 * re-reads every candidate it returns and skips tombstoned ones, and the entry is reclaimed when
 * the segment is compacted. {@link #removeEntry(Segment, long)} exists for a caller that holds a
 * genuine node id; the deletion path does not.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe. Index creation is serialized on a single lock, and the
 * indexes themselves are internally striped.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li><strong>Vector dimensions:</strong> Must match the configured dimensions for the LSH index</li>
 *   <li><strong>Entry IDs:</strong> Non-negative HNSW node ids, in the graph's id space</li>
 *   <li><strong>Insertion order:</strong> LSH indices support incremental insertion</li>
 *   <li><strong>Error handling:</strong> Failures are logged and never fail the caller; the
 *       pre-filter is an optimisation, not a system of record</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public class LshIngestionService implements io.excalibur.store.VectorPrefilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LshIngestionService.class);

    /**
     * Default number of hash tables for LSH.
     */
    public static final int DEFAULT_NUM_HASH_TABLES = 15;

    /**
     * Default number of hash functions per table.
     */
    public static final int DEFAULT_NUM_HASH_FUNCTIONS = 16;

    /**
     * Default number of multi-probe candidates.
     */
    public static final int DEFAULT_NUM_PROBES = 3;

    private final int vectorDimensions;
    private final int numHashTables;
    private final int numHashFunctions;
    private final int numProbes;

    /**
     * Lock for LSH index initialization.
     */
    private final ReentrantLock initializationLock = new ReentrantLock();

    /**
     * Creates a new LshIngestionService with the specified vector dimensions.
     *
     * @param vectorDimensions The dimensionality of vectors to be indexed
     * @throws IllegalArgumentException if vectorDimensions is not positive
     */
    public LshIngestionService(int vectorDimensions) {
        this(vectorDimensions, DEFAULT_NUM_HASH_TABLES, DEFAULT_NUM_HASH_FUNCTIONS, DEFAULT_NUM_PROBES);
    }

    /**
     * Creates a new LshIngestionService with custom LSH parameters.
     *
     * @param vectorDimensions The dimensionality of vectors to be indexed
     * @param numHashTables Number of hash tables (more = higher recall)
     * @param numHashFunctions Number of hash functions per table
     * @param numProbes Number of multi-probe candidates
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public LshIngestionService(int vectorDimensions, int numHashTables,
                               int numHashFunctions, int numProbes) {
        if (vectorDimensions <= 0) {
            throw new IllegalArgumentException("vectorDimensions must be positive: " + vectorDimensions);
        }
        if (numHashTables <= 0) {
            throw new IllegalArgumentException("numHashTables must be positive: " + numHashTables);
        }
        if (numHashFunctions <= 0) {
            throw new IllegalArgumentException("numHashFunctions must be positive: " + numHashFunctions);
        }
        if (numProbes <= 0) {
            throw new IllegalArgumentException("numProbes must be positive: " + numProbes);
        }

        this.vectorDimensions = vectorDimensions;
        this.numHashTables = numHashTables;
        this.numHashFunctions = numHashFunctions;
        this.numProbes = numProbes;

        LOGGER.info("LshIngestionService initialized: dimensions={}, tables={}, functions={}, probes={}",
            vectorDimensions, numHashTables, numHashFunctions, numProbes);
    }

    /**
     * Initializes an LSH index for a segment.
     *
     * <p>The index is backed by files in a directory beside the segment's data file, so the
     * buckets built during ingestion are still there after a restart. An in-memory index would be
     * backed by {@code deleteOnExit} temporary files, which makes every restart silently start
     * from an empty pre-filter while the query pipeline still believes it has one.</p>
     *
     * <p>The seed is derived from the segment id rather than left to the {@link LshConfig}
     * default of {@code System.nanoTime()}, so that segments opened in the same instant do not
     * share hyperplanes. It is not what makes the persisted buckets readable after a restart -
     * the segment id counts segment instances and differs from one run to the next. Each table
     * stores the seed it was built with and rebuilds its hash function from that, which is what
     * survives; the configured seed only ever applies to a table being created.</p>
     *
     * <p>If the segment already has an LSH index, this method is a no-op.</p>
     *
     * @param segment The segment to initialize with an LSH index
     * @throws NullPointerException if segment is null
     */
    public void initializeLshIndex(Segment segment) {
        Objects.requireNonNull(segment, "segment must not be null");

        // Check if already initialized
        if (segment.lshIndex() != null) {
            LOGGER.trace("Segment {} already has LSH index", segment.segmentId());
            return;
        }

        initializationLock.lock();
        try {
            // Double-check after acquiring lock
            if (segment.lshIndex() != null) {
                return;
            }

            LshConfig config = configForSegment(segment);
            Path directory = lshDirectory(segment);

            LshIndex lshIndex;
            if (directory != null) {
                lshIndex = LshIndex.open(config, directory);
            } else {
                // A segment with no backing path (in-memory fixtures) has nowhere to persist to.
                lshIndex = new LshIndex(config);
            }
            segment.attachLshIndex(lshIndex);

            LOGGER.debug("Initialized LSH index for segment {} with dimensions {} at {}",
                segment.segmentId(), vectorDimensions, directory);
        } catch (IOException e) {
            LOGGER.warn("Failed to open persistent LSH index for segment {}: {}",
                segment.segmentId(), e.getMessage());
        } finally {
            initializationLock.unlock();
        }
    }

    /**
     * Builds the LSH configuration for one segment, seeded from its id so the hyperplanes are
     * reproducible across restarts.
     */
    private LshConfig configForSegment(Segment segment) {
        return LshConfig.builder(vectorDimensions)
            .numHashTables(numHashTables)
            .numHashFunctions(numHashFunctions)
            .numProbes(numProbes)
            .seed(segment.segmentId())
            .build();
    }

    /**
     * Resolves the directory holding a segment's LSH tables: a sibling of the segment data file
     * named after it, so the two are moved, compacted and removed together.
     *
     * @return The directory, or null if the segment has no backing file
     */
    private static Path lshDirectory(Segment segment) {
        Path dataPath = segment.path();
        if (dataPath == null) {
            return null;
        }
        Path parent = dataPath.getParent();
        String name = dataPath.getFileName().toString();
        int extension = name.lastIndexOf('.');
        String base = extension > 0 ? name.substring(0, extension) : name;
        Path directory = Path.of(base + ".lsh");
        return parent != null ? parent.resolve(directory) : directory;
    }

    /**
     * Forces a segment's LSH index to its backing files.
     *
     * <p>A no-op for a segment with no index, or one backed by temporary files.</p>
     *
     * @param segment The segment whose index should be flushed
     * @throws NullPointerException if segment is null
     */
    public void flush(Segment segment) {
        Objects.requireNonNull(segment, "segment must not be null");

        LshIndex lshIndex = segment.lshIndex();
        if (lshIndex == null || lshIndex.isClosed()) {
            return;
        }
        try {
            lshIndex.flush();
        } catch (Exception e) {
            LOGGER.warn("Failed to flush LSH index for segment {}: {}",
                segment.segmentId(), e.getMessage());
        }
    }

    /**
     * Inserts a vector into a segment's LSH index.
     *
     * <p>This method inserts the vector with the specified entry ID into the
     * segment's LSH index. If the segment doesn't have an LSH index, it will
     * be initialized automatically.</p>
     *
     * <h3>Error Handling</h3>
     * <p>If the insertion fails (e.g., dimension mismatch), the error is logged
     * but the method returns normally. This ensures that LSH failures don't
     * block the ingestion pipeline.</p>
     *
     * @param segment The segment containing the LSH index
     * @param vector The vector to insert
     * @param entryId The unique entry ID for this vector
     * @return true if insertion succeeded, false otherwise
     * @throws NullPointerException if segment or vector is null
     * @throws IllegalArgumentException if entryId is negative
     */
    public boolean insertVector(Segment segment, float[] vector, long entryId) {
        Objects.requireNonNull(segment, "segment must not be null");
        Objects.requireNonNull(vector, "vector must not be null");

        if (entryId < 0) {
            throw new IllegalArgumentException("entryId must be non-negative: " + entryId);
        }

        try {
            // Ensure LSH index is initialized
            if (segment.lshIndex() == null) {
                initializeLshIndex(segment);
            }

            LshIndex lshIndex = segment.lshIndex();
            if (lshIndex == null) {
                LOGGER.warn("Failed to initialize LSH index for segment {}", segment.segmentId());
                return false;
            }

            // Validate dimensions
            if (vector.length != lshIndex.dimensions()) {
                LOGGER.warn("Vector dimension mismatch for segment {}: expected {}, got {}",
                    segment.segmentId(), lshIndex.dimensions(), vector.length);
                return false;
            }

            // Insert into LSH index
            lshIndex.insert(vector, entryId);

            LOGGER.trace("Inserted vector into LSH index for segment {}: entryId={}",
                segment.segmentId(), entryId);

            return true;

        } catch (Exception e) {
            LOGGER.warn("Failed to insert vector into LSH index for segment {}: {}",
                segment.segmentId(), e.getMessage());
            return false;
        }
    }

    /**
     * Attaches a pre-filter to a segment, re-opening whatever is persisted beside it.
     *
     * <p>The {@link io.excalibur.store.VectorPrefilter} entry point the storage engine calls for
     * every segment it makes queryable, so the pre-filter has the same lifecycle as the graph it
     * is keyed against.</p>
     *
     * @param segment The segment to attach a pre-filter to
     */
    @Override
    public void initialize(Segment segment) {
        initializeLshIndex(segment);
    }

    /**
     * Deletes the LSH tables persisted under a segment's current name.
     *
     * <p>Called by the storage engine on a split child before its own pre-filter is opened. The
     * files are unlinked rather than truncated, so an index another segment still has mapped -
     * the retiring parent's - keeps serving from its inode until that segment closes it.</p>
     *
     * @param segment The segment whose persisted tables must not be inherited
     */
    @Override
    public void discardPersistedState(Segment segment) {
        Objects.requireNonNull(segment, "segment must not be null");

        Path directory = lshDirectory(segment);
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(directory);
            LOGGER.debug("Discarded inherited LSH tables at {}", directory);
        } catch (IOException e) {
            LOGGER.warn("Failed to discard inherited LSH tables at {}: {}",
                directory, e.getMessage());
        }
    }

    /**
     * Records an integrated vector under the HNSW node id the graph assigned it.
     *
     * <p>This is the {@link io.excalibur.store.VectorPrefilter} entry point, called by the HNSW
     * integration worker once a node id exists. It is the only caller that can key the index the
     * way the query pipeline reads it.</p>
     *
     * @param segment The segment the vector was integrated into
     * @param vector  The vector as integrated
     * @param nodeId  The HNSW node id assigned to it
     */
    @Override
    public void indexVector(Segment segment, float[] vector, long nodeId) {
        insertVector(segment, vector, nodeId);
    }

    /**
     * Removes an entry from a segment's LSH index.
     *
     * <p>This method removes the entry with the specified ID from the segment's
     * LSH index. Used for tombstone propagation when entries are deleted.</p>
     *
     * @param segment The segment containing the LSH index
     * @param entryId The entry ID to remove
     * @return true if the entry was found and removed, false otherwise
     * @throws NullPointerException if segment is null
     * @throws IllegalArgumentException if entryId is negative
     */
    public boolean removeEntry(Segment segment, long entryId) {
        Objects.requireNonNull(segment, "segment must not be null");

        if (entryId < 0) {
            throw new IllegalArgumentException("entryId must be non-negative: " + entryId);
        }

        try {
            LshIndex lshIndex = segment.lshIndex();
            if (lshIndex == null) {
                LOGGER.trace("Segment {} has no LSH index, nothing to remove", segment.segmentId());
                return false;
            }

            boolean removed = lshIndex.delete(entryId);

            if (removed) {
                LOGGER.trace("Removed entry {} from LSH index for segment {}", entryId, segment.segmentId());
            }

            return removed;

        } catch (Exception e) {
            LOGGER.warn("Failed to remove entry {} from LSH index for segment {}: {}",
                entryId, segment.segmentId(), e.getMessage());
            return false;
        }
    }

    /**
     * Returns the configured vector dimensions.
     */
    public int getVectorDimensions() {
        return vectorDimensions;
    }

    /**
     * Returns the number of hash tables.
     */
    public int getNumHashTables() {
        return numHashTables;
    }

    /**
     * Returns the number of hash functions per table.
     */
    public int getNumHashFunctions() {
        return numHashFunctions;
    }

    /**
     * Returns the number of multi-probe candidates.
     */
    public int getNumProbes() {
        return numProbes;
    }

    /**
     * Returns the LSH configuration for this service.
     *
     * @return A new LshConfig instance with the configured parameters
     */
    public LshConfig getLshConfig() {
        return LshConfig.builder(vectorDimensions)
            .numHashTables(numHashTables)
            .numHashFunctions(numHashFunctions)
            .numProbes(numProbes)
            .build();
    }

    @Override
    public String toString() {
        return String.format("LshIngestionService[dimensions=%d, tables=%d, functions=%d, probes=%d]",
            vectorDimensions, numHashTables, numHashFunctions, numProbes);
    }
}
