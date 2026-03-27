/*
 * excalibur-test - Test Assertion Messages Unit Tests
 *
 * Verifies that TestAssertionMessages produces specific, actionable assertion messages.
 */

package io.excalibur.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TestAssertionMessages utility class.
 *
 * <p>These tests verify that assertion messages:</p>
 * <ul>
 *   <li>Contain the expected values (actual, expected)</li>
 *   <li>Include domain context</li>
 *   <li>Identify the domain correctness criterion violated</li>
 *   <li>Provide actionable debugging hints</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
class TestAssertionMessagesTest {

    @Nested
    @DisplayName("Coverage Assertions")
    class CoverageAssertions {

        @Test
        @DisplayName("coverageMustBeZero includes context and domain violation")
        void coverageMustBeZero_includesContextAndDomainViolation() {
            String message = TestAssertionMessages.coverageMustBeZero("empty index");

            assertThat(message)
                .as("Message should include context")
                .contains("empty index")
                .as("Message should state expected value")
                .contains("0.0")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should provide debugging hints")
                .contains("IndexCoverage");
        }

        @Test
        @DisplayName("coverageMustBeInRange includes actual value")
        void coverageMustBeInRange_includesActualValue() {
            String message = TestAssertionMessages.coverageMustBeInRange(1.5);

            assertThat(message)
                .as("Message should include actual value")
                .contains("1.5")
                .as("Message should state expected range")
                .contains("[0.0, 1.0]")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should provide debugging hints")
                .contains("normalized");
        }

        @Test
        @DisplayName("degradedMustBeTrue includes context and coverage")
        void degradedMustBeTrue_includesContextAndCoverage() {
            String message = TestAssertionMessages.degradedMustBeTrue("empty index", 0.0);

            assertThat(message)
                .as("Message should include context")
                .contains("empty index")
                .as("Message should include actual coverage")
                .contains("coverage=0.0000")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should mention readiness threshold")
                .contains("readiness threshold");
        }

        @Test
        @DisplayName("coverageShouldReflectState includes segment counts")
        void coverageShouldReflectState_includesSegmentCounts() {
            String message = TestAssertionMessages.coverageShouldReflectState(0.5, 0.2, 50, 100);

            assertThat(message)
                .as("Message should include expected minimum")
                .contains("Expected: >= 0.50")
                .as("Message should include actual value")
                .contains("Actual: 0.2000")
                .as("Message should include segment counts")
                .contains("Active segments: 50/100")
                .as("Message should provide debugging hints")
                .contains("VisibilityTracker");
        }

        @Test
        @DisplayName("coverageMustIncreaseMonotonically includes both stages")
        void coverageMustIncreaseMonotonically_includesBothStages() {
            String message = TestAssertionMessages.coverageMustIncreaseMonotonically(3, 0.5, 0.3);

            assertThat(message)
                .as("Message should include current stage")
                .contains("stage 3")
                .as("Message should include previous coverage")
                .contains("0.5000")
                .as("Message should include current coverage")
                .contains("0.3000")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should mention monotonicity")
                .contains("increase");
        }
    }

    @Nested
    @DisplayName("Result Assertions")
    class ResultAssertions {

        @Test
        @DisplayName("resultsShouldNotBeEmpty includes context and topK")
        void resultsShouldNotBeEmpty_includesContextAndTopK() {
            String message = TestAssertionMessages.resultsShouldNotBeEmpty("after ingesting 100 documents", 10);

            assertThat(message)
                .as("Message should include context")
                .contains("after ingesting 100 documents")
                .as("Message should include topK")
                .contains("top_k=10")
                .as("Message should provide debugging hints")
                .contains("EMPTY RESULTS INDICATE")
                .contains("VisibilityTracker");
        }

        @Test
        @DisplayName("resultsMustBeSortedByScore includes indices and scores")
        void resultsMustBeSortedByScore_includesIndicesAndScores() {
            String message = TestAssertionMessages.resultsMustBeSortedByScore(5, 0.7f, 0.9f);

            assertThat(message)
                .as("Message should include current index")
                .containsIgnoringCase("result[5]")
                .as("Message should include current score")
                .contains("0.7")
                .as("Message should include previous index")
                .containsIgnoringCase("result[4]")
                .as("Message should include previous score")
                .contains("0.9")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should mention sorting")
                .contains("sorted by score");
        }

        @Test
        @DisplayName("noDuplicateResults includes duplicate ID and indices")
        void noDuplicateResults_includesDuplicateIdAndIndices() {
            String message = TestAssertionMessages.noDuplicateResults(12345L, 2, 7);

            assertThat(message)
                .as("Message should include duplicate ID")
                .contains("12345")
                .as("Message should include first index")
                .contains("index 2")
                .as("Message should include second index")
                .contains("and 7")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should provide debugging hints")
                .contains("HNSW");
        }

        @Test
        @DisplayName("scoreMustBeNormalized includes score and document ID")
        void scoreMustBeNormalized_includesScoreAndDocumentId() {
            String message = TestAssertionMessages.scoreMustBeNormalized(2.5f, 42L);

            assertThat(message)
                .as("Message should include document ID")
                .contains("42")
                .as("Message should include actual score")
                .contains("2.5")
                .as("Message should state expected range")
                .contains("[0.0, 1.0]")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION");
        }

