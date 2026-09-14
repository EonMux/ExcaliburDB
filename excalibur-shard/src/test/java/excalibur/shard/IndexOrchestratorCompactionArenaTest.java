package excalibur.shard;

import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where the memory compaction allocates into comes from.
 *
 * <p>Compaction builds a replacement segment and hands it to the storage engine, which serves it
 * from then on. That segment is mapped out of an {@link Arena}, and it stays readable only for
 * as long as that arena is open - so the arena has to outlive not just the compaction, and not
 * just this orchestrator, but every reader of every segment compaction ever produced. The
 * storage engine's own arena is held to exactly that contract; one this orchestrator minted for
 * itself would have no one who could say when it was safe to close, which is a leak if it is
 * never closed and a use-after-free if it ever is.</p>
 *
 * <p>So the orchestrator borrows an arena or it goes without. Going without costs background
 * tombstone cleanup, which is a slower reclaim of dead space, not a wrong answer.</p>
 */
@DisplayName("Compaction arena ownership")
class IndexOrchestratorCompactionArenaTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;

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

    @Nested
    @DisplayName("When the storage engine has an arena")
    class WithArena {

        @Test
        @DisplayName("compaction runs, allocating out of the engine's arena")
        void compactionIsAvailable() {
            arena = Arena.ofShared();
            storageEngine = new StorageEngine(tempDir, arena);
            storageEngine.start();
            orchestrator = new IndexOrchestrator(storageEngine.getCatalog(), storageEngine);

            assertThat(orchestrator.getCompactionWorker()).isNotNull();
        }
    }

    @Nested
    @DisplayName("When the storage engine has none")
    class WithoutArena {

        private IndexOrchestrator withoutArena() {
            StorageEngine engine = mock(StorageEngine.class);
            when(engine.arena()).thenReturn(null);
            return new IndexOrchestrator(mock(SegmentCatalog.class), engine);
        }

        @Test
        @DisplayName("there is no compaction worker, rather than one over an arena nobody owns")
        void compactionIsUnavailable() {
            orchestrator = withoutArena();

            assertThat(orchestrator.getCompactionWorker())
                .as("an arena minted here could never be closed safely, so none is minted")
                .isNull();
        }

        @Test
        @DisplayName("the orchestrator still starts, stops and closes")
        void theLifecycleStillRuns() {
            orchestrator = withoutArena();

            assertThatCode(() -> {
                orchestrator.start();
                orchestrator.stop();
                orchestrator.close();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a run reports the segments it cannot compact as failures, not as skips")
        void compactionOnDemandReportsFailuresRatherThanSkips() {
            StorageEngine engine = mock(StorageEngine.class);
            when(engine.arena()).thenReturn(null);
            when(engine.getSegments()).thenReturn(Map.of(0L, mock(Segment.class)));
            orchestrator = new IndexOrchestrator(mock(SegmentCatalog.class), engine);

            CompactionReport report = orchestrator.compactNow(true);

            assertThat(report.segmentsConsidered()).isEqualTo(1);
            assertThat(report.segmentsFailed())
                .as("the caller asked for every segment to be rewritten and none could be")
                .isEqualTo(1);
            assertThat(report.segmentsSkipped())
                .as("a skip means the policy declined a segment, which nothing here did")
                .isZero();
            assertThat(report.hasFailures()).isTrue();
        }

        @Test
        @DisplayName("segments still register and unregister, minus the compaction registration")
        void segmentsStillRegister() {
            orchestrator = withoutArena();
            Segment segment = mock(Segment.class);

            assertThatCode(() -> {
                orchestrator.registerSegment(1L, segment, null, null, null);
                orchestrator.unregisterSegment(1L);
            }).doesNotThrowAnyException();
        }
    }
}
