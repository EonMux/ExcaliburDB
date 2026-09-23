package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.ingest.registry.ChecksummedLineReader;
import com.eonmux.excalibur.ingest.registry.EndLine;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Checks the line-oriented registries that end with a record count.
 *
 * <p>{@code document-registry}, {@code source-catalog} and {@code source-status} are UTF-8 text
 * with a versioned first line and a closing {@code \#end|<count>|<crc>} line: the count, which
 * is what distinguishes a whole file from one whose tail a failed write took with it, and the
 * CRC32C of every byte before the closing line, which is what distinguishes a record altered in
 * place from the one that was written. A file whose count and checksum both hold is intact. The
 * versions written before the checksum close with the count alone; checking it is the strongest
 * statement this tool can make about such a file, and it is a weaker statement than "intact" - a
 * line altered in the middle would pass it - so the verdict says so rather than implying
 * more.</p>
 *
 * <h2>The reader's verdict, not a looser one</h2>
 * <p>Each file is judged exactly as its reader in the node judges it: the header must name a
 * version that reader accepts, every line between the header and the count is a record - a blank
 * one included, as the reader counts it - the count must be the file's very last line, and from
 * the version that carries one the checksum on that line must match the bytes before it, read by
 * the same {@link ChecksummedLineReader} and parsed by the same {@link EndLine} the readers
 * use. A
 * check that trimmed lines, skipped blank ones or ignored the version would pass files the node
 * refuses, which is the one mistake a checker cannot make. Where the readers differ - a file with
 * no header, an empty file - {@link CountMarkedFormat} says what each does.</p>
 *
 * <h2>What a refusal is reported as</h2>
 * <p>A file whose records are missing, extra, altered or not text is reported corrupt: its bytes
 * are not what its writer left. One whose header names a version the reader does not take is different,
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
        ChecksummedLineReader.Lines read;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return unreadable(file, relative, format, e);
        }
        try {
            read = ChecksummedLineReader.readAll(file);
        } catch (CharacterCodingException e) {
            return verdicts.refused(null, size,
                "the file is not UTF-8 text, so its reader cannot read it: " + e);
        } catch (IOException e) {
            return unreadable(file, relative, format, e);
        }
        List<String> lines = read.lines();
        long checksum = read.checksumBeforeLast();
        if (lines.isEmpty()) {
            return empty(verdicts, size);
        }
        int version = declaredVersion(lines.get(0), format.headerMarker());
        if (version == NO_HEADER) {
            return headerless(verdicts, lines, checksum, size);
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
        return weigh(verdicts, version, versionText, lines.subList(1, lines.size()), checksum,
            size);
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
     * @param checksum The CRC32C of every byte before the last line
     * @param size     The file's size
     * @return The verdict
     */
    private static FileCheck headerless(Verdicts verdicts, List<String> lines, long checksum,
                                        long size) {
        CountMarkedFormat format = verdicts.format();
        return switch (format.headerless()) {
            case READ_WITHOUT_COUNT -> verdicts.unchecked(null, size, "the file has no header, "
                + "so it was written before the format had one, and before the record count "
                + "too: its reader takes every line as a record, and there is nothing to check "
                + "them against; " + format.upgrade());
            case READ_WITH_COUNT -> {
                if (EndLine.parse(lines.get(lines.size() - 1), format.endMarker(),
                        false) == null) {
                    yield verdicts.refused(null, size, "the file has neither a header nor a "
                        + "trailing record count, which is what a file cut short looks like "
                        + "as well as one older than the count, so its reader will not take it");
                }
                yield weigh(verdicts, format.oldestVersion(), null, lines, checksum, size);
            }
            case REFUSED -> verdicts.refused(null, size, "its first line, '" + lines.get(0)
                + "', is not the format's header, which every file of it has had");
        };
    }

    /**
     * Compares the closing line with the records actually present and the bytes before it.
     *
     * @param verdicts    How to phrase the verdict
     * @param version     The version the records are read in
     * @param versionText The declared version, or null for a file with no header
     * @param body        Every line after the header: the records, then the closing line
     * @param checksum    The CRC32C of every byte before the last line
     * @param size        The file's size
     * @return The verdict
     */
    private static FileCheck weigh(Verdicts verdicts, int version, String versionText,
                                   List<String> body, long checksum, long size) {
        if (body.isEmpty()) {
            return verdicts.refused(versionText, size, "the file holds a header and nothing "
                + "after it, so the write that published it never finished");
        }
        CountMarkedFormat format = verdicts.format();
        boolean checksummed = format.checksummed(version);
        String last = body.get(body.size() - 1);
        EndLine end = EndLine.parse(last, format.endMarker(), checksummed);
        if (end == null) {
            String what = last.startsWith(format.endMarker() + SEPARATOR)
                ? "its last line reads '" + last + "', which names no number of records"
                    + (checksummed ? " and checksum" : "")
                : "it does not end with a record count, so its tail was lost";
            return verdicts.refused(versionText, size, what);
        }
        int present = body.size() - 1;
        if (present != end.count()) {
            return verdicts.refused(versionText, size, "the file declares " + end.count()
                + " records and holds " + present + ", so lines were lost or added after it "
                + "was written");
        }
        if (!checksummed) {
            return new FileCheck(verdicts.file(), verdicts.relative(), format.displayName(),
                versionText, CheckStatus.LEGACY_UNPROTECTED, "all " + end.count() + " records "
                    + "the file declares are present, so nothing was truncated; this version "
                    + "carries no checksum, so the contents of those records are not verified, "
                    + "and " + format.upgrade(), size);
        }
        if (end.checksum() != checksum) {
            return verdicts.refused(versionText, size, end.mismatch(checksum));
        }
        return new FileCheck(verdicts.file(), verdicts.relative(), format.displayName(),
            versionText, CheckStatus.INTACT, "all " + end.count() + " records the file declares "
                + "are present, and every byte before its last line matches the checksum that "
                + "line carries", size);
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
