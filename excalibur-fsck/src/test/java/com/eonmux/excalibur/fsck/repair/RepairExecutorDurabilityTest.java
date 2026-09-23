package com.eonmux.excalibur.fsck.repair;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * What the repair executor waits for between moves, and what it says when a move gets part of
 * the way.
 *
 * <p>A compaction's staged pair is the case that makes order matter: the mapping must be gone,
 * and its going on the device, before the compacted graph beside it moves, or a crash between
 * the two can bring the mapping back alone - which the node reads as a finished compaction.
 * Directory syncs leave nothing to inspect afterwards, so they are watched through the
 * quarantine's seams, interleaved with the renames they must separate.</p>
 */
class RepairExecutorDurabilityTest {

    private static final String GRAPH = "segment_0.hnsw.compact";

    private static final String MAPPING = "segment_0.hnsw.compact.docmap";

    private static List<RepairOutcome> apply(Quarantine quarantine, RepairAction... actions) {
        try (PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8)) {
            return new RepairExecutor(quarantine, sink)
                .apply(new RepairPlan(List.of(actions), List.of()));
        }
    }

    private static RepairAction leftover(Path file, String relative) {
        return RepairAction.onFile(RepairKind.QUARANTINE_STAGING_LEFTOVER, file, relative,
            "move " + relative, "leftover");
    }

    /**
     * Builds a quarantine that renames and syncs for real, recording each call in order.
     *
     * @param directory The quarantine directory
     * @param events    Where each rename and sync is recorded
     * @return The quarantine
     */
    private static Quarantine watched(Path directory, List<String> events) {
        return new Quarantine(directory, (source, target) -> {
            events.add("rename " + source.getFileName());
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }, dir -> {
            events.add("force " + dir.getFileName());
            Quarantine.forceDirectory(dir);
        });
    }

    @Test
    void theMappingsRemovalIsOnTheDeviceBeforeTheGraphMoves(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Files.write(dataDir.resolve(GRAPH), new byte[32]);
        Files.write(dataDir.resolve(MAPPING), new byte[32]);
        Files.createDirectories(tempDir.resolve("q"));
        List<String> events = new ArrayList<>();

        List<RepairOutcome> outcomes = apply(watched(tempDir.resolve("q"), events),
            leftover(dataDir.resolve(MAPPING), MAPPING), leftover(dataDir.resolve(GRAPH), GRAPH));

        assertThat(outcomes).allSatisfy(outcome ->
            assertThat(outcome.applied()).as(outcome.detail()).isTrue());
        int mappingMoved = events.indexOf("rename " + MAPPING);
        int graphMoved = events.indexOf("rename " + GRAPH);
        assertThat(mappingMoved).isNotNegative().isLessThan(graphMoved);
        assertThat(events.subList(mappingMoved, graphMoved)).contains("force data");
    }

    @Test
    void aMappingRemovedBeforeThisRunIsMadeDurableBeforeTheGraphMoves(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Files.write(dataDir.resolve(GRAPH), new byte[32]);
        Files.createDirectories(tempDir.resolve("q"));
        List<String> events = new ArrayList<>();

        List<RepairOutcome> outcomes = apply(watched(tempDir.resolve("q"), events),
            leftover(dataDir.resolve(GRAPH), GRAPH));

        assertThat(outcomes).singleElement()
            .satisfies(outcome -> assertThat(outcome.applied()).isTrue());
        assertThat(events).as("whoever removed the mapping, its absence is forced first")
            .startsWith("force data", "rename " + GRAPH);
    }

    @Test
    void aGraphIsNotMovedWhenItsDirectoryCannotBeForced(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path graph = dataDir.resolve(GRAPH);
        Files.write(graph, new byte[32]);
        Quarantine quarantine = new Quarantine(tempDir.resolve("q"),
            (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE),
            dir -> {
                throw new IOException("injected");
            });

        List<RepairOutcome> outcomes = apply(quarantine, leftover(graph, GRAPH));

        assertThat(outcomes).singleElement()
            .satisfies(outcome -> assertThat(outcome.applied()).isFalse());
        assertThat(graph).exists();
    }

    @Test
    void anOriginalLeftBehindAfterItsCopyLandedIsReportedWithTheCopyNamed(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path file = dataDir.resolve("segment_3.dat");
        Files.writeString(file, "evidence");
        Quarantine quarantine = new Quarantine(tempDir.resolve("q"), (source, target) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                "Invalid cross-device link");
        });
        Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(dataDir),
                "a process that writes through directory permissions (root) cannot be refused");

            List<RepairOutcome> outcomes = apply(quarantine,
                RepairAction.onFile(RepairKind.QUARANTINE_CORRUPT_FILE, file, "segment_3.dat",
                    "move segment_3.dat", "corrupt"));

            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.applied()).isFalse();
                assertThat(outcome.detail())
                    .contains(tempDir.resolve("q/segment_3.dat").toString(), "still in place")
                    .doesNotContain("exactly as it was");
            });
            assertThat(file).hasContent("evidence");
        } finally {
            Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
