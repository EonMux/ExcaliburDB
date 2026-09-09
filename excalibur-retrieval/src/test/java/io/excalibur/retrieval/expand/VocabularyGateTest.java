package io.excalibur.retrieval.expand;

import excalibur.filter.CuckooFilter;
import io.excalibur.retrieval.lexical.LexicalIndex;
import io.excalibur.retrieval.rank.RankedList;
import io.excalibur.retrieval.rank.RetrievalQuery;

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
                QueryExpander inventing = query -> List.of(
                    SearchFormulation.original(query),
                    new SearchFormulation("junzi xiaoren", 0.9));

                RankedList gated = new ExpandedLexicalRanker(index, inventing,
                    "gated", new LuceneVocabulary(index))
                    .rank(RetrievalQuery.ofText("SIGKILL"), 5);

                assertThat(gated.documents()).isNotEmpty();
                assertThat(gated.documents().get(0).documentId()).isEqualTo(1L);
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
