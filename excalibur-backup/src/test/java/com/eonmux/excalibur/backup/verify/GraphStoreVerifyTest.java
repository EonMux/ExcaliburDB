package com.eonmux.excalibur.backup.verify;

import com.eonmux.excalibur.backup.snapshot.SnapshotOptions;
import com.eonmux.excalibur.backup.snapshot.SnapshotWriter;
import com.eonmux.excalibur.backup.testing.DataDirectoryFixture;
import com.eonmux.excalibur.graph.dictionary.LabelDictionary;
import com.eonmux.excalibur.graph.format.EdgeColumn;
import com.eonmux.excalibur.graph.format.EdgeStoreDescriptor;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;
import com.eonmux.excalibur.graph.vector.EntityVectors;
import com.eonmux.excalibur.retrieval.build.GraphDirectoryBuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant's built graph in a snapshot, as {@code verify --deep} judges it.
 *
 * <p>Damage is put into the data directory before the snapshot is taken, so the image's own
 * digests describe the damaged bytes and only the format checks can find it: the question here
 * is whether the graph's checksums are consulted, not whether the copy was faithful.</p>
 */
@DisplayName("Deep verification of a built graph")
class GraphStoreVerifyTest {

    private static final String GRAPH = "shared/graph";

    @TempDir
    Path tempDir;

    private static Provenance from(long document) {
        return Provenance.of(SourceType.DOCUMENT, document, 0.9, 1);
    }

    /** An engine directory with one namespace whose graph was built from its staging. */
    private Path dataDirectory() throws IOException {
        Path dataDir = DataDirectoryFixture.realEngineDirectory(tempDir.resolve("data"), 20);
        Path namespace = Files.createDirectories(dataDir.resolve("shared"));
        try (GraphStagingStore staging = GraphStagingStore.open(
                namespace.resolve("graph-staging"))) {
            staging.append(ExtractedTriple.alwaysValid(
                "Ada Lovelace", "collaborated_with", "Charles Babbage", from(1)));
            staging.append(ExtractedTriple.alwaysValid(
                "Charles Babbage", "designed", "Analytical Engine", from(1)));
            staging.appendEntityText(staging.entityIdOf("Ada Lovelace"),
                "Ada Lovelace wrote the first published algorithm.");
            staging.flush();
            GraphDirectoryBuilder.build(staging, namespace.resolve("graph"),
                SupersessionPolicy.NONE, 4.0, texts -> texts.stream()
                    .map(text -> new float[] {text.length(), 1f, 2f, 3f}).toList(),
                4, published -> published);
        }
        return dataDir;
    }

    private VerificationReport deepVerify(Path dataDir) throws IOException {
        Path snapshotDir = tempDir.resolve("snap");
        new SnapshotWriter().take(new SnapshotOptions(dataDir, snapshotDir, true, false));
        return new SnapshotVerifier().verify(snapshotDir, VerifyDepth.DEEP);
    }

    private static List<ContentCheck> under(VerificationReport report, String root) {
        return report.contentChecks().stream()
            .filter(check -> check.path().startsWith(root + "/"))
            .toList();
    }

    @Test
    @DisplayName("an intact graph is proven file by file, and index by index")
    void intact() throws IOException {
        Path dataDir = dataDirectory();

        VerificationReport report = deepVerify(dataDir);

        assertThat(report.isValid()).as("%s", report.problems()).isTrue();
        List<ContentCheck> graph = under(report, GRAPH);
        assertThat(graph).extracting(ContentCheck::path).contains(
            GRAPH + "/" + EdgeStoreDescriptor.FILE_NAME,
            GRAPH + "/" + EdgeColumn.PERM_REV.fileName(),
            GRAPH + "/relations.idx",
            GRAPH + "/" + EntityVectors.FILE_NAME,
            GRAPH + "/" + GraphDirectoryBuilder.LABEL_INDEX,
            GRAPH + "/" + GraphDirectoryBuilder.DOCUMENT_INDEX);
        assertThat(graph).allSatisfy(check -> {
            assertThat(check.healthy()).as("%s: %s", check.path(), check.detail()).isTrue();
            assertThat(check.proven()).as("%s: %s", check.path(), check.detail()).isTrue();
        });
        assertThat(graph).as("no file of the graph is left with its unproven per-file verdict")
            .noneSatisfy(check -> assertThat(check.detail()).contains("judged with the graph"));
    }

