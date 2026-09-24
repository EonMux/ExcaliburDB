package com.eonmux.excalibur.graph.browse;

import com.eonmux.excalibur.graph.dictionary.LabelDictionary;
import com.eonmux.excalibur.graph.staging.FactLabels;
import com.eonmux.excalibur.graph.staging.LabelInterner;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What a served graph holds, read by entity rather than by question.
 *
 * <p>{@code POST /v1/graph/query} answers a question; it does not say what extraction produced,
 * what the graph holds about one entity, or which id an entity has - and an id is what an
 * entity-scoped erasure names. This reads the same edge store and dictionaries the query walks,
 * so what it lists is exactly what a question could be answered from.</p>
 *
 * <h2>Finding an entity by name</h2>
 * <p>By exact name or by prefix, ignoring case and surrounding space, in name order. Not through
 * the Lucene index over labels that entity linking searches: that index ranks near matches, which
 * is right for a question and wrong for a lookup, whose answer must not depend on how a name
 * happened to score. The order comes from an index of entity ids sorted by name, built the first
 * time a listing needs it and kept for the life of the graph it describes: four bytes per entity,
 * with the names themselves read from the mapped dictionary as a search reaches them.</p>
 *
 * <p>An entity an erasure removed is not listed. Its id keeps a placeholder label, so that no id
 * moves, but the placeholder names nobody.</p>
 *
 * <h2>Paging</h2>
 * <p>Every listing takes a limit and returns a cursor for the next page; see
 * {@link CatalogCursor} for why the cursor is a key rather than an offset, and so survives a
 * rebuild between pages.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use over stores that are; the name index is built once, under a lock.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphCatalog {

    /** Most items one page may hold. */
    public static final int MAX_LIMIT = 500;

    private final MappedEdgeStore edges;
    private final LabelDictionary entities;
    private final LabelDictionary relations;
    private final Map<String, Integer> relationIds;
    private final Object indexLock = new Object();

    /** Entity ids sorted by folded name, then id; null until the first listing needs it. */
    private volatile int[] byName;

    /**
     * Creates a catalog over one served graph.
     *
     * @param edges     The edge store
     * @param entities  The entity dictionary published with it
     * @param relations The relation dictionary published with it
     */
    public GraphCatalog(MappedEdgeStore edges, LabelDictionary entities,
                        LabelDictionary relations) {
        this.edges = Objects.requireNonNull(edges, "edges must not be null");
        this.entities = Objects.requireNonNull(entities, "entities must not be null");
        this.relations = Objects.requireNonNull(relations, "relations must not be null");
        Map<String, Integer> ids = new HashMap<>();
        relations.forEach((id, name) -> ids.putIfAbsent(name, (int) id));
        this.relationIds = Map.copyOf(ids);
    }

    // ---------------------------------------------------------------- entities

    /**
     * Every entity, in name order.
     *
     * @param limit  Most entities to return, {@code 1..}{@value #MAX_LIMIT}
     * @param cursor The previous page's cursor, or null for the first page
     * @return The page
     * @throws IllegalArgumentException if the limit is out of range or the cursor malformed
     */
    public CatalogPage<CatalogEntity> allEntities(int limit, String cursor) {
        return list("", false, limit, cursor);
    }

    /**
     * The entities with exactly this name, ignoring case and surrounding space, in id order.
     *
     * @param name   The name
     * @param limit  Most entities to return, {@code 1..}{@value #MAX_LIMIT}
     * @param cursor The previous page's cursor, or null for the first page
     * @return The page; several entities can share a name
     * @throws IllegalArgumentException if the name is blank, the limit is out of range or the
     *                                  cursor malformed
     */
    public CatalogPage<CatalogEntity> entitiesNamed(String name, int limit, String cursor) {
        return list(requireText(name, "name"), true, limit, cursor);
    }

    /**
     * The entities whose name starts with a prefix, ignoring case, in name order.
     *
     * @param prefix The prefix; surrounding space is removed
     * @param limit  Most entities to return, {@code 1..}{@value #MAX_LIMIT}
     * @param cursor The previous page's cursor, or null for the first page
     * @return The page
     * @throws IllegalArgumentException if the prefix is blank, the limit is out of range or the
     *                                  cursor malformed
     */
    public CatalogPage<CatalogEntity> entitiesStartingWith(String prefix, int limit,
                                                           String cursor) {
        return list(requireText(prefix, "prefix"), false, limit, cursor);
    }

    /**
     * One entity, with how its edges divide among relations.
     *
     * @param id The entity id
     * @return The entity, or empty when this graph has no entity by that id
     */
    public Optional<CatalogEntity> entity(long id) {
        if (!holds(id)) {
            return Optional.empty();
        }
        Map<Integer, long[]> counts = new TreeMap<>();
        countRelations(id, false, counts);
        countRelations(id, true, counts);
        List<CatalogEntity.RelationDegree> perRelation = new ArrayList<>(counts.size());
        counts.forEach((relation, degree) -> perRelation.add(new CatalogEntity.RelationDegree(
            relation, relations.apply(relation), degree[0], degree[1])));
        return Optional.of(new CatalogEntity(id, entities.apply(id),
            edges.degree(id, false), edges.degree(id, true), perRelation));
    }

    /**
     * Whether this graph has a live entity by that id.
     *
     * @param id The entity id
     * @return false for an id out of range, one with no name recorded, or one erased
     */
    public boolean holds(long id) {
        if (id < 0 || id >= edges.entityCount() || !entities.contains(id)) {
            return false;
        }
        return !LabelInterner.erasedLabel((int) id).equals(entities.apply(id));
    }

    // ---------------------------------------------------------------- edges

    /**
     * An entity's edges, outgoing before incoming, each ordered by relation id and then by the
     * other endpoint's id.
     *
     * <p>An edge from an entity to itself is listed once under {@link EdgeDirection#BOTH}, as
     * outgoing.</p>
     *
     * @param id        The entity
     * @param relation  A relation label to narrow to, cleaned as a staged one is, or null for
     *                  every relation; one this graph does not have yields an empty page
     * @param direction Which of its edges to read
     * @param asOf      The valid-time instant the edges must hold at, or null for all of time
     * @param limit     Most edges to return, {@code 1..}{@value #MAX_LIMIT}
     * @param cursor    The previous page's cursor, or null for the first page
     * @return The page, or empty when this graph has no entity by that id
     * @throws IllegalArgumentException if the limit is out of range or the cursor malformed
     */
    public Optional<CatalogPage<CatalogEdge>> edges(long id, String relation,
                                                    EdgeDirection direction,
                                                    TemporalFilter asOf, int limit,
                                                    String cursor) {
        Objects.requireNonNull(direction, "direction must not be null");
        requireLimit(limit);
        CatalogCursor.EdgeKey key = CatalogCursor.edge(cursor);
        if (!holds(id)) {
            return Optional.empty();
        }
        int relationId = -1;
        if (relation != null) {
            Integer known = relationIds.get(FactLabels.relation(relation));
            if (known == null) {
                return Optional.of(new CatalogPage<>(List.of(), null));
            }
            relationId = known;
        }
        EdgeScan scan = new EdgeScan(id, relationId, direction,
            asOf == null ? TemporalFilter.ALL : asOf, limit);
        return Optional.of(scan.run(key));
    }

    /**
     * One pass over an entity's runs, collecting a page and the cursor after it.
     */
    private final class EdgeScan {

        private final long id;
        private final int relation;
        private final EdgeDirection direction;
        private final TemporalFilter asOf;
        private final int limit;
        private final List<CatalogEdge> page = new ArrayList<>();

        private CatalogCursor.EdgeKey last;
        private boolean more;

        EdgeScan(long id, int relation, EdgeDirection direction, TemporalFilter asOf, int limit) {
            this.id = id;
            this.relation = relation;
            this.direction = direction;
            this.asOf = asOf;
            this.limit = limit;
        }

        CatalogPage<CatalogEdge> run(CatalogCursor.EdgeKey key) {
            // Outgoing first: a cursor issued in the incoming half has nothing left outgoing.
            if (direction != EdgeDirection.IN && (key == null || !key.reverse())) {
                scan(false, key);
            }
            if (!more && direction != EdgeDirection.OUT) {
                scan(true, key != null && key.reverse() ? key : null);
            }
            return new CatalogPage<>(page, more ? CatalogCursor.edge(last) : null);
        }

        private void scan(boolean reverse, CatalogCursor.EdgeKey key) {
            long[] run = edges.runOf(id, reverse, relation);
            long position = run[0];
            int groupRelation = -1;
            int groupNeighbor = -1;
            long groupPassed = 0;
            if (key != null) {
                position = edges.seek(reverse, run[0], run[1], key.relation(), key.neighbor());
                groupRelation = key.relation();
                groupNeighbor = key.neighbor();
                while (position < run[1] && groupPassed < key.skip()
                    && edges.relationAt(reverse, position) == groupRelation
                    && edges.neighborAt(reverse, position) == groupNeighbor) {
                    position++;
                    groupPassed++;
                }
            }
            for (; position < run[1]; position++) {
                int edgeRelation = edges.relationAt(reverse, position);
                int neighbor = edges.neighborAt(reverse, position);
                if (edgeRelation == groupRelation && neighbor == groupNeighbor) {
                    groupPassed++;
                } else {
                    groupRelation = edgeRelation;
                    groupNeighbor = neighbor;
                    groupPassed = 1;
                }
                if (reverse && direction == EdgeDirection.BOTH && neighbor == id) {
                    continue;
                }
                long forward = edges.forwardIndexAt(reverse, position);
                if (!asOf.admits(edges.validFrom(forward), edges.validTo(forward))) {
                    continue;
                }
                if (page.size() == limit) {
                    more = true;
                    return;
                }
                page.add(edgeAt(reverse, edgeRelation, neighbor, forward));
                last = new CatalogCursor.EdgeKey(reverse, edgeRelation, neighbor, groupPassed);
            }
        }

        private CatalogEdge edgeAt(boolean reverse, int edgeRelation, int neighbor,
                                   long forward) {
            long subject = reverse ? neighbor : id;
            long object = reverse ? id : neighbor;
            return new CatalogEdge(reverse ? EdgeDirection.IN : EdgeDirection.OUT,
                edgeRelation, relations.apply(edgeRelation),
                subject, entities.apply(subject), object, entities.apply(object),
                edges.validFrom(forward), edges.validTo(forward), edges.provenanceOf(forward));
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Lists entities from the name index.
     *
     * @param folded The name or prefix, folded; empty for every entity
     * @param exact  Whether the name must equal it rather than start with it
     * @param limit  Most entities to return
     * @param cursor The previous page's cursor, or null
     * @return The page
     */
    private CatalogPage<CatalogEntity> list(String folded, boolean exact, int limit,
                                            String cursor) {
        requireLimit(limit);
        CatalogCursor.NameKey after = CatalogCursor.entity(cursor);
        int[] order = nameIndex();
        int from = firstAtOrAfter(order, folded, -1);
        if (after != null) {
            from = Math.max(from, firstAtOrAfter(order, after.folded(), after.id()));
        }

        List<CatalogEntity> page = new ArrayList<>();
        String lastFolded = null;
        long lastId = -1;
        boolean more = false;
        for (int i = from; i < order.length; i++) {
            int id = order[i];
            String name = entities.apply(id);
            String key = fold(name);
            if (exact ? !key.equals(folded) : !key.startsWith(folded)) {
                break;
            }
            if (page.size() == limit) {
                more = true;
                break;
            }
            page.add(new CatalogEntity(id, name, edges.degree(id, false),
                edges.degree(id, true), List.of()));
            lastFolded = key;
            lastId = id;
        }
        return new CatalogPage<>(page,
            more ? CatalogCursor.entity(new CatalogCursor.NameKey(lastFolded, lastId)) : null);
    }

    /**
     * The first position of the name index whose entity sorts after {@code (folded, id)}.
     *
     * @param order  The name index
     * @param folded A folded name
     * @param id     An id, or -1 for the first entity with that name
     * @return A position in {@code [0, order.length]}
     */
    private int firstAtOrAfter(int[] order, String folded, long id) {
        int lo = 0;
        int hi = order.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int c = fold(entities.apply(order[mid])).compareTo(folded);
            if (c < 0 || (c == 0 && order[mid] <= id)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * The entity ids sorted by folded name and then id, built on first use.
     *
     * @return The index
     */
    private int[] nameIndex() {
        int[] index = byName;
        if (index != null) {
            return index;
        }
        synchronized (indexLock) {
            if (byName == null) {
                byName = buildNameIndex();
            }
            return byName;
        }
    }

    private int[] buildNameIndex() {
        List<Named> named = new ArrayList<>(entities.size());
        long count = edges.entityCount();
        entities.forEach((id, name) -> {
            if (id >= 0 && id < count && !LabelInterner.erasedLabel((int) id).equals(name)) {
                named.add(new Named(fold(name), (int) id));
            }
        });
        Named[] sorted = named.toArray(new Named[0]);
        Arrays.sort(sorted, (a, b) -> {
            int c = a.folded().compareTo(b.folded());
            return c != 0 ? c : Integer.compare(a.id(), b.id());
        });
        int[] order = new int[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            order[i] = sorted[i].id();
        }
        return order;
    }

    /** An entity id and its folded name, while the name index is sorted. */
    private record Named(String folded, int id) {
    }

    /**
     * Adds an entity's per-relation edge counts in one direction.
     *
     * @param id      The entity
     * @param reverse The incoming edges rather than the outgoing ones
     * @param counts  Per relation id, {@code {out, in}}
     */
    private void countRelations(long id, boolean reverse, Map<Integer, long[]> counts) {
        long[] run = edges.runOf(id, reverse, -1);
        long position = run[0];
        while (position < run[1]) {
            int relation = edges.relationAt(reverse, position);
            long end = edges.runOf(id, reverse, relation)[1];
            counts.computeIfAbsent(relation, r -> new long[2])[reverse ? 1 : 0] += end - position;
            position = end;
        }
    }

    /**
     * A name as listings compare it: case and surrounding space ignored.
     *
     * @param name A name
     * @return Its folded form
     */
    private static String fold(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static String requireText(String text, String field) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("'" + field + "' must not be blank");
        }
        return fold(text);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                "'limit' must be between 1 and " + MAX_LIMIT + ", got " + limit);
        }
    }
}
