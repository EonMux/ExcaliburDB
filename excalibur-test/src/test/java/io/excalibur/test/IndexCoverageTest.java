package io.excalibur.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for IndexCoverage domain correctness validation.
 *
 * <p>Verifies that coverage percentage is always a valid number between 0-100
 * (stored internally as 0.0-1.0).</p>
 */
@DisplayName("IndexCoverage")
class IndexCoverageTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("accepts coverage at minimum boundary (0.0)")
        void acceptsMinimumCoverage() {
            IndexCoverage coverage = new IndexCoverage(10, 5, 2, 1, 0.0, false);

            assertThat(coverage.getEstimatedCoverage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("accepts coverage at maximum boundary (1.0)")
        void acceptsMaximumCoverage() {
            IndexCoverage coverage = new IndexCoverage(10, 10, 0, 0, 1.0, false);

            assertThat(coverage.getEstimatedCoverage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("accepts coverage at 50%")
        void acceptsFiftyPercentCoverage() {
            IndexCoverage coverage = new IndexCoverage(10, 5, 0, 0, 0.5, false);

            assertThat(coverage.getEstimatedCoverage()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("rejects coverage below 0.0")
        void rejectsNegativeCoverage() {
            assertThatThrownBy(() ->
                    new IndexCoverage(10, 5, 2, 1, -0.01, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0.0")
                .hasMessageContaining("-0.01");
        }

        @Test
        @DisplayName("rejects coverage above 1.0")
        void rejectsCoverageAbove100Percent() {
            assertThatThrownBy(() ->
                    new IndexCoverage(10, 5, 2, 1, 1.01, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1.0")
                .hasMessageContaining("1.01");
        }

        @Test
        @DisplayName("rejects extremely negative coverage")
        void rejectsExtremelyNegativeCoverage() {
            assertThatThrownBy(() ->
                    new IndexCoverage(10, 5, 2, 1, -999.0, false))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects extremely high coverage")
        void rejectsExtremelyHighCoverage() {
            assertThatThrownBy(() ->
                    new IndexCoverage(10, 5, 2, 1, 999.0, false))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects negative totalSegments")
        void rejectsNegativeTotalSegments() {
            assertThatThrownBy(() ->
                    new IndexCoverage(-1, 5, 2, 1, 0.5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalSegments");
        }

        @Test
        @DisplayName("rejects negative activeSegments")
        void rejectsNegativeActiveSegments() {
            assertThatThrownBy(() ->
                    new IndexCoverage(10, -1, 2, 1, 0.5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activeSegments");
        }

        @Test
        @DisplayName("rejects negative indexingSegments")
        void rejectsNegativeIndexingSegments() {
            assertThatThrownBy(() ->
                    new IndexCoverage(10, 5, -1, 1, 0.5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indexingSegments");
        }

        @Test
        @DisplayName("rejects negative warmingSegments")
        void rejectsNegativeWarmingSegments() {
            assertThatThrownBy(() ->
                    new IndexCoverage(10, 5, 2, -1, 0.5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warmingSegments");
        }

        @Test
        @DisplayName("rejects queryable segments exceeding totalSegments")
        void rejectsQueryableSegmentsExceedingTotal() {
            assertThatThrownBy(() ->
                    new IndexCoverage(10, 8, 2, 1, 0.5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalSegments");
        }

        @Test
        @DisplayName("accepts queryable segments exactly filling totalSegments")
        void acceptsQueryableSegmentsEqualToTotal() {
            IndexCoverage coverage = new IndexCoverage(10, 7, 2, 1, 1.0, false);

            assertThat(coverage.getQueryableRatio()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Static factory methods")
    class StaticFactoryMethods {

        @Test
        @DisplayName("empty() creates coverage with 0% coverage and degraded=true")
        void emptyCreatesZeroCoverage() {
            IndexCoverage coverage = IndexCoverage.empty();

            assertThat(coverage.getTotalSegments()).isEqualTo(0);
            assertThat(coverage.getActiveSegments()).isEqualTo(0);
            assertThat(coverage.getIndexingSegments()).isEqualTo(0);
            assertThat(coverage.getWarmingSegments()).isEqualTo(0);
            assertThat(coverage.getEstimatedCoverage()).isEqualTo(0.0);
            assertThat(coverage.isDegraded()).isTrue();
        }

        @Test
        @DisplayName("fullyActive() creates coverage with 100% coverage and degraded=false")
        void fullyActiveCreatesFullCoverage() {
            IndexCoverage coverage = IndexCoverage.fullyActive(100);

            assertThat(coverage.getTotalSegments()).isEqualTo(100);
            assertThat(coverage.getActiveSegments()).isEqualTo(100);
            assertThat(coverage.getIndexingSegments()).isEqualTo(0);
            assertThat(coverage.getWarmingSegments()).isEqualTo(0);
            assertThat(coverage.getEstimatedCoverage()).isEqualTo(1.0);
            assertThat(coverage.isDegraded()).isFalse();
        }

        @Test
        @DisplayName("withWarmUp() creates coverage with one warming segment")
        void withWarmUpCreatesWarmingSegment() {
            IndexCoverage coverage = IndexCoverage.withWarmUp(100);

            assertThat(coverage.getTotalSegments()).isEqualTo(100);
            assertThat(coverage.getActiveSegments()).isEqualTo(99);
            assertThat(coverage.getWarmingSegments()).isEqualTo(1);
            assertThat(coverage.getEstimatedCoverage()).isEqualTo(1.0);
            assertThat(coverage.isDegraded()).isFalse();
        }

        @Test
        @DisplayName("withWarmUp(1) leaves the single segment warming")
        void withWarmUpOfOneSegment() {
            IndexCoverage coverage = IndexCoverage.withWarmUp(1);

            assertThat(coverage.getTotalSegments()).isEqualTo(1);
            assertThat(coverage.getActiveSegments()).isEqualTo(0);
            assertThat(coverage.getWarmingSegments()).isEqualTo(1);
            assertThat(coverage.getQueryableRatio()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("withWarmUp() rejects a segment count below 1")
        void withWarmUpRejectsZeroSegments() {
            assertThatThrownBy(() -> IndexCoverage.withWarmUp(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one segment");

            assertThatThrownBy(() -> IndexCoverage.withWarmUp(-1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Ratio calculations")
    class RatioCalculations {

        @Test
        @DisplayName("getActiveRatio returns correct ratio")
        void activeRatioCorrect() {
            IndexCoverage coverage = new IndexCoverage(100, 75, 0, 0, 0.75, false);

            assertThat(coverage.getActiveRatio()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("getActiveRatio returns 0.0 when total is 0")
        void activeRatioZeroWhenNoSegments() {
            IndexCoverage coverage = IndexCoverage.empty();

            assertThat(coverage.getActiveRatio()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("getQueryableRatio includes active, indexing, and warming")
        void queryableRatioIncludesAllQueryable() {
            IndexCoverage coverage = new IndexCoverage(100, 50, 20, 10, 0.8, false);

            // queryable = 50 + 20 + 10 = 80
            assertThat(coverage.getQueryableRatio()).isEqualTo(0.8);
        }

        @Test
        @DisplayName("getQueryableRatio returns 0.0 when total is 0")
        void queryableRatioZeroWhenNoSegments() {
            IndexCoverage coverage = IndexCoverage.empty();

            assertThat(coverage.getQueryableRatio()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("getQueryableRatio never exceeds 1.0")
        void queryableRatioNeverExceedsOne() {
            assertThat(new IndexCoverage(10, 4, 3, 3, 1.0, false).getQueryableRatio())
                .isLessThanOrEqualTo(1.0);
            assertThat(IndexCoverage.fullyActive(7).getQueryableRatio()).isEqualTo(1.0);
            assertThat(IndexCoverage.withWarmUp(7).getQueryableRatio()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Equality and hashCode")
    class EqualityAndHashCode {

        @Test
        @DisplayName("equal instances have same hashCode")
        void equalInstancesHaveSameHashCode() {
            IndexCoverage coverage1 = new IndexCoverage(100, 50, 10, 5, 0.65, false);
            IndexCoverage coverage2 = new IndexCoverage(100, 50, 10, 5, 0.65, false);

            assertThat(coverage1).isEqualTo(coverage2);
            assertThat(coverage1.hashCode()).isEqualTo(coverage2.hashCode());
        }

        @Test
        @DisplayName("different coverage values are not equal")
        void differentCoverageNotEqual() {
            IndexCoverage coverage1 = new IndexCoverage(100, 50, 10, 5, 0.65, false);
            IndexCoverage coverage2 = new IndexCoverage(100, 50, 10, 5, 0.70, false);

            assertThat(coverage1).isNotEqualTo(coverage2);
        }

        @Test
        @DisplayName("small coverage differences use epsilon comparison")
        void epsilonComparisonForCoverage() {
            IndexCoverage coverage1 = new IndexCoverage(100, 50, 10, 5, 0.65, false);
            IndexCoverage coverage2 = new IndexCoverage(100, 50, 10, 5, 0.650000001, false);

            // Should be equal due to epsilon comparison
            assertThat(coverage1).isEqualTo(coverage2);
        }
    }

    @Nested
    @DisplayName("toString output")
    class ToStringOutput {

        @Test
        @DisplayName("toString contains all relevant fields")
        void toStringContainsAllFields() {
            IndexCoverage coverage = new IndexCoverage(100, 50, 10, 5, 0.65, true);

            String result = coverage.toString();

            assertThat(result).contains("total=100");
            assertThat(result).contains("active=50");
            assertThat(result).contains("indexing=10");
            assertThat(result).contains("warming=5");
            assertThat(result).contains("coverage=0.65");
            assertThat(result).contains("degraded=true");
        }
    }

    @Nested
    @DisplayName("Domain correctness guarantees")
    class DomainCorrectnessGuarantees {

        @Test
        @DisplayName("coverage is never NaN")
        void coverageNeverNaN() {
            // NaN would fail the range check
            assertThatThrownBy(() ->
                    new IndexCoverage(100, 50, 10, 5, Double.NaN, false))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("coverage is never positive infinity")
        void coverageNeverPositiveInfinity() {
            assertThatThrownBy(() ->
                    new IndexCoverage(100, 50, 10, 5, Double.POSITIVE_INFINITY, false))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("coverage is never negative infinity")
        void coverageNeverNegativeInfinity() {
            assertThatThrownBy(() ->
                    new IndexCoverage(100, 50, 10, 5, Double.NEGATIVE_INFINITY, false))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
