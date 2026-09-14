package io.excalibur.retrieval.lexical.native_;

import io.excalibur.retrieval.lexical.Bm25Parameters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skipping must not change the answer.
 *
 * <p>WAND earns its speed by never scoring documents that cannot reach the top-k. That is only
 * sound if "cannot" is genuinely certain, and the failure mode when it is not is quiet: the right
 * documents come back in the wrong order, or a document that belonged in the results is missing,
 * and both look like an ordinary ranking. An earlier attempt at block skipping in this class did
 * exactly that - it left recall intact while moving MRR and nDCG down, and was only caught by
 * comparing against another engine.
 *
 * <p>So these compare the optimised search against a brute-force scorer that considers every
 * document and every term, over many random corpora and queries. Any divergence at all is a bug.
 */
@DisplayName("WAND exactness")
class WandExactnessTest {

    /** Scores every document against every term, with no skipping whatsoever. */
    private static List<double[]> bruteForce(PostingsIndex index, String[] terms,
                                          double[] weights, int limit) {
        int docCount = index.docCount();
        double[] scores = new double[docCount];
        int[] lengths = index.documentLengths();
        long totalTokens = 0;
        for (int length : lengths) {
            totalTokens += length;
        }
        double average = docCount == 0 ? 1.0 : (double) totalTokens / docCount;
        // The index's own pair, so this stays a check on skipping rather than on which constants
        // the scorer happens to hold.
        Bm25Parameters bm25 = index.bm25();

        for (int t = 0; t < terms.length; t++) {
            int termId = index.termId(terms[t]);
            if (termId < 0) {
                continue;
            }
            PostingsIndex.Row row = index.row(termId);
            int df = row.docs().length;
            if (df == 0) {
                continue;
            }
            double idf = Math.log(1.0 + (docCount - df + 0.5) / (df + 0.5));
            for (int i = 0; i < df; i++) {
                int doc = row.docs()[i];
                int tf = row.freqs()[i];
                scores[doc] += weights[t] * idf
                    * bm25.saturation(tf, bm25.lengthNorm(lengths[doc], average));
            }
        }
        List<double[]> ranked = new ArrayList<>();
        Integer[] docs = new Integer[docCount];
        for (int i = 0; i < docCount; i++) {
            docs[i] = i;
        }
        java.util.Arrays.sort(docs, (a, b) -> Double.compare(scores[b], scores[a]));
        for (int i = 0; i < Math.min(limit, docCount); i++) {
            if (scores[docs[i]] <= 0) {
                break;
            }
            ranked.add(new double[] {docs[i], scores[docs[i]]});
        }
        return ranked;
    }

    @Test
    @DisplayName("optimised search returns exactly what exhaustive scoring returns")
    void matchesBruteForce(@TempDir Path root) throws Exception {
        Random random = new Random(1234);
        // A vocabulary with a steep frequency skew, so queries mix a term in almost every
        // document with terms in very few - which is the shape that makes skipping worthwhile
        // and the shape that breaks an unsound implementation.
        String[] common = {"the", "of", "and", "to", "a"};
        String[] mid = {"memory", "process", "system", "kernel", "patient", "journal"};
        String[] rare = {"sigkill", "enomem", "transhydrogenase", "nephilengys", "apocolocyntosis"};

        List<String> docs = new ArrayList<>();
        for (int i = 0; i < 900; i++) {
            StringBuilder sb = new StringBuilder();
            for (int w = 0; w < 30 + random.nextInt(90); w++) {
                double roll = random.nextDouble();
                if (roll < 0.6) {
                    sb.append(common[random.nextInt(common.length)]);
                } else if (roll < 0.95) {
                    sb.append(mid[random.nextInt(mid.length)]);
                } else {
                    sb.append(rare[random.nextInt(rare.length)]);
                }
                sb.append(' ');
            }
            docs.add(sb.toString());
        }

        PostingsWriter writer = new PostingsWriter();
        for (String doc : docs) {
            writer.add(doc);
        }
        writer.write(root);

        try (PostingsIndex index = PostingsIndex.open(root)) {
            List<String> pool = new ArrayList<>();
            pool.addAll(List.of(common));
            pool.addAll(List.of(mid));
            pool.addAll(List.of(rare));

            for (int trial = 0; trial < 300; trial++) {
                int termCount = 1 + random.nextInt(5);
                String[] terms = new String[termCount];
                double[] weights = new double[termCount];
                for (int i = 0; i < termCount; i++) {
                    terms[i] = pool.get(random.nextInt(pool.size()));
                    weights[i] = 0.2 + random.nextDouble();
                }
                int limit = 1 + random.nextInt(15);

                PostingsIndex.Hit[] fast = index.search(terms, weights, limit);
                List<double[]> slow = bruteForce(index, terms, weights, limit);

                // Scores, not doc ids. Documents can tie exactly, and when more documents tie
                // than the limit admits, any of them is a correct answer - so a doc-id
                // comparison would fail on a difference that is not an error. The score at each
                // rank is the thing that must be identical: if it is, the optimised search found
                // a document just as good, and if it is not, it missed one.
                assertThat(fast.length)
                    .as("trial %d, terms %s", trial, String.join(",", terms))
                    .isEqualTo(slow.size());
                for (int i = 0; i < fast.length; i++) {
                    assertThat(fast[i].score())
                        .as("trial %d rank %d, terms %s", trial, i, String.join(",", terms))
                        .isCloseTo(slow.get(i)[1], org.assertj.core.data.Offset.offset(1e-9));
                }
            }
        }
    }

