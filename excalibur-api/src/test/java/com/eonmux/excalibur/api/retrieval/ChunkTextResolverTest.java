package com.eonmux.excalibur.api.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a caller is promised when it asks for the text behind a result identifier.
 */
@DisplayName("ChunkTextResolver")
class ChunkTextResolverTest {

    private static final Function<String, String> CORPUS =
        Map.of("a", "alpha", "b", "beta")::get;

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects a null source")
        void rejectsNullSource() {
            assertThatThrownBy(() -> ChunkTextResolver.over(null, 10))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects a non-positive bound")
        void rejectsNonPositiveBound() {
            assertThatThrownBy(() -> ChunkTextResolver.over(CORPUS, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxChars");
        }
    }

    @Nested
    @DisplayName("Resolution")
    class Resolution {

        @Test
        @DisplayName("returns the stored text untouched when it fits")
        void returnsStoredText() {
            ChunkTextResolver resolver = ChunkTextResolver.over(CORPUS, 10);

            ChunkTextResolver.Resolved resolved = resolver.resolve("a");

            assertThat(resolved.text()).isEqualTo("alpha");
            assertThat(resolved.truncated()).isFalse();
        }

        /**
         * A chunk the index still ranks but storage no longer holds is the normal aftermath of an
         * erasure that has not been compacted out of the vector index yet. Null text says so;
         * inventing an empty string would read as a chunk that genuinely holds nothing.
         */
        @Test
        @DisplayName("answers null for a chunk storage no longer holds")
        void answersNullForAMissingChunk() {
            ChunkTextResolver resolver = ChunkTextResolver.over(CORPUS, 10);

            ChunkTextResolver.Resolved resolved = resolver.resolve("gone");

            assertThat(resolved.text()).isNull();
            assertThat(resolved.truncated()).isFalse();
        }

        @Test
        @DisplayName("answers null for a null identifier rather than throwing")
        void answersNullForANullId() {
            assertThat(ChunkTextResolver.over(CORPUS, 10).resolve(null).text()).isNull();
        }

        /**
         * The bound is what stops one request turning k identifiers into an unbounded response
         * body. Truncation has to be visible: a caller that cannot tell a whole chunk from a
         * clipped one will quote the clipped one as if it were the whole thing.
         */
        @Test
        @DisplayName("clips text past the bound and says that it did")
        void clipsAndSaysSo() {
            ChunkTextResolver resolver = ChunkTextResolver.over(CORPUS, 3);

            ChunkTextResolver.Resolved resolved = resolver.resolve("a");

            assertThat(resolved.text()).isEqualTo("alp");
            assertThat(resolved.truncated()).isTrue();
        }

        /**
         * An unpaired surrogate cannot be encoded, so it leaves the server as a literal '?'.
         * A passage ending in an emoji would gain a character it never contained.
         */
        @Test
        @DisplayName("never clips a passage in the middle of a surrogate pair")
        void doesNotSplitASurrogatePair() {
            // Two code units each, so a bound of 3 lands inside the second pair.
            String emoji = "\uD83D\uDE00\uD83D\uDE00";
            ChunkTextResolver resolver = ChunkTextResolver.over(Map.of("e", emoji)::get, 3);

            ChunkTextResolver.Resolved resolved = resolver.resolve("e");

            assertThat(resolved.text()).isEqualTo("\uD83D\uDE00");
            assertThat(resolved.truncated()).isTrue();
            assertThat(resolved.text().codePoints().allMatch(Character::isValidCodePoint)).isTrue();
            assertThat(Character.isHighSurrogate(
                resolved.text().charAt(resolved.text().length() - 1))).isFalse();
        }

        @Test
        @DisplayName("text exactly at the bound is not reported as truncated")
        void exactlyAtTheBoundIsWhole() {
            ChunkTextResolver resolver = ChunkTextResolver.over(CORPUS, 5);

            ChunkTextResolver.Resolved resolved = resolver.resolve("a");

            assertThat(resolved.text()).isEqualTo("alpha");
            assertThat(resolved.truncated()).isFalse();
        }

        /**
         * A storage read that throws is one unreadable chunk, not a failed search. The result it
         * belongs to still has an identifier and a score worth returning.
         */
        @Test
        @DisplayName("a source that throws yields null text rather than failing the query")
        void aThrowingSourceYieldsNull() {
            ChunkTextResolver resolver = ChunkTextResolver.over(id -> {
                throw new IllegalStateException("storage is closed");
            }, 10);

            assertThat(resolver.resolve("a").text()).isNull();
        }
    }
}
