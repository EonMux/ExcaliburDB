package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.filter.sketch.CountMinSketch;
import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.ConsistencyFinding;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.check.FindingSeverity;
import com.eonmux.excalibur.fsck.check.TermSketchCheck;
import com.eonmux.excalibur.fsck.repair.RepairKind;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.store.TermSketchFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A segment's term-frequency sketch, the one derived file the node rebuilds by itself.
 *
 * <p>The store dates each {@code segment_N.tfs} by a watermark and recomputes the sketch from
 * the segment's live entries on open whenever it is missing, stale or damaged. What is pinned
 * here is that this tool verifies it with the store's own checker, reports a damaged one as
 * the corrupt file it is without letting it fail the run, plans the harmless repair, and
 * treats the sidecar's staged write and a sketch whose segment is gone the way it treats every
 * other staged write and orphan.</p>
 */
class TermSketchCheckTest {

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
     * Writes a sketch with the class that owns the format.
     *
     * @param file Where to write it
     * @throws IOException if it cannot be written
     */
    private static void writeSketch(Path file) throws IOException {
        CountMinSketch sketch = new CountMinSketch(64, 4, 7L);
        sketch.insert("vector".getBytes(StandardCharsets.UTF_8));
        TermSketchFile.write(file, new TermSketchFile.Watermark(3L, 2L, 2L), sketch.toBytes());
    }

    /**
     * Lists the sketches a real store left beside its segments.
     *
     * @param dataDir The data directory
     * @return Every {@code segment_N.tfs}, in name order
     * @throws IOException if the directory cannot be listed
     */
    private static List<Path> sketches(Path dataDir) throws IOException {
        try (var stream = Files.list(dataDir)) {
            return stream.filter(p -> FileKind.TERM_SKETCH_NAME
                    .matcher(p.getFileName().toString()).matches())
                .sorted().toList();
        }
    }

