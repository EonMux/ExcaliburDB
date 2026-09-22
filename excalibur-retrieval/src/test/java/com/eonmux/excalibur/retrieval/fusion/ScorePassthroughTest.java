package com.eonmux.excalibur.retrieval.fusion;

import java.util.List;

import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Handing one signal's scores through")
class ScorePassthroughTest {

    private static final RankedList BM25 = RankedList.of("bm25", List.of(
        new ScoredDocument(4, 0.82), new ScoredDocument(9, 0.10), new ScoredDocument(2, 0.05)), 10);

    private final ScorePassthrough passthrough = new ScorePassthrough();

    @Test
    @DisplayName("keeps the signal's order and its own scores, each explained by that signal")
    void keepsScoresAndOrder() {
        List<FusedDocument> fused = passthrough.fuse(List.of(BM25), 10);

        assertThat(fused).extracting(FusedDocument::documentId).containsExactly(4L, 9L, 2L);
        assertThat(fused).extracting(FusedDocument::score).containsExactly(0.82, 0.10, 0.05);
        assertThat(fused.get(1).contributions()).singleElement().satisfies(own -> {
            assertThat(own.rankerName()).isEqualTo("bm25");
            assertThat(own.rank()).isEqualTo(2);
            assertThat(own.contribution()).isEqualTo(0.10);
        });
    }

    @Test
    @DisplayName("stops at the limit, and returns nothing for nothing")
    void honoursTheLimit() {
        assertThat(passthrough.fuse(List.of(BM25), 2)).hasSize(2);
        assertThat(passthrough.fuse(List.of(BM25), 0)).isEmpty();
        assertThat(passthrough.fuse(List.of(), 10)).isEmpty();
    }

    @Test
    @DisplayName("refuses two signals, which have no common scale to pass through")
    void refusesSeveralSignals() {
        RankedList dense = RankedList.of("dense", List.of(new ScoredDocument(4, 0.9)), 10);

        assertThatThrownBy(() -> passthrough.fuse(List.of(BM25, dense), 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("got 2");
    }

    @Test
    @DisplayName("refuses a null list and a negative limit")
    void refusesNonsense() {
        assertThatThrownBy(() -> passthrough.fuse(null, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> passthrough.fuse(List.of(BM25), -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(passthrough.name()).isEqualTo("passthrough").isEqualTo(passthrough.toString());
    }
}
