package io.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.excalibur.graph.erasure.CompactionPolicy.CompactionStats;
import io.excalibur.graph.store.MappedEdgeStore;

import org.roaringbitmap.RoaringBitmap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns outstanding erasure requests into an actual rewrite of the edge store.
 *
 * <p>The three pieces this joins already work on their own and do nothing on their own.
 * {@link ErasureLog} remembers what is owed, {@link CompactionPolicy} knows when paying is
 * worthwhile, and {@link EdgeStoreCompactor} does the work. Without something holding them
 * together a request is accepted, recorded, and then waited on forever, because nothing in the
 * system ever asks whether a compaction is due.
 *
 * <h2>Why the requests are resolved before the policy is asked</h2>
 * <p>{@link CompactionStats} wants how many edges are tombstoned, and that is not a number the
 * store carries - a tombstone here is the outcome of matching a request against every edge. So the
 * resolve pass runs first and the policy decides whether to pay for the rewrite that would follow
 * it. Resolving is one sequential scan; the rewrite is a scan plus a full copy of every column, so
 * the cheap half is what gets spent on the decision.
 *
 * <h2>Who closes the store</h2>
 * <p>{@link EdgeStoreCompactor#compact} closes the store it is given, but only on the path that
 * rewrites anything: an empty tombstone set returns 0 before the swap and leaves the store open,
 * and an exception thrown during the rewrite leaves it open as well, because the close is the last
 * statement after the directories have swapped. So ownership is handed over at exactly one point -
 * after {@code compact} has returned normally, having been called with a non-empty set. Every other
 * path closes the store here. Getting this wrong in either direction is quiet: a leak holds
 * mappings against {@code vm.max_map_count} until the process dies, and a double close would
 * unmap memory a reader is still inside.
 *
 * <h2>Thread Safety</h2>
 * <p>{@link #submit} is safe from any thread; the log serialises it. Compaction is serialised on
 * this instance, because two rewrites of one directory would share the same temporary and retired
 * directories and delete each other's work. That lock cannot reach another coordinator or another
 * process, so a graph directory must be compacted through one coordinator only.
 *
 * @author ExcaliburDB
 */
public final class GraphErasureCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphErasureCoordinator.class);

    /**
     * Segments a mapped edge store is spread over: one.
     *
     * <p>{@link MappedEdgeStore} is a single generation of column files in a single directory -
     * there is no segmented layout behind it to count. Reported honestly rather than left at zero,
     * because {@link CompactionPolicy#standard} compares this against a maximum and a zero would
     * describe a store that is not there.
     */
    private static final int SEGMENTS_PER_STORE = 1;

    private final ErasureLog log;
    private final CompactionPolicy policy;
    private final Clock clock;
    private final StoreOpener opener;
    private final Object compactionLock = new Object();

    /**
     * Builds a coordinator over an open log.
     *
     * @param log    Where requests are recorded; not closed by this class, because the caller's
     *               lifecycle for it is usually longer than the coordinator's
     * @param policy Decides whether a due compaction is worth running
     * @param clock  Read for "now" whenever a request is timed or a policy is asked, so a test can
     *               move time without sleeping
     * @throws NullPointerException if any argument is null
     */
    public GraphErasureCoordinator(ErasureLog log, CompactionPolicy policy, Clock clock) {
        this(log, policy, clock, MappedEdgeStore::open);
    }

    /**
     * As the public constructor, with the store opener replaced.
     *
     * <p>Package-private and here only so a test can hold the store this opens. Whether the store
     * is closed on each path is the whole point of this class and is otherwise unobservable from
     * outside, since the handle never leaves the method that made it.
     *
     * @param log    Where requests are recorded
     * @param policy Decides whether a due compaction is worth running
     * @param clock  Read for "now"
     * @param opener Opens the store at a graph directory
     * @throws NullPointerException if any argument is null
     */
    GraphErasureCoordinator(ErasureLog log, CompactionPolicy policy, Clock clock,
                            StoreOpener opener) {
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.opener = Objects.requireNonNull(opener, "opener must not be null");
    }

    /** How a graph directory is turned into an open store. */
    @FunctionalInterface
    interface StoreOpener {

        /**
         * Opens the store.
         *
         * @param directory Where the column files live
         * @return The open store, which the caller closes unless it hands it to the compactor
         * @throws IOException if the store cannot be opened
         */
        MappedEdgeStore open(Path directory) throws IOException;
    }

    /**
     * Accepts an erasure request and records it durably.
     *
     * <p>Timestamped from this coordinator's clock, because the deadline the policy later measures
     * has to be against the same clock the policy is asked with.
     *
     * @param scope What to erase
     * @return The request id, for auditing what was erased and when
     * @throws IOException if the request cannot be recorded, in which case it has not been accepted
     * @throws NullPointerException if {@code scope} is null
     */
    public long submit(ErasureScope scope) throws IOException {
        Objects.requireNonNull(scope, "scope must not be null");
        return log.submit(scope, clock.instant());
    }

    /**
     * What a compaction attempt did.
     *
     * <p>{@code ran} is about the store, not about the requests: it is true only when the column
     * files were rewritten. A request that matched no edges is satisfied without a rewrite, so
     * {@code requestsSatisfied} can be non-zero while {@code ran} is false.
     *
     * @param requestsSatisfied How many outstanding requests this marked complete
     * @param edgesRemoved      How many edges the rewrite dropped
     * @param ran               Whether the store was rewritten
     * @param reason            Why, in a form fit for an operator's log
     */
    public record CompactionOutcome(boolean ran, long edgesRemoved, int requestsSatisfied,
                                    String reason) {

        /**
         * @throws IllegalArgumentException if a count is negative
         * @throws NullPointerException if {@code reason} is null
         */
        public CompactionOutcome {
            if (edgesRemoved < 0) {
                throw new IllegalArgumentException("edgesRemoved must not be negative, got "
                    + edgesRemoved);
            }
            if (requestsSatisfied < 0) {
                throw new IllegalArgumentException("requestsSatisfied must not be negative, got "
                    + requestsSatisfied);
            }
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * Compacts the store at {@code graphDirectory} if the policy says it is time.
     *
     * <p>Safe to call on a schedule and cheap when nothing is owed: with no outstanding requests
     * the resolve pass returns immediately without touching a page of adjacency, and the policy is
     * asked about a store with nothing tombstoned.
     *
     * @param graphDirectory Where the edge store's column files live
     * @return What happened, including why nothing happened
     * @throws IOException if the store cannot be opened, rewritten, or the completion recorded
     * @throws NullPointerException if {@code graphDirectory} is null
     */
    public CompactionOutcome compactIfDue(Path graphDirectory) throws IOException {
        return compact(graphDirectory, true);
    }

    /**
     * Compacts the store at {@code graphDirectory} whatever the policy would have said.
     *
     * <p>For an erasure that has to be applied now rather than when it becomes economical - an
     * operator answering a deadline that the configured policy was not written for.
     *
     * @param graphDirectory Where the edge store's column files live
     * @return What happened; still reports {@code ran} false when no edge matched any request,
     *         because there was nothing to rewrite
     * @throws IOException if the store cannot be opened, rewritten, or the completion recorded
     * @throws NullPointerException if {@code graphDirectory} is null
     */
    public CompactionOutcome compactNow(Path graphDirectory) throws IOException {
        return compact(graphDirectory, false);
    }

    private CompactionOutcome compact(Path graphDirectory, boolean consultPolicy)
            throws IOException {
        Objects.requireNonNull(graphDirectory, "graphDirectory must not be null");

        synchronized (compactionLock) {
            List<ErasureRequest> requests = log.outstanding();

            MappedEdgeStore store = opener.open(graphDirectory);
            // Ownership, not a "did we close it" flag. It stays true until the compactor has
            // taken the store off our hands, and the finally below acts on it exactly once.
            boolean ours = true;
            try {
                RoaringBitmap tombstoned = EdgeStoreCompactor.resolve(store, requests);

                if (consultPolicy) {
                    CompactionStats stats = new CompactionStats(store.edgeCount(),
                        tombstoned.getCardinality(),
                        EdgeStoreCompactor.oldestOutstanding(requests), SEGMENTS_PER_STORE);
                    Instant now = clock.instant();
                    if (!policy.shouldCompact(stats, now)) {
                        String reason = "policy declined: " + describe(stats);
                        LOGGER.debug("compaction of {} not due: {}", graphDirectory, reason);
                        return new CompactionOutcome(false, 0, 0, reason);
                    }
                }

                if (tombstoned.isEmpty()) {
                    // Not a no-op. These requests name nothing that is in the store - an entity
                    // with no edges, a source already compacted away - and leaving them
                    // outstanding would make the oldest-request condition fire on every call from
                    // here to the end of time, rewriting the store repeatedly to remove nothing.
                    log.complete(idsOf(requests));
                    return new CompactionOutcome(false, 0, requests.size(),
                        requests.isEmpty() ? "no outstanding erasure requests"
                            : requests.size() + " outstanding request(s) matched no edge; "
                                + "marked satisfied");
                }

                long removed = EdgeStoreCompactor.compact(store, graphDirectory, tombstoned);
                // Reached only when compact returned normally with a non-empty set, which is the
                // one case in which it closed the store. Set before anything that could throw,
                // and after the only call that could have left it open.
                ours = false;

                // Completed after the rewrite, never before. A crash in this gap repeats an
                // erasure that has already removed everything it names, which costs a scan; the
                // other order would record the obligation as discharged with the data still there.
                log.complete(idsOf(requests));

                LOGGER.info("compacted {}: {} edges removed for {} erasure request(s)",
                    graphDirectory, removed, requests.size());
                return new CompactionOutcome(true, removed, requests.size(),
                    "compacted: " + removed + " edge(s) removed for " + requests.size()
                        + " request(s)");
            } finally {
                if (ours) {
                    store.close();
                }
            }
        }
    }

    private static List<Long> idsOf(List<ErasureRequest> requests) {
        List<Long> ids = new ArrayList<>(requests.size());
        for (ErasureRequest request : requests) {
            ids.add(request.id());
        }
        return ids;
    }

    private static String describe(CompactionStats stats) {
        return stats.tombstonedEdges() + " of " + stats.totalEdges() + " edge(s) tombstoned, "
            + stats.segmentCount() + " segment(s), oldest request "
            + stats.oldestOutstanding().map(Instant::toString).orElse("none");
    }

    /**
     * How many obligations are recorded and not yet discharged.
     *
     * <p>What an operator watches to know whether erasure is keeping up: a count that only grows
     * means the policy is never firing, and the obligations are accumulating rather than being
     * met.</p>
     *
     * @return The count
     */
    public int outstandingCount() {
        return log.outstandingCount();
    }

    @Override
    public String toString() {
        return "GraphErasureCoordinator[" + log.outstandingCount() + " outstanding]";
    }
}
