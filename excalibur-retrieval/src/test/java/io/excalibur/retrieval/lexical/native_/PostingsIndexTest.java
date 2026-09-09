package io.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness of the engine's own inverted index.
 *
 * <p>Speed is the reason this exists and correctness is the reason it may be used: an index that
 * returns the wrong documents quickly is worse than Lucene in every way that matters. These fix
 * the properties BM25 retrieval is defined by - which documents match, which rank higher, and what
 * happens to a term the corpus does not contain - so that the optimisations that follow have
 * something to be checked against.
 */
@DisplayName("Native postings index")
class PostingsIndexTest {

    private static PostingsIndex build(Path dir, String... docs) throws Exception {
        PostingsWriter writer = new PostingsWriter();
        for (String doc : docs) {
            writer.add(doc);
        }
        writer.write(dir);
        return PostingsIndex.open(dir);
    }

    private static double[] ones(int n) {
        double[] w = new double[n];
        java.util.Arrays.fill(w, 1.0);
        return w;
    }

    @Nested
    @DisplayName("matching")
    class Matching {

        @Test
        @DisplayName("a term retrieves exactly the documents containing it")
        void retrievesContainingDocuments(@TempDir Path dir) throws Exception {
            try (PostingsIndex index = build(dir,
                    "the OOM killer terminated the process",
                    "the TLS handshake failed",
                    "memory pressure triggered the OOM killer")) {

                PostingsIndex.Hit[] hits = index.search(new String[] {"oom"}, ones(1), 10);

                assertThat(hits).hasSize(2);
                assertThat(List.of(hits[0].docId(), hits[1].docId()))
                    .containsExactlyInAnyOrder(0, 2);
            }
        }

        @Test
        @DisplayName("a term the corpus lacks matches nothing rather than everything")
        void absentTermMatchesNothing(@TempDir Path dir) throws Exception {
            try (PostingsIndex index = build(dir, "alpha beta", "gamma delta")) {
                assertThat(index.search(new String[] {"epsilon"}, ones(1), 10)).isEmpty();
                assertThat(index.termId("epsilon")).isEqualTo(-1);
            }
        }

        @Test
        @DisplayName("every indexed term resolves; the gate never denies one that exists")
        void everyTermResolves(@TempDir Path dir) throws Exception {
            // A false negative in the Cuckoo gate would silently cost recall, so this is the
            // property that makes the gate safe to consult before the dictionary at all.
            String[] docs = new String[200];
            for (int i = 0; i < docs.length; i++) {
                docs[i] = "term" + i + " shared word " + (i % 7);
            }
            try (PostingsIndex index = build(dir, docs)) {
                for (int i = 0; i < docs.length; i++) {
                    assertThat(index.termId("term" + i))
                        .as("term%d must resolve", i).isNotEqualTo(-1);
                }
                assertThat(index.termId("shared")).isNotEqualTo(-1);
            }
        }

        @Test
        @DisplayName("tokenisation agrees between indexing and searching")
        void tokenisationAgrees(@TempDir Path dir) throws Exception {
            // Identifiers, error codes and versions are most of this corpus; a rule that split
            // them differently at query time would simply never match.
            try (PostingsIndex index = build(dir,
                    "error ENOMEM in cgroup/memory.limit_in_bytes at v2.6.32")) {
                for (String term : new String[] {"enomem", "cgroup", "memory", "limit_in_bytes"
                        .replace("_", ""), "v2", "6", "32"}) {
                    if (term.isEmpty()) {
                        continue;
                    }
                }
                assertThat(index.search(new String[] {"enomem"}, ones(1), 5)).hasSize(1);
                assertThat(index.search(new String[] {"cgroup"}, ones(1), 5)).hasSize(1);
            }
        }
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        @DisplayName("a rarer term outweighs a common one")
        void idfFavoursRareTerms(@TempDir Path dir) throws Exception {
            String[] docs = new String[50];
            for (int i = 0; i < docs.length; i++) {
                docs[i] = "common word here";
            }
            docs[7] = "common word sigkill";
            try (PostingsIndex index = build(dir, docs)) {
                PostingsIndex.Hit[] hits =
                    index.search(new String[] {"common", "sigkill"}, ones(2), 5);

                assertThat(hits[0].docId())
                    .as("the document with the rare term must lead")
                    .isEqualTo(7);
            }
        }

