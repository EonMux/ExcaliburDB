package excalibur.api.bootstrap;

import io.excalibur.retrieval.lexical.native_.SegmentManifest;
import io.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The epoch a restarted node has to resume at.
 *
 * <p>Epochs are stamped into durable records and compared against a counter held in memory. A
 * counter that restarts below the stamps already on disk reads the entire corpus as written in
 * the future and answers every query with nothing, while reporting itself healthy - and reads a
 * segment tombstoned in the past as still live, which puts erased documents back in front of
 * callers. Both directions are silent, so the floor is tested rather than trusted.</p>
 */
@DisplayName("Durable epoch floor")
class DurableEpochFloorTest {

    @Nested
    @DisplayName("A store that has never been written to")
    class FreshStart {

        @Test
        @DisplayName("resumes at zero")
        void nothingStampedYet(@TempDir Path dataDir) {
            assertThat(DurableEpochFloor.resumeEpoch(dataDir, new SegmentCatalog()))
                .isZero();
        }

        @Test
        @DisplayName("resumes at zero when the lexical directory exists but holds no segments")
        void anEmptyManifestStampsNothing(@TempDir Path dataDir) throws IOException {
            Path lexical = Files.createDirectories(
                dataDir.resolve(ChunkIndexFactory.NATIVE_DIRECTORY));
            SegmentManifest.empty().write(lexical);

            assertThat(DurableEpochFloor.resumeEpoch(dataDir, new SegmentCatalog())).isZero();
        }
    }

    @Nested
    @DisplayName("A store carrying stamps from an earlier run")
    class Resume {

        @Test
        @DisplayName("clears the newest epoch the lexical manifest published at")
        void pastThePublishedLexicalSegments(@TempDir Path dataDir) throws IOException {
            writeManifest(dataDir, new SegmentManifest.Entry("seg-0", 4_000, 3, 0),
                new SegmentManifest.Entry("seg-1", 9_100, 5, 1));

            assertThat(DurableEpochFloor.resumeEpoch(dataDir, new SegmentCatalog()))
                .as("a segment published at 9100 is invisible to any reader below it")
                .isEqualTo(9_101);
        }

        @Test
        @DisplayName("clears the newest epoch a segment was created at")
        void pastTheCatalogsCreationEpochs(@TempDir Path dataDir) {
            SegmentCatalog catalog = new SegmentCatalog();
            catalog.registerFile(7L, dataDir).setCreationEpoch(2_500);

            assertThat(DurableEpochFloor.resumeEpoch(dataDir, catalog)).isEqualTo(2_501);
        }

        @Test
        @DisplayName("clears the epoch a segment was tombstoned at")
        void pastTheCatalogsTombstoneEpochs(@TempDir Path dataDir) {
            // Below the tombstone epoch the segment reads as still visible, so the documents it
            // holds come back from an erasure that was already honoured.
            SegmentCatalog catalog = new SegmentCatalog();
            catalog.registerFile(7L, dataDir).setTombstoneEpoch(6_400);

            assertThat(DurableEpochFloor.resumeEpoch(dataDir, catalog)).isEqualTo(6_401);
        }

        @Test
        @DisplayName("takes the highest stamp across every store, not the first one it reads")
        void theHighestOfAllOfThem(@TempDir Path dataDir) throws IOException {
            writeManifest(dataDir, new SegmentManifest.Entry("seg-0", 4_000, 3, 0));
            SegmentCatalog catalog = new SegmentCatalog();
            catalog.registerFile(7L, dataDir).setCreationEpoch(11_000);
            catalog.registerFile(8L, dataDir).setTombstoneEpoch(2_000);

            assertThat(DurableEpochFloor.resumeEpoch(dataDir, catalog)).isEqualTo(11_001);
        }
    }

    @Nested
    @DisplayName("A manifest it cannot read")
    class Unreadable {

        @Test
        @DisplayName("contributes nothing rather than failing the start")
        void malformedManifestIsNotFatal(@TempDir Path dataDir) throws IOException {
            // The same read fails when the lexical store opens, so a store that cannot be counted
            // here cannot be queried either and has no epoch anyone can be blind to.
            Path lexical = Files.createDirectories(
                dataDir.resolve(ChunkIndexFactory.NATIVE_DIRECTORY));
            Files.writeString(lexical.resolve("manifest"),
                "excalibur-lexical-store v1\nnot-a-segment-line\n", StandardCharsets.UTF_8);

            SegmentCatalog catalog = new SegmentCatalog();
            catalog.registerFile(7L, dataDir).setCreationEpoch(300);

            assertThat(DurableEpochFloor.resumeEpoch(dataDir, catalog)).isEqualTo(301);
        }
    }

    private static void writeManifest(Path dataDir, SegmentManifest.Entry... entries)
            throws IOException {
        Path lexical = Files.createDirectories(
            dataDir.resolve(ChunkIndexFactory.NATIVE_DIRECTORY));
        SegmentManifest manifest = SegmentManifest.empty();
        for (SegmentManifest.Entry entry : entries) {
            manifest.add(entry);
        }
        manifest.write(lexical);
    }
}
