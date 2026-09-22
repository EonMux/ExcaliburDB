package com.eonmux.excalibur.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RelevancyScore}.
 *
 * <h2>Domain Correctness Verification</h2>
 *
 * <h3>Score Clamping (CRITICAL)</h3>
 * <p>Every score must be in the valid range [0.0, 1.0] after construction.
 * This includes handling edge cases:</p>
 * <ul>
 *   <li>Normal values in range → unchanged</li>
 *   <li>Values above 1.0 → clamped to 1.0</li>
 *   <li>Values below 0.0 → clamped to 0.0</li>
 *   <li>NaN → 0.0 (treat as lowest relevancy)</li>
 *   <li>Positive infinity → 1.0</li>
 *   <li>Negative infinity → 0.0</li>
 *   <li>Maximum finite double → 1.0</li>
 *   <li>Minimum finite double → 0.0</li>
 * </ul>
 *
 * <h3>Index Validation</h3>
 * <ul>
 *   <li>Index must be &ge; 0 (negative throws IllegalArgumentException)</li>
 * </ul>
 */
@Timeout(value = 10)
class RelevancyScoreTest {

    private static final double EPSILON = 0.0001;

    // ============= Score Clamping: Normal Values in Range =============

    @Test
    void constructor_scoreInMiddleOfRange_unchanged() {
        RelevancyScore rs = new RelevancyScore(0, 0.5, "test");
        assertEquals(0.5, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_scoreAtLowerBound_unchanged() {
        RelevancyScore rs = new RelevancyScore(0, 0.0, "test");
        assertEquals(0.0, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_scoreAtUpperBound_unchanged() {
        RelevancyScore rs = new RelevancyScore(0, 1.0, "test");
        assertEquals(1.0, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_scoreNearLowerBound_unchanged() {
        RelevancyScore rs = new RelevancyScore(0, 0.0001, "test");
        assertEquals(0.0001, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_scoreNearUpperBound_unchanged() {
        RelevancyScore rs = new RelevancyScore(0, 0.9999, "test");
        assertEquals(0.9999, rs.getScore(), EPSILON);
    }

    // ============= Score Clamping: Values Above Range =============

    @Test
    void constructor_scoreAboveOne_clampedToOne() {
        RelevancyScore rs = new RelevancyScore(0, 1.5, "test");
        assertEquals(1.0, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_scoreSlightlyAboveOne_clampedToOne() {
        RelevancyScore rs = new RelevancyScore(0, 1.0001, "test");
        assertEquals(1.0, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_scoreLargePositive_clampedToOne() {
        RelevancyScore rs = new RelevancyScore(0, 1000.0, "test");
        assertEquals(1.0, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_scoreMaxDouble_clampedToOne() {
        // Domain correctness: largest finite double must be clamped to 1.0
        RelevancyScore rs = new RelevancyScore(0, Double.MAX_VALUE, "test");
        assertEquals(1.0, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_positiveInfinity_clampedToOne() {
        // Domain correctness: positive infinity must be clamped to 1.0
        RelevancyScore rs = new RelevancyScore(0, Double.POSITIVE_INFINITY, "test");
        assertEquals(1.0, rs.getScore(), EPSILON);
        assertTrue(Double.isFinite(rs.getScore()), "Clamped score must be finite");
    }

    // ============= Score Clamping: Values Below Range =============

    @Test
    void constructor_scoreBelowZero_clampedToZero() {
        RelevancyScore rs = new RelevancyScore(0, -0.3, "test");
        assertEquals(0.0, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_scoreSlightlyBelowZero_clampedToZero() {
        RelevancyScore rs = new RelevancyScore(0, -0.0001, "test");
        assertEquals(0.0, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_scoreLargeNegative_clampedToZero() {
        RelevancyScore rs = new RelevancyScore(0, -1000.0, "test");
        assertEquals(0.0, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_scoreMinDouble_clampedToZero() {
        // Domain correctness: most negative finite double must be clamped to 0.0
        RelevancyScore rs = new RelevancyScore(0, -Double.MAX_VALUE, "test");
        assertEquals(0.0, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_negativeInfinity_clampedToZero() {
        // Domain correctness: negative infinity must be clamped to 0.0
        RelevancyScore rs = new RelevancyScore(0, Double.NEGATIVE_INFINITY, "test");
        assertEquals(0.0, rs.getScore(), EPSILON);
        assertTrue(Double.isFinite(rs.getScore()), "Clamped score must be finite");
    }

    // ============= Score Clamping: NaN (CRITICAL EDGE CASE) =============

    @Test
    void constructor_scoreNaN_clampedToZero() {
        // Domain correctness: NaN must be clamped to 0.0 (lowest relevancy)
        // This is critical because Math.max/min don't work correctly with NaN
        RelevancyScore rs = new RelevancyScore(0, Double.NaN, "test");
        assertEquals(0.0, rs.getScore(), EPSILON);
        assertTrue(Double.isFinite(rs.getScore()), "Clamped score must be finite");
        assertFalse(Double.isNaN(rs.getScore()), "Clamped score must not be NaN");
    }

    // ============= Score Clamping: Special Values =============

    @Test
    void constructor_negativeZero_unchanged() {
        // Negative zero is technically 0.0, should remain 0.0
        RelevancyScore rs = new RelevancyScore(0, -0.0, "test");
        assertEquals(0.0, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_smallPositiveValue_unchanged() {
        RelevancyScore rs = new RelevancyScore(0, Double.MIN_VALUE, "test");
        assertEquals(Double.MIN_VALUE, rs.getScore(), EPSILON);
    }

    @Test
    void constructor_verySmallPositiveValue_unchanged() {
        double verySmall = 1e-300;
        RelevancyScore rs = new RelevancyScore(0, verySmall, "test");
        assertEquals(verySmall, rs.getScore(), EPSILON);
    }

    // ============= Index Validation =============

    @Test
    void constructor_validIndex_accepted() {
        RelevancyScore rs = new RelevancyScore(42, 0.5, "test");
        assertEquals(42, rs.getIndex());
    }

    @Test
    void constructor_zeroIndex_accepted() {
        RelevancyScore rs = new RelevancyScore(0, 0.5, "test");
        assertEquals(0, rs.getIndex());
    }

    @Test
    void constructor_largeIndex_accepted() {
        RelevancyScore rs = new RelevancyScore(Integer.MAX_VALUE, 0.5, "test");
        assertEquals(Integer.MAX_VALUE, rs.getIndex());
    }

    @Test
    void constructor_negativeIndex_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
            new RelevancyScore(-1, 0.5, "test"));
    }

    @Test
    void constructor_negativeOneIndex_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
            new RelevancyScore(-1, 0.5, "test"));
    }

    @Test
    void constructor_minIntegerIndex_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
            new RelevancyScore(Integer.MIN_VALUE, 0.5, "test"));
    }

    // ============= Candidate Text Handling =============

    @Test
    void constructor_nullCandidateText_accepted() {
        RelevancyScore rs = new RelevancyScore(0, 0.5, null);
        assertNull(rs.getCandidateText());
    }

    @Test
    void constructor_emptyCandidateText_accepted() {
        RelevancyScore rs = new RelevancyScore(0, 0.5, "");
        assertEquals("", rs.getCandidateText());
    }

    @Test
    void constructor_nonNullCandidateText_preserved() {
        String text = "This is a test passage about databases.";
        RelevancyScore rs = new RelevancyScore(0, 0.5, text);
        assertEquals(text, rs.getCandidateText());
    }

    @Test
    void twoArgConstructor_candidateTextIsNull() {
        RelevancyScore rs = new RelevancyScore(0, 0.5);
        assertNull(rs.getCandidateText());
    }

    @Test
    void twoArgConstructor_scoreClamped() {
        RelevancyScore rs = new RelevancyScore(0, 1.5);
        assertEquals(1.0, rs.getScore(), EPSILON);
    }

    // ============= Threshold Checking =============

    @Test
    void meetsThreshold_scoreAboveThreshold_returnsTrue() {
        RelevancyScore rs = new RelevancyScore(0, 0.7, "test");
        assertTrue(rs.meetsThreshold(0.5));
    }

    @Test
    void meetsThreshold_scoreBelowThreshold_returnsFalse() {
        RelevancyScore rs = new RelevancyScore(0, 0.3, "test");
        assertFalse(rs.meetsThreshold(0.5));
    }

    @Test
    void meetsThreshold_scoreEqualToThreshold_returnsTrue() {
        RelevancyScore rs = new RelevancyScore(0, 0.5, "test");
        assertTrue(rs.meetsThreshold(0.5));
    }

    @Test
    void meetsThreshold_clampedScoreWorksCorrectly() {
        // Score 1.5 clamped to 1.0 should meet threshold 0.9
        RelevancyScore rs = new RelevancyScore(0, 1.5, "test");
        assertTrue(rs.meetsThreshold(0.9));
    }

    @Test
    void meetsThreshold_clampedScoreAtBoundary() {
        // Score -0.3 clamped to 0.0 should not meet threshold 0.1
        RelevancyScore rs = new RelevancyScore(0, -0.3, "test");
        assertFalse(rs.meetsThreshold(0.1));
    }

    // ============= Equals and HashCode =============

    @Test
    void equals_sameValues_returnsTrue() {
        RelevancyScore rs1 = new RelevancyScore(0, 0.5, "test");
        RelevancyScore rs2 = new RelevancyScore(0, 0.5, "test");
        assertEquals(rs1, rs2);
    }

    @Test
    void equals_differentIndex_returnsFalse() {
        RelevancyScore rs1 = new RelevancyScore(0, 0.5, "test");
        RelevancyScore rs2 = new RelevancyScore(1, 0.5, "test");
        assertNotEquals(rs1, rs2);
    }

    @Test
    void equals_differentScore_returnsFalse() {
        RelevancyScore rs1 = new RelevancyScore(0, 0.5, "test");
        RelevancyScore rs2 = new RelevancyScore(0, 0.6, "test");
        assertNotEquals(rs1, rs2);
    }

    @Test
    void equals_differentText_returnsFalse() {
        RelevancyScore rs1 = new RelevancyScore(0, 0.5, "test1");
        RelevancyScore rs2 = new RelevancyScore(0, 0.5, "test2");
        assertNotEquals(rs1, rs2);
    }

    @Test
    void equals_nullTextBoth_returnsTrue() {
        RelevancyScore rs1 = new RelevancyScore(0, 0.5, null);
        RelevancyScore rs2 = new RelevancyScore(0, 0.5, null);
        assertEquals(rs1, rs2);
    }

    @Test
    void equals_oneNullText_returnsFalse() {
        RelevancyScore rs1 = new RelevancyScore(0, 0.5, null);
        RelevancyScore rs2 = new RelevancyScore(0, 0.5, "test");
        assertNotEquals(rs1, rs2);
    }

    @Test
    void hashCode_consistentWithEquals() {
        RelevancyScore rs1 = new RelevancyScore(0, 0.5, "test");
        RelevancyScore rs2 = new RelevancyScore(0, 0.5, "test");
        assertEquals(rs1.hashCode(), rs2.hashCode());
    }

    // ============= Thread Safety (Immutability) =============

    @Test
    void immutability_scoreCannotBeModified() {
        // RelevancyScore is immutable - no setters exist
        // This test verifies the class structure is correct
        RelevancyScore rs = new RelevancyScore(0, 0.5, "test");
        assertEquals(0, rs.getIndex());
        assertEquals(0.5, rs.getScore(), EPSILON);
        assertEquals("test", rs.getCandidateText());

        // Multiple calls return same values
        assertEquals(0, rs.getIndex());
        assertEquals(0.5, rs.getScore(), EPSILON);
        assertEquals("test", rs.getCandidateText());
    }

    // ============= toString =============

    @Test
    void toString_containsIndex() {
        RelevancyScore rs = new RelevancyScore(42, 0.5, "test");
        assertTrue(rs.toString().contains("42"));
    }

    @Test
    void toString_containsScore() {
        RelevancyScore rs = new RelevancyScore(0, 0.75, "test");
        assertTrue(rs.toString().contains("0.75"));
    }

    @Test
    void toString_nullText_noTextLength() {
        RelevancyScore rs = new RelevancyScore(0, 0.5, null);
        String str = rs.toString();
        assertTrue(str.contains("index=0"));
        assertTrue(str.contains("score=0.5"));
        assertFalse(str.contains("textLength"));
    }

    @Test
    void toString_nonNullText_containsTextLength() {
        RelevancyScore rs = new RelevancyScore(0, 0.5, "hello world");
        String str = rs.toString();
        assertTrue(str.contains("textLength=11"));
    }

    // ============= Domain Correctness: Score Always Finite =============

    @Test
    void domainCorrectness_allScoresAreFinite() {
        // Comprehensive test: all edge case inputs produce finite outputs
        double[] edgeCases = {
            0.5,
            0.0,
            1.0,
            -0.0,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Double.MAX_VALUE,
            -Double.MAX_VALUE,
            Double.MIN_VALUE,
            1.5,
            -0.3,
            1000.0,
            -1000.0,
            1e-300
        };

        for (double score : edgeCases) {
            RelevancyScore rs = new RelevancyScore(0, score, "test");
            assertTrue(Double.isFinite(rs.getScore()),
                "Score must be finite for input: " + score);
            assertTrue(rs.getScore() >= 0.0 && rs.getScore() <= 1.0,
                "Score must be in [0.0, 1.0] for input: " + score + ", got: " + rs.getScore());
        }
    }

    @Test
    void domainCorrectness_allScoresInValidRange() {
        // Verify that after construction, getScore() ALWAYS returns value in [0.0, 1.0]
        double[] testScores = {0.0, 0.25, 0.5, 0.75, 1.0, 1.5, -0.5, Double.NaN, Double.POSITIVE_INFINITY};

        for (double score : testScores) {
            RelevancyScore rs = new RelevancyScore(0, score);
            double result = rs.getScore();

            assertTrue(result >= 0.0,
                "Score must be >= 0.0 for input " + score + ", got: " + result);
            assertTrue(result <= 1.0,
                "Score must be <= 1.0 for input " + score + ", got: " + result);
        }
    }
}
