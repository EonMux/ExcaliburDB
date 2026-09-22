package com.eonmux.excalibur.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Attaching a segment's vector index structures, and refusing to let a split child inherit
 * someone else's.
 *
 * <p>An HNSW graph and its pre-filter are not part of a segment's key-value data, but they are
 * derived from it and named after it, and that pairing is what this class is responsible for.
 * Every segment that can be queried comes through here - created, pre-allocated, recovered at
 * start-up, or produced by a split - because a segment that reaches service without a graph
 * contributes no candidates to any vector search while answering key lookups perfectly, and
 * nothing downstream can tell the difference.</p>
 *
 * <h2>A failure is counted, not fatal</h2>
 * <p>The failures reachable here are permanent for the segment they concern: an unrecognised
 * magic number, a graph version this build does not know, a dimension or distance metric that
 * does not match the configured one. Nothing retries them and nothing repairs them, so the
 * choice is between refusing to start and starting without that one index - and refusing takes
 * the whole node down, key-value reads included, to protect the part of it that does vector
 * search. What made the old behaviour indefensible was not that it kept serving; it was that it
 * kept serving <em>silently</em>. So the failure is recorded against the segment, logged at
 * SEVERE, and published through {@code StorageEngine.segmentsWithoutVectorIndex()} where a
 * health surface can read it. A non-zero count means vector results from this engine are
 * incomplete.</p>
 *
 * <h2>Why a split child discards what it finds</h2>
 * <p>The left child of a split takes over its parent's canonical file name, and the graph and
 * pre-filter files are derived from that name - so without intervention the child opens its
 * parent's index. That index describes the parent's contents: it holds nodes for every entry
 * that moved to the right child, its node ids are not the ones the child's fresh graph will
 * hand out, and its header carries a checkpoint into a write log that no longer exists. Half
 * of the point of a split is that the children hold different entries, so there is nothing in
 * it worth keeping, and keeping it means answering with the wrong nodes rather than with
 * fewer.</p>
 *
 * <p><strong>Thread safety:</strong> the failure map is concurrent; the initializer and
 * pre-filter are read once each from the engine's volatile fields into a local, so a
 * concurrent install cannot be seen half-applied within one call.</p>
 */
final class SegmentIndexAttachment {

    /** The engine whose segments are being indexed. */
    private final StorageEngine engine;

    /**
     * Segment index to the reason its HNSW graph could not be attached.
     *
     * <p>Drained when a later attempt succeeds, so it is the current set rather than a
     * history.</p>
     */
    private final Map<Long, String> vectorIndexFailures = new ConcurrentHashMap<>();

    /**
     * Binds this attachment point to its engine.
     *
     * @param engine The owning engine
     */
    SegmentIndexAttachment(StorageEngine engine) {
        this.engine = engine;
    }

    /**
     * Returns how many segments are serving without a vector index.
     *
     * @return The count; zero is the only healthy value
     */
    int failureCount() {
        return vectorIndexFailures.size();
    }

    /**
     * Returns each segment serving without a vector index, and why.
     *
     * @return An immutable snapshot, empty when every segment has its graph
     */
    Map<Long, String> failures() {
        return Map.copyOf(vectorIndexFailures);
    }

