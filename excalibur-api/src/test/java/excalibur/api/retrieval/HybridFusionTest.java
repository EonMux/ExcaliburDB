package excalibur.api.retrieval;

import excalibur.query.QueryResultItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What fusing the two retrieval signals on {@code /v1/query} has to get right.
 */
@DisplayName("HybridFusion")
class HybridFusionTest {

    private static QueryResultItem dense(String documentId, float score, long segmentId) {
        return new QueryResultItem(documentId, score, null, segmentId, 0L);
    }

    private static LexicalQueryService.Hit lexical(String chunkId, double score) {
        return new LexicalQueryService.Hit(chunkId, score);
    }

    private static List<String> idsOf(List<HybridFusion.Result> results) {
        return results.stream().map(HybridFusion.Result::documentId).toList();
    }

    @Nested
    @DisplayName("Ordering")
    class Ordering {

        @Test
        @DisplayName("a document both signals returned outranks one only vector search found")
        void agreementWins() {
            List<HybridFusion.Result> fused = HybridFusion.fuse(
                List.of(dense("a", 0.9f, 1), dense("b", 0.8f, 1)),
                List.of(lexical("b", 14.2)),
                10);

            assertThat(idsOf(fused))
                .as("agreement between independent signals is the point of fusing them")
                .containsExactly("b", "a");
        }

