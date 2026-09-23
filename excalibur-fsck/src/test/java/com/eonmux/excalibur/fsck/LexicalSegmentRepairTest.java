package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.repair.RepairKind;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.retrieval.lexical.native_.LexicalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A damaged lexical segment, as this tool reports and repairs it and as the node then treats it.
 *
 * <p>The two have to agree. This tool finds a segment whose column fails its checksum and moves
 * it aside; the node keeps such a segment out of every query whether or not it is still there,
 * and rebuilds it from its key table and the stored text at its next compaction. So the repair
 * plan says what the node will do, and a directory repaired by it comes back whole.</p>
 */
class LexicalSegmentRepairTest {

    private static final String SEGMENT = "chunk-index/seg-0";

    /** What storage holds, which is what a rebuild reads the text back from. */
    private final Map<String, String> storage = new LinkedHashMap<>();

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

    /** A data directory whose chunk index holds two segments, the first of them damaged. */
    private Path damagedStore(Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        storage.put("a1", "quorum replication latency");
        storage.put("a2", "quorum election timeout");
        storage.put("b1", "quorum snapshot transfer");
        try (LexicalStore store = LexicalStore.open(dataDir.resolve("chunk-index"))) {
            store.add(Map.of("a1", storage.get("a1"), "a2", storage.get("a2")), 1);
            store.add(Map.of("b1", storage.get("b1")), 2);
        }
        Path postings = dataDir.resolve(SEGMENT).resolve("post.dat");
        byte[] bytes = Files.readAllBytes(postings);
        bytes[0] ^= 0x01;
        Files.write(postings, bytes);
        return dataDir;
    }

    private static FileCheck verdictOn(DataDirectoryScan scan, String relative) {
        return scan.files().stream().filter(f -> f.relative().equals(relative))
            .findFirst().orElseThrow();
    }

    @Test
    void theDamageIsFoundAndTheRepairSaysWhatTheNodeWillDo(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = damagedStore(tempDir);

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        assertThat(verdictOn(scan, SEGMENT).status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(verdictOn(scan, SEGMENT).detail()).contains("post.dat");
        assertThat(scan.hasDamage())
            .as("until a compaction rebuilds it, keyword search is missing its chunks")
            .isTrue();
        assertThat(RepairPlanner.plan(scan).actions()).anySatisfy(action -> {
            assertThat(action.relative()).isEqualTo(SEGMENT);
            assertThat(action.kind()).isEqualTo(RepairKind.QUARANTINE_CORRUPT_FILE);
            assertThat(action.rationale())
                .contains("key table")
                .contains("next compaction")
                .doesNotContain("reindexing");
        });
        assertThat(RepairPlanner.plan(scan).needsOperator()).isEmpty();
        assertThat(invoke(dataDir.toString()).exitCode()).isEqualTo(1);
    }

    /**
     * A damaged manifest is the one part of the store this tool must not move aside: the node
     * opens a store without one as a new, empty index and deletes every segment it does not
     * name.
     */
    @Test
    void aDamagedManifestIsLeftForTheOperator(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        try (LexicalStore store = LexicalStore.open(dataDir.resolve("chunk-index"))) {
            store.add(Map.of("a1", "quorum replication latency"), 1);
            store.add(Map.of("b1", "quorum snapshot transfer"), 2);
        }
        Path manifest = dataDir.resolve("chunk-index").resolve("manifest");
        List<String> lines = new ArrayList<>(Files.readAllLines(manifest));
        lines.remove(1);
        Files.write(manifest, lines);

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        assertThat(verdictOn(scan, "chunk-index/manifest").status())
            .isEqualTo(CheckStatus.CORRUPT);
        assertThat(RepairPlanner.plan(scan).actions())
            .noneSatisfy(action -> assertThat(action.relative())
                .isEqualTo("chunk-index/manifest"));
        assertThat(RepairPlanner.plan(scan).needsOperator())
            .anySatisfy(question -> assertThat(question)
                .startsWith("chunk-index/manifest is damaged")
                .contains("not moved aside"));

        invoke(dataDir.toString(), "--repair", "--quarantine-dir=" + tempDir.resolve("q"));

        assertThat(manifest).exists();
        assertThat(dataDir.resolve(SEGMENT)).as("no segment is lost to the repair").exists();
    }

    @Test
    void aStoreRepairedByThisToolIsRebuiltByTheNode(@TempDir Path tempDir) throws IOException {
        Path dataDir = damagedStore(tempDir);
        Path quarantine = tempDir.resolve("q");

        Invocation repair = invoke(dataDir.toString(), "--repair",
            "--quarantine-dir=" + quarantine);

        assertThat(dataDir.resolve(SEGMENT)).as(repair.out()).doesNotExist();
        assertThat(quarantine.resolve("chunk-index_seg-0").resolve("post.dat"))
            .as("every byte is kept").exists();

        try (LexicalStore store = LexicalStore.open(dataDir.resolve("chunk-index"))) {
            assertThat(store.damagedSegments()).singleElement()
                .satisfies(segment -> assertThat(segment.rebuildable()).isTrue());

            CompactionResult rebuilt = store.compactIfDue(storage::get, 0);

            assertThat(rebuilt.segmentsRebuilt()).isEqualTo(1);
            assertThat(store.liveDocuments(99)).isEqualTo(3);
            assertThat(store.search(new String[] {"quorum"}, new double[] {1.0}, 10, 99))
                .extracting(LexicalStore.Hit::key)
                .containsExactlyInAnyOrder("a1", "a2", "b1");
        }
        assertThat(invoke(dataDir.toString()).exitCode())
            .as("after the rebuild nothing in the directory is damaged")
            .isZero();
    }
}
