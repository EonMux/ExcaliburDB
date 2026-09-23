package com.eonmux.excalibur.filter.sketch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CountMinSketch.
 */
class CountMinSketchTest {

    private CountMinSketch sketch;
    private static final byte[] ITEM_A = "item-a".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ITEM_B = "item-b".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ITEM_C = "item-c".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        sketch = new CountMinSketch();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Default constructor creates valid sketch")
        void defaultConstructor() {
            CountMinSketch cms = new CountMinSketch();
            assertEquals(CountMinSketch.DEFAULT_WIDTH, cms.width());
            assertEquals(CountMinSketch.DEFAULT_DEPTH, cms.depth());
            assertEquals(0, cms.totalCount());
        }

        @Test
        @DisplayName("Custom parameters create valid sketch")
        void customParameters() {
            CountMinSketch cms = new CountMinSketch(1000, 5);
            assertEquals(1000, cms.width());
            assertEquals(5, cms.depth());
        }

        @Test
        @DisplayName("Invalid width throws exception")
        void invalidWidth() {
            assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(0, 10));
            assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(-1, 10));
        }

        @Test
        @DisplayName("Invalid depth throws exception")
        void invalidDepth() {
            assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(100, 0));
            assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(100, -1));
        }

        @Test
        @DisplayName("Create for error rate produces valid parameters")
        void createForErrorRate() {
            CountMinSketch cms = CountMinSketch.createForErrorRate(0.01, 0.01);
            assertTrue(cms.width() >= 200); // 2/0.01 = 200
            assertTrue(cms.depth() >= 7);   // ln(1/0.01)/ln(2) ≈ 6.64
        }

        @Test
        @DisplayName("Invalid error rate throws exception")
        void invalidErrorRate() {
            assertThrows(IllegalArgumentException.class,
                () -> CountMinSketch.createForErrorRate(0, 0.01));
            assertThrows(IllegalArgumentException.class,
                () -> CountMinSketch.createForErrorRate(1, 0.01));
            assertThrows(IllegalArgumentException.class,
                () -> CountMinSketch.createForErrorRate(-0.1, 0.01));
        }
    }

    @Nested
    @DisplayName("Insert and Estimate")
    class InsertAndEstimate {

        @Test
        @DisplayName("Single insert returns count of 1")
        void singleInsert() {
            sketch.insert(ITEM_A);
            assertEquals(1, sketch.estimate(ITEM_A));
        }

        @Test
        @DisplayName("Multiple inserts accumulate count")
        void multipleInserts() {
            sketch.insert(ITEM_A);
            sketch.insert(ITEM_A);
            sketch.insert(ITEM_A);
            assertEquals(3, sketch.estimate(ITEM_A));
        }

        @Test
        @DisplayName("Insert with count adds specified count")
        void insertWithCount() {
            sketch.insert(ITEM_A, 5);
            assertEquals(5, sketch.estimate(ITEM_A));
        }

        @Test
        @DisplayName("Insert with zero count is no-op")
        void insertZeroCount() {
            sketch.insert(ITEM_A, 0);
            assertEquals(0, sketch.estimate(ITEM_A));
            assertEquals(0, sketch.totalCount());
        }

        @Test
        @DisplayName("Negative count throws exception")
        void negativeCount() {
            assertThrows(IllegalArgumentException.class, () -> sketch.insert(ITEM_A, -1));
        }

        @Test
        @DisplayName("Null item throws exception")
        void nullItem() {
            assertThrows(NullPointerException.class, () -> sketch.insert(null));
            assertThrows(NullPointerException.class, () -> sketch.estimate(null));
        }

        @Test
        @DisplayName("Different items have independent counts")
        void independentCounts() {
            sketch.insert(ITEM_A, 10);
            sketch.insert(ITEM_B, 20);
            sketch.insert(ITEM_C, 30);

            // Estimates should be at least the actual count (never under-estimate)
            assertTrue(sketch.estimate(ITEM_A) >= 10);
            assertTrue(sketch.estimate(ITEM_B) >= 20);
            assertTrue(sketch.estimate(ITEM_C) >= 30);
        }

        @Test
        @DisplayName("Total count tracks all inserts")
        void totalCount() {
            sketch.insert(ITEM_A, 10);
            sketch.insert(ITEM_B, 20);
            sketch.insert(ITEM_C, 30);
            assertEquals(60, sketch.totalCount());
        }

        @Test
        @DisplayName("Estimate never underestimates")
        void neverUnderestimates() {
            sketch.insert(ITEM_A, 100);
            // Due to hash collisions, estimate may be higher but never lower
            assertTrue(sketch.estimate(ITEM_A) >= 100);
        }

        @Test
        @DisplayName("Non-existent item returns 0 or small count")
        void nonExistentItem() {
            sketch.insert(ITEM_A, 10);
            byte[] unknownItem = "unknown".getBytes(StandardCharsets.UTF_8);
            // Unknown items may have small counts due to hash collisions
            // but should be significantly less than inserted items
            assertTrue(sketch.estimate(unknownItem) < 10);
        }
    }

    @Nested
    @DisplayName("Frequency Estimation")
    class FrequencyEstimation {

        @Test
        @DisplayName("Frequency is ratio of count to total")
        void frequencyRatio() {
            sketch.insert(ITEM_A, 30);
            sketch.insert(ITEM_B, 70);

            // Total = 100. A Count-Min Sketch never underestimates, so a lower bound of 0.3
            // is satisfied by construction and an upper bound of 1.0 excludes nothing - an
            // estimator that always returned 1.0 would pass. With only two distinct items in
            // a correctly sized sketch there is no collision slack, so pin both values.
            assertEquals(0.30, sketch.estimateFrequency(ITEM_A), 0.01);
            assertEquals(0.70, sketch.estimateFrequency(ITEM_B), 0.01);
        }

        @Test
        @DisplayName("Frequency is 0 when sketch is empty")
        void emptySketchFrequency() {
            assertEquals(0.0, sketch.estimateFrequency(ITEM_A));
        }
    }

    @Nested
    @DisplayName("Clear Operation")
    class ClearOperation {

        @Test
        @DisplayName("Clear resets all counters")
        void clearResetsCounters() {
            sketch.insert(ITEM_A, 100);
            sketch.insert(ITEM_B, 200);
            sketch.clear();
            assertEquals(0, sketch.estimate(ITEM_A));
            assertEquals(0, sketch.estimate(ITEM_B));
            assertEquals(0, sketch.totalCount());
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("Serialize and deserialize preserves state")
        void serializeDeserialize() {
            sketch.insert(ITEM_A, 10);
            sketch.insert(ITEM_B, 20);
            long originalTotal = sketch.totalCount();

            MemorySegment segment = sketch.toMemorySegment(Arena.ofShared());
            CountMinSketch restored = CountMinSketch.fromMemorySegment(segment);

            assertEquals(sketch.width(), restored.width());
            assertEquals(sketch.depth(), restored.depth());
            assertEquals(originalTotal, restored.totalCount());
            assertEquals(sketch.estimate(ITEM_A), restored.estimate(ITEM_A));
            assertEquals(sketch.estimate(ITEM_B), restored.estimate(ITEM_B));
        }

        @Test
        @DisplayName("Invalid magic number throws exception")
        void invalidMagic() {
            MemorySegment segment = Arena.ofShared().allocate(HEADER_SIZE);
            segment.fill((byte) 0);
            assertThrows(IllegalArgumentException.class,
                () -> CountMinSketch.fromMemorySegment(segment));
        }

        @Test
        @DisplayName("Segment too small throws exception")
        void segmentTooSmall() {
            MemorySegment segment = Arena.ofShared().allocate(16);
            assertThrows(IllegalArgumentException.class,
                () -> CountMinSketch.fromMemorySegment(segment));
        }

        @Test
        @DisplayName("Null segment throws exception")
        void nullSegment() {
            assertThrows(IllegalArgumentException.class,
                () -> CountMinSketch.fromMemorySegment(null));
        }

        @Test
        @DisplayName("The byte form round-trips every counter, the seed and the total")
        void bytesRoundTrip() {
            CountMinSketch seeded = new CountMinSketch(64, 3, 42L);
            seeded.insert(ITEM_A, 7);
            seeded.insert(ITEM_B, 3);

            byte[] bytes = seeded.toBytes();
            CountMinSketch restored = CountMinSketch.fromBytes(bytes);

            assertEquals(HEADER_SIZE + 64 * 3 * 8, bytes.length);
            assertEquals(seeded.width(), restored.width());
            assertEquals(seeded.depth(), restored.depth());
            assertEquals(seeded.totalCount(), restored.totalCount());
            assertEquals(seeded.estimate(ITEM_A), restored.estimate(ITEM_A));
            assertEquals(seeded.estimate(ITEM_B), restored.estimate(ITEM_B));
            assertEquals(seeded.estimate(ITEM_C), restored.estimate(ITEM_C));

            // The seed travels with the counters, so the restored sketch keeps counting into
            // the same columns rather than scattering new counts over unrelated ones.
            restored.insert(ITEM_A, 1);
            seeded.insert(ITEM_A, 1);
            assertEquals(seeded.estimate(ITEM_A), restored.estimate(ITEM_A));
        }

        @Test
        @DisplayName("The byte form is the same layout as the memory-segment form")
        void bytesMatchMemorySegmentLayout() {
            sketch.insert(ITEM_A, 5);
            MemorySegment segment = sketch.toMemorySegment(Arena.ofAuto());

            CountMinSketch fromBytes = CountMinSketch.fromBytes(
                segment.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));

            assertArrayEquals(segment.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                sketch.toBytes());
            assertEquals(sketch.estimate(ITEM_A), fromBytes.estimate(ITEM_A));
        }

        @Test
        @DisplayName("Bytes that are not exactly one sketch are refused")
        void bytesOfTheWrongLengthAreRefused() {
            byte[] bytes = new CountMinSketch(16, 2, 1L).toBytes();

            assertThrows(IllegalArgumentException.class,
                () -> CountMinSketch.fromBytes(java.util.Arrays.copyOf(bytes, bytes.length - 1)));
            assertThrows(IllegalArgumentException.class,
                () -> CountMinSketch.fromBytes(java.util.Arrays.copyOf(bytes, bytes.length + 8)));
            assertThrows(IllegalArgumentException.class, () -> CountMinSketch.fromBytes(null));
        }

        @Test
        @DisplayName("A header declaring a shape that cannot exist is refused")
        void impossibleShapeIsRefused() {
            byte[] bytes = new CountMinSketch(16, 2, 1L).toBytes();
            java.nio.ByteBuffer.wrap(bytes).putInt(8, -16);

            assertThrows(IllegalArgumentException.class, () -> CountMinSketch.fromBytes(bytes));
        }
    }

    private static final int HEADER_SIZE = 32;

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("Concurrent inserts are safe")
        void concurrentInserts() throws InterruptedException {
            int numThreads = 10;
            int insertsPerThread = 1000;
            Thread[] threads = new Thread[numThreads];

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                threads[i] = Thread.ofVirtual().start(() -> {
                    for (int j = 0; j < insertsPerThread; j++) {
                        byte[] item = ("item-" + threadId + "-" + j).getBytes(StandardCharsets.UTF_8);
                        sketch.insert(item);
                    }
                });
            }

            for (Thread thread : threads) {
                thread.join();
            }

            assertEquals(numThreads * insertsPerThread, sketch.totalCount());
        }

        @Test
        @DisplayName("Concurrent reads and writes are safe")
        void concurrentReadWrite() throws InterruptedException {
            int numWriters = 5;
            int numReaders = 5;
            int operationsPerThread = 500;
            Thread[] threads = new Thread[numWriters + numReaders];
            boolean[] errors = new boolean[numWriters + numReaders];

            // Writers
            for (int i = 0; i < numWriters; i++) {
                final int id = i;
                threads[i] = Thread.ofVirtual().start(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            sketch.insert(ITEM_A);
                        }
                    } catch (Exception e) {
                        errors[id] = true;
                    }
                });
            }

            // Readers
            for (int i = 0; i < numReaders; i++) {
                final int id = numWriters + i;
                threads[id] = Thread.ofVirtual().start(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            sketch.estimate(ITEM_A);
                        }
                    } catch (Exception e) {
                        errors[id] = true;
                    }
                });
            }

            for (Thread thread : threads) {
                thread.join();
            }

            for (boolean error : errors) {
                assertFalse(error, "Thread encountered exception");
            }

            assertEquals(numWriters * operationsPerThread, sketch.totalCount());
        }
    }

    @Nested
    @DisplayName("Memory Usage")
    class MemoryUsage {

        @Test
        @DisplayName("Estimated memory usage is reasonable")
        void memoryUsage() {
            long usage = sketch.estimatedMemoryUsage();
            long expected = HEADER_SIZE + (long) sketch.width() * sketch.depth() * 8;
            assertEquals(expected, usage);
        }
    }

    @Nested
    @DisplayName("Accuracy")
    class Accuracy {

        @Test
        @DisplayName("Estimate accuracy for single item")
        void singleItemAccuracy() {
            // Insert same item many times
            for (int i = 0; i < 10000; i++) {
                sketch.insert(ITEM_A);
            }
            // Estimate should be exact or close (no other items to cause collisions)
            assertEquals(10000, sketch.estimate(ITEM_A));
        }

        @Test
        @DisplayName("Heavy hitters are identifiable")
        void heavyHitters() {
            Random random = new Random(42);

            // Insert many items with zipf-like distribution
            for (int i = 0; i < 10000; i++) {
                // Heavy item appears frequently
                sketch.insert(ITEM_A);
                // Other items appear less often
                if (random.nextDouble() < 0.1) {
                    byte[] item = ("rare-" + random.nextInt(100)).getBytes(StandardCharsets.UTF_8);
                    sketch.insert(item);
                }
            }

            // Heavy hitter should have significantly higher count
            long heavyCount = sketch.estimate(ITEM_A);
            assertTrue(heavyCount >= 10000);
        }
    }

    @Nested
    @DisplayName("Hashing once per item")
    class HashedInsertion {

        @Test
        @DisplayName("an item handed over as its hash is counted as the item itself")
        void hashedAndByteInsertsAgree() {
            CountMinSketch bytes = new CountMinSketch(1024, 4, 7L);
            CountMinSketch hashed = new CountMinSketch(1024, 4, 7L);
            for (int i = 0; i < 500; i++) {
                byte[] item = ("term-" + (i % 37)).getBytes(StandardCharsets.UTF_8);
                bytes.insert(item);
                hashed.insertHashed(CountMinSketch.itemHash(item), 1);
            }

            assertArrayEquals(bytes.toBytes(), hashed.toBytes());
            assertEquals(bytes.estimate(ITEM_A), hashed.estimateHashed(CountMinSketch.itemHash(ITEM_A)));
        }

        @Test
        @DisplayName("the item hash is the same for every sketch; the seed still moves the columns")
        void itemHashIsSeedIndependent() {
            CountMinSketch first = new CountMinSketch(1024, 4, 1L);
            CountMinSketch second = new CountMinSketch(1024, 4, 2L);
            long hash = CountMinSketch.itemHash(ITEM_A);
            first.insertHashed(hash, 5);
            second.insertHashed(hash, 5);

            assertEquals(5, first.estimate(ITEM_A));
            assertEquals(5, second.estimate(ITEM_A));
            assertFalse(java.util.Arrays.equals(first.toBytes(), second.toBytes()),
                "two seeds placing an item in the same columns of every row");
            assertEquals(1L, first.seed());
        }

        @Test
        @DisplayName("rows spread an item over different columns")
        void rowsAreNotTheSameColumn() {
            int alignedRows = 0;
            for (int i = 0; i < 200; i++) {
                CountMinSketch probe = new CountMinSketch(1 << 16, 8, 3L);
                probe.insert(("item-" + i).getBytes(StandardCharsets.UTF_8));
                long[] columns = columnsHolding(probe, 8, 1 << 16);
                if (columns[0] == columns[1]) {
                    alignedRows++;
                }
            }
            assertTrue(alignedRows < 3, "rows 0 and 1 agree on " + alignedRows + " of 200 items");
        }

        /** The column each row counted the one inserted item in. */
        private long[] columnsHolding(CountMinSketch probe, int depth, int width) {
            java.nio.ByteBuffer counters = java.nio.ByteBuffer.wrap(probe.toBytes());
            long[] columns = new long[depth];
            for (int row = 0; row < depth; row++) {
                for (int column = 0; column < width; column++) {
                    if (counters.getLong(32 + (row * width + column) * Long.BYTES) != 0) {
                        columns[row] = column;
                    }
                }
            }
            return columns;
        }

        @Test
        @DisplayName("a count of zero changes nothing, and a negative count is refused")
        void zeroAndNegativeCounts() {
            long hash = CountMinSketch.itemHash(ITEM_B);
            sketch.insertHashed(hash, 0);
            assertEquals(0, sketch.totalCount());
            assertThrows(IllegalArgumentException.class, () -> sketch.insertHashed(hash, -1));
            assertThrows(NullPointerException.class, () -> CountMinSketch.itemHash(null));
        }

        @Test
        @DisplayName("a sketch written under the per-row hashing reads back as unsupported")
        void perRowHashingVersionIsRefused() {
            assertVersionRefused(1);
        }

        @Test
        @DisplayName("a sketch written under the raw-step double hashing reads back as unsupported")
        void rawStepVersionIsRefused() {
            assertVersionRefused(2);
        }

        private void assertVersionRefused(int version) {
            byte[] bytes = new CountMinSketch(16, 2, 9L).toBytes();
            java.nio.ByteBuffer.wrap(bytes).putInt(4, version);

            IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> CountMinSketch.fromBytes(bytes));
            assertTrue(refused.getMessage().contains("version: " + version), refused.getMessage());
        }

        @Test
        @DisplayName("a step that is a multiple of a power-of-two width still gives every row its own column")
        void stepsSharingAFactorWithAPowerOfTwoWidthStayDistinct() {
            CountMinSketch probe = new CountMinSketch(1024, 4, 5L);
            // Upper halves the raw step used as-is: zero and the width's multiples collapsed
            // every row into one column or its mirror, half the width paired the rows off.
            long[] upperHalves = {0L, 1024L, 512L, 256L, 1L << 20, 0x8000_0000L, 0xFFFF_FC00L};
            for (long upper : upperHalves) {
                for (long lower : new long[] {0L, 7L, 0xFFFF_FFFFL, 0x8000_0000L}) {
                    assertRowsDistinct(probe, (upper << 32) | lower, 4);
                }
            }
        }

        @Test
        @DisplayName("every row has its own column for widths that are not powers of two")
        void stepsStayDistinctForCompositeWidths() {
            CountMinSketch probe = new CountMinSketch(2000, 10, 5L);
            for (long upper : new long[] {0L, 1000L, 250L, 400L, 500L, 125L, 2000L * 7}) {
                assertRowsDistinct(probe, upper << 32 | 13L, 10);
            }
        }

        @Test
        @DisplayName("every row has its own column for any mixed hash while depth fits the width")
        void rowsNeverShareAColumn() {
            CountMinSketch probe = new CountMinSketch(1024, 16, 5L);
            Random random = new Random(42);
            for (int i = 0; i < 10_000; i++) {
                assertRowsDistinct(probe, random.nextLong(), 16);
            }
        }

        @Test
        @DisplayName("a width of one is served by its single column")
        void widthOfOne() {
            CountMinSketch narrow = new CountMinSketch(1, 3, 5L);
            narrow.insert(ITEM_A);
            narrow.insert(ITEM_B);
            assertEquals(2, narrow.estimate(ITEM_A));
            assertEquals(0, narrow.column(-1L, 2));
        }

        private void assertRowsDistinct(CountMinSketch probe, long mixed, int rows) {
            java.util.Set<Integer> columns = new java.util.HashSet<>();
            for (int row = 0; row < rows; row++) {
                int column = probe.column(mixed, row);
                assertTrue(column >= 0 && column < probe.width(), "column " + column);
                columns.add(column);
            }
            assertEquals(rows, columns.size(),
                "rows share a column for mixed hash 0x" + Long.toHexString(mixed));
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTest {

        @Test
        @DisplayName("toString contains key information")
        void toStringContainsInfo() {
            String str = sketch.toString();
            assertTrue(str.contains("CountMinSketch"));
            assertTrue(str.contains("width=" + sketch.width()));
            assertTrue(str.contains("depth=" + sketch.depth()));
        }
    }
}
