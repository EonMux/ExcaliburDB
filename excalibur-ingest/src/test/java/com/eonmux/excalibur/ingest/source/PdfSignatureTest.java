package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A PDF is told by its header")
class PdfSignatureTest {

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("at the first byte, as the specification puts it, whatever follows")
    void atTheStart() {
        assertThat(PdfSignature.isPdf(bytes("%PDF-1.7\n%âã\n1 0 obj"))).isTrue();
        assertThat(PdfSignature.isPdf(bytes("%PDF-"))).isTrue();
    }

    @Test
    @DisplayName("past a few bytes of junk, when it begins a line and the file ends as a PDF")
    void afterJunk() {
        String pdf = "\u0000\u0001junk\r\n%PDF-1.4\n1 0 obj\nendobj\ntrailer\n%%EOF\n";
        assertThat(PdfSignature.isPdf(bytes(pdf))).isTrue();
    }

    @Test
    @DisplayName("not where text merely mentions it")
    void notInText() {
        // This class's own javadoc once read as a PDF: "{@code %PDF-}" within its first kilobyte.
        assertThat(PdfSignature.isPdf(bytes("/** Opens with {@code %PDF-} and a version. */")))
            .isFalse();
        assertThat(PdfSignature.isPdf(bytes("# Notes\n%PDF-1.7 is the header\nno trailer here")))
            .as("at a line start with a version, but without a PDF's end")
            .isFalse();
        assertThat(PdfSignature.isPdf(bytes("Notes\n%PDF- and %%EOF bracket a PDF\n%%EOF")))
            .as("with the trailer, but no version after the header")
            .isFalse();
    }

    @Test
    @DisplayName("not past the first kilobyte")
    void notPastTheWindow() {
        String late = " ".repeat(PdfSignature.SEARCH_WINDOW) + "\n%PDF-1.4\n%%EOF";
        assertThat(PdfSignature.isPdf(bytes(late))).isFalse();
    }

    @Test
    @DisplayName("not from a near miss or nothing at all")
    void notByANearMiss() {
        assertThat(PdfSignature.isPdf(bytes("%PDF"))).isFalse();
        assertThat(PdfSignature.isPdf(bytes(""))).isFalse();
    }

    @Test
    @DisplayName("from a buffer's position, leaving the buffer as it was")
    void fromABuffer() {
        ByteBuffer buffer = bytes("xx%PDF-2.0").asReadOnlyBuffer();
        buffer.position(2);

        assertThat(PdfSignature.isPdf(buffer)).isTrue();
        assertThat(buffer.position()).isEqualTo(2);
    }

    @Test
    @DisplayName("the same from a file's two ends as from its whole")
    void fromAFilesEnds(@TempDir Path dir) throws Exception {
        String pdf = "junk\n%PDF-1.4\n" + "x".repeat(10_000) + "\n%%EOF\n";
        Path file = Files.writeString(dir.resolve("a.pdf"), pdf, StandardCharsets.ISO_8859_1);
        byte[] all = Files.readAllBytes(file);
        ByteBuffer head = ByteBuffer.wrap(all, 0, PdfSignature.SEARCH_WINDOW).slice();
        ByteBuffer tail = ByteBuffer.wrap(all, all.length - PdfSignature.SEARCH_WINDOW,
            PdfSignature.SEARCH_WINDOW).slice();

        assertThat(PdfSignature.isPdf(head, tail)).isTrue();
        assertThat(PdfSignature.isPdf(ByteBuffer.wrap(all))).isTrue();
    }
}
