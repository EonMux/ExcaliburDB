package excalibur.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for QueryResponse domain correctness and validation.
 */
@DisplayName("QueryResponse")
class QueryResponseTest {

    private static IndexCoverage createCoverage() {
        return IndexCoverage.builder()
            .totalSegments(10)
            .activeSegments(8)
            .estimatedCorpusCoverage(0.8)
            .build();
    }

    private static QueryResultItem createResult(long docId, float score) {
        return new QueryResultItem(docId, score);
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("rejects null results")
        void rejectsNullResults() {
            assertThatThrownBy(() -> new QueryResponse(null, createCoverage(), 100))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Results cannot be null");
        }

        @Test
        @DisplayName("rejects null coverage")
        void rejectsNullCoverage() {
            List<QueryResultItem> results = List.of(createResult(1L, 0.9f));

            assertThatThrownBy(() -> new QueryResponse(results, null, 100))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Coverage cannot be null");
        }

        @Test
        @DisplayName("accepts empty results")
        void acceptsEmptyResults() {
            QueryResponse response = new QueryResponse(
                Collections.emptyList(), createCoverage(), 100);

            assertThat(response.results()).isEmpty();
            assertThat(response.isEmpty()).isTrue();
            assertThat(response.resultCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Result access")
    class ResultAccess {

        @Test
        @DisplayName("returns immutable results list")
        void returnsImmutableResultsList() {
            List<QueryResultItem> results = Arrays.asList(
                createResult(1L, 0.9f),
                createResult(2L, 0.8f)
            );

            QueryResponse response = new QueryResponse(results, createCoverage(), 100);

            assertThatThrownBy(() -> response.results().add(createResult(3L, 0.7f)))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("topResult returns first result")
        void topResultReturnsFirstResult() {
            List<QueryResultItem> results = Arrays.asList(
                createResult(1L, 0.9f),
                createResult(2L, 0.8f)
            );

            QueryResponse response = new QueryResponse(results, createCoverage(), 100);

            assertThat(response.topResult()).isNotNull();
            assertThat(response.topResult().documentId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("topResult returns null for empty results")
        void topResultReturnsNullForEmpty() {
            QueryResponse response = new QueryResponse(
                Collections.emptyList(), createCoverage(), 100);

            assertThat(response.topResult()).isNull();
        }
    }

    @Nested
    @DisplayName("Degraded state")
    class DegradedState {

        @Test
        @DisplayName("isDegraded returns true when coverage is degraded")
        void isDegradedWhenCoverageIsDegraded() {
            IndexCoverage degradedCoverage = IndexCoverage.builder()
                .totalSegments(10)
                .activeSegments(3)
                .estimatedCorpusCoverage(0.3)
                .degraded(true)
                .build();

            QueryResponse response = new QueryResponse(
                Collections.emptyList(), degradedCoverage, 100);

            assertThat(response.isDegraded()).isTrue();
        }

        @Test
        @DisplayName("isDegraded returns true when timed out")
        void isDegradedWhenTimedOut() {
            QueryResponse response = new QueryResponse(
                Collections.emptyList(), createCoverage(), 100, false, true);

            assertThat(response.isDegraded()).isTrue();
            assertThat(response.timedOut()).isTrue();
        }

        @Test
        @DisplayName("isDegraded returns false when healthy")
        void isNotDegradedWhenHealthy() {
            IndexCoverage healthyCoverage = IndexCoverage.builder()
                .totalSegments(10)
                .activeSegments(10)
                .estimatedCorpusCoverage(1.0)
                .degraded(false)
                .build();

            QueryResponse response = new QueryResponse(
                Collections.emptyList(), healthyCoverage, 100, false, false);

            assertThat(response.isDegraded()).isFalse();
            assertThat(response.timedOut()).isFalse();
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("empty creates empty response")
        void emptyCreatesEmptyResponse() {
            QueryResponse response = QueryResponse.empty(createCoverage(), 50);

            assertThat(response.results()).isEmpty();
            assertThat(response.isEmpty()).isTrue();
            assertThat(response.queryTimeMs()).isEqualTo(50);
            assertThat(response.fromCache()).isFalse();
        }
    }

    @Nested
    @DisplayName("With methods")
    class WithMethods {

        @Test
        @DisplayName("withFromCache creates new instance with cache flag")
        void withFromCacheCreatesNewInstance() {
            QueryResponse original = new QueryResponse(
                Collections.emptyList(), createCoverage(), 100, false, false);

            QueryResponse cached = original.withFromCache();

            assertThat(cached.fromCache()).isTrue();
            assertThat(original.fromCache()).isFalse(); // Original unchanged
        }

        @Test
        @DisplayName("withQueryTimeMs creates new instance with new time")
        void withQueryTimeMsCreatesNewInstance() {
            QueryResponse original = new QueryResponse(
                Collections.emptyList(), createCoverage(), 100, false, false);

            QueryResponse updated = original.withQueryTimeMs(200);

            assertThat(updated.queryTimeMs()).isEqualTo(200);
            assertThat(original.queryTimeMs()).isEqualTo(100); // Original unchanged
        }

        @Test
        @DisplayName("withTimedOut creates new instance with timedOut flag")
        void withTimedOutCreatesNewInstance() {
            QueryResponse original = new QueryResponse(
                Collections.emptyList(), createCoverage(), 100, false, false);

            QueryResponse timedOut = original.withTimedOut(true);

            assertThat(timedOut.timedOut()).isTrue();
            assertThat(timedOut.isDegraded()).isTrue();
            assertThat(original.timedOut()).isFalse(); // Original unchanged
        }
    }

    @Nested
    @DisplayName("Object methods")
    class ObjectMethods {

        @Test
        @DisplayName("equals compares all fields")
        void equalsComparesAllFields() {
            List<QueryResultItem> results = List.of(createResult(1L, 0.9f));
            IndexCoverage coverage = createCoverage();

            QueryResponse r1 = new QueryResponse(results, coverage, 100, true, false);
            QueryResponse r2 = new QueryResponse(results, coverage, 100, true, false);

            assertThat(r1).isEqualTo(r2);
            assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        }

        @Test
        @DisplayName("equals returns false for different values")
        void equalsReturnsFalseForDifferentValues() {
            QueryResponse r1 = new QueryResponse(
                Collections.emptyList(), createCoverage(), 100, false, false);
            QueryResponse r2 = new QueryResponse(
                Collections.emptyList(), createCoverage(), 200, false, false);

            assertThat(r1).isNotEqualTo(r2);
        }

        @Test
        @DisplayName("toString contains key information")
        void toStringContainsKeyInformation() {
            QueryResponse response = new QueryResponse(
                List.of(createResult(1L, 0.9f)),
                createCoverage(),
                150,
                true,
                false
            );

            String str = response.toString();

            assertThat(str).contains("results=1");
            assertThat(str).contains("time=150");
            assertThat(str).contains("cached=true");
        }
    }
}
