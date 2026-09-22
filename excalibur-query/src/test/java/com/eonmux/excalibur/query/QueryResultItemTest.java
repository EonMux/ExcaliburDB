package com.eonmux.excalibur.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for QueryResultItem domain correctness and validation.
 */
@DisplayName("QueryResultItem")
class QueryResultItemTest {

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("creates item with all fields")
        void createsItemWithAllFields() {
            byte[] payload = new byte[]{1, 2, 3, 4};

            QueryResultItem item = new QueryResultItem(
                12345L, 0.95f, payload, 100L, 5L);

            assertThat(item.documentId()).isEqualTo(12345L);
            assertThat(item.score()).isEqualTo(0.95f);
            assertThat(item.segmentId()).isEqualTo(100L);
            assertThat(item.nodeId()).isEqualTo(5L);
            assertThat(item.hasPayload()).isTrue();
        }

        @Test
        @DisplayName("creates item with minimal fields")
        void createsItemWithMinimalFields() {
            QueryResultItem item = new QueryResultItem(12345L, 0.95f);

            assertThat(item.documentId()).isEqualTo(12345L);
            assertThat(item.score()).isEqualTo(0.95f);
            assertThat(item.segmentId()).isEqualTo(-1);
            assertThat(item.nodeId()).isEqualTo(-1);
            assertThat(item.hasPayload()).isFalse();
        }

        @Test
        @DisplayName("handles null payload")
        void handlesNullPayload() {
            QueryResultItem item = new QueryResultItem(
                12345L, 0.95f, null, 100L, 5L);

            assertThat(item.hasPayload()).isFalse();
            assertThat(item.payload()).isNull();
        }

