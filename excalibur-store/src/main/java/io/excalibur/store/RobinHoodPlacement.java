package io.excalibur.store;

import java.util.List;

/**
 * Everything a completed Robin Hood insertion changed, in the order it changed it.
 *
 * <p>An insertion is the one segment mutation that can still be refused once it has started -
 * a probe chain can run out of length or overflow the distance byte - so the placement already
 * had to be undoable part-way through. It has to be undoable after it has completed as well,
 * because the write-ahead log record for the entry is appended once the placement has
 * succeeded and the device can refuse that record: a write the caller is then told failed must
 * not be left readable in the segment.</p>
 *
 * <p>Undoing works because a displacement moves only slot metadata. The incoming entry's
 * record is appended to the data region once and nothing else is copied, so restoring the
 * slots each victim held and rewinding the data offset puts the segment back exactly as it
 * was. The landing slot is never one of the victims: a slot a victim was taken from is
 * occupied from that moment on, while the chain terminates at a slot that was empty.</p>
 *
 * @param landingSlot  The slot the incoming entry came to rest in, which was empty before
 * @param recordOffset Data region offset of the record appended for the incoming entry
 * @param displaced    Victims in the order they were overwritten, oldest first; may be empty
 */
record RobinHoodPlacement(int landingSlot, long recordOffset, List<SlotSnapshot> displaced) {

    RobinHoodPlacement {
        displaced = displaced == null ? List.of() : List.copyOf(displaced);
    }
}
