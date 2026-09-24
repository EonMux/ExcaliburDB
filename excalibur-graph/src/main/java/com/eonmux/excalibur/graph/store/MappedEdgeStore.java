package com.eonmux.excalibur.graph.store;

import java.io.IOException;
import java.nio.file.Path;

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

    // ---------------------------------------------------------------- incident runs

    /**
     * Where an entity's edges lie in one index, optionally narrowed to one relation.
     *
     * <p>The forward index holds the edges an entity is the subject of, sorted by relation and
     * then object; the reverse index the edges it is the object of, sorted by relation and then
     * subject. Either run is therefore ordered by {@code (relation, neighbour)}, and edges that
     * agree on both keep the order they were staged in. That order is what lets a reader page
     * through a run by key, and resume after a rebuild at the same place: relation and entity ids
     * are never renumbered.</p>
     *
     * @param entity   The entity
     * @param reverse  The reverse index rather than the forward one
     * @param relation The relation id to narrow to, or a negative value for every relation
     * @return {@code {start, end}}, positions in that index, end exclusive
     * @throws IllegalArgumentException if {@code entity} is not a valid id for this store
     */
    public long[] runOf(long entity, boolean reverse, int relation) {
        int id = checkEntity(entity);
        EdgeColumn offsets = reverse ? EdgeColumn.OFFSETS_REV : EdgeColumn.OFFSETS_FWD;
        long start = columns.getLong(offsets, id);
        long end = columns.getLong(offsets, id + 1L);
        if (relation >= 0) {
            EdgeColumn rel = reverse ? EdgeColumn.REL_REV : EdgeColumn.REL_FWD;
            long from = lowerBound(rel, start, end, relation);
            end = upperBound(rel, from, end, relation);
            start = from;
        }
        return new long[] {start, end};
    }

    /**
     * How many edges an entity has in one direction, over all of valid time.
     *
     * @param entity  The entity
     * @param reverse Count the edges it is the object of rather than the subject of
     * @return The count, from the offset array alone
     * @throws IllegalArgumentException if {@code entity} is not a valid id for this store
     */
    public long degree(long entity, boolean reverse) {
        long[] run = runOf(entity, reverse, -1);
        return run[1] - run[0];
    }

    /**
     * The relation of the edge at a position of one index.
     *
     * @param reverse  Which index
     * @param position A position within it
     * @return The relation id
     */
    public int relationAt(boolean reverse, long position) {
        return columns.getInt(reverse ? EdgeColumn.REL_REV : EdgeColumn.REL_FWD, position);
    }

    /**
     * The other endpoint of the edge at a position of one index: its object in the forward
     * index, its subject in the reverse one.
     *
     * @param reverse  Which index
     * @param position A position within it
     * @return The neighbouring entity id
     */
    public int neighborAt(boolean reverse, long position) {
        return columns.getInt(reverse ? EdgeColumn.EDGES_REV : EdgeColumn.EDGES_FWD, position);
    }

    /**
     * The forward index of the edge at a position of one index, which is what the validity and
     * provenance columns are addressed by.
     *
     * @param reverse  Which index
     * @param position A position within it
     * @return The edge's position in the forward index
     */
    public long forwardIndexAt(boolean reverse, long position) {
        return reverse ? columns.getLong(EdgeColumn.PERM_REV, position) : position;
    }

    /**
     * The first position of a run whose {@code (relation, neighbour)} is at or after the given
     * pair.
     *
     * @param reverse  Which index
     * @param start    Where the run starts
     * @param end      Where it ends, exclusive
     * @param relation The relation id of the pair
     * @param neighbor The neighbouring entity id of the pair
     * @return A position in {@code [start, end]}
     */
    public long seek(boolean reverse, long start, long end, int relation, int neighbor) {
        long lo = start;
        long hi = end;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            int midRelation = relationAt(reverse, mid);
            boolean before = midRelation < relation
                || (midRelation == relation && neighborAt(reverse, mid) < neighbor);
            if (before) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
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
