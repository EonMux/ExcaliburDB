package com.eonmux.excalibur.core.logging;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Helper class for structured logging with MDC (Mapped Diagnostic Context).
 *
 * <p>This class provides utilities for managing correlation IDs and other
 * context information in a thread-safe manner. All MDC operations are
 * wrapped in try-finally blocks to ensure proper cleanup.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Automatic correlation ID propagation via MDC</li>
 *   <li>Thread-safe context management</li>
 *   <li>Automatic cleanup in finally blocks</li>
 *   <li>Support for nested contexts with scope management</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // In request handler
 * String correlationId = UUID.randomUUID().toString().substring(0, 8);
 *
 * // Using runWithCorrelationId for automatic cleanup
 * StructuredLoggingHelper.runWithCorrelationId(correlationId, () -> {
 *     LOGGER.info("Processing request");  // correlationId automatically included
 *     // ... business logic ...
 * });
 *
 * // Or manually manage the context
 * try {
 *     StructuredLoggingHelper.setCorrelationId(correlationId);
 *     LOGGER.info("Processing request");
 *     // ... business logic ...
 * } finally {
 *     StructuredLoggingHelper.clearCorrelationId();
 * }
 * }</pre>
 *
 * <h2>MDC Fields</h2>
 * <p>The following MDC fields are supported:</p>
 * <ul>
 *   <li>{@code correlationId} - Request correlation ID for tracing</li>
 *   <li>{@code queryId} - Query identifier for query-specific operations</li>
 *   <li>{@code sourceId} - Ingestion source identifier</li>
 *   <li>{@code documentId} - Document being processed</li>
 *   <li>{@code segmentId} - Segment being accessed</li>
 *   <li>{@code stage} - Pipeline stage being executed</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. MDC uses thread-local storage, so each thread
 * has its own independent context. When using virtual threads, the context
 * does NOT automatically propagate to child threads - use the provided
 * methods to explicitly propagate context when needed.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class StructuredLoggingHelper {

    // MDC key constants
    public static final String CORRELATION_ID = "correlationId";
    public static final String QUERY_ID = "queryId";
    public static final String SOURCE_ID = "sourceId";
    public static final String DOCUMENT_ID = "documentId";
    public static final String SEGMENT_ID = "segmentId";
    public static final String STAGE = "stage";
    public static final String THREAD_NAME = "threadName";

    // Private constructor - utility class
    private StructuredLoggingHelper() {
    }

    // ==================== Correlation ID ====================

    /**
     * Sets the correlation ID in MDC.
     *
     * @param correlationId The correlation ID to set (may be null to clear)
     */
    public static void setCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.isEmpty()) {
            MDC.put(CORRELATION_ID, correlationId);
        } else {
            MDC.remove(CORRELATION_ID);
        }
    }

    /**
     * Clears the correlation ID from MDC.
     */
    public static void clearCorrelationId() {
        MDC.remove(CORRELATION_ID);
    }

    // ==================== Source ID ====================

    /**
     * Sets the source ID in MDC.
     *
     * @param sourceId The source ID to set (may be null to clear)
     */
    public static void setSourceId(String sourceId) {
        if (sourceId != null && !sourceId.isEmpty()) {
            MDC.put(SOURCE_ID, sourceId);
        } else {
            MDC.remove(SOURCE_ID);
        }
    }

    // ==================== Document ID ====================

    /**
     * Sets the document ID in MDC.
     *
     * @param documentId The document ID to set (may be null to clear)
     */
    public static void setDocumentId(String documentId) {
        if (documentId != null && !documentId.isEmpty()) {
            MDC.put(DOCUMENT_ID, documentId);
        } else {
            MDC.remove(DOCUMENT_ID);
        }
    }

    /**
     * Clears the document ID from MDC.
     */
    public static void clearDocumentId() {
        MDC.remove(DOCUMENT_ID);
    }

    // ==================== Stage ====================

    /**
     * Sets the pipeline stage in MDC.
     *
     * @param stage The stage name to set (may be null to clear)
     */
    public static void setStage(String stage) {
        if (stage != null && !stage.isEmpty()) {
            MDC.put(STAGE, stage);
        } else {
            MDC.remove(STAGE);
        }
    }

    // ==================== Clear All ====================

    /**
     * Clears all ExcaliburDB MDC fields.
     *
     * <p>This should be called in finally blocks to prevent context leakage
     * between requests when threads are reused.</p>
     */
    public static void clearAll() {
        MDC.remove(CORRELATION_ID);
        MDC.remove(QUERY_ID);
        MDC.remove(SOURCE_ID);
        MDC.remove(DOCUMENT_ID);
        MDC.remove(SEGMENT_ID);
        MDC.remove(STAGE);
        MDC.remove(THREAD_NAME);
    }

    // ==================== Context Capture and Restore ====================

    /**
     * Captures the current MDC context.
     *
     * <p>Use this to propagate context to other threads:</p>
     * <pre>{@code
     * Map<String, String> context = StructuredLoggingHelper.captureContext();
     * executor.submit(() -> {
     *     StructuredLoggingHelper.restoreContext(context);
     *     try {
     *         // ... work with context ...
     *     } finally {
     *         StructuredLoggingHelper.clearAll();
     *     }
     * });
     * }</pre>
     *
     * @return A copy of the current MDC context
     */
    public static Map<String, String> captureContext() {
        return MDC.getCopyOfContextMap();
    }

    /**
     * Restores a previously captured MDC context.
     *
     * @param context The context to restore (may be null)
     */
    public static void restoreContext(Map<String, String> context) {
        if (context != null) {
            MDC.setContextMap(context);
        } else {
            clearAll();
        }
    }

    // ==================== Runnable Wrappers ====================

    /**
     * Runs a Runnable with a correlation ID set in MDC, ensuring cleanup.
     *
     * @param correlationId The correlation ID to set
     * @param runnable The runnable to execute
     */
    public static void runWithCorrelationId(String correlationId, Runnable runnable) {
        setCorrelationId(correlationId);
        try {
            runnable.run();
        } finally {
            clearCorrelationId();
        }
    }

    /**
     * Runs a Runnable with multiple MDC fields set, ensuring cleanup.
     *
     * @param context The MDC context to set
     * @param runnable The runnable to execute
     */
    public static void runWithContext(Map<String, String> context, Runnable runnable) {
        if (context != null) {
            MDC.setContextMap(context);
        }
        try {
            runnable.run();
        } finally {
            clearAll();
        }
    }

    /**
     * Runs a Callable with a correlation ID set in MDC, ensuring cleanup.
     *
     * @param correlationId The correlation ID to set
     * @param callable The callable to execute
     * @param <T> The return type
     * @return The result of the callable
     * @throws Exception If the callable throws an exception
     */
    public static <T> T callWithCorrelationId(String correlationId, Callable<T> callable) throws Exception {
        setCorrelationId(correlationId);
        try {
            return callable.call();
        } finally {
            clearCorrelationId();
        }
    }

    /**
     * Runs a Callable with multiple MDC fields set, ensuring cleanup.
     *
     * @param context The MDC context to set
     * @param callable The callable to execute
     * @param <T> The return type
     * @return The result of the callable
     * @throws Exception If the callable throws an exception
     */
    public static <T> T callWithContext(Map<String, String> context, Callable<T> callable) throws Exception {
        if (context != null) {
            MDC.setContextMap(context);
        }
        try {
            return callable.call();
        } finally {
            clearAll();
        }
    }

    // ==================== Scoped Context ====================

    /**
     * Creates a scoped context that automatically clears when closed.
     *
     * <p>Usage with try-with-resources:</p>
     * <pre>{@code
     * try (ScopedContext ctx = StructuredLoggingHelper.scopedContext()
     *         .withCorrelationId(correlationId)
     *         .withStage("query")
     *         .build()) {
     *     LOGGER.info("Processing query");
     *     // ... context automatically cleared on exit ...
     * }
     * }</pre>
     *
     * @return A builder for creating a scoped context
     */
    public static ScopedContextBuilder scopedContext() {
        return new ScopedContextBuilder();
    }

    /**
     * Builder for creating scoped MDC contexts.
     */
    public static class ScopedContextBuilder {
        private String correlationId;
        private String queryId;
        private String sourceId;
        private String documentId;
        private String segmentId;
        private String stage;
        private String threadName;

        public ScopedContextBuilder withCorrelationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public ScopedContextBuilder withQueryId(String queryId) {
            this.queryId = queryId;
            return this;
        }

        public ScopedContextBuilder withSourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public ScopedContextBuilder withDocumentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public ScopedContextBuilder withSegmentId(String segmentId) {
            this.segmentId = segmentId;
            return this;
        }

        public ScopedContextBuilder withSegmentId(long segmentId) {
            this.segmentId = String.valueOf(segmentId);
            return this;
        }

        public ScopedContextBuilder withStage(String stage) {
            this.stage = stage;
            return this;
        }

        public ScopedContextBuilder withThreadName(String threadName) {
            this.threadName = threadName;
            return this;
        }

        /**
         * Builds and activates the scoped context.
         *
         * @return A ScopedContext that clears MDC when closed
         */
        public ScopedContext build() {
            return new ScopedContext(correlationId, queryId, sourceId, documentId, segmentId, stage, threadName);
        }
    }

    /**
     * An AutoCloseable scoped MDC context.
     *
     * <p>When closed, all MDC fields set by this scope are cleared.</p>
     */
    public static class ScopedContext implements AutoCloseable {
        private final boolean hadPreviousCorrelationId;
        private final String previousCorrelationId;
        private final boolean hadPreviousQueryId;
        private final String previousQueryId;
        private final boolean hadPreviousSourceId;
        private final String previousSourceId;
        private final boolean hadPreviousDocumentId;
        private final String previousDocumentId;
        private final boolean hadPreviousSegmentId;
        private final String previousSegmentId;
        private final boolean hadPreviousStage;
        private final String previousStage;
        private final boolean hadPreviousThreadName;
        private final String previousThreadName;

        private ScopedContext(String correlationId, String queryId, String sourceId,
                              String documentId, String segmentId, String stage, String threadName) {
            // Save previous values
            this.previousCorrelationId = MDC.get(CORRELATION_ID);
            this.hadPreviousCorrelationId = this.previousCorrelationId != null;
            this.previousQueryId = MDC.get(QUERY_ID);
            this.hadPreviousQueryId = this.previousQueryId != null;
            this.previousSourceId = MDC.get(SOURCE_ID);
            this.hadPreviousSourceId = this.previousSourceId != null;
            this.previousDocumentId = MDC.get(DOCUMENT_ID);
            this.hadPreviousDocumentId = this.previousDocumentId != null;
            this.previousSegmentId = MDC.get(SEGMENT_ID);
            this.hadPreviousSegmentId = this.previousSegmentId != null;
            this.previousStage = MDC.get(STAGE);
            this.hadPreviousStage = this.previousStage != null;
            this.previousThreadName = MDC.get(THREAD_NAME);
            this.hadPreviousThreadName = this.previousThreadName != null;

            // Set new values
            if (correlationId != null) MDC.put(CORRELATION_ID, correlationId);
            if (queryId != null) MDC.put(QUERY_ID, queryId);
            if (sourceId != null) MDC.put(SOURCE_ID, sourceId);
            if (documentId != null) MDC.put(DOCUMENT_ID, documentId);
            if (segmentId != null) MDC.put(SEGMENT_ID, segmentId);
            if (stage != null) MDC.put(STAGE, stage);
            if (threadName != null) MDC.put(THREAD_NAME, threadName);
        }

        @Override
        public void close() {
            // Restore previous values or clear
            if (hadPreviousCorrelationId) {
                MDC.put(CORRELATION_ID, previousCorrelationId);
            } else {
                MDC.remove(CORRELATION_ID);
            }

            if (hadPreviousQueryId) {
                MDC.put(QUERY_ID, previousQueryId);
            } else {
                MDC.remove(QUERY_ID);
            }

            if (hadPreviousSourceId) {
                MDC.put(SOURCE_ID, previousSourceId);
            } else {
                MDC.remove(SOURCE_ID);
            }

            if (hadPreviousDocumentId) {
                MDC.put(DOCUMENT_ID, previousDocumentId);
            } else {
                MDC.remove(DOCUMENT_ID);
            }

            if (hadPreviousSegmentId) {
                MDC.put(SEGMENT_ID, previousSegmentId);
            } else {
                MDC.remove(SEGMENT_ID);
            }

            if (hadPreviousStage) {
                MDC.put(STAGE, previousStage);
            } else {
                MDC.remove(STAGE);
            }

            if (hadPreviousThreadName) {
                MDC.put(THREAD_NAME, previousThreadName);
            } else {
                MDC.remove(THREAD_NAME);
            }
        }
    }
}
