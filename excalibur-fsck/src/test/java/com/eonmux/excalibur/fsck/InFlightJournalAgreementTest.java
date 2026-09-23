package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.InFlightJournalCheck;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.InFlightJournalException;
import com.eonmux.excalibur.ingest.visibility.InFlightJournalFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline check of the in-flight document journal against the node's own reader of it.
 *
 * <p>Every case starts from a journal the node wrote, alters it the way a crash, damage or
 * another build might, and puts it to both: {@code excalibur-fsck}'s check first, since the
 * node's recovery rewrites what it reads, and then a tracker opened over the same data directory,
 * which is where a node reads the journal as it starts. They must agree on whether the journal is
 * taken.</p>
 */
class InFlightJournalAgreementTest {

    /** One way of altering a journal's text, named for the report. */
    private record Mutation(String name, UnaryOperator<String> text) {

        @Override
        public String toString() {
            return name;
        }
    }

    static List<Mutation> mutations() {
        return List.of(
            new Mutation("as written", text -> text),
            new Mutation("a record altered in place", text -> text.replace("docs:a", "docs:b")),
            new Mutation("a header altered in place", text -> text.replaceFirst("\\|2\\|", "|2|0")),
            new Mutation("a seal removed", text -> text.replaceFirst("(\\+\\|docs:a\\.md)\\|[0-9a-f]{8}",
                "$1")),
            new Mutation("a torn last append", text -> text + "+|docs:c.md|12"),
            new Mutation("a whole last record without its terminator", text ->
                text + InFlightJournalFormat.seal("+|docs:c.md")),
            new Mutation("only a torn header", text -> "\\#in-fli"),
            new Mutation("nothing at all", text -> ""),
            new Mutation("a newer version", text -> InFlightJournalFormat.seal("\\#in-flight|3")
                + "\n"),
            new Mutation("version zero", text -> "\\#in-flight|0\n"),
            new Mutation("a version that is not a number", text -> "\\#in-flight|two\n"),
            new Mutation("no header", text -> text.substring(text.indexOf('\n') + 1)),
            new Mutation("version 1", text -> "\\#in-flight|1\n+|docs:a.md\n?\n-|docs:a.md\n"),
            new Mutation("version 1 with a seal on its header", text ->
                InFlightJournalFormat.seal("\\#in-flight|1") + "\n+|docs:a.md\n"),
            new Mutation("a sealed line that is no record", text ->
                text + InFlightJournalFormat.seal("?|docs:c.md") + "\n"),
            new Mutation("a blank line among the records", text -> text.replaceFirst("\n", "\n\n")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void theCheckTakesWhatTheReaderTakes(Mutation mutation, @org.junit.jupiter.api.io.TempDir Path dataDir)
            throws IOException {
        Path registry = dataDir.resolve("document-registry");
        DocumentVisibilityTracker writer = new DocumentVisibilityTracker(new DocumentRegistry(registry));
        writer.beginIngestion("docs:a.md", 1);
        writer.beginIngestion("docs:x.md", 1);
        Path file = dataDir.resolve("document-registry" + InFlightJournalFormat.SUFFIX);
        Files.writeString(file, mutation.text().apply(Files.readString(file, StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8);

        FileCheck check = InFlightJournalCheck.check(file, file.getFileName().toString());
        boolean checkRefuses = check.status() == CheckStatus.CORRUPT
            || check.status() == CheckStatus.UNREADABLE;
        boolean readerRefuses;
        try {
            new DocumentVisibilityTracker(new DocumentRegistry(registry));
            readerRefuses = false;
        } catch (InFlightJournalException e) {
            readerRefuses = true;
        }

        assertThat(checkRefuses)
            .as("%s: the node %s it, and the check said %s: %s", mutation,
                readerRefuses ? "refuses" : "takes", check.status(), check.detail())
            .isEqualTo(readerRefuses);
    }
}
