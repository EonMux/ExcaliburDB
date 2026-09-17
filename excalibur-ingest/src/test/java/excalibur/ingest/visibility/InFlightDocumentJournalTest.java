package excalibur.ingest.visibility;

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

    private static InFlightDocumentJournal journal(Path dataDir) {
        return new InFlightDocumentJournal(dataDir.resolve("document-registry"));
    }
}
