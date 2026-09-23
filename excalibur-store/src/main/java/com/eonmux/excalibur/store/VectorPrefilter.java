package com.eonmux.excalibur.store;

/**
 * Writes a segment's approximate vector pre-filter, keyed by HNSW node id.
 *
 * <p>The query pipeline asks a segment's pre-filter for the entries that share a hash bucket
 * with a query vector and measures those nodes exactly beside the graph search, ranking them in
 * with its results. The pre-filter and the graph therefore share one id space, and it is the
 * graph's: an id the graph does not recognise names no node, and one it does recognise names
 * whichever vector the graph holds under it, so a pre-filter keyed any other way contributes
 * nothing but wasted measurements.</p>
 *
 * <p>Node ids exist only once the HNSW integration worker has absorbed an entry from the write
 * log, which is why this interface is written from there rather than from ingestion. Ingestion
 * has the vector but not yet the id it will be searched by.</p>
 *
 * @since 8.0
 */
public interface VectorPrefilter {

    /**
     * Attaches a pre-filter to a segment, opening whatever persisted state it already has.
     *
     * <p>Called for every segment the storage engine makes queryable - created, recovered at
     * start-up, or published as a split child - so the pre-filter has the same lifecycle as the
     * graph it is keyed against. Left to be created lazily by the first write instead, a segment
     * that is only read never regains the pre-filter it had before a restart, and the persisted
     * state is never opened again.</p>
     *
     * <p>A no-op for a segment that already has one.</p>
     *
     * @param segment The segment to attach a pre-filter to
     */
    void initialize(Segment segment);

    /**
     * Removes the persisted pre-filter state held under a segment's current name.
     *
     * <p>A split child takes a canonical segment name, and the pre-filter's files are derived
     * from that name - so the left child, which takes its parent's name, would open the parent's
     * pre-filter. That state describes the parent's graph, and the child starts a new graph whose
     * node ids begin again at zero: every id in it names a different vector in the child, or none
     * at all. The query pipeline would spend every query measuring vectors the inherited buckets
     * never described, and the child would recover none of the neighbours its own index would
     * have named.</p>
     *
     * <p>Called before {@link #initialize(Segment)} on a segment whose contents no longer match
     * the state stored under its name.</p>
     *
     * @param segment The segment whose persisted pre-filter state must not be inherited
     */
    void discardPersistedState(Segment segment);

    /**
     * Records one integrated vector under the node id the graph assigned it.
     *
     * <p>Implementations are best-effort: the pre-filter is an optimisation, and a failure to
     * record an entry must not fail the integration that produced it. The query pipeline only
     * adds the nodes a pre-filter names to what the graph search finds, so a missing entry costs
     * nothing but the chance to recover that node when the graph search walks past it.</p>
     *
     * @param segment The segment the vector was integrated into
     * @param vector  The vector as integrated
     * @param nodeId  The HNSW node id the graph assigned to it
     */
    void indexVector(Segment segment, float[] vector, long nodeId);

    /**
     * Forces a segment's pre-filter to its backing files.
     *
     * @param segment The segment whose pre-filter should be made durable
     */
    void flush(Segment segment);
}
