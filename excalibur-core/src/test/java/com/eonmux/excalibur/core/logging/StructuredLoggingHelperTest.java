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

    @Nested
    @DisplayName("Correlation ID")
    class CorrelationIdTests {

        @Test
        @DisplayName("sets and gets correlation ID")
        void setAndGetCorrelationId() {
            String testId = "abc12345";

            StructuredLoggingHelper.setCorrelationId(testId);

            assertThat(StructuredLoggingHelper.getCorrelationId()).isEqualTo(testId);
            assertThat(MDC.get("correlationId")).isEqualTo(testId);
        }

        @Test
        @DisplayName("clears correlation ID")
        void clearCorrelationId() {
            StructuredLoggingHelper.setCorrelationId("test-id");
            StructuredLoggingHelper.clearCorrelationId();

            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
            assertThat(MDC.get("correlationId")).isNull();
        }

        @Test
        @DisplayName("handles null correlation ID")
        void handlesNullCorrelationId() {
            StructuredLoggingHelper.setCorrelationId("existing-id");
            StructuredLoggingHelper.setCorrelationId(null);

            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
        }

        @Test
        @DisplayName("handles empty correlation ID")
        void handlesEmptyCorrelationId() {
            StructuredLoggingHelper.setCorrelationId("existing-id");
            StructuredLoggingHelper.setCorrelationId("");

            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
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

            assertThat(StructuredLoggingHelper.getSourceId()).isEqualTo(sourceId);
            assertThat(MDC.get("sourceId")).isEqualTo(sourceId);
        }

        @Test
        @DisplayName("clears source ID")
        void clearSourceId() {
            StructuredLoggingHelper.setSourceId("test-source");
            StructuredLoggingHelper.clearSourceId();

            assertThat(StructuredLoggingHelper.getSourceId()).isNull();
        }
    }

    @Nested
    @DisplayName("Query ID")
    class QueryIdTests {

        @Test
        @DisplayName("sets and gets query ID")
        void setAndGetQueryId() {
            String queryId = "query-123";

            StructuredLoggingHelper.setQueryId(queryId);

            assertThat(StructuredLoggingHelper.getQueryId()).isEqualTo(queryId);
            assertThat(MDC.get("queryId")).isEqualTo(queryId);
        }

        @Test
        @DisplayName("clears query ID")
        void clearQueryId() {
            StructuredLoggingHelper.setQueryId("test-query");
            StructuredLoggingHelper.clearQueryId();

            assertThat(StructuredLoggingHelper.getQueryId()).isNull();
        }
    }

    @Nested
    @DisplayName("Segment ID")
    class SegmentIdTests {

        @Test
        @DisplayName("sets segment ID from string")
        void setSegmentIdFromString() {
            String segmentId = "segment-42";

            StructuredLoggingHelper.setSegmentId(segmentId);

            assertThat(StructuredLoggingHelper.getSegmentId()).isEqualTo(segmentId);
            assertThat(MDC.get("segmentId")).isEqualTo(segmentId);
        }

        @Test
        @DisplayName("sets segment ID from long")
        void setSegmentIdFromLong() {
            long segmentId = 12345L;

            StructuredLoggingHelper.setSegmentId(segmentId);

            assertThat(StructuredLoggingHelper.getSegmentId()).isEqualTo("12345");
            assertThat(MDC.get("segmentId")).isEqualTo("12345");
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

            assertThat(StructuredLoggingHelper.getStage()).isEqualTo(stage);
            assertThat(MDC.get("stage")).isEqualTo(stage);
        }

        @Test
        @DisplayName("clears stage")
        void clearStage() {
            StructuredLoggingHelper.setStage("crawling");
            StructuredLoggingHelper.clearStage();

            assertThat(StructuredLoggingHelper.getStage()).isNull();
        }
    }

    @Nested
    @DisplayName("Clear All")
    class ClearAllTests {

        @Test
        @DisplayName("clears all MDC fields")
        void clearsAllFields() {
            // Set all fields
            StructuredLoggingHelper.setCorrelationId("corr-1");
            StructuredLoggingHelper.setQueryId("query-1");
            StructuredLoggingHelper.setSourceId("source-1");
            StructuredLoggingHelper.setDocumentId("doc-1");
            StructuredLoggingHelper.setSegmentId("seg-1");
            StructuredLoggingHelper.setStage("test");
            StructuredLoggingHelper.setThreadName("thread-1");

            // Clear all
            StructuredLoggingHelper.clearAll();

            // Verify all are null
            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
            assertThat(StructuredLoggingHelper.getQueryId()).isNull();
            assertThat(StructuredLoggingHelper.getSourceId()).isNull();
            assertThat(StructuredLoggingHelper.getDocumentId()).isNull();
            assertThat(StructuredLoggingHelper.getSegmentId()).isNull();
            assertThat(StructuredLoggingHelper.getStage()).isNull();
            assertThat(StructuredLoggingHelper.getThreadName()).isNull();
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
            assertThat(StructuredLoggingHelper.getCorrelationId()).isEqualTo("original");
            assertThat(StructuredLoggingHelper.getSourceId()).isEqualTo("original-source");
        }

        @Test
        @DisplayName("handles null context on restore")
        void handlesNullContextOnRestore() {
            StructuredLoggingHelper.setCorrelationId("existing");

            StructuredLoggingHelper.restoreContext(null);

            // Should clear all
            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
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
                capturedId.set(StructuredLoggingHelper.getCorrelationId());
            });

            assertThat(capturedId.get()).isEqualTo("test-corr-id");
            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
        }

        @Test
        @DisplayName("runWithCorrelationId clears context on exception")
        void runWithCorrelationIdClearsOnException() {
            assertThatCode(() -> StructuredLoggingHelper.runWithCorrelationId("test-id", () -> {
                throw new RuntimeException("Test exception");
            })).isInstanceOf(RuntimeException.class);

            // Context should still be cleared
            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
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
                capturedCorr.set(StructuredLoggingHelper.getCorrelationId());
                capturedSource.set(StructuredLoggingHelper.getSourceId());
            });

            assertThat(capturedCorr.get()).isEqualTo("corr-1");
            assertThat(capturedSource.get()).isEqualTo("source-1");
            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
            assertThat(StructuredLoggingHelper.getSourceId()).isNull();
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

                assertThat(StructuredLoggingHelper.getCorrelationId()).isEqualTo("scoped-corr");
                assertThat(StructuredLoggingHelper.getSourceId()).isEqualTo("scoped-source");
            }

            // Context should be cleared after try-with-resources
            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
            assertThat(StructuredLoggingHelper.getSourceId()).isNull();
        }

        @Test
        @DisplayName("sets segment ID from long")
        void setsSegmentIdFromLong() {
            try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                    .withSegmentId(42L)
                    .build()) {

                assertThat(StructuredLoggingHelper.getSegmentId()).isEqualTo("42");
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

                assertThat(StructuredLoggingHelper.getCorrelationId()).isEqualTo("inner-corr");
                assertThat(StructuredLoggingHelper.getSourceId()).isEqualTo("outer-source"); // Preserved
            }

            // Outer values should be restored
            assertThat(StructuredLoggingHelper.getCorrelationId()).isEqualTo("outer-corr");
            assertThat(StructuredLoggingHelper.getSourceId()).isEqualTo("outer-source");
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
            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
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

                assertThat(StructuredLoggingHelper.getCorrelationId()).isEqualTo("corr-1");
                assertThat(StructuredLoggingHelper.getQueryId()).isEqualTo("query-1");
                assertThat(StructuredLoggingHelper.getSourceId()).isEqualTo("source-1");
                assertThat(StructuredLoggingHelper.getDocumentId()).isEqualTo("doc-1");
                assertThat(StructuredLoggingHelper.getSegmentId()).isEqualTo("seg-1");
                assertThat(StructuredLoggingHelper.getStage()).isEqualTo("test-stage");
                assertThat(StructuredLoggingHelper.getThreadName()).isEqualTo("test-thread");
            }

            // All should be cleared
            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
            assertThat(StructuredLoggingHelper.getQueryId()).isNull();
            assertThat(StructuredLoggingHelper.getSourceId()).isNull();
            assertThat(StructuredLoggingHelper.getDocumentId()).isNull();
            assertThat(StructuredLoggingHelper.getSegmentId()).isNull();
            assertThat(StructuredLoggingHelper.getStage()).isNull();
            assertThat(StructuredLoggingHelper.getThreadName()).isNull();
        }
    }

    @Nested
    @DisplayName("Callable Wrappers")
    class CallableWrapperTests {

        @Test
        @DisplayName("callWithCorrelationId returns result and clears context")
        void callWithCorrelationId() throws Exception {
            String result = StructuredLoggingHelper.callWithCorrelationId("call-corr", () -> {
                assertThat(StructuredLoggingHelper.getCorrelationId()).isEqualTo("call-corr");
                return "success";
            });

            assertThat(result).isEqualTo("success");
            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
        }

        @Test
        @DisplayName("callWithCorrelationId clears context on exception")
        void callWithCorrelationIdClearsOnException() {
            assertThatCode(() -> StructuredLoggingHelper.callWithCorrelationId("call-corr", () -> {
                throw new Exception("Test exception");
            })).isInstanceOf(Exception.class);

            assertThat(StructuredLoggingHelper.getCorrelationId()).isNull();
        }
    }
}
