package com.eonmux.excalibur.fsck.check;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Checks the line-oriented registries that end with a record count.
 *
 * <p>{@code document-registry}, {@code source-catalog} and {@code source-status} are UTF-8 text,
 * and none carries a checksum of any kind. What they do carry is a versioned first line and a
 * trailing {@code \#end|count} line, which is the only thing that can distinguish a whole file
 * from one whose tail a failed write took with it. Checking that count is therefore the strongest
 * statement this tool can make about them, and it is a weaker statement than "intact" - a line
 * altered in the middle would pass it. The verdict says so rather than implying more.</p>
 *
 * <h2>The reader's verdict, not a looser one</h2>
 * <p>Each file is judged exactly as its reader in the node judges it: the header must name a
 * version that reader accepts, every line between the header and the count is a record - a blank
 * one included, as the reader counts it - and the count must be the file's very last line. A
 * check that trimmed lines, skipped blank ones or ignored the version would pass files the node
 * refuses, which is the one mistake a checker cannot make. Where the readers differ - a file with
 * no header, an empty file - {@link CountMarkedFormat} says what each does.</p>
 *
 * <h2>What a refusal is reported as</h2>
 * <p>A file whose records are missing, extra or not text is reported corrupt: its bytes are not
 * what its writer left. One whose header names a version the reader does not take is different,
 * because nothing shows it is damaged - a newer build may have written it - so it is reported
 * unreadable and put to an operator rather than moved aside. The source status history is the
 * exception: the node discards any history it will not read, so every refusal of one is reported
 * corrupt, which {@link FileKind#discardedOnOpen()} keeps from counting as damage to the
 * store.</p>
 */
public final class CountMarkedTextCheck {

    /** Separates a marker from the version or count after it. */
    private static final String SEPARATOR = "|";

    /** What {@link #declaredVersion} reports for a first line that is not a header. */
    private static final int NO_HEADER = -1;

    /** What {@link #declaredVersion} reports for a header whose version is not a number. */
    private static final int UNREADABLE_VERSION = -2;

    private CountMarkedTextCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads a registry and judges it as its reader would.
     *
     * @param file     The registry file
     * @param relative Its path relative to the data directory
     * @param format   Which registry it is
     * @return The verdict
     * @throws NullPointerException if any argument is null
     */
    public static FileCheck check(Path file, String relative, CountMarkedFormat format) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(relative, "relative");
        Objects.requireNonNull(format, "format");
        Verdicts verdicts = new Verdicts(file, relative, format);
        long size;
        List<String> lines;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return unreadable(file, relative, format, e);
        }
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return verdicts.refused(null, size,
                "the file is not UTF-8 text, so its reader cannot read it: " + e);
        } catch (IOException e) {
            return unreadable(file, relative, format, e);
        }
        if (lines.isEmpty()) {
            return empty(verdicts, size);
        }
        int version = declaredVersion(lines.get(0), format.headerMarker());
        if (version == NO_HEADER) {
            return headerless(verdicts, lines, size);
        }
        if (version == UNREADABLE_VERSION) {
            return verdicts.unsupported(null, size, "its first line, '" + lines.get(0)
                + "', names no format version its reader can read");
        }
        String versionText = "v" + version;
        if (version < format.oldestVersion() || version > format.newestVersion()) {
            return verdicts.unsupported(versionText, size, "it is format version " + version
                + ", and its reader takes " + readableRange(format) + ". A newer build wrote "
                + "it, or its first line was altered");
        }
        return weigh(verdicts, versionText, lines.subList(1, lines.size()), size);
    }

    /**
     * Judges a file with no lines at all.
     *
     * @param verdicts How to phrase the verdict
     * @param size     The file's size
     * @return The verdict
     */
    private static FileCheck empty(Verdicts verdicts, long size) {
        if (verdicts.format().emptyReadable()) {
            return verdicts.unchecked(null, size, "the file is empty, and its reader takes "
                + "that as a registry holding nothing; it declares nothing, so there is "
                + "nothing to verify");
        }
        return verdicts.refused(null, size, "the file is empty, so the write that published "
            + "it never reached the disk");
    }

    /**
     * Judges a file whose first line is not the format's header, by its reader's rule for one.
     *
     * @param verdicts How to phrase the verdict
     * @param lines    Every line of the file
     * @param size     The file's size
     * @return The verdict
     */
    private static FileCheck headerless(Verdicts verdicts, List<String> lines, long size) {
        CountMarkedFormat format = verdicts.format();
        return switch (format.headerless()) {
            case READ_WITHOUT_COUNT -> verdicts.unchecked(null, size, "the file has no header, "
                + "so it was written before the format had one, and before the record count "
                + "too: its reader takes every line as a record, and there is nothing to check "
                + "them against. The next save rewrites it in version "
                + format.newestVersion());
            case READ_WITH_COUNT -> {
                if (declaredCount(lines.get(lines.size() - 1), format.endMarker()) < 0) {
                    yield verdicts.refused(null, size, "the file has neither a header nor a "
                        + "trailing record count, which is what a file cut short looks like "
                        + "as well as one older than the count, so its reader will not take it");
                }
                yield weigh(verdicts, null, lines, size);
            }
            case REFUSED -> verdicts.refused(null, size, "its first line, '" + lines.get(0)
                + "', is not the format's header, which every file of it has had");
        };
    }

    /**
     * Compares the declared record count with the records actually present.
     *
     * @param verdicts    How to phrase the verdict
     * @param versionText The declared version, or null for a file with no header
     * @param body        Every line after the header: the records, then the count
     * @param size        The file's size
     * @return The verdict
     */
    private static FileCheck weigh(Verdicts verdicts, String versionText, List<String> body,
                                   long size) {
        if (body.isEmpty()) {
            return verdicts.refused(versionText, size, "the file holds a header and nothing "
                + "after it, so the write that published it never finished");
        }
        String last = body.get(body.size() - 1);
        int declared = declaredCount(last, verdicts.format().endMarker());
        if (declared < 0) {
            String what = last.startsWith(verdicts.format().endMarker() + SEPARATOR)
                ? "its last line reads '" + last + "', which names no number of records"
                : "it does not end with a record count, so its tail was lost";
            return verdicts.refused(versionText, size, what);
        }
        int present = body.size() - 1;
        if (present != declared) {
            return verdicts.refused(versionText, size, "the file declares " + declared
                + " records and holds " + present + ", so lines were lost or added after it "
                + "was written");
        }
        return new FileCheck(verdicts.file(), verdicts.relative(),
            verdicts.format().displayName(), versionText, CheckStatus.LEGACY_UNPROTECTED,
            "all " + declared + " records the file declares are present, so nothing was "
                + "truncated; the format carries no checksum, so the contents of those "
                + "records are not verified", size);
    }

    /**
     * Reads the version out of a candidate header line, exactly as the readers do.
     *
     * @param line   The first line
     * @param marker The format's header marker
     * @return The version, {@link #NO_HEADER}, or {@link #UNREADABLE_VERSION}
     */
    private static int declaredVersion(String line, String marker) {
        String prefix = marker + SEPARATOR;
        if (!line.startsWith(prefix)) {
            return NO_HEADER;
        }
        try {
            return Integer.parseInt(line.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return UNREADABLE_VERSION;
        }
    }

    /**
     * Reads the count out of a candidate last line, exactly as the readers do.
     *
     * @param line   The last line
     * @param marker The format's end marker
     * @return The count, or -1 if the line is not a record count
     */
    private static int declaredCount(String line, String marker) {
        String prefix = marker + SEPARATOR;
        if (!line.startsWith(prefix)) {
            return -1;
        }
        try {
            int count = Integer.parseInt(line.substring(prefix.length()));
            return count < 0 ? -1 : count;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Phrases the versions a reader takes.
     *
     * @param format Which registry
     * @return "version N" or "versions A to B"
     */
    private static String readableRange(CountMarkedFormat format) {
        return format.oldestVersion() == format.newestVersion()
            ? "version " + format.newestVersion()
            : "versions " + format.oldestVersion() + " to " + format.newestVersion();
    }

    /**
     * Builds the verdict for a file the operating system would not hand over.
     *
     * @param file     The registry file
     * @param relative Its path relative to the data directory
     * @param format   Which registry it is
     * @param e        What stopped the read
     * @return An unreadable verdict
     */
    private static FileCheck unreadable(Path file, String relative, CountMarkedFormat format,
                                        IOException e) {
        return new FileCheck(file, relative, format.displayName(), null,
            CheckStatus.UNREADABLE, "the file could not be read: " + e, -1);
    }

    /**
     * Phrases the verdicts on one file.
     *
     * @param file     The registry file
     * @param relative Its path relative to the data directory
     * @param format   Which registry it is
     */
    private record Verdicts(Path file, String relative, CountMarkedFormat format) {

        /**
         * A file its reader takes, with nothing in it to check.
         */
        FileCheck unchecked(String version, long size, String detail) {
            return new FileCheck(file, relative, format.displayName(), version,
                CheckStatus.UNCHECKED, detail, size);
        }

        /**
         * A file its reader will not take because its contents are not what was written.
         */
        FileCheck refused(String version, long size, String detail) {
            return new FileCheck(file, relative, format.displayName(), version,
                CheckStatus.CORRUPT, detail + "; " + format.refusal(), size);
        }

        /**
         * A file its reader will not take because it names a format the reader does not know.
         *
         * <p>Corrupt only where the node discards what it will not read, since there the
         * file costs nothing but itself and moving it aside is what the node would do anyway.
         * Elsewhere nothing shows the bytes are damaged, and moving aside a file a newer build
         * wrote would lose what that build recorded.</p>
         */
        FileCheck unsupported(String version, long size, String detail) {
            CheckStatus status = format.kind().discardedOnOpen()
                ? CheckStatus.CORRUPT : CheckStatus.UNREADABLE;
            return new FileCheck(file, relative, format.displayName(), version, status,
                detail + "; " + format.refusal(), size);
        }
    }
}
