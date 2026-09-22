/*
 * excalibur-test - Flaky Test Analyzer Tests
 */

package com.eonmux.excalibur.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for FlakyTestAnalyzer.
 */
@DisplayName("FlakyTestAnalyzer")
class FlakyTestAnalyzerTest {

    @Nested
    @DisplayName("Builder Methods")
    class BuilderTests {

        @Test
        @DisplayName("should create analyzer with default settings")
        void shouldCreateWithDefaults() {
            FlakyTestAnalyzer analyzer = new FlakyTestAnalyzer();

            // Should not throw - tests default configuration is valid
            assertThatCode(() -> analyzer.withModuleName("test-module")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should set module name")
        void shouldSetModuleName() {
            FlakyTestAnalyzer analyzer = new FlakyTestAnalyzer()
                .withModuleName("excalibur-integration");

            assertThatCode(() -> analyzer.withRunCount(3)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should reject null module name")
        void shouldRejectNullModuleName() {
            assertThatNullPointerException()
                .isThrownBy(() -> new FlakyTestAnalyzer().withModuleName(null));
        }

        @Test
        @DisplayName("should set valid run count")
        void shouldSetValidRunCount() {
            assertThatCode(() -> new FlakyTestAnalyzer().withRunCount(3)).doesNotThrowAnyException();
            assertThatCode(() -> new FlakyTestAnalyzer().withRunCount(10)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should reject run count less than 3")
        void shouldRejectLowRunCount() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer().withRunCount(2))
                .withMessageContaining("at least 3");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer().withRunCount(1))
                .withMessageContaining("at least 3");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer().withRunCount(0))
                .withMessageContaining("at least 3");
        }

        @Test
        @DisplayName("should set gradle path")
        void shouldSetGradlePath() {
            assertThatCode(() -> new FlakyTestAnalyzer().withGradlePath("./gradlew"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should reject null gradle path")
        void shouldRejectNullGradlePath() {
            assertThatNullPointerException()
                .isThrownBy(() -> new FlakyTestAnalyzer().withGradlePath(null));
        }
    }

    @Nested
    @DisplayName("TestRunResult")
    class TestRunResultTests {

        @Test
        @DisplayName("should create passed result")
        void shouldCreatePassedResult() {
            FlakyTestAnalyzer.TestRunResult result = new FlakyTestAnalyzer.TestRunResult(
                "TestClass.testMethod",
                FlakyTestAnalyzer.TestStatus.PASSED,
                null,
                1
            );

            assertThat(result.testName()).isEqualTo("TestClass.testMethod");
            assertThat(result.status()).isEqualTo(FlakyTestAnalyzer.TestStatus.PASSED);
            assertThat(result.errorMessage()).isNull();
            assertThat(result.runNumber()).isEqualTo(1);
            assertThat(result.passed()).isTrue();
            assertThat(result.failed()).isFalse();
            assertThat(result.skipped()).isFalse();
        }

        @Test
        @DisplayName("should create failed result")
        void shouldCreateFailedResult() {
            FlakyTestAnalyzer.TestRunResult result = new FlakyTestAnalyzer.TestRunResult(
                "TestClass.testMethod",
                FlakyTestAnalyzer.TestStatus.FAILED,
                "Assertion failed",
                2
            );

            assertThat(result.failed()).isTrue();
            assertThat(result.passed()).isFalse();
            assertThat(result.errorMessage()).isEqualTo("Assertion failed");
        }

        @Test
        @DisplayName("should create skipped result")
        void shouldCreateSkippedResult() {
            FlakyTestAnalyzer.TestRunResult result = new FlakyTestAnalyzer.TestRunResult(
                "TestClass.testMethod",
                FlakyTestAnalyzer.TestStatus.SKIPPED,
                null,
                1
            );

            assertThat(result.skipped()).isTrue();
            assertThat(result.passed()).isFalse();
            assertThat(result.failed()).isFalse();
        }

        @Test
        @DisplayName("should reject null test name")
        void shouldRejectNullTestName() {
            assertThatNullPointerException()
                .isThrownBy(() -> new FlakyTestAnalyzer.TestRunResult(
                    null,
                    FlakyTestAnalyzer.TestStatus.PASSED,
                    null,
                    1
                ));
        }

        @Test
        @DisplayName("should reject null status")
        void shouldRejectNullStatus() {
            assertThatNullPointerException()
                .isThrownBy(() -> new FlakyTestAnalyzer.TestRunResult(
                    "TestClass.testMethod",
                    null,
                    null,
                    1
                ));
        }

        @Test
        @DisplayName("should reject non-positive run number")
        void shouldRejectNonPositiveRunNumber() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer.TestRunResult(
                    "TestClass.testMethod",
                    FlakyTestAnalyzer.TestStatus.PASSED,
                    null,
                    0
                ));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer.TestRunResult(
                    "TestClass.testMethod",
                    FlakyTestAnalyzer.TestStatus.PASSED,
                    null,
                    -1
                ));
        }
    }

    @Nested
    @DisplayName("RunSummary")
    class RunSummaryTests {

        @Test
        @DisplayName("should calculate total correctly")
        void shouldCalculateTotal() {
            FlakyTestAnalyzer.RunSummary summary = new FlakyTestAnalyzer.RunSummary(
                1,
                List.of(
                    createPassedResult("test1"),
                    createPassedResult("test2"),
                    createFailedResult("test3"),
                    createSkippedResult("test4")
                ),
                2,
                1,
                1,
                java.time.Duration.ofMillis(1000),
                0
            );

            assertThat(summary.total()).isEqualTo(4);
            assertThat(summary.passed()).isEqualTo(2);
            assertThat(summary.failed()).isEqualTo(1);
            assertThat(summary.skipped()).isEqualTo(1);
            assertThat(summary.success()).isFalse(); // Has failures
        }

        @Test
        @DisplayName("should detect success when no failures")
        void shouldDetectSuccess() {
            FlakyTestAnalyzer.RunSummary summary = new FlakyTestAnalyzer.RunSummary(
                1,
                List.of(createPassedResult("test1")),
                1,
                0,
                0,
                java.time.Duration.ofMillis(1000),
                0
            );

            assertThat(summary.success()).isTrue();
        }

        @Test
        @DisplayName("should return unmodifiable results list")
        void shouldReturnUnmodifiableResults() {
            List<FlakyTestAnalyzer.TestRunResult> mutableList = new java.util.ArrayList<>();
            mutableList.add(createPassedResult("test1"));

            FlakyTestAnalyzer.RunSummary summary = new FlakyTestAnalyzer.RunSummary(
                1,
                mutableList,
                1,
                0,
                0,
                java.time.Duration.ofMillis(1000),
                0
            );

            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> summary.results().add(createPassedResult("test2")));
        }
    }

