package com.eonmux.excalibur.graph.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import com.eonmux.excalibur.core.memory.MappedFootprint;
import com.eonmux.excalibur.graph.format.EdgeColumn;
import com.eonmux.excalibur.graph.format.EdgeStoreDescriptor;
import com.eonmux.excalibur.graph.format.EdgeStoreIntegrity;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.roaringbitmap.RoaringBitmap;

/**
 * {@link EdgeStore} over mmap'd CSR columns.
 *
 * <p>Adjacency lives in the page cache rather than the heap, so a graph far larger than the JVM's
 * memory is queryable and a restart does not have to rebuild anything. The collector never walks
 * it, because to the collector it is not there.
 *
 * <p>Both indexes are sorted by (entity, relation) jointly, which makes a relation-filtered lookup
 * a binary search to a contiguous run instead of a scan of the entity's whole adjacency list
 * testing each element. On a high-degree entity — the ones a retrieval traversal actually hits —
 * that is the difference between touching a few cache lines and touching thousands.
 *
 * <p>Reads are safe from any number of threads. {@link #close()} may race with them: the shared
 * arena's handshake turns a racing read into an {@link IllegalStateException} rather than a read of
 * unmapped memory.
 */
public final class MappedEdgeStore implements EdgeStore, MappedFootprint {

    private final MappedColumns columns;
    private final EdgeStoreDescriptor descriptor;
    private final long entityCount;
    private final FrontierExpander expander;

    private MappedEdgeStore(MappedColumns columns) {
        this.columns = columns;
        this.descriptor = columns.descriptor();
        this.entityCount = descriptor.entityCount();
        this.expander = new FrontierExpander(this);
    }

    /**
     * Opens the store in {@code directory}, verifying every column before it is mapped.
     *
     * <p>See {@link EdgeStoreIntegrity} for why the whole store is digested on every open rather
     * than trusted once its sizes add up.</p>
     *
     * @throws com.eonmux.excalibur.graph.file.GraphCorruptionException if the descriptor or a
     *         column is missing, the wrong size, or does not match the checksum the build
     *         recorded for it
     * @throws IOException if a file cannot be read
     * @throws EdgeStoreDescriptor.IncompatibleStoreException if it was written with other widths
     *         or a format version this build does not read
     */
    public static MappedEdgeStore open(Path directory) throws IOException {
        EdgeStoreDescriptor descriptor = EdgeStoreIntegrity.requireIntact(directory);
        return new MappedEdgeStore(MappedColumns.open(directory, descriptor));
    }

    /** How many entity ids this store addresses. */
    public long entityCount() {
        return entityCount;
    }

    /** How many edges it holds. */
    public long edgeCount() {
        return descriptor.edgeCount();
    }

    /**
     * How many memory mappings an open store holds.
     *
     * <p>One per column, always, whatever the store's size. A namespace registry needs this to
     * budget against {@code vm.max_map_count} before opening one, and a guessed figure there means
     * either evicting too eagerly or discovering the kernel limit by hitting it.
     */
    public static int mappingCount() {
        return EdgeColumn.values().length;
    }

