package com.eonmux.excalibur.ingest.visibility;

import com.eonmux.excalibur.ingest.registry.EndLine;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/**
 * The layout of the in-flight document journal, {@code document-registry.in-flight}.
 *
 * <h2>Layout</h2>
 * <p>A first line, {@code \#in-flight|<version>}, names the format. Each line after it is a
 * record: {@code +|<key>} when a document's ingest began, {@code -|<key>} when it was published or
 * rolled back, with backslashes and line terminators in the key escaped.</p>
 *
 * <p>From version 2 every line, the header included, is sealed: it ends with {@code |<crc>}, the
 * CRC32C of the UTF-8 bytes of the line before that suffix, in eight lowercase hexadecimal digits.
 * The journal is appended to a line at a time and forced after each append, so a checksum over
 * the whole file would have to be rewritten with every mark; a checksum per line is what an
 * append-only log can carry. It is also the granularity the journal's failures come in: an
 * append the process did not live to finish damages its own line and nothing else.</p>
 *
 * <p>Version 1 lines carry no seal and are read exactly as they were written. What the checksum
 * decides, and why a damaged complete line is refused where a torn last line is not, is described
 * on the journal itself.</p>
 *
 * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which judge
 * this file offline and must accept exactly what the journal accepts.</p>
 */
public final class InFlightJournalFormat {

    /** Appended to the registry's own name, so the two sit together in the data directory. */
    public static final String SUFFIX = ".in-flight";

    /** Opens the first line, which names the format the rest of the file is in. */
    public static final String HEADER_MARKER = "\\#in-flight";

    /** The format this build writes, and the newest it reads. */
    public static final int FORMAT_VERSION = 2;

    /** The oldest format this build still reads: the one written before lines were sealed. */
    public static final int OLDEST_READABLE_VERSION = 1;

    /** The version that first sealed every line with its checksum. */
    public static final int FIRST_CHECKSUMMED_VERSION = 2;

    /** What {@link #declaredVersion(String)} reports for a first line that is not a header. */
    public static final int NO_HEADER = -1;

    /** What {@link #declaredVersion(String)} reports for a header whose version is no number. */
    public static final int UNREADABLE_VERSION = -2;

    /** Opens the record that marks a document as being ingested. */
    public static final char BEGAN = '+';

    /** Opens the record that releases one. */
    public static final char ENDED = '-';

    /** Separates a line's fields, and a sealed line from its checksum. */
    public static final char SEPARATOR = '|';

    /** A seal: a separator and eight lowercase hexadecimal digits, ending the line. */
    private static final Pattern SEAL =
        Pattern.compile(".*\\|[0-9a-f]{" + EndLine.CHECKSUM_DIGITS + "}", Pattern.DOTALL);

    /** Characters a seal adds to the line it closes. */
    private static final int SEAL_LENGTH = 1 + EndLine.CHECKSUM_DIGITS;

    private InFlightJournalFormat() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns the header this build writes, sealed.
     *
     * @return The first line of a journal, without a terminator
     */
    public static String header() {
        return seal(HEADER_MARKER + SEPARATOR + FORMAT_VERSION);
    }

    /**
     * Reads the version out of a candidate header line.
     *
     * <p>Only the version is read here: from version 2 a header also carries a seal after it, and
     * whether a header is well formed for the version it names is {@link #headerIntact(String,
     * int)}'s to say.</p>
     *
     * @param line The first line
     * @return The declared version, {@link #NO_HEADER}, or {@link #UNREADABLE_VERSION}
     * @throws NullPointerException if the line is null
     */
    public static int declaredVersion(String line) {
        String prefix = HEADER_MARKER + SEPARATOR;
        if (!line.startsWith(prefix)) {
            return NO_HEADER;
        }
        String rest = line.substring(prefix.length());
        int end = rest.indexOf(SEPARATOR);
        try {
            return Integer.parseInt(end < 0 ? rest : rest.substring(0, end));
        } catch (NumberFormatException e) {
            return UNREADABLE_VERSION;
        }
    }

    /**
     * Reports whether a header is one the journal takes for the version it declares.
     *
     * <p>Version 1 wrote the bare version and nothing after it, and was read by parsing all of
     * what follows the marker as a number, so a version 1 header with anything after the number
     * is not one. From version 2 the header must be sealed, and the seal must match.</p>
     *
     * @param line    The first line
     * @param version The version it declares, a readable one
     * @return true if the header is intact
     * @throws NullPointerException if the line is null
     */
    public static boolean headerIntact(String line, int version) {
        if (version < FIRST_CHECKSUMMED_VERSION) {
            return line.indexOf(SEPARATOR, HEADER_MARKER.length() + 1) < 0;
        }
        return (HEADER_MARKER + SEPARATOR + version).equals(unseal(line));
    }

    /**
     * Closes a line with the checksum of its own bytes.
     *
     * @param content The line's content
     * @return The content, a separator, and its CRC32C in eight lowercase hexadecimal digits
     * @throws NullPointerException if the content is null
     */
    public static String seal(String content) {
        Objects.requireNonNull(content, "content must not be null");
        return content + SEPARATOR + EndLine.hex(checksum(content));
    }

    /**
     * Opens a sealed line, if its seal is present and matches.
     *
     * @param line The line as read
     * @return The content the seal covers, or null when the line carries no seal or its content
     *         does not match it
     * @throws NullPointerException if the line is null
     */
    public static String unseal(String line) {
        if (!SEAL.matcher(line).matches()) {
            return null;
        }
        int split = line.length() - SEAL_LENGTH;
        String content = line.substring(0, split);
        long recorded = Long.parseLong(line.substring(split + 1), 16);
        return recorded == checksum(content) ? content : null;
    }

    /**
     * Computes the CRC32C of a line's UTF-8 bytes.
     *
     * @param content The line's content
     * @return The checksum
     */
    private static long checksum(String content) {
        CRC32C crc = new CRC32C();
        crc.update(content.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
