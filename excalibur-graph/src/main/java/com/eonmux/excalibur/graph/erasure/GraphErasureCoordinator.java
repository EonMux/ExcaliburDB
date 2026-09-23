package com.eonmux.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.eonmux.excalibur.graph.erasure.CompactionPolicy.CompactionStats;
import com.eonmux.excalibur.graph.erasure.EdgeStoreCompactor.CompactionResult;
import com.eonmux.excalibur.graph.file.GraphCorruptionException;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.ReferencedLabels;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;

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
 * rewrites anything: a compaction with nothing to do returns before the swap and leaves the store
 * open, and an exception thrown during the rewrite leaves it open as well, because the close is
 * the last statement after the directories have swapped. So ownership is handed over at exactly
 * one point - when the result says the directory was rewritten. Every other path closes the store
 * here. Getting this wrong in either direction is quiet: a leak holds mappings against
 * {@code vm.max_map_count} until the process dies, and a double close would unmap memory a reader
 * is still inside.
 *
 * <p>"Nothing to do" is the compaction's judgement rather than an empty tombstone set. An entity
 * with no edges still has a name, a document and a vector in the served directory, and those are
 * what an erasure was about; see {@link EdgeStoreCompactor.CompactionResult#rewritten()}.
 *
 * <h2>Thread Safety</h2>
 * <p>{@link #submit} is safe from any thread; the log serialises it. Compaction is serialised on
 * this instance, because two rewrites of one directory would share the same scratch and displaced
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
    private final GraphStagingStore staging;
    private final List<ServedArtifactEraser> erasers;
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
        this(log, policy, clock, MappedEdgeStore::open, null);
    }

    /**
     * As above, and also erasing the staged facts the served graph was built from.
     *
     * <p>Without the staging store this coordinator removes an erased edge from the graph and
     * leaves the fact it was built from in the staging log, so the next build puts it straight
     * back - and the fact sits on disk in the meantime, which for an erasure obligation is the
     * point rather than a detail.</p>
     *
     * @param log     Where requests are recorded
     * @param policy  Decides whether a due compaction is worth running
     * @param clock   Read for "now"
     * @param staging The staged facts to erase alongside the store, or null for a coordinator
     *                with no staging behind it
     */
    public GraphErasureCoordinator(ErasureLog log, CompactionPolicy policy, Clock clock,
                                   GraphStagingStore staging) {
        this(log, policy, clock, MappedEdgeStore::open, staging);
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
        this(log, policy, clock, opener, null);
    }

    /**
     * As above, and also erasing the artifacts the graph is served beside.
     *
     * <p>The entity dictionary and the entity vectors are erased by the compaction whether or not
     * anything is passed here, because this module owns those formats. What has to be passed is
     * an eraser for every artifact it does not - above all the two Lucene indexes, which hold the
     * erased person's name and the prose their snippets were copied out of, and which
     * {@code excalibur-retrieval} writes. Without one, the compaction reports those indexes as
     * unreached and this coordinator leaves the request outstanding rather than calling it done;
     * see {@link EdgeStoreCompactor.CompactionResult#unreached()}.
     *
     * @param log     Where requests are recorded
     * @param policy  Decides whether a due compaction is worth running
     * @param clock   Read for "now"
     * @param staging The staged facts to erase alongside the store, or null
     * @param erasers One per served artifact this module cannot read, or null for none
     */
    public GraphErasureCoordinator(ErasureLog log, CompactionPolicy policy, Clock clock,
                                   GraphStagingStore staging,
                                   Collection<ServedArtifactEraser> erasers) {
        this(log, policy, clock, MappedEdgeStore::open, staging, erasers);
    }

    GraphErasureCoordinator(ErasureLog log, CompactionPolicy policy, Clock clock,
                            StoreOpener opener, GraphStagingStore staging) {
        this(log, policy, clock, opener, staging, null);
    }

    GraphErasureCoordinator(ErasureLog log, CompactionPolicy policy, Clock clock,
                            StoreOpener opener, GraphStagingStore staging,
                            Collection<ServedArtifactEraser> erasers) {
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.opener = Objects.requireNonNull(opener, "opener must not be null");
        this.staging = staging;
        this.erasers = erasers == null ? List.of() : List.copyOf(erasers);
    }

    /**
     * Removes the staged facts, snippets and labels an erasure covers.
     *
     * <p>Run before the requests are marked discharged and on every path that discharges them,
     * including the one where nothing in the served store matched: a fact staged but never
     * built is in no store to be found, and is exactly the fact a build would publish next.
     *
     * @param requests The outstanding requests
     * @return What was removed, and what could not be judged
     * @throws IOException if the staging logs cannot be rewritten
     */
    private GraphStagingStore.EraseResult eraseStaged(Collection<ErasureRequest> requests)
            throws IOException {
        if (staging == null || requests.isEmpty()) {
            return NOTHING_STAGED;
        }
        List<ErasureScope> scopes = new ArrayList<>(requests.size());
        for (ErasureRequest request : requests) {
            scopes.add(request.scope());
        }
        GraphStagingStore.EraseResult result = staging.erase(ScopedStagedErasure.of(scopes));
        if (result.removed() > 0) {
            LOGGER.info("erased {} staged record(s) so the next build cannot republish them: "
                + "{} fact(s), {} snippet(s), {} label(s)", result.removed(),
                result.factsRemoved(), result.snippetsRemoved(), result.labelsTombstoned());
        }
        return result;
    }

    /** What a coordinator with no staging behind it removed from staging. */
    private static final GraphStagingStore.EraseResult NOTHING_STAGED =
        new GraphStagingStore.EraseResult(0, 0, 0, 0);

    /**
     * The requests this pass may honestly mark discharged.
     *
     * <p>A scope that selects by provenance can only be answered against records that carry
     * some. Snippets recorded before origins were kept carry none, and there is no third option
     * that is not a lie: removing them would destroy prose nobody asked to erase, and completing
     * the request would report an erasure that had not happened. So the request stays
     * outstanding and says why.
     *
     * @param requests The batch
     * @param staged   What the staging pass could and could not reach
     * @return The subset that has actually been satisfied
     */
    private static List<ErasureRequest> satisfiedBy(List<ErasureRequest> requests,
                                                    GraphStagingStore.EraseResult staged,
                                                    CompactionResult compaction) {
        boolean originsMissing = staged.snippetsOfUnknownOrigin() > 0;
        if (!originsMissing && compaction.complete()) {
            return requests;
        }
        List<ErasureRequest> satisfied = new ArrayList<>(requests.size());
        for (ErasureRequest request : requests) {
            boolean reachedOnDisk = !compaction.unreached().containsKey(request.id());
            boolean matchable = !originsMissing || request.scope() instanceof ErasureScope.Entity;
            if (reachedOnDisk && matchable) {
                satisfied.add(request);
            }
        }
        return satisfied;
    }

    /**
     * Records the completions this pass has earned, and reports the ones it has not.
     *
     * @param requests The batch
     * @param staged   What the staging pass could and could not reach
     * @return How many requests were marked discharged
     * @throws IOException if the completions cannot be recorded
     */
    private int completeWhatWasSatisfied(List<ErasureRequest> requests,
                                         GraphStagingStore.EraseResult staged,
                                         CompactionResult compaction) throws IOException {
        List<ErasureRequest> satisfied = satisfiedBy(requests, staged, compaction);
        log.complete(idsOf(satisfied));
        int withheld = requests.size() - satisfied.size();
        if (withheld == 0) {
            return satisfied.size();
        }
        if (staged.snippetsOfUnknownOrigin() > 0) {
            LOGGER.error("{} staged snippet(s) carry no recorded origin, so the prose they may "
                + "have come from is still on disk and will be re-indexed by the next build. "
                + "Requests selecting by provenance are left outstanding rather than reported as "
                + "discharged; they cannot be satisfied until the snippets of unrecorded origin "
                + "are removed or re-ingested.", staged.snippetsOfUnknownOrigin());
        }
        compaction.unreached().forEach((id, artifacts) ->
            LOGGER.error("erasure request {} is left outstanding: the compaction could not reach "
                + "{} in the served graph directory, so the data it covers is still on disk and "
                + "still being served. Supply a ServedArtifactEraser for those artifacts, or "
                + "rebuild the graph, before this request can honestly be called discharged.",
                id, artifacts));
        return satisfied.size();
    }

    /**
     * Why some of a batch could not be discharged, for an operator's log.
     *
     * @param withheld How many were withheld
     * @param staged   What the staging pass could not reach
     * @return A sentence, or an empty string when nothing was withheld
     */
    private static String withheldReason(int withheld, GraphStagingStore.EraseResult staged,
                                         CompactionResult compaction) {
        if (withheld == 0) {
            return "";
        }
        if (!compaction.complete()) {
            return "; " + withheld + " request(s) withheld because the compaction could not reach "
                + compaction.unreached().values().stream().flatMap(List::stream).distinct().sorted()
                    .toList()
                + " in the served graph directory";
        }
        return "; " + withheld + " request(s) withheld because " + staged.snippetsOfUnknownOrigin()
            + " staged snippet(s) carry no recorded origin to match a provenance scope against";
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
     * Whether the staging store behind this coordinator has handed out an entity id.
     *
     * <p>Read without a lock, and safe to act on without one. The staging store's entity
     * interner counts an id as assigned only once its label is forced to the device, and never
     * gives one up while it is open - an erased entity's label is tombstoned, its id kept - so an
     * id this answers true for names the same entity for as long as the store is open. An
     * erasure accepted for it is ordered after the label that id was given to, and one refused
     * because this answered false is ordered before it: nobody had been handed the id yet.</p>
     *
     * @param entityId The id
     * @return true when the id is one staging has assigned; false with no staging behind this
     */
    public boolean stagedEntity(long entityId) {
        return staging != null && entityId >= 0 && entityId < staging.entityCount();
    }

    /**
     * Whether an entity id names anything in this namespace: staged, or in a graph published at
     * {@code graphDirectory}.
     *
     * <p>The published graph is asked as well as staging because the two can disagree: a graph
     * is built from staging, but staging may since have been restored from an older copy or
     * started afresh while the graph it was built into is still served. The answer about the
     * published graph holds only until the next build or compaction replaces it, so a caller
     * that acts on it holds whatever lock those take.</p>
     *
     * @param entityId       The id
     * @param graphDirectory Where the namespace's graph is served from; need not exist
     * @return true when staging assigned the id or a served or displaced graph names it
     * @throws IOException if a published dictionary cannot be read
     * @throws NullPointerException if {@code graphDirectory} is null
     */
    public boolean entityAssigned(long entityId, Path graphDirectory) throws IOException {
        Objects.requireNonNull(graphDirectory, "graphDirectory must not be null");
        return stagedEntity(entityId)
            || (entityId >= 0 && ReferencedLabels.publishedEntity(graphDirectory, entityId));
    }

    /**
     * Discharges every outstanding entity-scoped request that names an id nothing assigned.
     *
     * <h3>Why such a request is discharged rather than kept</h3>
     * <p>A request records an entity by id alone, and ids are handed out in order. One that names
     * an id neither staging nor any published graph has assigned names no entity, and the only
     * thing it can ever erase is whichever entity is next given that id - a stranger, whose
     * erasure is irreversible. The node now refuses such a request at the door, but a log
     * written before it did may hold them. Discharging one cannot leave anything on disk that it
     * was accepted to remove: there is no label, no fact, no dictionary entry for it to have
     * covered, and a compaction would already mark it satisfied as matching nothing. Keeping it
     * risks the stranger; discharging it risks nothing.</p>
     *
     * <p>Run when a namespace is opened, before anything can intern a new label. The completion
     * is forced before this returns, so a crash cannot bring back a request this has already
     * reported discharged.</p>
     *
     * <p>A published dictionary too damaged to answer leaves the request outstanding rather than
     * failing the open: whether the id was assigned cannot be known, and a request kept is one
     * whose id no interner hands out again, where one discharged on a guess may be the erasure of
     * a real entity dropped. The next open after the graph is rebuilt answers it.</p>
     *
     * @param graphDirectory Where the namespace's graph is served from; need not exist
     * @return The requests discharged, oldest first; empty when none named an unassigned id
     * @throws IOException if a published dictionary cannot be read, or the completion cannot be
     *                     recorded and forced
     * @throws NullPointerException if {@code graphDirectory} is null
     */
    public List<ErasureRequest> dischargeUnassigned(Path graphDirectory) throws IOException {
        Objects.requireNonNull(graphDirectory, "graphDirectory must not be null");
        List<ErasureRequest> unassigned = new ArrayList<>();
        for (ErasureRequest request : log.outstanding()) {
            if (request.scope() instanceof ErasureScope.Entity entity
                    && !assignedOrUnknown(request, entity.entityId(), graphDirectory)) {
                unassigned.add(request);
            }
        }
        if (unassigned.isEmpty()) {
            return List.of();
        }
        log.complete(idsOf(unassigned));
        log.flush();
        for (ErasureRequest request : unassigned) {
            LOGGER.warn("erasure request {} names entity {}, which nothing in this namespace was "
                + "ever given: no staged label and no published graph holds it. Discharged "
                + "rather than left to erase whichever entity is given that id next",
                request.id(), ((ErasureScope.Entity) request.scope()).entityId());
        }
        return List.copyOf(unassigned);
    }

    /**
     * Whether an id may have been assigned, counting one only a damaged dictionary could answer
     * for as assigned.
     *
     * @param request        The request naming it, for the log line
     * @param entityId       The id
     * @param graphDirectory Where the namespace's graph is served from
     * @return false only when the id is known to name nothing
     * @throws IOException if a published dictionary cannot be read for a reason other than damage
     */
    private boolean assignedOrUnknown(ErasureRequest request, long entityId, Path graphDirectory)
            throws IOException {
        try {
            return entityAssigned(entityId, graphDirectory);
        } catch (GraphCorruptionException damage) {
            LOGGER.warn("erasure request {} names entity {}, which staging never assigned; "
                + "whether the published graph does cannot be told because {}. The request is "
                + "kept outstanding", request.id(), entityId, damage.getMessage());
            return true;
        }
    }

    /**
     * What a compaction attempt did.
     *
     * <p>{@code ran} is about the store, not about the requests: it is true only when the column
     * files were rewritten. A request that matched no edges is satisfied without a rewrite, so
     * {@code requestsSatisfied} can be non-zero while {@code ran} is false.
     *
     * @param requestsSatisfied How many outstanding requests this marked complete. Only requests
     *                          whose data this pass actually reached are counted: a request it
     *                          could not fully honour is left outstanding and counted in
     *                          {@code requestsWithheld} instead, because reporting an erasure
     *                          that did not happen is the worst outcome this mechanism has.
     * @param requestsWithheld  How many were deliberately left outstanding as unsatisfiable
     * @param edgesRemoved      How many edges the rewrite dropped
     * @param ran               Whether the store was rewritten
     * @param reason            Why, in a form fit for an operator's log
     */
    public record CompactionOutcome(boolean ran, long edgesRemoved, int requestsSatisfied,
                                    int requestsWithheld, String reason) {

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
            if (requestsWithheld < 0) {
                throw new IllegalArgumentException("requestsWithheld must not be negative, got "
                    + requestsWithheld);
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
        return compact(graphDirectory, true, StagingErasureGuard.NONE);
    }

    /**
     * As {@link #compactIfDue(Path)}, telling {@code guard} before staging is erased.
     *
     * @param graphDirectory Where the edge store's column files live
     * @param guard          Told, and allowed to refuse, before staging loses anything
     * @return What happened, including why nothing happened
     * @throws IOException if the guard refuses, or the store cannot be opened, rewritten, or the
     *                     completion recorded
     * @throws NullPointerException if an argument is null
     */
    public CompactionOutcome compactIfDue(Path graphDirectory, StagingErasureGuard guard)
            throws IOException {
        return compact(graphDirectory, true, guard);
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
        return compact(graphDirectory, false, StagingErasureGuard.NONE);
    }

    /**
     * As {@link #compactNow(Path)}, telling {@code guard} before staging is erased.
     *
     * @param graphDirectory Where the edge store's column files live
     * @param guard          Told, and allowed to refuse, before staging loses anything
     * @return What happened
     * @throws IOException if the guard refuses, or the store cannot be opened, rewritten, or the
     *                     completion recorded
     * @throws NullPointerException if an argument is null
     */
    public CompactionOutcome compactNow(Path graphDirectory, StagingErasureGuard guard)
            throws IOException {
        return compact(graphDirectory, false, guard);
    }

    /**
     * Told that a compaction is about to erase staging ahead of the served graph.
     *
     * <h2>Why the served graph's owner has to know first</h2>
     * <p>A compaction erases staging and only then rewrites the served directory, because a
     * served artifact derived from staging can only be rebuilt without the withdrawn data once
     * staging no longer holds it. From the moment staging is erased until the rewritten graph
     * is published, the graph on disk is the one place the erased data is still readable - and
     * nothing on disk says so until the compaction creates its scratch directory. A process that
     * dies in that gap, or a compaction that fails in it, leaves a graph that opens like any
     * other and would be served with the erased facts in it.</p>
     *
     * <p>So whoever serves the graph is told before staging is touched, and records that the
     * graph must be withheld until a compaction has applied what is outstanding. The record has
     * to be durable when this returns: it is what the next open reads after a crash. A guard
     * that throws stops the compaction with staging untouched, which leaves the served graph
     * exactly as trustworthy as it was.</p>
     *
     * <p>Not called when nothing is outstanding, when the policy declines, or by a coordinator
     * with no staging behind it: staging is not erased on any of those paths.</p>
     */
    @FunctionalInterface
    public interface StagingErasureGuard {

        /** A guard for a caller that serves nothing from the graph directory. */
        StagingErasureGuard NONE = () -> { };

        /**
         * Records, durably, that staging is about to be erased ahead of the served graph.
         *
         * @throws IOException if that cannot be recorded; the compaction then does not proceed
         */
        void beforeStagingErased() throws IOException;
    }

    /**
     * Discharges what can be discharged on a namespace whose graph has never been built.
     *
     * <p>There is no served store to rewrite, but there is something to erase: the facts,
     * snippets and labels staged for the build that has not run. Those are precisely what the
     * next {@code POST /v1/graph/build} would publish, so leaving them until a graph exists
     * turns an erasure into a delay - and on a namespace that is simply never built, into a
     * promise that is never kept while {@code outstanding} grows forever.
     *
     * <p>A request is judged on the staging pass alone here, because staging is the whole of
     * what this namespace holds. Nothing is served, so there is no served artifact a request
     * could fail to reach; the withheld cases that remain are the ones staging itself reports,
     * such as snippets carrying no recorded origin.
     *
     * <p>No graph is served, but a build that crashed may have left its scratch beside where one
     * would be, assembled from staging before this erased it; it is removed before anything is
     * discharged. See {@link #removeUnservedCopies}.</p>
     *
     * @param graphDirectory Where the namespace's graph would be served from; nothing is there
     * @param consultPolicy  Whether the policy decides, as it does for the served store. With no
     *                       edges there is no tombstone ratio and no segment count, so the only
     *                       condition that can fire is the latency bound - which is the one that
     *                       belongs here, since the obligation is the whole of what is waiting
     * @return What it did; {@code ran} is always false, because no store was rewritten
     * @throws IOException if the staging logs cannot be rewritten, a copy beside the graph
     *                     directory cannot be removed, or the completions recorded
     * @throws NullPointerException if {@code graphDirectory} is null
     */
    public CompactionOutcome eraseStagedOnly(Path graphDirectory, boolean consultPolicy)
            throws IOException {
        Objects.requireNonNull(graphDirectory, "graphDirectory must not be null");
        synchronized (compactionLock) {
            List<ErasureRequest> requests = log.outstanding();
            if (requests.isEmpty()) {
                return new CompactionOutcome(false, 0, 0, 0, "no outstanding erasure requests");
            }

            if (consultPolicy) {
                CompactionStats stats = new CompactionStats(
                    0, 0, EdgeStoreCompactor.oldestOutstanding(requests), 0);
                if (!policy.shouldCompact(stats, clock.instant())) {
                    return new CompactionOutcome(false, 0, 0, requests.size(),
                        "no graph has been built, and the policy does not yet consider the "
                            + requests.size() + " outstanding request(s) due");
                }
            }

            // Before staging is touched: nothing reads a copy nothing serves, and a displaced
            // graph that refuses the pass is better found while staging still matches it.
            removeUnservedCopies(graphDirectory);
            GraphStagingStore.EraseResult staged = eraseStaged(requests);
            // No served directory exists, so nothing is unreached: a request that staging could
            // answer has been answered in full.
            CompactionResult nothingServed = new CompactionResult(false, 0, Map.of());

            int satisfied = completeWhatWasSatisfied(requests, staged, nothingServed);
            int withheld = requests.size() - satisfied;

            LOGGER.info("no graph built for this namespace; erased staging for {} outstanding "
                + "request(s): {} satisfied, {} withheld", requests.size(), satisfied, withheld);
            return new CompactionOutcome(false, 0, satisfied, withheld,
                "no graph has been built, so there was nothing served to rewrite; "
                    + staged.removed() + " staged record(s) removed so the next build cannot "
                    + "republish them, " + satisfied + " request(s) discharged"
                    + withheldReason(withheld, staged, nothingServed));
        }
    }

    private CompactionOutcome compact(Path graphDirectory, boolean consultPolicy,
                                      StagingErasureGuard guard) throws IOException {
        Objects.requireNonNull(graphDirectory, "graphDirectory must not be null");
        Objects.requireNonNull(guard, "guard must not be null");

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
                        return new CompactionOutcome(false, 0, 0, 0, reason);
                    }
                }

                // The compaction decides for itself whether there is anything to rewrite. An
                // empty tombstone set is not on its own a no-op: an entity with no edges still
                // has a name in the served dictionary, prose in the served document index and a
                // vector computed from that prose, and those are what an erasure was about.
                // Staging is erased first, and that ordering is load-bearing. A served
                // artifact whose content is derived rather than stored - the entity documents
                // and the vectors computed from them - cannot be edited in place to remove one
                // source's prose; the only way to reach it is to rebuild it from staging, and a
                // ServedArtifactEraser can only do that once staging no longer holds what was
                // withdrawn. A crash in the gap leaves the request outstanding, and the retry
                // finds staging already clean and removes zero - but it also leaves the served
                // graph as the only copy of what staging lost, with nothing on disk yet to say
                // so. The guard is what says so, before the first byte of staging goes.
                if (staging != null && !requests.isEmpty()) {
                    guard.beforeStagingErased();
                }
                GraphStagingStore.EraseResult staged = eraseStaged(requests);

                CompactionResult compaction = EdgeStoreCompactor.compact(
                    store, graphDirectory, tombstoned, requests, erasers);
                // Reached only when the compaction rewrote the directory, which is the one case
                // in which it closed the store. Set before anything that could throw, and after
                // the only call that could have left it open.
                if (compaction.rewritten()) {
                    ours = false;
                }
                if (!requests.isEmpty()) {
                    removeUnservedCopies(graphDirectory);
                }

                // Completed after the rewrite, never before. A crash in this gap repeats an
                // erasure that has already removed everything it names, which costs a scan; the
                // other order would record the obligation as discharged with the data still there.
                int satisfied = completeWhatWasSatisfied(requests, staged, compaction);
                int withheld = requests.size() - satisfied;

                if (!compaction.rewritten()) {
                    // Leaving these outstanding would make the oldest-request condition fire on
                    // every call from here to the end of time, rewriting the store repeatedly to
                    // remove nothing.
                    return new CompactionOutcome(false, 0, satisfied, withheld,
                        requests.isEmpty() ? "no outstanding erasure requests"
                            : requests.size() + " outstanding request(s) matched no edge; "
                                + satisfied + " marked satisfied"
                                + withheldReason(withheld, staged, compaction));
                }

                LOGGER.info("compacted {}: {} edges removed for {} erasure request(s)",
                    graphDirectory, compaction.edgesRemoved(), requests.size());
                return new CompactionOutcome(true, compaction.edgesRemoved(), satisfied, withheld,
                    "compacted: " + compaction.edgesRemoved() + " edge(s) removed for "
                        + requests.size() + " request(s)"
                        + withheldReason(withheld, staged, compaction));
            } finally {
                if (ours) {
                    store.close();
                }
            }
        }
    }

    /**
     * Removes every copy of the graph beside {@code graphDirectory} that nothing will serve,
     * durably, before a request is discharged.
     *
     * <h3>Why an erasure has to</h3>
     * <p>An erasure promises that what it covers no longer exists on disk, and staging and the
     * served graph are not the only places it can. A build that crashed leaves its scratch
     * directory - a whole graph assembled from staging as it then was - until the next build
     * clears it; a compaction that crashed leaves its own; and a publish that crashed after both
     * of its renames leaves the graph it replaced beside the new one. None is ever served, and
     * each may hold exactly what this erasure removed from staging, so each is removed here, in
     * the gap between applying the requests and recording them discharged.</p>
     *
     * <h3>Why this may remove the compaction's scratch</h3>
     * <p>A node reads a compaction's scratch as a sign that an erasure was interrupted and the
     * graph may still hold what it erases. That sign has done its work by now: a node that found
     * it withholds the graph and records a marker before it compacts, and a compaction that
     * erases staging has its guard record the same marker first. The requests it signalled have
     * just been applied, which is what the sign was asking for.</p>
     *
     * <p>A graph set aside with nothing served in its place is not removed: it is the graph,
     * which recovery restores. The caller recovers an interrupted publish before compacting; one
     * still found here is refused rather than discharged over.</p>
     *
     * @param graphDirectory Where the namespace's graph is served from
     * @throws IOException if a copy cannot be removed or its removal forced, or a displaced graph
     *                     is waiting to be restored
     */
    private static void removeUnservedCopies(Path graphDirectory) throws IOException {
        boolean served = Files.isDirectory(graphDirectory);
        if (!served && GraphBuilder.hasDisplacedGraph(graphDirectory)) {
            throw new IOException("a graph a publish set aside is beside " + graphDirectory
                + " with nothing served in its place; it may hold what the outstanding erasures "
                + "cover, so they are not discharged until recovery has restored it and they "
                + "have been applied to it");
        }
        List<Path> copies = new ArrayList<>(List.of(GraphBuilder.scratchFor(graphDirectory),
            EdgeStoreCompactor.scratchFor(graphDirectory)));
        if (served) {
            copies.add(GraphBuilder.previousFor(graphDirectory));
            copies.add(GraphBuilder.retiredFor(graphDirectory));
        }
        for (Path copy : copies) {
            if (Files.exists(copy)) {
                GraphBuilder.discard(copy);
                LOGGER.info("removed {}, which nothing serves and which may hold what the "
                    + "erasure being discharged covers", copy);
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
        return stats.tombstonedRecords() + " of " + stats.totalRecords() + " edge(s) tombstoned, "
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

    /**
     * How many obligations are outstanding, and how long the oldest has waited.
     *
     * <p>The count alone says whether erasure is keeping up; the age is what is measured against
     * the deadline the deployment promised, and the one figure an operator can compare with
     * {@code excalibur.graph.erasure-max-latency-seconds}.</p>
     *
     * @return What the log owes
     */
    public ErasureLog.Pending pending() {
        return log.pending();
    }

    @Override
    public String toString() {
        return "GraphErasureCoordinator[" + log.outstandingCount() + " outstanding]";
    }
}
