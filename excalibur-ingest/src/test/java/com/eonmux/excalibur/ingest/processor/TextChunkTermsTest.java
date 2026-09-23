package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.core.text.TermExtractor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A chunk's terms, tokenised once for everything a write feeds.
 *
 * <p>Storing a chunk counts its terms into the segment's frequency sketch, and filing it counts
 * its distinct terms into the membership filter. Both read the terms from here, so the text is
 * tokenised once per chunk and the two can never disagree about what the chunk holds.</p>
 */
@DisplayName("Text chunk terms")
class TextChunkTermsTest {

    private static TextChunk chunk(String text) {
        return new TextChunk("doc", text, 1, 0, text.length(), 0);
    }

    @Test
    @DisplayName("are the terms the extractor finds, repetition included")
    void matchTheExtractor() {
        String text = "The engine writes the chunk, and the engine counts the chunk.";

        assertThat(chunk(text).terms()).isEqualTo(TermExtractor.extractTerms(text));
    }

    @Test
    @DisplayName("are tokenised once, however often they are asked for")
    void areTokenisedOnce() {
        TextChunk chunk = chunk("Ada Lovelace wrote the first algorithm.");

        assertThat(chunk.terms()).isSameAs(chunk.terms());
    }

    @Test
    @DisplayName("cannot be changed by a reader")
    void areUnmodifiable() {
        TextChunk chunk = chunk("Ada Lovelace");

        assertThatThrownBy(() -> chunk.terms().add("babbage"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("of empty text are none")
    void emptyTextHasNone() {
        assertThat(chunk("").terms()).isEmpty();
    }
}
