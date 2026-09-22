package com.eonmux.excalibur.llm;

import java.util.Objects;

/**
 * Represents a relevancy score for a candidate passage.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the score
 * cannot be modified.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Index: must be &ge; 0 (reject negative)</li>
 *   <li>Score: must be in [0.0, 1.0] (clamp to range)</li>
 * </ul>
 */
public final class RelevancyScore {

    private final int index;
    private final double score;
    private final String candidateText;

    /**
     * Creates a new RelevancyScore.
     *
     * @param index         The candidate index in the original list (must be &ge; 0)
     * @param score         The relevancy score (will be clamped to [0.0, 1.0])
     * @param candidateText The original candidate text (may be null)
     * @throws IllegalArgumentException if index is negative
     */
    public RelevancyScore(int index, double score, String candidateText) {
        if (index < 0) {
            throw new IllegalArgumentException("Index must be >= 0, got: " + index);
        }
        this.index = index;
        // Clamp score to valid range [0.0, 1.0]
        // Handle NaN first (NaN comparisons return false, so Math.max/min don't work)
        this.score = clampScore(score);
        this.candidateText = candidateText;
    }

    /**
     * Clamps a score to the valid range [0.0, 1.0].
     *
     * <p>Handles edge cases:</p>
     * <ul>
     *   <li>NaN → 0.0 (treat as lowest relevancy)</li>
     *   <li>Positive infinity → 1.0</li>
     *   <li>Negative infinity → 0.0</li>
     *   <li>Values &gt; 1.0 → 1.0</li>
     *   <li>Values &lt; 0.0 → 0.0</li>
     * </ul>
     *
     * @param score Raw score value
     * @return Score clamped to [0.0, 1.0], guaranteed to be finite
     */
    private static double clampScore(double score) {
        // NaN must be handled explicitly since Math.max/min don't work with NaN
        if (Double.isNaN(score)) {
            return 0.0;
        }
        // Clamp to valid range - handles infinities and out-of-range values
        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * Creates a new RelevancyScore without candidate text.
     *
     * @param index The candidate index in the original list (must be &ge; 0)
     * @param score The relevancy score (will be clamped to [0.0, 1.0])
     */
    public RelevancyScore(int index, double score) {
        this(index, score, null);
    }

    /**
     * Returns the index of this candidate in the original list.
     *
     * @return Candidate index (always &ge; 0)
     */
    public int getIndex() {
        return index;
    }

    /**
     * Returns the relevancy score.
     *
     * @return Score in range [0.0, 1.0]
     */
    public double getScore() {
        return score;
    }

    /**
     * Returns the original candidate text, if available.
     *
     * @return Candidate text or null
     */
    public String getCandidateText() {
        return candidateText;
    }

    /**
     * Checks if this score meets the given threshold.
     *
     * @param threshold Threshold to check against (should be in [0.0, 1.0])
     * @return true if score &ge; threshold
     */
    public boolean meetsThreshold(double threshold) {
        return score >= threshold;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RelevancyScore that = (RelevancyScore) o;
        return index == that.index &&
                Double.compare(that.score, score) == 0 &&
                Objects.equals(candidateText, that.candidateText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, score, candidateText);
    }

    @Override
    public String toString() {
        return "RelevancyScore{" +
                "index=" + index +
                ", score=" + score +
                (candidateText != null ? ", textLength=" + candidateText.length() : "") +
                '}';
    }
}
