package com.eonmux.excalibur.bench.retrieval;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The measures that decide whether a retrieval strategy is worth its cost.
 *
 * <h2>Why more than one</h2>
 * <p>Each answers a different question and each can be gamed alone. Recall asks whether the right
 * documents were reached at all, which is what a reranker downstream can still fix. Precision asks
 * how much of what came back was worth reading, which is what a token budget pays for. MRR asks
 * how far down the first right answer sat, which is what a user feels. nDCG is the only one that
 * accounts for graded relevance and for position together, and it is the one to compare strategies
 * on when they disagree.</p>
 *
 * <p>All of them are computed against a fixed set of judgements, so a strategy that retrieves
 * something genuinely relevant but unjudged is scored as if it were wrong. That is the standard
 * bargain of offline evaluation and worth remembering before treating a small gap as real.</p>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
        throw new AssertionError("Utility class");
    }

    /**
     * Fraction of the relevant documents that appear in the first {@code k}.
     *
     * @param retrieved Retrieved identifiers, best first
     * @param relevant  The judged-relevant identifiers
     * @param k         Cutoff
     * @return Recall in [0, 1], or 0 when nothing is relevant
     */
    public static double recallAt(List<String> retrieved, Collection<String> relevant, int k) {
        validate(retrieved, relevant, k);
        if (relevant.isEmpty()) {
            return 0.0;
        }
        Set<String> want = new HashSet<>(relevant);
        long hits = retrieved.stream().limit(k).distinct().filter(want::contains).count();
        return (double) hits / want.size();
    }

    /**
     * Fraction of the first {@code k} that are relevant.
     *
     * @param retrieved Retrieved identifiers, best first
     * @param relevant  The judged-relevant identifiers
     * @param k         Cutoff
     * @return Precision in [0, 1], measured against what was actually returned
     */
    public static double precisionAt(List<String> retrieved, Collection<String> relevant, int k) {
        validate(retrieved, relevant, k);
        List<String> head = retrieved.stream().limit(k).distinct().toList();
        if (head.isEmpty()) {
            return 0.0;
        }
        Set<String> want = new HashSet<>(relevant);
        long hits = head.stream().filter(want::contains).count();
        // Divided by what came back, not by k: a strategy that returns three results and gets all
        // three right is precise, and dividing by ten would report it as a failure.
        return (double) hits / head.size();
    }

    /**
     * Reciprocal of the rank of the first relevant document, or 0 when none is retrieved.
     *
     * @param retrieved Retrieved identifiers, best first
     * @param relevant  The judged-relevant identifiers
     * @return 1/rank, or 0
     */
    public static double reciprocalRank(List<String> retrieved, Collection<String> relevant) {
        validate(retrieved, relevant, 1);
        Set<String> want = new HashSet<>(relevant);
        List<String> unique = retrieved.stream().distinct().toList();
        for (int i = 0; i < unique.size(); i++) {
            if (want.contains(unique.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * Normalised discounted cumulative gain over binary judgements.
     *
     * <p>Normalised against the best ordering achievable for this query, so queries with different
     * numbers of relevant documents are comparable and can be averaged.</p>
     *
     * @param retrieved Retrieved identifiers, best first
     * @param relevant  The judged-relevant identifiers
     * @param k         Cutoff
     * @return nDCG in [0, 1]
     */
    public static double ndcgAt(List<String> retrieved, Collection<String> relevant, int k) {
        validate(retrieved, relevant, k);
        if (relevant.isEmpty()) {
            return 0.0;
        }
        Set<String> want = new HashSet<>(relevant);
        List<String> head = retrieved.stream().distinct().limit(k).toList();

        double dcg = 0.0;
        for (int i = 0; i < head.size(); i++) {
            if (want.contains(head.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        double ideal = 0.0;
        for (int i = 0; i < Math.min(k, want.size()); i++) {
            ideal += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return ideal == 0.0 ? 0.0 : dcg / ideal;
    }

    private static void validate(List<String> retrieved, Collection<String> relevant, int k) {
        Objects.requireNonNull(retrieved, "retrieved must not be null");
        Objects.requireNonNull(relevant, "relevant must not be null");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
    }
}
