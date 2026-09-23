package com.eonmux.excalibur.vector.hnsw;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Reusable visited sets for graph walks, one borrowed per walk.
 *
 * <h2>Why a pool</h2>
 * <p>A beam walk has to remember which nodes it has already reached. A fresh set per walk is
 * allocated and zeroed at the size of the whole graph, so its cost grows with the graph's
 * capacity rather than with the few hundred nodes a walk actually touches - and a build runs
 * one walk per layer per insert. Here each {@link Marker} keeps an array of epoch stamps
 * instead: a node counts as visited when its stamp equals the marker's current epoch, and
 * starting a new walk only advances the epoch. The array is cleared only when the epoch
 * wraps, once in about two billion walks.</p>
 *
 * <p>This is the scheme hnswlib's {@code VisitedListPool} uses.</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@link #borrow} and {@link #release} are thread-safe. A borrowed marker belongs to one
 * walk on one thread until it is released, and is never handed to two walks at once; a pool
 * therefore holds as many markers as the graph has ever had concurrent walks.</p>
 */
final class HnswVisitedPool {

    private final ConcurrentLinkedDeque<Marker> idle = new ConcurrentLinkedDeque<>();

    /**
     * Takes a marker that remembers nothing, able to mark any id below {@code nodeLimit}.
     *
     * @param nodeLimit The exclusive upper bound on the ids the walk will mark
     * @return A marker with no node visited, to be passed back to {@link #release}
     */
    Marker borrow(long nodeLimit) {
        if (nodeLimit < 0 || nodeLimit > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("node limit out of range: " + nodeLimit);
        }
        Marker marker = idle.pollFirst();
        if (marker == null) {
            marker = new Marker((int) nodeLimit, Integer.MAX_VALUE);
        }
        marker.reset((int) nodeLimit);
        return marker;
    }

    /**
     * Returns a marker for a later walk. It must not be used again by the caller.
     *
     * @param marker A marker this pool lent
     */
    void release(Marker marker) {
        idle.offerFirst(marker);
    }

    /**
     * The visited set of one walk.
     */
    static final class Marker {

        private final int lastEpoch;
        private int[] stamps;
        private int epoch;

        /**
         * Creates a marker with no walk begun.
         *
         * @param capacity The ids the marker can hold
         * @param lastEpoch The last epoch before the stamps are cleared and counting restarts;
         *        the pool uses {@code Integer.MAX_VALUE}, and a test a small value to reach it
         */
        Marker(int capacity, int lastEpoch) {
            if (lastEpoch < 1) {
                throw new IllegalArgumentException("last epoch must be positive: " + lastEpoch);
            }
            this.stamps = new int[capacity];
            this.lastEpoch = lastEpoch;
        }

        /**
         * Forgets every visit and makes room for ids below {@code nodeLimit}.
         */
        void reset(int nodeLimit) {
            if (stamps.length < nodeLimit) {
                // Grown to half again the request, so a graph filling one node at a time does
                // not reallocate on every walk.
                int grown = (int) Math.min(Integer.MAX_VALUE, nodeLimit + (long) nodeLimit / 2);
                stamps = Arrays.copyOf(stamps, grown);
            }
            if (epoch == lastEpoch) {
                Arrays.fill(stamps, 0);
                epoch = 0;
            }
            epoch++;
        }

        /**
         * Marks a node visited.
         *
         * @param nodeId An id below the node limit the marker was borrowed or reset for
         * @return true if this is the node's first visit in the current walk
         */
        boolean visit(int nodeId) {
            if (stamps[nodeId] == epoch) {
                return false;
            }
            stamps[nodeId] = epoch;
            return true;
        }

        /**
         * The number of ids the marker can currently hold.
         *
         * @return The capacity
         */
        int capacity() {
            return stamps.length;
        }
    }
}
