package io.excalibur.retrieval.lexical.native_;

import io.excalibur.retrieval.lexical.Bm25Parameters;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bound computed in one segment has to hold against the statistics the whole store scores with.
 *
 * <p>A segment knows only its own documents, so the average length it can measure at build time is
 * its own. A query scores every segment against the average of the collection, because a term's
 * rarity and a document's relative length are properties of the collection rather than of wherever
 * a document happened to land. Those two numbers differ whenever segments differ in shape, and
 * BM25's length normalisation moves against the average: a segment of short documents scores
 * <em>higher</em> under a large collection average than under its own small one.
 *
 * <p>That makes a bound measured at build time an underestimate at query time, and MaxScore treats
 * a bound as certain. A term whose bound has fallen below the current tenth-best score stops being
 * scanned, so a document only that term holds is never surfaced - and if the bound was an
 * underestimate, that document may have belonged in the results. The failure is silent: the
 * ranking that comes back looks like an ordinary ranking of a corpus that never had the document.
 *
 * <p>So these build segments with deliberately different length distributions and compare the
 * store's answer against exhaustive scoring under the same collection statistics. The expected
 * answer is derived rather than written down, because a hand-written one would only ever assert
 * what the implementation already did.
 */
@DisplayName("Block bounds against collection statistics")
class BlockBoundStatisticsTest {

    /** Ten tokens, five of them the query term: short beside a collection of long documents. */
    private static final String SHORT_ALPHA = "alpha alpha alpha alpha alpha p1 p2 p3 p4 p5";

    /** One document as the reference scorer sees it. */
    private record Document(String key, List<String> tokens) { }

    /** One scored document, for comparing a ranking against a derived one. */
    private record Scored(String key, double score) { }

