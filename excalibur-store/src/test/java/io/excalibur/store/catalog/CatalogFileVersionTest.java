package io.excalibur.store.catalog;

import io.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an older build must do with a catalog a newer one wrote.
 *
 * <p>The catalog is the only record of which segment files this store owns and what state each
 * of them is in - lifecycle, {@code lastIntegratedOffset}, source and epochs. A build that
 * cannot read the format it finds has exactly two options, and only one of them is honest:
 * refuse, or start with an empty catalog and let the next flush overwrite the real one. The
 * second is indistinguishable from a first start, so a rollback that lands on it loses every
 * segment's lifecycle state without anything in the logs saying a catalog was ever there.</p>
 *
 * <p>Refusal is only safe when the version is known for certain, which is why it keys off a
 * header slot that passes its own checksum: a version field garbled by a torn write must still
 * take the recovery path rather than stop the node.</p>
 */
@DisplayName("Catalog format version handling")
class CatalogFileVersionTest {

    /** A version no build in this line writes, standing in for one written by a newer build. */
    private static final int FUTURE_VERSION = CatalogFile.VERSION + 1;

    @TempDir
    Path tempDir;

    /**
     * Restamps both header slots with a version, re-checksumming each so the slot is genuinely
     * valid - which is what a catalog written by a newer build looks like.
     */
    private static void restampVersion(Path catalogPath, int version) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(catalogPath.toFile(), "rw")) {
            for (int slot = 0; slot < 2; slot++) {
                long base = (long) slot * CatalogFile.HEADER_SLOT_SIZE;
                raf.seek(base);
                if (raf.readInt() != CatalogFile.MAGIC_NUMBER) {
                    continue;
                }
                raf.seek(base + 4);
                raf.writeInt(version);

                raf.seek(base);
                byte[] covered = new byte[28];
                raf.readFully(covered);
                CRC32 crc32 = new CRC32();
                crc32.update(covered);
                raf.seek(base + 28);
                raf.writeInt((int) crc32.getValue());
            }
        }
    }

    private static SegmentCatalog.SegmentMetadata segment(long id, Path volumePath) {
        SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(id);
        metadata.setVolumePath(volumePath);
        metadata.setEntryCount(17);
        metadata.setEntryState(SegmentCatalog.ENTRY_STATE_ACTIVE);
        metadata.setLastIntegratedOffset(4096);
        return metadata;
    }

    @Test
    @DisplayName("a catalog from a newer build is refused, not reset to empty")
    void aNewerCatalogIsRefused() throws IOException {
        Path catalogPath = tempDir.resolve("catalog.dat");
        try (CatalogFile catalog = new CatalogFile(catalogPath)) {
            catalog.writeSegment(segment(1L, tempDir.resolve("segment_1.dat")));
        }
        restampVersion(catalogPath, FUTURE_VERSION);

        assertThatThrownBy(() -> new CatalogFile(catalogPath))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(String.valueOf(FUTURE_VERSION))
            .hasMessageContaining(String.valueOf(CatalogFile.VERSION))
            .hasMessageContaining(catalogPath.toString());
    }

    @Test
    @DisplayName("the refusal leaves the catalog on disk untouched")
    void theRefusalDoesNotRewriteTheFile() throws IOException {
        Path catalogPath = tempDir.resolve("untouched.dat");
        try (CatalogFile catalog = new CatalogFile(catalogPath)) {
            catalog.writeSegment(segment(7L, tempDir.resolve("segment_7.dat")));
        }
        restampVersion(catalogPath, FUTURE_VERSION);
        byte[] before = java.nio.file.Files.readAllBytes(catalogPath);

        assertThatThrownBy(() -> new CatalogFile(catalogPath)).isInstanceOf(IOException.class);

        assertThat(java.nio.file.Files.readAllBytes(catalogPath))
            .as("a build that cannot read the file must not write to it either")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("a version field reading as legacy is not migrated over an intact header")
    void aGarbledVersionIsNotMistakenForALegacyCatalog() throws IOException {
        Path catalogPath = tempDir.resolve("pseudo-legacy.dat");
        try (CatalogFile catalog = new CatalogFile(catalogPath)) {
            catalog.writeSegment(segment(5L, tempDir.resolve("segment_5.dat")));
        }

        // One slot's version field, torn back to the legacy value without its checksum
        // following. The legacy format has no header checksum, so the version field is the
        // only evidence there is - and taken at face value it sends a current-format catalog
        // through a migration that reads its records at the wrong width and recovers none.
        try (RandomAccessFile raf = new RandomAccessFile(catalogPath.toFile(), "rw")) {
            raf.seek(4);
            raf.writeInt(1);
        }

        try (CatalogFile reopened = new CatalogFile(catalogPath)) {
            assertThat(reopened.getSegmentCount()).isEqualTo(1);
            assertThat(reopened.readSegment(5L)).isNotNull();
            assertThat(reopened.readSegment(5L).getLastIntegratedOffset()).isEqualTo(4096);
        }
    }

    @Test
    @DisplayName("a version garbled by a torn write still takes the recovery path")
    void aGarbledVersionIsNotMistakenForANewerBuild() throws IOException {
        Path catalogPath = tempDir.resolve("garbled.dat");
        try (CatalogFile catalog = new CatalogFile(catalogPath)) {
            catalog.writeSegment(segment(3L, tempDir.resolve("segment_3.dat")));
        }

        // The version field of both slots, changed without fixing the checksums. Neither slot
        // now describes anything the file can be trusted to be, so the records - which carry
        // their own checksums - are what the catalog is rebuilt from.
        try (RandomAccessFile raf = new RandomAccessFile(catalogPath.toFile(), "rw")) {
            raf.seek(4);
            raf.writeInt(FUTURE_VERSION);
            raf.seek(CatalogFile.HEADER_SLOT_SIZE + 4L);
            raf.writeInt(FUTURE_VERSION);
        }

        try (CatalogFile reopened = new CatalogFile(catalogPath)) {
            assertThat(reopened.getSegmentCount()).isEqualTo(1);
            assertThat(reopened.readSegment(3L)).isNotNull();
        }
    }
}
