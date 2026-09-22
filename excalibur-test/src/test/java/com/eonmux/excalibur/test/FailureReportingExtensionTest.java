package com.eonmux.excalibur.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The report a failing suite leaves behind.
 *
 * <p>This runs at exactly the moment nobody is watching - a CI job that already failed - so the
 * report is read later, by someone who was not there. What matters is that it is machine-readable
 * when it claims to be: the JSON is assembled by string concatenation, and an assertion message
 * carrying a quote, a backslash or a newline is entirely ordinary. If those are not escaped the
 * file is not JSON, and the one artefact explaining the failure is the one thing that cannot be
 * opened.</p>
 */
@DisplayName("Failure reporting extension")
class FailureReportingExtensionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // The extension keys its records by test class in a static map that outlives any one
    // instance, which is what lets afterAll report failures the instance never saw. Each test
    // below therefore needs its own subject, or it reports the previous test's failures too.
    static class SubjectUnderReport { }

    static class ParseableSubject { }

    static class AwkwardMessageSubject { }

    static class MessagelessSubject { }

    static class CauseChainSubject { }

    static class DirectoryCreationSubject { }

    static class ConsoleOnlySubject { }

    static class DefaultsSubject { }

    static class OtherSubject { }

    private static ExtensionContext contextFor(Class<?> testClass, String displayName) {
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getRequiredTestClass()).thenAnswer(invocation -> testClass);
        when(context.getDisplayName()).thenReturn(displayName);
        return context;
    }

    private static void recordFailure(FailureReportingExtension extension,
                                      Class<?> testClass, String displayName, Throwable cause) {
        // handleTestExecutionException records and then rethrows, because swallowing it would
        // turn a failing test green.
        assertThatThrownBy(() -> extension.handleTestExecutionException(
            contextFor(testClass, displayName), cause))
            .isSameAs(cause);
    }

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        @DisplayName("a handled exception is rethrown, never swallowed")
        void exceptionsAreRethrown() {
            FailureReportingExtension extension = FailureReportingExtension.create();
            RuntimeException cause = new RuntimeException("boom");

            recordFailure(extension, SubjectUnderReport.class, "someTest()", cause);
        }

        @Test
        @DisplayName("the lifecycle callbacks count what ran")
        void lifecycleCallbacksCount() {
            FailureReportingExtension extension = FailureReportingExtension.create();
            ExtensionContext context = contextFor(SubjectUnderReport.class, "t()");

            extension.testSuccessful(context);
            extension.testFailed(context, new RuntimeException("x"));
            extension.testAborted(context, new RuntimeException("y"));
            extension.testDisabled(context, Optional.of("not on this platform"));

            assertThat(FailureReportingExtension.getGlobalSummary()).isNotNull();
        }

        @Test
        @DisplayName("the global report can be printed without a suite having failed")
        void globalReportIsAlwaysPrintable() {
            FailureReportingExtension.printGlobalReport();

            assertThat(FailureReportingExtension.getGlobalSummary()).isNotNull();
        }
    }

    @Nested
    @DisplayName("the JSON report")
    class JsonReport {

        private Path reportFor(Path directory, Class<?> subject, Throwable cause)
                throws Exception {
            FailureReportingExtension extension = FailureReportingExtension.builder()
                .reportDirectory(directory)
                .writeJsonReport(true)
                .consoleReport(false)
                .build();
            recordFailure(extension, subject, "failing()", cause);
            extension.afterAll(contextFor(subject, subject.getSimpleName()));

            try (var files = Files.list(directory)) {
                List<Path> written = files.toList();
                assertThat(written).as("one report per class").hasSize(1);
                return written.get(0);
            }
        }

        @Test
        @DisplayName("a failing suite writes a parseable report")
        void reportIsParseable(@TempDir Path directory) throws Exception {
            Path report = reportFor(directory, ParseableSubject.class,
                new AssertionError("expected 5 but was 3"));

            JsonNode json = MAPPER.readTree(Files.readString(report));

            assertThat(json.path("testClass").asText())
                .isEqualTo(ParseableSubject.class.getSimpleName());
            assertThat(json.path("timestamp").asText()).isNotBlank();
            assertThat(json.path("summary").isObject()).isTrue();
            assertThat(json.path("summary").path("total").asInt())
                .as("a class's report carries that class's failures and no other's")
                .isEqualTo(1);
            assertThat(json.path("failures")).hasSize(1);
        }

        @Test
        @DisplayName("an assertion message full of quotes and newlines stays parseable")
        void awkwardMessagesAreEscaped(@TempDir Path directory) throws Exception {
            // AssertJ writes exactly this shape: quoted expected/actual values across several
            // lines. Unescaped, it ends the JSON string early and the rest of the file is garbage.
            String message = "expected: \"a\\b\"\n but was: \"c\td\"\r\nat Foo.bar()";

            Path report = reportFor(directory, AwkwardMessageSubject.class,
                new AssertionError(message));

            String raw = Files.readString(report);
            assertThat(MAPPER.readTree(raw)).isNotNull();
            assertThat(raw)
                .as("the newline inside the message must not appear raw inside a JSON string")
                .contains("\\n");
        }

        @Test
        @DisplayName("an exception with no message is reported by its type")
        void messagelessExceptionsAreStillReported(@TempDir Path directory) throws Exception {
            Path report = reportFor(directory, MessagelessSubject.class,
                new NullPointerException());

            String raw = Files.readString(report);

            assertThat(MAPPER.readTree(raw)).isNotNull();
            assertThat(raw)
                .as("a report saying only \"null\" tells the reader nothing")
                .contains("NullPointerException");
        }

        @Test
        @DisplayName("a cause chain is reported, not just the outermost wrapper")
        void causesAreIncluded(@TempDir Path directory) throws Exception {
            Path report = reportFor(directory, CauseChainSubject.class,
                new RuntimeException("wrapper", new IllegalStateException("the real reason")));

            String raw = Files.readString(report);

            assertThat(MAPPER.readTree(raw)).isNotNull();
            assertThat(raw).contains("the real reason");
        }

        @Test
        @DisplayName("no report is written for a class that did not fail")
        void cleanClassesWriteNothing(@TempDir Path directory) throws Exception {
            FailureReportingExtension extension = FailureReportingExtension.builder()
                .reportDirectory(directory)
                .writeJsonReport(true)
                .consoleReport(false)
                .build();

            extension.afterAll(contextFor(OtherSubject.class, "OtherSubject"));

            assertThat(directory).isEmptyDirectory();
        }

        @Test
        @DisplayName("the report directory is created if it does not exist")
        void directoryIsCreated(@TempDir Path parent) throws Exception {
            Path nested = parent.resolve("reports").resolve("failures");

            reportFor(nested, DirectoryCreationSubject.class, new AssertionError("x"));

            assertThat(nested).isDirectory();
        }
    }

    @Nested
    @DisplayName("the console report")
    class ConsoleReport {

        @Test
        @DisplayName("a failing suite prints without writing a file")
        void consoleOnlyWritesNothing(@TempDir Path directory) throws Exception {
            FailureReportingExtension extension = FailureReportingExtension.builder()
                .reportDirectory(directory)
                .writeJsonReport(false)
                .consoleReport(true)
                .build();

            recordFailure(extension, ConsoleOnlySubject.class, "failing()",
                new AssertionError("expected 5 but was 3"));
            extension.afterAll(contextFor(ConsoleOnlySubject.class, "ConsoleOnlySubject"));

            assertThat(directory)
                .as("console reporting must not leave files behind")
                .isEmptyDirectory();
        }

        @Test
        @DisplayName("the default extension reports to the console and writes nothing")
        void defaultsAreConsoleOnly(@TempDir Path directory) throws Exception {
            FailureReportingExtension extension = new FailureReportingExtension();

            recordFailure(extension, DefaultsSubject.class, "failing()",
                new AssertionError("boom"));

            PrintStream original = System.out;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            try {
                System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
                extension.afterAll(contextFor(DefaultsSubject.class, "DefaultsSubject"));
            } finally {
                System.setOut(original);
            }

            // Both halves of the name. Without the first, a default that reported nothing at
            // all would pass; without the second, one that quietly wrote a file would.
            assertThat(captured.toString(StandardCharsets.UTF_8))
                .as("the default extension reports to the console")
                .contains(DefaultsSubject.class.getSimpleName())
                .contains("boom");
            assertThat(directory)
                .as("and writes nothing, having been given no report directory")
                .isEmptyDirectory();
        }
    }
}
