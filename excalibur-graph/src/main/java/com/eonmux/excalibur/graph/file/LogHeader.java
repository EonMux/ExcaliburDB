package com.eonmux.excalibur.graph.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The sixteen bytes every append-only log in this module begins with, and what a file too short
 * to hold them is.
 *
 * <h2>Layout</h2>
 * <pre>
 * [magic : int64][version : int32][reserved : int32]    big-endian
 * </pre>
 *
 * <h2>Why a short file has two readings</h2>
 * <p>A log is created by writing its header, and nothing else is ever written to it first. A
 * crash inside that write therefore leaves a strict prefix of the header the build that created
 * it writes - a log that never held a record, and never acknowledged anything to anybody. Starting
 * it afresh loses nothing, and refusing it would leave a node that will not start over a file
 * with nothing in it.</p>
 *
 * <p>Any other file that short was not written by that log's creation: a different file at the
 * path, a header of a version this build does not write, bytes a device mangled. Treating it as
 * new is how a log comes to append a header after bytes nobody validated, and then refuse its own
 * file on the next open, which reads those bytes where the magic should be. So it is refused
 * instead, and left exactly as it was.</p>
 *
 * <p>Every log's open and its read-only inspection judge a short file here, so the node and an
 * offline checker cannot disagree about it.</p>
 *
 * @author ExcaliburDB
 */
public final class LogHeader {

    /** Bytes before the first record. */
    public static final int BYTES = 16;

    private LogHeader() {
        throw new AssertionError("Utility class");
    }

    /**
     * The header a log of {@code magic} is created with at {@code version}.
     *
     * @param magic   The log's magic number
     * @param version The version the log is created at
     * @return The {@value #BYTES} header bytes
     */
    public static byte[] of(long magic, int version) {
        return ByteBuffer.allocate(BYTES).putLong(magic).putInt(version).putInt(0).array();
    }

    /**
     * Whether bytes shorter than a header are that header's creation cut short.
     *
     * @param contents The whole file
     * @param magic    The log's magic number
     * @param version  The version this build creates the log at
     * @return true when {@code contents} is shorter than a header and a prefix of the one this
     *         build writes, the empty file included
     */
    public static boolean isTornCreation(byte[] contents, long magic, int version) {
        if (contents.length >= BYTES) {
            return false;
        }
        byte[] header = of(magic, version);
        return Arrays.equals(contents, 0, contents.length, header, 0, contents.length);
    }

    /**
     * Judges a file for a log's open: whether it has to be created, and whether it may be.
     *
     * <p>An absent file, and one whose bytes are a torn creation, are to be created: the open
     * truncates the second to nothing before writing the header, so the header lands at the start
     * of the file rather than after the fragment. A file holding at least a header is left to the
     * log's own replay. Anything else is refused.</p>
     *
     * @param file    The log
     * @param what    What the log is, for the message
     * @param magic   The log's magic number
     * @param version The version this build creates the log at
     * @return true when the log has to be created, false when it holds a header to be read
     * @throws IOException if the file is too short to hold a header and is not a torn creation,
     *                     or cannot be read
     */
    public static boolean requireCreatable(Path file, String what, long magic, int version)
            throws IOException {
        if (!Files.exists(file)) {
            return true;
        }
        if (Files.size(file) >= BYTES) {
            return false;
        }
        byte[] contents = Files.readAllBytes(file);
        if (contents.length >= BYTES) {
            return false;
        }
        requireTornCreation(file, what, contents, magic, version);
        return true;
    }

    /**
     * Refuses bytes shorter than a header that are not a torn creation of it.
     *
     * @param file     The log, named in the failure
     * @param what     What the log is, for the message
     * @param contents The whole file, shorter than a header
     * @param magic    The log's magic number
     * @param version  The version this build creates the log at
     * @throws IOException if the bytes are not a prefix of the header this build writes
     */
    public static void requireTornCreation(Path file, String what, byte[] contents, long magic,
                                           int version) throws IOException {
        if (!isTornCreation(contents, magic, version)) {
            String article = "aeiou".indexOf(Character.toLowerCase(what.charAt(0))) >= 0
                ? "an " : "a ";
            throw new IOException("not " + article + what + ", and too short to be a torn one: "
                + file
                + " is " + contents.length + " bytes, which are not the start of the "
                + BYTES + "-byte header this build creates the log with. The node writes nothing "
                + "after bytes it cannot account for; move the file aside if it is not needed.");
        }
    }

    /**
     * What a read-only inspection reports for a torn creation.
     *
     * @param version  The version this build creates the log at
     * @param contents The whole file, a torn creation
     * @return No records, and every byte a tail the open discards
     */
    public static LogInspection tornCreation(int version, byte[] contents) {
        return new LogInspection(version, 0, 0, contents.length);
    }
}
