package com.eonmux.excalibur.graph.format;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeStoreDescriptorTest {

    @Test
    @DisplayName("a descriptor round-trips")
    void roundTrip(@TempDir Path dir) throws IOException {
        EdgeStoreDescriptor written = EdgeStoreDescriptor.of(1_000, 5_000);
        written.write(dir);

        EdgeStoreDescriptor read = EdgeStoreDescriptor.read(dir);
        assertThat(read).isEqualTo(written);
        assertThat(read.entityCount()).isEqualTo(1_000);
        assertThat(read.edgeCount()).isEqualTo(5_000);
        assertThat(read.byteOrder()).isEqualTo(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    @DisplayName("the widths this build fixed are the ones written")
    void widthsAreFixed(@TempDir Path dir) throws IOException {
        EdgeStoreDescriptor.of(10, 10).write(dir);
        EdgeStoreDescriptor read = EdgeStoreDescriptor.read(dir);

        assertThat(read.entityIdBytes())
            .as("entity ids are 32-bit: the per-edge array that sets the memory budget")
            .isEqualTo(Integer.BYTES);
        assertThat(read.edgeOffsetBytes())
            .as("edge offsets are 64-bit: edge count crosses 2^31 before entity count does")
            .isEqualTo(Long.BYTES);
        assertThat(read.relationIdBytes()).isEqualTo(Integer.BYTES);
    }

    @Test
    @DisplayName("a file that is not an edge store is rejected by its magic")
    void rejectsForeignFile(@TempDir Path dir) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(dir.resolve(EdgeStoreDescriptor.FILE_NAME)))) {
            out.writeLong(0x0123456789ABCDEFL);
            out.writeInt(1);
            out.writeByte(0);
            out.writeByte(4);
            out.writeByte(8);
            out.writeByte(4);
            out.writeLong(0);
            out.writeLong(0);
        }

        assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
            .isInstanceOf(EdgeStoreDescriptor.IncompatibleStoreException.class)
            .hasMessageContaining("not an edge store");
    }

    @Test
    @DisplayName("a store written with a different entity id width is refused, not reinterpreted")
    void rejectsForeignWidths(@TempDir Path dir) throws IOException {
        // The whole reason the widths are written down: a store built by a hypothetical 64-bit-id
        // build must fail here rather than be read as twice as many half-sized ids.
        writeDescriptor(dir, EdgeStoreDescriptor.CURRENT_VERSION, (byte) 8, (byte) 8, (byte) 4);

        assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
            .isInstanceOf(EdgeStoreDescriptor.IncompatibleStoreException.class)
            .hasMessageContaining("8-byte entity id");
    }

    @Test
    @DisplayName("a store written with a narrower edge offset is refused")
    void rejectsNarrowOffsets(@TempDir Path dir) throws IOException {
        writeDescriptor(dir, EdgeStoreDescriptor.CURRENT_VERSION, (byte) 4, (byte) 4, (byte) 4);

        assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
            .isInstanceOf(EdgeStoreDescriptor.IncompatibleStoreException.class)
            .hasMessageContaining("4-byte edge offset");
    }

    @Test
    @DisplayName("a future format version is refused")
    void rejectsFutureVersion(@TempDir Path dir) throws IOException {
        writeDescriptor(dir, EdgeStoreDescriptor.CURRENT_VERSION + 1, (byte) 4, (byte) 8, (byte) 4);

        assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
            .isInstanceOf(EdgeStoreDescriptor.IncompatibleStoreException.class)
            .hasMessageContaining("format version");
    }

    @Test
    @DisplayName("a truncated descriptor reports truncation rather than reading garbage")
    void rejectsTruncated(@TempDir Path dir) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(dir.resolve(EdgeStoreDescriptor.FILE_NAME)))) {
            out.writeLong(EdgeStoreDescriptor.MAGIC);
            out.writeInt(EdgeStoreDescriptor.CURRENT_VERSION);
            // Stops here: the widths and counts never arrive.
        }

        assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("truncated");
    }

    @Test
    @DisplayName("an entity count beyond what a 32-bit id can address is refused")
    void rejectsUnaddressableEntityCount() {
        assertThatThrownBy(() -> EdgeStoreDescriptor.of(1L + Integer.MAX_VALUE, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entity id can address");
    }

    @Test
    @DisplayName("column sizes follow from the counts")
    void columnSizes() {
        EdgeStoreDescriptor descriptor = EdgeStoreDescriptor.of(100, 1_000);

        assertThat(descriptor.byteSizeOf(EdgeColumn.EDGES_FWD)).isEqualTo(1_000L * Integer.BYTES);
        assertThat(descriptor.byteSizeOf(EdgeColumn.SOURCE_ID)).isEqualTo(1_000L * Long.BYTES);
        assertThat(descriptor.byteSizeOf(EdgeColumn.SOURCE_TYPE)).isEqualTo(1_000L);
        assertThat(descriptor.byteSizeOf(EdgeColumn.EXTRACTOR_VERSION))
            .isEqualTo(1_000L * Short.BYTES);
        assertThat(descriptor.byteSizeOf(EdgeColumn.OFFSETS_FWD))
            .as("one offset per entity plus a terminator, so every run is [o[e], o[e+1])")
            .isEqualTo(101L * Long.BYTES);
    }

    @Test
    @DisplayName("writing replaces an existing descriptor atomically")
    void writeIsAtomic(@TempDir Path dir) throws IOException {
        EdgeStoreDescriptor.of(1, 1).write(dir);
        EdgeStoreDescriptor.of(2, 2).write(dir);

        assertThat(EdgeStoreDescriptor.read(dir).entityCount()).isEqualTo(2);
        assertThat(Files.exists(dir.resolve(EdgeStoreDescriptor.FILE_NAME + ".tmp")))
            .as("the staging file is moved, not left behind")
            .isFalse();
    }

    private static void writeDescriptor(Path dir, int version,
                                        byte entityIdBytes, byte edgeOffsetBytes,
                                        byte relationIdBytes) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(dir.resolve(EdgeStoreDescriptor.FILE_NAME)))) {
            out.writeLong(EdgeStoreDescriptor.MAGIC);
            out.writeInt(version);
            out.writeByte(0);
            out.writeByte(entityIdBytes);
            out.writeByte(edgeOffsetBytes);
            out.writeByte(relationIdBytes);
            out.writeLong(0);
            out.writeLong(0);
        }
    }
}
