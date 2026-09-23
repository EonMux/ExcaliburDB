package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the store has promised to erase, and when it promised it.
 *
 * <h2>Why the timestamp is the point</h2>
 * <p>A log of keys alone can say what is owed but not how overdue it is, and "how overdue" is the
 * only thing that makes erasure an obligation rather than a preference. Compaction is expensive, so
 * a policy driven by volume alone will never fire for a single erased document in a large store -
 * which is exactly the request most likely to carry a deadline. Recording when each request arrived
 * is what lets the policy answer that.</p>
 *
 * <h2>Durability</h2>
 * <p>An append is forced to disk before it returns. A caller told its request was accepted and then
 * losing it to a power failure is the one outcome erasure must not have, because nothing afterwards
 * would ever notice the data was still there. The cost is one fsync per erase call, which erasure
 * can afford and correctness cannot do without.</p>
 *
 * <p>The file is text, one request a line, because an operator asked to prove what a store still
 * owes should be able to read the answer.</p>
 *
 * <h2>Owed is not the same as unapplied</h2>
 * <p>A promise stays on the log until compaction physically removes what it names, which may be an
 * hour after the document was hidden. For all of that hour the promise has already been applied:
 * the document is tombstoned, durably. Re-applying every outstanding promise when the store opens
 * would therefore reach past the document it was made about and hide whatever has been written
 * under the same key since - a document erased and then legitimately ingested again would vanish
 * on the next restart, and be compacted away for good at the one after. So the log also records
 * which promises the store has confirmed as applied, and only the rest - the ones a crash caught
 * between the promise and its application - are replayed.</p>
 *
 * <h2>Format</h2>
 * <p>Version 3, after the header line: {@code P<tab>millis<tab>key<tab>crc} for a promise not yet
 * confirmed as applied, {@code A<tab>millis<tab>key<tab>crc} for one that has been, and
 * {@code D<tab>key<tab>crc} confirming every earlier promise of that key - the line an
 * application appends, so confirming costs an append rather than a rewrite. The {@code crc} is
 * the CRC32C of the UTF-8 bytes of the line before its last tab, as eight lowercase hexadecimal
 * digits; a key never holds a tab, so the last tab always begins it.</p>
 *
 * <h2>Why every line carries a checksum</h2>
 * <p>The log is appended to, not replaced, so there is no one moment to seal it at; each line is
 * sealed as it is written instead. A changed byte in a key is otherwise a well-formed promise
 * about some other name: the erasure the caller asked for is never applied, and the store
 * discharges the promise at the next compaction because the name it now holds names nothing. A
 * line whose checksum does not match is refused, as a malformed one always has been, and the
 * store does not open until the log is restored: guessing which promises it held could only
 * make one of them lapse.</p>
 *
 * <h2>Older versions</h2>
 * <p>A version 2 log, which is version 3 without the checksums, is read as it always was. A
 * version 1 log, which records {@code millis<tab>key} and nothing about application, is read
 * with every promise unapplied, which is the only safe reading of it. Either is rewritten as
 * version 3 before anything is appended to it. A log whose header names a later version was
 * written by a newer build and is refused.</p>
 */
public final class DocumentErasureLog {

    /**
     * One outstanding promise.
     *
     * @param requestedAt When it was made
     * @param key         The document key it names
     * @param applied     Whether the store has durably applied it, so a reopen must not again
     */
    public record Record(Instant requestedAt, String key, boolean applied) {
        public Record {
            Objects.requireNonNull(requestedAt, "requestedAt must not be null");
            DocumentKeys.require(key);
        }

        /**
         * A promise not yet confirmed as applied.
         *
         * @param requestedAt When it was made
         * @param key         The document key it names
         */
        public Record(Instant requestedAt, String key) {
            this(requestedAt, key, false);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentErasureLog.class);

    /** The log's file name inside the store directory. */
    public static final String FILE = "erasure.log";

    /** Name used when a damaged log is refused or reported. */
    public static final String FORMAT = "lexical erasure log";

    /** The version this build writes. */
    public static final int VERSION = 3;

    /** The oldest version this build reads. */
    public static final int OLDEST_VERSION = 1;

