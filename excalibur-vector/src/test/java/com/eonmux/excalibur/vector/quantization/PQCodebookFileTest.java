package com.eonmux.excalibur.vector.quantization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trained codebook has to survive a restart, and a codebook that does not belong to the
 * index beside it has to be refused rather than read.
 *
 * <p>Codebooks are the expensive half of product quantisation: k-means over a sample of the
 * segment, paid once. Without a file to keep them in, every restart either pays it again or -
 * as it did - drops quantisation entirely. And because a codebook is just a block of floats,
 * one written for a different vector space parses perfectly against any other; the shape it
 * was trained for is recorded so the mismatch is caught instead of quantising against
 * centroids from another corpus.</p>
 */
@DisplayName("Codebook sidecar")
class PQCodebookFileTest {

    private static final int DIMENSION = 16;
    private static final int SUBSPACES = 4;
    private static final int CENTROIDS = 8;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a written codebook reads back as the same quantiser")
    void roundTripsTheCodebook() throws IOException {
        ProductQuantizer trained = trainedQuantizer(DIMENSION, SUBSPACES, CENTROIDS);
        Path sidecar = tempDir.resolve("graph.hnsw.pqcb");

        PQCodebookFile.write(sidecar, trained);
        ProductQuantizer loaded = PQCodebookFile.read(sidecar, DIMENSION);

        assertThat(loaded.isTrained()).isTrue();
        assertThat(loaded.numSubspaces()).isEqualTo(SUBSPACES);
        assertThat(loaded.numCentroids()).isEqualTo(CENTROIDS);
        assertThat(loaded.getCodebooks()).isDeepEqualTo(trained.getCodebooks());

        float[] probe = randomVector(DIMENSION, new Random(7L));
        assertThat(loaded.encode(probe))
            .as("the same vector must quantise to the same codes after a reload")
            .isEqualTo(trained.encode(probe));
    }

    @Test
    @DisplayName("the write leaves no partial file behind")
    void writeIsAtomic() throws IOException {
        Path sidecar = tempDir.resolve("graph.hnsw.pqcb");

        PQCodebookFile.write(sidecar, trainedQuantizer(DIMENSION, SUBSPACES, CENTROIDS));

        try (var entries = Files.list(tempDir)) {
            assertThat(entries.map(Path::getFileName).map(Path::toString).toList())
                .as("only the sidecar itself, never the temporary it was staged in")
                .containsExactly(sidecar.getFileName().toString());
        }
    }

    @Test
    @DisplayName("a rewrite replaces the previous codebook")
    void writeReplacesAnExistingSidecar() throws IOException {
        Path sidecar = tempDir.resolve("graph.hnsw.pqcb");
        PQCodebookFile.write(sidecar, trainedQuantizer(DIMENSION, SUBSPACES, CENTROIDS));

        ProductQuantizer replacement = trainedQuantizer(DIMENSION, SUBSPACES, CENTROIDS * 2);
        PQCodebookFile.write(sidecar, replacement);

        assertThat(PQCodebookFile.read(sidecar, DIMENSION).numCentroids()).isEqualTo(CENTROIDS * 2);
    }

    @Test
    @DisplayName("a codebook trained for another dimension is refused")
    void refusesADimensionMismatch() throws IOException {
        Path sidecar = tempDir.resolve("graph.hnsw.pqcb");
        PQCodebookFile.write(sidecar, trainedQuantizer(32, SUBSPACES, CENTROIDS));

        assertThatThrownBy(() -> PQCodebookFile.read(sidecar, DIMENSION))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("dimension")
            .hasMessageContaining("32")
            .hasMessageContaining(String.valueOf(DIMENSION));
    }

    @Test
    @DisplayName("a file that is not a codebook is refused")
    void refusesAForeignFile() throws IOException {
        Path sidecar = tempDir.resolve("graph.hnsw.pqcb");
        Files.write(sidecar, "this is not a codebook, it is a text file".getBytes());

        assertThatThrownBy(() -> PQCodebookFile.read(sidecar, DIMENSION))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("magic");
    }

    @Test
    @DisplayName("a truncated codebook is refused rather than padded")
    void refusesATruncatedFile() throws IOException {
        Path sidecar = tempDir.resolve("graph.hnsw.pqcb");
        PQCodebookFile.write(sidecar, trainedQuantizer(DIMENSION, SUBSPACES, CENTROIDS));

        byte[] full = Files.readAllBytes(sidecar);
        Files.write(sidecar, java.util.Arrays.copyOf(full, full.length - 8));

        assertThatThrownBy(() -> PQCodebookFile.read(sidecar, DIMENSION))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("truncated");
    }

    @Test
    @DisplayName("trailing bytes mean the file is not what it claims")
    void refusesTrailingBytes() throws IOException {
        Path sidecar = tempDir.resolve("graph.hnsw.pqcb");
        PQCodebookFile.write(sidecar, trainedQuantizer(DIMENSION, SUBSPACES, CENTROIDS));

        Files.write(sidecar, new byte[16], java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> PQCodebookFile.read(sidecar, DIMENSION))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("trailing");
    }

    @Test
    @DisplayName("an untrained quantiser has nothing to write")
    void refusesToWriteAnUntrainedQuantiser() {
        Path sidecar = tempDir.resolve("graph.hnsw.pqcb");

        assertThatThrownBy(() ->
            PQCodebookFile.write(sidecar, new ProductQuantizer(DIMENSION, SUBSPACES, CENTROIDS)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("trained");
    }

    private static ProductQuantizer trainedQuantizer(int dimension, int subspaces, int centroids) {
        ProductQuantizer quantizer = new ProductQuantizer(dimension, subspaces, centroids);
        Random random = new Random(dimension * 31L + centroids);
        float[][] samples = new float[centroids * 8][];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = randomVector(dimension, random);
        }
        quantizer.train(samples);
        return quantizer;
    }

    private static float[] randomVector(int dimension, Random random) {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }
        return vector;
    }
}
