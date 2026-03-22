package excalibur.query.pipeline;

import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.vector.hnsw.HnswGraph;

import java.util.Objects;

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