    @Nested
    @DisplayName("FlakyTestResult")
    class FlakyTestResultTests {

        @Test
        @DisplayName("should create valid flaky test result")
        void shouldCreateValidResult() {
            FlakyTestAnalyzer.FlakyTestResult result = new FlakyTestAnalyzer.FlakyTestResult(
                "TestClass.testMethod",
                3,
                2,
                5,
                0.48,  // Flakiness score (near 0.5 for 60% pass rate)
                0.6,   // 60% pass rate
                List.of("Timeout exceeded (2x)")
            );

            assertThat(result.testName()).isEqualTo("TestClass.testMethod");
            assertThat(result.passCount()).isEqualTo(3);
            assertThat(result.failCount()).isEqualTo(2);
            assertThat(result.totalRuns()).isEqualTo(5);
            assertThat(result.flakinessScore()).isBetween(0.0, 1.0);
            assertThat(result.passRate()).isEqualTo(0.6);
        }

        @Test
        @DisplayName("should reject negative counts")
        void shouldRejectNegativeCounts() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer.FlakyTestResult(
                    "test", -1, 2, 5, 0.4, 0.6, List.of()
                ));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer.FlakyTestResult(
                    "test", 3, -1, 5, 0.4, 0.6, List.of()
                ));
        }

        @Test
        @DisplayName("should reject mismatched counts")
        void shouldRejectMismatchedCounts() {
            // passCount + failCount must equal totalRuns
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer.FlakyTestResult(
                    "test", 3, 2, 10, 0.4, 0.6, List.of()
                ))
                .withMessageContaining("must equal");
        }

        @Test
        @DisplayName("should reject out-of-range flakiness score")
        void shouldRejectOutOfRangeFlakinessScore() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer.FlakyTestResult(
                    "test", 3, 2, 5, 1.5, 0.6, List.of()
                ))
                .withMessageContaining("flakinessScore");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer.FlakyTestResult(
                    "test", 3, 2, 5, -0.1, 0.6, List.of()
                ))
                .withMessageContaining("flakinessScore");
        }

        @Test
        @DisplayName("should reject out-of-range pass rate")
        void shouldRejectOutOfRangePassRate() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer.FlakyTestResult(
                    "test", 3, 2, 5, 0.4, 1.5, List.of()
                ))
                .withMessageContaining("passRate");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer.FlakyTestResult(
                    "test", 3, 2, 5, 0.4, -0.1, List.of()
                ))
                .withMessageContaining("passRate");
        }

        @Test
        @DisplayName("should generate description")
        void shouldGenerateDescription() {
            FlakyTestAnalyzer.FlakyTestResult result = new FlakyTestAnalyzer.FlakyTestResult(
                "TestClass.testMethod",
                3,
                2,
                5,
                0.48,
                0.6,
                List.of()
            );

            String description = result.getDescription();

            assertThat(description)
                .contains("TestClass.testMethod")
                .contains("3/5")
                .contains("60%");
        }
    }

    @Nested
    @DisplayName("ConsistentFailure")
    class ConsistentFailureTests {

        @Test
        @DisplayName("should create valid consistent failure")
        void shouldCreateValidFailure() {
            FlakyTestAnalyzer.ConsistentFailure failure = new FlakyTestAnalyzer.ConsistentFailure(
                "TestClass.testMethod",
                5,
                "Assertion failed: expected 10 but was 5"
            );

            assertThat(failure.testName()).isEqualTo("TestClass.testMethod");
            assertThat(failure.failCount()).isEqualTo(5);
            assertThat(failure.errorMessage()).contains("expected 10");
        }

        @Test
        @DisplayName("should reject null test name")
        void shouldRejectNullTestName() {
            assertThatNullPointerException()
                .isThrownBy(() -> new FlakyTestAnalyzer.ConsistentFailure(null, 5, "error"));
        }

        @Test
        @DisplayName("should reject negative fail count")
        void shouldRejectNegativeFailCount() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FlakyTestAnalyzer.ConsistentFailure("test", -1, "error"));
        }
    }

    @Nested
    @DisplayName("FlakyTestReport")
    class FlakyTestReportTests {

        @Test
        @DisplayName("should identify all tests passed")
        void shouldIdentifyAllTestsPassed() {
            FlakyTestAnalyzer.FlakyTestReport report = createReport(
                List.of(),  // No flaky tests
                List.of(),  // No consistent failures
                List.of("test1", "test2")
            );

            assertThat(report.allTestsPassed()).isTrue();
            assertThat(report.hasFlakyTests()).isFalse();
            assertThat(report.hasConsistentFailures()).isFalse();
        }

        @Test
        @DisplayName("should identify flaky tests")
        void shouldIdentifyFlakyTests() {
            FlakyTestAnalyzer.FlakyTestReport report = createReport(
                List.of(createFlakyResult("flakyTest", 3, 2)),
                List.of(),
                List.of("stableTest")
            );

            assertThat(report.hasFlakyTests()).isTrue();
            assertThat(report.allTestsPassed()).isFalse();
            assertThat(report.getFlakyTests()).hasSize(1);
        }

        @Test
        @DisplayName("should identify consistent failures")
        void shouldIdentifyConsistentFailures() {
            FlakyTestAnalyzer.FlakyTestReport report = createReport(
                List.of(),
                List.of(new FlakyTestAnalyzer.ConsistentFailure("failingTest", 5, "error")),
                List.of("passingTest")
            );

            assertThat(report.hasConsistentFailures()).isTrue();
            assertThat(report.allTestsPassed()).isFalse();
        }

        @Test
        @DisplayName("should calculate overall pass rate")
        void shouldCalculateOverallPassRate() {
            FlakyTestAnalyzer.FlakyTestReport report = createReportWithRunSummaries(
                List.of(
                    createRunSummary(10, 2, 0),  // 10 passed, 2 failed
                    createRunSummary(9, 3, 0),   // 9 passed, 3 failed
                    createRunSummary(11, 1, 0)   // 11 passed, 1 failed
                )
            );

            // Total: 30 passed, 6 failed, 36 total = 83.3% pass rate
            double passRate = report.getOverallPassRate();
            assertThat(passRate).isBetween(0.83, 0.84);
        }

        @Test
        @DisplayName("should handle empty test suite")
        void shouldHandleEmptyTestSuite() {
            FlakyTestAnalyzer.FlakyTestReport report = createReport(
                List.of(),
                List.of(),
                List.of()
            );

            assertThat(report.getTotalTestCount()).isEqualTo(0);
            assertThat(report.getOverallPassRate()).isEqualTo(0.0);
            assertThat(report.allTestsPassed()).isTrue();
        }

        @Test
        @DisplayName("should generate summary")
        void shouldGenerateSummary() {
            FlakyTestAnalyzer.FlakyTestReport report = createReport(
                List.of(createFlakyResult("flakyTest", 3, 2)),
                List.of(new FlakyTestAnalyzer.ConsistentFailure("failingTest", 5, "error")),
                List.of("passingTest")
            );

            String summary = report.getSummary();

            assertThat(summary)
                .contains("FLAKY TESTS")
                .contains("CONSISTENT FAILURES")
                .contains("flakyTest")
                .contains("failingTest");
        }

        @Test
        @DisplayName("should generate JSON output")
        void shouldGenerateJsonOutput() {
            FlakyTestAnalyzer.FlakyTestReport report = createReport(
                List.of(createFlakyResult("TestClass.testMethod", 3, 2)),
                List.of(),
                List.of("passingTest")
            );

            String json = report.toJson();

            assertThat(json)
                .contains("\"module\"")
                .contains("\"runCount\"")
                .contains("\"flakyTestCount\"")
                .contains("\"TestClass.testMethod\"");
        }

        @Test
        @DisplayName("should escape JSON special characters")
        void shouldEscapeJsonSpecialCharacters() {
            String json = new FlakyTestAnalyzer.FlakyTestReport(
                "module\"with\"quotes",
                5,
                List.of(),  // runSummaries
                List.of(),  // flakyTests
                List.of(),  // consistentFailures
                List.of(),  // alwaysPassing
                Map.of(),   // categorizedFailures
                Map.of()    // allResults
            ).toJson();

            assertThat(json).contains("module\\\"with\\\"quotes");
        }
    }

    @Nested
    @DisplayName("Flakiness Score Calculation")
    class FlakinessScoreTests {

        @Test
        @DisplayName("should score 0% pass rate as not flaky")
        void shouldScoreZeroPassRateAsNotFlaky() {
            // 0% pass rate = consistent failure = not flaky
            FlakyTestAnalyzer.FlakyTestResult result = new FlakyTestAnalyzer.FlakyTestResult(
                "test", 0, 5, 5, 0.0, 0.0, List.of()
            );
            assertThat(result.flakinessScore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should score 100% pass rate as not flaky")
        void shouldScoreHundredPercentPassRateAsNotFlaky() {
            // 100% pass rate = consistent pass = not flaky
            FlakyTestAnalyzer.FlakyTestResult result = new FlakyTestAnalyzer.FlakyTestResult(
                "test", 5, 0, 5, 0.0, 1.0, List.of()
            );
            assertThat(result.flakinessScore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should score 50% pass rate as maximally flaky")
        void shouldScoreFiftyPercentPassRateAsMaximallyFlaky() {
            // 50% pass rate = maximally flaky = score 1.0
            FlakyTestAnalyzer.FlakyTestResult result = new FlakyTestAnalyzer.FlakyTestResult(
                "test", 2, 2, 4, 1.0, 0.5, List.of()
            );
            assertThat(result.flakinessScore()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should score 25% pass rate as moderately flaky")
        void shouldScoreTwentyFivePercentPassRateAsModeratelyFlaky() {
            // 25% pass rate = moderately flaky = score 0.5
            FlakyTestAnalyzer.FlakyTestResult result = new FlakyTestAnalyzer.FlakyTestResult(
                "test", 1, 3, 4, 0.5, 0.25, List.of()
            );
            assertThat(result.flakinessScore()).isEqualTo(0.5);
        }
    }

    // Helper methods

    private FlakyTestAnalyzer.TestRunResult createPassedResult(String testName) {
        return new FlakyTestAnalyzer.TestRunResult(
            testName, FlakyTestAnalyzer.TestStatus.PASSED, null, 1
        );
    }

    private FlakyTestAnalyzer.TestRunResult createFailedResult(String testName) {
        return new FlakyTestAnalyzer.TestRunResult(
            testName, FlakyTestAnalyzer.TestStatus.FAILED, "Failed", 1
        );
    }

    private FlakyTestAnalyzer.TestRunResult createSkippedResult(String testName) {
        return new FlakyTestAnalyzer.TestRunResult(
            testName, FlakyTestAnalyzer.TestStatus.SKIPPED, null, 1
        );
    }

    private FlakyTestAnalyzer.FlakyTestResult createFlakyResult(String testName, int passCount, int failCount) {
        return new FlakyTestAnalyzer.FlakyTestResult(
            testName,
            passCount,
            failCount,
            passCount + failCount,
            0.5,  // Simplified flakiness score
            (double) passCount / (passCount + failCount),
            List.of()
        );
    }

    private FlakyTestAnalyzer.RunSummary createRunSummary(int passed, int failed, int skipped) {
        return new FlakyTestAnalyzer.RunSummary(
            1,
            List.of(),
            passed,
            failed,
            skipped,
            java.time.Duration.ofMillis(1000),
            failed > 0 ? 1 : 0
        );
    }

    private FlakyTestAnalyzer.FlakyTestReport createReport(
        List<FlakyTestAnalyzer.FlakyTestResult> flakyTests,
        List<FlakyTestAnalyzer.ConsistentFailure> consistentFailures,
        List<String> alwaysPassing
    ) {
        Map<String, List<FlakyTestAnalyzer.TestRunResult>> allResults = new java.util.LinkedHashMap<>();
        for (String test : alwaysPassing) {
            allResults.put(test, List.of(createPassedResult(test)));
        }

        return new FlakyTestAnalyzer.FlakyTestReport(
            "test-module",
            5,
            List.of(createRunSummary(alwaysPassing.size(), consistentFailures.size(), 0)),
            flakyTests,
            consistentFailures,
            alwaysPassing,
            Map.of(),
            allResults
        );
    }

    private FlakyTestAnalyzer.FlakyTestReport createReportWithRunSummaries(
        List<FlakyTestAnalyzer.RunSummary> runSummaries
    ) {
        return new FlakyTestAnalyzer.FlakyTestReport(
            "test-module",
            runSummaries.size(),
            runSummaries,
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of()
        );
    }
}
