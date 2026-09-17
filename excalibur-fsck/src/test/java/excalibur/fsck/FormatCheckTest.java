package excalibur.fsck;

import excalibur.fsck.check.CatalogCheck;
import excalibur.fsck.check.CheckStatus;
import excalibur.fsck.check.CountMarkedTextCheck;
import excalibur.fsck.check.GraphStagingCheck;
import excalibur.fsck.check.RoutingDirectoryCheck;
import excalibur.fsck.check.SegmentDataCheck;
import io.excalibur.graph.erasure.ErasureLog;
import io.excalibur.graph.staging.GraphStagingStore;
import io.excalibur.graph.staging.LabelInterner;
import io.excalibur.store.RoutingDirectory;
import io.excalibur.store.catalog.CatalogFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each format reader against the damage it is supposed to name.
 *
 * <p>Driven at the reader rather than through the command, because the failures worth
 * pinning here are the ones a whole-directory run would only ever reach by accident: a
 * header slot that half-wrote, a width field that is not a power of two, a file whose
 * magic number is somebody else's.</p>
 */
class FormatCheckTest {

    @Test
    void routingDirectoryWithForeignMagicIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("routing.dir");
        Files.write(file, new byte[32]);

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.check().detail()).contains("magic number");
        assertThat(result.slots()).isEmpty();
    }

    @Test
    void routingDirectoryTooShortForItsHeaderIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("routing.dir");
        Files.write(file, new byte[4]);

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.check().detail()).contains("too short");
    }

    @Test
    void routingDirectoryWithAnUnknownVersionIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("routing.dir");
        Files.write(file, routing(RoutingDirectory.VERSION + 7, 4));

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.check().detail()).contains("format version");
    }

    @Test
    void routingDirectoryWithANonPowerOfTwoWidthIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("routing.dir");
        Files.write(file, routing(RoutingDirectory.VERSION, 3));

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.check().detail()).contains("power of two");
    }

    @Test
    void routingDirectoryShorterThanItsDeclaredWidthIsCorrupt(@TempDir Path dir)
            throws IOException {
        byte[] full = routing(RoutingDirectory.VERSION, 8);
        Path file = dir.resolve("routing.dir");
        Files.write(file, java.util.Arrays.copyOf(full, full.length - 8));

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.check().detail()).contains("declares 8 slots");
    }

    @Test
    void wellFormedRoutingDirectoryIsUnprotectedRatherThanIntact(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("routing.dir");
        Files.write(file, routing(RoutingDirectory.VERSION, 4));

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(result.check().detail()).contains("no checksum");
        assertThat(result.slots()).hasSize(4);
    }

    @Test
    void blankCatalogIsUncheckedRatherThanCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("catalog.dat");
        Files.write(file, new byte[16]);

        var result = CatalogCheck.check(file, "catalog.dat");

        assertThat(result.check().status()).isEqualTo(CheckStatus.UNCHECKED);
        assertThat(result.check().detail()).contains("never written");
    }

    @Test
    void truncatedCatalogIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("catalog.dat");
        Files.write(file, new byte[] { 1, 2, 3, 4 });

        var result = CatalogCheck.check(file, "catalog.dat");

        assertThat(result.check().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.check().detail()).contains("truncated");
    }

    @Test
    void catalogWithNoUsableHeaderIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = writtenCatalog(dir);
        byte[] bytes = Files.readAllBytes(file);
        for (int i = 0; i < CatalogFile.HEADER_SIZE; i++) {
            bytes[i] ^= 0x5A;
        }
        Files.write(file, bytes);

        var result = CatalogCheck.check(file, "catalog.dat");

        assertThat(result.check().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.check().detail()).contains("neither header slot");
        assertThat(result.records()).isEmpty();
    }

    @Test
    void catalogWithADamagedRowIsCorruptAndSaysHowMany(@TempDir Path dir) throws IOException {
        Path file = writtenCatalog(dir);
        byte[] bytes = Files.readAllBytes(file);
        bytes[CatalogFile.HEADER_SIZE + 20] ^= 0x33;
        Files.write(file, bytes);

        var result = CatalogCheck.check(file, "catalog.dat");

        assertThat(result.check().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.check().detail()).contains("1 of the 2 rows");
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).crcValid()).isFalse();
        assertThat(result.records().get(0).expectsFileOnDisk()).isFalse();
    }

    @Test
    void catalogWrittenByTheCatalogReadsBackIntact(@TempDir Path dir) throws IOException {
        Path file = writtenCatalog(dir);

        var result = CatalogCheck.check(file, "catalog.dat");

        assertThat(result.check().status()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(1).fileName()).isEqualTo("segment_1.dat");
        assertThat(result.records().get(1).expectsFileOnDisk()).isTrue();
    }

    @Test
    void segmentFileTooSmallToHoldASlotIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("segment_0.dat");
        Files.write(file, new byte[8]);

        var result = SegmentDataCheck.check(file, "segment_0.dat");

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.detail()).contains("too short");
    }

    @Test
    void registryWhoseCountDisagreesWithItsLinesIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("document-registry");
        Files.writeString(file, """
            \\#document-registry|1
            a|b|1|h|2|3|c1
            \\#end|4
            """);

        var result = CountMarkedTextCheck.check(
            file, "document-registry", "document registry", "\\#document-registry");

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.detail()).contains("declares 4 records and holds 1");
    }

    @Test
    void registryWhoseCountAgreesIsWholeButUnverified(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("document-registry");
        Files.writeString(file, """
            \\#document-registry|1
            a|b|1|h|2|3|c1
            \\#end|1
            """);

        var result = CountMarkedTextCheck.check(
            file, "document-registry", "document registry", "\\#document-registry");

        assertThat(result.status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(result.version()).isEqualTo("v1");
        assertThat(result.detail()).contains("not verified");
    }

    @Test
    void registryWithNoCountMarkerIsUnchecked(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("source-catalog");
        Files.writeString(file, "\\#source-catalog|2\nfile|a|ns|k=v\n");

        var result = CountMarkedTextCheck.check(
            file, "source-catalog", "source catalog", "\\#source-catalog");

        assertThat(result.status()).isEqualTo(CheckStatus.UNCHECKED);
        assertThat(result.detail()).contains("no trailing record count");
    }

    @Test
    void registryWithAnUnreadableCountIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("source-catalog");
        Files.writeString(file, "\\#source-catalog|2\nfile|a|ns\n\\#end|many\n");

        var result = CountMarkedTextCheck.check(
            file, "source-catalog", "source catalog", "\\#source-catalog");

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.detail()).contains("names no number");
    }

    @Test
    void emptyRegistryIsUncheckedRatherThanSound(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("document-registry");
        Files.write(file, new byte[0]);

        var result = CountMarkedTextCheck.check(
            file, "document-registry", "document registry", "\\#document-registry");

        assertThat(result.status()).isEqualTo(CheckStatus.UNCHECKED);
        assertThat(result.detail()).contains("empty");
    }

    @Test
    void stagingLogHeaderIsConfirmedAndItsRecordsAreNotClaimed(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve(GraphStagingStore.ENTITY_LABELS);
        Files.write(file, stagingHeader(LabelInterner.MAGIC, LabelInterner.VERSION));

        var result = GraphStagingCheck.check(file, "shared-staging/entities.labels");

        assertThat(result.status()).isEqualTo(CheckStatus.UNCHECKED);
        assertThat(result.version()).isEqualTo("v" + LabelInterner.VERSION);
        assertThat(result.detail()).contains("records themselves were not read");
    }

    @Test
    void stagingLogWithTheWrongMagicIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(GraphStagingStore.TRIPLE_LOG);
        Files.write(file, stagingHeader(LabelInterner.MAGIC, 1));

        var result = GraphStagingCheck.check(file, "shared-staging/triples.log");

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.detail()).contains("magic number");
    }

    @Test
    void stagingLogTooShortForAHeaderIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(GraphStagingStore.ENTITY_TEXT_LOG);
        Files.write(file, new byte[4]);

        var result = GraphStagingCheck.check(file, "shared-staging/entity_text.log");

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.detail()).contains("too short");
    }

    @Test
    void graphErasureLogIsActuallyReplayed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(GraphStagingCheck.ERASURE_LOG);
        Files.write(file, stagingHeader(ErasureLog.MAGIC, ErasureLog.VERSION));

        var result = GraphStagingCheck.check(file, "shared-staging/erasure.log");

        assertThat(result.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.detail()).contains("0 erasure requests");
    }

    @Test
    void graphErasureLogWithAForeignMagicIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(GraphStagingCheck.ERASURE_LOG);
        Files.write(file, stagingHeader(LabelInterner.MAGIC, 1));

        var result = GraphStagingCheck.check(file, "shared-staging/erasure.log");

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
    }

    @Test
    void everyStagingLogNameIsRecognised() {
        assertThat(GraphStagingCheck.matches(GraphStagingStore.ENTITY_LABELS)).isTrue();
        assertThat(GraphStagingCheck.matches(GraphStagingStore.RELATION_LABELS)).isTrue();
        assertThat(GraphStagingCheck.matches(GraphStagingCheck.ERASURE_LOG)).isTrue();
        assertThat(GraphStagingCheck.matches("something-else")).isFalse();
    }

    /**
     * Writes a routing directory header with a chosen version and width.
     *
     * @param version The version field
     * @param width   The width field, and how many slot entries follow
     * @return The file's bytes
     */
    private static byte[] routing(int version, int width) {
        ByteBuffer buffer = ByteBuffer
            .allocate(RoutingDirectory.HEADER_BYTES + width * Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(RoutingDirectory.MAGIC).putInt(version).putInt(width);
        for (int i = 0; i < width; i++) {
            buffer.putInt(0);
        }
        return buffer.array();
    }

    /**
     * Writes the sixteen-byte, big-endian header every graph staging log begins with.
     *
     * @param magic   The log's magic number
     * @param version Its format version
     * @return The header bytes
     */
    private static byte[] stagingHeader(long magic, int version) {
        return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(magic).putInt(version).putInt(0).array();
    }

    /**
     * Writes a catalog holding two rows, using the catalog's own writer.
     *
     * @param dir Where to write it
     * @return The catalog file
     * @throws IOException if it cannot be written
     */
    private static Path writtenCatalog(Path dir) throws IOException {
        Path file = dir.resolve("catalog.dat");
        try (CatalogFile catalog = new CatalogFile(file)) {
            for (int i = 0; i < 2; i++) {
                var metadata = new io.excalibur.store.SegmentCatalog.SegmentMetadata(i);
                metadata.setVolumePath(dir.resolve("segment_" + i + ".dat"));
                metadata.setEntryCount(i);
                metadata.setSourceId("source-" + i);
                catalog.writeSegment(metadata);
            }
            catalog.flush();
        }
        assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).isNotEmpty();
        return file;
    }
}
