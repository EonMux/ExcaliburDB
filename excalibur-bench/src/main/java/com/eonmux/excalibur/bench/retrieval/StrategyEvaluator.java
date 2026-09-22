package com.eonmux.excalibur.bench.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs a set of queries through each retrieval strategy and scores the results.
 *
 * <h2>What it is measuring</h2>
 * <p>Whether moving semantic work to the query buys the same recall as moving it into the corpus.
 * The strategies that skip {@link RetrievalStrategy#usesVector()} need no embedding pass at all, so
 * a result showing them close to the vector strategies is not a tie - it is a large win, because
 * the costs on the two sides differ by orders of magnitude. This class deliberately reports latency
 * beside quality for that reason: a quality number with no cost beside it cannot answer the
 * question that prompted the comparison.</p>
 *
 * <h2>Strategies that cannot run</h2>
 * <p>A deployment without embeddings cannot measure the vector strategies, and one without a graph
 * cannot measure traversal. Those are reported as skipped with the reason rather than as zero: a
 * zero would average into a comparison and quietly make the available strategies look better than
 * they are.</p>
 */
public final class StrategyEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger(StrategyEvaluator.class);

    /** One judged query: the text, and the chunk ids that genuinely answer it. */
    public record Judged(String query, Collection<String> relevant) {
        public Judged {
            Objects.requireNonNull(query, "query must not be null");
            Objects.requireNonNull(relevant, "relevant must not be null");
        }
    }

    /** A strategy's retrieval function, or null when this deployment cannot run it. */
    @FunctionalInterface
    public interface Retrieval {
        List<String> search(String query, int limit);
    }

    private final List<Judged> queries;
    private final int k;

    public StrategyEvaluator(List<Judged> queries, int k) {
        this.queries = List.copyOf(Objects.requireNonNull(queries, "queries must not be null"));
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        this.k = k;
    }

    /**
     * Evaluates every strategy for which a retrieval function is supplied.
     *
     * @param retrievals Strategy to its retrieval function; a missing or null entry is skipped
     * @return One result per strategy, in declaration order
     */
    public List<StrategyResult> evaluateAll(Map<RetrievalStrategy, Retrieval> retrievals) {
        return evaluateAll(retrievals, Map.of());
    }

    /**
     * Evaluates every strategy for which a retrieval function is supplied, saying why the others
     * were not.
     *
     * <p>The default reason is that nothing supplied one, which is true and rarely the useful
     * answer: a strategy the engine under test structurally cannot run is a different fact from one
     * this deployment happens not to have wired, and a reader comparing two engines' tables needs
     * to be able to tell them apart.</p>
     *
     * @param retrievals Strategy to its retrieval function; a missing or null entry is skipped
     * @param reasons    Why a particular strategy has no retrieval function, where that is known
     * @return One result per strategy, in declaration order
     */
    public List<StrategyResult> evaluateAll(Map<RetrievalStrategy, Retrieval> retrievals,
                                            Map<RetrievalStrategy, String> reasons) {
        Objects.requireNonNull(retrievals, "retrievals must not be null");
        Objects.requireNonNull(reasons, "reasons must not be null");
        Map<RetrievalStrategy, StrategyResult> out = new EnumMap<>(RetrievalStrategy.class);
        for (RetrievalStrategy strategy : RetrievalStrategy.values()) {
            Retrieval retrieval = retrievals.get(strategy);
            if (retrieval == null) {
                String reason = reasons.get(strategy);
                out.put(strategy, StrategyResult.skipped(strategy, reason != null ? reason
                    : strategy.usesVector()
                        ? "no embedded corpus on this deployment"
                        : "no retrieval function supplied"));
                continue;
            }
            out.put(strategy, evaluate(strategy, retrieval));
        }
        return Arrays.stream(RetrievalStrategy.values()).map(out::get).toList();
    }

    /** Evaluates one strategy. */
    public StrategyResult evaluate(RetrievalStrategy strategy, Retrieval retrieval) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(retrieval, "retrieval must not be null");
        if (queries.isEmpty()) {
            return StrategyResult.skipped(strategy, "no judged queries");
        }

        double recall = 0;
        double precision = 0;
        double mrr = 0;
        double ndcg = 0;
        List<Double> latencies = new ArrayList<>(queries.size());

        for (Judged judged : queries) {
            long start = System.nanoTime();
            List<String> hits;
            try {
                hits = retrieval.search(judged.query(), k);
            } catch (RuntimeException e) {
                // One query failing is a data point, not a reason to abandon the comparison.
                LOGGER.warn("{} failed on '{}': {}", strategy, judged.query(), e.toString());
                hits = List.of();
            }
            latencies.add((System.nanoTime() - start) / 1_000_000.0);
            hits = hits == null ? List.of() : hits;

            recall += RetrievalMetrics.recallAt(hits, judged.relevant(), k);
            precision += RetrievalMetrics.precisionAt(hits, judged.relevant(), k);
            mrr += RetrievalMetrics.reciprocalRank(hits, judged.relevant());
            ndcg += RetrievalMetrics.ndcgAt(hits, judged.relevant(), k);
        }

        int n = queries.size();
        return new StrategyResult(strategy, n, recall / n, precision / n, mrr / n, ndcg / n,
            percentile(latencies, 50), percentile(latencies, 95), null);
    }

    /** Renders a comparison a human can read. */
    public static String report(List<StrategyResult> results, int k) {
        return report(results, k, null);
    }

    /**
     * Renders a comparison, naming the engine that produced it.
     *
     * <p>The engine belongs in the table's own heading rather than only in the surrounding output.
     * These tables get pasted somewhere on their own, and a quality number with no engine beside it
     * is a number about an unnamed system - which is the same problem as reporting quality with no
     * cost beside it.</p>
     *
     * @param engine Which lexical engine answered, or null when the comparison is not engine-bound
     */
    public static String report(List<StrategyResult> results, int k, String engine) {
        StringBuilder sb = new StringBuilder();
        sb.append("Retrieval strategy comparison (k=").append(k);
        if (engine != null) {
            sb.append(", engine=").append(engine);
        }
        sb.append(")\n");
        sb.append(StrategyResult.header()).append('\n');
        for (StrategyResult result : results) {
            sb.append(result.toRow()).append('\n');
        }
        sb.append("\n  Strategies without a vector signal need no corpus-wide embedding pass.\n");
        return sb.toString();
    }

    private static double percentile(List<Double> values, int percentile) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}
