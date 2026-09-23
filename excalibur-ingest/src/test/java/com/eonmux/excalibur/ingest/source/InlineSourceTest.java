package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A source over documents a client supplied directly, rather than a target to crawl.
 */
@DisplayName("InlineSource")
class InlineSourceTest {

    private static InlineSource.Document doc(String id, String text) {
        return new InlineSource.Document(id, text.getBytes(StandardCharsets.UTF_8), "text/plain", 0L);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects a null source id")
        void rejectsNullSourceId() {
            assertThatThrownBy(() -> InlineSource.of(null, List.of(doc("a", "x"))))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a source id past the shared limit")
        void rejectsAnOversizedSourceId() {
            String tooLong = "s".repeat(Source.MAX_ID_LENGTH + 1);
            assertThatThrownBy(() -> InlineSource.of(tooLong, List.of(doc("a", "x"))))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects an empty document list")
        void rejectsNoDocuments() {
            assertThatThrownBy(() -> InlineSource.of("notes", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * A document id is copied into every chunk id, and a chunk id is a storage key, a
         * registry entry retained for the life of the process, and a line in the registry file.
         * An unbounded one turns a single request under the body limit into a hundred times its
         * size in keys and retained records.
         */
        @Test
        @DisplayName("rejects a document id long enough to amplify into the registry")
        void rejectsAnOversizedDocumentId() {
            String tooLong = "d".repeat(InlineSource.MAX_DOCUMENT_ID_LENGTH + 1);
            assertThatThrownBy(() -> doc(tooLong, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(InlineSource.MAX_DOCUMENT_ID_LENGTH));
        }

        /**
         * Two documents under one identifier in one request is a client that has lost track of
         * what it is sending; silently keeping the last would index one and drop the other.
         */
        @Test
        @DisplayName("rejects the same document identifier twice in one batch")
        void rejectsDuplicateIds() {
            assertThatThrownBy(() ->
                InlineSource.of("notes", List.of(doc("a", "x"), doc("a", "y"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
        }
    }

    @Nested
    @DisplayName("Crawling and fetching")
    class CrawlAndFetch {

        private final InlineSource source =
            InlineSource.of("notes", List.of(doc("a.md", "alpha"), doc("b.md", "beta")));

        @Test
        @DisplayName("lists exactly the documents it was given, in the order given")
        void listsWhatItWasGiven() throws Exception {
            try (Stream<String> ids = source.crawl()) {
                assertThat(ids.toList()).containsExactly("a.md", "b.md");
            }
        }

        @Test
        @DisplayName("fetches a document's own bytes")
        void fetchesContent() throws Exception {
            FetchedDocument fetched = source.fetch("b.md");

            assertThat(new String(fetched.getContent(), StandardCharsets.UTF_8)).isEqualTo("beta");
            assertThat(fetched.getContentType()).isEqualTo("text/plain");
        }

        @Test
        @DisplayName("reports a document it was never given as not found")
        void unknownDocumentIsNotFound() {
            assertThatThrownBy(() -> source.fetch("c.md"))
                .isInstanceOf(DocumentNotFoundException.class);
        }

        @Test
        @DisplayName("knows how many documents it holds without crawling")
        void knowsItsCount() throws Exception {
            assertThat(source.getDocumentCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("names itself inline so a registry can tell it from a crawl")
        void namesItsType() {
            assertThat(source.getSourceType()).isEqualTo(InlineSource.TYPE);
            assertThat(source.getSourceId()).isEqualTo("notes");
        }
    }
}
