package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TextEncoding")
class TextEncodingTest {

    /** U+FEFF; each encoding writes it as that encoding's mark. */
    private static final String BOM = "﻿";

    private static final Charset UTF_32BE = Charset.forName("UTF-32BE");
    private static final Charset UTF_32LE = Charset.forName("UTF-32LE");

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** The text encoded with the charset's own mark in front of it. */
    private static byte[] marked(String text, Charset charset) {
        return (BOM + text).getBytes(charset);
    }

    @Nested
    @DisplayName("reading a byte-order mark")
    class ByteOrderMark {

        @Test
        @DisplayName("names every Unicode encoding by its mark")
        void namesEachEncoding() {
            assertThat(TextEncoding.ofByteOrderMark(marked("a", StandardCharsets.UTF_8)))
                .isEqualTo(StandardCharsets.UTF_8);
            assertThat(TextEncoding.ofByteOrderMark(marked("a", StandardCharsets.UTF_16BE)))
                .isEqualTo(StandardCharsets.UTF_16BE);
            assertThat(TextEncoding.ofByteOrderMark(marked("a", StandardCharsets.UTF_16LE)))
                .isEqualTo(StandardCharsets.UTF_16LE);
            assertThat(TextEncoding.ofByteOrderMark(marked("a", UTF_32BE))).isEqualTo(UTF_32BE);
            assertThat(TextEncoding.ofByteOrderMark(marked("a", UTF_32LE))).isEqualTo(UTF_32LE);
        }

        @Test
        @DisplayName("reads a UTF-32LE mark as UTF-32, not as UTF-16 followed by a zero")
        void utf32LittleEndianIsNotUtf16() {
            assertThat(TextEncoding.ofByteOrderMark(bytes(0xFF, 0xFE, 0x00, 0x00)))
                .isEqualTo(UTF_32LE);
        }

        @Test
        @DisplayName("finds no mark in content without one, or too short to hold one")
        void noMark() {
            assertThat(TextEncoding.ofByteOrderMark("plain".getBytes(StandardCharsets.UTF_8)))
                .isNull();
            assertThat(TextEncoding.ofByteOrderMark(new byte[0])).isNull();
            assertThat(TextEncoding.ofByteOrderMark(bytes(0xFF))).isNull();
            assertThat(TextEncoding.ofByteOrderMark(bytes(0xEF, 0xBB))).isNull();
        }

        @Test
        @DisplayName("refuses null content")
        void refusesNull() {
            assertThatThrownBy(() -> TextEncoding.ofByteOrderMark(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("resolving the encoding")
    class Resolve {

        @Test
        @DisplayName("a mark outranks what the source declared")
        void markOutranksDeclaration() {
            byte[] content = marked("héllo", StandardCharsets.UTF_16LE);

            assertThat(TextEncoding.resolve(content, StandardCharsets.ISO_8859_1))
                .isEqualTo(StandardCharsets.UTF_16LE);
        }

        @Test
        @DisplayName("an unmarked document is read as declared")
        void declarationWithoutMark() {
            byte[] content = "héllo".getBytes(StandardCharsets.ISO_8859_1);

            assertThat(TextEncoding.resolve(content, StandardCharsets.ISO_8859_1))
                .isEqualTo(StandardCharsets.ISO_8859_1);
        }

        @Test
        @DisplayName("an unmarked, undeclared document is read as UTF-8")
        void defaultsToUtf8() {
            assertThat(TextEncoding.resolve("x".getBytes(StandardCharsets.UTF_8), null))
                .isEqualTo(StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("decoding")
    class Decode {

        @Test
        @DisplayName("drops the mark, so it is not glued to the first word")
        void dropsTheMark() {
            for (Charset charset : new Charset[] {StandardCharsets.UTF_8,
                    StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE, UTF_32BE, UTF_32LE}) {
                byte[] content = marked("Introduction", charset);

                assertThat(TextEncoding.decode(content, TextEncoding.resolve(content, null)))
                    .as(charset.name())
                    .isEqualTo("Introduction");
            }
        }

        @Test
        @DisplayName("keeps text that has no mark exactly as it is")
        void keepsUnmarkedText() {
            assertThat(TextEncoding.decode("abc".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8)).isEqualTo("abc");
            assertThat(TextEncoding.decode(new byte[0], StandardCharsets.UTF_8)).isEmpty();
        }

        @Test
        @DisplayName("refuses a null charset")
        void refusesNullCharset() {
            assertThatThrownBy(() -> TextEncoding.decode(new byte[0], null))
                .isInstanceOf(NullPointerException.class);
        }
    }
}
