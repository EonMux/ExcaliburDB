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

        assertThat(source.getMaxFileSize()).isEqualTo(DocumentLimits.MAX_DOCUMENT_BYTES);
    }

    @Test
    @DisplayName("a filesystem walk cannot be told to read a file too large to index")
    void filesystemRefusesMoreThanTheEngineIndexes(@TempDir Path root) {
        FilesystemSource.Builder builder = FilesystemSource.builder()
            .sourceId("docs").rootPath(root)
            .maxFileSize(DocumentLimits.MAX_DOCUMENT_BYTES + 1L);

        assertThatThrownBy(builder::build)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(Long.toString(DocumentLimits.MAX_DOCUMENT_BYTES));
    }

    @Test
    @DisplayName("a file over the walk's bound is never listed, so it is never read")
    void anOversizedFileIsNotListed(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("small.txt"), "x".repeat(10));
        Files.writeString(root.resolve("large.txt"), "x".repeat(1000));
        FilesystemSource source = FilesystemSource.builder()
            .sourceId("docs").rootPath(root).maxFileSize(100).build();

        List<String> listed;
        try (var crawl = source.crawl()) {
            listed = crawl.toList();
        }

        assertThat(listed).containsExactly("small.txt");
    }

    @Test
    @DisplayName("an HTTP crawl cannot be told to hold a body too large to index")
    void httpRefusesMoreThanTheEngineIndexes() {
        HttpSource.Builder builder = HttpSource.builder()
            .allowPrivateAddresses(true)
            .sourceId("site")
            .baseUrl(URI.create("http://127.0.0.1:1/"))
            .maxResponseSize(DocumentLimits.MAX_DOCUMENT_BYTES + 1L);

        assertThatThrownBy(builder::build)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(Long.toString(DocumentLimits.MAX_DOCUMENT_BYTES));
    }

    @Test
    @DisplayName("an HTTP crawl may be told to hold exactly as much as can be indexed")
    void httpAcceptsTheEngineLimit() {
        try (HttpSource source = HttpSource.builder()
                .allowPrivateAddresses(true)
                .sourceId("site")
                .baseUrl(URI.create("http://127.0.0.1:1/"))
                .maxResponseSize(DocumentLimits.MAX_DOCUMENT_BYTES)
                .build()) {
            assertThat(source.getMaxResponseSize()).isEqualTo(DocumentLimits.MAX_DOCUMENT_BYTES);
        }
    }
}
