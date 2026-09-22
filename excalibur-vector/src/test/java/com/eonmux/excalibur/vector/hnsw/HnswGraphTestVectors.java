package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.DistanceFunction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Vector fixtures and brute-force ground truth shared by the {@link HnswGraph} test classes.
 *
 * <p>Every {@code HnswGraph} suite needs vectors to feed the graph, and the search and
 * incremental-insertion suites additionally need an exact k-NN baseline to measure recall
 * against. Keeping both here means the graph under test is the only thing that differs
 * between suites: they all index the same shapes of data and score recall the same way.</p>
 */
final class HnswGraphTestVectors {

    /**
     * The one seed behind every {@link #randomVector(int)} in a run.
     *
     * <p>{@code randomVector} used to open a fresh unseeded {@code Random} per call, which
     * made the data that the deletion, adjacency and error-handling suites index different
     * on every run — so a failure in any of them named a graph nobody could rebuild. The
     * suites that already care, such as the recall tests, pass their own seeded
     * {@code Random} to {@link #generateNormalizedVector}; this gives the callers that do
     * not the same guarantee.</p>
     */
    private static final long VECTOR_SEED = 0x5CA1AB1EL;

    /**
     * The single stream {@link #randomVector(int)} draws from.
     *
     * <p>One shared stream rather than a fresh {@code Random} per call seeded from a
     * counter: consecutive seeds differ by one, and {@code Random}'s first output is very
     * nearly a linear function of its seed, so counter-seeded generators would hand out
     * vectors whose leading component barely moved. Successive draws from one stream are
     * decorrelated, which is what the callers want from a vector that is only required to
     * be arbitrary.</p>
     *
     * <p>What this does and does not buy: the n-th call in a JVM always returns the same
     * vector, so re-running a single test class reproduces its data exactly. Gradle spreads
     * test classes across forked JVMs, so in a whole-module run which draw a given class
     * sees still depends on what ran before it in its fork; reproduce such a failure by
     * running that class on its own.</p>
     */
    private static final Random VECTOR_SOURCE = new Random(VECTOR_SEED);

    private HnswGraphTestVectors() {
    }

    /**
     * Generates a random vector with components in the range [-1, 1].
     *
     * <p>Drawn from a fixed-seed stream: distinct on every call, and the same on every run
     * for a given sequence of calls. See {@link #VECTOR_SOURCE}.</p>
     *
     * @param dim The vector's length
     * @return A freshly drawn vector
     */
    static float[] randomVector(int dim) {
        float[] vec = new float[dim];
        // One vector is one contiguous run of the stream: without this, two threads drawing
        // at once would each get a vector stitched from both their draws, and neither run
        // would be reproducible.
        synchronized (VECTOR_SOURCE) {
            for (int i = 0; i < dim; i++) {
                vec[i] = VECTOR_SOURCE.nextFloat() * 2 - 1; // Range [-1, 1]
            }
        }
        return vec;
    }

    /**
     * Generates a basis vector that is 1.0 at {@code hotIndex} and zero elsewhere.
     */
    static float[] unitVector(int dim, int hotIndex) {
        float[] vec = new float[dim];
        vec[hotIndex % dim] = 1.0f;
        return vec;
    }

    /**
     * Generates a normalized random vector.
     * Normalization ensures consistent distance calculations.
     */
    static float[] generateNormalizedVector(int dimension, Random random) {
        float[] vec = new float[dimension];
        float norm = 0.0f;
        for (int i = 0; i < dimension; i++) {
            vec[i] = (float) random.nextGaussian();  // Gaussian for better distribution
            norm += vec[i] * vec[i];
        }
        // Normalize to unit length
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    /**
     * Brute-force k-NN search for ground truth.
     */
    static List<Integer> bruteForceSearch(float[] query, List<float[]> vectors,
                                          DistanceFunction distFn, int k) {
        List<SearchCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            float dist = distFn.compute(query, vectors.get(i));
            candidates.add(new SearchCandidate(i, dist));
        }

        candidates.sort((a, b) -> Float.compare(a.distance, b.distance));

        List<Integer> result = new ArrayList<>(k);
        for (int i = 0; i < Math.min(k, candidates.size()); i++) {
            result.add(candidates.get(i).index);
        }
        return result;
    }

    /**
     * Calculates recall as the fraction of ground truth results found.
     */
    static double calculateRecall(List<HnswSearchResult> hnswResults, List<Integer> groundTruth) {
        if (groundTruth.isEmpty()) {
            return 1.0;  // No ground truth to miss
        }

        Set<Long> hnswIds = new HashSet<>();
        for (HnswSearchResult result : hnswResults) {
            hnswIds.add(result.nodeId());
        }

        int found = 0;
        for (Integer gtId : groundTruth) {
            if (hnswIds.contains(gtId.longValue())) {
                found++;
            }
        }

        return (double) found / groundTruth.size();
    }

    private record SearchCandidate(int index, float distance) {
    }
}
