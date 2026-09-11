package excalibur.api.retrieval;

import excalibur.ingest.lexical.ChunkIndexStats;
import excalibur.query.IndexCoverage;

/**
 * What the lexical path may claim about how much of the corpus it searched.
 *
 * <h2>Why this is not a constant</h2>
 * <p>Coverage is the field that separates two identical-looking answers: an empty result at 1.0
 * means the corpus does not hold what was asked for, and the same empty result at 0.3 means the
 * node did not look at most of it. A hardcoded 1.0 collapses the two, and it collapses them in
 * the dangerous direction - a node whose index is empty or unopened reports having searched
 * everything.</p>
 *
 * <h2>Mapping the two indexes onto one shape</h2>
 * <p>The vector index has a segment lifecycle; the lexical store does not, and the difference is
 * substantive rather than a gap to be filled with zeros:</p>
 * <ul>
 *   <li><b>Every published segment is active.</b> The store publishes a segment by writing it
 *       fully and renaming it into the manifest, so a segment a query can see is a segment that
 *       is complete and searchable. There is no indexing, warming or compacting state for one to
 *       be in - those states exist for the vector index because a graph has to be built and warmed
 *       after its records land.</li>
 *   <li><b>Every published segment is searched.</b> BM25 visits every segment the manifest names.
 *       Nothing selects a subset, so nothing can be excluded, time out on its own, or fail while
 *       the rest succeed.</li>
 *   <li><b>Nothing is tail-scanned.</b> The vector path scans a write-log tail the graph has not
 *       absorbed; the lexical store has no equivalent, because an unpublished segment is not
 *       partially visible.</li>
 * </ul>
 *
 * <h2>What erasure does not mean</h2>
 * <p>An erased chunk is one a caller asked the node to forget. It is hidden from queries and still
 * on disk until compaction reclaims it, but it is not corpus the query failed to reach - counting
 * it as missed would report a node discharging its erasure obligations as degraded.</p>
 *
 * <h2>Degradation</h2>
 * <p>Always true. The answer is real and it came from one signal where a fully configured node
 * would fuse several, and saying so is what lets a caller tell "this is all there was" from
 * "this is all we looked at".</p>
 */
public final class LexicalCoverage {

    private LexicalCoverage() {
    }

    /**
     * Describes what a lexical query covered when it ran out of time.
     *
     * <p>BM25 visits every segment the manifest names, so a search cut off partway has read
     * an unknown prefix of them and can claim none: the segments are reported as timed out
     * rather than searched, and coverage is 0.0. Reporting the healthy 1.0 here would say the
     * whole corpus was read to produce what is actually an empty answer.</p>
     *
     * @param stats The index's own account of itself, or null when it has none to give
     * @return Coverage in the shape the vector path reports
     */
    public static IndexCoverage timedOut(ChunkIndexStats stats) {
        return unreached(stats, true);
    }

    /**
     * Describes what a lexical query covered when the caller withdrew it.
     *
     * <p>Nothing was searched, as with a timeout - but no segment timed out, and saying one
     * did would blame the deadline for a request the caller took back. Which of the two
     * happened is on the response itself, in {@code cancelled} and {@code timed_out}.</p>
     *
     * @param stats The index's own account of itself, or null when it has none to give
     * @return Coverage in the shape the vector path reports
     */
    public static IndexCoverage cancelled(ChunkIndexStats stats) {
        return unreached(stats, false);
    }

    private static IndexCoverage unreached(ChunkIndexStats stats, boolean timedOut) {
        int segments = stats == null ? 0 : stats.segments();
        return IndexCoverage.builder()
            .totalSegments(segments)
            .activeSegments(segments)
            .segmentsSearched(0)
            .segmentsTimedOut(timedOut ? segments : 0)
            .estimatedCorpusCoverage(0.0)
            .degraded(true)
            .build();
    }

    /**
     * Describes what a lexical query covered.
     *
     * @param stats The index's own account of itself, or null when it has none to give
     * @return Coverage in the shape the vector path reports
     */
    public static IndexCoverage of(ChunkIndexStats stats) {
        if (stats == null || stats.segments() == 0) {
            // Nothing to search is not the same as having searched everything.
            return IndexCoverage.builder()
                .degraded(true)
                .estimatedCorpusCoverage(0.0)
                .build();
        }
        return IndexCoverage.builder()
            .totalSegments(stats.segments())
            .activeSegments(stats.segments())
            .segmentsSearched(stats.segments())
            .estimatedCorpusCoverage(1.0)
            .degraded(true)
            .build();
    }
}