        @Test
        @DisplayName("expectedDocumentInResults includes document ID and result count")
        void expectedDocumentInResults_includesDocumentIdAndResultCount() {
            String message = TestAssertionMessages.expectedDocumentInResults("doc-123", 5);

            assertThat(message)
                .as("Message should include expected document ID")
                .contains("doc-123")
                .as("Message should include result count")
                .contains("5 results")
                .as("Message should provide debugging hints")
                .contains("FAILURE INDICATES")
                .contains("HNSW");
        }
    }

    @Nested
    @DisplayName("Epoch Isolation Assertions")
    class EpochIsolationAssertions {

        @Test
        @DisplayName("epochIsolationNoTornReads includes epoch and visibility")
        void epochIsolationNoTornReads_includesEpochAndVisibility() {
            String message = TestAssertionMessages.epochIsolationNoTornReads(42L, true);

            assertThat(message)
                .as("Message should include epoch")
                .contains("epoch=42")
                .as("Message should include visibility")
                .contains("visible=true")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should mention torn reads")
                .contains("Torn reads");
        }

        @Test
        @DisplayName("epochMustIncreaseMonotonically includes both epochs")
        void epochMustIncreaseMonotonically_includesBothEpochs() {
            String message = TestAssertionMessages.epochMustIncreaseMonotonically(100L, 50L);

            assertThat(message)
                .as("Message should include previous epoch")
                .contains("Previous=100")
                .as("Message should include current epoch")
                .contains("Current=50")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should mention monotonicity")
                .contains("monotonically");
        }

        @Test
        @DisplayName("documentAtomicity includes document ID and chunk counts")
        void documentAtomicity_includesDocumentIdAndChunkCounts() {
            String message = TestAssertionMessages.documentAtomicity("doc-456", 5, 3);

            assertThat(message)
                .as("Message should include document ID")
                .contains("doc-456")
                .as("Message should include expected chunks")
                .contains("expected 5")
                .as("Message should include found chunks")
                .contains("found 3")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should mention atomicity")
                .contains("atomic");
        }
    }

    @Nested
    @DisplayName("Deletion Correctness Assertions")
    class DeletionCorrectnessAssertions {

        @Test
        @DisplayName("deletedKeyMustNotBeFound includes key and filter type")
        void deletedKeyMustNotBeFound_includesKeyAndFilterType() {
            String message = TestAssertionMessages.deletedKeyMustNotBeFound("user-789", "Cuckoo");

            assertThat(message)
                .as("Message should include deleted key")
                .contains("user-789")
                .as("Message should include filter type")
                .contains("Cuckoo filter")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should mention contains() contract")
                .contains("contains(key) MUST return false");
        }

        @Test
        @DisplayName("falsePositiveRateWithinTarget includes rates and counts")
        void falsePositiveRateWithinTarget_includesRatesAndCounts() {
            String message = TestAssertionMessages.falsePositiveRateWithinTarget(0.05, 0.01, 10000, 500);

            assertThat(message)
                .as("Message should include actual rate as percentage")
                .contains("5.0000%")
                .as("Message should include false positive count")
                .contains("500/10000")
                .as("Message should include target rate")
                .contains("1.0000%")
                .as("Message should provide debugging hints")
                .contains("fingerprint");
        }
    }

    @Nested
    @DisplayName("LLM Fallback Assertions")
    class LlmFallbackAssertions {

        @Test
        @DisplayName("llmFallbackMustReturnResults includes LLM availability and result count")
        void llmFallbackMustReturnResults_includesLlmAvailabilityAndResultCount() {
            String message = TestAssertionMessages.llmFallbackMustReturnResults(false, 0);

            assertThat(message)
                .as("Message should include LLM availability")
                .contains("LLM available=false")
                .as("Message should include result count")
                .contains("Results=0")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should mention fallback")
                .contains("fallback");
        }

        @Test
        @DisplayName("circuitBreakerMustOpen includes failure count and threshold")
        void circuitBreakerMustOpen_includesFailureCountAndThreshold() {
            String message = TestAssertionMessages.circuitBreakerMustOpen(5, 3, "CLOSED");

            assertThat(message)
                .as("Message should include failure count")
                .contains("5 consecutive failures")
                .as("Message should include threshold")
                .contains("threshold=3")
                .as("Message should include current state")
                .contains("Current state: CLOSED")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION")
                .as("Message should mention state transition")
                .contains("CLOSED -> OPEN");
        }

        @Test
        @DisplayName("responseMustIndicateDegraded includes reason and values")
        void responseMustIndicateDegraded_includesReasonAndValues() {
            String message = TestAssertionMessages.responseMustIndicateDegraded("LLM unavailable", false, 0.8);

            assertThat(message)
                .as("Message should include reason")
                .contains("LLM unavailable")
                .as("Message should include degraded flag")
                .contains("degraded=false")
                .as("Message should include coverage")
                .contains("coverage=0.8000")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION");
        }
    }

