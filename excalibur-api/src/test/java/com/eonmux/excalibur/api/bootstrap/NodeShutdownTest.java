package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.api.server.ShutdownBudget;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The order a node is taken apart in, which is the only thing about shutdown that can corrupt.
 *
 * <p>Query and reindex threads read memory-mapped segments through the shared arena. Closing
 * that arena, or the storage engine under it, while a reader is still live is a JVM crash rather
 * than an exception - so nothing in this sequence is cosmetic and nothing about it is verifiable
 * from a log line. These tests pin the order itself, and pin that a step which throws does not
 * stop the ones after it, because the step most likely to throw is the first one and the arena
 * is the last.</p>
 */
@DisplayName("Node shutdown")
class NodeShutdownTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ExcaliburDBServer server;
    private IndexOrchestrator indexOrchestrator;
    private LexicalErasureSweep sweep;
    private ChunkIndex chunkIndex;
    private DocumentVisibilityTracker tracker;
    private DocumentRegistry registry;
    private GraphTenants tenants;
    private StorageEngine storageEngine;
    private Arena arena;

    @BeforeEach
    void setUp() {
        server = mock(ExcaliburDBServer.class);
        indexOrchestrator = mock(IndexOrchestrator.class);
        sweep = mock(LexicalErasureSweep.class);
        chunkIndex = mock(ChunkIndex.class);
        tracker = mock(DocumentVisibilityTracker.class);
        registry = mock(DocumentRegistry.class);
        tenants = mock(GraphTenants.class);
        storageEngine = mock(StorageEngine.class);
        when(tracker.getRegistry()).thenReturn(registry);
        when(storageEngine.getCatalog()).thenReturn(new SegmentCatalog());
        when(sweep.drain(any(Duration.class))).thenReturn(true);
        when(chunkIndex.compactionReadsStorage()).thenReturn(true);
        arena = Arena.ofShared();
    }

    @AfterEach
    void tearDown() {
        if (arena != null) {
            try {
                arena.close();
            } catch (IllegalStateException ignored) {
                // already closed by the sequence under test
            }
            arena = null;
        }
    }

    /**
     * Builds the shutdown under test over the mocked node.
     *
     * @param withSweep      The erasure sweep, or null for a node running none
     * @param withChunkIndex The lexical index, or null for a node that opened none
     * @return The shutdown, not yet run
     */
    private NodeShutdown shutdown(LexicalErasureSweep withSweep, ChunkIndex withChunkIndex) {
        return new NodeShutdown(server, indexOrchestrator, withSweep, withChunkIndex,
            tracker, tenants, storageEngine, arena, TIMEOUT);
    }

    @Test
    @DisplayName("stops accepting, drains, persists, then closes storage and the arena")
    void runsInTheOnlySafeOrder() throws IOException {
        shutdown(sweep, chunkIndex).run();

        InOrder order = inOrder(server, indexOrchestrator, sweep, chunkIndex, registry, tenants,
            storageEngine);
        order.verify(server).stop(any(ShutdownBudget.class));
        order.verify(indexOrchestrator).close();
        // The sweep before the index it compacts: a sweep in flight is inside a compaction of it.
        order.verify(sweep).drain(any(Duration.class));
        // The index after ingestion has stopped and before storage closes: its buffer is
        // published first, and storage is where those chunks were written first.
        order.verify(chunkIndex).publishBuffered();
        order.verify(chunkIndex).close();
        // Staged facts before the registry: the registry is what stops their documents being
        // extracted again, and it is forced to the device as it is written.
        order.verify(tenants).syncStaging();
        order.verify(registry).persist();
        order.verify(tenants).close(any(Duration.class));
        order.verify(storageEngine).close();
    }

    @Test
    @DisplayName("every wait is cut from one budget, and the durable writes keep their reserve")
    void everyWaitComesOutOfOneBudget() throws IOException {
        // The listener and its drains used all of their share; the waits after them must not
        // add their own fixed minutes on top, which is what put the publish past SIGKILL.
        ArgumentCaptor<ShutdownBudget> budget = ArgumentCaptor.forClass(ShutdownBudget.class);
        org.mockito.Mockito.doAnswer(call -> {
            Thread.sleep(50);
            return null;
        }).when(server).stop(budget.capture());
        ArgumentCaptor<Duration> sweepWait = ArgumentCaptor.forClass(Duration.class);
        ArgumentCaptor<Duration> tenantsWait = ArgumentCaptor.forClass(Duration.class);
        when(sweep.drain(sweepWait.capture())).thenReturn(true);

        shutdown(sweep, chunkIndex).run();

        verify(tenants).close(tenantsWait.capture());
        assertThat(budget.getValue().isBounded()).isTrue();
        assertThat(sweepWait.getValue())
            .as("the sweep waits only for what the drains have left")
            .isLessThanOrEqualTo(TIMEOUT.minus(ShutdownBudget.DURABILITY_RESERVE));
        assertThat(tenantsWait.getValue())
            .as("the tenants' sweep waits only for what the whole shutdown has left")
            .isLessThan(TIMEOUT);
        verify(chunkIndex).publishBuffered();
        verify(registry).persist();
    }

    @Test
    @DisplayName("skips the components a node never opened")
    void skipsAbsentComponents() throws IOException {
        shutdown(null, null).run();

        verify(sweep, never()).drain(any(Duration.class));
        verify(chunkIndex, never()).publishBuffered();
        verify(chunkIndex, never()).close();
        verify(registry).persist();
        verify(storageEngine).close();
    }

    @Test
    @DisplayName("a step that throws does not strand the arena with mappings still live")
    void keepsGoingAfterAFailedStep() throws IOException {
        doThrow(new IllegalStateException("listener refused to stop"))
            .when(server).stop(any(ShutdownBudget.class));
        doThrow(new RuntimeException("registry file is unwritable")).when(registry).persist();

        shutdown(sweep, chunkIndex).run();

        verify(storageEngine).close();
        assertThatThrownBy(() -> arena.allocate(8, 8))
            .as("the arena must be closed even when earlier steps failed")
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("storage stays open while a compaction the drain could not stop still reads it")
    void leavesStorageOpenUnderARunningCompaction() throws IOException {
        when(server.isStorageStillInUse()).thenReturn(true);

        shutdown(sweep, chunkIndex).run();

        verify(storageEngine, never()).close();
        // Everything that does not pull storage out from under the run still happens.
        verify(registry).persist();
        verify(tenants).close(any(Duration.class));
        org.assertj.core.api.Assertions.assertThat(arena.allocate(8, 8))
            .as("the arena the run is reading through is still open")
            .isNotNull();
    }

    @Test
    @DisplayName("the graph tenants stay open while a build the drain could not stop writes them")
    void leavesTheGraphTenantsOpenUnderARunningBuild() throws IOException {
        when(server.isGraphsStillInUse()).thenReturn(true);

        shutdown(sweep, chunkIndex).run();

        verify(tenants, never()).close(any(Duration.class));
        // A build writes graph files, not storage: the rest of the teardown still happens.
        verify(registry).persist();
        verify(storageEngine).close();
    }

    @Test
    @DisplayName("staged graph facts reach disk even when the tenants must stay open")
    void forcesStagingUnderARunningBuild() throws IOException {
        // Closing the tenants is what would otherwise write out staging's buffers, and it is
        // refused here. The registry already records the documents those facts came from, so a
        // process exiting with them still buffered loses them for good.
        when(server.isGraphsStillInUse()).thenReturn(true);

        shutdown(sweep, chunkIndex).run();

        verify(tenants).syncStaging();
        verify(tenants, never()).close(any(Duration.class));
    }

    @Test
    @DisplayName("staging that cannot be forced fails its own step and stops no other")
    void aFailedStagingSyncIsAFailedStep() throws IOException {
        when(tenants.syncStaging()).thenReturn(1);

        shutdown(sweep, chunkIndex).run();

        verify(registry).persist();
        verify(tenants).close(any(Duration.class));
        verify(storageEngine).close();
    }

    @Test
    @DisplayName("the chunk index's buffer is published even when its files must stay open")
    void publishesTheBufferUnderARunningCompaction() throws IOException {
        // Chunks reach storage before the index, and nothing reindexes one the index was never
        // told about: refusing to publish along with refusing to close would lose them.
        when(server.isChunkIndexStillInUse()).thenReturn(true);

        shutdown(sweep, chunkIndex).run();

        verify(chunkIndex).publishBuffered();
        verify(chunkIndex, never()).close();
        verify(storageEngine).close();
    }

    @Test
    @DisplayName("a sweep still compacting an index that reads storage keeps storage and the "
        + "index open")
    void aSweepThatDidNotDrainKeepsStorageOpen() throws IOException {
        when(sweep.drain(any(Duration.class))).thenReturn(false);

        shutdown(sweep, chunkIndex).run();

        verify(chunkIndex).publishBuffered();
        verify(chunkIndex, never()).close();
        verify(storageEngine, never()).close();
        org.assertj.core.api.Assertions.assertThat(arena.allocate(8, 8)).isNotNull();
    }

    @Test
    @DisplayName("a sweep still force-merging an index that keeps its own text leaves storage "
        + "free to close")
    void aSweepThatReadsNoStorageDoesNotHoldStorage() throws IOException {
        when(sweep.drain(any(Duration.class))).thenReturn(false);
        when(chunkIndex.compactionReadsStorage()).thenReturn(false);

        shutdown(sweep, chunkIndex).run();

        verify(chunkIndex, never()).close();
        verify(storageEngine).close();
    }

    @Test
    @DisplayName("a sweep whose drain threw is taken to be still running")
    void aSweepThatThrewIsStillRunning() throws IOException {
        when(sweep.drain(any(Duration.class)))
            .thenThrow(new IllegalStateException("scheduler refused"));

        shutdown(sweep, chunkIndex).run();

        verify(chunkIndex).publishBuffered();
        verify(chunkIndex, never()).close();
        verify(storageEngine, never()).close();
    }

    @Test
    @DisplayName("a buffer that cannot be published does not keep the index or storage open")
    void aFailedPublishStillReleases() throws IOException {
        doThrow(new IOException("disk full")).when(chunkIndex).publishBuffered();

        shutdown(sweep, chunkIndex).run();

        verify(chunkIndex).close();
        verify(storageEngine).close();
    }

    @Test
    @DisplayName("a catalog that did not close durably is reported, not swallowed")
    void reportsAStaleCatalog() throws IOException {
        // The next start re-processes write-log ranges it had already integrated, so this is the
        // one shutdown outcome an operator must not have to infer from the absence of a line.
        SegmentCatalog catalog = mock(SegmentCatalog.class);
        when(catalog.closeFailure()).thenReturn(new IllegalStateException("fsync failed"));
        when(storageEngine.getCatalog()).thenReturn(catalog);

        shutdown(sweep, chunkIndex).run();

        verify(catalog).closeFailure();
        verify(storageEngine).close();
    }

    @Test
    @DisplayName("refuses to be built without the components every running node has")
    void refusesMissingComponents() {
        assertThatThrownBy(() -> new NodeShutdown(null, indexOrchestrator, null, null, tracker,
            tenants, storageEngine, arena, TIMEOUT))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("server");
        assertThatThrownBy(() -> new NodeShutdown(server, indexOrchestrator, null, null, null,
            tenants, storageEngine, arena, TIMEOUT))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("documentVisibilityTracker");
        assertThatThrownBy(() -> new NodeShutdown(server, indexOrchestrator, null, null, tracker,
            tenants, null, arena, TIMEOUT))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("storageEngine");
    }

    @Test
    @DisplayName("refuses a deadline with nothing left over after the durable writes")
    void refusesADeadlineInsideTheReserve() {
        // Found when the node is wired rather than when it is asked to stop: a shutdown that
        // cannot even build its budget would skip every step, the durable writes included.
        assertThatThrownBy(() -> new NodeShutdown(server, indexOrchestrator, null, null, tracker,
            tenants, storageEngine, arena, ShutdownBudget.DURABILITY_RESERVE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("durable writes");
        assertThatThrownBy(() -> new NodeShutdown(server, indexOrchestrator, null, null, tracker,
            tenants, storageEngine, arena, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("shutdownTimeout");
    }

    @Test
    @DisplayName("a node with no arena still runs every step of the shutdown")
    void runsWithoutAnArena() throws IOException {
        // There is no arena when the node fell back to ByteBuffer-backed segments, which
        // -Dcom.eonmux.excalibur.useFallback=true selects and a failed Panama probe forces. A bound method
        // reference on a null receiver throws while the argument list is being evaluated, before
        // the step is even registered - so the whole sequence would fail to build and not one
        // step would run. That is the shutdown where the damage is permanent: the listener never
        // stops, the registry is never persisted, and storage never closes durably.
        new NodeShutdown(server, indexOrchestrator, sweep, chunkIndex, tracker, tenants,
            storageEngine, null, TIMEOUT).run();

        InOrder order = inOrder(server, indexOrchestrator, registry, storageEngine);
        order.verify(server).stop(any(ShutdownBudget.class));
        order.verify(indexOrchestrator).close();
        order.verify(registry).persist();
        order.verify(storageEngine).close();
    }
}
