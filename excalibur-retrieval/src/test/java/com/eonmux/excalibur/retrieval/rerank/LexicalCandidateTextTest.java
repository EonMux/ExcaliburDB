package com.eonmux.excalibur.retrieval.rerank;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.lexical.LuceneRanker;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;
import com.eonmux.excalibur.retrieval.serialize.SerializationBudget;
import com.eonmux.excalibur.retrieval.serialize.TokenCounter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LexicalCandidateTextTest {

    private static final String NOTE_G = "Ada Lovelace wrote Note G, the first published algorithm "
        + "intended to be carried out by a machine.";

    private static LexicalIndex indexOf(Path dir, String... texts) throws IOException {
        LexicalIndex index = LexicalIndex.open(dir, 16.0);
        for (int i = 0; i < texts.length; i++) {
            index.put(i, texts[i]);
        }
        index.refresh();
        return index;
    }

    @Test
    @DisplayName("the text a document was indexed with comes back verbatim")
    void roundTripsTheIndexedText(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir, "unrelated", NOTE_G)) {
            CandidateText candidates = new LexicalCandidateText(index);

            assertThat(candidates.textOf(1))
                .as("a model has to be shown the document, not a reconstruction of its terms")
                .isEqualTo(NOTE_G);
            assertThat(candidates.textOf(0)).isEqualTo("unrelated");
        }
    }

    @Test
    @DisplayName("punctuation and case survive, because analysis is not what is stored")
    void storedTextIsNotTheAnalysedForm(@TempDir Path dir) throws IOException {
        String text = "ERR_7742_TIMEOUT: the ROLLOUT stalled, and the operator was not paged.";
        try (LexicalIndex index = indexOf(dir, text)) {
            assertThat(new LexicalCandidateText(index).textOf(0)).isEqualTo(text);
        }
    }

    @Test
    @DisplayName("a document the index does not hold has no text rather than an invented one")
    void unknownDocumentHasNoText(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir, NOTE_G)) {
            assertThat(new LexicalCandidateText(index).textOf(4242)).isNull();
        }
    }

    @Test
    @DisplayName("a replaced document yields the text it now holds")
    void replacementIsReflected(@TempDir Path dir) throws IOException {
        // The reason the text is read from the index rather than from a store beside it: there is
        // only one copy, so a replacement cannot leave the reranker judging the previous version.
        try (LexicalIndex index = indexOf(dir, "the first version of this document")) {
            index.put(0, "the second version of this document");
            index.refresh();

            assertThat(new LexicalCandidateText(index).textOf(0))
                .isEqualTo("the second version of this document");
        }
    }

    @Test
    @DisplayName("a deleted document has no text")
    void deletedDocumentHasNoText(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir, NOTE_G)) {
            index.delete(0);
            index.refresh();

            assertThat(new LexicalCandidateText(index).textOf(0)).isNull();
        }
    }

    @Test
    @DisplayName("text appears on the same refresh that makes the document searchable")
    void visibilityFollowsRefresh(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir, "already visible")) {
            index.put(1, "written but not yet refreshed");

            CandidateText candidates = new LexicalCandidateText(index);
            assertThat(candidates.textOf(1))
                .as("a candidate this snapshot cannot rank has no text in it either")
                .isNull();

            index.refresh();
            assertThat(candidates.textOf(1)).isEqualTo("written but not yet refreshed");
        }
    }

    @Test
    @DisplayName("the index is required")
    void indexIsRequired() {
        assertThatThrownBy(() -> new LexicalCandidateText(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("index");
    }

    @Test
    @DisplayName("BM25 still ranks by term match now that the text is stored as well as indexed")
    void storingTheTextLeavesRankingAlone(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir,
                "a treatise on distributed consensus algorithms",
                NOTE_G,
                "notes on the mechanical loom")) {

            RankedList results = new LuceneRanker(index).rank(RetrievalQuery.ofText("Note G"), 10);

            assertThat(results.documents()).isNotEmpty();
            assertThat(results.documents().get(0).documentId()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the reranker is handed the real document text, which is what closes the gap")
    void feedsTheRerankerRealText(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = indexOf(dir,
                "an unrelated document about looms",
                NOTE_G)) {

            AtomicReference<List<String>> sent = new AtomicReference<>(List.of());
            BatchRelevanceModel model = (query, candidates) -> {
                sent.set(List.copyOf(candidates));
                // Prefers whichever candidate actually mentions the query's subject, which it can
                // only do if it was given the text rather than an id.
                List<Double> scores = new ArrayList<>();
                for (String candidate : candidates) {
                    scores.add(candidate.contains("Note G") ? 0.9 : 0.1);
                }
                return scores;
            };

            RankedList result = new BatchedLlmReranker(
                new LuceneRanker(index), model, new LexicalCandidateText(index),
                new CircuitBreaker(3, Duration.ofSeconds(30), "test"),
                new SerializationBudget(10_000, TokenCounter.conservativeEstimate()))
                .rank(RetrievalQuery.ofText("looms Note G"), 10);

            assertThat(sent.get()).contains(NOTE_G);
            assertThat(result.documents()).extracting(ScoredDocument::documentId)
                .containsExactly(1L, 0L);
        }
    }

    @Test
    @DisplayName("a closed index yields no text rather than failing the query")
    void aClosedIndexYieldsNoText(@TempDir Path dir) throws IOException {
        LexicalIndex index = LexicalIndex.open(dir.resolve("documents"), 4.0);
        index.put(1L, "Ada Lovelace");
        index.commit();
        index.refresh();
        LexicalCandidateText text = new LexicalCandidateText(index);
        assertThat(text.textOf(1L)).isEqualTo("Ada Lovelace");

        // A build or a compaction replaces the served stack and closes what it replaced, while
        // queries are still reading it. Lucene's AlreadyClosedException is an
        // IllegalStateException, and this is called while the reranker assembles its batch -
        // outside the guard that catches a failing model - so letting it out ends the request
        // with a 500 for a candidate that would simply have kept its rank.
        index.close();

        assertThat(text.textOf(1L)).isNull();
    }
}
