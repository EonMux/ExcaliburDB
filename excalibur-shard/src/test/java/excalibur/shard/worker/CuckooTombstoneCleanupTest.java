package excalibur.shard.worker;

import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Retiring a segment has to take its terms out of the filter.
 *
 * <p>The filter answers whether a segment holds a term, and a term left behind for a segment that
 * is gone routes every query matching it to a segment that cannot answer. A segment is cleaned
 * once, because a Cuckoo delete removes the first slot whose fingerprint matches and repeating it
 * would evict a live term that collides - so which segments count as cleaned decides both whether
 * the filter is ever repaired and whether the log is telling the truth about it.</p>
 */
@DisplayName("Tombstoned segment filter cleanup")
class CuckooTombstoneCleanupTest {

    @TempDir
    Path tempDir;

    private SegmentCatalog catalog;
    private StorageEngine storageEngine;
    private List<Long> cleaned;
    private CuckooMaintenanceWorker worker;

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog();
        storageEngine = mock(StorageEngine.class);
        cleaned = new ArrayList<>();
        worker = new CuckooMaintenanceWorker(catalog, storageEngine, cleaned::add);
    }

    private void tombstone(long segmentId) {
        var metadata = catalog.add(segmentId, tempDir.resolve("segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
        metadata.transitionToTombstoned();
    }

    @Test
    @DisplayName("a segment the engine has not loaded is tried again, not written off")
    void anUnloadedSegmentIsRetried() {
        tombstone(7L);
        when(storageEngine.getSegment(7L)).thenReturn(null);

        worker.processTombstonedSegments();

        assertThat(worker.getTombstonedSegmentsProcessed())
            .as("nothing was removed from the filter, so nothing was processed")
            .isZero();
        assertThat(cleaned).isEmpty();
    }

    @Test
    @DisplayName("a segment that loads on a later cycle is cleaned then")
    void aSegmentThatArrivesLateIsCleaned() throws Exception {
        tombstone(7L);
        when(storageEngine.getSegment(7L)).thenReturn(null);
        worker.processTombstonedSegments();

        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("segment7"), 1024L * 1024L, arena)) {
            when(storageEngine.getSegment(7L)).thenReturn(segment);

            worker.processTombstonedSegments();

            assertThat(worker.getTombstonedSegmentsProcessed()).isEqualTo(1);
            assertThat(cleaned).containsExactly(7L);
        }
    }

    @Test
    @DisplayName("a segment already cleaned is not cleaned twice")
    void aCleanedSegmentIsNotRepeated() throws Exception {
        tombstone(7L);
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("segment7"), 1024L * 1024L, arena)) {
            when(storageEngine.getSegment(7L)).thenReturn(segment);

            worker.processTombstonedSegments();
            worker.processTombstonedSegments();

            assertThat(worker.getTombstonedSegmentsProcessed())
                .as("a Cuckoo delete repeated would evict a live term that collides")
                .isEqualTo(1);
            assertThat(cleaned).containsExactly(7L);
        }
    }
}
