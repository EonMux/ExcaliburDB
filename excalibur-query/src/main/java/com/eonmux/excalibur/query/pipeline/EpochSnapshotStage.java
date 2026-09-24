package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.filter.lsh.LshIndex;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.visibility.QuerySnapshot;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Stage 0: Capture visibility epoch and resolve queryable segments.
 *
 * <p>This stage captures a consistent snapshot of segment visibility at the current epoch using
 * {@link QuerySnapshot#capture}, or at the epoch the request fixed
 * ({@link QueryRequest#readEpoch()}) using {@link QuerySnapshot#atEpoch}. All subsequent
 * stages operate on this snapshot.</p>
 */
final class EpochSnapshotStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(EpochSnapshotStage.class);

    private final StorageEngine storageEngine;
    private final VisibilityTracker visibilityTracker;
    private final QueryPipeline.HnswGraphProvider graphProvider;

    EpochSnapshotStage(StorageEngine storageEngine,
                       VisibilityTracker visibilityTracker,
                       QueryPipeline.HnswGraphProvider graphProvider) {
        this.storageEngine = storageEngine;
        this.visibilityTracker = visibilityTracker;
        this.graphProvider = graphProvider;
    }

    /**
     * Captures the epoch and the segments this query may read at it.
     *
     * @param request The query request
     * @param token The cancellation token
     * @return A fresh context carrying the epoch and its candidates
     */
    PipelineContext capture(QueryRequest request, CancellationToken token) {
        token.checkCancelled("Stage 0 cancelled before epoch snapshot");

        LOGGER.trace("Stage 0: Capturing epoch snapshot");

        // At the epoch the caller fixed, when it answers from another index beside this one and
        // both halves have to read the same moment; otherwise at the epoch current now.
        OptionalLong pinned = request.readEpoch();
        QuerySnapshot snapshot = pinned.isPresent()
            ? QuerySnapshot.atEpoch(pinned.getAsLong(), visibilityTracker,
                storageEngine.getCatalog(), storageEngine)
            : QuerySnapshot.capture(visibilityTracker, storageEngine.getCatalog(), storageEngine);

        Admissions admissions = admitVisibleSegments(snapshot, request);

        LOGGER.debug("Stage 0: Captured epoch {} with {} queryable segments, {} excluded",
            snapshot.epoch(), admissions.candidates().size(), admissions.segmentsExcluded());

        return new PipelineContext(request, token, snapshot.epoch(),
            admissions.candidates(), admissions.segmentsExcluded());
    }

    /**
     * Turns the segments visible at the snapshot's epoch into the ones this query may read.
     *
     * <p>A segment the catalog counts as live corpus and this query does not read is a gap in
     * the answer, not an error: nothing throws, nothing is logged as a failure, and coverage
     * taken from the catalog alone reports it as searched. Counting the gaps is what lets the
     * response say how much of the corpus the query actually reached.</p>
     *
     * @param snapshot The visibility snapshot this query reads at
     * @param request The query request, which decides whether partial segments are admitted
     * @return The admitted candidates, and how many live segments were held back
     */
    private Admissions admitVisibleSegments(QuerySnapshot snapshot, QueryRequest request) {
        SegmentCatalog catalog = storageEngine.getCatalog();
        List<SegmentCandidate> candidates = new ArrayList<>();
        int segmentsExcluded = 0;

        // The ID comes from the snapshot, not from the segment. Segment.segmentId() counts every
        // segment instance the process has constructed; the catalog, the visibility tracker and
        // the graph provider are all keyed by the directory index the engine routes by. The two agree while segments are created in directory order and part
        // company the moment one is created out of it - which is what a compaction and a split
        // both do. Past that point the catalog lookup below misses, the segment is dropped from
        // stage 0, and it is absent from every query result with nothing logged as an error.
        for (Map.Entry<Long, Segment> visible : snapshot.resolveVisibleSegmentsById().entrySet()) {
            long segmentId = visible.getKey();
            Segment segment = visible.getValue();
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            if (metadata == null) continue;

            LifecycleState state = metadata.getLifecycleState();
            if (!state.isQueryable()) continue;

            // Hold back the segments still taking entries when the caller asked for complete ones
            if (!request.includePartialSegments() && state.isPartiallyIndexed()) {
                segmentsExcluded++;
                continue;
            }

            HnswGraph graph = graphProvider.getGraph(segmentId);
            if (graph == null) {
                segmentsExcluded++;
                continue;
            }

            LshIndex lshIndex = segment.lshIndex();
            candidates.add(new SegmentCandidate(segmentId, segment, graph, state, lshIndex));
        }

        return new Admissions(candidates, segmentsExcluded);
    }

    /**
     * What stage 0 admitted, and what it knowingly left out.
     *
     * @param candidates The segments the query may be answered from
     * @param segmentsExcluded How many queryable segments were held back, so the response can
     *                         say how much of the corpus this query actually reached
     */
    private record Admissions(List<SegmentCandidate> candidates, int segmentsExcluded) {
    }
}
