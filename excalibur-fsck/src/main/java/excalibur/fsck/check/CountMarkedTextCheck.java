package excalibur.fsck.check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks the line-oriented registries that end with a record count.
 *
 * <p>{@code document-registry} and {@code source-catalog} are UTF-8 text, and neither
 * carries a checksum of any kind. What they do carry is a trailing {@code \#end|count}
 * line, which is the only thing that can distinguish a whole file from one whose tail a
 * failed write took with it. Checking that count is therefore the strongest statement this
 * tool can make about them, and it is a weaker statement than "intact" - a line altered in
 * the middle would pass it. The verdict says so rather than implying more.</p>
 */
public final class CountMarkedTextCheck {

    /** The line that closes a whole file, followed by the record count. */
    private static final String END_MARKER = "\\#end|";

    private CountMarkedTextCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads a registry and weighs its records against its declared count.
     *
     * @param file         The registry file
     * @param relative     Its path relative to the data directory
     * @param format       Name of the format, for the report
     * @param headerMarker The exact first-line marker, without the version suffix
     * @return The verdict
     */
    public static FileCheck check(Path file, String relative, String format,
                                  String headerMarker) {
        List<String> lines;
        long size;
        try {
            size = Files.size(file);
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new FileCheck(file, relative, format, null, CheckStatus.UNREADABLE,
                "the file could not be read as UTF-8 text: " + e, -1);
        }
        if (size == 0) {
            return new FileCheck(file, relative, format, null, CheckStatus.UNCHECKED,
                "the file is empty, so it declares nothing and has nothing to verify", 0);
        }
        List<String> body = lines.stream().map(String::trim).filter(l -> !l.isEmpty()).toList();
        if (body.isEmpty()) {
            return new FileCheck(file, relative, format, null, CheckStatus.CORRUPT,
                "the file holds " + size + " bytes but no non-blank line", size);
        }
        String version = versionOf(body.get(0), headerMarker);
        return weigh(file, relative, format, version, body, size, headerMarker);
    }

    /**
     * Compares the declared record count with the records actually present.
     *
     * @param file         The registry file
     * @param relative     Its path relative to the data directory
     * @param format       Name of the format
     * @param version      The declared version, or null when there is no header
     * @param body         Non-blank, trimmed lines
     * @param size         The file's size
     * @param headerMarker The first-line marker
     * @return The verdict
     */
    private static FileCheck weigh(Path file, String relative, String format, String version,
                                   List<String> body, long size, String headerMarker) {
        String last = body.get(body.size() - 1);
        if (!last.startsWith(END_MARKER)) {
            return new FileCheck(file, relative, format, version, CheckStatus.UNCHECKED,
                "the file has no trailing record count, which is the only thing this format "
                    + "carries that could show it is all of itself; it predates the marker, or "
                    + "its tail was lost", size);
        }
        int declared;
        try {
            declared = Integer.parseInt(last.substring(END_MARKER.length()).trim());
        } catch (NumberFormatException e) {
            return new FileCheck(file, relative, format, version, CheckStatus.CORRUPT,
                "the record count line reads '" + last + "', which names no number", size);
        }
        int headerLines = body.get(0).startsWith(headerMarker) ? 1 : 0;
        int present = body.size() - headerLines - 1;
        if (present != declared) {
            return new FileCheck(file, relative, format, version, CheckStatus.CORRUPT,
                "the file declares " + declared + " records and holds " + present
                    + ", so lines were lost or added after it was written", size);
        }
        return new FileCheck(file, relative, format, version, CheckStatus.LEGACY_UNPROTECTED,
            "all " + declared + " records the file declares are present, so nothing was "
                + "truncated; the format carries no checksum, so the contents of those "
                + "records are not verified", size);
    }

    /**
     * Reads the version out of a header line.
     *
     * @param first        The first non-blank line
     * @param headerMarker The marker the line must begin with
     * @return The version as text, or null when the line is not a header
     */
    private static String versionOf(String first, String headerMarker) {
        if (!first.startsWith(headerMarker + "|")) {
            return null;
        }
        return "v" + first.substring(headerMarker.length() + 1).trim();
    }
}
