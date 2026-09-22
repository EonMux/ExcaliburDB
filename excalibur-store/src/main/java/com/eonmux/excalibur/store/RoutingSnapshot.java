package com.eonmux.excalibur.store;

/**
 * The routing directory and the mask that indexes it, as one immutable value.
 *
 * <p>Routing a key takes two pieces of information - how wide the directory is, and which
 * segment sits in the slot the hash lands on - and they have to come from the same instant.
 * Reading them separately is not a benign race: an operation that masked with the pre-split
 * width and then read a slot the split had already re-pointed sends a key whose split bit is
 * 1 to the left child. That is a lookup answering "absent" for a key that exists, and a write
 * landing in the half that will never be asked for it.</p>
 *
 * <p>Publishing them together as one object makes that impossible to get wrong by
 * construction: {@link StorageEngine} holds a single volatile reference to one of these, every
 * routed operation reads it once into a local, and a directory change replaces the whole
 * value. A reader therefore always sees a width and a slot array that were true together.</p>
 *
 * <p>This is a value, not a view: the array is never mutated after publication. Whoever
 * changes the directory builds a fresh snapshot under the engine's segment lock and assigns
 * it, which is the write that makes the change visible.</p>
 *
 * @param width The number of slots; always a power of two
 * @param slots The segment serving each slot, indexed by slot
 */
record RoutingSnapshot(int width, Segment[] slots) {

    /**
     * The segment a hash routes to, or null when the slot is empty.
     *
     * @param keyHash The key's hash
     * @return The segment, or null
     */
    Segment segmentFor(long keyHash) {
        if (width == 0) {
            return null;
        }
        return slots[(int) (keyHash & (width - 1))];
    }

    /**
     * The slot a hash routes to.
     *
     * @param keyHash The key's hash
     * @return The slot index, or -1 when the directory is empty
     */
    int slotFor(long keyHash) {
        return width == 0 ? -1 : (int) (keyHash & (width - 1));
    }
}
