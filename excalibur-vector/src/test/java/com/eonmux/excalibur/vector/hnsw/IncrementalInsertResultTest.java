package com.eonmux.excalibur.vector.hnsw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for IncrementalInsertResult.
 */
@DisplayName("IncrementalInsertResult Tests")
class IncrementalInsertResultTest {

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("FAILED constant should have nodeId -1")
        void failedConstantShouldHaveInvalidNodeId() {
            assertThat(IncrementalInsertResult.FAILED.nodeId()).isEqualTo(-1);
        }

        @Test
        @DisplayName("FAILED constant should not be success")
        void failedConstantShouldNotBeSuccess() {
            assertThat(IncrementalInsertResult.FAILED.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("FAILED constant should have empty connections")
        void failedConstantShouldHaveEmptyConnections() {
            assertThat(IncrementalInsertResult.FAILED.connectedNodes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create result with all fields")
        void shouldCreateResultWithAllFields() {
            List<Long> connectedNodes = Arrays.asList(1L, 2L, 3L);
            IncrementalInsertResult result = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, connectedNodes
            );

            assertThat(result.nodeId()).isEqualTo(100L);
            assertThat(result.level()).isEqualTo(2);
            assertThat(result.dataOffset()).isEqualTo(5000L);
            assertThat(result.connectionsMade()).isEqualTo(5);
            assertThat(result.writeLogOffset()).isEqualTo(1000L);
            assertThat(result.connectedNodes()).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("Should handle null connected nodes")
        void shouldHandleNullConnectedNodes() {
            IncrementalInsertResult result = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, null
            );

            assertThat(result.connectedNodes()).isNotNull();
            assertThat(result.connectedNodes()).isEmpty();
        }

        @Test
        @DisplayName("Should handle empty connected nodes")
        void shouldHandleEmptyConnectedNodes() {
            IncrementalInsertResult result = new IncrementalInsertResult(
                100L, 2, 5000L, 0, 1000L, Collections.emptyList()
            );

            assertThat(result.connectedNodes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("isSuccess")
    class IsSuccessTests {

        @Test
        @DisplayName("Should return true for non-negative nodeId")
        void shouldReturnTrueForNonNegativeNodeId() {
            IncrementalInsertResult result = new IncrementalInsertResult(
                0L, 0, 0L, 0, 0L, null
            );

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should return true for positive nodeId")
        void shouldReturnTrueForPositiveNodeId() {
            IncrementalInsertResult result = new IncrementalInsertResult(
                999L, 3, 1000L, 10, 500L, Arrays.asList(1L, 2L)
            );

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should return false for negative nodeId")
        void shouldReturnFalseForNegativeNodeId() {
            IncrementalInsertResult result = new IncrementalInsertResult(
                -1L, 0, 0L, 0, 0L, null
            );

            assertThat(result.isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("withWriteLogOffset")
    class WithWriteLogOffsetTests {

        @Test
        @DisplayName("Should create new result with updated write log offset")
        void shouldCreateNewResultWithUpdatedOffset() {
            IncrementalInsertResult original = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, Arrays.asList(1L, 2L)
            );

            IncrementalInsertResult updated = original.withWriteLogOffset(2000L);

            assertThat(updated.nodeId()).isEqualTo(original.nodeId());
            assertThat(updated.level()).isEqualTo(original.level());
            assertThat(updated.dataOffset()).isEqualTo(original.dataOffset());
            assertThat(updated.connectionsMade()).isEqualTo(original.connectionsMade());
            assertThat(updated.writeLogOffset()).isEqualTo(2000L);
            assertThat(updated.connectedNodes()).isEqualTo(original.connectedNodes());
        }

        @Test
        @DisplayName("Should not modify original result")
        void shouldNotModifyOriginal() {
            IncrementalInsertResult original = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, Arrays.asList(1L, 2L)
            );

            original.withWriteLogOffset(2000L);

            assertThat(original.writeLogOffset()).isEqualTo(1000L);
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            List<Long> nodes = Arrays.asList(1L, 2L);
            IncrementalInsertResult result1 = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, nodes
            );
            IncrementalInsertResult result2 = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, nodes
            );

            assertThat(result1).isEqualTo(result2);
            assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
        }

        @Test
        @DisplayName("Should be same instance")
        void shouldBeSameInstance() {
            IncrementalInsertResult result = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, Arrays.asList(1L)
            );

            assertThat(result).isEqualTo(result);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void shouldNotBeEqualToNull() {
            IncrementalInsertResult result = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, Arrays.asList(1L)
            );

            assertThat(result).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Should not be equal to different type")
        void shouldNotBeEqualToDifferentType() {
            IncrementalInsertResult result = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, Arrays.asList(1L)
            );

            assertThat(result).isNotEqualTo("string");
        }

        @Test
        @DisplayName("Should not be equal for different nodeId")
        void shouldNotBeEqualForDifferentNodeId() {
            IncrementalInsertResult result1 = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, null
            );
            IncrementalInsertResult result2 = new IncrementalInsertResult(
                101L, 2, 5000L, 5, 1000L, null
            );

            assertThat(result1).isNotEqualTo(result2);
        }

        @Test
        @DisplayName("Should not be equal for different level")
        void shouldNotBeEqualForDifferentLevel() {
            IncrementalInsertResult result1 = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, null
            );
            IncrementalInsertResult result2 = new IncrementalInsertResult(
                100L, 3, 5000L, 5, 1000L, null
            );

            assertThat(result1).isNotEqualTo(result2);
        }

        @Test
        @DisplayName("Should not be equal for different dataOffset")
        void shouldNotBeEqualForDifferentDataOffset() {
            IncrementalInsertResult result1 = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, null
            );
            IncrementalInsertResult result2 = new IncrementalInsertResult(
                100L, 2, 6000L, 5, 1000L, null
            );

            assertThat(result1).isNotEqualTo(result2);
        }

        @Test
        @DisplayName("Should not be equal for different connectionsMade")
        void shouldNotBeEqualForDifferentConnectionsMade() {
            IncrementalInsertResult result1 = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, null
            );
            IncrementalInsertResult result2 = new IncrementalInsertResult(
                100L, 2, 5000L, 6, 1000L, null
            );

            assertThat(result1).isNotEqualTo(result2);
        }

        @Test
        @DisplayName("Should not be equal for different writeLogOffset")
        void shouldNotBeEqualForDifferentWriteLogOffset() {
            IncrementalInsertResult result1 = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, null
            );
            IncrementalInsertResult result2 = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 2000L, null
            );

            assertThat(result1).isNotEqualTo(result2);
        }

