package excalibur.fsck.check;

/**
 * How much a cross-file inconsistency matters.
 */
public enum FindingSeverity {

    /** The store is inconsistent with itself and something is unreachable or absent. */
    DAMAGE,

    /** Worth an operator's attention, but nothing is lost and nothing is unreachable. */
    WARNING
}
