package com.eonmux.excalibur.shard;

/**
 * Functional interface for handling reindex operations.
 *
 * <p>This interface decouples the IndexOrchestrator from the actual reindex
 * implementation, allowing the excalibur-shard module to trigger reindex
 * operations without depending on the excalibur-ingest module.</p>
 *
 * <h2>Usage</h2>
 * <p>The handler is typically implemented at the API layer where both
 * excalibur-shard and excalibur-ingest modules are available:</p>
 * <pre>{@code
 * // At API layer (ExcaliburDBServer)
 * orchestrator.setReindexOperationHandler((sourceId) -> {
 *     Source source = sourceResolver.resolve(sourceId);
 *     if (source == null) return false;
 *     ReindexResult result = documentReindexer.reindexAll(source);
 *     return !result.hasErrors();
 * });
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe. The handler may be called
 * concurrently from multiple threads.</p>
 *
 * @author ExcaliburDB
 * @since 5.0
 * @see IndexOrchestrator#setReindexOperationHandler(ReindexOperationHandler)
 */
@FunctionalInterface
public interface ReindexOperationHandler {

    /**
     * Executes a reindex operation for the specified source.
     *
     * <p>Domain constraints:</p>
     * <ul>
     *   <li>sourceId must be non-null and non-empty</li>
     *   <li>Returns {@code true} if reindex succeeded (even with partial errors)</li>
     *   <li>Returns {@code false} if reindex failed completely or source not found</li>
     *   <li>Should handle exceptions internally and return false on error</li>
     * </ul>
     *
     * @param sourceId The source ID to reindex (non-null)
     * @return {@code true} if reindex succeeded, {@code false} otherwise
     */
    boolean executeReindex(String sourceId);
}