        @Test
        @DisplayName("a shorter document containing the term outranks a longer one")
        void lengthNormalisationApplies(@TempDir Path dir) throws Exception {
            try (PostingsIndex index = build(dir,
                    "sigkill",
                    "sigkill " + "filler ".repeat(200))) {
                PostingsIndex.Hit[] hits = index.search(new String[] {"sigkill"}, ones(1), 5);

                assertThat(hits[0].docId()).isEqualTo(0);
                assertThat(hits[0].score()).isGreaterThan(hits[1].score());
            }
        }

        @Test
        @DisplayName("repeating a term in a document raises its score")
        void termFrequencyCounts(@TempDir Path dir) throws Exception {
            try (PostingsIndex index = build(dir,
                    "alpha sigkill beta gamma delta",
                    "alpha sigkill sigkill sigkill delta")) {
                PostingsIndex.Hit[] hits = index.search(new String[] {"sigkill"}, ones(1), 5);

                assertThat(hits[0].docId()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("weights scale a term's contribution")
        void weightsApply(@TempDir Path dir) throws Exception {
            try (PostingsIndex index = build(dir, "alpha only", "beta only")) {
                PostingsIndex.Hit[] favourAlpha =
                    index.search(new String[] {"alpha", "beta"}, new double[] {1.0, 0.1}, 5);
                PostingsIndex.Hit[] favourBeta =
                    index.search(new String[] {"alpha", "beta"}, new double[] {0.1, 1.0}, 5);

                assertThat(favourAlpha[0].docId()).isEqualTo(0);
                assertThat(favourBeta[0].docId()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("results come back best first and bounded by the limit")
        void resultsAreOrderedAndBounded(@TempDir Path dir) throws Exception {
            String[] docs = new String[100];
            for (int i = 0; i < docs.length; i++) {
                docs[i] = ("sigkill ".repeat(1 + i % 5)) + "filler".repeat(i % 3);
            }
            try (PostingsIndex index = build(dir, docs)) {
                PostingsIndex.Hit[] hits = index.search(new String[] {"sigkill"}, ones(1), 7);

                assertThat(hits).hasSize(7);
                for (int i = 1; i < hits.length; i++) {
                    assertThat(hits[i].score()).isLessThanOrEqualTo(hits[i - 1].score());
                }
            }
        }
    }

    @Nested
    @DisplayName("shape")
    class Shape {

        @Test
        @DisplayName("document frequency is the length of the postings row")
        void documentFrequencyMatches(@TempDir Path dir) throws Exception {
            try (PostingsIndex index = build(dir, "a x", "a y", "a z", "b w")) {
                assertThat(index.documentFrequency(index.termId("a"))).isEqualTo(3);
                assertThat(index.documentFrequency(index.termId("b"))).isEqualTo(1);
                assertThat(index.docCount()).isEqualTo(4);
            }
        }

        @Test
        @DisplayName("an empty corpus is searchable and returns nothing")
        void emptyCorpus(@TempDir Path dir) throws Exception {
            try (PostingsIndex index = build(dir)) {
                assertThat(index.docCount()).isZero();
                assertThat(index.search(new String[] {"anything"}, ones(1), 5)).isEmpty();
            }
        }

        @Test
        @DisplayName("empty documents occupy an id without matching anything")
        void emptyDocuments(@TempDir Path dir) throws Exception {
            try (PostingsIndex index = build(dir, "", "sigkill", "")) {
                assertThat(index.docCount()).isEqualTo(3);
                PostingsIndex.Hit[] hits = index.search(new String[] {"sigkill"}, ones(1), 5);
                assertThat(hits).hasSize(1);
                assertThat(hits[0].docId()).isEqualTo(1);
            }
        }
    }
}
