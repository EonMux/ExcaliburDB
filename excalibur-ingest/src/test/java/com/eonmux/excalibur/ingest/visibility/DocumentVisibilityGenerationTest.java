package com.eonmux.excalibur.ingest.visibility;

import com.eonmux.excalibur.ingest.registry.DocumentRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a tracker reports about itself for the query paths that filter by it and cache over it.
 */
@DisplayName("Document visibility tracker, as filters and caches read it")
class DocumentVisibilityGenerationTest {

    private final DocumentVisibilityTracker tracker =
        new DocumentVisibilityTracker(new DocumentRegistry());
    private final DocumentVisibilityChecker checker = new DocumentVisibilityTrackerAdapter(tracker);

    @Test
    @DisplayName("every change to what it answers moves the generation")
    void everyChangeMovesTheGeneration() {
        long start = checker.visibilityGeneration();

        tracker.beginIngestion("docs:a", 1);
        long marked = checker.visibilityGeneration();
        tracker.registerDocument("docs:a", Set.of("docs:a:chunk:0"), tracker.advanceEpoch());
        long published = checker.visibilityGeneration();
        tracker.beginIngestion("docs:b", 1);
        tracker.abortIngestion("docs:b");
        long released = checker.visibilityGeneration();
        tracker.removeDocument("docs:a");
        long removed = checker.visibilityGeneration();
        tracker.clear();
        long cleared = checker.visibilityGeneration();

        assertThat(List.of(start, marked, published, released, removed, cleared))
            .doesNotHaveDuplicates().isSorted();
    }

    @Test
    @DisplayName("reading it is not a change")
    void readingIsStable() {
        tracker.registerDocument("docs:a", Set.of("docs:a:chunk:0"), tracker.advanceEpoch());
        long before = checker.visibilityGeneration();

        checker.isDocumentVisible("docs:a", Long.MAX_VALUE);
        checker.isDocumentTracked("docs:a");
        tracker.advanceEpoch();

        assertThat(checker.visibilityGeneration()).isEqualTo(before);
    }

    @Test
    @DisplayName("counts a document in flight as something to withhold, with nothing published")
    void inFlightAloneIsTracked() {
        assertThat(checker.hasTrackedDocuments()).isFalse();

        tracker.beginIngestion("docs:torn", 1);

        assertThat(checker.getDocumentCount()).isZero();
        assertThat(checker.hasTrackedDocuments()).isTrue();

        tracker.abortIngestion("docs:torn");
        assertThat(checker.hasTrackedDocuments()).isFalse();
        tracker.registerDocument("docs:a", Set.of("docs:a:chunk:0"), tracker.advanceEpoch());
        assertThat(checker.hasTrackedDocuments()).isTrue();
    }

    @Test
    @DisplayName("a checker that keeps no in-flight state answers from its committed count")
    void defaultsReadTheCommittedCount() {
        DocumentVisibilityChecker committedOnly = new DocumentVisibilityChecker() {
            @Override
            public boolean isDocumentVisible(String documentId, long queryEpoch) {
                return true;
            }

            @Override
            public boolean isChunkVisible(String chunkId, long queryEpoch) {
                return true;
            }

            @Override
            public List<String> filterPartialDocuments(List<String> chunkIds, long queryEpoch) {
                return chunkIds;
            }

            @Override
            public Set<String> getVisibleDocuments(long queryEpoch) {
                return Set.of();
            }

            @Override
            public long getCommitEpoch(String documentId) {
                return 0;
            }

            @Override
            public int getChunkCount(String documentId) {
                return 0;
            }

            @Override
            public int getDocumentCount() {
                return 2;
            }

            @Override
            public long currentEpoch() {
                return 0;
            }
        };

        assertThat(committedOnly.hasTrackedDocuments()).isTrue();
        assertThat(committedOnly.visibilityGeneration()).isZero();
    }
}
