package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.endpoint.CompactionRunner;
import com.eonmux.excalibur.api.endpoint.GraphBuildRunner;
import com.eonmux.excalibur.api.endpoint.QueryEndpoint;
import com.eonmux.excalibur.api.endpoint.ReindexEndpoint;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The order everything behind the listener is stopped in, and why it is that order.
 *
 * <p>A node's shutdown is not a list of {@code close()} calls in any convenient sequence. The
 * storage engine's mappings live in an arena the caller closes once this returns, and a
 * background task still writing into a mapping whose arena has been closed takes the JVM down
 * rather than throwing. Everything that can still reach storage - the query pipeline's own
 * threads, a compaction rewrite, an ingestion run, the segment workers - therefore has to be
 * drained first, and each of those is owned by a different collaborator.</p>
 *
 * <p>Written here rather than inside {@code stop()} because the ordering is the whole content:
 * on its own it reads as a sequence with a reason attached to each step, where interleaved with
 * the listener's own teardown it reads as housekeeping and invites a reordering that crashes
 * the process under load and never in a test.</p>
 *
 * <h2>Nothing is interrupted</h2>
 * <p>Every drain here asks and waits; none interrupts. The work being drained writes, forces,
 * grows or maps files through channels other threads share - the storage engine's write logs,
 * the chunk index, the graph stores - and an interrupt that reaches a thread inside one of those
 * operations closes the channel for every thread using it. What a drain cannot stop in time is
 * reported instead, and the caller leaves open whatever that work is still using.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and static. The server calls it from inside its synchronized {@code stop()},
 * after the compare-and-set that makes the node not running, so one shutdown runs at a time.</p>
 *
 * @author ExcaliburDB
 */
