package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileInspector;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.check.GraphStoreCheck;
import com.eonmux.excalibur.fsck.repair.Quarantine;
import com.eonmux.excalibur.fsck.repair.RepairAction;
import com.eonmux.excalibur.fsck.repair.RepairExecutor;
import com.eonmux.excalibur.fsck.repair.RepairKind;
import com.eonmux.excalibur.fsck.repair.RepairOutcome;
import com.eonmux.excalibur.fsck.repair.RepairPlan;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant's built graph as this tool surveys and repairs it.
 *
 * <p>Built through the one build path the node uses, so the files judged here are the files a
 * node writes: the edge store, both dictionaries, the entity vectors and both Lucene indexes.</p>
 */
@DisplayName("Built graph check")
class GraphStoreCheckTest {

    private static final String NAMESPACE = "shared";
    private static final String GRAPH = NAMESPACE + "/graph";
    private static final int DIMENSION = 4;

    private static Provenance from(long document) {
        return Provenance.of(SourceType.DOCUMENT, document, 0.9, 1);
    }

    /** A data directory holding one namespace with staging and a graph built from it. */
    private static Path builtNamespace(Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        Path namespace = Files.createDirectories(dataDir.resolve(NAMESPACE));
        try (GraphStagingStore staging = GraphStagingStore.open(
                namespace.resolve("graph-staging"))) {
            staging.append(ExtractedTriple.alwaysValid(
                "Ada Lovelace", "collaborated_with", "Charles Babbage", from(1)));
            staging.append(ExtractedTriple.alwaysValid(
                "Charles Babbage", "designed", "Analytical Engine", from(1)));
            staging.appendEntityText(staging.entityIdOf("Ada Lovelace"),
                "Ada Lovelace wrote the first published algorithm.");
            staging.appendEntityText(staging.entityIdOf("Charles Babbage"),
                "Charles Babbage designed the Analytical Engine.");
            staging.flush();
            GraphDirectoryBuilder.build(staging, namespace.resolve("graph"),
                SupersessionPolicy.NONE, 4.0, texts -> texts.stream()
                    .map(text -> new float[] {text.length(), 1f, 2f, 3f}).toList(),
                DIMENSION, published -> published);
        }
        return dataDir;
    }

    private static DataDirectoryScan scan(Path dataDir) throws IOException {
        return new DataDirectoryScanner(dataDir).scan();
    }

    private static List<FileCheck> under(DataDirectoryScan scan, String root) {
        return scan.files().stream()
            .filter(file -> file.relative().startsWith(root + "/"))
            .toList();
    }

