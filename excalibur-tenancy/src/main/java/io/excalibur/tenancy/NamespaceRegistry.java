package io.excalibur.tenancy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens, caches and evicts one index per tenant, and owns every one of their lifetimes.
 *
 * <p>Isolation is per-directory rather than per-row. A shared index with a tenant column means
 * every query carries a filter that must never be forgotten, every compaction rewrites all tenants'
 * data together, and erasing one tenant means rewriting the whole index. Separate directories make
 * "this tenant's data" a thing that exists, so isolation is structural instead of a predicate
 * someone has to remember, and deleting a tenant is deleting a directory.
 *
 * <p>What that costs is resources: each open namespace holds mappings and descriptors, and both are
 * capped by the kernel. So the registry is a bounded cache, not a map. It evicts by least-recent
 * use, driven by both a namespace count and the {@link MappingBudget}, because either can be the
 * binding constraint - many small tenants exhaust the count, few large ones exhaust the mappings.
 *
 * <p>Generic over what a namespace holds. The registry's job is lifetime, eviction and accounting;
 * it does not need to know whether it is holding an edge store, a vector index or all of them, and
 * keeping it ignorant is what lets the same policy govern each.
 *
 * <h2>Borrowing</h2>
 * <p>Because the registry owns the lifetimes, a reference from {@link #get(NamespaceId)} is only
 * good until something else evicts that namespace, and eviction runs on whichever thread happens to
 * open the namespace that needs the room. A mapped index closed under a reader does not return
 * stale bytes - the arena refuses the access - but it does fail a query that did nothing wrong, and
 * on a concurrent query path that is a matter of timing rather than of luck.
 *
 * <p>{@link #borrow(NamespaceId)} is the path that survives this. While a lease is outstanding the
 * resource is not closed: an explicit eviction is deferred until the last lease is released, and
 * the eviction that makes room for another namespace passes the borrowed one over. {@code get} is
 * unchanged and remains right for callers that open, use and finish with a namespace without ever
 * publishing the reference to another thread.
 *
 * <h2>Thread Safety</h2>
 * <p>Every method is safe to call from any thread. One {@link ReentrantLock} guards the open map,
 * the budget accounting and each entry's borrow count together, because those three have to move as
 * one - a borrow counted after eviction had already decided the namespace was idle would be a lease
 * over a resource that is closing. Resources are closed while that lock is held, so a loader or a
 * resource whose {@code close} calls back into the registry re-enters the lock rather than
 * deadlocking, but one that blocks stalls every other namespace.
 *
 * <p>Leases may be taken on one thread and released on another, and releasing one twice is a no-op.
 *
 * @param <T> the per-namespace resource
 */
public final class NamespaceRegistry<T extends AutoCloseable> implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NamespaceRegistry.class);

    /** Opens a namespace's resource from its directory. */
    @FunctionalInterface
    public interface Loader<T> {
        /**
         * @param id        the namespace
         * @param directory its directory, already resolved and verified to be inside the root
         */
        T load(NamespaceId id, Path directory) throws IOException;
    }

    /** Reports how many mappings and descriptors a loaded resource holds. */
    @FunctionalInterface
    public interface ResourceCost<T> {
        /** @return {@code [mappings, descriptors]} */
        long[] costOf(T resource);

        /** A cost function for resources whose footprint is not known. */
        static <T> ResourceCost<T> unknown() {
            // One mapping and one descriptor, so an open namespace is never free and the count
            // limit still bounds the registry even when the byte cost cannot be measured.
            return resource -> new long[] { 1L, 1L };
        }
    }

    /**
     * A claim on a namespace's resource that keeps it open for as long as the claim is held.
     *
     * <p>The resource still belongs to the registry. Closing a lease gives up the claim; it does
     * not close the resource, and it does not stop the registry reopening the namespace later.
     *
     * <p>Hold one for the span of a read and no longer. An eviction that arrives meanwhile waits
     * for the last lease, so a lease held for the whole life of a request pins mappings the
     * registry is trying to reclaim, and a lease never closed pins them until the process ends.
     *
     * <h2>Thread Safety</h2>
     * <p>A lease may be closed from any thread, and from several at once; only the first close
     * counts.
     *
     * @param <T> the per-namespace resource
     */
    public interface Lease<T> extends AutoCloseable {

        /**
         * The borrowed resource, safe to use until this lease is closed.
         *
         * @return the resource, never null
         * @throws IllegalStateException if this lease has already been closed - the resource may
         *                               have been closed along with it, so handing it back would
         *                               be exactly the use-after-close the lease prevents
         */
        T resource();

        /**
         * Gives up the borrow, closing the resource if an eviction has been waiting on it.
         *
         * <p>Declared to throw nothing, unlike {@link AutoCloseable#close()}, so try-with-resources
         * over a lease does not force callers to handle an exception that is never raised - a
         * failure to close the underlying resource is the registry's to log, not the borrower's to
         * catch. Calling it more than once has no effect.
         */
        @Override
        void close();
    }

    /** What an eviction request did to the namespace. */
    public enum Eviction {

        /** It was not open, so there was nothing to evict. */
        NOT_OPEN,

        /** Its resource was closed and its mappings given back to the budget. */
        CLOSED,

        /**
         * It was removed from the registry, but leases were outstanding, so the resource stays open
         * - and its mappings stay on the budget - until the last lease is released.
         */
        DEFERRED
    }

    private final Path root;
    private final Loader<T> loader;
    private final ResourceCost<T> cost;
    private final MappingBudget budget;
    private final TieringPolicy tiering;
    private final int maxOpenNamespaces;

    /** Access-ordered, so the eldest entry is the least recently used. */
    private final LinkedHashMap<NamespaceId, Entry<T>> open =
        new LinkedHashMap<>(16, 0.75f, true);

    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;

    /** Guarded by {@link #lock}. */
    private long evictionCount;

    /** Evicted entries still held open by leases. Guarded by {@link #lock}. */
    private int pendingCloses;

    /** A registry pinning only the shared namespace. */
    public NamespaceRegistry(Path root, Loader<T> loader, ResourceCost<T> cost,
                             MappingBudget budget, int maxOpenNamespaces) {
        this(root, loader, cost, budget, TieringPolicy.sharedIsHot(), maxOpenNamespaces);
    }

    public NamespaceRegistry(Path root, Loader<T> loader, ResourceCost<T> cost,
                             MappingBudget budget, TieringPolicy tiering, int maxOpenNamespaces) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        if (loader == null) {
            throw new IllegalArgumentException("loader must not be null");
        }
        if (cost == null) {
            throw new IllegalArgumentException("cost must not be null");
        }
        if (budget == null) {
            throw new IllegalArgumentException("budget must not be null");
        }
        if (tiering == null) {
            throw new IllegalArgumentException("tiering must not be null");
        }
        if (maxOpenNamespaces < 1) {
            throw new IllegalArgumentException(
                "maxOpenNamespaces must be at least 1, got " + maxOpenNamespaces);
        }
        this.root = root;
        this.loader = loader;
        this.cost = cost;
        this.budget = budget;
        this.tiering = tiering;
        this.maxOpenNamespaces = maxOpenNamespaces;
    }

    /**
     * The resource for {@code id}, opening it if it is not already open.
     *
     * <p>Evicts before loading, not after, so the new namespace's mappings are taken against
     * headroom that already exists. Evicting afterwards would mean crossing the limit first, and
     * crossing it is the failure this is here to prevent.
     *
     * <p>The reference is not protected from eviction. Anything holding it across a point where
     * another thread might open a namespace wants {@link #borrow(NamespaceId)} instead.
     */
    public T get(NamespaceId id) {
        if (id == null) {
            throw new IllegalArgumentException("namespace id must not be null");
        }
        lock.lock();
        try {
            checkOpen();
            Entry<T> existing = open.get(id);
            if (existing != null) {
                return existing.resource;
            }
            evictUntilRoomFor(id);
            return load(id).resource;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Borrows the resource for {@code id}, opening it if it is not already open.
     *
     * <p>The resource cannot be closed while the returned lease is held, which is the whole
     * difference from {@link #get(NamespaceId)}: an {@link #evict(NamespaceId)} arriving meanwhile
     * is deferred to the last release, and the eviction that makes room for another namespace skips
     * this one. Borrowing counts as use, so a borrowed namespace is also the most recently used.
     *
     * <p>The lease must be closed, and try-with-resources is the way to be sure of it. A lease that
     * is dropped instead keeps the resource mapped for the life of the process, because nothing
     * else can know the borrower has finished.
     *
     * @param id the namespace to borrow
     * @return a lease over the namespace's resource, never null
     * @throws IllegalArgumentException if {@code id} is null
     * @throws IllegalStateException    if the registry is closed
     * @throws UncheckedIOException     if the namespace is not open and cannot be opened
     */
    public Lease<T> borrow(NamespaceId id) {
        if (id == null) {
            throw new IllegalArgumentException("namespace id must not be null");
        }
        lock.lock();
        try {
            checkOpen();
            Entry<T> entry = open.get(id);
            if (entry == null) {
                evictUntilRoomFor(id);
                entry = load(id);
            }
            // Counted under the same lock that found the entry. Counting it after the lock was
            // dropped would leave a window in which eviction sees an idle namespace and closes the
            // resource this lease is about to hand out.
            entry.borrows++;
            return new BorrowedLease<>(this, entry);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Borrows {@code id} only if it already exists, and never brings it into being.
     *
     * <p>Loading a namespace is what creates it: the loader is handed a directory and writes what
     * it does not find there. That is right for the operation that populates a namespace and wrong
     * for one that only reads it, because the cost of a read that loads is not paid by the read.
     * It leaves a directory on disk, takes a slot in this registry and a share of the mapping
     * budget, and evicts whichever real namespace was least recently used to get them - once per
     * name, and on a request path the name usually arrives with the request.</p>
     *
     * <p>Already open counts as existing without asking the filesystem, so a namespace whose
     * resource lives only in memory is still readable. Otherwise the question is whether its
     * directory is there, which is the same question {@link #load} would answer by creating one.</p>
     *
     * @param id the namespace to borrow
     * @return a lease over the namespace's resource, or empty if the namespace does not exist
     * @throws IllegalArgumentException if {@code id} is null
     * @throws IllegalStateException    if the registry is closed
     * @throws UncheckedIOException     if the namespace exists and cannot be opened
     */
    public Optional<Lease<T>> borrowIfPresent(NamespaceId id) {
        if (id == null) {
            throw new IllegalArgumentException("namespace id must not be null");
        }
        lock.lock();
        try {
            checkOpen();
            Entry<T> entry = open.get(id);
            if (entry == null) {
                if (!Files.isDirectory(id.directoryUnder(root))) {
                    return Optional.empty();
                }
                evictUntilRoomFor(id);
                entry = load(id);
            }
            entry.borrows++;
            return Optional.of(new BorrowedLease<>(this, entry));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gives up one borrow, closing the resource if this was the last lease on an evicted entry.
     *
     * <p>Called only by {@link BorrowedLease}, and only once per lease.
     */
    private void release(Entry<T> entry) {
        lock.lock();
        try {
            entry.borrows--;
            if (entry.borrows == 0 && entry.pendingClose) {
                entry.pendingClose = false;
                pendingCloses--;
                closeEntry(entry, "last lease released");
            }
        } finally {
            lock.unlock();
        }
    }

    /** The resource for {@code id} if it is already open, without loading it. */
    public Optional<T> getIfOpen(NamespaceId id) {
        lock.lock();
        try {
            Entry<T> entry = open.get(id);
            return Optional.ofNullable(entry == null ? null : entry.resource);
        } finally {
            lock.unlock();
        }
    }

    private Entry<T> load(NamespaceId id) {
        Path directory = id.directoryUnder(root);
        try {
            T resource = loader.load(id, directory);
            if (resource == null) {
                throw new IOException("loader returned no resource for namespace " + id);
            }
            long[] resourceCost = cost.costOf(resource);
            Entry<T> entry = new Entry<>(id, resource, resourceCost[0], resourceCost[1]);
            open.put(id, entry);
            budget.acquire(entry.mappings, entry.descriptors);
            LOGGER.debug("opened namespace {} ({} mappings, {} fds); {}",
                id, entry.mappings, entry.descriptors, budget);
            return entry;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open namespace " + id, e);
        }
    }

    /**
     * Evicts least-recently-used namespaces until there is room for another.
     *
     * <p>Bounded by both limits, and it makes room for the namespace about to arrive rather than
     * merely checking whether the budget is already exceeded. Checking only the current usage would
     * evict one namespace too late every time - the load that crosses the limit is exactly the one
     * that needed the room.
     *
     * <p>The incoming namespace's cost cannot be known before it is opened, so this reserves one
     * average namespace's worth. That estimate is why the high-water mark sits well below the real
     * limit: the headroom absorbs the times a namespace turns out larger than the average.
     */
    private void evictUntilRoomFor(NamespaceId incoming) {
        long estimate = estimatedCostOfNextLoad();
        while (open.size() >= maxOpenNamespaces || budget.wouldExceedHighWater(estimate)) {
            NamespaceId victim = chooseVictim(incoming);
            if (victim == null && open.isEmpty()) {
                // Nothing is open, so nothing can be evicted: the budget has no room for even
                // one namespace. Naming that is the useful thing to say - the other branch would
                // report every one of zero namespaces as pinned, which reads as a leaked lease
                // and sends an operator looking for one.
                LOGGER.warn("mapping budget has no room for a single namespace, so {} is opened "
                    + "outside it. Raise vm.max_map_count, or the configured budget. {}",
                    incoming, budget);
                return;
            }
            if (victim == null) {
                // Everything left is pinned or borrowed. Opening anyway is the lesser evil:
                // refusing the request trades a definite failure now for a possible one later, and
                // the high-water mark leaves headroom precisely so this can be survived. It is a
                // misconfiguration, or a leaked lease, so it is logged loudly rather than passed
                // over.
                LOGGER.warn("cannot make room for namespace {}: all {} open namespaces are pinned "
                    + "HOT or borrowed. Opening anyway, which may exceed the mapping budget. {}",
                    incoming, open.size(), budget);
                return;
            }
            evictOrDefer(victim, "making room for " + incoming);
        }
    }

    /**
     * How many mappings to reserve for a namespace that has not been opened yet.
     *
     * <p>The mean of what is already open, which is the only evidence available. Falls back to one
     * mapping when nothing is open - enough that an empty registry does not reserve the whole
     * budget for a namespace whose size is entirely unknown.
     */
    private long estimatedCostOfNextLoad() {
        if (open.isEmpty()) {
            return 1L;
        }
        long total = 0;
        for (Entry<T> entry : open.values()) {
            total += entry.mappings;
        }
        return Math.max(1L, total / open.size());
    }

    /**
     * The next namespace to close: the least recently used of the coldest unborrowed tier present.
     *
     * <p>Scans rather than taking the map's eldest entry, because recency alone would evict the
     * shared index. The scan is over at most {@code maxOpenNamespaces} entries, which is bounded
     * and small next to the close it is about to perform.
     *
     * @return the victim, or null if every open namespace is pinned or borrowed
     */
    private NamespaceId chooseVictim(NamespaceId incoming) {
        NamespaceId coldest = null;
        NamespaceTier coldestTier = null;
        // Access-ordered, so iteration runs least-recently-used first and the first candidate
        // found at a given tier is that tier's LRU. Iterating the entry set rather than looking
        // each key up again: a get() here would reorder the map mid-scan and fail the iterator.
        for (Map.Entry<NamespaceId, Entry<T>> candidate : open.entrySet()) {
            NamespaceId id = candidate.getKey();
            if (id.equals(incoming)) {
                continue;
            }
            // Borrowed namespaces are passed over rather than deferred. Deferring frees no
            // mappings until the lease is released, so a loop deferring under budget pressure
            // would empty the registry and still be over the high-water mark.
            if (candidate.getValue().borrows > 0) {
                continue;
            }
            NamespaceTier tier = tiering.tierOf(id);
            if (tier == NamespaceTier.HOT) {
                continue;
            }
            if (tier == NamespaceTier.COLD) {
                return id;
            }
            if (coldestTier == null) {
                coldest = id;
                coldestTier = tier;
            }
        }
        return coldest;
    }

    /**
     * Closes a namespace and releases its mappings, or defers that if it is borrowed.
     *
     * <p>Returns whether it was open. Closing something already closed is not an error - a caller
     * evicting on a timer and a caller evicting on deletion will race, and both are correct.
     *
     * <p>A deferred close reports {@code true} here as well: the namespace was open, and it has
     * left the registry either way. A caller that has to know whether the mappings are actually
     * back - one deleting a tenant, or one about to remove the directory - wants
     * {@link #evictOrDefer(NamespaceId)}, which says which of the two happened.
     *
     * @param id the namespace to evict
     * @return true if it was open, whether its resource was closed or its close deferred
     */
    public boolean evict(NamespaceId id) {
        return evictOrDefer(id) != Eviction.NOT_OPEN;
    }

    /**
     * Evicts a namespace, saying whether its resource was closed or the close was deferred.
     *
     * <p>An open namespace leaves the registry immediately either way, so a later
     * {@link #get(NamespaceId)} reopens it. What a lease changes is only when the old resource is
     * closed: while one is outstanding the resource stays open and its mappings stay on the budget,
     * because closing it would break the reader holding the lease, and releasing its accounting
     * early would tell the budget it has headroom that is still in use.
     *
     * @param id the namespace to evict
     * @return what happened - {@link Eviction#NOT_OPEN}, {@link Eviction#CLOSED} or
     *         {@link Eviction#DEFERRED}
     */
    public Eviction evictOrDefer(NamespaceId id) {
        lock.lock();
        try {
            return evictOrDefer(id, "requested");
        } finally {
            lock.unlock();
        }
    }

    private Eviction evictOrDefer(NamespaceId id, String why) {
        Entry<T> entry = open.remove(id);
        if (entry == null) {
            return Eviction.NOT_OPEN;
        }
        evictionCount++;
        if (entry.borrows > 0) {
            entry.pendingClose = true;
            pendingCloses++;
            LOGGER.debug("deferring close of namespace {} ({}); {} lease(s) outstanding",
                id, why, entry.borrows);
            return Eviction.DEFERRED;
        }
        closeEntry(entry, why);
        return Eviction.CLOSED;
    }

    /** Closes a resource that nothing is borrowing and gives its mappings back to the budget. */
    private void closeEntry(Entry<T> entry, String why) {
        // Accounting is released whether or not the close succeeded. A resource whose close threw
        // is gone from the registry either way, and leaving its mappings on the books would leak
        // budget until the process restarted.
        try {
            entry.resource.close();
        } catch (Exception e) {
            LOGGER.warn("failed to close namespace {} while evicting ({})", entry.id, why, e);
        } finally {
            budget.release(entry.mappings, entry.descriptors);
        }
        LOGGER.debug("evicted namespace {} ({}); {}", entry.id, why, budget);
    }

    /** Namespaces currently open, most recently used last. */
    public List<NamespaceId> openNamespaces() {
        lock.lock();
        try {
            return new ArrayList<>(open.keySet());
        } finally {
            lock.unlock();
        }
    }

    /** How many namespaces are open. */
    public int openCount() {
        lock.lock();
        try {
            return open.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * How many evicted namespaces are still mapped because leases are outstanding.
     *
     * <p>Worth a gauge. This is the only outward sign of a lease that was never closed, and such a
     * lease holds mappings the registry can no longer reclaim by any means short of a restart, so a
     * count that does not come back down is the shape a mapping leak has.
     */
    public int pendingCloseCount() {
        lock.lock();
        try {
            return pendingCloses;
        } finally {
            lock.unlock();
        }
    }

    /** How many evictions have happened, for a counter. */
    public long evictionCount() {
        lock.lock();
        try {
            return evictionCount;
        } finally {
            lock.unlock();
        }
    }

    /** The budget this registry is working to, for gauges. */
    public MappingBudget budget() {
        return budget;
    }

    /**
     * Closes every open namespace.
     *
     * <p>A borrowed namespace is not closed here. Its reader is mid-flight, and unmapping under it
     * would make shutdown the one moment that produces the use-after-close a lease exists to
     * prevent. Each such namespace is evicted and its close deferred exactly as
     * {@link #evictOrDefer(NamespaceId)} would, so it closes when its last lease is released.
     *
     * <p>The registry itself is closed regardless and refuses further work at once. It does not
     * wait for leases: blocking shutdown on a lease that is never released would hang the process,
     * where returning leaves at worst a few namespaces mapped until the process exits. An
     * outstanding lease at this point is a caller that has not finished tidying up, so the count is
     * logged at warning level. {@link #pendingCloseCount()} reports what is left.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            for (NamespaceId id : new ArrayList<>(open.keySet())) {
                evictOrDefer(id, "registry closing");
            }
            if (pendingCloses > 0) {
                LOGGER.warn("registry closed with {} namespace(s) still borrowed; each stays "
                    + "mapped until its last lease is released. {}", pendingCloses, budget);
            }
        } finally {
            lock.unlock();
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("namespace registry is closed");
        }
    }

    /**
     * One open namespace: its resource and what that resource costs.
     *
     * <p>Not a record, because the borrow count and the pending-close flag change over the entry's
     * life. An entry is in the open map or pending close, never both - eviction removes it before
     * marking it - so a fresh load of the same namespace gets an entry of its own and the two
     * accounts never mix.
     *
     * <h2>Thread Safety</h2>
     * <p>The mutable fields are read and written only under the registry's lock.
     *
     * @param <T> the per-namespace resource
     */
    private static final class Entry<T> {

        private final NamespaceId id;
        private final T resource;
        private final long mappings;
        private final long descriptors;

        /** Leases outstanding. Guarded by the registry's lock. */
        private int borrows;

        /** Whether the registry has evicted this entry and is waiting on its leases to close it. */
        private boolean pendingClose;

        Entry(NamespaceId id, T resource, long mappings, long descriptors) {
            this.id = id;
            this.resource = resource;
            this.mappings = mappings;
            this.descriptors = descriptors;
        }
    }

    /**
     * The lease handed out by {@link NamespaceRegistry#borrow(NamespaceId)}.
     *
     * <h2>Thread Safety</h2>
     * <p>Closable from any thread, and from several at once: the flag picks a single winner, so the
     * borrow count falls by exactly one however many times close is called. A second decrement
     * would let the registry close a resource that another lease still has out, which is worse than
     * the leak it would be trying to correct.
     *
     * @param <T> the per-namespace resource
     */
    private static final class BorrowedLease<T extends AutoCloseable> implements Lease<T> {

        private final NamespaceRegistry<T> registry;
        private final Entry<T> entry;
        private final AtomicBoolean released = new AtomicBoolean();

        BorrowedLease(NamespaceRegistry<T> registry, Entry<T> entry) {
            this.registry = registry;
            this.entry = entry;
        }

        @Override
        public T resource() {
            if (released.get()) {
                throw new IllegalStateException(
                    "lease on namespace " + entry.id + " has already been released");
            }
            return entry.resource;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                registry.release(entry);
            }
        }

        @Override
        public String toString() {
            return "Lease[" + entry.id + (released.get() ? ", released]" : "]");
        }
    }
}
