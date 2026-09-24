package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.CountMarkedFormat;
import com.eonmux.excalibur.fsck.check.CountMarkedTextCheck;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileInspector;
import com.eonmux.excalibur.fsck.check.InFlightJournalCheck;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.ErasureReceiptFile;

import com.eonmux.excalibur.ingest.registry.EndLine;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.registry.SourceStatusFile;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.InFlightJournalFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The verdicts on the ingest side's checksummed files: proven when the checksum holds, damage
 * when it does not, and neither for a version written before the checksum existed.
 */
class IngestChecksumCheckTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(CountMarkedFormat.class)
    @DisplayName("a file as its writer left it is proven intact")
    void aWrittenFileIsIntact(CountMarkedFormat format, @TempDir Path dataDir)
            throws IOException {
        Path file = written(format, dataDir);

        FileCheck check = check(format, file);

        assertThat(check.status()).as(check.detail()).isEqualTo(CheckStatus.INTACT);
        assertThat(check.version()).isEqualTo("v" + format.newestVersion());
        assertThat(check.detail()).contains("checksum");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CountMarkedFormat.class)
    @DisplayName("a record altered in place is corrupt, with the reader's consequence")
    void aFlippedByteIsCorrupt(CountMarkedFormat format, @TempDir Path dataDir)
            throws IOException {
        Path file = written(format, dataDir);
        byte[] bytes = Files.readAllBytes(file);
        int secondLine = new String(bytes, StandardCharsets.UTF_8).indexOf('\n') + 1;
        bytes[secondLine] ^= 0x01;
        Files.write(file, bytes);

        FileCheck check = check(format, file);

        assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(check.detail())
            .contains("checksum does not match")
            .contains(format.refusal());
        assertThat(check.isDamage()).isEqualTo(!format.kind().discardedOnOpen());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CountMarkedFormat.class)
    @DisplayName("a file cut short is still corrupt")
    void aTruncatedFileIsCorrupt(CountMarkedFormat format, @TempDir Path dataDir)
            throws IOException {
        Path file = written(format, dataDir);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 5));

        assertThat(check(format, file).status()).isEqualTo(CheckStatus.CORRUPT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CountMarkedFormat.class)
    @DisplayName("the last version without a checksum is whole but unproven")
    void anOlderVersionIsLegacyUnprotected(CountMarkedFormat format, @TempDir Path dataDir)
            throws IOException {
        assumeTrue(format.firstChecksummedVersion() > format.oldestVersion(),
            "a format born with its checksum has no version without one");
        Path file = dataDir.resolve(fileName(format));
        int version = format.firstChecksummedVersion() - 1;
        Files.writeString(file, format.headerMarker() + "|" + version + "\n" + record(format)
            + "\n" + format.endMarker() + "|1\n");

        FileCheck check = check(format, file);

        assertThat(check.status()).as(check.detail()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(check.version()).isEqualTo("v" + version);
        assertThat(check.detail()).contains("no checksum");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CountMarkedFormat.class)
    @DisplayName("a version above the newest is put to an operator, or discarded for the history")
    void aNewerVersionIsNotRead(CountMarkedFormat format, @TempDir Path dataDir)
            throws IOException {
        Path file = dataDir.resolve(fileName(format));
        String body = format.headerMarker() + "|" + (format.newestVersion() + 1) + "\n";
        Files.writeString(file, body + EndLine.render(format.endMarker(), 0, crc(body)) + "\n");

        FileCheck check = check(format, file);

        assertThat(check.status()).isEqualTo(format.kind().discardedOnOpen()
            ? CheckStatus.CORRUPT : CheckStatus.UNREADABLE);
        assertThat(check.isDamage()).isEqualTo(!format.kind().discardedOnOpen());
        assertThat(check.detail()).contains("format version " + (format.newestVersion() + 1));
    }

    @Test
    @DisplayName("a journal as the node wrote it is proven intact")
    void aWrittenJournalIsIntact(@TempDir Path dataDir) throws IOException {
        Path file = journal(dataDir, "docs:a.md", "docs:b.md");

        FileCheck check = InFlightJournalCheck.check(file, file.getFileName().toString());

        assertThat(check.status()).as(check.detail()).isEqualTo(CheckStatus.INTACT);
        assertThat(check.version()).isEqualTo("v" + InFlightJournalFormat.FORMAT_VERSION);
        assertThat(FileInspector.inspect(file, file.getFileName().toString()).check().status())
            .isEqualTo(CheckStatus.INTACT);
    }

    @Test
    @DisplayName("a journal line altered in place is corrupt, and the node refuses to start on it")
    void aFlippedJournalByteIsCorrupt(@TempDir Path dataDir) throws IOException {
        Path file = journal(dataDir, "docs:a.md", "docs:b.md");
        byte[] bytes = Files.readAllBytes(file);
        int secondLine = new String(bytes, StandardCharsets.UTF_8).indexOf('\n') + 1;
        bytes[secondLine + 3] ^= 0x01;
        Files.write(file, bytes);

        FileCheck check = InFlightJournalCheck.check(file, file.getFileName().toString());

        assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(check.detail()).contains("line 2").contains("checksum")
            .contains("refuses to start");
    }

    @Test
    @DisplayName("a version 1 journal is read but unproven")
    void aVersionOneJournalIsLegacyUnprotected(@TempDir Path dataDir) throws IOException {
        Path file = dataDir.resolve("document-registry" + InFlightJournalFormat.SUFFIX);
        Files.writeString(file, "\\#in-flight|1\n+|docs:a.md\n");

        FileCheck check = InFlightJournalCheck.check(file, file.getFileName().toString());

        assertThat(check.status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(check.version()).isEqualTo("v1");
    }

    @Test
    @DisplayName("a journal from a newer build is put to an operator")
    void aNewerJournalIsUnreadable(@TempDir Path dataDir) throws IOException {
        Path file = dataDir.resolve("document-registry" + InFlightJournalFormat.SUFFIX);
        Files.writeString(file, InFlightJournalFormat.seal("\\#in-flight|3") + "\n");

        FileCheck check = InFlightJournalCheck.check(file, file.getFileName().toString());

        assertThat(check.status()).isEqualTo(CheckStatus.UNREADABLE);
        assertThat(check.detail()).contains("format version 3");
    }

    private static FileCheck check(CountMarkedFormat format, Path file) {
        return CountMarkedTextCheck.check(file, file.getFileName().toString(), format);
    }

    private static String fileName(CountMarkedFormat format) {
        return switch (format) {
            case DOCUMENT_REGISTRY -> "document-registry";
            case SOURCE_CATALOG -> SourceCatalog.FILE;
            case SOURCE_STATUS -> SourceStatusFile.FILE;
            case ERASURE_RECEIPTS -> ErasureReceiptFile.FILE;
        };
    }

    private static String record(CountMarkedFormat format) {
        return switch (format) {
            case DOCUMENT_REGISTRY -> "docs|a.txt|1000|4242|128|2000|docs:a.txt:chunk:0";
            case SOURCE_CATALOG -> "filesystem|a|shared|root_path=/a";
            case SOURCE_STATUS -> "a|idle|1.0|3|0|1|0|1000";
            case ERASURE_RECEIPTS -> "r-1|1000|docs|document|1|0|0|0|0|0|shared=1+1";
        };
    }

    private static Path written(CountMarkedFormat format, Path dataDir) throws IOException {
        return switch (format) {
            case DOCUMENT_REGISTRY -> {
                Path file = dataDir.resolve("document-registry");
                DocumentRegistry registry = new DocumentRegistry(file);
                registry.register(DocumentMetadata.builder().sourceId("docs").documentId("a.txt")
                    .lastModifiedMillis(1000L).contentHash(4242L).contentLength(128)
                    .indexTimeMillis(2000L).chunkIds(List.of("docs:a.txt:chunk:0")).build());
                registry.persist();
                yield file;
            }
            case SOURCE_CATALOG -> {
                SourceCatalog.open(dataDir).record(
                    new SourceDescriptor("filesystem", "a", Map.of("root_path", "/a")));
                yield dataDir.resolve(SourceCatalog.FILE);
            }
            case SOURCE_STATUS -> {
                SourceStatusFile.open(dataDir).write(List.of(
                    new SourceStatusFile.Entry("a", "idle", 1.0, 3, 0, 1, 0, 1000L, null)));
                yield dataDir.resolve(SourceStatusFile.FILE);
            }
            case ERASURE_RECEIPTS -> {
                ErasureReceiptFile.open(dataDir).record(new ErasureReceiptFile.Receipt("r-1",
                    1000L, "docs", "document", 1, 0, 0, 0, false, false,
                    List.of(new ErasureReceiptFile.GraphRequests("shared", List.of(1L)))));
                yield dataDir.resolve(ErasureReceiptFile.FILE);
            }
        };
    }

    /**
     * Leaves a journal behind the way a node that stopped mid-ingest does.
     */
    private static Path journal(Path dataDir, String... documents) {
        DocumentVisibilityTracker tracker =
            new DocumentVisibilityTracker(new DocumentRegistry(dataDir.resolve("document-registry")));
        for (String document : documents) {
            tracker.beginIngestion(document, 1);
        }
        return dataDir.resolve("document-registry" + InFlightJournalFormat.SUFFIX);
    }

    private static long crc(String text) {
        CRC32C crc = new CRC32C();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
