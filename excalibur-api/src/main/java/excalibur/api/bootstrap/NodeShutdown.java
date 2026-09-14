package excalibur.api.bootstrap;

import excalibur.api.server.ExcaliburDBServer;
import excalibur.ingest.lexical.ChunkIndex;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import excalibur.shard.IndexOrchestrator;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.util.Objects;

/**
 * The order a node has to be taken apart in, and what to say about how it went.
 *
 * <p>{@link ShutdownSequence} is the mechanism - run every step, keep going after a failure,
 * report which ones completed. This is the node's own answer to the question the mechanism does
 * not know: which steps, in which order. That order is a correctness property rather than
 * tidiness. Query and reindex threads read memory-mapped segments through the shared
 * {@link Arena}, and closing that arena while a mapping is still live crashes the JVM instead of
 * throwing, so the sequence is always: stop accepting, drain what is in flight, close storage,
 * close the arena.</p>
 *
 * <p>Written down here rather than in the composition root because it is the one part of
 * startup that runs in reverse, and the two have to stay in step: anything the root opens and
 * does not list here is a directory this process still holds at exit, or a buffer whose contents
 * never reached disk. Keeping the list beside the sequence that runs it is what makes that
 * comparison possible to make.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>The record is immutable and {@link #run()} is expected to be called once, from the shutdown
 * hook. The underlying {@link ShutdownSequence} is itself idempotent, so a second call is inert
 * rather than a second teardown.</p>
 *
 * @param server                    The HTTP surface, stopped first so nothing new arrives
 * @param indexOrchestrator         Background indexing, drained before storage closes
 * @param lexicalErasureSweep       The chunk index's compaction timer, or null if none runs
 * @param chunkIndex                The BM25 index, or null if none opened
 * @param documentVisibilityTracker Holds the registry that says which documents this node has
 * @param graphTenants              Every open namespace graph
 * @param storageEngine             Storage, closed after every reader has drained
 * @param arena                     The shared arena backing the mappings, or null on the
 *                                  ByteBuffer fallback
 * @param timeoutSeconds            How long the HTTP server is given to drain in-flight requests
 * @author ExcaliburDB
 */
public record NodeShutdown(ExcaliburDBServer server,
                           IndexOrchestrator indexOrchestrator,
                           LexicalErasureSweep lexicalErasureSweep,
                           ChunkIndex chunkIndex,
                           DocumentVisibilityTracker documentVisibilityTracker,
                           GraphTenants graphTenants,
                           StorageEngine storageEngine,
                           Arena arena,
                           int timeoutSeconds) {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeShutdown.class);

    /**
     * Validates the components that have no absent case.
     *
     * <p>The four checked below are what a running node always has; the rest are genuinely
     * optional and their absence is a shape of node rather than a fault. A missing one of these
     * would otherwise surface as a shutdown step that threw, which is reported as a node that
     * did not stop cleanly rather than as the wiring mistake it is.</p>
     *
     * @throws NullPointerException if the server, orchestrator, tracker or storage is null
     */
    public NodeShutdown {
        Objects.requireNonNull(server, "server cannot be null");
        Objects.requireNonNull(indexOrchestrator, "indexOrchestrator cannot be null");
        Objects.requireNonNull(documentVisibilityTracker, "documentVisibilityTracker cannot be null");
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
    }

    /**
     * Takes the node apart and reports the outcome.
     *
     * <p>Every step runs even when an earlier one failed, because a step that throws must not
     * leave the arena open with the process still holding mappings.</p>
     */
    public void run() {
        ShutdownSequence sequence = sequence();
        int failedSteps = sequence.stepNames().size() - sequence.run().size();
        reportOutcome(failedSteps, storageEngine.getCatalog().closeFailure());
    }

    /**
     * Lists the teardown steps in the only order that is safe.
     *
     * @return The sequence, built but not yet run
     */
    private ShutdownSequence sequence() {
        return new ShutdownSequence()
            .add("stop HTTP server and drain in-flight requests",
                () -> server.stop(timeoutSeconds))
            .add("stop index orchestrator", indexOrchestrator::close)
            // After ingestion has stopped and before storage closes: closing it publishes what
            // was still buffered, and it is storage that those chunks were written to first.
            // Before the index: a sweep in flight is inside a compaction of it.
            .addIfPresent("stop lexical erasure sweep", lexicalErasureSweep,
                LexicalErasureSweep::close)
            .addIfPresent("close chunk lexical index", chunkIndex, ChunkIndex::close)
            // After ingestion has stopped, so what is written is the final state rather than a
            // snapshot something was still adding to. Every operation that changes the registry
            // writes it too; this is what catches a node stopped between them.
            .add("persist document registry",
                () -> documentVisibilityTracker.getRegistry().persist())
            .addIfPresent("close graph tenants", graphTenants, GraphTenants::close)
            .add("close storage engine", storageEngine::close)
            .addIfPresent("release memory-mapped segments", arena, Arena::close);
    }

    /**
     * Logs how the shutdown actually went.
     *
     * <p>An operator reads this line to decide whether the node can be restarted without further
     * thought, so it has to distinguish a shutdown that persisted everything from one that did
     * not. A stale catalog in particular is not cosmetic: the next start re-processes write-log
     * ranges it has already integrated.</p>
     *
     * @param failedSteps    Number of shutdown steps that threw
     * @param catalogFailure The failure that stopped the catalog closing durably, or null
     */
    static void reportOutcome(int failedSteps, Throwable catalogFailure) {
        if (failedSteps == 0 && catalogFailure == null) {
            LOGGER.info("ExcaliburDB shutdown complete");
            return;
        }

        if (catalogFailure != null) {
            LOGGER.error("ExcaliburDB shutdown finished with {} failed step(s) and a catalog that "
                + "did not close durably; the next start may re-process write-log ranges it had "
                + "already integrated", failedSteps, catalogFailure);
        } else {
            LOGGER.error("ExcaliburDB shutdown finished with {} failed step(s); see the errors "
                + "logged above", failedSteps);
        }
    }
}
