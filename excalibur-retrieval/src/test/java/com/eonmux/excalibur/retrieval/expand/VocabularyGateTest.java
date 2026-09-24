package com.eonmux.excalibur.retrieval.expand;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checking a generated term against the corpus before searching it.
 *
 * <p>An expander guesses the vocabulary of documents it has never read. Measured on a real corpus,
 * about 4% of its guesses name things the corpus has no word for — {@code junzi} for a Confucian
 * text held only in English translation, {@code PayPal} for a corpus that predates it. Each is a
 * clause competing with the user's own words for the ranking, so the corpus is asked first.
 */
@DisplayName("Vocabulary gate")
class VocabularyGateTest {

    private static LexicalIndex indexed(Path dir) throws Exception {
        LexicalIndex index = LexicalIndex.open(dir, 8.0);
        index.put(1L, "the kernel OOM killer sent SIGKILL after the cgroup memory limit", "a");
        index.put(2L, "the certificate chain could not be verified during the TLS handshake", "b");
        index.refresh();
        return index;
    }

    @Nested
    @DisplayName("backed by Lucene")
    class Lucene {

        @Test
        @DisplayName("knows exactly which terms the corpus holds")
        void exactMembership(@TempDir Path dir) throws Exception {
            try (LexicalIndex index = indexed(dir)) {
                VocabularyGate gate = new LuceneVocabulary(index);

                assertThat(gate.mayContain("sigkill")).isTrue();
                assertThat(gate.mayContain("cgroup")).isTrue();
                assertThat(gate.mayContain("junzi")).isFalse();
                assertThat(gate.mayContain("paypal")).isFalse();
            }
        }

        @Test
        @DisplayName("an empty index admits everything rather than emptying every query")
        void emptyIndexIsPermissive(@TempDir Path dir) throws Exception {
            try (LexicalIndex index = LexicalIndex.open(dir, 8.0)) {
                assertThat(new LuceneVocabulary(index).mayContain("anything")).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("backed by a Cuckoo filter")
    class Cuckoo {

        private CuckooVocabulary of(String... terms) {
            CuckooVocabulary gate = new CuckooVocabulary(CuckooFilter.create(4096, 13));
            for (String term : terms) {
                gate.add(term);
            }
            return gate;
        }

        @Test
        @DisplayName("never denies a term the corpus really holds")
        void noFalseNegatives() {
            // The one error direction that would lose a good expansion, and the reason a
            // probabilistic filter is acceptable here at all.
            CuckooVocabulary gate = of("sigkill", "cgroup", "enomem", "handshake");

            assertThat(gate.mayContain("sigkill")).isTrue();
            assertThat(gate.mayContain("cgroup")).isTrue();
            assertThat(gate.mayContain("enomem")).isTrue();
            assertThat(gate.mayContain("handshake")).isTrue();
        }

        @Test
        @DisplayName("rejects the great majority of terms the corpus lacks")
        void rejectsAbsentTerms() {
            CuckooVocabulary gate = of("sigkill", "cgroup", "enomem");
            int admitted = 0;
            for (int i = 0; i < 2000; i++) {
                if (gate.mayContain("absent-term-" + i)) {
                    admitted++;
                }
            }
            // A false positive costs one clause that finds nothing, so a small rate is fine;
            // this pins that it is small rather than that it is zero.
            assertThat(admitted).isLessThan(40);
        }
    }

    @Nested
    @DisplayName("applied to retrieval")
    class Applied {

        @Test
        @DisplayName("a formulation the corpus cannot contain is never searched")
        void absentFormulationsAreDropped(@TempDir Path dir) throws Exception {
            try (LexicalIndex index = indexed(dir)) {
                QueryExpander inventing = QueryExpander.infallible(query -> List.of(
                    SearchFormulation.original(query),
                    new SearchFormulation("junzi xiaoren", 0.9)));

                RankedList gated = new ExpandedLexicalRanker(index, inventing,
                    "gated", new LuceneVocabulary(index))
                    .rank(RetrievalQuery.ofText("SIGKILL"), 5);

                assertThat(gated.documents()).isNotEmpty();
                assertThat(gated.documents().get(0).documentId()).isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("a formulation the gate refuses is not searched")
        void refusedFormulationsAreNotSearched(@TempDir Path dir) throws Exception {
            try (LexicalIndex index = indexed(dir)) {
                QueryExpander expanding = QueryExpander.infallible(query -> List.of(
                    SearchFormulation.original(query),
                    new SearchFormulation("SIGKILL", 1.0)));
                // Refusing a term the corpus does hold is the only way to see the gate from
                // outside it: a term the corpus lacks matches nothing whether or not it is
                // searched, so gating it away changes the work done and not the answer.
                VocabularyGate refusing = term -> !"sigkill".equals(term);

                RankedList out = new ExpandedLexicalRanker(index, expanding, "gated", refusing)
                    .rank(RetrievalQuery.ofText("handshake"), 5);

                assertThat(out.documents()).extracting(ScoredDocument::documentId)
                    .as("the refused formulation is the only reason document 1 would rank")
                    .containsExactly(2L);
            }
        }

        @Test
        @DisplayName("a formulation the corpus does hold survives, whatever its script")
        void presentFormulationsSurviveTheGate(@TempDir Path dir) throws Exception {
            try (LexicalIndex index = LexicalIndex.open(dir, 8.0)) {
                // The gate answers about the terms the index holds, which are whatever its
                // analyzer produced. Asking it about anything else - a run of CJK the analyzer
                // split into bigrams, a contraction it kept whole - is asking about terms no
                // index has, and every answer is "absent".
                index.put(1L, "\u5185\u5b58\u4e0d\u8db3\u5bfc\u81f4\u8fdb\u7a0b\u88ab\u7ec8\u6b62", "a");
                index.put(2L, "the certificate chain could not be verified", "b");
                index.refresh();

                QueryExpander expanding = QueryExpander.infallible(query -> List.of(
                    SearchFormulation.original(query),
                    new SearchFormulation("\u5185\u5b58\u4e0d\u8db3", 1.0)));

                RankedList gated = new ExpandedLexicalRanker(index, expanding,
                    "gated", new LuceneVocabulary(index))
                    .rank(RetrievalQuery.ofText("certificate"), 5);

                assertThat(gated.documents()).extracting(ScoredDocument::documentId)
                    .as("the formulation names words document 1 really holds; dropping it loses "
                        + "a hit the ungated ranker returns")
                    .contains(1L);
            }
        }

        @Test
        @DisplayName("the user's own query is never gated away")
        void originalQuerySurvivesTheGate(@TempDir Path dir) throws Exception {
            try (LexicalIndex index = indexed(dir)) {
                // Searching for words the corpus lacks must return nothing, not silently become
                // a different query.
                RankedList out = new ExpandedLexicalRanker(index, QueryExpander.identity(),
                    "gated", new LuceneVocabulary(index))
                    .rank(RetrievalQuery.ofText("junzi"), 5);

                assertThat(out.documents()).isEmpty();
            }
        }
    }
}
