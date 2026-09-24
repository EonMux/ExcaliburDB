package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;

import com.eonmux.excalibur.tenancy.NamespaceId;

import java.io.IOException;
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
     * What a registration or a replacement must still pass once the registry has found nothing
     * wrong with it: typically, admitting the namespace it names.
     *
     * <p>Asked by the registry rather than by its caller because admitting a namespace can create
     * it, and a created namespace is a tenant - counted against the node's cap and listed to every
     * caller. Asked first, a request the registry then refuses as a duplicate, into a full
     * registry, or as a replacement naming another namespace would leave that tenant behind.
     * Asked afterwards, from outside, it races the registrations, replacements and removals that
     * decide whether the request is refused. So the registry asks it in the same step as its own
     * checks, once the request is known to go through, and before it records or starts
     * anything.</p>
     */
    @FunctionalInterface
    interface Admission {

        /** Admits everything, for a caller with nothing further to check. */
        Admission NONE = descriptor -> { };

        /**
         * Lets the registration or replacement go ahead, or refuses it by throwing.
         *
         * @param descriptor What the source is being registered as
         * @throws IOException if what it has to do, such as creating a namespace, failed
         */
        void admit(SourceDescriptor descriptor) throws IOException;
    }
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
     * Registers a source and records what it was registered as, once {@code admission} lets it.
     *
     * <p>{@code admission} is asked once the id is claimed and before anything is recorded or
     * started; when it throws, the claim is given back and the registration leaves nothing
     * behind. See {@link Admission}.</p>
     *
     * <p>The default asks it first and then registers, which is the best a registry that cannot
     * hold its claim across the question can do: a registration it then refuses has still been
     * admitted. A registry that claims ids should override this.</p>
     *
     * @param source     The source to register
     * @param descriptor What the source is registered as
     * @param admission  What the registration must still pass
     * @throws SourceRegistrationException if the ID is taken or the registry is full
     * @throws SourceCatalogException      if the registration cannot be recorded
     * @throws IOException                 if the admission failed
     */
    default void register(Source source, SourceDescriptor descriptor, Admission admission)
            throws IOException {
        admission.admit(descriptor);
        register(source, descriptor);
    }

    /**
     * Replaces what a registered source is configured as, keeping everything else about it.
     *
     * <p>One step, not a removal followed by a registration. A removal clears the source's status
     * history and takes it off the re-crawl schedule, and the pair leaves a moment in which the id
     * is free for another caller to claim. A replacement keeps the id, the namespace, the status
     * history, the place in the schedule and every document already indexed, records the new
     * configuration before the live source is swapped, and starts no crawl: the next scheduled
     * re-crawl, or {@code POST /v1/reindex}, crawls with the new configuration.</p>
     *
     * <p>The default refuses. A registry that cannot swap a source in one step must not
     * approximate it with the two-step sequence above.</p>
     *
     * @param source     The crawler built from the new configuration
     * @param descriptor What the source is now registered as
     * @throws SourceRegistrationException   if no source is registered under the id, the
     *                                       namespace differs, a crawl or reindex of it is
     *                                       running, or the node is shutting down
     * @throws SourceCatalogException        if the new configuration cannot be recorded
     * @throws UnsupportedOperationException if this registry cannot replace a registration
     */
    default void replace(Source source, SourceDescriptor descriptor) {
        throw new UnsupportedOperationException(
            "This source registry cannot replace a registration in one step");
    }

    /**
     * Replaces what a registered source is configured as, once {@code admission} lets it.
     *
     * <p>{@code admission} is asked once every check {@link #replace(Source, SourceDescriptor)}
     * makes has passed and before the new configuration is recorded; when it throws, the
     * registration is left as it was. See {@link Admission}.</p>
     *
     * <p>The default refuses, as {@link #replace(Source, SourceDescriptor)} does, and without
     * asking: a replacement that cannot happen must not admit anything first.</p>
     *
     * @param source     The crawler built from the new configuration
     * @param descriptor What the source is now registered as
     * @param admission  What the replacement must still pass
     * @throws SourceRegistrationException   as {@link #replace(Source, SourceDescriptor)}
     * @throws SourceCatalogException        if the new configuration cannot be recorded
     * @throws IOException                   if the admission failed
     * @throws UnsupportedOperationException if this registry cannot replace a registration
     */
    default void replace(Source source, SourceDescriptor descriptor, Admission admission)
            throws IOException {
        throw new UnsupportedOperationException(
            "This source registry cannot replace a registration in one step");
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
     * must stop all three and close the source it owns. The runs are stopped and, within a bound,
     * waited for before this returns - the ingestion run and a re-crawl alike - because a caller
     * erasing the source removes its documents next and a run still writing would put them back.
     * {@link #removeSource(String)} says whether they ended within it.</p>
     *
     * <p>What it must <em>not</em> touch is the documents the source contributed. Stopping a crawl
     * and erasing what it produced are different operations, and only one of them is reversible.</p>
     *
     * @param sourceId The source ID to remove
     * @return true if a source was registered under that id
     */
    boolean remove(String sourceId);

    /**
     * Removes a source as {@link #remove(String)} does, and says whether its runs have ended.
     *
     * <p>The removal stands either way. What the second half answers is whether anything of the
     * source is still writing: a run that did not reach its next document boundary within the
     * bound is still finishing a document, and a caller about to erase the source's documents
     * must not read the registry until it has.</p>
     *
     * <p>The default reports the runs as ended, for a registry with no runs behind it.</p>
     *
     * @param sourceId The source ID to remove
     * @return Whether a source was registered under that id, and whether its runs have ended
     */
    default Removal removeSource(String sourceId) {
        return new Removal(remove(sourceId), true);
    }

    /**
     * Stops every run writing under a source id, and waits for them within the same bound a
     * removal uses.
     *
     * <p>Whether or not the id is registered: documents supplied directly are ingested under an
     * id no registration holds, and an erasure of that id needs the run ended just as much as a
     * crawl's. Nothing else about the id is touched.</p>
     *
     * <p>The default reports nothing in flight, for a registry with no runs behind it.</p>
     *
     * @param sourceId The source id whose runs should end
     * @return true if no run under the id is in flight any longer
     */
    default boolean stopRuns(String sourceId) {
        return true;
    }

    /**
     * What a removal did.
     *
     * @param registered Whether a source was registered under the id
     * @param stopped    Whether every run of the source has ended; false when one was still
     *                   finishing a document when the bound ran out
     */
    record Removal(boolean registered, boolean stopped) {
    }

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
