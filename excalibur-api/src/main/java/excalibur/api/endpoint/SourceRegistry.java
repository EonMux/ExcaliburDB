package excalibur.api.endpoint;

import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceDescriptor;

import io.excalibur.tenancy.NamespaceId;

import java.util.Optional;
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
     * Registers a source and records what it was registered as.
     *
     * <p>A {@link Source} is a live crawler and cannot describe itself, so a registry that has to
     * bring one back after a restart needs the descriptor the registration was built from. The
     * two arrive together because they are one act: a source claimed but not recorded is crawled
     * until the process ends and then forgotten, with the corpus still indexed and nothing in the
     * node reporting that a crawl schedule has quietly stopped.</p>
     *
     * <p>A registry that keeps no catalog registers the source and has nothing to do with the
     * descriptor; the registration then lasts as long as the process, which is all such a
     * registry ever promised.</p>
     *
     * @param source     The source to register
     * @param descriptor What the source was registered as
     * @throws SourceRegistrationException if the ID is taken or the registry is full
     * @throws SourceCatalogException      if the registration cannot be recorded
     */
    default void register(Source source, SourceDescriptor descriptor) {
        register(source);
    }

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
     * Removes a source, undoing everything {@link #register(Source)} started.
     *
     * <p>Registration starts more than a map entry: an ingestion run, a status entry and a place
     * in the re-crawl schedule. An implementation that removed only the entry would leave the node
     * crawling a source no request can name and reporting one that no longer exists, so a removal
     * must stop all three and close the source it owns.</p>
     *
     * <p>What it must <em>not</em> touch is the documents the source contributed. Stopping a crawl
     * and erasing what it produced are different operations, and only one of them is reversible.</p>
     *
     * @param sourceId The source ID to remove
     * @return true if a source was registered under that id
     */
    boolean remove(String sourceId);

    /**
     * Stops an ingestion run without touching the registration behind it.
     *
     * <p>Half of what {@link #remove(String)} does, and the half an operator usually wants.
     * A crawl that is hammering a site, or walking a root that turned out to be far larger
     * than whoever registered it expected, has to be stoppable without also cancelling the
     * registration, clearing its status and taking it out of the re-crawl schedule - all of
     * which have to be rebuilt by re-registering, which starts the same crawl again.</p>
     *
     * <p>Like every other cancellation here it is a request: the run stops at its next
     * document boundary, so a document already being written is finished rather than
     * abandoned half-way.</p>
     *
     * <p>The default does nothing, for a registry with no ingestion behind it to stop.</p>
     *
     * @param sourceId The source whose run should stop
     * @return true if a run was in flight and has been asked to stop
     */
    default boolean stopIngestion(String sourceId) {
        return false;
    }

    /**
     * The namespace a registered source's documents belong to.
     *
     * <p>Asked once per published document, by the graph sink deciding which tenant's staging
     * store the facts extracted from it are appended to. The answer has to be empty rather than
     * a guess for a source this registry does not hold: a sink that read "unknown" as "shared"
     * would put a deregistered tenant's last in-flight documents into the index every other
     * tenant reads, which is the one mistake a tenancy boundary exists to make impossible.</p>
     *
     * <p>The default is what a registry that records no namespaces means: every source it holds
     * is in the shared namespace, which is exactly how a node that has never seen the
     * {@code X-Excalibur-Namespace} header behaves.</p>
     *
     * @param sourceId The source id
     * @return Its namespace, or empty if no source is registered under that id
     */
    default Optional<NamespaceId> namespaceOf(String sourceId) {
        return isRegistered(sourceId) ? Optional.of(NamespaceId.SHARED) : Optional.empty();
    }

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