    @Test
    @DisplayName("skipping stays exact under a BM25 pair an operator chose")
    void matchesBruteForceUnderTunedParameters(@TempDir Path root) throws Exception {
        // The trap this whole parameterisation has to avoid. Block bounds are computed from k1
        // and b at build time and the scorer prunes against them, so a pair that reached one
        // half and not the other would prune blocks holding real results - quietly, and only on
        // some queries. Exhaustive scoring under the same pair is what catches that.
        Bm25Parameters tuned = new Bm25Parameters(2.5, 0.2);
        Random random = new Random(4242);
        String[] common = {"the", "of", "and", "to", "a"};
        String[] mid = {"memory", "process", "system", "kernel", "patient", "journal"};
        String[] rare = {"sigkill", "enomem", "transhydrogenase", "nephilengys", "apocolocyntosis"};

        PostingsWriter writer = new PostingsWriter(tuned);
        for (int i = 0; i < 600; i++) {
            StringBuilder sb = new StringBuilder();
            for (int w = 0; w < 20 + random.nextInt(90); w++) {
                double roll = random.nextDouble();
                if (roll < 0.6) {
                    sb.append(common[random.nextInt(common.length)]);
                } else if (roll < 0.95) {
                    sb.append(mid[random.nextInt(mid.length)]);
                } else {
                    sb.append(rare[random.nextInt(rare.length)]);
                }
                sb.append(' ');
            }
            writer.add(sb.toString());
        }
        writer.write(root);

        try (PostingsIndex index = PostingsIndex.open(root, tuned)) {
            List<String> pool = new ArrayList<>();
            pool.addAll(List.of(common));
            pool.addAll(List.of(mid));
            pool.addAll(List.of(rare));

            for (int trial = 0; trial < 200; trial++) {
                int termCount = 1 + random.nextInt(5);
                String[] terms = new String[termCount];
                double[] weights = new double[termCount];
                for (int i = 0; i < termCount; i++) {
                    terms[i] = pool.get(random.nextInt(pool.size()));
                    weights[i] = 0.2 + random.nextDouble();
                }
                int limit = 1 + random.nextInt(15);

                PostingsIndex.Hit[] fast = index.search(terms, weights, limit);
                List<double[]> slow = bruteForce(index, terms, weights, limit);

                assertThat(fast.length)
                    .as("trial %d, terms %s", trial, String.join(",", terms))
                    .isEqualTo(slow.size());
                for (int i = 0; i < fast.length; i++) {
                    assertThat(fast[i].score())
                        .as("trial %d rank %d, terms %s", trial, i, String.join(",", terms))
                        .isCloseTo(slow.get(i)[1], org.assertj.core.data.Offset.offset(1e-9));
                }
            }
        }
    }

    @Test
    @DisplayName("a query of only the commonest words still ranks exactly")
    void allStopwordQuery(@TempDir Path root) throws Exception {
        // The pathological case for skipping: every term matches nearly every document, so the
        // threshold rises slowly and the pivot moves little. It must still be exact.
        Random random = new Random(77);
        PostingsWriter writer = new PostingsWriter();
        for (int i = 0; i < 400; i++) {
            StringBuilder sb = new StringBuilder();
            for (int w = 0; w < 40; w++) {
                sb.append(random.nextBoolean() ? "the " : "of ");
            }
            sb.append("doc").append(i);
            writer.add(sb.toString());
        }
        writer.write(root);

        try (PostingsIndex index = PostingsIndex.open(root)) {
            String[] terms = {"the", "of"};
            double[] weights = {1.0, 1.0};
            PostingsIndex.Hit[] fast = index.search(terms, weights, 10);
            List<double[]> slow = bruteForce(index, terms, weights, 10);

            assertThat(fast.length).isEqualTo(slow.size());
            for (int i = 0; i < fast.length; i++) {
                assertThat(fast[i].score()).as("rank %d", i)
                    .isCloseTo(slow.get(i)[1], org.assertj.core.data.Offset.offset(1e-9));
            }
        }
    }
}
