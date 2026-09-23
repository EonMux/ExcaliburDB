package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Files the terms of written chunks under the segment that holds them, so the query pipeline can
 * search the segments holding a query's terms first.
 *
 * <p>Recording is an optimisation and never a condition of the write: a term the filter cannot
 * take silences the filter for one segment, and a filter that is never given a term says nothing
 * about any, but neither loses a chunk.</p>
 */
final class SegmentTermRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentTermRecorder.class);

    /**
     * Membership filter the query pipeline orders segments by, or null when the node keeps
     * none.
     *
     * <p>Written from the same loop that writes the chunks, under the segment the engine
     * reports the chunk landed in.</p>
     */
    private volatile HierarchicalCuckooFilter membershipFilter;

    /**
     * Points this recorder at the membership filter the query pipeline orders segments by.
     *
     * <p>Set after construction, like the lexical index: the filter is built by the API layer's
     * bootstrap alongside the index orchestrator. Without one, stage 1 of the query pipeline
     * has nothing to say about anything this node crawled and leaves the segments in the order
     * their sketches gave them.</p>
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
     * <p>Nothing here can fail the write. A term the filter has no room for stops the filter
     * speaking for that segment - {@link HierarchicalCuckooFilter#coversSegment} then reports
     * it - and queries treat the segment as one that may hold any term.</p>
     *
     * <p>A filing that throws part-way has to leave the segment in that same state, and does not
     * on its own. A segment a rebuild has vouched for stays trusted through the failure, with
     * some of this chunk's terms in its level and the rest missing; a query for a missing one
     * would then be told the segment holds none of its terms and search it last. So the level
     * is marked stale: the filter says nothing about the segment from here until the next
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
            LOGGER.warn("Chunk {} stored but its terms were not filed in the membership filter; "
                + "segment {} is not ordered by its terms until its level is next rebuilt",
                chunkId, segment, e);
        }
    }

    /**
     * Stops the filter speaking for a segment after its terms could not all be filed.
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
