package io.excalibur.store.catalog;

import io.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the catalog does when its headers are gone and its records are not all intact.
 *
 * <p>The catalog is the only record of which segment files this store owns and what state each
 * is in. Two failures of the recovery path were indistinguishable from a first start, which is
 * the worst shape they could take: the node comes up, reports itself healthy, and has silently
 * forgotten every segment's lifecycle state, integrated offset, source and epochs.</p>
 *
 * <p>Neither is hypothetical - both header slots failing their checksum is exactly what a torn
 * write during a header update leaves behind, and it is the case the record-level recovery
 * exists for.</p>
 */
@DisplayName("Catalog recovery when the headers are unusable")
class CatalogFileRecoveryRefusalTest {

    /** Offset of the CRC within a record, and of the trailing CRC of each header slot. */
    private static final int CRC_OFFSET_IN_RECORD = CatalogFile.RECORD_SIZE - 4;

    @TempDir
    Path directory;

    private Path catalogPath;

    private Path writeCatalogWith(int segments) throws IOException {
        catalogPath = directory.resolve("segments.catalog");
        try (CatalogFile file = new CatalogFile(catalogPath)) {
            for (int i = 1; i <= segments; i++) {
                file.writeSegment(new SegmentCatalog.SegmentMetadata(i));
            }
        }
        return catalogPath;
    }

    /** Destroys both header slots, the state a torn header update leaves behind. */
    private void corruptBothHeaderSlots() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(catalogPath.toFile(), "rw")) {
            raf.seek(CatalogFile.HEADER_SLOT_SIZE - 4);
            raf.writeInt(0xDEADBEEF);
            raf.seek(CatalogFile.HEADER_SIZE - 4);
            raf.writeInt(0xDEADBEEF);
        }
    }

    /** Destroys the CRC of one record, leaving every other record intact. */
    private void corruptRecord(int index) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(catalogPath.toFile(), "rw")) {
            raf.seek(CatalogFile.HEADER_SIZE
                + (long) index * CatalogFile.RECORD_SIZE + CRC_OFFSET_IN_RECORD);
            raf.writeInt(0xDEADBEEF);
        }
    }

    @Nested
    @DisplayName("records behind a damaged one")
    class RecordsBehindADamagedOne {

        @Test
        @DisplayName("a damaged first record does not discard the intact records behind it")
        void aDamagedFirstRecordDoesNotDiscardTheRest() throws IOException {
            // Recovery counted the records at the *head* of the file that still checksummed
            // and stopped at the first that did not, so one bad row at index 0 threw away
            // every intact row behind it. Each record carries its own CRC precisely so that
            // damage to one says nothing about the others.
            writeCatalogWith(3);
            corruptBothHeaderSlots();
            corruptRecord(0);

            try (CatalogFile reopened = new CatalogFile(catalogPath)) {
                assertThat(reopened.readAll())
                    .as("the two records behind the damaged one still checksum")
                    .hasSize(2)
                    .extracting(SegmentCatalog.SegmentMetadata::getSegmentId)
                    .containsExactlyInAnyOrder(2L, 3L);
            }
        }

        @Test
        @DisplayName("a damaged record in the middle costs only itself")
        void aDamagedMiddleRecordCostsOnlyItself() throws IOException {
            writeCatalogWith(3);
            corruptBothHeaderSlots();
            corruptRecord(1);

            try (CatalogFile reopened = new CatalogFile(catalogPath)) {
                assertThat(reopened.readAll())
                    .extracting(SegmentCatalog.SegmentMetadata::getSegmentId)
                    .containsExactlyInAnyOrder(1L, 3L);
            }
        }
    }

    @Nested
    @DisplayName("nothing readable at all")
    class NothingReadableAtAll {

        @Test
        @DisplayName("a catalog with nothing readable refuses to open rather than coming up empty")
        void refusesRatherThanResetting() throws IOException {
            // The old path wrote a fresh empty header over the file. The corpus survives -
            // segments are rediscovered from the filesystem - but every segment's lifecycle
            // state, integrated offset, source and epochs are gone, and the node reports the
            // result as a first start. Refusing is what gives an operator something to act on.
            writeCatalogWith(2);
            corruptBothHeaderSlots();
            corruptRecord(0);
            corruptRecord(1);

            assertThatThrownBy(() -> new CatalogFile(catalogPath))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(catalogPath.toString());
        }

        @Test
        @DisplayName("the refusal leaves the file alone, so a later build can still read it")
        void theRefusalWritesNothing() throws IOException {
            writeCatalogWith(2);
            corruptBothHeaderSlots();
            corruptRecord(0);
            corruptRecord(1);
            byte[] before = Files.readAllBytes(catalogPath);

            assertThatThrownBy(() -> new CatalogFile(catalogPath))
                .isInstanceOf(IOException.class);

            assertThat(Files.readAllBytes(catalogPath))
                .as("a refusal that rewrote the header would destroy the evidence")
                .isEqualTo(before);
        }

        @Test
        @DisplayName("a file that was never written is a first start, not a corruption")
        void anUnwrittenFileIsAFirstStart() throws IOException {
            // A crash between creating the file and writing its first header leaves zeroes.
            // Nothing has been lost there, so refusing would strand a node on a file that
            // holds nothing.
            catalogPath = directory.resolve("segments.catalog");
            Files.write(catalogPath, new byte[CatalogFile.HEADER_SIZE + CatalogFile.RECORD_SIZE]);

            try (CatalogFile fresh = new CatalogFile(catalogPath)) {
                assertThat(fresh.readAll()).isEmpty();
            }
        }

        @Test
        @DisplayName("an empty file is a first start too")
        void anEmptyFileIsAFirstStart() throws IOException {
            catalogPath = directory.resolve("segments.catalog");
            Files.write(catalogPath, new byte[0]);

            try (CatalogFile fresh = new CatalogFile(catalogPath)) {
                assertThat(fresh.readAll()).isEmpty();
            }
        }
    }
}
