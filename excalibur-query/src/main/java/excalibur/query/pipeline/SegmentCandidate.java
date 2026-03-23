package excalibur.query.pipeline;

import excalibur.filter.lsh.LshIndex;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.vector.hnsw.HnswGraph;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a candidate segment for querying in the query pipeline.
 *
 * <p>A SegmentCandidate bundles together all the components needed to query
 * a segment: the segment itself, its HNSW graph, and its lifecycle state.</p>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>{@code segmentId} must be a positive segment identifier</li>
 *   <li>{@code segment} must not be null for valid candidates</li>
 *   <li>{@code graph} must not be null for valid candidates</li>
 *   <li>{@code state} must be a valid LifecycleState</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and therefore thread-safe. However, the
 * underlying Segment and HnswGraph may have their own thread-safety guarantees.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class SegmentCandidate {

    private final long segmentId;
    private final Segment segment;
    private final HnswGraph graph;
    private final LifecycleState state;
    private final LshIndex lshIndex;
    private final Set<Long> lshCandidates;

    /**
     * Creates a new SegmentCandidate.
     *
     * @param segmentId The segment identifier
     * @param segment The storage segment
     * @param graph The HNSW graph for the segment
     * @param state The lifecycle state of the segment
     */
    public SegmentCandidate(long segmentId, Segment segment, HnswGraph graph, LifecycleState state) {
        this.segmentId = segmentId;
        this.segment = segment;
        this.graph = graph;
        this.state = state;
        this.lshIndex = null;
        this.lshCandidates = Collections.emptySet();
    }

    /**
     * Creates a new SegmentCandidate with LSH index.
     *
     * @param segmentId The segment identifier
     * @param segment The storage segment
     * @param graph The HNSW graph for the segment
     * @param state The lifecycle state of the segment
     * @param lshIndex The LSH index for the segment (may be null)
     */
    public SegmentCandidate(long segmentId, Segment segment, HnswGraph graph,
                            LifecycleState state, LshIndex lshIndex) {
        this.segmentId = segmentId;
        this.segment = segment;
        this.graph = graph;
        this.state = state;
        this.lshIndex = lshIndex;
        this.lshCandidates = Collections.emptySet();
    }

    /**
     * Creates a new SegmentCandidate with all fields.
     *
     * @param segmentId The segment identifier
     * @param segment The storage segment
     * @param graph The HNSW graph for the segment
     * @param state The lifecycle state of the segment
     * @param lshIndex The LSH index for the segment (may be null)
     * @param lshCandidates The set of candidate entry IDs from LSH filtering
     */
    private SegmentCandidate(long segmentId, Segment segment, HnswGraph graph,
                             LifecycleState state, LshIndex lshIndex, Set<Long> lshCandidates) {
        this.segmentId = segmentId;
        this.segment = segment;
        this.graph = graph;
        this.state = state;
        this.lshIndex = lshIndex;
        this.lshCandidates = lshCandidates != null ? lshCandidates : Collections.emptySet();
    }

    /**
     * Gets the segment identifier.
     *
     * @return The segment ID
     */
    public long segmentId() {
        return segmentId;
    }

    /**
     * Gets the storage segment.
     *
     * @return The segment, or null if not available
     */
    public Segment segment() {
        return segment;
    }

    /**
     * Gets the HNSW graph for the segment.
     *
     * @return The HNSW graph, or null if not available
     */
    public HnswGraph graph() {
        return graph;
    }

    /**
     * Gets the lifecycle state of the segment.
     *
     * @return The lifecycle state
     */
    public LifecycleState state() {
        return state;
    }

    /**
     * Gets the LSH index for the segment.
     *
     * @return The LSH index, or null if not available
     */
    public LshIndex lshIndex() {
        return lshIndex;
    }

    /**
     * Gets the set of candidate entry IDs from LSH filtering.
     *
     * <p>These are the entry IDs that passed LSH pre-filtering for this segment.
     * This set is populated during Stage 2 of the query pipeline.</p>
     *
     * @return The set of candidate entry IDs (may be empty)
     */
    public Set<Long> lshCandidates() {
        return lshCandidates;
    }

    /**
     * Creates a new SegmentCandidate with the specified LSH candidates.
     *
     * <p>This method is used during Stage 2 (LSH pre-filter) to attach
     * the set of candidate entry IDs that passed LSH filtering.</p>
     *
     * @param lshCandidates The set of candidate entry IDs from LSH filtering
     * @return A new SegmentCandidate with the LSH candidates
     */
    public SegmentCandidate withLshCandidates(Set<Long> lshCandidates) {
        return new SegmentCandidate(segmentId, segment, graph, state, lshIndex, lshCandidates);
    }

    /**
     * Creates a new SegmentCandidate with the specified LSH index.
     *
     * @param lshIndex The LSH index for the segment (may be null)
     * @return A new SegmentCandidate with the LSH index
     */
    public SegmentCandidate withLshIndex(LshIndex lshIndex) {
        return new SegmentCandidate(segmentId, segment, graph, state, lshIndex, lshCandidates);
    }

    /**
     * Checks if this candidate has an LSH index.
     *
     * @return true if LSH index is available, false otherwise
     */
    public boolean hasLshIndex() {
        return lshIndex != null;
    }

    /**
     * Checks if this candidate has LSH pre-filtered candidates.
     *
     * @return true if LSH candidates are available (non-empty), false otherwise
     */
    public boolean hasLshCandidates() {
        return lshCandidates != null && !lshCandidates.isEmpty();
    }

    /**
     * Checks if this candidate is valid for querying.
     *
     * <p>A candidate is valid if both the segment and graph are non-null.</p>
     *
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        return segment != null && graph != null;
    }

    /**
     * Checks if this candidate is in a queryable state.
     *
     * @return true if queryable, false otherwise
     */
    public boolean isQueryable() {
        return state != null && state.isQueryable();
    }

    /**
     * Checks if this candidate has a write log attached.
     *
     * @return true if the segment has a write log, false otherwise
     */
    public boolean hasWriteLog() {
        return segment != null && segment.writeLog() != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SegmentCandidate that = (SegmentCandidate) o;
        return segmentId == that.segmentId &&
               Objects.equals(segment, that.segment) &&
               Objects.equals(graph, that.graph) &&
               state == that.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(segmentId, segment, graph, state);
    }

    @Override
    public String toString() {
        return String.format("SegmentCandidate{segmentId=%d, state=%s, valid=%s}",
                            segmentId, state, isValid());
    }
}
