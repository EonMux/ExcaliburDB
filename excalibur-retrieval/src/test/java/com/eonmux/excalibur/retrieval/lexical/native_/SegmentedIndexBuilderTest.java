package com.eonmux.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Indexing a corpus larger than the memory available to index it.
 *
 * <p>The budget is set absurdly low in these tests so that a few hundred documents force many
 * flushes and several merge rounds. That is the arrangement a real corpus produces at a realistic
 * budget, and it is the arrangement worth testing: the failure it guards against is a merge tree
 * that loses documents in the rounds rather than in a single merge.
 */
@DisplayName("Segmented index building")
class SegmentedIndexBuilderTest {

    private static List<String> corpus(int count, long seed) {
        Random random = new Random(seed);
        String[] vocabulary = {"oom", "killer", "sigkill", "cgroup", "memory", "certificate",
            "handshake", "enomem", "kernel", "process", "venom", "journal", "patient"};
        List<String> docs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder();
            for (int w = 0; w < 5 + random.nextInt(30); w++) {
                sb.append(vocabulary[random.nextInt(vocabulary.length)]).append(' ');
            }
            docs.add(sb.append("unique").append(i).toString());
        }
        return docs;
    }

    private static double[] ones(int n) {
        double[] w = new double[n];
        java.util.Arrays.fill(w, 1.0);
        return w;
    }

    @Test
    @DisplayName("a corpus indexed in many pieces answers as one indexed whole")
    void manyPiecesMatchWhole(@TempDir Path root) throws Exception {
        List<String> docs = corpus(600, 3);

        PostingsWriter whole = new PostingsWriter();
        for (String doc : docs) {
            whole.add(doc);
        }
        whole.write(root.resolve("whole"));

        Path segmented = root.resolve("segmented");
        try (SegmentedIndexBuilder builder =
                 new SegmentedIndexBuilder(root.resolve("work"), 200)) {
            for (String doc : docs) {
                builder.add(doc);
            }
            assertThat(builder.pieceCount())
                .as("the budget must actually have forced flushes")
                .isGreaterThan(SegmentedIndexBuilder.MERGE_FAN_IN);
            builder.build(segmented);
        }

        try (PostingsIndex a = PostingsIndex.open(root.resolve("whole"));
             PostingsIndex b = PostingsIndex.open(segmented)) {
            assertThat(b.docCount()).isEqualTo(a.docCount());
            assertThat(b.termCount()).isEqualTo(a.termCount());

            String[][] queries = {{"oom", "killer"}, {"unique3"}, {"unique599"},
                {"memory", "cgroup", "kernel"}, {"venom"}};
            for (String[] query : queries) {
                PostingsIndex.Hit[] x = a.search(query, ones(query.length), 10);
                PostingsIndex.Hit[] y = b.search(query, ones(query.length), 10);
                assertThat(y).as("%s", String.join(" ", query)).hasSameSizeAs(x);
                for (int i = 0; i < x.length; i++) {
                    assertThat(y[i].docId()).isEqualTo(x[i].docId());
                    assertThat(y[i].score())
                        .isCloseTo(x[i].score(), org.assertj.core.data.Offset.offset(1e-9));
                }
            }
        }
    }

    @Test
    @DisplayName("every document survives the merge rounds")
    void noDocumentIsLost(@TempDir Path root) throws Exception {
        List<String> docs = corpus(400, 9);
        Path out = root.resolve("out");
        try (SegmentedIndexBuilder builder =
                 new SegmentedIndexBuilder(root.resolve("work"), 200)) {
            for (String doc : docs) {
                builder.add(doc);
            }
            builder.build(out);
        }
        try (PostingsIndex index = PostingsIndex.open(out)) {
            assertThat(index.docCount()).isEqualTo(docs.size());
            // Each document carries a term only it has, so a lost document is a term that
            // resolves to nothing rather than a count that merely looks wrong.
            for (int i = 0; i < docs.size(); i++) {
                assertThat(index.search(new String[] {"unique" + i}, ones(1), 2))
                    .as("document %d", i).hasSize(1);
            }
        }
    }

    @Test
    @DisplayName("a corpus small enough to need no merge still builds")
    void singlePieceStillBuilds(@TempDir Path root) throws Exception {
        Path out = root.resolve("out");
        try (SegmentedIndexBuilder builder = new SegmentedIndexBuilder(root.resolve("work"))) {
            builder.add("only one document here");
            builder.build(out);
        }
        try (PostingsIndex index = PostingsIndex.open(out)) {
            assertThat(index.docCount()).isEqualTo(1);
            assertThat(index.search(new String[] {"document"}, ones(1), 5)).hasSize(1);
        }
    }

    @Test
    @DisplayName("intermediate pieces are removed when the builder closes")
    void workDirectoryIsCleaned(@TempDir Path root) throws Exception {
        Path work = root.resolve("work");
        Path out = root.resolve("out");
        try (SegmentedIndexBuilder builder = new SegmentedIndexBuilder(work, 1000)) {
            for (String doc : corpus(200, 4)) {
                builder.add(doc);
            }
            builder.build(out);
        }
        assertThat(Files.exists(work)).as("scratch pieces must not outlive the build").isFalse();
        assertThat(Files.exists(out)).isTrue();
    }
}
