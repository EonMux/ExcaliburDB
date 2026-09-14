package excalibur.shard.allocator;

import io.excalibur.store.StorageEngine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Turning {@code excalibur.shard.volumes} into a placement the storage engine can use.
 *
 * <p>The volume list is a list of strings an operator typed, and a directory has many
 * spellings: a relative path, a path with {@code .} or {@code ..} in it, a symlink to the same
 * mount. Two spellings of one directory registered as two volumes give the consistent hash ring
 * two ring positions backed by one disk. Nothing fails - segments are placed, found and served
 * exactly as before - but that disk now takes twice the share the operator sized it for, and
 * the only evidence is a distribution that does not match the configuration.</p>
 */
@DisplayName("Volume placement installation")
class VolumePlacementsTest {

    @TempDir
    Path tempDir;

    /**
     * Runs an action against a freshly built, unstarted engine over a new data directory.
     *
     * @param action What to do with the engine
     * @throws IOException if the data directory cannot be created
     */
    private void withEngine(Consumer<StorageEngine> action) throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        StorageEngine engine = new StorageEngine(dataDir);
        try {
            action.accept(engine);
        } finally {
            engine.close();
        }
    }

    /**
     * Creates a volume directory under the temporary root.
     *
     * @param name The directory name
     * @return The created directory
     * @throws IOException if it cannot be created
     */
    private Path volume(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    @Test
    @DisplayName("two spellings of one directory are refused, not registered twice")
    void aDirectorySpeltTwoWaysIsRefused() throws IOException {
        Path first = volume("volume-a");
        String viaDotSegment = first.resolve(".").toString();

        withEngine(engine ->
            assertThatThrownBy(() -> VolumePlacements.install(
                engine, new VolumeManager(), List.of(first.toString(), viaDotSegment)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(first.toString())
                .hasMessageContaining(viaDotSegment));
    }

    @Test
    @DisplayName("a symlink to a volume already configured is refused")
    void aSymlinkedDuplicateIsRefused() throws IOException {
        Path first = volume("volume-a");
        Path link = tempDir.resolve("volume-a-link");
        Files.createSymbolicLink(link, first);

        withEngine(engine ->
            assertThatThrownBy(() -> VolumePlacements.install(
                engine, new VolumeManager(), List.of(first.toString(), link.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(link.toString()));
    }

    @Test
    @DisplayName("a path that walks out of a volume and back in is refused")
    void aPathWithParentSegmentsIsRefused() throws IOException {
        Path first = volume("volume-a");
        String roundTrip = first.resolve("..").resolve("volume-a").toString();

        withEngine(engine ->
            assertThatThrownBy(() -> VolumePlacements.install(
                engine, new VolumeManager(), List.of(first.toString(), roundTrip)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(roundTrip));
    }

    @Test
    @DisplayName("a volume that is really the data directory is folded into it")
    void theDataDirectorySpeltAsAVolumeIsFolded() throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path link = tempDir.resolve("data-link");
        Files.createSymbolicLink(link, dataDir);

        StorageEngine engine = new StorageEngine(dataDir);
        try {
            VolumePlacements.install(engine, new VolumeManager(), List.of(link.toString()));

            assertThat(engine.segmentPlacement().directories())
                .as("the data directory already holds segments; naming it again adds nothing")
                .containsExactly(dataDir);
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("genuinely distinct volumes are all installed")
    void distinctVolumesAreInstalled() throws IOException {
        Path first = volume("volume-a");
        Path second = volume("volume-b");

        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        StorageEngine engine = new StorageEngine(dataDir);
        try {
            VolumePlacements.install(engine, new VolumeManager(),
                List.of(first.toString(), second.toString()));

            assertThat(engine.segmentPlacement().directories())
                .containsExactly(dataDir, first, second);
        } finally {
            engine.close();
        }
    }
}
