package io.excalibur.vector.hnsw;

import io.excalibur.core.mapped.MappedFile;
import io.excalibur.core.memory.Segment;
import io.excalibur.vector.distance.DistanceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The fixed 4 KiB header at the front of an HNSW graph file, and the rules for believing it.
 *
 * <h2>Why it is its own class</h2>
 * <p>Every offset below is a byte position in a file that outlives the process, so a change to
 * one of them is a format change whether or not anyone meant it that way. Holding them in one
 * place - beside the version table that says which of them a given file even has, and beside the
 * checks that decide whether a header may be believed - means the layout can be read as a whole
 * and cannot drift field by field across the call sites that happen to touch it.</p>
 *
 * <h2>Layout</h2>
 * <pre>
 *   offset  0  magic          (8 bytes)
 *   offset  8  version        (4 bytes)
 *   offset 12  dimension      (4 bytes)
 *   offset 16  nodeCount      (8 bytes)
 *   offset 24  entryPoint     (8 bytes)
 *   offset 32  maxLevel       (4 bytes)
 *   offset 36  lastIntegrated (8 bytes)
 *   offset 44  maxConnections (4 bytes)
 *   offset 48  maxConnections at layer 0 (4 bytes)
 *   offset 52  efConstruction (4 bytes)
 *   offset 56  mL             (8 bytes, double bits)
 *   offset 64  efSearch       (4 bytes)
 *   offset 68  maxNodes       (8 bytes)
 *   offset 76  distance metric code (4 bytes)
 * </pre>
 * <p>The integrity block that follows at offset 88 is described on {@link HnswGraphIntegrity};
 * the node index table and node records that follow the header are described on
 * {@link HnswNodeRecords}.</p>
 *
 * <h2>Byte order</h2>
 * <p>All persistent I/O goes through {@link MappedFile}, which uses {@code BIG_ENDIAN} (see
 * {@code MappedFile.LONG_LAYOUT} / {@code INT_LAYOUT} / {@code FLOAT_LAYOUT}). Direct
 * {@code ValueLayout} reads and writes must not be introduced here - they would silently
 * diverge from the on-disk byte order.</p>
 *
 * <h2>Reading a header is a decision, not an access</h2>
 * <p>A header names the dimension, the connection limits and the capacity that fix where every
 * node record begins. A damaged or foreign header therefore does not produce a wrong field, it
 * produces a wrong reading of the whole file, which is why the checks here refuse rather than
 * warn and why they run before any layout field is used.</p>
 */
