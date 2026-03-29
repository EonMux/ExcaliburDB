package excalibur.query.validation;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryResponse;
import excalibur.query.QueryResultItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for DomainCorrectnessValidator.
 *
 * <p>Verifies that all domain correctness constraints are properly enforced:</p>
 * <ul>
 *   <li>Scores are in [0.0, 1.0]</li>
 *   <li>Coverage is in [0.0, 1.0]</li>
 *   <li>Latency is non-negative</li>
 *   <li>Results are sorted by score (descending)</li>
 *   <li>No duplicate document IDs</li>
 * </ul>
 */
@DisplayName("DomainCorrectnessValidator")
class DomainCorrectnessValidatorTest {

    // ==================== Score Validation Tests ====================

    @Nested
    @DisplayName("Score validation")
    class ScoreValidation {

        @Test
        @DisplayName("accepts score at minimum boundary (0.0)")
        void acceptsMinimumScore() {
            assertThatCode(() -> DomainCorrectnessValidator.validateScore(0.0f))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts score at maximum boundary (1.0)")
        void acceptsMaximumScore() {
            assertThatCode(() -> DomainCorrectnessValidator.validateScore(1.0f))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts score in middle of range (0.5)")
        void acceptsMiddleScore() {
            assertThatCode(() -> DomainCorrectnessValidator.validateScore(0.5f))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects score below 0.0")
        void rejectsNegativeScore() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateScore(-0.01f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0.0");
        }

        @Test
        @DisplayName("rejects score above 1.0")
        void rejectsScoreAboveOne() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateScore(1.01f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1.0");
        }

        @Test
        @DisplayName("rejects NaN score")
        void rejectsNaN() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateScore(Float.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NaN");
        }

        @Test
        @DisplayName("rejects positive infinity score")
        void rejectsPositiveInfinity() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateScore(Float.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("infinite");
        }

        @Test
        @DisplayName("rejects negative infinity score")
        void rejectsNegativeInfinity() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateScore(Float.NEGATIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("infinite");
        }

        @Test
        @DisplayName("rejects extremely negative score")
        void rejectsExtremelyNegativeScore() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateScore(-999.0f))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects extremely high score")
        void rejectsExtremelyHighScore() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateScore(999.0f))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== Score Clamping Tests ====================

    @Nested
    @DisplayName("Score clamping")
    class ScoreClamping {

        @Test
        @DisplayName("clamps negative score to 0.0")
        void clampsNegativeToMin() {
            assertThat(DomainCorrectnessValidator.clampScore(-0.5f)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("clamps score above 1.0 to 1.0")
        void clampsAboveOneToMax() {
            assertThat(DomainCorrectnessValidator.clampScore(1.5f)).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("clamps NaN to 0.0")
        void clampsNaNToMin() {
            assertThat(DomainCorrectnessValidator.clampScore(Float.NaN)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("clamps positive infinity to 1.0")
        void clampsPositiveInfinityToMax() {
            assertThat(DomainCorrectnessValidator.clampScore(Float.POSITIVE_INFINITY)).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("clamps negative infinity to 0.0")
        void clampsNegativeInfinityToMin() {
            assertThat(DomainCorrectnessValidator.clampScore(Float.NEGATIVE_INFINITY)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("preserves valid score")
        void preservesValidScore() {
            assertThat(DomainCorrectnessValidator.clampScore(0.75f)).isEqualTo(0.75f);
        }

        @Test
        @DisplayName("preserves minimum boundary")
        void preservesMinBoundary() {
            assertThat(DomainCorrectnessValidator.clampScore(0.0f)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("preserves maximum boundary")
        void preservesMaxBoundary() {
            assertThat(DomainCorrectnessValidator.clampScore(1.0f)).isEqualTo(1.0f);
        }
    }

    // ==================== Score isValid Tests ====================

    @Nested
    @DisplayName("isValidScore")
    class IsValidScore {

        @Test
        @DisplayName("returns true for valid score")
        void returnsTrueForValidScore() {
            assertThat(DomainCorrectnessValidator.isValidScore(0.5f)).isTrue();
        }

        @Test
        @DisplayName("returns true for boundary scores")
        void returnsTrueForBoundaries() {
            assertThat(DomainCorrectnessValidator.isValidScore(0.0f)).isTrue();
            assertThat(DomainCorrectnessValidator.isValidScore(1.0f)).isTrue();
        }

        @Test
        @DisplayName("returns false for NaN")
        void returnsFalseForNaN() {
            assertThat(DomainCorrectnessValidator.isValidScore(Float.NaN)).isFalse();
        }

        @Test
        @DisplayName("returns false for infinity")
        void returnsFalseForInfinity() {
            assertThat(DomainCorrectnessValidator.isValidScore(Float.POSITIVE_INFINITY)).isFalse();
            assertThat(DomainCorrectnessValidator.isValidScore(Float.NEGATIVE_INFINITY)).isFalse();
        }

        @Test
        @DisplayName("returns false for out of range")
        void returnsFalseForOutOfRange() {
            assertThat(DomainCorrectnessValidator.isValidScore(-0.1f)).isFalse();
            assertThat(DomainCorrectnessValidator.isValidScore(1.1f)).isFalse();
        }
    }

    // ==================== Coverage Validation Tests ====================

    @Nested
    @DisplayName("Coverage validation")
    class CoverageValidation {

        @Test
        @DisplayName("accepts coverage at minimum boundary (0.0)")
        void acceptsMinimumCoverage() {
            assertThatCode(() -> DomainCorrectnessValidator.validateCoverage(0.0))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts coverage at maximum boundary (1.0)")
        void acceptsMaximumCoverage() {
            assertThatCode(() -> DomainCorrectnessValidator.validateCoverage(1.0))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts coverage in middle of range (0.5)")
        void acceptsMiddleCoverage() {
            assertThatCode(() -> DomainCorrectnessValidator.validateCoverage(0.5))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects coverage below 0.0")
        void rejectsNegativeCoverage() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateCoverage(-0.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0.0");
        }

        @Test
        @DisplayName("rejects coverage above 1.0")
        void rejectsCoverageAboveOne() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateCoverage(1.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1.0");
        }

        @Test
        @DisplayName("rejects NaN coverage")
        void rejectsNaN() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateCoverage(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NaN");
        }

        @Test
        @DisplayName("rejects infinite coverage")
        void rejectsInfinity() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateCoverage(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("infinite");
        }
    }

    // ==================== Coverage Clamping Tests ====================

    @Nested
    @DisplayName("Coverage clamping")
    class CoverageClamping {

        @Test
        @DisplayName("clamps negative coverage to 0.0")
        void clampsNegativeToMin() {
            assertThat(DomainCorrectnessValidator.clampCoverage(-0.5)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("clamps coverage above 1.0 to 1.0")
        void clampsAboveOneToMax() {
            assertThat(DomainCorrectnessValidator.clampCoverage(1.5)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("clamps NaN to 0.0")
        void clampsNaNToMin() {
            assertThat(DomainCorrectnessValidator.clampCoverage(Double.NaN)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("clamps infinity to 1.0")
        void clampsInfinityToMax() {
            assertThat(DomainCorrectnessValidator.clampCoverage(Double.POSITIVE_INFINITY)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("preserves valid coverage")
        void preservesValidCoverage() {
            assertThat(DomainCorrectnessValidator.clampCoverage(0.75)).isEqualTo(0.75);
        }
    }

    // ==================== Latency Validation Tests ====================

    @Nested
    @DisplayName("Latency validation")
    class LatencyValidation {

        @Test
        @DisplayName("accepts zero latency")
        void acceptsZeroLatency() {
            assertThatCode(() -> DomainCorrectnessValidator.validateLatency(0L))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts positive latency")
        void acceptsPositiveLatency() {
            assertThatCode(() -> DomainCorrectnessValidator.validateLatency(100L))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects negative latency")
        void rejectsNegativeLatency() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateLatency(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("isValidLatency returns correct value")
        void isValidLatencyReturnsCorrectValue() {
            assertThat(DomainCorrectnessValidator.isValidLatency(0L)).isTrue();
            assertThat(DomainCorrectnessValidator.isValidLatency(100L)).isTrue();
            assertThat(DomainCorrectnessValidator.isValidLatency(-1L)).isFalse();
        }
    }

    // ==================== Result Item Validation Tests ====================

    @Nested
    @DisplayName("Result item validation")
    class ResultItemValidation {

        private QueryResultItem createItem(float score) {
            return new QueryResultItem(1L, score);
        }

        @Test
        @DisplayName("accepts valid result item")
        void acceptsValidItem() {
            QueryResultItem item = createItem(0.5f);
            assertThatCode(() -> DomainCorrectnessValidator.validateResultItem(item))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects null item")
        void rejectsNullItem() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateResultItem(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("validates list of items")
        void validatesListOfItems() {
            List<QueryResultItem> items = Arrays.asList(
                createItem(0.9f),
                createItem(0.5f),
                createItem(0.1f)
            );

            assertThatCode(() -> DomainCorrectnessValidator.validateResultItems(items))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects list with null item")
        void rejectsListWithNullItem() {
            List<QueryResultItem> items = Arrays.asList(
                createItem(0.5f),
                null,
                createItem(0.3f)
            );

            assertThatThrownBy(() -> DomainCorrectnessValidator.validateResultItems(items))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null")
                .hasMessageContaining("index 1");
        }
    }

    // ==================== Sorting Validation Tests ====================

    @Nested
    @DisplayName("Sorting validation")
    class SortingValidation {

        private QueryResultItem createItem(long docId, float score) {
            return new QueryResultItem(docId, score);
        }

        @Test
        @DisplayName("accepts empty list")
        void acceptsEmptyList() {
            assertThat(DomainCorrectnessValidator.isSortedDescending(Collections.emptyList())).isTrue();
        }

        @Test
        @DisplayName("accepts single item")
        void acceptsSingleItem() {
            List<QueryResultItem> items = Collections.singletonList(createItem(1L, 0.5f));
            assertThat(DomainCorrectnessValidator.isSortedDescending(items)).isTrue();
        }

        @Test
        @DisplayName("accepts properly sorted descending list")
        void acceptsSortedDescending() {
            List<QueryResultItem> items = Arrays.asList(
                createItem(1L, 0.9f),
                createItem(2L, 0.7f),
                createItem(3L, 0.5f),
                createItem(4L, 0.3f),
                createItem(5L, 0.1f)
            );

            assertThat(DomainCorrectnessValidator.isSortedDescending(items)).isTrue();
        }

        @Test
        @DisplayName("accepts list with equal scores")
        void acceptsEqualScores() {
            List<QueryResultItem> items = Arrays.asList(
                createItem(1L, 0.5f),
                createItem(2L, 0.5f),
                createItem(3L, 0.5f)
            );

            assertThat(DomainCorrectnessValidator.isSortedDescending(items)).isTrue();
        }

        @Test
        @DisplayName("rejects ascending order")
        void rejectsAscendingOrder() {
            List<QueryResultItem> items = Arrays.asList(
                createItem(1L, 0.1f),
                createItem(2L, 0.3f),
                createItem(3L, 0.5f)
            );

            assertThat(DomainCorrectnessValidator.isSortedDescending(items)).isFalse();
        }

        @Test
        @DisplayName("rejects unsorted list")
        void rejectsUnsortedList() {
            List<QueryResultItem> items = Arrays.asList(
                createItem(1L, 0.9f),
                createItem(2L, 0.3f),
                createItem(3L, 0.5f)  // Out of order
            );

            assertThat(DomainCorrectnessValidator.isSortedDescending(items)).isFalse();
        }

        @Test
        @DisplayName("validateSortedDescending throws on unsorted")
        void validateSortedDescendingThrows() {
            List<QueryResultItem> items = Arrays.asList(
                createItem(1L, 0.1f),
                createItem(2L, 0.9f)
            );

            assertThatThrownBy(() -> DomainCorrectnessValidator.validateSortedDescending(items))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sorted");
        }
    }

    // ==================== Duplicate Detection Tests ====================

    @Nested
    @DisplayName("Duplicate detection")
    class DuplicateDetection {

        private QueryResultItem createItem(long docId) {
            return new QueryResultItem(docId, 0.5f);
        }

        @Test
        @DisplayName("returns empty set for list with no duplicates")
        void noDuplicatesReturnsEmptySet() {
            List<QueryResultItem> items = Arrays.asList(
                createItem(1L),
                createItem(2L),
                createItem(3L)
            );

            Set<Long> duplicates = DomainCorrectnessValidator.findDuplicateDocumentIds(items);
            assertThat(duplicates).isEmpty();
        }

        @Test
        @DisplayName("returns empty set for empty list")
        void emptyListReturnsEmptySet() {
            Set<Long> duplicates = DomainCorrectnessValidator.findDuplicateDocumentIds(Collections.emptyList());
            assertThat(duplicates).isEmpty();
        }

        @Test
        @DisplayName("finds single duplicate")
        void findsSingleDuplicate() {
            List<QueryResultItem> items = Arrays.asList(
                createItem(1L),
                createItem(2L),
                createItem(1L)  // Duplicate
            );

            Set<Long> duplicates = DomainCorrectnessValidator.findDuplicateDocumentIds(items);
            assertThat(duplicates).containsExactly(1L);
        }

        @Test
        @DisplayName("finds multiple duplicates")
        void findsMultipleDuplicates() {
            List<QueryResultItem> items = Arrays.asList(
                createItem(1L),
                createItem(2L),
                createItem(1L),  // Duplicate
                createItem(3L),
                createItem(2L)   // Duplicate
            );

            Set<Long> duplicates = DomainCorrectnessValidator.findDuplicateDocumentIds(items);
            assertThat(duplicates).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        @DisplayName("validateNoDuplicates throws on duplicates")
        void validateNoDuplicatesThrows() {
            List<QueryResultItem> items = Arrays.asList(
                createItem(1L),
                createItem(1L)
            );

            assertThatThrownBy(() -> DomainCorrectnessValidator.validateNoDuplicates(items))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        }
    }

    // ==================== Full Response Validation Tests ====================

    @Nested
    @DisplayName("Full response validation")
    class FullResponseValidation {

        private QueryResultItem createItem(long docId, float score) {
            return new QueryResultItem(docId, score);
        }

        private IndexCoverage createCoverage() {
            return IndexCoverage.builder()
                .totalSegments(10)
                .activeSegments(10)
                .estimatedCorpusCoverage(1.0)
                .build();
        }

        private QueryResponse createValidResponse() {
            List<QueryResultItem> results = Arrays.asList(
                createItem(1L, 0.9f),
                createItem(2L, 0.7f),
                createItem(3L, 0.5f)
            );

            return new QueryResponse(results, createCoverage(), 100L);
        }

        @Test
        @DisplayName("accepts valid response")
        void acceptsValidResponse() {
            QueryResponse response = createValidResponse();
            assertThatCode(() -> DomainCorrectnessValidator.validateResponse(response))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects null response")
        void rejectsNullResponse() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateResponse(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects response with unsorted results")
        void rejectsResponseWithUnsortedResults() {
            List<QueryResultItem> results = Arrays.asList(
                createItem(1L, 0.1f),
                createItem(2L, 0.9f)  // Out of order
            );

            QueryResponse response = new QueryResponse(results, createCoverage(), 100L);

            assertThatThrownBy(() -> DomainCorrectnessValidator.validateResponse(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sorted");
        }

        @Test
        @DisplayName("rejects response with duplicates")
        void rejectsResponseWithDuplicates() {
            List<QueryResultItem> results = Arrays.asList(
                createItem(1L, 0.9f),
                createItem(1L, 0.7f)  // Duplicate doc ID
            );

            QueryResponse response = new QueryResponse(results, createCoverage(), 100L);

            assertThatThrownBy(() -> DomainCorrectnessValidator.validateResponse(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        }
    }

    // ==================== validateResponseWithResult Tests ====================

    @Nested
    @DisplayName("validateResponseWithResult")
    class ValidateResponseWithResult {

        private QueryResultItem createItem(long docId, float score) {
            return new QueryResultItem(docId, score);
        }

        private IndexCoverage createCoverage() {
            return IndexCoverage.builder()
                .totalSegments(10)
                .activeSegments(8)
                .estimatedCorpusCoverage(0.8)
                .build();
        }

        @Test
        @DisplayName("returns valid result for valid response")
        void returnsValidForValidResponse() {
            List<QueryResultItem> results = Arrays.asList(
                createItem(1L, 0.9f),
                createItem(2L, 0.7f)
            );

            QueryResponse response = new QueryResponse(results, createCoverage(), 100L);

            DomainCorrectnessValidator.ValidationResult result =
                DomainCorrectnessValidator.validateResponseWithResult(response);

            assertThat(result.isValid()).isTrue();
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("returns invalid result for null response")
        void returnsInvalidForNullResponse() {
            DomainCorrectnessValidator.ValidationResult result =
                DomainCorrectnessValidator.validateResponseWithResult(null);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).contains("Response is null");
        }

        @Test
        @DisplayName("collects multiple errors")
        void collectsMultipleErrors() {
            List<QueryResultItem> results = Arrays.asList(
                createItem(1L, 0.1f),
                createItem(1L, 0.9f)  // Out of order AND duplicate
            );

            QueryResponse response = new QueryResponse(results, createCoverage(), 100L);

            DomainCorrectnessValidator.ValidationResult result =
                DomainCorrectnessValidator.validateResponseWithResult(response);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors().size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("summary contains error count")
        void summaryContainsErrorCount() {
            DomainCorrectnessValidator.ValidationResult result =
                DomainCorrectnessValidator.validateResponseWithResult(null);

            String summary = result.summary();
            assertThat(summary).contains("1 error");
        }
    }

    // ==================== Distance Normalization Tests ====================

    @Nested
    @DisplayName("Distance normalization")
    class DistanceNormalization {

        @Test
        @DisplayName("normalizes L2 distance (0 -> 1.0)")
        void normalizesL2ZeroToOne() {
            float score = DomainCorrectnessValidator.normalizeDistanceToScore(0.0f, true);
            assertThat(score).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("normalizes L2 distance (1 -> 0.5)")
        void normalizesL2OneToHalf() {
            float score = DomainCorrectnessValidator.normalizeDistanceToScore(1.0f, true);
            assertThat(score).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("normalizes L2 distance (large -> near 0)")
        void normalizesL2LargeToNearZero() {
            float score = DomainCorrectnessValidator.normalizeDistanceToScore(1000.0f, true);
            assertThat(score).isLessThan(0.01f);
            assertThat(score).isGreaterThanOrEqualTo(0.0f);
        }

        @Test
        @DisplayName("normalizes cosine similarity (-1 -> 0.0)")
        void normalizesCosineMinusOneToZero() {
            float score = DomainCorrectnessValidator.normalizeDistanceToScore(-1.0f, false);
            assertThat(score).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("normalizes cosine similarity (0 -> 0.5)")
        void normalizesCosineZeroToHalf() {
            float score = DomainCorrectnessValidator.normalizeDistanceToScore(0.0f, false);
            assertThat(score).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("normalizes cosine similarity (1 -> 1.0)")
        void normalizesCosineOneToOne() {
            float score = DomainCorrectnessValidator.normalizeDistanceToScore(1.0f, false);
            assertThat(score).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("clamps negative L2 to valid score")
        void clampsNegativeL2() {
            float score = DomainCorrectnessValidator.normalizeDistanceToScore(-5.0f, true);
            assertThat(score).isGreaterThanOrEqualTo(0.0f);
            assertThat(score).isLessThanOrEqualTo(1.0f);
        }
    }

    // ==================== Utility Method Tests ====================

    @Nested
    @DisplayName("Utility methods")
    class UtilityMethods {

        @Test
        @DisplayName("validateNonNegativeCount accepts zero")
        void acceptsZeroCount() {
            assertThatCode(() -> DomainCorrectnessValidator.validateNonNegativeCount(0L, "test"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validateNonNegativeCount accepts positive")
        void acceptsPositiveCount() {
            assertThatCode(() -> DomainCorrectnessValidator.validateNonNegativeCount(100L, "test"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validateNonNegativeCount rejects negative")
        void rejectsNegativeCount() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateNonNegativeCount(-1L, "testCount"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("testCount")
                .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("validateIndex accepts valid index")
        void acceptsValidIndex() {
            assertThatCode(() -> DomainCorrectnessValidator.validateIndex(0, 5, "test"))
                .doesNotThrowAnyException();

            assertThatCode(() -> DomainCorrectnessValidator.validateIndex(4, 5, "test"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validateIndex rejects negative index")
        void rejectsNegativeIndex() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateIndex(-1, 5, "test"))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("validateIndex rejects index >= size")
        void rejectsIndexAtSize() {
            assertThatThrownBy(() -> DomainCorrectnessValidator.validateIndex(5, 5, "test"))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }
}
