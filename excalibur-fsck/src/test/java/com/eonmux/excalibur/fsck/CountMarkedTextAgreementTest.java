package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.CountMarkedFormat;
import com.eonmux.excalibur.fsck.check.CountMarkedTextCheck;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.ErasureReceiptFile;

import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.registry.SourceStatusFile;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline check of each count-marked registry against the node's own reader of it.
 *
 * <p>Every case starts from a file the ingestion module's writer produced, alters it the way a
 * crash, an operator or another build might, and puts the result to both: the reader the node
 * loads the file with, and the check {@code excalibur-fsck} and {@code excalibur-backup verify
 * --deep} judge it with. They must agree on whether the file is taken. A check that passes a
 * file the node refuses tells an operator a node that will not start is healthy, and one that
 * fails a file the node takes has them restore a backup over good state.</p>
 */
class CountMarkedTextAgreementTest {

    /** One way of altering a file, named for the report. */
    private record Mutation(String name, UnaryOperator<List<String>> lines) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static List<Mutation> mutations(CountMarkedFormat format) {
        String header = format.headerMarker() + "|";
        String end = format.endMarker() + "|";
        return List.of(
            new Mutation("as written", lines -> lines),
            new Mutation("a newer version", lines ->
                replaced(lines, 0, header + (format.newestVersion() + 1))),
            new Mutation("the oldest version read", lines ->
                replaced(lines, 0, header + format.oldestVersion())),
            new Mutation("the last version without a checksum", lines ->
                replaced(lines, 0, header + (format.firstChecksummedVersion() - 1))),
            new Mutation("a record altered in place", lines ->
                replaced(lines, 1, lines.get(1) + "x")),
            new Mutation("the checksum lost", lines -> replaced(lines, lines.size() - 1,
                end + (lines.size() - 2))),
            new Mutation("version zero", lines -> replaced(lines, 0, header + "0")),
            new Mutation("a version that is not a number", lines ->
                replaced(lines, 0, header + "two")),
            new Mutation("a header with trailing space", lines ->
                replaced(lines, 0, lines.get(0) + " ")),
            new Mutation("no header", lines -> lines.subList(1, lines.size())),
            new Mutation("no header and no count", lines -> lines.subList(1, lines.size() - 1)),
            new Mutation("the count lost", lines -> lines.subList(0, lines.size() - 1)),
            new Mutation("a count that disagrees", lines ->
                replaced(lines, lines.size() - 1, end + (lines.size() + 3))),
            new Mutation("a negative count", lines -> replaced(lines, lines.size() - 1, end + "-1")),
            new Mutation("a blank line among the records", lines -> inserted(lines, 2, "")),
            new Mutation("a blank line after the count", lines -> inserted(lines, lines.size(), "")),
            new Mutation("a padded count", lines ->
                replaced(lines, lines.size() - 1, lines.get(lines.size() - 1) + " ")),
            new Mutation("only the header", lines -> lines.subList(0, 1)),
            new Mutation("nothing at all", lines -> List.of()));
    }

