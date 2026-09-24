package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.bootstrap.GraphBuildService;
import com.eonmux.excalibur.api.bootstrap.GraphExtraction;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRefusedException;

import com.eonmux.excalibur.graph.build.BuildCancelledException;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Runs one graph build at a time, behind the route that asked for it, and remembers what it did.
 *
 * <h2>Why the build is not done on the request thread</h2>
 * <p>A build replays every staged fact into a fresh edge store, writes two Lucene indexes over
 * the prose those facts came from and embeds every entity. On a real corpus that is minutes, and
 * a request held open for it is a connection a load balancer will cut, a client will time out
 * of, and a caller will retry - while the first build carries on invisibly and the retry races
 * it. So the route accepts and this runs the work, exactly as {@link CompactionRunner} does for
 * {@code POST /v1/compact}.</p>
 *
 * <h2>Why one at a time, for the whole node</h2>
 * <p>Two builds do not corrupt each other: each namespace's build serialises on its own lock and
 * assembles into its own scratch directory. They do something worse for an operator, which is to
 * take twice as long each while saturating the same disk and the same embedding endpoint, with
 * neither caller told why. A second request is therefore refused and told which namespace holds
 * the node.</p>
 *
 * <h2>Why the memory of a build is per namespace</h2>
 * <p>Every graph route is scoped by the {@value GraphTenants#NAMESPACE_HEADER} header, and a
 * status route that answered with another tenant's build would be the one place where that stops
 * being true. Each namespace is therefore told about its own last build, and a namespace that
 * has never had one is idle - even while another namespace is building.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. The single build slot and the per-namespace memory are guarded by one lock,
 * held for the few field writes each operation needs and never across a build.</p>
 *
 * @see GraphBuildEndpoint
 * @see GraphBuildStatusEndpoint
 * @see GraphBuildCancelEndpoint
 * @author ExcaliburDB
 */
public final class GraphBuildRunner implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphBuildRunner.class);

    /** What {@link #close()} waits for: a build it asked to stop, for as long as that takes. */
    private static final Duration UNTIL_STOPPED = Duration.ofNanos(Long.MAX_VALUE);

    /**
     * How many namespaces' last builds are remembered.
     *
     * <p>A bound rather than a map that grows with every name a caller puts in the header. The
     * least recently touched entry is dropped, which is never the build in progress - it is the
     * most recently touched one - so forgetting costs an operator a status they could have read
     * and never costs the node the state it is acting on.</p>
     */
    private static final int REMEMBERED_NAMESPACES = 64;

    /** No build has been asked for, for this namespace, since the node started. */
    public static final String STATE_IDLE = "idle";

    /** A build is happening now. */
    public static final String STATE_RUNNING = "running";

    /** The last build finished; whether it published anything is in its outcome. */
    public static final String STATE_COMPLETED = "completed";

    /** The last build raised an error and published nothing. */
    public static final String STATE_FAILED = "failed";

    /**
     * The last build was asked to stop, and stopped before publishing.
     *
     * <p>A state of its own rather than {@code failed}, for the reason {@code /v1/reindex}
     * distinguishes the two: a stop somebody asked for is not an outage, and filing it as one
     * puts a deliberate act in the column an operator is paged about. Distinct from
     * {@code completed} too, because nothing was published - the namespace is still serving the
     * graph it had.</p>
     */
    public static final String STATE_CANCELLED = "cancelled";

    /**
     * The build itself, as one namespace's worth of work.
     *
     * <p>Narrower than the tenancy registry it is normally bound to, so the route can be tested
     * without a graph on disk behind it. {@link #over(GraphTenants)} is the real one.</p>
     */
    @FunctionalInterface
    public interface Build {

        /**
         * Builds one namespace's graph, on the calling thread.
         *
         * @param namespace       Whose graph to build
         * @param cancelRequested Read at the build's own boundaries; true asks it to stop
         * @return What was built
         * @throws IOException if the graph cannot be built or reopened
         * @throws BuildCancelledException if the build stopped because it was asked to
         */
        GraphBuildService.BuildOutcome build(NamespaceId namespace,
                                             java.util.function.BooleanSupplier cancelRequested)
            throws IOException;
    }

    /**
     * What the runner has to say about one namespace's current or last build.
     *
     * @param state        one of the {@code STATE_*} constants
     * @param namespace    whose build this describes
     * @param startedAtMs  wall-clock start, or 0 when nothing has been built
     * @param finishedAtMs wall-clock finish, or 0 while a build is in progress
     * @param outcome      what the build published, or null while running, stopped or failed
     * @param error        why the build failed, or null
     */
    public record Snapshot(String state, NamespaceId namespace, long startedAtMs,
                           long finishedAtMs, GraphBuildService.BuildOutcome outcome,
                           String error) {

        /**
         * Whether a build is happening now.
         *
         * @return true while one is in progress
         */
        public boolean isRunning() {
            return STATE_RUNNING.equals(state);
        }
    }

    /**
     * The answer to a request to start a build: what was started, or what is in the way.
     *
     * <p>Both are returned rather than a flag, so the route can describe the build it started -
     * or name the namespace holding the node - without re-reading shared state that a short
     * build may already have moved on from.</p>
     *
     * @param started   the build this request started, or null if it started nothing
     * @param blockedBy the build already in progress, or null if this request started one
     */
    public record Claim(Snapshot started, Snapshot blockedBy) {

        /**
         * Whether this request started a build.
         *
         * @return true if a build was started
         */
        public boolean accepted() {
            return started != null;
        }
    }

    private final Build build;
    private final Supplier<Optional<GraphExtraction>> extraction;
    private final NamespaceAdmission admission;
    private final ExecutorService executor;
    private final Object lock = new Object();

    /**
     * Whether builds are still accepted: false from the moment a drain begins. Guarded by
     * {@link #lock}.
     *
     * <p>Read before the namespace is admitted, and the build handed to the executor under the
     * same lock, so a build refused because the node is shutting down has admitted - and
     * possibly created - nothing. Relying on the executor's own refusal instead would find out
     * only after the admission.</p>
     */
    private boolean accepting = true;

    /**
     * Whether the build in progress has been asked to stop.
     *
     * <p>Read by the build itself at its own boundaries. Not an interrupt: a build publishes by
     * closing the served stack, renaming two directories and reopening what it renamed, and an
     * interrupt landing in that window leaves a namespace serving nothing over a graph that is
     * intact on disk - which is worse than the long build the stop was meant to end.</p>
     */
    private final AtomicBoolean cancelRequested = new AtomicBoolean();

    /** The build in progress, or null when the node is not building anything. */
    private Snapshot inFlight;

    /**
     * How every build since startup ended, by outcome.
     *
     * <p>Kept apart from the per-namespace memory, which is bounded and forgets: a node's stats
     * report whether builds are failing, and a count that drops when the sixty-fifth namespace
     * builds would say they had stopped.</p>
     */
    private long completedBuilds;
    private long failedBuilds;
    private long cancelledBuilds;

    /** The last build of each namespace, bounded and least-recently-touched first. */
    private final Map<NamespaceId, Snapshot> remembered =
        new LinkedHashMap<>(16, 0.75f, true) {

            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<NamespaceId, Snapshot> eldest) {
                return size() > REMEMBERED_NAMESPACES;
            }
        };

    /**
     * Creates a runner that builds the graphs of the given namespaces.
     *
     * @param tenants The namespaces whose graphs can be built
     * @throws NullPointerException if tenants is null
     */
    public GraphBuildRunner(GraphTenants tenants) {
        this(over(tenants), () -> Optional.of(tenants.extraction()), tenants::admit);
    }

    /**
     * Creates a runner over the given build work, on a node that does not say whether its
     * ingestion extracts facts.
     *
     * @param build What building one namespace's graph does
     * @throws NullPointerException if build is null
     */
    public GraphBuildRunner(Build build) {
        this(build, Optional::empty);
    }

    /**
     * Creates a runner over the given build work.
     *
     * @param build      What building one namespace's graph does
     * @param extraction Whether ingestion on this node extracts facts, read on every request
     *                   because ingestion is wired after the routes are
     * @throws NullPointerException if either is null
     */
    public GraphBuildRunner(Build build, Supplier<Optional<GraphExtraction>> extraction) {
        this(build, extraction, NamespaceAdmission.ANY);
    }

    /**
     * Creates a runner over the given build work that admits a namespace before building it.
     *
     * @param build      What building one namespace's graph does
     * @param extraction Whether ingestion on this node extracts facts, read on every request
     *                   because ingestion is wired after the routes are
     * @param admission  Whether a namespace may be built at all, asked before a build is claimed
     *                   so a refused one is answered to the caller rather than failing behind
     *                   the {@code 202}
     * @throws NullPointerException if any is null
     */
    public GraphBuildRunner(Build build, Supplier<Optional<GraphExtraction>> extraction,
                            NamespaceAdmission admission) {
        this.build = Objects.requireNonNull(build, "build cannot be null");
        this.extraction = Objects.requireNonNull(extraction, "extraction cannot be null");
        this.admission = Objects.requireNonNull(admission, "admission cannot be null");
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "graph-build");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The real build: borrow the namespace, build it, and tell the budget what it now costs.
     *
     * @param tenants The namespaces whose graphs can be built
     * @return The build work
     * @throws NullPointerException if tenants is null
     */
    public static Build over(GraphTenants tenants) {
        Objects.requireNonNull(tenants, "tenants cannot be null");
        return tenants::build;
    }

    /**
     * Starts a build of one namespace, unless the node is already building something.
     *
     * <p>The namespace is admitted once the slot is known to be free and the node is known to be
     * accepting builds, and before the slot is claimed: a refused namespace claims nothing, and a
     * build that is in the way, or a shutdown, creates nothing.</p>
     *
     * @param namespace Whose graph to build
     * @return What was started, or what is in the way
     * @throws NullPointerException if namespace is null
     * @throws NamespaceRefusedException if the namespace does not exist and may not be created
     * @throws UncheckedIOException if the namespace could not be created
     * @throws java.util.concurrent.RejectedExecutionException if the node is shutting down
     */
    public Claim start(NamespaceId namespace) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        long startedAtMs = System.currentTimeMillis();
        Snapshot started = new Snapshot(STATE_RUNNING, namespace, startedAtMs, 0L, null, null);
        synchronized (lock) {
            if (!accepting) {
                throw new RejectedExecutionException(
                    "the node is shutting down and starts no graph build");
            }
            if (inFlight != null) {
                return new Claim(null, inFlight);
            }
            try {
                admission.admit(namespace);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            inFlight = started;
            remembered.put(namespace, started);
            cancelRequested.set(false);
            try {
                executor.submit(() -> run(started));
            } catch (RuntimeException e) {
                // A rejected task would otherwise leave the slot claimed and every later build
                // answered 409 for the life of the process.
                finish(new Snapshot(STATE_FAILED, namespace, startedAtMs,
                    System.currentTimeMillis(), null,
                    "the build could not be scheduled: " + e.getMessage()));
                throw e;
            }
        }
        LOGGER.info("graph build of namespace {} started", namespace);
        return new Claim(started, null);
    }

    /**
     * What a namespace's current or last build has to say.
     *
     * @param namespace The namespace
     * @return Its snapshot, idle if it has never been built on this node
     * @throws NullPointerException if namespace is null
     */
    public Snapshot snapshot(NamespaceId namespace) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        synchronized (lock) {
            Snapshot snapshot = remembered.get(namespace);
            return snapshot != null
                ? snapshot
                : new Snapshot(STATE_IDLE, namespace, 0L, 0L, null, null);
        }
    }

    /**
     * Whether ingestion on this node extracts the facts a build publishes.
     *
     * <p>Beside the builds because it is the first thing to read when one published nothing:
     * a node that ingests without extracting stages no facts, and every build of it is empty.</p>
     *
     * @return The state, or empty when this node does not say
     */
    public Optional<GraphExtraction> extraction() {
        return extraction.get();
    }

    /**
     * Whether a build is running, and how every build since startup ended.
     *
     * <p>Totals across namespaces, for the node's stats: whether builds are failing is a
     * question about the node, and a count per namespace would be one series for every name a
     * caller ever put in the header.</p>
     *
     * @return The counts, read together
     */
    public Counts counts() {
        synchronized (lock) {
            return new Counts(inFlight != null, completedBuilds, failedBuilds, cancelledBuilds);
        }
    }

    /**
     * Graph builds on this node since startup.
     *
     * @param running   Whether one is in progress now
     * @param completed How many published a graph
     * @param failed    How many raised an error and published nothing
     * @param cancelled How many were asked to stop and stopped before publishing
     */
    public record Counts(boolean running, long completed, long failed, long cancelled) {

        /**
         * Validates the counts.
         *
         * @throws IllegalArgumentException if any is negative
         */
        public Counts {
            if (completed < 0 || failed < 0 || cancelled < 0) {
                throw new IllegalArgumentException("build counts must not be negative");
            }
        }
    }

    /**
     * Asks a namespace's build to stop, if that is the build in progress.
     *
     * <p>A request rather than a stop, in the same sense {@code DELETE /v1/compact} is: the
     * build ends at the next staged fact, the next snippet or the next embedded entity, and
     * reports itself {@link #STATE_CANCELLED}. It is safe at every one of those points, because
     * a build assembles into a scratch directory and becomes the served graph in a single
     * rename - so what a stopped build loses is its own work, never data, and the namespace
     * goes on serving the graph it had.</p>
     *
     * <p>Scoped to the namespace for the same reason the status is: a caller holding the
     * {@value GraphTenants#NAMESPACE_HEADER} header of one tenant must not be able to end
     * another tenant's build by sending a DELETE at the wrong moment.</p>
     *
     * @param namespace Whose build to stop
     * @return true if that namespace's build was in progress and has been asked to stop
     * @throws NullPointerException if namespace is null
     */
    public boolean cancel(NamespaceId namespace) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        synchronized (lock) {
            if (inFlight == null || !inFlight.namespace().equals(namespace)) {
                return false;
            }
            cancelRequested.set(true);
        }
        LOGGER.info("graph build of namespace {} was asked to stop", namespace);
        return true;
    }

    /**
     * Performs one build and records its outcome.
     *
     * @param started The build this thread is running
     */
    private void run(Snapshot started) {
        NamespaceId namespace = started.namespace();
        long startedAtMs = started.startedAtMs();
        try {
            GraphBuildService.BuildOutcome outcome =
                build.build(namespace, cancelRequested::get);
            // Completed, even where a stop was asked for: the build got past its last boundary
            // and published, so reporting it as stopped would describe a graph that is not the
            // one being served.
            finish(new Snapshot(STATE_COMPLETED, namespace, startedAtMs,
                System.currentTimeMillis(), outcome, null));
            LOGGER.info("graph build of namespace {} finished: {}", namespace, outcome);
        } catch (IOException | RuntimeException | Error e) {
            finish(endOf(e, namespace, startedAtMs));
        } finally {
            // This pool has one thread and every later build uses it. Nothing here interrupts a
            // build, but a flag left set by anything else would kill the next build at its
            // first channel operation - closing that channel - for a reason nothing in its own
            // request explains.
            Thread.interrupted();
        }
    }

    /**
     * Decides whether a build that threw was stopped or failed, and records which.
     *
     * @param failure     What the build threw
     * @param namespace   Whose build it was
     * @param startedAtMs When it started
     * @return The snapshot to publish
     */
    private Snapshot endOf(Throwable failure, NamespaceId namespace, long startedAtMs) {
        long finishedAtMs = System.currentTimeMillis();
        if (isCancellation(failure)) {
            LOGGER.info("graph build of namespace {} stopped before publishing; the graph it "
                + "would have replaced is still being served", namespace);
            return new Snapshot(STATE_CANCELLED, namespace, startedAtMs, finishedAtMs, null,
                null);
        }
        // Recorded, not only logged. The operator who asked for this build reads the status,
        // and a failure that reaches only the log reads there as a build still running.
        LOGGER.error("graph build of namespace {} failed", namespace, failure);
        return new Snapshot(STATE_FAILED, namespace, startedAtMs, finishedAtMs, null,
            String.valueOf(failure.getMessage()));
    }

    /**
     * Whether a failure is the build reporting that it was asked to stop.
     *
     * <p>The causes are walked because a phase that wraps what it catches would otherwise turn
     * a stop somebody asked for into a failure nobody can explain.</p>
     *
     * @param failure What the build threw
     * @return true for a cancellation
     */
    private static boolean isCancellation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BuildCancelledException) {
                return true;
            }
            if (cause.getCause() == cause) {
                // A self-referencing chain is a malformed exception, not a cancellation.
                return false;
            }
        }
        return false;
    }

    /**
     * Publishes the outcome of a build and releases the node's single build slot.
     *
     * @param snapshot What the build ended as
     */
    private void finish(Snapshot snapshot) {
        synchronized (lock) {
            remembered.put(snapshot.namespace(), snapshot);
            inFlight = null;
            switch (snapshot.state()) {
                case STATE_COMPLETED -> completedBuilds++;
                case STATE_CANCELLED -> cancelledBuilds++;
                // The only other state a build ends in: finish() is never handed a running or
                // idle snapshot.
                default -> failedBuilds++;
            }
        }
    }

    /**
     * Stops accepting builds, asks one in progress to stop, and waits for it.
     *
     * <p>Asked, then waited for, and never interrupted. A build reads the staging store through
     * the channels ingestion appends to, and opens and maps the directory it publishes for
     * queries to be served from, and an interrupt reaching any of those channel operations
     * closes the channel for every thread using it. It publishes by renaming a directory over
     * the served one, and an interrupt inside that window leaves a namespace serving nothing.
     * The caller closes the graph tenants next - unmapping the files a build writes through and
     * closing the Lucene directories it indexes into - so false is the answer it has to act on,
     * by leaving them open.</p>
     *
     * <p>How long to wait is the caller's: a node's shutdown passes what its budget leaves the
     * drains. A build reads the stop at its own boundaries, and the longest stretch between two
     * is one model request.</p>
     *
     * @param wait How long to wait for a build asked to stop to end
     * @return true if no build is still in progress, false if one outlived the wait
     * @throws NullPointerException if wait is null
     */
    public boolean drain(Duration wait) {
        Objects.requireNonNull(wait, "wait must not be null");
        synchronized (lock) {
            accepting = false;
        }
        cancelRequested.set(true);
        executor.shutdown();
        try {
            if (executor.awaitTermination(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (executor.isTerminated()) {
                return true;
            }
        }
        LOGGER.error("a graph build is still running {}ms after being asked to stop; the graph "
            + "tenants it writes into must stay open", wait.toMillis());
        return false;
    }

    /**
     * As {@link #drain(Duration)}, waiting for as long as the build takes to stop, for an owner
     * with no deadline and no use for the answer - the way {@code ExecutorService.close} waits.
     */
    @Override
    public void close() {
        drain(UNTIL_STOPPED);
    }

}
