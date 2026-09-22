package com.eonmux.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Building a corpus in pieces must be indistinguishable from building it whole.
 *
 * <p>This is the property that lets the index outgrow memory. A single-pass build holds every
 * postings row until it can write them in term order, so it is bounded by the heap; building in
 * bounded pieces and merging is what makes the ceiling the disk's instead. The merge is only
 * worth having if it is exact, so these compare a merged index against a whole one built from
 * the same documents and require the same answers, not merely similar ones - a merge that
 * reorders or drops postings produces a plausible index that is quietly missing documents.
 */
@DisplayName("Merging built indexes")
class PostingsMergerTest {

    private static List<String> corpus(int count, long seed) {
        Random random = new Random(seed);
        String[] vocabulary = {"oom", "killer", "sigkill", "cgroup", "memory", "limit",
            "certificate", "handshake", "truststore", "enomem", "kernel", "process",
            "venom", "toxin", "spider", "journal", "abstract", "patient"};
        List<String> docs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder();
            int words = 5 + random.nextInt(60);
            for (int w = 0; w < words; w++) {
                sb.append(vocabulary[random.nextInt(vocabulary.length)]).append(' ');
            }
            sb.append("unique").append(i);
            docs.add(sb.toString());
        }
        return docs;
    }

    private static Path buildWhole(Path dir, List<String> docs) throws Exception {
        PostingsWriter writer = new PostingsWriter();
        for (String doc : docs) {
            writer.add(doc);
        }
        writer.write(dir);
        return dir;
    }

    private static Path buildInPieces(Path root, List<String> docs, int pieceSize)
            throws Exception {
        List<Path> pieces = new ArrayList<>();
        for (int start = 0; start < docs.size(); start += pieceSize) {
            PostingsWriter writer = new PostingsWriter();
            for (int i = start; i < Math.min(docs.size(), start + pieceSize); i++) {
                writer.add(docs.get(i));
            }
            Path piece = root.resolve("piece-" + start);
            writer.write(piece);
            pieces.add(piece);
        }
        Path merged = root.resolve("merged");
        PostingsMerger.merge(pieces, merged);
        return merged;
    }

    private static double[] ones(int n) {
        double[] w = new double[n];
        java.util.Arrays.fill(w, 1.0);
        return w;
    }

    @Test
    @DisplayName("a merged index answers exactly as a whole one does")
    void mergedMatchesWhole(@TempDir Path root) throws Exception {
        List<String> docs = corpus(500, 11);
        try (PostingsIndex whole = PostingsIndex.open(buildWhole(root.resolve("whole"), docs));
             PostingsIndex merged = PostingsIndex.open(
                 buildInPieces(root.resolve("pieces"), docs, 70))) {

            assertThat(merged.docCount()).isEqualTo(whole.docCount());
            assertThat(merged.termCount()).isEqualTo(whole.termCount());

            String[][] queries = {
                {"oom", "killer"}, {"certificate"}, {"memory", "limit", "cgroup"},
                {"unique17"}, {"venom", "spider"}, {"sigkill", "enomem", "kernel"}};
            for (String[] query : queries) {
                PostingsIndex.Hit[] a = whole.search(query, ones(query.length), 10);
                PostingsIndex.Hit[] b = merged.search(query, ones(query.length), 10);

                assertThat(b).as("hit count for %s", String.join(" ", query))
                    .hasSameSizeAs(a);
                for (int i = 0; i < a.length; i++) {
                    assertThat(b[i].docId())
                        .as("rank %d of %s", i, String.join(" ", query))
                        .isEqualTo(a[i].docId());
                    assertThat(b[i].score())
                        .as("score at rank %d of %s", i, String.join(" ", query))
                        .isCloseTo(a[i].score(), org.assertj.core.data.Offset.offset(1e-9));
                }
            }
        }
    }

    @Test
    @DisplayName("document frequencies survive the merge")
    void documentFrequenciesMatch(@TempDir Path root) throws Exception {
        List<String> docs = corpus(300, 5);
        try (PostingsIndex whole = PostingsIndex.open(buildWhole(root.resolve("whole"), docs));
             PostingsIndex merged = PostingsIndex.open(
                 buildInPieces(root.resolve("pieces"), docs, 40))) {
            for (String term : new String[] {"oom", "memory", "spider", "unique0", "unique299"}) {
                int a = whole.termId(term);
                int b = merged.termId(term);
                assertThat(b).as("%s resolves in the merged index", term).isNotEqualTo(-1);
                assertThat(merged.documentFrequency(b))
                    .as("df of %s", term)
                    .isEqualTo(whole.documentFrequency(a));
            }
        }
    }

    @Test
    @DisplayName("uneven pieces, including a piece of one document, merge correctly")
    void unevenPieces(@TempDir Path root) throws Exception {
        List<String> docs = corpus(101, 7);
        try (PostingsIndex whole = PostingsIndex.open(buildWhole(root.resolve("whole"), docs));
             PostingsIndex merged = PostingsIndex.open(
                 buildInPieces(root.resolve("pieces"), docs, 100))) {
            PostingsIndex.Hit[] a = whole.search(new String[] {"oom"}, ones(1), 5);
            PostingsIndex.Hit[] b = merged.search(new String[] {"oom"}, ones(1), 5);
            assertThat(b).hasSameSizeAs(a);
            for (int i = 0; i < a.length; i++) {
                assertThat(b[i].docId()).isEqualTo(a[i].docId());
            }
        }
    }

    @Test
    @DisplayName("a piece with terms no other piece has is not lost")
    void disjointVocabularies(@TempDir Path root) throws Exception {
        PostingsWriter one = new PostingsWriter();
        one.add("alpha beta gamma");
        one.write(root.resolve("one"));
        PostingsWriter two = new PostingsWriter();
        two.add("delta epsilon zeta");
        two.write(root.resolve("two"));

        PostingsMerger.merge(List.of(root.resolve("one"), root.resolve("two")),
            root.resolve("merged"));

        try (PostingsIndex merged = PostingsIndex.open(root.resolve("merged"))) {
            assertThat(merged.docCount()).isEqualTo(2);
            assertThat(merged.search(new String[] {"alpha"}, ones(1), 5)[0].docId()).isZero();
            assertThat(merged.search(new String[] {"zeta"}, ones(1), 5)[0].docId()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("merging nothing is refused rather than producing an empty index")
    void refusesEmptyMerge(@TempDir Path root) {
        assertThatThrownBy(() -> PostingsMerger.merge(List.of(), root.resolve("out")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
