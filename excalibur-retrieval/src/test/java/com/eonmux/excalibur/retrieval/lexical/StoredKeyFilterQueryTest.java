package com.eonmux.excalibur.retrieval.lexical;

import com.eonmux.excalibur.retrieval.expand.ExpandedLexicalRanker;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Lucene engine's filter by caller key, as a clause of the search rather than a pass over its
 * page.
 */
@DisplayName("Stored key filter")
class StoredKeyFilterQueryTest {

    private static final Predicate<String> MINE = key -> key.startsWith("mine:");

    /** Twenty refused documents that match better than the two admitted ones. */
    private static LexicalIndex crowded(Path dir) throws IOException {
        LexicalIndex index = LexicalIndex.open(dir, 16.0);
        for (int i = 0; i < 20; i++) {
            index.put(100 + i, "kernel kernel kernel panic", "other:" + i);
        }
        index.put(1, "kernel module", "mine:a");
        index.put(2, "a kernel parameter and a long tail of unrelated words", "mine:b");
        index.put(3, "kernel", null);
        index.refresh();
        return index;
    }

    @Test
    @DisplayName("fills the page from admitted documents when the best matches are refused")
    void fillsThePage(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = crowded(dir)) {
            RankedList ranked = new ExpandedLexicalRanker(index, QueryExpander.identity())
                .admitting(MINE)
                .rank(RetrievalQuery.ofText("kernel"), 2);

            assertThat(ranked.documents()).extracting(ScoredDocument::documentId)
                .containsExactly(1L, 2L);
        }
    }

    @Test
    @DisplayName("scores admitted documents exactly as an unfiltered search does")
    void keepsTheScores(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = crowded(dir)) {
            ExpandedLexicalRanker ranker = new ExpandedLexicalRanker(index,
                QueryExpander.identity());
            RetrievalQuery query = RetrievalQuery.ofText("kernel");

            List<ScoredDocument> unfiltered = ranker.rank(query, 50).documents().stream()
                .filter(hit -> hit.documentId() == 1 || hit.documentId() == 2).toList();
            List<ScoredDocument> filtered = ranker.admitting(MINE).rank(query, 50).documents();

            assertThat(filtered).isEqualTo(unfiltered);
        }
    }

    @Test
    @DisplayName("never admits a document stored without a key")
    void refusesUnkeyedDocuments(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = crowded(dir)) {
            RankedList ranked = new ExpandedLexicalRanker(index, QueryExpander.identity())
                .admitting(key -> true)
                .rank(RetrievalQuery.ofText("kernel"), 50);

            assertThat(ranked.documents()).extracting(ScoredDocument::documentId)
                .doesNotContain(3L)
                .hasSize(22);
        }
    }

    @Test
    @DisplayName("a null filter is the unfiltered ranker itself")
    void nullAdmitsEverything(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = crowded(dir)) {
            ExpandedLexicalRanker ranker = new ExpandedLexicalRanker(index,
                QueryExpander.identity());

            assertThat(ranker.admitting(null)).isSameAs(ranker);
        }
    }

    @Test
    @DisplayName("is equal only to a filter over the same predicate")
    void identity() {
        Predicate<String> one = key -> true;
        assertThat(new StoredKeyFilterQuery(one)).isEqualTo(new StoredKeyFilterQuery(one))
            .hasSameHashCodeAs(new StoredKeyFilterQuery(one));
        assertThat(new StoredKeyFilterQuery(one)).isNotEqualTo(new StoredKeyFilterQuery(key -> true));
        assertThat(new StoredKeyFilterQuery(one).toString()).contains("external_key");
    }
}
