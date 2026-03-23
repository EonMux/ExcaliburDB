package excalibur.query.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for QuerySnapshot.SnapshotStats domain correctness.
 */
@DisplayName("QuerySnapshot.SnapshotStats")
class QuerySnapshotStatsTest {

    @Nested
    @DisplayName("isDegraded threshold check")
    class IsDegradedThresholdCheck {

        @Test
        @DisplayName("isDegraded returns true when coverage below default threshold (0.5)")
        void isDegradedWhenCoverageBelowDefaultThreshold() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                10,   // totalVisible
                3,    // queryable
                1, 1, 1, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                0.3   // coverage (30%)
            );

            assertThat(stats.isDegraded()).isTrue();
        }

        @Test
        @DisplayName("isDegraded returns false when coverage at default threshold (0.5)")
        void isNotDegradedWhenCoverageAtDefaultThreshold() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                10,   // totalVisible
                5,    // queryable
                1, 1, 3, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                0.5   // coverage (50%)
            );

            assertThat(stats.isDegraded()).isFalse();
        }

        @Test
        @DisplayName("isDegraded returns false when coverage above default threshold (0.5)")
        void isNotDegradedWhenCoverageAboveDefaultThreshold() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                10,   // totalVisible
                8,    // queryable
                2, 2, 4, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                0.8   // coverage (80%)
            );

            assertThat(stats.isDegraded()).isFalse();
        }

        @Test
        @DisplayName("isDegraded with custom threshold returns correct result")
        void isDegradedWithCustomThreshold() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                10,   // totalVisible
                6,    // queryable
                2, 2, 2, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                0.6   // coverage (60%)
            );

            // Below 0.7 threshold
            assertThat(stats.isDegraded(0.7)).isTrue();

            // At or above 0.5 threshold
            assertThat(stats.isDegraded(0.5)).isFalse();

            // At or above 0.6 threshold
            assertThat(stats.isDegraded(0.6)).isFalse();
        }

        @Test
        @DisplayName("isDegraded handles zero coverage")
        void isDegradedHandlesZeroCoverage() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                0,    // totalVisible
                0,    // queryable
                0, 0, 0, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                0.0   // coverage (0%)
            );

            assertThat(stats.isDegraded()).isTrue();
            assertThat(stats.isDegraded(0.0)).isFalse();
        }

        @Test
        @DisplayName("isDegraded handles full coverage")
        void isDegradedHandlesFullCoverage() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L,   // epoch
                10,   // totalVisible
                10,   // queryable
                0, 0, 10, 0, // indexing, warming, active, compacting
                0,    // tombstoned
                1.0   // coverage (100%)
            );

            assertThat(stats.isDegraded()).isFalse();
            assertThat(stats.isDegraded(0.99)).isFalse();
        }
    }

    @Nested
    @DisplayName("Record accessor methods")
    class RecordAccessorMethods {

        @Test
        @DisplayName("returns all record values correctly")
        void returnsAllRecordValuesCorrectly() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                12345L, // epoch
                100,    // totalVisible
                80,     // queryable
                20,     // indexing
                10,     // warming
                45,     // active
                5,      // compacting
                20,     // tombstoned
                0.8     // coverage
            );

            assertThat(stats.epoch()).isEqualTo(12345L);
            assertThat(stats.totalVisible()).isEqualTo(100);
            assertThat(stats.queryable()).isEqualTo(80);
            assertThat(stats.indexing()).isEqualTo(20);
            assertThat(stats.warming()).isEqualTo(10);
            assertThat(stats.active()).isEqualTo(45);
            assertThat(stats.compacting()).isEqualTo(5);
            assertThat(stats.tombstoned()).isEqualTo(20);
            assertThat(stats.coverage()).isEqualTo(0.8);
        }
    }

    @Nested
    @DisplayName("Domain correctness")
    class DomainCorrectness {

        @Test
        @DisplayName("coverage can be exactly 0.0")
        void coverageCanBeZero() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L, 0, 0, 0, 0, 0, 0, 0, 0.0
            );
            assertThat(stats.coverage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("coverage can be exactly 1.0")
        void coverageCanBeOne() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L, 10, 10, 0, 0, 10, 0, 0, 1.0
            );
            assertThat(stats.coverage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("queryable equals sum of individual states")
        void queryableEqualsSumOfStates() {
            QuerySnapshot.SnapshotStats stats = new QuerySnapshot.SnapshotStats(
                1L, 100, 50, 15, 10, 20, 5, 0, 0.5
            );

            int expectedQueryable = stats.indexing() + stats.warming() +
                stats.active() + stats.compacting();

            assertThat(stats.queryable()).isEqualTo(expectedQueryable);
        }
    }
}
