package excalibur.filter.hyperloglog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HyperLogLog.
 */
class HyperLogLogTest {

    private HyperLogLog hll;
    private static final int DEFAULT_PRECISION = 14;

    @BeforeEach
    void setUp() {
        hll = new HyperLogLog(DEFAULT_PRECISION);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Default constructor creates valid sketch")
        void defaultConstructor() {
            HyperLogLog sketch = new HyperLogLog();
            assertEquals(DEFAULT_PRECISION, sketch.precision());
            assertEquals(1 << DEFAULT_PRECISION, sketch.numRegisters());
        }

        @Test
        @DisplayName("Custom precision creates valid sketch")
        void customPrecision() {
            HyperLogLog sketch = new HyperLogLog(10);
            assertEquals(10, sketch.precision());
            assertEquals(1024, sketch.numRegisters());
        }

        @Test
        @DisplayName("Precision below minimum throws exception")
        void precisionBelowMinimum() {
            assertThrows(IllegalArgumentException.class, () -> new HyperLogLog(3));
        }

        @Test
        @DisplayName("Precision above maximum throws exception")
        void precisionAboveMaximum() {
            assertThrows(IllegalArgumentException.class, () -> new HyperLogLog(17));
        }

        @Test
        @DisplayName("Custom seed creates valid sketch")
        void customSeed() {
            HyperLogLog sketch = new HyperLogLog(12, 12345L);
            assertEquals(12, sketch.precision());
        }

        @Test
        @DisplayName("Standard error is computed correctly")
        void standardError() {
            // precision 14: m=16384, error = 1.04/sqrt(16384)*100 ≈ 0.8125%
            double error14 = HyperLogLog.standardError(14);
            assertTrue(error14 > 0.7 && error14 < 0.9,
                "Expected error ~0.81% for precision 14, got: " + error14);

            // precision 10: m=1024, error = 1.04/sqrt(1024)*100 ≈ 3.25%
            double error10 = HyperLogLog.standardError(10);
            assertTrue(error10 > 3.1 && error10 < 3.4,
                "Expected error ~3.25% for precision 10, got: " + error10);
        }
    }

    @Nested
    @DisplayName("Insert and Estimate")
    class InsertAndEstimate {

        @Test
        @DisplayName("Empty sketch returns 0")
        void emptySketch() {
            assertEquals(0, hll.estimate());
        }

        @Test
        @DisplayName("Single element cardinality is approximately 1")
        void singleElement() {
            hll.insert("element1".getBytes(StandardCharsets.UTF_8));
            long estimate = hll.estimate();
            // With precision 14, HLL isn't very accurate for single elements
            // Linear counting correction should give a reasonable estimate
            assertTrue(estimate >= 1 && estimate <= 5,
                "Estimate should be close to 1, got: " + estimate);
        }

        @Test
        @DisplayName("Null item throws exception")
        void nullItem() {
            assertThrows(NullPointerException.class, () -> hll.insert(null));
        }

        @Test
        @DisplayName("1000 unique elements estimate within 5% error")
        void thousandUniqueElements() {
            Set<String> inserted = new HashSet<>();
            Random random = new Random(42);

            for (int i = 0; i < 1000; i++) {
                String item = "item-" + random.nextInt(Integer.MAX_VALUE);
                while (inserted.contains(item)) {
                    item = "item-" + random.nextInt(Integer.MAX_VALUE);
                }
                inserted.add(item);
                hll.insert(item.getBytes(StandardCharsets.UTF_8));
            }

            long estimate = hll.estimate();
            double error = Math.abs(estimate - 1000) / 1000.0;
            assertTrue(error < 0.05,
                "Error should be < 5%, actual: " + (error * 100) + "%, estimate: " + estimate);
        }

        @Test
        @DisplayName("10000 unique elements estimate within 3% error")
        void tenThousandUniqueElements() {
            Set<String> inserted = new HashSet<>();
            Random random = new Random(123);

            for (int i = 0; i < 10000; i++) {
                String item = "item-" + random.nextInt(Integer.MAX_VALUE);
                while (inserted.contains(item)) {
                    item = "item-" + random.nextInt(Integer.MAX_VALUE);
                }
                inserted.add(item);
                hll.insert(item.getBytes(StandardCharsets.UTF_8));
            }

            long estimate = hll.estimate();
            double error = Math.abs(estimate - 10000) / 10000.0;
            assertTrue(error < 0.03,
                "Error should be < 3%, actual: " + (error * 100) + "%, estimate: " + estimate);
        }

