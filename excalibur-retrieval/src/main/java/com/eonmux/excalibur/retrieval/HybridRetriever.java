package com.eonmux.excalibur.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.eonmux.excalibur.core.trace.QueryTrace;
import com.eonmux.excalibur.core.trace.Stage;
import com.eonmux.excalibur.core.util.QueryBudget;
import com.eonmux.excalibur.retrieval.fusion.FusedDocument;
import com.eonmux.excalibur.retrieval.fusion.FusionStrategy;
import com.eonmux.excalibur.retrieval.fusion.ReciprocalRankFusion;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs every retrieval signal and fuses their answers.
 *
 * <p>Adding a signal means adding a {@link Ranker} to this list. Nothing here knows what a signal
 * is or how it scores, and the fusion strategy reads only positions, so a new signal cannot
 * rescale, outweigh or interfere with the existing ones. That is the property that makes it
 * reasonable to try a signal and measure it, rather than committing to it.
 *
 * <p>Signals run concurrently because they are independent and each is dominated by waiting — on
 * the page cache, on a disk, or on a model. Running them one after another would make the query
 * cost their sum where it should cost their maximum.
 *
 * <h2>Partial failure</h2>
 * A signal that fails contributes nothing and the query continues on the rest, because a degraded
 * answer from three signals is worth more than no answer at all. It is logged at warning rather
 * than swallowed, and if <em>every</em> signal fails the query fails — at that point there is no
 * answer, and returning an empty result would present a total outage as "nothing matched".
 */
public final class HybridRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(HybridRetriever.class);

    private final List<Ranker> rankers;
    private final FusionStrategy fusion;
    private final int perSignalOverFetch;

    /**
     * How many results to ask each signal for, relative to the final limit.
     *
     * <p>Fusion can only rank what it is given. A document that one signal places at position 30
     * and another at 25 may well be the best answer once both are counted, but only if both lists
     * ran deep enough to contain it. Asking each signal for exactly the final limit would discard
     * that agreement before it could be observed.
     *
     * <p>Deeper costs every signal proportionally more work, so a deployment sizes it with
     * {@code excalibur.query.per-signal-over-fetch} and hands the value to the three-argument
     * constructor.
     */
    public static final int DEFAULT_PER_SIGNAL_OVER_FETCH = 3;

    /**
     * A retriever over {@code rankers} at the defaults.
     *
     * <p>Fuses with reciprocal rank fusion at its own default k. A deployment that has configured
     * either k or the over-fetch wants the three-argument constructor: this one cannot see those
     * values and would silently ignore them.
     */
    public HybridRetriever(List<Ranker> rankers) {
        this(rankers, new ReciprocalRankFusion(), DEFAULT_PER_SIGNAL_OVER_FETCH);
    }

    /**
     * @param rankers            The signals to run, each with a distinct name
     * @param fusion             How their answers are combined, carrying its own configuration
     * @param perSignalOverFetch Multiplies the final limit to give each signal's depth, from
     *                           {@code excalibur.query.per-signal-over-fetch}
     */
    public HybridRetriever(List<Ranker> rankers, FusionStrategy fusion, int perSignalOverFetch) {
        if (rankers == null || rankers.isEmpty()) {
            throw new IllegalArgumentException("at least one ranker is required");
        }
        if (fusion == null) {
            throw new IllegalArgumentException("fusion must not be null");
        }
        if (perSignalOverFetch < 1) {
            throw new IllegalArgumentException(
                "perSignalOverFetch must be at least 1, got " + perSignalOverFetch);
        }
        List<String> names = rankers.stream().map(Ranker::name).distinct().toList();
        if (names.size() != rankers.size()) {
            // Two signals sharing a name would each contribute to fusion while being
            // indistinguishable in the breakdown, so a result could not be explained.
            throw new IllegalArgumentException(
                "ranker names must be distinct; got " + rankers.stream().map(Ranker::name).toList());
        }
        this.rankers = List.copyOf(rankers);
        this.fusion = fusion;
        this.perSignalOverFetch = perSignalOverFetch;
    }

    /** The signals in use. */
    public List<Ranker> rankers() {
        return rankers;
    }

    /** The fusion strategy in use. */
    public FusionStrategy fusion() {
        return fusion;
    }

    /** Retrieves without recording timings. */
    public List<FusedDocument> retrieve(RetrievalQuery query, int limit) {
        return retrieve(query, limit, new QueryTrace());
    }

    /**
     * Runs every signal and fuses the results.
     *
     * @param trace receives the time spent ranking; never null
     * @return fused documents, best first, at most {@code limit}
     */
    public List<FusedDocument> retrieve(RetrievalQuery query, int limit, QueryTrace trace) {
        return retrieve(query, limit, trace, QueryBudget.unbounded());
    }

    /**
     * Runs every signal within what is left of a query's time, and fuses the results.
     *
     * <p>The budget is handed to each signal, which is what bounds a model call inside one; a
     * signal that runs out of time contributes the ranking it had and marks the budget timed
     * out. A cancelled query ends here rather than being counted as signals that failed.</p>
     *
     * @param trace  receives the time spent ranking; never null
     * @param budget the query's deadline and cancellation; never null
     * @return fused documents, best first, at most {@code limit}
     * @throws java.util.concurrent.CancellationException if the query was cancelled
     */
    public List<FusedDocument> retrieve(RetrievalQuery query, int limit, QueryTrace trace,
                                        QueryBudget budget) {
        if (budget == null) {
            throw new IllegalArgumentException("budget must not be null");
        }
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (trace == null) {
            throw new IllegalArgumentException("trace must not be null");
        }
        if (limit <= 0) {
            return List.of();
        }
        return trace.time(Stage.RANK, () -> {
            List<RankedList> lists = runSignals(query, overFetch(limit), budget);
            return fusion.fuse(lists, limit);
        });
    }

    private List<RankedList> runSignals(RetrievalQuery query, int perSignalLimit,
                                        QueryBudget budget) {
        budget.throwIfCancelled();
        List<Future<RankedList>> futures = new ArrayList<>(rankers.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Ranker ranker : rankers) {
                futures.add(executor.submit(() -> ranker.rank(query, perSignalLimit, budget)));
            }
        }

        List<RankedList> lists = new ArrayList<>(rankers.size());
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Ranker ranker = rankers.get(i);
            try {
                RankedList list = futures.get(i).get();
                if (list != null) {
                    lists.add(list);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while running retrieval signals", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof CancellationException cancelled) {
                    throw cancelled;
                }
                LOGGER.warn("retrieval signal {} failed and will contribute nothing",
                    ranker.name(), e.getCause());
                failed.add(ranker.name());
            }
        }

        budget.throwIfCancelled();
        if (lists.isEmpty()) {
            throw new IllegalStateException(
                "every retrieval signal failed: " + failed
                    + "; returning no results would report a total outage as an empty match");
        }
        return lists;
    }

    private int overFetch(int limit) {
        long requested = (long) limit * perSignalOverFetch;
        return requested > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) requested;
    }
}
