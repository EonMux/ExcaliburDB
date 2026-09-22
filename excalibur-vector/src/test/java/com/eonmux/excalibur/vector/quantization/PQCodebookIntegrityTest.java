package com.eonmux.excalibur.vector.quantization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integrity tests for the persisted product-quantiser codebook.
 *
 * <p>The codebook decides which centroid every compressed vector stands for. A flipped
 * centroid coordinate moves the whole neighbourhood it represents, so the index keeps
 * answering and keeps answering with the wrong vectors.</p>
 */
@DisplayName("PQCodebookFile integrity")
class PQCodebookIntegrityTest {

    private static final int DIMENSION = 16;
    private static final int SUBSPACES = 4;
    private static final int CENTROIDS = 8;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a flipped codebook byte is refused on read")
    void flippedCodebookByteIsRefused() throws IOException {
        Path sidecar = tempDir.resolve("flip.pqcb");
        PQCodebookFile.write(sidecar, trainedQuantizer());

        flipByte(sidecar, sidecar.toFile().length() - 5);

        assertThatThrownBy(() -> PQCodebookFile.read(sidecar, DIMENSION))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(sidecar.toString());
    }

    @Test
    @DisplayName("an intact codebook still round-trips")
    void intactCodebookRoundTrips() throws IOException {
        Path sidecar = tempDir.resolve("intact.pqcb");
        ProductQuantizer written = trainedQuantizer();
        PQCodebookFile.write(sidecar, written);

        ProductQuantizer loaded = PQCodebookFile.read(sidecar, DIMENSION);

        assertThat(loaded.getCodebooks()).isDeepEqualTo(written.getCodebooks());
    }

    private static ProductQuantizer trainedQuantizer() {
        ProductQuantizer quantizer = new ProductQuantizer(DIMENSION, SUBSPACES, CENTROIDS);
        Random random = new Random(11);
        float[][] samples = new float[200][DIMENSION];
        for (int i = 0; i < samples.length; i++) {
            for (int d = 0; d < DIMENSION; d++) {
                samples[i][d] = random.nextFloat();
            }
        }
        quantizer.train(samples);
        return quantizer;
    }

    private static void flipByte(Path file, long offset) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
            handle.seek(offset);
            int original = handle.readUnsignedByte();
            handle.seek(offset);
            handle.writeByte(original ^ 0x40);
        }
    }
}