        @Test
        @DisplayName("Duplicate inserts don't increase estimate significantly")
        void duplicateInserts() {
            byte[] item = "duplicate-item".getBytes(StandardCharsets.UTF_8);

            // Insert same item 1000 times
            for (int i = 0; i < 1000; i++) {
                hll.insert(item);
            }

            long estimate = hll.estimate();
            // Estimate should still be close to 1
            assertTrue(estimate >= 1 && estimate <= 5,
                "Estimate should be close to 1, got: " + estimate);
        }

        @Test
        @DisplayName("Mixed unique and duplicate items")
        void mixedUniqueAndDuplicates() {
            Set<String> uniqueItems = new HashSet<>();
            Random random = new Random(999);

            // Insert 100 unique items
            for (int i = 0; i < 100; i++) {
                String item = "unique-" + i;
                uniqueItems.add(item);
                hll.insert(item.getBytes(StandardCharsets.UTF_8));
            }

            // Insert duplicates 10 times each
            for (int round = 0; round < 10; round++) {
                for (String item : uniqueItems) {
                    hll.insert(item.getBytes(StandardCharsets.UTF_8));
                }
            }

            long estimate = hll.estimate();
            double error = Math.abs(estimate - 100) / 100.0;
            assertTrue(error < 0.1,
                "Error should be < 10%, actual: " + (error * 100) + "%, estimate: " + estimate);
        }
    }

    @Nested
    @DisplayName("Merge Operation")
    class MergeOperation {

        @Test
        @DisplayName("Merge of two sketches combines cardinality")
        void mergeTwoSketches() {
            HyperLogLog hll1 = new HyperLogLog(DEFAULT_PRECISION);
            HyperLogLog hll2 = new HyperLogLog(DEFAULT_PRECISION);

            // Add 500 items to first sketch
            for (int i = 0; i < 500; i++) {
                hll1.insert(("item-a-" + i).getBytes(StandardCharsets.UTF_8));
            }

            // Add 500 different items to second sketch
            for (int i = 0; i < 500; i++) {
                hll2.insert(("item-b-" + i).getBytes(StandardCharsets.UTF_8));
            }

            // Merge
            hll1.merge(hll2);

            long estimate = hll1.estimate();
            double error = Math.abs(estimate - 1000) / 1000.0;
            assertTrue(error < 0.05,
                "Error should be < 5%, actual: " + (error * 100) + "%, estimate: " + estimate);
        }

        @Test
        @DisplayName("Merge with overlapping items")
        void mergeWithOverlap() {
            HyperLogLog hll1 = new HyperLogLog(DEFAULT_PRECISION, 42);
            HyperLogLog hll2 = new HyperLogLog(DEFAULT_PRECISION, 42);

            // Add items 0-499 to first sketch
            for (int i = 0; i < 500; i++) {
                hll1.insert(("item-" + i).getBytes(StandardCharsets.UTF_8));
            }

            // Add items 250-749 to second sketch (250 overlap)
            for (int i = 250; i < 750; i++) {
                hll2.insert(("item-" + i).getBytes(StandardCharsets.UTF_8));
            }

            // Merge - should estimate ~750 unique items
            hll1.merge(hll2);

            long estimate = hll1.estimate();
            double error = Math.abs(estimate - 750) / 750.0;
            assertTrue(error < 0.05,
                "Error should be < 5%, actual: " + (error * 100) + "%, estimate: " + estimate);
        }

        @Test
        @DisplayName("Merge with different precision throws exception")
        void mergeDifferentPrecision() {
            HyperLogLog hll1 = new HyperLogLog(12);
            HyperLogLog hll2 = new HyperLogLog(14);

            assertThrows(IllegalArgumentException.class, () -> hll1.merge(hll2));
        }

        @Test
        @DisplayName("Merge with null throws exception")
        void mergeNull() {
            assertThrows(NullPointerException.class, () -> hll.merge(null));
        }

