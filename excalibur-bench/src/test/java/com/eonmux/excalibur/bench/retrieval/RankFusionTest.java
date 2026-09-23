package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.retrieval.fusion.ReciprocalRankFusion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fusing a strategy's ranking with the graph's, by rank.
 *
 * <p>The arithmetic is the production fusion's; what is pinned here is the adaptation to chunk
 * names - that agreement between the two lists is what wins, that a tie keeps the order the
 * strategy had without the graph, and that a missing signal leaves the other one's order alone.
 */
@DisplayName("Rank fusion over chunk names")
class RankFusionTest {

    private static final int K = ReciprocalRankFusion.DEFAULT_K;

    @Test
    @DisplayName("a chunk both lists return outranks one only the first list loves")
    void agreementWins() {
        List<String> fused = RankFusion.fuse(3, K,
            new RankFusion.Ranking("bm25", List.of("a", "b", "c")),
            new RankFusion.Ranking("graph", List.of("c")));

        assertThat(fused).containsExactly("c", "a", "b");
    }

    @Test
    @DisplayName("a tie keeps the order the strategy had without the graph")
    void tiesKeepTheFirstListsOrder() {
        List<String> fused = RankFusion.fuse(2, K,
            new RankFusion.Ranking("bm25", List.of("a")),
            new RankFusion.Ranking("graph", List.of("g")));

        assertThat(fused).containsExactly("a", "g");
    }

    @Test
    @DisplayName("a signal that found nothing leaves the other one's order as it was")
    void emptySignal() {
        assertThat(RankFusion.fuse(5, K,
            new RankFusion.Ranking("bm25", List.of("a", "b")),
            new RankFusion.Ranking("graph", List.of())))
            .containsExactly("a", "b");
        assertThat(RankFusion.fuse(5, K,
            new RankFusion.Ranking("bm25", List.of()),
            new RankFusion.Ranking("graph", List.of("g"))))
            .containsExactly("g");
    }

    @Test
    @DisplayName("the fused list is cut at the limit")
    void limit() {
        assertThat(RankFusion.fuse(1, K,
            new RankFusion.Ranking("bm25", List.of("a", "b")),
            new RankFusion.Ranking("graph", List.of("b"))))
            .containsExactly("b");
        assertThat(RankFusion.fuse(0, K, new RankFusion.Ranking("bm25", List.of("a")))).isEmpty();
    }

    @Test
    @DisplayName("a chunk a list names twice counts once, at its better rank")
    void duplicatesCountOnce() {
        assertThat(RankFusion.fuse(3, K,
            new RankFusion.Ranking("graph", List.of("a", "a", "b")),
            new RankFusion.Ranking("bm25", List.of("b"))))
            .containsExactly("b", "a");
    }

    @Test
    @DisplayName("a ranking needs a signal name and a list")
    void rankingIsChecked() {
        assertThatThrownBy(() -> new RankFusion.Ranking(" ", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RankFusion.Ranking("bm25", null))
            .isInstanceOf(NullPointerException.class);
    }
}
