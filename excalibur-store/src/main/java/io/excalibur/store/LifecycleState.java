package io.excalibur.store;

/**
 * LifecycleState represents the lifecycle stage of a segment in the storage engine.
 *
 * <p>Segments progress through these states:
 * <ol>
 *   <li>{@link #BOOTSTRAPPING} — Allocated, no data. Invisible to queries.</li>
 *   <li>{@link #INDEXING} — Entries being written. Cuckoo filter and LSH incrementally populated.</li>
 *   <li>{@link #WARMING} — Batch complete. PQ codebooks training.</li>
 *   <li>{@link #ACTIVE} — Fully indexed, fully optimized. Queryable.</li>
 *   <li>{@link #COMPACTING} — Tombstones being cleaned. Frozen snapshot serves queries.</li>
 *   <li>{@link #TOMBSTONED} — Invisible to new queries. Kept on disk until epoch-based reclamation.</li>
 *   <li>{@link #PURGED} — Disk freed. Removed from catalog.</li>
 * </ol>
 *
 * <h2>State Transition Rules</h2>
 * <p>Transitions are validated to prevent invalid state changes:</p>
 * <ul>
 *   <li>BOOTSTRAPPING → INDEXING (initial state transition)</li>
 *   <li>INDEXING → WARMING (indexing complete)</li>
 *   <li>WARMING → ACTIVE (optimization complete)</li>
 *   <li>ACTIVE → COMPACTING (compaction initiated)</li>
 *   <li>COMPACTING → ACTIVE (compaction complete)</li>
 *   <li>ACTIVE → TOMBSTONED (segment marked for deletion)</li>
 *   <li>COMPACTING → TOMBSTONED (segment deleted during compaction)</li>
 *   <li>TOMBSTONED → PURGED (segment fully removed)</li>
 * </ul>
 *
 * <h2>Query Visibility</h2>
 * <ul>
 *   <li>INDEXING, WARMING, ACTIVE, COMPACTING: Queryable</li>
 *   <li>BOOTSTRAPPING: Not queryable (no data)</li>
 *   <li>TOMBSTONED: Not queryable (data removed from filter hierarchy)</li>
 *   <li>PURGED: Not queryable (disk freed)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public enum LifecycleState {
    
    /**
     * Allocated, no data. Invisible to queries.
     * <p>Segments in this state are reserved but contain no usable data.</p>
     */
    BOOTSTRAPPING(0),
    
    /**
     * Entries being written. Cuckoo filter and LSH incrementally populated.
     * <p>Segments are queryable. Readers see a snapshot; concurrent writes via 
     * append log do not corrupt reads.</p>
     */
    INDEXING(1),
    
    /**
     * Batch complete. PQ codebooks training.
     * <p>Every entry is written and absorbed into the graph. Segments are queryable, and answer
     * with the same structures an {@link #ACTIVE} segment does.</p>
     */
    WARMING(2),
    
    /**
     * Fully indexed, fully optimized.
     * <p>Segments are fully queryable with all optimizations applied.</p>
     */
    ACTIVE(3),
    
    /**
     * Tombstones being cleaned.
     * <p>Frozen snapshot serves queries. No new writes during compaction.</p>
     */
    COMPACTING(4),
    
    /**
     * Invisible to new queries. Kept on disk until epoch-based reclamation.
     * <p>Cuckoo filter entries for this segment are deleted and removals 
     * propagated up the hierarchy. Segment remains visible to in-flight queries.</p>
     */
    TOMBSTONED(5),
    
    /**
     * Disk freed. Removed from catalog.
     * <p>Segment file has been deleted from disk. No further operations allowed.</p>
     */
    PURGED(6);

    private final int value;

    LifecycleState(int value) {
        this.value = value;
    }

    /**
     * Gets the integer value for this lifecycle state.
     * <p>Values are used for efficient serialization and comparison.</p>
     * @return The integer value
     */
    public int value() {
        return value;
    }

    /**
     * Creates a LifecycleState from its integer value.
     * <p>Returns null for invalid values.</p>
     * @param value The integer value
     * @return The LifecycleState, or null if invalid
     */
    public static LifecycleState fromValue(int value) {
        for (LifecycleState state : values()) {
            if (state.value == value) {
                return state;
            }
        }
        return null;
    }

    /**
     * Validates that a state transition is valid.
     * <p>Throws IllegalStateException if the transition is invalid.</p>
     * @param currentState The current lifecycle state
     * @param nextState The proposed next lifecycle state
     */
    public static void validateTransition(LifecycleState currentState, LifecycleState nextState) {
        if (currentState == nextState) {
            return; // No-op transition is valid
        }

        // Define valid transitions based on the lifecycle diagram:
        // BOOTSTRAPPING → INDEXING → WARMING → ACTIVE → COMPACTING → ACTIVE → TOMBSTONED → PURGED
        switch (currentState) {
            case BOOTSTRAPPING:
                if (nextState != INDEXING) {
                    throw new IllegalStateException(
                        "Invalid transition from BOOTSTRAPPING to " + nextState);
                }
                break;
            case INDEXING:
                // Can go to WARMING, ACTIVE (direct), or TOMBSTONED (direct)
                if (nextState != WARMING && nextState != ACTIVE && nextState != TOMBSTONED) {
                    throw new IllegalStateException(
                        "Invalid transition from INDEXING to " + nextState);
                }
                break;
            case WARMING:
                if (nextState != ACTIVE) {
                    throw new IllegalStateException(
                        "Invalid transition from WARMING to " + nextState);
                }
                break;
            case ACTIVE:
                // Can go to COMPACTING, TOMBSTONED, ACTIVE (re-optimization), or WARMING (re-indexing)
                if (nextState != COMPACTING && nextState != TOMBSTONED && nextState != ACTIVE && nextState != WARMING) {
                    throw new IllegalStateException(
                        "Invalid transition from ACTIVE to " + nextState);
                }
                break;
            case COMPACTING:
                // Can go to ACTIVE (compaction complete) or TOMBSTONED (segment deleted during compaction)
                if (nextState != ACTIVE && nextState != TOMBSTONED) {
                    throw new IllegalStateException(
                        "Invalid transition from COMPACTING to " + nextState);
                }
                break;
            case TOMBSTONED:
                if (nextState != PURGED) {
                    throw new IllegalStateException(
                        "Invalid transition from TOMBSTONED to " + nextState);
                }
                break;
            case PURGED:
                // Special case: PURGED can go back to BOOTSTRAPPING for re-allocation
                if (nextState != BOOTSTRAPPING) {
                    throw new IllegalStateException(
                        "Invalid transition from PURGED to " + nextState);
                }
                break;
        }
    }

    /**
     * Checks if this segment is queryable.
     * <p>Queryable segments are: INDEXING, WARMING, ACTIVE, COMPACTING</p>
     * @return true if the segment is queryable
     */
    public boolean isQueryable() {
        return this == INDEXING || this == WARMING || 
               this == ACTIVE || this == COMPACTING;
    }

    /**
     * Checks if this segment holds only part of the data it will eventually hold.
     *
     * <p>A query that asks to be spared partially-indexed segments is asking to be spared partial
     * answers. {@link #INDEXING} is the only queryable state where the segment's own contents are
     * still moving as the query reads them; {@link #WARMING} has every entry in and is training a
     * product quantizer no query path reads, and {@link #COMPACTING} serves a frozen snapshot.
     * Both of those answer a query exactly as {@link #ACTIVE} does.</p>
     *
     * @return true if a query would see only part of this segment
     */
    public boolean isPartiallyIndexed() {
        return this == INDEXING;
    }

    /**
     * Checks if this segment is visible to new queries.
     * <p>Visible segments are: INDEXING, WARMING, ACTIVE, COMPACTING, ACTIVE</p>
     * @return true if the segment is visible
     */
    public boolean isVisible() {
        return isQueryable();
    }

    /**
     * Checks if this segment can accept new writes.
     * <p>Writeable segments are: BOOTSTRAPPING, INDEXING, WARMING, ACTIVE, COMPACTING</p>
     * @return true if the segment can accept writes
     */
    public boolean isWriteable() {
        return this == BOOTSTRAPPING || this == INDEXING || 
               this == WARMING || this == ACTIVE || this == COMPACTING;
    }

    /**
     * Checks if this segment is being cleaned up.
     * @return true if the segment is being cleaned up
     */
    public boolean isBeingCleanedUp() {
        return this == TOMBSTONED || this == PURGED;
    }

    /**
     * Checks if this segment is in an initial state with no data.
     * @return true if the segment has no data
     */
    public boolean hasNoData() {
        return this == BOOTSTRAPPING;
    }

    /**
     * Advances the lifecycle state to the next state.
     *
     * <p>Automatically advances through the lifecycle sequence:
     * BOOTSTRAPPING → INDEXING → WARMING → ACTIVE → COMPACTING → ACTIVE → TOMBSTONED → PURGED</p>
     *
     * @return The new lifecycle state
     * @throws IllegalStateException if transition is invalid
     */
    public LifecycleState advance() {
        switch (this) {
            case BOOTSTRAPPING:
                validateTransition(this, LifecycleState.INDEXING);
                return LifecycleState.INDEXING;
            case INDEXING:
                validateTransition(this, LifecycleState.WARMING);
                return LifecycleState.WARMING;
            case WARMING:
                validateTransition(this, LifecycleState.ACTIVE);
                return LifecycleState.ACTIVE;
            case ACTIVE:
                validateTransition(this, LifecycleState.COMPACTING);
                return LifecycleState.COMPACTING;
            case COMPACTING:
                validateTransition(this, LifecycleState.ACTIVE);
                return LifecycleState.ACTIVE;
            case TOMBSTONED:
                validateTransition(this, LifecycleState.PURGED);
                return LifecycleState.PURGED;
            case PURGED:
                // PURGED advances to BOOTSTRAPPING (re-allocation)
                validateTransition(this, LifecycleState.BOOTSTRAPPING);
                return LifecycleState.BOOTSTRAPPING;
            default:
                throw new IllegalStateException(
                    "Unexpected lifecycle state: " + this);
        }
    }

    /**
     * Resets the lifecycle state to BOOTSTRAPPING.
     *
     * <p>This is a static method that validates the transition.</p>
     *
     * @param currentState The current lifecycle state
     * @throws IllegalStateException if transition is invalid
     */
    public static void reset(LifecycleState currentState) {
        validateTransition(currentState, LifecycleState.BOOTSTRAPPING);
        currentState = LifecycleState.BOOTSTRAPPING;
    }

    @Override
    public String toString() {
        switch (this) {
            case BOOTSTRAPPING: return "BOOTSTRAPPING";
            case INDEXING: return "INDEXING";
            case WARMING: return "WARMING";
            case ACTIVE: return "ACTIVE";
            case COMPACTING: return "COMPACTING";
            case TOMBSTONED: return "TOMBSTONED";
            case PURGED: return "PURGED";
            default: return "UNKNOWN(" + value + ")";
        }
    }
}
