package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.ingest.source.FetchedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A fetched document is decoded")
class DocumentDecoderTest {

    private static FetchedDocument pdf(byte[] content) {
        return new FetchedDocument("paper.pdf", content, "application/pdf", 0L);
    }

    @Nested
    @DisplayName("as text when it is not a PDF")
    class AsText {

        @Test
        @DisplayName("by its own content type and encoding")
        void byItsTypeAndEncoding() throws Exception {
            FetchedDocument page = new FetchedDocument("page.html",
                "<p>Café</p>".getBytes(StandardCharsets.ISO_8859_1), "text/html",
                StandardCharsets.ISO_8859_1, 0L);

            DocumentDecoder.Decoded decoded = DocumentDecoder.builtIn().decode(page);

            assertThat(decoded.text()).isEqualTo("Café");
            assertThat(DocumentDecoder.builtIn().decodingOf(page)).isEqualTo("ISO-8859-1+markup");
        }

        @Test
        @DisplayName("and refused, saying why, when its bytes are not text")
        void refusedWhenBinary() throws Exception {
            FetchedDocument binary = new FetchedDocument("blob.bin",
                new byte[] {0, 1, 2, 3}, "application/octet-stream", 0L);

            DocumentDecoder.Decoded decoded = DocumentDecoder.builtIn().decode(binary);

            assertThat(decoded.isText()).isFalse();
            assertThat(decoded.refusal()).contains("not text in UTF-8");
        }

        @Test
        @DisplayName("whatever its name says, since a .pdf of plain text is text")
        void notByItsName() throws Exception {
            FetchedDocument misnamed = new FetchedDocument("notes.pdf",
                "just notes".getBytes(StandardCharsets.UTF_8), "application/pdf", 0L);

            assertThat(DocumentDecoder.builtIn().decode(misnamed).text()).isEqualTo("just notes");
            assertThat(DocumentDecoder.builtIn().decodingOf(misnamed)).isEqualTo("UTF-8");
        }
    }

    @Nested
    @DisplayName("from its text layer when it is a PDF and no converter is configured")
    class BuiltInPdf {

        @Test
        @DisplayName("every page, in order, a page break apart")
        void readsEveryPage() throws Exception {
            byte[] content = PdfFixtures.withPages("Ada Lovelace wrote the first program.",
                "", "Charles Babbage designed the engine.");

            DocumentDecoder.Decoded decoded = DocumentDecoder.builtIn().decode(pdf(content));

            assertThat(decoded.isText()).isTrue();
            assertThat(decoded.text()).isEqualTo("Ada Lovelace wrote the first program.\n\n"
                + "Charles Babbage designed the engine.");
            assertThat(DocumentDecoder.builtIn().decodingOf(pdf(content))).isEqualTo("pdf");
        }

        @Test
        @DisplayName("refused, naming the setting that reads it, when it has no text layer")
        void scanIsRefused() throws Exception {
            DocumentDecoder.Decoded decoded =
                DocumentDecoder.builtIn().decode(pdf(PdfFixtures.withoutText(3)));

            assertThat(decoded.isText()).isFalse();
            assertThat(decoded.refusal()).contains("excalibur.ingest.pdf-reader=indra");
        }

        @Test
        @DisplayName("refused when it cannot be opened without a password")
        void encryptedIsRefused() throws Exception {
            DocumentDecoder.Decoded decoded =
                DocumentDecoder.builtIn().decode(pdf(PdfFixtures.encrypted("hidden")));

            assertThat(decoded.refusal()).contains("password");
        }

        @Test
        @DisplayName("refused, not thrown, when it is no PDF past its header")
        void damagedIsRefused() throws Exception {
            byte[] content = "%PDF-1.7\nnothing a parser can use".getBytes(StandardCharsets.UTF_8);

            DocumentDecoder.Decoded decoded = DocumentDecoder.builtIn().decode(pdf(content));

            assertThat(decoded.refusal()).contains("not a readable PDF");
        }

        @Test
        @DisplayName("stops when its thread is interrupted")
        void stopsWhenInterrupted() {
            byte[] content = PdfFixtures.withPages("one", "two");
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> DocumentDecoder.builtIn().decode(pdf(content)))
                    .isInstanceOf(InterruptedException.class);
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("without the control characters an unmapped glyph leaves behind")
        void controlCharactersAreDropped() {
            assertThat(PdfText.withoutControlCharacters("a\u0000b\u0007c\td\ne"))
                .isEqualTo("abc\td\ne");
            String clean = "nothing to drop";
            assertThat(PdfText.withoutControlCharacters(clean)).isSameAs(clean);
        }
    }

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("by the configured converter when it is a PDF")
    class Converted {