        @Test
        @DisplayName("Merge preserves max register values")
        void mergePreservesMax() {
            HyperLogLog hll1 = new HyperLogLog(DEFAULT_PRECISION, 42);
            HyperLogLog hll2 = new HyperLogLog(DEFAULT_PRECISION, 42);

            // Insert same item into both - should result in same register update
            byte[] item = "test-item".getBytes(StandardCharsets.UTF_8);
            hll1.insert(item);
            hll2.insert(item);

            // Merge should keep the max (which is same)
            hll1.merge(hll2);

            long estimate = hll1.estimate();
            assertTrue(estimate >= 1 && estimate <= 3,
                "Estimate should be close to 1 after merging identical sketches, got: " + estimate);
        }
    }

    @Nested
    @DisplayName("Clear Operation")
    class ClearOperation {

        @Test
        @DisplayName("Clear resets sketch to empty")
        void clearResetsSketch() {
            for (int i = 0; i < 1000; i++) {
                hll.insert(("item-" + i).getBytes(StandardCharsets.UTF_8));
            }

            assertTrue(hll.estimate() > 0);

            hll.clear();

            assertEquals(0, hll.estimate());
        }

        @Test
        @DisplayName("Clear allows reuse")
        void clearAllowsReuse() {
            // First batch
            for (int i = 0; i < 100; i++) {
                hll.insert(("batch1-" + i).getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(hll.estimate() > 50);

            hll.clear();

            // Second batch
            for (int i = 0; i < 50; i++) {
                hll.insert(("batch2-" + i).getBytes(StandardCharsets.UTF_8));
            }

            long estimate = hll.estimate();
            double error = Math.abs(estimate - 50) / 50.0;
            assertTrue(error < 0.15,
                "Error should be < 15%, actual: " + (error * 100) + "%, estimate: " + estimate);
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("Serialize and deserialize preserves state")
        void serializeDeserialize() {
            // Insert some items
            for (int i = 0; i < 1000; i++) {
                hll.insert(("item-" + i).getBytes(StandardCharsets.UTF_8));
            }
            long originalEstimate = hll.estimate();

            MemorySegment segment = hll.toMemorySegment(Arena.ofShared());
            HyperLogLog restored = HyperLogLog.fromMemorySegment(segment);

            assertEquals(hll.precision(), restored.precision());
            assertEquals(hll.numRegisters(), restored.numRegisters());
            assertEquals(originalEstimate, restored.estimate());
        }

        @Test
        @DisplayName("Serialize empty sketch")
        void serializeEmptySketch() {
            MemorySegment segment = hll.toMemorySegment(Arena.ofShared());
            HyperLogLog restored = HyperLogLog.fromMemorySegment(segment);

            assertEquals(0, restored.estimate());
        }

        @Test
        @DisplayName("Invalid magic number throws exception")
        void invalidMagic() {
            MemorySegment segment = Arena.ofShared().allocate(HEADER_SIZE);
            segment.fill((byte) 0);
            assertThrows(IllegalArgumentException.class,
                () -> HyperLogLog.fromMemorySegment(segment));
        }

        @Test
        @DisplayName("Segment too small throws exception")
        void segmentTooSmall() {
            MemorySegment segment = Arena.ofShared().allocate(16);
            assertThrows(IllegalArgumentException.class,
                () -> HyperLogLog.fromMemorySegment(segment));
        }

        @Test
        @DisplayName("Null segment throws exception")
        void nullSegment() {
            assertThrows(IllegalArgumentException.class,
                () -> HyperLogLog.fromMemorySegment(null));
        }

        @Test
        @DisplayName("Invalid precision in segment throws exception")
        void invalidPrecisionInSegment() {
            MemorySegment segment = Arena.ofShared().allocate(HEADER_SIZE + 1000);
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT.withOrder(java.nio.ByteOrder.BIG_ENDIAN), 0, 0x484C4C4B); // MAGIC
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT.withOrder(java.nio.ByteOrder.BIG_ENDIAN), 4, 1); // VERSION
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT.withOrder(java.nio.ByteOrder.BIG_ENDIAN), 8, 20); // Invalid precision

            assertThrows(IllegalArgumentException.class,
                () -> HyperLogLog.fromMemorySegment(segment));
        }
    }

