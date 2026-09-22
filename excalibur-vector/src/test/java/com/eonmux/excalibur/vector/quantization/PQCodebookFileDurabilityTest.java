package com.eonmux.excalibur.vector.quantization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A codebook must be on storage before its name says it is.
 *
 * <p>The rename is what publishes the file: from that moment every reopened segment quantises
 * against whatever bytes are under that name. Bytes still sitting in the page cache when the
 * host dies leave a file of the right length, with a valid header, whose centroids are not the
 * ones that were trained - and the header's magic, version and dimension all still check out,
 * so nothing downstream refuses it. It quantises every vector against the wrong centroids and
 * returns confident, meaningless rankings.</p>
 *
 * <p>An fsync leaves no trace in the file it protects, so the only thing that can be asserted
 * about it is that it happened, and that it happened before the rename. That is what the
 * {@link PQCodebookFile.Sync} seam exists for.</p>
 */
@DisplayName("Codebook durability")
class PQCodebookFileDurabilityTest {

    @TempDir
    Path tempDir;

    /**
     * Records the durability steps a write takes, and whether the destination had already
     * been published when each one happened.
     */
    private static final class RecordingSync implements PQCodebookFile.Sync {

        private final Path destination;
        private final List<String> steps = new ArrayList<>();
        private final List<Boolean> destinationExisted = new ArrayList<>();

        RecordingSync(Path destination) {
            this.destination = destination;
        }

        @Override
        public void forceStagedContents(FileChannel staged) throws IOException {
            steps.add("contents");
            destinationExisted.add(Files.exists(destination));
            staged.force(true);
        }

        @Override
        public void forceDirectoryEntry(Path file) {
            steps.add("directory");
            destinationExisted.add(Files.exists(destination));
        }
    }

    private static ProductQuantizer trainedQuantizer() {
        ProductQuantizer quantizer = new ProductQuantizer(8, 4, 4);
        Random random = new Random(11);
        float[][] samples = new float[64][8];
        for (float[] sample : samples) {
            for (int d = 0; d < sample.length; d++) {
                sample[d] = random.nextFloat();
            }
        }
        quantizer.train(samples);
        return quantizer;
    }

    @Test
    @DisplayName("the staged contents are forced before the rename publishes them")
    void theStagedContentsAreForcedBeforeTheRename() throws IOException {
        Path path = tempDir.resolve("codebook" + PQCodebookFile.SUFFIX);
        RecordingSync sync = new RecordingSync(path);

        PQCodebookFile.write(path, trainedQuantizer(), sync);

        assertThat(sync.steps)
            .as("the contents reach storage first, then the name that publishes them")
            .containsExactly("contents", "directory");
        assertThat(sync.destinationExisted.get(0))
            .as("nothing may be published under the codebook's name until it is durable")
            .isFalse();
        assertThat(sync.destinationExisted.get(1))
            .as("the directory entry is forced once the rename has created it")
            .isTrue();
    }

    @Test
    @DisplayName("the published codebook still reads back as the quantiser that was written")
    void theForcedWriteStillRoundTrips() throws IOException {
        Path path = tempDir.resolve("roundtrip" + PQCodebookFile.SUFFIX);
        ProductQuantizer written = trainedQuantizer();

        PQCodebookFile.write(path, written);

        ProductQuantizer read = PQCodebookFile.read(path, written.originalDim());
        assertThat(read.getCodebooks()).isDeepEqualTo(written.getCodebooks());
        assertThat(Files.exists(tempDir.resolve("roundtrip" + PQCodebookFile.SUFFIX + ".tmp")))
            .as("the staging file is consumed by the rename")
            .isFalse();
    }
}
