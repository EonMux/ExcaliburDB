package excalibur.ingest.lsh;

import excalibur.filter.lsh.LshConfig;
import excalibur.filter.lsh.LshIndex;
import io.excalibur.store.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages LSH index population during document ingestion.
 *
 * <h2>Purpose</h2>
 * <p>This service is responsible for inserting vectors into segment LSH indices
 * during the ingestion pipeline. It ensures that every vector written to a segment
 * is also indexed in the segment's LSH index for efficient pre-filtering during queries.</p>
 *
 * <h2>Integration Points</h2>
 * <ul>
 *   <li><strong>Ingestion Pipeline:</strong> After vectors are written to segments,
 *       this service is called to insert them into the LSH index</li>
 *   <li><strong>Segment Creation:</strong> When a new segment is created, this service
 *       initializes its LSH index with the configured dimensions</li>
 *   <li><strong>Deletion Propagation:</strong> When entries are tombstoned, this service
 *       removes them from the LSH index</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe. LSH insertions are protected by per-segment locks
 * to allow concurrent ingestion across different segments while maintaining consistency
 * within each segment.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li><strong>Vector dimensions:</strong> Must match the configured dimensions for the LSH index</li>
 *   <li><strong>Entry IDs:</strong> Non-negative long values representing unique entry identifiers</li>
 *   <li><strong>Insertion order:</strong> LSH indices support incremental insertion</li>
 *   <li><strong>Error handling:</strong> Failed insertions are logged but don't fail the ingestion</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public class LshIngestionService {

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
     * default of {@code System.nanoTime()}: a per-process random seed produces different
     * hyperplanes on every start, which would make the persisted buckets meaningless.</p>
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
