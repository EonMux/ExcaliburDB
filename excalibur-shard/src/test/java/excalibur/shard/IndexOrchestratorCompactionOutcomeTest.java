package excalibur.shard;

import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.store.WriteLog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * What the orchestrator tells the compaction worker it did with a swap.
 *
 * <p>The worker installs the compacted file at the canonical segment name before this listener
 * runs, and then retires whichever segment the listener says is unreachable. That answer is the
 * whole of the worker's knowledge: told a swap was installed when it was not, it closes the
 * segment the storage directory is still routing to, and every read and write to that key range
 * fails until the process is restarted. Told nothing was installed when the directory has
 * already moved on, it leaves two segments open with nothing reading either.</p>
 */
@DisplayName("Compaction swap outcomes")
class IndexOrchestratorCompactionOutcomeTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        storageEngine = new StorageEngine(tempDir, arena);
        storageEngine.start();
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    @DisplayName("a segment the directory no longer holds is retired with its replacement")
    void aSegmentTheDirectoryNoLongerHoldsIsNotCountedAsCompacted() throws IOException {
        orchestrator = new IndexOrchestrator(storageEngine.getCatalog(), storageEngine);
        Segment orphan = orphanSegment();

        boolean compacted = orchestrator.getCompactionWorker().compactNow(orphan, true);

        assertThat(compacted)
            .as("nothing routes to either segment, so no space was returned to anyone")
            .isFalse();
        assertThat(orchestrator.getCompactionWorker().getCompactionCount())
            .as("a rewrite the registry never took is not a compaction the operator can count on")
            .isZero();
        assertThat(orphan.isClosed()).isTrue();
    }

    @Test
    @DisplayName("bookkeeping that fails after the swap does not undo the swap")
    void bookkeepingThatFailsAfterTheSwapStillReportsItInstalled() throws IOException {
        SegmentCatalog catalog = spy(storageEngine.getCatalog());
        doThrow(new IllegalStateException("catalog unavailable"))
            .when(catalog).completeCompaction(anyLong());
        orchestrator = new IndexOrchestrator(catalog, storageEngine);

        Segment original = storageEngine.getSegment(0L);
        original.put("keep".getBytes(StandardCharsets.UTF_8),
            "value".getBytes(StandardCharsets.UTF_8));
        orchestrator.registerSegment(0L, original, null, null, null);

        boolean compacted = orchestrator.getCompactionWorker().compactNow(original, true);

        // The directory routes to the replacement from the moment replaceSegment returns, which
        // makes the original unreachable whatever happens next. Reporting a refusal because a
        // later registration failed would keep it open for the life of the process, serving
        // nobody, while the reconciliation sweep repairs the registrations that did not land.
        assertThat(compacted).isTrue();
        assertThat(storageEngine.getSegment(0L)).isNotSameAs(original);
        assertThat(original.isClosed()).isTrue();
    }

    /**
     * Creates a segment the storage engine's routing directory has never heard of.
     *
     * @return An open segment holding one live entry and one tombstone
     * @throws IOException if the segment or its write log cannot be created
     */
    private Segment orphanSegment() throws IOException {
        Segment orphan = new Segment(tempDir.resolve("orphan_0.dat"), 1024 * 1024, arena);
        orphan.attachWriteLog(new WriteLog(tempDir.resolve("orphan_0.wal")));
        orphan.put("keep".getBytes(StandardCharsets.UTF_8),
            "value".getBytes(StandardCharsets.UTF_8));
        orphan.put("drop".getBytes(StandardCharsets.UTF_8),
            "value".getBytes(StandardCharsets.UTF_8));
        orphan.delete("drop".getBytes(StandardCharsets.UTF_8));
        return orphan;
    }
}
