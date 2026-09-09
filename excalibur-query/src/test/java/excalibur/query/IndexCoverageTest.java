package excalibur.query;

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
    @DisplayName("Builder validation")
    class BuilderValidation {

        @Test
        @DisplayName("accepts coverage at minimum boundary (0.0)")
        void acceptsMinimumCoverage() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(10)
                .estimatedCorpusCoverage(0.0)
                .build();

            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("accepts coverage at maximum boundary (1.0)")
        void acceptsMaximumCoverage() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(10)
                .estimatedCorpusCoverage(1.0)
                .build();

            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("accepts coverage at 50%")
        void acceptsFiftyPercentCoverage() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(10)
                .estimatedCorpusCoverage(0.5)
                .build();

            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("rejects coverage below 0.0")
        void rejectsNegativeCoverage() {
            assertThatThrownBy(() -> IndexCoverage.builder()
                    .totalSegments(10)
                    .estimatedCorpusCoverage(-0.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0.0")
                .hasMessageContaining("-0.01");
        }

        @Test
        @DisplayName("rejects coverage above 1.0")
        void rejectsCoverageAbove100Percent() {
            assertThatThrownBy(() -> IndexCoverage.builder()
                    .totalSegments(10)
                    .estimatedCorpusCoverage(1.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1.0")
                .hasMessageContaining("1.01");
        }

        @Test
        @DisplayName("rejects extremely negative coverage")
        void rejectsExtremelyNegativeCoverage() {
            assertThatThrownBy(() -> IndexCoverage.builder()
                    .totalSegments(10)
                    .estimatedCorpusCoverage(-999.0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects extremely high coverage")
        void rejectsExtremelyHighCoverage() {
            assertThatThrownBy(() -> IndexCoverage.builder()
                    .totalSegments(10)
                    .estimatedCorpusCoverage(999.0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("computeCoverage domain correctness")
    class ComputeCoverageDomainCorrectness {

        @Test
        @DisplayName("produces 0.0 when no segments exist")
        void noSegmentsProducesZeroCoverage() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(0)
                .tombstonedSegments(0)
                .computeCoverage()
                .build();

            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("produces 0.0 when all segments are tombstoned")
        void allTombstonedProducesZeroCoverage() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(100)
                .tombstonedSegments(100)
                .computeCoverage()
                .build();

            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("produces 1.0 when all non-tombstoned segments are queryable")
        void fullyQueryableProducesFullCoverage() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(100)
                .activeSegments(100)
                .computeCoverage()
                .build();

            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("produces 0.5 when half of segments are queryable")
        void halfQueryableProducesHalfCoverage() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(100)
                .activeSegments(50)
                .computeCoverage()
                .build();

            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("clamps to 1.0 when queryable exceeds non-purged (edge case)")
        void clampsToMaxWhenQueryableExceedsNonPurged() {
            // This is a domain correctness safeguard: if somehow the counts are
            // inconsistent (e.g., active > total - tombstoned), we clamp to 1.0
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(50)
                .activeSegments(80)  // More active than total!
                .tombstonedSegments(0)
                .computeCoverage()
                .build();

            // Raw calculation would be 80/50 = 1.6, but we clamp to 1.0
            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("clamps to 0.0 when tombstoned exceeds total (edge case)")
        void clampsToMinWhenTombstonedExceedsTotal() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(50)
                .activeSegments(10)
                .tombstonedSegments(60)  // More tombstoned than total!
                .computeCoverage()
                .build();

            // nonPurged would be negative, but we handle it gracefully
            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("handles mixed segment states correctly")
        void handlesMixedSegmentStates() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(100)
                .activeSegments(60)
                .indexingSegments(10)
                .warmingSegments(5)
                .compactingSegments(5)
                .tombstonedSegments(20)
                .computeCoverage()
                .build();

            // queryable = 60 + 10 + 5 + 5 = 80
            // nonPurged = 100 - 20 = 80
            // coverage = 80/80 = 1.0
            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("produces correct partial coverage with tombstones")
        void partialCoverageWithTombstones() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(100)
                .activeSegments(50)
                .tombstonedSegments(20)
                .computeCoverage()
                .build();

            // queryable = 50
            // nonPurged = 100 - 20 = 80
            // coverage = 50/80 = 0.625
            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.625);
        }
    }

    @Nested
    @DisplayName("Domain correctness guarantees")
    class DomainCorrectnessGuarantees {

        @Test
        @DisplayName("rejects NaN coverage")
        void rejectsNaN() {
            assertThatThrownBy(() -> IndexCoverage.builder()
                    .totalSegments(10)
                    .estimatedCorpusCoverage(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NaN");
        }

        @Test
        @DisplayName("rejects positive infinity coverage")
        void rejectsPositiveInfinity() {
            assertThatThrownBy(() -> IndexCoverage.builder()
                    .totalSegments(10)
                    .estimatedCorpusCoverage(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects negative infinity coverage")
        void rejectsNegativeInfinity() {
            assertThatThrownBy(() -> IndexCoverage.builder()
                    .totalSegments(10)
                    .estimatedCorpusCoverage(Double.NEGATIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Negative count validation")
    class NegativeCountValidation {

        @Test
        @DisplayName("rejects negative totalSegments")
        void rejectsNegativeTotalSegments() {
            assertThatThrownBy(() -> IndexCoverage.builder().totalSegments(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("rejects negative activeSegments")
        void rejectsNegativeActiveSegments() {
            assertThatThrownBy(() -> IndexCoverage.builder().activeSegments(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("rejects negative indexingSegments")
        void rejectsNegativeIndexingSegments() {
            assertThatThrownBy(() -> IndexCoverage.builder().indexingSegments(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("rejects negative warmingSegments")
        void rejectsNegativeWarmingSegments() {
            assertThatThrownBy(() -> IndexCoverage.builder().warmingSegments(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("rejects negative compactingSegments")
        void rejectsNegativeCompactingSegments() {
            assertThatThrownBy(() -> IndexCoverage.builder().compactingSegments(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("rejects negative tombstonedSegments")
        void rejectsNegativeTombstonedSegments() {
            assertThatThrownBy(() -> IndexCoverage.builder().tombstonedSegments(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("rejects negative tailScanEntries")
        void rejectsNegativeTailScanEntries() {
            assertThatThrownBy(() -> IndexCoverage.builder().tailScanEntries(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("rejects negative segmentsSearched")
        void rejectsNegativeSegmentsSearched() {
            assertThatThrownBy(() -> IndexCoverage.builder().segmentsSearched(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("rejects negative segmentsWithErrors")
        void rejectsNegativeSegmentsWithErrors() {
            assertThatThrownBy(() -> IndexCoverage.builder().segmentsWithErrors(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("rejects negative segmentsTimedOut")
        void rejectsNegativeSegmentsTimedOut() {
            assertThatThrownBy(() -> IndexCoverage.builder().segmentsTimedOut(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }
    }

    @Nested
    @DisplayName("toString output")
    class ToStringOutput {

        @Test
        @DisplayName("formats coverage as percentage in toString")
        void formatsCoverageAsPercentage() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(100)
                .activeSegments(50)
                .estimatedCorpusCoverage(0.75)
                .build();

            String result = coverage.toString();

            assertThat(result).contains("75.00%");
            assertThat(result).contains("total=100");
            assertThat(result).contains("active=50");
        }
    }

    @Nested
    @DisplayName("Immutability and thread safety")
    class ImmutabilityAndThreadSafety {

        @Test
        @DisplayName("is immutable - builder changes don't affect built instance")
        void isImmutable() {
            var builder = IndexCoverage.builder()
                .totalSegments(100)
                .estimatedCorpusCoverage(0.5);

            IndexCoverage coverage = builder.build();

            // Modify builder after build
            builder.totalSegments(200).estimatedCorpusCoverage(0.8);

            // Original instance should be unchanged
            assertThat(coverage.totalSegments()).isEqualTo(100);
            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("Segments the query was not permitted to search")
    class ExcludedSegments {

        @Test
        @DisplayName("are taken out of the coverage numerator")
        void exclusionsLowerCoverage() {
            // The catalog census says both segments are part of the live corpus. One of them was
            // withheld from this query, so half the corpus was searched, not all of it.
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(2)
                .activeSegments(1)
                .indexingSegments(1)
                .segmentsExcluded(1)
                .computeCoverage()
                .build();

            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("report zero coverage when they are the whole corpus")
        void everySegmentExcludedIsNoCoverage() {
            // The blackout a rolling reindex opens: the old segments are tombstoned and the new
            // one has not reached ACTIVE, so a query that asked for complete segments only is
            // shown nothing. Reporting full coverage over that makes an empty answer look
            // authoritative.
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(2)
                .warmingSegments(1)
                .tombstonedSegments(1)
                .segmentsExcluded(1)
                .computeCoverage()
                .computeDegraded(0.5)
                .build();

            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.0);
            assertThat(coverage.degraded())
                .as("a query shown none of the corpus is degraded, whatever the catalog holds")
                .isTrue();
        }

        @Test
        @DisplayName("are counted so a client can tell why coverage is short")
        void theCountIsReported() {
            // Short coverage has three unrelated causes - a segment errored, a segment timed out,
            // or the query was never allowed to look at it. Only the last is the client's own
            // doing, and the ratio alone cannot tell them apart.
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(4)
                .activeSegments(4)
                .segmentsExcluded(3)
                .computeCoverage()
                .build();

            assertThat(coverage.segmentsExcluded()).isEqualTo(3);
            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.25);
        }

        @Test
        @DisplayName("clamp at zero rather than reporting negative coverage")
        void moreExclusionsThanQueryableClampsToZero() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(4)
                .activeSegments(2)
                .segmentsExcluded(3)
                .computeCoverage()
                .build();

            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("leave the census alone when there are none")
        void noExclusionsLeavesTheCensusIntact() {
            IndexCoverage coverage = IndexCoverage.builder()
                .totalSegments(100)
                .activeSegments(60)
                .indexingSegments(10)
                .warmingSegments(5)
                .compactingSegments(5)
                .tombstonedSegments(20)
                .computeCoverage()
                .build();

            assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(1.0);
            assertThat(coverage.segmentsExcluded()).isZero();
        }

        @Test
        @DisplayName("cannot be negative")
        void rejectsNegativeSegmentsExcluded() {
            assertThatThrownBy(() -> IndexCoverage.builder().segmentsExcluded(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }
    }
}
