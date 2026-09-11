package excalibur.api.bootstrap;

import io.excalibur.config.Config;
import io.excalibur.tenancy.MappingBudget;
import io.excalibur.tenancy.NamespaceId;
import io.excalibur.tenancy.NamespaceRegistry;
import io.excalibur.tenancy.TieringPolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * <h2>Why the erasure deadline is swept here</h2>
 * <p>{@code excalibur.graph.erasure-max-latency-seconds} bounds how long an obligation may wait
 * before a compaction runs regardless of how little has accumulated. Nothing evaluated that
 * bound: the only caller of {@code compactIfDue} was the handler of the <em>next</em> erasure
 * request, which on a quiet node never arrives - so a single request submitted to an idle node
 * waited forever and the bound was a number in a table. The sweep below is what makes it a
 * deadline. It visits the namespaces that are open, which on a node that never sees the
 * namespace header is the only one there is.</p>
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

    /**
     * How much of the configured bound may pass between two checks.
     *
     * <p>A tenth, so an obligation is discharged within 110% of the bound rather than within
     * twice it. Checking is cheap - an outstanding count per open namespace, and nothing else
     * unless something is owed.</p>
     */
    private static final long SWEEP_FRACTION = 10;

    /** Shortest gap between sweeps, so a small configured bound cannot turn into a spin. */
    static final long MIN_SWEEP_INTERVAL_MS = 1_000L;

    /** How long close() waits for a sweep in flight, which may be inside a compaction. */
    private static final long SHUTDOWN_WAIT_SECONDS = 30L;

    private final NamespaceRegistry<GraphTenant> registry;
    private final MappingBudget budget;
    private final ScheduledExecutorService erasureSweep;

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

        TieringPolicy tiering = TieringPolicy.declared(
            namespaces(config.graph().getHotNamespaces(), "excalibur.graph.hot-namespaces"),
            namespaces(config.graph().getColdNamespaces(), "excalibur.graph.cold-namespaces"));

        this.registry = new NamespaceRegistry<>(
            root,
            (id, directory) -> GraphTenant.open(id, directory.resolve("graph"), config),
            GraphTenant::cost,
            budget,
            tiering,
            maxOpen);
        this.sharedLease = registry.borrow(NamespaceId.SHARED);

        long intervalMs = sweepIntervalMillis(config.graph().getErasureMaxLatencySeconds());
        this.erasureSweep = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "graph-erasure-sweep");
            thread.setDaemon(true);
            return thread;
        });
        this.erasureSweep.scheduleWithFixedDelay(
            this::sweepQuietly, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        LOGGER.info("graph tenants rooted at {}: at most {} open, erasure deadline swept every "
            + "{}ms, {}", root, maxOpen, intervalMs, budget);
    }

    /**
     * How often to check whether an erasure obligation has passed its deadline.
     *
     * @param maxLatencySeconds The configured bound
     * @return The gap between sweeps, never below {@link #MIN_SWEEP_INTERVAL_MS}
     */
    static long sweepIntervalMillis(long maxLatencySeconds) {
        return Math.max(MIN_SWEEP_INTERVAL_MS,
            maxLatencySeconds * 1_000L / SWEEP_FRACTION);
    }

    /**
     * Runs a sweep, swallowing whatever it throws.
     *
     * <p>A scheduled task that throws is silently cancelled and never runs again, which would
     * turn one failed compaction into an erasure deadline that stops being enforced for the life
     * of the process - the exact failure this sweep exists to prevent, arrived at quietly.</p>
     */
    private void sweepQuietly() {
        try {
            compactDueErasures();
        } catch (RuntimeException e) {
            LOGGER.error("graph erasure sweep failed; the next one will try again", e);
        }
    }

    /**
     * Compacts every open namespace whose erasure policy says a compaction is due.
     *
     * <p>Open namespaces only. One that has been evicted is not holding anything this node is
     * serving, and reopening every tenant on disk to ask would spend the mapping budget the
     * eviction was protecting; an evicted namespace settles its backlog on its next sweep after
     * something reopens it. A tenant being read is left alone by eviction for the length of the
     * lease this takes, so a compaction never unmaps a store out from under a query.</p>
     *
     * @return How many namespaces were visited with something outstanding
     */
    public int compactDueErasures() {
        int owing = 0;
        for (NamespaceId id : openNamespaces()) {
            Optional<NamespaceRegistry.Lease<GraphTenant>> lease = borrowResident(id);
            if (lease.isEmpty()) {
                // Evicted between listing and borrowing. borrowResident declines to reopen it
                // rather than spending the mapping budget the eviction just reclaimed; the
                // request is on disk and is swept once something else reopens the namespace.
                continue;
            }
            try (NamespaceRegistry.Lease<GraphTenant> held = lease.get()) {
                GraphErasureService erasure = held.resource().erasureService();
                if (erasure.outstandingCount() == 0) {
                    continue;
                }
                owing++;
                GraphErasureService.ErasureOutcome outcome = erasure.compactIfDue();
                if (outcome.ran()) {
                    LOGGER.info("erasure deadline reached for namespace {}: {} edge(s) removed, "
                        + "{} request(s) discharged", id, outcome.edgesRemoved(),
                        outcome.requestsSatisfied());
                } else {
                    LOGGER.debug("namespace {} owes {} erasure(s), none due yet: {}",
                        id, erasure.outstandingCount(), outcome.reason());
                }
            } catch (IOException e) {
                // One namespace's compaction failing must not stop the others being swept, and
                // must not stop this one being retried on the next pass.
                LOGGER.error("could not compact namespace {} for erasure: {}", id, e.toString());
            }
        }
        return owing;
    }

    /**
     * Reads configured namespace names, refusing rather than dropping anything unusable.
     *
     * <p>A name that is not a valid identifier cannot be the namespace it was meant to name, and
     * skipping it would leave a deployment with a tiering policy that reads as configured and
     * silently does nothing. The shared namespace is rejected here too, by
     * {@link NamespaceId#of(String)}: it is pinned regardless, and naming it says otherwise.</p>
     */
    private static Set<NamespaceId> namespaces(List<String> names, String key) {
        Set<NamespaceId> ids = new LinkedHashSet<>(names.size());
        for (String name : names) {
            try {
                ids.add(NamespaceId.of(name));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    key + " cannot name '" + name + "': " + e.getMessage(), e);
            }
        }
        return ids;
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
     * Borrows a namespace's graph only while it is already open, never loading it.
     *
     * <p>For the background passes, which visit every open namespace rather than serving a
     * caller who asked for one. {@link #borrowIfPresent(NamespaceId)} would load a namespace
     * whose directory exists, so a sweep running against a list taken a moment earlier could
     * reopen a tenant that was evicted in between - spending the mapping budget the eviction
     * had just reclaimed - and would mark every namespace it visited as recently used, making
     * eviction order the sweep's iteration order rather than the deployment's access pattern.</p>
     *
     * @param id The namespace
     * @return The lease, or empty if the namespace is not currently open; close it when done
     */
    public Optional<NamespaceRegistry.Lease<GraphTenant>> borrowResident(NamespaceId id) {
        return registry.borrowResident(Objects.requireNonNull(id, "id must not be null"));
    }

    /**
     * Drops a namespace's graph so the next request reopens it.
     *
     * <p>For a namespace whose directory was replaced by something other than this node - a
     * restore, or an operator swapping it - so that the next request reads what is now there.
     * A build does not need this: it installs its new stack into the tenant's holder, which is
     * what the holder is for.</p>
     *
     * <p>The shared namespace cannot be dropped. This class borrows it once and keeps that lease
     * for the life of the process, so evicting it would not close it - it would leave the old
     * tenant alive behind {@link #shared()} and open a second one on the same directory at the
     * next borrow. Two {@code GraphStagingStore}s appending to one log truncate each other's
     * partial records and hand the same interned id to different labels.</p>
     *
     * @param id The namespace
     * @return true if it was open
     * @throws IllegalArgumentException if {@code id} is the shared namespace
     */
    public boolean invalidate(NamespaceId id) {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isShared()) {
            throw new IllegalArgumentException(
                "the shared namespace is held for the life of the process and cannot be dropped");
        }
        return registry.evict(id);
    }

    /**
     * Re-measures what a namespace costs after a build or a compaction changed it.
     *
     * <p>A tenant opened before anything was built into it holds no mappings and is charged none.
     * The build that installs a store gives it thirteen, and nothing reopens it to notice - so
     * without this the budget under-counts the process's real mappings for as long as it runs.</p>
     *
     * @param id The namespace
     * @return true if it was open and its cost had changed
     */
    public boolean recost(NamespaceId id) {
        return registry.recost(Objects.requireNonNull(id, "id must not be null"));
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
        // The sweep first, and waited for: it borrows tenants and may be inside a compaction
        // that has the served stack closed, so shutting the registry under it would race a
        // reopen against the close.
        erasureSweep.shutdownNow();
        try {
            if (!erasureSweep.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("the graph erasure sweep did not stop within {}s",
                    SHUTDOWN_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // The lease next: while it is outstanding the registry defers closing the shared tenant,
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
