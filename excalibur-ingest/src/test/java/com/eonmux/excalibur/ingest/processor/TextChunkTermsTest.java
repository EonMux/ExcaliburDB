package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.core.text.TermExtractor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A chunk's terms, tokenised once however often they are asked for.
 *
 * <p>A consumer that needs a chunk's terms reads them from here, so the text is tokenised once
 * per chunk rather than once per consumer.</p>
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
