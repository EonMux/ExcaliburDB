package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.core.text.TermExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 1: Hierarchical Cuckoo Filter Cascade.
 *
 * <p>Moves the candidate segments the hierarchical Cuckoo filter vouches hold none of the query
 * text's terms behind the ones that may hold some, keeping Stage 0b's order within each group.
 * Every candidate is still searched.</p>
 *
 * <h2>Why term membership orders and never excludes</h2>
 * <p>Every request the pipeline runs is a nearest-neighbour query over an embedding, and the
 * HTTP route hands it the question's text beside that embedding. The embedding is there because
 * the words a question is phrased in need not be the words of the documents that answer it: a
 * segment whose chunks say "OOM killer" is exactly where "out of RAM" should reach. A segment
 * holding none of the query's terms can therefore hold its nearest neighbours - every non-empty
 * segment holds k nearest vectors, whatever words sit beside them - and dropping it on the
 * filter's word loses them with nothing to show for it: the query succeeds, coverage reads as
 * complete, and the results are simply further away. No request asks for term-gated vector
 * search, so there is no case in which the exclusion is the answer asked for.</p>
 *
 * <p>What the filter's answer does say is where the query's words are, and a segment holding
 * them is the likelier one to answer. So, like Stage 0b's sketch estimate, it decides the order
 * the segments are dispatched in, which is what a query that runs out of time has spent its
 * time on. The two signals differ: the sketch multiplies each term's estimated share of a
 * segment, which a single absent term takes to nearly nothing, and over-counts; the filter asks
 * whether any one term is present, with a false-positive rate the filter is sized for. The
 * filter's answer is the coarser key, so it partitions and the sketch orders within each
 * part.</p>
 *
 * <h2>Term-Based Lookup</h2>
 * <ol>
 *   <li>Extract significant terms from query text using {@link TermExtractor}</li>
 *   <li>For each term, find the segments that might contain it, asking the corpus-wide level
 *       and then each segment's own</li>
 *   <li>Union the segments across all query terms: a segment holding any one of them is kept
 *       in front</li>
 * </ol>
 * <p>Ingestion and the shard's level refills file chunk text through the same
 * {@link TermExtractor}, and segment ids are compared as {@code String.valueOf(segmentId)} on
 * both sides.</p>
 *
 * <h2>A segment is moved back only on a verdict the filter could give</h2>
 * <p>A level speaks for a segment only while it holds everything the segment holds: see
 * {@link #filterCovers} for the ways it may not. A segment the filter does not cover stays in
 * front, since the filter has not looked at it. And coverage is judged over the whole lookup,
 * not after it: a rebuild of the level can complete between the lookups and the verdict - the
 * lookups ran against a level still being refilled and missed a term the segment holds, and
 * coverage asked afterwards says trusted. So each candidate's
 * {@linkplain HierarchicalCuckooFilter#coverageStamp(String) coverage stamp} is read before the
 * lookups and again after, and a segment is moved back only when both are the same covering
 * stamp. A wrong verdict now costs order rather than results, but an ordering signal is worth
 * having only while it says what the filter actually knows.</p>
 */
final class TermFilterCascade {

    private static final Logger LOGGER = LoggerFactory.getLogger(TermFilterCascade.class);

    private final HierarchicalCuckooFilter hierarchicalFilter;

    /**
     * @param hierarchicalFilter The filter to consult, or null when none is configured, in
     *                           which case the order is left as it is
     */
    TermFilterCascade(HierarchicalCuckooFilter hierarchicalFilter) {
        this.hierarchicalFilter = hierarchicalFilter;
    }

    /**
     * Moves the candidates the filter vouches hold none of the query's terms to the back.
     *
     * @param context The pipeline context with segment candidates
     * @param token The cancellation token
     * @return Context with every candidate, those that may hold a query term first
     */
    PipelineContext apply(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 1 cancelled before Cuckoo filter cascade");

        LOGGER.trace("Stage 1: Running Cuckoo filter cascade");

        List<String> queryTerms = termsToLookUp(context);
        if (queryTerms.isEmpty()) {
            return context;
        }

        LOGGER.trace("Stage 1: Extracted {} terms from query: {}",
            queryTerms.size(), queryTerms);

        // Before the lookups: a segment may be moved back only if its level was already trusted
        // when they began and still is, unchanged, when they end.
        Map<Long, Long> coveredBefore = coverageStamps(context.candidates);
        TermMatches matches = segmentsHoldingAnyTerm(queryTerms);

        List<SegmentCandidate> mayHoldTerms = new ArrayList<>(context.candidates.size());
        List<SegmentCandidate> holdNone = new ArrayList<>();
        for (SegmentCandidate candidate : context.candidates) {
            long stampBefore = coveredBefore.getOrDefault(
                candidate.segmentId(), HierarchicalCuckooFilter.NOT_COVERED);
            if (mayHoldQueryTerms(hierarchicalFilter, candidate.segmentId(),
                    matches.segmentIds(), stampBefore)) {
                mayHoldTerms.add(candidate);
            } else {
                holdNone.add(candidate);
            }
        }

        LOGGER.debug("Stage 1: {} of {} segments may hold a query term and are searched first, "
            + "{} hold none ({} terms matched, {} unique segment IDs from filter)",
            mayHoldTerms.size(), context.candidates.size(), holdNone.size(),
            matches.termsWithMatches(), matches.segmentIds().size());

        if (holdNone.isEmpty()) {
            return context;
        }
        List<SegmentCandidate> ordered = new ArrayList<>(mayHoldTerms);
        ordered.addAll(holdNone);
        return context.withCandidates(ordered);
    }

    /**
     * The query's terms, or nothing when this stage has no lookup to make.
     *
     * <p>Three silences end the cascade the same way and are told apart only in the log: no
     * filter is configured, the request carried no text, and the text held no significant term.
     * In none of them has anything been asked of the filter, so the order is left alone.</p>
     *
     * @param context The pipeline context, read for the query text and the candidate count
     *                the log reports
     * @return The terms to look up, or an empty list when the order stands as it is
     */
    private List<String> termsToLookUp(PipelineContext context) {
        if (hierarchicalFilter == null) {
            LOGGER.debug("Stage 1: No hierarchical filter configured, {} candidates left in order",
                context.candidates.size());
            return List.of();
        }

        String queryText = context.request.queryText();
        if (queryText == null || queryText.isEmpty()) {
            LOGGER.debug("Stage 1: No query text for filter lookup, {} candidates left in order",
                context.candidates.size());
            return List.of();
        }

        // Extract terms from query text using the same TermExtractor as ingestion
        List<String> queryTerms = TermExtractor.extractTerms(queryText);
        if (queryTerms.isEmpty()) {
            LOGGER.debug("Stage 1: No significant terms extracted from query, {} candidates left "
                + "in order", context.candidates.size());
        }
        return queryTerms;
    }

    /**
     * Reads the coverage stamp of every candidate the filter currently covers.
     *
     * @param candidates The candidate segments
     * @return Each covered candidate's stamp, keyed by segment id; uncovered ones are absent
     */
    private Map<Long, Long> coverageStamps(List<SegmentCandidate> candidates) {
        Map<Long, Long> stamps = new HashMap<>();
        for (SegmentCandidate candidate : candidates) {
            long stamp = hierarchicalFilter.coverageStamp(String.valueOf(candidate.segmentId()));
            if (stamp != HierarchicalCuckooFilter.NOT_COVERED) {
                stamps.put(candidate.segmentId(), stamp);
            }
        }
        return stamps;
    }

    /**
     * Unions the segments the filter reports for each query term.
     *
     * <p>The union, not the intersection: a segment holding any one of the query's terms is as
     * likely a place for the answer as the ranking stages can tell, and requiring all of them
     * would move segments back on a test the ranking never applies.</p>
     *
     * @param queryTerms The terms extracted from the query text
     * @return The segment ids the filter named, and how many terms named at least one
     */
    private TermMatches segmentsHoldingAnyTerm(List<String> queryTerms) {
        Set<String> candidateSegmentIds = new HashSet<>();
        int termsWithMatches = 0;

        for (String term : queryTerms) {
            byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
            List<String> segmentsForTerm = hierarchicalFilter.findCandidateSegments(termBytes);

            if (!segmentsForTerm.isEmpty()) {
                termsWithMatches++;
                candidateSegmentIds.addAll(segmentsForTerm);
                LOGGER.trace("Stage 1: Term '{}' found in {} segments",
                    term, segmentsForTerm.size());
            }
        }

        return new TermMatches(candidateSegmentIds, termsWithMatches);
    }

    /**
     * What the filter said about the query's terms.
     *
     * @param segmentIds The segment ids at least one query term was found in
     * @param termsWithMatches How many query terms the filter named a segment for
     */
    private record TermMatches(Set<String> segmentIds, int termsWithMatches) {
    }

    /**
     * Whether the hierarchical filter holds everything a segment holds.
     *
     * <p>A level is filled by the writes that reach its segment, so one whose segment took
     * entries another way - reopened at startup, handed its share of a split, rewritten by a
     * compaction - is stale until the shard refills it from the segment; a segment with no
     * level, or an empty one, has had nothing looked at; and a level that ran out of room
     * refused terms it was asked to record, so it holds some of a segment and reads as though
     * it held all of it. In each case the filter's silence about a term says nothing about the
     * segment.</p>
     *
     * @param segmentId The segment's id
     * @return true when the filter's silence about this segment is meaningful
     */
    static boolean filterCovers(HierarchicalCuckooFilter filter, long segmentId) {
        return filter != null && filter.coversSegment(String.valueOf(segmentId));
    }

    /**
     * Whether a segment stays among those searched first.
     *
     * <p>It does when a query term was found in it, or when the filter did not speak for it for
     * the whole of the lookups: see {@link #filterCovers} for why a filter may have nothing to
     * say about a segment, and the class documentation for why that has to hold from before the
     * lookups to after them.
     *
     * @param filter        The hierarchical filter, which may be null
     * @param segmentId     The candidate segment
     * @param matched       Segment ids the query's terms were found in
     * @param stampBefore   The segment's coverage stamp read before the lookups, or
     *                      {@link HierarchicalCuckooFilter#NOT_COVERED}
     * @return true when the segment may hold a query term; false only when the filter vouches
     *         it holds none
     */
    static boolean mayHoldQueryTerms(HierarchicalCuckooFilter filter, long segmentId,
                                     Set<String> matched, long stampBefore) {
        if (matched.contains(String.valueOf(segmentId))) {
            return true;
        }
        if (filter == null || stampBefore == HierarchicalCuckooFilter.NOT_COVERED) {
            return true;
        }
        return filter.coverageStamp(String.valueOf(segmentId)) != stampBefore;
    }
}
