package com.eonmux.excalibur.store.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the slot and entry checksums, and the slot's data offset, to a byte-at-a-time
 * reading of the documented layout.
 *
 * <p>Both checksums are stored on disk, so the readers that compute them on the lookup path
 * may read the mapping however is fastest, but must arrive at exactly the value a plain
 * byte-by-byte CRC32 over the covered bytes arrives at: a file written by one build has to
 * verify under every other. The slot contents are random rather than well-formed so that
 * every bit of every covered and uncovered byte takes both values.</p>
 */
@DisplayName("Checksum reference equivalence")
class ChecksumReferenceTest {

    /** Slots filled with random bytes per property. */
    private static final int SLOTS = 4096;

    @Nested
    @DisplayName("SlotChecksum")
    class Slots {

        @Test
        @DisplayName("compute equals a byte-wise CRC32 of the key hash, flags and data offset")
        void computeMatchesByteWiseReference() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment table = randomSlotTable(arena, 7L);
                for (int slot = 0; slot < SLOTS; slot++) {
                    long slotOffset = SlotChecksum.offsetOf(slot);
                    assertEquals(referenceSlotChecksum(table, slotOffset),
                        SlotChecksum.compute(table, slotOffset), "slot " + slot);
                }
            }
        }

        @Test
        @DisplayName("the distance byte and the stored checksum stay outside the slot checksum")
        void uncoveredBytesDoNotChangeTheChecksum() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment table = randomSlotTable(arena, 11L);
                for (int slot = 0; slot < SLOTS; slot++) {
                    long slotOffset = SlotChecksum.offsetOf(slot);
                    int before = SlotChecksum.compute(table, slotOffset);
                    for (int uncovered : new int[] {SegmentLayout.SLOT_DISTANCE_OFFSET,
                            SegmentLayout.SLOT_CHECKSUM_OFFSET,
                            SegmentLayout.SLOT_RESERVED_OFFSET}) {
                        long at = slotOffset + uncovered;
                        table.set(SegmentLayout.BYTE_LAYOUT, at,
                            (byte) ~table.get(SegmentLayout.BYTE_LAYOUT, at));
                    }
                    assertEquals(before, SlotChecksum.compute(table, slotOffset), "slot " + slot);
                }
            }
        }

        @Test
        @DisplayName("dataRegionOffset decodes the six little-endian bytes at offset 10")
        void dataRegionOffsetMatchesByteWiseReference() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment table = randomSlotTable(arena, 13L);
                for (int slot = 0; slot < SLOTS; slot++) {
                    long slotOffset = SlotChecksum.offsetOf(slot);
                    long expected = 0;
                    for (int i = 0; i < SegmentLayout.SLOT_DATA_OFFSET_LENGTH; i++) {
                        expected |= (table.get(SegmentLayout.BYTE_LAYOUT,
                            slotOffset + SegmentLayout.SLOT_DATA_OFFSET_OFFSET + i) & 0xFFL)
                            << (8 * i);
                    }
                    assertEquals(expected, SlotChecksum.dataRegionOffset(table, slotOffset),
                        "slot " + slot);
                }
            }
        }

        @Test
        @DisplayName("the last slot of a mapping is read without running past its end")
        void lastSlotOfAnExactlySizedMapping() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment table = randomSlotTable(arena, 17L);
                long last = SlotChecksum.offsetOf(SLOTS - 1);
                MemorySegment oneSlot = table.asSlice(last, SegmentLayout.SLOT_SIZE);
                assertEquals(referenceSlotChecksum(table, last), SlotChecksum.compute(oneSlot, 0));
                assertEquals(SlotChecksum.dataRegionOffset(table, last),
                    SlotChecksum.dataRegionOffset(oneSlot, 0));
            }
        }
    }

    @Nested
    @DisplayName("EntryChecksum")
    class Entries {

        @ParameterizedTest(name = "value of {0} bytes")
        @ValueSource(ints = {0, 1, 63, 64, 8170, 8171, 8192, 20000, 65536})
        @DisplayName("compute equals a byte-wise CRC32 of the header prefix, key and value")
        void computeMatchesByteWiseReference(int valueLen) {
            SegmentFormat format = SegmentFormat.CURRENT;
            int keyLen = 16;
            long entryOffset = 3;
            long span = format.keyBytesOffset() + (long) keyLen + valueLen
                + SegmentLayout.ENTRY_TRAILER_SIZE;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment mapped = arena.allocate(entryOffset + span);
                fillRandom(mapped, 19L + valueLen);
                mapped.set(SegmentLayout.INT_LAYOUT,
                    entryOffset + SegmentLayout.ENTRY_KEY_LEN_OFFSET, keyLen);
                mapped.set(SegmentLayout.INT_LAYOUT,
                    entryOffset + SegmentLayout.ENTRY_VALUE_LEN_OFFSET, valueLen);

                CRC32 reference = new CRC32();
                for (int i = 0; i < SegmentLayout.ENTRY_CHECKSUMMED_HEADER_PREFIX; i++) {
                    reference.update(mapped.get(SegmentLayout.BYTE_LAYOUT, entryOffset + i));
                }
                long payloadStart = entryOffset + format.keyBytesOffset();
                for (long i = 0; i < (long) keyLen + valueLen; i++) {
                    reference.update(mapped.get(SegmentLayout.BYTE_LAYOUT, payloadStart + i));
                }

                assertEquals((int) reference.getValue(),
                    EntryChecksum.compute(mapped, entryOffset, format));
            }
        }
    }

    /**
     * Computes a slot checksum one byte at a time from the layout constants.
     *
     * @param table      The slot table
     * @param slotOffset Absolute offset of the slot
     * @return The CRC32 of the slot's covered bytes
     */
    private static int referenceSlotChecksum(MemorySegment table, long slotOffset) {
        CRC32 crc = new CRC32();
        for (int i = 0; i < SegmentLayout.SLOT_CHECKSUMMED_HEAD_LENGTH; i++) {
            crc.update(table.get(SegmentLayout.BYTE_LAYOUT,
                slotOffset + SegmentLayout.SLOT_CHECKSUMMED_HEAD_OFFSET + i));
        }
        for (int i = 0; i < SegmentLayout.SLOT_CHECKSUMMED_TAIL_LENGTH; i++) {
            crc.update(table.get(SegmentLayout.BYTE_LAYOUT,
                slotOffset + SegmentLayout.SLOT_CHECKSUMMED_TAIL_OFFSET + i));
        }
        return (int) crc.getValue();
    }

    /**
     * Allocates a slot table of {@link #SLOTS} slots filled with random bytes.
     *
     * @param arena The arena to allocate in
     * @param seed  The random seed
     * @return The table, sized exactly to its slots
     */
    private static MemorySegment randomSlotTable(Arena arena, long seed) {
        MemorySegment table = arena.allocate((long) SLOTS * SegmentLayout.SLOT_SIZE);
        fillRandom(table, seed);
        return table;
    }

    /**
     * Fills a segment with seeded random bytes.
     *
     * @param segment The segment to fill
     * @param seed    The random seed
     */
    private static void fillRandom(MemorySegment segment, long seed) {
        byte[] bytes = new byte[(int) segment.byteSize()];
        new Random(seed).nextBytes(bytes);
        MemorySegment.copy(bytes, 0, segment, SegmentLayout.BYTE_LAYOUT, 0, bytes.length);
    }
}
