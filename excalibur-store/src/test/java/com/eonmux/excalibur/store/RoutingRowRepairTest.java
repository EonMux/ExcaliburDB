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
 * That start-up gives a routing segment back its own catalog row when a source's record holds it.
 *
 * <p>A store whose {@code create()} shared a counter with the routing indices can have published
 * a split child at an index a rolling reindex had already been given. The child then carries the
 * source's record - its source id and, when the reindex fell back, TOMBSTONED - so it answers no
 * query while writes keep landing in it. Each test builds a real split, closes the engine, and
 * rewrites the catalog row on disk the way such a store holds it.</p>
 */
@DisplayName("Routing rows held by created records")
class RoutingRowRepairTest {

    private static final int KEYS = 2_000;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a split child registered under a source's tombstoned record is queryable again")
    void aCollidedRowIsReplacedAtStart() throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("collided"));
        int width = StorageEngine.DEFAULT_INITIAL_SEGMENTS;
        long rightIndex = width;

        Path rightPath;
        long checkpoint;
        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();
            populate(engine);
            engine.getSplitWorker().requestSplit(engine.getSegment(0L));
            engine.getSplitWorker().stop(Duration.ofSeconds(10));
            assertThat(engine.getSegmentCount()).isEqualTo(width * 2);

            rightPath = engine.getSegment(rightIndex).path();
            assertThat(engine.getSegment(rightIndex).entryCount()).isPositive();
            checkpoint = engine.getCatalog().get(rightIndex).getLastIntegratedOffset();
        }

        SegmentCatalog.SegmentMetadata collided = new SegmentCatalog.SegmentMetadata(rightIndex);
        collided.setSourceId("other-source");
        collided.setVolumePath(rightPath);
        collided.setCreationTime(System.currentTimeMillis());
        collided.setLastIntegratedOffset(checkpoint);
        collided.restoreLifecycleState(LifecycleState.TOMBSTONED);
        collided.setTombstoneEpoch(3L);
        writeRow(dataDir, collided);

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();

            Segment right = engine.getSegment(rightIndex);
            SegmentCatalog.SegmentMetadata row = engine.getCatalog().get(rightIndex);
            assertThat(row.getSourceId()).isNull();
            assertThat(row.getLifecycleState()).isEqualTo(LifecycleState.ACTIVE);
            assertThat(row.getEntryCount()).isEqualTo(right.entryCount());
            assertThat(row.getVolumePath()).isEqualTo(right.path());
            assertThat(row.getTombstoneEpoch()).isNegative();
            assertThat(row.getLastIntegratedOffset())
                .as("the checkpoint the routing segment advanced is kept")
                .isEqualTo(checkpoint);
            assertThat(engine.getQueryableSegments()).contains(right);
            assertThat(engine.captureSnapshot().segments()).contains(right);
        }

        // Persisted: a further start reads the clean row back and has nothing to repair.
        try (CatalogFile file = new CatalogFile(dataDir.resolve("catalog.dat"))) {
            SegmentCatalog.SegmentMetadata persisted = file.readSegment(rightIndex);
            assertThat(persisted.getSourceId()).isNull();
            assertThat(persisted.getLifecycleState()).isEqualTo(LifecycleState.ACTIVE);
        }
        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();
            assertThat(engine.getCatalog().get(rightIndex).getLifecycleState())
                .isEqualTo(LifecycleState.ACTIVE);
            assertThat(engine.getQueryableSegments()).contains(engine.getSegment(rightIndex));
        }
    }

    @Test
    @DisplayName("a routing segment that was itself tombstoned stays tombstoned")
    void aTombstonedRoutingSegmentIsLeftAlone() throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("tombstoned"));
        long retired = 5L;

        long created;
        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();
            populate(engine);
            SegmentCatalog catalog = engine.getCatalog();
            catalog.completeIndexing(retired);
            catalog.completeWarmup(retired);
            engine.tombstoneSegment(retired);

            created = catalog.create("a-source", dataDir.resolve("rolling")).getSegmentId();
            catalog.beginIndexing(created);
            catalog.tombstone(created, 0L);
        }

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();

            SegmentCatalog.SegmentMetadata row = engine.getCatalog().get(retired);
            assertThat(row.getLifecycleState()).isEqualTo(LifecycleState.TOMBSTONED);
            assertThat(row.getSourceId()).isNull();
            assertThat(engine.getQueryableSegments()).doesNotContain(engine.getSegment(retired));

            SegmentCatalog.SegmentMetadata source = engine.getCatalog().get(created);
            assertThat(source.getSourceId()).isEqualTo("a-source");
            assertThat(source.getLifecycleState()).isEqualTo(LifecycleState.TOMBSTONED);
        }
    }

    private static void populate(StorageEngine engine) {
        for (int i = 0; i < KEYS; i++) {
            engine.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
        }
    }

    private static void writeRow(Path dataDir, SegmentCatalog.SegmentMetadata row)
            throws Exception {
        try (CatalogFile file = new CatalogFile(dataDir.resolve("catalog.dat"))) {
            file.writeSegment(row);
            file.flush();
        }
    }
}
