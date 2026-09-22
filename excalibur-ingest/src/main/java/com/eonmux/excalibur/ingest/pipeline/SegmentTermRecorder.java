package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.core.text.TermExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

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
     * Cluster every segment is filed under in the membership filter.
     *
     * <p>The filter's middle level partitions segments by topic; nothing in this server assigns
     * topics, so there is one cluster and it matches what excalibur-shard registers segments
     * under. A second name here would put ingested terms in a cluster the query side never
     * looks in.</p>
     */
    private static final String MEMBERSHIP_CLUSTER = "default";

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
     */
    void record(long segmentId, String chunkId, String text) {
        HierarchicalCuckooFilter target = this.membershipFilter;
        if (target == null || text == null || text.isEmpty()) {
            return;
        }
        String segment = String.valueOf(segmentId);
        try {
            target.registerSegment(segment, MEMBERSHIP_CLUSTER);
            for (String term : TermExtractor.extractUniqueTerms(text)) {
                target.insert(term.getBytes(StandardCharsets.UTF_8), segment);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Chunk {} stored but its terms were not filed for pruning; segment {} "
                + "will be read in full: {}", chunkId, segment, e.toString());
        }
    }
}
