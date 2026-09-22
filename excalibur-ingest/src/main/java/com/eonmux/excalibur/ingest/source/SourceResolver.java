package com.eonmux.excalibur.ingest.source;

/**
 * Functional interface for resolving source IDs to Source instances.
 *
 * <p>SourceResolver provides a decoupled mechanism for looking up Source
 * instances by their ID, enabling the reindexing infrastructure to work
 * without direct knowledge of source storage implementations.</p>
 *
 * <h2>Usage</h2>
 * <p>This interface is used by:</p>
 * <ul>
 *   <li>{@code ReindexEndpoint} - to resolve sources for API-triggered reindexing</li>
 *   <li>{@code IndexOrchestrator} - to resolve sources for scheduled reindexing</li>
 * </ul>
 *
 * <h2>Implementations</h2>
 * <p>Typical implementations delegate to a source registry:</p>
 * <pre>{@code
 * SourceRegistry registry = ...;
 * SourceResolver resolver = registry::get;
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe. Multiple threads may call
 * {@link #resolve(String)} concurrently.</p>
 *
 * @author ExcaliburDB
 * @since 8.0
 * @see Source
 */
@FunctionalInterface
public interface SourceResolver {

    /**
     * Resolves a source ID to its corresponding Source instance.
     *
     * <p>Domain constraints:</p>
     * <ul>
     *   <li>Returns {@code null} if the source is not found (not an exception)</li>
     *   <li>Must be thread-safe for concurrent access</li>
     *   <li>Should be fast (O(1) lookup preferred)</li>
     * </ul>
     *
     * @param sourceId The source ID to resolve (non-null, non-empty)
     * @return The Source instance, or {@code null} if not found
     * @throws NullPointerException if sourceId is null
     */
    Source resolve(String sourceId);
}
