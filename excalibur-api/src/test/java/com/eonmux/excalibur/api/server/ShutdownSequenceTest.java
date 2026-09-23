package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.endpoint.CompactionRunner;
import com.eonmux.excalibur.api.endpoint.GraphBuildRunner;
import com.eonmux.excalibur.api.endpoint.QueryEndpoint;
import com.eonmux.excalibur.api.endpoint.ReindexEndpoint;
import com.eonmux.excalibur.api.endpoint.SourceRegistry;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a node's drain reports is what its caller closes storage on.
 *
 * <p>The caller closes the storage engine, its arena and the chunk index's files straight after
 * this returns, and closing a mapping under a running reader takes the JVM down rather than
 * throwing. So every drain that can leave work running has to be heard in the answer - an
 * ingestion run that outlived its wait, a request thread still compacting - and a step that
 * throws must neither skip the steps after it nor read as a clean drain.</p>
 *
 * <p>Each drain holds only what the work it waits for uses: a reindex, an ingestion run, a BM25
 * query and a request thread hold storage and the chunk index; the vector pipeline and the
 * segment workers hold storage; a graph build holds the graph tenants.</p>
 */
@DisplayName("Shutdown sequence")
class ShutdownSequenceTest {

    private static final Duration GRACE = Duration.ofSeconds(10);

    private ServerWiring wiring;
    private IngestionOrchestrator ingestion;
    private IndexOrchestrator indexOrchestrator;
    private SourceRegistry sources;
    private ReindexEndpoint reindex;
    private CompactionRunner compaction;
    private GraphBuildRunner graphBuild;
    private QueryEndpoint query;
    private QueryPipeline pipeline;
    private EndpointRegistrar.Endpoints endpoints;

    /** A node's default budget, started now: far more than any drain here needs. */
    private static ShutdownBudget budget() {
        return ShutdownBudget.startingNow(
            Duration.ofSeconds(ShutdownBudget.DEFAULT_TIMEOUT_SECONDS));
    }

    @BeforeEach
    void setUp() {
        wiring = mock(ServerWiring.class);
        ingestion = mock(IngestionOrchestrator.class);
        indexOrchestrator = mock(IndexOrchestrator.class);
        sources = mock(SourceRegistry.class);
        when(wiring.ingestionOrchestrator()).thenReturn(ingestion);
        when(wiring.indexOrchestrator()).thenReturn(indexOrchestrator);
        when(wiring.ingestSourceRegistry()).thenReturn(sources);
        pipeline = mock(QueryPipeline.class);
        when(wiring.queryPipeline()).thenReturn(pipeline);
        when(pipeline.shutdown()).thenReturn(true);
        when(ingestion.shutdown(any())).thenReturn(true);
        when(indexOrchestrator.shutdown()).thenReturn(true);

        reindex = mock(ReindexEndpoint.class);
        compaction = mock(CompactionRunner.class);
        graphBuild = mock(GraphBuildRunner.class);
        when(reindex.drain(any())).thenReturn(true);
        when(compaction.drain(any())).thenReturn(CompactionRunner.Drained.CLEAN);
        when(graphBuild.drain(any())).thenReturn(true);
        query = mock(QueryEndpoint.class);
        when(query.shutdown()).thenReturn(true);
        endpoints = new EndpointRegistrar.Endpoints(query, reindex, compaction, graphBuild, null);
    }

    @Test
    @DisplayName("everything drained reads as clean")
    void aCleanDrain() {
        assertThat(ShutdownSequence.drain(endpoints, wiring, null, GRACE, budget()))
            .isEqualTo(ShutdownSequence.Drained.CLEAN);
    }

    @Test
    @DisplayName("a node that never registered its endpoints still drains what it wired")
    void withoutEndpoints() {
        assertThat(ShutdownSequence.drain(null, wiring, null, GRACE, budget()))
            .isEqualTo(ShutdownSequence.Drained.CLEAN);
        verify(ingestion).shutdown(any());
        verify(indexOrchestrator).close();
    }

    @Test
    @DisplayName("ingestion still running holds storage, the chunk index and the graph tenants")
    void ingestionStillRunning() {
        // Every document a run publishes is staged into its namespace's graph, and the report
        // cannot fail the document: tenants closed under a run still going lose those facts
        // with nothing but a log line to show for it.
        when(ingestion.shutdown(any())).thenReturn(false);

        assertThat(ShutdownSequence.drain(endpoints, wiring, null, GRACE, budget()))
            .isEqualTo(new ShutdownSequence.Drained(false, false, false));
    }

