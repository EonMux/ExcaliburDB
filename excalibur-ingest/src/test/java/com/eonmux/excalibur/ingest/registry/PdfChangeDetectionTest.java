package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.ingest.processor.DocumentDecoder;
import com.eonmux.excalibur.ingest.processor.PdfConverter;
import com.eonmux.excalibur.ingest.processor.PdfFixtures;
import com.eonmux.excalibur.ingest.source.DocumentValidators;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A source that can answer a conditional request answers that an unchanged PDF is unchanged, even
 * once the node reads PDFs another way.
 */
@DisplayName("Change detection for a PDF read another way")
@DisabledOnOs(OS.WINDOWS)
class PdfChangeDetectionTest {

    private static final String SOURCE_ID = "site";
    private static final String DOCUMENT = "paper.pdf";

    @TempDir
    Path dir;

    private DocumentRegistry registry;
    private Source source;
    private FetchedDocument pdf;

    @BeforeEach
    void setUp() throws Exception {
        registry = new DocumentRegistry();
        pdf = new FetchedDocument(DOCUMENT, PdfFixtures.withPages("Bell's inequality."),
            "application/pdf", 1L);
        registry.register(DocumentMetadata.builder()
            .sourceId(SOURCE_ID)
            .documentId(DOCUMENT)
            .lastModifiedMillis(1L)
            .entityTag("\"v1\"")
            .decoding(DocumentDecoder.builtIn().decodingOf(pdf))
            .contentHash(pdf.contentHash())
            .contentLength(pdf.getContentLength())
            .chunkIds(List.of(DOCUMENT + "#0"))
            .indexTimeMillis(1L)
            .build());
        source = mock(Source.class);
        when(source.getSourceId()).thenReturn(SOURCE_ID);
        // What a server holding its validators answers: nothing changed.
        when(source.fetchIfChanged(eq(DOCUMENT), any(DocumentValidators.class)))
            .thenReturn(Optional.empty());
        when(source.fetch(DOCUMENT)).thenReturn(pdf);
    }

    private DocumentDecoder converting() throws Exception {
        Path program = dir.resolve("ocr");
        Files.writeString(program, "#!/bin/sh\necho text\n");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        return DocumentDecoder.reading(
            PdfConverter.of(program.toString(), List.of(), Duration.ofSeconds(30), 1));
    }

    @Test
    @DisplayName("is fetched whole, and found changed, once a converter is configured")
    void aNewReaderFetchesItWhole() throws Exception {
        ChangeDetector detector = new ChangeDetector(registry, converting());

        assertThat(detector.hasChanged(source, DOCUMENT)).isTrue();
        verify(source).fetch(DOCUMENT);
        verify(source, never()).fetchIfChanged(any(), any());
    }

    @Test
    @DisplayName("is asked about conditionally while it is read as it was")
    void theSameReaderAsksConditionally() throws Exception {
        ChangeDetector detector = new ChangeDetector(registry, DocumentDecoder.builtIn());

        assertThat(detector.hasChanged(source, DOCUMENT)).isFalse();
        verify(source, never()).fetch(any());
    }

    @Test
    @DisplayName("a text document is never fetched whole for this")
    void textIsUnaffected() throws Exception {
        DocumentDecoder decoder = converting();

        assertThat(decoder.readsOtherwise("UTF-8")).isFalse();
        assertThat(decoder.readsOtherwise("UTF-8+markup")).isFalse();
        assertThat(decoder.readsOtherwise(null)).isFalse();
        assertThat(decoder.readsOtherwise("pdf")).isTrue();
        assertThat(decoder.readsOtherwise("pdf+ocr")).isFalse();
        assertThat(DocumentDecoder.builtIn().readsOtherwise("pdf+ocr")).isTrue();
    }
}
