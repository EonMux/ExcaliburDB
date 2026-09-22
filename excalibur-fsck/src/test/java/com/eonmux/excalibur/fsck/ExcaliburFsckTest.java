package com.eonmux.excalibur.fsck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command's contract: what it says about a directory, and what it does to one.
 *
 * <p>Driven through {@link ExcaliburFsck#run} rather than through the checker classes,
 * because the exit code and the output are the whole interface a deploy gate has.</p>
 */
class ExcaliburFsckTest {

    /** Captured stdout, stderr and exit code of one invocation. */
    private record Invocation(int exitCode, String out, String err) {
    }

    /**
     * Runs the command with arguments, capturing everything it produced.
     *
     * @param args The command line
     * @return What it printed and returned
     */
    private static Invocation invoke(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = ExcaliburFsck.run(args, outStream, errStream);
        }
        return new Invocation(code,
            out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void cleanDirectoryReportsCleanAndExitsZero(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);

        Invocation result = invoke(dataDir.toString());

        assertThat(result.exitCode())
            .as("a healthy directory must gate a deploy open\n%s%s", result.out(), result.err())
            .isZero();
        assertThat(result.out()).contains("catalog.dat");
        assertThat(result.out()).containsIgnoringCase("no damage");
    }

    @Test
    void flippedByteInSegmentIsReportedAndExitsNonZero(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);
        Path damaged = DataDirectoryFixture.flipPayloadByteInSomeSegment(dataDir);

        Invocation result = invoke(dataDir.toString());

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.out()).contains(damaged.getFileName().toString());
        assertThat(result.out()).containsIgnoringCase("corrupt");
    }

    @Test
    void checkModeNeverModifiesTheDirectory(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);
        DataDirectoryFixture.flipPayloadByteInSomeSegment(dataDir);
        Files.writeString(dataDir.resolve("segment_404.hnsw"), "not an index");
        Map<String, String> before = DataDirectoryFixture.fingerprint(dataDir);

        invoke(dataDir.toString());
        invoke(dataDir.toString(), "--json");
        invoke(dataDir.toString(), "--strict");

        assertThat(DataDirectoryFixture.fingerprint(dataDir))
            .as("check mode must leave every byte, size and timestamp as it found them")
            .isEqualTo(before);
    }

    @Test
    void repairQuarantinesRatherThanDeletes(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);
        Path damaged = DataDirectoryFixture.flipPayloadByteInSomeSegment(dataDir);
        byte[] damagedBytes = Files.readAllBytes(damaged);
        Path quarantine = tempDir.resolve("quarantine");

        Invocation result = invoke(
            dataDir.toString(), "--repair", "--quarantine-dir=" + quarantine);

        assertThat(result.out()).containsIgnoringCase("quarantine");
        assertThat(Files.exists(damaged))
            .as("the corrupt file must be moved aside, not left in place")
            .isFalse();
        Path preserved = quarantine.resolve(damaged.getFileName().toString());
        assertThat(preserved)
            .as("a human must still be able to recover the bytes")
            .exists();
        assertThat(Files.readAllBytes(preserved)).isEqualTo(damagedBytes);
    }

    @Test
    void repairIsOffByDefaultAndOnlyStatesWhatItWouldDo(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);
        Path damaged = DataDirectoryFixture.flipPayloadByteInSomeSegment(dataDir);

        Invocation result = invoke(dataDir.toString());

        assertThat(result.out()).containsIgnoringCase("would");
        assertThat(result.out()).contains("--repair");
        assertThat(Files.exists(damaged))
            .as("nothing may be moved without the opt-in")
            .isTrue();
    }

    @Test
    void repairNamesEveryActionBeforeApplyingIt(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);
        Path damaged = DataDirectoryFixture.flipPayloadByteInSomeSegment(dataDir);
        Path quarantine = tempDir.resolve("quarantine");

        Invocation result = invoke(
            dataDir.toString(), "--repair", "--quarantine-dir=" + quarantine);

        int planned = result.out().indexOf("Repair plan");
        int applied = result.out().indexOf("Applying repairs");
        assertThat(planned).isNotNegative();
        assertThat(applied)
            .as("the plan has to be printed before anything acts on it")
            .isGreaterThan(planned);
    }

    @Test
    void jsonModeIsParseableAndCarriesEveryVerdict(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 16);
        DataDirectoryFixture.flipPayloadByteInSomeSegment(dataDir);

        Invocation result = invoke(dataDir.toString(), "--json");

        JsonNode root = new ObjectMapper().readTree(result.out());
        assertThat(root.get("dataDirectory").asText()).isEqualTo(dataDir.toString());
        assertThat(root.get("exitCode").asInt()).isEqualTo(result.exitCode());
        assertThat(root.get("files").isArray()).isTrue();
        assertThat(root.get("files")).anySatisfy(file ->
            assertThat(file.get("status").asText()).isEqualTo("CORRUPT"));
        assertThat(root.get("summary").get("corrupt").asInt()).isPositive();
    }

    @Test
    void unverifiableFilesAreNeverReportedAsHealthy(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        Files.writeString(dataDir.resolve("something-nobody-recognises"), "x");

        Invocation plain = invoke(dataDir.toString());
        Invocation strict = invoke(dataDir.toString(), "--strict");

        assertThat(plain.out()).contains("something-nobody-recognises");
        assertThat(plain.out()).containsIgnoringCase("UNCHECKED");
        assertThat(plain.exitCode())
            .as("an unrecognised file is not damage, so it must not fail a deploy by default")
            .isZero();
        assertThat(strict.exitCode())
            .as("--strict is how an operator asks for proof rather than the absence of doubt")
            .isNotZero();
    }

    @Test
    void catalogRecordWithNoSegmentFileIsReportedAndCanBeDropped(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 32);
        List<Path> segments = DataDirectoryFixture.segmentFiles(dataDir);
        Path removed = segments.get(segments.size() - 1);
        Files.delete(removed);

        Invocation check = invoke(dataDir.toString());
        assertThat(check.exitCode()).isNotZero();
        assertThat(check.out()).contains(removed.getFileName().toString());
        assertThat(check.out()).containsIgnoringCase("catalog");

        Invocation repair = invoke(
            dataDir.toString(), "--repair", "--quarantine-dir=" + tempDir.resolve("q"));
        assertThat(repair.out()).containsIgnoringCase("purged");
        // The finding kind is what a re-check must no longer raise. The file name alone is
        // not: the routing directory still points at the lost segment, which is a separate
        // finding that names the same file and which no tool may repair by guessing.
        assertThat(invoke(dataDir.toString()).out())
            .doesNotContain("catalog-names-missing-segment");
    }

    @Test
    void indexFileWithNoSegmentDataFileIsReported(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        Files.writeString(dataDir.resolve("segment_9001.hnsw"), "orphan");

        Invocation result = invoke(dataDir.toString());

        assertThat(result.out()).contains("segment_9001.hnsw");
        assertThat(result.out()).containsIgnoringCase("segment_9001.dat is not here");
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void missingDirectoryIsAUsageFailureRatherThanACleanRun(@TempDir Path tempDir) {
        Invocation result = invoke(tempDir.resolve("nowhere").toString());

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.err()).containsIgnoringCase("does not exist");
    }

    @Test
    void noArgumentsPrintsUsageAndFails() {
        Invocation result = invoke();

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.err()).containsIgnoringCase("usage");
    }

    @Test
    void helpIsAnAnswer_notAnError() {
        Invocation invocation = invoke("--help");

        assertThat(invocation.exitCode())
            .as("asking a tool how to use it is not a failure, and a shell that stops on "
                + "non-zero must not stop here")
            .isEqualTo(FsckExit.CLEAN);
        assertThat(invocation.out())
            .as("help belongs on stdout, where it can be piped to a pager")
            .contains("Usage: excalibur-fsck");
        assertThat(invocation.err())
            .as("nothing went wrong, so nothing goes to stderr")
            .isEmpty();
    }

    @Test
    void shortAndWordFormsOfHelpBehaveTheSame() {
        assertThat(invoke("-h").out()).contains("Usage: excalibur-fsck");
        assertThat(invoke("help").out()).contains("Usage: excalibur-fsck");
        assertThat(invoke("-h").exitCode()).isEqualTo(FsckExit.CLEAN);
        assertThat(invoke("help").exitCode()).isEqualTo(FsckExit.CLEAN);
    }

    @Test
    void helpAfterADirectoryStillPrintsHelpRatherThanChecking(@TempDir Path dir) {
        Invocation invocation = invoke(dir.toString(), "--help");

        assertThat(invocation.exitCode()).isEqualTo(FsckExit.CLEAN);
        assertThat(invocation.out())
            .as("a half-typed command asking for help must not silently check a directory")
            .contains("Usage: excalibur-fsck")
            .doesNotContain("Scanned");
    }

    @Test
    void usageTextDocumentsEveryFlagTheParserAccepts() {
        String usage = FsckOptionsParser.usage();

        assertThat(usage)
            .as("a flag the tool accepts but never mentions is a flag nobody uses")
            .contains("--repair", "--quarantine-dir", "--json", "--strict", "--help");
    }
}
