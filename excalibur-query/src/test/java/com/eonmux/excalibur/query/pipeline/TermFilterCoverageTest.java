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
 * Tests when the term filter is allowed to exclude a segment.
 *
 * <p>The hierarchical filter is built in memory as documents are published and is not reloaded
 * at startup. On a fresh process it holds nothing, and stage 1 read that silence as "this term
 * is in no segment" — so every query carrying a text term returned zero results until the whole
 * corpus had been re-ingested. Nothing errored; the database simply answered that it knew
 * nothing. Stage 2 already applied the opposite rule to an LSH index that does not cover its
 * segment, which is the rule these tests pin down for stage 1.
 */
@DisplayName("Term filter coverage")
class TermFilterCoverageTest {

    /** A filter whose level for the segment was refilled with these terms and vouched for. */
    private static HierarchicalCuckooFilter filterWith(long segmentId, String... terms) {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000L, 13, 100);
        String key = String.valueOf(segmentId);
        long generation = filter.beginSegmentRebuild(key);
        for (String term : terms) {
            filter.insert(bytes(term), key);
        }
        filter.completeSegmentRebuild(key, generation);
        return filter;
    }

    private static byte[] bytes(String term) {
        return term.getBytes(StandardCharsets.UTF_8);
    }

    /** Whether the segment survives when its stamp is read now, as stage 1 reads it first. */
    private static boolean survives(HierarchicalCuckooFilter filter, long segmentId,
                                    Set<String> matched) {
        long before = filter == null ? HierarchicalCuckooFilter.NOT_COVERED
            : filter.coverageStamp(String.valueOf(segmentId));
        return TermFilterCascade.survivesTermFilter(filter, segmentId, matched, before);
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

    private static List<Long> survivors(PipelineContext context) {
        return context.candidates.stream().map(SegmentCandidate::segmentId).toList();
    }

    @Test
    @DisplayName("an empty filter excludes nothing")
    void emptyFilterHasNoVerdict() {
        // The restart case. Every segment must survive, or the node answers nothing at all.
        HierarchicalCuckooFilter empty = new HierarchicalCuckooFilter(10_000L, 13, 100);

        assertThat(TermFilterCascade.filterCovers(empty, 0L)).isFalse();
        assertThat(survives(empty, 0L, Set.of())).isTrue();
        assertThat(survives(empty, 7L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a registered but unpopulated segment is not covered")
    void registeredButEmptySegmentHasNoVerdict() {
        // Registration allocates the per-segment filter; it says nothing about the terms in it.
        HierarchicalCuckooFilter filter = filterWith(0L);

        assertThat(TermFilterCascade.filterCovers(filter, 0L)).isFalse();
        assertThat(survives(filter, 0L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a populated segment that matched nothing is excluded")
    void populatedSegmentIsExcludedOnAMiss() {
        // The case the filter exists for: it holds this segment's terms, and none of them
        // are the query's.
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice", "quarterly");

        assertThat(TermFilterCascade.filterCovers(filter, 0L)).isTrue();
        assertThat(survives(filter, 0L, Set.of())).isFalse();
    }

    @Test
    @DisplayName("a populated segment that matched is kept")
    void populatedSegmentIsKeptOnAHit() {
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice");

        assertThat(survives(filter, 0L, Set.of("0"))).isTrue();
    }

    @Test
    @DisplayName("a segment the filter has never heard of is kept, even beside one it has")
    void unknownSegmentSurvivesBesideAKnownOne() {
        // A mixed node: one segment published since startup, others recovered from disk. The
        // recovered ones must not disappear because a newer one happens to be in the filter.
        HierarchicalCuckooFilter filter = filterWith(0L, "invoice");

        assertThat(survives(filter, 0L, Set.of())).isFalse();
        assertThat(survives(filter, 1L, Set.of())).isTrue();
        assertThat(survives(filter, 99L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a segment whose filter ran out of room excludes nothing")
    void aSaturatedSegmentHasNoVerdict() {
        // The undersized-filter case. Terms were recorded right up to the point the segment's
        // filter filled, so it holds some of what the segment holds and reads as authoritative
        // - and every term it had no room for now prunes the segment that contains it. The
        // documents are in storage and the query answers with nothing.
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
        assertThat(survives(filter, 0L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a full corpus level excludes nothing anywhere")
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

        assertThat(survives(filter, 0L, Set.of())).isTrue();
        assertThat(survives(filter, 1L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a level that ingestion filled but no rebuild vouched for excludes nothing")
    void aLevelNoRebuildVouchedForHasNoVerdict() {
        // A segment reopened at startup: the first write after the restart creates its level
        // and files that one chunk's terms. The level is non-empty and knows nothing of what
        // the segment held before, so it must not rule the segment out.
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000L, 13, 100);
        filter.registerSegment("0");
        filter.insert(bytes("invoice"), "0");

        assertThat(TermFilterCascade.filterCovers(filter, 0L)).isFalse();
        assertThat(survives(filter, 0L, Set.of())).isTrue();
    }

    @Test
    @DisplayName("a segment is kept when its rebuild completes between the lookups and the verdict")
    void aRebuildCompletingDuringTheLookupsDoesNotPrune() {
        // The level is mid-refill: the rebuild has emptied it and not yet reached the entry
        // holding "invoice". The lookup misses, then the rebuild files the term and completes,
        // so asking for coverage afterwards says trusted. Pruning on that pair drops a segment
        // that holds the term.
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

        PipelineContext result = new TermFilterCascade(filter).apply(queryFor("invoice", 0L),
            new CancellationToken());

        assertThat(TermFilterCascade.filterCovers(filter, 0L))
            .as("the rebuild did complete, so a verdict asked only afterwards would prune")
            .isTrue();
        assertThat(survivors(result)).containsExactly(0L);
    }

    @Test
    @DisplayName("a segment is kept when its level is marked stale during the lookups")
    void aMarkDuringTheLookupsDoesNotPrune() {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000L, 13, 100) {
            @Override
            public List<String> findCandidateSegments(byte[] item) {
                List<String> found = super.findCandidateSegments(item);
                markSegmentStale("0");
                return found;
            }
        };
        long generation = filter.beginSegmentRebuild("0");
        filter.insert(bytes("unrelated"), "0");
        filter.completeSegmentRebuild("0", generation);

        PipelineContext result = new TermFilterCascade(filter).apply(queryFor("invoice", 0L),
            new CancellationToken());

        assertThat(survivors(result)).containsExactly(0L);
    }

    @Test
    @DisplayName("a covered segment that holds none of the query's terms is pruned by the stage")
    void theStagePrunesACoveredMiss() {
        HierarchicalCuckooFilter filter = filterWith(0L, "unrelated");
        long generation = filter.beginSegmentRebuild("1");
        filter.insert(bytes("invoice"), "1");
        filter.completeSegmentRebuild("1", generation);

        PipelineContext result = new TermFilterCascade(filter).apply(
            queryFor("invoice", 0L, 1L, 2L), new CancellationToken());

        assertThat(survivors(result)).containsExactly(1L, 2L);
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
        assertThat(TermFilterCascade.survivesTermFilter(filter, 0L, Set.of(), first))
            .as("a stamp read in an earlier period of trust does not vouch for this one")
            .isTrue();
    }

    @Test
    @DisplayName("no filter at all excludes nothing")
    void nullFilterHasNoVerdict() {
        assertThat(TermFilterCascade.filterCovers(null, 0L)).isFalse();
        assertThat(survives(null, 0L, Set.of())).isTrue();
    }
}
