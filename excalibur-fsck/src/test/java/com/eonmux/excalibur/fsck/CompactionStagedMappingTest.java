package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.ConsistencyFinding;
import com.eonmux.excalibur.fsck.check.CrossFileChecks;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.check.FindingSeverity;
import com.eonmux.excalibur.fsck.repair.Quarantine;
import com.eonmux.excalibur.fsck.repair.RepairAction;
import com.eonmux.excalibur.fsck.repair.RepairExecutor;
import com.eonmux.excalibur.fsck.repair.RepairKind;
import com.eonmux.excalibur.fsck.repair.RepairOutcome;
import com.eonmux.excalibur.fsck.repair.RepairPlan;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping an HNSW compaction stages, which is scratch or state depending on its neighbour.
 *
 * <p>A compaction builds {@code segment_N.hnsw.compact} and stages the mapping in the compacted
 * numbering as {@code segment_N.hnsw.compact.docmap}, renames the graph over
 * {@code segment_N.hnsw}, then renames the mapping over {@code segment_N.hnsw.docmap}. A crash
 * before the first rename leaves the pair, which the next open discards. A crash between the
 * two leaves the staged mapping alone, and it is then the only mapping that matches the graph:
 * the next open installs it. What is pinned here is that this tool tells the two apart the
 * way the engine does, tidies the first, and never lays a hand on the second.</p>
 */
class CompactionStagedMappingTest {

    private static final String GRAPH = "segment_0.hnsw";
    private static final String COMPACTED_GRAPH = "segment_0.hnsw.compact";
    private static final String STAGED_MAPPING = "segment_0.hnsw.compact.docmap";

    /** Captured stdout and exit code of one invocation. */
    private record Invocation(int exitCode, String out) {
    }