        @Test
        @DisplayName("Should not be equal for different connectedNodes")
        void shouldNotBeEqualForDifferentConnectedNodes() {
            IncrementalInsertResult result1 = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, Arrays.asList(1L)
            );
            IncrementalInsertResult result2 = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, Arrays.asList(2L)
            );

            assertThat(result1).isNotEqualTo(result2);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("Should contain all fields")
        void shouldContainAllFields() {
            IncrementalInsertResult result = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, Arrays.asList(1L, 2L)
            );

            String str = result.toString();

            assertThat(str).contains("100");
            assertThat(str).contains("level=2");
            assertThat(str).contains("5000");
            assertThat(str).contains("connections=5");
            assertThat(str).contains("1000");
        }

        @Test
        @DisplayName("FAILED toString should contain -1")
        void failedToStringShouldContainMinusOne() {
            String str = IncrementalInsertResult.FAILED.toString();

            assertThat(str).contains("-1");
        }
    }

    @Nested
    @DisplayName("Connected Nodes Immutability")
    class ConnectedNodesImmutabilityTests {

        @Test
        @DisplayName("Connected nodes list should be unmodifiable")
        void connectedNodesShouldBeUnmodifiable() {
            List<Long> originalList = Arrays.asList(1L, 2L, 3L);
            IncrementalInsertResult result = new IncrementalInsertResult(
                100L, 2, 5000L, 5, 1000L, originalList
            );

            assertThatThrownBy(() -> result.connectedNodes().add(4L))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
