package excalibur.api.server;

import excalibur.api.endpoint.CompactionRunner;
import excalibur.api.endpoint.GraphBuildRunner;
import excalibur.api.endpoint.QueryEndpoint;
import excalibur.api.endpoint.ReindexEndpoint;
import excalibur.ingest.pipeline.IngestionOrchestrator;
import excalibur.query.pipeline.QueryPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

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
     * Drains everything the node started, in the order that keeps it off closed storage.
     *
     * <p>The listener must already have stopped accepting: this drains what the requests it
     * accepted left running, and nothing here refuses new work.</p>
     *
     * @param endpoints The endpoints that own executors, or null if {@code start()} never
     *                  reached the point of registering them
     * @param wiring    What the node is made of, for the pipelines and registries it owns
     * @throws NullPointerException if wiring is null
     */
    static void drain(EndpointRegistrar.Endpoints endpoints, ServerWiring wiring) {
        Objects.requireNonNull(wiring, "wiring cannot be null");

        drainEndpoints(endpoints, wiring.queryPipeline());
        drainCompaction(endpoints);
        drainGraphBuild(endpoints);
        drainIngestion(wiring);

        // Stop the index orchestrator after the HTTP server stops
        // This ensures no new queries are being accepted before stopping workers
        wiring.indexOrchestrator().close();
        LOGGER.info("Index orchestrator stopped");
    }

    /**
     * Drains the endpoints that own executors, and the query pipeline behind them.
     *
     * @param endpoints     What was registered, or null
     * @param queryPipeline The pipeline the query endpoint dispatches into
     */
    private static void drainEndpoints(EndpointRegistrar.Endpoints endpoints,
                                       QueryPipeline queryPipeline) {
        if (endpoints != null) {
            ReindexEndpoint reindex = endpoints.reindex();
            if (reindex != null) {
                reindex.close();
            }

            QueryEndpoint query = endpoints.query();
            if (query != null) {
                query.shutdown();
            }
        }

        // The endpoint's own shutdown stops the threads it owns - the BM25 half - and not the
        // vector pipeline's. Without this the pipeline's segment-search threads and its parallel
        // dispatcher ran on through the shutdown, into the storage engine the caller closes next.
        if (queryPipeline != null) {
            queryPipeline.shutdown();
        }
    }

    /**
     * Stops on-demand compaction and waits for a run in progress.
     *
     * <p>Before the storage engine is closed: a rewrite still running into a mapping whose
     * arena has been closed crashes the JVM rather than throwing. A run that outlives the wait
     * is reported at ERROR rather than passed over, because the next step of this shutdown is
     * exactly the thing that makes it dangerous.</p>
     *
     * @param endpoints What was registered, or null
     */
    private static void drainCompaction(EndpointRegistrar.Endpoints endpoints) {
        if (endpoints == null) {
            return;
        }
        CompactionRunner compaction = endpoints.compaction();
        if (compaction != null && !compaction.drain()) {
            LOGGER.error("A compaction was still rewriting segments when the node shut down; "
                + "the storage engine is being closed underneath it");
        }
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
     */
    private static void drainGraphBuild(EndpointRegistrar.Endpoints endpoints) {
        if (endpoints == null) {
            return;
        }
        GraphBuildRunner graphBuild = endpoints.graphBuild();
        if (graphBuild != null && !graphBuild.drain()) {
            LOGGER.error("A graph build was still running when the node shut down; the graph "
                + "tenants are being closed underneath it");
        }
    }

    /**
     * Stops background ingestion and closes the sources the registry published.
     *
     * @param wiring What the node is made of
     */
    private static void drainIngestion(ServerWiring wiring) {
        IngestionOrchestrator ingestion = wiring.ingestionOrchestrator();
        if (ingestion != null && !ingestion.shutdown()) {
            LOGGER.warn("Ingestion threads did not finish before shutdown timeout");
        }

        // The registry owns the sources it published; nothing else may close them.
        try {
            wiring.ingestSourceRegistry().close();
        } catch (Exception e) {
            LOGGER.warn("Error closing ingestion sources: {}", e.getMessage());
        }
    }
}
