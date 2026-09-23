package com.eonmux.excalibur.ingest.registry;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Reads a UTF-8 text file line by line while keeping the CRC32C of every byte it has passed.
 *
 * <h2>Why not a {@code BufferedReader}</h2>
 * <p>The line-oriented files the node keeps close with a line that carries the CRC32C of every
 * byte before it, header included, exactly as written. A {@code BufferedReader} hands back lines
 * with their terminators stripped and reads ahead of the line it returns, so neither the bytes a
 * line was written as nor where the last line starts can be recovered from it. This reads the
 * bytes itself, so that {@link #checksumBeforeLine()} is the checksum of precisely the bytes that
 * precede the line most recently returned - which, once the last line has been read, is what the
 * file's closing line must name.</p>
 *
 * <h2>The same lines a {@code BufferedReader} would return</h2>
 * <p>A line ends at {@code \n}, at {@code \r}, or at {@code \r\n}, and a last line with no
 * terminator is still a line - {@link java.io.BufferedReader#readLine()}'s rules, so a file judged here
 * splits into the same records it did when it was read with one. Each line is decoded strictly: a
 * byte sequence that is not UTF-8 fails the read with a {@link
 * java.nio.charset.CharacterCodingException} rather than turning into a replacement character,
 * which is what {@link Files#readAllLines(Path)} and {@link Files#newBufferedReader(Path)} do too.
 * Splitting on the terminator bytes before decoding is sound because UTF-8 never uses either
 * byte inside a multi-byte sequence.</p>
 *
 * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which judge
 * these files offline and must read them exactly as the node does.</p>
 *
 * <p>Not thread-safe.</p>
 */
public final class ChecksummedLineReader implements Closeable {

    /** Bytes read from the underlying stream at a time. */
    private static final int BUFFER_BYTES = 64 * 1024;

    private final InputStream in;
    private final CRC32C checksum = new CRC32C();
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    private final byte[] buffer = new byte[BUFFER_BYTES];
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private int position;
    private int limit;
    private long checksumBeforeLine;
    private boolean lineTerminated = true;

    /**
     * Reads from a stream, which this reader closes when it is closed.
     *
     * @param in The bytes to read
     * @throws NullPointerException if the stream is null
     */
    public ChecksummedLineReader(InputStream in) {
        this.in = Objects.requireNonNull(in, "in must not be null");
    }

    /**
     * Opens a file for reading.
     *
     * @param file The file
     * @return A reader positioned at its first byte
     * @throws IOException if the file cannot be opened
     */
    public static ChecksummedLineReader open(Path file) throws IOException {
        return new ChecksummedLineReader(Files.newInputStream(file));
    }

    /**
     * Every line of a file, and the checksum of the bytes before its last one.
     *
     * @param lines              The lines, terminators removed
     * @param checksumBeforeLast The CRC32C of every byte before the last line; the CRC32C of
     *                           nothing when the file is empty
     * @param lastLineTerminated Whether the file ends with a line terminator; true when it is
     *                           empty
     */
    public record Lines(List<String> lines, long checksumBeforeLast, boolean lastLineTerminated) {

        /**
         * Copies the lines.
         *
         * @throws NullPointerException if the list is null
         */
        public Lines {
            lines = List.copyOf(lines);
        }
    }

    /**
     * Reads a whole file, for the files small enough to hold in memory at once.
     *
     * @param file The file
     * @return Its lines and the checksum its closing line is compared with
     * @throws IOException if the file cannot be read, or is not UTF-8 text
     */
    public static Lines readAll(Path file) throws IOException {
        try (ChecksummedLineReader reader = open(file)) {
            List<String> lines = new ArrayList<>();
            long before = new CRC32C().getValue();
            boolean terminated = true;
            for (String next = reader.readLine(); next != null; next = reader.readLine()) {
                lines.add(next);
                before = reader.checksumBeforeLine();
                terminated = reader.lineTerminated();
            }
            return new Lines(lines, before, terminated);
        }
    }

    /**
     * Reads the next line.
     *
     * @return The line without its terminator, or null at the end of the file
     * @throws IOException if the stream cannot be read, or the line is not UTF-8
     */
    public String readLine() throws IOException {
        long before = checksum.getValue();
        line.reset();
        boolean readAny = false;
        while (true) {
            if (position >= limit && !fill()) {
                if (!readAny) {
                    return null;
                }
                lineTerminated = false;
                break;
            }
            readAny = true;
            int start = position;
            while (position < limit && buffer[position] != '\n' && buffer[position] != '\r') {
                position++;
            }
            line.write(buffer, start, position - start);
            checksum.update(buffer, start, position - start);
            if (position < limit) {
                byte terminator = buffer[position++];
                checksum.update(terminator);
                if (terminator == '\r') {
                    consumeLineFeed();
                }
                lineTerminated = true;
                break;
            }
        }
        checksumBeforeLine = before;
        return decoder.decode(ByteBuffer.wrap(line.toByteArray())).toString();
    }

    /**
     * After a {@code \r}, consumes a {@code \n} that makes the two one terminator.
     */
    private void consumeLineFeed() throws IOException {
        if (position >= limit && !fill()) {
            return;
        }
        if (buffer[position] == '\n') {
            checksum.update(buffer[position++]);
        }
    }

    /**
     * Refills the buffer.
     *
     * @return false at the end of the stream
     */
    private boolean fill() throws IOException {
        int read;
        do {
            read = in.read(buffer, 0, buffer.length);
        } while (read == 0);
        if (read < 0) {
            return false;
        }
        position = 0;
        limit = read;
        return true;
    }

    /**
     * Returns the CRC32C of every byte before the line most recently returned.
     *
     * <p>Once {@link #readLine()} has returned the last line, this is the checksum that line
     * must name.</p>
     *
     * @return The checksum, as the unsigned 32-bit value {@link CRC32C#getValue()} reports
     */
    public long checksumBeforeLine() {
        return checksumBeforeLine;
    }

    /**
     * Reports whether the line most recently returned ended with a terminator.
     *
     * <p>False only for a last line the file ends inside of, which is what an append the
     * process did not live to finish leaves behind.</p>
     *
     * @return true if the line was terminated
     */
    public boolean lineTerminated() {
        return lineTerminated;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
