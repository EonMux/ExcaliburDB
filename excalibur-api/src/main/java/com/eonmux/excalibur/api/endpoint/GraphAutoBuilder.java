package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.bootstrap.GraphBuildService;
import com.eonmux.excalibur.api.bootstrap.GraphExtraction;
import com.eonmux.excalibur.api.bootstrap.GraphFreshness;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.config.GraphConfig;
import com.eonmux.excalibur.graph.build.BuildCancelledException;
import com.eonmux.excalibur.graph.staging.PublishRefusedException;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Rebuilds a namespace's graph by itself once the graph has fallen behind what it has staged.
 *
 * <h2>Why builds happen without being asked for</h2>
 * <p>Ingestion stages every change - a new document's facts, and the withdrawal of a replaced or
 * removed one's - but {@code /v1/graph/query} answers from the graph the last build published.
 * With builds only on request, a crawl that replaced a document left the graph answering with
 * what the document used to say until an operator thought to build, while {@code /v1/query}
 * reflected the change at once. This closes that gap: the staging store records which changes no
 * published graph has, and a periodic pass rebuilds each namespace that is behind.</p>
 *
 * <h2>Why a rebuild, and not a withdrawal applied to the served graph</h2>
 * <p>A withdrawal could be applied to the served graph the way an erasure compaction applies an
 * erasure. It would still rebuild the two Lucene indexes and the entity vectors from staging,
 * which is most of what a build costs; it would bring a replaced document's new facts no closer,
 * because a compaction only removes; and it would be a second path to the served graph to keep
 * serialised with the first. A build publishes removals and additions in one move through the
 * code path explicit builds already take. What a withdrawal waits for instead is bounded: the
 * debounce, at most {@code excalibur.graph.auto-build-max-delay-seconds}, plus the build.</p>
 *
 * <h2>When a namespace is rebuilt</h2>
 * <p>Every {@code excalibur.graph.auto-build-interval-seconds} each namespace on disk is asked
 * whether it is behind - from memory when it is resident, from a few file stats when it is not.
 * One that is behind is rebuilt once its staging has been quiet for
 * {@code excalibur.graph.auto-build-debounce-seconds}, so a crawl is published by one build
 * rather than one per document, or once its oldest unpublished change has waited
 * {@code excalibur.graph.auto-build-max-delay-seconds}, so ingestion that never pauses cannot
 * hold the graph back indefinitely. Oldest change first.</p>
 *
 * <h2>What it will not do</h2>
 * <ul>
 *   <li>Build a namespace twice at once, or one an explicit build is running for. The build
 *       itself takes the namespace's replacement lock, which serialises it with explicit builds,
 *       erasure compactions and the erasure sweep, and asks again under that lock whether the
 *       graph is still behind - an explicit build may have published everything since.</li>
 *   <li>Run more than {@code excalibur.graph.auto-build-max-concurrent} builds at once across
 *       the node, since each saturates the disk and the embedding endpoint.</li>
 *   <li>Retry a namespace whose build failed before its backoff has passed: from
 *       {@code excalibur.graph.auto-build-failure-backoff-seconds}, doubling with each further
 *       failure up to {@code excalibur.graph.auto-build-max-failure-backoff-seconds}.</li>
 *   <li>Count a build an erasure overtook as a failure. An obligation recorded while a build
 *       runs refuses its publish, because the build read staging before the obligation existed;
 *       nothing is wrong, and the next build leaves the new obligation out. So that build is
 *       {@code superseded} and is due again on the next pass - still once staging has been
 *       quiet for the debounce, or its oldest change has waited the maximum delay. A namespace
 *       erased faster than it can be built would otherwise be rebuilt on every pass, each build
 *       spending a whole graph's embeddings to be refused, so from the second refusal in a row
 *       the next attempt waits: the debounce (or the pass interval, with no debounce), doubling
 *       with each further refusal, up to the maximum delay - the longest the configuration says
 *       an unpublished change should wait.</li>
 *   <li>Build while extraction is unavailable - asked for, but with no API key or no pipeline
 *       to feed it. A replaced document's old facts are withdrawn whether or not its new ones
 *       could be extracted, so a build then would publish a graph that has lost facts nothing
 *       will replace. The status says it is paused and why.</li>
 *   <li>Build a graph withheld until an interrupted erasure is applied; the erasure sweep
 *       applies it first.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Passes run on one scheduler thread; builds on a pool of
 * {@code max-concurrent} threads; the per-namespace memory is a concurrent map of immutable
 * records.</p>
 *
 * @see GraphBuildRunner
 * @author ExcaliburDB
 */
