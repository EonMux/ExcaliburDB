package com.eonmux.excalibur.retrieval.lexical.native_;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <h2>Reading a manifest written before the count existed</h2>
 * <p>Version 1 carried no count, so nothing distinguishes a whole one from a truncated one and
 * no amount of checking can. Such a manifest is read - an existing data directory must not
 * become unopenable by upgrading the engine - but it is marked unverified, and an unverified
 * manifest is not evidence that anything beside it is an orphan, so no segment is deleted on its
 * authority. The first write of any kind republishes it as version 2, after which the store
 * sweeps normally again. An operator only sees the unverified state on the first open after an
 * upgrade; a manifest that is unverified on later opens is one this build never wrote, which is
 * itself worth knowing.</p>
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

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentManifest.class);

    private static final String FILE = "manifest";

    /** Written by every build that ends the file with a count. */
    private static final String HEADER = "excalibur-lexical-store v2";

    /** Written before the count existed, and readable but never verifiable. */
    private static final String LEGACY_HEADER = "excalibur-lexical-store v1";

    /** Opens the last line, which states how many records precede it. */
    private static final String END_MARKER = "#end";

    /** Separates a record's fields, and the end marker from its count. */
    private static final char SEPARATOR = '\t';

    private final List<Entry> entries;
    private boolean verified;

    private SegmentManifest(List<Entry> entries, boolean verified) {
        this.entries = entries;
        this.verified = verified;
    }

    /** An empty store. */
    public static SegmentManifest empty() {
        return new SegmentManifest(new ArrayList<>(), true);
    }

    /**
     * Reads the manifest, or an empty one when the store is new.
     *
     * @throws IOException if the file is there but cannot be shown to be all of itself, which is
     *                     a store to be repaired rather than one to be opened and swept
     */
    public static SegmentManifest read(Path directory) throws IOException {
        Path file = directory.resolve(FILE);
        if (!Files.exists(file)) {
            return empty();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException(file + " is empty, so the rename that published it landed "
                + "before its contents reached the device. Every segment it named is still on "
                + "disk; restore the manifest rather than opening the store, because an empty "
                + "one names nothing and nothing is what would be kept.");
        }
        boolean verified = verifyHeader(file, lines.get(0));
        List<String> records = records(lines);
        if (verified) {
            records = withoutMarker(file, records);
        } else {
            LOGGER.warn("{} predates the record count, so it cannot be shown to be whole; "
                + "segments it does not name are left alone until it has been rewritten", file);
        }
        return new SegmentManifest(parse(file, records), verified);
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

    /** Which format the file is in, or a refusal if it is not one of them. */
    private static boolean verifyHeader(Path file, String header) throws IOException {
        if (HEADER.equals(header.trim())) {
            return true;
        }
        if (LEGACY_HEADER.equals(header.trim())) {
            return false;
        }
        throw new IOException(file + " does not begin with " + HEADER + "; it is not a lexical "
            + "store manifest, or its first line was overwritten");
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

    /** Strips the end marker, having checked that what precedes it is all there. */
    private static List<String> withoutMarker(Path file, List<String> records)
            throws IOException {
        String marker = records.isEmpty() ? "" : records.get(records.size() - 1);
        String prefix = END_MARKER + SEPARATOR;
        if (!marker.startsWith(prefix)) {
            throw new IOException(file + " does not end with a record count, so it was cut short "
                + "before the write that published it finished. The segments it named are still "
                + "on disk and none of them may be removed on the strength of this file.");
        }
        int declared = count(marker.substring(prefix.length()));
        List<String> named = records.subList(0, records.size() - 1);
        if (declared != named.size()) {
            throw new IOException(file + " declares " + declared + " segment(s) and carries "
                + named.size() + "; it lost lines after it was written, and the segments it no "
                + "longer names are still on disk");
        }
        return named;
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
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        int written = 0;
        for (Entry entry : entries) {
            text.append(entry.name()).append(SEPARATOR).append(entry.epoch()).append(SEPARATOR)
                .append(entry.docCount()).append(SEPARATOR).append(entry.tombstoned())
                .append('\n');
            written++;
        }
        // Counted in the same pass that writes them, so the number states what actually reached
        // the file rather than what the list held when the pass began.
        text.append(END_MARKER).append(SEPARATOR).append(written).append('\n');
        durability.replace(directory.resolve(FILE),
            text.toString().getBytes(StandardCharsets.UTF_8));
        // The file on disk is now one this build wrote, whatever the one it was read from was.
        verified = true;
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
