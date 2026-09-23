package com.eonmux.excalibur.ingest.registry;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.CRC32C;
import java.util.zip.CheckedOutputStream;

/**
 * Writes a count-marked file as UTF-8 text and closes it with its checksummed {@link EndLine}.
 *
 * <p>The checksum is taken from the bytes as they leave the encoder, not recomputed from the
 * strings handed in, so it covers exactly what reaches the file - line terminators and all - and
 * a reader that hashes the bytes it reads arrives at the same value on any platform. The encoder
 * reports rather than replaces a character it cannot encode, as {@link
 * Files#newBufferedWriter(Path, OpenOption...)} does, so a string that would not survive the
 * round trip fails the save instead of being written as something else.</p>
 *
 * <p>Not thread-safe; each save uses its own.</p>
 */
final class ChecksummedTextWriter implements Closeable {

    private final CheckedOutputStream bytes;
    private final BufferedWriter out;

    private ChecksummedTextWriter(CheckedOutputStream bytes) {
        this.bytes = bytes;
        this.out = new BufferedWriter(new OutputStreamWriter(bytes,
            StandardCharsets.UTF_8.newEncoder()));
    }

    /**
     * Opens a file for writing.
     *
     * @param file    The file
     * @param options How to open it, as {@link Files#newOutputStream(Path, OpenOption...)} takes
     * @return A writer positioned at the file's first byte
     * @throws IOException if the file cannot be opened
     */
    static ChecksummedTextWriter create(Path file, OpenOption... options) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        return new ChecksummedTextWriter(
            new CheckedOutputStream(Files.newOutputStream(file, options), new CRC32C()));
    }

    /**
     * Writes one line and its terminator.
     *
     * @param line The line, which must not itself contain a terminator
     * @throws IOException if the line cannot be written
     */
    void writeLine(String line) throws IOException {
        out.write(line);
        out.newLine();
    }

    /**
     * Writes the closing line, naming the count and the checksum of everything written so far.
     *
     * @param marker The format's end marker
     * @param count  How many records were written
     * @throws IOException if the line cannot be written
     */
    void writeEnd(String marker, int count) throws IOException {
        out.flush();
        writeLine(EndLine.render(marker, count, bytes.getChecksum().getValue()));
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
