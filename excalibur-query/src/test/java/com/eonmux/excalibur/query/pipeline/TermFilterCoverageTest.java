package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.store.LifecycleState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests when the term filter may move a segment behind the others, and that it never removes
 * one.
 *
 * <p>Stage 1 orders the segments: those that may hold a query term are searched first, and the
 * ones the filter vouches hold none follow. Every segment is searched either way, because a
 * vector query's nearest neighbours need not share its words. The filter's verdict still has to
 * be one it can give: the hierarchical filter is built in memory and refilled at startup, so a
 * fresh process holds nothing for a segment until its level is refilled, and a level can be
 * stale, mid-refill or out of room. A segment the filter cannot speak for stays in front.</p>
 */
@DisplayName("Term filter coverage")
class TermFilterCoverageTest {

    /** A filter whose level for the segment was refilled with these terms and vouched for. */
    private static HierarchicalCuckooFilter filterWith(long segmentId, String... terms) {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000L, 13, 100);
        fill(filter, segmentId, terms);
        return filter;
    }

    /** Refills a segment's level with these terms and vouches for it. */
    private static void fill(HierarchicalCuckooFilter filter, long segmentId, String... terms) {
        String key = String.valueOf(segmentId);
        long generation = filter.beginSegmentRebuild(key);
        for (String term : terms) {
            filter.insert(bytes(term), key);
        }
        filter.completeSegmentRebuild(key, generation);
    }

    private static byte[] bytes(String term) {
        return term.getBytes(StandardCharsets.UTF_8);
    }

    /** Whether the segment stays in front when its stamp is read now, as stage 1 reads it first. */
    private static boolean inFront(HierarchicalCuckooFilter filter, long segmentId,
                                   Set<String> matched) {
        long before = filter == null ? HierarchicalCuckooFilter.NOT_COVERED
            : filter.coverageStamp(String.valueOf(segmentId));
        return TermFilterCascade.mayHoldQueryTerms(filter, segmentId, matched, before);
    }

    private static PipelineContext queryFor(String text, long... segmentIds) {
        QueryRequest request = QueryRequest.builder()
            .queryText(text).queryVector(new float[] {1f}).topK(5).build();
        List<SegmentCandidate> candidates = new java.util.ArrayList<>();
        for (long id : segmentIds) {
            candidates.add(new SegmentCandidate(id, null, null, LifecycleState.ACTIVE));
        }
        return new PipelineContext(request, new CancellationToken(), 0L, candidates, 0);
    }

    private static List<Long> searchOrder(PipelineContext context) {
        return context.candidates.stream().map(SegmentCandidate::segmentId).toList();
    }

    @Test
    @DisplayName("an empty filter has no verdict")
    void emptyFilterHasNoVerdict() {
        // The restart case: nothing has been refilled yet, so nothing has been looked at.
        HierarchicalCuckooFilter empty = new HierarchicalCuckooFilter(10_000L, 13, 100);

        assertThat(TermFilterCascade.filterCovers(empty, 0L)).isFalse();
        assertThat(inFront(empty, 0L, Set.of())).isTrue();
        assertThat(inFront(empty, 7L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a registered but unpopulated segment is not covered")
    void registeredButEmptySegmentHasNoVerdict() {
        // Registration allocates the per-segment filter; it says nothing about the terms in it.
        HierarchicalCuckooFilter filter = filterWith(0L);

        assertThat(TermFilterCascade.filterCovers(filter, 0L)).isFalse();
        assertThat(inFront(filter, 0L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a populated segment that matched nothing is vouched to hold no query term")
    void populatedSegmentHoldsNoTermOnAMiss() {
        // The case the filter can speak to: it holds this segment's terms, and none of them
        // are the query's.
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice", "quarterly");

        assertThat(TermFilterCascade.filterCovers(filter, 0L)).isTrue();
        assertThat(inFront(filter, 0L, Set.of())).isFalse();
    }

    @Test
    @DisplayName("a populated segment that matched stays in front")
    void populatedSegmentStaysInFrontOnAHit() {
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice");

        assertThat(inFront(filter, 0L, Set.of("0"))).isTrue();
    }

    @Test
    @DisplayName("a segment the filter has never heard of stays in front, even beside one it has")
    void unknownSegmentStaysInFrontBesideAKnownOne() {
        // A mixed node: one segment refilled since startup, others still waiting. The waiting
        // ones have not been looked at, so nothing says they hold no query term.
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice");

        assertThat(inFront(filter, 0L, Set.of())).isFalse();
        assertThat(inFront(filter, 1L, Set.of())).isTrue();
        assertThat(inFront(filter, 99L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a segment whose filter ran out of room has no verdict")
    void aSaturatedSegmentHasNoVerdict() {
        // Terms were recorded right up to the point the segment's filter filled, so it holds
        // some of what the segment holds and reads as authoritative about all of it.
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000L, 13, 8);
        long generation = filter.beginSegmentRebuild("0");
        filter.completeSegmentRebuild("0", generation);
        boolean refused = false;
        for (int i = 0; i < 4096 && !refused; i++) {
            refused = !filter.insert(("term-" + i).getBytes(StandardCharsets.UTF_8), "0");
        }
        assertThat(refused).as("the segment filter has to fill for this test to mean anything")
            .isTrue();

        assertThat(TermFilterCascade.filterCovers(filter, 0L)).isFalse();
        assertThat(inFront(filter, 0L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a full corpus level has no verdict anywhere")
    void aSaturatedCorpusHasNoVerdict() {
        // The corpus filter is the first level consulted, so a term it could not record is
        // invisible in every segment - not only the one being written when it filled.
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(8L, 13, 8);
        filter.completeSegmentRebuild("0", filter.beginSegmentRebuild("0"));
        long generation = filter.beginSegmentRebuild("1");
        filter.insert("invoice".getBytes(StandardCharsets.UTF_8), "1");
        filter.completeSegmentRebuild("1", generation);
        assertThat(TermFilterCascade.filterCovers(filter, 1L)).isTrue();
        // Spread over many segments, so the level holding every term fills before any segment.
        for (int i = 0; i < 4096 && !filter.isSaturated(); i++) {
            String spread = String.valueOf(2 + i % 64);
            filter.registerSegment(spread);
            filter.insert(("term-" + i).getBytes(StandardCharsets.UTF_8), spread);
        }
        assertThat(filter.isSaturated())
            .as("the corpus filter has to fill for this test to mean anything").isTrue();

        assertThat(inFront(filter, 0L, Set.of())).isTrue();
        assertThat(inFront(filter, 1L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a level that ingestion filled but no rebuild vouched for has no verdict")
    void aLevelNoRebuildVouchedForHasNoVerdict() {
        // A segment reopened at startup: the first write after the restart creates its level
        // and files that one chunk's terms. The level is non-empty and knows nothing of what
        // the segment held before.
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000L, 13, 100);
        filter.registerSegment("0");
        filter.insert(bytes("invoice"), "0");

        assertThat(TermFilterCascade.filterCovers(filter, 0L)).isFalse();
        assertThat(inFront(filter, 0L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a segment stays in front when its rebuild completes between the lookups and the verdict")
    void aRebuildCompletingDuringTheLookupsDoesNotMoveItBack() {
        // The level is mid-refill: the rebuild has emptied it and not yet reached the entry
        // holding "invoice". The lookup misses, then the rebuild files the term and completes,
        // so asking for coverage afterwards says trusted. Judging on that pair would move back
        // a segment that holds the term.
        long[] rebuild = new long[1];
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000L, 13, 100) {
            private boolean completed;

            @Override
            public List<String> findCandidateSegments(byte[] item) {
                List<String> found = super.findCandidateSegments(item);
                if (!completed) {
                    completed = true;
                    insert(bytes("invoice"), "0");
                    completeSegmentRebuild("0", rebuild[0]);
                }
                return found;
            }
        };
        // Another segment holds the term, so the corpus level lets the lookup reach segment 0.
        filter.completeSegmentRebuild("1", filter.beginSegmentRebuild("1"));
        filter.insert(bytes("invoice"), "1");
        rebuild[0] = filter.beginSegmentRebuild("0");
        filter.insert(bytes("unrelated"), "0");
        assertThat(TermFilterCascade.filterCovers(filter, 0L)).isFalse();

        PipelineContext result = new TermFilterCascade(filter).apply(queryFor("invoice", 0L, 1L),
            new CancellationToken());

        assertThat(TermFilterCascade.filterCovers(filter, 0L))
            .as("the rebuild did complete, so a verdict asked only afterwards would move it back")
            .isTrue();
        assertThat(searchOrder(result)).containsExactly(0L, 1L);
    }

    @Test
    @DisplayName("a segment stays in front when its level is marked stale during the lookups")
    void aMarkDuringTheLookupsDoesNotMoveItBack() {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000L, 13, 100) {
            @Override
            public List<String> findCandidateSegments(byte[] item) {
                List<String> found = super.findCandidateSegments(item);
                markSegmentStale("0");
                return found;
            }
        };
        fill(filter, 0L, "unrelated");
        fill(filter, 1L, "invoice");

        PipelineContext result = new TermFilterCascade(filter).apply(queryFor("invoice", 0L, 1L),
            new CancellationToken());

        assertThat(searchOrder(result)).containsExactly(0L, 1L);
    }

    @Test
    @DisplayName("a covered segment holding none of the query's terms is searched last, not dropped")
    void theStageMovesACoveredMissBehindTheOthers() {
        HierarchicalCuckooFilter filter = filterWith(0L, "unrelated");
        fill(filter, 1L, "invoice");

        PipelineContext result = new TermFilterCascade(filter).apply(
            queryFor("invoice", 0L, 1L, 2L), new CancellationToken());

        assertThat(searchOrder(result)).containsExactly(1L, 2L, 0L);
    }

    @Test
    @DisplayName("the stage keeps the order it was given within those in front and those behind")
    void theStageKeepsTheIncomingOrderWithinEachGroup() {
        // Stage 0b has already ordered the segments by their sketches' estimates; the filter's
        // coarser answer partitions that order and must not scramble it.
        HierarchicalCuckooFilter filter = filterWith(4L, "unrelated");
        fill(filter, 3L, "invoice");
        fill(filter, 2L, "other");
        fill(filter, 1L, "invoice");

        PipelineContext result = new TermFilterCascade(filter).apply(
            queryFor("invoice", 4L, 3L, 2L, 1L, 0L), new CancellationToken());

        assertThat(searchOrder(result)).containsExactly(3L, 1L, 0L, 4L, 2L);
    }

    @Test
    @DisplayName("query text found in no segment leaves every segment searched, in order")
    void textFoundNowhereKeepsEverySegment() {
        // The semantic case: the question is phrased in words no document uses, and its
        // embedding is what finds the answer. Every segment is vouched to hold none of the
        // terms, and every one is still searched.
        HierarchicalCuckooFilter filter = filterWith(0L, "oom", "killer");
        fill(filter, 1L, "enomem");

        PipelineContext context = queryFor("out of RAM", 0L, 1L);
        PipelineContext result = new TermFilterCascade(filter).apply(context,
            new CancellationToken());

        assertThat(searchOrder(result)).containsExactly(0L, 1L);
    }

    @Test
    @DisplayName("without a filter, text or terms the order is left as it is")
    void nothingToLookUpLeavesTheOrder() {
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice");
        PipelineContext noText = queryFor(null, 0L, 1L);
        PipelineContext onlyStopWords = queryFor("the of and", 0L, 1L);
        PipelineContext withText = queryFor("invoice", 1L, 0L);

        assertThat(new TermFilterCascade(filter).apply(noText, new CancellationToken()))
            .isSameAs(noText);
        assertThat(new TermFilterCascade(filter).apply(onlyStopWords, new CancellationToken()))
            .isSameAs(onlyStopWords);
        assertThat(new TermFilterCascade(null).apply(withText, new CancellationToken()))
            .isSameAs(withText);
    }

    @Test
    @DisplayName("every period of trust carries a stamp no other period used")
    void stampsAreNotReused() {
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice");
        long first = filter.coverageStamp("0");

        filter.markSegmentStale("0");
        assertThat(filter.coverageStamp("0")).isEqualTo(HierarchicalCuckooFilter.NOT_COVERED);
        long generation = filter.beginSegmentRebuild("0");
        filter.insert(bytes("invoice"), "0");
        filter.completeSegmentRebuild("0", generation);

        assertThat(filter.coverageStamp("0")).isNotEqualTo(first)
            .isNotEqualTo(HierarchicalCuckooFilter.NOT_COVERED);
        assertThat(TermFilterCascade.mayHoldQueryTerms(filter, 0L, Set.of(), first))
            .as("a stamp read in an earlier period of trust does not vouch for this one")
            .isTrue();
    }

    @Test
    @DisplayName("no filter at all has no verdict")
    void nullFilterHasNoVerdict() {
        assertThat(TermFilterCascade.filterCovers(null, 0L)).isFalse();
        assertThat(inFront(null, 0L, Set.of())).isTrue();
    }
}
