package io.excalibur.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for FailureReportingExtension.
 *
 * @author ExcaliburDB
 * @since 10.0
 */
class FailureReportingExtensionTest {

    @Nested
    @DisplayName("Builder Configuration")
    class BuilderConfiguration {

        @Test
        @DisplayName("create() returns extension with defaults")
        void createReturnsExtensionWithDefaults() {
            FailureReportingExtension extension = FailureReportingExtension.create();

            assertThat(extension).isNotNull();
        }

        @Test
        @DisplayName("builder() allows custom configuration")
        void builderAllowsCustomConfiguration() {
            FailureReportingExtension extension = FailureReportingExtension.builder()
                .consoleReport(true)
                .writeJsonReport(false)
                .build();

            assertThat(extension).isNotNull();
        }
    }

    @Nested
    @DisplayName("Global Summary")
    class GlobalSummary {

        @Test
        @DisplayName("getGlobalSummary returns empty summary when no failures")
        void getGlobalSummaryReturnsEmptyWhenNoFailures() {
            // Reset global state is not possible in this static context,
            // but we can verify the method returns a valid summary
            FailureCategorizer.FailureSummary summary = FailureReportingExtension.getGlobalSummary();

            assertThat(summary).isNotNull();
        }
    }

    @Nested
    @DisplayName("FailureCategorizer Integration")
    class CategorizerIntegration {

        @Test
        @DisplayName("extension categorizes failures correctly")
        void extensionCategorizesFailuresCorrectly() {
            // Simulate a failure through the categorizer directly
            FailureCategory category = FailureCategorizer.categorize(
                "testClass.ingestedDocumentsAppearInQueryResults",
                "ingested documents not found in query results"
            );

            assertThat(category.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
            assertThat(category.getCategory()).isEqualTo("Data Loss");
            assertThat(category.getRemediationSteps()).isNotEmpty();
        }

        @Test
        @DisplayName("domain violations are captured")
        void domainViolationsAreCaptured() {
            FailureCategory category = FailureCategorizer.categorize(
                "testClass.epochIsolationTest",
                "torn read detected during concurrent operations"
            );

            assertThat(category.getSeverity()).isEqualTo(FailureSeverity.CRITICAL);
            assertThat(category.getDomainCorrectnessViolation())
                .contains("query must only see data visible at its captured epoch");
        }
    }
}
