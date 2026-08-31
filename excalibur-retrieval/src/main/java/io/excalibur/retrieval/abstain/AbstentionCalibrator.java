package io.excalibur.retrieval.abstain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.subgraph.Subgraph;
import io.excalibur.retrieval.fusion.FusedDocument;

/**
 * Fits an abstention threshold against queries whose answers could be checked.
 *
 * <p>A threshold picked by hand is a number that felt right on the examples someone happened to
 * try. This one is measured: run a set of queries whose correct answer is known, record what
 * retrieval scored and whether it was right, then choose the lowest threshold that still meets a
 * required precision. Lowest, because every point of threshold above what precision demands is
 * coverage given away for nothing — questions the system could have answered and refused to.
 *
 * <h2>Where the labels come from</h2>
 * From the provenance columns. An edge whose {@link SourceType} is {@link
 * SourceType#KNOWLEDGE_BASE} came from a curated statement, so for the slice of queries answered
 * entirely from such edges the correct answer is already known and no human labelling is needed.
 * That slice is what {@link #isVerifiable} identifies. It is a biased sample — curated facts are
 * cleaner than extracted ones — so a threshold fitted here is a floor, and the section below says
 * what to do about that.
 *
 * <h2>The bias, and what it means</h2>
 * Precision measured on curated edges overstates precision on extracted ones. A threshold fitted
 * here will therefore be slightly too permissive in production. Two honest responses: fit at a
 * higher target precision than actually required, or fit separately per {@link SourceType} once
 * enough labelled extracted queries exist. What is not honest is quoting the fitted precision as
 * the system's precision.
 */
public final class AbstentionCalibrator {

    private AbstentionCalibrator() {
    }

    /**
     * One query's outcome, with the ground truth.
     *
     * @param topScore       the best fused score retrieval produced
     * @param separation     relative gap between the best and second candidate
     * @param meanConfidence mean extractor confidence of the evidence
     * @param correct        whether the answer retrieval would have given was right
     */
    public record LabelledOutcome(double topScore, double separation, double meanConfidence,
                                  boolean correct) {
        public LabelledOutcome {
            if (Double.isNaN(topScore) || topScore < 0) {
                throw new IllegalArgumentException("topScore must be non-negative, got " + topScore);
            }
        }
    }

    /**
     * A fitted threshold and what it achieves on the sample it was fitted to.
     *
     * @param minScore   the fitted fused-score threshold
     * @param precision  fraction of answered queries that were correct, at this threshold
     * @param coverage   fraction of queries answered at all, at this threshold
     * @param sampleSize how many labelled queries this was fitted to
     * @param metTarget  whether the requested precision was reachable at any threshold
     */
    public record Calibration(double minScore, double precision, double coverage,
                              int sampleSize, boolean metTarget) {
    }

    /**
     * Whether a query's answer can be checked without a human.
     *
     * <p>True when the evidence is non-empty and every edge came from a knowledge base. "Every",
     * not "mostly": one extracted edge in an otherwise curated subgraph is enough to make the
     * answer unverifiable, and counting it as verified would quietly put unlabelled data into the
     * ground truth.
     */
    public static boolean isVerifiable(Subgraph subgraph) {
        if (subgraph == null || subgraph.edges().isEmpty()) {
            return false;
        }
        return subgraph.edges().stream()
            .allMatch(edge -> edge.provenance().sourceType() == SourceType.KNOWLEDGE_BASE);
    }

    /**
     * Builds a labelled outcome from what retrieval produced.
     *
     * @param fused    the fused candidates, best first
     * @param subgraph the evidence
     * @param correct  whether the answer was right
     */
    public static LabelledOutcome observe(List<FusedDocument> fused, Subgraph subgraph,
                                          boolean correct) {
        if (fused == null || fused.isEmpty()) {
            return new LabelledOutcome(0.0, 0.0, 0.0, correct);
        }
        double top = fused.get(0).score();
        double separation = fused.size() > 1 && top > 0
            ? (top - fused.get(1).score()) / top
            : 1.0;
        double meanConfidence = subgraph == null ? 0.0 : subgraph.meanConfidence();
        return new LabelledOutcome(top, separation, meanConfidence, correct);
    }

    /**
     * Finds the lowest score threshold reaching {@code targetPrecision}.
     *
     * <p>Sweeps every observed score as a candidate threshold — the precision/coverage curve only
     * changes at points where a sample crosses, so there is nothing between them worth testing and
     * nothing outside them that is reachable.
     *
     * <p>If no threshold reaches the target, returns the one with the highest precision and says so
     * in {@link Calibration#metTarget}. Silently returning the best available as though it met the
     * target is how a system ends up believing it is more precise than it is.
     *
     * @param samples        labelled outcomes; at least one required
     * @param targetPrecision required precision, in {@code (0, 1]}
     */
    public static Calibration calibrate(List<LabelledOutcome> samples, double targetPrecision) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException(
                "calibration needs at least one labelled outcome; a threshold cannot be fitted to "
                    + "nothing");
        }
        if (targetPrecision <= 0 || targetPrecision > 1 || Double.isNaN(targetPrecision)) {
            throw new IllegalArgumentException(
                "targetPrecision must be in (0, 1], got " + targetPrecision);
        }

        List<Double> thresholds = new ArrayList<>(samples.size() + 1);
        thresholds.add(0.0);
        for (LabelledOutcome sample : samples) {
            thresholds.add(sample.topScore());
        }
        thresholds.sort(Comparator.naturalOrder());

        Calibration best = null;
        Calibration highestPrecision = null;

        for (double threshold : thresholds) {
            int answered = 0;
            int correct = 0;
            for (LabelledOutcome sample : samples) {
                if (sample.topScore() >= threshold) {
                    answered++;
                    if (sample.correct()) {
                        correct++;
                    }
                }
            }
            if (answered == 0) {
                // A threshold that answers nothing has undefined precision, not perfect precision.
                continue;
            }
            double precision = correct / (double) answered;
            double coverage = answered / (double) samples.size();
            Calibration candidate =
                new Calibration(threshold, precision, coverage, samples.size(), true);

            if (highestPrecision == null || precision > highestPrecision.precision()) {
                highestPrecision = candidate;
            }
            // Thresholds ascend, so the first that reaches the target has the greatest coverage
            // of any that does.
            if (best == null && precision >= targetPrecision) {
                best = candidate;
            }
        }

        if (best != null) {
            return best;
        }
        Calibration fallback = highestPrecision;
        return new Calibration(fallback.minScore(), fallback.precision(), fallback.coverage(),
            fallback.sampleSize(), false);
    }

    /**
     * A policy built from a fitted threshold.
     *
     * @param calibration      the fitted score threshold
     * @param minSeparation    ambiguity threshold, which this calibration does not fit
     * @param minMeanConfidence provenance threshold, which this calibration does not fit
     */
    public static ThresholdAbstentionPolicy toPolicy(Calibration calibration,
                                                     double minSeparation,
                                                     double minMeanConfidence) {
        if (calibration == null) {
            throw new IllegalArgumentException("calibration must not be null");
        }
        return new ThresholdAbstentionPolicy(
            calibration.minScore(), minSeparation, minMeanConfidence, 5);
    }
}
