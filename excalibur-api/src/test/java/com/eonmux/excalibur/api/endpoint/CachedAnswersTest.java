package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.HybridFusion;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two answers the query cache holds besides the pipeline's own: when each may be replayed,
 * what it forgets before it is stored, and what total it can stand behind.
 */
@DisplayName("Cached lexical and fused answers")
class CachedAnswersTest {

    /** Lexical coverage of a search that reached the whole index: degraded by construction. */
    private static final IndexCoverage LEXICAL_FULL = IndexCoverage.builder()
        .totalSegments(2).activeSegments(2).segmentsSearched(2)
        .estimatedCorpusCoverage(1.0).degraded(true).build();

    private static final IndexCoverage VECTOR_FULL = IndexCoverage.builder()
        .totalSegments(2).activeSegments(2).segmentsSearched(2)
        .estimatedCorpusCoverage(1.0).degraded(false).build();

    private static final List<LexicalQueryService.Hit> TWO =
        List.of(new LexicalQueryService.Hit("a", 0.9), new LexicalQueryService.Hit("b", 0.4));

    private static LexicalResponse lexical(IndexCoverage coverage, boolean timedOut,
                                           boolean cancelled, boolean fellBack) {
        return new LexicalResponse("q-1", TWO, true, coverage, timedOut, cancelled, false,
            fellBack, 5L, false);
    }

    @Nested
    @DisplayName("a BM25 answer")
    class Lexical {

        @Test
        @DisplayName("may be replayed although its coverage says degraded, which on this route "
            + "is a fact about the node")
        void cacheableDespiteStructuralDegradation() {
            assertThat(lexical(LEXICAL_FULL, false, false, false).cacheable()).isTrue();
        }

        @Test
        @DisplayName("may not be replayed when it timed out, was withdrawn, or its scorer fell "
            + "back")
        void notCacheableAfterTrouble() {
            assertThat(lexical(LEXICAL_FULL, true, false, false).cacheable()).isFalse();
            assertThat(lexical(LEXICAL_FULL, false, true, false).cacheable()).isFalse();
            assertThat(lexical(LEXICAL_FULL, false, false, true).cacheable()).isFalse();
        }

        @Test
        @DisplayName("may not be replayed when it did not reach the whole index")
        void notCacheableOverAnEmptyIndex() {
            IndexCoverage nothing = IndexCoverage.builder()
                .estimatedCorpusCoverage(0.0).degraded(true).build();

            assertThat(lexical(nothing, false, false, false).cacheable()).isFalse();
        }

        @Test
        @DisplayName("forgets the query id it was computed under, and says so when replayed")
        void storageAndReplay() {
            LexicalResponse stored = lexical(LEXICAL_FULL, false, false, false).forStorage();
            LexicalResponse replayed = stored.servedFromCache().withQueryId("q-2")
                .withQueryTimeMs(1L);

            assertThat(stored.queryId()).isNull();
            assertThat(stored.fromCache()).isFalse();
            assertThat(replayed.fromCache()).isTrue();
            assertThat(replayed.queryId()).isEqualTo("q-2");
            assertThat(replayed.queryTimeMs()).isEqualTo(1L);
            assertThat(replayed.hits()).isEqualTo(TWO);
        }

        @Test
        @DisplayName("gives an exact total only when retrieval ran out of candidates")
        void exactOnlyWhenExhausted() {
            LexicalResponse exhausted = new LexicalResponse(null, TWO, true, LEXICAL_FULL,
                false, false, true, false, 1L, false);
            LexicalResponse cut = new LexicalResponse(null, TWO, false, LEXICAL_FULL,
                false, false, true, false, 1L, false);

            assertThat(exhausted.total(10).relation()).isEqualTo(ResultTotal.EXACT);
            assertThat(cut.total(10).relation())
                .as("a threshold that chose from a full candidate set says nothing past it")
                .isEqualTo(ResultTotal.AT_LEAST);
        }

        @Test
        @DisplayName("requires a coverage block")
        void requiresCoverage() {
            assertThatThrownBy(() -> new LexicalResponse(null, TWO, true, null, false, false,
                false, false, 1L, false)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("a fused answer")
    class Fused {

        private final QueryResponse vector = new QueryResponse("q-1",
            List.of(new QueryResultItem("a", 0.9f, null, 1L, 1L)), VECTOR_FULL, 5L, false,
            false);
        private final List<HybridFusion.Result> fused =
            List.of(new HybridFusion.Result("a", 0.03, 1L, List.of("vector", "bm25")));

        private HybridResponse answer(boolean fellBack, boolean timedOut) {
            return new HybridResponse(vector, TWO, fused, true, true, fellBack, timedOut);
        }

        @Test
        @DisplayName("may be replayed when the vector search was whole and scoring finished")
        void cacheableWhenWhole() {
            assertThat(answer(false, false).cacheable()).isTrue();
            assertThat(answer(false, false).coverage().degraded()).isFalse();
        }

        @Test
        @DisplayName("is degraded, and not replayed, when scoring after fusion fell back")
        void degradedWhenScoringFellBack() {
            HybridResponse fellBack = answer(true, false);

            assertThat(fellBack.coverage().degraded()).isTrue();
            assertThat(fellBack.cacheable()).isFalse();
        }

        @Test
        @DisplayName("reports a timeout, and is not replayed, when scoring ran out of time")
        void timedOutWhenScoringWasTooSlow() {
            HybridResponse slow = answer(false, true);

            assertThat(slow.timedOut()).isTrue();
            assertThat(slow.coverage().degraded()).isTrue();
            assertThat(slow.cacheable()).isFalse();
            assertThat(slow.total(10).relation()).isEqualTo(ResultTotal.AT_LEAST);
        }

        @Test
        @DisplayName("may not be replayed when its vector half is degraded")
        void notCacheableOverADegradedVectorSearch() {
            QueryResponse partial = vector.withCoverage(VECTOR_FULL.orDegraded(true));

            assertThat(new HybridResponse(partial, TWO, fused, true, false, false, false)
                .cacheable()).isFalse();
        }

        @Test
        @DisplayName("forgets the query id it was computed under, and says so when replayed")
        void storageAndReplay() {
            HybridResponse stored = answer(false, false).forStorage();
            HybridResponse replayed = stored.servedFromCache().withQueryId("q-9")
                .withQueryTimeMs(2L);

            assertThat(stored.vector().queryId()).isNull();
            assertThat(replayed.vector().fromCache()).isTrue();
            assertThat(replayed.vector().queryId()).isEqualTo("q-9");
            assertThat(replayed.vector().queryTimeMs()).isEqualTo(2L);
            assertThat(replayed.fused()).isEqualTo(fused);
            assertThat(replayed.lexical()).isEqualTo(TWO);
            assertThat(replayed.llmScored()).isTrue();
        }

        @Test
        @DisplayName("an unscored fusion shorter than asked for is an exact total")
        void unscoredShortFusionIsExact() {
            HybridResponse unscored = HybridResponse.unscored(vector, TWO, fused, 10);

            assertThat(unscored.total(10).relation()).isEqualTo(ResultTotal.EXACT);
            assertThat(unscored.llmScored()).isFalse();
        }
    }
}
