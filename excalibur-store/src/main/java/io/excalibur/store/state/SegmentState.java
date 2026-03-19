package io.excalibur.store.state;

import java.lang.annotation.Documented;

/**
 * Enum representing the lifecycle states of a segment in ExcaliburDB.
 * 
 * State lifecycle:
 * BOOTSTRAPPING → INDEXING → WARMING → ACTIVE → COMPACTING → ACTIVE
 *                                                    ↓
 *                                                TOMBSTONED → PURGED
 * 
 * Thread-safety: All state transitions are atomic using VarHandle.
 * No external synchronization required for state access.
 */
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
        switch (code) {
            case 0: return BOOTSTRAPPING;
            case 1: return INDEXING;
            case 2: return WARMING;
            case 3: return ACTIVE;
            case 4: return COMPACTING;
            case 5: return TOMBSTONED;
            case 6: return PURGED;
            default: return null;
        }
    }
    
    /**
     * Get the human-readable name for this state.
     */
    public String toStringHuman() {
        return switch (this) {
            case BOOTSTRAPPING -> "BOOTSTRAPPING";
            case INDEXING -> "INDEXING";
            case WARMING -> "WARMING";
            case ACTIVE -> "ACTIVE";
            case COMPACTING -> "COMPACTING";
            case TOMBSTONED -> "TOMBSTONED";
            case PURGED -> "PURGED";
        };
    }
}
