package com.eonmux.excalibur.retrieval.fusion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

/**
 * Reciprocal rank fusion: {@code score(d) = sum over signals of 1 / (k + rank(d))}.
 *
 * <p>Reads position, never magnitude. That is the entire reason to prefer it here: a BM25 score of
 * 14.2 and a cosine similarity of 0.83 have no common scale, and any attempt to give them one —
 * min-max normalisation, z-scores, hand-tuned weights — is a calibration that silently goes stale
 * as the corpus changes. Rank is comparable across signals by construction, so nothing needs
 * calibrating and adding a signal cannot rescale the others.
 *
 * <p>A document contributes from each signal that returned it, and nothing from the signals that
 * did not. So a document that several independent signals rank highly beats one that a single
 * signal loves, which is the behaviour hybrid retrieval exists to get.
 *
 * <p>The constant {@code k} flattens the top of each list. With {@code k = 60} the difference
 * between rank 1 and rank 2 is about 1.6%, where without it the first result would be worth twice
 * the second. That matters because the exact order within a signal's top few is mostly noise —
 * what carries information is that a document is near the top at all, and agreement between
 * signals. A small {@code k} would let one signal's arbitrary first choice dominate the fusion.
 */
public final class ReciprocalRankFusion implements FusionStrategy {

    /**
     * The value from the original formulation, and the right default.
     *
     * <p>Not a tuning knob to reach for first: it trades off how sharply top ranks are rewarded,
     * and moving it without measuring recall on a real query set usually makes things worse.
     * A deployment that has measured sets it with {@code excalibur.query.rrf-k} and passes the
     * configured strategy where the fusion is used, rather than relying on this.
     */
    public static final int DEFAULT_K = 60;

    private final int k;

    /** Fusion at {@link #DEFAULT_K}. */
    public ReciprocalRankFusion() {
        this(DEFAULT_K);
    }

    /**
     * Fusion at a chosen {@code k}.
     *
     * @param k rank damping constant; must be positive, since {@code k = 0} makes rank 1 worth
     *          twice rank 2 and a negative {@code k} can divide by zero
     */
    public ReciprocalRankFusion(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException(
                "k must be positive, got " + k
                    + "; at or below zero the reciprocal is undefined or degenerate");
        }
        this.k = k;
    }

    /** The rank damping constant in use. */
    public int k() {
        return k;
    }

    @Override
    public String name() {
        return "rrf(k=" + k + ")";
    }

    @Override
    public List<FusedDocument> fuse(List<RankedList> lists, int limit) {
        if (lists == null) {
            throw new IllegalArgumentException("lists must not be null");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative, got " + limit);
        }
        if (lists.isEmpty() || limit == 0) {
            return List.of();
        }

        Map<Long, Accumulator> byDocument = new HashMap<>();
        for (RankedList list : lists) {
            List<ScoredDocument> documents = list.documents();
            for (int position = 0; position < documents.size(); position++) {
                int rank = position + 1;
                double contribution = 1.0 / (k + rank);
                long documentId = documents.get(position).documentId();
                byDocument
                    .computeIfAbsent(documentId, id -> new Accumulator())
                    .add(list.rankerName(), rank, contribution);
            }
        }

        List<FusedDocument> fused = new ArrayList<>(byDocument.size());
        for (Map.Entry<Long, Accumulator> entry : byDocument.entrySet()) {
            fused.add(entry.getValue().toFusedDocument(entry.getKey()));
        }
        Collections.sort(fused);
        return fused.size() > limit ? List.copyOf(fused.subList(0, limit)) : List.copyOf(fused);
    }

    /**
     * Running total for one document.
     *
     * <p>Accumulated in a map keyed by document rather than by scanning each list per candidate,
     * so fusing {@code s} signals of {@code n} results each costs one pass over {@code s * n}
     * instead of {@code s * n * s}.
     */
    private static final class Accumulator {
        private final List<SignalContribution> contributions = new ArrayList<>(4);
        private double score;

        void add(String rankerName, int rank, double contribution) {
            // A signal that returns the same document twice would otherwise count twice. Keep the
            // better rank, which is what the signal actually asserted about the document.
            for (int i = 0; i < contributions.size(); i++) {
                SignalContribution existing = contributions.get(i);
                if (existing.rankerName().equals(rankerName)) {
                    if (rank < existing.rank()) {
                        score += contribution - existing.contribution();
                        contributions.set(i, new SignalContribution(rankerName, rank, contribution));
                    }
                    return;
                }
            }
            contributions.add(new SignalContribution(rankerName, rank, contribution));
            score += contribution;
        }

        FusedDocument toFusedDocument(long documentId) {
            List<SignalContribution> ordered = new ArrayList<>(contributions);
            ordered.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
            return new FusedDocument(documentId, score, ordered);
        }
    }
}
