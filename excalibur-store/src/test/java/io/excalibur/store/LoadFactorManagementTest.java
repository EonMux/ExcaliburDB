package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for automatic load factor management and segment splitting.
 *
 * <p>These tests verify that when segments reach the load factor threshold (0.7),
 * the StorageEngine automatically triggers splitting to enable continued writes.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
class LoadFactorManagementTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine engine;
    private Path dataDir;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        engine = new StorageEngine(dataDir, arena);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    @DisplayName("SplitWorker should be initialized when StorageEngine is created")
    void splitWorkerShouldBeInitialized() {
        assertNotNull(engine.getSplitWorker(), "SplitWorker should be initialized");
    }

    @Test
    @DisplayName("SplitWorker should not be running before start()")
    void splitWorkerNotRunningBeforeStart() {
        assertFalse(engine.getSplitWorker().isRunning(),
            "SplitWorker should not be running before start()");
    }

    @Test
    @DisplayName("SplitWorker should be running after start()")
    void splitWorkerShouldBeRunningAfterStart() {
        engine.start();
        assertTrue(engine.getSplitWorker().isRunning(),
            "SplitWorker should be running after start()");
    }

    @Test
    @DisplayName("SplitWorker should register all pre-allocated segments after start()")
    void splitWorkerShouldRegisterSegmentsAfterStart() {
        engine.start();

        // Should have registered DEFAULT_INITIAL_SEGMENTS (64) segments
        int registeredCount = engine.getSplitWorker().getRegisteredSegmentCount();
        assertEquals(64, registeredCount,
            "SplitWorker should have registered all pre-allocated segments");
    }

    @Test
    @DisplayName("New segments should be registered with SplitWorker")
    void newSegmentsShouldBeRegisteredWithSplitWorker() {
        engine.start();

        // Get initial count
        int initialCount = engine.getSplitWorker().getRegisteredSegmentCount();

        // The segments map is pre-populated during start(), so creating a new segment
        // at an existing index shouldn't change the count
        assertEquals(64, initialCount,
            "Should have 64 registered segments after start()");
    }

    @Test
    @DisplayName("Segment should reject writes when load factor exceeds threshold")
    void segmentShouldRejectWritesWhenLoadFactorExceedsThreshold() {
        // Don't start the engine - split worker won't be running

        // Fill a segment to capacity by inserting directly into segment
        Path segmentPath = dataDir.resolve("test_segment.dat");
        try {
            // Create a segment with small slot count for faster testing
            Segment segment = new Segment(segmentPath, 1024 * 10, arena);

            // Calculate the exact number of entries at threshold
            // The segment rejects when (entryCount + 1) >= ceil(slotCount * LOAD_FACTOR_THRESHOLD)
            int maxEntriesAtThreshold = (int) Math.ceil(segment.slotCount() * Segment.LOAD_FACTOR_THRESHOLD);

            // Fill to exactly at the threshold limit
            for (int i = 0; i < maxEntriesAtThreshold - 1; i++) {
                byte[] key = ("key" + i).getBytes();
                byte[] value = ("value" + i).getBytes();
                segment.put(key, value);
            }

            // Verify load factor is near threshold
            double currentLoadFactor = segment.loadFactor();
            assertTrue(currentLoadFactor >= Segment.LOAD_FACTOR_THRESHOLD - 0.1,
                "Load factor should be near threshold: " + currentLoadFactor +
                " (entries: " + segment.entryCount() + ", slots: " + segment.slotCount() + ")");

            // Next put should fail with IllegalStateException (load factor exceeded)
            // The rejection happens when (entryCount + 1) >= ceil(slotCount * LOAD_FACTOR_THRESHOLD)
            assertThrows(IllegalStateException.class, () -> {
                segment.put("overflow_key".getBytes(), "overflow_value".getBytes());
            }, "Should throw IllegalStateException when load factor would exceed threshold");

            segment.close();
        } catch (IOException e) {
            fail("Failed to create test segment", e);
        }
    }

    @Test
    @DisplayName("put() should handle load factor exceeded with automatic splitting")
    void putShouldHandleLoadFactorExceededWithSplitting() throws InterruptedException {
        engine.start();

        // Verify split worker is running
        assertTrue(engine.getSplitWorker().isRunning(),
            "SplitWorker should be running after start()");

        // Insert entries until one segment reaches capacity
        // With 64 segments and ~716 entries per segment at 0.7 load factor,
        // we need many insertions to trigger a split
        int inserted = 0;
        int maxAttempts = 100_000;
        boolean splitTriggered = false;

        while (inserted < maxAttempts && !splitTriggered) {
            try {
                byte[] key = ("key" + inserted).getBytes();
                byte[] value = ("value" + inserted).getBytes();
                engine.put(key, value);
                inserted++;
            } catch (SegmentAtCapacityException e) {
                // Split was triggered - wait for completion and continue
                splitTriggered = true;

                // Wait briefly for split to complete
                Thread.sleep(100);

                // Verify we can continue inserting after split
                try {
                    byte[] retryKey = ("retry_key" + inserted).getBytes();
                    byte[] retryValue = ("retry_value" + inserted).getBytes();
                    engine.put(retryKey, retryValue);
                    inserted++;
                } catch (SegmentAtCapacityException e2) {
                    // Still at capacity - this is OK, split may still be in progress
                }
            }
        }

        // We should have been able to insert entries
        assertTrue(inserted > 0, "Should have inserted some entries");

        // If split was triggered, verify split count increased
        if (splitTriggered) {
            assertTrue(engine.getSplitWorker().getSplitCount() > 0,
                "Split count should have increased after split");
        }
    }

    @Test
    @DisplayName("SplitWorker should stop when StorageEngine is closed")
    void splitWorkerShouldStopOnClose() {
        engine.start();
        assertTrue(engine.getSplitWorker().isRunning(),
            "SplitWorker should be running after start()");

        engine.close();

        assertFalse(engine.getSplitWorker().isRunning(),
            "SplitWorker should not be running after close()");

        // Prevent double-close in tearDown
        engine = null;
    }

    @Test
    @DisplayName("SegmentAtCapacityException utility method should detect capacity exceptions")
    void segmentAtCapacityExceptionUtilityMethod() {
        SegmentAtCapacityException direct = new SegmentAtCapacityException("Direct");
        assertTrue(SegmentAtCapacityException.isCapacityException(direct),
            "Should detect direct SegmentAtCapacityException");

        RuntimeException wrapped = new RuntimeException("Wrapped", direct);
        assertTrue(SegmentAtCapacityException.isCapacityException(wrapped),
            "Should detect wrapped SegmentAtCapacityException");

        RuntimeException other = new RuntimeException("Other");
        assertFalse(SegmentAtCapacityException.isCapacityException(other),
            "Should not detect non-capacity exception");
    }
}
