package excalibur.filter.lsh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for LshIndex.
 */
class LshIndexTest {

    private static final int DIMENSIONS = 128;
    private LshConfig config;
    private LshIndex index;

    @BeforeEach
    void setUp() {
        config = LshConfig.builder(DIMENSIONS)
            .numHashTables(10)
            .numHashFunctions(12)
            .numProbes(3)
            .seed(42L)
            .build();
        index = new LshIndex(config);
    }

    @Test
    @DisplayName("Should create index with default configuration")
    void testCreateDefault() {
        LshIndex defaultIndex = LshIndex.create(DIMENSIONS);
        assertThat(defaultIndex.dimensions()).isEqualTo(DIMENSIONS);
        assertThat(defaultIndex.numTables()).isEqualTo(LshConfig.DEFAULT_NUM_HASH_TABLES);
    }

    @Test
    @DisplayName("Should insert and query vectors")
    void testInsertAndQuery() throws IOException {
        float[] vector1 = createRandomVector(DIMENSIONS, 1);
        float[] vector2 = createRandomVector(DIMENSIONS, 2);
        float[] similarVector = createSimilarVector(vector1, 0.01f);

        index.insert(vector1, 1L);
        index.insert(vector2, 2L);

        Set<Long> candidates = index.query(similarVector);

        // Similar vector should find the original
        assertThat(candidates).contains(1L);
        assertThat(index.entryCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should return empty set for query on empty index")
    void testQueryEmpty() {
        float[] vector = createRandomVector(DIMENSIONS, 1);
        Set<Long> candidates = index.query(vector);
        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("Should delete entries")
    void testDelete() throws IOException {
        float[] vector = createRandomVector(DIMENSIONS, 1);
        index.insert(vector, 1L);
        assertThat(index.entryCount()).isEqualTo(1L);

        boolean deleted = index.delete(1L);
        assertThat(deleted).isTrue();
        assertThat(index.entryCount()).isEqualTo(0L);

        // Delete again should return false
        assertThat(index.delete(1L)).isFalse();
    }

    @Test
    @DisplayName("Should handle multiple insertions of same entry")
    void testDuplicateInsert() throws IOException {
        float[] vector = createRandomVector(DIMENSIONS, 1);

        index.insert(vector, 1L);
        index.insert(vector, 1L); // Same entry

        // Entry count should reflect multiple inserts (one per table each time)
        assertThat(index.entryCount()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("Should validate vector dimensions")
    void testInvalidVectorDimensions() {
        float[] wrongSize = new float[64];

        assertThatThrownBy(() -> index.insert(wrongSize, 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vector length");

        assertThatThrownBy(() -> index.query(wrongSize))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vector length");
    }

    @Test
    @DisplayName("Should validate null vector")
    void testNullVector() {
        assertThatThrownBy(() -> index.insert(null, 1L))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> index.query(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should validate negative entry ID")
    void testNegativeEntryId() {
        float[] vector = createRandomVector(DIMENSIONS, 1);

        assertThatThrownBy(() -> index.insert(vector, -1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entryId must be non-negative");
    }

    @Test
    @DisplayName("Should handle concurrent insertions")
    void testConcurrentInsert() throws InterruptedException, IOException {
        int numThreads = 4;
        int insertsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < insertsPerThread; i++) {
                        long entryId = threadId * insertsPerThread + i;
                        float[] vector = createRandomVector(DIMENSIONS, (int) entryId);
                        index.insert(vector, entryId);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertThat(errors.get()).isEqualTo(0);
        assertThat(index.entryCount()).isEqualTo(numThreads * insertsPerThread);
    }

    @Test
    @DisplayName("Should handle concurrent queries")
    void testConcurrentQuery() throws InterruptedException, IOException {
        // Insert some vectors first
        for (int i = 0; i < 100; i++) {
            float[] vector = createRandomVector(DIMENSIONS, i);
            index.insert(vector, i);
        }

        int numThreads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        float[] vector = createRandomVector(DIMENSIONS, i);
                        Set<Long> candidates = index.query(vector);
                        assertThat(candidates).isNotNull();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertThat(errors.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should clear index")
    void testClear() throws IOException {
        for (int i = 0; i < 10; i++) {
            float[] vector = createRandomVector(DIMENSIONS, i);
            index.insert(vector, i);
        }

        assertThat(index.entryCount()).isEqualTo(10L);

        index.clear();

        assertThat(index.entryCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should find similar vectors with high probability")
    void testFindSimilarVectors() throws IOException {
        // Create base vector
        float[] base = createRandomVector(DIMENSIONS, 1);
        index.insert(base, 1L);

        // Create similar vectors with small perturbations
        int found = 0;
        int trials = 20;
        for (int i = 0; i < trials; i++) {
            float[] similar = createSimilarVector(base, 0.05f);
            Set<Long> candidates = index.query(similar);
            if (candidates.contains(1L)) {
                found++;
            }
        }

        // Should find similar vectors most of the time (LSH property)
        double recall = (double) found / trials;
        assertThat(recall).isGreaterThan(0.5); // At least 50% recall
    }

    @Test
    @DisplayName("Should return configuration")
    void testConfig() {
        assertThat(index.config()).isEqualTo(config);
        assertThat(index.dimensions()).isEqualTo(DIMENSIONS);
    }

    @Test
 @DisplayName("Should get individual tables")
    void testGetTable() {
        assertThat(index.numTables()).isEqualTo(config.numHashTables());

        LshHashTable table = index.getTable(0);
        assertThat(table).isNotNull();
        assertThat(table.numBits()).isEqualTo(config.numHashFunctions());

        assertThatThrownBy(() -> index.getTable(-1))
            .isInstanceOf(IndexOutOfBoundsException.class);

        assertThatThrownBy(() -> index.getTable(config.numHashTables()))
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("Should estimate memory usage")
    void testEstimatedMemoryUsage() throws IOException {
        long initialMemory = index.estimatedMemoryUsage();

        for (int i = 0; i < 100; i++) {
            float[] vector = createRandomVector(DIMENSIONS, i);
            index.insert(vector, i);
        }

        long finalMemory = index.estimatedMemoryUsage();
        assertThat(finalMemory).isGreaterThan(initialMemory);
    }

    @Test
    @DisplayName("Should check might contain")
    void testMightContain() throws IOException {
        float[] vector = createRandomVector(DIMENSIONS, 1);
        index.insert(vector, 1L);

        // Same vector should be found
        assertThat(index.mightContain(vector, 1L)).isTrue();

        // Different vector should likely not find the entry
        float[] different = createRandomVector(DIMENSIONS, 2);
        assertThat(index.mightContain(different, 1L)).isFalse();

        // Non-existent entry should return false
        assertThat(index.mightContain(vector, 999L)).isFalse();
    }

    @Test
    @DisplayName("Should serialize and deserialize index")
    void testSerializationRoundTrip() throws IOException {
        // Insert several entries
        for (int i = 0; i < 10; i++) {
            float[] vector = createRandomVector(DIMENSIONS, i);
            index.insert(vector, i);
        }

        long originalEntryCount = index.entryCount();
        assertThat(originalEntryCount).isEqualTo(10L);

        // Serialize
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = index.toMemorySegment(arena);

            // Verify segment is not empty
            assertThat(segment.byteSize()).isGreaterThan(0);

            // Deserialize
            LshIndex restored = LshIndex.fromMemorySegment(segment);

            // Verify restored index
            assertThat(restored.entryCount()).isEqualTo(originalEntryCount);
            assertThat(restored.dimensions()).isEqualTo(config.dimensions());
            assertThat(restored.numTables()).isEqualTo(config.numHashTables());
            assertThat(restored.config().numHashFunctions()).isEqualTo(config.numHashFunctions());
            assertThat(restored.config().numProbes()).isEqualTo(config.numProbes());
            assertThat(restored.config().seed()).isEqualTo(config.seed());
        }
    }

    @Test
    @DisplayName("Should restore queryability after deserialization")
    void testQueryAfterDeserialization() throws IOException {
        // Insert entries with known vectors
        float[] vector1 = createRandomVector(DIMENSIONS, 1);
        float[] vector2 = createRandomVector(DIMENSIONS, 2);
        float[] vector3 = createRandomVector(DIMENSIONS, 3);

        index.insert(vector1, 1L);
        index.insert(vector2, 2L);
        index.insert(vector3, 3L);

        // Serialize and deserialize
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = index.toMemorySegment(arena);
            LshIndex restored = LshIndex.fromMemorySegment(segment);

            // Verify entries are queryable
            assertThat(restored.mightContain(vector1, 1L)).isTrue();
            assertThat(restored.mightContain(vector2, 2L)).isTrue();
            assertThat(restored.mightContain(vector3, 3L)).isTrue();

            // Verify queries return expected candidates
            Set<Long> candidates1 = restored.query(vector1);
            assertThat(candidates1).contains(1L);

            Set<Long> candidates2 = restored.query(vector2);
            assertThat(candidates2).contains(2L);
        }
    }

    @Test
    @DisplayName("Should handle serialization of empty index")
    void testSerializeEmptyIndex() throws IOException {
        // Don't insert anything - index is empty

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = index.toMemorySegment(arena);
            LshIndex restored = LshIndex.fromMemorySegment(segment);

            assertThat(restored.entryCount()).isEqualTo(0L);
            assertThat(restored.numTables()).isEqualTo(config.numHashTables());
        }
    }

    @Test
    @DisplayName("Should reject deserialization with invalid magic number")
    void testDeserializeInvalidMagic() {
        try (Arena arena = Arena.ofConfined()) {
            // Create a segment with wrong magic number
            MemorySegment badSegment = arena.allocate(100);
            badSegment.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0, 0xDEADBEEF);

            assertThatThrownBy(() -> LshIndex.fromMemorySegment(badSegment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid magic number");
        }
    }

    @Test
    @DisplayName("Should reject deserialization with segment too small")
    void testDeserializeSegmentTooSmall() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment smallSegment = arena.allocate(10);

            assertThatThrownBy(() -> LshIndex.fromMemorySegment(smallSegment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Segment too small");
        }
    }

    @Test
    @DisplayName("Should reject deserialization with null segment")
    void testDeserializeNullSegment() {
        assertThatThrownBy(() -> LshIndex.fromMemorySegment(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("segment cannot be null");
    }

    @Test
    @DisplayName("Should preserve deleted state after deserialization")
    void testDeleteAfterDeserialization() throws IOException {
        // Insert entries
        for (int i = 0; i < 5; i++) {
            float[] vector = createRandomVector(DIMENSIONS, i);
            index.insert(vector, i);
        }

        // Delete one entry before serialization
        index.delete(2L);

        long countBeforeDelete = index.entryCount();

        // Serialize and deserialize
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = index.toMemorySegment(arena);
            LshIndex restored = LshIndex.fromMemorySegment(segment);

            // Verify entry count preserved
            assertThat(restored.entryCount()).isEqualTo(countBeforeDelete);

            // Verify deletion is reflected
            assertThat(restored.delete(2L)).isFalse(); // Already deleted
            assertThat(restored.delete(0L)).isTrue();  // Still present
        }
    }

    // Helper methods

    private float[] createRandomVector(int dimensions, int seed) {
        float[] vector = new float[dimensions];
        java.util.Random random = new java.util.Random(seed);
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        // Normalize
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimensions; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private float[] createSimilarVector(float[] original, float noiseLevel) {
        float[] similar = original.clone();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < similar.length; i++) {
            similar[i] += random.nextGaussian() * noiseLevel;
        }
        // Re-normalize
        float norm = 0;
        for (float v : similar) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < similar.length; i++) {
                similar[i] /= norm;
            }
        }
        return similar;
    }
}
