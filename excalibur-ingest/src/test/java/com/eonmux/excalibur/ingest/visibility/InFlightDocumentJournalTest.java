package com.eonmux.excalibur.ingest.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The file that remembers which documents were being written when the node stopped.
 *
 * <p>Its whole job is to be readable by the next process, so these tests are about what a second
 * journal over the same path sees - not about the shape of the lines, except where the shape is
 * what a document id can attack.</p>
 */
class InFlightDocumentJournalTest {

    @Test
    @DisplayName("a marked document is there for the next process to find")
    void aMarkOutlivesTheJournalThatWroteIt(@TempDir Path dataDir) {
        journal(dataDir).begin("docs:paper.md");

        assertThat(journal(dataDir).recover()).containsExactly("docs:paper.md");
    }

    @Test
    @DisplayName("a released document is not marked for the next process")
    void anEndedMarkIsNotRecovered(@TempDir Path dataDir) {
        InFlightDocumentJournal journal = journal(dataDir);
        journal.begin("docs:paper.md");
        journal.end("docs:paper.md");

        assertThat(journal(dataDir).recover()).isEmpty();
    }

    @Test
    @DisplayName("a document id carrying a line terminator cannot forge a second record")
    void aNewlineInAKeyCannotForgeARecord(@TempDir Path dataDir) {
        String hostile = "docs:evil\n+|docs:innocent.md";
        journal(dataDir).begin(hostile);

        assertThat(journal(dataDir).recover())
            .as("one marked document must come back as exactly one, under its own id")
            .containsExactly(hostile);
    }

    @Test
    @DisplayName("ingesting steadily does not grow the log without bound")
    void theLogIsBounded(@TempDir Path dataDir) throws IOException {
        InFlightDocumentJournal journal = journal(dataDir);
        for (int i = 0; i < 2_000; i++) {
            journal.begin("docs:doc-" + i + ".md");
            journal.end("docs:doc-" + i + ".md");
        }

        List<String> remaining = Files.exists(journal.path())
            ? Files.readAllLines(journal.path(), StandardCharsets.UTF_8)
            : List.of();
        assertThat(remaining)
            .as("four thousand marks and releases must not be four thousand lines a restart reads")
            .hasSizeLessThan(1_024);
        assertThat(journal(dataDir).recover()).isEmpty();
    }

    @Test
    @DisplayName("a compacted journal still names what is in flight")
    void compactionKeepsTheLiveMarks(@TempDir Path dataDir) {
        InFlightDocumentJournal journal = journal(dataDir);
        journal.begin("docs:interrupted.md");
        for (int i = 0; i < 700; i++) {
            journal.begin("docs:doc-" + i + ".md");
            journal.end("docs:doc-" + i + ".md");
        }

        assertThat(journal(dataDir).recover()).containsExactly("docs:interrupted.md");
    }

    @Test
    @DisplayName("clearing the journal releases every mark")
    void clearingReleasesEverything(@TempDir Path dataDir) {
        InFlightDocumentJournal journal = journal(dataDir);
        journal.begin("docs:one.md");
        journal.begin("docs:two.md");

        journal.clear();

        assertThat(journal(dataDir).recover()).isEmpty();
    }

    @Test
    @DisplayName("a journal written by a newer build is refused rather than reinterpreted")
    void aJournalFromANewerBuildIsRefused(@TempDir Path dataDir) throws IOException {
        InFlightDocumentJournal journal = journal(dataDir);
        journal.begin("docs:paper.md");
        List<String> lines = Files.readAllLines(journal.path(), StandardCharsets.UTF_8);
        Files.write(journal.path(), List.of("\\#in-flight|99", lines.get(1)),
            StandardCharsets.UTF_8);

        assertThatThrownBy(() -> journal(dataDir).recover())
            .isInstanceOf(InFlightJournalException.class)
            .hasMessageContaining("99");
    }

    @Test
    @DisplayName("a half-written last line costs that record, not the start-up")
    void anUnreadableRecordIsSkipped(@TempDir Path dataDir) throws IOException {
        InFlightDocumentJournal journal = journal(dataDir);
        journal.begin("docs:paper.md");
        // What a power cut during an append can leave: a record cut off before it names anything.
        // Refusing to start over it would be refusing to start over a line that says nothing, and
        // the marks in front of it are the ones that matter.
        Files.writeString(journal.path(), "+", StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND);

        assertThat(journal(dataDir).recover()).containsExactly("docs:paper.md");
    }

    @Test
    @DisplayName("every line carries the checksum of its own bytes")
    void everyLineIsChecksummed(@TempDir Path dataDir) throws IOException {
        InFlightDocumentJournal journal = journal(dataDir);
        journal.begin("docs:paper.md");
        journal.end("docs:paper.md");
        journal.begin("docs:other.md");

        List<String> lines = Files.readAllLines(journal.path(), StandardCharsets.UTF_8);

        assertThat(InFlightJournalFormat.FORMAT_VERSION).isEqualTo(2);
        assertThat(lines).containsExactly(
            sealed("\\#in-flight|2"),
            sealed("+|docs:paper.md"),
            sealed("-|docs:paper.md"),
            sealed("+|docs:other.md"));
        assertThat(journal(dataDir).recover()).containsExactly("docs:other.md");
    }

