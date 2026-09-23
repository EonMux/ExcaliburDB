package com.eonmux.excalibur.graph.integrity;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import com.eonmux.excalibur.graph.dictionary.LabelDictionary;
import com.eonmux.excalibur.graph.dictionary.LabelDictionaryWriter;
import com.eonmux.excalibur.graph.file.GraphCorruptionException;
import com.eonmux.excalibur.graph.format.EdgeColumn;
import com.eonmux.excalibur.graph.format.EdgeStoreDescriptor;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.vector.EntityVectorWriter;
import com.eonmux.excalibur.graph.vector.EntityVectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every file this module writes into a built graph carries a checksum the node checks at open and
 * an inspection reports on, and the two agree.
 */
class ServedGraphIntegrityTest {

    private static final int ENTITIES = 5;
    private static final int DIMENSION = 3;

    /** Every file a built graph with vectors holds, as this module writes them. */
    static Stream<String> servedFiles() {
        List<String> names = new ArrayList<>();
        names.add(EdgeStoreDescriptor.FILE_NAME);
        for (EdgeColumn column : EdgeColumn.values()) {
            names.add(column.fileName());
        }
        names.add(GraphBuilder.ENTITY_DICTIONARY + ".idx");
        names.add(GraphBuilder.ENTITY_DICTIONARY + ".dat");
        names.add(GraphBuilder.RELATION_DICTIONARY + ".idx");
        names.add(GraphBuilder.RELATION_DICTIONARY + ".dat");
        names.add(EntityVectors.FILE_NAME);
        return names.stream();
    }

    /** Builds a small graph of every kind of file this module writes. */
    private static Path build(Path directory) throws IOException {
        new EdgeStoreWriter()
            .add(0, 0, 1)
            .add(0, 1, 2)
            .add(1, 0, 3)
            .add(3, 1, 4)
            .build(directory, ENTITIES);
        LabelDictionaryWriter entities = new LabelDictionaryWriter();
        for (int id = 0; id < ENTITIES; id++) {
            entities.put(id, "Entity_" + id);
        }
        entities.write(directory, GraphBuilder.ENTITY_DICTIONARY);
        new LabelDictionaryWriter()
            .put(0, "knows")
            .put(1, "worksAt")
            .write(directory, GraphBuilder.RELATION_DICTIONARY);
        try (EntityVectorWriter vectors = new EntityVectorWriter(
                directory.resolve(EntityVectors.FILE_NAME), DIMENSION, ENTITIES)) {
            for (int id = 0; id < ENTITIES; id++) {
                vectors.put(id == 2 ? null : new float[] {id, id + 0.5f, -id});
            }
            vectors.finish();
        }
        return directory;
    }

    /** Opens everything the node opens from this module, the way it does. */
    private static void openAsNode(Path directory) throws IOException {
        try (MappedEdgeStore store = MappedEdgeStore.open(directory);
             LabelDictionary entities = LabelDictionary.open(
                 directory, GraphBuilder.ENTITY_DICTIONARY, "E");
             LabelDictionary relations = LabelDictionary.open(
                 directory, GraphBuilder.RELATION_DICTIONARY, "R");
             EntityVectors vectors = EntityVectors.openIfPresent(directory)) {
            assertThat(store.entityCount()).isEqualTo(ENTITIES);
            assertThat(entities.apply(3)).isEqualTo("Entity_3");
            assertThat(relations.apply(1)).isEqualTo("worksAt");
            assertThat(vectors.vectorOf(1)).containsExactly(1f, 1.5f, -1f);
            assertThat(vectors.vectorOf(2)).isNull();
        }
    }

    private static IntegrityReport reportOn(List<IntegrityReport> reports, Path file) {
        return reports.stream()
            .filter(report -> report.file().equals(file))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no report on " + file + " in " + reports));
    }