    private static final int HEADER_SIZE = 24;

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("Concurrent inserts are safe")
        void concurrentInserts() throws InterruptedException {
            int numThreads = 10;
            int insertsPerThread = 1000;
            Thread[] threads = new Thread[numThreads];
            AtomicLong uniqueCount = new AtomicLong(0);

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                threads[i] = Thread.ofVirtual().start(() -> {
                    for (int j = 0; j < insertsPerThread; j++) {
                        byte[] item = ("item-" + threadId + "-" + j).getBytes(StandardCharsets.UTF_8);
                        hll.insert(item);
                        uniqueCount.incrementAndGet();
                    }
                });
            }

            for (Thread thread : threads) {
                thread.join();
            }

            // All items are unique, estimate should be close to total
            long estimate = hll.estimate();
            int expectedTotal = numThreads * insertsPerThread;
            double error = Math.abs(estimate - expectedTotal) / (double) expectedTotal;
            assertTrue(error < 0.05,
                "Error should be < 5%, actual: " + (error * 100) + "%, estimate: " + estimate);
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
                            hll.insert(("write-" + id + "-" + j).getBytes(StandardCharsets.UTF_8));
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
                            hll.estimate();
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

            // Verify estimate is reasonable
            long estimate = hll.estimate();
            assertTrue(estimate > 0);
        }

        @Test
        @DisplayName("Concurrent merge operations are safe")
        void concurrentMerge() throws InterruptedException {
            int numThreads = 5;
            Thread[] threads = new Thread[numThreads];

            for (int i = 0; i < numThreads; i++) {
                final int id = i;
                threads[i] = Thread.ofVirtual().start(() -> {
                    HyperLogLog other = new HyperLogLog(DEFAULT_PRECISION);
                    for (int j = 0; j < 100; j++) {
                        other.insert(("merge-" + id + "-" + j).getBytes(StandardCharsets.UTF_8));
                    }
                    hll.merge(other);
                });
            }

            for (Thread thread : threads) {
                thread.join();
            }

            // Should have ~500 unique items
            long estimate = hll.estimate();
            assertTrue(estimate > 400 && estimate < 600,
                "Estimate should be around 500, got: " + estimate);
        }
    }

    @Nested
    @DisplayName("Memory Usage")
    class MemoryUsage {

        @Test
        @DisplayName("Estimated memory usage is reasonable")
        void memoryUsage() {
            long usage = hll.estimatedMemoryUsage();
            long expected = HEADER_SIZE + hll.numRegisters();
            assertEquals(expected, usage);
        }

        @Test
        @DisplayName("Memory scales with precision")
        void memoryScalesWithPrecision() {
            HyperLogLog hll10 = new HyperLogLog(10);
            HyperLogLog hll14 = new HyperLogLog(14);
            HyperLogLog hll16 = new HyperLogLog(16);

            assertTrue(hll10.estimatedMemoryUsage() < hll14.estimatedMemoryUsage());
            assertTrue(hll14.estimatedMemoryUsage() < hll16.estimatedMemoryUsage());
        }
    }

    @Nested
    @DisplayName("Accuracy")
    class Accuracy {

        @Test
        @DisplayName("100000 unique elements estimate within 2% error")
        void largeCardinality() {
            Set<String> inserted = new HashSet<>();
            Random random = new Random(777);

            for (int i = 0; i < 100000; i++) {
                String item = "large-" + random.nextInt(Integer.MAX_VALUE);
                while (inserted.contains(item)) {
                    item = "large-" + random.nextInt(Integer.MAX_VALUE);
                }
                inserted.add(item);
                hll.insert(item.getBytes(StandardCharsets.UTF_8));
            }

            long estimate = hll.estimate();
            double error = Math.abs(estimate - 100000) / 100000.0;
            assertTrue(error < 0.02,
                "Error should be < 2%, actual: " + (error * 100) + "%, estimate: " + estimate);
        }

        @Test
        @DisplayName("Very small cardinality (5 items)")
        void verySmallCardinality() {
            for (int i = 0; i < 5; i++) {
                hll.insert(("tiny-" + i).getBytes(StandardCharsets.UTF_8));
            }

            long estimate = hll.estimate();
            // With linear counting correction, should be reasonably accurate
            assertTrue(estimate >= 4 && estimate <= 8,
                "Estimate should be close to 5, got: " + estimate);
        }

        @Test
        @DisplayName("Cardinality progression")
        void cardinalityProgression() {
            HyperLogLog sketch = new HyperLogLog(14);
            Random random = new Random(111);

            int[] checkpoints = {10, 100, 1000, 10000};
            int inserted = 0;

            for (int checkpoint : checkpoints) {
                while (inserted < checkpoint) {
                    sketch.insert(("prog-" + random.nextInt(Integer.MAX_VALUE)).getBytes(StandardCharsets.UTF_8));
                    inserted++;
                }

                long estimate = sketch.estimate();
                double error = Math.abs(estimate - checkpoint) / (double) checkpoint;
                assertTrue(error < 0.1,
                    "At " + checkpoint + " items, error should be < 10%, actual: " + (error * 100) + "%");
            }
        }
    }

    @Nested
    @DisplayName("Seeding")
    class SeedingTest {

        /**
         * The seed decides which register a term lands in, so it is what makes two sketches
         * comparable at all. These pin that contract: merging is only meaningful between
         * sketches that hash identically, and an estimate has to be reproducible across restarts
         * because the query planner reads it as a statistic.
         */

        @Test
        @DisplayName("two sketches of the same precision hash identically by default")
        void defaultSeedIsFixed() {
            HyperLogLog first = new HyperLogLog(DEFAULT_PRECISION);
            HyperLogLog second = new HyperLogLog(DEFAULT_PRECISION);
            for (int i = 0; i < 500; i++) {
                first.insert(("term-" + i).getBytes(StandardCharsets.UTF_8));
                second.insert(("term-" + i).getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(first.estimate(), second.estimate(),
                "the same terms in two default sketches must give the same estimate; a per-"
                    + "instance seed would make the planner's statistic differ per restart");
        }

        @Test
        @DisplayName("merging overlapping sketches is a union, not a sum")
        void mergeIsAUnion() {
            // With a per-instance seed the same term lands in different registers in each
            // sketch, so taking the per-register maximum reports roughly the sum instead of the
            // union - a plausible-looking number that over-counts by the overlap.
            HyperLogLog left = new HyperLogLog(DEFAULT_PRECISION);
            HyperLogLog right = new HyperLogLog(DEFAULT_PRECISION);
            for (int i = 0; i < 600; i++) {
                left.insert(("term-" + i).getBytes(StandardCharsets.UTF_8));
            }
            for (int i = 300; i < 900; i++) {
                right.insert(("term-" + i).getBytes(StandardCharsets.UTF_8));
            }

            left.merge(right);

            double error = Math.abs(left.estimate() - 900) / 900.0;
            assertTrue(error < 0.05,
                "union of 900 distinct terms, got " + left.estimate());
        }

        @Test
        @DisplayName("merging a sketch that hashes differently is refused, not guessed at")
        void mismatchedSeedsAreRefused() {
            HyperLogLog mine = new HyperLogLog(DEFAULT_PRECISION, 1L);
            HyperLogLog theirs = new HyperLogLog(DEFAULT_PRECISION, 2L);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mine.merge(theirs));
            assertTrue(thrown.getMessage().contains("seed"), thrown.getMessage());
        }

        @Test
        @DisplayName("a sketch round-trips through its serialised form and still merges")
        void serialisedSketchesStillMerge() {
            HyperLogLog original = new HyperLogLog(DEFAULT_PRECISION);
            original.insert("a".getBytes(StandardCharsets.UTF_8));

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment written = original.toMemorySegment(arena);
                HyperLogLog restored = HyperLogLog.fromMemorySegment(written);

                HyperLogLog fresh = new HyperLogLog(DEFAULT_PRECISION);
                fresh.insert("b".getBytes(StandardCharsets.UTF_8));

                restored.merge(fresh);
                assertEquals(2L, restored.estimate(),
                    "a sketch read back from disk must still merge with a live one");
            }
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTest {

        @Test
        @DisplayName("toString contains key information")
        void toStringContainsInfo() {
            String str = hll.toString();
            assertTrue(str.contains("HyperLogLog"));
            assertTrue(str.contains("precision=" + hll.precision()));
            assertTrue(str.contains("error"));
        }
    }
}
