package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.core.text.TermExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stage 1: Hierarchical Cuckoo Filter Cascade.
 *
 * <p>Eliminates segments that cannot contain relevant entries using
 * the hierarchical Cuckoo filter. Segments that pass this stage
 * are candidates for further processing.</p>
 *
 * <h2>Term-Based Filtering</h2>
 * <p>This implementation uses term-based filtering:
 * <ol>
 *   <li>Extract significant terms from query text using {@link TermExtractor}</li>
 *   <li>For each term, find candidate segments that might contain that term</li>
 *   <li>Union all candidate segments across all query terms</li>
 *   <li>This gives segments that might contain ANY of the query terms</li>
 * </ol>
 *
 * <h2>Key Matching</h2>
 * <p><strong>CRITICAL:</strong> For this to work correctly:
 * <ul>
 *   <li><strong>Ingestion:</strong> Terms from chunk text are inserted into the filter</li>
 *   <li><strong>Query:</strong> Terms from query text are used to find candidate segments</li>
 *   <li>Both use the same {@link TermExtractor} for consistent term extraction</li>
 * </ul>
 *
 * <h2>Segment ID Format</h2>
 * <p>Segment IDs are compared as strings using {@code String.valueOf(segmentId)}
 * to match the format used during ingestion. Both sides must use this format.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Query terms extracted consistently with ingestion pipeline</li>
 *   <li>Segment IDs compared as strings (filter returns String IDs)</li>
 *   <li>Empty candidate set is valid (no segments match any query term)</li>
 *   <li>If no terms extracted, all candidates pass through (no filtering)</li>
 * </ul>
 */
final class TermFilterCascade {

    private static final Logger LOGGER = LoggerFactory.getLogger(TermFilterCascade.class);

    private final HierarchicalCuckooFilter hierarchicalFilter;

    /**
     * @param hierarchicalFilter The filter to consult, or null when none is configured, in
     *                           which case every candidate passes
     */
    TermFilterCascade(HierarchicalCuckooFilter hierarchicalFilter) {
        this.hierarchicalFilter = hierarchicalFilter;
    }

    /**
     * Drops the candidate segments the filter can vouch hold none of the query's terms.
     *
     * @param context The pipeline context with segment candidates
     * @param token The cancellation token
     * @return Context with the surviving candidates
     */
    PipelineContext apply(PipelineContext context, CancellationToken token) {
        token.checkCancelled("Stage 1 cancelled before Cuckoo filter cascade");

        LOGGER.trace("Stage 1: Running Cuckoo filter cascade");

        List<String> queryTerms = termsToLookUp(context);
        if (queryTerms.isEmpty()) {
            return context.withCandidates(new ArrayList<>(context.candidates));
        }

        LOGGER.trace("Stage 1: Extracted {} terms from query: {}",
            queryTerms.size(), queryTerms);

        TermMatches matches = segmentsHoldingAnyTerm(queryTerms);

        // A segment is excluded only on the word of a filter that actually holds its terms.
        // Two ways it does not. The filter is built in memory as documents are published and is
        // not reloaded at startup, so on a fresh process it holds nothing at all; and a level
        // that ran out of room refused terms it was asked to record, so it holds some of a
        // segment and reads as though it held all of it. Reading either silence as "this term
        // is in no segment" makes queries return zero results for documents sitting in storage.
        // Nothing errors; the database simply answers that it knows nothing. This is the same
        // rule stage 2 already applies to an LSH index that does not cover its segment: an
        // index that cannot speak for a segment has no verdict to give.
        List<SegmentCandidate> passed = context.candidates.stream()
            .filter(c -> survivesTermFilter(hierarchicalFilter, c.segmentId(), matches.segmentIds()))
            .collect(Collectors.toList());

        LOGGER.debug("Stage 1: {} of {} segments passed Cuckoo filter cascade "
            + "({} terms matched, {} unique segment IDs from filter)",
            passed.size(), context.candidates.size(), matches.termsWithMatches(),
            matches.segmentIds().size());

        return context.withCandidates(passed);
    }

    /**
     * The query's terms, or nothing when this stage has no lookup to make.
     *
     * <p>Three silences end the cascade the same way and are told apart only in the log: no
     * filter is configured, the request carried no text, and the text held no significant term.
     * In none of them has anything been asked of the filter, so no segment may be dropped on
     * the strength of it.</p>
     *
     * @param context The pipeline context, read for the query text and the candidate count
     *                the log reports as passing untouched
     * @return The terms to look up, or an empty list when every candidate must pass
     */
    private List<String> termsToLookUp(PipelineContext context) {
        // If no hierarchical filter configured, pass all segments through
        if (hierarchicalFilter == null) {
            LOGGER.debug("Stage 1: No hierarchical filter configured, {} candidates pass",
                context.candidates.size());
            return List.of();
        }

        // Extract query text
        String queryText = context.request.queryText();
        if (queryText == null || queryText.isEmpty()) {
            LOGGER.debug("Stage 1: No query text for filter lookup, {} candidates pass",
                context.candidates.size());
            return List.of();
        }

        // Extract terms from query text using the same TermExtractor as ingestion
        List<String> queryTerms = TermExtractor.extractTerms(queryText);
        if (queryTerms.isEmpty()) {
            LOGGER.debug("Stage 1: No significant terms extracted from query, {} candidates pass",
                context.candidates.size());
        }
        return queryTerms;
    }

    /**
     * Unions the segments the filter reports for each query term.
     *
     * <p>The union, not the intersection: a segment holding any one of the query's terms may
     * still hold the answer, and requiring all of them would exclude segments on a test the
     * ranking stages never applied.</p>
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
     * <p>Only a segment the filter has actually been populated for can be excluded by it, and
     * only while the filter has had room for every term of it. A segment with no registered
     * filter, one holding nothing, or one whose segment or corpus level has refused a
     * term for want of room is a segment the filter has no opinion about — which is not the
     * same as one it has looked at and rejected.
     *
     * @param segmentId The segment's id
     * @return true when the filter's silence about this segment is meaningful
     */
    static boolean filterCovers(HierarchicalCuckooFilter filter, long segmentId) {
        return filter != null && filter.coversSegment(String.valueOf(segmentId));
    }

    /**
     * Whether a segment survives the term filter.
     *
     * <p>It does when a query term was found in it, or when the filter holds nothing for it and
     * so has nothing to say. Only the second half is subtle, and it is the half that was
     * missing: see {@link #filterCovers}.
     *
     * @param filter    The hierarchical filter, which may be null
     * @param segmentId The candidate segment
     * @param matched   Segment ids the query's terms were found in
     * @return true when the segment should be searched
     */
    static boolean survivesTermFilter(HierarchicalCuckooFilter filter, long segmentId,
                                      Set<String> matched) {
        return matched.contains(String.valueOf(segmentId)) || !filterCovers(filter, segmentId);
    }
}
