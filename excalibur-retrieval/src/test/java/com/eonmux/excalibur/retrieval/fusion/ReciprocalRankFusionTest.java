package com.eonmux.excalibur.retrieval.fusion;

import java.util.List;

import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

    private static RankedList list(String name, long... documentIds) {
        List<ScoredDocument> documents = new java.util.ArrayList<>();
        // Descending scores so the given order is the ranked order.
        double score = documentIds.length;
        for (long id : documentIds) {
            documents.add(new ScoredDocument(id, score--));
        }
        return new RankedList(name, documents);
    }

    @Test
    @DisplayName("the score is the sum of 1/(k+rank) over the signals that returned the document")
    void scoreIsTheFormula() {
        List<FusedDocument> fused = fusion.fuse(
            List.of(list("a", 10, 20), list("b", 20, 10)), 10);

        // Document 10: rank 1 in a, rank 2 in b. Document 20: rank 2 in a, rank 1 in b.
        double expected = 1.0 / (60 + 1) + 1.0 / (60 + 2);
        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).score()).isCloseTo(expected, within(1e-12));
        assertThat(fused.get(1).score()).isCloseTo(expected, within(1e-12));
    }

    @Test
    @DisplayName("agreement between signals beats a single signal's top result")
    void agreementBeatsOneStrongSignal() {
        // Document 1 is second in both signals; document 99 is first in one and absent from the
        // other. Two signals agreeing that something is nearly best is stronger evidence than one
        // signal insisting it is best -- which is the entire reason to fuse rather than pick.
        List<FusedDocument> fused = fusion.fuse(
            List.of(list("dense", 99, 1), list("bm25", 42, 1)), 10);

        assertThat(fused.get(0).documentId()).isEqualTo(1);
        assertThat(fused.get(0).signalCount()).isEqualTo(2);
        assertThat(fused.get(1).signalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("scores of wildly different magnitude do not need normalising")
    void magnitudeIsIrrelevant() {
        // A BM25 score near 14 and a cosine similarity near 0.8 share no scale. RRF reads only
        // position, so the fused order must be identical whatever the magnitudes are.
        RankedList huge = new RankedList("bm25", List.of(
            new ScoredDocument(1, 14_000.0), new ScoredDocument(2, 13_999.0)));
        RankedList tiny = new RankedList("dense", List.of(
            new ScoredDocument(1, 0.83), new ScoredDocument(2, 0.82)));

        List<FusedDocument> fused = fusion.fuse(List.of(huge, tiny), 10);

        assertThat(fused.get(0).documentId()).isEqualTo(1);
        assertThat(fused.get(1).documentId()).isEqualTo(2);
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
    }

    @Test
    @DisplayName("k flattens the top of each list")
    void kDampensTopRanks() {
        // At k=60 the gap between rank 1 and rank 2 is small, because the exact order within a
        // signal's top few carries little information.
        double gapAt60 = (1.0 / 61) - (1.0 / 62);
        // At k=0 rank 1 would be worth twice rank 2, letting one signal's arbitrary first pick
        // dominate the fusion. k must be positive, so 1 is the closest legal comparison.
        double gapAt1 = (1.0 / 2) - (1.0 / 3);

        assertThat(gapAt60).isLessThan(gapAt1 / 100);

        ReciprocalRankFusion sharp = new ReciprocalRankFusion(1);
        assertThat(sharp.k()).isEqualTo(1);
        assertThat(fusion.k()).isEqualTo(ReciprocalRankFusion.DEFAULT_K);
    }

    @Test
    @DisplayName("two values of k fuse the same lists into different orders")
    void kChangesTheFusedOrder() {
        // Document 1 is one signal's top pick and barely on the other's list; document 2 is
        // fourth on both. A small k rewards the single strong placement, a large k flattens it
        // until steady agreement wins instead. Same lists, opposite answers - which is what
        // makes k worth exposing to an operator at all.
        long[] shallow = {1, 101, 102, 2};
        long[] deep = new long[20];
        deep[0] = 103;
        deep[1] = 104;
        deep[2] = 105;
        deep[3] = 2;
        for (int position = 4; position < 19; position++) {
            deep[position] = 200 + position;
        }
        deep[19] = 1;
        List<RankedList> lists = List.of(list("dense", shallow), list("bm25", deep));

        List<FusedDocument> sharp = new ReciprocalRankFusion(1).fuse(lists, 2);
        List<FusedDocument> flat = new ReciprocalRankFusion(60).fuse(lists, 2);

        assertThat(sharp.get(0).documentId())
            .as("at k=1 one signal's first choice carries the document")
            .isEqualTo(1);
        assertThat(flat.get(0).documentId())
            .as("at k=60 agreement between the signals carries it instead")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("the contribution breakdown records each signal's rank")
    void breakdownIsRecorded() {
        List<FusedDocument> fused = fusion.fuse(
            List.of(list("dense", 5, 7), list("bm25", 7, 5)), 10);

        FusedDocument first = fused.get(0);
        assertThat(first.contributions()).hasSize(2);
        assertThat(first.contributions())
            .extracting(SignalContribution::rankerName)
            .containsExactlyInAnyOrder("dense", "bm25");
        assertThat(first.bestRank()).isEqualTo(1);
        assertThat(first.contributions().get(0).rank())
            .as("contributions are ordered best rank first")
            .isLessThanOrEqualTo(first.contributions().get(1).rank());
    }

    @Test
    @DisplayName("a signal returning nothing simply contributes nothing")
    void emptySignalIsNotAnError() {
        List<FusedDocument> fused = fusion.fuse(
            List.of(list("dense", 1, 2), RankedList.empty("bm25")), 10);

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).signalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a signal listing the same document twice counts it once, at its better rank")
    void duplicateWithinASignalCountsOnce() {
        RankedList repeated = new RankedList("dense", List.of(
            new ScoredDocument(1, 9.0), new ScoredDocument(2, 8.0), new ScoredDocument(1, 7.0)));

        List<FusedDocument> fused = fusion.fuse(List.of(repeated), 10);

        FusedDocument document1 = fused.stream()
            .filter(d -> d.documentId() == 1).findFirst().orElseThrow();
        assertThat(document1.signalCount())
            .as("one signal, so one contribution however many times it listed the document")
            .isEqualTo(1);
        assertThat(document1.contributions().get(0).rank()).isEqualTo(1);
        assertThat(document1.score()).isCloseTo(1.0 / 61, within(1e-12));
    }

    @Test
    @DisplayName("the limit is respected")
    void limitRespected() {
        assertThat(fusion.fuse(List.of(list("a", 1, 2, 3, 4, 5)), 3)).hasSize(3);
        assertThat(fusion.fuse(List.of(list("a", 1, 2, 3)), 0)).isEmpty();
    }

    @Test
    @DisplayName("no signals at all fuses to nothing")
    void noSignals() {
        assertThat(fusion.fuse(List.of(), 10)).isEmpty();
    }

    @Test
    @DisplayName("ties break deterministically so a fused order does not vary between runs")
    void tiesAreDeterministic() {
        // Every document has an identical fused score here, so only the tie break orders them.
        List<FusedDocument> first = fusion.fuse(List.of(list("a", 3), list("b", 1), list("c", 2)), 10);
        List<FusedDocument> second = fusion.fuse(List.of(list("c", 2), list("a", 3), list("b", 1)), 10);

        assertThat(first).extracting(FusedDocument::documentId).containsExactly(1L, 2L, 3L);
        assertThat(second).extracting(FusedDocument::documentId)
            .as("the same documents fuse to the same order regardless of signal order")
            .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("a non-positive k is refused")
    void kMustBePositive() {
        assertThatThrownBy(() -> new ReciprocalRankFusion(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
        assertThatThrownBy(() -> new ReciprocalRankFusion(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the strategy names its k so a trace records which fusion ran")
    void nameCarriesK() {
        assertThat(fusion.name()).isEqualTo("rrf(k=60)");
        assertThat(new ReciprocalRankFusion(10).name()).isEqualTo("rrf(k=10)");
    }
}
