package com.eonmux.excalibur.ingest.processor;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("TextContent Tests")
class TextContentTest {

    /** The first bytes of a one-pixel PNG: signature, then a chunk length that is three zeroes. */
    private static final byte[] PNG_PREFIX = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
        0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'
    };

    @Nested
    @DisplayName("Text is decoded")
    class TextIsDecoded {

        @Test
        @DisplayName("ASCII decodes to itself")
        void asciiDecodes() {
            byte[] content = "Ada Lovelace wrote the first algorithm.".getBytes(StandardCharsets.UTF_8);

            assertThat(TextContent.decode(content, StandardCharsets.UTF_8))
                .isEqualTo("Ada Lovelace wrote the first algorithm.");
        }

        @Test
        @DisplayName("UTF-8 beyond ASCII decodes to itself")
        void utf8Decodes() {
            String original = "Grüße aus Köln — 日本語も";
            byte[] content = original.getBytes(StandardCharsets.UTF_8);

            assertThat(TextContent.decode(content, StandardCharsets.UTF_8)).isEqualTo(original);
        }

        @Test
        @DisplayName("Empty content is text, not a refusal")
        void emptyIsText() {
            assertThat(TextContent.decode(new byte[0], StandardCharsets.UTF_8)).isEmpty();
            assertThat(TextContent.isText("")).isTrue();
        }

        @Test
        @DisplayName("UTF-16 text is text when the source declares UTF-16")
        void utf16DeclaredIsText() {
            // Every other byte is zero, so a test on the raw bytes would call this binary. It is
            // the decoded characters that decide, and those hold no U+0000 at all.
            String original = "The Analytical Engine";
            byte[] content = original.getBytes(StandardCharsets.UTF_16);

            assertThat(TextContent.decode(content, StandardCharsets.UTF_16)).isEqualTo(original);
        }

        @Test
        @DisplayName("A windows-1252 document read as UTF-8 survives its replacement characters")
        void mostlyReadableIsStillText() {
            // What a crawl that assumes UTF-8 actually meets: a European-language text whose
            // accented characters are single high bytes. Each becomes one U+FFFD, a few percent
            // of the document, and the rest is worth indexing.
            byte[] content = "Il a dépassé la crête, très tôt, à Genève."
                .getBytes(Charset.forName("windows-1252"));

            String decoded = TextContent.decode(content, StandardCharsets.UTF_8);

            assertThat(decoded).isNotNull();
            assertThat(decoded).contains("Gen").contains("ve.");
        }
    }

    @Nested
    @DisplayName("Markup is reduced to its text")
    class MarkupIsReduced {

        private static final String PAGE =
            "<html><head><script>track()</script></head><body><p>Ada &amp; Charles</p></body></html>";

        @Test
        @DisplayName("An HTML document is indexed by its words, not its tags")
        void htmlIsExtracted() {
            byte[] content = PAGE.getBytes(StandardCharsets.UTF_8);

            assertThat(TextContent.decode(content, StandardCharsets.UTF_8, "text/html; charset=utf-8"))
                .isEqualTo("Ada & Charles");
        }

        @Test
        @DisplayName("An XHTML document is too")
        void xhtmlIsExtracted() {
            byte[] content = PAGE.getBytes(StandardCharsets.UTF_8);

            assertThat(TextContent.decode(content, StandardCharsets.UTF_8, "application/xhtml+xml"))
                .isEqualTo("Ada & Charles");
        }

        @Test
        @DisplayName("Any other type keeps its angle brackets: they are its content")
        void otherTypesAreLeftAlone() {
            byte[] content = PAGE.getBytes(StandardCharsets.UTF_8);

            assertThat(TextContent.decode(content, StandardCharsets.UTF_8, "text/plain"))
                .isEqualTo(PAGE);
            assertThat(TextContent.decode(content, StandardCharsets.UTF_8, "application/xml"))
                .isEqualTo(PAGE);
        }

        @Test
        @DisplayName("Binary named HTML is still binary")
        void binaryUnderAnHtmlTypeIsRefused() {
            // Judged before the markup is stripped: stripping would drop the angle brackets a
            // PNG happens to contain and could leave too little behind for either rule to see.
            assertThat(TextContent.decode(PNG_PREFIX, StandardCharsets.UTF_8, "text/html"))
                .isNull();
        }
    }

    @Nested
    @DisplayName("Binary is refused")
    class BinaryIsRefused {

        @Test
        @DisplayName("A PNG is not text")
        void pngIsNotText() {
            assertThat(TextContent.decode(PNG_PREFIX, StandardCharsets.UTF_8)).isNull();
        }

        @Test
        @DisplayName("A PNG is not text under an encoding that accepts every byte either")
        void pngIsNotTextInLatin1() {
            // ISO-8859-1 decodes any byte sequence without complaint, so the replacement-character
            // rule never fires here. The zero bytes are what give it away.
            assertThat(TextContent.decode(PNG_PREFIX, StandardCharsets.ISO_8859_1)).isNull();
        }

        @Test
        @DisplayName("Random bytes are not text")
        void randomBytesAreNotText() {
            byte[] content = new byte[4096];
            new Random(42).nextBytes(content);

            assertThat(TextContent.decode(content, StandardCharsets.UTF_8)).isNull();
        }

        @Test
        @DisplayName("A single zero character anywhere is enough")
        void oneZeroCharacterIsEnough() {
            String text = "a".repeat(10_000) + '\u0000' + "b".repeat(10_000);

            assertThat(TextContent.isText(text)).isFalse();
        }

        @Test
        @DisplayName("Bytes with no zero but nothing readable are not text")
        void unreadableWithoutZeroIsNotText() {
            // 0x80 is a UTF-8 continuation byte with nothing to continue: never a valid start,
            // never zero. Content made only of these carries no zero byte and decodes to nothing
            // but replacement characters, which is the case the second rule exists for.
            byte[] content = new byte[512];
            java.util.Arrays.fill(content, (byte) 0x80);

            assertThat(TextContent.decode(content, StandardCharsets.UTF_8)).isNull();
        }
    }

    @Nested
    @DisplayName("The replacement-character bar")
    class ReplacementBar {

        @Test
        @DisplayName("Just under a third replacement characters is still text")
        void justUnderTheBar() {
            String text = "\uFFFD".repeat(32) + "a".repeat(68);

            assertThat(TextContent.isText(text)).isTrue();
        }

        @Test
        @DisplayName("A third replacement characters is not text")
        void atTheBar() {
            String text = "\uFFFD".repeat(34) + "a".repeat(66);

            assertThat(TextContent.isText(text)).isFalse();
        }
    }

    @Nested
    @DisplayName("Arguments")
    class Arguments {

        @Test
        @DisplayName("Null content is refused")
        void nullContent() {
            assertThatNullPointerException()
                .isThrownBy(() -> TextContent.decode(null, StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("Null charset is refused")
        void nullCharset() {
            assertThatNullPointerException()
                .isThrownBy(() -> TextContent.decode(new byte[0], null));
        }

        @Test
        @DisplayName("Null text is refused")
        void nullText() {
            assertThatNullPointerException().isThrownBy(() -> TextContent.isText(null));
        }
    }
    @Nested
    @DisplayName("The decoding is named")
    class DecodingIsNamed {

        @Test
        @DisplayName("by the charset the bytes are read in")
        void namesTheCharset() {
            assertThat(TextContent.decodingOf(StandardCharsets.UTF_8, "text/plain"))
                .isEqualTo("UTF-8");
            assertThat(TextContent.decodingOf(Charset.forName("windows-1252"), "text/plain"))
                .isEqualTo("windows-1252");
        }

        @Test
        @DisplayName("and by whether markup is reduced to its text")
        void namesMarkup() {
            assertThat(TextContent.decodingOf(StandardCharsets.UTF_8, "text/html; charset=utf-8"))
                .isEqualTo("UTF-8+markup");
        }

        @Test
        @DisplayName("and by nothing else a content type says")
        void ignoresTypesThatDecodeAlike() {
            assertThat(TextContent.decodingOf(StandardCharsets.UTF_8, "text/markdown"))
                .isEqualTo(TextContent.decodingOf(StandardCharsets.UTF_8, "text/plain"));
            assertThat(TextContent.decodingOf(StandardCharsets.UTF_8, null))
                .isEqualTo("UTF-8");
        }

        @Test
        @DisplayName("Null charset is refused")
        void nullCharset() {
            assertThatNullPointerException()
                .isThrownBy(() -> TextContent.decodingOf(null, "text/plain"));
        }
    }
}
