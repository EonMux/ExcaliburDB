package excalibur.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for QueryRequest domain correctness and validation.
 */
@DisplayName("QueryRequest")
class QueryRequestTest {

    private static float[] createVector(int dim) {
        float[] vec = new float[dim];
        for (int i = 0; i < dim; i++) {
            vec[i] = (float) Math.random();
        }
        return vec;
    }

    @Nested
    @DisplayName("Builder validation")
    class BuilderValidation {

        @Test
        @DisplayName("builds with valid required parameters")
        void buildsWithValidParameters() {
            float[] vector = createVector(128);

            QueryRequest request = QueryRequest.builder()
                .queryVector(vector)
                .build();

            assertThat(request).isNotNull();
            assertThat(request.dimension()).isEqualTo(128);
            assertThat(request.topK()).isEqualTo(10); // default
        }

        @Test
        @DisplayName("rejects null query vector")
        void rejectsNullQueryVector() {
            assertThatThrownBy(() -> QueryRequest.builder()
                    .queryVector(null)
                    .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector cannot be null");
        }

        @Test
        @DisplayName("rejects empty query vector")
        void rejectsEmptyQueryVector() {
            assertThatThrownBy(() -> QueryRequest.builder()
                    .queryVector(new float[0])
                    .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector cannot be null or empty");
        }

        @Test
        @DisplayName("rejects non-positive topK")
        void rejectsNonPositiveTopK() {
            assertThatThrownBy(() -> QueryRequest.builder()
                    .queryVector(createVector(10))
                    .topK(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be positive");

            assertThatThrownBy(() -> QueryRequest.builder()
                    .queryVector(createVector(10))
                    .topK(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK must be positive");
        }

        @Test
        @DisplayName("rejects recallTarget outside [0.0, 1.0]")
        void rejectsInvalidRecallTarget() {
            assertThatThrownBy(() -> QueryRequest.builder()
                    .queryVector(createVector(10))
                    .recallTarget(-0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recallTarget must be in [0.0, 1.0]");

            assertThatThrownBy(() -> QueryRequest.builder()
                    .queryVector(createVector(10))
                    .recallTarget(1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recallTarget must be in [0.0, 1.0]");
        }

        @Test
        @DisplayName("accepts recallTarget at boundaries")
        void acceptsBoundaryRecallTarget() {
            QueryRequest r1 = QueryRequest.builder()
                .queryVector(createVector(10))
                .recallTarget(0.0)
                .build();
            assertThat(r1.recallTarget()).isEqualTo(0.0);

            QueryRequest r2 = QueryRequest.builder()
                .queryVector(createVector(10))
                .recallTarget(1.0)
                .build();
            assertThat(r2.recallTarget()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("rejects non-positive timeoutMs")
        void rejectsNonPositiveTimeoutMs() {
            assertThatThrownBy(() -> QueryRequest.builder()
                    .queryVector(createVector(10))
                    .timeoutMs(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs must be positive");

            assertThatThrownBy(() -> QueryRequest.builder()
                    .queryVector(createVector(10))
                    .timeoutMs(-100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs must be positive");
        }

        @Test
        @DisplayName("rejects null timeout duration")
        void rejectsNullTimeoutDuration() {
            assertThatThrownBy(() -> QueryRequest.builder()
                    .queryVector(createVector(10))
                    .timeout(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Timeout cannot be null");
        }

        @Test
        @DisplayName("accepts timeout via Duration")
        void acceptsTimeoutViaDuration() {
            QueryRequest request = QueryRequest.builder()
                .queryVector(createVector(10))
                .timeout(Duration.ofSeconds(30))
                .build();

            assertThat(request.timeoutMs()).isEqualTo(30_000);
            assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(30));
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("of(float[]) creates request with defaults")
        void ofVectorCreatesWithDefaults() {
            float[] vector = createVector(64);

            QueryRequest request = QueryRequest.of(vector);

            assertThat(request.dimension()).isEqualTo(64);
            assertThat(request.queryText()).isNull();
            assertThat(request.topK()).isEqualTo(10);
        }

        @Test
        @DisplayName("of(String, float[]) creates request with text and vector")
        void ofTextAndVectorCreatesRequest() {
            float[] vector = createVector(64);

            QueryRequest request = QueryRequest.of("search query", vector);

            assertThat(request.queryText()).isEqualTo("search query");
            assertThat(request.dimension()).isEqualTo(64);
        }
    }

    @Nested
    @DisplayName("Vector handling")
    class VectorHandling {

        @Test
        @DisplayName("queryVector returns defensive copy")
        void queryVectorReturnsDefensiveCopy() {
            float[] original = createVector(10);
            QueryRequest request = QueryRequest.builder()
                .queryVector(original)
                .build();

            float[] copy = request.queryVector();

            // Modify the copy
            copy[0] = 999.0f;

            // Original in request should be unchanged
            assertThat(request.queryVectorUnsafe()[0]).isNotEqualTo(999.0f);
        }

        @Test
        @DisplayName("queryVectorUnsafe returns internal array")
        void queryVectorUnsafeReturnsInternalArray() {
            float[] original = createVector(10);
            QueryRequest request = QueryRequest.builder()
                .queryVector(original)
                .build();

            assertThat(request.queryVectorUnsafe()).isSameAs(request.queryVectorUnsafe());
        }

        @Test
        @DisplayName("builder copies vector defensively")
        void builderCopiesVectorDefensively() {
            float[] original = createVector(10);
            QueryRequest request = QueryRequest.builder()
                .queryVector(original)
                .build();

            // Modify original after building
            original[0] = 999.0f;

            // Request should have unchanged copy
            assertThat(request.queryVectorUnsafe()[0]).isNotEqualTo(999.0f);
        }
    }

    @Nested
    @DisplayName("Accessors")
    class Accessors {

        @Test
        @DisplayName("returns all configured values")
        void returnsAllConfiguredValues() {
            float[] vector = createVector(128);

            QueryRequest request = QueryRequest.builder()
                .queryText("test query")
                .queryVector(vector)
                .topK(20)
                .recallTarget(0.95)
                .timeoutMs(5000)
                .includePartialSegments(false)
                .threshold(0.5f)
                .enableLlmScoring(false)
                .llmThreshold(0.4f)
                .correlationId("corr-123")
                .build();

            assertThat(request.queryText()).isEqualTo("test query");
            assertThat(request.dimension()).isEqualTo(128);
            assertThat(request.topK()).isEqualTo(20);
            assertThat(request.recallTarget()).isEqualTo(0.95);
            assertThat(request.timeoutMs()).isEqualTo(5000);
            assertThat(request.includePartialSegments()).isFalse();
            assertThat(request.threshold()).isEqualTo(0.5f);
            assertThat(request.enableLlmScoring()).isFalse();
            assertThat(request.llmThreshold()).isEqualTo(0.4f);
            assertThat(request.correlationId()).isEqualTo("corr-123");
        }
    }

    @Nested
    @DisplayName("Object methods")
    class ObjectMethods {

        @Test
        @DisplayName("equals compares all fields")
        void equalsComparesAllFields() {
            float[] vector = createVector(10);

            QueryRequest r1 = QueryRequest.builder()
                .queryVector(vector)
                .topK(10)
                .build();

            QueryRequest r2 = QueryRequest.builder()
                .queryVector(vector.clone())
                .topK(10)
                .build();

            assertThat(r1).isEqualTo(r2);
            assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        }

        @Test
        @DisplayName("equals returns false for different values")
        void equalsReturnsFalseForDifferentValues() {
            QueryRequest r1 = QueryRequest.builder()
                .queryVector(createVector(10))
                .topK(10)
                .build();

            QueryRequest r2 = QueryRequest.builder()
                .queryVector(createVector(10))
                .topK(20)
                .build();

            assertThat(r1).isNotEqualTo(r2);
        }

        @Test
        @DisplayName("toString contains key information")
        void toStringContainsKeyInformation() {
            QueryRequest request = QueryRequest.builder()
                .queryVector(createVector(128))
                .topK(15)
                .timeoutMs(5000)
                .build();

            String str = request.toString();

            assertThat(str).contains("dim=128");
            assertThat(str).contains("topK=15");
            assertThat(str).contains("timeout=5000ms");
        }
    }

    @Nested
    @DisplayName("toQueryOptions conversion")
    class ToQueryOptionsConversion {

        @Test
        @DisplayName("converts to QueryOptions with equivalent settings")
        void convertsToQueryOptions() {
            QueryRequest request = QueryRequest.builder()
                .queryVector(createVector(10))
                .topK(25)
                .threshold(0.6f)
                .includePartialSegments(false)
                .timeoutMs(10000)
                .build();

            QueryOptions options = request.toQueryOptions();

            assertThat(options.topK()).isEqualTo(25);
            assertThat(options.threshold()).isEqualTo(0.6f);
            assertThat(options.includePartialSegments()).isFalse();
        }
    }
}
