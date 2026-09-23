package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a segment ID names somewhere writes can actually land.
 *
 * <p>Two things allocate segment IDs. The storage engine's routing directory holds a
 * power-of-two number of slots, at most {@code int}-wide, and writes reach a segment by index.
 * {@link SegmentCatalog#create} draws from a range that starts above every index such a directory
 * can hold ({@link SegmentCatalog#FIRST_CREATED_SEGMENT_ID}) - so it hands back IDs that name
 * metadata and no storage, however far the directory has split.</p>
 *
 * <p>A caller that discovers this by writing finds out one chunk at a time, after paying to fetch,
 * chunk and embed each of them. Asking first is one map lookup.</p>
 */
@DisplayName("Segment write targets")
class StorageChunkWriterTargetTest {

    @Test
    @DisplayName("a catalog-created segment is reported as unwritable rather than failing per chunk")
    void aCatalogCreatedSegmentIsNotAWriteTarget(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();

            StorageChunkWriter writer = new StorageChunkWriter(engine);

            long routed = 0L;
            assertThat(engine.getSegment(routed)).isNotNull();
            assertThat(writer.canWriteTo(routed))
                .as("a routing index the engine holds a segment at is a write target")
                .isTrue();

            SegmentCatalog.SegmentMetadata created =
                engine.getCatalog().create("a-source", dataDir.resolve("rolling.dat"));
            long catalogId = created.getSegmentId();

            assertThat(engine.getSegment(catalogId))
                .as("created ids lie above every routing index, so this names no segment")
                .isNull();
            assertThat(writer.canWriteTo(catalogId))
                .as("and it must be reported as unwritable before any chunk is embedded for it")
                .isFalse();
        }
    }

    @Test
    @DisplayName("after a split, a catalog-created segment still names no segment and is not writable")
    void aCreatedSegmentStaysClearOfASplitDirectory(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("split"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();

            int widthBefore = engine.getSegmentCount();
            engine.getSplitWorker().requestSplit(engine.getSegment(0L));
            // Joins every split requestSplit dispatched, so the split has been published or
            // refused by the time this returns.
            engine.getSplitWorker().stop(java.time.Duration.ofSeconds(30));
            assertThat(engine.getSegmentCount())
                .as("the split must publish before the rest of the test means anything")
                .isEqualTo(widthBefore * 2);

            long rightChildIndex = widthBefore;
            assertThat(engine.getCatalog().get(rightChildIndex).getSourceId())
                .as("the right child's catalog record is its own, not a source's")
                .isNull();

            StorageChunkWriter writer = new StorageChunkWriter(engine);
            long catalogId = engine.getCatalog()
                .create("a-source", dataDir.resolve("rolling.dat")).getSegmentId();

            assertThat(catalogId)
                .as("a created id never lands on an index a split publishes at")
                .isGreaterThanOrEqualTo(SegmentCatalog.FIRST_CREATED_SEGMENT_ID);
            assertThat(engine.getSegment(catalogId))
                .as("the doubled directory does not reach it either")
                .isNull();
            assertThat(engine.getSegments()).doesNotContainKey(catalogId);
            assertThat(writer.canWriteTo(catalogId)).isFalse();
        }
    }
}
