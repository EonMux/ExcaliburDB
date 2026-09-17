package excalibur.api.bootstrap;

import excalibur.ingest.graph.GraphIngestSink;
import excalibur.ingest.graph.RelationExtractor;
import excalibur.ingest.graph.StagingGraphIngestSink;

import io.excalibur.tenancy.NamespaceId;
import io.excalibur.tenancy.NamespaceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stages a published document's facts into the namespace its source was registered in.
 *
 * <h2>The gap this closes</h2>
 * <p>{@value GraphTenants#NAMESPACE_HEADER} was honoured by every graph route and by nothing that
 * writes. A caller could open a namespace, query it and erase from it, and no path existed by
 * which a fact could arrive in one: ingestion held the shared tenant's staging store for the life
 * of the process and every document went there. A named namespace was therefore permanently
 * empty, and a documented multi-tenant capability was inert. This sink is the write side of that
 * header.</p>
 *
 * <h2>Why the routing lives here</h2>
 * <p>{@link GraphTenants} is an API-layer concern and {@code excalibur-ingest} does not depend on
 * it, so the sink ingestion is handed is the piece that knows about tenants. What arrives is the
 * source-qualified document key - {@code sourceId:documentId}, as
 * {@link io.excalibur.core.text.ChunkIdentifiers#documentKey} forms it - which is enough to find
 * the source, and the source is what carries the namespace. Nothing new is threaded through the
 * ingest pipeline for it.</p>
 *
 * <h2>Why the tenant is borrowed per document and not held</h2>
 * <p>A raw {@code GraphStagingStore} held across an eviction is a write to a closed file: the
 * registry closes a tenant it evicts, and the shared tenant is the only one pinned for the life
 * of the process. Holding a lease per namespace instead would pin every namespace ingestion has
 * ever touched against eviction, which is exactly the mapping budget {@link GraphTenants} exists
 * to defend. So each document borrows for the length of its own staging call and releases: an
 * eviction that arrives meanwhile is deferred until the call returns, and the number of graphs
 * open at once stays bounded by {@code excalibur.graph.max-open-namespaces} because the borrow
 * goes through the same registry every query does. The {@link StagingGraphIngestSink} doing the
 * work is built inside the lease and discarded with it, for the same reason: it is the reference
 * to the staging store that must not outlive the borrow.</p>
 *
 * <p>The cost is one borrow and one small object per document, against one chat-model call per
 * document for extraction. It is not the expensive part.</p>
 *
 * <h2>Why an unresolved source is not staged into the shared namespace</h2>
 * <p>A document whose source this node no longer holds - deregistered while its crawl was
 * finishing - has no namespace to name. Staging it into the shared namespace would move one
 * tenant's content into the index every other tenant reads, which is worse than losing the facts
 * extracted from one document: the document itself is published and searchable either way. So it
 * is counted and logged and not staged.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use: one virtual thread per document, each with its own lease and its
 * own staging sink, over stores that serialise their own appends.</p>
 *
 * @author ExcaliburDB
 */
public final class NamespaceRoutingGraphIngestSink implements GraphIngestSink {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(NamespaceRoutingGraphIngestSink.class);

    /** Separates the source id from the document id in a document key. */
    private static final char KEY_SEPARATOR = ':';

    /**
     * Which namespace a registered source's documents belong to.
     *
     * <p>An interface rather than the source registry itself, so that this class depends on the
     * one question it asks rather than on registration, removal and crawl scheduling - and so a
     * test can answer that question without building a registry.</p>
     */
    @FunctionalInterface
    public interface SourceNamespaces {

        /**
         * The namespace a source's documents belong to.
         *
         * @param sourceId The source id
         * @return Its namespace, or empty if no source is registered under that id
         */
        Optional<NamespaceId> namespaceOf(String sourceId);
    }

    private final GraphTenants tenants;
    private final SourceNamespaces sources;
    private final RelationExtractor extractor;

    private final AtomicLong documentsSeen = new AtomicLong();
    private final AtomicLong factsStaged = new AtomicLong();
    private final AtomicLong unresolvedDocuments = new AtomicLong();
    private final AtomicLong tenantFailures = new AtomicLong();

    /**
     * Creates a routing sink.
     *
     * @param tenants   Every namespace's graph, which this borrows one of per document
     * @param sources   Resolves a source id to the namespace it was registered in
     * @param extractor Finds the facts; {@link RelationExtractor#none()} disables extraction
     * @throws NullPointerException if any argument is null
     */
    public NamespaceRoutingGraphIngestSink(GraphTenants tenants, SourceNamespaces sources,
                                           RelationExtractor extractor) {
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
    }

    /**
     * Stages a published document's facts into its source's namespace.
     *
     * <p>Never throws. The document is published and counted by the time this runs, so a failure
     * here cannot be signalled by failing it - that would report a loss that did not happen.
     * Every failure is counted as well as logged, because the interesting one is the steady
     * failure: a graph that has quietly stopped growing for an hour shows up in a count and in
     * nothing else.</p>
     *
     * @param documentKey The source-qualified document key
     * @param text        The document's text
     */
    @Override
    public void onDocumentPublished(String documentKey, String text) {
        if (documentKey == null || documentKey.isBlank() || text == null || text.isBlank()) {
            return;
        }
        documentsSeen.incrementAndGet();

        Optional<NamespaceId> namespace = resolve(documentKey);
        if (namespace.isEmpty()) {
            unresolvedDocuments.incrementAndGet();
            LOGGER.warn("No registered source owns {}, so there is no namespace to stage its "
                + "facts into; they are dropped rather than staged into the shared namespace, "
                + "which every tenant reads", documentKey);
            return;
        }
        stageInto(namespace.get(), documentKey, text);
    }

    /**
     * Stages one document into one namespace, for the length of a borrow.
     *
     * @param namespace   Where the facts belong
     * @param documentKey The document
     * @param text        Its text
     */
    private void stageInto(NamespaceId namespace, String documentKey, String text) {
        try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(namespace)) {
            StagingGraphIngestSink sink =
                new StagingGraphIngestSink(lease.resource().staging(), extractor);
            sink.onDocumentPublished(documentKey, text);
            factsStaged.addAndGet(sink.factsStaged());
        } catch (RuntimeException e) {
            // Opening a tenant can fail - a directory that will not create, a staging log that
            // will not open - and one namespace failing must not stop the next document, in this
            // namespace or in any other.
            tenantFailures.incrementAndGet();
            LOGGER.warn("Could not stage {} into namespace {}: {}",
                documentKey, namespace, e.toString());
        }
    }

    /**
     * Finds the namespace of the source that published a document.
     *
     * <p>A document key is {@code sourceId:documentId} and a document id may itself contain
     * colons - an HTTP source's ids are absolute URLs - so the split cannot be made on the last
     * colon. It is not made on the first one either: a source id is not required to be free of
     * colons, so each candidate prefix is offered to the registry in turn and the first one that
     * names a registered source is the answer. In the ordinary case, where the id has no colon,
     * that is a single lookup.</p>
     *
     * @param documentKey The source-qualified document key
     * @return The namespace, or empty if no registered source owns the document
     */
    private Optional<NamespaceId> resolve(String documentKey) {
        for (int colon = documentKey.indexOf(KEY_SEPARATOR); colon > 0;
                colon = documentKey.indexOf(KEY_SEPARATOR, colon + 1)) {
            Optional<NamespaceId> found = sources.namespaceOf(documentKey.substring(0, colon));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * How many published documents this sink has seen.
     *
     * @return The count
     */
    public long documentsSeen() {
        return documentsSeen.get();
    }

    /**
     * How many facts it has staged, across every namespace.
     *
     * @return The count
     */
    public long factsStaged() {
        return factsStaged.get();
    }

    /**
     * How many documents named no source this node has registered.
     *
     * <p>Nothing was staged for them. A non-zero count on a node whose sources are all still
     * registered means the key convention and the registry have drifted apart, which is
     * otherwise invisible: the documents are indexed and searchable, and only the graph is
     * missing what they said.</p>
     *
     * @return The count
     */
    public long unresolvedDocuments() {
        return unresolvedDocuments.get();
    }

    /**
     * How many documents it could not open a namespace's graph for.
     *
     * @return The count
     */
    public long tenantFailures() {
        return tenantFailures.get();
    }

    @Override
    public String toString() {
        return "NamespaceRoutingGraphIngestSink[" + documentsSeen() + " documents, "
            + factsStaged() + " facts, " + unresolvedDocuments() + " unresolved, "
            + tenantFailures() + " tenant failures]";
    }
}
