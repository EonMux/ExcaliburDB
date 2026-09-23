package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Files the terms of written chunks under the segment that holds them, so the query pipeline can
 * prune segments that cannot match.
 *
 * <p>Recording is an optimisation and never a condition of the write: a term the filter cannot
 * take costs the pruning of one segment, and a filter that is never given a term costs all of it,
 * but neither loses a chunk.</p>
 */
final class SegmentTermRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentTermRecorder.class);

    /**
     * Membership filter the query pipeline prunes segments with, or null when the node keeps
     * none.
     *
     * <p>Written from the same loop that writes the chunks, under the segment the engine
     * reports the chunk landed in.</p>
     */
    private volatile HierarchicalCuckooFilter membershipFilter;

    /**
     * Points this recorder at the membership filter the query pipeline prunes with.
     *
     * <p>Set after construction, like the lexical index: the filter is built by the API layer's
     * bootstrap alongside the index orchestrator. Without one, stage 1 of the query pipeline
     * has nothing to say about anything this node crawled and every query reads every
     * segment.</p>
     *
     * @param membershipFilter The filter, or null to keep none
     */
    void setFilter(HierarchicalCuckooFilter membershipFilter) {
        this.membershipFilter = membershipFilter;
        LOGGER.info("Membership filter {}", membershipFilter == null ? "disabled" : "enabled");
    }

    /**
     * Files one chunk's distinct terms under the segment it was written to.
     *
     * <p>Distinct terms, because the filter answers whether a segment holds a term: a word
     * occurring forty times in a chunk is one fact. The segment is registered first because the
     * engine creates segments as writes route to them, and a segment the filter has never been
     * told about would be refused rather than recorded; registration is idempotent.</p>
     *
     * <p>Nothing here can fail the write. A term the filter has no room for takes that segment
     * out of pruning - {@link HierarchicalCuckooFilter#coversSegment} then reports it - and the
     * query reads the segment in full, which is slower and not wrong.</p>
     *
     * <p>A filing that throws part-way has to leave the segment in that same state, and does not
     * on its own. A segment a rebuild has vouched for stays trusted through the failure, with
     * some of this chunk's terms in its level and the rest missing; a query for a missing one
     * would then prune the very segment that holds it, which is not a slow answer but a wrong
     * one. So the level is marked stale: the segment is read in full from here until the next
     * rebuild of it - a compaction's, a split's or a restart's - refills the level from what the
     * segment actually holds. The mark is all this layer can do; the rebuild queue belongs to
     * the index orchestrator, which ingestion does not reach.</p>
     *
     * @param segmentId The segment the engine reported the chunk landed in
     * @param chunkId   The chunk, for the log line a refusal leaves
     * @param terms     The chunk's {@linkplain com.eonmux.excalibur.ingest.processor.TextChunk#terms()
     *                  terms}, the same list the write handed the storage engine, so the
     *                  filter and the segment's frequency sketch are fed from one tokenisation
     */
    void record(long segmentId, String chunkId, List<String> terms) {
        HierarchicalCuckooFilter target = this.membershipFilter;
        if (target == null || terms.isEmpty()) {
            return;
        }
        String segment = String.valueOf(segmentId);
        try {
            target.registerSegment(segment);
            for (String term : new LinkedHashSet<>(terms)) {
                target.insert(term.getBytes(StandardCharsets.UTF_8), segment);
            }
        } catch (RuntimeException e) {
            distrust(target, segment, e);
            LOGGER.warn("Chunk {} stored but its terms were not filed for pruning; segment {} "
                + "will be read in full until its level is next rebuilt", chunkId, segment, e);
        }
    }

    /**
     * Takes a segment out of pruning after its terms could not all be filed.
     *
     * <p>Cannot itself fail the write either: a mark that throws is attached to the failure it
     * was answering, which the caller logs.</p>
     *
     * @param target  The filter the filing failed against
     * @param segment The segment's id
     * @param failure Why the filing failed
     */
    private static void distrust(HierarchicalCuckooFilter target, String segment,
                                 RuntimeException failure) {
        try {
            target.markSegmentStale(segment);
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
    }
}