    private static final String HEADER_PREFIX = "excalibur-lexical-erasure v";
    private static final String HEADER = HEADER_PREFIX + VERSION;

    private static final String PROMISED = "P";
    private static final String APPLIED = "A";
    private static final String CONFIRMED = "D";

    private final Path file;
    private final List<Record> records;

    /** Whether the file on disk is in the current format, so appending to it is safe. */
    private boolean current;

    private DocumentErasureLog(Path file, List<Record> records, boolean current) {
        this.file = file;
        this.records = records;
        this.current = current;
    }

    /**
     * A log written at a version this build does not read.
     */
    public static final class UnsupportedVersionException extends IOException {

        private static final long serialVersionUID = 1L;

        private final int version;

        UnsupportedVersionException(Path file, int version) {
            super(file + " is erasure log version " + version + ", which this build does not "
                + "read - it reads versions " + OLDEST_VERSION + " to " + VERSION + "; a newer "
                + "build wrote it");
            this.version = version;
        }

        /**
         * Returns the version the log declares.
         *
         * @return The version
         */
        public int version() {
            return version;
        }
    }

    /** What reading a log's file found. */
    private record Contents(List<Record> records, int version, int lines) {
    }

    /** Opens the log for a store directory, reading back anything already owed. */
    public static DocumentErasureLog open(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Path file = directory.resolve(FILE);
        Contents contents = readFile(file);
        return new DocumentErasureLog(file, contents.records(),
            contents.version() == VERSION);
    }

    /**
     * Reaches a verdict on a log without changing anything.
     *
     * <p>The log is read exactly as the store reads it, so what the store would refuse is
     * corrupt here and what it would read is not: intact at the current version, whose every
     * line matched its checksum; unprotected at the versions before it, whose lines are
     * well-formed and nothing more.</p>
     *
     * @param file The log
     * @return The verdict
     * @throws NullPointerException if file is null
     */
    public static IntegrityReport inspect(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        Contents contents;
        try {
            contents = readFile(file);
        } catch (UnsupportedVersionException e) {
            return IntegrityReport.unsupportedVersion(file, FORMAT, e.version(), OLDEST_VERSION,
                VERSION);
        } catch (IOException e) {
            return IntegrityReport.corrupt(file, FORMAT, e.getMessage());
        }
        if (contents.lines() == 0) {
            return IntegrityReport.intact(file, FORMAT, "empty, which the store reads as owing "
                + "nothing: it is what a crash between creating the log and writing its first "
                + "line leaves");
        }
        String owed = contents.records().size() + " promise(s) outstanding";
        if (contents.version() < VERSION) {
            return new IntegrityReport(file, FORMAT, IntegrityStatus.LEGACY_UNPROTECTED,
                "every line is well-formed, " + owed + "; this version carries no checksums, so "
                    + "the keys and times themselves are not verified. The store rewrites it "
                    + "with them before it next records or settles an erasure");
        }
        return IntegrityReport.intact(file, FORMAT, "every one of its " + contents.lines()
            + " line(s) matched its checksum, " + owed);
    }

    /**
     * Reads a log file into its records, refusing anything the store must not guess at.
     *
     * @param file The log
     * @return The records, the version, and how many record lines there were
     * @throws UnsupportedVersionException if a newer build wrote it
     * @throws IOException if a line is malformed or does not match its checksum
     */
    private static Contents readFile(Path file) throws IOException {
        List<Record> records = new ArrayList<>();
        if (!Files.exists(file) || Files.size(file) == 0) {
            return new Contents(records, VERSION, 0);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int version = versionOf(file, lines.get(0));
        int read = 0;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            read++;
            if (version == OLDEST_VERSION) {
                records.add(versionOneRecord(line));
            } else {
                readLine(version == VERSION ? verified(file, i + 1, line) : line, records);
            }
        }
        return new Contents(records, version, read);
    }

