package io.excalibur.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the FailureCategorizer.
 *
 * <p>Tests verify that failures are correctly categorized as CRITICAL, MAJOR, or MINOR
 * based on error patterns and test names.</p>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
class FailureCategorizerTest {

    @Nested
    @DisplayName("CRITICAL Severity Categorization")
    class CriticalSeverityTests {

        @Test
        @DisplayName("data loss patterns are CRITICAL")
        void dataLossPatternsAreCritical() {
            FailureCategory result = FailureCategorizer.categorize(
                "ingestedDocumentsAppearInQueryResults",
                "ingested documents not found in query results"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
            assertThat(result.getCategory()).isEqualTo("Data Loss");
            assertThat(result.isCritical()).isTrue();
        }

        @Test
        @DisplayName("epoch isolation violations are CRITICAL")
        void epochIsolationViolationsAreCritical() {
            FailureCategory result = FailureCategorizer.categorize(
                "noTornReadsDuringConcurrentIngestion",
                "torn read detected during concurrent operations"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
            assertThat(result.getCategory()).isEqualTo("Epoch Isolation");
        }

        @Test
        @DisplayName("deletion correctness failures are CRITICAL")
        void deletionCorrectnessFailuresAreCritical() {
            FailureCategory result = FailureCategorizer.categorize(
                "deletedEntriesNotFoundByContains",
                "deleted key still found in filter - contains check failed"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
            assertThat(result.getCategory()).isEqualTo("Deletion Correctness");
            assertThat(result.getDomainCorrectnessViolation())
                .contains("delete(key), contains(key) MUST return false");
        }

        @Test
        @DisplayName("empty results on populated index is CRITICAL")
        void emptyResultsOnPopulatedIndexIsCritical() {
            FailureCategory result = FailureCategorizer.categorize(
                "fullPipelineTest",
                "Results empty but expected at least 5 results after ingesting 100 documents"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
        }

        @Test
        @DisplayName("coverage out of range is CRITICAL")
        void coverageOutOfRangeIsCritical() {
            FailureCategory result = FailureCategorizer.categorize(
                "coverageTest",
                "coverage outside range [0,1]: got 1.5"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
            assertThat(result.getDomainCorrectnessViolation())
                .contains("[0.0, 1.0]");
        }

        @Test
        @DisplayName("duplicate results is CRITICAL")
        void duplicateResultsIsCritical() {
            FailureCategory result = FailureCategorizer.categorize(
                "noDuplicateEntriesInResults",
                "duplicate entries found in results - seenIds check failed"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
            assertThat(result.getCategory()).isEqualTo("Duplicate Results");
        }

        @Test
        @DisplayName("results not sorted is CRITICAL")
        void resultsNotSortedIsCritical() {
            FailureCategory result = FailureCategorizer.categorize(
                "queryOrderingTest",
                "results not sorted by distance in ascending order"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
            assertThat(result.getCategory()).isEqualTo("Result Ordering");
        }

        @Test
        @DisplayName("document atomicity violations are CRITICAL")
        void documentAtomicityViolationsAreCritical() {
            FailureCategory result = FailureCategorizer.categorize(
                "allDocumentChunksBecomeVisibleAtomically",
                "partial document visible - atomicity violated"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
            assertThat(result.getCategory()).isEqualTo("Document Atomicity");
        }
    }

    @Nested
    @DisplayName("MAJOR Severity Categorization")
    class MajorSeverityTests {

        @Test
        @DisplayName("circuit breaker failures are MAJOR")
        void circuitBreakerFailuresAreMajor() {
            FailureCategory result = FailureCategorizer.categorize(
                "circuitOpensAfterConsecutiveFailures",
                "Circuit breaker not open after 5 consecutive failures"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.MAJOR);
            assertThat(result.getCategory()).isEqualTo("Circuit Breaker");
            assertThat(result.isMajor()).isTrue();
        }

        @Test
        @DisplayName("timeout violations are MAJOR")
        void timeoutViolationsAreMajor() {
            FailureCategory result = FailureCategorizer.categorize(
                "queryTimeoutTest",
                "Query timeout exceeded - took 10s but limit is 5s"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.MAJOR);
            assertThat(result.getCategory()).isEqualTo("Timeout");
        }

        @Test
        @DisplayName("concurrent modification exceptions are MAJOR")
        void concurrentModificationExceptionsAreMajor() {
            FailureCategory result = FailureCategorizer.categorize(
                "concurrentQueryTest",
                "ConcurrentModificationException during concurrent access"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.MAJOR);
            assertThat(result.getCategory()).isEqualTo("Concurrency");
        }

        @Test
        @DisplayName("performance issues are MAJOR")
        void performanceIssuesAreMajor() {
            FailureCategory result = FailureCategorizer.categorize(
                "throughputTest",
                "Throughput below target: got 500k ops/sec, expected 1M"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.MAJOR);
            assertThat(result.getCategory()).isEqualTo("Performance");
        }

        @Test
        @DisplayName("high false positive rate is MAJOR")
        void highFalsePositiveRateIsMajor() {
            FailureCategory result = FailureCategorizer.categorize(
                "falsePositiveRateTest",
                "False positive rate 0.08 (8%) - significantly above 0.1% target"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.MAJOR);
        }

        @Test
        @DisplayName("exceptions during concurrent operations are MAJOR")
        void exceptionsDuringConcurrentOperationsAreMajor() {
            FailureCategory result = FailureCategorizer.categorize(
                "concurrentIngestionTest",
                "Exceptions should be 0 but got 5 during concurrent operations"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.MAJOR);
        }
    }

    @Nested
    @DisplayName("MINOR Severity Categorization")
    class MinorSeverityTests {

        @Test
        @DisplayName("slightly elevated false positive rate is MINOR")
        void slightlyElevatedFalsePositiveRateIsMinor() {
            FailureCategory result = FailureCategorizer.categorize(
                "cuckooFilterTest",
                "False positive rate slightly above target: 0.015 vs 0.01"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.MINOR);
            assertThat(result.getCategory()).isEqualTo("False Positive Rate");
            assertThat(result.isMinor()).isTrue();
        }

        @Test
        @DisplayName("test infrastructure issues are MINOR")
        void testInfrastructureIssuesAreMinor() {
            FailureCategory result = FailureCategorizer.categorize(
                "tempFileTest",
                "Temp file cleanup failed - test infrastructure issue"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.MINOR);
            assertThat(result.getCategory()).isEqualTo("Test Infrastructure");
        }

        @Test
        @DisplayName("cosmetic issues are MINOR")
        void cosmeticIssuesAreMinor() {
            FailureCategory result = FailureCategorizer.categorize(
                "outputFormatTest",
                "Cosmetic formatting issue in log output"
            );

            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.MINOR);
        }
    }

    @Nested
    @DisplayName("Unknown Failures")
    class UnknownFailureTests {

        @Test
        @DisplayName("unknown failures default to MAJOR")
        void unknownFailuresDefaultToMajor() {
            FailureCategory result = FailureCategorizer.categorize(
                "someRandomTest",
                "Some random error that doesn't match any pattern"
            );

            // Unknown failures should default to MAJOR (safer than MINOR)
            assertThat(result.getSeverity()).isEqualTo(FailureSeverity.MAJOR);
            assertThat(result.getCategory()).isEqualTo("Unknown");
        }
    }

    @Nested
    @DisplayName("FailureCategory Properties")
    class FailureCategoryProperties {

        @Test
        @DisplayName("category includes remediation steps")
        void categoryIncludesRemediationSteps() {
            FailureCategory result = FailureCategorizer.categorize(
                "dataLossTest",
                "Ingested documents not found in query results"
            );

            assertThat(result.getRemediationSteps()).isNotEmpty();
            assertThat(result.getRemediationSteps())
                .anyMatch(step -> step.contains("CRITICAL"));
        }

        @Test
        @DisplayName("category includes domain correctness violation when applicable")
        void categoryIncludesDomainCorrectnessViolation() {
            FailureCategory result = FailureCategorizer.categorize(
                "coverageTest",
                "coverage outside range [0,1] or 0.0 check failed"
            );

            assertThat(result.getDomainCorrectnessViolation()).isNotNull();
        }

        @Test
        @DisplayName("summary is formatted correctly")
        void summaryIsFormattedCorrectly() {
            FailureCategory result = FailureCategorizer.categorize(
                "testName",
                "Ingested documents not found"
            );

            String summary = result.getSummary();
            assertThat(summary).contains("[CRITICAL]");
            assertThat(summary).contains("testName");
        }
    }

    @Nested
    @DisplayName("Bulk Categorization")
    class BulkCategorization {

        @Test
        @DisplayName("categorizeAll groups failures by severity")
        void categorizeAllGroupsFailuresBySeverity() {
            List<FailureCategorizer.TestFailure> failures = Arrays.asList(
                new FailureCategorizer.TestFailure("test1", "Ingested documents not found"),
                new FailureCategorizer.TestFailure("test2", "Circuit breaker not open"),
                new FailureCategorizer.TestFailure("test3", "Temp file cleanup failed")
            );

            FailureCategorizer.FailureSummary summary = FailureCategorizer.categorizeAll(failures);

            assertThat(summary.getCritical()).hasSize(1);
            assertThat(summary.getMajor()).hasSize(1);
            assertThat(summary.getMinor()).hasSize(1);
            assertThat(summary.getTotalCount()).isEqualTo(3);
            assertThat(summary.hasCriticalFailures()).isTrue();
        }

        @Test
        @DisplayName("empty failure list produces empty summary")
        void emptyFailureListProducesEmptySummary() {
            List<FailureCategorizer.TestFailure> failures = List.of();

            FailureCategorizer.FailureSummary summary = FailureCategorizer.categorizeAll(failures);

            assertThat(summary.hasFailures()).isFalse();
            assertThat(summary.getTotalCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("report includes all severity levels")
        void reportIncludesAllSeverityLevels() {
            List<FailureCategorizer.TestFailure> failures = Arrays.asList(
                new FailureCategorizer.TestFailure("criticalTest", "Ingested documents not found"),
                new FailureCategorizer.TestFailure("majorTest", "Circuit breaker not open"),
                new FailureCategorizer.TestFailure("minorTest", "Temp file cleanup failed")
            );

            FailureCategorizer.FailureSummary summary = FailureCategorizer.categorizeAll(failures);
            String report = summary.getReport();

            assertThat(report).contains("CRITICAL (1)");
            assertThat(report).contains("MAJOR (1)");
            assertThat(report).contains("MINOR (1)");
            assertThat(report).contains("Total: 3 failures");
        }
    }

    @Nested
    @DisplayName("Domain Correctness Violations")
    class DomainCorrectnessViolations {

        @Test
        @DisplayName("coverage violation includes range")
        void coverageViolationIncludesRange() {
            FailureCategory result = FailureCategorizer.categorize(
                "test",
                "coverage outside range [0,1] or 0.0 check failed"
            );

            assertThat(result.getDomainCorrectnessViolation())
                .isEqualTo("Coverage values must be in range [0.0, 1.0]");
        }

        @Test
        @DisplayName("epoch isolation violation includes description")
        void epochIsolationViolationIncludesDescription() {
            FailureCategory result = FailureCategorizer.categorize(
                "test",
                "torn read detected during concurrent operations"
            );

            assertThat(result.getDomainCorrectnessViolation())
                .contains("query must only see data visible at its captured epoch");
        }

        @Test
        @DisplayName("deletion violation includes contains requirement")
        void deletionViolationIncludesContainsRequirement() {
            FailureCategory result = FailureCategorizer.categorize(
                "test",
                "deleted key still found in Cuckoo filter"
            );

            assertThat(result.getDomainCorrectnessViolation())
                .contains("contains(key) MUST return false");
        }
    }
}