        @Test
        @DisplayName("keeps the dense ordering when the lexical signal found nothing")
        void noLexicalHitsKeepsDenseOrder() {
            List<HybridFusion.Result> fused = HybridFusion.fuse(
                List.of(dense("a", 0.9f, 1), dense("b", 0.8f, 1), dense("c", 0.7f, 2)),
                List.of(),
                10);

            assertThat(idsOf(fused)).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("returns a match only BM25 found")
        void lexicalOnlyMatchSurvives() {
            List<HybridFusion.Result> fused = HybridFusion.fuse(
                List.of(dense("a", 0.9f, 1)),
                List.of(lexical("CVE-2021-44228", 31.7)),
                10);

            assertThat(idsOf(fused))
                .as("an exact term a dense vector blurs is the reason the BM25 signal is there")
                .contains("CVE-2021-44228");
        }

        @Test
        @DisplayName("counts a document one signal returned twice only once")
        void duplicatesWithinASignalCountOnce() {
            List<HybridFusion.Result> fused = HybridFusion.fuse(
                List.of(dense("a", 0.9f, 1), dense("a", 0.4f, 2)),
                List.of(),
                10);

            assertThat(idsOf(fused)).containsExactly("a");
        }

        @Test
        @DisplayName("returns at most the requested number of results")
        void respectsTheLimit() {
            List<HybridFusion.Result> fused = HybridFusion.fuse(
                List.of(dense("a", 0.9f, 1), dense("b", 0.8f, 1), dense("c", 0.7f, 1)),
                List.of(lexical("d", 9.0), lexical("e", 8.0)),
                2);

            assertThat(fused).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Attribution")
    class Attribution {

        @Test
        @DisplayName("names the signals that returned each result")
        void namesTheContributingSignals() {
            List<HybridFusion.Result> fused = HybridFusion.fuse(
                List.of(dense("a", 0.9f, 1), dense("b", 0.8f, 1)),
                List.of(lexical("b", 14.2), lexical("z", 3.0)),
                10);

            assertThat(fused).filteredOn(r -> r.documentId().equals("b")).singleElement()
                .extracting(HybridFusion.Result::signals, org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactlyInAnyOrder(HybridFusion.DENSE_SIGNAL, HybridFusion.LEXICAL_SIGNAL);
            assertThat(fused).filteredOn(r -> r.documentId().equals("z")).singleElement()
                .extracting(HybridFusion.Result::signals, org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly(HybridFusion.LEXICAL_SIGNAL);
        }

        @Test
        @DisplayName("carries the vector segment for a result vector search found, and none for "
            + "one only BM25 found")
        void segmentTravelsWithTheDenseHit() {
            List<HybridFusion.Result> fused = HybridFusion.fuse(
                List.of(dense("a", 0.9f, 7)),
                List.of(lexical("z", 3.0)),
                10);

            assertThat(fused).filteredOn(r -> r.documentId().equals("a")).singleElement()
                .extracting(HybridFusion.Result::segmentId).isEqualTo(7L);
            assertThat(fused).filteredOn(r -> r.documentId().equals("z")).singleElement()
                .extracting(HybridFusion.Result::segmentId)
                .as("a BM25 hit was never in a vector segment, and saying 0 would name one")
                .isNull();
        }

        @Test
        @DisplayName("scores are the fused rank sums, so every result is on one scale")
        void scoresAreComparable() {
            List<HybridFusion.Result> fused = HybridFusion.fuse(
                List.of(dense("a", 0.9f, 1)),
                List.of(lexical("z", 31.7)),
                10);

            assertThat(fused).allSatisfy(result -> assertThat(result.score())
                .as("a BM25 score of 31.7 and a cosine of 0.9 have no common scale; ranks do")
                .isBetween(0.0, 1.0));
        }
    }

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("keeps two results the vector path distinguishes only by segment")
        void anonymousResultsAreNotCollapsed() {
            // A result the pipeline could not name carries the node id as its numeric id, and
            // node ids are per-segment. Two such results are different documents; folding them
            // together because their numbers match would make turning fusion on lose answers
            // that vector search alone returns.
            List<HybridFusion.Result> fused = HybridFusion.fuse(
                List.of(new QueryResultItem(42L, null, 0.9f, null, 3L, 42L),
                        new QueryResultItem(42L, null, 0.8f, null, 9L, 42L)),
                List.of(),
                10);

            assertThat(fused).hasSize(2);
            assertThat(fused).extracting(HybridFusion.Result::segmentId)
                .containsExactlyInAnyOrder(3L, 9L);
        }

        @Test
        @DisplayName("fuses the same chunk found in two segments into one result")
        void namedResultsAreStillMerged() {
            // The opposite case, and the reason the key cannot simply be the segment and node:
            // one chunk indexed in two segments is one answer, and both signals name it the
            // same way.
            List<HybridFusion.Result> fused = HybridFusion.fuse(
                List.of(dense("kb:a.md:chunk:0", 0.9f, 3),
                        dense("kb:a.md:chunk:0", 0.7f, 9)),
                List.of(lexical("kb:a.md:chunk:0", 12.0)),
                10);

            assertThat(fused).hasSize(1);
            assertThat(fused.get(0).signals())
                .containsExactlyInAnyOrder(HybridFusion.DENSE_SIGNAL, HybridFusion.LEXICAL_SIGNAL);
        }
    }

    @Nested
    @DisplayName("Degenerate input")
    class DegenerateInput {

        @Test
        @DisplayName("treats a missing signal as one that found nothing")
        void nullListsAreEmptySignals() {
            assertThat(idsOf(HybridFusion.fuse(null, List.of(lexical("z", 3.0)), 10)))
                .containsExactly("z");
            assertThat(idsOf(HybridFusion.fuse(List.of(dense("a", 0.9f, 1)), null, 10)))
                .containsExactly("a");
        }

        @Test
        @DisplayName("returns nothing for a limit of zero and refuses a negative one")
        void limitBounds() {
            assertThat(HybridFusion.fuse(List.of(dense("a", 0.9f, 1)), List.of(), 0)).isEmpty();
            assertThatThrownBy(() -> HybridFusion.fuse(List.of(), List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("falls back to the numeric id for a result that carries no string one")
        void numericIdFallback() {
            List<HybridFusion.Result> fused = HybridFusion.fuse(
                List.of(new QueryResultItem(42L, 0.9f, null, 1L, 3L)), List.of(), 10);

            assertThat(idsOf(fused)).containsExactly("42");
        }
    }
}
