/*
 * excalibur-test - JUnit XML Report Reader
 *
 * Package: com.eonmux.excalibur.test
 *
 * Thread Safety:
 *   - JUnitReportReader: Stateless, all methods are static and thread-safe
 */

package com.eonmux.excalibur.test;

import com.eonmux.excalibur.test.FlakyTestAnalyzer.TestRunResult;
import com.eonmux.excalibur.test.FlakyTestAnalyzer.TestStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the JUnit XML reports that Gradle writes for a test task.
 *
 * <p>The reports are the authoritative record of a test run: they are structured,
 * they carry the real failure message for every failed test, and unlike console
 * output they cannot be confused with a test's own stdout.</p>
 */
final class JUnitReportReader {

    /** Slack allowed between the run's start instant and a report's modification time */
    private static final long CLOCK_SKEW_SECONDS = 1;

    private JUnitReportReader() {
    }

    /**
     * Read every report in a directory that was written by the run starting at {@code notBefore}.
     *
     * <p>Reports left over from an earlier run are ignored, so a Gradle invocation that never
     * executed the tests can never be mistaken for a successful one.</p>
     *
     * @param resultsDirectory The test task's XML report directory
     * @param notBefore Reports older than this instant are stale and skipped
     * @param runNumber The 1-based run number to stamp on each result
     * @return All test results from this run, empty if the directory holds none
     * @throws IOException if the directory cannot be listed or a report is not well-formed
     */
    static List<TestRunResult> readReports(Path resultsDirectory, Instant notBefore, int runNumber)
            throws IOException {
        if (!Files.isDirectory(resultsDirectory)) {
            return List.of();
        }

        List<Path> reports;
        try (var files = Files.list(resultsDirectory)) {
            reports = files
                .filter(path -> path.getFileName().toString().endsWith(".xml"))
                .sorted()
                .toList();
        }

        Instant staleBefore = notBefore.minusSeconds(CLOCK_SKEW_SECONDS);
        List<TestRunResult> results = new ArrayList<>();
        for (Path report : reports) {
            if (Files.getLastModifiedTime(report).toInstant().isBefore(staleBefore)) {
                continue;
            }
            results.addAll(parseReport(report, runNumber));
        }
        return results;
    }

    /**
     * Parse a single JUnit XML report into test results.
     *
     * @param report Path to a {@code TEST-*.xml} report
     * @param runNumber The 1-based run number to stamp on each result
     * @return One result per {@code <testcase>} in the report
     * @throws IOException if the report cannot be read or is not well-formed
     */
    static List<TestRunResult> parseReport(Path report, int runNumber) throws IOException {
        Document document = parseXml(report);

        List<TestRunResult> results = new ArrayList<>();
        NodeList testCases = document.getElementsByTagName("testcase");
        for (int i = 0; i < testCases.getLength(); i++) {
            results.add(toResult((Element) testCases.item(i), runNumber));
        }
        return results;
    }

    private static Document parseXml(Path report) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(report.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Malformed test report: " + report, e);
        }
    }

    private static TestRunResult toResult(Element testCase, int runNumber) {
        String className = testCase.getAttribute("classname");
        String testName = testCase.getAttribute("name");
        String fullName = className.isEmpty() ? testName : className + "." + testName;

        Element failure = firstChild(testCase, "failure");
        if (failure == null) {
            failure = firstChild(testCase, "error");
        }
        if (failure != null) {
            return new TestRunResult(fullName, TestStatus.FAILED, failureMessage(failure), runNumber);
        }
        if (firstChild(testCase, "skipped") != null) {
            return new TestRunResult(fullName, TestStatus.SKIPPED, null, runNumber);
        }
        return new TestRunResult(fullName, TestStatus.PASSED, null, runNumber);
    }

    private static Element firstChild(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        return children.getLength() == 0 ? null : (Element) children.item(0);
    }

    /**
     * Extract a usable failure message, preferring the assertion message over the stack trace.
     */
    private static String failureMessage(Element failure) {
        String message = failure.getAttribute("message");
        if (!message.isBlank()) {
            return message.trim();
        }
        String body = failure.getTextContent();
        if (body != null && !body.isBlank()) {
            // The body is a stack trace; its first line carries the exception and message
            return body.strip().lines().findFirst().orElse("").trim();
        }
        return "Test failed";
    }
}
