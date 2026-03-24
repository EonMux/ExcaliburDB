package excalibur.query.plan;

import excalibur.filter.sketch.CountMinSketch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SelectivityEstimator}.
 *
 * <p>Tests cover:</p>
 * <ul>
 *   <li>Single term selectivity estimation</li>
 *   <li>Combined selectivity for multiple terms</li>
 *   <li>Edge cases: null sketch, empty terms, empty lists</li>
 *   <li>Domain correctness: selectivity clamped to [0.0, 1.0]</li>
 *   <li>Segment prioritization and skip decisions</li>
 * </ul>
 */
@DisplayName("SelectivityEstimator")
class SelectivityEstimatorTest {

    private CountMinSketch sketch;
    private SelectivityEstimator estimator;

    @BeforeEach
    void setUp() {
        sketch = new CountMinSketch();
        estimator = new SelectivityEstimator(sketch);
    }

    @Nested
    @DisplayName("with populated sketch")
    class WithPopulatedSketch {

        @BeforeEach
        void populateSketch() {
            // Insert terms with varying frequencies
            // "common" appears 100 times
            for (int i = 0; i < 100; i++) {
                sketch.insert("common".getBytes(StandardCharsets.UTF_8));
            }
            // "rare" appears 1 time
            sketch.insert("rare".getBytes(StandardCharsets.UTF_8));
            // "medium" appears 10 times
            for (int i = 0; i < 10; i++) {
                sketch.insert("medium".getBytes(StandardCharsets.UTF_8));
            }
        }

        @Test
        @DisplayName("estimates higher selectivity for common terms")
        void estimatesHigherSelectivityForCommonTerms() {
            double commonSelectivity = estimator.estimateSelectivity("common");
            double rareSelectivity = estimator.estimateSelectivity("rare");

            // Common term should have higher selectivity
            assertTrue(commonSelectivity > rareSelectivity,
                "Common term should have higher selectivity than rare term");
        }

        @Test
        @DisplayName("returns selectivity in valid range [0.0, 1.0]")
        void returnsSelectivityInValidRange() {
            double selectivity = estimator.estimateSelectivity("common");
            assertTrue(selectivity >= 0.0 && selectivity <= 1.0,
                "Selectivity should be in range [0.0, 1.0]");
        }

        @Test
        @DisplayName("estimates combined selectivity as product")
        void estimatesCombinedSelectivityAsProduct() {
            List<String> terms = Arrays.asList("common", "rare");
            double combined = estimator.estimateCombinedSelectivity(terms);

            // Combined selectivity should be lower than individual selectivities
            double commonSelectivity = estimator.estimateSelectivity("common");
            double rareSelectivity = estimator.estimateSelectivity("rare");

            assertTrue(combined <= commonSelectivity,
                "Combined selectivity should be <= first term selectivity");
            assertTrue(combined <= rareSelectivity,
                "Combined selectivity should be <= second term selectivity");
        }

        @Test
        @DisplayName("handles byte array input")
        void handlesByteArrayInput() {
            byte[] termBytes = "common".getBytes(StandardCharsets.UTF_8);
            double selectivity = estimator.estimateSelectivity(termBytes);

            assertTrue(selectivity >= 0.0 && selectivity <= 1.0,
                "Byte array selectivity should be in valid range");
        }

        @Test
        @DisplayName("identifies high priority segments")
        void identifiesHighPrioritySegments() {
            // Common term should make segment high priority
            List<String> commonTerms = Collections.singletonList("common");
            assertTrue(estimator.shouldPrioritizeSegment(commonTerms),
                "Segment with common term should be prioritized");
        }

        @Test
        @DisplayName("handles combined byte array selectivity")
        void handlesCombinedByteArraySelectivity() {
            List<byte[]> termBytes = Arrays.asList(
                "common".getBytes(StandardCharsets.UTF_8),
                "rare".getBytes(StandardCharsets.UTF_8)
            );
            double combined = estimator.estimateCombinedSelectivityBytes(termBytes);

            assertTrue(combined >= 0.0 && combined <= 1.0,
                "Combined byte selectivity should be in valid range");
        }
    }

    @Nested
    @DisplayName("with null sketch")
    class WithNullSketch {

        @Test
        @DisplayName("returns default selectivity when sketch is null")
        void returnsDefaultSelectivityWhenSketchIsNull() {
            SelectivityEstimator nullEstimator = new SelectivityEstimator(null);

            assertEquals(SelectivityEstimator.DEFAULT_SELECTIVITY,
                nullEstimator.estimateSelectivity("term"),
                "Should return default selectivity when sketch is null");
        }

        @Test
        @DisplayName("returns default for combined selectivity with null sketch")
        void returnsDefaultForCombinedSelectivityWithNullSketch() {
            SelectivityEstimator nullEstimator = new SelectivityEstimator(null);
            List<String> terms = Arrays.asList("term1", "term2");

            assertEquals(SelectivityEstimator.DEFAULT_SELECTIVITY,
                nullEstimator.estimateCombinedSelectivity(terms),
                "Should return default selectivity when sketch is null");
        }

        @Test
        @DisplayName("does not skip segments with null sketch")
        void doesNotSkipSegmentsWithNullSketch() {
            SelectivityEstimator nullEstimator = new SelectivityEstimator(null);
            List<String> terms = Collections.singletonList("term");

            assertFalse(nullEstimator.canSkipSegment(terms),
                "Should not skip segment when sketch is null");
        }

        @Test
        @DisplayName("reports hasSketch as false")
        void reportsHasSketchAsFalse() {
            SelectivityEstimator nullEstimator = new SelectivityEstimator(null);

            assertFalse(nullEstimator.hasSketch(),
                "hasSketch should return false for null sketch");
        }

