package io.excalibur.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Equality for the values a golden test compares.
 *
 * <p>These classes exist to be compared: a golden test asserts that what the engine returned
 * equals what was recorded, and a failure is read as a regression. That makes {@code equals} the
 * assertion itself rather than a convenience. Two failure modes matter and neither shows up as an
 * error - an {@code equals} that is too strict reports regressions that are not there until the
 * suite is ignored, and one that is too loose passes a genuine change through. Both are pinned
 * below, along with the hashCode contract, because these values are collected into sets and maps
 * while differences are computed.</p>
 */
@DisplayName("Golden-test value semantics")
class ValueSemanticsTest {

    private static QueryResultItem item(String id, float score) {
        return new QueryResultItem(id, score);
    }

    @Nested
    @DisplayName("QueryResultItem")
    class Items {

        @Test
        @DisplayName("two items with the same fields are equal and hash alike")
        void equalItemsHashAlike() {
            QueryResultItem a = new QueryResultItem("doc1", 0.5f, Map.of("k", "v"));
            QueryResultItem b = new QueryResultItem("doc1", 0.5f, Map.of("k", "v"));

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isEqualTo(a);
        }

        @Test
        @DisplayName("a different document or score is a different item")
        void differencesAreDetected() {
            QueryResultItem base = item("doc1", 0.5f);

            assertThat(base).isNotEqualTo(item("doc2", 0.5f));
            assertThat(base).isNotEqualTo(item("doc1", 0.6f));
            assertThat(base).isNotEqualTo(null);
            assertThat(base).isNotEqualTo("doc1");
        }

        @Test
        @DisplayName("matches accepts a score within tolerance and rejects one outside it")
        void matchesRespectsTolerance() {
            // Scores are floats produced by a distance computation; requiring exact equality
            // would fail the suite on a rounding difference, and an unbounded tolerance would
            // stop it noticing a ranking change.
            QueryResultItem recorded = item("doc1", 0.500f);

            assertThat(recorded.matches(item("doc1", 0.5004f), 0.001)).isTrue();
            assertThat(recorded.matches(item("doc1", 0.510f), 0.001)).isFalse();
            assertThat(recorded.matches(item("doc1", 0.500f), 0.0)).isTrue();
        }

        @Test
        @DisplayName("matches compares identity, not only the score")
        void matchesComparesIdentity() {
            QueryResultItem recorded = new QueryResultItem(
                "doc1", 0.5f, Map.of(), "text", "src", 2);

            assertThat(recorded.matches(
                new QueryResultItem("doc2", 0.5f, Map.of(), "text", "src", 2), 0.1)).isFalse();
            assertThat(recorded.matches(
                new QueryResultItem("doc1", 0.5f, Map.of(), "text", "other", 2), 0.1)).isFalse();
            assertThat(recorded.matches(
                new QueryResultItem("doc1", 0.5f, Map.of(), "text", "src", 3), 0.1)).isFalse();
            assertThat(recorded.matches(
                new QueryResultItem("doc1", 0.5f, Map.of(), "changed", "src", 2), 0.1)).isFalse();
        }

        @Test
        @DisplayName("matching against nothing is false, not an error")
        void matchesNullIsFalse() {
            assertThat(item("doc1", 0.5f).matches(null, 0.1)).isFalse();
        }

        @Test
        @DisplayName("an item survives being put in a set")
        void itemsWorkInSets() {
            Set<QueryResultItem> set = new HashSet<>();
            set.add(item("doc1", 0.5f));
            set.add(item("doc1", 0.5f));
            set.add(item("doc2", 0.5f));

            assertThat(set).hasSize(2);
        }

        @Test
        @DisplayName("the accessors report what was built")
        void accessorsReportTheFields() {
            QueryResultItem full = new QueryResultItem(
                "doc1", 0.75f, Map.of("a", 1), "body", "source-a", 4);

            assertThat(full.getDocumentId()).isEqualTo("doc1");
            assertThat(full.getScore()).isEqualTo(0.75f);
            assertThat(full.getMetadata()).containsEntry("a", 1);
            assertThat(full.getContent()).isEqualTo("body");
            assertThat(full.getSource()).isEqualTo("source-a");
            assertThat(full.getChunkIndex()).isEqualTo(4);
            assertThat(full.toString()).contains("doc1");
        }

