package com.eonmux.excalibur.retrieval.abstain;

import java.util.List;
import java.util.Locale;

/**
 * A fitted temperature, and what it did to the reliability of the confidences it was fitted to.
 *
 * <h2>What a temperature is for</h2>
 * A reported confidence is built from a fused score — which has no absolute meaning — by
 * arithmetic over constants someone chose. The result is usually monotone in the evidence, so it
 * orders answers correctly, without being a probability: it can claim 0.9 across a population that
 * turns out to be right 0.6 of the time, and every caller that reads 0.9 as nine-in-ten is then
 * wrong in the same direction.
 *
 * <p>Temperature scaling is the smallest correction that fixes this. It divides the confidence's
 * log-odds by one fitted scalar and maps the result back. Because that transform is strictly
 * monotone, it cannot reorder two answers and so cannot damage a ranking; because it fixes the
 * midpoint, it cannot turn an answer the policy leaned towards into one it leaned against.
 * Everything a temperature can change, it changes about how strongly a claim is stated and nothing
 * about which claim is made. One parameter is also all a modest sample can support — fitting a
 * richer correction to a few hundred labelled queries buys a better in-sample number and a worse
 * deployment.
 *
 * <p>A temperature above 1 softens: the confidences were overconfident and get pulled towards 0.5.
 * Below 1 sharpens. At exactly 1 the fit found nothing to correct.
 *
 * <h2>Reading the two errors</h2>
 * {@link #uncalibratedError()} is the expected calibration error of the raw confidences;
 * {@link #expectedCalibrationError()} is that of the scaled ones, over the same sample. The second
 * being far below the first is the fit doing work. Both already near zero means the confidences
 * were honest and the temperature should be near 1.
 *
 * <p>Both are <em>in-sample</em>. The temperature was fitted to these outcomes and then scored
 * against them, so the improvement is an upper bound on what held-out queries would show. Quoting
 * {@link #expectedCalibrationError()} as a deployment's calibration error commits exactly the
 * error this type exists to catch — and on top of that, the labelled sample available here comes
 * from {@link AbstentionCalibrator#isVerifiable}, which is curated rather than representative. Hold
 * queries back, or refit periodically against fresh outcomes, and quote the held-out number.
 *
 * @param temperature              the fitted divisor on the log-odds; 1 means no correction
 * @param expectedCalibrationError ECE after scaling, in {@code [0, 1]}, lower being better
 * @param uncalibratedError        ECE of the same sample before scaling
 * @param sampleSize               how many answered queries this was fitted to
 * @param wellPosed                whether the sample actually determines a temperature; see
 *                                 {@link #wellPosed()}
 * @param reliability              the reliability diagram after scaling, lowest band first
 */
public record ConfidenceCalibration(double temperature,
                                    double expectedCalibrationError,
                                    double uncalibratedError,
                                    int sampleSize,
                                    boolean wellPosed,
                                    List<ReliabilityBin> reliability) {

    /**
     * How close to 0 or 1 a confidence may be taken before its log-odds are clamped.
     *
     * <p>The log-odds of 0 and of 1 are infinite, and a single such sample would otherwise decide
     * the fit on its own. Clamping bounds the log-odds at about ±13.8, which is far outside the
     * range any policy here reports and so costs nothing in practice.
     */
    static final double EPSILON = 1e-6;

    public ConfidenceCalibration {
        if (Double.isNaN(temperature) || temperature <= 0.0 || Double.isInfinite(temperature)) {
            throw new IllegalArgumentException(
                "temperature must be finite and positive, got " + temperature);
        }
        requireError("expectedCalibrationError", expectedCalibrationError);
        requireError("uncalibratedError", uncalibratedError);
        if (sampleSize < 0) {
            throw new IllegalArgumentException("sampleSize must be non-negative, got " + sampleSize);
        }
        if (reliability == null) {
            throw new IllegalArgumentException("reliability must not be null");
        }
        reliability = List.copyOf(reliability);
    }

    private static void requireError(String name, double value) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0, 1], got " + value);
        }
    }

    /**
     * Whether the sample determines a temperature at all.
     *
     * <p>False when the fit ran to the bounds of the searched range instead of settling inside it.
     * Two samples do that. One where confidence separates right from wrong perfectly — every
     * correct answer more confident than every incorrect one — has a likelihood that keeps
     * improving as the temperature falls towards zero, and the honest reading is that the sample
     * is too small or too easy rather than that the policy should report near-certainty. One where
     * confidence carries no information at all drives the temperature the other way, towards
     * reporting 0.5 for everything.
     *
     * <p>Reported rather than thrown, because both are real findings about a query set worth
     * seeing. It is the caller's to check before deploying a temperature: {@link #apply} will use
     * whatever was fitted.
     */
    @Override
    public boolean wellPosed() {
        return wellPosed;
    }

    /**
     * Applies the fitted temperature to one raw confidence.
     *
     * <p>Capped at {@link ThresholdAbstentionPolicy#MAX_REPORTED_CONFIDENCE}, for the reason given
     * there and with more force after a fit than before one: the temperature was measured on a
     * finite, in-sample, curated set of outcomes, so a calibrated 0.999 is a claim about that set
     * and not about the next query.
     *
     * @param rawConfidence what the underlying policy reported, in {@code [0, 1]}
     * @return the calibrated probability, in {@code [0, MAX_REPORTED_CONFIDENCE]}
     */
    public double apply(double rawConfidence) {
        if (Double.isNaN(rawConfidence) || rawConfidence < 0.0 || rawConfidence > 1.0) {
            throw new IllegalArgumentException(
                "rawConfidence must be in [0, 1], got " + rawConfidence);
        }
        return scale(rawConfidence, temperature);
    }

    /**
     * The transform itself, separated from the record so that a fit can measure its own result.
     *
     * <p>{@link AbstentionCalibrator#fitTemperature} needs to score the scaled confidences before
     * it has a {@code ConfidenceCalibration} to call {@link #apply} on. Having it repeat the
     * arithmetic instead would let the error a calibration reports drift away from the numbers it
     * goes on to produce — including the cap, which is part of what gets reported and therefore
     * part of what has to be measured.
     */
    static double scale(double rawConfidence, double temperature) {
        double scaled = sigmoid(logit(rawConfidence) / temperature);
        return Math.max(0.0,
            Math.min(ThresholdAbstentionPolicy.MAX_REPORTED_CONFIDENCE, scaled));
    }

    /** The log-odds of a probability, with its argument clamped away from 0 and 1. */
    static double logit(double probability) {
        double clamped = Math.max(EPSILON, Math.min(1.0 - EPSILON, probability));
        return Math.log(clamped / (1.0 - clamped));
    }

    /**
     * The logistic function.
     *
     * <p>Written so that the exponential is only ever taken of a non-positive number. The naive
     * form overflows to infinity for large positive arguments, and a temperature near the bottom
     * of the fitted range makes the argument large by design.
     */
    static double sigmoid(double x) {
        if (x >= 0.0) {
            return 1.0 / (1.0 + Math.exp(-x));
        }
        double exp = Math.exp(x);
        return exp / (1.0 + exp);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
            "ConfidenceCalibration[temperature=%.4f, ece=%.4f (was %.4f), n=%d%s]",
            temperature, expectedCalibrationError, uncalibratedError, sampleSize,
            wellPosed ? "" : ", NOT WELL POSED");
    }
}
