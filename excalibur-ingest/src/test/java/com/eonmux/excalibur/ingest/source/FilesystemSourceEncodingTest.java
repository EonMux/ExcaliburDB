package com.eonmux.excalibur.ingest.source;

import com.eonmux.excalibur.ingest.processor.TextContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which encoding a directory walk reads a file in: its byte-order mark, else the charset the
 * operator declared for the source, else UTF-8.
 *
 * <p>Every case checks the decoded text rather than only the charset reported, because the text
 * is what gets chunked and searched - a right answer about the charset that still decoded the
 * wrong characters would pass a charset-only assertion.</p>
 */
@DisplayName("FilesystemSource encoding")
class FilesystemSourceEncodingTest {

    /** Text outside ASCII, so a wrong single-byte guess cannot decode it by coincidence. */
    private static final String TEXT = "Grüße aus Köln – café";

    private static final String BOM = "﻿";

    @TempDir
    Path root;

    private FilesystemSource source(Charset declared) {
        return FilesystemSource.builder()
            .sourceId("encodings")
            .rootPath(root)
            .charset(declared)
            .build();
    }

    private FetchedDocument fetch(String name, byte[] content, Charset declared)
            throws IOException, SourceException, DocumentNotFoundException {
        Files.write(root.resolve(name), content);
        try (FilesystemSource source = source(declared)) {
            return source.fetch(name);
        }
    }

    @Test
    @DisplayName("a UTF-16LE file with a mark is read as UTF-16LE with nothing declared")
    void utf16LittleEndianWithMark() throws Exception {
        FetchedDocument doc = fetch("le.txt", (BOM + TEXT).getBytes(StandardCharsets.UTF_16LE),
            null);

        assertThat(doc.getCharset()).isEqualTo(StandardCharsets.UTF_16LE);
        assertThat(doc.getText()).isEqualTo(TEXT);
    }

    @Test
    @DisplayName("a UTF-16BE file with a mark is read as UTF-16BE with nothing declared")
    void utf16BigEndianWithMark() throws Exception {
        FetchedDocument doc = fetch("be.txt", (BOM + TEXT).getBytes(StandardCharsets.UTF_16BE),
            null);

        assertThat(doc.getCharset()).isEqualTo(StandardCharsets.UTF_16BE);
        assertThat(doc.getText()).isEqualTo(TEXT);
    }

    @Test
    @DisplayName("a UTF-16 file without a mark is read as the source declares")
    void declaredUtf16WithoutMark() throws Exception {
        FetchedDocument doc = fetch("bare.txt", TEXT.getBytes(StandardCharsets.UTF_16LE),
            StandardCharsets.UTF_16LE);

        assertThat(doc.getCharset()).isEqualTo(StandardCharsets.UTF_16LE);
        assertThat(doc.getText()).isEqualTo(TEXT);
        assertThat(TextContent.isText(doc.getText()))
            .as("every second byte is zero, but no decoded character is U+0000")
            .isTrue();
    }

    @Test
    @DisplayName("a Latin-1 file is read as Latin-1 when the source declares it")
    void declaredLatin1() throws Exception {
        FetchedDocument doc = fetch("legacy.txt", "Grüße aus Köln".getBytes(
            StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1);

        assertThat(doc.getCharset()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(doc.getText()).isEqualTo("Grüße aus Köln");
    }

    @Test
    @DisplayName("a mark outranks the charset the source declares")
    void markOutranksDeclaration() throws Exception {
        // One file in a windows-1252 export saved by an editor that writes UTF-16: the mark is
        // about these bytes, the declaration only about the directory.
        FetchedDocument doc = fetch("odd-one-out.txt",
            (BOM + TEXT).getBytes(StandardCharsets.UTF_16LE), Charset.forName("windows-1252"));

        assertThat(doc.getCharset()).isEqualTo(StandardCharsets.UTF_16LE);
        assertThat(doc.getText()).isEqualTo(TEXT);
    }

    @Test
    @DisplayName("an unmarked, undeclared file is read as UTF-8, and a UTF-8 mark is dropped")
    void utf8ByDefault() throws Exception {
        FetchedDocument plain = fetch("plain.txt", TEXT.getBytes(StandardCharsets.UTF_8), null);
        FetchedDocument marked = fetch("marked.txt",
            (BOM + TEXT).getBytes(StandardCharsets.UTF_8), null);

        assertThat(plain.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(plain.getText()).isEqualTo(TEXT);
        assertThat(marked.getText()).isEqualTo(TEXT);
    }

    @Test
    @DisplayName("reports the charset it was given, or none")
    void reportsDeclaredCharset() {
        try (FilesystemSource declared = source(StandardCharsets.ISO_8859_1);
             FilesystemSource undeclared = source(null)) {
            assertThat(declared.getCharset()).isEqualTo(StandardCharsets.ISO_8859_1);
            assertThat(undeclared.getCharset()).isNull();
        }
    }
}