    private static Invocation invoke(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code;
        try (PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8)) {
            code = ExcaliburFsck.run(args, stream, stream);
        }
        return new Invocation(code, out.toString(StandardCharsets.UTF_8));
    }

    /**
     * Writes a sealed mapping with the class that owns the format.
     *
     * @param file Where to write it
     * @throws IOException if it cannot be written
     */
    private static void writeMapping(Path file) throws IOException {
        NodeDocumentMapping mapping = new NodeDocumentMapping(file);
        mapping.register(0L, "source:doc-0:chunk:0");
        mapping.register(1L, "source:doc-1:chunk:0");
        mapping.persist();
    }

    private static FileCheck verdictOn(DataDirectoryScan scan, String relative) {
        return scan.files().stream().filter(f -> f.relative().equals(relative))
            .findFirst().orElseThrow();
    }

    private static List<String> findingKindsFor(DataDirectoryScan scan, String relative) {
        return scan.findings().stream().filter(f -> f.subject().equals(relative))
            .map(ConsistencyFinding::kind).toList();
    }

    @Test
    void theStagedMappingHasAKindOfItsOwnAndCarriesItsSegmentNumber() {
        assertThat(FileClassifier.classify(STAGED_MAPPING))
            .isEqualTo(FileKind.HNSW_STAGED_DOCUMENT_MAP);
        assertThat(FileClassifier.classify(COMPACTED_GRAPH))
            .isEqualTo(FileKind.STAGING_LEFTOVER);
        assertThat(FileClassifier.segmentNumber("segment_7.hnsw.compact.docmap")).isEqualTo(7);
        assertThat(FileClassifier.compactedGraphOf(STAGED_MAPPING)).isEqualTo(COMPACTED_GRAPH);
        assertThat(FileClassifier.liveMappingOf(STAGED_MAPPING))
            .isEqualTo("segment_0.hnsw.docmap");
    }

    @Test
    void aNameThatIsNotAStagedMappingIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> FileClassifier.compactedGraphOf("segment_0.hnsw.docmap"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void besideItsCompactedGraphTheStagedMappingIsALeftoverMovedAsideFirst(
            @TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        Files.write(dataDir.resolve(COMPACTED_GRAPH), new byte[256]);
        writeMapping(dataDir.resolve(STAGED_MAPPING));

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        FileCheck staged = verdictOn(scan, STAGED_MAPPING);
        assertThat(staged.status())
            .as("scratch the node discards is reported as scratch, not read as state")
            .isEqualTo(CheckStatus.UNCHECKED);
        assertThat(staged.format()).isEqualTo("staging leftover");
        assertThat(findingKindsFor(scan, STAGED_MAPPING)).containsExactly("staging-leftover");
        assertThat(scan.findings())
            .noneMatch(f -> f.kind().equals(CrossFileChecks.PENDING_MAPPING));

        List<String> order = RepairPlanner.plan(scan).actions().stream()
            .filter(a -> a.kind() == RepairKind.QUARANTINE_STAGING_LEFTOVER)
            .map(RepairAction::relative).toList();
        assertThat(order).containsSubsequence(STAGED_MAPPING, COMPACTED_GRAPH);

        Path quarantine = tempDir.resolve("q");
        invoke(dataDir.toString(), "--repair", "--quarantine-dir=" + quarantine);
        assertThat(dataDir.resolve(STAGED_MAPPING)).doesNotExist();
        assertThat(dataDir.resolve(COMPACTED_GRAPH)).doesNotExist();
        assertThat(quarantine.resolve(STAGED_MAPPING)).exists();
        assertThat(quarantine.resolve(COMPACTED_GRAPH)).exists();
    }

    @Test
    void aloneTheStagedMappingIsVerifiedAndReportedAsPendingAndNeverRepaired(
            @TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        writeMapping(dataDir.resolve(STAGED_MAPPING));

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        FileCheck staged = verdictOn(scan, STAGED_MAPPING);
        assertThat(staged.status()).as(staged.detail()).isEqualTo(CheckStatus.INTACT);
        assertThat(staged.format()).startsWith("staged ");
        assertThat(staged.detail()).contains("pending", "segment_0.hnsw.docmap");
        assertThat(findingKindsFor(scan, STAGED_MAPPING))
            .containsExactly(CrossFileChecks.PENDING_MAPPING);
        assertThat(scan.findings()).filteredOn(f -> f.subject().equals(STAGED_MAPPING))
            .allMatch(f -> f.severity() == FindingSeverity.WARNING);
        assertThat(scan.hasDamage()).isFalse();

        RepairPlan plan = RepairPlanner.plan(scan);
        assertThat(plan.actions()).noneMatch(a -> a.relative().equals(STAGED_MAPPING));

        assertThat(staged.unverifiedContent())
            .as("a sealed mapping is proven, so --strict has nothing in it to fail on")
            .isFalse();

        Invocation repair = invoke(dataDir.toString(), "--repair",
            "--quarantine-dir=" + tempDir.resolve("q"));
        assertThat(repair.exitCode())
            .as("a directory part way through a recovery the engine completes is not damage\n"
                + repair.out())
            .isZero();
        assertThat(repair.out()).contains(CrossFileChecks.PENDING_MAPPING);
        assertThat(dataDir.resolve(STAGED_MAPPING)).as("never moved by a repair").exists();
    }

    @Test
    void aDamagedStagedMappingIsPutToAnOperatorAndLeftInPlace(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        Files.write(dataDir.resolve(STAGED_MAPPING), new byte[64]);

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();
        FileCheck staged = verdictOn(scan, STAGED_MAPPING);
        assertThat(staged.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(staged.needsOperator()).isTrue();
        assertThat(scan.hasDamage()).isTrue();

        RepairPlan plan = RepairPlanner.plan(scan);
        assertThat(plan.actions()).noneMatch(a -> a.relative().equals(STAGED_MAPPING));
        assertThat(plan.needsOperator()).anySatisfy(item ->
            assertThat(item).contains(STAGED_MAPPING, "wrong documents"));

        Invocation repair = invoke(dataDir.toString(), "--repair",
            "--quarantine-dir=" + tempDir.resolve("q"));
        assertThat(repair.exitCode()).isNotZero();
        assertThat(dataDir.resolve(STAGED_MAPPING)).hasBinaryContent(new byte[64]);
    }

    @Test
    void aStagedMappingWhoseSegmentIsGoneIsAnOrphanNotAPendingInstall(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        String orphan = "segment_9001.hnsw.compact.docmap";
        writeMapping(dataDir.resolve(orphan));

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        assertThat(findingKindsFor(scan, orphan)).containsExactly("index-without-segment");
        assertThat(RepairPlanner.plan(scan).actions()).anySatisfy(action -> {
            assertThat(action.relative()).isEqualTo(orphan);
            assertThat(action.kind()).isEqualTo(RepairKind.QUARANTINE_ORPHANED_INDEX);
        });
    }

    @Test
    void theCompactedGraphIsNotMovedWhileItsStagedMappingIsStillBesideIt(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path compacted = dataDir.resolve(COMPACTED_GRAPH);
        Files.write(compacted, new byte[32]);
        writeMapping(dataDir.resolve(STAGED_MAPPING));
        RepairAction moveGraph = RepairAction.onFile(RepairKind.QUARANTINE_STAGING_LEFTOVER,
            compacted, COMPACTED_GRAPH, "move " + COMPACTED_GRAPH, "leftover");

        List<RepairOutcome> outcomes;
        try (PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8)) {
            outcomes = new RepairExecutor(new Quarantine(tempDir.resolve("q")), sink)
                .apply(new RepairPlan(List.of(moveGraph), List.of()));
        }

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.detail()).contains(STAGED_MAPPING);
        });
        assertThat(compacted).as("moving the graph alone would make the node install the "
            + "staged mapping over a graph that was never compacted").exists();
    }

    @Test
    void aGraphWithNoStagedMappingBesideItIsMovedAsBefore(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path compacted = dataDir.resolve(COMPACTED_GRAPH);
        Files.write(compacted, new byte[32]);
        Files.write(dataDir.resolve(GRAPH), new byte[32]);
        RepairAction moveGraph = RepairAction.onFile(RepairKind.QUARANTINE_STAGING_LEFTOVER,
            compacted, COMPACTED_GRAPH, "move " + COMPACTED_GRAPH, "leftover");

        List<RepairOutcome> outcomes;
        try (PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8)) {
            outcomes = new RepairExecutor(new Quarantine(tempDir.resolve("q")), sink)
                .apply(new RepairPlan(List.of(moveGraph), List.of()));
        }

        assertThat(outcomes).singleElement()
            .satisfies(outcome -> assertThat(outcome.applied()).isTrue());
        assertThat(compacted).doesNotExist();
    }
}
