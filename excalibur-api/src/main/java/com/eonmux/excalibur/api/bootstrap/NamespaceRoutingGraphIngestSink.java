package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.graph.GraphIngestSink;
import com.eonmux.excalibur.ingest.graph.RelationExtractor;
import com.eonmux.excalibur.ingest.graph.StagingGraphIngestSink;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;

import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
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
 * it, so the sink ingestion is handed is the piece that knows about tenants. Ingestion names the
 * source each document came from, and the source is what carries the namespace. The source is
 * taken as ingestion names it and never recovered from the document key: a source id may contain
 * a colon, so with {@code team} and {@code team:wiki} both registered the key
 * {@code team:wiki:home.md} is as much document {@code wiki:home.md} of {@code team} as document
 * {@code home.md} of {@code team:wiki}, and a guess from the key would stage one tenant's facts
 * into the other's graph.</p>
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
 * <p>Extraction runs before the borrow, and a document with no facts borrows nothing. A borrow
 * opens the namespace's graph if it is not resident and can evict another to make room, so
 * borrowing for a document that stages nothing - every document, on a node with extraction
 * disabled - would churn the working set the query path depends on for no write at all. And the
 * extraction is a chat-model call: made inside the lease, it would pin the namespace against
 * eviction for the length of a network round trip.</p>
 *
 * <p>A document that replaced a version this node already held is the exception: it borrows
 * whether or not the new version yields facts, because what was staged from the earlier version
 * has to be withdrawn either way - see {@link StagingGraphIngestSink}, which does the withdrawing
 * inside the same borrow as the staging that follows it. A document removed outright borrows for
 * the same reason, to withdraw and stage nothing.</p>
 *
 * <p>A withdrawal is asked for once and never again - a removed document is not reported a second
 * time, nor is an unchanged one - so one that cannot be made is not dropped. Made through the
 * tenant's {@link com.eonmux.excalibur.ingest.graph.StagedWithdrawals}, a withdrawal that fails is
 * recorded there and retried until it succeeds. When the tenant itself will not open, the
 * withdrawal is left with {@link GraphTenants#oweWithdrawal}, which hands it to the tenant once
 * it does.</p>
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

    /** What happened to a document, which decides what is withdrawn and what is staged. */
    private enum Change {
        /** A first version: its facts are staged. */
        PUBLISHED,
        /** A later version: the earlier one's records are withdrawn and its facts staged. */
        REPLACED,
        /** The document is gone: its records are withdrawn and nothing is staged. */
        REMOVED;

        /** Whether records staged from the document before now have to be withdrawn. */
        boolean withdraws() {
            return this != PUBLISHED;
        }
    }

    private final GraphTenants tenants;
    private final SourceNamespaces sources;
    private final RelationExtractor extractor;

    private final AtomicLong documentsSeen = new AtomicLong();
    private final AtomicLong factsStaged = new AtomicLong();
    private final AtomicLong unresolvedDocuments = new AtomicLong();
    private final AtomicLong tenantFailures = new AtomicLong();
    private final AtomicLong extractionFailures = new AtomicLong();
    private final AtomicLong recordsWithdrawn = new AtomicLong();
    private final AtomicLong withdrawalFailures = new AtomicLong();

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
     * @param sourceId           The source that ingested the document
     * @param documentKey        The source-qualified document key
     * @param text               The document's text
     * @param lastModifiedMillis The document's own date, or 0 when its source does not say
     */
    @Override
    public void onDocumentPublished(String sourceId, String documentKey, String text,
                                    long lastModifiedMillis) {
        route(sourceId, documentKey, text, lastModifiedMillis, Change.PUBLISHED);
    }

    /**
     * Withdraws what was staged from a document's earlier version, in its source's namespace,
     * and stages the new version's facts there.
     *
     * <p>Never throws, for the reason {@link #onDocumentPublished} does not.</p>
     *
     * @param sourceId           The source that ingested the document
     * @param documentKey        The source-qualified document key
     * @param text               The new version's text
     * @param lastModifiedMillis The new version's date, or 0 when its source does not say
     */
    @Override
    public void onDocumentReplaced(String sourceId, String documentKey, String text,
                                   long lastModifiedMillis) {
        route(sourceId, documentKey, text, lastModifiedMillis, Change.REPLACED);
    }

    /**
     * Withdraws what was staged from a removed document, in its source's namespace.
     *
     * <p>Never throws, for the reason {@link #onDocumentPublished} does not. Not counted among
     * the documents seen, which are the published ones offered to the graph; what it withdrew
     * is counted with every other withdrawal.</p>
     *
     * @param sourceId    The source that ingested the document
     * @param documentKey The source-qualified document key
     */
    @Override
    public void onDocumentRemoved(String sourceId, String documentKey) {
        route(sourceId, documentKey, null, 0L, Change.REMOVED);
    }

    /**
     * Finds a document's namespace and withdraws from it or stages into it, as the change asks.
     *
     * @param sourceId           The source that ingested the document
     * @param documentKey        The source-qualified document key
     * @param text               The document's text, or null when it was removed
     * @param lastModifiedMillis The document's own date, or 0 when unknown
     * @param change             What happened to the document
     */
    private void route(String sourceId, String documentKey, String text,
                       long lastModifiedMillis, Change change) {
        if (sourceId == null || documentKey == null || documentKey.isBlank()) {
            return;
        }
        boolean hasText = change != Change.REMOVED && text != null && !text.isBlank();
        if (!hasText && !change.withdraws()) {
            return;
        }
        if (change != Change.REMOVED) {
            documentsSeen.incrementAndGet();
        }

        Optional<NamespaceId> namespace = sources.namespaceOf(sourceId);
        if (namespace.isEmpty()) {
            unresolvedDocuments.incrementAndGet();
            LOGGER.warn("Source {} of {} is not registered, so there is no namespace to {} its "
                + "facts in; nothing is done in the shared namespace instead, which every tenant "
                + "reads", sourceId, documentKey, change == Change.REMOVED ? "withdraw" : "stage");
            return;
        }
        List<ExtractedTriple> extracted = hasText ? extract(documentKey, text) : List.of();
        if (extracted.isEmpty() && !change.withdraws()) {
            return;
        }
        stageInto(namespace.get(), sourceId, documentKey, text, lastModifiedMillis, extracted,
            change);
    }

    /**
     * Asks the extractor for a document's facts, outside any borrow.
     *
     * @param documentKey The document
     * @param text        Its text
     * @return The facts, empty when there are none or the extractor failed
     */
    private List<ExtractedTriple> extract(String documentKey, String text) {
        try {
            List<ExtractedTriple> triples = extractor.extract(documentKey, text);
            return triples == null ? List.of() : List.copyOf(triples);
        } catch (RuntimeException e) {
            extractionFailures.incrementAndGet();
            LOGGER.warn("Could not extract facts from {}: {}", documentKey, e.toString());
            return List.of();
        }
    }

    /**
     * Stages one document's facts into one namespace, for the length of a borrow, withdrawing
     * what was staged from it before first when it was replaced or removed.
     *
     * @param namespace          Where the facts belong
     * @param sourceId           The source that ingested the document
     * @param documentKey        The document
     * @param text               Its text, or null when it was removed
     * @param lastModifiedMillis The document's own date, or 0 when unknown
     * @param extracted          Its facts, already extracted
     * @param change             What happened to the document
     */
    private void stageInto(NamespaceId namespace, String sourceId, String documentKey,
                           String text, long lastModifiedMillis,
                           List<ExtractedTriple> extracted, Change change) {
        NamespaceRegistry.Lease<GraphTenant> borrowed;
        try {
            borrowed = tenants.borrow(namespace);
        } catch (NamespaceRefusedException e) {
            // Dropped since its source was registered, on a node that does not recreate
            // namespaces on demand or has no room for another. Nothing is staged in a namespace
            // that does not exist, so a withdrawal owes nothing there either.
            tenantFailures.incrementAndGet();
            LOGGER.warn("Namespace {} was refused, so {} is not staged there: {}", namespace,
                documentKey, e.getMessage());
            return;
        } catch (RuntimeException e) {
            // Opening a tenant can fail - a directory that will not create, a staging log that
            // will not open - and one namespace failing must not stop the next document, in this
            // namespace or in any other.
            tenantFailures.incrementAndGet();
            if (change.withdraws()) {
                tenants.oweWithdrawal(namespace, documentKey);
                LOGGER.error("Could not open namespace {} to withdraw what was staged from {}; "
                    + "the withdrawal is owed and made when the namespace next opens, which the "
                    + "erasure sweep keeps trying - a restart before then leaves those facts "
                    + "staged: {}", namespace, documentKey, e.toString());
            } else {
                LOGGER.warn("Could not open namespace {} to stage {}: {}", namespace,
                    documentKey, e.toString());
            }
            return;
        }
        try (NamespaceRegistry.Lease<GraphTenant> lease = borrowed) {
            // Handed what was already extracted, so the staging sink stages the facts and the
            // entity text they mention without calling the model a second time.
            StagingGraphIngestSink sink = new StagingGraphIngestSink(
                lease.resource().withdrawals(), (key, body) -> extracted);
            switch (change) {
                case PUBLISHED -> sink.onDocumentPublished(sourceId, documentKey, text,
                    lastModifiedMillis);
                case REPLACED -> sink.onDocumentReplaced(sourceId, documentKey, text,
                    lastModifiedMillis);
                case REMOVED -> sink.onDocumentRemoved(sourceId, documentKey);
            }
            factsStaged.addAndGet(sink.factsStaged());
            recordsWithdrawn.addAndGet(sink.recordsWithdrawn());
            withdrawalFailures.addAndGet(sink.withdrawalFailures());
        } catch (RuntimeException e) {
            // The staging sink counts and absorbs its own failures, so what arrives here is the
            // tenant failing underneath it: a store an eviction closed, for one.
            tenantFailures.incrementAndGet();
            LOGGER.warn("Could not {} {} in namespace {}: {}",
                change == Change.REMOVED ? "withdraw" : "stage", documentKey, namespace,
                e.toString());
        }
    }

    /**
     * Forces the staged facts of every resident namespace to the device.
     *
     * <p>Every namespace rather than the ones this sink wrote to, because the sink keeps no
     * record of which those were, and a namespace nothing wrote to since its last sync costs a
     * check and no device flush. One an eviction closed was forced as it closed.</p>
     *
     * @throws IOException if any namespace could not be forced
     */
    @Override
    public void sync() throws IOException {
        int failed = tenants.syncStaging();
        if (failed > 0) {
            throw new IOException(failed + " namespace(s) could not force their staged facts "
                + "to disk");
        }
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
     * How many documents came from a source this node no longer has registered.
     *
     * <p>Nothing was staged for them, and nothing withdrawn. The ordinary cause is a source
     * deregistered while its last documents were being reported; a count that keeps climbing on
     * a node whose sources are all still registered means ingestion and the source registry have
     * drifted apart, which is otherwise invisible: the documents are indexed and searchable, and
     * only the graph is missing what they said.</p>
     *
     * @return The count
     */
    public long unresolvedDocuments() {
        return unresolvedDocuments.get();
    }

    /**
     * How many documents the extractor failed on.
     *
     * <p>The documents are published and searchable; only their facts are missing from the
     * graph.</p>
     *
     * @return The count
     */
    public long extractionFailures() {
        return extractionFailures.get();
    }

    /**
     * How many staged facts and snippets it withdrew because their document was replaced or
     * removed.
     *
     * @return The count
     */
    public long recordsWithdrawn() {
        return recordsWithdrawn.get();
    }

    /**
     * How many times a replaced or removed document's earlier facts could not be withdrawn when
     * it was reported.
     *
     * <p>Each withdrawal is owed from then on and retried until it is made, and nothing newer is
     * staged for the document meanwhile; a build before it is made publishes what the document
     * used to say. Counted per attempt that found it still owed, so a figure that keeps climbing
     * means the staging logs are refusing to be rewritten.</p>
     *
     * @return The count
     */
    public long withdrawalFailures() {
        return withdrawalFailures.get();
    }

    /**
     * How many documents it could not open a namespace's graph for.
     *
     * @return The count
     */
    public long tenantFailures() {
        return tenantFailures.get();
    }

    /**
     * What finds the facts this sink stages.
     *
     * <p>So the node can report the extractor's own health - its circuit breaker - beside the
     * counts this sink keeps of what it did with the extractor's answers.</p>
     *
     * @return The extractor; {@link RelationExtractor#none()} where extraction is off
     */
    public RelationExtractor extractor() {
        return extractor;
    }

    @Override
    public String toString() {
        return "NamespaceRoutingGraphIngestSink[" + documentsSeen() + " documents, "
            + factsStaged() + " facts, " + recordsWithdrawn() + " withdrawn, "
            + unresolvedDocuments() + " unresolved, "
            + tenantFailures() + " tenant failures, "
            + withdrawalFailures() + " withdrawal failures]";
    }
}
