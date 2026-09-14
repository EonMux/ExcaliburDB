package excalibur.query.pipeline;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryResponse;
import excalibur.query.executor.SegmentQueryTask;
import excalibur.query.visibility.EmptyResultValidator;
import excalibur.query.visibility.HnswGraphDiagnostic;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.hnsw.HnswGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Explains a query that came back with nothing, when the explanation is worth the cost.
 *
 * <p>Empty results are only acceptable when no documents match the query vector, so an
 * unexpected empty response is diagnosed rather than passed over. The diagnosis walks whole
 * graphs, which is why it is both rate limited and kept off the thread completing the
 * response.</p>
 */
final class EmptyResultDiagnostics {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmptyResultDiagnostics.class);

    /**
     * Minimum interval between two runs of the empty-result diagnostics.
     */
    private static final long EMPTY_RESULT_DIAGNOSTIC_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final StorageEngine storageEngine;
    private final VisibilityTracker visibilityTracker;
    private final QueryPipeline.HnswGraphProvider graphProvider;

    /**
     * Supplies the executor the graph walk is handed to, resolved per use so that the
     * pipeline's executor is still created lazily and a request arriving after shutdown is
     * refused by it rather than silently running the walk inline.
     */
    private final Supplier<Executor> executor;

    /**
     * Timestamp (nanoTime) after which empty-result diagnostics may run again.
     *
     * <p>The diagnostics walk whole graphs, so they are rate limited: a query storm that
     * legitimately returns nothing must not turn every response into a full graph scan.</p>
     */
    private final AtomicLong nextEmptyResultDiagnosticNanos = new AtomicLong(Long.MIN_VALUE);

    EmptyResultDiagnostics(StorageEngine storageEngine,
                           VisibilityTracker visibilityTracker,
                           QueryPipeline.HnswGraphProvider graphProvider,
                           Supplier<Executor> executor) {
        this.storageEngine = storageEngine;
        this.visibilityTracker = visibilityTracker;
        this.graphProvider = graphProvider;
        this.executor = executor;
    }

    /**
     * Validates that empty results are legitimate and logs diagnostic information.
     *
     * <p>This method enforces the domain correctness constraint that empty results
     * should only be returned when no documents match the query vector.</p>
     *
     * <p>The diagnostics scan graphs, so they are rate limited to at most one run per
     * {@link #EMPTY_RESULT_DIAGNOSTIC_INTERVAL_NANOS}. Without that bound a workload
     * whose queries legitimately match nothing would attach a full graph walk to every
     * response, on the response thread and outside the query's own timeout.</p>
     *
     * @param context The pipeline context
     * @param coverage The computed index coverage
     * @param response The query response (for validation)
     */
    void validateAndLogEmptyResults(PipelineContext context, IndexCoverage coverage, QueryResponse response) {
        if (!acquireEmptyResultDiagnosticSlot()) {
            LOGGER.debug("Query returned empty results; diagnostics rate limited");
            return;
        }

        // Use EmptyResultValidator to diagnose the cause of empty results
        // Adapt the HnswGraphProvider interface using a lambda
        EmptyResultValidator.ValidationResult validationResult =
            EmptyResultValidator.validate(
                response,
                context.request,
                storageEngine,
                visibilityTracker,
                context.segmentResults,
                graphProvider::getGraph  // Adapt to EmptyResultValidator.HnswGraphProvider
            );

        if (validationResult.isLegitimate()) {
            LOGGER.debug("Query returned empty results (legitimate): {}", validationResult.oneLineSummary());
            return;
        }

        // Log at WARN level for unexpected empty results to help diagnose issues
        LOGGER.warn("Query returned empty results unexpectedly - potential bug detected%n{}",
            validationResult.diagnosticSummary());

        scheduleGraphDiagnostics(context);
    }

    /**
     * Reserves the next diagnostic slot, returning false when one ran too recently.
     */
    private boolean acquireEmptyResultDiagnosticSlot() {
        long now = System.nanoTime();
        long next = nextEmptyResultDiagnosticNanos.get();
        if (next != Long.MIN_VALUE && now - next < 0) {
            return false;
        }
        return nextEmptyResultDiagnosticNanos.compareAndSet(
            next, now + EMPTY_RESULT_DIAGNOSTIC_INTERVAL_NANOS);
    }

    /**
     * Runs the HNSW graph diagnostic off the query path.
     *
     * <p>{@code runFullDiagnostic} walks the whole graph and verifies its invariants,
     * which is unbounded relative to graph size. It must never run on the thread that is
     * completing a query response, so it is handed to the pipeline executor and its result
     * is logged whenever it finishes.</p>
     *
     * @param context The pipeline context whose segment results triggered the diagnostic
     */
    private void scheduleGraphDiagnostics(PipelineContext context) {
        List<Long> segmentsToDiagnose = new ArrayList<>();
        for (SegmentQueryTask.SegmentQueryResult segmentResult : context.segmentResults) {
            if (segmentResult.success() && segmentResult.results().isEmpty()) {
                segmentsToDiagnose.add(segmentResult.segmentId());
            }
        }

        if (segmentsToDiagnose.isEmpty()) {
            return;
        }

        try {
            executor.get().execute(() -> runGraphDiagnostics(segmentsToDiagnose));
        } catch (RuntimeException e) {
            // Executor shut down mid-flight; diagnostics are best-effort
            LOGGER.debug("Could not schedule HNSW diagnostics: {}", e.getMessage());
        }
    }

    /**
     * Runs the HNSW graph diagnostic for each segment that returned nothing despite
     * holding active nodes, logging what it finds.
     *
     * @param segmentIds The segments to diagnose
     */
    private void runGraphDiagnostics(List<Long> segmentIds) {
        for (long segmentId : segmentIds) {
            HnswGraph graph = graphProvider.getGraph(segmentId);
            if (graph == null || graph.activeNodeCount() <= 0) {
                continue;
            }

            LOGGER.warn("Segment {} has {} active nodes but search returned empty - running HNSW diagnostic",
                segmentId, graph.activeNodeCount());

            try {
                HnswGraphDiagnostic.GraphDiagnosticResult graphDiagnostic =
                    HnswGraphDiagnostic.runFullDiagnostic(graph, 0);

                LOGGER.warn("HNSW diagnostic for segment {}: {}", segmentId, graphDiagnostic.summary());

                for (HnswGraphDiagnostic.GraphDiagnosticIssue issue
                        : graphDiagnostic.issues()) {
                    LOGGER.warn("  Issue: {}", issue);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to run HNSW diagnostic for segment {}: {}", segmentId, e.getMessage());
            }
        }
    }
}
