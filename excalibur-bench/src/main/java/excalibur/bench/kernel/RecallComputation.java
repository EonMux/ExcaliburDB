package excalibur.bench.kernel;

import io.excalibur.vector.distance.DistanceFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Brute-force ground truth and the recall arithmetic scored against it.
 *
 * <p>An approximate index is only as trustworthy as the exact answer it is compared
 * with. This class computes that exact answer by scanning the whole dataset, and
 * counts how much of it an approximate result recovered.</p>
 *
 * <h2>Thread safety</h2>
 * <p>The static methods are stateless and thread-safe. {@link Tally} is not.</p>
 */
public final class RecallComputation {

    /**
     * The exact {@code k} nearest neighbours of a query, by brute-force scan.
     *
     * <p>Ranks by {@link DistanceFunction#distance}, never by {@code compute}: a
     * similarity metric such as cosine reports <em>larger</em> values for closer
     * vectors, so ranking its raw output ascending returns the {@code k} most
     * dissimilar vectors and labels them ground truth.</p>
     *
     * @param query the query vector
     * @param dataset the vectors to search, indexed by node id
     * @param k how many neighbours to return
     * @param distanceFunction the metric
     * @return the node ids of the nearest neighbours, closest first; at most
     *         {@code min(k, dataset.length)} of them
     * @throws IllegalArgumentException if {@code k} is not positive
     */
    public static List<Long> trueTopK(
            float[] query, float[][] dataset, int k, DistanceFunction distanceFunction) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(distanceFunction, "distanceFunction");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, got " + k);
        }

        // A max-heap of the best k seen so far: the worst of them sits at the head and
        // is what a new candidate has to beat, which keeps the scan at O(n log k).
        PriorityQueue<Neighbour> best = new PriorityQueue<>(
            Comparator.comparingDouble(Neighbour::distance).reversed());

        for (int id = 0; id < dataset.length; id++) {
            float distance = distanceFunction.distance(query, dataset[id]);
            if (best.size() < k) {
                best.offer(new Neighbour(id, distance));
            } else if (distance < best.peek().distance()) {
                best.poll();
                best.offer(new Neighbour(id, distance));
            }
        }

        List<Long> nearestFirst = new ArrayList<>(best.size());
        while (!best.isEmpty()) {
            nearestFirst.add(best.poll().nodeId());
        }
        java.util.Collections.reverse(nearestFirst);
        return nearestFirst;
    }

    /**
     * The share of the ground truth that a retrieved set recovered.
     *
     * <p>The denominator is the size of the ground truth, so returning extra ids
     * cannot raise the score - only returning the right ones can.</p>
     *
     * @param retrieved the ids an index returned
     * @param groundTruth the ids a brute-force scan returned
     * @return recall in {@code [0, 1]}, or 0 when there is no ground truth
     */
    public static double recall(Collection<Long> retrieved, Collection<Long> groundTruth) {
        Objects.requireNonNull(retrieved, "retrieved");
        Objects.requireNonNull(groundTruth, "groundTruth");
        if (groundTruth.isEmpty()) {
            return 0.0;
        }
        return (double) hits(retrieved, groundTruth) / groundTruth.size();
    }

    private static int hits(Collection<Long> retrieved, Collection<Long> groundTruth) {
        java.util.Set<Long> found = retrieved instanceof java.util.Set<Long> set
            ? set
            : new java.util.HashSet<>(retrieved);
        int hits = 0;
        for (Long expected : groundTruth) {
            if (found.contains(expected)) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * Accumulates recall over many queries.
     *
     * <p>Aggregating as {@code totalFound / totalExpected} rather than as a mean of
     * per-query recalls is what keeps queries with fewer than {@code k} true
     * neighbours from being weighted as heavily as full ones.</p>
     *
     * <p>Mutable accumulator; confine one instance to one thread.</p>
     */
    public static final class Tally {

        private long found;
        private long expected;

        /**
         * Records one query's outcome.
         *
         * @param retrieved the ids the index returned
         * @param groundTruth the ids a brute-force scan returned
         */
        public void record(Collection<Long> retrieved, Collection<Long> groundTruth) {
            Objects.requireNonNull(retrieved, "retrieved");
            Objects.requireNonNull(groundTruth, "groundTruth");
            found += hits(retrieved, groundTruth);
            expected += groundTruth.size();
        }

        /**
         * The recall accumulated so far.
         *
         * @return recall in {@code [0, 1]}, or 0 when nothing has been recorded
         */
        public double recall() {
            return expected == 0 ? 0.0 : (double) found / expected;
        }

        /**
         * The number of ground-truth neighbours recovered so far.
         *
         * @return the hit count
         */
        public long found() {
            return found;
        }

        /**
         * The number of ground-truth neighbours sought so far.
         *
         * @return the expected count
         */
        public long expected() {
            return expected;
        }
    }

    private record Neighbour(long nodeId, float distance) {}

    private RecallComputation() {
        // Utility class
    }
}
