package com.eonmux.excalibur.graph.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.roaringbitmap.IntIterator;
import org.roaringbitmap.RoaringBitmap;

/**
 * Takes one hop: turns a frontier of entities into the set they reach.
 *
 * <p>Hops are strictly dependent — hop <i>n</i>'s frontier does not exist until hop <i>n-1</i>
 * finishes — so there is no parallelism between hops and all of it is here, across the entities of
 * a single frontier. Those are independent: each reads its own runs and writes its own result.
 *
 * <p>Every task builds a private bitmap that is merged at the end, because {@link RoaringBitmap} is
 * not safe for concurrent mutation. Merging is cheap next to the page faults the scan itself
 * causes.
 *
 * <p>Virtual threads rather than a pool, because the work is dominated by waiting on page faults
 * rather than by computation: the useful degree of parallelism is set by how many faults the device
 * can have outstanding, not by core count, and a fixed pool sized to cores would leave the device
 * idle.
 */
final class FrontierExpander {

    /**
     * Below this many entities a hop runs inline.
     *
     * <p>Most hops in a retrieval traversal are small — a handful of seeds, then their immediate
     * neighbours — and for those the cost of handing work to another thread and merging a bitmap
     * back exceeds the scan. Parallelism only pays once a frontier is wide.
     */
    private static final int PARALLEL_THRESHOLD = 256;

    /**
     * Entities per task. Large enough that a task does real work, small enough that one
     * high-degree entity in a chunk does not hold up the hop.
     */
    private static final int CHUNK_SIZE = 64;

    /** A stop signal that never fires, for a walk nothing bounds. */
    private static final BooleanSupplier NEVER = () -> false;

    private final MappedEdgeStore store;

    FrontierExpander(MappedEdgeStore store) {
        this.store = store;
    }

    /**
     * Entities reachable in one hop from {@code frontier}.
     *
     * <p>The result may include entities already visited; the caller subtracts what it has seen,
     * because only the caller knows the traversal's history.
     */
    RoaringBitmap expandFrontier(RoaringBitmap frontier, int[] selectors, TemporalFilter asOf) {
        return expandFrontier(frontier, selectors, asOf, NEVER);
    }

    /**
     * As {@link #expandFrontier(RoaringBitmap, int[], TemporalFilter)}, giving the hop up when
     * {@code stop} fires.
     *
     * @return the entities reached, or null when the hop was stopped before it finished
     */
    RoaringBitmap expandFrontier(RoaringBitmap frontier, int[] selectors, TemporalFilter asOf,
                                 BooleanSupplier stop) {
        int cardinality = frontier.getCardinality();
        if (cardinality == 0) {
            return new RoaringBitmap();
        }
        if (cardinality < PARALLEL_THRESHOLD) {
            return expandRange(frontier.toArray(), 0, cardinality, selectors, asOf, stop);
        }
        return expandParallel(frontier.toArray(), selectors, asOf, stop);
    }

    /**
     * The {@code keep} candidates reached from {@code frontier} along the strongest edges.
     *
     * <p>Ranked by the strongest admitted edge from any frontier entity to each candidate - see
     * {@link MappedEdgeStore#expand(long[], int, int[], TemporalFilter, int, BooleanSupplier)} -
     * with the lower entity id winning a tie, so the choice does not depend on scan order.
     * Sequential rather than parallel: it runs only when a hop has already overflowed its cap,
     * and taking a maximum needs no merge to be deterministic, but it does need one to be
     * parallel.</p>
     *
     * @param frontier   the hop's frontier
     * @param candidates the new entities the hop reached, more than {@code keep} of them
     * @param selectors  selectors to follow, or null for every relation both ways
     * @param asOf       valid-time window
     * @param keep       how many to keep
     * @param stop       true once the walk should stop
     * @return the kept entities, or null when {@code stop} fired first
     */
    RoaringBitmap strongest(RoaringBitmap frontier, RoaringBitmap candidates, int[] selectors,
                            TemporalFilter asOf, int keep, BooleanSupplier stop) {
        int[] ids = candidates.toArray();
        long[] best = new long[ids.length];
        Arrays.fill(best, -1L);
        IntIterator entities = frontier.getIntIterator();
        while (entities.hasNext()) {
            if (stop.getAsBoolean()) {
                return null;
            }
            store.strengthenNeighbors(entities.next(), selectors, asOf, candidates, best);
        }

        // The strength of the keep-th strongest, then every candidate above it and, in id order,
        // as many of those equal to it as fit.
        long[] ranked = best.clone();
        Arrays.sort(ranked);
        long threshold = ranked[ranked.length - keep];
        int above = 0;
        for (long strength : best) {
            if (strength > threshold) {
                above++;
            }
        }
        int ties = keep - above;
        RoaringBitmap kept = new RoaringBitmap();
        for (int i = 0; i < ids.length; i++) {
            if (best[i] > threshold) {
                kept.add(ids[i]);
            } else if (best[i] == threshold && ties > 0) {
                kept.add(ids[i]);
                ties--;
            }
        }
        return kept;
    }

    private RoaringBitmap expandParallel(int[] entities, int[] selectors, TemporalFilter asOf,
                                         BooleanSupplier stop) {
        List<Future<RoaringBitmap>> futures = new ArrayList<>(
            (entities.length + CHUNK_SIZE - 1) / CHUNK_SIZE);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int start = 0; start < entities.length; start += CHUNK_SIZE) {
                int from = start;
                int to = Math.min(start + CHUNK_SIZE, entities.length);
                futures.add(executor.submit(
                    () -> expandRange(entities, from, to, selectors, asOf, stop)));
            }
            // The try-with-resources close() awaits every task, so the results below are all
            // complete by the time they are read.
        }

        RoaringBitmap merged = new RoaringBitmap();
        for (Future<RoaringBitmap> future : futures) {
            RoaringBitmap part = get(future);
            if (part == null) {
                return null;
            }
            merged.or(part);
        }
        return merged;
    }

    /** One chunk of a hop, or null when {@code stop} fired before it finished. */
    private RoaringBitmap expandRange(int[] entities, int from, int to,
                                      int[] selectors, TemporalFilter asOf, BooleanSupplier stop) {
        RoaringBitmap local = new RoaringBitmap();
        for (int i = from; i < to; i++) {
            if (stop.getAsBoolean()) {
                return null;
            }
            store.collectNeighbors(entities[i], selectors, asOf, local);
        }
        return local;
    }

    private static RoaringBitmap get(Future<RoaringBitmap> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while expanding a frontier", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("frontier expansion failed", cause);
        }
    }
}