final class HnswGraphHeader {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswGraphHeader.class);

    /** Magic number for file format validation ("HNSWGRPH" truncated). */
    static final long MAGIC = 0x484E535747525048L;

    /**
     * Current file format version. Version 3 added the integrity block described on
     * {@link HnswGraphIntegrity}; a graph written at an earlier version carries no checksum and
     * is read, and left, at the version it was written with.
     */
    static final int VERSION = 3;

    /** The version written before the header carried checksums. */
    static final int VERSION_WITHOUT_INTEGRITY = 2;

    /**
     * The version written before the header recorded a distance metric. Cosine was the only
     * metric a graph could be built under at the time, so a file at this version is read as
     * cosine and is left at this version rather than being rewritten.
     */
    static final int VERSION_WITHOUT_METRIC = 1;

    /** Header size (4 KiB aligned). */
    static final int HEADER_SIZE = 4096;

    /** Offset of the magic number. */
    static final long OFFSET_MAGIC = 0;
    /** Offset of the format version. */
    static final long OFFSET_VERSION = 8;
    /** Offset of the vector dimension. */
    static final long OFFSET_DIMENSION = 12;
    /** Offset of the node count. */
    static final long OFFSET_NODE_COUNT = 16;
    /** Offset of the entry point node id. */
    static final long OFFSET_ENTRY_POINT = 24;
    /** Offset of the highest occupied layer. */
    static final long OFFSET_MAX_LEVEL = 32;
    /** Offset of the write-log integration checkpoint. */
    static final long OFFSET_LAST_INTEGRATED = 36;
    /** Offset of the per-layer connection limit. */
    static final long OFFSET_CONFIG_MAX_CONN = 44;
    /** Offset of the layer-0 connection limit. */
    static final long OFFSET_CONFIG_MAX_CONN_0 = 48;
    /** Offset of the construction beam width. */
    static final long OFFSET_CONFIG_EF_CONSTRUCTION = 52;
    /** Offset of the level-assignment constant, stored as double bits. */
    static final long OFFSET_CONFIG_ML = 56;
    /** Offset of the search beam width. */
    static final long OFFSET_CONFIG_EF_SEARCH = 64;
    /** Offset of the node capacity, which fixes where the data region starts. */
    static final long OFFSET_MAX_NODES = 68;
    /** Offset of the distance metric code. {@link #OFFSET_MAX_NODES} holds a long, so this is 76. */
    static final long OFFSET_METRIC = 76;

    /** How much of a file {@link #requireEntirelyBlank} reads at a time. */
    private static final int BLANK_SCAN_CHUNK = 64 * 1024;

    private HnswGraphHeader() {
    }

    /**
     * Layout and configuration recovered from an existing graph file header.
     *
     * @param config The layout and tunables the file was written with
     * @param maxNodes The capacity the file was created for
     * @param distanceMetric The canonical name of the metric the graph's edges were chosen under
     * @param version The format version the file is at
     */
    record PersistedLayout(HnswConfig config, long maxNodes, String distanceMetric, int version) {}

    /**
     * Reports whether this build can interpret a header at the given version.
     *
     * @param version The version read from a header
     * @return true if the field offsets above describe that version
     */
    static boolean isSupportedVersion(int version) {
        return version == VERSION || version == VERSION_WITHOUT_INTEGRITY
            || version == VERSION_WITHOUT_METRIC;
    }

    /**
     * Returns the metric a persisted header describes.
     *
     * @param version The header's format version, already known to be supported
     * @param metricCode The value at {@link #OFFSET_METRIC}, ignored for a header
     *                   written before the field existed
     * @param path The graph file, for the error message
     * @return The canonical metric name the graph was built with
     * @throws IOException if the header names a metric this build cannot provide
     */
    static String persistedMetric(int version, int metricCode, Path path) throws IOException {
        if (version == VERSION_WITHOUT_METRIC) {
            return PersistedMetric.LEGACY_METRIC;
        }
        try {
            return PersistedMetric.nameFor(metricCode);
        } catch (IllegalArgumentException e) {
            throw new IOException("HNSW graph " + path + " records a distance metric this build "
                + "does not know (header code " + metricCode + "): " + e.getMessage(), e);
        }
    }

    /**
     * Refuses a graph whose edges were built under a metric other than the requested one.
     *
     * <p>Every edge in the graph was chosen because it was the closest candidate under
     * the metric in force when its node was inserted. Under a different metric those
     * edges are no longer monotone, so greedy descent walks away from the true
     * neighbours and the search returns a plausible but wrong answer, with the scores
     * clamped into the new metric's range on the way out. Nothing about the result
     * shows that it happened, which is why this is an error and not a warning.</p>
     *
     * @param persistedMetric The metric recorded in the header
     * @param distanceFunction The metric the caller is configured for
     * @param path The graph file, for the error message
     * @throws IOException if the two metrics differ
     */
    static void requireMatchingMetric(String persistedMetric,
                                      DistanceFunction distanceFunction,
                                      Path path) throws IOException {
        String requested = distanceFunction.name();
        if (!persistedMetric.equals(requested)) {
            throw new IOException("HNSW graph " + path + " was built with the distance metric '"
                + persistedMetric + "' but this node is configured for '" + requested
                + "'. A graph's edges only rank correctly under the metric that chose them, "
                + "so the corpus has to be reindexed to change it.");
        }
    }

    /**
     * Reads the layout of an existing graph file without mapping it read-write.
     *
     * @param path The graph file path
     * @return The persisted layout, or null if the file does not exist or has
     *         never been initialized (all-zero header)
     * @throws IOException if the file exists and holds a header that cannot be used
     */
    static PersistedLayout readPersistedLayout(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) < HEADER_SIZE) {
            return null;
        }

        try (MappedFile probe = MappedFile.open(path)) {
            long magic = probe.readLong(OFFSET_MAGIC);
            if (magic == 0L) {
                // A file that is zero all the way through is a create that never reached its
                // first header and has lost nothing, so it is initialized as new. A file whose
                // magic alone is gone has lost its header, and starting fresh over it would
                // keep every node on disk while presenting the segment as empty - a corruption
                // arriving as a corpus that has disappeared.
                requireEntirelyBlank(probe, path);
                return null;
            }
            if (magic != MAGIC) {
                throw new IOException("Invalid HNSW file (bad magic number): " + path);
            }

            int version = probe.readInt(OFFSET_VERSION);
            if (!isSupportedVersion(version)) {
                throw new IOException("Unsupported HNSW version " + version + " in " + path);
            }

            // Before the layout fields are read, not after: a damaged header describes a
            // different graph, and adopting its dimension or node count would read every
            // record from the wrong offset.
            HnswGraphIntegrity.requireIntact(probe, path, version);

            String metric = persistedMetric(version, probe.readInt(OFFSET_METRIC), path);

            long persistedMaxNodes = probe.readLong(OFFSET_MAX_NODES);
            if (persistedMaxNodes <= 0) {
                // Written before maxNodes was part of the header. dataRegionOffset
                // cannot be reconstructed from the remaining fields, and guessing it
                // would read node data from the wrong offsets.
                throw new IOException("HNSW file predates the maxNodes header field and " +
                    "cannot be opened safely (rebuild the index): " + path);
            }

            HnswConfig persistedConfig = new HnswConfig(
                probe.readInt(OFFSET_CONFIG_MAX_CONN),
                probe.readInt(OFFSET_CONFIG_MAX_CONN_0),
                probe.readInt(OFFSET_CONFIG_EF_CONSTRUCTION),
                probe.readInt(OFFSET_CONFIG_EF_SEARCH),
                Double.longBitsToDouble(probe.readLong(OFFSET_CONFIG_ML)),
                probe.readInt(OFFSET_DIMENSION));

            return new PersistedLayout(persistedConfig, persistedMaxNodes, metric, version);
        }
    }

    /**
     * Refuses a file whose header is gone but whose body is not.
     *
     * @param probe The mapping of the file
     * @param path  The file, for the refusal message
     * @throws IOException if any byte in the file is non-zero
     */
    static void requireEntirelyBlank(MappedFile probe, Path path) throws IOException {
        long size = probe.size();
        byte[] chunk = new byte[(int) Math.min(BLANK_SCAN_CHUNK, Math.max(size, 1))];
        for (long at = 0; at < size; at += chunk.length) {
            int step = (int) Math.min(chunk.length, size - at);
            probe.read(at, chunk, 0, step);
            for (int i = 0; i < step; i++) {
                if (chunk[i] != 0) {
                    throw new IOException("HNSW graph " + path + " has lost its header - the "
                        + "magic number is zero but the file holds data at offset " + (at + i)
                        + ". It will not be opened, because rebuilding over it would present "
                        + "the segment as empty while its nodes are still on disk. Restore the "
                        + "file from a backup or reindex this shard.");
                }
            }
        }
    }

    /**
     * Writes the header of a brand new graph and initializes its integrity block.
     *
     * @param file The mapping of the new file
     * @param config The layout and tunables the graph is created with
     * @param maxNodes The capacity, which fixes where the data region starts
     * @param distanceFunction The metric whose code is recorded for later opens
     */
    static void writeInitial(MappedFile file, HnswConfig config, long maxNodes,
                             DistanceFunction distanceFunction) {
        file.writeLong(OFFSET_MAGIC, MAGIC);
        file.writeInt(OFFSET_VERSION, VERSION);
        file.writeInt(OFFSET_DIMENSION, config.dimension());
        file.writeLong(OFFSET_NODE_COUNT, 0);
        file.writeLong(OFFSET_ENTRY_POINT, -1);
        file.writeInt(OFFSET_MAX_LEVEL, -1);
        file.writeLong(OFFSET_LAST_INTEGRATED, 0);
        file.writeInt(OFFSET_CONFIG_MAX_CONN, config.maxConnections());
        file.writeInt(OFFSET_CONFIG_MAX_CONN_0, config.maxConnectionsLayer0());
        file.writeInt(OFFSET_CONFIG_EF_CONSTRUCTION, config.efConstruction());
        file.writeInt(OFFSET_CONFIG_EF_SEARCH, config.efSearch());
        file.writeLong(OFFSET_CONFIG_ML, Double.doubleToLongBits(config.mL()));
        file.writeLong(OFFSET_MAX_NODES, maxNodes);
        file.writeInt(OFFSET_METRIC, PersistedMetric.codeFor(distanceFunction.name()));
        HnswGraphIntegrity.initialize(file);
    }

    /**
     * Writes the entry point and highest layer back into the header.
     *
     * <p>The two move together - a new entry point is only chosen with its own level - so
     * they are written together rather than left to two call sites that might not agree.</p>
     *
     * @param file The mapping to write into
     * @param entryPoint The node searches descend from, or -1 if there is none
     * @param maxLevel The highest occupied layer, or -1 if there is none
     */
    static void writeEntryPoint(MappedFile file, long entryPoint, int maxLevel) {
        file.writeLong(OFFSET_ENTRY_POINT, entryPoint);
        file.writeInt(OFFSET_MAX_LEVEL, maxLevel);
    }

    /**
     * Reads a header out of a {@link Segment} without putting a graph into service.
     *
     * @param segment The segment holding a graph file's first bytes
     * @return The header's contents, or null if the segment holds no header this build reads
     */
    static HnswGraph.HeaderInfo readInfo(Segment segment) {
        if (segment.byteSize() < HEADER_SIZE) {
            return null;
        }

        try {
            long magic = segment.getLong(OFFSET_MAGIC);
            if (magic != MAGIC) {
                return null;
            }

            int version = segment.getInt(OFFSET_VERSION);
            if (!isSupportedVersion(version)) {
                // The remaining fields may sit at other offsets in a version this build
                // does not know, so reporting them would be reporting noise.
                LOGGER.debug("Segment holds an HNSW header at unsupported version {}", version);
                return null;
            }

            String distanceMetric = version == VERSION_WITHOUT_METRIC
                ? PersistedMetric.LEGACY_METRIC
                : PersistedMetric.nameFor(segment.getInt(OFFSET_METRIC));

            int dimension = segment.getInt(OFFSET_DIMENSION);
            long nodeCount = segment.getLong(OFFSET_NODE_COUNT);
            long entryPoint = segment.getLong(OFFSET_ENTRY_POINT);
            int maxLevel = segment.getInt(OFFSET_MAX_LEVEL);
            long lastIntegrated = segment.getLong(OFFSET_LAST_INTEGRATED);
            int maxConn = segment.getInt(OFFSET_CONFIG_MAX_CONN);
            int maxConn0 = segment.getInt(OFFSET_CONFIG_MAX_CONN_0);
            int efConstruction = segment.getInt(OFFSET_CONFIG_EF_CONSTRUCTION);
            double mL = Double.longBitsToDouble(segment.getLong(OFFSET_CONFIG_ML));
            int efSearch = segment.getInt(OFFSET_CONFIG_EF_SEARCH);

            return new HnswGraph.HeaderInfo(version, dimension, nodeCount, entryPoint, maxLevel,
                lastIntegrated, maxConn, maxConn0, efConstruction, efSearch, mL, distanceMetric);
        } catch (Exception e) {
            LOGGER.debug("Failed to read header info from segment: {}", e.getMessage());
            return null;
        }
    }
}