    @Test
    @DisplayName("a complete record altered in place is refused, naming the checksum")
    void aFlippedByteIsRefused(@TempDir Path dataDir) throws IOException {
        InFlightDocumentJournal journal = journal(dataDir);
        journal.begin("docs:paper.md");
        journal.begin("docs:other.md");
        byte[] bytes = Files.readAllBytes(journal.path());
        int record = new String(bytes, StandardCharsets.UTF_8).indexOf("+|docs:paper.md");
        bytes[record + 3] ^= 0x01;
        Files.write(journal.path(), bytes);

        assertThatThrownBy(() -> journal(dataDir).recover())
            .isInstanceOf(InFlightJournalException.class)
            .hasMessageContaining(journal.path().toString())
            .hasMessageContaining("checksum");
    }

    @Test
    @DisplayName("a header altered in place is refused")
    void aDamagedHeaderIsRefused(@TempDir Path dataDir) throws IOException {
        InFlightDocumentJournal journal = journal(dataDir);
        journal.begin("docs:paper.md");
        List<String> lines = Files.readAllLines(journal.path(), StandardCharsets.UTF_8);
        String header = lines.get(0);
        Files.write(journal.path(), List.of(header.substring(0, header.length() - 1)
            + (header.endsWith("0") ? "1" : "0"), lines.get(1)), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> journal(dataDir).recover())
            .isInstanceOf(InFlightJournalException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    @DisplayName("an append cut short is skipped, and the next append starts on a line of its own")
    void aTornAppendIsSkippedAndSealedOff(@TempDir Path dataDir) throws IOException {
        InFlightDocumentJournal journal = journal(dataDir);
        journal.begin("docs:paper.md");
        String torn = sealed("+|docs:torn.md");
        Files.writeString(journal.path(), torn.substring(0, torn.length() - 3),
            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        InFlightDocumentJournal restarted = journal(dataDir);
        assertThat(restarted.recover()).containsExactly("docs:paper.md");
        restarted.begin("docs:next.md");

        assertThat(journal(dataDir).recover())
            .as("a record appended after a torn one must not be welded onto it")
            .containsExactlyInAnyOrder("docs:paper.md", "docs:next.md");
    }

    @Test
    @DisplayName("a whole last record whose terminator never landed is still applied")
    void aWholeUnterminatedRecordIsApplied(@TempDir Path dataDir) throws IOException {
        Path file = dataDir.resolve("document-registry" + InFlightJournalFormat.SUFFIX);
        Files.writeString(file, sealed("\\#in-flight|2") + "\n" + sealed("+|docs:a.md"),
            StandardCharsets.UTF_8);

        assertThat(journal(dataDir).recover()).containsExactly("docs:a.md");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).endsWith("\n");
    }

    @Test
    @DisplayName("a version 1 journal is read as it always was, and rewritten in version 2")
    void aVersionOneJournalIsUpgraded(@TempDir Path dataDir) throws IOException {
        Path file = dataDir.resolve("document-registry" + InFlightJournalFormat.SUFFIX);
        Files.writeString(file, "\\#in-flight|1\n+|docs:a.md\n+|docs:b.md\n-|docs:a.md\n?\n",
            StandardCharsets.UTF_8);

        assertThat(journal(dataDir).recover()).containsExactly("docs:b.md");
        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8))
            .containsExactly(sealed("\\#in-flight|2"), sealed("+|docs:b.md"));
    }

    @Test
    @DisplayName("a version above the newest is refused, checksum or not")
    void aNewerVersionIsRefused(@TempDir Path dataDir) throws IOException {
        Path file = dataDir.resolve("document-registry" + InFlightJournalFormat.SUFFIX);
        Files.writeString(file, sealed("\\#in-flight|3") + "\n" + sealed("+|docs:a.md") + "\n",
            StandardCharsets.UTF_8);

        assertThatThrownBy(() -> journal(dataDir).recover())
            .isInstanceOf(InFlightJournalException.class)
            .hasMessageContaining("format version 3");
    }

    @Test
    @DisplayName("the seal is exactly the line, a bar, and eight hex digits of its CRC32C")
    void theSealIsTheLinesOwnChecksum() {
        String line = InFlightJournalFormat.seal("+|docs:a.md");
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update("+|docs:a.md".getBytes(StandardCharsets.UTF_8));

        assertThat(line).isEqualTo("+|docs:a.md|" + String.format("%08x", crc.getValue()));
        assertThat(InFlightJournalFormat.unseal(line)).isEqualTo("+|docs:a.md");
        assertThat(InFlightJournalFormat.unseal("+|docs:a.md")).isNull();
        assertThat(InFlightJournalFormat.unseal("+|docs:b.md" + line.substring(11))).isNull();
        assertThat(InFlightJournalFormat.unseal(line.toUpperCase())).isNull();
    }

    private static String sealed(String content) {
        return InFlightJournalFormat.seal(content);
    }

    private static InFlightDocumentJournal journal(Path dataDir) {
        return new InFlightDocumentJournal(dataDir.resolve("document-registry"));
    }
}