    /**
     * Scores every document of every segment against every term, with no skipping whatsoever.
     *
     * <p>Statistics are gathered across all the segments, exactly as {@code LexicalStore} gathers
     * them, so any difference is a difference in skipping rather than in scoring.
     */
    private static List<Scored> exhaustive(List<Map<String, String>> segments, String[] terms,
                                           double[] weights, int limit, Bm25Parameters bm25) {
        List<Document> corpus = new ArrayList<>();
        for (Map<String, String> segment : segments) {
            for (Map.Entry<String, String> entry : segment.entrySet()) {
                corpus.add(new Document(entry.getKey(), Tokens.of(entry.getValue())));
            }
        }
        long totalTokens = 0;
        for (Document document : corpus) {
            totalTokens += document.tokens().size();
        }
        int documents = corpus.size();
        double average = documents == 0 ? 1.0 : (double) totalTokens / documents;

        List<Scored> ranked = new ArrayList<>();
        for (Document document : corpus) {
            double normBase = bm25.lengthNorm(document.tokens().size(), average);
            double score = 0;
            for (int t = 0; t < terms.length; t++) {
                int frequency = 0;
                for (String token : document.tokens()) {
                    if (token.equals(terms[t])) {
                        frequency++;
                    }
                }
                if (frequency == 0) {
                    continue;
                }
                int df = Math.max(1, documentFrequency(corpus, terms[t]));
                double idf = Math.log(1.0 + (documents - df + 0.5) / (df + 0.5));
                score += weights[t] * idf * bm25.saturation(frequency, normBase);
            }
            if (score > 0) {
                ranked.add(new Scored(document.key(), score));
            }
        }
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed());
        return ranked.size() > limit ? ranked.subList(0, limit) : ranked;
    }

    private static int documentFrequency(List<Document> corpus, String term) {
        int count = 0;
        for (Document document : corpus) {
            if (document.tokens().contains(term)) {
                count++;
            }
        }
        return count;
    }

    private static void assertSameRanking(List<LexicalStore.Hit> actual, List<Scored> expected,
                                          String description) {
        assertThat(actual).as("%s: number of hits", description).hasSameSizeAs(expected);
        for (int rank = 0; rank < expected.size(); rank++) {
            // Scores rather than keys: documents can tie exactly, and when more of them tie than
            // the limit admits any of them is a correct answer. The score at each rank is what
            // must be identical - if it is, the store found a document just as good.
            assertThat(actual.get(rank).score())
                .as("%s: score at rank %d", description, rank)
                .isCloseTo(expected.get(rank).score(), Offset.offset(1e-9));
        }
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** A document of {@code count} tokens, none of which is a query term. */
    private static String filler(String prefix, int count) {
        StringBuilder text = new StringBuilder(count * 6);
        for (int i = 0; i < count; i++) {
            text.append(prefix).append(i).append(' ');
        }
        return text.toString();
    }

    @Test
    @DisplayName("a document a pruned term holds is still returned when it belongs in the top-k")
    void shortSegmentDocumentSurvivesPruning(@TempDir Path dir) throws Exception {
        // Two hundred documents of a thousand tokens each. They hold none of the query terms;
        // they are here only to pull the collection's average length far above the average of
        // the segment that does hold them.
        Map<String, String> lengthy = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            lengthy.put("long-" + i, filler("f", 1000));
        }

        // Twenty short documents carrying "alpha", then one carrying "beta". The "beta"
        // document is last, so it is reached only after the heap has filled and pruning has
        // engaged, and it is the only document its term holds - so a pruned "beta" is a lost
        // document rather than a lost contribution.
        Map<String, String> brief = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            brief.put(String.format("short-%02d", i), SHORT_ALPHA);
        }
        brief.put("short-beta", "beta " + filler("q", 99));

        try (LexicalStore store = LexicalStore.open(dir)) {
            store.add(lengthy, 1);
            store.add(brief, 2);

            String[] terms = {"alpha", "beta"};
            double[] weights = {1.0, 1.0};
            int limit = 5;

            List<LexicalStore.Hit> hits = store.search(terms, weights, limit, 9);
            List<Scored> expected =
                exhaustive(List.of(lengthy, brief), terms, weights, limit, Bm25Parameters.DEFAULT);

            assertThat(expected).as("the derived answer must put the short document first")
                .first().extracting(Scored::key).isEqualTo("short-beta");
            assertThat(hits).extracting(LexicalStore.Hit::key)
                .as("the document a pruned term holds must still be returned")
                .contains("short-beta");
            assertSameRanking(hits, expected, "short document in a long collection");
        }
    }

    @Test
    @DisplayName("segments of different shapes rank exactly as exhaustive scoring does")
    void randomisedSegmentsOfDifferentShapes(@TempDir Path dir) throws Exception {
        Random random = new Random(20260914L);
        String[] pool = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta"};

        // Four segments whose documents differ in length by three orders of magnitude, so no
        // segment's own average is anywhere near the collection's - and the rate at which query
        // terms appear differs too, so a term can be common in one shape and almost absent from
        // another, which is the arrangement that lets a bound fall below a live score.
        int[] shapes = {3, 12, 200, 2500};
        double[] density = {0.5, 0.3, 0.05, 0.01};
        List<Map<String, String>> segments = new ArrayList<>();
        for (int s = 0; s < shapes.length; s++) {
            Map<String, String> segment = new LinkedHashMap<>();
            for (int d = 0; d < 50; d++) {
                int length = 1 + random.nextInt(shapes[s]);
                StringBuilder text = new StringBuilder();
                for (int w = 0; w < length; w++) {
                    text.append(random.nextDouble() < density[s]
                        ? pool[random.nextInt(pool.length)] : "pad" + random.nextInt(600));
                    text.append(' ');
                }
                segment.put("s" + s + "-d" + d, text.toString());
            }
            segments.add(segment);
        }

        try (LexicalStore store = LexicalStore.open(dir)) {
            long epoch = 1;
            for (Map<String, String> segment : segments) {
                store.add(segment, epoch++);
            }

            for (int trial = 0; trial < 200; trial++) {
                int count = 1 + random.nextInt(4);
                List<String> chosen = new ArrayList<>();
                while (chosen.size() < count) {
                    String term = pool[random.nextInt(pool.length)];
                    if (!chosen.contains(term)) {
                        chosen.add(term);
                    }
                }
                String[] terms = chosen.toArray(new String[0]);
                double[] weights = new double[terms.length];
                for (int i = 0; i < weights.length; i++) {
                    weights[i] = 0.2 + random.nextDouble();
                }
                int limit = 1 + random.nextInt(12);

                assertSameRanking(store.search(terms, weights, limit, epoch),
                    exhaustive(segments, terms, weights, limit, Bm25Parameters.DEFAULT),
                    "trial " + trial + ", terms " + String.join(",", terms));
            }
        }
    }

    @Test
    @DisplayName("a tuned BM25 pair does not rescue a bound measured on the wrong average")
    void tunedParametersAcrossSegments(@TempDir Path dir) throws Exception {
        Bm25Parameters tuned = new Bm25Parameters(2.0, 0.9);
        Map<String, String> lengthy = new LinkedHashMap<>();
        for (int i = 0; i < 120; i++) {
            lengthy.put("long-" + i, filler("f", 800));
        }
        Map<String, String> brief = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            brief.put(String.format("short-%02d", i), "alpha alpha alpha r1 r2");
        }
        brief.put("short-beta", "beta " + filler("q", 60));

        try (LexicalStore store = LexicalStore.open(dir, tuned)) {
            store.add(lengthy, 1);
            store.add(brief, 2);

            String[] terms = {"alpha", "beta"};
            double[] weights = {1.0, 1.0};
            for (int limit = 1; limit <= 10; limit++) {
                assertSameRanking(store.search(terms, weights, limit, 9),
                    exhaustive(List.of(lengthy, brief), terms, weights, limit, tuned),
                    "tuned pair, limit " + limit);
            }
        }
    }

    @Test
    @DisplayName("one segment scored against another collection's statistics stays exact")
    void statisticsSuppliedByTheCaller(@TempDir Path dir) throws Exception {
        // The same unsoundness at the level it actually lives at: a single index, told to score
        // against an average document length that is not its own.
        PostingsWriter writer = new PostingsWriter();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            texts.add(SHORT_ALPHA);
        }
        texts.add("beta " + filler("q", 99));
        for (String text : texts) {
            writer.add(text);
        }
        writer.write(dir);

        try (PostingsIndex index = PostingsIndex.open(dir)) {
            String[] terms = {"alpha", "beta"};
            double[] weights = {1.0, 1.0};
            int documents = 221;
            double average = 906.33484162895921;
            Map<String, Integer> frequencies = Map.of("alpha", 20, "beta", 1);
            PostingsIndex.Stats stats = new PostingsIndex.Stats(documents, average,
                term -> frequencies.getOrDefault(term, 1));

            PostingsIndex.Hit[] hits = index.search(terms, weights, 5, stats);

            // Derived from the same statistics, by scoring every document of every term.
            int[] lengths = index.documentLengths();
            double[] scores = new double[index.docCount()];
            for (int t = 0; t < terms.length; t++) {
                int termId = index.termId(terms[t]);
                PostingsIndex.Row row = index.row(termId);
                int df = frequencies.get(terms[t]);
                double idf = Math.log(1.0 + (documents - df + 0.5) / (df + 0.5));
                for (int i = 0; i < row.docs().length; i++) {
                    int doc = row.docs()[i];
                    scores[doc] += weights[t] * idf * Bm25Parameters.DEFAULT.saturation(
                        row.freqs()[i], Bm25Parameters.DEFAULT.lengthNorm(lengths[doc], average));
                }
            }
            double[] sorted = scores.clone();
            java.util.Arrays.sort(sorted);

            assertThat(hits).hasSize(5);
            for (int rank = 0; rank < 5; rank++) {
                assertThat(hits[rank].score()).as("rank %d", rank)
                    .isCloseTo(sorted[sorted.length - 1 - rank], Offset.offset(1e-9));
            }
        }
    }

    /**
     * Puts an index back into the shape it had before the block header carried the ingredients.
     *
     * <p>Every block's impact field becomes the bound as the old writers computed it - the
     * largest impact the block can have <em>under this segment's own mean length</em> - and the
     * metadata goes back to the version that means it. An index in this shape is the one an
     * existing deployment has on disk, and its bounds are exactly the ones that cannot be
     * trusted once the segment is one of several.
     */
    private static void degradeToBakedInBlockBounds(Path directory) throws Exception {
        Path metaFile = directory.resolve("index.meta");
        byte[] meta = java.nio.file.Files.readAllBytes(metaFile);
        java.nio.ByteBuffer.wrap(meta).putInt(12, 1);
        java.nio.file.Files.write(metaFile, meta);
        double average = java.nio.ByteBuffer.wrap(meta).getDouble(16);

        Path blockFile = directory.resolve("post.blk");
        byte[] blocks = java.nio.file.Files.readAllBytes(blockFile);
        java.nio.ByteBuffer view = java.nio.ByteBuffer.wrap(blocks);
        for (int at = 0; at < blocks.length; at += BlockLayout.RECORD_BYTES) {
            int maxFrequency = blocks[at + BlockLayout.OFFSET_MAX_FREQUENCY] & 0xFF;
            int minLength = ((blocks[at + BlockLayout.OFFSET_MIN_LENGTH] & 0xFF) << 16)
                | ((blocks[at + BlockLayout.OFFSET_MIN_LENGTH + 1] & 0xFF) << 8)
                | (blocks[at + BlockLayout.OFFSET_MIN_LENGTH + 2] & 0xFF);
            view.putFloat(at + BlockLayout.OFFSET_MAX_FREQUENCY,
                (float) Bm25Parameters.DEFAULT.saturation(maxFrequency,
                    Bm25Parameters.DEFAULT.lengthNorm(minLength, average)));
        }
        java.nio.file.Files.write(blockFile, blocks);
    }

    @Test
    @DisplayName("an index written before the header carried the ingredients is still exact")
    void indexWithBakedInBoundsFallsBackToTheCeiling(@TempDir Path dir) throws Exception {
        PostingsWriter writer = new PostingsWriter();
        for (int i = 0; i < 20; i++) {
            writer.add(SHORT_ALPHA);
        }
        writer.add("beta " + filler("q", 99));
        writer.write(dir);
        degradeToBakedInBlockBounds(dir);

        int documents = 221;
        double average = 906.33484162895921;
        Map<String, Integer> frequencies = Map.of("alpha", 20, "beta", 1);

        try (PostingsIndex index = PostingsIndex.open(dir)) {
            for (int termId = 0; termId < index.termCount(); termId++) {
                assertThat(index.termBound(termId, average))
                    .as("term %s falls back to the ceiling rather than trusting the stored bound",
                        index.termAt(termId))
                    .isGreaterThanOrEqualTo((float) Bm25Parameters.DEFAULT.maximumSaturation());
            }

            PostingsIndex.Hit[] hits = index.search(new String[] {"alpha", "beta"},
                new double[] {1.0, 1.0}, 5,
                new PostingsIndex.Stats(documents, average,
                    term -> frequencies.getOrDefault(term, 1)));

            double idfBeta = Math.log(1.0 + (documents - 1 + 0.5) / 1.5);
            double betaScore = idfBeta * Bm25Parameters.DEFAULT.saturation(1,
                Bm25Parameters.DEFAULT.lengthNorm(100, average));
            assertThat(hits[0].score())
                .as("the document the stored bound would have pruned is still first")
                .isCloseTo(betaScore, Offset.offset(1e-9));
        }
    }

    @Test
    @DisplayName("no posting can score above its term's bound under the query's own average")
    void boundsCoverEveryPostingAtAnyAverage(@TempDir Path dir) throws Exception {
        Random random = new Random(31337L);
        String[] vocabulary = {"the", "of", "memory", "kernel", "sigkill", "enomem", "alpha"};
        PostingsWriter writer = new PostingsWriter();
        for (int d = 0; d < 400; d++) {
            StringBuilder text = new StringBuilder();
            for (int w = 0; w < 1 + random.nextInt(200); w++) {
                text.append(vocabulary[random.nextInt(vocabulary.length)]).append(' ');
            }
            writer.add(text.toString());
        }
        writer.write(dir);

        try (PostingsIndex index = PostingsIndex.open(dir)) {
            int[] lengths = index.documentLengths();
            // Means far below and far above the one this index would measure for itself, which
            // is the whole span a collection of unevenly shaped segments can present.
            for (double average : new double[] {0.5, 1.0, 7.0, index.averageDocumentLength(),
                    5_000.0, 1_000_000.0}) {
                for (int termId = 0; termId < index.termCount(); termId++) {
                    double bound = index.termBound(termId, average);
                    PostingsIndex.Row row = index.row(termId);
                    for (int i = 0; i < row.docs().length; i++) {
                        double impact = Bm25Parameters.DEFAULT.saturation(row.freqs()[i],
                            Bm25Parameters.DEFAULT.lengthNorm(lengths[row.docs()[i]], average));
                        assertThat(bound)
                            .as("term '%s', document %d, mean length %s",
                                row.term(), row.docs()[i], average)
                            .isGreaterThanOrEqualTo(impact);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("an index searched against its own statistics is unaffected")
    void ownStatisticsStillExact(@TempDir Path dir) throws Exception {
        // The case the engine already had covered, kept so a fix for the cross-segment bound
        // cannot be mistaken for a change in how a single index scores on its own.
        Map<String, String> only = ordered(
            "a", "alpha alpha beta",
            "b", "beta gamma gamma gamma",
            "c", "alpha gamma delta delta");
        try (LexicalStore store = LexicalStore.open(dir)) {
            store.add(only, 1);
            String[] terms = {"alpha", "gamma"};
            double[] weights = {1.0, 0.5};
            assertSameRanking(store.search(terms, weights, 3, 9),
                exhaustive(List.of(only), terms, weights, 3, Bm25Parameters.DEFAULT),
                "single segment");
        }
    }
}