    static Stream<Arguments> cases() {
        return Arrays.stream(CountMarkedFormat.values())
            .flatMap(format -> mutations(format).stream()
                .map(mutation -> Arguments.of(format, mutation)));
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("cases")
    void theCheckTakesWhatTheReaderTakes(CountMarkedFormat format, Mutation mutation,
                                         @TempDir Path dataDir) throws IOException {
        Path file = written(format, dataDir);
        List<String> altered = mutation.lines().apply(Files.readAllLines(file));
        Files.writeString(file, altered.isEmpty() ? "" : String.join("\n", altered) + "\n");

        assertAgreement(format, file, dataDir, mutation.name());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formats")
    void aRecordThatIsNotUtf8IsRefusedByBoth(CountMarkedFormat format, @TempDir Path dataDir)
            throws IOException {
        Path file = written(format, dataDir);
        byte[] bytes = Files.readAllBytes(file);
        int secondLine = indexOf(bytes, (byte) '\n') + 1;
        ByteArrayOutputStream damaged = new ByteArrayOutputStream();
        damaged.write(bytes, 0, secondLine);
        damaged.write(0xFF);
        damaged.write(bytes, secondLine, bytes.length - secondLine);
        Files.write(file, damaged.toByteArray());

        FileCheck check = assertAgreement(format, file, dataDir, "a byte that is not UTF-8");
        assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
    }

    static Stream<CountMarkedFormat> formats() {
        return Arrays.stream(CountMarkedFormat.values());
    }

    @Test
    void aNewerRegistryIsPutToAnOperatorRatherThanCalledDamaged(@TempDir Path dataDir)
            throws IOException {
        for (CountMarkedFormat format : List.of(CountMarkedFormat.DOCUMENT_REGISTRY,
                CountMarkedFormat.SOURCE_CATALOG)) {
            Path file = written(format, dataDir);
            List<String> lines = Files.readAllLines(file);
            Files.writeString(file, String.join("\n", replaced(lines, 0,
                format.headerMarker() + "|" + (format.newestVersion() + 1))) + "\n");

            FileCheck check = CountMarkedTextCheck.check(file, file.getFileName().toString(),
                format);

            assertThat(check.status()).as(format.name()).isEqualTo(CheckStatus.UNREADABLE);
            assertThat(check.version()).isEqualTo("v" + (format.newestVersion() + 1));
            assertThat(check.detail())
                .contains("format version " + (format.newestVersion() + 1))
                .contains("refuses to start");
        }
    }

    @Test
    void aNewerStatusHistoryIsDiscardedAndSoNotDamage(@TempDir Path dataDir) throws IOException {
        Path file = written(CountMarkedFormat.SOURCE_STATUS, dataDir);
        List<String> lines = Files.readAllLines(file);
        Files.writeString(file, String.join("\n", replaced(lines, 0,
            SourceStatusFile.HEADER_MARKER + "|" + (SourceStatusFile.FORMAT_VERSION + 1))) + "\n");

        FileCheck check = CountMarkedTextCheck.check(file, SourceStatusFile.FILE,
            CountMarkedFormat.SOURCE_STATUS);

        assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(check.isDamage()).isFalse();
        assertThat(check.detail()).contains("discards it");
    }

    @Test
    void aStatusHistoryAtTheOldestVersionIsStillTaken(@TempDir Path dataDir) throws IOException {
        Path file = dataDir.resolve(SourceStatusFile.FILE);
        Files.writeString(file, SourceStatusFile.HEADER_MARKER + "|"
            + SourceStatusFile.OLDEST_READABLE_VERSION
            + "\ndocs|idle|1.0|420|0|7|1730000000000\n" + SourceStatusFile.END_MARKER + "|1\n");

        FileCheck check = assertAgreement(CountMarkedFormat.SOURCE_STATUS, file, dataDir,
            "version 1");

        assertThat(check.status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(check.version()).isEqualTo("v" + SourceStatusFile.OLDEST_READABLE_VERSION);
    }

    /**
     * Puts one file to both judges and requires them to agree.
     *
     * @return The check's verdict, for anything further a case wants to say about it
     */
    private static FileCheck assertAgreement(CountMarkedFormat format, Path file, Path dataDir,
                                             String what) {
        FileCheck check = CountMarkedTextCheck.check(file, file.getFileName().toString(), format);
        boolean checkRefuses = check.status() == CheckStatus.CORRUPT
            || check.status() == CheckStatus.UNREADABLE;
        boolean readerRefuses = readerRefuses(format, file, dataDir);
        assertThat(checkRefuses)
            .as("%s with %s: the node %s it, and the check said %s: %s", format, what,
                readerRefuses ? "refuses" : "takes", check.status(), check.detail())
            .isEqualTo(readerRefuses);
        return check;
    }

    /**
     * Loads a file with the reader the node uses, and reports whether it was turned away.
     *
     * <p>The status history is never refused outright - the reader logs it and starts empty -
     * so for it, turned away means that the records every written file here holds did not come
     * back.</p>
     */
    private static boolean readerRefuses(CountMarkedFormat format, Path file, Path dataDir) {
        return switch (format) {
            case DOCUMENT_REGISTRY -> {
                try {
                    new DocumentRegistry(file);
                    yield false;
                } catch (DocumentRegistry.RegistryLoadException e) {
                    yield true;
                }
            }
            case SOURCE_CATALOG -> {
                try {
                    new SourceCatalog(file);
                    yield false;
                } catch (SourceCatalog.CatalogLoadException e) {
                    yield true;
                }
            }
            case SOURCE_STATUS -> SourceStatusFile.open(dataDir).loaded().isEmpty();
            case ERASURE_RECEIPTS -> ErasureReceiptFile.open(dataDir).size() == 0;
        };
    }

    /**
     * Writes a genuine two-record file of the format with the node's own writer.
     *
     * @return The file
     */
    private static Path written(CountMarkedFormat format, Path dataDir) throws IOException {
        switch (format) {
            case DOCUMENT_REGISTRY -> {
                Path file = dataDir.resolve("document-registry");
                DocumentRegistry registry = new DocumentRegistry(file);
                registry.register(metadata("docs", "a.txt"));
                registry.register(metadata("docs", "b.txt"));
                registry.persist();
                return file;
            }
            case SOURCE_CATALOG -> {
                SourceCatalog catalog = SourceCatalog.open(dataDir);
                catalog.record(new SourceDescriptor("filesystem", "a", Map.of("root_path", "/a")));
                catalog.record(new SourceDescriptor("filesystem", "b", Map.of("root_path", "/b")));
                return dataDir.resolve(SourceCatalog.FILE);
            }
            case SOURCE_STATUS -> {
                SourceStatusFile.open(dataDir).write(List.of(
                    new SourceStatusFile.Entry("a", "idle", 1.0, 3, 0, 1, 0, 0, 1000L, null),
                    new SourceStatusFile.Entry("b", "idle", 1.0, 5, 0, 0, 2, 1, 2000L, "boom")));
                return dataDir.resolve(SourceStatusFile.FILE);
            }
            case ERASURE_RECEIPTS -> {
                ErasureReceiptFile receipts = ErasureReceiptFile.open(dataDir);
                receipts.record(new ErasureReceiptFile.Receipt("r-1", 1000L, "docs", "document",
                    1, 0, 0, 0, false, true, List.of()));
                receipts.record(new ErasureReceiptFile.Receipt("r-2", 2000L, "docs", "source",
                    2, 0, 1, 0, true, false,
                    List.of(new ErasureReceiptFile.GraphRequests("shared", List.of(4L, 5L)))));
                return dataDir.resolve(ErasureReceiptFile.FILE);
            }
            default -> throw new IllegalArgumentException("No writer for " + format);
        }
    }

    private static DocumentMetadata metadata(String sourceId, String documentId) {
        return DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(1000L)
            .contentHash(4242L)
            .contentLength(128)
            .indexTimeMillis(2000L)
            .chunkIds(List.of(sourceId + ":" + documentId + ":chunk:0"))
            .build();
    }

    private static List<String> replaced(List<String> lines, int index, String line) {
        List<String> copy = new ArrayList<>(lines);
        copy.set(index, line);
        return copy;
    }

    private static List<String> inserted(List<String> lines, int index, String line) {
        List<String> copy = new ArrayList<>(lines);
        copy.add(index, line);
        return copy;
    }

    private static int indexOf(byte[] bytes, byte value) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        throw new IllegalStateException("no line break in " + new String(bytes,
            StandardCharsets.UTF_8));
    }
}
