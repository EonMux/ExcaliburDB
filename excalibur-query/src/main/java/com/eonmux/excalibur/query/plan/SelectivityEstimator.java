package com.eonmux.excalibur.query.plan;

import com.eonmux.excalibur.filter.sketch.CountMinSketch;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Estimates query selectivity using Count-Min Sketch term frequency data.
 *
 * <p>Selectivity estimation helps the query planner determine how many results
 * a query is likely to return, which enables optimization decisions like:</p>
 * <ul>
 *   <li>Segment prioritization - search high-selectivity segments first</li>
 *   <li>Filter ordering - apply more selective filters first</li>
 *   <li>Early termination - skip segments with very low selectivity</li>
 * </ul>
 *
 * <h2>Selectivity Definition</h2>
 * <p>Selectivity is defined as the fraction of documents in a segment that
 * contain a given term. A selectivity of 1.0 means all documents contain the term,
 * while a selectivity of 0.0 means no documents contain it.</p>
 *
 * <h2>Combined Selectivity</h2>
 * <p>For multi-term queries, combined selectivity is estimated by multiplying
 * individual term selectivities. This assumes term independence, which is an
 * approximation but provides reasonable estimates for query planning.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. It delegates to the thread-safe
 * {@link CountMinSketch} for frequency estimation.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Selectivity values are always in range [0.0, 1.0]</li>
 *   <li>Combined selectivity for multiple terms is capped at 1.0</li>
 *   <li>Empty term list returns selectivity 1.0 (match all)</li>
 *   <li>Null sketch returns selectivity 1.0 (conservative default)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public class SelectivityEstimator {

    /**
     * Default selectivity when sketch is unavailable or term not found.
     * Conservative value that doesn't filter aggressively.
     */
    public static final double DEFAULT_SELECTIVITY = 1.0;

    /**
     * Minimum selectivity threshold for early termination optimization.
     * Segments with combined selectivity below this threshold may be skipped.
     */
    public static final double MIN_SELECTIVITY_THRESHOLD = 0.001;

    /**
     * High selectivity threshold for segment prioritization.
     * Segments with selectivity above this are searched first.
     */
    public static final double HIGH_SELECTIVITY_THRESHOLD = 0.1;

    private final CountMinSketch sketch;

    /**
     * Creates a new SelectivityEstimator with the given Count-Min Sketch.
     *
     * @param sketch The Count-Min Sketch containing term frequency data
     *               (may be null, in which case default selectivity is returned)
     */
    public SelectivityEstimator(CountMinSketch sketch) {
        this.sketch = sketch;
    }

    /**
     * Estimates the selectivity of a single term.
     *
     * <p>Selectivity is the fraction of documents in the segment that contain
     * the term. Returns a value in [0.0, 1.0].</p>
     *
     * @param term The term to estimate selectivity for (non-null)
     * @return Estimated selectivity in range [0.0, 1.0]
     * @throws NullPointerException if term is null
     */
    public double estimateSelectivity(String term) {
        Objects.requireNonNull(term, "Term cannot be null");

        if (sketch == null) {
            return DEFAULT_SELECTIVITY;
        }

        if (term.isEmpty()) {
            return DEFAULT_SELECTIVITY;
        }

        byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
        return estimateSelectivity(termBytes);
    }

    /**
     * Estimates the selectivity of a single term given as bytes.
     *
     * <p>This method is useful when terms are already in byte form,
     * avoiding redundant UTF-8 encoding.</p>
     *
     * @param termBytes The term bytes to estimate selectivity for (non-null)
     * @return Estimated selectivity in range [0.0, 1.0]
     * @throws NullPointerException if termBytes is null
     */
    public double estimateSelectivity(byte[] termBytes) {
        Objects.requireNonNull(termBytes, "Term bytes cannot be null");

        if (sketch == null) {
            return DEFAULT_SELECTIVITY;
        }

        if (termBytes.length == 0) {
            return DEFAULT_SELECTIVITY;
        }

        // estimateFrequency returns relative frequency (0.0 to 1.0)
        double frequency = sketch.estimateFrequency(termBytes);

        // Clamp to valid range [0.0, 1.0] for safety
        return Math.max(0.0, Math.min(1.0, frequency));
    }

    /**
     * Estimates combined selectivity for multiple terms.
     *
     * <p>Combined selectivity assumes term independence and is computed as
     * the product of individual term selectivities. This is an approximation
     * that tends to underestimate selectivity when terms are correlated.</p>
     *
     * <h3>Special Cases</h3>
     * <ul>
     *   <li>Empty list returns 1.0 (match all documents)</li>
     *   <li>Null sketch returns 1.0 for all terms</li>
     *   <li>Result is clamped to [0.0, 1.0]</li>
     * </ul>
     *
     * @param terms List of terms to estimate combined selectivity for (non-null)
     * @return Estimated combined selectivity in range [0.0, 1.0]
     * @throws NullPointerException if terms is null
     */
    public double estimateCombinedSelectivity(List<String> terms) {
        Objects.requireNonNull(terms, "Terms list cannot be null");

        if (terms.isEmpty()) {
            return DEFAULT_SELECTIVITY;
        }

        if (sketch == null) {
            return DEFAULT_SELECTIVITY;
        }

        double combinedSelectivity = 1.0;

        for (String term : terms) {
            if (term == null || term.isEmpty()) {
                continue;
            }
            double termSelectivity = estimateSelectivity(term);
            combinedSelectivity *= termSelectivity;

            // Early termination if selectivity drops below minimum
            if (combinedSelectivity < MIN_SELECTIVITY_THRESHOLD) {
                return 0.0;
            }
        }

        // Clamp to valid range
        return Math.max(0.0, Math.min(1.0, combinedSelectivity));
    }

    /**
     * Estimates combined selectivity for multiple terms given as byte arrays.
     *
     * @param termBytesList List of term byte arrays (non-null)
     * @return Estimated combined selectivity in range [0.0, 1.0]
     * @throws NullPointerException if termBytesList is null
     */
    public double estimateCombinedSelectivityBytes(List<byte[]> termBytesList) {
        Objects.requireNonNull(termBytesList, "Term bytes list cannot be null");

        if (termBytesList.isEmpty()) {
            return DEFAULT_SELECTIVITY;
        }

        if (sketch == null) {
            return DEFAULT_SELECTIVITY;
        }

        double combinedSelectivity = 1.0;

        for (byte[] termBytes : termBytesList) {
            if (termBytes == null || termBytes.length == 0) {
                continue;
            }
            double termSelectivity = estimateSelectivity(termBytes);
            combinedSelectivity *= termSelectivity;

            // Early termination if selectivity drops below minimum
            if (combinedSelectivity < MIN_SELECTIVITY_THRESHOLD) {
                return 0.0;
            }
        }

        return Math.max(0.0, Math.min(1.0, combinedSelectivity));
    }

    /**
     * Checks if a segment should be prioritized based on selectivity.
     *
     * <p>Segments with selectivity above {@link #HIGH_SELECTIVITY_THRESHOLD}
     * are considered high-priority and should be searched first.</p>
     *
     * @param terms The query terms
     * @return true if the segment should be prioritized
     */
    public boolean shouldPrioritizeSegment(List<String> terms) {
        double selectivity = estimateCombinedSelectivity(terms);
        return selectivity >= HIGH_SELECTIVITY_THRESHOLD;
    }

    /**
     * Checks if a segment can be skipped based on selectivity.
     *
     * <p>Segments with selectivity below {@link #MIN_SELECTIVITY_THRESHOLD}
     * can be safely skipped without significantly affecting recall.</p>
     *
     * @param terms The query terms
     * @return true if the segment can be skipped
     */
    public boolean canSkipSegment(List<String> terms) {
        double selectivity = estimateCombinedSelectivity(terms);
        return selectivity < MIN_SELECTIVITY_THRESHOLD;
    }

    /**
     * Returns the underlying Count-Min Sketch.
     *
     * @return The sketch, or null if not available
     */
    public CountMinSketch getSketch() {
        return sketch;
    }

    /**
     * Checks if this estimator has an underlying sketch.
     *
     * @return true if sketch is available, false otherwise
     */
    public boolean hasSketch() {
        return sketch != null;
    }

    /**
     * Returns the total count of all items in the sketch.
     *
     * <p>This can be used to estimate the size of the corpus in the segment.</p>
     *
     * @return Total count, or 0 if no sketch is available
     */
    public long getTotalCount() {
        return sketch != null ? sketch.totalCount() : 0;
    }

    @Override
    public String toString() {
        return String.format("SelectivityEstimator[hasSketch=%s, totalCount=%d]",
            hasSketch(), getTotalCount());
    }
}
