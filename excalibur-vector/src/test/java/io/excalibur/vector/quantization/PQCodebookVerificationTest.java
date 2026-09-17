package io.excalibur.vector.quantization;

import io.excalibur.core.integrity.IntegrityStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the codebook file's verification entry point and its handling of older files.
 */
@DisplayName("PQCodebookFile verification")
class PQCodebookVerificationTest {

    private static final int DIMENSION = 16;
    private static final int SUBSPACES = 4;
    private static final int CENTROIDS = 8;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a written codebook verifies as intact")
    void writtenCodebookVerifiesIntact() throws IOException {
        Path sidecar = tempDir.resolve("sealed.pqcb");
        PQCodebookFile.write(sidecar, trainedQuantizer());

        assertThat(PQCodebookFile.verifyIntegrity(sidecar).status())
            .isEqualTo(IntegrityStatus.INTACT);
    }

    @Test
    @DisplayName("a damaged codebook is reported corrupt")
    void damagedCodebookIsReportedCorrupt() throws IOException {
        Path sidecar = tempDir.resolve("rotted.pqcb");
        PQCodebookFile.write(sidecar, trainedQuantizer());
        flipByte(sidecar, Files.size(sidecar) - 9);

        var report = PQCodebookFile.verifyIntegrity(sidecar);

        assertThat(report.status()).isEqualTo(IntegrityStatus.CORRUPT);
        assertThat(report.loadable()).isFalse();
    }

    @Test
    @DisplayName("a codebook written before checksums existed still reads, named as unprotected")
    void olderCodebookStillReads() throws IOException {
        Path sidecar = tempDir.resolve("legacy.pqcb");
        ProductQuantizer written = trainedQuantizer();
        Files.write(sidecar, legacyCodebook(written));

        assertThat(PQCodebookFile.verifyIntegrity(sidecar).status())
            .isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);
        assertThat(PQCodebookFile.read(sidecar, DIMENSION).getCodebooks())
            .isDeepEqualTo(written.getCodebooks());
    }

    @Test
    @DisplayName("a codebook file that is not there is reported missing")
    void absentCodebookIsReportedMissing() {
        assertThat(PQCodebookFile.verifyIntegrity(tempDir.resolve("absent.pqcb")).status())
            .isEqualTo(IntegrityStatus.MISSING);
    }

    /**
     * Builds a version 1 codebook file: header and centroids, no checksum fields.
     */
    private static byte[] legacyCodebook(ProductQuantizer quantizer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeLong(0x5051434F4445424BL);
            data.writeInt(1);
            data.writeInt(quantizer.originalDim());
            data.writeInt(quantizer.numSubspaces());
            data.writeInt(quantizer.numCentroids());
            for (float[][] subspace : quantizer.getCodebooks()) {
                for (float[] centroid : subspace) {
                    for (float value : centroid) {
                        data.writeFloat(value);
                    }
                }
            }
        }
        return bytes.toByteArray();
    }

    private static ProductQuantizer trainedQuantizer() {
        ProductQuantizer quantizer = new ProductQuantizer(DIMENSION, SUBSPACES, CENTROIDS);
        Random random = new Random(13);
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
