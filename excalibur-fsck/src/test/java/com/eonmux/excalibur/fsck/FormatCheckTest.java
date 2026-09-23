package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CatalogCheck;
import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.CountMarkedFormat;
import com.eonmux.excalibur.fsck.check.CountMarkedTextCheck;
import com.eonmux.excalibur.fsck.check.GraphStagingCheck;
import com.eonmux.excalibur.fsck.check.RoutingDirectoryCheck;
import com.eonmux.excalibur.fsck.check.SegmentDataCheck;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.staging.EntityTextLog;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.LabelInterner;
import com.eonmux.excalibur.store.RoutingDirectory;
import com.eonmux.excalibur.store.catalog.CatalogFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void routingDirectoryFromANewerBuildIsUnreadableRatherThanCorrupt(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("routing.dir");
        int newer = RoutingDirectory.VERSION + 7;
        Files.write(file, routing(newer, 4));

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.UNREADABLE);
        assertThat(result.check().version()).isEqualTo("v" + newer);
        assertThat(result.check().detail()).contains("format version " + newer)
            .contains("versions " + RoutingDirectory.UNCHECKSUMMED_VERSION + " to "
                + RoutingDirectory.VERSION);
        assertThat(result.slots()).isEmpty();
    }

    @Test
    void routingDirectoryAtAVersionNoBuildWritesIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("routing.dir");
        Files.write(file, routing(0, 4));

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.check().detail()).contains("format version 0");
    }

    @Test
    void routingDirectoryWhoseChecksumMatchesIsIntact(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("routing.dir");
        Files.write(file, routing(RoutingDirectory.VERSION, 4));

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.check().version()).isEqualTo("v" + RoutingDirectory.VERSION);
        assertThat(result.slots()).hasSize(4);
    }

    @Test
    void routingDirectoryWithAFlippedSlotIsCorruptAndLeftToAnOperator(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        Path file = dataDir.resolve("routing.dir");
        byte[] bytes = Files.readAllBytes(file);
        bytes[RoutingDirectory.HEADER_BYTES + 5 * Integer.BYTES] ^= 1;
        Files.write(file, bytes);

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.check().detail()).contains("checksum did not match");
        assertThat(result.slots()).isEmpty();

        var plan = com.eonmux.excalibur.fsck.repair.RepairPlanner.plan(
            new com.eonmux.excalibur.fsck.check.DataDirectoryScanner(dataDir).scan());
        assertThat(plan.actions())
            .as("the routing directory is never moved aside: nothing can rebuild it")
            .noneSatisfy(action -> assertThat(action.relative()).isEqualTo("routing.dir"));
        assertThat(plan.needsOperator()).anySatisfy(advice ->
            assertThat(advice).contains("routing.dir").contains("checksum did not match"));
    }

    @Test
    void routingDirectoryCutShortIsCorrupt(@TempDir Path dir) throws IOException {
        byte[] full = routing(RoutingDirectory.VERSION, 8);
        Path file = dir.resolve("routing.dir");
        Files.write(file, java.util.Arrays.copyOf(full, full.length - 2));

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.check().detail()).contains("cut short");
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
    void routingDirectoryFromBeforeTheChecksumIsUnprotectedRatherThanIntact(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("routing.dir");
        Files.write(file, routing(RoutingDirectory.UNCHECKSUMMED_VERSION, 4));

        var result = RoutingDirectoryCheck.check(file, "routing.dir");

        assertThat(result.check().status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(result.check().version()).isEqualTo("v" + RoutingDirectory.UNCHECKSUMMED_VERSION);
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
            file, "document-registry", CountMarkedFormat.DOCUMENT_REGISTRY);

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
            file, "document-registry", CountMarkedFormat.DOCUMENT_REGISTRY);

        assertThat(result.status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(result.version()).isEqualTo("v1");
        assertThat(result.detail()).contains("not verified");
    }

    @Test
    void registryWithAHeaderAndNoCountMarkerIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("source-catalog");
        Files.writeString(file, "\\#source-catalog|2\nfile|a|ns|k=v\n");

        var result = CountMarkedTextCheck.check(
            file, "source-catalog", CountMarkedFormat.SOURCE_CATALOG);

        assertThat(result.status())
            .as("a header is written with the count, so a file with one and not the other lost "
                + "its tail, and the catalog's reader refuses it")
            .isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.detail()).contains("does not end with a record count");
    }

    @Test
    void registryWithAnUnreadableCountIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("source-catalog");
        Files.writeString(file, "\\#source-catalog|2\nfile|a|ns\n\\#end|many\n");

        var result = CountMarkedTextCheck.check(
            file, "source-catalog", CountMarkedFormat.SOURCE_CATALOG);

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.detail()).contains("names no number");
    }

    @Test
    void emptyRegistryIsUncheckedRatherThanSound(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("document-registry");
        Files.write(file, new byte[0]);

        var result = CountMarkedTextCheck.check(
            file, "document-registry", CountMarkedFormat.DOCUMENT_REGISTRY);

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
    void stagingLogAtAVersionItsReaderRefusesIsUnreadable(@TempDir Path dir) throws IOException {
        Path labels = dir.resolve(GraphStagingStore.ENTITY_LABELS);
        Files.write(labels, stagingHeader(LabelInterner.MAGIC, LabelInterner.VERSION + 1));
        Path triples = dir.resolve(GraphStagingStore.TRIPLE_LOG);
        Files.write(triples, stagingHeader(GraphStagingStore.MAGIC, 0));
        Path entities = dir.resolve(GraphStagingStore.ENTITY_TEXT_LOG);
        Files.write(entities, stagingHeader(EntityTextLog.MAGIC, EntityTextLog.VERSION + 1));

        for (var result : List.of(
                GraphStagingCheck.check(labels, "shared-staging/entities.labels"),
                GraphStagingCheck.check(triples, "shared-staging/triples.log"),
                GraphStagingCheck.check(entities, "shared-staging/entity_text.log"))) {
            assertThat(result.status()).as(result.relative()).isEqualTo(CheckStatus.UNREADABLE);
            assertThat(result.detail()).contains("refuses to open it");
        }
    }

    @Test
    void stagingLogAtAnOlderVersionItsReaderMigratesIsStillAccepted(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve(GraphStagingStore.TRIPLE_LOG);
        Files.write(file, stagingHeader(GraphStagingStore.MAGIC,
            GraphStagingStore.VERSION_WITHOUT_STATED_ON));

        var result = GraphStagingCheck.check(file, "shared-staging/triples.log");

        assertThat(result.status()).isEqualTo(CheckStatus.UNCHECKED);
        assertThat(result.version()).isEqualTo("v" + GraphStagingStore.VERSION_WITHOUT_STATED_ON);
    }

    @Test
    void erasureLogAtANewerVersionIsUnreadableRatherThanCorrupt(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve(GraphStagingCheck.ERASURE_LOG);
        Files.write(file, stagingHeader(ErasureLog.MAGIC, ErasureLog.VERSION + 1));

        var result = GraphStagingCheck.check(file, "shared-staging/erasure.log");

        assertThat(result.status())
            .as("corrupt would have a repair move aside the erasures a newer build still owes")
            .isEqualTo(CheckStatus.UNREADABLE);
        assertThat(result.version()).isEqualTo("v" + (ErasureLog.VERSION + 1));
    }

    @Test
    void catalogWhoseHeaderNamesANewerVersionIsUnreadableAndYieldsNoRows(@TempDir Path dir)
            throws IOException {
        Path file = writtenCatalog(dir);
        byte[] bytes = Files.readAllBytes(file);
        restampCatalogVersion(bytes, CatalogFile.VERSION + 1);
        Files.write(file, bytes);

        var result = CatalogCheck.check(file, "catalog.dat");

        assertThat(result.check().status()).isEqualTo(CheckStatus.UNREADABLE);
        assertThat(result.check().detail()).contains("version " + (CatalogFile.VERSION + 1));
        assertThat(result.records())
            .as("rows laid out by a build this one does not know are not rows to act on")
            .isEmpty();
        assertThatThrownBy(() -> new CatalogFile(file).close())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("cannot read");
    }

    @Test
    void catalogIsRefusedForANewerVersionInTheSlotNotInEffect(@TempDir Path dir)
            throws IOException {
        Path file = writtenCatalog(dir);
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int slotSize = CatalogFile.HEADER_SLOT_SIZE;
        assertThat(buffer.getInt(0)).isEqualTo(CatalogFile.MAGIC_NUMBER);
        assertThat(buffer.getInt(slotSize)).isEqualTo(CatalogFile.MAGIC_NUMBER);
        int older = buffer.getLong(20) < buffer.getLong(slotSize + 20) ? 0 : 1;
        byte[] olderSlotOnly = bytes.clone();
        restampCatalogVersion(olderSlotOnly, CatalogFile.VERSION + 1, older);
        Files.write(file, olderSlotOnly);

        var result = CatalogCheck.check(file, "catalog.dat");

        assertThat(result.check().status())
            .as("the opener refuses a file any checksummed slot of which names a version it "
                + "does not know, not only the slot in effect")
            .isEqualTo(CheckStatus.UNREADABLE);
        assertThatThrownBy(() -> new CatalogFile(file).close()).isInstanceOf(IOException.class);
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
     * Writes a routing directory with a chosen version and width.
     *
     * <p>A version from the checksummed one on carries the trailing CRC32C its writer appends;
     * the version before it carries none, as that build wrote it.</p>
     *
     * @param version The version field
     * @param width   The width field, and how many slot entries follow
     * @return The file's bytes
     */
    private static byte[] routing(int version, int width) {
        boolean checksummed = version >= RoutingDirectory.VERSION;
        ByteBuffer buffer = ByteBuffer
            .allocate(RoutingDirectory.HEADER_BYTES + width * Integer.BYTES
                + (checksummed ? RoutingDirectory.CHECKSUM_BYTES : 0))
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(RoutingDirectory.MAGIC).putInt(version).putInt(width);
        for (int i = 0; i < width; i++) {
            buffer.putInt(0);
        }
        if (checksummed) {
            java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
            crc.update(buffer.array(), 0, buffer.position());
            buffer.putInt((int) crc.getValue());
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
     * Rewrites the version of every header slot a catalog carries, and the CRC over it, so each
     * slot vouches for the new version the way a newer build's writer would have made it.
     *
     * @param bytes   The whole catalog, changed in place
     * @param version The version to name
     */
    private static void restampCatalogVersion(byte[] bytes, int version) {
        for (int slot = 0; slot < 2; slot++) {
            restampCatalogVersion(bytes, version, slot);
        }
    }

    /**
     * Rewrites one header slot's version and the CRC over it, if the slot carries a header.
     *
     * @param bytes   The whole catalog, changed in place
     * @param version The version to name
     * @param slot    Index of the slot, 0 or 1
     */
    private static void restampCatalogVersion(byte[] bytes, int version, int slot) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int base = slot * CatalogFile.HEADER_SLOT_SIZE;
        if (buffer.getInt(base) != CatalogFile.MAGIC_NUMBER) {
            return;
        }
        buffer.putInt(base + 4, version);
        CRC32 crc = new CRC32();
        crc.update(bytes, base, 28);
        buffer.putInt(base + 28, (int) crc.getValue());
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
                var metadata = new com.eonmux.excalibur.store.SegmentCatalog.SegmentMetadata(i);
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
