package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a source may be told to read, against what ingestion can index.
 *
 * <p>A document is held whole while it is decoded, chunked and embedded, so the engine indexes
 * none above {@link DocumentLimits#MAX_DOCUMENT_BYTES}. A source allowed to read past that reads
 * a file into memory only for ingestion to discard it and count it as a failure - and at 2 GiB
 * the read cannot even produce the array. The bound a source reads to therefore has to stop where
 * the engine's does.</p>
 */
@DisplayName("Document limits")
class DocumentLimitsTest {

    @Test
    @DisplayName("a filesystem walk reads, by default, as large a file as can be indexed")
    void filesystemDefaultIsTheEngineLimit(@TempDir Path root) {
        FilesystemSource source = FilesystemSource.builder()
            .sourceId("docs").rootPath(root).build();

        assertThat(source.getMaxFileSize()).isEqualTo(DocumentLimits.MAX_PDF_BYTES);
    }

    @Test
    @DisplayName("a filesystem walk cannot be told to read a file too large to index")
    void filesystemRefusesMoreThanTheEngineIndexes(@TempDir Path root) {
        FilesystemSource.Builder builder = FilesystemSource.builder()
            .sourceId("docs").rootPath(root)
            .maxFileSize(DocumentLimits.MAX_PDF_BYTES + 1L);

        assertThatThrownBy(builder::build)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(Long.toString(DocumentLimits.MAX_PDF_BYTES));
    }

    @Test
    @DisplayName("a file over the walk's bound is listed, and refused rather than read")
    void anOversizedFileIsListedAndRefused(@TempDir Path root) throws Exception {
        // Listed because what a crawl lists is what a re-crawl subtracts from the registry: a
        // file left out for its size would be tombstoned as though it had been deleted.
        Files.writeString(root.resolve("small.txt"), "x".repeat(10));
        Files.writeString(root.resolve("large.txt"), "x".repeat(1000));
        FilesystemSource source = FilesystemSource.builder()
            .sourceId("docs").rootPath(root).maxFileSize(100).build();

        List<String> listed;
        try (var crawl = source.crawl()) {
            listed = crawl.toList();
        }

        assertThat(listed).containsExactlyInAnyOrder("small.txt", "large.txt");
        assertThatThrownBy(() -> source.fetch("large.txt"))
            .isInstanceOf(DocumentRefusedException.class)
            .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("an HTTP crawl cannot be told to hold a body too large to index")
    void httpRefusesMoreThanTheEngineIndexes() {
        HttpSource.Builder builder = HttpSource.builder()
            .allowPrivateAddresses(true)
            .sourceId("site")
            .baseUrl(URI.create("http://127.0.0.1:1/"))
            .maxResponseSize(DocumentLimits.MAX_PDF_BYTES + 1L);

        assertThatThrownBy(builder::build)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(Long.toString(DocumentLimits.MAX_PDF_BYTES));
    }

    @Test
    @DisplayName("an HTTP crawl may be told to hold exactly as much as can be indexed")
    void httpAcceptsTheEngineLimit() {
        try (HttpSource source = HttpSource.builder()
                .allowPrivateAddresses(true)
                .sourceId("site")
                .baseUrl(URI.create("http://127.0.0.1:1/"))
                .maxResponseSize(DocumentLimits.MAX_PDF_BYTES)
                .build()) {
            assertThat(source.getMaxResponseSize()).isEqualTo(DocumentLimits.MAX_PDF_BYTES);
        }
    }

    @Test
    @DisplayName("a file past the text bound that is not a PDF is refused without being read")
    void aLargeNonPdfIsRefused(@TempDir Path root) throws Exception {
        // Sparse: the length is set without writing the bytes, so the test costs no disk.
        try (var file = new java.io.RandomAccessFile(root.resolve("dump.log").toFile(), "rw")) {
            file.setLength(DocumentLimits.MAX_DOCUMENT_BYTES + 1L);
        }
        FilesystemSource source = FilesystemSource.builder().sourceId("docs").rootPath(root).build();

        assertThatThrownBy(() -> source.fetch("dump.log"))
            .isInstanceOf(DocumentRefusedException.class)
            .hasMessageContaining("not a PDF");
    }

    @Test
    @DisplayName("a PDF past the text bound is read, since its bytes are mostly not its text")
    void aLargePdfIsRead(@TempDir Path root) throws Exception {
        Path pdf = root.resolve("atlas.pdf");
        try (var file = new java.io.RandomAccessFile(pdf.toFile(), "rw")) {
            file.write("%PDF-1.7\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            file.setLength(DocumentLimits.MAX_DOCUMENT_BYTES + 1L);
        }
        FilesystemSource source = FilesystemSource.builder().sourceId("docs").rootPath(root).build();

        FetchedDocument fetched = source.fetch("atlas.pdf");

        assertThat(fetched.getContentLength()).isEqualTo(DocumentLimits.MAX_DOCUMENT_BYTES + 1);
        assertThat(DocumentLimits.exceeds(fetched)).isFalse();
    }

    @Test
    @DisplayName("ingestion holds each kind of document to its own bound")
    void eachKindToItsOwnBound() {
        byte[] large = new byte[DocumentLimits.MAX_DOCUMENT_BYTES + 1];
        assertThat(DocumentLimits.exceeds(new FetchedDocument("big.txt", large, "text/plain")))
            .isTrue();
        System.arraycopy("%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0,
            large, 0, 8);
        assertThat(DocumentLimits.exceeds(new FetchedDocument("big.pdf", large, null)))
            .isFalse();
        assertThat(DocumentLimits.maxBytes(true)).isEqualTo(DocumentLimits.maxPdfBytes());
        assertThat(DocumentLimits.maxBytes(false)).isEqualTo(DocumentLimits.MAX_DOCUMENT_BYTES);
    }

    @Test
    @DisplayName("reads a PDF up to a quarter of the heap, within 1 GiB and never below 100 MiB")
    void pdfBoundFollowsTheHeap() {
        long gib = 1024L * 1024 * 1024;
        assertThat(DocumentLimits.maxPdfBytes(256L * 1024 * 1024))
            .isEqualTo(DocumentLimits.MAX_DOCUMENT_BYTES);
        assertThat(DocumentLimits.maxPdfBytes(2 * gib)).isEqualTo(512 * 1024 * 1024);
        assertThat(DocumentLimits.maxPdfBytes(16 * gib)).isEqualTo(DocumentLimits.MAX_PDF_BYTES);
        assertThat(DocumentLimits.maxPdfBytes(Long.MAX_VALUE))
            .isEqualTo(DocumentLimits.MAX_PDF_BYTES);
        assertThat(DocumentLimits.maxPdfBytes())
            .isBetween(DocumentLimits.MAX_DOCUMENT_BYTES, DocumentLimits.MAX_PDF_BYTES);
    }

    @Test
    @DisplayName("says more heap reads a PDF only when the heap, not 1 GiB, refuses it")
    void pdfTooLargeNamesTheHeap() {
        assertThat(DocumentLimits.pdfTooLarge(600_000_000L, 512 * 1024 * 1024))
            .isEqualTo("600000000 bytes (max: 536870912 for a PDF, 25% of this node's maximum"
                + " heap; a larger heap (-Xmx) reads it)");
        assertThat(DocumentLimits.pdfTooLarge(2_000_000_000L, DocumentLimits.MAX_PDF_BYTES))
            .isEqualTo("2000000000 bytes (max: 1073741824 for a PDF)");
    }
}