    @Nested
    @DisplayName("Concurrent Operation Assertions")
    class ConcurrentOperationAssertions {

        @Test
        @DisplayName("noExceptionsDuringConcurrentOperations includes count and samples")
        void noExceptionsDuringConcurrentOperations_includesCountAndSamples() {
            List<String> exceptions = Arrays.asList(
                "NullPointerException at line 42",
                "ConcurrentModificationException in HashMap",
                "ArrayIndexOutOfBoundsException at index 5"
            );

            String message = TestAssertionMessages.noExceptionsDuringConcurrentOperations(
                "ingestion/query", 3, exceptions
            );

            assertThat(message)
                .as("Message should include operation type")
                .contains("ingestion/query")
                .as("Message should include exception count")
                .contains("3 exceptions")
                .as("Message should include sample exceptions")
                .contains("NullPointerException")
                .contains("ConcurrentModificationException")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION");
        }

        @Test
        @DisplayName("noExceptionsDuringConcurrentOperations handles empty exception list")
        void noExceptionsDuringConcurrentOperations_handlesEmptyList() {
            String message = TestAssertionMessages.noExceptionsDuringConcurrentOperations(
                "query", 0, Collections.emptyList()
            );

            assertThat(message)
                .as("Message should include operation type")
                .contains("query")
                .as("Message should show zero exceptions")
                .contains("0 exceptions")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION");
        }

        @Test
        @DisplayName("resultsShouldImproveProgressively includes stages and counts")
        void resultsShouldImproveProgressively_includesStagesAndCounts() {
            String message = TestAssertionMessages.resultsShouldImproveProgressively(4, 50, 30);

            assertThat(message)
                .as("Message should include current stage")
                .contains("stage 4")
                .as("Message should include current count")
                .contains("30")
                .as("Message should include previous stage")
                .contains("stage 3")
                .as("Message should include previous count")
                .contains("50")
                .as("Message should identify domain violation")
                .contains("DOMAIN VIOLATION");
        }
    }

    @Nested
    @DisplayName("Summary Methods")
    class SummaryMethods {

        @Test
        @DisplayName("queryResponseSummary handles null response")
        void queryResponseSummary_handlesNullResponse() {
            String summary = TestAssertionMessages.queryResponseSummary(null);

            assertThat(summary)
                .as("Summary should indicate null response")
                .contains("null");
        }

        @Test
        @DisplayName("resultScoresSummary handles empty results")
        void resultScoresSummary_handlesEmptyResults() {
            String summary = TestAssertionMessages.resultScoresSummary(Collections.emptyList(), 5);

            assertThat(summary)
                .as("Summary should indicate no results")
                .contains("No results");
        }
    }

    @Nested
    @DisplayName("Message Format Verification")
    class MessageFormatVerification {

        @Test
        @DisplayName("All messages contain identifying keywords for failure categorization")
        void allMessagesContainIdentifyingKeywords() {
            // Verify that messages contain keywords that FailureCategorizer can match

            assertThat(TestAssertionMessages.coverageMustBeZero("test"))
                .contains("coverage");

            assertThat(TestAssertionMessages.coverageMustBeInRange(1.5))
                .contains("range");

            assertThat(TestAssertionMessages.deletedKeyMustNotBeFound("key", "Cuckoo"))
                .containsAnyOf("deleted", "found");

            assertThat(TestAssertionMessages.resultsShouldNotBeEmpty("test", 10))
                .containsAnyOf("empty", "results");

            assertThat(TestAssertionMessages.llmFallbackMustReturnResults(false, 0))
                .containsAnyOf("LLM", "fallback");

            assertThat(TestAssertionMessages.circuitBreakerMustOpen(5, 3, "CLOSED"))
                .containsAnyOf("circuit", "breaker");
        }

        @Test
        @DisplayName("Messages include actionable debugging hints")
        void messagesIncludeActionableDebuggingHints() {
            // Verify that messages include hints about what to check

            String coverageMessage = TestAssertionMessages.coverageMustBeInRange(2.0);
            assertThat(coverageMessage).contains("Check:");

            String resultsMessage = TestAssertionMessages.resultsShouldNotBeEmpty("test", 10);
            assertThat(resultsMessage).containsAnyOf("INDICATE", "Check:", "verify");

            String epochMessage = TestAssertionMessages.epochIsolationNoTornReads(1L, true);
            assertThat(epochMessage).containsAnyOf("INDICATE", "Check:", "indicate");
        }

        @Test
        @DisplayName("Messages format numeric values consistently")
        void messagesFormatNumericValuesConsistently() {
            // Verify numeric formatting

            String coverageMessage = TestAssertionMessages.coverageMustBeInRange(1.23456789);
            assertThat(coverageMessage).contains("1.2346"); // 4 decimal places

            String scoreMessage = TestAssertionMessages.resultsMustBeSortedByScore(5, 0.123456f, 0.789012f);
            assertThat(scoreMessage).contains("0.1235"); // 4 decimal places
        }
    }
}