    /**
     * Reads the version a header line declares.
     *
     * <p>Checked rather than assumed. If the first line is not a header it is a record, and
     * skipping it would silently discharge a promise nobody agreed to discharge.</p>
     */
    private static int versionOf(Path file, String header) throws IOException {
        if (header.startsWith(HEADER_PREFIX)) {
            int version;
            try {
                version = Integer.parseInt(header.substring(HEADER_PREFIX.length()));
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
        throw new IOException("Not an erasure log, first line is: " + header);
    }

    /**
     * Checks a version 3 line against its checksum and returns the line without it.
     *
     * @param file   The log, for the message
     * @param number The line's number in the file, counting the header as 1
     * @param line   The line as read
     * @return The line up to its last tab
     * @throws IOException if the line carries no checksum or does not match it
     */
    private static String verified(Path file, int number, String line) throws IOException {
        int tab = line.lastIndexOf('\t');
        String stored = tab < 0 ? "" : line.substring(tab + 1);
        if (stored.length() != 8) {
            throw new IOException(file + " line " + number + " does not end with a checksum, so "
                + "it was cut short or overwritten: " + line);
        }
        String body = line.substring(0, tab);
        String actual = checksumOf(body);
        if (!actual.equals(stored)) {
            throw new IOException(file + " line " + number + " failed its integrity check: the "
                + "checksum did not match. It records " + stored + " and the line digests to "
                + actual + ", so the promise it holds cannot be relied on: " + body);
        }
        return body;
    }

    /** The checksum a version 3 line carries: CRC32C of its UTF-8 bytes, as eight hex digits. */
    private static String checksumOf(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return String.format("%08x", IndexChecksum.of(bytes, 0, bytes.length));
    }

    /** Appends one line, sealed with its checksum. */
    private static void appendSealed(StringBuilder sb, String body) {
        sb.append(body).append('\t').append(checksumOf(body)).append('\n');
    }

    /** A version 1 line: a promise, with nothing recorded about whether it was applied. */
    private static Record versionOneRecord(String line) throws IOException {
        int tab = line.indexOf('\t');
        if (tab < 0) {
            throw new IOException("Malformed erasure log line: " + line);
        }
        return new Record(millisOf(line, line.substring(0, tab)), line.substring(tab + 1));
    }

    /** Applies one version 2 line to the records read so far. */
    private static void readLine(String line, List<Record> records) throws IOException {
        int tab = line.indexOf('\t');
        if (tab < 0) {
            throw new IOException("Malformed erasure log line: " + line);
        }
        String kind = line.substring(0, tab);
        String rest = line.substring(tab + 1);
        if (CONFIRMED.equals(kind)) {
            confirm(records, Set.of(rest));
            return;
        }
        if (!PROMISED.equals(kind) && !APPLIED.equals(kind)) {
            throw new IOException("Malformed erasure log line: " + line);
        }
        int second = rest.indexOf('\t');
        if (second < 0) {
            throw new IOException("Malformed erasure log line: " + line);
        }
        records.add(new Record(millisOf(line, rest.substring(0, second)),
            rest.substring(second + 1), APPLIED.equals(kind)));
    }

    private static Instant millisOf(String line, String millis) throws IOException {
        try {
            return Instant.ofEpochMilli(Long.parseLong(millis));
        } catch (NumberFormatException e) {
            throw new IOException("Malformed erasure log timestamp: " + line, e);
        }
    }

    /** Marks every unapplied record of these keys applied, in place. */
    private static int confirm(List<Record> records, Set<String> keys) {
        int confirmed = 0;
        for (int i = 0; i < records.size(); i++) {
            Record record = records.get(i);
            if (!record.applied() && keys.contains(record.key())) {
                records.set(i, new Record(record.requestedAt(), record.key(), true));
                confirmed++;
            }
        }
        return confirmed;
    }

    /**
     * Records promises to erase, and forces them to disk before returning.
     *
     * @param keys        Document keys promised
     * @param requestedAt When the promise was made
     */
    public void append(Collection<String> keys, Instant requestedAt) throws IOException {
        Objects.requireNonNull(keys, "keys must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        if (keys.isEmpty()) {
            return;
        }
        List<Record> appended = new ArrayList<>(keys.size());
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            Record record = new Record(requestedAt, key);
            appended.add(record);
            appendRecord(sb, record);
        }
        appendLines(sb);
        records.addAll(appended);
    }

    /**
     * Confirms that the store has durably applied every outstanding promise of these keys.
     *
     * <p>Called only once the tombstones or deletes are on the device. Confirming earlier would
     * let a crash in between lose a promise that a reopen then declines to replay; confirming
     * never is safe but reaches too far, which is what this exists to prevent.</p>
     *
     * @param keys Document keys whose promises are now applied
     */
    public void confirmApplied(Collection<String> keys) throws IOException {
        Objects.requireNonNull(keys, "keys must not be null");
        Set<String> pending = new LinkedHashSet<>();
        for (Record record : records) {
            if (!record.applied() && keys.contains(record.key())) {
                pending.add(record.key());
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String key : pending) {
            appendSealed(sb, CONFIRMED + '\t' + key);
        }
        appendLines(sb);
        confirm(records, pending);
    }

    /** Appends lines in the current format, forcing them to disk before returning. */
    private void appendLines(StringBuilder lines) throws IOException {
        if (!current) {
            // An older file cannot take a current line, so it is rewritten first - a version 1
            // log with every promise it held still unapplied, which is all it could say.
            rewrite(records);
            current = true;
        }
        // Empty counts as absent: a file created but not yet written to is what a crash between
        // the two leaves behind, and appending records to it without a header would make the
        // first of them look like one on the next read.
        boolean fresh = !Files.exists(file) || Files.size(file) == 0;
        String text = fresh ? HEADER + "\n" + lines : lines.toString();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        if (fresh) {
            // Forcing the file does not promise its directory entry survives with it, and a
            // promise in a file the directory has forgotten is a promise lost.
            forceDirectory();
        }
    }

    private static void appendRecord(StringBuilder sb, Record record) {
        appendSealed(sb, (record.applied() ? APPLIED : PROMISED) + '\t'
            + record.requestedAt().toEpochMilli() + '\t' + record.key());
    }

    /**
     * Drops every promise except those named, and forces the result to disk.
     *
     * <p>Called once compaction has physically removed documents: what remains outstanding is what
     * is still present. A key that never named anything in the store is discharged here too, since
     * a promise about data that does not exist is already kept - left in the log it would age past
     * the latency bound forever and demand compaction that could never settle it.</p>
     */
    public void retain(Set<String> keys) throws IOException {
        Objects.requireNonNull(keys, "keys must not be null");
        List<Record> kept = new ArrayList<>();
        for (Record record : records) {
            if (keys.contains(record.key())) {
                kept.add(record);
            }
        }
        if (kept.size() == records.size()) {
            return;
        }
        if (kept.isEmpty()) {
            Files.deleteIfExists(file);
            records.clear();
            current = true;
            return;
        }
        rewrite(kept);
        current = true;
        records.clear();
        records.addAll(kept);
    }

    /** Replaces the file with these records, in the current format, durably. */
    private void rewrite(List<Record> kept) throws IOException {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Record record : kept) {
            appendRecord(sb, record);
        }
        Path temp = file.resolveSibling(FILE + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        forceDirectory();
    }

    private void forceDirectory() throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            // Directories cannot be opened for sync on every filesystem. The record is already
            // forced; this was the belt to its braces, and failing the erasure over it would
            // reject a promise that has in fact been written.
            LOGGER.debug("Could not sync {} after creating the erasure log", parent, e);
        }
    }

    /** When the longest-waiting promise was made, or empty when nothing is owed. */
    public Optional<Instant> oldestOutstanding() {
        Instant oldest = null;
        for (Record record : records) {
            if (oldest == null || record.requestedAt().isBefore(oldest)) {
                oldest = record.requestedAt();
            }
        }
        return Optional.ofNullable(oldest);
    }

    /** Every key still owed, in the order first promised. */
    public Set<String> outstandingKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Record record : records) {
            keys.add(record.key());
        }
        return keys;
    }

    /**
     * Every key with a promise the store has not confirmed as applied.
     *
     * <p>What a reopen replays. A promise that was applied stays owed until compaction removes
     * the document, but replaying it would hide whatever has since been written under its key.</p>
     */
    public Set<String> unappliedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Record record : records) {
            if (!record.applied()) {
                keys.add(record.key());
            }
        }
        return keys;
    }

    /** Promises still on the log. */
    public List<Record> outstanding() {
        return List.copyOf(records);
    }
}