    /**
     * How many file descriptors an open store holds: none.
     *
     * <p>A mapping outlives the channel that created it, so {@link MappedColumns} closes each
     * channel as soon as the file is mapped. Worth stating, because the natural assumption is one
     * descriptor per column, and budgeting for thirteen per namespace would cut the number of
     * tenants a process can hold for no reason.
     */
    public static int descriptorCount() {
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The static {@link #mappingCount()} answers it, because this store's footprint really is
     * fixed: one mapping per column whatever the store holds.
     */
    @Override
    public long mappings() {
        return mappingCount();
    }

    /**
     * {@inheritDoc}
     *
     * <p>None, for the reason {@link #descriptorCount()} gives.
     */
    @Override
    public long descriptors() {
        return descriptorCount();
    }

    // ---------------------------------------------------------------- EdgeStore

    /**
     * {@inheritDoc}
     *
     * <p><b>Valid time is not applied here.</b> This is the raw adjacency primitive and returns
     * every neighbour ever recorded, whatever interval its edge holds over. That is deliberate — it
     * is the accessor a rebuild or an audit needs — but it means it is the wrong call for
     * answering a user's question. For that use {@link #expand} with one hop, which filters as it
     * goes.
     */
    @Override
    public long[] neighbors(long entity, int[] relationFilter) {
        int id = checkEntity(entity);
        RoaringBitmap found = new RoaringBitmap();
        collectNeighbors(id, relationFilter, TemporalFilter.ALL, found);
        return toLongArray(found);
    }

    @Override
    public long[] expand(long[] seeds, int hops, int[] relWhitelist, TemporalFilter asOf) {
        if (seeds == null) {
            throw new IllegalArgumentException("seeds must not be null");
        }
        if (hops < 0) {
            throw new IllegalArgumentException("hops must not be negative, got " + hops);
        }
        if (asOf == null) {
            throw new IllegalArgumentException(
                "asOf must not be null; use TemporalFilter.ALL to ignore valid time");
        }

        RoaringBitmap visited = new RoaringBitmap();
        for (long seed : seeds) {
            visited.add(checkEntity(seed));
        }
        if (hops == 0 || visited.isEmpty()) {
            return toLongArray(visited);
        }

        RoaringBitmap frontier = visited.clone();
        for (int hop = 0; hop < hops; hop++) {
            // Hops are dependent: this hop's frontier is not known until the last one finished, so
            // the parallelism lives inside expandFrontier, across the frontier's entities.
            RoaringBitmap next = expander.expandFrontier(frontier, relWhitelist, asOf);
            next.andNot(visited);
            if (next.isEmpty()) {
                break;
            }
            visited.or(next);
            frontier = next;
        }
        return toLongArray(visited);
    }

    /**
     * As {@link #expand(long[], int, int[], TemporalFilter)}, with at most {@code maxFrontier}
     * entities admitted by each hop and a signal that stops the walk early.
     *
     * <p>A hub - an entity with thousands of neighbours - would otherwise hand every one of them to
     * the next hop, and the walk's cost would grow with the widest entity it touches rather than
     * with the question. So when a hop reaches more new entities than the cap, the ones kept are
     * those reached along the strongest edge, and the choice is deterministic: the edge's
     * extraction confidence first, then the later valid-time start (an undated edge is the
     * oldest), then the lower entity id. The same question on the same graph walks the same
     * entities every time, which is what lets a truncated answer be reproduced and explained.</p>
     *
     * <p>The seeds are always admitted: they are the entities the question named, not ones the
     * walk found. {@code stop} is read before each hop and as each frontier entity is expanded; a
     * hop it interrupts is discarded whole, so the walk keeps exactly the hops it finished rather
     * than an arbitrary part of one.</p>
     *
     * @param seeds        entities to start from
     * @param hops         maximum hops to take; {@code 0} returns the seeds alone
     * @param relWhitelist selectors to follow, or {@code null} for every relation both ways
     * @param asOf         valid-time window applied as edges are considered
     * @param maxFrontier  most new entities one hop may admit, at least 1
     * @param stop         true once the walk should stop where it is
     * @return what the walk reached and what it left out
     * @throws IllegalArgumentException as {@link #expand(long[], int, int[], TemporalFilter)} does,
     *                                  or if {@code maxFrontier} is below 1 or {@code stop} is null
     */
    public Expansion expand(long[] seeds, int hops, int[] relWhitelist, TemporalFilter asOf,
                            int maxFrontier, BooleanSupplier stop) {
        if (seeds == null) {
            throw new IllegalArgumentException("seeds must not be null");
        }
        if (hops < 0) {
            throw new IllegalArgumentException("hops must not be negative, got " + hops);
        }
        if (asOf == null) {
            throw new IllegalArgumentException(
                "asOf must not be null; use TemporalFilter.ALL to ignore valid time");
        }
        if (maxFrontier < 1) {
            throw new IllegalArgumentException("maxFrontier must be at least 1, got " + maxFrontier);
        }
        if (stop == null) {
            throw new IllegalArgumentException("stop must not be null");
        }

        RoaringBitmap visited = new RoaringBitmap();
        for (long seed : seeds) {
            visited.add(checkEntity(seed));
        }
        long dropped = 0;
        boolean stopped = false;
        RoaringBitmap frontier = visited.clone();
        for (int hop = 0; hop < hops && !frontier.isEmpty(); hop++) {
            if (stop.getAsBoolean()) {
                stopped = true;
                break;
            }
            RoaringBitmap next = expander.expandFrontier(frontier, relWhitelist, asOf, stop);
            if (next == null) {
                stopped = true;
                break;
            }
            next.andNot(visited);
            int reached = next.getCardinality();
            if (reached > maxFrontier) {
                next = expander.strongest(frontier, next, relWhitelist, asOf, maxFrontier, stop);
                if (next == null) {
                    stopped = true;
                    break;
                }
                dropped += reached - maxFrontier;
            }
            visited.or(next);
            frontier = next;
        }
        return new Expansion(toLongArray(visited), dropped, stopped);
    }

    /**
     * What a bounded expansion reached, and what it left out.
     *
     * @param reached         reachable entity ids including the seeds, ascending
     * @param entitiesDropped entities a hop reached and did not admit because of its cap
     * @param stopped         whether the stop signal ended the walk before its last hop
     */
    public record Expansion(long[] reached, long entitiesDropped, boolean stopped) {

        /** Whether a cap left anything out. */
        public boolean truncated() {
            return entitiesDropped > 0;
        }
    }

    @Override
    public long count(long subject, int relation) {
        int id = checkEntity(subject);
        boolean reverse = RelationSelector.isReverse(relation);
        int wanted = RelationSelector.relationOf(relation);

        EdgeColumn offsets = reverse ? EdgeColumn.OFFSETS_REV : EdgeColumn.OFFSETS_FWD;
        EdgeColumn rel = reverse ? EdgeColumn.REL_REV : EdgeColumn.REL_FWD;

        long start = columns.getLong(offsets, id);
        long end = columns.getLong(offsets, id + 1L);
        // Answered from the offset array and a binary search over the relation column: the
        // adjacency itself is never touched, which is what makes this usable as a planning input.
        long from = lowerBound(rel, start, end, wanted);
        long to = upperBound(rel, from, end, wanted);
        return to - from;
    }

    @Override
    public void close() {
        columns.close();
    }

    // ---------------------------------------------------------------- provenance

    /**
     * Where the edge at a forward position came from.
     *
     * <p>Materialises a record, so it is for explaining a result rather than for traversal. The
     * columns behind it are read directly by anything on a hot path.
     *
     * @param forwardEdgeIndex position in forward edge order
     */
    public Provenance provenanceOf(long forwardEdgeIndex) {
        return new Provenance(
            SourceType.fromByte(columns.getByte(EdgeColumn.SOURCE_TYPE, forwardEdgeIndex)),
            columns.getLong(EdgeColumn.SOURCE_ID, forwardEdgeIndex),
            columns.getByte(EdgeColumn.EXTRACTION_CONFIDENCE, forwardEdgeIndex) & 0xFF,
            columns.getShort(EdgeColumn.EXTRACTOR_VERSION, forwardEdgeIndex));
    }

    /** Valid interval start of the edge at a forward position, days since epoch. */
    public int validFrom(long forwardEdgeIndex) {
        return columns.getInt(EdgeColumn.VALID_FROM, forwardEdgeIndex);
    }

    /** Valid interval end of the edge at a forward position, or {@link TemporalFilter#OPEN}. */
    public int validTo(long forwardEdgeIndex) {
        return columns.getInt(EdgeColumn.VALID_TO, forwardEdgeIndex);
    }

    /**
     * Walks every edge in forward order.
     *
     * <p>The whole store, subject included — which a caller cannot reconstruct from a forward index
     * alone, because the subject lives in the offset array rather than beside the edge. Compaction,
     * export and verification all need it, and each deriving it independently would mean three
     * copies of the same offset walk.
     *
     * <p>Allocates nothing per edge: the visitor is handed primitives. It is an ordinary sequential
     * scan, so the pages are read once, in order.
     */
    public void forEachEdge(EdgeVisitor visitor) {
        if (visitor == null) {
            throw new IllegalArgumentException("visitor must not be null");
        }
        for (long entity = 0; entity < entityCount; entity++) {
            long start = columns.getLong(EdgeColumn.OFFSETS_FWD, entity);
            long end = columns.getLong(EdgeColumn.OFFSETS_FWD, entity + 1L);
            for (long position = start; position < end; position++) {
                visitor.visit(
                    (int) entity,
                    columns.getInt(EdgeColumn.REL_FWD, position),
                    columns.getInt(EdgeColumn.EDGES_FWD, position),
                    position);
            }
        }
    }

    /**
     * Walks the forward edges of one entity.
     *
     * <p>The bounded form of {@link #forEachEdge}, for a caller that already knows which
     * entities it cares about. Materialising a subgraph by scanning the whole store costs
     * O(|E|) per request however small the subgraph is, which is the opposite of what a CSR
     * adjacency exists to provide: latency then grows with the size of the graph rather than
     * with the size of the answer.</p>
     *
     * <p>Allocates nothing per edge, like the full walk.</p>
     *
     * @param entity  The subject whose edges to walk
     * @param visitor Receives each edge
     * @throws IllegalArgumentException if the entity is out of range or the visitor is null
     */
    public void forEachEdgeOf(long entity, EdgeVisitor visitor) {
        if (visitor == null) {
            throw new IllegalArgumentException("visitor must not be null");
        }
        int id = checkEntity(entity);
        long start = columns.getLong(EdgeColumn.OFFSETS_FWD, id);
        long end = columns.getLong(EdgeColumn.OFFSETS_FWD, id + 1L);
        for (long position = start; position < end; position++) {
            visitor.visit(
                id,
                columns.getInt(EdgeColumn.REL_FWD, position),
                columns.getInt(EdgeColumn.EDGES_FWD, position),
                position);
        }
    }

    /** Receives each edge of a {@link #forEachEdge} walk. */
    @FunctionalInterface
    public interface EdgeVisitor {
        /**
         * @param subject          subject entity id
         * @param relation         relation id
         * @param object           object entity id
         * @param forwardEdgeIndex position in forward edge order, for reading the other columns
         */
        void visit(int subject, int relation, int object, long forwardEdgeIndex);
    }

    // ---------------------------------------------------------------- internals

    /**
     * Adds every neighbour of {@code entity} admitted by the filters into {@code sink}.
     *
     * <p>Allocates nothing beyond what {@code sink} grows by. Called once per frontier entity per
     * hop, which is the hottest path in the store, so the filters are applied inline as edges are
     * read rather than by building an intermediate list.
     */
    void collectNeighbors(int entity, int[] selectors, TemporalFilter asOf, RoaringBitmap sink) {
        if (selectors == null) {
            // Both runs. No relation filter means no filter, and direction is not a relation -
            // see the contract on EdgeStore. The sink is a bitmap, so an entity reached both ways
            // costs nothing to deduplicate.
            collectRun(entity, EdgeColumn.OFFSETS_FWD, EdgeColumn.EDGES_FWD,
                -1, false, asOf, sink);
            collectRun(entity, EdgeColumn.OFFSETS_REV, EdgeColumn.EDGES_REV,
                -1, true, asOf, sink);
            return;
        }
        for (int selector : selectors) {
            boolean reverse = RelationSelector.isReverse(selector);
            int relation = RelationSelector.relationOf(selector);
            if (reverse) {
                collectRun(entity, EdgeColumn.OFFSETS_REV, EdgeColumn.EDGES_REV,
                    relation, true, asOf, sink);
            } else {
                collectRun(entity, EdgeColumn.OFFSETS_FWD, EdgeColumn.EDGES_FWD,
                    relation, false, asOf, sink);
            }
        }
    }

    /**
     * Raises each candidate's strength to that of the strongest admitted edge from
     * {@code entity} reaching it.
     *
     * <p>The ranking behind {@link #expand(long[], int, int[], TemporalFilter, int,
     * BooleanSupplier)}'s cap: the same edges {@link #collectNeighbors} admits, each scored by
     * {@link #strength}, recorded at the candidate's position in {@code candidates}.</p>
     *
     * @param entity     the frontier entity whose edges to read
     * @param selectors  selectors to follow, or null for every relation both ways
     * @param asOf       valid-time window
     * @param candidates the entities being ranked
     * @param best       each candidate's strongest edge so far, indexed by rank in candidates
     */
    void strengthenNeighbors(int entity, int[] selectors, TemporalFilter asOf,
                             RoaringBitmap candidates, long[] best) {
        if (selectors == null) {
            strengthenRun(entity, EdgeColumn.OFFSETS_FWD, EdgeColumn.EDGES_FWD, -1, false, asOf,
                candidates, best);
            strengthenRun(entity, EdgeColumn.OFFSETS_REV, EdgeColumn.EDGES_REV, -1, true, asOf,
                candidates, best);
            return;
        }
        for (int selector : selectors) {
            boolean reverse = RelationSelector.isReverse(selector);
            int relation = RelationSelector.relationOf(selector);
            if (reverse) {
                strengthenRun(entity, EdgeColumn.OFFSETS_REV, EdgeColumn.EDGES_REV, relation, true,
                    asOf, candidates, best);
            } else {
                strengthenRun(entity, EdgeColumn.OFFSETS_FWD, EdgeColumn.EDGES_FWD, relation,
                    false, asOf, candidates, best);
            }
        }
    }

    private void strengthenRun(int entity, EdgeColumn offsets, EdgeColumn adjacency, int relation,
                               boolean reverse, TemporalFilter asOf, RoaringBitmap candidates,
                               long[] best) {
        long start = columns.getLong(offsets, entity);
        long end = columns.getLong(offsets, entity + 1L);
        if (relation >= 0 && start < end) {
            EdgeColumn relColumn = reverse ? EdgeColumn.REL_REV : EdgeColumn.REL_FWD;
            start = lowerBound(relColumn, start, end, relation);
            end = upperBound(relColumn, start, end, relation);
        }
        boolean unbounded = asOf.isUnbounded();
        for (long position = start; position < end; position++) {
            int target = columns.getInt(adjacency, position);
            if (!candidates.contains(target)) {
                continue;
            }
            long forwardIndex = reverse ? columns.getLong(EdgeColumn.PERM_REV, position) : position;
            int validFrom = columns.getInt(EdgeColumn.VALID_FROM, forwardIndex);
            if (!unbounded
                    && !asOf.admits(validFrom, columns.getInt(EdgeColumn.VALID_TO, forwardIndex))) {
                continue;
            }
            int slot = candidates.rank(target) - 1;
            best[slot] = Math.max(best[slot], strength(forwardIndex, validFrom));
        }
    }

    /**
     * How strongly one edge ties its ends together, for choosing which neighbours a capped hop
     * keeps: extraction confidence in the high bits, the valid-time start below it, so a more
     * confident edge always wins and a later one breaks the tie. Never negative.
     */
    private long strength(long forwardIndex, int validFrom) {
        long confidence = columns.getByte(EdgeColumn.EXTRACTION_CONFIDENCE, forwardIndex) & 0xFF;
        return (confidence << 32) | ((long) validFrom - Integer.MIN_VALUE);
    }

    /**
     * Walks one entity's run in one direction.
     *
     * @param relation the relation to restrict to, or {@code -1} for every relation
     */
    private void collectRun(int entity, EdgeColumn offsets, EdgeColumn adjacency,
                            int relation, boolean reverse, TemporalFilter asOf,
                            RoaringBitmap sink) {
        long start = columns.getLong(offsets, entity);
        long end = columns.getLong(offsets, entity + 1L);
        if (start == end) {
            return;
        }
        if (relation >= 0) {
            EdgeColumn relColumn = reverse ? EdgeColumn.REL_REV : EdgeColumn.REL_FWD;
            long from = lowerBound(relColumn, start, end, relation);
            long to = upperBound(relColumn, from, end, relation);
            start = from;
            end = to;
        }

        boolean unbounded = asOf.isUnbounded();
        for (long position = start; position < end; position++) {
            if (!unbounded) {
                // A reverse position names a different edge than the same forward position, so the
                // temporal columns — which exist once, in forward order — are reached through the
                // permutation. This is the indirection that lets the columns exist once.
                long forwardIndex = reverse
                    ? columns.getLong(EdgeColumn.PERM_REV, position)
                    : position;
                int from = columns.getInt(EdgeColumn.VALID_FROM, forwardIndex);
                int to = columns.getInt(EdgeColumn.VALID_TO, forwardIndex);
                if (!asOf.admits(from, to)) {
                    // Excluded here, before it can contribute a neighbour. Filtering afterwards
                    // would already have let this edge pull its target into the next hop.
                    continue;
                }
            }
            sink.add(columns.getInt(adjacency, position));
        }
    }

    /**
     * First position in {@code [from, to)} whose relation is at least {@code relation}.
     *
     * <p>Plain binary search over a sorted run. Two calls bracket a relation's edges exactly, which
     * is what the joint (entity, relation) sort order buys.
     */
    /**
     * The position one past the last edge on {@code relation}.
     *
     * <p>Compares against the relation itself rather than against {@code relation + 1}, which
     * overflows for {@link Integer#MAX_VALUE}. That id is legal - the selector's ordering is
     * total over every non-negative id and the writer accepts it - and the overflow made a
     * bracket that started at the run and ended before it, so edges on that relation were
     * invisible to every filtered lookup and counted as zero, with nothing raised.</p>
     *
     * @param relColumn The relation column to search
     * @param from      Start of the range, inclusive
     * @param to        End of the range, exclusive
     * @param relation  The relation whose run to bound
     * @return The first position after the run
     */
    private long upperBound(EdgeColumn relColumn, long from, long to, int relation) {
        long lo = from;
        long hi = to;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            if (columns.getInt(relColumn, mid) <= relation) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private long lowerBound(EdgeColumn relColumn, long from, long to, int relation) {
        long lo = from;
        long hi = to;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            if (columns.getInt(relColumn, mid) < relation) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private int checkEntity(long entity) {
        if (entity < 0 || entity >= entityCount) {
            throw new IllegalArgumentException(
                "entity " + entity + " out of range for a store of " + entityCount + " entities");
        }
        return (int) entity;
    }

    /**
     * Widens a bitmap of int32 ids to the {@code long[]} the interface returns.
     *
     * <p>The widening is the boundary the interface documents: ids are 32-bit on disk and 64-bit to
     * callers, so a later store with wider ids changes nothing above this line.
     */
    private static long[] toLongArray(RoaringBitmap bitmap) {
        long[] result = new long[bitmap.getCardinality()];
        int index = 0;
        org.roaringbitmap.IntIterator iterator = bitmap.getIntIterator();
        while (iterator.hasNext()) {
            result[index++] = iterator.next();
        }
        return result;
    }
}