    @Test
    @DisplayName("a reindex still running holds storage and the chunk index")
    void reindexStillRunning() {
        when(reindex.drain(any())).thenReturn(false);

        assertThat(ShutdownSequence.drain(endpoints, wiring, null, GRACE, budget()))
            .isEqualTo(new ShutdownSequence.Drained(false, false, true));
    }

    @Test
    @DisplayName("a compaction holding only the chunk index leaves storage free")
    void compactionHoldingOnlyTheIndex() {
        when(compaction.drain(any())).thenReturn(new CompactionRunner.Drained(true, false));

        assertThat(ShutdownSequence.drain(endpoints, wiring, null, GRACE, budget()))
            .isEqualTo(new ShutdownSequence.Drained(true, false, true));
    }

    @Test
    @DisplayName("query work still running holds storage and the chunk index")
    void queryWorkStillRunning() {
        when(query.shutdown()).thenReturn(false);

        assertThat(ShutdownSequence.drain(endpoints, wiring, null, GRACE, budget()))
            .isEqualTo(new ShutdownSequence.Drained(false, false, true));
    }

    @Test
    @DisplayName("a segment search still running holds storage alone")
    void segmentSearchStillRunning() {
        when(pipeline.shutdown()).thenReturn(false);

        assertThat(ShutdownSequence.drain(endpoints, wiring, null, GRACE, budget()))
            .isEqualTo(new ShutdownSequence.Drained(false, true, true));
    }

    @Test
    @DisplayName("segment workers still running hold storage alone, and are not closed")
    void segmentWorkersStillRunning() {
        when(indexOrchestrator.shutdown()).thenReturn(false);

        assertThat(ShutdownSequence.drain(endpoints, wiring, null, GRACE, budget()))
            .isEqualTo(new ShutdownSequence.Drained(false, true, true));
        verify(indexOrchestrator, never()).close();
    }

    @Test
    @DisplayName("a graph build still running holds the graph tenants alone")
    void graphBuildStillRunning() {
        when(graphBuild.drain(any())).thenReturn(false);

        assertThat(ShutdownSequence.drain(endpoints, wiring, null, GRACE, budget()))
            .isEqualTo(new ShutdownSequence.Drained(true, true, false));
    }