    @ParameterizedTest(name = "a flipped byte in {0}")
    @ValueSource(strings = {"edgestore.meta", "valid_from.col", "entities.dat", "relations.idx",
        "entity_vectors.vec", "labels"})
    @DisplayName("damage the image faithfully copied is still found")
    void flipped(String which) throws IOException {
        Path dataDir = dataDirectory();
        Path graph = dataDir.resolve(GRAPH);
        Path file = which.equals("labels") ? luceneDataFile(graph.resolve(which))
            : graph.resolve(which);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x10;
        Files.write(file, bytes);

        VerificationReport report = deepVerify(dataDir);

        assertThat(report.isValid()).isFalse();
        assertThat(report.problems()).anySatisfy(problem ->
            assertThat(problem.path()).isEqualTo(GRAPH + "/" + which));
    }

    @Test
    @DisplayName("a file cut short before the snapshot is found")
    void truncated() throws IOException {
        Path dataDir = dataDirectory();
        Path column = dataDir.resolve(GRAPH).resolve(EdgeColumn.SOURCE_ID.fileName());
        byte[] bytes = Files.readAllBytes(column);
        Files.write(column, Arrays.copyOf(bytes, bytes.length - 1));

        VerificationReport report = deepVerify(dataDir);

        assertThat(report.problems()).anySatisfy(problem ->
            assertThat(problem.path()).isEqualTo(GRAPH + "/" + EdgeColumn.SOURCE_ID.fileName()));
    }

    @Test
    @DisplayName("a graph written before checksums restores, and is reported unproven")
    void legacy() throws IOException {
        Path dataDir = dataDirectory();
        Path graph = dataDir.resolve(GRAPH);
        EdgeStoreDescriptor current = EdgeStoreDescriptor.read(graph);
        new EdgeStoreDescriptor(EdgeStoreDescriptor.OLDEST_VERSION, current.byteOrder(),
            current.entityIdBytes(), current.edgeOffsetBytes(), current.relationIdBytes(),
            current.entityCount(), current.edgeCount(), Map.of()).write(graph);
        Path index = graph.resolve(GraphBuilder.ENTITY_DICTIONARY + ".idx");
        byte[] bytes = Files.readAllBytes(index);
        byte[] legacy = Arrays.copyOf(bytes, bytes.length - LabelDictionary.TRAILER_BYTES);
        ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(8, LabelDictionary.OLDEST_VERSION);
        Files.write(index, legacy);

        VerificationReport report = deepVerify(dataDir);

        assertThat(report.isValid()).as("%s", report.problems()).isTrue();
        assertThat(under(report, GRAPH)).filteredOn(check ->
                check.path().endsWith(EdgeStoreDescriptor.FILE_NAME)
                    || check.path().endsWith("entities.idx"))
            .hasSize(2)
            .allSatisfy(check -> {
                assertThat(check.proven()).isFalse();
                assertThat(check.detail()).startsWith("LEGACY_UNPROTECTED");
            });
    }

    @Test
    @DisplayName("a graph a newer build wrote fails, since nothing here can read it")
    void newer() throws IOException {
        Path dataDir = dataDirectory();
        Path descriptor = dataDir.resolve(GRAPH).resolve(EdgeStoreDescriptor.FILE_NAME);
        byte[] bytes = Files.readAllBytes(descriptor);
        ByteBuffer.wrap(bytes).putInt(8, EdgeStoreDescriptor.CURRENT_VERSION + 1);
        Files.write(descriptor, bytes);

        VerificationReport report = deepVerify(dataDir);

        assertThat(report.problems()).anySatisfy(problem -> {
            assertThat(problem.path()).isEqualTo(GRAPH + "/" + EdgeStoreDescriptor.FILE_NAME);
            assertThat(problem.detail()).contains("UNREADABLE");
        });
    }

    @Test
    @DisplayName("an image without a graph is folded to itself")
    void noGraph() throws IOException {
        List<ContentCheck> checks = List.of(new ContentCheck("segment_0.dat", "segment", true,
            true, "fine"));

        assertThat(FormatChecks.withGraphStores(tempDir, checks)).isEqualTo(checks);
    }

    private static Path luceneDataFile(Path index) throws IOException {
        try (Stream<Path> files = Files.list(index)) {
            return files
                .filter(file -> !file.getFileName().toString().startsWith("segments"))
                .filter(file -> !file.getFileName().toString().equals("write.lock"))
                .max((a, b) -> Long.compare(a.toFile().length(), b.toFile().length()))
                .orElseThrow();
        }
    }
}
