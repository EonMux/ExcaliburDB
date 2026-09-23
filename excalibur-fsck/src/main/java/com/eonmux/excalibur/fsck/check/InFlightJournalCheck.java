package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.ingest.registry.ChecksummedLineReader;
import com.eonmux.excalibur.ingest.visibility.InFlightJournalFormat;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Checks {@code document-registry.in-flight}, the journal of documents whose ingest had begun and
 * not finished.
 *
 * <h2>What is verified</h2>
 * <p>The journal is append-only, so it carries no closing line; from version 2 every line, the
 * header included, instead ends with the CRC32C of its own bytes (see {@link
 * InFlightJournalFormat}). A journal whose every line matches its checksum is intact. Version 1
 * lines carry none, and a version 1 journal is reported as read but unproven.</p>
 *
 * <h2>The reader's verdict, not a looser one</h2>
 * <p>The journal is judged exactly as the node reads it when it starts, with the same line
 * reader, the same header rules and the same seal. The node refuses to start on a journal whose
 * header it will not take, and on one holding a complete line that fails its checksum, because
 * that line may be the mark hiding a half-written document; both are reported here as the node
 * would treat them. A last line the file ends inside of is an append the process did not finish,
 * which the node skips and rewrites away as it starts, because nothing it would have marked was
 * ever written; it is reported without counting against the file. A journal whose header names a
 * version the node does not read is put to an operator as unreadable rather than called damaged,
 * since a newer build may have written it.</p>
 */
public final class InFlightJournalCheck {

    /** The name the report gives this format. */
    private static final String FORMAT = "in-flight ingest journal";

    /** What the node does with a journal it will not take. */
    private static final String REFUSAL = "the node refuses to start on it";

    private InFlightJournalCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads a journal and judges it as the node would.
     *
     * @param file     The journal file
     * @param relative Its path relative to the data directory
     * @return The verdict
     * @throws NullPointerException if either argument is null
     */
    public static FileCheck check(Path file, String relative) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(relative, "relative");
        Verdicts verdicts = new Verdicts(file, relative);
        long size;
        ChecksummedLineReader.Lines read;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return verdicts.of(CheckStatus.UNREADABLE, null, -1, "the file could not be read: " + e);
        }
        try {
            read = ChecksummedLineReader.readAll(file);
        } catch (CharacterCodingException e) {
            return verdicts.of(CheckStatus.CORRUPT, null, size,
                "the file is not UTF-8 text, so the node cannot read it: " + e + "; " + REFUSAL);
        } catch (IOException e) {
            return verdicts.of(CheckStatus.UNREADABLE, null, -1, "the file could not be read: " + e);
        }
        List<String> lines = read.lines();
        if (lines.isEmpty()) {
            return verdicts.of(CheckStatus.UNCHECKED, null, size, "the file is empty, which the "
                + "node reads as nothing in flight; it holds nothing to verify");
        }
        String header = lines.get(0);
        if (lines.size() == 1 && !read.lastLineTerminated()) {
            return verdicts.of(CheckStatus.UNSEALED, null, size, "the file ends inside its first "
                + "line, which is the first append cut short: nothing it would have marked was "
                + "written, and the node discards it as it starts");
        }
        int version = InFlightJournalFormat.declaredVersion(header);
        if (version == InFlightJournalFormat.NO_HEADER) {
            return verdicts.of(CheckStatus.CORRUPT, null, size, "its first line, '" + header
                + "', is not the journal's header, which every journal has had; " + REFUSAL);
        }
        if (version == InFlightJournalFormat.UNREADABLE_VERSION) {
            return verdicts.of(CheckStatus.UNREADABLE, null, size, "its first line, '" + header
                + "', names no format version the node can read; " + REFUSAL);
        }
        String versionText = "v" + version;
        if (version < InFlightJournalFormat.OLDEST_READABLE_VERSION
                || version > InFlightJournalFormat.FORMAT_VERSION) {
            return verdicts.of(CheckStatus.UNREADABLE, versionText, size, "it is format version "
                + version + ", and the node reads versions "
                + InFlightJournalFormat.OLDEST_READABLE_VERSION + " to "
                + InFlightJournalFormat.FORMAT_VERSION + ". A newer build wrote it, or its first "
                + "line was altered; " + REFUSAL);
        }
        boolean sealed = version >= InFlightJournalFormat.FIRST_CHECKSUMMED_VERSION;
        if (!InFlightJournalFormat.headerIntact(header, version)) {
            return sealed
                ? verdicts.of(CheckStatus.CORRUPT, versionText, size, "its first line does not "
                    + "match its checksum, so it was altered after it was written; " + REFUSAL)
                : verdicts.of(CheckStatus.UNREADABLE, versionText, size, "its first line, '"
                    + header + "', names no format version the node can read; " + REFUSAL);
        }
        return records(verdicts, versionText, sealed, lines, read.lastLineTerminated(), size);
    }

    /**
     * Judges every line after the header.
     *
     * @param verdicts       How to phrase the verdict
     * @param versionText    The declared version
     * @param sealed         Whether the version seals every line
     * @param lines          Every line of the file
     * @param lastTerminated Whether the file ends with a line terminator
     * @param size           The file's size
     * @return The verdict
     */
    private static FileCheck records(Verdicts verdicts, String versionText, boolean sealed,
                                     List<String> lines, boolean lastTerminated, long size) {
        int records = lines.size() - 1;
        if (!sealed) {
            return verdicts.of(CheckStatus.LEGACY_UNPROTECTED, versionText, size, "the node reads "
                + "its " + records + " line(s) as they are, skipping any it cannot parse; this "
                + "version carries no checksum, so they are not verified, and the node rewrites "
                + "it in version " + InFlightJournalFormat.FORMAT_VERSION + ", with a checksum on "
                + "every line, as it starts");
        }
        boolean torn = false;
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            boolean last = index == lines.size() - 1 && !lastTerminated;
            String content = InFlightJournalFormat.unseal(line);
            if (content != null && isRecord(content)) {
                continue;
            }
            if (last) {
                torn = true;
                continue;
            }
            return verdicts.of(CheckStatus.CORRUPT, versionText, size, content == null
                ? "line " + (index + 1) + " does not match its checksum, so it was altered after "
                    + "it was written; " + REFUSAL
                : "line " + (index + 1) + " matches its checksum and is no record the node "
                    + "writes; " + REFUSAL);
        }
        int whole = torn ? records - 1 : records;
        return verdicts.of(CheckStatus.INTACT, versionText, size, "every one of its " + whole
            + " complete record(s) matches its checksum"
            + (torn ? "; its last line was an append cut short, which recorded nothing that was "
                + "written, and the node discards it as it starts" : ""));
    }

    /**
     * Reports whether a line's content is a record the node applies.
     *
     * @param content The line, its seal removed
     * @return true for a begin or an end naming a document
     */
    private static boolean isRecord(String content) {
        return content.length() >= 2
            && content.charAt(1) == InFlightJournalFormat.SEPARATOR
            && (content.charAt(0) == InFlightJournalFormat.BEGAN
                || content.charAt(0) == InFlightJournalFormat.ENDED);
    }

    /**
     * Phrases the verdicts on one journal.
     *
     * @param file     The journal file
     * @param relative Its path relative to the data directory
     */
    private record Verdicts(Path file, String relative) {

        FileCheck of(CheckStatus status, String version, long size, String detail) {
            return new FileCheck(file, relative, FORMAT, version, status, detail, size);
        }
    }
}
