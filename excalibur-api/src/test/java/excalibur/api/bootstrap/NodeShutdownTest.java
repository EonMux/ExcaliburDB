package excalibur.api.bootstrap;

import excalibur.api.server.ExcaliburDBServer;
import excalibur.ingest.lexical.ChunkIndex;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import excalibur.shard.IndexOrchestrator;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
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
            tracker, tenants, storageEngine, arena, 30);
    }

    @Test
    @DisplayName("stops accepting, drains, persists, then closes storage and the arena")
    void runsInTheOnlySafeOrder() throws IOException {
        shutdown(sweep, chunkIndex).run();

        InOrder order = inOrder(server, indexOrchestrator, sweep, chunkIndex, registry, tenants,
            storageEngine);
        order.verify(server).stop(30);
        order.verify(indexOrchestrator).close();
        // The sweep before the index it compacts: a sweep in flight is inside a compaction of it.
        order.verify(sweep).close();
        // The index after ingestion has stopped and before storage closes: closing it publishes
        // what was still buffered, and storage is where those chunks were written first.
        order.verify(chunkIndex).close();
        order.verify(registry).persist();
        order.verify(tenants).close();
        order.verify(storageEngine).close();
    }

    @Test
    @DisplayName("skips the components a node never opened")
    void skipsAbsentComponents() throws IOException {
        shutdown(null, null).run();

        verify(sweep, never()).close();
        verify(chunkIndex, never()).close();
        verify(registry).persist();
        verify(storageEngine).close();
    }

    @Test
    @DisplayName("a step that throws does not strand the arena with mappings still live")
    void keepsGoingAfterAFailedStep() throws IOException {
        doThrow(new IllegalStateException("listener refused to stop"))
            .when(server).stop(anyInt());
        doThrow(new RuntimeException("registry file is unwritable")).when(registry).persist();

        shutdown(sweep, chunkIndex).run();

        verify(storageEngine).close();
        assertThatThrownBy(() -> arena.allocate(8, 8))
            .as("the arena must be closed even when earlier steps failed")
            .isInstanceOf(IllegalStateException.class);
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
            tenants, storageEngine, arena, 30))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("server");
        assertThatThrownBy(() -> new NodeShutdown(server, indexOrchestrator, null, null, null,
            tenants, storageEngine, arena, 30))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("documentVisibilityTracker");
        assertThatThrownBy(() -> new NodeShutdown(server, indexOrchestrator, null, null, tracker,
            tenants, null, arena, 30))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("storageEngine");
    }

    @Test
    @DisplayName("a node with no arena still runs every step of the shutdown")
    void runsWithoutAnArena() throws IOException {
        // There is no arena when the node fell back to ByteBuffer-backed segments, which
        // -Dexcalibur.useFallback=true selects and a failed Panama probe forces. A bound method
        // reference on a null receiver throws while the argument list is being evaluated, before
        // the step is even registered - so the whole sequence would fail to build and not one
        // step would run. That is the shutdown where the damage is permanent: the listener never
        // stops, the registry is never persisted, and storage never closes durably.
        new NodeShutdown(server, indexOrchestrator, sweep, chunkIndex, tracker, tenants,
            storageEngine, null, 30).run();

        InOrder order = inOrder(server, indexOrchestrator, registry, storageEngine);
        order.verify(server).stop(30);
        order.verify(indexOrchestrator).close();
        order.verify(registry).persist();
        order.verify(storageEngine).close();
    }
}
