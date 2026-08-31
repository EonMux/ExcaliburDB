package excalibur.api.endpoint;

import excalibur.ingest.source.Source;

import java.util.Set;

/**
 * Registry interface for managing sources.
 *
 * <p>The registry owns every {@link Source} it holds: sources are shared with the
 * ingestion pipeline and with every later reindex, so only the registry may close
 * one.</p>
 */
public interface SourceRegistry extends AutoCloseable {
    /**
     * Registers a new source, atomically claiming its ID.
     *
     * @param source The source to register
     * @throws SourceRegistrationException if the ID is taken or the registry is full
     */
    void register(Source source);

    /**
     * Checks if a source ID is already registered.
     *
     * @param sourceId The source ID to check
     * @return true if registered
     */
    boolean isRegistered(String sourceId);

    /**
     * Gets a source by ID.
     *
     * @param sourceId The source ID
     * @return The source, or null if not found
     */
    Source get(String sourceId);

    /**
     * Removes a source.
     *
     * @param sourceId The source ID to remove
     * @return true if removed
     */
    boolean remove(String sourceId);

    /**
     * Gets all registered source IDs.
     *
     * @return Set of source IDs
     */
    Set<String> getAllSourceIds();

    /**
     * Closes every source the registry owns.
     */
    @Override
    default void close() {
        // Registries that do not own closeable state need do nothing.
    }
}