public final class GraphAutoBuilder implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphAutoBuilder.class);

    /** What {@link #close()} waits for: the builds in progress, for as long as they take. */
    private static final Duration UNTIL_STOPPED = Duration.ofNanos(Long.MAX_VALUE);

    /** Automatic builds are turned off on this node. */
    public static final String STATE_DISABLED = "disabled";

    /** Extraction is unavailable, so nothing is built automatically until it is. */
    public static final String STATE_PAUSED = "paused";

    /** The served graph has everything staging holds. */
    public static final String STATE_CURRENT = "current";

    /** The graph is behind, and will be rebuilt once the debounce or the maximum delay passes. */
    public static final String STATE_PENDING = "pending";

    /** An automatic build of this namespace is in progress. */
    public static final String STATE_RUNNING = "running";

    /**
     * The last automatic build failed, or several in a row were superseded, and the next waits
     * out its backoff.
     */
    public static final String STATE_BACKING_OFF = "backing_off";

    /** The graph is withheld until an interrupted erasure is applied, which comes first. */
    public static final String STATE_WITHHELD = "withheld";

    /** A build that published, or retired a graph whose facts were all withdrawn. */
    public static final String RESULT_COMPLETED = "completed";

    /** A build that found the graph already current once it held the lock. */
    public static final String RESULT_UNCHANGED = "unchanged";

    /** A build that raised an error and published nothing. */
    public static final String RESULT_FAILED = "failed";

    /** A build stopped by the node shutting down. */
    public static final String RESULT_CANCELLED = "cancelled";

    /**
     * A build whose publish was refused because an erasure was recorded while it ran; the next
     * one leaves that erasure out.
     */
    public static final String RESULT_SUPERSEDED = "superseded";

    /**
     * How the automatic build is configured.
     *
     * @param enabled           Whether it runs at all
     * @param interval          How often namespaces are looked at
     * @param debounce          How long staging must be quiet before a rebuild
     * @param maxDelay          Longest an unpublished change waits, however busy staging is
     * @param maxConcurrent     Most automatic builds at once, across the node
     * @param failureBackoff    First wait after a failed build
     * @param maxFailureBackoff Longest the wait grows to
     */
    public record Settings(boolean enabled, Duration interval, Duration debounce,
                           Duration maxDelay, int maxConcurrent, Duration failureBackoff,
                           Duration maxFailureBackoff) {

        /**
         * Validates the settings.
         *
         * @throws NullPointerException     if a duration is null
         * @throws IllegalArgumentException if a bound cannot schedule anything
         */
        public Settings {
            Objects.requireNonNull(interval, "interval must not be null");
            Objects.requireNonNull(debounce, "debounce must not be null");
            Objects.requireNonNull(maxDelay, "maxDelay must not be null");
            Objects.requireNonNull(failureBackoff, "failureBackoff must not be null");
            Objects.requireNonNull(maxFailureBackoff, "maxFailureBackoff must not be null");
            if (interval.isNegative() || interval.isZero() || maxConcurrent < 1
                    || debounce.isNegative() || failureBackoff.isNegative()
                    || maxFailureBackoff.compareTo(failureBackoff) < 0) {
                throw new IllegalArgumentException("automatic graph build settings cannot "
                    + "schedule anything: " + this);
            }
        }

        /**
         * Reads the settings from the graph section.
         *
         * @param graph The graph configuration
         * @return The settings
         */
        public static Settings from(GraphConfig graph) {
            Objects.requireNonNull(graph, "graph must not be null");
            return new Settings(graph.isAutoBuildEnabled(),
                Duration.ofSeconds(graph.getAutoBuildIntervalSeconds()),
                Duration.ofSeconds(graph.getAutoBuildDebounceSeconds()),
                Duration.ofSeconds(graph.getAutoBuildMaxDelaySeconds()),
                graph.getAutoBuildMaxConcurrent(),
                Duration.ofSeconds(graph.getAutoBuildFailureBackoffSeconds()),
                Duration.ofSeconds(graph.getAutoBuildMaxFailureBackoffSeconds()));
        }
    }

    /**
     * The namespaces automatic builds look at, narrower than the tenancy registry so the
     * scheduling can be tested without graphs on disk. {@link #over(GraphTenants)} is the real
     * one.
     */
    public interface Namespaces {

        /**
         * Every namespace on the node.
         *
         * @return Their ids
         * @throws IOException if they cannot be listed
         */
        List<NamespaceId> list() throws IOException;

        /**
         * Whether a namespace's served graph is behind its staging.
         *
         * @param id The namespace
         * @return Its freshness
         * @throws IOException if it cannot be read
         */
        GraphFreshness freshness(NamespaceId id) throws IOException;

        /**
         * Builds a namespace's graph if it is still behind once its build lock is held.
         *
         * @param id              The namespace
         * @param cancelRequested Read at the build's boundaries
         * @return What was built, or empty when there was nothing to build
         * @throws IOException if the build fails
         */
        Optional<GraphBuildService.BuildOutcome> buildIfBehind(NamespaceId id,
                                                               BooleanSupplier cancelRequested)
            throws IOException;
    }

    /**
     * The real namespaces: every one on disk.
     *
     * @param tenants The node's graph tenants
     * @return The namespaces
     */
    public static Namespaces over(GraphTenants tenants) {
        Objects.requireNonNull(tenants, "tenants must not be null");
        return new Namespaces() {
            @Override
            public List<NamespaceId> list() throws IOException {
                return tenants.namespacesOnDisk();
            }

            @Override
            public GraphFreshness freshness(NamespaceId id) throws IOException {
                return tenants.freshness(id);
            }

            @Override
            public Optional<GraphBuildService.BuildOutcome> buildIfBehind(
                    NamespaceId id, BooleanSupplier cancelRequested) throws IOException {
                return tenants.buildIfBehind(id, cancelRequested);
            }
        };
    }

    /**
     * What the automatic build remembers about one namespace.
     *
     * @param running             Whether a build of it is in progress
     * @param lastStartedAtMs     When the last build started, or 0
     * @param lastFinishedAtMs    When it finished, or 0 while running or before the first
     * @param lastResult          One of the {@code RESULT_*} constants, or null before the first
     * @param lastError           Why the last build failed, or null
     * @param lastOutcome         What the last build that ran published, or null
     * @param consecutiveFailures      Failures since the last build that did not fail
     * @param consecutiveSupersessions Builds in a row an erasure overtook
     * @param retryAtMs                When a namespace backing off may be built again, or 0
     */
    public record Memory(boolean running, long lastStartedAtMs, long lastFinishedAtMs,
                         String lastResult, String lastError,
                         GraphBuildService.BuildOutcome lastOutcome, int consecutiveFailures,
                         int consecutiveSupersessions, long retryAtMs) {

        /** A namespace nothing has been built for. */
        static final Memory NONE = new Memory(false, 0, 0, null, null, null, 0, 0, 0);
    }

    /**
     * What the automatic build has to say about one namespace.
     *
     * @param state          One of the {@code STATE_*} constants
     * @param enabled        Whether automatic builds are on for this node
     * @param pausedBy       Why automatic builds are paused, when extraction is unavailable
     * @param freshness      Whether the served graph is behind its staging, or null when that
     *                       could not be read
     * @param freshnessError Why it could not be read, or null
     * @param memory         What was last done for the namespace
     * @param settings       The configuration, for the debounce and delay the state is read
     *                       against
     */
    public record Status(String state, boolean enabled, Optional<GraphExtraction> pausedBy,
                         GraphFreshness freshness, String freshnessError, Memory memory,
                         Settings settings) {
    }

    /**
     * Automatic builds on this node since startup, across namespaces.
     *
     * @param running   How many are in progress now
     * @param completed How many published or retired a graph
     * @param unchanged How many found nothing left to build
     * @param failed     How many failed
     * @param cancelled  How many were stopped by the node shutting down
     * @param superseded How many had their publish refused by an erasure recorded meanwhile
     */
    public record Counts(int running, long completed, long unchanged, long failed,
                         long cancelled, long superseded) {
    }

    private final Settings settings;
    private final Namespaces namespaces;
    private final Supplier<Optional<GraphExtraction>> extraction;
    private final Predicate<NamespaceId> explicitBuildRunning;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService builds;
    private final ConcurrentHashMap<NamespaceId, Memory> remembered = new ConcurrentHashMap<>();
    private final Set<NamespaceId> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Read by every automatic build at its boundaries; set only when the node shuts down.
     *
     * <p>Never an interrupt, for the reason {@link GraphBuildRunner} gives: a build publishes
     * by renaming a directory over the served one, and an interrupt inside that window leaves a
     * namespace serving nothing.</p>
     */
    private final AtomicBoolean cancelRequested = new AtomicBoolean();

    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean closing;

    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong unchanged = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private final AtomicLong superseded = new AtomicLong();

    /**
     * Creates the automatic build. Nothing runs until {@link #start()}.
     *
     * @param settings             How it is configured
     * @param namespaces           The namespaces it looks at and builds
     * @param extraction           Whether ingestion extracts facts, read on every pass because
     *                             ingestion is wired after the routes are
     * @param explicitBuildRunning Whether an explicit build of a namespace is in progress
     * @param clock                What debounce, delay and backoff are measured by
     * @throws NullPointerException if any argument is null
     */
    public GraphAutoBuilder(Settings settings, Namespaces namespaces,
                            Supplier<Optional<GraphExtraction>> extraction,
                            Predicate<NamespaceId> explicitBuildRunning, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces must not be null");
        this.extraction = Objects.requireNonNull(extraction, "extraction must not be null");
        this.explicitBuildRunning = Objects.requireNonNull(explicitBuildRunning,
            "explicitBuildRunning must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            daemon("graph-auto-build-scheduler"));
        AtomicInteger threads = new AtomicInteger();
        this.builds = Executors.newFixedThreadPool(settings.maxConcurrent(),
            task -> daemon("graph-auto-build-" + threads.incrementAndGet()).newThread(task));
    }

    /**
     * Creates and starts the automatic build over a node's graph tenants.
     *
     * @param tenants The node's graph tenants
     * @param runner  The runner explicit builds go through, so a namespace it is building is
     *                left to it
     * @param graph   The graph configuration
     * @return The automatic build, running when the configuration enables it
     */
    public static GraphAutoBuilder start(GraphTenants tenants, GraphBuildRunner runner,
                                         GraphConfig graph) {
        Objects.requireNonNull(runner, "runner must not be null");
        GraphAutoBuilder builder = new GraphAutoBuilder(Settings.from(graph), over(tenants),
            runner::extraction, namespace -> runner.snapshot(namespace).isRunning(),
            Clock.systemUTC());
        builder.start();
        return builder;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Schedules the periodic pass, when automatic builds are enabled. Idempotent.
     */
    public void start() {
        if (!settings.enabled()) {
            LOGGER.info("automatic graph builds are disabled "
                + "(excalibur.graph.auto-build-enabled=false); a graph changes only when "
                + "POST /v1/graph/build asks for it");
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long intervalMs = settings.interval().toMillis();
        scheduler.scheduleWithFixedDelay(this::passQuietly, intervalMs, intervalMs,
            TimeUnit.MILLISECONDS);
        LOGGER.info("automatic graph builds every {}s once staging has been quiet for {}s "
            + "(at most {}s after a change), {} at a time", settings.interval().toSeconds(),
            settings.debounce().toSeconds(), settings.maxDelay().toSeconds(),
            settings.maxConcurrent());
    }

    /**
     * Runs a pass, swallowing whatever it throws, since a scheduled task that throws is never
     * run again.
     */
    private void passQuietly() {
        try {
            runPass();
        } catch (RuntimeException e) {
            LOGGER.error("automatic graph build pass failed; the next one will try again", e);
        }
    }

    /**
     * Looks at every namespace once and starts the builds that are due.
     *
     * @return The namespaces whose builds this pass started
     */
    public List<NamespaceId> runPass() {
        if (!settings.enabled() || closing) {
            return List.of();
        }
        Optional<GraphExtraction> paused = pausedBy();
        if (paused.isPresent()) {
            LOGGER.debug("automatic graph builds paused: extraction is {}",
                paused.get().wireName());
            return List.of();
        }
        List<NamespaceId> onDisk;
        try {
            onDisk = namespaces.list();
        } catch (IOException | RuntimeException e) {
            LOGGER.error("could not list the graph namespaces for automatic builds: {}",
                e.toString());
            return List.of();
        }
        forgetDropped(onDisk);

        Instant now = clock.instant();
        List<Due> due = new ArrayList<>();
        for (NamespaceId id : onDisk) {
            if (closing) {
                return List.of();
            }
            dueAt(id, now).ifPresent(due::add);
        }
        due.sort(Comparator.comparing(Due::oldestChange));

        List<NamespaceId> startedNow = new ArrayList<>();
        for (Due candidate : due) {
            if (inFlight.size() >= settings.maxConcurrent()) {
                LOGGER.debug("{} namespace(s) behind their staging wait for a free automatic "
                    + "build slot", due.size() - startedNow.size());
                break;
            }
            if (submit(candidate.id())) {
                startedNow.add(candidate.id());
            }
        }
        return List.copyOf(startedNow);
    }

    /**
     * A namespace due for a build, and how long its oldest unpublished change has waited.
     *
     * @param id           The namespace
     * @param oldestChange When its oldest unpublished change was made; unknown sorts first
     */
    private record Due(NamespaceId id, Instant oldestChange) {
    }

    /**
     * Whether a namespace is to be built now.
     *
     * @param id  The namespace
     * @param now The pass's clock reading
     * @return The namespace, when it is due
     */
    private Optional<Due> dueAt(NamespaceId id, Instant now) {
        // The memory before the slot: a build frees its slot as it records how it ended, so a
        // memory read first that still says running is never paired with a free slot and a
        // backoff not yet recorded.
        Memory memory = remembered.getOrDefault(id, Memory.NONE);
        if (memory.running() || inFlight.contains(id) || explicitBuildRunning.test(id)) {
            return Optional.empty();
        }
        if (memory.retryAtMs() > now.toEpochMilli()) {
            return Optional.empty();
        }
        GraphFreshness freshness;
        try {
            freshness = namespaces.freshness(id);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not tell whether namespace {}'s graph is behind its staging; "
                + "it is looked at again on the next pass: {}", id, e.toString());
            return Optional.empty();
        }
        if (!freshness.behind() || freshness.withheld()) {
            return Optional.empty();
        }
        boolean quiet = freshness.lastChangedAt()
            .map(last -> !now.isBefore(last.plus(settings.debounce())))
            .orElse(true);
        boolean overdue = freshness.pendingSince()
            .map(since -> !now.isBefore(since.plus(settings.maxDelay())))
            .orElse(true);
        if (!quiet && !overdue) {
            return Optional.empty();
        }
        return Optional.of(new Due(id, freshness.pendingSince().orElse(Instant.MIN)));
    }

    /**
     * Starts one namespace's build on the pool.
     *
     * @param id The namespace
     * @return true if it was started
     */
    private boolean submit(NamespaceId id) {
        if (!inFlight.add(id)) {
            return false;
        }
        long startedAtMs = clock.millis();
        remembered.compute(id, (key, memory) -> {
            Memory last = memory == null ? Memory.NONE : memory;
            return new Memory(true, startedAtMs, 0, last.lastResult(), last.lastError(),
                last.lastOutcome(), last.consecutiveFailures(), last.consecutiveSupersessions(),
                0);
        });
        try {
            builds.execute(() -> build(id, startedAtMs));
            return true;
        } catch (RejectedExecutionException e) {
            // The node is shutting down between the pass's check and the submit.
            inFlight.remove(id);
            remembered.computeIfPresent(id, (key, memory) -> new Memory(false,
                memory.lastStartedAtMs(), clock.millis(), RESULT_CANCELLED, null,
                memory.lastOutcome(), memory.consecutiveFailures(),
                memory.consecutiveSupersessions(), 0));
            return false;
        }
    }

    /**
     * Builds one namespace and records how it ended.
     *
     * @param id          The namespace
     * @param startedAtMs When the pass started it
     */
    private void build(NamespaceId id, long startedAtMs) {
        try {
            LOGGER.info("automatic graph build of namespace {} started: its graph is behind "
                + "its staging", id);
            Optional<GraphBuildService.BuildOutcome> outcome =
                namespaces.buildIfBehind(id, cancelRequested::get);
            if (outcome.isPresent()) {
                completed.incrementAndGet();
                finish(id, startedAtMs, RESULT_COMPLETED, null, outcome.get(), 0, 0, 0);
                LOGGER.info("automatic graph build of namespace {} finished: {}", id,
                    outcome.get());
            } else {
                unchanged.incrementAndGet();
                finish(id, startedAtMs, RESULT_UNCHANGED, null, null, 0, 0, 0);
                LOGGER.info("automatic graph build of namespace {} found nothing to build",
                    id);
            }
        } catch (IOException | RuntimeException | Error e) {
            if (isCancellation(e)) {
                cancelled.incrementAndGet();
                finish(id, startedAtMs, RESULT_CANCELLED, null, null, 0, 0, 0);
                LOGGER.info("automatic graph build of namespace {} stopped before publishing",
                    id);
                return;
            }
            Optional<PublishRefusedException> refusal = refusalIn(e);
            if (refusal.isPresent()) {
                superseded(id, startedAtMs, refusal.get());
                return;
            }
            failed.incrementAndGet();
            int failures = remembered.getOrDefault(id, Memory.NONE).consecutiveFailures() + 1;
            Duration wait = backoff(failures);
            long retryAtMs = clock.millis() + wait.toMillis();
            finish(id, startedAtMs, RESULT_FAILED, String.valueOf(e.getMessage()), null,
                failures, 0, retryAtMs);
            LOGGER.error("automatic graph build of namespace {} failed ({} in a row); the "
                + "next attempt waits {}s", id, failures, wait.toSeconds(), e);
        } finally {
            // The slot was freed by finish(), with the memory: freeing it again here could free
            // one a later pass has since taken for the next build of this namespace.
            // One pool thread runs many builds; a flag left set by anything would kill the
            // next build at its first channel operation.
            Thread.interrupted();
        }
    }

    /**
     * Records a build whose publish an erasure refused, and when the next may start.
     *
     * <p>Not a failure, so the failure streak ends here - the build got as far as its publish -
     * and no backoff is owed for it. The first refusal in a row leaves the namespace due on
     * the next pass; later ones space the attempts out by {@link #supersededWait(int)}.</p>
     *
     * @param id          The namespace
     * @param startedAtMs When the pass started the build
     * @param refusal     Which obligation overtook it
     */
    private void superseded(NamespaceId id, long startedAtMs, PublishRefusedException refusal) {
        superseded.incrementAndGet();
        int inARow = remembered.getOrDefault(id, Memory.NONE).consecutiveSupersessions() + 1;
        Duration wait = supersededWait(inARow);
        long retryAtMs = wait.isZero() ? 0 : clock.millis() + wait.toMillis();
        finish(id, startedAtMs, RESULT_SUPERSEDED, null, null, 0, inARow, retryAtMs);
        if (wait.isZero()) {
            LOGGER.info("automatic graph build of namespace {} was superseded: {}; it is built "
                + "again, without that erasure, once it is next due", id, refusal.getMessage());
        } else {
            LOGGER.warn("automatic graph build of namespace {} was superseded {} times in a row, "
                + "most recently: {}; the next attempt waits {}s", id, inARow,
                refusal.getMessage(), wait.toSeconds());
        }
    }

    /**
     * How long a namespace waits after its {@code inARow}-th consecutive superseded build.
     *
     * @param inARow Consecutive superseded builds, at least 1
     * @return Nothing after the first; then the debounce, or the pass interval where there is
     *         no debounce, doubled for each further refusal, up to the maximum delay
     */
    Duration supersededWait(int inARow) {
        if (inARow <= 1) {
            return Duration.ZERO;
        }
        Duration first = settings.debounce().isZero() ? settings.interval() : settings.debounce();
        Duration ceiling = settings.maxDelay().compareTo(first) < 0 ? first : settings.maxDelay();
        Duration wait = first;
        for (int i = 2; i < inARow && wait.compareTo(ceiling) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(ceiling) > 0 ? ceiling : wait;
    }

    /**
     * Records how a build ended, and frees its slot in the same step.
     *
     * <p>Together, so nothing sees the build finished and its namespace still in flight - a pass
     * would skip it - or free and its backoff not yet recorded - a pass would retry at once.</p>
     */
    private void finish(NamespaceId id, long startedAtMs, String result, String error,
                        GraphBuildService.BuildOutcome outcome, int failures,
                        int supersessions, long retryAtMs) {
        long finishedAtMs = clock.millis();
        remembered.compute(id, (key, memory) -> {
            GraphBuildService.BuildOutcome kept = outcome != null ? outcome
                : memory == null ? null : memory.lastOutcome();
            inFlight.remove(id);
            return new Memory(false, startedAtMs, finishedAtMs, result, error, kept, failures,
                supersessions, retryAtMs);
        });
    }

    /**
     * How long a namespace waits after its {@code failures}-th consecutive failure.
     *
     * @param failures Consecutive failures, at least 1
     * @return The first wait, doubled for each further failure, up to the ceiling
     */
    Duration backoff(int failures) {
        Duration wait = settings.failureBackoff();
        for (int i = 1; i < failures && wait.compareTo(settings.maxFailureBackoff()) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(settings.maxFailureBackoff()) > 0
            ? settings.maxFailureBackoff() : wait;
    }

    /**
     * Whether a failure is a build reporting that it was asked to stop.
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
                return false;
            }
        }
        return false;
    }

    /**
     * The refusal a failure reports, if it is one: an erasure recorded while the build ran.
     *
     * @param failure What the build threw
     * @return The refusal, wherever in the cause chain it is
     */
    private static Optional<PublishRefusedException> refusalIn(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PublishRefusedException refusal) {
                return Optional.of(refusal);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * Drops what is remembered about namespaces no longer on disk.
     *
     * @param onDisk The namespaces that exist
     */
    private void forgetDropped(List<NamespaceId> onDisk) {
        Set<NamespaceId> present = new HashSet<>(onDisk);
        remembered.keySet().removeIf(id -> !present.contains(id) && !inFlight.contains(id));
    }

    /**
     * Why automatic builds are paused, if they are.
     *
     * @return The extraction state that pauses them, or empty
     */
    private Optional<GraphExtraction> pausedBy() {
        return extraction.get().filter(state -> state.warning().isPresent());
    }

    /**
     * What the automatic build has to say about one namespace.
     *
     * @param id The namespace
     * @return Its status
     */
    public Status status(NamespaceId id) {
        Objects.requireNonNull(id, "id must not be null");
        GraphFreshness freshness = null;
        String freshnessError = null;
        try {
            freshness = namespaces.freshness(id);
        } catch (IOException | RuntimeException e) {
            freshnessError = String.valueOf(e.getMessage());
        }
        Memory memory = remembered.getOrDefault(id, Memory.NONE);
        Optional<GraphExtraction> paused = pausedBy();
        return new Status(stateOf(freshness, memory, paused), settings.enabled(), paused,
            freshness, freshnessError, memory, settings);
    }

    /**
     * Names the state a namespace is in.
     */
    private String stateOf(GraphFreshness freshness, Memory memory,
                           Optional<GraphExtraction> paused) {
        if (memory.running()) {
            return STATE_RUNNING;
        }
        if (freshness == null || !freshness.behind()) {
            return settings.enabled() ? STATE_CURRENT : STATE_DISABLED;
        }
        if (!settings.enabled()) {
            return STATE_DISABLED;
        }
        if (paused.isPresent()) {
            return STATE_PAUSED;
        }
        if (freshness.withheld()) {
            return STATE_WITHHELD;
        }
        if (memory.retryAtMs() > clock.millis()) {
            return STATE_BACKING_OFF;
        }
        return STATE_PENDING;
    }

    /**
     * Whether automatic builds are enabled on this node.
     *
     * @return true when they run
     */
    public boolean enabled() {
        return settings.enabled();
    }

    /**
     * Automatic builds since startup.
     *
     * @return The counts
     */
    public Counts counts() {
        return new Counts(inFlight.size(), completed.get(), unchanged.get(), failed.get(),
            cancelled.get(), superseded.get());
    }

    /**
     * Stops looking for work, asks the builds in progress to stop, and waits for them.
     *
     * <p>For the same reason as {@link GraphBuildRunner#drain}: the graph tenants are closed
     * next, and a build still writing into one must keep it open, so false is the answer the
     * caller acts on.</p>
     *
     * @param wait How long to wait for the builds in progress
     * @return true if nothing is still running
     * @throws NullPointerException if wait is null
     */
    public boolean drain(Duration wait) {
        Objects.requireNonNull(wait, "wait must not be null");
        closing = true;
        cancelRequested.set(true);
        scheduler.shutdown();
        builds.shutdown();
        long budget = wait.toNanos();
        long start = System.nanoTime();
        try {
            if (scheduler.awaitTermination(budget, TimeUnit.NANOSECONDS)
                && builds.awaitTermination(Math.max(0, budget - (System.nanoTime() - start)),
                    TimeUnit.NANOSECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (scheduler.isTerminated() && builds.isTerminated()) {
                return true;
            }
        }
        LOGGER.error("an automatic graph build is still running {}ms after being asked to "
            + "stop; the graph tenants it writes into must stay open", wait.toMillis());
        return false;
    }

    /**
     * As {@link #drain(Duration)}, waiting for as long as the builds take to stop.
     */
    @Override
    public void close() {
        drain(UNTIL_STOPPED);
    }
}