    private static void flipLastByte(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);
    }

    private static FileCheck verdictOn(DataDirectoryScan scan, String relative) {
        return scan.files().stream().filter(f -> f.relative().equals(relative))
            .findFirst().orElseThrow();
    }

    @Test
    void theSketchAndItsStagedWriteAreClassifiedByName() {
        assertThat(FileClassifier.classify("segment_3.tfs")).isEqualTo(FileKind.TERM_SKETCH);
        assertThat(FileClassifier.segmentNumber("segment_3.tfs")).isEqualTo(3);
        assertThat(FileClassifier.classify(
                "segment_3.tfs.0b7e1f7a-3c1d-4e59-9a4f-2a6f1d0c9e11.tmp"))
            .as("the write stages under a unique name and renames; a stranded one is scratch")
            .isEqualTo(FileKind.STAGING_LEFTOVER);
        assertThat(FileClassifier.classify("segment_3.dat.left.tfs"))
            .as("only a segment at its canonical name has a write log, so only it has a sketch")
            .isEqualTo(FileKind.UNRECOGNISED);
    }

    @Test
    void onlyTheSketchIsRebuiltByTheNodeOnOpen() {
        assertThat(Arrays.stream(FileKind.values()).filter(FileKind::rebuiltOnOpen))
            .containsExactly(FileKind.TERM_SKETCH);
    }

    @Test
    void aSketchTheStoreWroteIsIntact(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("segment_0.tfs");
        writeSketch(file);

        FileCheck check = TermSketchCheck.check(file, "segment_0.tfs");

        assertThat(check.status()).as(check.detail()).isEqualTo(CheckStatus.INTACT);
        assertThat(check.sizeBytes()).isEqualTo(Files.size(file));
    }

    @Test
    void anAbsentSketchIsMissing(@TempDir Path tempDir) {
        FileCheck check = TermSketchCheck.check(tempDir.resolve("segment_0.tfs"), "segment_0.tfs");

        assertThat(check.status()).isEqualTo(CheckStatus.MISSING);
        assertThat(check.sizeBytes()).isEqualTo(-1);
    }

    @Test
    void aDamagedSketchIsCorruptButNotDamage(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("segment_0.tfs");
        writeSketch(file);
        flipLastByte(file);

        FileCheck check = TermSketchCheck.check(file, "segment_0.tfs");

        assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(check.isDamage()).isFalse();
        assertThat(check.needsOperator()).isFalse();
        assertThat(check.detail()).contains("rebuilds");
    }

    @Test
    void aSketchTheDeviceRefusesIsUnreadableAndFailsTheRun(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);
        Path sketch = sketches(dataDir).get(0);
        String relative = sketch.getFileName().toString();
        Files.setPosixFilePermissions(sketch, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(sketch),
                "a process that reads through file permissions (root) cannot be refused");

            FileCheck check = TermSketchCheck.check(sketch, relative);
            assertThat(check.status()).as(check.detail()).isEqualTo(CheckStatus.UNREADABLE);
            assertThat(check.isDamage())
                .as("a refused read says something about the device, not about a derived file")
                .isTrue();
            assertThat(check.detail()).doesNotContain("not counted as damage");

            DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();
            assertThat(verdictOn(scan, relative).status()).isEqualTo(CheckStatus.UNREADABLE);
            assertThat(scan.hasDamage()).isTrue();
            assertThat(scan.rebuiltOnOpenCount()).isZero();

            Invocation run = invoke(dataDir.toString());
            assertThat(run.exitCode()).as(run.out()).isNotZero();
        } finally {
            Files.setPosixFilePermissions(sketch, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void aSketchThatCannotBeReadIsStillDamage(@TempDir Path tempDir) {
        FileCheck unreadable = new FileCheck(tempDir.resolve("segment_0.tfs"), "segment_0.tfs",
            "term frequency sketch", null, CheckStatus.UNREADABLE, "permission denied", -1);
        FileCheck corruptGraph = new FileCheck(tempDir.resolve("segment_0.hnsw"),
            "segment_0.hnsw", "HNSW graph", null, CheckStatus.CORRUPT, "bad checksum", 1);

        assertThat(unreadable.isDamage())
            .as("an unreadable file says something about the device, whatever its kind")
            .isTrue();
        assertThat(corruptGraph.isDamage())
            .as("a graph is rebuilt only by a reindex, so its damage fails the run")
            .isTrue();
    }

    @Test
    void theSketchesARealStoreWritesVerify(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);

        List<Path> sketches = sketches(dataDir);
        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        assertThat(sketches).as("a clean close persists each segment's sketch").isNotEmpty();
        for (Path sketch : sketches) {
            FileCheck check = verdictOn(scan, sketch.getFileName().toString());
            assertThat(check.status()).as(check.detail()).isEqualTo(CheckStatus.INTACT);
        }
        assertThat(scan.files()).filteredOn(f -> f.format().equals("term frequency sketch"))
            .as("a clean store's sketches are proven, so --strict has nothing in them to fail on")
            .hasSameSizeAs(sketches)
            .allMatch(f -> f.status().isProven() && !f.unverifiedContent());
    }

    @Test
    void aDamagedSketchIsReportedPlannedForQuarantineAndDoesNotFailTheRun(
            @TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);
        Path sketch = sketches(dataDir).get(0);
        String relative = sketch.getFileName().toString();
        int strictBefore = invoke(dataDir.toString(), "--strict").exitCode();
        flipLastByte(sketch);

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();
        assertThat(verdictOn(scan, relative).status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(scan.hasDamage()).isFalse();
        assertThat(scan.rebuiltOnOpenCount()).isEqualTo(1);
        assertThat(RepairPlanner.plan(scan).actions()).anySatisfy(action -> {
            assertThat(action.relative()).isEqualTo(relative);
            assertThat(action.kind()).isEqualTo(RepairKind.QUARANTINE_CORRUPT_FILE);
            assertThat(action.rationale()).contains("rebuild");
        });

        Invocation check = invoke(dataDir.toString());
        assertThat(check.exitCode()).as(check.out()).isZero();
        assertThat(check.out()).contains(relative, "CORRUPT", "not counted as damage");
        assertThat(invoke(dataDir.toString(), "--strict").exitCode())
            .as("a damaged sketch neither is damage nor leaves anything unverified")
            .isEqualTo(strictBefore);

        Invocation json = invoke(dataDir.toString(), "--json");
        JsonNode root = new ObjectMapper().readTree(json.out());
        assertThat(root.get("damageFound").asBoolean()).isFalse();
        assertThat(root.get("summary").get("corruptRebuiltOnOpen").asInt()).isEqualTo(1);
        assertThat(root.get("files")).anySatisfy(file -> {
            assertThat(file.get("path").asText()).isEqualTo(relative);
            assertThat(file.get("damage").asBoolean()).isFalse();
        });

        Path quarantine = tempDir.resolve("q");
        invoke(dataDir.toString(), "--repair", "--quarantine-dir=" + quarantine);
        assertThat(sketch).doesNotExist();
        assertThat(quarantine.resolve(relative)).exists();
    }

    @Test
    void aStoreOpensAndServesAfterItsDamagedSketchIsSetAside(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);
        flipLastByte(sketches(dataDir).get(0));
        invoke(dataDir.toString(), "--repair", "--quarantine-dir=" + tempDir.resolve("q"));

        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena, 2)) {
            engine.start();
            for (int i = 0; i < 16; i++) {
                assertThat(engine.get(DataDirectoryFixture.key(i))
                        .toArray(ValueLayout.JAVA_BYTE))
                    .isEqualTo(DataDirectoryFixture.value(i));
            }
        }
        assertThat(invoke(dataDir.toString()).exitCode()).isZero();
    }

    @Test
    void aSketchWhoseSegmentIsGoneIsAnOrphanedIndex(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        writeSketch(dataDir.resolve("segment_9001.tfs"));

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        assertThat(scan.findings()).filteredOn(f -> f.subject().equals("segment_9001.tfs"))
            .singleElement().satisfies(finding -> {
                assertThat(finding.kind()).isEqualTo("index-without-segment");
                assertThat(finding.severity()).isEqualTo(FindingSeverity.DAMAGE);
                assertThat(finding.detail()).contains("term sketch", "segment_9001.dat");
            });
        assertThat(RepairPlanner.plan(scan).actions()).anySatisfy(action -> {
            assertThat(action.relative()).isEqualTo("segment_9001.tfs");
            assertThat(action.kind()).isEqualTo(RepairKind.QUARANTINE_ORPHANED_INDEX);
        });
    }

    @Test
    void aStrandedStagedSketchIsALeftover(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        String staged = "segment_0.tfs.0b7e1f7a-3c1d-4e59-9a4f-2a6f1d0c9e11.tmp";
        Files.write(dataDir.resolve(staged), new byte[40]);

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        assertThat(scan.findings()).filteredOn(f -> f.subject().equals(staged))
            .extracting(ConsistencyFinding::kind).containsExactly("staging-leftover");
        assertThat(RepairPlanner.plan(scan).actions()).anySatisfy(action -> {
            assertThat(action.relative()).isEqualTo(staged);
            assertThat(action.kind()).isEqualTo(RepairKind.QUARANTINE_STAGING_LEFTOVER);
        });
    }
}
