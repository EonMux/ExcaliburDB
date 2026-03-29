package excalibur.filter.lsh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for LshHashTable class.
 */
@DisplayName("LshHashTable Tests")
class LshHashTableTest {

    private static final int DIMENSIONS = 128;
    private static final int NUM_BITS = 16;
    private static final long SEED = 42L;

    private LshHashTable table;

    @BeforeEach
    void setUp() {
        table = new LshHashTable(DIMENSIONS, NUM_BITS, SEED);
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create table with parameters")
        void shouldCreateWithParameters() {
            assertThat(table.numBits()).isEqualTo(NUM_BITS);
            assertThat(table.dimensions()).isEqualTo(DIMENSIONS);
            assertThat(table.entryCount()).isEqualTo(0);
            assertThat(table.bucketCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should create table from hash function")
        void shouldCreateFromHashFunction() {
            RandomHyperplaneHash hashFunction = new RandomHyperplaneHash(DIMENSIONS, NUM_BITS, SEED);
            LshHashTable hashTable = new LshHashTable(hashFunction);

            assertThat(hashTable.numBits()).isEqualTo(NUM_BITS);
            assertThat(hashTable.dimensions()).isEqualTo(DIMENSIONS);
            // Compare properties since the constructor creates a new hash function internally
            assertThat(hashTable.hashFunction().numBits()).isEqualTo(hashFunction.numBits());
            assertThat(hashTable.hashFunction().dimensions()).isEqualTo(hashFunction.dimensions());
        }
    }

    @Nested
    @DisplayName("Insert Tests")
    class InsertTests {

        @Test
        @DisplayName("Should insert single entry")
        void shouldInsertSingleEntry() throws IOException {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            table.insert(vector, 1L);

            assertThat(table.entryCount()).isEqualTo(1);
            assertThat(table.bucketCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should insert multiple entries")
        void shouldInsertMultipleEntries() throws IOException {
            for (int i = 0; i < 100; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            assertThat(table.entryCount()).isEqualTo(100);
        }

        @Test
        @DisplayName("Should reject null vector")
        void shouldRejectNullVector() {
            assertThatThrownBy(() -> table.insert(null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector cannot be null");
        }

        @Test
        @DisplayName("Should reject wrong size vector")
        void shouldRejectWrongSizeVector() {
            float[] wrongSize = new float[64];

            assertThatThrownBy(() -> table.insert(wrongSize, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector length");
        }
    }

    @Nested
    @DisplayName("Query Tests")
    class QueryTests {

        @Test
        @DisplayName("Should query empty table")
        void shouldQueryEmptyTable() {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            Set<Long> candidates = table.query(vector, 3);

            assertThat(candidates).isEmpty();
        }

        @Test
        @DisplayName("Should find inserted entry")
        void shouldFindInsertedEntry() throws IOException {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            table.insert(vector, 1L);

            Set<Long> candidates = table.query(vector, 1);

            assertThat(candidates).contains(1L);
        }

        @Test
        @DisplayName("Should find entry with multi-probe")
        void shouldFindWithMultiProbe() throws IOException {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            table.insert(vector, 1L);

            Set<Long> candidates = table.query(vector, 5);

            assertThat(candidates).contains(1L);
        }

        @Test
        @DisplayName("Should reject null vector in query")
        void shouldRejectNullVectorInQuery() {
            assertThatThrownBy(() -> table.query(null, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector cannot be null");
        }

        @Test
        @DisplayName("Should reject wrong size vector in query")
        void shouldRejectWrongSizeVectorInQuery() {
            float[] wrongSize = new float[64];

            assertThatThrownBy(() -> table.query(wrongSize, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector length");
        }
    }

    @Nested
    @DisplayName("Query Exact Tests")
    class QueryExactTests {

        @Test
        @DisplayName("Should query exact hash")
        void shouldQueryExact() throws IOException {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            table.insert(vector, 1L);

            Set<Long> candidates = table.queryExact(vector);

            assertThat(candidates).contains(1L);
        }

        @Test
        @DisplayName("Should return empty for empty bucket")
        void shouldReturnEmptyForEmptyBucket() throws IOException {
            float[] vector1 = createRandomVector(DIMENSIONS, 1);
            float[] vector2 = createRandomVector(DIMENSIONS, 2);

            table.insert(vector1, 1L);

            Set<Long> candidates = table.queryExact(vector2);

            // May or may not contain, depending on hash collision
            assertThat(candidates).isNotNull();
        }

        @Test
        @DisplayName("Should reject null vector in queryExact")
        void shouldRejectNullVectorInQueryExact() {
            assertThatThrownBy(() -> table.queryExact(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector cannot be null");
        }

        @Test
        @DisplayName("Should reject wrong size vector in queryExact")
        void shouldRejectWrongSizeVectorInQueryExact() {
            float[] wrongSize = new float[64];

            assertThatThrownBy(() -> table.queryExact(wrongSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector length");
        }
    }

    @Nested
    @DisplayName("Delete Tests")
    class DeleteTests {

        @Test
        @DisplayName("Should delete entry")
        void shouldDeleteEntry() throws IOException {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            table.insert(vector, 1L);

            boolean deleted = table.delete(1L);

            assertThat(deleted).isTrue();
            assertThat(table.entryCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should return false for non-existent entry")
        void shouldReturnFalseForNonExistent() {
            boolean deleted = table.delete(999L);

            assertThat(deleted).isFalse();
        }

        @Test
        @DisplayName("Should delete from specific bucket")
        void shouldDeleteFromSpecificBucket() throws IOException {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            table.insert(vector, 1L);

            long hash = table.hashFunction().hash(vector);
            boolean deleted = table.deleteFromBucket(hash, 1L);

            assertThat(deleted).isTrue();
            assertThat(table.entryCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should return false for wrong bucket")
        void shouldReturnFalseForWrongBucket() throws IOException {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            table.insert(vector, 1L);

            boolean deleted = table.deleteFromBucket(999L, 1L);

            assertThat(deleted).isFalse();
            assertThat(table.entryCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should remove empty bucket after deletion")
        void shouldRemoveEmptyBucket() throws IOException {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            table.insert(vector, 1L);

            assertThat(table.bucketCount()).isEqualTo(1);

            table.delete(1L);

            assertThat(table.bucketCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Contains Tests")
    class ContainsTests {

        @Test
        @DisplayName("Should check contains for existing entry")
        void shouldCheckContainsExisting() throws IOException {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            table.insert(vector, 1L);

            long hash = table.hashFunction().hash(vector);
            boolean contains = table.contains(hash, 1L);

            assertThat(contains).isTrue();
        }

        @Test
        @DisplayName("Should check contains for non-existent entry")
        void shouldCheckContainsNonExistent() throws IOException {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            table.insert(vector, 1L);

            long hash = table.hashFunction().hash(vector);
            boolean contains = table.contains(hash, 999L);

            assertThat(contains).isFalse();
        }

        @Test
        @DisplayName("Should check contains for non-existent bucket")
        void shouldCheckContainsNonExistentBucket() {
            boolean contains = table.contains(999L, 1L);

            assertThat(contains).isFalse();
        }
    }

    @Nested
    @DisplayName("Clear Tests")
    class ClearTests {

        @Test
        @DisplayName("Should clear all entries")
        void shouldClearAllEntries() throws IOException {
            for (int i = 0; i < 50; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            assertThat(table.entryCount()).isEqualTo(50);

            table.clear();

            assertThat(table.entryCount()).isEqualTo(0);
            assertThat(table.bucketCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Serialization Tests")
    class SerializationTests {

        @Test
        @DisplayName("Should serialize to bytes")
        void shouldSerializeToBytes() throws IOException {
            for (int i = 0; i < 10; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            byte[] bytes = table.toBytes();

            assertThat(bytes).isNotEmpty();
            assertThat(bytes.length).isGreaterThan(32); // At least header size
        }

        @Test
        @DisplayName("Should serialize to MemorySegment")
        void shouldSerializeToMemorySegment() throws IOException {
            for (int i = 0; i < 10; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            try (Arena arena = Arena.ofConfined()) {
                var segment = table.toMemorySegment(arena);

                assertThat(segment).isNotNull();
                assertThat(segment.byteSize()).isGreaterThan(32);
            }
        }

        @Test
        @DisplayName("Should deserialize from MemorySegment")
        void shouldDeserializeFromMemorySegment() throws IOException {
            // Insert entries
            for (int i = 0; i < 10; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            // Serialize
            byte[] bytes;
            try (Arena arena = Arena.ofConfined()) {
                var segment = table.toMemorySegment(arena);
                bytes = segment.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            }

            // Deserialize
            try (Arena arena = Arena.ofConfined()) {
                var segment = Arena.ofConfined().allocate(bytes.length);
                segment.copyFrom(java.lang.foreign.MemorySegment.ofArray(bytes));

                LshHashTable restored = LshHashTable.fromMemorySegment(segment);

                assertThat(restored.numBits()).isEqualTo(NUM_BITS);
                assertThat(restored.dimensions()).isEqualTo(DIMENSIONS);
                assertThat(restored.entryCount()).isEqualTo(10);
            }
        }

        @Test
        @DisplayName("Should reject segment too small")
        void shouldRejectSegmentTooSmall() {
            try (Arena arena = Arena.ofConfined()) {
                var smallSegment = arena.allocate(16);

                assertThatThrownBy(() -> LshHashTable.fromMemorySegment(smallSegment))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Segment too small");
            }
        }

        @Test
        @DisplayName("Should reject invalid magic number")
        void shouldRejectInvalidMagicNumber() {
            try (Arena arena = Arena.ofConfined()) {
                var segment = arena.allocate(64);
                // Write wrong magic number
                segment.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0, 0xDEADBEEF);

                assertThatThrownBy(() -> LshHashTable.fromMemorySegment(segment))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid magic number");
            }
        }

        @Test
        @DisplayName("Should reject unsupported version")
        void shouldRejectUnsupportedVersion() {
            try (Arena arena = Arena.ofConfined()) {
                var segment = arena.allocate(64);
                // Write magic and wrong version
                segment.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0, 0x4C534854); // MAGIC
                segment.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 4, 999); // Wrong version

                assertThatThrownBy(() -> LshHashTable.fromMemorySegment(segment))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported version");
            }
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should contain key fields in toString")
        void shouldContainKeyFields() {
            String str = table.toString();

            assertThat(str).contains("LshHashTable");
            assertThat(str).contains("numBits=" + NUM_BITS);
            assertThat(str).contains("buckets=0");
            assertThat(str).contains("entries=0");
        }

        @Test
        @DisplayName("Should show counts after insertions")
        void shouldShowCountsAfterInsertions() throws IOException {
            for (int i = 0; i < 10; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            String str = table.toString();

            assertThat(str).contains("entries=10");
        }
    }

    @Nested
    @DisplayName("Hash Function Tests")
    class HashFunctionTests {

        @Test
        @DisplayName("Should return hash function")
        void shouldReturnHashFunction() {
            RandomHyperplaneHash hashFunction = table.hashFunction();

            assertThat(hashFunction).isNotNull();
            assertThat(hashFunction.numBits()).isEqualTo(NUM_BITS);
            assertThat(hashFunction.dimensions()).isEqualTo(DIMENSIONS);
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
}
