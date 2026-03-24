package excalibur.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for QueryOptions domain correctness and validation.
 */
@DisplayName("QueryOptions")
class QueryOptionsTest {

    @Nested
    @DisplayName("Default values")
    class DefaultValues {

        @Test
        @DisplayName("defaults returns instance with default values")
        void defaultsReturnsCorrectValues() {
            QueryOptions options = QueryOptions.defaults();

            assertThat(options.topK()).isEqualTo(10);
            assertThat(options.threshold()).isEqualTo(0.0f);
            assertThat(options.includePartialSegments()).isTrue();
            assertThat(options.timeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(options.oversamplingFactor()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Builder validation")
    class BuilderValidation {

        @Test
        @DisplayName("rejects non-positive topK")
        void rejectsNonPositiveTopK() {
            assertThatThrownBy(() -> QueryOptions.builder().topK(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be positive");

            assertThatThrownBy(() -> QueryOptions.builder().topK(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be positive");
        }

        @Test
        @DisplayName("accepts positive topK")
        void acceptsPositiveTopK() {
            QueryOptions options = QueryOptions.builder().topK(100).build();
            assertThat(options.topK()).isEqualTo(100);
        }

        @Test
        @DisplayName("rejects null timeout")
        void rejectsNullTimeout() {
            assertThatThrownBy(() -> QueryOptions.builder().timeout(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Timeout cannot be null");
        }

        @Test
        @DisplayName("rejects negative timeout")
        void rejectsNegativeTimeout() {
            assertThatThrownBy(() -> QueryOptions.builder().timeout(Duration.ofMillis(-100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Timeout cannot be negative");
        }

        @Test
        @DisplayName("accepts zero timeout")
        void acceptsZeroTimeout() {
            QueryOptions options = QueryOptions.builder()
                .timeout(Duration.ZERO)
                .build();
            assertThat(options.timeout()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("rejects oversampling factor less than 1")
        void rejectsOversamplingFactorLessThanOne() {
            assertThatThrownBy(() -> QueryOptions.builder().oversamplingFactor(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Oversampling factor must be at least 1");

            assertThatThrownBy(() -> QueryOptions.builder().oversamplingFactor(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Oversampling factor must be at least 1");
        }

        @Test
        @DisplayName("accepts oversampling factor of 1")
        void acceptsOversamplingFactorOfOne() {
            QueryOptions options = QueryOptions.builder().oversamplingFactor(1).build();
            assertThat(options.oversamplingFactor()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Threshold handling")
    class ThresholdHandling {

        @Test
        @DisplayName("accepts threshold of 0.0")
        void acceptsZeroThreshold() {
            QueryOptions options = QueryOptions.builder().threshold(0.0f).build();
            assertThat(options.threshold()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("accepts threshold of 1.0")
        void acceptsOneThreshold() {
            QueryOptions options = QueryOptions.builder().threshold(1.0f).build();
            assertThat(options.threshold()).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("accepts threshold in valid range")
        void acceptsValidThreshold() {
            QueryOptions options = QueryOptions.builder().threshold(0.5f).build();
            assertThat(options.threshold()).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("rejects negative threshold")
        void rejectsNegativeThreshold() {
            // Domain correctness: threshold must be in [0.0, 1.0] since relevancy scores are in this range
            assertThatThrownBy(() -> QueryOptions.builder().threshold(-1.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Threshold must be in [0.0, 1.0]");
        }

        @Test
        @DisplayName("rejects threshold greater than 1.0")
        void rejectsThresholdGreaterThanOne() {
            // Domain correctness: threshold must be in [0.0, 1.0] since relevancy scores are in this range
            assertThatThrownBy(() -> QueryOptions.builder().threshold(5.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Threshold must be in [0.0, 1.0]");
        }

        @Test
        @DisplayName("rejects NaN threshold")
        void rejectsNaNThreshold() {
            assertThatThrownBy(() -> QueryOptions.builder().threshold(Float.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Threshold cannot be NaN");
        }
    }

    @Nested
    @DisplayName("toBuilder")
    class ToBuilder {

        @Test
        @DisplayName("toBuilder creates builder with same values")
        void toBuilderCreatesBuilderWithSameValues() {
            QueryOptions original = QueryOptions.builder()
                .topK(50)
                .threshold(0.7f)
                .includePartialSegments(false)
                .timeout(Duration.ofSeconds(60))
                .oversamplingFactor(4)
                .build();

            QueryOptions copy = original.toBuilder().build();

            assertThat(copy).isEqualTo(original);
            assertThat(copy.topK()).isEqualTo(50);
            assertThat(copy.threshold()).isEqualTo(0.7f);
            assertThat(copy.includePartialSegments()).isFalse();
            assertThat(copy.timeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(copy.oversamplingFactor()).isEqualTo(4);
        }

        @Test
        @DisplayName("toBuilder allows modification")
        void toBuilderAllowsModification() {
            QueryOptions original = QueryOptions.builder()
                .topK(10)
                .build();

            QueryOptions modified = original.toBuilder()
                .topK(20)
                .build();

            assertThat(original.topK()).isEqualTo(10);
            assertThat(modified.topK()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("Object methods")
    class ObjectMethods {

        @Test
        @DisplayName("equals compares all fields")
        void equalsComparesAllFields() {
            QueryOptions o1 = QueryOptions.builder()
                .topK(20)
                .threshold(0.5f)
                .includePartialSegments(false)
                .timeout(Duration.ofSeconds(10))
                .oversamplingFactor(3)
                .build();

            QueryOptions o2 = QueryOptions.builder()
                .topK(20)
                .threshold(0.5f)
                .includePartialSegments(false)
                .timeout(Duration.ofSeconds(10))
                .oversamplingFactor(3)
                .build();

            assertThat(o1).isEqualTo(o2);
            assertThat(o1.hashCode()).isEqualTo(o2.hashCode());
        }

        @Test
        @DisplayName("equals returns false for different topK")
        void equalsReturnsFalseForDifferentTopK() {
            QueryOptions o1 = QueryOptions.builder().topK(10).build();
            QueryOptions o2 = QueryOptions.builder().topK(20).build();

            assertThat(o1).isNotEqualTo(o2);
        }

        @Test
        @DisplayName("equals returns false for different includePartialSegments")
        void equalsReturnsFalseForDifferentIncludePartial() {
            QueryOptions o1 = QueryOptions.builder().includePartialSegments(true).build();
            QueryOptions o2 = QueryOptions.builder().includePartialSegments(false).build();

            assertThat(o1).isNotEqualTo(o2);
        }

        @Test
        @DisplayName("toString contains all fields")
        void toStringContainsAllFields() {
            QueryOptions options = QueryOptions.builder()
                .topK(25)
                .threshold(0.75f)
                .includePartialSegments(false)
                .timeout(Duration.ofSeconds(45))
                .oversamplingFactor(5)
                .build();

            String str = options.toString();

            assertThat(str).contains("topK=25");
            assertThat(str).contains("threshold=0.750");
            assertThat(str).contains("includePartial=false");
            assertThat(str).contains("oversampling=5");
        }
    }
}
