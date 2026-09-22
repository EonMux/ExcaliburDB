package com.eonmux.excalibur.integration.util;

import com.eonmux.excalibur.integration.util.FailureReportGenerator.TestResult;
import com.eonmux.excalibur.test.FailureCategory;
import com.eonmux.excalibur.test.FailureSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for FailureReportGenerator.
 *
 * @author ExcaliburDB
 * @since 10.0
 */
class FailureReportGeneratorTest {

    @Nested
    @DisplayName("Report Generation")
    class ReportGeneration {

        @Test
        @DisplayName("generateReport produces formatted output")
        void generateReportProducesFormattedOutput() {
            List<TestResult> results = Arrays.asList(
                new TestResult("ingestedDocumentsAppearInQuery", "ingested documents not found in results"),
                new TestResult("circuitBreakerTest", "circuit breaker not open after failures"),
                new TestResult("tempFileCleanupTest", "temp file cleanup failed")
            );

            String report = FailureReportGenerator.generateReport(results);

            assertThat(report).contains("CRITICAL");
            assertThat(report).contains("MAJOR");
            assertThat(report).contains("MINOR");
        }

        @Test
        @DisplayName("generateReport handles empty list")
        void generateReportHandlesEmptyList() {
            String report = FailureReportGenerator.generateReport(List.of());

            assertThat(report).contains("No failures to report");
        }
    }

    @Nested
    @DisplayName("Categorization")
    class Categorization {

        @Test
        @DisplayName("categorizeResults groups by severity")
        void categorizeResultsGroupsBySeverity() {
            List<TestResult> results = Arrays.asList(
                new TestResult("test1", "ingested documents not found"),
                new TestResult("test2", "circuit breaker not open"),
                new TestResult("test3", "temp file cleanup")
            );

            var summary = FailureReportGenerator.categorizeResults(results);

            assertThat(summary.getCritical()).hasSize(1);
            assertThat(summary.getMajor()).hasSize(1);
            assertThat(summary.getMinor()).hasSize(1);
            assertThat(summary.getTotalCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("hasCriticalFailures detects critical issues")
        void hasCriticalFailuresDetectsCriticalIssues() {
            List<TestResult> criticalResults = Arrays.asList(
                new TestResult("test", "ingested documents not found in results")
            );

            List<TestResult> nonCriticalResults = Arrays.asList(
                new TestResult("test", "temp file cleanup")
            );

            assertThat(FailureReportGenerator.hasCriticalFailures(criticalResults)).isTrue();
            assertThat(FailureReportGenerator.hasCriticalFailures(nonCriticalResults)).isFalse();
        }

        @Test
        @DisplayName("hasFailures detects any failures")
        void hasFailuresDetectsAnyFailures() {
            List<TestResult> withFailures = Arrays.asList(
                new TestResult("test", "some error")
            );

            List<TestResult> noFailures = List.of();

            assertThat(FailureReportGenerator.hasFailures(withFailures)).isTrue();
            assertThat(FailureReportGenerator.hasFailures(noFailures)).isFalse();
        }
    }

    @Nested
    @DisplayName("Failure Counts")
    class FailureCounts {

        @Test
        @DisplayName("getFailureCounts returns non-negative counts")
        void getFailureCountsReturnsNonNegativeCounts() {
            List<TestResult> results = Arrays.asList(
                new TestResult("test1", "ingested documents not found"),
                new TestResult("test2", "circuit breaker not open"),
                new TestResult("test3", "temp file cleanup failed")
            );

            int[] counts = FailureReportGenerator.getFailureCounts(results);

            // Verify counts are non-negative and add up to total
            assertThat(counts[0]).isGreaterThanOrEqualTo(0);
            assertThat(counts[1]).isGreaterThanOrEqualTo(0);
            assertThat(counts[2]).isGreaterThanOrEqualTo(0);
            assertThat(counts[0] + counts[1] + counts[2]).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Get Failure")
    class GetFailure {

        @Test
        @DisplayName("getFailure returns correct category")
        void getFailureReturnsCorrectCategory() {
            List<TestResult> results = Arrays.asList(
                new TestResult("myTest", "ingested documents not found")
            );

            FailureCategory category = FailureReportGenerator.getFailureFor(results, "myTest");

            assertThat(category).isNotNull();
            assertThat(category.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
        }

        @Test
        @DisplayName("getFailure returns null for unknown test")
        void getFailureReturnsNullForUnknownTest() {
            List<TestResult> results = Arrays.asList(
                new TestResult("myTest", "some error")
            );

            FailureCategory category = FailureReportGenerator.getFailureFor(results, "unknownTest");

            assertThat(category).isNull();
        }
    }
}
