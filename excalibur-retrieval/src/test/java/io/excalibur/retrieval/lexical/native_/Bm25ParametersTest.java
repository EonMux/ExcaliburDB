package io.excalibur.retrieval.lexical.native_;

import io.excalibur.retrieval.lexical.Bm25Parameters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BM25's two constants, as something an operator sets rather than something the source fixes.
 *
 * <p>They are not a runtime knob. Every postings block stores the largest impact any of its
 * postings can have, computed from {@code k1} and {@code b} at build time, and the scorer prunes
 * whole blocks against that bound. Score with one pair against bounds written with another and a
 * block holding a top result can be pruned - a missing search result that looks exactly like a
 * corpus that never had the document. So the pair an index was built with is recorded in it, and
 * an index built with one pair refuses to open under another.
 */
@DisplayName("BM25 parameters")
class Bm25ParametersTest {

    private static final Bm25Parameters TUNED = new Bm25Parameters(0.9, 0.4);

    private static void build(Path directory, Bm25Parameters bm25, String... documents)
            throws IOException {
        PostingsWriter writer = new PostingsWriter(bm25);
        for (String document : documents) {
            writer.add(document);
        }
        writer.write(directory);
    }

    private static double[] ones(int n) {
        double[] weights = new double[n];
        java.util.Arrays.fill(weights, 1.0);
        return weights;
    }

    private static double scoreOf(PostingsIndex.Hit[] hits, int docId) {
        for (PostingsIndex.Hit hit : hits) {
            if (hit.docId() == docId) {
                return hit.score();
            }
        }
        throw new AssertionError("document " + docId + " was not returned at all");
    }

