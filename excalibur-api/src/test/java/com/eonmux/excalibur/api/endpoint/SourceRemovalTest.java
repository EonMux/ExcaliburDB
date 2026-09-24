package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.reindex.ReindexCoordinator;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.shard.worker.SegmentReindexScheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Taking a source out of the registry has to undo what putting it in started.
 *
 * <p>Registering a source starts three things besides the map entry: an ingestion run, a status
 * entry, and a place in the reindex schedule. Removal used to do none of them, which nothing
 * noticed because nothing removed a source - until erasing one meant taking it out of the crawl.
 * The failures are all quiet: a node that goes on crawling a source no request can name, a
 * {@code /v1/ingest/status} entry for a source that no longer exists, and a source-not-found
 * warning every scheduling interval for the life of the process.
 */
@DisplayName("Source removal")
class SourceRemovalTest {

    private SegmentReindexScheduler scheduler;
    private IngestStatusEndpoint.IngestionStatusRegistry statuses;
    private InMemorySourceRegistry registry;

    @BeforeEach
    void setUp() {
        scheduler = mock(SegmentReindexScheduler.class);
        statuses = new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        registry = new InMemorySourceRegistry();
        registry.setStatusRegistry(statuses);
        registry.setReindexScheduler(scheduler, 3_600_000L);
    }

    private Source source(String sourceId) {
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn(sourceId);
        return source;
    }

    @Test
    @DisplayName("a removed source's reindex status is cleared with it")
    void clearsTheReindexStatus() {
        // The status outlives the source otherwise: /v1/reindex/status keeps answering for a
        // source that is gone, and the map grows for the life of the process. The ingestion
        // status is already cleared here; the reindex status describes the same source.
        ReindexEndpoint.InMemoryReindexStatusRegistry reindexes =
            new ReindexEndpoint.InMemoryReindexStatusRegistry();
        registry.setReindexStatusRegistry(reindexes);
        registry.register(source("42"));
        reindexes.startReindex("42");

        registry.remove("42");

        assertThat(reindexes.getStatus("42")).isNull();
        assertThat(reindexes.getAllStatuses()).isEmpty();
    }

    @Test
    @DisplayName("a removed source is no longer registered")
    void removesTheEntry() {
        registry.register(source("42"));

        assertThat(registry.remove("42")).isTrue();
        assertThat(registry.isRegistered("42")).isFalse();
    }

    @Test
    @DisplayName("a removed source stops being re-crawled")
    void unregistersFromTheScheduler() {
        registry.register(source("42"));
        verify(scheduler).registerSource("42", 3_600_000L);

        registry.remove("42");

        verify(scheduler).unregisterSource("42");
    }

    @Test
    @DisplayName("a removed source stops being reported by /v1/ingest/status")
    void clearsTheStatusEntry() {
        registry.register(source("42"));
        assertThat(statuses.getStatus("42")).isNotNull();

        registry.remove("42");

        assertThat(statuses.getStatus("42"))
            .as("a status entry nothing can clear outlives the source forever")
            .isNull();
        assertThat(statuses.getAllStatuses()).isEmpty();
    }

    @Test
    @DisplayName("a source named like a source is removed like any other")
    void namedIdsAreRemovable() {
        registry.register(source("project-docs"));

        assertThat(registry.remove("project-docs")).isTrue();
        assertThat(registry.isRegistered("project-docs")).isFalse();
        verify(scheduler).unregisterSource("project-docs");
    }