final class ShutdownSequence {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShutdownSequence.class);

    private ShutdownSequence() {
    }

    /**
     * Asks the work that runs on request threads to end early, before the listener's grace period.
     *
     * <p>Called before the listener stops, because the listener's grace period is the time it
     * gives in-flight requests to finish, and an erasure made with {@code compact: true} is a
     * request that rewrites the lexical index on its own thread - reading chunk text back out of
     * storage for as long as that takes. Told now, it ends at its next boundary and answers with
     * its erasure still owed, inside the grace period rather than after it.</p>
     *
     * @param wiring What the node is made of
     * @throws NullPointerException if wiring is null
     */
    static void askRequestsToStop(ServerWiring wiring) {
        Objects.requireNonNull(wiring, "wiring cannot be null");
        DocumentErasureService erasure = wiring.documentErasure();
        if (erasure != null) {
            erasure.requestStop();
        }
    }

    /**
     * Drains everything the node started, in the order that keeps it off closed storage.
     *
     * <p>The listener must already have stopped accepting: this drains what the requests it
     * accepted left running, and nothing here refuses new work.</p>
     *
     * <p>Every step runs even when an earlier one throws, and a step that throws counts as one
     * that left its work running: a compaction nobody asked to stop because the step before it
     * failed is still reading storage, and the answer has to say so. The first failure is
     * rethrown once every step has had its turn.</p>
     *
     * @param endpoints    The endpoints that own executors, or null if {@code start()} never
     *                     reached the point of registering them
     * @param wiring       What the node is made of, for the pipelines and registries it owns
     * @param requests     The executor the listener dispatched requests on, or null
     * @param requestGrace How long request threads still running once everything else has
     *                     drained are given to end
     * @return What is still running, as storage, the chunk index and the graph tenants see it
     * @throws NullPointerException if wiring or requestGrace is null
     */
    static Drained drain(EndpointRegistrar.Endpoints endpoints, ServerWiring wiring,
                         ExecutorService requests, Duration requestGrace) {
        Objects.requireNonNull(wiring, "wiring cannot be null");
        Objects.requireNonNull(requestGrace, "requestGrace cannot be null");

        Steps steps = new Steps();
        boolean reindexDrained = steps.run(() -> drainReindex(endpoints));
        boolean queriesDrained = steps.run(() -> drainQueryEndpoint(endpoints));
        boolean pipelineDrained = steps.run(() -> drainQueryPipeline(wiring.queryPipeline()));
        CompactionRunner.Drained compaction = steps.call(() -> drainCompaction(endpoints),
            new CompactionRunner.Drained(false, false));
        boolean graphBuildDrained = steps.run(() -> drainGraphBuild(endpoints));
        boolean ingestionDrained = steps.run(() -> drainIngestion(wiring));
        // After the listener has stopped and the endpoints have drained, so no new query or
        // write can hand the workers anything more.
        boolean orchestratorDrained = steps.run(() -> drainIndexOrchestrator(wiring));

        // Last, so that everything that could end a request thread early - the stop asked of
        // synchronous ingestion, the drained query executor, the erasure's stop request - has
        // already been delivered.
        boolean requestsDrained = steps.run(() -> drainRequests(requests, requestGrace));

        // Who holds what. A reindex, an ingestion run, a BM25 query and a request thread each
        // reach both storage and the chunk index; the vector pipeline and the segment workers
        // reach storage alone; a graph build, a request thread and an ingestion run the graph
        // tenants - the last because every document it publishes is staged into its namespace's
        // graph, and a run still going stages into whatever the shutdown closes next. The
        // graph sink cannot fail the document it is reporting, so a tenant closed under it
        // costs that document's facts for good rather than an error anyone sees.
        boolean indexUsersDrained =
            reindexDrained && queriesDrained && ingestionDrained && requestsDrained;
        Drained drained = new Drained(
            compaction.storageReleased() && indexUsersDrained && pipelineDrained
                && orchestratorDrained,
            compaction.chunkIndexReleased() && indexUsersDrained,
            graphBuildDrained && requestsDrained && ingestionDrained);
        steps.rethrow();
        return drained;
    }

    /**
     * What the drain left running, as the three things a shutdown closes afterwards see it.
     *
     * @param storageReleased    Whether nothing is still reading or writing the storage engine
     *                           or its mappings; false means both must stay open
     * @param chunkIndexReleased Whether nothing is still reading, compacting or writing the
     *                           chunk index; false means its files must stay open
     * @param graphsReleased     Whether nothing is still building into, staging into or reading
     *                           the graph tenants; false means they must stay open
     */
    record Drained(boolean storageReleased, boolean chunkIndexReleased,
                   boolean graphsReleased) {

        /** Nothing was left running. */
        static final Drained CLEAN = new Drained(true, true, true);
    }

    /**
     * Runs drain steps so that one throwing does not stop the rest, remembering the first failure.
     */
    private static final class Steps {

        private RuntimeException failure;

        /** A step that answers whether it drained; a step that throws did not. */
        boolean run(java.util.function.BooleanSupplier step) {
            try {
                return step.getAsBoolean();
            } catch (RuntimeException e) {
                record(e);
                return false;
            }
        }

        /** A step that answers with a value; a step that throws answers {@code ifFailed}. */
        <T> T call(java.util.function.Supplier<T> step, T ifFailed) {
            try {
                return step.get();
            } catch (RuntimeException e) {
                record(e);
                return ifFailed;
            }
        }

        private void record(RuntimeException e) {
            LOGGER.error("A shutdown drain step failed; what it drains is treated as still running",
                e);
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }

        void rethrow() {
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * Waits for the request threads the listener dispatched on.
     *
     * <p>The listener's own stop waits for exchanges only up to its grace period and then stops
     * waiting, not the threads: a handler still running - an erasure's compaction, a synchronous
     * ingestion - goes on reading and writing storage on a thread nothing else tracks. Waited on
     * here, and reported, so storage is not closed underneath one.</p>
     *
     * @param requests The listener's executor, or null if it never started
     * @param grace    How long to wait
     * @return true if no request thread is still running
     */
    private static boolean drainRequests(ExecutorService requests, Duration grace) {
        if (requests == null) {
            return true;
        }
        requests.shutdown();
        try {
            if (requests.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (requests.isTerminated()) {
                return true;
            }
        }
        LOGGER.error("Requests were still running {}ms after every other drain; the storage "
            + "engine will be left open rather than closed underneath them", grace.toMillis());
        return false;
    }

    /**
     * Stops reindexing, asking what is running to stop, and waits for it.
     *
     * @param endpoints What was registered, or null
     * @return false if a reindex outlived its drain and is still writing into storage
     */
    private static boolean drainReindex(EndpointRegistrar.Endpoints endpoints) {
        ReindexEndpoint reindex = endpoints == null ? null : endpoints.reindex();
        if (reindex != null && !reindex.drain()) {
            LOGGER.error("A reindex was still running when the node shut down; the storage "
                + "engine and the chunk index will be left open rather than closed underneath it");
            return false;
        }
        return true;
    }

    /**
     * Waits for the query work the query endpoint runs on its own executor - the BM25 half.
     *
     * @param endpoints What was registered, or null
     * @return false if query work outlived the wait and is still reading storage and the index
     */
    private static boolean drainQueryEndpoint(EndpointRegistrar.Endpoints endpoints) {
        QueryEndpoint query = endpoints == null ? null : endpoints.query();
        if (query != null && !query.shutdown()) {
            LOGGER.error("Query work was still running when the node shut down; the storage "
                + "engine and the chunk index will be left open rather than closed underneath it");
            return false;
        }
        return true;
    }

    /**
     * Waits for the vector pipeline's own segment-search threads and parallel dispatcher.
     *
     * <p>The endpoint's shutdown stops the threads it owns and not these. Without this the
     * pipeline's threads ran on through the shutdown, into the storage engine the caller closes
     * next.</p>
     *
     * @param queryPipeline The pipeline the query endpoint dispatches into, or null
     * @return false if a segment search outlived the wait and is still reading storage
     */
    private static boolean drainQueryPipeline(QueryPipeline queryPipeline) {
        if (queryPipeline != null && !queryPipeline.shutdown()) {
            LOGGER.error("Segment searches were still running when the node shut down; the "
                + "storage engine will be left open rather than closed underneath them");
            return false;
        }
        return true;
    }

    /**
     * Stops the segment workers - integration, warming, compaction, splits, reindex scheduling -
     * and waits for them.
     *
     * @param wiring What the node is made of
     * @return false if a worker outlived the wait and is still writing into storage
     */
    private static boolean drainIndexOrchestrator(ServerWiring wiring) {
        IndexOrchestrator orchestrator = wiring.indexOrchestrator();
        if (!orchestrator.shutdown()) {
            // Not closed: what close() releases beyond the workers is theirs to finish with.
            LOGGER.error("Segment workers were still running when the node shut down; the "
                + "storage engine will be left open rather than closed underneath them");
            return false;
        }
        orchestrator.close();
        LOGGER.info("Index orchestrator stopped");
        return true;
    }

    /**
     * Stops on-demand compaction and waits for a run in progress.
     *
     * <p>Before the storage engine is closed: a rewrite still running into a mapping whose
     * arena has been closed crashes the JVM rather than throwing. The drain asks the run to stop
     * and waits for it; one that outlives the wait is reported, and reported to the caller as
     * well as the log, because the next step of this shutdown is exactly the thing that makes it
     * dangerous - the caller leaves storage open rather than closing it underneath the run.</p>
     *
     * @param endpoints What was registered, or null
     * @return What the compaction left running
     */
    private static CompactionRunner.Drained drainCompaction(EndpointRegistrar.Endpoints endpoints) {
        if (endpoints == null || endpoints.compaction() == null) {
            return CompactionRunner.Drained.CLEAN;
        }
        CompactionRunner.Drained drained = endpoints.compaction().drain();
        if (!drained.storageReleased()) {
            LOGGER.error("A compaction was still reading storage when the node shut down; "
                + "the storage engine will be left open rather than closed underneath it");
        } else if (!drained.chunkIndexReleased()) {
            LOGGER.error("A compaction of the chunk index was still running when the node shut "
                + "down; the index's files will be left open rather than closed underneath it");
        }
        return drained;
    }

    /**
     * Stops the graph build in progress and waits for it.
     *
     * <p>Before the graph tenants are closed: closing a tenant unmaps the files a build is
     * writing through and closes the Lucene directories it is indexing into. The build is
     * asked to stop rather than interrupted, because it publishes by renaming a directory over
     * the served one, and an interrupt inside that window leaves a namespace serving
     * nothing.</p>
     *
     * @param endpoints What was registered, or null
     * @return false if a build outlived the wait and is still writing into a graph tenant
     */
    private static boolean drainGraphBuild(EndpointRegistrar.Endpoints endpoints) {
        GraphBuildRunner graphBuild = endpoints == null ? null : endpoints.graphBuild();
        if (graphBuild != null && !graphBuild.drain()) {
            LOGGER.error("A graph build was still running when the node shut down; the graph "
                + "tenants will be left open rather than closed underneath it");
            return false;
        }
        return true;
    }

    /**
     * Stops background ingestion and closes the sources the registry published.
     *
     * @param wiring What the node is made of
     * @return false if an ingestion run outlived the drain and is still writing into storage,
     *         the chunk index and the graph tenants
     */
    private static boolean drainIngestion(ServerWiring wiring) {
        IngestionOrchestrator ingestion = wiring.ingestionOrchestrator();
        boolean drained = true;
        if (ingestion != null && !ingestion.shutdown()) {
            LOGGER.error("Ingestion threads did not finish before the shutdown timeout; the "
                + "storage engine, the chunk index and the graph tenants will be left open "
                + "rather than closed underneath them");
            drained = false;
        }

        // The registry owns the sources it published; nothing else may close them.
        try {
            wiring.ingestSourceRegistry().close();
        } catch (Exception e) {
            LOGGER.warn("Error closing ingestion sources: {}", e.getMessage());
        }
        return drained;
    }
}
