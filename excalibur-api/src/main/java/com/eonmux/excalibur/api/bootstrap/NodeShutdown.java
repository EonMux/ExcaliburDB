package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.api.server.ShutdownBudget;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.processor.DocumentDecoder;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.time.Duration;
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
 * <h2>One deadline</h2>
 * <p>Every wait in the sequence is cut from a single {@link ShutdownBudget} of
 * {@code shutdownTimeout}, started when the sequence starts. Each drain used to carry its own
 * fixed wait, and run one after another they added up to minutes - far past the stop timeout of
 * any supervisor, whose SIGKILL then landed before the chunk index's buffer was published or the
 * registry persisted. The budget keeps a reserve back for those durable writes: the drains share
 * what is left, and whatever they leave unfinished is reported and left open rather than waited
 * out.</p>
 *
 * <h2>What a refusal leaves behind</h2>
 * <p>A drain that could not stop something - a compaction past its last boundary, an ingestion
 * run that did not reach its next document in time, a graph build between two of its own
 * boundaries - is reported rather than waited out, and the steps that would close what it is
 * using refuse. Nothing is interrupted to make it stop sooner, because an interrupt reaching a
 * thread inside a shared channel operation closes that channel for every thread. Storage and the
 * arena stay open under anything still reading them, the chunk index's files stay open under
 * anything still compacting or writing it, and the graph tenants stay open under a build still
 * writing into them.
 * The chunk index's buffer is published regardless, in a step of its own: chunks reach storage
 * before the index, and nothing afterwards would index ones storage holds and the index was
 * never told about. The graph tenants' staged facts are forced to disk regardless too, for the
 * same reason: the registry already records the documents they were extracted from.</p>
 *
 * @param server                    The HTTP surface, stopped first so nothing new arrives
 * @param indexOrchestrator         Background indexing, drained before storage closes
 * @param lexicalErasureSweep       The chunk index's compaction timer, or null if none runs
 * @param chunkIndex                The BM25 index, or null if none opened
 * @param documentVisibilityTracker Holds the registry that says which documents this node has
 * @param graphTenants              Every open namespace graph
 * @param documentDecoder           How ingestion reads documents, whose PDF reader may hold OCR
 *                                  models; or null when no ingestion was wired
 * @param storageEngine             Storage, closed after every reader has drained
 * @param arena                     The shared arena backing the mappings, or null when
 *                                  there is none to close
 * @param shutdownTimeout           The whole shutdown's deadline, from
 *                                  {@link ShutdownBudget#configuredTimeout}
 * @author ExcaliburDB
 */
public record NodeShutdown(ExcaliburDBServer server,
                           IndexOrchestrator indexOrchestrator,
                           LexicalErasureSweep lexicalErasureSweep,
                           ChunkIndex chunkIndex,
                           DocumentVisibilityTracker documentVisibilityTracker,
                           GraphTenants graphTenants,
                           DocumentDecoder documentDecoder,
                           StorageEngine storageEngine,
                           Arena arena,
                           Duration shutdownTimeout) {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeShutdown.class);

    /**
     * Validates the components that have no absent case.
     *
     * <p>The four checked below are what a running node always has; the rest are genuinely
     * optional and their absence is a shape of node rather than a fault. A missing one of these
     * would otherwise surface as a shutdown step that threw, which is reported as a node that
     * did not stop cleanly rather than as the wiring mistake it is.</p>
     *
     * @throws NullPointerException     if the server, orchestrator, tracker, storage or timeout is
     *                                  null
     * @throws IllegalArgumentException if the timeout leaves nothing beyond the durability reserve
     */
    public NodeShutdown {
        Objects.requireNonNull(server, "server cannot be null");
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout cannot be null");
        if (shutdownTimeout.compareTo(ShutdownBudget.DURABILITY_RESERVE) <= 0) {
            throw new IllegalArgumentException("shutdownTimeout must exceed the "
                + ShutdownBudget.DURABILITY_RESERVE.toSeconds() + "s kept for the durable writes");
        }
        Objects.requireNonNull(indexOrchestrator, "indexOrchestrator cannot be null");
        Objects.requireNonNull(documentVisibilityTracker, "documentVisibilityTracker cannot be null");
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
    }

    /**
     * Takes the node apart and reports the outcome.
     *
     * <p>Every step runs even when an earlier one failed, because a step that throws must not
     * leave the arena open with the process still holding mappings.</p>
     *
     * @return true if every step completed, so nothing the node started is still running and
     *         nothing it opened is still open
     */
    public boolean run() {
        SweepState sweep = new SweepState(lexicalErasureSweep != null);
        BackgroundWork background = new BackgroundWork();
        ShutdownSequence sequence =
            sequence(sweep, background, ShutdownBudget.startingNow(shutdownTimeout));
        int failedSteps = sequence.stepNames().size() - sequence.run().size();
        Throwable catalogFailure = storageEngine.getCatalog().closeFailure();
        reportOutcome(failedSteps, catalogFailure);
        return failedSteps == 0 && catalogFailure == null;
    }

    /**
     * Whether the lexical erasure sweep is still compacting, as far as this shutdown knows.
     *
     * <p>Running until its drain says otherwise, so a drain that threw - or never ran - is read
     * the safe way: as a compaction still inside the index and, for an index that reads text
     * back, still inside storage.</p>
     */
    private static final class SweepState {

        private volatile boolean running;

        SweepState(boolean running) {
            this.running = running;
        }
    }

    /**
     * Whether the segment workers and the storage engine's own background work are still
     * running, as far as this shutdown knows.
     *
     * <p>Running until their steps say otherwise, so a step that threw is read the safe way: as
     * work still writing into storage.</p>
     */
    private static final class BackgroundWork {

        private volatile boolean workersRunning = true;
        private volatile boolean storageWorkRunning = true;

        boolean running() {
            return workersRunning || storageWorkRunning;
        }
    }

    /**
     * Lists the teardown steps in the only order that is safe.
     *
     * @param sweep      What the sweep's drain reported, filled in by its step
     * @param background What the background work's steps reported, filled in by them
     * @param budget     The deadline every wait below is cut from
     * @return The sequence, built but not yet run
     */
    private ShutdownSequence sequence(SweepState sweep, BackgroundWork background,
                                      ShutdownBudget budget) {
        return new ShutdownSequence()
            .add("stop HTTP server and drain in-flight requests", () -> server.stop(budget))
            // Once ingestion has drained, so no conversion is left to use what is released. One
            // the drain could not stop keeps its models: freeing them under a page being read
            // would end the process rather than fail the page.
            .addIfPresent("release PDF reader", documentDecoder,
                decoder -> releasePdfReader(decoder, budget))
            // The server's stop has already drained both of these when it was running, and then
            // they answer at once. They wait here only for what that stop left running, or for
            // what a server that never started did not reach; and no longer than the drains have
            // left, so the reserve stays intact. Their answers gate the storage engine's close.
            .add("stop index orchestrator",
                () -> background.workersRunning = !stopIndexOrchestrator(budget))
            // After the workers, the last thing that could start a split or a lifecycle check.
            .add("stop storage background work", () -> background.storageWorkRunning =
                !storageEngine.stopBackgroundWork(budget.forDrains()))
            // Before the index: a sweep in flight is inside a compaction of it. Told to stop,
            // never interrupted, and what it answers decides whether what it reads may close.
            // It waits only for what the drains have left, so the reserve stays intact.
            .addIfPresent("stop lexical erasure sweep", lexicalErasureSweep,
                running -> sweep.running = !running.drain(budget.forDrains()))
            // Always, whatever is still running: every chunk reached storage before it reached
            // the index, and nothing reindexes one storage holds and the index lacks, so a buffer
            // left unpublished here is lost to lexical search for good. Publishing takes the
            // same exclusion an ingestion run's refresh does, which a compaction runs alongside.
            .addIfPresent("publish chunk lexical index", chunkIndex, ChunkIndex::publishBuffered)
            .addIfPresent("close chunk lexical index", chunkIndex,
                index -> {
                    requireChunkIndexReleased(sweep);
                    index.close();
                })
            // Always, and before the registry: see syncGraphStaging.
            .addIfPresent("force staged graph facts to disk", graphTenants,
                NodeShutdown::syncGraphStaging)
            // After ingestion has stopped, so what is written is the final state rather than a
            // snapshot something was still adding to. Every operation that changes the registry
            // writes it too; this is what catches a node stopped between them.
            .add("persist document registry",
                () -> documentVisibilityTracker.getRegistry().persist())
            .addIfPresent("close graph tenants", graphTenants, tenants -> {
                requireGraphsReleased();
                // After the durable writes, so this wait may spend what they left of the reserve.
                tenants.close(budget.remaining());
            })
            .add("close storage engine", () -> {
                requireStorageReleased(sweep, background, "the storage engine");
                storageEngine.close();
            })
            .addIfPresent("release memory-mapped segments", arena, mappings -> {
                requireStorageReleased(sweep, background, "the memory-mapped segments");
                mappings.close();
            });
    }

    /**
     * Stops the segment workers within what the drains have left and, once they have ended,
     * releases the rest of what the orchestrator holds.
     *
     * <p>Not closed when a worker is still running: what the close releases beyond the workers
     * is theirs to finish with.</p>
     *
     * @param budget The shutdown's deadline
     * @return true if every worker has ended
     */
    private boolean stopIndexOrchestrator(ShutdownBudget budget) {
        if (!indexOrchestrator.shutdown(budget.forDrains())) {
            LOGGER.error("Segment workers were still running when the drains' share of the "
                + "shutdown ran out; the storage engine will be left open rather than closed "
                + "underneath them");
            return false;
        }
        // Everything it waits for has already ended, so this returns without waiting.
        indexOrchestrator.close(budget.forDrains());
        return true;
    }

    /**
     * Releases what the PDF reader holds - an OCR engine's models and threads - within what the
     * drains have left.
     *
     * @param decoder How ingestion reads documents
     * @param budget  The shutdown's deadline
     * @throws IllegalStateException if a conversion was still running at the end of the wait, so
     *                               the outcome line says the shutdown was not clean
     */
    private static void releasePdfReader(DocumentDecoder decoder, ShutdownBudget budget) {
        boolean released;
        try {
            released = decoder.release(budget.forDrains());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while releasing the PDF reader", e);
        }
        if (!released) {
            throw new IllegalStateException("left the PDF reader's OCR models loaded: a "
                + "conversion still running after the drain is using them");
        }
    }

    /**
     * Forces every open namespace's staged facts to disk, whether or not the tenants may close.
     *
     * <p>A step of its own, ahead of the gated close, because staging buffers its appends in
     * this process and closing the tenants is what would otherwise write them out - and the
     * close is refused when a build or the erasure sweep is still inside them. A process that
     * exits then loses the buffers, and the documents the facts came from are already in the
     * registry, so nothing extracts them again. The same pattern as the chunk index's buffer
     * above: publish always, release only when nothing is using it.</p>
     *
     * <p>Before the registry is persisted, because the registry is forced to the device when it
     * is written. The other order leaves a window - and after a power loss, a result - in which
     * the registry records documents whose staged facts never reached the disk.</p>
     *
     * @param tenants Every open namespace graph
     * @throws IllegalStateException if any namespace could not be forced, so the outcome line
     *                               says the shutdown was not clean
     */
    private static void syncGraphStaging(GraphTenants tenants) {
        int failed = tenants.syncStaging();
        if (failed > 0) {
            throw new IllegalStateException(failed + " namespace(s) could not force their staged "
                + "graph facts to disk; see the errors logged above");
        }
    }

    /**
     * Refuses to release the chunk index's files while something is still compacting or writing
     * it.
     *
     * <p>The buffer has already been published by then, so what a refusal costs is an index
     * whose files are not closed at exit - which the next open recovers from - rather than
     * chunks that were stored and never indexed.</p>
     *
     * @param sweep What the sweep's drain reported
     * @throws IllegalStateException if the index is still in use
     */
    private void requireChunkIndexReleased(SweepState sweep) {
        if (sweep.running || server.isChunkIndexStillInUse()) {
            throw new IllegalStateException("left the chunk lexical index open: a compaction or "
                + "a writer still running after the drain is using it; its buffered chunks were "
                + "published first");
        }
    }

    /**
     * Refuses to close the graph tenants while a graph build the drain could not stop is still
     * writing into them.
     *
     * <p>Closing a tenant unmaps the files the build writes through and closes the Lucene
     * directories it indexes into, which can end the process rather than fail the build. Left
     * open, they are released when the process exits; the refusal is a failed step, so the
     * outcome line says the shutdown was not clean.</p>
     *
     * @throws IllegalStateException if the tenants are still in use
     */
    private void requireGraphsReleased() {
        if (server.isGraphsStillInUse()) {
            throw new IllegalStateException("left the graph tenants open: a graph build still "
                + "running after the drain is writing into them");
        }
    }

    /**
     * Refuses a step that would close storage while the server reports something still using it.
     *
     * <p>A compaction whose rewrite did not stop when drained is still reading chunk text and
     * segment mappings, and closing what it reads crashes the JVM rather than throwing. Leaving
     * storage open at exit costs a replay of the write log on the next start; the refusal is a
     * failed step, so the outcome line says the shutdown was not clean.</p>
     *
     * <p>The sweep counts only for an index whose compactions read text back out of storage;
     * one still force-merging its own segments reads nothing of the storage engine's.</p>
     *
     * <p>The segment workers and the storage engine's splits and lifecycle checks count
     * whatever the server reported, because a server that never started drained neither.</p>
     *
     * @param sweep      What the sweep's drain reported
     * @param background What the background work's steps reported
     * @param what       What the step would have closed
     * @throws IllegalStateException if storage is still in use
     */
    private void requireStorageReleased(SweepState sweep, BackgroundWork background,
                                        String what) {
        boolean sweepReadsStorage = sweep.running
            && (chunkIndex == null || chunkIndex.compactionReadsStorage());
        if (sweepReadsStorage || server.isStorageStillInUse()) {
            throw new IllegalStateException("left " + what + " open: a compaction still running "
                + "after the drain is reading it, and closing it underneath the run could crash "
                + "the process");
        }
        if (background.running()) {
            throw new IllegalStateException("left " + what + " open: a segment worker, split or "
                + "lifecycle check still running after its drain is writing into it, and closing "
                + "it underneath that work could crash the process");
        }
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
