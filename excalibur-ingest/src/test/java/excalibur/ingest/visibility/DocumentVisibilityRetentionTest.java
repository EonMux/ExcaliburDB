package excalibur.ingest.visibility;

import excalibur.ingest.registry.DocumentMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tracker holds a bounded recent window, not every document the node has ever published.
 *
 * <h2>Why forgetting is safe here</h2>
 * <p>A document this tracker has never heard of is passed through by the atomicity filter rather
 * than hidden - deliberately, because the tracker is built in memory as documents are ingested and
 * everything written by a previous process is unknown after a restart. Forgetting a document that
 * is already fully visible therefore returns it to exactly the state a restart would leave it in,
 * and the answer for it is the same either way.</p>
 *
 * <p>What must not be forgotten is a document that is <em>not</em> yet visible to some query, and
 * those are the newest ones. So eviction takes the oldest commit epochs first: the entries whose
 * answer is least likely to still be in dispute.</p>
 */
@DisplayName("Document visibility retention")
class DocumentVisibilityRetentionTest {

    private static DocumentMetadata metadata(String documentId) {
        return DocumentMetadata.builder()
            .sourceId("source")
            .documentId(documentId)
            .chunkIds(List.of(documentId + ":chunk:0"))
            .build();
    }

    private static String key(String documentId) {
        return "source:" + documentId;
    }

    @Nested
    @DisplayName("Bounded growth")
    class BoundedGrowth {

        @Test
        @DisplayName("tracking never exceeds the configured retention")
        void staysWithinRetention() {
            DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(100);

            for (int i = 0; i < 1_000; i++) {
                tracker.registerFromMetadata(metadata("doc-" + i), i);
                assertThat(tracker.getDocumentCount())
                    .as("after registering %d documents", i + 1)
                    .isLessThanOrEqualTo(100);
            }
        }

        @Test
        @DisplayName("the newest documents are the ones kept")
        void keepsTheNewest() {
            DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(50);

            for (int i = 0; i < 500; i++) {
                tracker.registerFromMetadata(metadata("doc-" + i), i);
            }

            assertThat(tracker.isDocumentTracked(key("doc-499")))
                .as("the most recent document is still tracked")
                .isTrue();
            assertThat(tracker.isDocumentTracked(key("doc-0")))
                .as("the oldest document has been forgotten")
                .isFalse();
        }

        @Test
        @DisplayName("a forgotten document reads as untracked, not as invisible")
        void forgettingIsNotHiding() {
            DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(10);

            tracker.registerFromMetadata(metadata("first"), 1);
            for (int i = 0; i < 100; i++) {
                tracker.registerFromMetadata(metadata("later-" + i), 2 + i);
            }

            // This is the whole safety argument: the filter distinguishes "not tracked" from
            // "tracked and not yet visible", and only the second hides a result.
            assertThat(tracker.isDocumentTracked(key("first"))).isFalse();
            assertThat(tracker.getVisibility(key("first"))).isNull();
        }

        @Test
        @DisplayName("in-flight documents are never evicted by retention")
        void inFlightSurvives() {
            DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(10);

            tracker.beginIngestion("source:in-flight", 3);
            for (int i = 0; i < 200; i++) {
                tracker.registerFromMetadata(metadata("doc-" + i), i);
            }

            assertThat(tracker.isDocumentTracked("source:in-flight"))
                .as("a document still being written must stay tracked, or it becomes visible "
                    + "half-written")
                .isTrue();
            assertThat(tracker.getPendingDocumentCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Configuration")
    class Configuration {

        @Test
        @DisplayName("retention must be positive")
        void rejectsNonPositiveRetention() {
            assertThatThrownBy(() -> new DocumentVisibilityTracker(0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DocumentVisibilityTracker(-1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the default tracker bounds itself too")
        void defaultIsBounded() {
            DocumentVisibilityTracker tracker = new DocumentVisibilityTracker();
            assertThat(tracker.retainedDocumentLimit()).isPositive();
        }
    }
}
