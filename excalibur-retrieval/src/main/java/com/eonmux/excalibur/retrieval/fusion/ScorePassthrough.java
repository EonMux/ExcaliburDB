package com.eonmux.excalibur.retrieval.fusion;

import java.util.ArrayList;
import java.util.List;

import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

/**
 * Hands one signal's own scores through, in its own order, instead of fusing them.
 *
 * <p>For a retriever that has exactly one signal and whose caller reads the scores as evidence.
 * Reciprocal rank fusion over a single list is that list's order with every magnitude replaced by
 * {@code 1 / (k + rank)}: the top score is always {@code 1 / (k + 1)} and the gap to the second
 * always about the same fraction of it, whatever the signal found. A threshold on those numbers
 * judges the constant. Passed through, a strong match and a weak one stay as different as the
 * signal said they were.
 *
 * <p>Not for a retriever whose callers rank rather than threshold, such as {@code /v1/query}: there
 * the scale an answer reports must not depend on how many signals a node happens to have.
 *
 * <p>More than one list is refused rather than fused. Signals with no common scale cannot be passed
 * through side by side, and silently keeping one of them would drop evidence.
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and safe for concurrent use.</p>
 */
public final class ScorePassthrough implements FusionStrategy {

    @Override
    public String name() {
        return "passthrough";
    }

    /**
     * @param lists the one signal that ran; empty when it contributed nothing
     * @param limit maximum documents to return
     * @return the signal's documents as fused documents, best first, each scored as the signal
     *         scored it
     * @throws IllegalArgumentException if more than one signal ran, or the limit is negative
     */
    @Override
    public List<FusedDocument> fuse(List<RankedList> lists, int limit) {
        if (lists == null) {
            throw new IllegalArgumentException("lists must not be null");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative, got " + limit);
        }
        if (lists.size() > 1) {
            throw new IllegalArgumentException(
                "passthrough hands one signal through; got " + lists.size());
        }
        if (lists.isEmpty() || limit == 0) {
            return List.of();
        }
        RankedList list = lists.get(0);
        List<ScoredDocument> documents = list.documents();
        int kept = Math.min(limit, documents.size());
        List<FusedDocument> fused = new ArrayList<>(kept);
        for (int position = 0; position < kept; position++) {
            ScoredDocument document = documents.get(position);
            SignalContribution own =
                new SignalContribution(list.rankerName(), position + 1, document.score());
            fused.add(new FusedDocument(document.documentId(), document.score(), List.of(own)));
        }
        return List.copyOf(fused);
    }

    @Override
    public String toString() {
        return name();
    }
}
