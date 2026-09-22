package com.eonmux.excalibur.api.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ResultText}.
 */
class ResultTextTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("refuses a non-positive bound, which would clip every result to nothing")
        void refusesANonPositiveBound() {
            assertThatThrownBy(() -> new ResultText(0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Without a source")
    class WithoutASource {

        private final ResultText text = new ResultText(16);

        @Test
        @DisplayName("returns no reader for a request that did not ask for text")
        void returnsNoReaderWhenNotAsked() {
            assertThat(text.resolverFor(false)).isNull();
        }

        @Test
        @DisplayName("refuses a request that asked, rather than answering without text")
        void refusesWhenAsked() {
            assertThatThrownBy(() -> text.resolverFor(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("include_text");
        }
    }

    @Nested
    @DisplayName("With a source")
    class WithASource {

        private final ResultText text = new ResultText(16);

        @Test
        @DisplayName("reads a chunk's text")
        void readsText() {
            text.source(id -> "text for " + id);

            ChunkTextResolver.Resolved resolved = text.resolverFor(true).resolve("a:b:chunk:0");

            assertThat(resolved.text()).isEqualTo("text for a:b:chu");
            assertThat(resolved.truncated()).isTrue();
        }

        @Test
        @DisplayName("stops reading once the source is taken away")
        void stopsReadingWhenTheSourceIsRemoved() {
            text.source(id -> "held");
            text.source(null);

            assertThatThrownBy(() -> text.resolverFor(true))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
