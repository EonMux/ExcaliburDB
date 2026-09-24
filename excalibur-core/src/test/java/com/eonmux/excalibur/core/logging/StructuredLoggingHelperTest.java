package com.eonmux.excalibur.core.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link StructuredLoggingHelper}.
 *
 * <p>Verifies that MDC context is properly managed for structured logging:</p>
 * <ul>
 *   <li>Correlation IDs are set and cleared correctly</li>
 *   <li>Scoped contexts clean up properly on close</li>
 *   <li>Context can be captured and restored for thread propagation</li>
 *   <li>Nested contexts preserve and restore previous values</li>
 * </ul>
 */
@DisplayName("StructuredLoggingHelper")
class StructuredLoggingHelperTest {

    @BeforeEach
    void setUp() {
        // Ensure MDC is clean before each test
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        // Clean up MDC after each test
        MDC.clear();
    }

    private static String mdc(String key) {
        return MDC.get(key);
    }

    @Nested
    @DisplayName("Correlation ID")
    class CorrelationIdTests {

        @Test
        @DisplayName("sets and gets correlation ID")
        void setAndGetCorrelationId() {
            String testId = "abc12345";

            StructuredLoggingHelper.setCorrelationId(testId);

            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isEqualTo(testId);
            assertThat(MDC.get("correlationId")).isEqualTo(testId);
        }

        @Test
        @DisplayName("clears correlation ID")
        void clearCorrelationId() {
            StructuredLoggingHelper.setCorrelationId("test-id");
            StructuredLoggingHelper.clearCorrelationId();

            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
            assertThat(MDC.get("correlationId")).isNull();
        }