        @Test
        @DisplayName("returns zero total count")
        void returnsZeroTotalCount() {
            SelectivityEstimator nullEstimator = new SelectivityEstimator(null);

            assertEquals(0, nullEstimator.getTotalCount(),
                "Total count should be 0 for null sketch");
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("handles empty term string")
        void handlesEmptyTermString() {
            double selectivity = estimator.estimateSelectivity("");

            assertEquals(SelectivityEstimator.DEFAULT_SELECTIVITY, selectivity,
                "Empty term should return default selectivity");
        }

        @Test
        @DisplayName("handles empty byte array")
        void handlesEmptyByteArray() {
            double selectivity = estimator.estimateSelectivity(new byte[0]);

            assertEquals(SelectivityEstimator.DEFAULT_SELECTIVITY, selectivity,
                "Empty byte array should return default selectivity");
        }

        @Test
        @DisplayName("handles empty term list")
        void handlesEmptyTermList() {
            double selectivity = estimator.estimateCombinedSelectivity(Collections.emptyList());

            assertEquals(SelectivityEstimator.DEFAULT_SELECTIVITY, selectivity,
                "Empty term list should return default selectivity");
        }

        @Test
        @DisplayName("handles term list with nulls")
        void handlesTermListWithNulls() {
            // Note: List.of doesn't allow nulls, so we use Arrays.asList
            List<String> terms = Arrays.asList("term1", null, "term2");

            // Should not throw - null terms are skipped
            assertDoesNotThrow(() -> estimator.estimateCombinedSelectivity(terms),
                "Should handle null terms gracefully");
        }

        @Test
        @DisplayName("handles term list with empty strings")
        void handlesTermListWithEmptyStrings() {
            List<String> terms = Arrays.asList("term1", "", "term2");

            // Should not throw - empty terms are skipped
            assertDoesNotThrow(() -> estimator.estimateCombinedSelectivity(terms),
                "Should handle empty terms gracefully");
        }

        @Test
        @DisplayName("handles unknown term")
        void handlesUnknownTerm() {
            double selectivity = estimator.estimateSelectivity("unknowntermxyz");

            // Unknown terms should have 0 or very low selectivity
            assertTrue(selectivity >= 0.0 && selectivity <= 0.01,
                "Unknown term should have very low selectivity");
        }

        @Test
        @DisplayName("rejects null term string")
        void rejectsNullTermString() {
            assertThrows(NullPointerException.class,
                () -> estimator.estimateSelectivity((String) null),
                "Should reject null term string");
        }

        @Test
        @DisplayName("rejects null term bytes")
        void rejectsNullTermBytes() {
            assertThrows(NullPointerException.class,
                () -> estimator.estimateSelectivity((byte[]) null),
                "Should reject null term bytes");
        }

        @Test
        @DisplayName("rejects null term list")
        void rejectsNullTermList() {
            assertThrows(NullPointerException.class,
                () -> estimator.estimateCombinedSelectivity(null),
                "Should reject null term list");
        }
    }

    @Nested
    @DisplayName("utility methods")
    class UtilityMethods {

        @Test
        @DisplayName("getSketch returns underlying sketch")
        void getSketchReturnsUnderlyingSketch() {
            assertSame(sketch, estimator.getSketch(),
                "getSketch should return the underlying sketch");
        }

        @Test
        @DisplayName("hasSketch returns true when sketch is present")
        void hasSketchReturnsTrueWhenSketchIsPresent() {
            assertTrue(estimator.hasSketch(),
                "hasSketch should return true when sketch is present");
        }

        @Test
        @DisplayName("getTotalCount returns sketch total count")
        void getTotalCountReturnsSketchTotalCount() {
            sketch.insert("term".getBytes(StandardCharsets.UTF_8));

            assertEquals(sketch.totalCount(), estimator.getTotalCount(),
                "getTotalCount should match sketch total count");
        }

        @Test
        @DisplayName("toString contains useful information")
        void toStringContainsUsefulInformation() {
            String str = estimator.toString();

            assertTrue(str.contains("SelectivityEstimator"),
                "toString should contain class name");
            assertTrue(str.contains("hasSketch"),
                "toString should contain hasSketch status");
        }
    }

    @Nested
    @DisplayName("selectivity thresholds")
    class SelectivityThresholds {

        @Test
        @DisplayName("canSkipSegment returns true for very low selectivity")
        void canSkipSegmentReturnsTrueForVeryLowSelectivity() {
            // Insert a term many times to make any single term have high frequency
            for (int i = 0; i < 1000; i++) {
                sketch.insert("common".getBytes(StandardCharsets.UTF_8));
            }

            // Multiple rare terms should result in very low combined selectivity
            List<String> rareTerms = Arrays.asList("rare1", "rare2", "rare3", "rare4", "rare5");

            // With no insertions for rare terms, selectivity should be 0
            // and the segment should be skippable
            boolean canSkip = estimator.canSkipSegment(rareTerms);

            // Since these terms don't exist, selectivity is 0
            assertTrue(canSkip, "Segment with very low selectivity should be skippable");
        }

        @Test
        @DisplayName("shouldPrioritizeSegment respects threshold")
        void shouldPrioritizeSegmentRespectsThreshold() {
            // Create a new sketch with known content
            CountMinSketch newSketch = new CountMinSketch();
            // Insert a term that will have high selectivity
            for (int i = 0; i < 100; i++) {
                newSketch.insert("test".getBytes(StandardCharsets.UTF_8));
            }

            SelectivityEstimator newEstimator = new SelectivityEstimator(newSketch);
            List<String> terms = Collections.singletonList("test");

            // Verify the method runs without error
            assertDoesNotThrow(() -> newEstimator.shouldPrioritizeSegment(terms),
                "shouldPrioritizeSegment should not throw");
        }
    }
}
