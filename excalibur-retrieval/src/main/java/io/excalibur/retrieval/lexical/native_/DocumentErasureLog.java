package io.excalibur.retrieval.lexical.native_;

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
 */
public final class DocumentErasureLog {

    /** One outstanding promise. */
    public record Record(Instant requestedAt, String key) {
        public Record {
            Objects.requireNonNull(requestedAt, "requestedAt must not be null");
            DocumentKeys.require(key);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentErasureLog.class);

    private static final String FILE = "erasure.log";
    private static final String HEADER = "excalibur-lexical-erasure v1";

    private final Path file;
    private final List<Record> records;

    private DocumentErasureLog(Path file, List<Record> records) {
        this.file = file;
        this.records = records;
    }

    /** Opens the log for a store directory, reading back anything already owed. */
    public static DocumentErasureLog open(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Path file = directory.resolve(FILE);
        List<Record> records = new ArrayList<>();
        if (Files.exists(file) && Files.size(file) > 0) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (!HEADER.equals(lines.get(0))) {
                // Checked rather than assumed. If the first line is not the header it is a record,
                // and skipping it would silently discharge a promise nobody agreed to discharge.
                throw new IOException("Not an erasure log, first line is: " + lines.get(0));
            }
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    throw new IOException("Malformed erasure log line: " + line);
                }
                long millis;
                try {
                    millis = Long.parseLong(line.substring(0, tab));
                } catch (NumberFormatException e) {
                    throw new IOException("Malformed erasure log timestamp: " + line, e);
                }
                records.add(new Record(Instant.ofEpochMilli(millis), line.substring(tab + 1)));
            }
        }
        return new DocumentErasureLog(file, records);
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
        // Empty counts as absent: a file created but not yet written to is what a crash between
        // the two leaves behind, and appending records to it without a header would make the
        // first of them look like one on the next read.
        boolean fresh = !Files.exists(file) || Files.size(file) == 0;
        StringBuilder sb = new StringBuilder();
        if (fresh) {
            sb.append(HEADER).append('\n');
        }
        List<Record> appended = new ArrayList<>(keys.size());
        for (String key : keys) {
            Record record = new Record(requestedAt, key);
            appended.add(record);
            sb.append(requestedAt.toEpochMilli()).append('\t').append(key).append('\n');
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        if (fresh) {
            // Forcing the file does not promise its directory entry survives with it, and a
            // promise in a file the directory has forgotten is a promise lost.
            forceDirectory();
        }
        records.addAll(appended);
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
            return;
        }
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Record record : kept) {
            sb.append(record.requestedAt().toEpochMilli()).append('\t')
              .append(record.key()).append('\n');
        }
        Path temp = file.resolveSibling(FILE + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        records.clear();
        records.addAll(kept);
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

    /** Promises still on the log. */
    public List<Record> outstanding() {
        return List.copyOf(records);
    }
}