    private static FileCheck verdictOn(DataDirectoryScan scan, String relative) {
        return scan.files().stream()
            .filter(file -> file.relative().equals(relative))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no verdict on " + relative + " in "
                + scan.files().stream().map(FileCheck::relative).toList()));
    }

    /** The largest file of a Lucene index other than its commit point and lock. */
    private static Path luceneDataFile(Path index) throws IOException {
        try (Stream<Path> files = Files.list(index)) {
            return files
                .filter(file -> !file.getFileName().toString().startsWith("segments"))
                .filter(file -> !file.getFileName().toString().equals("write.lock"))
                .max((a, b) -> Long.compare(a.toFile().length(), b.toFile().length()))
                .orElseThrow();
        }
    }

    /**
     * Resolves a file of the graph by role, returning it and the verdict that judges it.
     *
     * @return {file, relative path of the verdict}
     */
    private static String[] target(Path dataDir, String which) throws IOException {
        Path graph = dataDir.resolve(GRAPH);
        return switch (which) {
            case "labels", "documents" -> new String[] {
                luceneDataFile(graph.resolve(which)).toString(), GRAPH + "/" + which};
            default -> new String[] {graph.resolve(which).toString(), GRAPH + "/" + which};
        };
    }

    private static void flip(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x20;
        Files.write(file, bytes);
    }

    private static void truncate(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
    }

    private static List<RepairAction> graphActions(RepairPlan plan) {
        return plan.actions().stream()
            .filter(action -> action.relative().startsWith(NAMESPACE + "/"))
            .toList();
    }

    @Test
    @DisplayName("a built namespace is recognised in full and every graph file is proven")
    void builtNamespaceIsIntact(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        assertThat(dataDir.resolve(GRAPH).resolve(EntityVectors.FILE_NAME)).exists();

        DataDirectoryScan scan = scan(dataDir);

        assertThat(scan.files()).allSatisfy(file -> {
            assertThat(FileClassifier.classify(file.relative()))
                .as(file.relative())
                .isNotEqualTo(FileKind.UNRECOGNISED);
            assertThat(file.format()).as(file.relative()).isNotEqualTo("unrecognised");
        });
        List<FileCheck> graph = under(scan, GRAPH);
        assertThat(graph).extracting(FileCheck::relative).contains(
            GRAPH + "/" + EdgeStoreDescriptor.FILE_NAME,
            GRAPH + "/" + EdgeColumn.EDGES_FWD.fileName(),
            GRAPH + "/" + GraphBuilder.ENTITY_DICTIONARY + ".idx",
            GRAPH + "/" + GraphBuilder.RELATION_DICTIONARY + ".dat",
            GRAPH + "/" + EntityVectors.FILE_NAME,
            GRAPH + "/" + GraphDirectoryBuilder.LABEL_INDEX,
            GRAPH + "/" + GraphDirectoryBuilder.DOCUMENT_INDEX);
        assertThat(graph).allSatisfy(file -> assertThat(file.status())
            .as(file.relative() + ": " + file.detail())
            .isEqualTo(CheckStatus.INTACT));
        assertThat(graph).as("one verdict per column, dictionary file, vectors and index")
            .hasSize(1 + EdgeColumn.values().length + 4 + 1 + 2);
        assertThat(scan.hasDamage()).isFalse();
        assertThat(RepairPlanner.plan(scan).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("each file of a graph, taken alone, is a part judged with the whole")
    void perFileVerdictDefersToTheGraph(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        String relative = GRAPH + "/" + EdgeColumn.EDGES_FWD.fileName();

        var inspection = FileInspector.inspect(dataDir.resolve(relative), relative);

        assertThat(inspection.kind()).isEqualTo(FileKind.GRAPH_STORE_PART);
        assertThat(inspection.check().status()).isEqualTo(CheckStatus.UNCHECKED);
        assertThat(inspection.check().detail()).contains("judged with the graph");
    }

    @ParameterizedTest(name = "a flipped byte in {0}")
    @ValueSource(strings = {"edgestore.meta", "edges_fwd.csr", "offsets_rev.idx",
        "extraction_conf.col", "entities.idx", "entities.dat", "relations.idx", "relations.dat",
        "entity_vectors.vec", "labels", "documents"})
    @DisplayName("damage anywhere is corrupt, and the whole graph is moved aside once")
    void flippedByte(String which, @TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        String[] target = target(dataDir, which);
        flip(Path.of(target[0]));

        DataDirectoryScan scan = scan(dataDir);

        assertThat(verdictOn(scan, target[1]).status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(scan.hasDamage()).isTrue();
        RepairPlan plan = RepairPlanner.plan(scan);
        assertThat(graphActions(plan)).singleElement().satisfies(action -> {
            assertThat(action.kind()).isEqualTo(RepairKind.QUARANTINE_CORRUPT_FILE);
            assertThat(action.relative()).isEqualTo(GRAPH);
            assertThat(action.rationale()).contains("/v1/graph/build", "never move it back");
        });
    }

    @ParameterizedTest(name = "{0} cut short")
    @ValueSource(strings = {"edgestore.meta", "rel_rev.csr", "relations.dat",
        "entity_vectors.vec", "documents"})
    @DisplayName("a truncated file is corrupt")
    void truncated(String which, @TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        String[] target = target(dataDir, which);
        truncate(Path.of(target[0]));

        DataDirectoryScan scan = scan(dataDir);

        assertThat(verdictOn(scan, target[1]).status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(graphActions(RepairPlanner.plan(scan))).singleElement()
            .extracting(RepairAction::relative).isEqualTo(GRAPH);
    }

    @Test
    @DisplayName("a missing file is missing, which is damage the graph is moved aside for")
    void missingFile(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        Files.delete(dataDir.resolve(GRAPH).resolve(EdgeColumn.VALID_TO.fileName()));
        Files.delete(dataDir.resolve(GRAPH).resolve("entities.dat"));

        DataDirectoryScan scan = scan(dataDir);

        assertThat(verdictOn(scan, GRAPH + "/" + EdgeColumn.VALID_TO.fileName()).status())
            .isEqualTo(CheckStatus.MISSING);
        assertThat(verdictOn(scan, GRAPH + "/entities.dat").status())
            .isEqualTo(CheckStatus.MISSING);
        assertThat(scan.hasDamage()).isTrue();
        assertThat(graphActions(RepairPlanner.plan(scan)))
            .as("two faults, one move")
            .singleElement().extracting(RepairAction::relative).isEqualTo(GRAPH);
    }

    @Test
    @DisplayName("an empty graph directory is refused by the node, and reported so")
    void emptyGraphDirectory(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir.resolve(GRAPH));

        DataDirectoryScan scan = scan(dataDir);

        assertThat(verdictOn(scan, GRAPH + "/" + EdgeStoreDescriptor.FILE_NAME).status())
            .isEqualTo(CheckStatus.MISSING);
        assertThat(scan.hasDamage()).isTrue();
    }

    @Test
    @DisplayName("the repair moves the damaged graph aside whole, and fsck is then clean")
    void repairMovesTheGraph(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        flip(dataDir.resolve(GRAPH).resolve(EdgeColumn.EDGES_REV.fileName()));
        RepairPlan plan = RepairPlanner.plan(scan(dataDir));

        List<RepairOutcome> outcomes;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8)) {
            outcomes = new RepairExecutor(new Quarantine(tempDir.resolve("q")), out).apply(plan);
        }

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.applied())
            .as(outcome.detail()).isTrue());
        assertThat(dataDir.resolve(GRAPH)).doesNotExist();
        assertThat(dataDir.resolve(NAMESPACE).resolve("graph-staging"))
            .as("staging, which rebuilds the graph, is untouched")
            .isDirectory();
        try (Stream<Path> quarantined = Files.list(tempDir.resolve("q"))) {
            assertThat(quarantined.toList()).singleElement()
                .satisfies(copy -> assertThat(copy.resolve(EdgeColumn.EDGES_REV.fileName()))
                    .exists());
        }
        DataDirectoryScan after = scan(dataDir);
        assertThat(after.hasDamage()).isFalse();
    }

    @Nested
    @DisplayName("beside a displaced copy")
    class Displaced {

        @Test
        @DisplayName("a copy beside the served graph is left for the node to delete")
        void besideServed(@TempDir Path tempDir) throws IOException {
            Path dataDir = builtNamespace(tempDir);
            copyTree(dataDir.resolve(GRAPH), dataDir.resolve(GRAPH + ".previous"));
            flip(dataDir.resolve(GRAPH + ".previous").resolve("entities.dat"));

            DataDirectoryScan scan = scan(dataDir);

            assertThat(under(scan, GRAPH + ".previous")).isNotEmpty().allSatisfy(file -> {
                assertThat(file.status()).isEqualTo(CheckStatus.UNCHECKED);
                assertThat(file.detail()).contains("deletes it on its next open");
            });
            assertThat(scan.hasDamage()).isFalse();
            assertThat(RepairPlanner.plan(scan).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a copy with nothing served in its place is checked as the graph")
        void aloneIsJudged(@TempDir Path tempDir) throws IOException {
            Path dataDir = builtNamespace(tempDir);
            Files.move(dataDir.resolve(GRAPH), dataDir.resolve(GRAPH + ".retired"));

            DataDirectoryScan clean = scan(dataDir);
            assertThat(under(clean, GRAPH + ".retired")).allSatisfy(file ->
                assertThat(file.status()).isEqualTo(CheckStatus.INTACT));

            flip(dataDir.resolve(GRAPH + ".retired").resolve("relations.idx"));
            DataDirectoryScan damaged = scan(dataDir);
            assertThat(damaged.hasDamage()).isTrue();
            assertThat(graphActions(RepairPlanner.plan(damaged))).singleElement()
                .extracting(RepairAction::relative).isEqualTo(GRAPH + ".retired");
        }

        @Test
        @DisplayName("a damaged graph is not moved while a copy the node would restore is "
            + "beside it")
        void damagedServedIsPutToAnOperator(@TempDir Path tempDir) throws IOException {
            Path dataDir = builtNamespace(tempDir);
            copyTree(dataDir.resolve(GRAPH), dataDir.resolve(GRAPH + ".previous"));
            flip(dataDir.resolve(GRAPH).resolve(EdgeColumn.EDGES_FWD.fileName()));

            RepairPlan plan = RepairPlanner.plan(scan(dataDir));

            assertThat(graphActions(plan)).isEmpty();
            assertThat(plan.needsOperator()).singleElement().asString()
                .startsWith(GRAPH + " is damaged")
                .contains("graph.previous", "erasure");
        }

        @Test
        @DisplayName("the move is refused at the moment of acting if a copy has since appeared")
        void refusedAtApplyTime(@TempDir Path tempDir) throws IOException {
            Path dataDir = builtNamespace(tempDir);
            flip(dataDir.resolve(GRAPH).resolve(EdgeColumn.EDGES_FWD.fileName()));
            RepairPlan plan = RepairPlanner.plan(scan(dataDir));
            Files.createDirectories(dataDir.resolve(GRAPH + ".retired"));

            List<RepairOutcome> outcomes;
            try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8)) {
                outcomes = new RepairExecutor(new Quarantine(tempDir.resolve("q")), out)
                    .apply(plan);
            }

            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.applied()).isFalse();
                assertThat(outcome.detail()).contains("graph.retired");
            });
            assertThat(dataDir.resolve(GRAPH)).isDirectory();
        }
    }

    @Test
    @DisplayName("an interner counts as having damage to cut as its open would judge it")
    void internersWithDamageAreJudgedAsTheOpenJudgesThem(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = builtNamespace(tempDir);
        Path graph = dataDir.resolve(GRAPH);
        Path staging = dataDir.resolve(NAMESPACE + "/graph-staging");
        assertThat(GraphStoreCheck.internersWithDamageBeside(graph)).isEmpty();

        // A creation cut short held no id, and its open asks nobody.
        Files.write(staging.resolve(GraphStagingStore.RELATION_LABELS),
            Arrays.copyOf(com.eonmux.excalibur.graph.file.LogHeader.of(
                com.eonmux.excalibur.graph.staging.LabelInterner.MAGIC,
                com.eonmux.excalibur.graph.staging.LabelInterner.VERSION), 5));
        assertThat(GraphStoreCheck.internersWithDamageBeside(graph)).isEmpty();

        // Bytes that are not an interner at all: what its open would do cannot be told.
        Files.writeString(staging.resolve(GraphStagingStore.ENTITY_LABELS),
            "not a label interner, and longer than its header");
        assertThat(GraphStoreCheck.internersWithDamageBeside(graph))
            .containsExactly(staging.resolve(GraphStagingStore.ENTITY_LABELS).toAbsolutePath());
    }

    @Test
    @DisplayName("the move is refused at the moment of acting if a staging interner beside the "
        + "graph has since been found with damage to cut")
    void refusedAtApplyTimeOverADamagedInterner(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        flip(dataDir.resolve(GRAPH).resolve(EdgeColumn.EDGES_FWD.fileName()));
        RepairPlan plan = RepairPlanner.plan(scan(dataDir));
        Path interner = dataDir.resolve(NAMESPACE + "/graph-staging")
            .resolve(GraphStagingStore.RELATION_LABELS);
        byte[] labels = Files.readAllBytes(interner);
        labels[labels.length - Integer.BYTES - 1] ^= 0x40;
        Files.write(interner, labels);

        List<RepairOutcome> outcomes;
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8)) {
            outcomes = new RepairExecutor(new Quarantine(tempDir.resolve("q")), out).apply(plan);
        }

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.detail()).contains(GraphStagingStore.RELATION_LABELS);
        });
        assertThat(dataDir.resolve(GRAPH)).isDirectory();
    }

    @Test
    @DisplayName("scratch a build or a compaction left is recognised, unchecked and left alone")
    void scratch(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        copyTree(dataDir.resolve(GRAPH), dataDir.resolve(GRAPH + ".building"));
        Files.writeString(dataDir.resolve(GRAPH + ".building").resolve("edgestore.meta.tmp"), "x");
        copyTree(dataDir.resolve(GRAPH), dataDir.resolve(GRAPH + ".compacting"));
        Files.writeString(dataDir.resolve(GRAPH + ".compacting")
            .resolve(EntityVectors.FILE_NAME + ".body"), "x");

        DataDirectoryScan scan = scan(dataDir);

        assertThat(under(scan, GRAPH + ".building")).isNotEmpty().allSatisfy(file -> {
            assertThat(file.status()).isEqualTo(CheckStatus.UNCHECKED);
            assertThat(file.detail()).contains("deletes it when the namespace next opens")
                .contains("before it discharges an erasure");
        });
        assertThat(under(scan, GRAPH + ".compacting")).isNotEmpty().allSatisfy(file -> {
            assertThat(file.status()).isEqualTo(CheckStatus.UNCHECKED);
            assertThat(file.detail()).contains("erasure may have been interrupted")
                .contains("deletes it when the namespace next opens")
                .contains("before it discharges");
        });
        assertThat(scan.hasDamage()).isFalse();
        assertThat(RepairPlanner.plan(scan).isEmpty())
            .as("moving the compaction's scratch would hide an interrupted erasure from the node")
            .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"triples.log.erasing", "triples.log.rewriting",
        "entity_text.log.rewriting", "entities.labels.rewriting", "relations.labels.rewriting"})
    @DisplayName("a staging rewrite that did not finish is left for the node, which deletes it on "
        + "open, rather than moved where it would keep what an erasure removed")
    void aStagingRewriteLeftoverIsLeftForTheNode(String name, @TempDir Path tempDir)
            throws IOException {
        assertThat(GraphStagingStore.UNFINISHED_REWRITES).contains(name);
        Path dataDir = builtNamespace(tempDir);
        String relative = NAMESPACE + "/graph-staging/" + name;
        Files.writeString(dataDir.resolve(relative), "Ada Lovelace wrote the first algorithm.");

        DataDirectoryScan scan = scan(dataDir);

        FileCheck verdict = verdictOn(scan, relative);
        assertThat(verdict.status()).isEqualTo(CheckStatus.UNCHECKED);
        assertThat(verdict.detail()).contains("deletes it when the namespace next opens");
        RepairPlan plan = RepairPlanner.plan(scan);
        assertThat(plan.actions())
            .noneMatch(action -> action.kind() == RepairKind.QUARANTINE_STAGING_LEFTOVER);
        assertThat(Files.exists(dataDir.resolve(relative))).isTrue();
    }

    @Test
    @DisplayName("a leftover with a staging rewrite's name outside graph staging is still "
        + "quarantined")
    void aLeftoverElsewhereIsStillQuarantined(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        String relative = NAMESPACE + "/triples.log.rewriting";
        Files.writeString(dataDir.resolve(relative), "x");

        RepairPlan plan = RepairPlanner.plan(scan(dataDir));

        assertThat(plan.actions())
            .anyMatch(action -> action.kind() == RepairKind.QUARANTINE_STAGING_LEFTOVER);
    }

    @Test
    @DisplayName("a file a build does not write is recognised as part of the graph, unchecked")
    void strayFile(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        Files.writeString(dataDir.resolve(GRAPH).resolve("notes.txt"), "hello");

        DataDirectoryScan scan = scan(dataDir);

        FileCheck stray = verdictOn(scan, GRAPH + "/notes.txt");
        assertThat(stray.status()).isEqualTo(CheckStatus.UNCHECKED);
        assertThat(stray.format()).isEqualTo(GraphStoreCheck.FORMAT);
        assertThat(scan.hasDamage()).isFalse();
    }

    @Test
    @DisplayName("a graph written before checksums is readable and reported unprotected")
    void legacy(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        Path graph = dataDir.resolve(GRAPH);
        EdgeStoreDescriptor current = EdgeStoreDescriptor.read(graph);
        new EdgeStoreDescriptor(EdgeStoreDescriptor.OLDEST_VERSION, current.byteOrder(),
            current.entityIdBytes(), current.edgeOffsetBytes(), current.relationIdBytes(),
            current.entityCount(), current.edgeCount(), Map.of()).write(graph);
        for (String dictionary : List.of(GraphBuilder.ENTITY_DICTIONARY,
                GraphBuilder.RELATION_DICTIONARY)) {
            Path index = graph.resolve(dictionary + ".idx");
            byte[] bytes = Files.readAllBytes(index);
            byte[] legacy = Arrays.copyOf(bytes, bytes.length - LabelDictionary.TRAILER_BYTES);
            ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(8, LabelDictionary.OLDEST_VERSION);
            Files.write(index, legacy);
        }

        DataDirectoryScan scan = scan(dataDir);

        FileCheck descriptor = verdictOn(scan, GRAPH + "/" + EdgeStoreDescriptor.FILE_NAME);
        assertThat(descriptor.status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(descriptor.detail()).contains("next build replaces it");
        assertThat(verdictOn(scan, GRAPH + "/entities.idx").status())
            .isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(scan.hasDamage()).isFalse();
        assertThat(scan.hasUnverified()).as("--strict asks about it").isTrue();
        assertThat(RepairPlanner.plan(scan).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a graph a newer build wrote is unreadable, and put to an operator")
    void newer(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        Path descriptorFile = dataDir.resolve(GRAPH).resolve(EdgeStoreDescriptor.FILE_NAME);
        byte[] bytes = Files.readAllBytes(descriptorFile);
        ByteBuffer.wrap(bytes).putInt(8, EdgeStoreDescriptor.CURRENT_VERSION + 1);
        Files.write(descriptorFile, bytes);

        DataDirectoryScan scan = scan(dataDir);

        assertThat(verdictOn(scan, GRAPH + "/" + EdgeStoreDescriptor.FILE_NAME).status())
            .isEqualTo(CheckStatus.UNREADABLE);
        assertThat(scan.hasDamage()).isTrue();
        RepairPlan plan = RepairPlanner.plan(scan);
        assertThat(graphActions(plan)).as("nothing shows the bytes are damaged").isEmpty();
        assertThat(plan.needsOperator()).anySatisfy(note ->
            assertThat(note).startsWith(GRAPH + "/" + EdgeStoreDescriptor.FILE_NAME));
    }

    @Test
    @DisplayName("the interrupted-erasure marker is recognised, and its presence is its content")
    void erasureMarker(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        Path marker = dataDir.resolve(NAMESPACE).resolve("graph-staging")
            .resolve("erasure.interrupted");
        Files.createFile(marker);
        String relative = NAMESPACE + "/graph-staging/erasure.interrupted";

        FileCheck empty = verdictOn(scan(dataDir), relative);
        assertThat(FileClassifier.classify(relative)).isEqualTo(FileKind.GRAPH_ERASURE_MARKER);
        assertThat(empty.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(empty.detail()).contains("withheld");

        Files.writeString(marker, "unexpected");
        FileCheck written = verdictOn(scan(dataDir), relative);
        assertThat(written.status()).isEqualTo(CheckStatus.UNCHECKED);
    }

    @Test
    @DisplayName("the unpublished-changes marker is recognised, and its presence is its content")
    void unpublishedMarker(@TempDir Path tempDir) throws IOException {
        Path dataDir = builtNamespace(tempDir);
        Path marker = dataDir.resolve(NAMESPACE).resolve("graph-staging")
            .resolve(GraphStagingStore.UNPUBLISHED_MARKER);
        Files.deleteIfExists(marker);
        Files.createFile(marker);
        String relative = NAMESPACE + "/graph-staging/" + GraphStagingStore.UNPUBLISHED_MARKER;

        FileCheck empty = verdictOn(scan(dataDir), relative);
        assertThat(FileClassifier.classify(relative))
            .isEqualTo(FileKind.GRAPH_UNPUBLISHED_MARKER);
        assertThat(empty.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(empty.detail()).contains("behind");

        Files.writeString(marker, "unexpected");
        FileCheck written = verdictOn(scan(dataDir), relative);
        assertThat(written.status()).isEqualTo(CheckStatus.UNCHECKED);
    }

    @Test
    @DisplayName("names outside a graph directory are not taken for one")
    void classification() {
        assertThat(GraphStoreCheck.isGraphPart("shared/graph/edges_fwd.csr")).isTrue();
        assertThat(GraphStoreCheck.isGraphPart("shared/graph/labels/_0.cfs")).isTrue();
        assertThat(GraphStoreCheck.isGraphPart("shared/graph.compacting/x")).isTrue();
        assertThat(GraphStoreCheck.isGraphPart("graph/edges_fwd.csr"))
            .as("a graph sits in a namespace directory").isFalse();
        assertThat(GraphStoreCheck.isGraphPart("shared/graph")).isFalse();
        assertThat(GraphStoreCheck.isGraphPart("shared/graph-staging/triples.log")).isFalse();
        assertThat(GraphStoreCheck.rootOf("shared/graph.previous/labels/_0.cfs"))
            .isEqualTo("shared/graph.previous");
    }

    private static void copyTree(Path source, Path target) throws IOException {
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(source)) {
            entries = new ArrayList<>(walk.toList());
        }
        for (Path entry : entries) {
            Path copy = target.resolve(source.relativize(entry).toString());
            if (Files.isDirectory(entry)) {
                Files.createDirectories(copy);
            } else {
                Files.copy(entry, copy);
            }
        }
    }
}