        @Test
        @DisplayName("handles null correlation ID")
        void handlesNullCorrelationId() {
            StructuredLoggingHelper.setCorrelationId("existing-id");
            StructuredLoggingHelper.setCorrelationId(null);

            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("handles empty correlation ID")
        void handlesEmptyCorrelationId() {
            StructuredLoggingHelper.setCorrelationId("existing-id");
            StructuredLoggingHelper.setCorrelationId("");

            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("Source ID")
    class SourceIdTests {

        @Test
        @DisplayName("sets and gets source ID")
        void setAndGetSourceId() {
            String sourceId = "my-docs-source";

            StructuredLoggingHelper.setSourceId(sourceId);

            assertThat(mdc(StructuredLoggingHelper.SOURCE_ID)).isEqualTo(sourceId);
            assertThat(MDC.get("sourceId")).isEqualTo(sourceId);
        }

    }

    @Nested
    @DisplayName("Document ID")
    class DocumentIdTests {

        @Test
        @DisplayName("sets and clears document ID")
        void setAndClearDocumentId() {
            StructuredLoggingHelper.setDocumentId("doc-7");
            assertThat(mdc(StructuredLoggingHelper.DOCUMENT_ID)).isEqualTo("doc-7");

            StructuredLoggingHelper.clearDocumentId();
            assertThat(mdc(StructuredLoggingHelper.DOCUMENT_ID)).isNull();
        }

        @Test
        @DisplayName("a null or empty document ID clears it")
        void nullOrEmptyDocumentIdClears() {
            StructuredLoggingHelper.setDocumentId("doc-7");
            StructuredLoggingHelper.setDocumentId(null);
            assertThat(mdc(StructuredLoggingHelper.DOCUMENT_ID)).isNull();

            StructuredLoggingHelper.setDocumentId("doc-7");
            StructuredLoggingHelper.setDocumentId("");
            assertThat(mdc(StructuredLoggingHelper.DOCUMENT_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("Stage")
    class StageTests {

        @Test
        @DisplayName("sets and gets stage")
        void setAndGetStage() {
            String stage = "embedding";

            StructuredLoggingHelper.setStage(stage);

            assertThat(mdc(StructuredLoggingHelper.STAGE)).isEqualTo(stage);
            assertThat(MDC.get("stage")).isEqualTo(stage);
        }

    }

    @Nested
    @DisplayName("Clear All")
    class ClearAllTests {

        @Test
        @DisplayName("clears all MDC fields")
        void clearsAllFields() {
            MDC.put(StructuredLoggingHelper.CORRELATION_ID, "corr-1");
            MDC.put(StructuredLoggingHelper.QUERY_ID, "query-1");
            MDC.put(StructuredLoggingHelper.SOURCE_ID, "source-1");
            MDC.put(StructuredLoggingHelper.DOCUMENT_ID, "doc-1");
            MDC.put(StructuredLoggingHelper.SEGMENT_ID, "seg-1");
            MDC.put(StructuredLoggingHelper.STAGE, "test");
            MDC.put(StructuredLoggingHelper.THREAD_NAME, "thread-1");
            MDC.put("unrelated", "kept");

            // Clear all
            StructuredLoggingHelper.clearAll();

            // Verify all are null
            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.QUERY_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.SOURCE_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.DOCUMENT_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.SEGMENT_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.STAGE)).isNull();
            assertThat(mdc(StructuredLoggingHelper.THREAD_NAME)).isNull();
            assertThat(MDC.get("unrelated"))
                .as("only this helper's own keys are cleared")
                .isEqualTo("kept");
        }
    }

    @Nested
    @DisplayName("Context Capture and Restore")
    class ContextCaptureRestoreTests {

        @Test
        @DisplayName("captures current context")
        void capturesCurrentContext() {
            StructuredLoggingHelper.setCorrelationId("corr-1");
            StructuredLoggingHelper.setSourceId("source-1");

            Map<String, String> captured = StructuredLoggingHelper.captureContext();

            assertThat(captured).isNotNull();
            assertThat(captured.get("correlationId")).isEqualTo("corr-1");
            assertThat(captured.get("sourceId")).isEqualTo("source-1");
        }

        @Test
        @DisplayName("restores captured context")
        void restoresCapturedContext() {
            // Set initial context
            StructuredLoggingHelper.setCorrelationId("original");
            StructuredLoggingHelper.setSourceId("original-source");

            // Capture it
            Map<String, String> captured = StructuredLoggingHelper.captureContext();

            // Change context
            StructuredLoggingHelper.setCorrelationId("changed");
            StructuredLoggingHelper.setSourceId("changed-source");

            // Restore
            StructuredLoggingHelper.restoreContext(captured);

            // Verify restored values
            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isEqualTo("original");
            assertThat(mdc(StructuredLoggingHelper.SOURCE_ID)).isEqualTo("original-source");
        }

        @Test
        @DisplayName("handles null context on restore")
        void handlesNullContextOnRestore() {
            StructuredLoggingHelper.setCorrelationId("existing");

            StructuredLoggingHelper.restoreContext(null);

            // Should clear all
            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("Runnable Wrappers")
    class RunnableWrapperTests {

        @Test
        @DisplayName("runWithCorrelationId sets and clears context")
        void runWithCorrelationId() {
            AtomicReference<String> capturedId = new AtomicReference<>();

            StructuredLoggingHelper.runWithCorrelationId("test-corr-id", () -> {
                capturedId.set(mdc(StructuredLoggingHelper.CORRELATION_ID));
            });

            assertThat(capturedId.get()).isEqualTo("test-corr-id");
            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("runWithCorrelationId clears context on exception")
        void runWithCorrelationIdClearsOnException() {
            assertThatCode(() -> StructuredLoggingHelper.runWithCorrelationId("test-id", () -> {
                throw new RuntimeException("Test exception");
            })).isInstanceOf(RuntimeException.class);

            // Context should still be cleared
            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("runWithContext sets and clears multiple fields")
        void runWithContext() {
            Map<String, String> context = new HashMap<>();
            context.put("correlationId", "corr-1");
            context.put("sourceId", "source-1");

            AtomicReference<String> capturedCorr = new AtomicReference<>();
            AtomicReference<String> capturedSource = new AtomicReference<>();

            StructuredLoggingHelper.runWithContext(context, () -> {
                capturedCorr.set(mdc(StructuredLoggingHelper.CORRELATION_ID));
                capturedSource.set(mdc(StructuredLoggingHelper.SOURCE_ID));
            });

            assertThat(capturedCorr.get()).isEqualTo("corr-1");
            assertThat(capturedSource.get()).isEqualTo("source-1");
            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.SOURCE_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("Scoped Context")
    class ScopedContextTests {

        @Test
        @DisplayName("sets context and clears on close")
        void setsContextAndClearsOnClose() {
            try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                    .withCorrelationId("scoped-corr")
                    .withSourceId("scoped-source")
                    .build()) {

                assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isEqualTo("scoped-corr");
                assertThat(mdc(StructuredLoggingHelper.SOURCE_ID)).isEqualTo("scoped-source");
            }

            // Context should be cleared after try-with-resources
            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.SOURCE_ID)).isNull();
        }

        @Test
        @DisplayName("sets segment ID from long")
        void setsSegmentIdFromLong() {
            try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                    .withSegmentId(42L)
                    .build()) {

                assertThat(mdc(StructuredLoggingHelper.SEGMENT_ID)).isEqualTo("42");
            }
        }

        @Test
        @DisplayName("restores previous values on close")
        void restoresPreviousValuesOnClose() {
            // Set initial values
            StructuredLoggingHelper.setCorrelationId("outer-corr");
            StructuredLoggingHelper.setSourceId("outer-source");

            // Create nested scope
            try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                    .withCorrelationId("inner-corr")
                    .build()) {

                assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isEqualTo("inner-corr");
                assertThat(mdc(StructuredLoggingHelper.SOURCE_ID)).isEqualTo("outer-source"); // Preserved
            }

            // Outer values should be restored
            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isEqualTo("outer-corr");
            assertThat(mdc(StructuredLoggingHelper.SOURCE_ID)).isEqualTo("outer-source");
        }

        @Test
        @DisplayName("clears context on exception")
        void clearsContextOnException() {
            assertThatCode(() -> {
                try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                        .withCorrelationId("exception-corr")
                        .build()) {
                    throw new RuntimeException("Test exception");
                }
            }).isInstanceOf(RuntimeException.class);

            // Context should still be cleared (or restored to previous)
            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("supports all field types")
        void supportsAllFieldTypes() {
            try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                    .withCorrelationId("corr-1")
                    .withQueryId("query-1")
                    .withSourceId("source-1")
                    .withDocumentId("doc-1")
                    .withSegmentId("seg-1")
                    .withStage("test-stage")
                    .withThreadName("test-thread")
                    .build()) {

                assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isEqualTo("corr-1");
                assertThat(mdc(StructuredLoggingHelper.QUERY_ID)).isEqualTo("query-1");
                assertThat(mdc(StructuredLoggingHelper.SOURCE_ID)).isEqualTo("source-1");
                assertThat(mdc(StructuredLoggingHelper.DOCUMENT_ID)).isEqualTo("doc-1");
                assertThat(mdc(StructuredLoggingHelper.SEGMENT_ID)).isEqualTo("seg-1");
                assertThat(mdc(StructuredLoggingHelper.STAGE)).isEqualTo("test-stage");
                assertThat(mdc(StructuredLoggingHelper.THREAD_NAME)).isEqualTo("test-thread");
            }

            // All should be cleared
            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.QUERY_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.SOURCE_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.DOCUMENT_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.SEGMENT_ID)).isNull();
            assertThat(mdc(StructuredLoggingHelper.STAGE)).isNull();
            assertThat(mdc(StructuredLoggingHelper.THREAD_NAME)).isNull();
        }
    }

    @Nested
    @DisplayName("Callable Wrappers")
    class CallableWrapperTests {

        @Test
        @DisplayName("callWithCorrelationId returns result and clears context")
        void callWithCorrelationId() throws Exception {
            String result = StructuredLoggingHelper.callWithCorrelationId("call-corr", () -> {
                assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isEqualTo("call-corr");
                return "success";
            });

            assertThat(result).isEqualTo("success");
            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("callWithCorrelationId clears context on exception")
        void callWithCorrelationIdClearsOnException() {
            assertThatCode(() -> StructuredLoggingHelper.callWithCorrelationId("call-corr", () -> {
                throw new Exception("Test exception");
            })).isInstanceOf(Exception.class);

            assertThat(mdc(StructuredLoggingHelper.CORRELATION_ID)).isNull();
        }
    }
}
