package excalibur.bench.retrieval;

import java.util.Objects;

/**
 * What one strategy scored, and what it cost.
 *
 * @param strategy    The configuration measured
 * @param queries     Queries evaluated
 * @param recallAtK   Mean recall at the cutoff
 * @param precisionAtK Mean precision at the cutoff
 * @param mrr         Mean reciprocal rank
 * @param ndcgAtK     Mean nDCG at the cutoff
 * @param medianLatencyMs Median end-to-end query latency
 * @param p95LatencyMs    95th percentile query latency
 * @param skippedReason   Why the strategy could not run, or null when it did
 */
public record StrategyResult(RetrievalStrategy strategy, int queries, double recallAtK,
                             double precisionAtK, double mrr, double ndcgAtK,
                             double medianLatencyMs, double p95LatencyMs, String skippedReason) {

    public StrategyResult {
        Objects.requireNonNull(strategy, "strategy must not be null");
    }

    /** A strategy this deployment could not measure, with the reason it could not. */
    public static StrategyResult skipped(RetrievalStrategy strategy, String reason) {
        return new StrategyResult(strategy, 0, 0, 0, 0, 0, 0, 0,
            Objects.requireNonNull(reason, "reason must not be null"));
    }

    public boolean ran() {
        return skippedReason == null;
    }

    /** One aligned row, for a report a human reads. */
    public String toRow() {
        if (!ran()) {
            return String.format("  %-28s %s", strategy, "skipped: " + skippedReason);
        }
        return String.format("  %-28s %7.4f %9.4f %7.4f %8.4f %9.1f %8.1f",
            strategy, recallAtK, precisionAtK, mrr, ndcgAtK, medianLatencyMs, p95LatencyMs);
    }

    /** The header {@link #toRow()} aligns to. */
    public static String header() {
        return String.format("  %-28s %7s %9s %7s %8s %9s %8s",
            "strategy", "recall", "precision", "MRR", "nDCG", "med ms", "p95 ms");
    }
}
