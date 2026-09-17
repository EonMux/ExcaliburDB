package io.excalibur.metrics;

/**
 * The clamping rule shared by every metric whose domain is the closed interval [0, 1].
 *
 * <h2>Why this exists</h2>
 * <p>Health scores, corpus coverage and filter false-positive rates are all published as
 * fractions, and each is computed from a division that can leave the interval: a ratio taken
 * before its denominator filled, a latency past its own budget, a sample averaged over no
 * samples. {@code Math.max}/{@code Math.min} alone let NaN through, and a NaN published as a
 * health score is rejected downstream by the stats endpoint rather than read as "unknown", so
 * the node looks broken for a reason that is not in the node. Stated once here rather than at
 * each call site that needs it, because a call site that forgets it fails silently.</p>
 *
 * @author ExcaliburDB
 */
final class UnitInterval {

    /** Not instantiable: this is a rule about values, not a value of its own. */
    private UnitInterval() {
    }

    /**
     * Clamps a rate or score into [0.0, 1.0], mapping NaN to 0.0.
     *
     * @param value Raw value
     * @return Value clamped to the valid domain
     */
    static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
