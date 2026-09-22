package com.eonmux.excalibur.vector.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for VectorLayout configuration.
 *
 * <p>Verifies:</p>
 * <ul>
 *   <li>Layout calculations are correct</li>
 *   <li>Domain constraints are enforced</li>
 *   <li>Cache alignment is proper</li>
 * </ul>
 */
class VectorLayoutTest {

    private static final int DEFAULT_CACHE_LINE = 64;
    private static final int HEADER_SIZE = 64;

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("Rejects dimension <= 0")
        void rejectsZeroDimension() {
            assertThatThrownBy(() -> VectorLayout.create(0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dimension");
        }

        @Test
        @DisplayName("Rejects negative dimension")
        void rejectsNegativeDimension() {
            assertThatThrownBy(() -> VectorLayout.create(-1, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dimension");
        }

        @Test
        @DisplayName("Rejects dimension > MAX_DIMENSION")
        void rejectsExcessiveDimension() {
            assertThatThrownBy(() -> VectorLayout.create(5000, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dimension");
        }

        @Test
        @DisplayName("Rejects vectorCount <= 0")
        void rejectsZeroVectorCount() {
            assertThatThrownBy(() -> VectorLayout.create(128, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vector count");
        }

        @Test
        @DisplayName("Rejects non-power-of-2 cache line size")
        void rejectsInvalidCacheLine() {
            assertThatThrownBy(() ->
                new VectorLayout(128, 100, 63, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cache line size");
        }

        @Test
        @DisplayName("Accepts valid parameters")
        void acceptsValidParameters() {
            assertThatCode(() -> VectorLayout.create(128, 1000))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Size calculations")
    class SizeCalculations {

        @Test
        @DisplayName("Header size is 64 bytes")
        void headerSizeIsCorrect() {
            assertThat(VectorLayout.HEADER_SIZE).isEqualTo(64);
        }

        @Test
        @DisplayName("Element block size is cache-aligned")
        void elementBlockIsAligned() {
            VectorLayout layout = VectorLayout.create(128, 100);

            // 100 floats = 400 bytes, should align to 448 (next 64-byte boundary)
            // Actually: (400 + 63) & ~63 = 448
            long expectedBlock = alignTo64(100 * 4);
            assertThat(layout.elementBlockSize()).isEqualTo(expectedBlock);
        }

        @Test
        @DisplayName("Total size includes header and all element blocks")
        void totalSizeIsCorrect() {
            int dimension = 128;
            int vectorCount = 100;
            VectorLayout layout = VectorLayout.create(dimension, vectorCount);

            long elementBlock = alignTo64(vectorCount * 4);
            long expected = HEADER_SIZE + dimension * elementBlock;

            assertThat(layout.totalBlockSize()).isEqualTo(expected);
        }

        @Test
        @DisplayName("Element block offset starts after header")
        void elementBlockOffsetAfterHeader() {
            VectorLayout layout = VectorLayout.create(128, 100);

            assertThat(layout.elementBlockOffset(0)).isEqualTo(HEADER_SIZE);
        }

        @Test
        @DisplayName("Element block offsets are sequential")
        void elementBlockOffsetsSequential() {
            VectorLayout layout = VectorLayout.create(128, 100);
            long elementBlock = layout.elementBlockSize();

            for (int e = 0; e < 128; e++) {
                assertThat(layout.elementBlockOffset(e))
                    .isEqualTo(HEADER_SIZE + e * elementBlock);
            }
        }
    }

    @Nested
    @DisplayName("Element offset calculations")
    class ElementOffsetCalculations {

        @Test
        @DisplayName("SoA layout: element offset within element block")
        void soaElementOffset() {
            VectorLayout layout = VectorLayout.create(4, 10);

            // Vector 0, Element 0 -> right after header
            assertThat(layout.elementOffset(0, 0)).isEqualTo(HEADER_SIZE);

            // Vector 1, Element 0 -> one float after
            assertThat(layout.elementOffset(1, 0)).isEqualTo(HEADER_SIZE + 4);

            // Vector 0, Element 1 -> start of element block 1
            long elementBlock1 = layout.elementBlockOffset(1);
            assertThat(layout.elementOffset(0, 1)).isEqualTo(elementBlock1);
        }

        @Test
        @DisplayName("Rejects out-of-range vector index")
        void rejectsOutOfRangeVectorIndex() {
            VectorLayout layout = VectorLayout.create(128, 100);

            assertThatThrownBy(() -> layout.elementOffset(100, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects out-of-range element index")
        void rejectsOutOfRangeElementIndex() {
            VectorLayout layout = VectorLayout.create(128, 100);

            assertThatThrownBy(() -> layout.elementOffset(0, 128))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("create(dimension, count) uses default settings")
        void createDefault() {
            VectorLayout layout = VectorLayout.create(128, 100);

            assertThat(layout.dimension()).isEqualTo(128);
            assertThat(layout.vectorCount()).isEqualTo(100);
            assertThat(layout.cacheLineSize()).isEqualTo(DEFAULT_CACHE_LINE);
            assertThat(layout.isNormalized()).isFalse();
            assertThat(layout.isTransposed()).isTrue();
        }

        @Test
        @DisplayName("create(dimension, count, normalized) sets normalized flag")
        void createNormalized() {
            VectorLayout layout = VectorLayout.create(128, 100, true);

            assertThat(layout.isNormalized()).isTrue();
        }

        @Test
        @DisplayName("withVectorCount creates new layout with different count")
        void withVectorCount() {
            VectorLayout original = VectorLayout.create(128, 100);
            VectorLayout modified = original.withVectorCount(200);

            assertThat(modified.vectorCount()).isEqualTo(200);
            assertThat(modified.dimension()).isEqualTo(original.dimension());
            assertThat(original.vectorCount()).isEqualTo(100); // Original unchanged
        }
    }

    @Nested
    @DisplayName("Domain correctness")
    class DomainCorrectness {

        @Test
        @DisplayName("Floats per cache line is 16")
        void floatsPerCacheLine() {
            VectorLayout layout = VectorLayout.create(128, 100);

            assertThat(layout.floatsPerCacheLine()).isEqualTo(16);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 8, 64, 128, 256, 512, 1024, 4096})
        @DisplayName("Accepts valid dimensions at boundaries")
        void acceptsValidDimensions(int dim) {
            assertThatCode(() -> VectorLayout.create(dim, 100))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MAX_DIMENSION is accepted")
        void acceptsMaxDimension() {
            assertThatCode(() -> VectorLayout.create(VectorLayout.MAX_DIMENSION, 10))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MAX_VECTORS_PER_BLOCK is accepted")
        void acceptsMaxVectors() {
            // Don't actually allocate this much memory
            VectorLayout layout = new VectorLayout(
                16, VectorLayout.MAX_VECTORS_PER_BLOCK,
                DEFAULT_CACHE_LINE, false, true);

            assertThat(layout.vectorCount()).isEqualTo(VectorLayout.MAX_VECTORS_PER_BLOCK);
        }
    }

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("Equal layouts have same hashCode")
        void equalLayoutsHaveSameHashCode() {
            VectorLayout layout1 = VectorLayout.create(128, 100, true);
            VectorLayout layout2 = VectorLayout.create(128, 100, true);

            assertThat(layout1).isEqualTo(layout2);
            assertThat(layout1.hashCode()).isEqualTo(layout2.hashCode());
        }

        @Test
        @DisplayName("Different dimensions are not equal")
        void differentDimensionsNotEqual() {
            VectorLayout layout1 = VectorLayout.create(128, 100);
            VectorLayout layout2 = VectorLayout.create(256, 100);

            assertThat(layout1).isNotEqualTo(layout2);
        }

        @Test
        @DisplayName("Different normalized flags are not equal")
        void differentNormalizedNotEqual() {
            VectorLayout layout1 = VectorLayout.create(128, 100, true);
            VectorLayout layout2 = VectorLayout.create(128, 100, false);

            assertThat(layout1).isNotEqualTo(layout2);
        }
    }

    // Helper
    private static long alignTo64(long size) {
        return (size + 63) & ~63L;
    }
}
