package com.eonmux.excalibur.store;

/**
 * Exception thrown by {@link Segment} when a write cannot be admitted because the
 * segment has run out of some capacity resource.
 *
 * <p>A segment has two independent capacity limits and either one can be reached
 * first depending on the workload:</p>
 * <ul>
 *   <li>{@link Reason#LOAD_FACTOR} — the slot table has reached
 *       {@link Segment#LOAD_FACTOR_THRESHOLD}. Typical for small values.</li>
 *   <li>{@link Reason#DATA_REGION} — the fixed-size data region cannot hold another
 *       entry record. Typical for vector payloads, which exhaust the data region long
 *       before the slot table fills.</li>
 *   <li>{@link Reason#PROBE_EXHAUSTED} / {@link Reason#DISTANCE_OVERFLOW} — the Robin
 *       Hood probe chain could not be completed. Both mean the slot table is
 *       effectively full for this key.</li>
 * </ul>
 *
 * <p>All of these are capacity signals and must be routed to the same capacity
 * handling (split / retry) by callers. Callers must never distinguish them by
 * matching on the exception message.</p>
 *
 * <p>This type extends {@link IllegalStateException} so that existing callers which
 * catch {@code IllegalStateException} around segment mutations keep working.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 * @see StorageEngine#put(byte[], byte[])
 * @see SegmentAtCapacityException
 */
public class SegmentCapacityExceededException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * The specific capacity resource that was exhausted.
     */
    public enum Reason {
        /** The slot table reached the load factor threshold. */
        LOAD_FACTOR,
        /** The data region cannot hold another entry record. */
        DATA_REGION,
        /** The Robin Hood probe chain exceeded {@link Segment#MAX_PROBE_LENGTH}. */
        PROBE_EXHAUSTED,
        /** A displaced entry's distance would exceed {@link Segment#MAX_DISTANCE}. */
        DISTANCE_OVERFLOW
    }

    private final Reason reason;

    /**
     * Creates a new capacity exception.
     *
     * @param reason  The capacity resource that was exhausted (must not be null)
     * @param message The error message
     */
    public SegmentCapacityExceededException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Returns the capacity resource that was exhausted.
     *
     * @return The reason
     */
    public Reason reason() {
        return reason;
    }
}
