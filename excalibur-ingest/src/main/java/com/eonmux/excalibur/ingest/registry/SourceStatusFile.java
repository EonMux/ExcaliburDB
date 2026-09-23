package com.eonmux.excalibur.ingest.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How each registered source's last ingestion ended, on disk.
 *
 * <h2>Why it is on disk</h2>
 * <p>{@code GET /v1/ingest/status} is how an operator asks whether the index is following a
 * source: when it was last crawled, what that crawl indexed, what it passed over and whether it
 * failed. Held only in memory, every answer resets at a restart - every source reads zero counts,
 * nothing skipped and a last update at the moment the node came up - so a source crawled a minute
 * ago and one last crawled before a restart three weeks back are indistinguishable, which is the
 * one question the readback exists to answer.</p>
 *
 * <p>So the outcome of each finished run is written here, beside the {@link SourceCatalog} whose
 * registrations it describes. A sibling rather than a field of the catalog, because the two are
 * different kinds of record: the catalog is a promise - a registration the node must not lose,
 * and whose loss has to stop the node - and is written only when a caller registers or removes a
 * source, while this is a report, rewritten whenever a run ends, and one that cannot be read costs
 * the history and nothing else.</p>
 *
 * <h2>What is kept</h2>
 * <p>Only runs that ended. A run in progress when the node stops never reaches this file, so a
 * source whose crawl the restart interrupted comes back reporting the last crawl that finished,
 * never a crawl that claims to still be running.</p>
 *
 * <h2>Record format</h2>
 * <p>The catalog's conventions, deliberately, so the tools that check a data directory read both
 * the same way. A first line, {@code \#source-status|<version>}, names the format; one line per
 * source follows, {@code id|state|progress|ingested|pending|skipped|disallowed|updated_ms[|error]},
 * escaped as the catalog escapes; and a last line, {@code \#end|count}, closes the file, so a file
 * that lost its tail is told apart from a shorter history. The error field is absent rather than
 * empty when there is none: an empty message and no message are different answers.</p>
 *
 * <p>Version 1 had no {@code disallowed} field, since nothing was left out of a crawl on a
 * publisher's say-so before robots.txt was honoured; its records are still read, with that count
 * as zero, and the next run to end rewrites the file in version 2. How many fields a record has
 * is a property of the version its file declares, never guessed from the record, because a
 * version 1 record carrying an error has as many fields as a version 2 record without one.</p>
 *
 * <h2>Reading an unreadable file</h2>
 * <p>A file with no header, a version this build does not know, a missing or disagreeing record
 * count, or one that cannot be read at all is logged and ignored: the node starts, and each source
 * reports from its next run onwards. Refusing to start over a report would take a serving node
 * down for the sake of its history. A record that does not parse costs that one source's
 * history.</p>
 *
 * <h2>Durability</h2>
 * <p>As the catalog's, through the same {@link SourceCatalog.CatalogWriter}: the whole file is
 * written to a sibling, forced, renamed over this one, and the directory forced after, so a crash
 * leaves either the previous history or the new one and never a truncated mixture.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>{@link #write(Collection)} is serialised on the file; {@link #loaded()} is immutable.</p>
 */
public final class SourceStatusFile {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceStatusFile.class);

    /** The status file, under the data directory. */
    public static final String FILE = "source-status";

    /**
     * The format this build writes, and the newest it reads.
     *
     * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which
     * judge this file offline and must accept exactly what this class accepts.</p>
     */
    public static final int FORMAT_VERSION = 2;

    /**
     * The oldest format this build still reads: the one written before the disallowed count.
     *
     * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which
     * judge this file offline and must accept exactly what this class accepts.</p>
     */
    public static final int OLDEST_READABLE_VERSION = 1;

    /** The version that first carried the disallowed count. */
    private static final int FIRST_DISALLOWED_VERSION = 2;

    /**
     * Opens the first line, which names the format the rest of the file is in.
     *
     * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which
     * judge this file offline and must accept exactly what this class accepts.</p>
     */
    public static final String HEADER_MARKER = "\\#source-status";

    /**
     * Opens the last line, which states how many records precede it.
     *
     * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which
     * judge this file offline and must accept exactly what this class accepts.</p>
     */
    public static final String END_MARKER = "\\#end";

    /** Separates the fields of one record. */
    private static final char FIELD_SEPARATOR = '|';

    /** Suffix of the sibling a rewrite is assembled in before it is published. */
    private static final String REPLACEMENT_SUFFIX = ".tmp";

    /** Fields every version 1 record carries: id, state, progress, three counts and the time. */
    private static final int FIXED_FIELDS_V1 = 7;

    /** Fields every record carries from version 2 on, the disallowed count among the counts. */
    private static final int FIXED_FIELDS_V2 = 8;

    private final Path file;
    private final SourceCatalog.CatalogWriter writer;
    private final Map<String, Entry> loaded;
    private final Object writeLock = new Object();

    /**
     * How one source's last finished run ended.
     *
     * @param sourceId      The source
     * @param state         The state the run ended in
     * @param progress      Its progress, in [0.0, 1.0]
     * @param ingested      Entries it indexed
     * @param pending       Entries it left waiting
     * @param skipped       Documents it passed over because they are not text
     * @param disallowed    Documents it left alone because their publisher forbids fetching
     *                      them
     * @param lastUpdatedMs When it ended, epoch milliseconds
     * @param lastError     What went wrong, or null if nothing did
     */
    public record Entry(String sourceId, String state, double progress, long ingested,
                        long pending, long skipped, long disallowed, long lastUpdatedMs,
                        String lastError) {

        /**
         * Validates an entry.
         *
         * @throws NullPointerException     if the id or the state is null
         * @throws IllegalArgumentException if a count or the time is negative, or the progress
         *                                  is not in [0.0, 1.0]
         */
        public Entry {
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(state, "state must not be null");
            if (ingested < 0 || pending < 0 || skipped < 0 || disallowed < 0
                    || lastUpdatedMs < 0) {
                throw new IllegalArgumentException("status counts must not be negative");
            }
            if (!(progress >= 0.0 && progress <= 1.0)) {
                throw new IllegalArgumentException("progress must be in [0.0, 1.0]: " + progress);
            }
        }
    }

    /**
     * Opens the status file under a data directory, reading what a previous run left there.
     *
     * @param dataDirectory The node's data directory
     * @return The file
     * @throws NullPointerException if the directory is null
     */
    public static SourceStatusFile open(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory must not be null");
        return new SourceStatusFile(dataDirectory.resolve(FILE), SourceCatalog.fileSystemWriter());
    }

    /**
     * Opens the file at an explicit path against an explicit set of disk operations.
     *
     * <p>Exists so a test can observe the order the contents and the rename are made durable in,
     * as the catalog's own seam does.</p>
     *
     * @param file   The status file, which need not exist yet
     * @param writer The operations that publish a rewrite
     */
    SourceStatusFile(Path file, SourceCatalog.CatalogWriter writer) {
        this.file = Objects.requireNonNull(file, "file must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        this.loaded = Files.exists(file) ? load() : Map.of();
    }

    /**
     * What the file held when it was opened: each source's last finished run, by source id.
     *
     * @return The entries, unmodifiable
     */
    public Map<String, Entry> loaded() {
        return loaded;
    }

    /**
     * Replaces the file's contents with these entries.
     *
     * <p>The whole history each time: the file holds one short line per registered source, so a
     * rewrite costs nothing worth optimising, and it is what makes the replacement atomic.</p>
     *
     * @param entries Every source's last finished run
     * @throws NullPointerException if the collection or an entry is null
     * @throws IOException          if the file cannot be written; the previous one is left as it
     *                              was
     */
    public void write(Collection<Entry> entries) throws IOException {
        Objects.requireNonNull(entries, "entries must not be null");
        List<Entry> snapshot = List.copyOf(entries);
        synchronized (writeLock) {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path replacement = file.resolveSibling(file.getFileName() + REPLACEMENT_SUFFIX);
            try {
                writeTo(replacement, snapshot);
                writer.force(replacement);
                writer.publish(replacement, file);
            } catch (IOException | RuntimeException e) {
                try {
                    Files.deleteIfExists(replacement);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }
            if (parent != null) {
                writer.forceDirectory(parent);
            }
        }
    }

    private static void writeTo(Path replacement, List<Entry> entries) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(replacement, StandardCharsets.UTF_8)) {
            out.write(HEADER_MARKER);
            out.write(FIELD_SEPARATOR);
            out.write(Integer.toString(FORMAT_VERSION));
            out.newLine();
            for (Entry entry : entries) {
                out.write(format(entry));
                out.newLine();
            }
            out.write(END_MARKER);
            out.write(FIELD_SEPARATOR);
            out.write(Integer.toString(entries.size()));
            out.newLine();
        }
    }

    private static String format(Entry entry) {
        StringBuilder line = new StringBuilder()
            .append(SourceCatalog.escape(entry.sourceId())).append(FIELD_SEPARATOR)
            .append(SourceCatalog.escape(entry.state())).append(FIELD_SEPARATOR)
            .append(entry.progress()).append(FIELD_SEPARATOR)
            .append(entry.ingested()).append(FIELD_SEPARATOR)
            .append(entry.pending()).append(FIELD_SEPARATOR)
            .append(entry.skipped()).append(FIELD_SEPARATOR)
            .append(entry.disallowed()).append(FIELD_SEPARATOR)
            .append(entry.lastUpdatedMs());
        if (entry.lastError() != null) {
            line.append(FIELD_SEPARATOR).append(SourceCatalog.escape(entry.lastError()));
        }
        return line.toString();
    }

    /**
     * Reads the file, or reports why it is being ignored and reads nothing.
     *
     * @return The entries by source id
     */
    private Map<String, Entry> load() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ignored("it could not be read: " + e);
        }
        if (lines.isEmpty()) {
            return ignored("it is empty");
        }
        String header = lines.get(0);
        String headerPrefix = HEADER_MARKER + FIELD_SEPARATOR;
        if (!header.startsWith(headerPrefix)) {
            return ignored("its first line names no format: " + header);
        }
        int version;
        try {
            version = Integer.parseInt(header.substring(headerPrefix.length()));
        } catch (NumberFormatException e) {
            return ignored("its first line names no format version this build reads: " + header);
        }
        if (version < OLDEST_READABLE_VERSION || version > FORMAT_VERSION) {
            return ignored("it is in format version " + version + " and this build reads versions "
                + OLDEST_READABLE_VERSION + " to " + FORMAT_VERSION);
        }
        List<String> records = lines.subList(1, Math.max(1, lines.size() - 1));
        int declared = lines.size() < 2 ? -1 : declaredCount(lines.get(lines.size() - 1));
        if (declared < 0) {
            return ignored("it does not end with a record count, so the write that published it "
                + "was cut short");
        }
        if (declared != records.size()) {
            return ignored("it declares " + declared + " record(s) and carries "
                + records.size());
        }
        return parseAll(records, version);
    }

    private Map<String, Entry> parseAll(List<String> records, int version) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        int skipped = 0;
        for (String line : records) {
            Entry entry = parse(line, version);
            if (entry == null) {
                skipped++;
            } else {
                entries.put(entry.sourceId(), entry);
            }
        }
        if (skipped > 0) {
            LOGGER.warn("Skipped {} unparseable record(s) in the source status file at {}; those "
                + "sources report from their next run onwards", skipped, file);
        }
        return Map.copyOf(entries);
    }

    /**
     * Logs why the file is being ignored, and answers with an empty history.
     *
     * @param reason What is wrong with it
     * @return An empty map
     */
    private Map<String, Entry> ignored(String reason) {
        LOGGER.warn("Ignoring the source status file at {}: {}. Every source reports from its "
            + "next run onwards, and the file is rewritten when the first one ends", file, reason);
        return Map.of();
    }

    private static int declaredCount(String line) {
        String prefix = END_MARKER + FIELD_SEPARATOR;
        if (!line.startsWith(prefix)) {
            return -1;
        }
        try {
            int count = Integer.parseInt(line.substring(prefix.length()));
            return Math.max(-1, count);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parses one record, in the format version the file declared.
     *
     * @param line    The record line
     * @param version The format the record was written in
     * @return The entry, or null when the line is malformed
     */
    private static Entry parse(String line, int version) {
        List<String> fields = SourceCatalog.splitEscaped(line, FIELD_SEPARATOR);
        boolean countsDisallowed = version >= FIRST_DISALLOWED_VERSION;
        int fixed = countsDisallowed ? FIXED_FIELDS_V2 : FIXED_FIELDS_V1;
        if (fields.size() != fixed && fields.size() != fixed + 1) {
            return null;
        }
        // The fields after the skipped count shift by one where the disallowed count is present.
        int time = countsDisallowed ? 7 : 6;
        try {
            return new Entry(
                SourceCatalog.unescape(fields.get(0)),
                SourceCatalog.unescape(fields.get(1)),
                Double.parseDouble(fields.get(2)),
                Long.parseLong(fields.get(3)),
                Long.parseLong(fields.get(4)),
                Long.parseLong(fields.get(5)),
                countsDisallowed ? Long.parseLong(fields.get(6)) : 0L,
                Long.parseLong(fields.get(time)),
                fields.size() > fixed ? SourceCatalog.unescape(fields.get(fixed)) : null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
