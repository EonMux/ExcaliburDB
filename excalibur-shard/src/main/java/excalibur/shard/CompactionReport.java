package excalibur.shard;

import java.time.Duration;
import java.util.Objects;

/**
 * What one on-demand compaction run actually did.
 *
 * <p>The counts are deliberately separate. A run that rewrote thirty segments and failed on one
 * is neither a success nor a failure, and collapsing it to either would mislead the operator
 * who asked for it: the space the failed segment holds is still held, and the reason is in the
 * log. Everything here is what a caller is shown, so nothing is rounded away.</p>
 *
 * <p>"Considered" counts the live segments the run looked at, which is every distinct segment
 * the storage engine holds. A considered segment that was neither compacted nor failed was
 * skipped: the policy declined it, it was already being rewritten by the background worker, or
 * the run was interrupted before it reached the segment.</p>
 *
 * <p>A graph is skipped for those reasons and for one more: it held nothing worth reclaiming.
 * "Compacted" means space actually came back, so a forced run over graphs with no tombstoned
 * nodes reports them skipped rather than inflating the count with rewrites that reclaimed
 * nothing.</p>
 *
 * @param segmentsConsidered live segments the run examined
 * @param segmentsCompacted  segments rewritten during the run
 * @param segmentsFailed     segments whose rewrite raised an error, logged with its cause
 * @param graphsConsidered   HNSW graphs the run examined
 * @param graphsCompacted    graphs rebuilt during the run
 * @param graphsFailed       graphs whose rebuild raised an error
 * @param duration           wall-clock time the whole run took
 */
public record CompactionReport(
    int segmentsConsidered,
    int segmentsCompacted,
    int segmentsFailed,
    int graphsConsidered,
    int graphsCompacted,
    int graphsFailed,
    Duration duration
) {

    /**
     * Validates that the counts describe a possible run.
     *
     * @throws NullPointerException     if duration is null
     * @throws IllegalArgumentException if any count is negative or more work is reported than
     *                                  was considered
     */
    public CompactionReport {
        Objects.requireNonNull(duration, "duration cannot be null");
        requireNonNegative(segmentsConsidered, "segmentsConsidered");
        requireNonNegative(segmentsCompacted, "segmentsCompacted");
        requireNonNegative(segmentsFailed, "segmentsFailed");
        requireNonNegative(graphsConsidered, "graphsConsidered");
        requireNonNegative(graphsCompacted, "graphsCompacted");
        requireNonNegative(graphsFailed, "graphsFailed");
        if (segmentsCompacted + segmentsFailed > segmentsConsidered) {
            throw new IllegalArgumentException(
                "More segments acted on than considered: compacted=" + segmentsCompacted
                    + ", failed=" + segmentsFailed + ", considered=" + segmentsConsidered);
        }
        if (graphsCompacted + graphsFailed > graphsConsidered) {
            throw new IllegalArgumentException(
                "More graphs acted on than considered: compacted=" + graphsCompacted
                    + ", failed=" + graphsFailed + ", considered=" + graphsConsidered);
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " cannot be negative: " + value);
        }
    }

    /**
     * Segments the run looked at and left alone.
     *
     * @return considered segments that were neither compacted nor failed
     */
    public int segmentsSkipped() {
        return segmentsConsidered - segmentsCompacted - segmentsFailed;
    }

    /**
     * Graphs the run looked at and left alone.
     *
     * <p>A common outcome rather than an unusual one, and the reason a forced run's compacted
     * count can be far below what it considered: forcing skips the tombstone-ratio policy, not
     * the check for whether there is anything to reclaim, and a graph with no tombstoned nodes
     * is skipped rather than rewritten and reported as compacted.</p>
     *
     * @return considered graphs that were neither compacted nor failed
     */
    public int graphsSkipped() {
        return graphsConsidered - graphsCompacted - graphsFailed;
    }

    /**
     * Whether anything at all went wrong.
     *
     * @return true if any segment or graph failed to compact
     */
    public boolean hasFailures() {
        return segmentsFailed > 0 || graphsFailed > 0;
    }
}
