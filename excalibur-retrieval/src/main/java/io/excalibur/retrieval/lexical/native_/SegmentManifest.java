package io.excalibur.retrieval.lexical.native_;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    private static final String FILE = "manifest";
    private static final String HEADER = "excalibur-lexical-store v1";

    private final List<Entry> entries;

    private SegmentManifest(List<Entry> entries) {
        this.entries = entries;
    }

    /** An empty store. */
    public static SegmentManifest empty() {
        return new SegmentManifest(new ArrayList<>());
    }

    /** Reads the manifest, or an empty one when the store is new. */
    public static SegmentManifest read(Path directory) throws IOException {
        Path file = directory.resolve(FILE);
        if (!Files.exists(file)) {
            return empty();
        }
        List<Entry> entries = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 4) {
                throw new IOException("Malformed manifest line: " + line);
            }
            entries.add(new Entry(parts[0], Long.parseLong(parts[1]),
                Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
        }
        return new SegmentManifest(entries);
    }

    /**
     * Replaces the manifest atomically.
     *
     * <p>Written beside and renamed over, so a reader sees the previous set of segments or the
     * new one and never a truncated file.</p>
     */
    public void write(Path directory) throws IOException {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Entry entry : entries) {
            sb.append(entry.name()).append('\t').append(entry.epoch()).append('\t')
              .append(entry.docCount()).append('\t').append(entry.tombstoned()).append('\n');
        }
        Path temp = directory.resolve(FILE + ".tmp");
        Files.writeString(temp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(temp, directory.resolve(FILE),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
