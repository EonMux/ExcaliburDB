package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;
import com.eonmux.excalibur.tenancy.TieringPolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Every namespace's graph, bounded by what the kernel will let this process map.
 *
 * <h2>Why the bound exists</h2>
 * <p>A served namespace is around twenty-seven memory mappings and two descriptors - measured
 * rather than assumed, because the Lucene share of it moves as segments merge - and a process has a
 * finite number of both. A node
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
 * deadline.</p>
 *
 * <p>It visits every namespace on disk, not only the resident ones. A sweep restricted to what
 * happened to be open was affordable and wrong: an obligation accepted with a 202, whose
 * namespace was then evicted and never queried again, was never discharged, and a node that
 * promises deletion within a bound and silently does not honour it is the worst failure this
 * mechanism has. What makes the wider sweep affordable is that the question and the work have
 * different costs - whether a namespace owes anything, and whether it is overdue, is in its
 * erasure log, which is one small append-only file rather than the mappings of a served
 * store. Only a namespace whose log says something is due gets reopened, and only
 * {@link #MAX_REOPENS_PER_SWEEP} of them per pass.</p>
 *
 * <p>The scan itself is one log per non-resident namespace per pass, so it grows with the number
 * of tenants a node holds rather than with the number that owe anything. That is accepted
 * deliberately: the files are small and stay in the page cache, and the alternative - remembering
 * which namespaces owe something instead of asking - is a cache of exactly the fact that must
 * never be stale, because being wrong about it means an accepted erasure that nothing ever
 * carries out.</p>
 *
 * <h2>Why a namespace can be listed and dropped</h2>
 * <p>Naming a namespace in the {@value #NAMESPACE_HEADER} header creates it. Until there was an
 * inventory, nothing could say which ones existed - {@link #openNamespaces()} reports a cache's
 * contents - and nothing could remove one, so a tenant offboarded from the product left its
 * graph, its staged facts and its erasure log on disk for the life of the deployment.
 * {@link #namespacesOnDisk()} and {@link #drop(NamespaceId)} are the two halves of that: an
 * inventory that cannot be acted on is a report nobody can use, and a delete over a set nobody
 * can enumerate cannot be aimed.</p>
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
     * twice it. Checking is cheap enough to do that often: an outstanding count per resident
     * namespace, and one small log read per namespace that is not resident. Nothing is opened
     * unless something is owed and overdue.</p>
     */
    private static final long SWEEP_FRACTION = 10;

    /** Shortest gap between sweeps, so a small configured bound cannot turn into a spin. */
    static final long MIN_SWEEP_INTERVAL_MS = 1_000L;

    /**
     * Most tenants one sweep will reopen to discharge an overdue obligation.
     *
     * <p>A bound is required. A node that has been handed a thousand namespaces and offboarded
     * them all would otherwise answer one timer tick by opening a thousand graphs, which is the
     * process running out of mappings - the outcome the whole eviction mechanism exists to
     * prevent, arrived at by the mechanism meant to keep a promise.</p>
     *
     * <p>Four, because a sweep runs ten times per latency window: forty overdue namespaces are
     * reached within one window without displacing a resident working set of any size worth
     * having. A tenant reopened here stays resident, so the following pass spends its budget on
     * different namespaces rather than the same ones, and the candidates are taken
     * oldest-obligation-first so that what is closest to breaching goes first. A backlog longer
     * than one pass can absorb is logged at WARN: at that point the node cannot meet its bound,
     * and that is an operator's problem to see rather than a sweep's to hide.</p>
     */
    static final int MAX_REOPENS_PER_SWEEP = 4;

    /** How long close() waits for a sweep in flight, which may be inside a compaction. */
    private static final long SHUTDOWN_WAIT_SECONDS = 30L;

    private final Path root;
    private final NamespaceRegistry<GraphTenant> registry;
    private final MappingBudget budget;
    private final Duration erasureMaxLatency;
    private final int reopensPerSweep;
    private final ScheduledExecutorService erasureSweep;

    /**
     * A lease on the shared namespace, held for the life of this registry.
     *
     * <p>The shared namespace is where every request without a {@value #NAMESPACE_HEADER} header
     * lands, and where every source registered without one stages, so it is the one namespace
     * that is never worth reopening. Pinning it is also what makes {@link #invalidate(NamespaceId)}
     * safe to refuse for it: evicting a tenant this class holds a reference to would leave the
     * old one alive behind that reference and open a second on the same directory.</p>
     *
     * <p>Every other namespace, and the shared one for ingestion's purposes, is borrowed for the
     * length of an operation - see {@code NamespaceRoutingGraphIngestSink}, which borrows per
     * published document rather than holding a staging store across an eviction.</p>
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
        Files.createDirectories(root);
        // Normalised once, so every path this class derives is comparable against one spelling
        // of the root rather than against whatever the caller happened to pass.
        this.root = root.toAbsolutePath().normalize();
        this.erasureMaxLatency =
            Duration.ofSeconds(config.graph().getErasureMaxLatencySeconds());
        // Never more than the registry can hold open: beyond that a pass is only evicting the
        // tenants it reopened earlier in the same pass.
        this.reopensPerSweep = Math.max(1, Math.min(MAX_REOPENS_PER_SWEEP, maxOpen));

        TieringPolicy tiering = TieringPolicy.declared(
            namespaces(config.graph().getHotNamespaces(), "excalibur.graph.hot-namespaces"),
            namespaces(config.graph().getColdNamespaces(), "excalibur.graph.cold-namespaces"));

        this.registry = new NamespaceRegistry<>(
            this.root,
            (id, directory) ->
                GraphTenant.open(id, directory.resolve(GraphTenant.GRAPH_SUBDIRECTORY), config),
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
     * Compacts every namespace whose erasure policy says a compaction is due.
     *
     * <p>Two passes over two populations, because they cost different amounts to ask. The
     * resident namespaces are already mapped, so each is simply asked what it owes. The rest are
     * asked their erasure log, which is a file read rather than a tenant open; only the ones
     * whose oldest obligation has passed {@code excalibur.graph.erasure-max-latency-seconds} are
     * reopened, and at most {@link #MAX_REOPENS_PER_SWEEP} of them per pass.</p>
     *
     * <p>A tenant being read is left alone by eviction for the length of the lease this takes,
     * so a compaction never unmaps a store out from under a query. One namespace failing - to be
     * read, to be opened, or to be compacted - never stops the others: it is logged at ERROR and
     * retried on the next pass, because the alternative is one bad tenant suspending the
     * deadline for every other one on the node.</p>
     *
     * @return How many namespaces were visited with something outstanding
     */
    public int compactDueErasures() {
        int owing = compactResidentNamespaces();
        return owing + compactOverdueEvictedNamespaces();
    }

    /**
     * Compacts what is already open, which costs nothing to ask and nothing to reach.
     *
     * @return How many resident namespaces had something outstanding
     */
    private int compactResidentNamespaces() {
        int owing = 0;
        for (NamespaceId id : openNamespaces()) {
            Optional<NamespaceRegistry.Lease<GraphTenant>> lease = borrowResident(id);
            if (lease.isEmpty()) {
                // Evicted between listing and borrowing. borrowResident declines to reopen it
                // rather than spending the mapping budget the eviction just reclaimed; the pass
                // below picks it up from its log if what it owes is overdue.
                continue;
            }
            try (NamespaceRegistry.Lease<GraphTenant> held = lease.get()) {
                owing += compactTenant(id, held.resource()) ? 1 : 0;
            }
        }
        return owing;
    }

    /**
     * Reopens and compacts the evicted namespaces whose obligations have passed the deadline.
     *
     * <p>Ordered by the oldest obligation, so a backlog longer than one pass can absorb is
     * worked off closest-to-breaching first rather than in directory order.</p>
     *
     * @return How many reopened namespaces had something outstanding
     */
    private int compactOverdueEvictedNamespaces() {
        List<Overdue> due = overdueEvictedNamespaces();
        if (due.isEmpty()) {
            return 0;
        }
        if (due.size() > reopensPerSweep) {
            LOGGER.warn("{} evicted namespace(s) hold erasure obligations past the {}s bound and "
                + "only {} are reopened per sweep; the oldest go first, but this node is not "
                + "keeping its erasure deadline", due.size(), erasureMaxLatency.toSeconds(),
                reopensPerSweep);
        }

        int owing = 0;
        for (Overdue candidate : due.subList(0, Math.min(due.size(), reopensPerSweep))) {
            NamespaceId id = candidate.id();
            try {
                // borrowIfPresent, not borrowResident: this one has to load. It also marks
                // the namespace recently used, which a background visit should not - but a
                // tenant this pass is about to rewrite is genuinely in use, and the
                // MAX_REOPENS_PER_SWEEP bound keeps the distortion to a handful per pass.
                Optional<NamespaceRegistry.Lease<GraphTenant>> lease = registry.borrowIfPresent(id);
                if (lease.isEmpty()) {
                    // Dropped between the listing and the reopen. Nothing is owed by a namespace
                    // that no longer exists.
                    continue;
                }
                try (NamespaceRegistry.Lease<GraphTenant> held = lease.get()) {
                    LOGGER.info("reopening namespace {} to discharge an erasure obligation "
                        + "outstanding since {}", id, candidate.oldestRequestedAt());
                    owing += compactTenant(id, held.resource()) ? 1 : 0;
                }
            } catch (RuntimeException e) {
                // A namespace that cannot be opened arrives here as an UncheckedIOException from
                // the registry. It still owes what it owes, and the next pass tries again.
                LOGGER.error("could not reopen namespace {} to discharge its erasure "
                    + "obligations: {}", id, e.toString());
            }
        }
        return owing;
    }

    /**
     * The evicted namespaces whose erasure logs say something is outstanding and overdue.
     *
     * <p>Read from the logs alone. That is the whole reason this is affordable: the question is
     * a small append-only file per namespace, where the answer would be a served store.</p>
     *
     * @return The candidates, oldest obligation first
     */
    private List<Overdue> overdueEvictedNamespaces() {
        List<NamespaceId> resident = openNamespaces();
        List<NamespaceId> onDisk;
        try {
            onDisk = namespacesOnDisk();
        } catch (IOException e) {
            LOGGER.error("could not enumerate the namespaces under {}; only the resident ones "
                + "were swept for erasure this pass: {}", root, e.toString());
            return List.of();
        }

        Instant now = Instant.now();
        List<Overdue> due = new ArrayList<>();
        for (NamespaceId id : onDisk) {
            if (resident.contains(id)) {
                continue;
            }
            try {
                ErasureLog.Pending pending =
                    ErasureLog.inspect(GraphTenant.erasureLogOf(graphDirectoryOf(id)));
                if (pending.dueAt(now, erasureMaxLatency)) {
                    due.add(new Overdue(id, pending.oldestRequestedAt().orElseThrow()));
                }
            } catch (IOException | RuntimeException e) {
                // Never read as "nothing outstanding". A log this node cannot read is a log
                // whose obligations it cannot account for, and treating that as a clean sweep is
                // exactly how an accepted erasure disappears.
                LOGGER.error("could not read the erasure log of namespace {}; whatever it owes "
                    + "is still owed and is not being swept: {}", id, e.toString());
            }
        }
        due.sort(Comparator.comparing(Overdue::oldestRequestedAt));
        return due;
    }

    /**
     * An evicted namespace that owes something past the deadline.
     *
     * @param id                The namespace
     * @param oldestRequestedAt When its longest-waiting obligation arrived
     */
    private record Overdue(NamespaceId id, Instant oldestRequestedAt) {
    }

    /**
     * Compacts one borrowed tenant if it owes anything.
     *
     * @param id     The namespace, for the log lines
     * @param tenant Its graph, borrowed for the length of this call
     * @return true if it had something outstanding, whether or not the compaction ran
     */
    private boolean compactTenant(NamespaceId id, GraphTenant tenant) {
        GraphErasureService erasure = tenant.erasureService();
        if (erasure.outstandingCount() == 0) {
            return false;
        }
        try {
            GraphErasureService.ErasureOutcome outcome = erasure.compactIfDue();
            if (outcome.ran()) {
                LOGGER.info("erasure deadline reached for namespace {}: {} edge(s) removed, "
                    + "{} request(s) discharged, {} withheld", id, outcome.edgesRemoved(),
                    outcome.requestsSatisfied(), outcome.requestsWithheld());
                // The store the budget was told about is not the one now mapped, and a reopen
                // that failed leaves the tenant serving nothing while still charged for a graph.
                recost(id);
            } else {
                LOGGER.debug("namespace {} owes {} erasure(s), none due yet: {}",
                    id, erasure.outstandingCount(), outcome.reason());
            }
        } catch (IOException e) {
            // One namespace's compaction failing must not stop the others being swept, and must
            // not stop this one being retried on the next pass.
            LOGGER.error("could not compact namespace {} for erasure: {}", id, e.toString());
        }
        return true;
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
     * Builds one namespace's graph, from the borrow to the re-costing.
     *
     * <h3>Why the whole operation lives here</h3>
     * <p>A build is three things a caller would otherwise have to remember in order: borrow the
     * namespace so it cannot be evicted out from under a build that is mapping its files, build
     * it, and tell the budget what it now costs. The last is the one that is easy to forget and
     * invisible when it is - a namespace that held no graph a moment ago now holds dozens of
     * mappings and two descriptors, and a budget that was not told under-counts the process for
     * as long as it runs.</p>
     *
     * <h3>What it does not do</h3>
     * <p>Serialise builds across namespaces. This borrows and builds on the calling thread;
     * running one build at a time is a decision about what a node should accept at once, and it
     * belongs to the route that accepts them.</p>
     *
     * @param id              The namespace to build
     * @param cancelRequested Read at the boundaries of the build; true asks it to stop
     * @return What was built
     * @throws IOException if the graph cannot be built or reopened
     * @throws com.eonmux.excalibur.graph.build.BuildCancelledException if the build was asked to stop
     *                     and reached a boundary before publishing
     */
    public GraphBuildService.BuildOutcome build(NamespaceId id, BooleanSupplier cancelRequested)
            throws IOException {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(cancelRequested, "cancelRequested must not be null");
        try (NamespaceRegistry.Lease<GraphTenant> lease = borrow(id)) {
            GraphBuildService.BuildOutcome outcome = lease.resource().build(cancelRequested);
            // The build installed the published stack into this tenant's holder, which is the
            // one every namespace-scoped request reads, so nothing stale is left to drop. What
            // did change is what the tenant costs.
            recost(id);
            return outcome;
        }
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
     * The build that installs a store gives it the whole served stack, and nothing reopens it to
     * notice - so without this the budget under-counts the process's real mappings for as long as
     * it runs. It is also what folds in a figure that moved on its own: the Lucene share of the
     * cost falls when segments merge.</p>
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
     * <p>What is resident, which is a cache's contents. For what exists, which is what an
     * operator means by "the namespaces on this node", see {@link #namespacesOnDisk()}.</p>
     *
     * @return Their ids
     */
    public List<NamespaceId> openNamespaces() {
        return registry.openNamespaces();
    }

    /**
     * Every namespace that exists under the registry root, open or not.
     *
     * <p>{@link #openNamespaces()} reports what is resident, which is a cache's contents rather
     * than an inventory: a tenant evicted an hour ago exists exactly as much as one being
     * queried now, and an operator asking "what is on this node" means this list.</p>
     *
     * <p>A directory whose name is not a valid namespace is passed over rather than coerced. A
     * name that {@link NamespaceId#of} refuses cannot be a namespace this node created, so it is
     * something an operator or a restore put there, and inventing an identifier for it would put
     * a name into a list that {@link #drop(NamespaceId)} would then refuse or, worse, resolve
     * somewhere else.</p>
     *
     * @return The namespaces, in identifier order
     * @throws IOException if the root cannot be listed
     */
    public List<NamespaceId> namespacesOnDisk() throws IOException {
        List<NamespaceId> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                NamespaceId id = namespaceOfDirectory(entry.getFileName().toString());
                if (id != null) {
                    found.add(id);
                }
            }
        }
        found.sort(Comparator.naturalOrder());
        return List.copyOf(found);
    }

    /**
     * The namespace a directory name denotes, or null when it denotes none.
     *
     * <p>The shared namespace is matched against the constant rather than parsed, because
     * {@link NamespaceId#of} refuses its name on purpose: a tenant may not claim it. It is still
     * a namespace on disk, and the one every unnamespaced request lands in.</p>
     *
     * @param name One directory name directly under the root
     * @return The namespace, or null
     */
    private static NamespaceId namespaceOfDirectory(String name) {
        if (NamespaceId.SHARED.value().equals(name)) {
            return NamespaceId.SHARED;
        }
        try {
            return NamespaceId.of(name);
        } catch (IllegalArgumentException e) {
            LOGGER.debug("ignoring '{}' under the graph root: it is not a namespace name ({})",
                name, e.getMessage());
            return null;
        }
    }

    /**
     * What one namespace holds, as far as can be told without opening its graph.
     *
     * @param id                  The namespace
     * @param open                Whether its graph is currently resident
     * @param built               Whether a build has ever published a graph for it
     * @param stagedFacts         How many facts it has staged, or empty when the staging log
     *                            could not be measured
     * @param outstandingErasures How many erasure obligations it still owes, or empty when the
     *                            erasure log could not be read - which is never the same as zero
     * @param error               What could not be read, or null when everything could
     */
    public record NamespaceSummary(NamespaceId id, boolean open, boolean built,
                                   OptionalLong stagedFacts, OptionalInt outstandingErasures,
                                   String error) {

        public NamespaceSummary {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(stagedFacts, "stagedFacts must not be null");
            Objects.requireNonNull(outstandingErasures, "outstandingErasures must not be null");
        }
    }

    /**
     * Describes every namespace on disk, for an operator deciding what to do with them.
     *
     * <h3>What it costs</h3>
     * <p>For a resident namespace, nothing beyond a lease: the tenant already knows what it has
     * staged and what it owes. For one that is not resident, two file reads - a {@code stat} of
     * the staging log, whose fixed-width records make its length the count, and a read of the
     * erasure log, which is one small record per accepted obligation. No graph is opened, so an
     * inventory does not evict the working set to produce a report, and a node holding a
     * thousand offboarded tenants can be asked about all of them.</p>
     *
     * <p>A namespace whose logs cannot be read is still listed, with the counts it could not
     * produce left empty and {@link NamespaceSummary#error()} saying why. Reporting zero would
     * be the one answer that reads as "safe to remove".</p>
     *
     * @return One summary per namespace, in identifier order
     * @throws IOException if the root cannot be listed
     */
    public List<NamespaceSummary> describeNamespaces() throws IOException {
        List<NamespaceSummary> summaries = new ArrayList<>();
        for (NamespaceId id : namespacesOnDisk()) {
            summaries.add(describe(id));
        }
        return List.copyOf(summaries);
    }

    /**
     * Describes one namespace, preferring the resident tenant's own answers where there is one.
     *
     * @param id The namespace
     * @return Its summary
     */
    private NamespaceSummary describe(NamespaceId id) {
        Optional<NamespaceRegistry.Lease<GraphTenant>> resident = registry.borrowResident(id);
        if (resident.isEmpty()) {
            return describeFromDisk(id);
        }
        try (NamespaceRegistry.Lease<GraphTenant> held = resident.get()) {
            GraphTenant tenant = held.resource();
            // A resident tenant may hold appends that are buffered and not yet in the file, so
            // its own counts are the true ones wherever they are available. "Built" is still
            // read from disk: the holder says what is being served now, and a tenant whose
            // stack failed to reopen after a compaction has still built a graph.
            return new NamespaceSummary(id, true,
                GraphTenant.hasBuiltGraph(graphDirectoryOf(id)),
                OptionalLong.of(tenant.staging().stagedCount()),
                OptionalInt.of(tenant.erasureService().outstandingCount()), null);
        }
    }

    /**
     * Describes a namespace nothing has open, from its logs alone.
     *
     * @param id The namespace
     * @return Its summary, with an error rather than a count for anything unreadable
     */
    private NamespaceSummary describeFromDisk(NamespaceId id) {
        Path graph = graphDirectoryOf(id);
        List<String> problems = new ArrayList<>(2);

        OptionalLong staged = OptionalLong.empty();
        try {
            staged = OptionalLong.of(GraphTenant.stagedFactsOnDisk(graph));
        } catch (IOException | RuntimeException e) {
            LOGGER.error("could not measure the staging log of namespace {}: {}", id, e.toString());
            problems.add("the staging log could not be measured: " + e.getMessage());
        }

        OptionalInt outstanding = OptionalInt.empty();
        try {
            outstanding = OptionalInt.of(
                ErasureLog.inspect(GraphTenant.erasureLogOf(graph)).count());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("could not read the erasure log of namespace {}: {}", id, e.toString());
            problems.add("the erasure log could not be read: " + e.getMessage());
        }

        return new NamespaceSummary(id, false, GraphTenant.hasBuiltGraph(graph), staged,
            outstanding, problems.isEmpty() ? null : String.join("; ", problems));
    }

    /**
     * What dropping a namespace destroyed.
     *
     * <p>Every field is measured before the delete, because after it there is nothing left to
     * ask. A caller that asked for an irreversible removal is owed an exact account of what went
     * with it.</p>
     *
     * @param id                 The namespace that no longer exists
     * @param wasOpen            Whether its graph was resident and had to be closed
     * @param hadGraph           Whether a build had ever published a graph for it
     * @param stagedFacts        How many staged facts went with it
     * @param discardedErasures  How many erasure obligations were discarded unapplied, or empty
     *                           when the log could not be read; the data they named is gone
     *                           either way, which is why the drop proceeds
     * @param filesDeleted       How many files were removed
     * @param bytesDeleted       How many bytes those files held
     */
    public record Dropped(NamespaceId id, boolean wasOpen, boolean hadGraph, long stagedFacts,
                          OptionalInt discardedErasures, long filesDeleted, long bytesDeleted) {

        public Dropped {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(discardedErasures, "discardedErasures must not be null");
        }
    }

    /**
     * Removes a namespace from this node entirely: its graph, its staging and its erasure log.
     *
     * <h3>Why this exists</h3>
     * <p>A namespace is created by naming it in the {@value #NAMESPACE_HEADER} header, and until
     * this there was no way back. A tenant offboarded from the product left everything it had
     * ever staged on disk indefinitely - which is a data-retention failure in a system that
     * takes erasure obligations seriously, and an operator could not even discover it.</p>
     *
     * <h3>What it does</h3>
     * <p>Evicts the namespace, closes its tenant, and deletes its directory tree - which holds
     * both {@link GraphTenant#GRAPH_SUBDIRECTORY} and the {@value GraphTenant#STAGING_SUFFIX}
     * directory beside it, so the staged facts and the erasure log go with the graph rather than
     * outliving it. Irreversible: there is no tombstone and nothing to restore from.</p>
     *
     * <p>Outstanding erasure obligations are discarded rather than applied. That is not a
     * shortcut - deleting the store they named satisfies them more completely than a compaction
     * would - but the count is reported, because an operator has to be able to see that the node
     * stopped owing something by destroying it.</p>
     *
     * <p>If a lease is outstanding the registry defers closing the tenant, and the files are
     * unlinked underneath it. That is correct on POSIX, where the reader keeps reading the
     * inode it already holds; on a filesystem that refuses to unlink an open file the delete
     * fails and the caller is told so rather than being told a lie.</p>
     *
     * <p>A request that names the same namespace while this is running can reopen the tenant in
     * the window between the eviction and the delete, and a tenant reopening writes its staging
     * directory. The namespace is evicted again afterwards so nothing is left holding deleted
     * files, but the delete itself can fail against a directory that was recreated under it -
     * reported as a failure, with whatever is left removable by repeating the request. Racing a
     * drop against traffic for the same tenant is not a supported operation; offboarding is.</p>
     *
     * @param id The namespace
     * @return What was destroyed, or empty if no such namespace exists on disk
     * @throws IllegalArgumentException if {@code id} is the shared namespace
     * @throws IOException if the directory tree cannot be measured or removed
     */
    public Optional<Dropped> drop(NamespaceId id) throws IOException {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isShared()) {
            throw new IllegalArgumentException(
                "the shared namespace cannot be dropped: it is where every request without a "
                    + NAMESPACE_HEADER + " header lands, and this registry holds a lease on it "
                    + "for the life of the process");
        }

        Path directory = id.directoryUnder(root);
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }

        // Closed before it is measured, so a resident tenant's buffered appends are in the files
        // this is about to account for and delete.
        boolean wasOpen = registry.evictOrDefer(id) != NamespaceRegistry.Eviction.NOT_OPEN;

        Path graph = graphDirectoryOf(id);
        boolean hadGraph = GraphTenant.hasBuiltGraph(graph);
        long stagedFacts = GraphTenant.stagedFactsOnDisk(graph);
        OptionalInt discarded = outstandingOnDisk(id, graph);

        long[] removed = deleteTree(directory);
        // Again, because a request naming this namespace between the first eviction and the
        // delete would have reopened a tenant over files that are now gone. Dropping it here
        // means the next request opens a fresh namespace rather than writing into the void.
        registry.evict(id);
        LOGGER.warn("namespace {} dropped: {} file(s), {} byte(s), {} staged fact(s) and {} "
            + "erasure obligation(s) destroyed", id, removed[0], removed[1], stagedFacts,
            discarded.isPresent() ? discarded.getAsInt() : "an unreadable number of");
        return Optional.of(new Dropped(id, wasOpen, hadGraph, stagedFacts, discarded,
            removed[0], removed[1]));
    }

    /**
     * How many obligations a namespace's log still holds, or empty when it cannot be read.
     *
     * <p>A log this node cannot parse does not stop a drop. The caller asked for the data to be
     * destroyed, and destroying it is what an unapplied obligation wanted; refusing would leave
     * the tenant on disk because a file describing what to delete was unreadable.</p>
     *
     * @param id    The namespace, for the log line
     * @param graph Its graph directory
     * @return The count, or empty
     */
    private static OptionalInt outstandingOnDisk(NamespaceId id, Path graph) {
        try {
            return OptionalInt.of(ErasureLog.inspect(GraphTenant.erasureLogOf(graph)).count());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("could not read the erasure log of namespace {} before dropping it; "
                + "the drop goes ahead and destroys whatever it named: {}", id, e.toString());
            return OptionalInt.empty();
        }
    }

    /**
     * Deletes a directory tree depth-first, counting what it held.
     *
     * @param directory The tree to remove
     * @return {@code [files, bytes]}
     * @throws IOException if anything cannot be removed
     */
    private static long[] deleteTree(Path directory) throws IOException {
        long[] counted = new long[2];
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                counted[0]++;
                counted[1] += attributes.size();
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path visited, IOException failure)
                    throws IOException {
                // A walk that could not read a subdirectory must not report the tree as removed.
                if (failure != null) {
                    throw failure;
                }
                Files.delete(visited);
                return FileVisitResult.CONTINUE;
            }
        });
        return counted;
    }

    /**
     * Where a namespace's graph is served from.
     *
     * @param id The namespace
     * @return Its graph directory, which need not exist
     */
    private Path graphDirectoryOf(NamespaceId id) {
        return id.directoryUnder(root).resolve(GraphTenant.GRAPH_SUBDIRECTORY);
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
     * <p>For a caller that needs the shared tenant without a borrow, which is safe only because
     * this class pins it. Ingestion no longer uses it: it borrows the namespace each document
     * belongs to.</p>
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
