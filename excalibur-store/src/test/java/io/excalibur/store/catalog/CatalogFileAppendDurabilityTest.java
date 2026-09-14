package io.excalibur.store.catalog;

import io.excalibur.core.mapped.MappedFile;
import io.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What must be true the moment a segment gains a catalog row.
 *
 * <p>A catalog row is the only record that a segment file belongs to this store. The file
 * itself is created and fsynced by the segment; the row that names it lives in a memory
 * mapping, and a mapping that is never msynced is durable only at the kernel's convenience. A
 * host that loses power therefore comes back with the segment file on disk and no row naming
 * it, which is the one shape of inconsistency the startup path cannot repair - it either
 * adopts the file as a brand new empty segment or refuses to start.</p>
 *
 * <p>The durability point is the append: the rare write that brings a segment into existence.
 * Every other catalog write is an update to a row that already exists - entry-count deltas
 * happen on every put - and forcing those would turn each write into a synchronous msync.</p>
 */
@DisplayName("Catalog append durability and ordering")
class CatalogFileAppendDurabilityTest {

    @TempDir
    Path tempDir;

    /**
     * A mapping that counts msyncs and can be told to refuse to grow.
     */
    private static final class InstrumentedMapping extends MappedFile {

        private final AtomicInteger flushes = new AtomicInteger();
        private volatile boolean refuseToGrow;

        InstrumentedMapping(Path path, long size) throws IOException {
            super(path, size);
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
            super.flush();
        }

        @Override
        public long size() {
            return refuseToGrow ? 0 : super.size();
        }

        @Override
        public void extend(long extensionSize) throws IOException {
            if (refuseToGrow) {
                throw new IOException("simulated ENOSPC while growing the catalog");
            }
            super.extend(extensionSize);
        }
    }

    /**
     * Swaps the catalog's mapping for an instrumented one over the same file.
     *
     * @param catalogFile The catalog to instrument
     * @return The mapping now in use
     * @throws Exception if the field cannot be replaced
     */
    private static InstrumentedMapping instrument(CatalogFile catalogFile) throws Exception {
        Field field = CatalogFile.class.getDeclaredField("mappedFile");
        field.setAccessible(true);
        MappedFile original = (MappedFile) field.get(catalogFile);
        long size = original.size();
        original.close();
        InstrumentedMapping instrumented = new InstrumentedMapping(catalogFile.getPath(), size);
        field.set(catalogFile, instrumented);
        return instrumented;
    }

    /**
     * Returns metadata for a segment that has never been written before.
     *
     * @param segmentId The segment identifier
     * @return Metadata naming a file in the temporary directory
     */
    private SegmentCatalog.SegmentMetadata metadata(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(segmentId);
        metadata.setVolumePath(tempDir.resolve("segment_" + segmentId + ".dat"));
        return metadata;
    }

    @Test
    @DisplayName("appending a segment row msyncs it before the write returns")
    void anAppendIsForcedToDisk() throws Exception {
        try (CatalogFile catalogFile = new CatalogFile(tempDir.resolve("catalog.dat"))) {
            InstrumentedMapping mapping = instrument(catalogFile);

            catalogFile.writeSegment(metadata(1L));

            assertThat(mapping.flushes.get())
                .as("a new segment row must reach the device before the append returns")
                .isPositive();
        }
    }

    @Test
    @DisplayName("updating a row that already exists does not msync")
    void anUpdateIsNotForcedToDisk() throws Exception {
        try (CatalogFile catalogFile = new CatalogFile(tempDir.resolve("catalog.dat"))) {
            SegmentCatalog.SegmentMetadata metadata = metadata(1L);
            catalogFile.writeSegment(metadata);

            InstrumentedMapping mapping = instrument(catalogFile);
            metadata.setEntryCount(17);
            catalogFile.writeSegment(metadata);

            assertThat(mapping.flushes.get())
                .as("entry-count deltas happen on every put; forcing them would msync per write")
                .isZero();
        }
    }

    @Test
    @DisplayName("a growth that fails leaves the count claiming no entry that was not written")
    void aFailedGrowthDoesNotAdvanceTheCount() throws Exception {
        try (CatalogFile catalogFile = new CatalogFile(tempDir.resolve("catalog.dat"))) {
            catalogFile.writeSegment(metadata(1L));
            catalogFile.writeSegment(metadata(2L));
            catalogFile.writeSegment(metadata(3L));

            InstrumentedMapping mapping = instrument(catalogFile);
            mapping.refuseToGrow = true;

            assertThatThrownBy(() -> catalogFile.writeSegment(metadata(4L)))
                .isInstanceOf(IOException.class);

            assertThat(catalogFile.getSegmentCount())
                .as("the count must not claim a record the growth never made room for")
                .isEqualTo(3);
        }
    }

    @Test
    @DisplayName("a segment appended after a failed growth lands in the next free slot")
    void aLaterAppendIsNotLeftWithAHole() throws Exception {
        Path path = tempDir.resolve("catalog.dat");
        try (CatalogFile catalogFile = new CatalogFile(path)) {
            catalogFile.writeSegment(metadata(1L));
            catalogFile.writeSegment(metadata(2L));

            InstrumentedMapping mapping = instrument(catalogFile);
            mapping.refuseToGrow = true;
            assertThatThrownBy(() -> catalogFile.writeSegment(metadata(3L)))
                .isInstanceOf(IOException.class);

            mapping.refuseToGrow = false;
            catalogFile.writeSegment(metadata(3L));
            catalogFile.flush();
        }

        try (CatalogFile reopened = new CatalogFile(path)) {
            assertThat(reopened.readAll())
                .as("a failed append must not leave an unreadable record between two good ones")
                .hasSize(3);
            assertThat(reopened.getSegmentCount()).isEqualTo(3);
        }
    }
}
