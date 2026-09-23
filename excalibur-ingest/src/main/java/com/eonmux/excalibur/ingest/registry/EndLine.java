package com.eonmux.excalibur.ingest.registry;

import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The closing line of a count-marked file: how many records precede it, and, from the version
 * each format first carried one, the checksum of every byte before it.
 *
 * <h2>Layout</h2>
 * <p>{@code <marker>|<count>} in the versions that carry no checksum, and
 * {@code <marker>|<count>|<crc>} from the version that does, where {@code <crc>} is the CRC32C of
 * every byte of the file before this line - the header line included, as it was written - in
 * eight lowercase hexadecimal digits. The count and the checksum answer different failures. The
 * count is what tells a file whose tail was lost from a shorter one, which no checksum can do on
 * its own once the line that carried it is gone; the checksum is what tells a record altered in
 * place from the record that was written, which a count cannot see at all.</p>
 *
 * <p>Which layout a file uses is decided by the version its header names, never by the line
 * itself: a file at a checksummed version whose last line carries no checksum lost it, and is
 * refused as a file that does not end with its closing line.</p>
 *
 * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which judge
 * these files offline and must accept exactly what the node's readers accept.</p>
 *
 * @param count    The number of records the file declares
 * @param checksum The CRC32C the line names, or {@link #NO_CHECKSUM} in a layout without one
 */
public record EndLine(int count, long checksum) {

    /** What {@link #checksum()} reports for a closing line whose layout carries none. */
    public static final long NO_CHECKSUM = -1L;

    /** Separates the marker, the count and the checksum. */
    private static final char SEPARATOR = '|';

    /** Hexadecimal digits a checksum is rendered in. */
    public static final int CHECKSUM_DIGITS = 8;

    /** A rendered checksum: exactly eight lowercase hexadecimal digits. */
    private static final Pattern CHECKSUM = Pattern.compile("[0-9a-f]{" + CHECKSUM_DIGITS + "}");

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Reports whether this line names a checksum.
     *
     * @return true for the checksummed layout
     */
    public boolean checksummed() {
        return checksum != NO_CHECKSUM;
    }

    /**
     * Renders a checksum as it is written in a closing line.
     *
     * @param checksum An unsigned 32-bit CRC32C value
     * @return Eight lowercase hexadecimal digits
     */
    public static String hex(long checksum) {
        return HEX.toHexDigits((int) checksum);
    }

    /**
     * Renders the checksummed closing line.
     *
     * @param marker   The format's end marker
     * @param count    How many records precede the line
     * @param checksum The CRC32C of every byte before the line
     * @return The line, without a terminator
     * @throws NullPointerException if the marker is null
     */
    public static String render(String marker, int count, long checksum) {
        Objects.requireNonNull(marker, "marker must not be null");
        return marker + SEPARATOR + count + SEPARATOR + hex(checksum);
    }

    /**
     * Reads a candidate closing line in the layout a file's version calls for.
     *
     * <p>The count is read as the readers have always read it, with {@link
     * Integer#parseInt(String)}, and a negative one is not a count. The checksum must be exactly
     * eight lowercase hexadecimal digits with nothing after them: a padded, shortened or
     * upper-cased checksum is not one the writer produced.</p>
     *
     * @param line        The candidate line, or null when the file had no lines
     * @param marker      The format's end marker
     * @param checksummed Whether the file's version carries a checksum on this line
     * @return The parsed line, or null if the line is not a closing line of that layout
     * @throws NullPointerException if the marker is null
     */
    public static EndLine parse(String line, String marker, boolean checksummed) {
        Objects.requireNonNull(marker, "marker must not be null");
        String prefix = marker + SEPARATOR;
        if (line == null || !line.startsWith(prefix)) {
            return null;
        }
        String rest = line.substring(prefix.length());
        String countText = rest;
        long checksum = NO_CHECKSUM;
        if (checksummed) {
            int split = rest.indexOf(SEPARATOR);
            if (split < 0) {
                return null;
            }
            String checksumText = rest.substring(split + 1);
            if (!CHECKSUM.matcher(checksumText).matches()) {
                return null;
            }
            countText = rest.substring(0, split);
            checksum = Long.parseLong(checksumText, 16);
        }
        try {
            int count = Integer.parseInt(countText);
            return count < 0 ? null : new EndLine(count, checksum);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Phrases a checksum mismatch the same way for every reader and checker.
     *
     * @param computed The CRC32C of the bytes before the closing line
     * @return A clause for a refusal's message
     */
    public String mismatch(long computed) {
        return "its checksum does not match its contents: the last line records "
            + hex(checksum) + " and the bytes before it hash to " + hex(computed)
            + ", so the file was altered after it was written";
    }
}
