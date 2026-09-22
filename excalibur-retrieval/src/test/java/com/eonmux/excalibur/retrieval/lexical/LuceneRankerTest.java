package com.eonmux.excalibur.retrieval.lexical;

import java.io.IOException;
import java.nio.file.Path;

import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuceneRankerTest {

    private static LexicalIndex indexOf(Path dir, String... texts) throws IOException {
        LexicalIndex index = LexicalIndex.open(dir, 16.0);
        for (int i = 0; i < texts.length; i++) {
            index.put(i, texts[i]);
        }
        index.refresh();
        return index;
    }

    @Test
    @DisplayName("the BM25 pair an operator sets reaches the Lucene-backed index too")
    void honoursConfiguredBm25Parameters(@TempDir Path root) throws IOException {
        // Two documents holding the term once each, one of them much longer. b is the only thing
        // that can separate them, so switching it off has to make them score alike - otherwise
        // the same setting would mean one thing for the engine's own index and nothing here.
        String[] texts = {
            "consensus protocol",
            "consensus alpha beta gamma delta epsilon zeta eta theta iota kappa"
        };

        try (LexicalIndex normalised = LexicalIndex.open(root.resolve("normalised"), 16.0);
             LexicalIndex flat = LexicalIndex.open(root.resolve("flat"), 16.0,
                 new Bm25Parameters(1.2, 0.0))) {
            for (int i = 0; i < texts.length; i++) {
                normalised.put(i, texts[i]);
                flat.put(i, texts[i]);
            }
            normalised.refresh();
            flat.refresh();

            RetrievalQuery query = RetrievalQuery.ofText("consensus");
            RankedList withNormalisation = new LuceneRanker(normalised).rank(query, 10);
            RankedList withoutNormalisation = new LuceneRanker(flat).rank(query, 10);

            assertThat(withNormalisation.documents().get(0).score())
                .as("at b=0.75 the shorter document scores higher")
                .isGreaterThan(withNormalisation.documents().get(1).score());
            assertThat(withoutNormalisation.documents().get(0).score())
                .as("at b=0 length stops mattering")
                .isEqualTo(withoutNormalisation.documents().get(1).score());
        }
    }

    @Test
    @DisplayName("BM25 ranks the document that actually contains the terms first")
    void ranksByTermMatch(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir,
                "the quick brown fox jumps over the lazy dog",
                "a treatise on distributed consensus algorithms",
                "foxes are omnivorous mammals of the family canidae")) {

            LuceneRanker ranker = new LuceneRanker(index);
            RankedList results = ranker.rank(RetrievalQuery.ofText("brown fox"), 10);

            assertThat(results.documents()).isNotEmpty();
            assertThat(results.documents().get(0).documentId()).isZero();
            assertThat(results.rankerName()).isEqualTo("bm25");
        }
    }

    @Test
    @DisplayName("an exact rare token is found, which is what dense retrieval loses")
    void findsRareExactTokens(@TempDir Path dir) throws IOException {
        // The case that justifies keeping a lexical signal at all: an identifier that no embedding
        // generalises usefully.
        try (LexicalIndex index = indexOf(dir,
                "general notes about error handling and recovery",
                "incident report referencing ERR_7742_TIMEOUT during rollout",
                "a discussion of timeouts in distributed systems")) {

            LuceneRanker ranker = new LuceneRanker(index);
            RankedList results = ranker.rank(RetrievalQuery.ofText("ERR_7742_TIMEOUT"), 10);

            assertThat(results.documents()).isNotEmpty();
            assertThat(results.documents().get(0).documentId()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("query syntax in user input is escaped rather than interpreted")
    void querySyntaxIsEscaped(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir, "alpha", "beta", "gamma")) {
            LuceneRanker ranker = new LuceneRanker(index);

            // Unescaped, this would reach a field the user was not searching and match everything.
            RankedList wildcard = ranker.rank(RetrievalQuery.ofText("doc_id:*"), 10);
            assertThat(wildcard.documents())
                .as("field syntax must be treated as literal text, matching nothing")
                .isEmpty();

            // Unescaped, this is a parse error that would fail the whole query.
            RankedList unbalanced = ranker.rank(RetrievalQuery.ofText("alpha ((("), 10);
            assertThat(unbalanced.documents())
                .as("unbalanced syntax must still find the real term")
                .isNotEmpty();
            assertThat(unbalanced.documents().get(0).documentId()).isZero();
        }
    }

    @Test
    @DisplayName("a query of only stop words returns nothing rather than failing")
    void stopWordsOnly(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir, "alpha beta gamma")) {
            LuceneRanker ranker = new LuceneRanker(index);
            assertThat(ranker.rank(RetrievalQuery.ofText("!!! ???"), 10).documents()).isEmpty();
        }
    }

    @Test
    @DisplayName("the limit is respected")
    void limitRespected(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir,
                "search term one", "search term two", "search term three", "search term four")) {

            LuceneRanker ranker = new LuceneRanker(index);
            assertThat(ranker.rank(RetrievalQuery.ofText("search"), 2).documents()).hasSize(2);
            assertThat(ranker.rank(RetrievalQuery.ofText("search"), 0).documents()).isEmpty();
        }
    }

    @Test
    @DisplayName("re-adding a document replaces it instead of duplicating it")
    void putReplaces(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = LexicalIndex.open(dir, 16.0)) {
            index.put(1, "original text about penguins");
            index.refresh();
            index.put(1, "revised text about walruses");
            index.refresh();

            LuceneRanker ranker = new LuceneRanker(index);
            assertThat(index.documentCount()).isEqualTo(1);
            assertThat(ranker.rank(RetrievalQuery.ofText("penguins"), 10).documents())
                .as("the superseded version must not still be matchable")
                .isEmpty();
            assertThat(ranker.rank(RetrievalQuery.ofText("walruses"), 10).documents())
                .hasSize(1);
        }
    }

    @Test
    @DisplayName("a deleted document stops matching")
    void deleteRemoves(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir, "alpha unique", "beta unique")) {
            index.delete(0);
            index.refresh();

            LuceneRanker ranker = new LuceneRanker(index);
            assertThat(ranker.rank(RetrievalQuery.ofText("unique"), 10).documents())
                .extracting(ScoredDocument::documentId)
                .containsExactly(1L);
        }
    }

    @Test
    @DisplayName("documents are invisible until refresh, which is the near-real-time contract")
    void visibilityRequiresRefresh(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = LexicalIndex.open(dir, 16.0)) {
            index.put(0, "visible after refresh");
            LuceneRanker ranker = new LuceneRanker(index);

            assertThat(ranker.rank(RetrievalQuery.ofText("visible"), 10).documents()).isEmpty();
            index.refresh();
            assertThat(ranker.rank(RetrievalQuery.ofText("visible"), 10).documents()).hasSize(1);
        }
    }

    @Test
    @DisplayName("a query with no text names the signal that needed it")
    void requiresText(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir, "alpha")) {
            LuceneRanker ranker = new LuceneRanker(index, "titles");
            assertThatThrownBy(() -> ranker.rank(
                    RetrievalQuery.ofEmbedding(new float[] { 1f, 2f }), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("titles");
        }
    }

    @Test
    @DisplayName("an index survives being closed and reopened")
    void survivesReopen(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir, "durable content here")) {
            assertThat(index.documentCount()).isEqualTo(1);
        }
        try (LexicalIndex reopened = LexicalIndex.open(dir, 16.0)) {
            assertThat(reopened.documentCount()).isEqualTo(1);
            assertThat(new LuceneRanker(reopened)
                .rank(RetrievalQuery.ofText("durable"), 10).documents()).hasSize(1);
        }
    }

    @Test
    @DisplayName("a non-positive RAM buffer is refused")
    void ramBufferMustBePositive(@TempDir Path dir) {
        assertThatThrownBy(() -> LexicalIndex.open(dir, 0.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }
}
