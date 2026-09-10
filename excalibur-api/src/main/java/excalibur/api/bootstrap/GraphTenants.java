package excalibur.api.bootstrap;

import io.excalibur.config.Config;
import io.excalibur.tenancy.MappingBudget;
import io.excalibur.tenancy.NamespaceId;
import io.excalibur.tenancy.NamespaceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Every namespace's graph, bounded by what the kernel will let this process map.
 *
 * <h2>Why the bound exists</h2>
 * <p>A served store is thirteen memory mappings, and a process has a finite number of them. A node
 * holding one graph per tenant will therefore run out - not gracefully, but at whatever unrelated
 * allocation happens to be next. The registry evicts the least recently used graph before that
 * happens, and reopens it when someone asks again: a tenant that has not been queried in a while
 * costs a reopen, which is the right thing to pay to keep the node alive.</p>
 *
 * <h2>Why every request borrows</h2>
 * <p>Eviction closes a store, and closing a store unmaps memory a query may be reading from. Every
 * request therefore takes a lease for its duration; a namespace being read is passed over by
 * eviction, and an eviction that lands on one is deferred until the last reader is done. Without
 * that, the mechanism that keeps the node alive would be the mechanism that crashes it.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphTenants implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphTenants.class);

    /** Header naming the namespace a request belongs to. */
    public static final String NAMESPACE_HEADER = "X-Excalibur-Namespace";

    private final NamespaceRegistry<GraphTenant> registry;
    private final MappingBudget budget;

    /**
     * A lease on the shared namespace, held for the life of this registry.
     *
     * <p>Ingestion is not namespace-aware: it hands published documents to one sink, which holds
     * the shared tenant's staging store for the life of the process. A lease is what makes that
     * reference safe - without it the tenant could be evicted and its staging store closed while
     * the sink still holds it, which is a write to a closed file rather than a slow reopen.</p>
     */
    private final NamespaceRegistry.Lease<GraphTenant> sharedLease;

    /**
     * Opens the tenant registry rooted at {@code root}.
     *
     * @param root     Directory holding one subdirectory per namespace
     * @param config   Configuration, passed to every tenant
     * @param budget   The process-wide mapping budget
     * @param maxOpen  Most graphs to keep open at once
     * @throws IOException if the root cannot be created
     */
    public GraphTenants(Path root, Config config, MappingBudget budget, int maxOpen)
            throws IOException {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(config, "config must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");

        // The registry never creates its own root, and a loader that had to would be doing it
        // under the registry's lock on the first request of every namespace.
        java.nio.file.Files.createDirectories(root);

        this.registry = new NamespaceRegistry<>(
            root,
            (id, directory) -> GraphTenant.open(id, directory.resolve("graph"), config),
            GraphTenant::cost,
            budget,
            maxOpen);
        this.sharedLease = registry.borrow(NamespaceId.SHARED);
        LOGGER.info("graph tenants rooted at {}: at most {} open, {}", root, maxOpen, budget);
    }

    /**
     * Borrows a namespace's graph for the length of one operation.
     *
     * <p>The lease is what keeps the graph mapped while the caller reads it. Close it - a
     * try-with-resources - as soon as the operation is done, because a lease held open pins a
     * namespace against eviction and the budget it is meant to protect.</p>
     *
     * @param id The namespace
     * @return The lease; close it when done
     */
    public NamespaceRegistry.Lease<GraphTenant> borrow(NamespaceId id) {
        return registry.borrow(Objects.requireNonNull(id, "id must not be null"));
    }

    /**
     * Borrows a namespace's graph for one operation, but only if the namespace already exists.
     *
     * <p>For the operations that read. Opening a tenant writes its staging directory, takes one of
     * the {@code maxOpen} slots and a share of the mapping budget, and evicts the least recently
     * used graph to get them - so a read that opened would let a caller spend all three on any name
     * it cared to put in the {@value #NAMESPACE_HEADER} header. The operations that populate a
     * namespace still use {@link #borrow(NamespaceId)}: creating one is what they are for.</p>
     *
     * @param id The namespace
     * @return The lease, or empty if no such namespace has been created; close it when done
     */
    public Optional<NamespaceRegistry.Lease<GraphTenant>> borrowIfPresent(NamespaceId id) {
        return registry.borrowIfPresent(Objects.requireNonNull(id, "id must not be null"));
    }

    /**
     * Drops a namespace's graph so the next request reopens it.
     *
     * <p>How a build makes its result visible: the directory on disk has been replaced, and the
     * stack this node had open describes what was there before. Evicting rather than swapping in
     * place means the reopen happens on the next request, under a lease, with the budget consulted
     * as it would be for any other namespace.</p>
     *
     * @param id The namespace
     * @return true if it was open
     */
    public boolean invalidate(NamespaceId id) {
        return registry.evict(Objects.requireNonNull(id, "id must not be null"));
    }

    /**
     * The namespaces currently open.
     *
     * @return Their ids
     */
    public List<NamespaceId> openNamespaces() {
        return registry.openNamespaces();
    }

    /**
     * How many graphs are open.
     *
     * @return The count
     */
    public int openCount() {
        return registry.openCount();
    }

    /**
     * How many graphs have been evicted to stay within the budget.
     *
     * @return The count
     */
    public long evictionCount() {
        return registry.evictionCount();
    }

    /**
     * The mapping budget these tenants share.
     *
     * @return The budget
     */
    public MappingBudget budget() {
        return budget;
    }

    /**
     * The shared namespace's graph, which is never evicted.
     *
     * <p>For a collaborator that has to hold part of it for the life of the process rather than
     * for the length of a request - ingestion's sink, which is handed the staging store once.</p>
     *
     * @return The shared tenant
     */
    public GraphTenant shared() {
        return sharedLease.resource();
    }

    /**
     * The namespace a request names, or the shared one.
     *
     * <p>A deployment that never sends the header behaves exactly as a single-tenant node: one
     * namespace, called shared, in one directory. That is deliberate - multi-tenancy should not be
     * something a node acquires by accident, and an operator who has never heard of it should not
     * have to configure it away.</p>
     *
     * @param header The header value, or null when absent
     * @return The namespace
     * @throws IllegalArgumentException if the header is present but not a valid namespace
     */
    public static NamespaceId namespaceOf(String header) {
        if (header == null || header.isBlank()) {
            return NamespaceId.SHARED;
        }
        return NamespaceId.of(header.trim());
    }

    @Override
    public void close() {
        // The lease first: while it is outstanding the registry defers closing the shared tenant,
        // so releasing it is what lets the close below actually release its files.
        try {
            sharedLease.close();
        } catch (Exception e) {
            LOGGER.warn("failed to release the shared namespace lease: {}", e.toString());
        }
        registry.close();
    }

    @Override
    public String toString() {
        return "GraphTenants[" + openCount() + " open, " + evictionCount() + " evicted, "
            + budget + "]";
    }
}
