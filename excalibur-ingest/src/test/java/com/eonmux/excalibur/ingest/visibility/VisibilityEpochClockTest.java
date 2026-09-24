package com.eonmux.excalibur.ingest.visibility;

import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that a document's commit epoch and a query's captured epoch are the same counter.
 *
 * <p>They were not. Ingest published on a counter private to {@link DocumentVisibilityTracker}
 * while queries captured the node's, so the two numbers were unrelated and the atomicity filter
 * could only ask "is this document complete", never "was it committed before my query started".
 * The interesting cases here are the ones that distinguish those two questions.
 */
@DisplayName("Visibility epoch clock")
class VisibilityEpochClockTest {

    private static DocumentMetadata metadata(String documentId, String... chunkIds) {
        return DocumentMetadata.builder()
            .sourceId("source")
            .documentId(documentId)
            .contentHash(documentId.hashCode())
            .contentLength(10)
            .chunkIds(List.of(chunkIds))
            .indexTimeMillis(1)
            .build();
    }

    private static VisibilityTracker nodeTracker() {
        return new VisibilityTracker(new SegmentCatalog());
    }

    @Nested
    @DisplayName("Clock ownership")
    class ClockOwnership {

        @Test
        @DisplayName("a tracker on the node clock says so; one with its own does not")
        void reportsWhichClock() {
            DocumentVisibilityTracker shared =
                new DocumentVisibilityTracker(new DocumentRegistry(), nodeTracker());
            DocumentVisibilityTracker isolated =
                new DocumentVisibilityTracker(new DocumentRegistry());

            assertThat(shared.sharesNodeEpochClock()).isTrue();
            assertThat(isolated.sharesNodeEpochClock()).isFalse();
        }

        @Test
        @DisplayName("the adapter reports its tracker's clock, not a guess")
        void adapterReportsThroughToTheTracker() {
            DocumentVisibilityChecker shared = new DocumentVisibilityTrackerAdapter(
                new DocumentVisibilityTracker(new DocumentRegistry(), nodeTracker()));
            DocumentVisibilityChecker isolated = new DocumentVisibilityTrackerAdapter(
                new DocumentVisibilityTracker(new DocumentRegistry()));

            assertThat(shared.sharesNodeEpochClock()).isTrue();
            assertThat(isolated.sharesNodeEpochClock()).isFalse();
        }

        @Test
        @DisplayName("an implementation that says nothing is assumed not to share")
        void defaultIsConservative() {
            DocumentVisibilityChecker minimal = new DocumentVisibilityChecker() {
                @Override
                public boolean isDocumentVisible(String documentId, long queryEpoch) {
                    return true;
                }

                @Override
                public boolean isChunkVisible(String chunkId, long queryEpoch) {
                    return true;
                }

                @Override
                public List<String> filterPartialDocuments(List<String> chunkIds, long epoch) {
                    return chunkIds;
                }

                @Override
                public java.util.Set<String> getVisibleDocuments(long queryEpoch) {
                    return java.util.Set.of();
                }

                @Override
                public long currentEpoch() {
                    return 0;
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
                    return 0;
                }
            };

            assertThat(minimal.sharesNodeEpochClock()).isFalse();
        }
    }

    @Nested
    @DisplayName("Sharing the counter")
    class SharingTheCounter {

        @Test
        @DisplayName("advancing the document tracker advances the node epoch")
        void advanceReachesTheNodeTracker() {
            VisibilityTracker node = nodeTracker();
            DocumentVisibilityTracker documents =
                new DocumentVisibilityTracker(new DocumentRegistry(), node);

            long before = node.currentEpoch();
            long advanced = documents.advanceEpoch();

            assertThat(advanced).isEqualTo(before + 1);
            assertThat(node.currentEpoch()).isEqualTo(advanced);
            assertThat(documents.currentEpoch()).isEqualTo(node.currentEpoch());
        }

        @Test
        @DisplayName("an epoch the node advanced is visible to the document tracker")
        void nodeAdvancesAreSeen() {
            VisibilityTracker node = nodeTracker();
            DocumentVisibilityTracker documents =
                new DocumentVisibilityTracker(new DocumentRegistry(), node);

            // The epoch tick scheduler and segment registration advance this too; the document
            // tracker has to see those, or its commit epochs drift below the query's.
            node.advanceEpoch();
            node.advanceEpoch();

            assertThat(documents.currentEpoch()).isEqualTo(node.currentEpoch());
        }

        @Test
        @DisplayName("a private clock is unaffected by the node's")
        void privateClockIsIndependent() {
            VisibilityTracker node = nodeTracker();
            DocumentVisibilityTracker isolated =
                new DocumentVisibilityTracker(new DocumentRegistry());

            node.advanceEpoch();
            node.advanceEpoch();

            assertThat(isolated.currentEpoch()).isZero();
        }
    }

    @Nested
    @DisplayName("Snapshot bound")
    class SnapshotBound {

        @Test
        @DisplayName("a query that started before the commit does not see the document")
        void committedAfterCaptureIsInvisible() {
            VisibilityTracker node = nodeTracker();
            DocumentVisibilityTracker documents =
                new DocumentVisibilityTracker(new DocumentRegistry(), node);

            long queryEpoch = node.captureEpoch();

            // The publish protocol: advance, then register at the epoch that advance returned.
            long commitEpoch = documents.advanceEpoch();
            documents.registerFromMetadata(metadata("doc", "source:doc:chunk:0"), commitEpoch);

            assertThat(documents.isDocumentFullyVisible("source:doc", queryEpoch)).isFalse();
            assertThat(documents.isDocumentFullyVisible("source:doc", node.captureEpoch())).isTrue();
        }

        @Test
        @DisplayName("a document stays visible to every later query")
        void visibilityIsMonotonic() {
            VisibilityTracker node = nodeTracker();
            DocumentVisibilityTracker documents =
                new DocumentVisibilityTracker(new DocumentRegistry(), node);

            long commitEpoch = documents.advanceEpoch();
            documents.registerFromMetadata(metadata("doc", "source:doc:chunk:0"), commitEpoch);

            for (int i = 0; i < 5; i++) {
                node.advanceEpoch();
                assertThat(documents.isDocumentFullyVisible("source:doc", node.captureEpoch()))
                    .isTrue();
            }
        }

        @Test
        @DisplayName("a chunk is visible exactly when its document is")
        void chunkFollowsItsDocument() {
            VisibilityTracker node = nodeTracker();
            DocumentVisibilityTracker documents =
                new DocumentVisibilityTracker(new DocumentRegistry(), node);

            long queryEpoch = node.captureEpoch();
            long commitEpoch = documents.advanceEpoch();
            documents.registerFromMetadata(metadata("doc", "source:doc:chunk:0"), commitEpoch);

            assertThat(documents.isChunkVisible("source:doc:chunk:0", queryEpoch)).isFalse();
            assertThat(documents.isChunkVisible("source:doc:chunk:0", node.captureEpoch())).isTrue();
        }
    }

    @Test
    @DisplayName("a clock needs a tracker to be built from")
    void clockRequiresATracker() {
        assertThatThrownBy(() -> VisibilityEpochClock.of(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("an explicit starting epoch is still rejected when negative")
    void negativeInitialEpochRejected() {
        assertThatThrownBy(() -> new DocumentVisibilityTracker(new DocumentRegistry(), -1L))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
