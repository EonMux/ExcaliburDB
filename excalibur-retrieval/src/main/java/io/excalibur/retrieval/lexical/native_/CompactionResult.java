package io.excalibur.retrieval.lexical.native_;

import java.util.Objects;

/**
 * What a compaction actually did.
 *
 * <p>Carries the reason as well as the counts because "nothing happened" has several causes that
 * matter differently to whoever asked: the policy declined, or it fired and found nothing to
 * remove. Returning zero for both would make those indistinguishable.</p>
 *
 * @param performed         Whether compaction ran at all
 * @param segmentsRewritten Segments read and replaced
 * @param segmentsProduced  Segments written in their place
 * @param documentsRemoved  Documents physically removed from disk
 * @param reason            Why it ran, or why it did not
 */
public record CompactionResult(boolean performed, int segmentsRewritten, int segmentsProduced,
                               int documentsRemoved, String reason) {

    public CompactionResult {
        Objects.requireNonNull(reason, "reason must not be null");
        if (segmentsRewritten < 0 || segmentsProduced < 0 || documentsRemoved < 0) {
            throw new IllegalArgumentException("compaction counts must not be negative");
        }
    }

    /** The policy did not consider compaction due. */
    public static CompactionResult declined(String reason) {
        return new CompactionResult(false, 0, 0, 0, reason);
    }
}
