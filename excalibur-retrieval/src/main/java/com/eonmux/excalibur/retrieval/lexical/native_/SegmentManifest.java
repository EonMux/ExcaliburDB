package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * What the store is made of, and when each part became visible.
 *
 * <h2>Why a manifest rather than a directory listing</h2>
 * <p>A segment appears on disk before it may be read: it is written, fsynced, and only then named
 * in the manifest. A reader that discovered segments by listing the directory would see a
 * half-written one and treat its absence of postings as an absence of documents. Publishing is the
 * manifest replacement, which is a single atomic rename, so a segment is either wholly visible or
 * not there at all.</p>
 *
 * <p>The file is text, one segment a line, because it is the thing an operator reads when they
 * need to know what a store actually contains.</p>
 *
 * <h2>Why the file ends with a count</h2>
 * <p>This is the only file that says what the store contains, and {@code LexicalStore} deletes
 * everything it does not name. A manifest that lost its tail is still a header and some whole,
 * well-formed records - it reads as a valid manifest of a smaller store, and the next open
 * removes the segments it forgot. Nothing in the records themselves can ever say that more of
 * them were meant to follow, so the last line states how many precede it and a manifest whose
 * count disagrees is refused rather than believed. Refusing is not a lost store: every segment
 * is still on disk, and an operator who has been told which manifest is wrong can restore it.</p>
 *
 * <h2>Why the end line also carries a checksum</h2>
 * <p>A count proves that no line was lost; it says nothing about the lines that are there. A
 * flipped digit in an epoch or a document count still reads as a well-formed record, and a
 * damaged segment name still reads as a name - one that matches no directory, so the segment it
 * used to name is swept as an orphan exactly as if its line had been lost. So the end line of a
 * version 3 manifest is {@code #end<tab>count<tab>crc}, where {@code crc} is the CRC32C of every
 * byte of the file before the end line, as eight lowercase hexadecimal digits. A manifest whose
 * bytes do not digest to it is refused exactly as one whose count disagrees is: the store does
 * not open on it, and nothing is swept on its authority.</p>
 *
 * <h2>Reading a manifest an older build wrote</h2>
 * <p>Version 2 carried the count and no checksum. It is read exactly as it always was - its
 * count is checked, and on a count that agrees it is trusted to sweep - and it is republished as
 * version 3 by the first write of any kind. Its lines are unverified until then, which is what
 * {@code excalibur-fsck} reports it as.</p>
 *
 * <p>Version 1 carried neither, so nothing distinguishes a whole one from a truncated one and
 * no amount of checking can. Such a manifest is read - an existing data directory must not
 * become unopenable by upgrading the engine - but it is marked unverified, and an unverified
 * manifest is not evidence that anything beside it is an orphan, so no segment is deleted on its
 * authority. The first write of any kind republishes it at the current version, after which the
 * store sweeps normally again. An operator only sees the unverified state on the first open
 * after an upgrade; a manifest that is unverified on later opens is one this build never wrote,
 * which is itself worth knowing.</p>
 *
 * <p>A manifest at a version above {@link #VERSION} was written by a newer build, and is refused
 * with {@link UnsupportedVersionException} rather than read by the rules of an older format: the
 * end line may mean something else there, and a store that swept on a misread manifest would
 * delete segments the newer build published.</p>
 */
public final class SegmentManifest {

    /** One segment: where it lives, when it became visible, and how many documents it holds. */
    public record Entry(String name, long epoch, int docCount, int tombstoned) {
        public Entry {
            Objects.requireNonNull(name, "name must not be null");
            if (epoch < 0) {
                throw new IllegalArgumentException("epoch must not be negative: " + epoch);
            }
            if (docCount < 0) {
                throw new IllegalArgumentException("docCount must not be negative: " + docCount);
            }
            if (tombstoned < 0 || tombstoned > docCount) {
                // Unchecked, this makes liveCount() negative, and a negative live count reads
                // downstream as a segment worth skipping rather than as the arithmetic error it is.
                throw new IllegalArgumentException("tombstoned (" + tombstoned
                    + ") must be between 0 and docCount (" + docCount + ") for " + name);
            }
        }

        /** Documents still readable in this segment. */
        public int liveCount() {
            return docCount - tombstoned;
        }

        /** Fraction of this segment that is tombstoned, which is what its rewrite would reclaim. */
        public double tombstoneRatio() {
            return docCount == 0 ? 0.0 : tombstoned / (double) docCount;
        }
    }

    /**
     * A manifest written at a version this build does not read.
     *
     * <p>Separate from every other refusal because it is not evidence of damage: the bytes may be
     * exactly what a newer build wrote. The store refuses it all the same, and a checker reports
     * it as unreadable rather than corrupt.</p>
     */
    public static final class UnsupportedVersionException extends IOException {

        private static final long serialVersionUID = 1L;

        private final int version;

        UnsupportedVersionException(Path file, int version) {
            super(file + " is a lexical store manifest at format version " + version + ", which "
                + "this build does not read - it reads versions " + OLDEST_VERSION + " to "
                + VERSION + ". A newer build wrote it; open the store with that build, because "
                + "reading its lines by an older format's rules could sweep segments it "
                + "published.");
            this.version = version;
        }

        /** The version the manifest's header names. */
        public int version() {
            return version;
        }
    }

    /** The version this build writes: records, then a count and a checksum on the end line. */
    public static final int VERSION = 3;

    /** The version that ended the file with a record count, and carried no checksum. */
    public static final int COUNTED_VERSION = 2;

    /** The oldest version this build reads: records only, readable but never verifiable. */
    public static final int OLDEST_VERSION = 1;

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentManifest.class);

    private static final String FILE = "manifest";

    /** The first line, followed by the version number. */
    private static final String HEADER_PREFIX = "excalibur-lexical-store v";

    /** Opens the last line, which states how many records precede it. */
    private static final String END_MARKER = "#end";

    /** Separates a record's fields, and the end marker from its count and checksum. */
    private static final char SEPARATOR = '\t';

    /** Hexadecimal digits in the checksum field. */
    private static final int CHECKSUM_DIGITS = 8;

    private final List<Entry> entries;
    private boolean verified;
    private int version;

    private SegmentManifest(List<Entry> entries, boolean verified, int version) {
        this.entries = entries;
        this.verified = verified;
        this.version = version;
    }

    /** An empty store. */
    public static SegmentManifest empty() {
        return new SegmentManifest(new ArrayList<>(), true, VERSION);
    }

    /**
     * Reads the manifest, or an empty one when the store is new.
     *
     * @throws UnsupportedVersionException if a newer build wrote the file
     * @throws IOException if the file is there but cannot be shown to be all of itself, or its
     *                     checksum did not match, which is a store to be repaired rather than
     *                     one to be opened and swept
     */
    public static SegmentManifest read(Path directory) throws IOException {
        Path file = directory.resolve(FILE);
        if (!Files.exists(file)) {
            return empty();
        }
        byte[] bytes = Files.readAllBytes(file);
        List<String> lines = decode(bytes).lines().toList();
        if (lines.isEmpty()) {
            throw new IOException(file + " is empty, so the rename that published it landed "
                + "before its contents reached the device. Every segment it named is still on "
                + "disk; restore the manifest rather than opening the store, because an empty "
                + "one names nothing and nothing is what would be kept.");
        }
        int version = versionOf(file, lines.get(0));
        List<String> records = records(lines);
        boolean verified = version >= COUNTED_VERSION;
        if (verified) {
            records = withoutEndLine(file, bytes, records, version);
        } else {
            LOGGER.warn("{} predates the record count, so it cannot be shown to be whole; "
                + "segments it does not name are left alone until it has been rewritten", file);
        }
        return new SegmentManifest(parse(file, records), verified, version);
    }

    /**
     * Whether this manifest could be proved to be all of itself.
     *
     * <p>False only for one written before the count marker existed. A caller that deletes what
     * the manifest does not name has to consult this first: on an unverified manifest, "not
     * named" and "named by a line that is no longer there" are the same thing.</p>
     */
    public boolean verified() {
        return verified;
    }

    /**
     * The format version of the file this manifest was read from, or of the one it last wrote.
     *
     * <p>{@link #VERSION} for a manifest this build wrote or created empty; lower for one an
     * older build wrote and nothing has rewritten yet. Only the current version's lines are
     * covered by a checksum.</p>
     */
    public int version() {
        return version;
    }

    /**
     * Decodes the file strictly, so bytes that are not UTF-8 are a refusal rather than a
     * replacement character in a segment name.
     */
    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    /** Which format the file is in, or a refusal if it is not one this build reads. */
    private static int versionOf(Path file, String header) throws IOException {
        String trimmed = header.trim();
        if (trimmed.startsWith(HEADER_PREFIX)) {
            int version;
            try {
                version = Integer.parseInt(trimmed.substring(HEADER_PREFIX.length()));
            } catch (NumberFormatException e) {
                version = -1;
            }
            if (version > VERSION) {
                throw new UnsupportedVersionException(file, version);
            }
            if (version >= OLDEST_VERSION) {
                return version;
            }
        }
        throw new IOException(file + " does not begin with " + HEADER_PREFIX + VERSION + "; it "
            + "is not a lexical store manifest, or its first line was overwritten");
    }

    /** Every non-blank line after the header. */
    private static List<String> records(List<String> lines) {
        List<String> records = new ArrayList<>(lines.size());
        for (String line : lines.subList(1, lines.size())) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                records.add(trimmed);
            }
        }
        return records;
    }

    /**
     * Strips the end line, having checked that what precedes it is all there and, from version 3
     * on, that it is exactly the bytes that were written.
     */
    private static List<String> withoutEndLine(Path file, byte[] bytes, List<String> records,
                                               int version) throws IOException {
        String marker = records.isEmpty() ? "" : records.get(records.size() - 1);
        String prefix = END_MARKER + SEPARATOR;
        if (!marker.startsWith(prefix)) {
            throw new IOException(file + " does not end with a record count, so it was cut short "
                + "before the write that published it finished. The segments it named are still "
                + "on disk and none of them may be removed on the strength of this file.");
        }
        boolean checksummed = version >= VERSION;
        String[] fields = marker.substring(prefix.length()).split(String.valueOf(SEPARATOR), -1);
        if (fields.length != (checksummed ? 2 : 1)) {
            throw new IOException(file + " is a version " + version + " manifest and its end line "
                + "is not " + (checksummed ? "a record count and a checksum" : "a record count")
                + ": " + marker + ". The segments it named are still on disk and none of them "
                + "may be removed on the strength of this file.");
        }
        int declared = count(fields[0]);
        List<String> named = records.subList(0, records.size() - 1);
        if (declared != named.size()) {
            throw new IOException(file + " declares " + declared + " segment(s) and carries "
                + named.size() + "; it lost lines after it was written, and the segments it no "
                + "longer names are still on disk");
        }
        if (checksummed) {
            verifyChecksum(file, bytes, fields[1]);
        }
        return named;
    }

    /**
     * Compares the end line's checksum with the bytes ahead of it.
     *
     * <p>The end line is found in the raw bytes rather than rebuilt from the decoded lines, so
     * that what is digested is what is on disk: a blank line, a stray carriage return or a
     * trailing space would all be invisible to the parsed records, and are all bytes the writer
     * did not write.</p>
     */
    private static void verifyChecksum(Path file, byte[] bytes, String recorded)
            throws IOException {
        long stored = checksumField(file, recorded);
        int covered = endLineStart(bytes);
        long actual = IndexChecksum.of(bytes, 0, covered);
        if (actual != stored) {
            throw new IOException(file + " failed its integrity check: the checksum did not "
                + "match. Its end line records " + recorded + " and the " + covered + " byte(s) "
                + "before it digest to " + hex(actual) + ", so its lines are not the ones that "
                + "were written. The segments it named are still on disk and none of them may be "
                + "removed on the strength of this file; restore the manifest from a backup.");
        }
    }

    /** Parses the checksum field, which is exactly eight lowercase hexadecimal digits. */
    private static long checksumField(Path file, String recorded) throws IOException {
        if (recorded.length() != CHECKSUM_DIGITS || !recorded.chars().allMatch(
                c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IOException(file + " records a checksum of " + recorded + ", which is not "
                + CHECKSUM_DIGITS + " lowercase hexadecimal digits; the end line was damaged, "
                + "and nothing in the file can be verified");
        }
        return Long.parseLong(recorded, 16);
    }

    /**
     * Returns where the end line begins: just past the last newline that precedes the end
     * marker, or the length of the file when there is none, which no checksum can then match.
     */
    private static int endLineStart(byte[] bytes) {
        byte[] opening = ("\n" + END_MARKER + SEPARATOR).getBytes(StandardCharsets.UTF_8);
        for (int at = bytes.length - opening.length; at >= 0; at--) {
            if (Arrays.equals(bytes, at, at + opening.length, opening, 0, opening.length)) {
                return at + 1;
            }
        }
        return bytes.length;
    }

    /** A checksum as the end line writes it. */
    private static String hex(long checksum) {
        return String.format("%0" + CHECKSUM_DIGITS + "x", checksum);
    }

    private static int count(String declared) throws IOException {
        try {
            return Integer.parseInt(declared);
        } catch (NumberFormatException e) {
            throw new IOException("Manifest record count is not a number: " + declared, e);
        }
    }

    private static List<Entry> parse(Path file, List<String> records) throws IOException {
        List<Entry> entries = new ArrayList<>(records.size());
        for (String record : records) {
            String[] parts = record.split(String.valueOf(SEPARATOR));
            if (parts.length < 4) {
                throw new IOException("Malformed line in " + file + ": " + record);
            }
            try {
                entries.add(new Entry(parts[0], Long.parseLong(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
            } catch (IllegalArgumentException e) {
                throw new IOException("Malformed line in " + file + ": " + record, e);
            }
        }
        return entries;
    }

    /**
     * Replaces the manifest durably.
     *
     * <p>Written beside, forced, renamed over, and the directory forced after it. The rename
     * alone stops a reader seeing a half-written file; it does nothing about a crash that keeps
     * the new name and loses the contents behind it, which is the same truncation arrived at
     * from the other end - and on this file a truncation is a deletion of everything it forgot.
     * See {@link Durability}.</p>
     */
    public void write(Path directory) throws IOException {
        write(directory, Durability.FILE_SYSTEM);
    }

    /**
     * Replaces the manifest through a given set of steps.
     *
     * @param durability How the contents are made durable and published; the ordering is the
     *                   whole of the guarantee, so it is a seam a test can record
     */
    void write(Path directory, Durability durability) throws IOException {
        StringBuilder text = new StringBuilder(HEADER_PREFIX).append(VERSION).append('\n');
        int written = 0;
        for (Entry entry : entries) {
            text.append(entry.name()).append(SEPARATOR).append(entry.epoch()).append(SEPARATOR)
                .append(entry.docCount()).append(SEPARATOR).append(entry.tombstoned())
                .append('\n');
            written++;
        }
        // Counted in the same pass that writes them, so the number states what actually reached
        // the file rather than what the list held when the pass began.
        // The checksum covers every byte ahead of the end line, header included, so it is taken
        // over exactly the bytes about to be written rather than recomputed from the records.
        byte[] covered = text.toString().getBytes(StandardCharsets.UTF_8);
        byte[] endLine = (END_MARKER + SEPARATOR + written + SEPARATOR
            + hex(IndexChecksum.of(covered, 0, covered.length)) + '\n')
            .getBytes(StandardCharsets.UTF_8);
        byte[] contents = Arrays.copyOf(covered, covered.length + endLine.length);
        System.arraycopy(endLine, 0, contents, covered.length, endLine.length);
        durability.replace(directory.resolve(FILE), contents);
        // The file on disk is now one this build wrote, whatever the one it was read from was.
        verified = true;
        version = VERSION;
    }

    /** Segments in publication order. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Segments visible at an epoch, in publication order. */
    public List<Entry> visibleAt(long epoch) {
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.epoch() <= epoch) {
                visible.add(entry);
            }
        }
        return visible;
    }

    /** The highest epoch any segment carries, or -1 when the store is empty. */
    public long latestEpoch() {
        long latest = -1;
        for (Entry entry : entries) {
            latest = Math.max(latest, entry.epoch());
        }
        return latest;
    }

    /** Adds a segment. */
    public void add(Entry entry) {
        entries.add(entry);
    }

    /** Replaces a segment's record, keeping its position. */
    public void replace(String name, Entry replacement) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equals(name)) {
                entries.set(i, replacement);
                return;
            }
        }
        throw new IllegalArgumentException("No such segment: " + name);
    }

    /**
     * Replaces a group of segments with the single segment they were rewritten into.
     *
     * <p>Placed where the first of them sat, so publication order survives compaction. The order
     * is what a reader following the manifest sees, and shuffling it on every merge would make two
     * stores holding identical documents describe themselves differently.</p>
     */
    public void replaceGroup(List<String> names, Entry replacement) {
        Objects.requireNonNull(names, "names must not be null");
        Objects.requireNonNull(replacement, "replacement must not be null");
        int at = -1;
        for (int i = 0; i < entries.size() && at < 0; i++) {
            if (names.contains(entries.get(i).name())) {
                at = i;
            }
        }
        if (at < 0) {
            throw new IllegalArgumentException("No such segments: " + names);
        }
        entries.removeIf(entry -> names.contains(entry.name()));
        entries.add(Math.min(at, entries.size()), replacement);
    }

    /** Every segment's name, in publication order. */
    public List<String> names() {
        List<String> names = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            names.add(entry.name());
        }
        return names;
    }

    /** Removes segments by name. */
    public void removeAll(List<String> names) {
        entries.removeIf(entry -> names.contains(entry.name()));
    }

    /** Documents across every segment, tombstoned ones included. */
    public int totalDocuments() {
        int total = 0;
        for (Entry entry : entries) {
            total += entry.docCount();
        }
        return total;
    }

    /** Documents erased but not yet compacted out. */
    public int tombstonedDocuments() {
        int total = 0;
        for (Entry entry : entries) {
            total += entry.tombstoned();
        }
        return total;
    }
}