    @Test
    @DisplayName("removing a source that was never registered changes nothing")
    void removingAnAbsentSource() {
        assertThat(registry.remove("nothing")).isFalse();
        verify(scheduler, never()).unregisterSource(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("the source itself is closed, since the registry owns it")
    void closesTheSource() throws Exception {
        Source source = source("42");
        registry.register(source);

        registry.remove("42");

        verify(source).close();
    }

    /**
     * A reindex of the source, in flight on its own thread, that runs until it is asked to stop
     * and then does what a stopped run does: finishes the document it is on, records that it
     * was cancelled, and releases its claim.
     */
    private static final class InFlightReindex {
        final CountDownLatch asked = new CountDownLatch(1);
        final AtomicBoolean sourceClosedWhileRunning = new AtomicBoolean();
        final Thread thread;

        InFlightReindex(ReindexCoordinator coordinator,
                        ReindexEndpoint.ReindexStatusRegistry statuses,
                        AtomicBoolean sourceClosed, String sourceId, long holdMillis) {
            ReindexCoordinator.Lease lease = coordinator.tryAcquire(sourceId);
            statuses.startReindex(sourceId);
            lease.onCancel(asked::countDown);
            thread = Thread.ofVirtual().start(() -> {
                try {
                    asked.await(10, TimeUnit.SECONDS);
                    Thread.sleep(holdMillis);
                    sourceClosedWhileRunning.set(sourceClosed.get());
                    statuses.cancelReindex(sourceId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lease.release();
                }
            });
        }
    }

    private Source closeRecording(String sourceId, AtomicBoolean closed) throws Exception {
        Source source = source(sourceId);
        doAnswer(invocation -> {
            closed.set(true);
            return null;
        }).when(source).close();
        return source;
    }

    @Test
    @DisplayName("a reindex in flight is stopped, and waited for, before the source is gone")
    void stopsAndAwaitsAnInFlightReindex() throws Exception {
        // Erasure deregisters first so that nothing restores what it removes. A re-crawl left
        // running would go on writing the source's documents back after the erasure, read
        // through a source that has been closed underneath it, and put back the status entry
        // this removal clears.
        ReindexCoordinator coordinator = new ReindexCoordinator();
        ReindexEndpoint.InMemoryReindexStatusRegistry reindexes =
            new ReindexEndpoint.InMemoryReindexStatusRegistry();
        registry.setReindexStatusRegistry(reindexes);
        registry.setReindexCoordinator(coordinator);
        AtomicBoolean closed = new AtomicBoolean();
        registry.register(closeRecording("42", closed));
        InFlightReindex run = new InFlightReindex(coordinator, reindexes, closed, "42", 50);

        registry.remove("42");

        assertThat(coordinator.isRunning("42"))
            .as("removal returned only once the reindex had ended")
            .isFalse();
        assertThat(run.sourceClosedWhileRunning)
            .as("the source was closed only after the run stopped reading it")
            .isFalse();
        assertThat(closed).isTrue();
        assertThat(reindexes.getStatus("42"))
            .as("what the stopped run recorded is cleared with the source")
            .isNull();
        run.thread.join();
    }

    @Test
    @DisplayName("a reindex that outlives the bound does not hold the removal, nor come back")
    void aReindexThatWillNotStopIsNotWaitedOnForever() throws Exception {
        ReindexCoordinator coordinator = new ReindexCoordinator();
        ReindexEndpoint.InMemoryReindexStatusRegistry reindexes =
            new ReindexEndpoint.InMemoryReindexStatusRegistry();
        registry.setReindexStatusRegistry(reindexes);
        registry.setReindexCoordinator(coordinator);
        registry.setRunStopWaitMs(20);
        AtomicBoolean closed = new AtomicBoolean();
        registry.register(closeRecording("42", closed));
        InFlightReindex run = new InFlightReindex(coordinator, reindexes, closed, "42", 300);

        SourceRegistry.Removal removal = registry.removeSource("42");
        assertThat(removal.registered()).isTrue();
        assertThat(removal.stopped())
            .as("the reindex was still going when the bound ran out, and the removal says so")
            .isFalse();
        assertThat(registry.isRegistered("42")).isFalse();
        run.thread.join();

        assertThat(reindexes.getStatus("42"))
            .as("a run that ends after its source was removed cannot recreate its status")
            .isNull();
    }

    @Test
    @DisplayName("a source with no reindex running is removed without waiting")
    void nothingToWaitFor() {
        ReindexCoordinator coordinator = new ReindexCoordinator();
        registry.setReindexCoordinator(coordinator);
        registry.register(source("42"));

        assertThat(registry.remove("42")).isTrue();
    }

    @Test
    @DisplayName("an interrupted removal still removes, and keeps the interrupt")
    void anInterruptedRemoval() {
        ReindexCoordinator coordinator = new ReindexCoordinator();
        ReindexEndpoint.InMemoryReindexStatusRegistry reindexes =
            new ReindexEndpoint.InMemoryReindexStatusRegistry();
        registry.setReindexStatusRegistry(reindexes);
        registry.setReindexCoordinator(coordinator);
        registry.register(source("42"));
        ReindexCoordinator.Lease lease = coordinator.tryAcquire("42");
        reindexes.startReindex("42");

        Thread.currentThread().interrupt();
        try {
            assertThat(registry.remove("42")).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            lease.release();
        }
        assertThat(reindexes.getStatus("42")).isNull();
        assertThat(lease.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("an ingestion run is waited for before what it records is cleared")
    void waitsForTheIngestionRun() throws Exception {
        // An erasure reads the registry the moment the removal returns. A run that finished its
        // document after that would register it past the erasure, which then reports the source
        // gone while the node still serves the document.
        IngestionOrchestrator orchestrator = mock(IngestionOrchestrator.class);
        registry.setIngestionOrchestrator(orchestrator);
        Source source = source("42");
        registry.register(source);
        when(orchestrator.stopIngestion("42")).thenReturn(true);
        AtomicBoolean waitedWhileRecorded = new AtomicBoolean();
        doAnswer(invocation -> {
            waitedWhileRecorded.set(statuses.getStatus("42") != null);
            verify(source, never()).close();
            return true;
        }).when(orchestrator).stopIngestionAndAwait(eq("42"), anyLong());

        SourceRegistry.Removal removal = registry.removeSource("42");

        assertThat(removal).isEqualTo(new SourceRegistry.Removal(true, true));
        assertThat(waitedWhileRecorded)
            .as("the run was waited for while its status was still there to record into")
            .isTrue();
        assertThat(statuses.getStatus("42")).isNull();
        verify(source).close();
    }

    @Test
    @DisplayName("an ingestion run that outlives the bound is reported, and the removal stands")
    void anIngestionRunThatWillNotStop() throws Exception {
        IngestionOrchestrator orchestrator = mock(IngestionOrchestrator.class);
        registry.setIngestionOrchestrator(orchestrator);
        registry.setRunStopWaitMs(20);
        registry.register(source("42"));
        when(orchestrator.stopIngestion("42")).thenReturn(true);
        when(orchestrator.stopIngestionAndAwait(eq("42"), anyLong())).thenReturn(false);

        SourceRegistry.Removal removal = registry.removeSource("42");

        assertThat(removal.registered()).isTrue();
        assertThat(removal.stopped()).isFalse();
        assertThat(registry.remove("42"))
            .as("the source is gone all the same")
            .isFalse();
        verify(orchestrator).stopIngestionAndAwait(eq("42"), longThat(wait -> wait <= 20));
    }

    @Test
    @DisplayName("the runs under an id no registration holds are stopped and waited for too")
    void stopsTheRunsOfAnUnregisteredId() throws Exception {
        // Documents supplied directly run under an id this registry never held.
        IngestionOrchestrator orchestrator = mock(IngestionOrchestrator.class);
        registry.setIngestionOrchestrator(orchestrator);
        when(orchestrator.stopIngestion("supplied")).thenReturn(true);
        when(orchestrator.stopIngestionAndAwait(eq("supplied"), anyLong())).thenReturn(true);

        assertThat(registry.stopRuns("supplied")).isTrue();
        verify(orchestrator).stopIngestionAndAwait(eq("supplied"), anyLong());
    }

    @Test
    @DisplayName("an id with nothing in flight is not waited on")
    void stopsNothingWhenNothingRuns() throws Exception {
        IngestionOrchestrator orchestrator = mock(IngestionOrchestrator.class);
        registry.setIngestionOrchestrator(orchestrator);
        registry.setReindexCoordinator(new ReindexCoordinator());

        assertThat(registry.stopRuns("idle")).isTrue();
        verify(orchestrator, never()).stopIngestionAndAwait(eq("idle"), anyLong());
    }

    @Test
    @DisplayName("an interrupted wait reports the runs as not stopped, and keeps the interrupt")
    void anInterruptedWaitIsNotAStop() throws Exception {
        IngestionOrchestrator orchestrator = mock(IngestionOrchestrator.class);
        registry.setIngestionOrchestrator(orchestrator);
        when(orchestrator.stopIngestion("42")).thenReturn(true);
        when(orchestrator.stopIngestionAndAwait(eq("42"), anyLong()))
            .thenThrow(new InterruptedException());

        try {
            assertThat(registry.stopRuns("42")).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a negative wait is refused")
    void aNegativeWaitIsRefused() {
        assertThatThrownBy(() -> registry.setRunStopWaitMs(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