    @Test
    @DisplayName("a step that throws does not skip the rest, and is rethrown")
    void aThrowingStepStillRunsTheRest() {
        doThrow(new IllegalStateException("reindex executor is wedged"))
            .when(reindex).drain(any());

        assertThatThrownBy(() -> ShutdownSequence.drain(endpoints, wiring, null, GRACE,
                budget()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("reindex executor is wedged");
        verify(compaction).drain(any());
        verify(graphBuild).drain(any());
        verify(ingestion).shutdown(any());
        verify(indexOrchestrator).close();
    }

    @Test
    @DisplayName("a compaction drain that throws is taken to hold everything")
    void aThrowingCompactionDrainHoldsEverything() {
        // The throw is rethrown, and the server keeps its pessimistic flags; the rest still ran.
        when(compaction.drain(any())).thenThrow(new IllegalStateException("drain failed"));

        assertThatThrownBy(() -> ShutdownSequence.drain(endpoints, wiring, null, GRACE,
                budget()))
            .hasMessageContaining("drain failed");
        verify(ingestion).shutdown(any());
    }

    @Test
    @DisplayName("a request thread still running after every other drain holds storage")
    void aRequestStillRunning() throws Exception {
        ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        requests.execute(() -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            assertThat(ShutdownSequence.drain(endpoints, wiring, requests, Duration.ofMillis(1),
                budget()))
                .isEqualTo(new ShutdownSequence.Drained(false, false, false));
            assertThat(requests.isShutdown()).as("no new request is dispatched").isTrue();
        } finally {
            release.countDown();
        }
        assertThat(requests.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("request threads that have ended drain clean")
    void finishedRequestsDrain() {
        ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();

        assertThat(ShutdownSequence.drain(endpoints, wiring, requests, GRACE, budget()))
            .isEqualTo(ShutdownSequence.Drained.CLEAN);
        assertThat(requests.isTerminated()).isTrue();
    }

    @Test
    @DisplayName("independent drains wait together, so the slowest sets the time, not the sum")
    void independentDrainsRunTogether() {
        // The reindex's drain cannot finish until the graph build's has started. Run one after
        // the other, the reindex would wait out its whole bound first and report itself still
        // running; run together, both finish.
        CountDownLatch graphBuildStarted = new CountDownLatch(1);
        when(reindex.drain(any()))
            .thenAnswer(call -> graphBuildStarted.await(10, TimeUnit.SECONDS));
        when(graphBuild.drain(any())).thenAnswer(call -> {
            graphBuildStarted.countDown();
            return true;
        });

        assertThat(ShutdownSequence.drain(endpoints, wiring, null, GRACE,
            budget()))
            .isEqualTo(ShutdownSequence.Drained.CLEAN);
    }

    @Test
    @DisplayName("a drain still going when the drains' share runs out is left running, and "
        + "reported so, without holding up the rest")
    void aDrainPastTheBudgetIsReportedNotWaitedOut() {
        CountDownLatch release = new CountDownLatch(1);
        when(reindex.drain(any())).thenAnswer(call -> release.await(60, TimeUnit.SECONDS));
        // One second for the drains, the rest held back for the durable writes.
        ShutdownBudget budget = ShutdownBudget.startingNow(
            ShutdownBudget.DURABILITY_RESERVE.plusSeconds(1));
        long started = System.nanoTime();

        try {
            assertThat(ShutdownSequence.drain(endpoints, wiring, null, GRACE, budget))
                .as("the reindex still holds storage and the chunk index; the graph build ended")
                .isEqualTo(new ShutdownSequence.Drained(false, false, true));
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("the drain's own minute is not waited out once the budget says stop")
                .isLessThan(ShutdownBudget.DURABILITY_RESERVE);
            assertThat(budget.remaining())
                .as("the reserve is still there for the writes that follow")
                .isPositive();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("the request drain is cut to what the budget has left")
    void theRequestDrainIsBounded() throws Exception {
        ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch release = new CountDownLatch(1);
        requests.execute(() -> {
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ShutdownBudget budget = ShutdownBudget.startingNow(
            ShutdownBudget.DURABILITY_RESERVE.plusSeconds(1));
        long started = System.nanoTime();

        try {
            assertThat(ShutdownSequence.drain(endpoints, wiring, requests, Duration.ofMinutes(1),
                budget))
                .isEqualTo(new ShutdownSequence.Drained(false, false, false));
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(ShutdownBudget.DURABILITY_RESERVE);
        } finally {
            release.countDown();
        }
        assertThat(requests.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("each drain is handed what the budget leaves the drains, however long that is")
    void eachDrainWaitsWhatTheBudgetAllows() {
        // A clock that stands still, so what the drains are handed is exactly the budget less
        // its reserve: a hundred-second budget gives each ninety, past any wait of its own.
        Duration total = Duration.ofSeconds(100);
        ShutdownBudget budget = new ShutdownBudget(total, () -> 0L);
        Duration forDrains = total.minus(ShutdownBudget.DURABILITY_RESERVE);

        assertThat(ShutdownSequence.drain(endpoints, wiring, null, GRACE, budget))
            .isEqualTo(ShutdownSequence.Drained.CLEAN);

        verify(reindex).drain(forDrains);
        verify(compaction).drain(forDrains);
        verify(graphBuild).drain(forDrains);
        verify(ingestion).shutdown(forDrains);
    }

    @Test
    @DisplayName("a drain that starts late is handed only what is left")
    void aLateDrainIsHandedWhatIsLeft() {
        // Each read of the clock moves it a second on, so every drain reads a budget that has
        // already been spent into; none is handed the whole share.
        AtomicLong now = new AtomicLong();
        ShutdownBudget budget = new ShutdownBudget(Duration.ofSeconds(100),
            () -> now.getAndAdd(TimeUnit.SECONDS.toNanos(1)));
        Duration whole = Duration.ofSeconds(100).minus(ShutdownBudget.DURABILITY_RESERVE);
        ArgumentCaptor<Duration> handed = ArgumentCaptor.forClass(Duration.class);

        ShutdownSequence.drain(endpoints, wiring, null, GRACE, budget);

        verify(reindex).drain(handed.capture());
        assertThat(handed.getValue()).isPositive().isLessThan(whole);
    }

    @Test
    @DisplayName("an erasure compacting on a request thread is asked to stop")
    void asksTheErasureToStop() {
        DocumentErasureService erasure = mock(DocumentErasureService.class);
        when(wiring.documentErasure()).thenReturn(erasure);

        ShutdownSequence.askRequestsToStop(wiring);

        verify(erasure).requestStop();
    }

    @Test
    @DisplayName("a node without erasure has nothing to ask")
    void withoutErasure() {
        ShutdownSequence.askRequestsToStop(wiring);

        assertThatThrownBy(() -> ShutdownSequence.askRequestsToStop(null))
            .isInstanceOf(NullPointerException.class);
    }
}