        @TempDir
        Path dir;

        /** Writes an executable shell script, the stand-in for a converter such as indra-ocr. */
        private Path script(String name, String body) throws IOException {
            Path file = dir.resolve(name);
            Files.writeString(file, "#!/bin/sh\n" + body);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
            return file;
        }

        private DocumentDecoder converting(Path program, Duration timeout, String... args) {
            return DocumentDecoder.reading(
                PdfConverter.of(program.toString(), List.of(args), timeout, 1));
        }

        @Test
        @DisplayName("with its arguments, then the PDF's path, reading what it prints")
        void runsTheConverter() throws Exception {
            Path program = script("fake-ocr", """
                for last; do :; done
                printf '# Converted\\n\\nflags: %s %s, %s bytes\\n' "$1" "$2" "$(wc -c < "$last" | tr -d ' ')"
                """);
            byte[] content = PdfFixtures.withoutText(1);
            DocumentDecoder decoder =
                converting(program, Duration.ofSeconds(30), "--format", "md");

            DocumentDecoder.Decoded decoded = decoder.decode(pdf(content));

            assertThat(decoded.text()).isEqualTo("# Converted\n\nflags: --format md, "
                + content.length + " bytes");
            assertThat(decoder.pdfDecoding()).startsWith("pdf+");
            assertThat(decoder.decodingOf(pdf(content))).isEqualTo("pdf+fake-ocr --format md");
        }

        @Test
        @DisplayName("leaving no copy of the document behind")
        void removesItsCopy() throws Exception {
            Path program = script("print-path", "for last; do :; done\necho \"$last\"\n");

            String staged = converting(program, Duration.ofSeconds(30))
                .decode(pdf(PdfFixtures.withPages("x"))).text();

            assertThat(staged).endsWith(".pdf");
            assertThat(Path.of(staged)).doesNotExist();
        }

        @Test
        @DisplayName("and leaves a document that is not a PDF to the text rules")
        void textIsNotConverted() throws Exception {
            Path program = script("never", "echo converted\n");
            FetchedDocument text = new FetchedDocument("a.txt",
                "plain".getBytes(StandardCharsets.UTF_8), "text/plain", 0L);

            DocumentDecoder decoder = converting(program, Duration.ofSeconds(30));

            assertThat(decoder.decode(text).text()).isEqualTo("plain");
            assertThat(decoder.decodingOf(text)).isEqualTo("UTF-8");
        }

        @Test
        @DisplayName("as an empty document when the converter finds no text")
        void nothingFoundIsEmpty() throws Exception {
            Path program = script("silent", "exit 0\n");

            DocumentDecoder.Decoded decoded = converting(program, Duration.ofSeconds(30))
                .decode(pdf(PdfFixtures.withoutText(1)));

            assertThat(decoded.text()).isEmpty();
        }

        @Test
        @DisplayName("failing, with what the converter said, when it exits non-zero")
        void failureCarriesStderr() throws Exception {
            Path program = script("broken", "echo 'model weights missing' >&2\nexit 3\n");
            DocumentDecoder decoder = converting(program, Duration.ofSeconds(30));

            assertThatThrownBy(() -> decoder.decode(pdf(PdfFixtures.withPages("x"))))
                .isInstanceOf(PdfConversionException.class)
                .hasMessageContaining("exited 3")
                .hasMessageContaining("model weights missing")
                .hasMessageContaining("paper.pdf");
        }

        @Test
        @DisplayName("failing, and killing everything it started, when it runs past its timeout")
        void timeoutKillsTheTree() throws Exception {
            Path childPid = dir.resolve("child.pid");
            Path program = script("hangs", "sleep 60 &\necho $! > '" + childPid + "'\nwait\n");
            DocumentDecoder decoder = converting(program, Duration.ofSeconds(1));

            assertThatThrownBy(() -> decoder.decode(pdf(PdfFixtures.withPages("x"))))
                .isInstanceOf(PdfConversionException.class)
                .hasMessageContaining("did not finish");

            long pid = Long.parseLong(Files.readString(childPid).strip());
            assertThat(waitForExit(pid)).as("the converter's own child is killed too").isTrue();
        }

        @Test
        @DisplayName("ending, and killing the converter, when its thread is interrupted")
        void interruptKillsTheConverter() throws Exception {
            Path pidFile = dir.resolve("converter.pid");
            Path program = script("slow", "echo $$ > '" + pidFile + "'\nexec sleep 60\n");
            DocumentDecoder decoder = converting(program, Duration.ofMinutes(5));
            byte[] content = PdfFixtures.withPages("x");

            CompletableFuture<Throwable> outcome = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    decoder.decode(pdf(content));
                    outcome.complete(null);
                } catch (Throwable t) {
                    outcome.complete(t);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) {
                assertThat(System.nanoTime()).as("the converter started").isLessThan(deadline);
                Thread.sleep(20);
            }
            worker.interrupt();

