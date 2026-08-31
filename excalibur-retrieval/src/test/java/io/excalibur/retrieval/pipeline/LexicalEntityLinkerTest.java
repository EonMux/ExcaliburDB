package io.excalibur.retrieval.pipeline;

import java.io.IOException;
import java.nio.file.Path;

import io.excalibur.retrieval.lexical.LexicalIndex;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LexicalEntityLinkerTest {

    private static LexicalIndex labels(Path dir) throws IOException {
        LexicalIndex index = LexicalIndex.open(dir, 16.0);
        LexicalEntityLinker.index(index, 1, "Ada Lovelace", "Augusta Ada King", "Lady Lovelace");
        LexicalEntityLinker.index(index, 2, "Charles Babbage");
        LexicalEntityLinker.index(index, 3, "Analytical Engine");
        LexicalEntityLinker.index(index, 4, "Cambridge Massachusetts");
        LexicalEntityLinker.index(index, 5, "Cambridge England");
        index.refresh();
        return index;
    }

    @Test
    @DisplayName("an exact name links to its entity")
    void exactName(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = labels(dir)) {
            assertThat(new LexicalEntityLinker(index).link("Ada Lovelace")).contains(1L);
        }
    }

    @Test
    @DisplayName("a partial name links without an exact match")
    void partialName(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = labels(dir)) {
            assertThat(new LexicalEntityLinker(index).link("Lovelace")).contains(1L);
        }
    }

    @Test
    @DisplayName("an alias links to the same entity as its primary name")
    void aliasLinks(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = labels(dir)) {
            assertThat(new LexicalEntityLinker(index).link("Augusta Ada King")).contains(1L);
        }
    }

    @Test
    @DisplayName("a genuinely ambiguous mention returns every reading rather than picking one")
    void ambiguityIsPreserved(@TempDir Path dir) throws IOException {
        // Disambiguation needs the whole question and the graph around each candidate. Guessing
        // here would deny the ranking and abstention stages the chance to do it properly.
        try (LexicalIndex index = labels(dir)) {
            assertThat(new LexicalEntityLinker(index).link("Cambridge"))
                .containsExactlyInAnyOrder(4L, 5L);
        }
    }

    @Test
    @DisplayName("a mention matching nothing links to nothing")
    void unknownMention(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = labels(dir)) {
            assertThat(new LexicalEntityLinker(index).link("Grace Hopper")).isEmpty();
        }
    }

    @Test
    @DisplayName("the relative floor keeps out the least-bad match")
    void relativeFloorExcludesWeakMatches(@TempDir Path dir) throws IOException {
        // "Ada Lovelace" shares no term with "Charles Babbage", but in a small index a weak
        // partial can still rank. The floor is relative because BM25 scores are not comparable
        // across queries -- a rare name scores far higher than a common one.
        try (LexicalIndex index = labels(dir)) {
            long[] strict = new LexicalEntityLinker(index, 5, 0.95).link("Ada Lovelace");
            assertThat(strict).containsExactly(1L);
        }
    }

    @Test
    @DisplayName("a blank mention links to nothing rather than matching everything")
    void blankMention(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = labels(dir)) {
            LexicalEntityLinker linker = new LexicalEntityLinker(index);
            assertThat(linker.link("")).isEmpty();
            assertThat(linker.link(null)).isEmpty();
            assertThat(linker.link("   ")).isEmpty();
        }
    }

    @Test
    @DisplayName("the candidate cap is respected")
    void candidateCap(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = labels(dir)) {
            assertThat(new LexicalEntityLinker(index, 1, 0.0).link("Cambridge")).hasSize(1);
        }
    }

    @Test
    @DisplayName("an entity needs a label")
    void labelRequired(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = LexicalIndex.open(dir, 16.0)) {
            assertThatThrownBy(() -> LexicalEntityLinker.index(index, 1, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