    /**
     * Attaches an HNSW graph to a segment if an initializer is configured.
     *
     * <p>Every segment that can be queried goes through this method - segments created
     * lazily, segments pre-allocated at startup, segments recovered by discovery and
     * split children alike - so vector search can never be silently disabled for part
     * of the keyspace.</p>
     *
     * <h3>Why a failure is counted rather than fatal</h3>
     * <p>The failures reachable here are permanent for the segment they concern: a graph file
     * with an unrecognised magic number, a version this build does not know, a dimension or a
     * distance metric that does not match the one configured. Nothing retries them and nothing
     * repairs them, so the choice is between refusing to start and starting without that one
     * index.</p>
     *
     * <p>Refusing to start is the wrong trade here. The segment's key-value data is intact and
     * unrelated to its graph, and the commonest way to reach this state - changing the
     * configured embedding dimension, or rolling back to a build with an older graph format -
     * would take the whole node down, key-value reads included, to protect the part of it that
     * does vector search. What made the old behaviour indefensible was not that it kept
     * serving; it was that it kept serving <em>silently</em>, leaving a warning in a log as the
     * only trace, while every vector search over that keyspace returned a confident answer with
     * one segment's worth of candidates missing.</p>
     *
     * <p>So the failure is recorded against the segment and exposed through
     * {@link StorageEngine#segmentsWithoutVectorIndexCount()} and {@link StorageEngine#segmentsWithoutVectorIndex()},
     * where a health or stats surface can read it, and logged at SEVERE with the cause
     * attached. A non-zero count means vector results from this engine are incomplete.</p>
     *
     * @param segment      The segment to initialize
     * @param segmentIndex The segment's directory index
     */
    void initializeHnswGraph(Segment segment, long segmentIndex) {
        HnswInitializer initializer = engine.hnswInitializer;
        if (initializer == null || segment.getHnswGraph() != null) {
            return;
        }
        try {
            initializer.initializeForSegment(segment);
            vectorIndexFailures.remove(segmentIndex);
            StorageEngine.LOGGER.fine("HNSW graph initialized for segment " + segmentIndex);
        } catch (IOException e) {
            vectorIndexFailures.put(segmentIndex, String.valueOf(e.getMessage()));
            StorageEngine.LOGGER.log(Level.SEVERE, "Segment " + segmentIndex + " came up without a vector "
                + "index and will contribute no candidates to any vector search; its "
                + "key-value data is unaffected. See segmentsWithoutVectorIndex().", e);
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
    void initializeVectorPrefilter(Segment segment, long segmentIndex) {
        VectorPrefilter prefilter = engine.vectorPrefilter;
        if (prefilter == null || segment.getLshIndex() != null) {
            return;
        }
        try {
            prefilter.initialize(segment);
            StorageEngine.LOGGER.fine("Vector pre-filter initialized for segment " + segmentIndex);
        } catch (RuntimeException e) {
            StorageEngine.LOGGER.warning("Failed to initialize vector pre-filter for segment "
                + segmentIndex + ": " + e.getMessage());
            // Continue without a pre-filter - the query pipeline falls back to a full search
        }
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
     * <p>Its sidecars go with it. The document mapping names the parent's nodes, and the
     * product-quantisation codebook was trained on the parent's vectors; a child left holding
     * either would describe its own contents with the parent's answers.</p>
     *
     * @param child The child being prepared
     * @param segmentIndex The child's canonical index
     */
    void discardInheritedGraph(Segment child, long segmentIndex) {
        HnswInitializer initializer = engine.hnswInitializer;
        if (initializer == null || child.getHnswGraph() != null) {
            return;
        }
        Path graphPath = initializer.computeGraphPath(child.path());
        try {
            java.nio.file.Files.deleteIfExists(graphPath);
            java.nio.file.Files.deleteIfExists(Path.of(graphPath.toString()
                + com.eonmux.excalibur.vector.hnsw.HnswGraph.DOCUMENT_MAPPING_SUFFIX));
            java.nio.file.Files.deleteIfExists(Path.of(graphPath.toString()
                + com.eonmux.excalibur.vector.hnsw.HnswGraph.PRODUCT_QUANTIZER_SUFFIX));
        } catch (IOException e) {
            StorageEngine.LOGGER.log(Level.WARNING,
                "Could not remove the graph a split child would inherit at "
                    + graphPath + " (segment " + segmentIndex + ")", e);
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
    void discardInheritedPrefilter(Segment child, long segmentIndex) {
        VectorPrefilter prefilter = engine.vectorPrefilter;
        if (prefilter == null || child.getLshIndex() != null) {
            return;
        }
        try {
            prefilter.discardPersistedState(child);
        } catch (RuntimeException e) {
            StorageEngine.LOGGER.log(Level.WARNING,
                "Could not remove the vector pre-filter a split child would inherit (segment "
                    + segmentIndex + ")", e);
        }
    }

    /**
     * Installs the graph initializer and back-fills every segment that has none.
     *
     * <p>Installing before {@link StorageEngine#start()} is preferred, because start-up creates
     * the whole base keyspace and each segment would get its graph on the way through. Wiring
     * is not always available that early, though, so a late install walks the segments that
     * already exist rather than leaving vector search quietly disabled over the part of the
     * keyspace that predates it.</p>
     *
     * @param initializer The initializer, or null to disable graph creation for new segments
     * @see StorageEngine#setHnswInitializer(HnswInitializer)
     */
    void setHnswInitializer(HnswInitializer initializer) {
        engine.hnswInitializer = initializer;
        StorageEngine.LOGGER.info("HNSW initializer " + (initializer != null ? "configured" : "disabled"));

        if (initializer == null) {
            return;
        }

        int initialized = 0;
        for (Map.Entry<Long, Segment> entry : engine.routingTable.distinctSegments().entrySet()) {
            Segment segment = entry.getValue();
            if (segment.getHnswGraph() == null && !segment.isClosed()) {
                initializeHnswGraph(segment, entry.getKey());
                if (segment.getHnswGraph() != null) {
                    initialized++;
                }
            }
        }
        if (initialized > 0) {
            StorageEngine.LOGGER.info("Back-filled HNSW graphs for " + initialized + " existing segment(s)");
        }
    }

    /**
     * Installs the vector pre-filter and back-fills every segment that has none.
     *
     * <p>The pre-filter is read off the segment by the query pipeline, so it has to be
     * attached wherever a graph is. Leaving it to the first write that reaches a segment
     * means a segment that is only ever read never gets one - which, after a restart, is
     * every segment, however much persisted pre-filter state is sitting beside it on disk.</p>
     *
     * @param prefilter The pre-filter, or null to disable
     * @see StorageEngine#setVectorPrefilter(VectorPrefilter)
     */
    void setVectorPrefilter(VectorPrefilter prefilter) {
        engine.vectorPrefilter = prefilter;
        StorageEngine.LOGGER.info("Vector pre-filter " + (prefilter != null ? "configured" : "disabled"));

        if (prefilter == null) {
            return;
        }

        int initialized = 0;
        for (Map.Entry<Long, Segment> entry : engine.routingTable.distinctSegments().entrySet()) {
            Segment segment = entry.getValue();
            if (segment.getLshIndex() == null && !segment.isClosed()) {
                initializeVectorPrefilter(segment, entry.getKey());
                if (segment.getLshIndex() != null) {
                    initialized++;
                }
            }
        }
        if (initialized > 0) {
            StorageEngine.LOGGER.info("Back-filled vector pre-filters for " + initialized + " existing segment(s)");
        }
    }
}
