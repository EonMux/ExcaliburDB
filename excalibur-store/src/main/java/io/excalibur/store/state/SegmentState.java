package io.excalibur.store.state;

import io.excalibur.store.LifecycleState;

/**
 * Enum representing the lifecycle states of a segment in ExcaliburDB.
 *
 * <p><strong>DEPRECATED:</strong> This enum is deprecated. Use {@link LifecycleState} directly.
 * This enum now delegates to {@link LifecycleState} for all operations.
 *
 * <p>State lifecycle:
 * BOOTSTRAPPING → INDEXING → WARMING → ACTIVE → COMPACTING → ACTIVE
 *                                                    ↓
                                                TOMBSTONED → PURGED
 *
 * <p>Thread-safety: All state transitions are atomic using VarHandle.
 * No external synchronization required for state access.
 *
 * @deprecated Use {@link LifecycleState} directly instead.
 *             This enum is maintained only for backward compatibility.
 * @see LifecycleState
 */
@Deprecated(since = "2.0", forRemoval = true)
public enum SegmentState {
    /**
     * Segment is allocated but has no data yet.
     * Invisible to queries.
     */
    BOOTSTRAPPING(0),

    /**
     * Entries are being written. Cuckoo filter and LSH incrementally populated.
     * Queryable - readers see a snapshot; concurrent writes via append log do not corrupt reads.
     */
    INDEXING(1),

    /**
     * Batch complete. HNSW edges being optimized, PQ codebooks training.
     * Queryable with pre-optimization structures.
     */
    WARMING(2),

    /**
     * Fully indexed, fully optimized.
     */
    ACTIVE(3),

    /**
     * Tombstones being cleaned. Frozen snapshot serves queries.
     */
    COMPACTING(4),

    /**
     * Invisible to new queries. Kept on disk until all in-flight queries drain.
     * Cuckoo filter entries for this segment are deleted and removals propagated up the hierarchy.
     */
    TOMBSTONED(5),

    /**
     * Disk freed. Final state.
     */
    PURGED(6);

    /**
     * Numeric encoding of the state for efficient comparison and storage.
     */
    private final int code;

    SegmentState(int code) {
        this.code = code;
    }

    /**
     * Get the numeric code for this state.
     */
    public int getCode() {
        return code;
    }

    /**
     * Get the state by its numeric code.
     * @param code the numeric code
     * @return the state, or null if not found
     */
    public static SegmentState fromCode(int code) {
        return switch (code) {
            case 0 -> BOOTSTRAPPING;
            case 1 -> INDEXING;
            case 2 -> WARMING;
            case 3 -> ACTIVE;
            case 4 -> COMPACTING;
            case 5 -> TOMBSTONED;
            case 6 -> PURGED;
            default -> null;
        };
    }

    /**
     * Get the human-readable name for this state.
     */
    public String toStringHuman() {
        return this.name();
    }

    /**
     * Converts this deprecated SegmentState to the canonical LifecycleState.
     *
     * @return the equivalent LifecycleState
     * @deprecated Use LifecycleState directly instead
     */
    @Deprecated(since = "2.0")
    public LifecycleState toLifecycleState() {
        return switch (this) {
            case BOOTSTRAPPING -> LifecycleState.BOOTSTRAPPING;
            case INDEXING -> LifecycleState.INDEXING;
            case WARMING -> LifecycleState.WARMING;
            case ACTIVE -> LifecycleState.ACTIVE;
            case COMPACTING -> LifecycleState.COMPACTING;
            case TOMBSTONED -> LifecycleState.TOMBSTONED;
            case PURGED -> LifecycleState.PURGED;
        };
    }

    /**
     * Converts from the canonical LifecycleState to this deprecated enum.
     *
     * @param lifecycleState the lifecycle state to convert
     * @return the equivalent SegmentState
     * @deprecated Use LifecycleState directly instead
     */
    @Deprecated(since = "2.0")
    public static SegmentState fromLifecycleState(LifecycleState lifecycleState) {
        if (lifecycleState == null) {
            return null;
        }
        return fromCode(lifecycleState.value());
    }

    /**
     * Checks if this state is queryable.
     *
     * @return true if the segment can be queried
     * @deprecated Use {@link LifecycleState#isQueryable()} instead
     */
    @Deprecated(since = "2.0")
    public boolean isQueryable() {
        return toLifecycleState().isQueryable();
    }

    /**
     * Validates that the given object is a valid segment state from the lifecycle enum.
     *
     * @param state the state to validate
     * @return true if the state is a valid LifecycleState or SegmentState
     * @deprecated Use LifecycleState directly
     */
    @Deprecated(since = "2.0")
    public static boolean isValidState(Object state) {
        if (state instanceof LifecycleState) {
            return true;
        }
        return state instanceof SegmentState;
    }
}