    /**
     * Rewrites an index's metadata in the shape it had before the pair was recorded.
     *
     * <p>Twenty-four bytes, with a zero where the format version now sits - which is exactly what
     * the writers emitted then. An index in this shape is the one an existing deployment has on
     * disk, and it has to keep opening.
     */
    private static void degradeToLegacyMeta(Path directory) throws IOException {
        Path meta = directory.resolve("index.meta");
        byte[] current = Files.readAllBytes(meta);
        byte[] legacy = java.util.Arrays.copyOf(current, 24);
        ByteBuffer.wrap(legacy).putInt(12, 0);
        Files.write(meta, legacy);
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("a negative k1 is refused")
        void negativeK1() {
            assertThatThrownBy(() -> new Bm25Parameters(-0.1, 0.75))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k1");
        }

        @Test
        @DisplayName("b outside [0, 1] is refused, since it is a proportion")
        void bOutsideUnitRange() {
            assertThatThrownBy(() -> new Bm25Parameters(1.2, 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("b");
            assertThatThrownBy(() -> new Bm25Parameters(1.2, -0.5))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a value that is not a number is refused")
        void notANumber() {
            assertThatThrownBy(() -> new Bm25Parameters(Double.NaN, 0.75))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Bm25Parameters(Double.POSITIVE_INFINITY, 0.75))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the defaults are the constants the engine has always scored with")
        void defaultsAreTheOldConstants() {
            assertThat(Bm25Parameters.DEFAULT.k1()).isEqualTo(1.2).isEqualTo(PostingsIndex.K1);
            assertThat(Bm25Parameters.DEFAULT.b()).isEqualTo(0.75).isEqualTo(PostingsIndex.B);
        }
    }

    @Nested
    @DisplayName("scoring")
    class Scoring {

        @Test
        @DisplayName("b reaches the scorer: at b=0 length normalisation is switched off")
        void bSwitchesOffLengthNormalisation(@TempDir Path root) throws Exception {
            // Two documents holding the term once each, one of them four times longer. Length
            // normalisation is the only thing that can separate them, so it is visible on its
            // own: at b=0.75 the short one wins, at b=0 they score identically.
            String shortDocument = "alpha beta";
            String longDocument = "alpha gamma delta epsilon zeta eta theta iota";

            Path normalised = root.resolve("normalised");
            Path flat = root.resolve("flat");
            build(normalised, Bm25Parameters.DEFAULT, shortDocument, longDocument);
            build(flat, new Bm25Parameters(1.2, 0.0), shortDocument, longDocument);

            try (PostingsIndex withNormalisation = PostingsIndex.open(normalised);
                 PostingsIndex withoutNormalisation =
                     PostingsIndex.open(flat, new Bm25Parameters(1.2, 0.0))) {

                PostingsIndex.Hit[] hits = withNormalisation.search(
                    new String[] {"alpha"}, ones(1), 10);
                assertThat(scoreOf(hits, 0))
                    .as("at b=0.75 the shorter document scores higher")
                    .isGreaterThan(scoreOf(hits, 1));

                PostingsIndex.Hit[] flatHits = withoutNormalisation.search(
                    new String[] {"alpha"}, ones(1), 10);
                assertThat(scoreOf(flatHits, 0))
                    .as("at b=0 the two documents are indistinguishable")
                    .isEqualTo(scoreOf(flatHits, 1));
            }
        }

        @Test
        @DisplayName("k1 reaches the scorer: a lower k1 saturates repetition sooner")
        void k1ControlsSaturation(@TempDir Path root) throws Exception {
            // One document says the word once, the other six times, and both are the same
            // length. Only k1 decides how much the repetition is worth.
            String once = "alpha one two three four five";
            String often = "alpha alpha alpha alpha alpha alpha";

            Path patient = root.resolve("patient");
            Path impatient = root.resolve("impatient");
            build(patient, Bm25Parameters.DEFAULT, once, often);
            build(impatient, new Bm25Parameters(0.1, 0.75), once, often);

            try (PostingsIndex withDefault = PostingsIndex.open(patient);
                 PostingsIndex withLowK1 =
                     PostingsIndex.open(impatient, new Bm25Parameters(0.1, 0.75))) {

                double defaultGain = scoreOf(withDefault.search(new String[] {"alpha"}, ones(1), 10), 1)
                    / scoreOf(withDefault.search(new String[] {"alpha"}, ones(1), 10), 0);
                double lowGain = scoreOf(withLowK1.search(new String[] {"alpha"}, ones(1), 10), 1)
                    / scoreOf(withLowK1.search(new String[] {"alpha"}, ones(1), 10), 0);

                assertThat(lowGain)
                    .as("a small k1 saturates early, so six occurrences are worth little more "
                        + "than one")
                    .isLessThan(defaultGain);
            }
        }
    }

    @Nested
    @DisplayName("what the index records")
    class Recorded {

        @Test
        @DisplayName("the pair an index was built with is stored in it and read back")
        void pairIsPersisted(@TempDir Path dir) throws Exception {
            build(dir, TUNED, "alpha beta", "beta gamma");

            try (PostingsIndex index = PostingsIndex.open(dir, TUNED)) {
                assertThat(index.bm25()).isEqualTo(TUNED);
            }
        }

        @Test
        @DisplayName("an index opened under a different pair is refused rather than mis-scored")
        void mismatchIsRefused(@TempDir Path dir) throws Exception {
            build(dir, TUNED, "alpha beta", "beta gamma");

            assertThatThrownBy(() -> PostingsIndex.open(dir, Bm25Parameters.DEFAULT))
                .isInstanceOf(Bm25MismatchException.class)
                .hasMessageContaining("k1=0.9")
                .hasMessageContaining("b=0.4")
                .hasMessageContaining("k1=1.2")
                .hasMessageContaining("b=0.75")
                .hasMessageContaining("Reingest the corpus");
        }

        @Test
        @DisplayName("the refusal carries both pairs, so a caller can report them")
        void refusalCarriesBothPairs(@TempDir Path dir) throws Exception {
            build(dir, TUNED, "alpha beta");

            assertThatThrownBy(() -> PostingsIndex.open(dir, Bm25Parameters.DEFAULT))
                .isInstanceOfSatisfying(Bm25MismatchException.class, failure -> {
                    assertThat(failure.persisted()).isEqualTo(TUNED);
                    assertThat(failure.configured()).isEqualTo(Bm25Parameters.DEFAULT);
                });
        }

        @Test
        @DisplayName("the default open path is the default pair, so existing callers are unchanged")
        void defaultOpenUsesTheDefaultPair(@TempDir Path dir) throws Exception {
            build(dir, Bm25Parameters.DEFAULT, "alpha beta");

            try (PostingsIndex index = PostingsIndex.open(dir)) {
                assertThat(index.bm25()).isEqualTo(Bm25Parameters.DEFAULT);
            }
        }
    }

    @Nested
    @DisplayName("indexes written before the pair was recorded")
    class Legacy {

        @Test
        @DisplayName("one opens under the defaults, because that is what built it")
        void legacyIndexOpensUnderDefaults(@TempDir Path dir) throws Exception {
            build(dir, Bm25Parameters.DEFAULT, "the OOM killer terminated the process",
                "the TLS handshake failed");
            degradeToLegacyMeta(dir);

            try (PostingsIndex index = PostingsIndex.open(dir)) {
                assertThat(index.bm25()).isEqualTo(Bm25Parameters.DEFAULT);
                assertThat(index.search(new String[] {"oom"}, ones(1), 10))
                    .as("and it still searches")
                    .hasSize(1);
            }
        }

        @Test
        @DisplayName("one is refused only when the operator actually changed the values")
        void legacyIndexRefusedUnderNewValues(@TempDir Path dir) throws Exception {
            build(dir, Bm25Parameters.DEFAULT, "alpha beta");
            degradeToLegacyMeta(dir);

            assertThatThrownBy(() -> PostingsIndex.open(dir, TUNED))
                .isInstanceOf(Bm25MismatchException.class)
                .hasMessageContaining("k1=1.2")
                .hasMessageContaining("k1=0.9");
        }
    }

    @Nested
    @DisplayName("the bound blocks are pruned against")
    class Bounds {

        /**
         * The invariant the whole arrangement rests on, asserted directly.
         *
         * <p>A term's bound is what lets the scorer stop scanning it. If any posting of that term
         * can score above the bound, a document the query wanted can be dropped without a trace.
         * Checking it posting by posting catches a writer and a scorer that disagree even where
         * no query in a sample happens to expose the difference.
         */
        @Test
        @DisplayName("no posting can score above the bound written for its term")
        void boundsCoverEveryPosting(@TempDir Path dir) throws Exception {
            java.util.Random random = new java.util.Random(99);
            String[] vocabulary = {"the", "of", "memory", "kernel", "sigkill", "enomem"};
            PostingsWriter writer = new PostingsWriter(TUNED);
            for (int d = 0; d < 300; d++) {
                StringBuilder text = new StringBuilder();
                for (int w = 0; w < 5 + random.nextInt(120); w++) {
                    text.append(vocabulary[random.nextInt(vocabulary.length)]).append(' ');
                }
                writer.add(text.toString());
            }
            writer.write(dir);

            try (PostingsIndex index = PostingsIndex.open(dir, TUNED)) {
                int[] lengths = index.documentLengths();
                double average = index.averageDocumentLength();
                for (int termId = 0; termId < index.termCount(); termId++) {
                    double bound = index.termBound(termId, average);
                    PostingsIndex.Row row = index.row(termId);
                    for (int i = 0; i < row.docs().length; i++) {
                        double saturation = TUNED.saturation(row.freqs()[i],
                            TUNED.lengthNorm(lengths[row.docs()[i]], average));
                        assertThat(bound)
                            .as("term '%s', document %d", row.term(), row.docs()[i])
                            // The bound is stored as a float, so it may sit one rounding step
                            // below the double the scorer computes and no further.
                            .isGreaterThanOrEqualTo(saturation * (1 - 1e-6));
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("through the writers that build an index in pieces")
    class Writers {

        @Test
        @DisplayName("a merge carries the pair into the merged index")
        void mergeCarriesThePair(@TempDir Path root) throws Exception {
            Path one = root.resolve("one");
            Path two = root.resolve("two");
            Path merged = root.resolve("merged");
            build(one, TUNED, "alpha beta", "beta gamma");
            build(two, TUNED, "gamma delta");

            PostingsMerger.merge(List.of(one, two), merged, TUNED);

            try (PostingsIndex index = PostingsIndex.open(merged, TUNED)) {
                assertThat(index.bm25()).isEqualTo(TUNED);
                assertThat(index.docCount()).isEqualTo(3);
            }
            assertThatThrownBy(() -> PostingsIndex.open(merged))
                .isInstanceOf(Bm25MismatchException.class);
        }

        @Test
        @DisplayName("a segmented build carries the pair through every piece and merge round")
        void segmentedBuildCarriesThePair(@TempDir Path root) throws Exception {
            Path destination = root.resolve("index");
            try (SegmentedIndexBuilder builder =
                     new SegmentedIndexBuilder(root.resolve("work"), 4, TUNED)) {
                for (int i = 0; i < 40; i++) {
                    builder.add("alpha beta document " + i);
                }
                builder.build(destination);
            }

            try (PostingsIndex index = PostingsIndex.open(destination, TUNED)) {
                assertThat(index.bm25()).isEqualTo(TUNED);
                assertThat(index.docCount()).isEqualTo(40);
            }
        }
    }

    @Nested
    @DisplayName("through the store")
    class Store {

        @Test
        @DisplayName("segments are written with the store's pair and read back with it")
        void storeRoundTrip(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir, TUNED)) {
                store.add(java.util.Map.of("a", "the OOM killer terminated the process"), 1);
            }

            try (LexicalStore store = LexicalStore.open(dir, TUNED)) {
                assertThat(store.search(new String[] {"oom"}, ones(1), 5, 99))
                    .singleElement()
                    .satisfies(hit -> assertThat(hit.key()).isEqualTo("a"));
            }
        }

        @Test
        @DisplayName("a store written before the pair was recorded opens and searches as before")
        void legacyStoreStillOpens(@TempDir Path dir) throws Exception {
            // The upgrade case, at the level a deployment actually meets it: an existing store
            // on disk, a node that has configured nothing, and no reason for either to notice.
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(java.util.Map.of("a", "the OOM killer terminated the process"), 1);
            }
            try (Stream<Path> entries = Files.list(dir)) {
                for (Path entry : entries.toList()) {
                    if (Files.isDirectory(entry)) {
                        degradeToLegacyMeta(entry);
                    }
                }
            }

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.search(new String[] {"oom"}, ones(1), 5, 99))
                    .singleElement()
                    .satisfies(hit -> assertThat(hit.key()).isEqualTo("a"));
            }
        }

        @Test
        @DisplayName("a store reopened under a different pair refuses at open, not at query time")
        void storeRefusesAChangedPair(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir, TUNED)) {
                store.add(java.util.Map.of("a", "the OOM killer terminated the process"), 1);
            }

            // Failing here rather than on the first query is the point: a store that opened and
            // then accepted writes would mix segments built with two different pairs.
            assertThatThrownBy(() -> LexicalStore.open(dir))
                .isInstanceOf(Bm25MismatchException.class)
                .hasMessageContaining("Reingest the corpus");
        }
    }
}
