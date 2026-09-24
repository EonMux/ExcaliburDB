package com.eonmux.excalibur.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;

import com.eonmux.excalibur.core.trace.QueryTrace;
import com.eonmux.excalibur.core.util.QueryBudget;
import com.eonmux.excalibur.retrieval.fusion.FusedDocument;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How retrieval carries a query's budget to its signals. */
class HybridRetrieverBudgetTest {

    private static final RetrievalQuery QUERY = RetrievalQuery.ofText("anything");

    /** Ranks two documents, recording each budget it is handed. */
    private record RecordingRanker(String name, List<QueryBudget> seen) implements Ranker {
        @Override
        public RankedList rank(RetrievalQuery query, int limit) {
            List<ScoredDocument> documents = new ArrayList<>();
            documents.add(new ScoredDocument(1, 2));
            documents.add(new ScoredDocument(2, 1));
            return RankedList.of(name, documents, limit);
        }

        @Override
        public RankedList rank(RetrievalQuery query, int limit, QueryBudget budget) {
            seen.add(budget);
            return rank(query, limit);
        }
    }

    /** A signal whose query is cancelled while it ranks. */
    private record CancellingRanker(QueryBudget budget) implements Ranker {
        @Override
        public String name() {
            return "cancelling";
        }

        @Override
        public RankedList rank(RetrievalQuery query, int limit) {
            throw new AssertionError("the budgeted overload is the one retrieval calls");
        }

        @Override
        public RankedList rank(RetrievalQuery query, int limit, QueryBudget handed) {
            budget.cancel("Withdrawn by the caller");
            handed.throwIfCancelled();
            return RankedList.empty(name());
        }
    }

    @Test
    @DisplayName("every signal is handed the query's budget")
    void budgetReachesEverySignal() {
        List<QueryBudget> seen = new CopyOnWriteArrayList<>();
        HybridRetriever retriever = new HybridRetriever(List.of(
            new RecordingRanker("bm25", seen), new RecordingRanker("dense", seen)));
        QueryBudget budget = QueryBudget.ofMillis(10_000);

        List<FusedDocument> results = retriever.retrieve(QUERY, 10, new QueryTrace(), budget);

        assertThat(results).isNotEmpty();
        assertThat(seen).hasSize(2).allMatch(handed -> handed == budget);
    }

    @Test
    @DisplayName("a query without a budget runs its signals unbounded")
    void noBudgetMeansUnbounded() {
        List<QueryBudget> seen = new CopyOnWriteArrayList<>();
        new HybridRetriever(List.of(new RecordingRanker("bm25", seen)))
            .retrieve(QUERY, 10, new QueryTrace());

        assertThat(seen).singleElement().matches(budget -> !budget.isBounded());
    }

    @Test
    @DisplayName("a signal ended by cancellation ends retrieval rather than counting as a failure")
    void cancellationInsideASignalPropagates() {
        QueryBudget budget = QueryBudget.ofMillis(10_000);
        HybridRetriever retriever = new HybridRetriever(List.of(
            new CancellingRanker(budget), new RecordingRanker("bm25", new ArrayList<>())));

        assertThatThrownBy(() -> retriever.retrieve(QUERY, 10, new QueryTrace(), budget))
            .isInstanceOf(CancellationException.class)
            .hasMessageContaining("Withdrawn by the caller");
    }

    @Test
    @DisplayName("a query cancelled before retrieval starts runs no signal")
    void cancelledBeforeRetrieval() {
        List<QueryBudget> seen = new CopyOnWriteArrayList<>();
        QueryBudget budget = QueryBudget.ofMillis(10_000);
        budget.cancel("Withdrawn by the caller");

        assertThatThrownBy(() -> new HybridRetriever(List.of(new RecordingRanker("bm25", seen)))
            .retrieve(QUERY, 10, new QueryTrace(), budget))
            .isInstanceOf(CancellationException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("a missing budget is refused")
    void budgetIsRequired() {
        HybridRetriever retriever =
            new HybridRetriever(List.of(new RecordingRanker("bm25", new ArrayList<>())));

        assertThatThrownBy(() -> retriever.retrieve(QUERY, 10, new QueryTrace(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
