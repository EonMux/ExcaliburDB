package excalibur.query;

import excalibur.query.visibility.QuerySnapshot;
import excalibur.shard.VisibilityTracker;

import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Equality for the values a query response is judged by.
 *
 * <p>{@link IndexCoverage} is what tells a caller whether an empty answer means "nothing matched"
 * or "most of the corpus was not searched". Those two are indistinguishable from the results
 * alone, so the coverage value travels with every response and is compared between them - by
 * regression tests, and by callers deciding whether to retry. That makes its {@code equals} the
 * comparison itself: a field left out of it is a difference that silently reads as "no change".
 * The test below builds two values differing in one field at a time, so a field added to the
 * class but not to {@code equals} fails here rather than going unnoticed.</p>
 */
@DisplayName("Query value semantics")
class QueryValueSemanticsTest {

    private static IndexCoverage.Builder full() {
        return IndexCoverage.builder()
            .totalSegments(10)
            .activeSegments(6)
            .indexingSegments(2)
            .warmingSegments(1)
            .compactingSegments(1)
            .tombstonedSegments(0)
            .estimatedCorpusCoverage(0.6)
            .tailScanEntries(25)
            .degraded(false)
            .segmentsSearched(6)
            .segmentsWithErrors(0)
            .segmentsTimedOut(0);
    }

    @Nested
    @DisplayName("IndexCoverage")
    class Coverage {

        @Test
        @DisplayName("two coverages built the same way are equal and hash alike")
        void equalCoveragesHashAlike() {
            IndexCoverage a = full().build();
            IndexCoverage b = full().build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isEqualTo(a);
            assertThat(a).isNotEqualTo(null);
            assertThat(a).isNotEqualTo("coverage");
        }

        @Test
        @DisplayName("every field participates in equality")
        void everyFieldIsCompared() {
            // A field the builder sets but equals ignores is a change that reads as no change.
            IndexCoverage base = full().build();

            assertThat(base).isNotEqualTo(full().totalSegments(11).build());
            assertThat(base).isNotEqualTo(full().activeSegments(7).build());
            assertThat(base).isNotEqualTo(full().indexingSegments(3).build());
            assertThat(base).isNotEqualTo(full().warmingSegments(2).build());
            assertThat(base).isNotEqualTo(full().compactingSegments(2).build());
            assertThat(base).isNotEqualTo(full().tombstonedSegments(1).build());
            assertThat(base).isNotEqualTo(full().estimatedCorpusCoverage(0.7).build());
            assertThat(base).isNotEqualTo(full().tailScanEntries(26).build());
            assertThat(base).isNotEqualTo(full().degraded(true).build());
            assertThat(base).isNotEqualTo(full().segmentsSearched(7).build());
            assertThat(base).isNotEqualTo(full().segmentsWithErrors(1).build());
            assertThat(base).isNotEqualTo(full().segmentsTimedOut(1).build());
        }

        @Test
        @DisplayName("a coverage difference in one field changes the hash in practice")
        void differingFieldsHashDifferently() {
            // Not required by the contract, but a hashCode that ignores fields equals compares
            // turns every coverage into a collision in the maps these are collected into.
            Set<IndexCoverage> distinct = new HashSet<>();
            distinct.add(full().build());
            distinct.add(full().degraded(true).build());
            distinct.add(full().segmentsWithErrors(1).build());
            distinct.add(full().estimatedCorpusCoverage(0.9).build());

            assertThat(distinct).hasSize(4);
        }

        @Test
        @DisplayName("a degraded coverage is what distinguishes an empty answer from a broken one")
        void degradationIsReported() {
            IndexCoverage degraded = full().degraded(true).segmentsWithErrors(3).build();

            assertThat(degraded.degraded()).isTrue();
            assertThat(degraded.segmentsWithErrors()).isEqualTo(3);
            assertThat(full().build().degraded()).isFalse();
            assertThat(full().build().queryableSegments())
                .as("queryable is what a caller compares against total to see the shortfall")
                .isEqualTo(10);
        }

        @Test
        @DisplayName("the summary names the counts a reader needs")
        void toStringSummarises() {
            assertThat(full().build().toString())
                .contains("total=10")
                .contains("active=6")
                .contains("degraded=false");
        }
    }

    @Nested
    @DisplayName("QuerySnapshot")
    class Snapshots {

        @TempDir
        Path tempDir;

        private StorageEngine storage;
        private SegmentCatalog catalog;
        private VisibilityTracker tracker;

        @BeforeEach
        void setUp() throws IOException {
            storage = new StorageEngine(Files.createDirectories(tempDir.resolve("data")));
            storage.start();
            catalog = new SegmentCatalog();
            tracker = new VisibilityTracker(catalog);
        }

        @AfterEach
        void tearDown() {
            if (storage != null) {
                try {
                    storage.close();
                } catch (Exception ignored) {
                    // Tidy-up only.
                }
            }
        }

        @Test
        @DisplayName("two snapshots of the same epoch and segments are equal")
        void equalSnapshotsHashAlike() {
            QuerySnapshot a = QuerySnapshot.atEpoch(3, tracker, catalog, storage);
            QuerySnapshot b = QuerySnapshot.atEpoch(3, tracker, catalog, storage);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isEqualTo(a);
            assertThat(a).isNotEqualTo(null);
            assertThat(a).isNotEqualTo("snapshot");
        }

        @Test
        @DisplayName("a snapshot at a different epoch is a different snapshot")
        void differentEpochsDiffer() {
            // Two queries that captured different epochs saw different corpora, whatever else
            // they have in common.
            assertThat(QuerySnapshot.atEpoch(3, tracker, catalog, storage))
                .isNotEqualTo(QuerySnapshot.atEpoch(4, tracker, catalog, storage));
        }

        @Test
        @DisplayName("a capture records the tracker's current epoch")
        void captureUsesTheCurrentEpoch() {
            QuerySnapshot captured = QuerySnapshot.capture(tracker, catalog, storage);

            assertThat(captured.epoch()).isNotNegative();
            assertThat(captured.toString()).contains("QuerySnapshot").contains("epoch=");
        }

        @Test
        @DisplayName("a negative epoch is refused rather than captured")
        void negativeEpochsAreRefused() {
            assertThatThrownBy(() -> QuerySnapshot.atEpoch(-1, tracker, catalog, storage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("the required collaborators are refused when null")
        void nullCollaboratorsAreRefused() {
            assertThatThrownBy(() -> QuerySnapshot.capture(null, catalog, storage))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> QuerySnapshot.capture(tracker, null, storage))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> QuerySnapshot.capture(tracker, catalog, null))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> QuerySnapshot.atEpoch(0, null, catalog, storage))
                .isInstanceOf(NullPointerException.class);
        }
    }
}
