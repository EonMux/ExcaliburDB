package com.eonmux.excalibur.api.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A supplied document")
class EmbeddedDocumentTest {

    @Test
    @DisplayName("is plain text with no date unless it says otherwise")
    void defaults() {
        EmbeddedDocument plain = EmbeddedDocument.of("a.txt", "text");
        assertThat(plain.contentType()).isEqualTo(EmbeddedDocument.DEFAULT_CONTENT_TYPE);
        assertThat(plain.lastModifiedMillis()).isZero();

        assertThat(new EmbeddedDocument("a.md", "text", " ", 5L).contentType())
            .isEqualTo("text/plain");
        assertThat(new EmbeddedDocument("a.md", "text", "text/markdown", 5L).contentType())
            .isEqualTo("text/markdown");
    }

    @Test
    @DisplayName("needs an id and text that is text, as a document posted over HTTP does")
    void refusesWhatCouldNotBeFound() {
        assertThatThrownBy(() -> EmbeddedDocument.of(null, "text"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddedDocument.of("", "text"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddedDocument.of("a", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddedDocument.of("a", ""))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'a'");
        assertThatThrownBy(() -> EmbeddedDocument.of("a", "bin\u0000ary"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not text");
    }
}
