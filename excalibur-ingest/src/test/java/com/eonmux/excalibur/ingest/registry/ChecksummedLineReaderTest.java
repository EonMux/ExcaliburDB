package com.eonmux.excalibur.ingest.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The line reader every count-marked file is read with, against the reader it replaced.
 *
 * <p>Two properties matter. It splits a file into exactly the lines a {@code BufferedReader}
 * would, so no record a file held before is read differently now; and the checksum it reports
 * before a line covers exactly the bytes before that line, terminators included, so a closing
 * line can be compared with it.</p>
 */
class ChecksummedLineReaderTest {

    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {"", "a", "a\n", "a\nb", "a\r\nb\r\n", "a\rb\r", "\n\n", "\r\n\r",
        "a\r\r\nb", "ünï\ncödé\n", "a\n\rb"})
    @DisplayName("splits into the lines a BufferedReader returns, with the bytes before the last")
    void agreesWithABufferedReader(String text) throws IOException {
        List<String> expected = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                expected.add(line);
            }
        }

        List<String> actual = new ArrayList<>();
        long before = 0;
        try (ChecksummedLineReader reader = reader(text)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                actual.add(line);
                before = reader.checksumBeforeLine();
            }
        }

        assertThat(actual).isEqualTo(expected);
        if (!expected.isEmpty()) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            byte[] last = expected.get(expected.size() - 1).getBytes(StandardCharsets.UTF_8);
            int trailing = text.endsWith("\r\n") ? 2
                : text.endsWith("\n") || text.endsWith("\r") ? 1 : 0;
            String prefix = new String(bytes, 0, bytes.length - trailing - last.length,
                StandardCharsets.UTF_8);
            assertThat(before).isEqualTo(Sealed.crc(prefix));
        }
    }

    @Test
    @DisplayName("lines and terminators that straddle the read buffer come back whole")
    void aLineLongerThanTheBufferIsWhole() throws IOException {
        String longLine = "x".repeat(64 * 1024 - 1);
        String text = longLine + "\r\n" + "y".repeat(200_000) + "\n\\#end";

        try (ChecksummedLineReader reader = reader(text)) {
            assertThat(reader.readLine()).isEqualTo(longLine);
            assertThat(reader.readLine()).hasSize(200_000);
            assertThat(reader.readLine()).isEqualTo("\\#end");
            assertThat(reader.checksumBeforeLine())
                .isEqualTo(Sealed.crc(text.substring(0, text.length() - "\\#end".length())));
            assertThat(reader.lineTerminated()).isFalse();
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    @DisplayName("a stream that returns short reads is read the same")
    void shortReadsChangeNothing() throws IOException {
        byte[] bytes = "one\r\ntwo\rthree\n".getBytes(StandardCharsets.UTF_8);
        InputStream trickle = new InputStream() {
            private int next;

            @Override
            public int read() {
                return next < bytes.length ? bytes[next++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (next >= bytes.length) {
                    return -1;
                }
                b[off] = bytes[next++];
                return 1;
            }
        };

        try (ChecksummedLineReader reader = new ChecksummedLineReader(trickle)) {
            assertThat(reader.readLine()).isEqualTo("one");
            assertThat(reader.readLine()).isEqualTo("two");
            assertThat(reader.readLine()).isEqualTo("three");
            assertThat(reader.checksumBeforeLine()).isEqualTo(Sealed.crc("one\r\ntwo\r"));
            assertThat(reader.lineTerminated()).isTrue();
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    @DisplayName("a byte that is not UTF-8 fails the read rather than becoming a replacement")
    void malformedTextIsRefused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("f");
        Files.write(file, new byte[] {'a', '\n', (byte) 0xFF, '\n'});

        assertThatThrownBy(() -> ChecksummedLineReader.readAll(file))
            .isInstanceOf(CharacterCodingException.class);
    }

    @Test
    @DisplayName("reading a whole file reports its lines, the checksum before the last, and its end")
    void readAllReportsTheLastLine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("f");
        Files.writeString(file, "h\nr\ne");

        ChecksummedLineReader.Lines lines = ChecksummedLineReader.readAll(file);

        assertThat(lines.lines()).containsExactly("h", "r", "e");
        assertThat(lines.checksumBeforeLast()).isEqualTo(Sealed.crc("h\nr\n"));
        assertThat(lines.lastLineTerminated()).isFalse();

        Files.writeString(file, "");
        ChecksummedLineReader.Lines empty = ChecksummedLineReader.readAll(file);
        assertThat(empty.lines()).isEmpty();
        assertThat(empty.checksumBeforeLast()).isEqualTo(Sealed.crc(""));
        assertThat(empty.lastLineTerminated()).isTrue();
    }

    @Test
    @DisplayName("a closing line is read in the layout the file's version calls for")
    void endLinesAreParsedByLayout() {
        String marker = "\\#end";

        assertThat(EndLine.parse("\\#end|3", marker, false)).isEqualTo(new EndLine(3,
            EndLine.NO_CHECKSUM));
        assertThat(EndLine.parse("\\#end|3|0000abcd", marker, true))
            .isEqualTo(new EndLine(3, 0xabcdL));
        assertThat(EndLine.parse("\\#end|3|0000abcd", marker, true).checksummed()).isTrue();
        assertThat(EndLine.parse("\\#end|3", marker, false).checksummed()).isFalse();
        assertThat(EndLine.render(marker, 3, 0xFFFFFFFFL)).isEqualTo("\\#end|3|ffffffff");

        for (String notAnEnd : new String[] {"\\#end|3", "\\#end|3|0000ABCD", "\\#end|3|abcd",
            "\\#end|3|0000abcd ", "\\#end|-1|0000abcd", "\\#end|x|0000abcd", "\\#end",
            "#end|3|0000abcd"}) {
            assertThat(EndLine.parse(notAnEnd, marker, true)).as(notAnEnd).isNull();
        }
        for (String notAnEnd : new String[] {"\\#end|3|0000abcd", "\\#end|-1", "\\#end|3 ",
            "\\#end|"}) {
            assertThat(EndLine.parse(notAnEnd, marker, false)).as(notAnEnd).isNull();
        }
        assertThat(EndLine.parse(null, marker, true)).isNull();
        assertThat(new EndLine(1, 5).mismatch(6))
            .contains("00000005").contains("00000006").contains("checksum does not match");
    }

    private static ChecksummedLineReader reader(String text) {
        return new ChecksummedLineReader(
            new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
}
