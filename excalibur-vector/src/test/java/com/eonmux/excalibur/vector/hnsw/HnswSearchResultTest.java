package com.eonmux.excalibur.vector.hnsw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for HnswSearchResult.
 */
@DisplayName("HnswSearchResult Tests")
class HnswSearchResultTest {

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create result with nodeId and distance")
        void shouldCreateResultWithNodeIdAndDistance() {
            HnswSearchResult result = new HnswSearchResult(100L, 0.5f);

            assertThat(result.nodeId()).isEqualTo(100L);
            assertThat(result.distance()).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("Should handle zero nodeId")
        void shouldHandleZeroNodeId() {
            HnswSearchResult result = new HnswSearchResult(0L, 0.0f);

            assertThat(result.nodeId()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should handle negative distance")
        void shouldHandleNegativeDistance() {
            HnswSearchResult result = new HnswSearchResult(100L, -0.5f);

            assertThat(result.distance()).isEqualTo(-0.5f);
        }

        @Test
        @DisplayName("Should handle large nodeId")
        void shouldHandleLargeNodeId() {
            HnswSearchResult result = new HnswSearchResult(Long.MAX_VALUE, 1.0f);

            assertThat(result.nodeId()).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        @DisplayName("Should handle special float values")
        void shouldHandleSpecialFloatValues() {
            HnswSearchResult nanResult = new HnswSearchResult(1L, Float.NaN);
            HnswSearchResult infResult = new HnswSearchResult(2L, Float.POSITIVE_INFINITY);
            HnswSearchResult negInfResult = new HnswSearchResult(3L, Float.NEGATIVE_INFINITY);

            assertThat(Float.isNaN(nanResult.distance())).isTrue();
            assertThat(infResult.distance()).isEqualTo(Float.POSITIVE_INFINITY);
            assertThat(negInfResult.distance()).isEqualTo(Float.NEGATIVE_INFINITY);
        }
    }

    @Nested
    @DisplayName("withDistance")
    class WithDistanceTests {

        @Test
        @DisplayName("Should create new result with different distance")
        void shouldCreateNewResultWithDifferentDistance() {
            HnswSearchResult original = new HnswSearchResult(100L, 0.5f);
            HnswSearchResult updated = original.withDistance(0.75f);

            assertThat(updated.nodeId()).isEqualTo(original.nodeId());
            assertThat(updated.distance()).isEqualTo(0.75f);
        }

        @Test
        @DisplayName("Should not modify original result")
        void shouldNotModifyOriginal() {
            HnswSearchResult original = new HnswSearchResult(100L, 0.5f);

            original.withDistance(0.75f);

            assertThat(original.distance()).isEqualTo(0.5f);
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            HnswSearchResult result1 = new HnswSearchResult(100L, 0.5f);
            HnswSearchResult result2 = new HnswSearchResult(100L, 0.5f);

            assertThat(result1).isEqualTo(result2);
            assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
        }

        @Test
        @DisplayName("Should be same instance")
        void shouldBeSameInstance() {
            HnswSearchResult result = new HnswSearchResult(100L, 0.5f);

            assertThat(result).isEqualTo(result);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void shouldNotBeEqualToNull() {
            HnswSearchResult result = new HnswSearchResult(100L, 0.5f);

            assertThat(result).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Should not be equal to different type")
        void shouldNotBeEqualToDifferentType() {
            HnswSearchResult result = new HnswSearchResult(100L, 0.5f);

            assertThat(result).isNotEqualTo("string");
        }

        @Test
        @DisplayName("Should not be equal for different nodeId")
        void shouldNotBeEqualForDifferentNodeId() {
            HnswSearchResult result1 = new HnswSearchResult(100L, 0.5f);
            HnswSearchResult result2 = new HnswSearchResult(101L, 0.5f);

            assertThat(result1).isNotEqualTo(result2);
        }

        @Test
        @DisplayName("Should not be equal for different distance")
        void shouldNotBeEqualForDifferentDistance() {
            HnswSearchResult result1 = new HnswSearchResult(100L, 0.5f);
            HnswSearchResult result2 = new HnswSearchResult(100L, 0.6f);

            assertThat(result1).isNotEqualTo(result2);
        }

        @Test
        @DisplayName("Should handle NaN equality correctly")
        void shouldHandleNaNEqualityCorrectly() {
            HnswSearchResult result1 = new HnswSearchResult(100L, Float.NaN);
            HnswSearchResult result2 = new HnswSearchResult(100L, Float.NaN);

            // Float.compare treats NaN as equal
            assertThat(result1).isEqualTo(result2);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("Should contain nodeId")
        void shouldContainNodeId() {
            HnswSearchResult result = new HnswSearchResult(100L, 0.5f);

            String str = result.toString();

            assertThat(str).contains("100");
        }

        @Test
        @DisplayName("Should contain distance")
        void shouldContainDistance() {
            HnswSearchResult result = new HnswSearchResult(100L, 0.5f);

            String str = result.toString();

            assertThat(str).contains("0.5");
        }

        @Test
        @DisplayName("Should contain class name")
        void shouldContainClassName() {
            HnswSearchResult result = new HnswSearchResult(100L, 0.5f);

            String str = result.toString();

            assertThat(str).contains("HnswSearchResult");
        }

        @Test
        @DisplayName("Should format distance with precision")
        void shouldFormatDistanceWithPrecision() {
            HnswSearchResult result = new HnswSearchResult(100L, 0.123456789f);

            String str = result.toString();

            assertThat(str).contains("0.123457");
        }
    }
}
