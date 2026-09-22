package com.eonmux.excalibur.retrieval.fusion;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FusedDocumentTest {

    @Test
    @DisplayName("a NaN score is refused, so no stage downstream has to decide what it means")
    void nanScoreIsRefused() {
        assertThatThrownBy(() -> new FusedDocument(7, Double.NaN, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NaN")
            .hasMessageContaining("7");
    }

    @Test
    @DisplayName("an ordinary score and its breakdown are kept")
    void keepsScoreAndContributions() {
        FusedDocument document = new FusedDocument(3, 0.25,
            List.of(new SignalContribution("bm25", 2, 0.25)));

        assertThat(document.score()).isEqualTo(0.25);
        assertThat(document.signalCount()).isEqualTo(1);
        assertThat(document.bestRank()).isEqualTo(2);
    }
}