    @Test
    @DisplayName("a freshly built graph verifies intact, every file of it")
    void builtGraphIsIntact(@TempDir Path dir) throws IOException {
        build(dir);

        List<IntegrityReport> reports = ServedGraphIntegrity.inspect(dir);

        assertThat(reports).hasSize((int) servedFiles().count());
        assertThat(reports).allSatisfy(report ->
            assertThat(report.status()).as(report.toString()).isEqualTo(IntegrityStatus.INTACT));
        assertThat(reports.stream().map(report -> report.file().getFileName().toString()))
            .containsExactlyInAnyOrderElementsOf(servedFiles().toList());
        assertThatCode(() -> openAsNode(dir)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a graph built without an embedder has no vectors, which is not damage")
    void vectorsAreOptional(@TempDir Path dir) throws IOException {
        build(dir);
        Files.delete(dir.resolve(EntityVectors.FILE_NAME));

        List<IntegrityReport> reports = ServedGraphIntegrity.inspect(dir);

        assertThat(reports).hasSize((int) servedFiles().count() - 1);
        assertThat(reports).allSatisfy(report ->
            assertThat(report.status()).isEqualTo(IntegrityStatus.INTACT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servedFiles")
    @DisplayName("a flipped byte in any file is refused by the node and reported corrupt")
    void flippedByte(String name, @TempDir Path dir) throws IOException {
        build(dir);
        Path file = dir.resolve(name);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x10;
        Files.write(file, bytes);

        assertRefusedAndReported(dir, file);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servedFiles")
    @DisplayName("a file cut short is refused by the node and reported corrupt")
    void truncated(String name, @TempDir Path dir) throws IOException {
        build(dir);
        Path file = dir.resolve(name);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));

        assertRefusedAndReported(dir, file);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servedFiles")
    @DisplayName("a file that is gone is refused by the node and reported")
    void missing(String name, @TempDir Path dir) throws IOException {
        build(dir);
        Path file = dir.resolve(name);
        Files.delete(file);
        if (name.equals(EntityVectors.FILE_NAME)) {
            // Its absence is a graph built without an embedder; see vectorsAreOptional.
            return;
        }

        assertThatThrownBy(() -> openAsNode(dir))
            .isInstanceOf(GraphCorruptionException.class);
        assertThat(reportOn(ServedGraphIntegrity.inspect(dir), file).status())
            .isEqualTo(IntegrityStatus.MISSING);
    }

    private static void assertRefusedAndReported(Path dir, Path file) {
        assertThatThrownBy(() -> openAsNode(dir))
            .isInstanceOf(GraphCorruptionException.class)
            .satisfies(e -> assertThat(((GraphCorruptionException) e).file()).isEqualTo(file));
        List<IntegrityReport> reports = ServedGraphIntegrity.inspect(dir);
        assertThat(reportOn(reports, file).status()).isEqualTo(IntegrityStatus.CORRUPT);
        assertThat(reports)
            .filteredOn(report -> !report.file().equals(file))
            .allSatisfy(report -> assertThat(report.status()).isEqualTo(IntegrityStatus.INTACT));
    }

    @Test
    @DisplayName("a flipped presence flag is caught, not read as an entity losing its vector")
    void flippedPresenceFlag(@TempDir Path dir) throws IOException {
        build(dir);
        Path file = dir.resolve(EntityVectors.FILE_NAME);
        byte[] bytes = Files.readAllBytes(file);
        bytes[EntityVectors.HEADER_BYTES + 1] = 0;
        Files.write(file, bytes);

        assertRefusedAndReported(dir, file);
        assertThat(reportOn(ServedGraphIntegrity.inspect(dir), file).detail())
            .contains("presence flags");
    }

    @Test
    @DisplayName("a dictionary index left beside text from another build is caught even at "
        + "the same length")
    void mismatchedDictionaryPair(@TempDir Path dir) throws IOException {
        build(dir);
        Path other = Files.createDirectory(dir.resolve("other"));
        LabelDictionaryWriter renamed = new LabelDictionaryWriter();
        for (int id = 0; id < ENTITIES; id++) {
            renamed.put(id, "Entity_" + (char) ('a' + id));
        }
        renamed.write(other, GraphBuilder.ENTITY_DICTIONARY);
        Path text = dir.resolve(GraphBuilder.ENTITY_DICTIONARY + ".dat");
        Files.copy(other.resolve(GraphBuilder.ENTITY_DICTIONARY + ".dat"), text,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertRefusedAndReported(dir, text);
        assertThat(reportOn(ServedGraphIntegrity.inspect(dir), text).detail())
            .contains("different build");
    }

    @Nested
    @DisplayName("a graph written before its files carried checksums")
    class Legacy {

        @Test
        @DisplayName("is served, and reported unprotected rather than damaged")
        void readableAndUnprotected(@TempDir Path dir) throws IOException {
            build(dir);
            downgrade(dir);

            assertThatCode(() -> openAsNode(dir)).doesNotThrowAnyException();
            List<IntegrityReport> reports = ServedGraphIntegrity.inspect(dir);
            assertThat(reports).hasSize((int) servedFiles().count());
            assertThat(reports).allSatisfy(report -> {
                assertThat(report.status()).as(report.toString())
                    .isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);
                assertThat(report.detail()).contains("next build");
            });
        }

        @Test
        @DisplayName("is still held to the sizes its headers imply")
        void sizesStillChecked(@TempDir Path dir) throws IOException {
            build(dir);
            downgrade(dir);
            for (String name : List.of(EdgeColumn.EDGES_FWD.fileName(),
                    GraphBuilder.ENTITY_DICTIONARY + ".dat", EntityVectors.FILE_NAME)) {
                Path file = dir.resolve(name);
                byte[] bytes = Files.readAllBytes(file);
                Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));

                assertThatThrownBy(() -> openAsNode(dir))
                    .as(name)
                    .isInstanceOf(GraphCorruptionException.class);
                assertThat(reportOn(ServedGraphIntegrity.inspect(dir), file).status())
                    .as(name)
                    .isEqualTo(IntegrityStatus.CORRUPT);
                Files.write(file, bytes);
            }
        }

        /** Rewrites every file in the layout of the version before checksums. */
        private static void downgrade(Path dir) throws IOException {
            EdgeStoreDescriptor current = EdgeStoreDescriptor.read(dir);
            new EdgeStoreDescriptor(EdgeStoreDescriptor.OLDEST_VERSION, current.byteOrder(),
                current.entityIdBytes(), current.edgeOffsetBytes(), current.relationIdBytes(),
                current.entityCount(), current.edgeCount(), Map.of()).write(dir);
            for (String dictionary : List.of(GraphBuilder.ENTITY_DICTIONARY,
                    GraphBuilder.RELATION_DICTIONARY)) {
                Path index = dir.resolve(dictionary + ".idx");
                byte[] bytes = Files.readAllBytes(index);
                byte[] legacy = Arrays.copyOf(bytes, bytes.length - LabelDictionary.TRAILER_BYTES);
                ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(8, LabelDictionary.OLDEST_VERSION);
                Files.write(index, legacy);
            }
            Path vectors = dir.resolve(EntityVectors.FILE_NAME);
            byte[] bytes = Files.readAllBytes(vectors);
            byte[] legacy = new byte[bytes.length - (EntityVectors.HEADER_BYTES
                - EntityVectors.V1_HEADER_BYTES)];
            System.arraycopy(bytes, 0, legacy, 0, EntityVectors.V1_HEADER_BYTES);
            System.arraycopy(bytes, EntityVectors.HEADER_BYTES, legacy,
                EntityVectors.V1_HEADER_BYTES, bytes.length - EntityVectors.HEADER_BYTES);
            ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(8, EntityVectors.OLDEST_VERSION);
            Files.write(vectors, legacy);
        }
    }

    @Nested
    @DisplayName("a file written by a newer build")
    class Newer {

        @Test
        @DisplayName("is refused as unreadable, never as damage")
        void edgeStore(@TempDir Path dir) throws IOException {
            build(dir);
            Path file = dir.resolve(EdgeStoreDescriptor.FILE_NAME);
            byte[] bytes = Files.readAllBytes(file);
            ByteBuffer.wrap(bytes).putInt(8, EdgeStoreDescriptor.CURRENT_VERSION + 1);
            Files.write(file, bytes);

            assertUnreadable(dir, file, EdgeStoreDescriptor.UnsupportedVersionException.class);
        }

        @Test
        @DisplayName("is refused as unreadable for a dictionary")
        void dictionary(@TempDir Path dir) throws IOException {
            build(dir);
            Path file = dir.resolve(GraphBuilder.RELATION_DICTIONARY + ".idx");
            bumpLittleEndianVersion(file, LabelDictionary.VERSION + 1);

            assertUnreadable(dir, file, IOException.class);
        }

        @Test
        @DisplayName("is refused as unreadable for the vectors")
        void vectors(@TempDir Path dir) throws IOException {
            build(dir);
            Path file = dir.resolve(EntityVectors.FILE_NAME);
            bumpLittleEndianVersion(file, EntityVectors.VERSION + 1);

            assertUnreadable(dir, file, IOException.class);
        }

        private static void bumpLittleEndianVersion(Path file, int version) throws IOException {
            byte[] bytes = Files.readAllBytes(file);
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, version);
            Files.write(file, bytes);
        }

        private static void assertUnreadable(Path dir, Path file,
                                             Class<? extends Exception> refusal) {
            assertThatThrownBy(() -> openAsNode(dir))
                .isInstanceOf(refusal)
                .isNotInstanceOf(GraphCorruptionException.class)
                .hasMessageContaining("version");
            IntegrityReport report = reportOn(ServedGraphIntegrity.inspect(dir), file);
            assertThat(report.status()).isEqualTo(IntegrityStatus.UNREADABLE);
        }
    }

    @Test
    @DisplayName("a file that is not what its name says is refused as damage")
    void foreignFiles(@TempDir Path dir) throws IOException {
        build(dir);
        for (String name : List.of(EdgeStoreDescriptor.FILE_NAME,
                GraphBuilder.ENTITY_DICTIONARY + ".idx", EntityVectors.FILE_NAME)) {
            Path file = dir.resolve(name);
            byte[] original = Files.readAllBytes(file);
            byte[] foreign = original.clone();
            foreign[0] ^= 0x7F;
            Files.write(file, foreign);

            assertThatThrownBy(() -> openAsNode(dir))
                .as(name)
                .isInstanceOf(GraphCorruptionException.class);
            IntegrityReport report = reportOn(ServedGraphIntegrity.inspect(dir), file);
            assertThat(report.status()).as(name).isEqualTo(IntegrityStatus.CORRUPT);
            assertThat(report.detail()).as(name).contains("not a");
            Files.write(file, original);
        }
    }
}
