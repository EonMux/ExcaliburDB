/*
 * excalibur-test - JUnit XML Report Reader Tests
 */

package io.excalibur.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for JUnitReportReader.
 */
@DisplayName("JUnitReportReader")
class JUnitReportReaderTest {

    @TempDir
    Path reportDirectory;

    private Path writeReport(String name, String body) throws IOException {
        Path report = reportDirectory.resolve(name);
        Files.writeString(report, """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="suite" tests="1">
            %s
            </testsuite>
            """.formatted(body));
        return report;
    }

    @Nested
    @DisplayName("Parsing a single report")
    class ParseReportTests {

        @Test
        @DisplayName("should read a passing test case")
        void shouldReadPassingTestCase() throws IOException {
            Path report = writeReport("TEST-passing.xml",
                "<testcase name=\"queryReturnsResults\" classname=\"excalibur.QueryTest\" time=\"0.1\"/>");

            List<FlakyTestAnalyzer.TestRunResult> results = JUnitReportReader.parseReport(report, 1);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).testName()).isEqualTo("excalibur.QueryTest.queryReturnsResults");
            assertThat(results.get(0).passed()).isTrue();
            assertThat(results.get(0).runNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("should read a failing test case with its assertion message")
        void shouldReadFailingTestCaseWithMessage() throws IOException {
            Path report = writeReport("TEST-failing.xml", """
                <testcase name="queryReturnsResults" classname="excalibur.QueryTest" time="0.1">
                  <failure message="ingested documents not found in query results">stack trace here</failure>
                </testcase>
                """);

            List<FlakyTestAnalyzer.TestRunResult> results = JUnitReportReader.parseReport(report, 2);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).failed()).isTrue();
            assertThat(results.get(0).errorMessage())
                .isEqualTo("ingested documents not found in query results");
        }

        @Test
        @DisplayName("should read a skipped test case")
        void shouldReadSkippedTestCase() throws IOException {
            Path report = writeReport("TEST-skipped.xml", """
                <testcase name="slowPath" classname="excalibur.QueryTest" time="0.0">
                  <skipped/>
                </testcase>
                """);

            List<FlakyTestAnalyzer.TestRunResult> results = JUnitReportReader.parseReport(report, 3);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).skipped()).isTrue();
            assertThat(results.get(0).errorMessage()).isNull();
        }

        @Test
        @DisplayName("should treat an error element as a failure")
        void shouldTreatErrorAsFailure() throws IOException {
            Path report = writeReport("TEST-error.xml", """
                <testcase name="setUp" classname="excalibur.QueryTest" time="0.0">
                  <error message="Arena already closed" type="java.lang.IllegalStateException"/>
                </testcase>
                """);

            List<FlakyTestAnalyzer.TestRunResult> results = JUnitReportReader.parseReport(report, 1);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).failed()).isTrue();
            assertThat(results.get(0).errorMessage()).isEqualTo("Arena already closed");
        }

        @Test
        @DisplayName("should fall back to the stack trace when no message attribute is present")
        void shouldFallBackToStackTrace() throws IOException {
            Path report = writeReport("TEST-nomessage.xml", """
                <testcase name="queryReturnsResults" classname="excalibur.QueryTest" time="0.1">
                  <failure type="java.lang.NullPointerException">java.lang.NullPointerException: boom
                	at excalibur.QueryTest.queryReturnsResults(QueryTest.java:42)</failure>
                </testcase>
                """);

            List<FlakyTestAnalyzer.TestRunResult> results = JUnitReportReader.parseReport(report, 1);

            assertThat(results.get(0).errorMessage())
                .isEqualTo("java.lang.NullPointerException: boom");
        }

        @Test
        @DisplayName("should read every test case in a suite")
        void shouldReadEverySuiteEntry() throws IOException {
            Path report = writeReport("TEST-suite.xml", """
                <testcase name="a" classname="excalibur.QueryTest" time="0.1"/>
                <testcase name="b" classname="excalibur.QueryTest" time="0.1">
                  <skipped/>
                </testcase>
                <testcase name="c" classname="excalibur.QueryTest" time="0.1">
                  <failure message="boom" type="java.lang.AssertionError"/>
                </testcase>
                """);

            List<FlakyTestAnalyzer.TestRunResult> results = JUnitReportReader.parseReport(report, 1);

            assertThat(results).hasSize(3);
            assertThat(results.stream().filter(FlakyTestAnalyzer.TestRunResult::passed).count()).isEqualTo(1);
            assertThat(results.stream().filter(FlakyTestAnalyzer.TestRunResult::skipped).count()).isEqualTo(1);
            assertThat(results.stream().filter(FlakyTestAnalyzer.TestRunResult::failed).count()).isEqualTo(1);
        }

        @Test
        @DisplayName("should reject a malformed report instead of reporting zero tests")
        void shouldRejectMalformedReport() throws IOException {
            Path report = reportDirectory.resolve("TEST-broken.xml");
            Files.writeString(report, "<testsuite><testcase name=\"a\"");

            assertThatThrownBy(() -> JUnitReportReader.parseReport(report, 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Malformed test report");
        }
    }

    @Nested
    @DisplayName("Reading a report directory")
    class ReadReportsTests {

        @Test
        @DisplayName("should return nothing when the directory does not exist")
        void shouldReturnNothingForMissingDirectory() throws IOException {
            List<FlakyTestAnalyzer.TestRunResult> results = JUnitReportReader.readReports(
                reportDirectory.resolve("absent"), Instant.now(), 1);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should collect every fresh report in the directory")
        void shouldCollectEveryFreshReport() throws IOException {
            Instant runStart = Instant.now();
            writeReport("TEST-a.xml",
                "<testcase name=\"a\" classname=\"excalibur.QueryTest\" time=\"0.1\"/>");
            writeReport("TEST-b.xml",
                "<testcase name=\"b\" classname=\"excalibur.QueryTest\" time=\"0.1\"/>");

            List<FlakyTestAnalyzer.TestRunResult> results =
                JUnitReportReader.readReports(reportDirectory, runStart, 4);

            assertThat(results).hasSize(2);
            assertThat(results).allMatch(r -> r.runNumber() == 4);
        }

        @Test
        @DisplayName("should skip reports left over from an earlier run")
        void shouldSkipStaleReports() throws IOException {
            Path stale = writeReport("TEST-stale.xml",
                "<testcase name=\"old\" classname=\"excalibur.QueryTest\" time=\"0.1\"/>");
            Instant runStart = Instant.now();
            Files.setLastModifiedTime(stale, FileTime.from(runStart.minusSeconds(600)));
            writeReport("TEST-fresh.xml",
                "<testcase name=\"new\" classname=\"excalibur.QueryTest\" time=\"0.1\"/>");

            List<FlakyTestAnalyzer.TestRunResult> results =
                JUnitReportReader.readReports(reportDirectory, runStart, 1);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).testName()).isEqualTo("excalibur.QueryTest.new");
        }

        @Test
        @DisplayName("should ignore non-XML files in the directory")
        void shouldIgnoreNonXmlFiles() throws IOException {
            Instant runStart = Instant.now();
            Files.writeString(reportDirectory.resolve("output.txt"), "not a report");
            writeReport("TEST-a.xml",
                "<testcase name=\"a\" classname=\"excalibur.QueryTest\" time=\"0.1\"/>");

            List<FlakyTestAnalyzer.TestRunResult> results =
                JUnitReportReader.readReports(reportDirectory, runStart, 1);

            assertThat(results).hasSize(1);
        }
    }
}
