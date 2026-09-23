package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.erasure.GraphErasureCoordinator;
import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.GraphBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Accepts erasure obligations and applies them to the graph the node is serving.
 *
 * <h2>What this adds to the coordinator</h2>
 * <p>The coordinator knows how to record a request and rewrite a store. It does not know that the
 * store it rewrites is one this node has mapped and is answering queries from - and a compaction
 * replaces that directory. So this closes the served stack around the compaction and reopens it
 * afterwards, the same way a build does, for the same reason: Lucene holds a per-path write lock
 * for the whole JVM, and the mappings the old stack holds describe files the compaction has
 * replaced.</p>
 *
 * <h2>A graph an erasure was interrupted on</h2>
 * <p>A compaction erases staging first and then publishes the rewritten graph, and a process that
 * dies in between leaves a graph on disk that still holds what the erasure removes: the original,
 * untouched or restored by crash recovery, with the obligation still outstanding. Serving it
 * would answer queries from erased facts for as long as the policy took to call the obligation
 * due. So a tenant that finds one opens it withheld and calls {@link #finishInterruptedErasure()},
 * and the graph is served only once a compaction has run over it - on that call, or on the first
 * later one that succeeds.</p>
 *
 * <p>The marker that says so is written before staging loses anything, not when the failure is
 * noticed, because a crash notices nothing: until the compaction creates its scratch directory
 * there is otherwise no sign on disk that one was under way. And a compaction that fails, rather
 * than dies, after staging was erased leaves the graph withheld for the same reason instead of
 * putting it back in service.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. Compactions are serialised against each other; a caller that submits
 * while one is running is not blocked, because recording the obligation is what must not wait -
 * with one exception, an entity id staging has not assigned, which has to be judged against the
 * published graph while nothing can replace it; see {@link #submit}.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphErasureService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphErasureService.class);

    private final GraphErasureCoordinator coordinator;
    private final GraphRagHolder holder;
    private final Config config;
    private final GraphModelClients models;

    /**
     * Records on disk that the graph is withheld, or null for a service with nowhere to record
     * it. See {@link #GraphErasureService(GraphErasureCoordinator, GraphRagHolder, Config,
     * GraphModelClients, Path)}.
     */
    private final Path withheldMarker;

    /**
     * Whether the graph on disk is deliberately not being served until a compaction has run over
     * it. Written under the replacement lock; volatile so {@link #servingWithheld()} reads it
     * without taking that lock.
     */
    private volatile boolean withheldForErasure;

    /**
     * Creates the service.
     *
     * @param coordinator Records requests and rewrites the store
     * @param holder      The stack being served, withdrawn around a compaction and reopened after
     * @param config      Read when reopening the stack
     * @param models      The node's model clients, which the reopened stack reranks and embeds
     *                    through
     */
    public GraphErasureService(GraphErasureCoordinator coordinator, GraphRagHolder holder,
                               Config config, GraphModelClients models) {
        this(coordinator, holder, config, models, null);
    }

    /**
     * Creates the service, recording on disk while a graph is withheld for an interrupted erasure.
     *
     * <h3>Why the withholding has to outlive the process</h3>
     * <p>What tells a tenant that an erasure was interrupted - a displaced graph recovery
     * restores, a compaction's leftover scratch directory - is gone once the first attempt to
     * finish it has run: recovery has restored the graph, and a failed compaction clears its own
     * scratch. If that attempt fails and the tenant is then evicted or the node restarts, the
     * next open would find an ordinary-looking graph and serve it, erased facts and all. So the
     * withholding is written down before the attempt, beside the obligations it concerns, and
     * removed only once a compaction has applied them; a tenant that finds it withholds again.</p>
     *
     * @param coordinator    Records requests and rewrites the store
     * @param holder         The stack being served, withdrawn around a compaction and reopened
     * @param config         Read when reopening the stack
     * @param models         The node's model clients
     * @param withheldMarker Where to record that the graph is withheld, or null for nowhere
     */
    public GraphErasureService(GraphErasureCoordinator coordinator, GraphRagHolder holder,
                               Config config, GraphModelClients models, Path withheldMarker) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.holder = Objects.requireNonNull(holder, "holder must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.models = Objects.requireNonNull(models, "models must not be null");
        this.withheldMarker = withheldMarker;
    }

    /**
     * What a compaction attempt did.
     *
     * @param ran               whether a compaction actually ran
     * @param edgesRemoved      how many edges were rewritten out of the store
     * @param requestsSatisfied how many obligations that discharged
     * @param requestsWithheld  how many it could not reach and deliberately left outstanding
     *                          rather than reporting as done; carried up to the response
     *                          because a caller under an obligation has to see it
     * @param reason            why it ran or did not
     */
    public record ErasureOutcome(boolean ran, long edgesRemoved, int requestsSatisfied,
                                 int requestsWithheld, String reason) {
    }

    /**
     * An entity-scoped erasure named an id nothing in the namespace holds.
     *
     * <p>Unchecked, and distinct from every other failure of {@link #submit}, because it is the
     * caller's to answer: nothing was recorded, and the request can be corrected rather than
     * retried.</p>
     */
    public static final class UnknownEntityException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The id the request named. */
        private final long entityId;

        /**
         * @param entityId The id the request named
         */
        public UnknownEntityException(long entityId) {
            super("no entity in this namespace has id " + entityId);
            this.entityId = entityId;
        }

        /**
         * The id the request named.
         *
         * @return The id
         */
        public long entityId() {
            return entityId;
        }
    }

    /**
     * Records an obligation to erase, durably, before returning.
     *
     * <h3>An entity-scoped request must name an entity</h3>
     * <p>A request records an entity by id alone, and ids are handed out in order, so one
     * accepted for an id nobody holds yet would erase whichever entity is given that id next -
     * irreversibly, and with nothing on record saying anybody asked. An entity id is accepted
     * only when staging has assigned it or a graph this namespace serves, or is about to restore,
     * names it; anything else is refused with {@link UnknownEntityException} and nothing is
     * recorded. Scopes that select by provenance name no id and are recorded as they are.</p>
     *
     * <h3>How the check is ordered against what changes the answer</h3>
     * <p>Two things can. Staging assigns new ids, and it never takes one back: an id is counted
     * only once its label is forced to the device, and an erased entity's label is tombstoned
     * with its id kept. So a staged id is accepted without a lock, ordered after the label it
     * names; one staging has not yet assigned is refused, ordered before it - nobody had been
     * given the id. A build or a compaction replaces the published graph, and can drop an id
     * only the published graph names; so such an id is judged and recorded under the replacement
     * lock those hold for their whole run, and the request is ordered before the replacement,
     * which then applies it. That makes a request for an id staging has not assigned wait out a
     * build or compaction already running.</p>
     *
     * @param scope What to erase
     * @return The request id
     * @throws UnknownEntityException if an entity-scoped request names an id nothing holds
     * @throws IOException if the obligation cannot be recorded, or a published dictionary
     *                     cannot be read to decide whether it may be
     * @throws NullPointerException if {@code scope} is null
     */
    public long submit(ErasureScope scope) throws IOException {
        Objects.requireNonNull(scope, "scope must not be null");
        long id = scope instanceof ErasureScope.Entity entity
                && !coordinator.stagedEntity(entity.entityId())
            ? submitUnstaged(entity)
            : coordinator.submit(scope);
        LOGGER.info("erasure request {} accepted: {}", id, scope);
        return id;
    }

    /**
     * Records an entity-scoped request whose id staging has not assigned, if anything else holds
     * it.
     *
     * @param entity The request
     * @return The request id
     * @throws UnknownEntityException if nothing holds the id
     * @throws IOException if a dictionary cannot be read or the request cannot be recorded
     */
    private long submitUnstaged(ErasureScope.Entity entity) throws IOException {
        ReentrantLock replacement = holder.replacementLock();
        replacement.lock();
        try {
            if (!coordinator.entityAssigned(entity.entityId(), holder.directory())) {
                throw new UnknownEntityException(entity.entityId());
            }
            return coordinator.submit(entity);
        } finally {
            replacement.unlock();
        }
    }

    /**
     * What withdrawing a set of documents did to the graph.
     *
     * @param submitted  How many source-scoped obligations this recorded, one per document
     * @param satisfied  How many obligations the compaction that followed discharged. The
     *                   coordinator reports per pass rather than per request, so this counts
     *                   everything the node owed and not only what was submitted here
     * @param withheld   How many it deliberately left outstanding as not yet reachable
     * @param outstanding How many obligations the node still owes in this namespace
     * @param compacted  Whether the served graph was actually rewritten
     * @param complete   Whether the obligations submitted here can be said to be discharged.
     *                   False whenever the pass withheld anything or discharged fewer than were
     *                   submitted, because the coordinator's counts cannot be attributed to
     *                   individual requests and reporting an erasure that did not happen is the
     *                   worse of the two errors
     * @param detail     Why the compaction ran, or why it did not
     */
    public record DocumentGraphOutcome(int submitted, int satisfied, int withheld,
                                       int outstanding, boolean compacted, boolean complete,
                                       String detail) {

        /** What a node serving no graph reports: nothing was extracted into one. */
        public static final DocumentGraphOutcome NO_GRAPH = new DocumentGraphOutcome(
            0, 0, 0, 0, false, true,
            "this node serves no graph, so nothing was extracted from these documents into one");

        /**
         * What a node with a graph but nothing to withdraw reports.
         *
         * @param detail Why there was nothing to withdraw
         * @return An outcome that submitted nothing and owes nothing
         */
        public static DocumentGraphOutcome none(String detail) {
            return new DocumentGraphOutcome(0, 0, 0, 0, false, true, detail);
        }

        /**
         * What withdrawing one set of documents did across several namespaces.
         *
         * <p>Counts add, a rewrite anywhere is a rewrite, and the whole is complete only where
         * every part is: a namespace still owing an obligation is not discharged by another one
         * that owed nothing.</p>
         *
         * @param parts The outcome in each namespace visited, at least one
         * @return Their combination
         * @throws IllegalArgumentException if {@code parts} is empty
         */
        public static DocumentGraphOutcome combine(List<DocumentGraphOutcome> parts) {
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("parts must not be empty");
            }
            if (parts.size() == 1) {
                return parts.get(0);
            }
            int submitted = 0;
            int satisfied = 0;
            int withheld = 0;
            int outstanding = 0;
            boolean compacted = false;
            boolean complete = true;
            StringBuilder detail = new StringBuilder();
            for (DocumentGraphOutcome part : parts) {
                submitted += part.submitted();
                satisfied += part.satisfied();
                withheld += part.withheld();
                outstanding += part.outstanding();
                compacted |= part.compacted();
                complete &= part.complete();
                if (!detail.isEmpty()) {
                    detail.append("; ");
                }
                detail.append(part.detail());
            }
            return new DocumentGraphOutcome(submitted, satisfied, withheld, outstanding,
                compacted, complete, detail.toString());
        }
    }

    /**
     * Withdraws from the graph everything that was extracted from the named documents.
     *
     * <h3>Why the corpus erasure cannot do this itself</h3>
     * <p>Removing a document's chunks, postings and registry entry leaves every fact extracted
     * from it in the graph store, and up to two kilobytes of its text against each entity it
     * mentioned in the staging log - where the next build replays both. The obligation is
     * derivable and was simply never submitted: each fact and snippet carries
     * {@link LlmRelationExtractor#sourceIdOf} of the document's key, which is exactly what a
     * source-scoped request selects on.
     *
     * <p>One request per document rather than one for the batch, because that is the shape of
     * the obligation: a caller repeating a source erasure until the source is empty submits the
     * documents it actually erased each time, and a request that named a document the node
     * never held submits nothing.
     *
     * @param documentKeys The documents actually erased, as
     *                     {@link com.eonmux.excalibur.core.text.ChunkIdentifiers#documentKey} forms them
     * @param compactNow   Rewrite the served graph now rather than leaving it to the policy
     * @return What was submitted and what the compaction that followed managed
     * @throws IOException if an obligation cannot be recorded or the compaction fails
     * @throws NullPointerException if {@code documentKeys} is null
     */
    public DocumentGraphOutcome eraseDocuments(Collection<String> documentKeys,
                                               boolean compactNow) throws IOException {
        Objects.requireNonNull(documentKeys, "documentKeys must not be null");
        if (documentKeys.isEmpty()) {
            return DocumentGraphOutcome.none(
                "no document was erased, so the graph holds nothing more of one");
        }
        for (String documentKey : List.copyOf(documentKeys)) {
            submit(new ErasureScope.Source(SourceType.DOCUMENT,
                LlmRelationExtractor.sourceIdOf(documentKey)));
        }

        ErasureOutcome outcome = compactNow ? compactNow() : compactIfDue();
        int submitted = documentKeys.size();
        boolean complete = outcome.requestsWithheld() == 0
            && outcome.requestsSatisfied() >= submitted;
        if (!complete) {
            LOGGER.info("{} document(s) withdrawn from the corpus and still owed by the graph: "
                + "{} obligation(s) outstanding after {}", submitted, outstandingCount(),
                outcome.reason());
        }
        return new DocumentGraphOutcome(submitted, outcome.requestsSatisfied(),
            outcome.requestsWithheld(), outstandingCount(), outcome.ran(), complete,
            outcome.reason());
    }

    /**
     * Runs a compaction if the policy says one is due.
     *
     * @return What it did
     * @throws IOException if the compaction fails
     */
    public ErasureOutcome compactIfDue() throws IOException {
        return compact(false);
    }

    /**
     * Runs a compaction now, whatever the policy says.
     *
     * <p>For a caller under a deadline that has to be able to say the data is gone rather than
     * scheduled to go.</p>
     *
     * @return What it did
     * @throws IOException if the compaction fails
     */
    public ErasureOutcome compactNow() throws IOException {
        return compact(true);
    }

    /**
     * Applies what is outstanding to a graph an erasure was interrupted on, and then serves it.
     *
     * <p>For a tenant that has opened a graph without serving it because a compaction died
     * part-way through: crash recovery restored the graph the compaction was replacing, or the
     * compaction's scratch directory was left beside it. Either way the graph may still hold
     * what an outstanding obligation erases, so it is compacted now, whatever the policy says,
     * and put in the holder only once that has succeeded.</p>
     *
     * <p>A failure leaves the graph unserved and this service remembering so: every later
     * compaction, the scheduled sweep's included, runs regardless of the policy until one gets
     * through, and that one serves the graph. Unserved is the right state to fail in - a query
     * told there is no graph is answered wrongly for a while; one answered from erased facts is
     * the obligation broken.</p>
     *
     * @return What the compaction did
     * @throws IOException if the compaction fails; the graph stays unserved
     */
    public ErasureOutcome finishInterruptedErasure() throws IOException {
        ReentrantLock lock = holder.replacementLock();
        lock.lock();
        try {
            withheldForErasure = true;
            recordWithheld();
            return compact(true);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records the withholding before a compaction erases staging, and remembers that it did.
     *
     * <p>See {@link GraphErasureCoordinator.StagingErasureGuard}. Called with the replacement
     * lock held, on the compaction's own thread.</p>
     */
    private final class StagingGuard implements GraphErasureCoordinator.StagingErasureGuard {

        /** Whether the coordinator reached staging: set once the marker is on disk, just before. */
        private boolean stagingErased;

        @Override
        public void beforeStagingErased() throws IOException {
            recordWithheld();
            stagingErased = true;
        }
    }

    /**
     * Writes the marker saying the graph is withheld, and makes its name durable.
     *
     * @throws IOException if the marker cannot be written; the graph stays withheld regardless
     */
    private void recordWithheld() throws IOException {
        if (withheldMarker == null || Files.exists(withheldMarker)) {
            return;
        }
        Path parent = withheldMarker.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.createFile(withheldMarker);
        if (parent != null) {
            Durability.DEVICE.fsyncDirectory(parent);
        }
    }

    /**
     * Removes the marker once the erasure it guarded has been applied.
     *
     * <p>A marker that will not go costs the next open an early compaction and nothing else, so
     * the failure is logged rather than raised over a compaction that succeeded.</p>
     */
    private void clearWithheld() {
        if (withheldMarker == null) {
            return;
        }
        try {
            Files.deleteIfExists(withheldMarker);
        } catch (IOException e) {
            LOGGER.warn("could not remove {}; the next open of this graph will apply its "
                + "outstanding erasures again before serving it: {}", withheldMarker,
                e.toString());
        }
    }

    /**
     * Whether a graph is on disk and deliberately not served until an interrupted erasure has
     * been applied to it.
     *
     * @return true while {@link #finishInterruptedErasure()} has not yet succeeded
     */
    public boolean servingWithheld() {
        return withheldForErasure;
    }

    /**
     * How many obligations are recorded and not yet discharged.
     *
     * @return The count
     */
    public int outstandingCount() {
        return coordinator.outstandingCount();
    }

    /**
     * How many obligations are outstanding, and when the longest-waiting one arrived.
     *
     * @return What this namespace owes
     */
    public com.eonmux.excalibur.graph.erasure.ErasureLog.Pending pending() {
        return coordinator.pending();
    }

    /**
     * Runs a compaction, closing and reopening the served stack around it.
     *
     * <p>A graph withheld for an interrupted erasure is compacted whatever {@code force} says and
     * is served once the compaction has returned; see {@link #finishInterruptedErasure()}.</p>
     *
     * @param requested Whether the caller asked to skip the policy
     * @return What it did
     * @throws IOException if the compaction fails
     */
    private ErasureOutcome compact(boolean requested) throws IOException {
        // The build's lock too: a build publishing while this rewrites the directory, or reading
        // staging while this erases from it, would serve what this is removing.
        ReentrantLock compactionLock = holder.replacementLock();
        compactionLock.lock();
        try {
            boolean withheld = withheldForErasure;
            boolean force = requested || withheld;
            java.nio.file.Path directory = holder.directory();
            // A graph an interrupted publish displaced is still a graph holding the facts being
            // erased, and its leftover copy beside a completed one would outlive the erasure.
            GraphBuilder.recoverInterruptedPublish(directory);
            if (!java.nio.file.Files.isDirectory(directory)) {
                // Nothing served, but not nothing to do. The obligation was recorded against
                // facts that are staged, and staged facts are what the next build publishes -
                // so returning here without touching them would leave an erasure to be
                // discharged only if a graph were ever built, and silently never on a namespace
                // that is not. The staging pass is the whole of what this namespace holds.
                GraphErasureCoordinator.CompactionOutcome outcome =
                    coordinator.eraseStagedOnly(!force);
                // Nothing on disk is left to withhold.
                if (withheld) {
                    withheldForErasure = false;
                    clearWithheld();
                }
                return new ErasureOutcome(outcome.ran(), outcome.edgesRemoved(),
                    outcome.requestsSatisfied(), outcome.requestsWithheld(), outcome.reason());
            }

            // Asked before the served stack is touched. A compaction rewrites only edges some
            // outstanding request names, so with nothing outstanding there is nothing it could
            // do - and closing the stack to find that out is an outage of /v1/graph/query for
            // the length of a reopen, bought for no work. That was merely wasteful while an
            // erasure request was the only caller; on the schedule that enforces the latency
            // bound it would be a recurring one. A forced compaction still goes through: a
            // caller who asked for one outright is answering a deadline, not an estimate.
            if (!force && coordinator.outstandingCount() == 0) {
                return new ErasureOutcome(false, 0, 0, 0, "no outstanding erasure requests");
            }

            // The compaction rewrites files the served stack maps, so the stack is withdrawn and
            // the queries reading it are let finish before anything is touched.
            boolean wasServing = holder.withdraw();
            StagingGuard guard = new StagingGuard();
            boolean completed = false;
            try {
                GraphErasureCoordinator.CompactionOutcome outcome = force
                    ? coordinator.compactNow(directory, guard)
                    : coordinator.compactIfDue(directory, guard);
                completed = true;

                return new ErasureOutcome(outcome.ran(), outcome.edgesRemoved(),
                    outcome.requestsSatisfied(), outcome.requestsWithheld(), outcome.reason());
            } finally {
                if (completed && (withheldForErasure || guard.stagingErased)) {
                    // The erasure that was interrupted, or the one this pass began, has now been
                    // applied, so what is on disk is what an uninterrupted one would have left.
                    withheldForErasure = false;
                    clearWithheld();
                } else if (!completed && guard.stagingErased) {
                    // Failed after staging lost what the requests cover and before the served
                    // graph was rewritten without it - or at least before this could tell that
                    // it was. The graph on disk may be the last copy of the erased data, so it
                    // is not reopened: it stays withheld, as the marker the guard wrote says it
                    // is, until a compaction gets through.
                    withheldForErasure = true;
                    LOGGER.error("an erasure compaction of {} failed after erasing staging; the "
                        + "graph is withheld until a later compaction applies what is still "
                        + "outstanding", directory);
                }
                // Reopened whether the compaction ran or not, and whether it failed or not, when
                // the node was serving a graph before this and nothing has made it untrustworthy
                // since: it must be serving one after. A graph withheld for the erasure is
                // served once it has been applied.
                boolean servable = completed || !withheldForErasure;
                if ((wasServing && servable) || (withheld && completed)) {
                    reopen(directory);
                }
            }
        } finally {
            compactionLock.unlock();
        }
    }

    /**
     * Puts the served stack back after a compaction.
     *
     * @param directory The graph directory
     */
    private void reopen(java.nio.file.Path directory) {
        try {
            holder.install(GraphRagFactory.openAt(config, directory, models));
        } catch (IOException | RuntimeException e) {
            // The graph is on disk and intact; this node just cannot serve it any more. Saying so
            // is better than leaving a stack mapped over files a compaction replaced.
            LOGGER.error("Could not reopen the graph at {} after a compaction: {}",
                directory, e.toString());
        }
    }
}
