package io.excalibur.store;

/**
 * The correspondence between a segment's {@link LifecycleState} and the older entry-state
 * integer its on-disk record stores.
 *
 * <p>Every segment's position is held twice. {@link LifecycleState} is what the engine
 * reasons about and what the transition rules govern; the {@code ENTRY_STATE_*} constants
 * on {@link SegmentCatalog} are what the catalog record format writes, and what predates
 * the enum. They name the same seven positions with different values, so something has to
 * translate, in both directions: the catalog derives a lifecycle state when an entry state
 * is set, and the storage engine derives an entry state when a lifecycle transition is
 * applied.</p>
 *
 * <p>Those two halves used to live in different files - one private method in
 * {@code SegmentCatalog}, its inverse private in {@code StorageEngine} - which is how a
 * mapping comes to disagree with itself. They are here together so that adding a state
 * means editing one file, and so that the one place the two are <em>not</em> inverses can
 * be stated rather than discovered.</p>
 *
 * <p>Neither direction is total. An entry-state integer that names no state maps to null,
 * and a lifecycle state with no entry state of its own maps to -1; both callers treat that
 * as "leave the other representation alone" rather than as an error, because an unknown
 * value read back from an older record must not stop a node starting.</p>
 */
final class EntryStateMapping {

    private EntryStateMapping() {
    }

    /**
     * Converts an entry state constant to the corresponding lifecycle state.
     *
     * @param entryState The entry state
     * @return The corresponding lifecycle state, or null if no mapping exists
     */
    static LifecycleState toLifecycleState(int entryState) {
        switch (entryState) {
            case SegmentCatalog.ENTRY_STATE_NONE: return LifecycleState.BOOTSTRAPPING;
            case SegmentCatalog.ENTRY_STATE_INDEXING: return LifecycleState.INDEXING;
            case SegmentCatalog.ENTRY_STATE_WARMING: return LifecycleState.WARMING;
            case SegmentCatalog.ENTRY_STATE_ACTIVE: return LifecycleState.ACTIVE;
            case SegmentCatalog.ENTRY_STATE_COMPACTING: return LifecycleState.COMPACTING;
            case SegmentCatalog.ENTRY_STATE_DELETED: return LifecycleState.TOMBSTONED;
            default: return null;
        }
    }

    /**
     * Converts a lifecycle state to the corresponding entry state constant.
     *
     * <p>Not quite the inverse of {@link #toLifecycleState(int)}: PURGED has no entry state
     * of its own and maps onto NONE, so a round trip through both loses the distinction
     * between a segment that has never held anything and one whose disk has been freed.</p>
     *
     * @param state The lifecycle state
     * @return The entry state constant, or -1 if no mapping exists
     */
    static int toEntryState(LifecycleState state) {
        switch (state) {
            case BOOTSTRAPPING: return SegmentCatalog.ENTRY_STATE_NONE;
            case INDEXING: return SegmentCatalog.ENTRY_STATE_INDEXING;
            case WARMING: return SegmentCatalog.ENTRY_STATE_WARMING;
            case ACTIVE: return SegmentCatalog.ENTRY_STATE_ACTIVE;
            case COMPACTING: return SegmentCatalog.ENTRY_STATE_COMPACTING;
            case TOMBSTONED: return SegmentCatalog.ENTRY_STATE_DELETED;
            case PURGED: return SegmentCatalog.ENTRY_STATE_NONE;
            default: return -1;
        }
    }
}
