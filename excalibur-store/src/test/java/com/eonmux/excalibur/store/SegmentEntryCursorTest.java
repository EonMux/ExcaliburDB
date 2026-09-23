package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.SegmentEntry;
import com.eonmux.excalibur.store.format.SegmentLayout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A walk over a segment that meets a damaged entry.
 *
 * <p>Copying damage forward is refused, so the walk reports the entry. Rebuilding derived data
 * from the rest must still be possible, so the report leaves the walk positioned after it.</p>
 */
@DisplayName("Segment entry walk over a damaged entry")
class SegmentEntryCursorTest {

    @TempDir
    Path tempDir;

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("reports the damaged entry, and a caller that carries on gets every other one")
    void resumesAfterADamagedEntry() throws IOException {
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("segment_0.dat"), 1 << 20, arena)) {
            for (int i = 0; i < 5; i++) {
                segment.put(bytes("key-" + i), bytes("value-" + i));
            }
            damageValueOf(segment, bytes("value-2"));

            List<String> read = new ArrayList<>();
            int reported = 0;
            try (Segment.EntryIterator walk = segment.liveEntries()) {
                while (true) {
                    try {
                        if (!walk.hasNext()) {
                            break;
                        }
                        SegmentEntry entry = walk.next();
                        read.add(new String(entry.key(), StandardCharsets.UTF_8));
                    } catch (CorruptSegmentEntryException e) {
                        reported++;
                    }
                }
            }

            assertThat(reported).isEqualTo(1);
            assertThat(read).containsExactlyInAnyOrder("key-0", "key-1", "key-3", "key-4");
        }
    }

    @Test
    @DisplayName("a length damaged beyond sense is reported as damage, not as a bad allocation")
    void aDamagedLengthIsReportedAsDamage() throws IOException {
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("segment_1.dat"), 1 << 20, arena)) {
            segment.put(bytes("only"), bytes("value"));
            long entryOffset = entryOffsetOf(segment, bytes("value"));
            segment.segment().set(Segment.INT_LAYOUT,
                entryOffset + SegmentLayout.ENTRY_KEY_LEN_OFFSET, -7);

            int reported = 0;
            try (Segment.EntryIterator walk = segment.liveEntries()) {
                try {
                    walk.hasNext();
                } catch (CorruptSegmentEntryException e) {
                    reported++;
                }
                assertThat(walk.hasNext()).as("nothing after the damaged entry").isFalse();
            }
            assertThat(reported).isEqualTo(1);
        }
    }

    /** Flips a bit inside the stored copy of a value, through the live mapping. */
    private static void damageValueOf(Segment segment, byte[] value) {
        long at = indexOf(segment, value);
        byte original = segment.segment().get(Segment.BYTE_LAYOUT, at + 1);
        segment.segment().set(Segment.BYTE_LAYOUT, at + 1, (byte) (original ^ 0x20));
    }

    /** The offset of the record whose value is the given bytes. */
    private static long entryOffsetOf(Segment segment, byte[] value) {
        long valueAt = indexOf(segment, value);
        for (int slot = 0; slot < segment.slotCount; slot++) {
            long flagsAt = (long) slot * Segment.SLOT_SIZE + Segment.SLOT_FLAGS_OFFSET;
            if ((segment.segment().get(Segment.BYTE_LAYOUT, flagsAt) & Segment.FLAG_OCCUPIED) == 0) {
                continue;
            }
            long entryOffset = segment.slotTableSize + segment.slots.readDataOffset(slot);
            int keyLen = segment.segment().get(Segment.INT_LAYOUT,
                entryOffset + SegmentLayout.ENTRY_KEY_LEN_OFFSET);
            if (entryOffset + segment.records.keyBytesOffset() + keyLen == valueAt) {
                return entryOffset;
            }
        }
        throw new IllegalStateException("no record holds the value");
    }

    private static long indexOf(Segment segment, byte[] needle) {
        long size = segment.segment().byteSize();
        outer:
        for (long i = segment.slotTableSize; i <= size - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (segment.segment().get(Segment.BYTE_LAYOUT, i + j) != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IllegalStateException("bytes not found in the segment");
    }
}
