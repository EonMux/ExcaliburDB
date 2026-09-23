package com.eonmux.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins why every read path of a segment gives the same answer while it is being split, even
 * though only {@link Segment#get} and {@link Segment#getWithEpoch} consult the split snapshot.
 *
 * <p>The snapshot is a read-only view of the live mapping, not a copy of it, and nothing may
 * write to a segment between {@link Segment#beginSplit()} and the end of its split. So the
 * live mapping and the snapshot are the same bytes for the whole split, and a probe of either
 * finds the same record at the same address. If either half of that stopped holding - the
 * snapshot became a copy, or a write path learned to reach a splitting segment - the pre-hashed
 * and caller-locked readers would start disagreeing with {@code get}, and these tests are what
 * would say so.</p>
 *
 * <p>Deterministic: {@code beginSplit()} leaves the segment in SPLITTING until it is completed
 * or aborted, so every read below runs mid-split with no thread or timing involved.</p>
 */
@DisplayName("Read paths agree during a split")
class SplitReadPathAgreementTest {

    /** Live keys written before the split. */
    private static final int KEYS = 200;

    @TempDir
    Path tempDir;

    private Arena arena;
    private Segment segment;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        segment = new Segment(tempDir.resolve("split-reads.bin"), 256 * 1024, arena);
        for (int i = 0; i < KEYS; i++) {
            segment.put(key(i), value(i, 0));
        }
        // An overwritten key, a tombstoned key: the two states a read can get wrong.
        segment.put(key(7), value(7, 1));
        segment.delete(key(11));
    }

    @AfterEach
    void tearDown() {
        if (!segment.isClosed()) {
            segment.close();
        }
        arena.close();
    }

    @Test
    @DisplayName("the split snapshot is a read-only view of the live mapping, not a copy")
    void snapshotAliasesTheLiveMapping() {
        segment.beginSplit();

        MemorySegment snapshot = segment.splitSnapshot.segment;
        assertTrue(snapshot.isReadOnly());
        assertEquals(segment.segment.address(), snapshot.address(),
            "a copy could diverge from the live mapping; a view cannot");
        assertEquals(segment.segment.byteSize(), snapshot.byteSize());
        assertEquals(segment.modificationEpoch, segment.splitSnapshot.modificationEpoch);
    }

    @Test
    @DisplayName("every read path returns what get returns, for every key, mid-split")
    void everyReadPathAgreesWithGetMidSplit() {
        segment.beginSplit();
        assertTrue(segment.isSplitting());

        byte[][] keys = new byte[KEYS + 1][];
        long[] hashes = new long[KEYS + 1];
        for (int i = 0; i <= KEYS; i++) {
            keys[i] = key(i); // key(KEYS) was never written
            hashes[i] = segment.hashKey(keys[i]);
        }
        MemorySegment[] batch = segment.batchGetByHash(keys, hashes);

        for (int i = 0; i <= KEYS; i++) {
            MemorySegment expected = segment.get(keys[i]);
            ZeroCopySlice stamped = segment.getWithEpoch(keys[i]);
            MemorySegment byHash = segment.getByHash(keys[i], hashes[i]);
            MemorySegment byHashUnlocked;
            MemorySegment unlocked;
            segment.readLock().lock();
            try {
                byHashUnlocked = segment.getByHashWithoutLock(keys[i], hashes[i]);
                unlocked = segment.getWithoutLock(keys[i]);
            } finally {
                segment.readLock().unlock();
            }

            String label = "key " + i;
            assertSame(expected, stamped.data(), label + " getWithEpoch");
            assertSame(expected, byHash, label + " getByHash");
            assertSame(expected, byHashUnlocked, label + " getByHashWithoutLock");
            assertSame(expected, unlocked, label + " getWithoutLock");
            assertSame(expected, batch[i], label + " batchGetByHash");
        }

        assertArrayEquals(value(7, 1), bytes(segment.getByHash(key(7), segment.hashKey(key(7)))),
            "the overwrite made before the split");
        assertNull(segment.getByHash(key(11), segment.hashKey(key(11))),
            "the delete made before the split");
        assertNull(segment.getByHash(key(KEYS), segment.hashKey(key(KEYS))));
    }

    @Test
    @DisplayName("no write path can change a splitting segment, so the view stays the snapshot")
    void everyWritePathIsRefusedMidSplit() {
        segment.beginSplit();

        assertThrows(IllegalStateException.class, () -> segment.put(key(0), value(0, 9)));
        assertThrows(IllegalStateException.class, () -> segment.delete(key(1)));
        segment.writeLock().lock();
        try {
            assertThrows(IllegalStateException.class,
                () -> segment.putWithoutLock(key(0), value(0, 9)));
            assertThrows(IllegalStateException.class, () -> segment.deleteWithoutLock(key(1)));
        } finally {
            segment.writeLock().unlock();
        }

        assertArrayEquals(value(0, 0), bytes(segment.getByHash(key(0), segment.hashKey(key(0)))));
        assertNotNull(segment.getByHash(key(1), segment.hashKey(key(1))));
    }

    @Test
    @DisplayName("a retired parent refuses the caller-locked writes as well, sending them to the router")
    void callerLockedWritesAreRefusedByARetiredParent() throws IOException {
        segment.beginSplit();
        try (Segment left = new Segment(tempDir.resolve("left.bin"), 256 * 1024, arena);
             Segment right = new Segment(tempDir.resolve("right.bin"), 256 * 1024, arena)) {
            segment.completeSplit(left, right, 0);
            segment.writeLock().lock();
            try {
                assertThrows(SegmentRetiredException.class,
                    () -> segment.putWithoutLock(key(0), value(0, 9)));
                assertThrows(SegmentRetiredException.class,
                    () -> segment.deleteWithoutLock(key(1)));
            } finally {
                segment.writeLock().unlock();
            }
        }
    }

    @Test
    @DisplayName("an aborted split leaves every read path agreeing and the segment writable")
    void abortedSplitRestoresNormalService() {
        segment.beginSplit();
        segment.abortSplit();

        segment.put(key(3), value(3, 2));
        long hash = segment.hashKey(key(3));
        assertSame(segment.get(key(3)), segment.getByHash(key(3), hash), "after abort");
        assertArrayEquals(value(3, 2), bytes(segment.getByHash(key(3), hash)));
    }

    /**
     * Asserts two slices view the same bytes of the same mapping, or are both absent.
     *
     * @param expected What {@code get} returned
     * @param actual   What the path under test returned
     * @param label    Which path and key
     */
    private static void assertSame(MemorySegment expected, MemorySegment actual, String label) {
        if (expected == null) {
            assertNull(actual, label);
            return;
        }
        assertNotNull(actual, label);
        assertEquals(expected.address(), actual.address(), label + ": same record");
        assertEquals(expected.byteSize(), actual.byteSize(), label);
        assertArrayEquals(bytes(expected), bytes(actual), label);
    }

    private static byte[] key(int i) {
        return ("split-read-key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int i, int version) {
        return ("value-" + i + "-v" + version).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(MemorySegment slice) {
        return slice.toArray(ValueLayout.JAVA_BYTE);
    }
}
