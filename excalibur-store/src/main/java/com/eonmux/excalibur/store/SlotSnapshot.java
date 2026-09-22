package com.eonmux.excalibur.store;

/**
 * The contents of a slot before a Robin Hood displacement overwrote it.
 *
 * <p>A displacement copies no entry data - the victim keeps the data region record it already
 * owns - so these five fields are the whole of what changed and the whole of what it takes to
 * put the slot back.</p>
 *
 * @param slotIndex        The slot that was overwritten
 * @param keyHash          The displaced entry's key hash
 * @param distance         The displaced entry's distance from its ideal slot
 * @param flags            The displaced entry's slot flags, including the tombstone bit
 * @param dataRegionOffset Offset of the displaced entry's record in the data region
 */
record SlotSnapshot(int slotIndex, long keyHash, int distance,
                    byte flags, long dataRegionOffset) {
}
