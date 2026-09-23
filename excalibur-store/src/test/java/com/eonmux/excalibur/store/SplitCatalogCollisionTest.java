package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.catalog.CatalogFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a split does to the catalog at the index it publishes its right child at.
 *
 * <p>The right child of a split at width {@code w} lands at {@code leftIndex + w}, an index the
 * directory has never routed to. Nothing else in the engine may already own that catalog id:
 * a record there that belongs to a source would hand the live child its lifecycle state. Each
 * test waits for the split through {@link SplitWorker#stop()}, which joins every split
 * {@link SplitWorker#requestSplit(Segment)} dispatched.</p>
 */
@DisplayName("Split publication against the catalog")
class SplitCatalogCollisionTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("the right child gets a routing record, and later created ids stay clear of it")
    void theRightChildIsRegisteredAsARoutingSegment() throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("clean"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();
            int width = engine.getSegmentCount();

            engine.getSplitWorker().requestSplit(engine.getSegment(0L));
            engine.getSplitWorker().stop(Duration.ofSeconds(10));

            assertThat(engine.getSegmentCount()).isEqualTo(width * 2);
            SegmentCatalog.SegmentMetadata right = engine.getCatalog().get(width);
            assertThat(right.getSourceId()).isNull();
            assertThat(right.getLifecycleState().isQueryable()).isTrue();
            assertThat(engine.getQueryableSegments()).contains(engine.getSegment(width));

            long created = engine.getCatalog()
                .create("a-source", dataDir.resolve("rolling.dat")).getSegmentId();
            assertThat(created).isGreaterThanOrEqualTo(SegmentCatalog.FIRST_CREATED_SEGMENT_ID);
            assertThat(engine.getSegment(created)).isNull();
        }
    }

    @Test
    @DisplayName("a split whose right index is held by a created segment is refused untouched")
    void aSplitOntoACreatedRecordIsRefused() throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("legacy"));
        int width = StorageEngine.DEFAULT_INITIAL_SEGMENTS;
        Path rolling = dataDir.resolve("rolling.dat");

        // A persisted catalog whose create() shared its counter with the routing indices: a
        // rolling reindex was handed the id the first split of segment 0 publishes its right
        // child at, and retired it.
        SegmentCatalog.SegmentMetadata legacy = new SegmentCatalog.SegmentMetadata(width);
        legacy.setSourceId("other-source");
        legacy.setVolumePath(rolling);
        legacy.restoreLifecycleState(LifecycleState.TOMBSTONED);
        try (CatalogFile file = new CatalogFile(dataDir.resolve("catalog.dat"))) {
            file.writeSegment(legacy);
            file.flush();
        }

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();
            assertThat(engine.getSegmentCount()).isEqualTo(width);
            Segment parent = engine.getSegment(0L);

            engine.getSplitWorker().requestSplit(parent);
            engine.getSplitWorker().stop(Duration.ofSeconds(10));

            assertThat(engine.getSegmentCount())
                .as("the directory was not widened")
                .isEqualTo(width);
            assertThat(engine.getSegment(0L))
                .as("the parent still serves its slot")
                .isSameAs(parent);
            assertThat(parent.isSplitting()).isFalse();
            assertThat(parent.isClosed()).isFalse();
            assertThat(Files.exists(engine.segmentPath(width)))
                .as("no child was moved onto the refused index")
                .isFalse();

            SegmentCatalog.SegmentMetadata record = engine.getCatalog().get(width);
            assertThat(record.getSourceId()).isEqualTo("other-source");
            assertThat(record.getVolumePath()).isEqualTo(rolling);
            assertThat(record.getLifecycleState()).isEqualTo(LifecycleState.TOMBSTONED);

            byte[] key = "still-writable".getBytes();
            engine.put(key, "value".getBytes());
            assertThat(engine.get(key)).isNotNull();
        }
    }
}
