package io.excalibur.retrieval.rank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One ranker's ordered answer.
 *
 * <p>Carries the ranker's name alongside the results so that a fused result can be explained —
 * which signal contributed a document, and at what position — and so that per-stage latency can be
 * attributed to the signal that spent it. A bare list would lose both.
 *
 * <p>Immutable, and ordered best first. Rank is position, one-based, because reciprocal rank fusion
 * divides by it and a zero-based rank would make the first result's contribution infinite.
 */
public record RankedList(String rankerName, List<ScoredDocument> documents) {

    public RankedList {
        if (rankerName == null || rankerName.isBlank()) {
            throw new IllegalArgumentException("rankerName must not be blank");
        }
        if (documents == null) {
            throw new IllegalArgumentException("documents must not be null; use an empty list");
        }
        documents = List.copyOf(documents);
    }

    /** An empty result: this signal ran and found nothing. */
    public static RankedList empty(String rankerName) {
        return new RankedList(rankerName, List.of());
    }

    /**
     * Sorts {@code documents} best first and takes at most {@code limit}.
     *
     * <p>The convenience most rankers want: they produce scores in whatever order their index
     * yields and need the ordering imposed once, consistently, including the deterministic tie
     * break.
     */
    public static RankedList of(String rankerName, List<ScoredDocument> documents, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative, got " + limit);
        }
        List<ScoredDocument> sorted = new ArrayList<>(documents);
        Collections.sort(sorted);
        if (sorted.size() > limit) {
            sorted = sorted.subList(0, limit);
        }
        return new RankedList(rankerName, sorted);
    }

    /** How many results this signal returned. */
    public int size() {
        return documents.size();
    }

    /** Whether this signal found nothing. */
    public boolean isEmpty() {
        return documents.isEmpty();
    }

    /**
     * One-based position of {@code documentId}, or {@code 0} if this signal did not return it.
     *
     * <p>Linear, which is correct for the list sizes fusion works with — a few hundred candidates
     * per signal. A caller fusing many signals over long lists should build an index once instead
     * of calling this per document, which is what {@code ReciprocalRankFusion} does.
     */
    public int rankOf(long documentId) {
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i).documentId() == documentId) {
                return i + 1;
            }
        }
        return 0;
    }
}
