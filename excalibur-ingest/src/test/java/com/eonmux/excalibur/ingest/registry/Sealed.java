package com.eonmux.excalibur.ingest.registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Builds count-marked files by hand, closed the way the node's writers close them.
 *
 * <p>For the tests that need a file whose records the writer would never produce - a malformed
 * line, a count that disagrees - and need the checksum to hold, so that what is under test is
 * the reader's handling of that record or that count rather than its handling of a checksum.</p>
 */
final class Sealed {

    private Sealed() {
    }

    /**
     * Writes lines, each ended with {@code \n}, followed by a checksummed closing line.
     *
     * @param file   The file
     * @param lines  Every line before the closing one, header included
     * @param marker The format's end marker
     * @param count  The count the closing line declares
     * @throws IOException if the file cannot be written
     */
    static void write(Path file, List<String> lines, String marker, int count) throws IOException {
        Files.writeString(file, seal(lines, marker, count), StandardCharsets.UTF_8);
    }

    /**
     * Renders lines and a checksummed closing line as one file's contents.
     *
     * @param lines  Every line before the closing one, header included
     * @param marker The format's end marker
     * @param count  The count the closing line declares
     * @return The contents
     */
    static String seal(List<String> lines, String marker, int count) {
        StringBuilder body = new StringBuilder();
        lines.forEach(line -> body.append(line).append('\n'));
        return body + EndLine.render(marker, count, crc(body.toString())) + "\n";
    }

    /**
     * Rewrites a file's closing line so its checksum matches whatever now precedes it, keeping
     * the count it declares.
     *
     * @param file   The file, whose last line is a checksummed closing line
     * @param marker The format's end marker
     * @throws IOException if the file cannot be read or written
     */
    static void reseal(Path file, String marker) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        EndLine end = EndLine.parse(lines.get(lines.size() - 1), marker, true);
        write(file, lines.subList(0, lines.size() - 1), marker, end.count());
    }

    /**
     * Computes the CRC32C of a string's UTF-8 bytes.
     *
     * @param text The text
     * @return The checksum
     */
    static long crc(String text) {
        CRC32C crc = new CRC32C();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /**
     * Flips one bit of the byte at an offset from the start of a file's second line.
     *
     * @param file   The file
     * @param offset How far into the second line
     * @throws IOException if the file cannot be read or written
     */
    static void flipInSecondLine(Path file, int offset) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int second = 0;
        while (bytes[second] != '\n') {
            second++;
        }
        bytes[second + 1 + offset] ^= 0x01;
        Files.write(file, bytes);
    }
}