            assertThat(outcome.get(30, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
            long pid = Long.parseLong(Files.readString(pidFile).strip());
            assertThat(waitForExit(pid)).isTrue();
        }

        @Test
        @DisplayName("without the node's own settings in its environment")
        void withoutTheNodesEnvironment() throws Exception {
            Path program = script("env-dump", "env\n");

            String environment = converting(program, Duration.ofSeconds(30))
                .decode(pdf(PdfFixtures.withPages("x"))).text();

            assertThat(environment).contains("PATH=").doesNotContain("EXCALIBUR_");
        }

        @Test
        @DisplayName("and killing what it left running once it has answered")
        void leftoverChildrenAreKilled() throws Exception {
            Path childPid = dir.resolve("left.pid");
            // Works for a moment after starting it, as a converter starting workers does.
            Path program = script("leaves-a-child",
                "sleep 60 &\necho $! > '" + childPid + "'\nsleep 0.5\necho done\n");

            String text = converting(program, Duration.ofSeconds(30))
                .decode(pdf(PdfFixtures.withPages("x"))).text();

            assertThat(text).isEqualTo("done");
            long pid = Long.parseLong(Files.readString(childPid).strip());
            assertThat(waitForExit(pid)).isTrue();
        }

        @Test
        @DisplayName("not again, for the same bytes, after a timeout or empty output")
        void unrepeatableOutcomesAreRemembered() throws Exception {
            Path runs = dir.resolve("runs");
            Path slowMode = dir.resolve("slow");
            // Prints nothing; hangs while the flag file exists.
            Path program = script("counts", "echo run >> '" + runs + "'\n"
                + "if [ -e '" + slowMode + "' ]; then sleep 60; fi\n");
            DocumentDecoder decoder = converting(program, Duration.ofSeconds(1));
            FetchedDocument slow = pdf(PdfFixtures.withPages("slow"));
            FetchedDocument blank = pdf(PdfFixtures.withPages("blank"));

            Files.createFile(slowMode);
            assertThatThrownBy(() -> decoder.decode(slow))
                .isInstanceOf(PdfConversionException.class)
                .hasMessageContaining("did not finish");
            Files.delete(slowMode);
            assertThatThrownBy(() -> decoder.decode(slow))
                .isInstanceOf(PdfConversionException.class)
                .hasMessageContaining("Not converting paper.pdf again")
                .hasMessageContaining("did not finish");
            assertThat(Files.readAllLines(runs)).as("the timed-out bytes ran once").hasSize(1);

            assertThat(decoder.decode(blank).text()).isEmpty();
            assertThat(decoder.decode(blank).text()).isEmpty();
            assertThat(Files.readAllLines(runs)).as("the empty bytes ran once").hasSize(2);

            decoder.decode(pdf(PdfFixtures.withPages("blank", "and a second page")));
            assertThat(Files.readAllLines(runs)).as("changed bytes are converted").hasSize(3);
        }

        @Test
        @DisplayName("again after a non-zero exit, which the operator may have fixed since")
        void failuresOfTheConverterAreRetried() throws Exception {
            Path runs = dir.resolve("runs");
            Path program = script("fails", "echo run >> '" + runs + "'\nexit 2\n");
            DocumentDecoder decoder = converting(program, Duration.ofSeconds(30));
            FetchedDocument document = pdf(PdfFixtures.withPages("x"));

            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> decoder.decode(document))
                    .hasMessageContaining("exited 2");
            }
            assertThat(Files.readAllLines(runs)).hasSize(2);
        }

        @Test
        @DisplayName("refusing, at construction, a converter that does not exist")
        void missingConverterIsRefused() {
            assertThatThrownBy(() -> PdfConverter.of(dir.resolve("absent").toString(), List.of(),
                    Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an executable file");
            assertThatThrownBy(() -> PdfConverter.of("no-such-converter-on-any-path", List.of(),
                    Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PATH");
        }

        @Test
        @DisplayName("finding a bare command on the PATH")
        void findsCommandsOnThePath() {
            assertThat(PdfConverter.locate("sh")).isAbsolute().isExecutable();
        }

        private boolean waitForExit(long pid) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                    return true;
                }
                Thread.sleep(20);
            }
            return false;
        }
    }

    @Test
    @DisplayName("an outcome is text or a refusal, never both or neither")
    void outcomeIsOneOrTheOther() {
        assertThatThrownBy(() -> new DocumentDecoder.Decoded("text", "reason"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentDecoder.Decoded(null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
