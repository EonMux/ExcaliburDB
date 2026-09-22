package com.eonmux.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ordering that makes a rename mean something.
 *
 * <p>No test can cut the power between two filesystem calls, so what is pinned here is the order
 * the calls are made in: contents forced before the rename that names them, and the directory
 * forced after it. A rename published ahead of its own contents produces a correctly named file
 * full of nothing, which is the failure the rename exists to prevent, reached from the other
 * end.
 */
@DisplayName("Durable publication")
class DurabilityTest {

    /** Records the steps instead of taking them, so their order can be asserted. */
    private static final class Recorder implements Durability {

        private final List<String> steps = new ArrayList<>();
        private IOException forceFailure;

        @Override
        public void force(Path file) throws IOException {
            steps.add("force " + file.getFileName());
            if (forceFailure != null) {
                throw forceFailure;
            }
        }

        @Override
        public void publish(Path replacement, Path target) throws IOException {
            steps.add("publish " + replacement.getFileName() + " -> " + target.getFileName());
            Durability.FILE_SYSTEM.publish(replacement, target);
        }

        @Override
        public void forceDirectory(Path directory) {
            steps.add("forceDirectory " + directory.getFileName());
        }
    }

    @Nested
    @DisplayName("replacing a file")
    class Replacing {

        @Test
        @DisplayName("the contents are forced before the rename and the directory after it")
        void ordersTheSteps(@TempDir Path dir) throws Exception {
            Recorder recorder = new Recorder();

            recorder.replace(dir.resolve("thing"), "payload".getBytes(StandardCharsets.UTF_8));

            assertThat(recorder.steps).containsExactly(
                "force thing.tmp",
                "publish thing.tmp -> thing",
                "forceDirectory " + dir.getFileName());
        }

        @Test
        @DisplayName("the target holds the new contents and no scratch file is left behind")
        void publishesTheContents(@TempDir Path dir) throws Exception {
            Path target = dir.resolve("thing");
            Durability.FILE_SYSTEM.replace(target, "first".getBytes(StandardCharsets.UTF_8));
            Durability.FILE_SYSTEM.replace(target, "second".getBytes(StandardCharsets.UTF_8));

            assertThat(Files.readString(target)).isEqualTo("second");
            try (var listing = Files.list(dir)) {
                assertThat(listing.map(path -> path.getFileName().toString()))
                    .containsExactly("thing");
            }
        }

        @Test
        @DisplayName("a failure to force leaves the old file in place and removes the scratch")
        void failureLeavesTheOldFile(@TempDir Path dir) throws Exception {
            Path target = dir.resolve("thing");
            Files.writeString(target, "original");
            Recorder recorder = new Recorder();
            recorder.forceFailure = new IOException("device is gone");

            assertThatThrownBy(() ->
                recorder.replace(target, "replacement".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class)
                .hasMessage("device is gone");

            assertThat(Files.readString(target)).isEqualTo("original");
            assertThat(Files.exists(dir.resolve("thing.tmp"))).isFalse();
        }
    }

    @Nested
    @DisplayName("the filesystem's own steps")
    class OnTheFileSystem {

        @Test
        @DisplayName("forcing a file that is not there is an error the caller must see")
        void forcingAMissingFileThrows(@TempDir Path dir) {
            assertThatThrownBy(() -> Durability.FILE_SYSTEM.force(dir.resolve("absent")))
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("a directory that cannot be forced does not fail the write it followed")
        void forcingAMissingDirectoryIsTolerated(@TempDir Path dir) {
            // Best effort by design: the bytes are already on the device by this point, and a
            // platform that will not open a directory channel is not a reason to fail a write
            // that otherwise succeeded.
            Durability.FILE_SYSTEM.forceDirectory(dir.resolve("absent"));
        }
    }

    @Nested
    @DisplayName("the manifest")
    class Manifest {

        @Test
        @DisplayName("is forced before the rename that publishes it")
        void isForcedBeforeItIsNamed(@TempDir Path dir) throws Exception {
            SegmentManifest manifest = SegmentManifest.empty();
            manifest.add(new SegmentManifest.Entry("seg-0", 1, 4, 0));
            Recorder recorder = new Recorder();

            manifest.write(dir, recorder);

            assertThat(recorder.steps).containsExactly(
                "force manifest.tmp",
                "publish manifest.tmp -> manifest",
                "forceDirectory " + dir.getFileName());
        }
    }
}
