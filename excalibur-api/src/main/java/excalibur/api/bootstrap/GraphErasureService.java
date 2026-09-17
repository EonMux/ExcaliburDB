package excalibur.api.bootstrap;

import excalibur.ingest.graph.LlmRelationExtractor;
import io.excalibur.config.Config;
import io.excalibur.graph.erasure.ErasureScope;
import io.excalibur.graph.erasure.GraphErasureCoordinator;
import io.excalibur.graph.provenance.SourceType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. Compactions are serialised against each other; a caller that submits
 * while one is running is not blocked, because recording the obligation is what must not wait.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphErasureService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphErasureService.class);

    private final GraphErasureCoordinator coordinator;
    private final GraphRagHolder holder;
    private final Config config;
    private final ReentrantLock compactionLock = new ReentrantLock();

    /**
     * Creates the service.
     *
     * @param coordinator Records requests and rewrites the store
     * @param holder      The stack being served, closed around a compaction and reopened after
     * @param config      Read when reopening the stack
     */
    public GraphErasureService(GraphErasureCoordinator coordinator, GraphRagHolder holder,
                               Config config) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.holder = Objects.requireNonNull(holder, "holder must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
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
     * Records an obligation to erase, durably, before returning.
     *
     * @param scope What to erase
     * @return The request id
     * @throws IOException if the obligation cannot be recorded
     */
    public long submit(ErasureScope scope) throws IOException {
        long id = coordinator.submit(scope);
        LOGGER.info("erasure request {} accepted: {}", id, scope);
        return id;
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

        /** What a node with a graph but nothing to withdraw reports. */
        static DocumentGraphOutcome none(String detail) {
            return new DocumentGraphOutcome(0, 0, 0, 0, false, true, detail);
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
     *                     {@link io.excalibur.core.text.ChunkIdentifiers#documentKey} forms them
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
     * How many obligations are recorded and not yet discharged.
     *
     * @return The count
     */
    public int outstandingCount() {
        return coordinator.outstandingCount();
    }

    /**
     * Runs a compaction, closing and reopening the served stack around it.
     *
     * @param force Whether to skip the policy
     * @return What it did
     * @throws IOException if the compaction fails
     */
    private ErasureOutcome compact(boolean force) throws IOException {
        compactionLock.lock();
        try {
            java.nio.file.Path directory = holder.directory();
            if (!java.nio.file.Files.isDirectory(directory)) {
                return new ErasureOutcome(false, 0, 0, 0,
                    "no graph has been built, so there is nothing to erase from yet");
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

            boolean wasServing = holder.isPresent();
            if (wasServing) {
                // The compaction replaces this directory. Closing first releases the mappings and
                // the Lucene write locks the replacement will need at the same paths.
                holder.install(null);
            }
            try {
                GraphErasureCoordinator.CompactionOutcome outcome = force
                    ? coordinator.compactNow(directory)
                    : coordinator.compactIfDue(directory);

                return new ErasureOutcome(outcome.ran(), outcome.edgesRemoved(),
                    outcome.requestsSatisfied(), outcome.requestsWithheld(), outcome.reason());
            } finally {
                if (wasServing) {
                    // Reopened whether the compaction ran or not, and whether it failed or not:
                    // the node was serving a graph before this and must be serving one after.
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
            holder.install(GraphRagFactory.openAt(config, directory));
        } catch (IOException | RuntimeException e) {
            // The graph is on disk and intact; this node just cannot serve it any more. Saying so
            // is better than leaving a stack mapped over files a compaction replaced.
            LOGGER.error("Could not reopen the graph at {} after a compaction: {}",
                directory, e.toString());
        }
    }
}
