package com.eonmux.excalibur.graph.format;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import com.eonmux.excalibur.graph.file.GraphCorruptionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeStoreDescriptorTest {

    @Test
    @DisplayName("a descriptor round-trips, column digests included")
    void roundTrip(@TempDir Path dir) throws IOException {
        EdgeStoreDescriptor written = of(1_000, 5_000);
        written.write(dir);

        EdgeStoreDescriptor read = EdgeStoreDescriptor.read(dir);
        assertThat(read).isEqualTo(written);
        assertThat(read.formatVersion()).isEqualTo(EdgeStoreDescriptor.CURRENT_VERSION);
        assertThat(read.checksummed()).isTrue();
        assertThat(read.entityCount()).isEqualTo(1_000);
        assertThat(read.edgeCount()).isEqualTo(5_000);
        assertThat(read.byteOrder()).isEqualTo(ByteOrder.LITTLE_ENDIAN);
        assertThat(read.digestOf(EdgeColumn.EDGES_FWD))
            .isEqualTo(new ColumnDigest(5_000L * Integer.BYTES, EdgeColumn.EDGES_FWD.ordinal()));
    }

    @Test
    @DisplayName("a version 1 descriptor is read, and records no digests")
    void readsLegacyVersion(@TempDir Path dir) throws IOException {
        writeDescriptor(dir, EdgeStoreDescriptor.OLDEST_VERSION, (byte) 4, (byte) 8, (byte) 4);

        EdgeStoreDescriptor read = EdgeStoreDescriptor.read(dir);

        assertThat(read.formatVersion()).isEqualTo(1);
        assertThat(read.checksummed()).isFalse();
        assertThatThrownBy(() -> read.digestOf(EdgeColumn.EDGES_FWD))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a version 1 descriptor is written back in its own layout")
    void writesLegacyLayout(@TempDir Path dir) throws IOException {
        writeDescriptor(dir, EdgeStoreDescriptor.OLDEST_VERSION, (byte) 4, (byte) 8, (byte) 4);
        EdgeStoreDescriptor.read(dir).write(dir);

        assertThat(Files.size(dir.resolve(EdgeStoreDescriptor.FILE_NAME))).isEqualTo(32);
    }

    @Test
    @DisplayName("a flipped byte anywhere in a version 2 descriptor is refused as damage")
    void rejectsFlippedByte(@TempDir Path dir) throws IOException {
        of(10, 20).write(dir);
        Path file = dir.resolve(EdgeStoreDescriptor.FILE_NAME);
        byte[] bytes = Files.readAllBytes(file);
        for (int at : new int[] {20, 40, bytes.length - 1}) {
            byte[] damaged = bytes.clone();
            damaged[at] ^= 0x01;
            Files.write(file, damaged);

            assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
                .as("byte %d", at)
                .isInstanceOf(GraphCorruptionException.class)
                .hasMessageContaining("checksum");
        }
    }

    @Test
    @DisplayName("a version 2 descriptor cut short is refused as damage")
    void rejectsTruncatedTable(@TempDir Path dir) throws IOException {
        of(10, 20).write(dir);
        Path file = dir.resolve(EdgeStoreDescriptor.FILE_NAME);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 3));

        assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
            .isInstanceOf(GraphCorruptionException.class)
            .hasMessageContaining("cut short");
    }

    @Test
    @DisplayName("a missing descriptor is refused as damage, naming the file")
    void rejectsMissing(@TempDir Path dir) {
        assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
            .isInstanceOf(GraphCorruptionException.class)
            .satisfies(e -> assertThat(((GraphCorruptionException) e).file())
                .isEqualTo(dir.resolve(EdgeStoreDescriptor.FILE_NAME)))
            .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("a descriptor far larger than any is refused without being loaded")
    void rejectsOversized(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve(EdgeStoreDescriptor.FILE_NAME), new byte[128 * 1024]);

        assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
            .isInstanceOf(GraphCorruptionException.class)
            .hasMessageContaining("far larger");
    }

    @Test
    @DisplayName("digests that do not describe every column at its size are refused")
    void rejectsIncompleteDigests() {
        Map<EdgeColumn, ColumnDigest> digests = digests(10, 20);
        digests.remove(EdgeColumn.REL_REV);
        assertThatThrownBy(() -> EdgeStoreDescriptor.of(10, 20, digests))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("REL_REV");

        Map<EdgeColumn, ColumnDigest> wrongLength = digests(10, 20);
        wrongLength.put(EdgeColumn.REL_REV, new ColumnDigest(1, 0));
        assertThatThrownBy(() -> EdgeStoreDescriptor.of(10, 20, wrongLength))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recorded at 1 bytes");

        assertThatThrownBy(() -> EdgeStoreDescriptor.of(10, 20, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a version 1 descriptor cannot carry digests")
    void legacyCarriesNoDigests() {
        assertThatThrownBy(() -> new EdgeStoreDescriptor(1, ByteOrder.LITTLE_ENDIAN, 4, 8, 4,
                10, 20, digests(10, 20)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no column digests");
    }

    @Test
    @DisplayName("the widths this build fixed are the ones written")
    void widthsAreFixed(@TempDir Path dir) throws IOException {
        of(10, 10).write(dir);
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
            .as("a foreign file is damage to the store, not a store another build wrote")
            .isInstanceOf(GraphCorruptionException.class)
            .hasMessageContaining("not an edge store");
    }

    @Test
    @DisplayName("a store written with a different entity id width is refused, not reinterpreted")
    void rejectsForeignWidths(@TempDir Path dir) throws IOException {
        // The whole reason the widths are written down: a store built by a hypothetical 64-bit-id
        // build must fail here rather than be read as twice as many half-sized ids.
        writeDescriptor(dir, EdgeStoreDescriptor.OLDEST_VERSION, (byte) 8, (byte) 8, (byte) 4);

        assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
            .isInstanceOf(EdgeStoreDescriptor.IncompatibleStoreException.class)
            .hasMessageContaining("8-byte entity id");
    }

    @Test
    @DisplayName("a store written with a narrower edge offset is refused")
    void rejectsNarrowOffsets(@TempDir Path dir) throws IOException {
        writeDescriptor(dir, EdgeStoreDescriptor.OLDEST_VERSION, (byte) 4, (byte) 4, (byte) 4);

        assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
            .isInstanceOf(EdgeStoreDescriptor.IncompatibleStoreException.class)
            .hasMessageContaining("4-byte edge offset");
    }

    @Test
    @DisplayName("a future format version is refused")
    void rejectsFutureVersion(@TempDir Path dir) throws IOException {
        writeDescriptor(dir, EdgeStoreDescriptor.CURRENT_VERSION + 1, (byte) 4, (byte) 8, (byte) 4);

        assertThatThrownBy(() -> EdgeStoreDescriptor.read(dir))
            .isInstanceOf(EdgeStoreDescriptor.UnsupportedVersionException.class)
            .hasMessageContaining("format version")
            .satisfies(e -> assertThat(((EdgeStoreDescriptor.UnsupportedVersionException) e)
                .version()).isEqualTo(EdgeStoreDescriptor.CURRENT_VERSION + 1));
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
        assertThatThrownBy(() -> EdgeStoreDescriptor.of(1L + Integer.MAX_VALUE, 0, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entity id can address");
    }

    @Test
    @DisplayName("column sizes follow from the counts")
    void columnSizes() {
        EdgeStoreDescriptor descriptor = of(100, 1_000);

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
        of(1, 1).write(dir);
        of(2, 2).write(dir);

        assertThat(EdgeStoreDescriptor.read(dir).entityCount()).isEqualTo(2);
        assertThat(Files.exists(dir.resolve(EdgeStoreDescriptor.FILE_NAME + ".tmp")))
            .as("the staging file is moved, not left behind")
            .isFalse();
    }

    /**
     * A current-version descriptor whose digests have the right lengths and a checksum that
     * differs per column, so a table read back in the wrong order would not compare equal.
     */
    private static EdgeStoreDescriptor of(long entities, long edges) {
        return EdgeStoreDescriptor.of(entities, edges, digests(entities, edges));
    }

    private static Map<EdgeColumn, ColumnDigest> digests(long entities, long edges) {
        Map<EdgeColumn, ColumnDigest> digests = new EnumMap<>(EdgeColumn.class);
        for (EdgeColumn column : EdgeColumn.values()) {
            digests.put(column, new ColumnDigest(column.byteSize(entities, edges),
                column.ordinal()));
        }
        return digests;
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