        @Test
        @DisplayName("handles empty payload")
        void handlesEmptyPayload() {
            QueryResultItem item = new QueryResultItem(
                12345L, 0.95f, new byte[0], 100L, 5L);

            assertThat(item.hasPayload()).isFalse();
        }
    }

    @Nested
    @DisplayName("Payload handling")
    class PayloadHandling {

        @Test
        @DisplayName("payload returns defensive copy")
        void payloadReturnsDefensiveCopy() {
            byte[] originalPayload = new byte[]{1, 2, 3, 4};
            QueryResultItem item = new QueryResultItem(
                1L, 0.9f, originalPayload, 1L, 1L);

            byte[] copy1 = item.payload();
            byte[] copy2 = item.payload();

            // Each call returns a new copy
            assertThat(copy1).isNotSameAs(copy2);
            assertThat(copy1).isEqualTo(copy2);

            // Modifying copy doesn't affect item
            copy1[0] = 99;
            assertThat(item.payload()[0]).isEqualTo((byte) 1);
        }

        @Test
        @DisplayName("constructor copies payload defensively")
        void constructorCopiesPayloadDefensively() {
            byte[] originalPayload = new byte[]{1, 2, 3, 4};
            QueryResultItem item = new QueryResultItem(
                1L, 0.9f, originalPayload, 1L, 1L);

            // Modify original after construction
            originalPayload[0] = 99;

            // Item should have original value
            assertThat(item.payload()[0]).isEqualTo((byte) 1);
        }
    }

    @Nested
    @DisplayName("With methods")
    class WithMethods {

        @Test
        @DisplayName("withScore creates new item with different score")
        void withScoreCreatesNewItem() {
            QueryResultItem original = new QueryResultItem(
                1L, 0.9f, new byte[]{1, 2}, 10L, 5L);

            QueryResultItem updated = original.withScore(0.5f);

            assertThat(updated.score()).isEqualTo(0.5f);
            assertThat(updated.documentId()).isEqualTo(1L);
            assertThat(updated.segmentId()).isEqualTo(10L);

            // Original unchanged
            assertThat(original.score()).isEqualTo(0.9f);
        }

        @Test
        @DisplayName("withPayload creates new item with different payload")
        void withPayloadCreatesNewItem() {
            QueryResultItem original = new QueryResultItem(
                1L, 0.9f, new byte[]{1, 2}, 10L, 5L);

            QueryResultItem updated = original.withPayload(new byte[]{9, 8, 7});

            assertThat(updated.hasPayload()).isTrue();
            assertThat(updated.payload()).containsExactly((byte) 9, (byte) 8, (byte) 7);

            // Original unchanged
            assertThat(original.payload()).containsExactly((byte) 1, (byte) 2);
        }
    }

    @Nested
    @DisplayName("Score domain correctness")
    class ScoreDomainCorrectness {

        @Test
        @DisplayName("accepts score of 0.0")
        void acceptsZeroScore() {
            QueryResultItem item = new QueryResultItem(1L, 0.0f);
            assertThat(item.score()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("accepts score of 1.0")
        void acceptsOneScore() {
            QueryResultItem item = new QueryResultItem(1L, 1.0f);
            assertThat(item.score()).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("accepts negative scores (for distance metrics)")
        void acceptsNegativeScores() {
            // L2 distance can produce values that appear negative after normalization
            // or when used with certain distance functions
            QueryResultItem item = new QueryResultItem(1L, -0.5f);
            assertThat(item.score()).isEqualTo(-0.5f);
        }

        @Test
        @DisplayName("accepts large positive scores")
        void acceptsLargePositiveScores() {
            // Inner product can produce large values
            QueryResultItem item = new QueryResultItem(1L, 1000.0f);
            assertThat(item.score()).isEqualTo(1000.0f);
        }

        @Test
        @DisplayName("rejects NaN score in constructor")
        void rejectsNanScore() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new QueryResultItem(1L, Float.NaN))
                .withMessageContaining("Score must be finite");
        }

        @Test
        @DisplayName("rejects positive infinity score in constructor")
        void rejectsPositiveInfinityScore() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new QueryResultItem(1L, Float.POSITIVE_INFINITY))
                .withMessageContaining("Score must be finite");
        }

        @Test
        @DisplayName("rejects negative infinity score in constructor")
        void rejectsNegativeInfinityScore() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new QueryResultItem(1L, Float.NEGATIVE_INFINITY))
                .withMessageContaining("Score must be finite");
        }

        @Test
        @DisplayName("rejects NaN score in string document ID constructor")
        void rejectsNanScoreStringDocId() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new QueryResultItem("doc1", Float.NaN, null, 1L, 1L))
                .withMessageContaining("Score must be finite");
        }

        @Test
        @DisplayName("rejects NaN score in full constructor")
        void rejectsNanScoreFullConstructor() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new QueryResultItem(1L, "doc1", Float.NaN, null, 1L, 1L))
                .withMessageContaining("Score must be finite");
        }

        @Test
        @DisplayName("rejects NaN score in withScore method")
        void rejectsNanScoreInWithScore() {
            QueryResultItem original = new QueryResultItem(1L, 0.5f);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> original.withScore(Float.NaN))
                .withMessageContaining("Score must be finite");
        }

        @Test
        @DisplayName("rejects infinity score in withScore method")
        void rejectsInfinityScoreInWithScore() {
            QueryResultItem original = new QueryResultItem(1L, 0.5f);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> original.withScore(Float.POSITIVE_INFINITY))
                .withMessageContaining("Score must be finite");
        }
    }

    @Nested
    @DisplayName("Object methods")
    class ObjectMethods {

        @Test
        @DisplayName("equals compares all fields including payload")
        void equalsComparesAllFields() {
            byte[] payload = new byte[]{1, 2, 3};

            QueryResultItem i1 = new QueryResultItem(1L, 0.9f, payload, 10L, 5L);
            QueryResultItem i2 = new QueryResultItem(1L, 0.9f, new byte[]{1, 2, 3}, 10L, 5L);

            assertThat(i1).isEqualTo(i2);
            assertThat(i1.hashCode()).isEqualTo(i2.hashCode());
        }

        @Test
        @DisplayName("equals returns false for different documentId")
        void equalsReturnsFalseForDifferentDocumentId() {
            QueryResultItem i1 = new QueryResultItem(1L, 0.9f);
            QueryResultItem i2 = new QueryResultItem(2L, 0.9f);

            assertThat(i1).isNotEqualTo(i2);
        }

        @Test
        @DisplayName("equals returns false for different score")
        void equalsReturnsFalseForDifferentScore() {
            QueryResultItem i1 = new QueryResultItem(1L, 0.9f);
            QueryResultItem i2 = new QueryResultItem(1L, 0.8f);

            assertThat(i1).isNotEqualTo(i2);
        }

        @Test
        @DisplayName("equals returns false for different payload")
        void equalsReturnsFalseForDifferentPayload() {
            QueryResultItem i1 = new QueryResultItem(1L, 0.9f, new byte[]{1}, 1L, 1L);
            QueryResultItem i2 = new QueryResultItem(1L, 0.9f, new byte[]{2}, 1L, 1L);

            assertThat(i1).isNotEqualTo(i2);
        }

        @Test
        @DisplayName("toString contains key information")
        void toStringContainsKeyInformation() {
            QueryResultItem item = new QueryResultItem(
                12345L, 0.95f, new byte[]{1, 2, 3}, 100L, 5L);

            String str = item.toString();

            assertThat(str).contains("docId=12345");
            assertThat(str).contains("segmentId=100");
            assertThat(str).contains("nodeId=5");
            assertThat(str).contains("hasPayload=true");
        }
    }
}
