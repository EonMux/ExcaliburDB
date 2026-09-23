package com.eonmux.excalibur.retrieval.lexical.native_;

import java.util.Objects;

/**
 * What a compaction actually did.
 *
 * <p>Carries the reason as well as the counts because "nothing happened" has several causes that
 * matter differently to whoever asked: the policy declined, or it fired and found nothing to
 * remove. Returning zero for both would make those indistinguishable.</p>
 *
 * <h2>Abandoned groups</h2>
 * <p>A rewrite is planned as groups of segments, and a group whose surviving text storage could
 * not read back is abandoned - left exactly as it was - rather than rewritten without the chunks
 * it could not read. That is the safe outcome for the data and the wrong one to report as a
 * success: an erasure recorded in an abandoned group is still on disk. So the count is carried
 * here, and a compaction that published nothing because every group it planned was abandoned is
 * reported as not {@link #performed() performed}, however much work it did before giving up. A
 * caller that needs to know whether erased chunks left disk asks the index what it still owes;
 * {@link #partial()} is what says a compaction that did publish left some of its plan behind.</p>
 *
 * @param performed         Whether compaction ran and published a rewrite, or ran and found its
 *                          plan had nothing to rewrite; false when it was declined, stopped, or
 *                          every group it planned was abandoned
 * @param segmentsRewritten Segments read and replaced
 * @param segmentsProduced  Segments written in their place
 * @param documentsRemoved  Documents physically removed from disk
 * @param groupsAbandoned   Planned groups left as they were because a surviving chunk's text
 *                          could not be read back
 * @param reason            Why it ran, or why it did not
 */
public record CompactionResult(boolean performed, int segmentsRewritten, int segmentsProduced,
                               int documentsRemoved, int groupsAbandoned, String reason) {

    public CompactionResult {
        Objects.requireNonNull(reason, "reason must not be null");
        if (segmentsRewritten < 0 || segmentsProduced < 0 || documentsRemoved < 0
                || groupsAbandoned < 0) {
            throw new IllegalArgumentException("compaction counts must not be negative");
        }
    }

    /**
     * A compaction that abandoned nothing - every outcome an index that never reads text back
     * can have.
     *
     * @param performed         Whether compaction ran
     * @param segmentsRewritten Segments read and replaced
     * @param segmentsProduced  Segments written in their place
     * @param documentsRemoved  Documents physically removed from disk
     * @param reason            Why it ran, or why it did not
     */
    public CompactionResult(boolean performed, int segmentsRewritten, int segmentsProduced,
                            int documentsRemoved, String reason) {
        this(performed, segmentsRewritten, segmentsProduced, documentsRemoved, 0, reason);
    }

    /**
     * Whether the compaction published a rewrite and left part of its plan as it was.
     *
     * @return true when something was published and at least one group was abandoned
     */
    public boolean partial() {
        return performed && groupsAbandoned > 0;
    }

    /** The policy did not consider compaction due. */
    public static CompactionResult declined(String reason) {
        return new CompactionResult(false, 0, 0, 0, reason);
    }

    /**
     * The caller asked the compaction to stop, and it ended without publishing anything.
     *
     * <p>Not performed, since nothing it built replaced anything: a stopped rewrite discards its
     * own output and leaves every segment as it found it.</p>
     *
     * @param reason How far it had got, for whoever asked
     * @return The result
     */
    public static CompactionResult stopped(String reason) {
        return new CompactionResult(false, 0, 0, 0, "stopped: " + reason);
    }
}
