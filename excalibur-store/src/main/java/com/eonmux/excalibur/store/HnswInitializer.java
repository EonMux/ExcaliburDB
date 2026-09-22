package com.eonmux.excalibur.store;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Initializes HNSW graphs for storage segments.
 *
 * <p>This class is responsible for creating and attaching HNSW graphs
 * to segments during segment creation. It handles:</p>
 * <ul>
 *   <li>Creating the HNSW graph file alongside the segment data file</li>
 *   <li>Configuring the graph with appropriate parameters</li>
 *   <li>Creating and attaching the tail offset tracker for live indexing</li>
 *   <li>Attaching both graph and tracker to the segment</li>
 * </ul>
 *
 * <h2>File Naming Convention</h2>
 * <p>For a segment file {@code segment_N.dat}, the HNSW graph is stored in
 * {@code segment_N.hnsw} in the same directory.</p>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Vector dimensions: Must match the configured dimension (default: 1536 for OpenAI embeddings)</li>
 *   <li>Max nodes: Should be sized appropriately for expected segment capacity</li>
 *   <li>Graph file is created if it doesn't exist, or opened if it does</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is thread-safe.</strong> Each initialization call operates
 * on a separate segment, and the factory configuration is immutable.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 * @see HnswGraph
 * @see TailOffsetTracker
 * @see Segment
 */
