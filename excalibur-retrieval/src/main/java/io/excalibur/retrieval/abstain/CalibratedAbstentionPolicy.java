package io.excalibur.retrieval.abstain;

import java.util.List;

import io.excalibur.graph.subgraph.Subgraph;
import io.excalibur.retrieval.fusion.FusedDocument;

/**
 * A policy that answers exactly as the one beneath it, and states its confidence honestly.
 *
 * <h2>It changes the number, never the decision</h2>
 * Deliberately. The decision was fitted by {@link AbstentionCalibrator#calibrate} against a
 * precision target, which chose an operating point on a precision/coverage curve. Re-deriving the
 * decision from a calibrated confidence would move that operating point without anyone asking,
 * and the move would be invisible: the policy would still answer, still decline, still look
 * configured the way it was configured. Whether to answer and how strongly to claim it are two
 * fits, and mixing them means neither can be changed without disturbing the other.
 *
 * <p>Nothing downstream that orders or compares answers is affected either, because temperature
 * scaling is strictly monotone. Two answers keep their order, and an answer above 0.5 stays above
 * it. What changes is only how far from the midpoint the claim sits.
 *
 * <h2>Check the fit before deploying one</h2>
 * A calibration whose {@link ConfidenceCalibration#wellPosed()} is false is applied here like any
 * other - this decorator does not second-guess its argument, and a badly posed fit is still worth
 * running in a harness to see what it does. It is not worth serving. The caller holds that
 * decision because only the caller knows which of the two it is doing.
 *
 * <h2>Why a bare temperature, and not the fit</h2>
 * The operative input is one number, and a node reads it from configuration, where the error a
 * fit measured months ago on someone else's query set is not available and would not be true of
 * this deployment anyway. A {@link ConfidenceCalibration} can be handed over instead, for a
 * harness that has just produced one.
 *
 * <h2>Thread Safety</h2>
 * Immutable, and safe to share as long as the delegate is. The scaling works entirely in locals,
 * so this adds no shared state of its own.
 */
public final class CalibratedAbstentionPolicy implements AbstentionPolicy {

    /** A temperature that changes nothing, and the value a node configures to opt out. */
    public static final double NEUTRAL_TEMPERATURE = 1.0;

    private final AbstentionPolicy delegate;
    private final double temperature;

    /**
     * @param delegate    the policy that decides; its confidences are the ones being corrected
     * @param temperature the fitted divisor on the log-odds; above 1 softens, below 1 sharpens
     */
    public CalibratedAbstentionPolicy(AbstentionPolicy delegate, double temperature) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate is required");
        }
        if (Double.isNaN(temperature) || Double.isInfinite(temperature) || temperature <= 0.0) {
            throw new IllegalArgumentException(
                "temperature must be finite and positive, got " + temperature);
        }
        if (delegate instanceof CalibratedAbstentionPolicy) {
            // Two temperatures compose into a third, so the result would be a policy scaled by a
            // number nobody fitted, reporting the inner calibration's error as though it still
            // held. Refit against the calibrated policy instead, and wrap the raw one in that.
            throw new IllegalArgumentException(
                "delegate is already calibrated; stacking temperatures reports an error that was "
                    + "never measured");
        }
        this.delegate = delegate;
        this.temperature = temperature;
    }

    /**
     * @param delegate    the policy that decides; its confidences are the ones being corrected
     * @param calibration the fit measured against that same policy
     */
    public CalibratedAbstentionPolicy(AbstentionPolicy delegate,
                                      ConfidenceCalibration calibration) {
        this(delegate, temperatureOf(calibration));
    }

    private static double temperatureOf(ConfidenceCalibration calibration) {
        if (calibration == null) {
            throw new IllegalArgumentException("calibration is required");
        }
        return calibration.temperature();
    }

    /** The fitted temperature in use. */
    public double temperature() {
        return temperature;
    }

    @Override
    public RetrievalOutcome decide(List<FusedDocument> fused, Subgraph subgraph) {
        RetrievalOutcome outcome = delegate.decide(fused, subgraph);
        if (outcome instanceof RetrievalOutcome.Results results) {
            return new RetrievalOutcome.Results(
                results.subgraph(),
                ConfidenceCalibration.scale(results.confidence(), temperature));
        }
        // A refusal carries no confidence and an ambiguity carries candidate scores, which are
        // fused scores rather than probabilities and so are not what this temperature was fitted
        // to. Both pass through untouched.
        return outcome;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "CalibratedAbstentionPolicy[%s @ T=%.4f]",
            delegate, temperature);
    }
}
