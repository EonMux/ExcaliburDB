package io.excalibur.graph.store;

import io.excalibur.graph.temporal.TemporalFilter;

/**
 * Adjacency over the entity graph that sits alongside the vector index.
 *
 * <p>Three methods, deliberately. Everything retrieval needs from a graph is a neighbour lookup, a
 * bounded multi-hop expansion, and a degree count; anything else belongs in a layer above rather
 * than in the interface that a mmap'd CSR has to implement. Keeping it at three is what allows an
 * in-memory implementation for tests, a mapped implementation for production, and a
 * namespace-routing implementation for multi-tenancy to be genuinely interchangeable.
 *
 * <h2>Identifiers</h2>
 * Entity ids are {@code long} here and {@code int} on disk. Widening at the boundary costs nothing
 * — these are counts of entities, not per-edge storage — and means a future store with 64-bit ids
 * does not change a single caller. Implementations reject ids outside their own range rather than
 * truncating.
 *
 * <h2>Relation filters</h2>
 * Filters are arrays of selectors as encoded by {@link RelationSelector}, so one array names both
 * the relations to follow and the direction to follow each of them in. A {@code null} filter means
 * every relation, forward only; an empty array means no relation at all and yields no results,
 * which is a legitimate request rather than an error.
 *
 * <h2>Time</h2>
 * Every method that walks edges takes valid time into account. Filtering happens <em>while</em>
 * edges are being considered, never as a pass over the result: an edge outside the window must not
 * contribute its neighbours to the next hop, and a post-filter cannot undo that.
 *
 * <h2>Direction, and what an absent filter means</h2>
 * A null relation filter means every relation in <em>both</em> directions, not every relation
 * forward. The parameter filters relations; direction is a separate axis, and letting the absence
 * of a relation filter quietly decide it too is how a traversal comes to answer only those
 * questions whose facts happen to have been asserted in the direction they are asked about. "Who
 * did Ada Lovelace collaborate with" and "who did Charles Babbage collaborate with" are the same
 * question about the same edge, and a store that answers one and not the other is not filtering,
 * it is losing half the graph.
 *
 * <p>The reverse index is mandatory, so both runs always exist and an unfiltered hop costs two
 * run scans rather than one. A caller that genuinely wants one direction says so with
 * {@link RelationSelector#forward} or {@link RelationSelector#reverse}, which is the same way it
 * would ask for one relation - stated, rather than inherited from an omission.
 *
 * <h2>Concurrency</h2>
 * Implementations are safe for concurrent readers. None of these methods mutate.
 */
public interface EdgeStore extends AutoCloseable {

    /**
     * Entities directly reachable from {@code entity}.
     *
     * <p>Results are distinct and carry no duplicate for an entity reachable by several relations.
     * Order is unspecified; callers that need an order impose one.
     *
     * @param entity         the entity to expand from
     * @param relationFilter selectors to follow, or {@code null} for every relation in both
     *                       directions
     * @return neighbouring entity ids, empty if there are none
     * @throws IllegalArgumentException if {@code entity} is not a valid id for this store
     */
    long[] neighbors(long entity, int[] relationFilter);

    /**
     * Entities reachable from any of {@code seeds} within {@code hops} hops.
     *
     * <p>Hops are dependent — hop <i>n</i> cannot start before hop <i>n-1</i> has produced its
     * frontier — so the parallelism available here is within a hop, across the seeds of that
     * frontier, not across hops.
     *
     * <p>The seeds themselves are included in the result. An entity reached at several depths
     * appears once.
     *
     * @param seeds        entities to start from
     * @param hops         maximum hops to take; {@code 0} returns the seeds alone
     * @param relWhitelist selectors to follow, or {@code null} for every relation in both
     *                     directions
     * @param asOf         valid-time window applied as edges are considered; must not be null
     * @return reachable entity ids including the seeds
     * @throws IllegalArgumentException if {@code hops} is negative or a seed is not a valid id
     */
    long[] expand(long[] seeds, int hops, int[] relWhitelist, TemporalFilter asOf);

    /**
     * Out-degree of {@code subject} along one relation, without materialising the neighbours.
     *
     * <p>Separate from {@link #neighbors} because a CSR answers it from the offset array alone,
     * touching two longs rather than the adjacency itself. That is what makes it usable as a
     * planning input — deciding whether an expansion is worth attempting — rather than a
     * convenience wrapper.
     *
     * <p>Counts edges, so an entity linked twice by the same relation counts twice; that is the
     * degree, which is the question being asked.
     *
     * @param subject  the entity whose degree is wanted
     * @param relation a relation selector, forward or reverse, as encoded by
     *                 {@link RelationSelector}
     * @return number of matching edges
     * @throws IllegalArgumentException if {@code subject} is not a valid id for this store
     */
    long count(long subject, int relation);

    /**
     * Releases the mappings this store owns.
     *
     * <p>Narrowed from {@link AutoCloseable#close()} so callers are not forced to handle a checked
     * exception they cannot act on.
     */
    @Override
    void close();
}