public class HnswInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswInitializer.class);

    /**
     * Default vector dimension for OpenAI text-embedding-3-small embeddings.
     */
    public static final int DEFAULT_VECTOR_DIMENSION = 1536;

    /**
     * Default maximum number of nodes in the HNSW graph.
     * Sized for segments with up to 1M entries.
     */
    public static final long DEFAULT_MAX_NODES = 1_000_000L;

    /**
     * HNSW configuration factory for creating graph configs.
     */
    private final HnswConfigFactory configFactory;

    /**
     * Distance function for vector comparison.
     */
    private final DistanceFunction distanceFunction;

    /**
     * Vector dimension for the graphs.
     */
    private final int dimension;

    /**
     * Maximum number of nodes per graph.
     */
    private final long maxNodes;

    /**
     * Arena for memory allocation (may be null for fallback).
     */
    private final Arena arena;

    /**
     * Creates a new HnswInitializer with default settings.
     *
     * <p>Uses:</p>
     * <ul>
     *   <li>Vector dimension: 1536 (OpenAI text-embedding-3-small)</li>
     *   <li>Max nodes: 1,000,000</li>
     *   <li>Distance function: Cosine similarity</li>
     *   <li>Config factory: Default efConstruction=128, efSearch=64</li>
     * </ul>
     */
    public HnswInitializer() {
        this(HnswConfigFactory.withDefaults(), CosineSimilarity.getInstance(),
             DEFAULT_VECTOR_DIMENSION, DEFAULT_MAX_NODES, null);
    }

    /**
     * Creates a new HnswInitializer with the specified configuration.
     *
     * @param configFactory    The HNSW config factory for creating graph configs
     * @param distanceFunction The distance function for vector comparison
     * @param dimension        The vector dimension
     * @param maxNodes         Maximum nodes per graph
     * @param arena            Arena for memory allocation (may be null)
     * @throws NullPointerException if configFactory or distanceFunction is null
     * @throws IllegalArgumentException if dimension or maxNodes is invalid
     */
    public HnswInitializer(HnswConfigFactory configFactory,
                           DistanceFunction distanceFunction,
                           int dimension,
                           long maxNodes,
                           Arena arena) {
        if (configFactory == null) {
            throw new NullPointerException("configFactory cannot be null");
        }
        if (distanceFunction == null) {
            throw new NullPointerException("distanceFunction cannot be null");
        }
        if (dimension < 1 || dimension > 65536) {
            throw new IllegalArgumentException(
                "dimension must be between 1 and 65536, got: " + dimension);
        }
        if (maxNodes < 1) {
            throw new IllegalArgumentException("maxNodes must be positive, got: " + maxNodes);
        }

        this.configFactory = configFactory;
        this.distanceFunction = distanceFunction;
        this.dimension = dimension;
        this.maxNodes = maxNodes;
        this.arena = arena;

        LOGGER.info("HnswInitializer created: dimension={}, maxNodes={}, efConstruction={}, efSearch={}",
            dimension, maxNodes, configFactory.getEfConstruction(), configFactory.getEfSearch());
    }

    /**
     * Creates a new HnswInitializer from an existing HnswConfigFactory.
     *
     * <p>This is a convenience constructor that uses default values for
     * dimension, maxNodes, and arena while allowing custom config from
     * the factory.</p>
     *
     * @param configFactory    The HNSW config factory
     * @param distanceFunction The distance function for vector comparison
     * @throws NullPointerException if configFactory or distanceFunction is null
     */
    public HnswInitializer(HnswConfigFactory configFactory, DistanceFunction distanceFunction) {
        this(configFactory, distanceFunction, DEFAULT_VECTOR_DIMENSION, DEFAULT_MAX_NODES, null);
    }

    /**
     * Creates a new HnswInitializer with a custom arena for memory allocation.
     *
     * @param configFactory    The HNSW config factory
     * @param distanceFunction The distance function
     * @param dimension        The vector dimension
     * @param maxNodes         Maximum nodes per graph
     * @param arena            Arena for memory allocation (may be null)
     * @return A new HnswInitializer
     * @throws NullPointerException if configFactory or distanceFunction is null
     */
    public static HnswInitializer create(HnswConfigFactory configFactory,
                                          DistanceFunction distanceFunction,
                                          int dimension,
                                          long maxNodes,
                                          Arena arena) {
        return new HnswInitializer(configFactory, distanceFunction, dimension, maxNodes, arena);
    }

    /**
     * Initializes an HNSW graph for a segment.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Computes the graph file path from the segment path</li>
     *   <li>Creates the HNSW graph with configured parameters</li>
     *   <li>Creates a tail offset tracker for the graph</li>
     *   <li>Attaches both graph and tracker to the segment</li>
     * </ol>
     *
     * <p>If the segment already has an HNSW graph attached, this method
     * is a no-op and returns successfully.</p>
     *
     * @param segment The segment to initialize
     * @throws IOException if the graph file cannot be created
     * @throws NullPointerException if segment is null
     */
    public void initializeForSegment(Segment segment) throws IOException {
        if (segment == null) {
            throw new NullPointerException("segment cannot be null");
        }

        // Skip if already initialized
        if (segment.getHnswGraph() != null) {
            LOGGER.debug("Segment {} already has HNSW graph attached", segment.path());
            return;
        }

        Path segmentPath = segment.path();
        Path graphPath = computeGraphPath(segmentPath);

        // Ensure parent directory exists
        Path parentDir = graphPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        LOGGER.debug("Initializing HNSW graph for segment {}: graphPath={}",
            segmentPath, graphPath);

        // Create HNSW config with the configured dimension
        HnswConfig config = configFactory.create(dimension);

        // Create the HNSW graph
        HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, maxNodes);

        // Create the tail offset tracker
        TailOffsetTracker tracker = new TailOffsetTracker(graph);

        // Attach to segment
        segment.attachHnswGraph(graph);
        segment.attachTailOffsetTracker(tracker);

        LOGGER.info("HNSW graph initialized for segment {}: dimension={}, maxNodes={}",
            segmentPath.getFileName(), dimension, maxNodes);
    }

    /**
     * Computes the HNSW graph file path from a segment path.
     *
     * <p>For a segment file {@code segment_N.dat}, the graph is stored
     * in {@code segment_N.hnsw} in the same directory.</p>
     *
     * @param segmentPath The segment file path
     * @return The corresponding HNSW graph file path
     */
    public Path computeGraphPath(Path segmentPath) {
        String fileName = segmentPath.getFileName().toString();
        String graphFileName;
        if (fileName.endsWith(".dat")) {
            graphFileName = fileName.substring(0, fileName.length() - 4) + ".hnsw";
        } else {
            graphFileName = fileName + ".hnsw";
        }
        return segmentPath.resolveSibling(graphFileName);
    }

    /**
     * Returns the vector dimension used by this initializer.
     *
     * @return The vector dimension
     */
    public int getDimension() {
        return dimension;
    }

    /**
     * Returns the maximum nodes per graph.
     *
     * @return Maximum nodes
     */
    public long getMaxNodes() {
        return maxNodes;
    }

    /**
     * Returns the distance function used by this initializer.
     *
     * @return The distance function
     */
    public DistanceFunction getDistanceFunction() {
        return distanceFunction;
    }

    /**
     * Returns the config factory used by this initializer.
     *
     * @return The config factory
     */
    public HnswConfigFactory getConfigFactory() {
        return configFactory;
    }

    @Override
    public String toString() {
        return String.format("HnswInitializer{dimension=%d, maxNodes=%d, efConstruction=%d, efSearch=%d}",
            dimension, maxNodes, configFactory.getEfConstruction(), configFactory.getEfSearch());
    }
}
