package excalibur.api.bootstrap;

import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a segment ID names somewhere writes can actually land.
 *
 * <p>Two things allocate segment IDs. The storage engine's routing directory holds a fixed,
 * power-of-two number of slots and writes reach a segment by index. {@link SegmentCatalog#create}
 * draws from its own counter, which continues past that width - so it hands back IDs that name
 * metadata and no storage.</p>
 *
 * <p>A caller that discovers this by writing finds out one chunk at a time, after paying to fetch,
 * chunk and embed each of them. Asking first is one map lookup.</p>
 *
 * <p>Once the directory has doubled, the question stops being whether the ID resolves at all. The
 * upper half of a split directory is filled with buddy pointers, so an ID past every canonical
 * index still resolves - to a segment already routing other keys. A write target has to be a
 * segment the engine holds under that ID, not merely one reachable through it.</p>
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
                .as("the catalog's counter runs past the routing width, so this names no segment")
                .isNull();
            assertThat(writer.canWriteTo(catalogId))
                .as("and it must be reported as unwritable before any chunk is embedded for it")
                .isFalse();

            // The behaviour the answer stands in for, so the two cannot drift apart.
            assertThat(writer.writeChunkWithOffset(catalogId, "chunk-1", "text", new float[]{0.1f}))
                .as("every write to it fails, one chunk at a time")
                .isEqualTo(StorageChunkWriter.WRITE_FAILED);
        }
    }

    @Test
    @DisplayName("a writer bound to a segment answers for that segment, not the one asked about")
    void aBoundWriterAnswersForItsOwnTarget(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("bound"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();

            StorageChunkWriter writer = new StorageChunkWriter(engine);
            long unwritable = engine.getCatalog()
                .create("a-source", dataDir.resolve("rolling.dat")).getSegmentId();

            // Every write through this view goes to the bound segment whatever it is handed, so
            // its reachability is the only one that decides whether a write can succeed.
            var bound = writer.forSegment(unwritable);
            assertThat(bound.canWriteTo(0L))
                .as("the bound target is unwritable, whichever segment the caller names")
                .isFalse();

            assertThat(writer.forSegment(0L).canWriteTo(unwritable))
                .as("and writable when the bound target is, for the same reason")
                .isTrue();
        }
    }

    @Test
    @DisplayName("a catalog id resolving to another segment's buddy slot is not a write target")
    void aBuddySlotIsNotAWriteTarget(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("split"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();

            int widthBefore = engine.getSegmentCount();
            engine.getSplitWorker().requestSplit(engine.getSegment(0L));
            awaitRoutingWidth(engine, widthBefore * 2);

            StorageChunkWriter writer = new StorageChunkWriter(engine);
            SegmentCatalog.SegmentMetadata created =
                engine.getCatalog().create("a-source", dataDir.resolve("rolling.dat"));
            long catalogId = created.getSegmentId();

            // The split reserved its children's IDs in the catalog, so the counter is past every
            // canonical index - and the doubled directory resolves that ID anyway.
            assertThat(engine.getSegment(catalogId))
                .as("the directory resolves it, which is why a null check calls it writable")
                .isNotNull();
            assertThat(engine.getSegments())
                .as("but the engine holds no segment of its own under that ID")
                .doesNotContainKey(catalogId);

            assertThat(writer.canWriteTo(catalogId))
                .as("filling it writes one source's data into a segment routing unrelated keys, "
                    + "and retires that source's real segments in favour of it")
                .isFalse();
        }
    }

    /**
     * Waits for a requested split to be published, which happens on its own thread.
     *
     * @param engine The engine whose routing directory is doubling
     * @param target The width to wait for
     */
    private static void awaitRoutingWidth(StorageEngine engine, int target)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (engine.getSegmentCount() < target && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(engine.getSegmentCount())
            .as("the split must publish before the rest of the test means anything")
            .isEqualTo(target);
    }
}