        @Test
        @DisplayName("an item built without content reports no chunk index")
        void minimalItemsHaveNoChunkIndex() {
            assertThat(item("doc1", 0.5f).getChunkIndex())
                .as("-1 means not applicable, which is distinct from chunk 0")
                .isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("PartialResult")
    class Partials {

        @Test
        @DisplayName("equal partials hash alike")
        void equalPartialsHashAlike() {
            PartialResult a = PartialResult.withPartialIds(10, Set.of("a", "b"));
            PartialResult b = PartialResult.withPartialIds(10, Set.of("b", "a"));

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("a different total or id set is a different partial")
        void differencesAreDetected() {
            PartialResult base = PartialResult.withPartialIds(10, Set.of("a"));

            assertThat(base).isNotEqualTo(PartialResult.withPartialIds(11, Set.of("a")));
            assertThat(base).isNotEqualTo(PartialResult.withPartialIds(10, Set.of("b")));
            assertThat(base).isNotEqualTo(null);
            assertThat(base).isNotEqualTo("partial");
        }

        @Test
        @DisplayName("the factories describe distinct situations")
        void factoriesDiffer() {
            assertThat(PartialResult.empty().isEmpty()).isTrue();
            assertThat(PartialResult.empty().getTotalCount()).isZero();

            PartialResult topKOnly = PartialResult.onlyTopK(50);
            assertThat(topKOnly.getTotalCount()).isEqualTo(50);
            assertThat(topKOnly.getPartialIds()).isEmpty();

            PartialResult withIds = PartialResult.withPartialIds(50, Set.of("x"));
            assertThat(withIds.getPartialIds()).containsExactly("x");
            assertThat(withIds).isNotEqualTo(topKOnly);
            assertThat(withIds.toString()).contains("50");
        }
    }

    @Nested
    @DisplayName("QueryResult and ExpectedResult")
    class Results {

        private QueryResult result(String queryId, float score) {
            return new QueryResult(queryId, List.of(item("doc1", score)),
                PartialResult.onlyTopK(1), Map.of(), null);
        }

        @Test
        @DisplayName("equal results hash alike")
        void equalResultsHashAlike() {
            assertThat(result("q1", 0.5f))
                .isEqualTo(result("q1", 0.5f))
                .hasSameHashCodeAs(result("q1", 0.5f));
        }

        @Test
        @DisplayName("a different query id or ranking is a different result")
        void differencesAreDetected() {
            QueryResult base = result("q1", 0.5f);

            assertThat(base).isNotEqualTo(result("q2", 0.5f));
            assertThat(base).isNotEqualTo(result("q1", 0.9f));
            assertThat(base).isNotEqualTo(null);
            assertThat(base).isNotEqualTo("q1");
            assertThat(base).isEqualTo(base);
        }

        @Test
        @DisplayName("ranking order is part of the result, not incidental")
        void orderMatters() {
            // Two results holding the same documents in a different order are a ranking
            // regression, which is exactly what a golden test is for.
            QueryResult ascending = new QueryResult("q", List.of(item("a", 0.9f), item("b", 0.1f)),
                PartialResult.empty(), Map.of(), null);
            QueryResult descending = new QueryResult("q", List.of(item("b", 0.1f), item("a", 0.9f)),
                PartialResult.empty(), Map.of(), null);

            assertThat(ascending).isNotEqualTo(descending);
        }

        @Test
        @DisplayName("the accessors report what was built")
        void accessorsReportTheFields() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("took_ms", 12);
            QueryResult result = new QueryResult("q1", List.of(item("doc1", 0.5f)),
                PartialResult.onlyTopK(3), metadata, null);

            assertThat(result.getQueryId()).isEqualTo("q1");
            assertThat(result.getTopK()).hasSize(1);
            assertThat(result.getPartial().getTotalCount()).isEqualTo(3);
            assertThat(result.getMetadata()).containsEntry("took_ms", 12);
            assertThat(result.getIndexCoverage())
                .as("an absent coverage becomes a degraded one, never null: a comparison that "
                    + "read it as null would either throw or silently skip the check")
                .isNotNull();
            assertThat(result.getIndexCoverage().isDegraded())
                .as("unknown coverage is reported as degraded rather than as complete")
                .isTrue();
            assertThat(result.toString()).contains("q1");
        }

        @Test
        @DisplayName("an expected result compares like the result it is compared against")
        void expectedResultsHaveValueSemantics() {
            ExpectedResult a = new ExpectedResult("q1", List.of(item("doc1", 0.5f)),
                PartialResult.onlyTopK(1), Map.of(), null);
            ExpectedResult b = new ExpectedResult("q1", List.of(item("doc1", 0.5f)),
                PartialResult.onlyTopK(1), Map.of(), null);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(new ExpectedResult("q2", List.of(item("doc1", 0.5f)),
                PartialResult.onlyTopK(1), Map.of(), null));
            assertThat(a).isNotEqualTo(null);
            assertThat(a).isNotEqualTo("q1");
            assertThat(a.getQueryId()).isEqualTo("q1");
            assertThat(a.toString()).contains("q1");
        }

        @Test
        @DisplayName("an expected result carries the tolerance its comparison uses")
        void expectedResultCarriesItsEpsilon() {
            ExpectedResult withEpsilon = new ExpectedResult("q1", List.of(item("doc1", 0.5f)),
                PartialResult.onlyTopK(1), Map.of(), null, 0.01);

            assertThat(withEpsilon.getEpsilon()).isEqualTo(0.01);
            assertThat(withEpsilon.getTopK()).hasSize(1);
            assertThat(withEpsilon.getPartial()).isNotNull();
            assertThat(withEpsilon.getMetadata()).isEmpty();
            assertThat(withEpsilon.getIndexCoverage()).isNotNull();
        }
    }

    @Nested
    @DisplayName("FailureCategory")
    class Failures {

        private FailureCategory.Builder base() {
            return FailureCategory.builder()
                .testName("SomeTest.method")
                .severity(FailureSeverity.CRITICAL)
                .category("correctness");
        }

        @Test
        @DisplayName("equal categories hash alike")
        void equalCategoriesHashAlike() {
            FailureCategory a = base().errorMessage("boom").build();
            FailureCategory b = base().errorMessage("boom").build();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isEqualTo(a);
        }

        @Test
        @DisplayName("a different test, severity, category or message is a different failure")
        void differencesAreDetected() {
            FailureCategory a = base().errorMessage("boom").build();

            assertThat(a).isNotEqualTo(base().errorMessage("other").build());
            assertThat(a).isNotEqualTo(
                base().severity(FailureSeverity.MINOR).errorMessage("boom").build());
            assertThat(a).isNotEqualTo(
                base().category("flake").errorMessage("boom").build());
            assertThat(a).isNotEqualTo(null);
            assertThat(a).isNotEqualTo("failure");
        }

        @Test
        @DisplayName("severity is reported through its predicates as well as its value")
        void severityPredicates() {
            assertThat(base().severity(FailureSeverity.CRITICAL).build().isCritical()).isTrue();
            assertThat(base().severity(FailureSeverity.MAJOR).build().isMajor()).isTrue();
            assertThat(base().severity(FailureSeverity.MINOR).build().isMinor()).isTrue();
            assertThat(base().severity(FailureSeverity.MINOR).build().isCritical()).isFalse();
        }

        @Test
        @DisplayName("a failure without a message reports an empty one rather than null")
        void missingMessageIsEmpty() {
            FailureCategory noMessage = base().build();

            assertThat(noMessage.getErrorMessage()).isEmpty();
            assertThat(noMessage.getDomainCorrectnessViolation()).isNull();
            assertThat(noMessage.getRemediationSteps()).isEmpty();
        }

        @Test
        @DisplayName("remediation steps are accumulated and cannot be altered afterwards")
        void remediationStepsAreOwned() {
            FailureCategory failure = base()
                .addRemediationStep("check the epoch")
                .addRemediationStep("rebuild the graph")
                .build();

            assertThat(failure.getRemediationSteps())
                .containsExactly("check the epoch", "rebuild the graph");
            assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> failure.getRemediationSteps().add("more")))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("the summary names the test and what to do about it")
        void summaryIsUseful() {
            FailureCategory failure = base()
                .errorMessage("expected 5 got 3")
                .domainCorrectnessViolation("result count")
                .addRemediationStep("check the epoch")
                .build();

            assertThat(failure.getSummary())
                .contains("SomeTest.method")
                .contains("CRITICAL");
            assertThat(failure.getDomainCorrectnessViolation()).isEqualTo("result count");
            assertThat(failure.toString()).contains("SomeTest.method");
            assertThat(failure.getTestName()).isEqualTo("SomeTest.method");
            assertThat(failure.getCategory()).isEqualTo("correctness");
            assertThat(failure.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
        }

        @Test
        @DisplayName("the required fields are required")
        void requiredFieldsAreEnforced() {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> FailureCategory.builder().severity(FailureSeverity.MINOR)
                    .category("c").build()))
                .isInstanceOf(NullPointerException.class);
            assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> FailureCategory.builder().testName("t").category("c").build()))
                .isInstanceOf(NullPointerException.class);
            assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> FailureCategory.builder().testName("t")
                    .severity(FailureSeverity.MINOR).build()))
                .isInstanceOf(NullPointerException.class);
        }
    }
}
