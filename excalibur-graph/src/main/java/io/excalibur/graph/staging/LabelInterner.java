package io.excalibur.graph.staging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Assigns a stable integer id to every distinct label it is shown, and remembers the assignment.
 *
 * <h2>Why the ids have to be stable</h2>
 * <p>The edge store addresses entities by integer, and so does everything built beside it: the
 * lexical index over entity labels stores that integer as its document id, and a serialized
 * subgraph names entities by it. The store is rebuilt whenever the graph is rebuilt, and if the
 * rebuild reassigned ids then every artifact that was not rebuilt in the same breath would point at
 * the wrong entity - silently, because an integer is always a valid integer. Ids are therefore
 * handed out once, in the order labels are first seen, and never reused or renumbered.</p>
 *
 * <h2>Format</h2>
 * <pre>
 * [magic : int64][version : int32][reserved : int32]
 * repeated, id = ordinal:
 *   [length : int32][label : UTF-8][crc32 of the label bytes : int32]
 * </pre>
 *
 * <p>Append-only and read to the end on open. A record whose checksum does not match, or whose
 * length runs past the end of the file, ends the scan: a process killed mid-append leaves a torn
 * tail, and the labels before it are still exactly the ids they were. Truncating there rather than
 * failing is what lets ingestion resume after a crash instead of requiring the graph to be rebuilt
 * from nothing.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. Lookups are lock-free; assigning a new id takes a lock, because the
 * id an appended record gets is its position in the file and the two have to agree.</p>
 *
 * @author ExcaliburDB
 */
public final class LabelInterner implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(LabelInterner.class);

    /** Identifies the file as an ExcaliburDB label interner: ASCII {@code EXCLBLBL}. */
    public static final long MAGIC = 0x4558434C424C424CL;

    /** Format version written by this build. */
    public static final int VERSION = 1;

    /** Bytes before the first record. */
    public static final int HEADER_BYTES = 16;

    /**
     * Longest label this interner will store.
     *
     * <p>An entity name is a name. A value far past this is a extraction that went wrong, and
     * storing it would put a megabyte of prose where an id's label belongs.
     */
    public static final int MAX_LABEL_BYTES = 4096;

    private final Path path;
    private final Map<String, Integer> ids = new ConcurrentHashMap<>();
    private final List<String> labels = new ArrayList<>();
    private final Object appendLock = new Object();

    private DataOutputStream out;
    private volatile boolean closed;

    private LabelInterner(Path path, DataOutputStream out) {
        this.path = path;
        this.out = out;
    }

    /**
     * Opens the interner at {@code path}, reading back every assignment it already holds.
     *
     * @param path The file to read and append to; created if absent
     * @return The interner
     * @throws IOException if the file cannot be read or opened for appending
     */
    public static LabelInterner open(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Replay replay = readExisting(path);
        List<String> existing = replay.labels();

        // Truncated to the last record that read cleanly, before anything is appended. Without
        // this, a torn tail stays in the file and every record written after it sits behind a
        // record the next replay stops at - so those labels are read back as absent and their ids
        // are handed out again to different labels. Ids that move are the one thing this class
        // exists to prevent.
        if (Files.exists(path) && Files.size(path) > replay.validBytes()) {
            LOGGER.warn("truncating {} from {} to {} bytes: the tail was not readable",
                path, Files.size(path), replay.validBytes());
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    path, StandardOpenOption.WRITE)) {
                channel.truncate(replay.validBytes());
            }
        }

        boolean fresh = !Files.exists(path) || Files.size(path) < HEADER_BYTES;
        OutputStream sink = Files.newOutputStream(path,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(sink));
        if (fresh) {
            out.writeLong(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(0);
            out.flush();
        }

        LabelInterner interner = new LabelInterner(path, out);
        for (String label : existing) {
            interner.labels.add(label);
            interner.ids.put(label, interner.labels.size() - 1);
        }
        LOGGER.debug("opened label interner {} holding {} labels", path, existing.size());
        return interner;
    }

    /**
     * The id for {@code label}, assigning and recording one if it is new.
     *
     * @param label The label
     * @return Its id, stable for the life of the file
     * @throws IllegalArgumentException if the label is null, blank or longer than
     *                                  {@link #MAX_LABEL_BYTES} when encoded
     * @throws IOException if a new assignment cannot be recorded
     */
    public int intern(String label) throws IOException {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be null or blank");
        }
        Integer existing = ids.get(label);
        if (existing != null) {
            return existing;
        }

        byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_LABEL_BYTES) {
            throw new IllegalArgumentException(
                "label is " + bytes.length + " bytes, above the " + MAX_LABEL_BYTES + " byte limit");
        }

        synchronized (appendLock) {
            // Re-checked under the lock: two threads can both miss the map, and only one of them
            // may write the record that fixes the id.
            Integer raced = ids.get(label);
            if (raced != null) {
                return raced;
            }
            requireOpen();

            CRC32 crc = new CRC32();
            crc.update(bytes);
            out.writeInt(bytes.length);
            out.write(bytes);
            out.writeInt((int) crc.getValue());

            int id = labels.size();
            labels.add(label);
            ids.put(label, id);
            return id;
        }
    }

    /**
     * The label an id was assigned to.
     *
     * @param id The id
     * @return The label, or null when no such id has been assigned
     */
    public String labelOf(int id) {
        synchronized (appendLock) {
            return id >= 0 && id < labels.size() ? labels.get(id) : null;
        }
    }

    /**
     * Every label in id order.
     *
     * @return A snapshot; index is the id
     */
    public List<String> labels() {
        synchronized (appendLock) {
            return List.copyOf(labels);
        }
    }

    /**
     * How many ids have been assigned.
     *
     * @return The count; ids are {@code 0..size()-1}
     */
    public int size() {
        return ids.size();
    }

    /**
     * Forces every assignment made so far to the file.
     *
     * @throws IOException if the write fails
     */
    public void flush() throws IOException {
        synchronized (appendLock) {
            if (!closed) {
                out.flush();
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (appendLock) {
            if (closed) {
                return;
            }
            closed = true;
            out.flush();
            out.close();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("label interner is closed: " + path);
        }
    }

    /**
     * Reads back the assignments a previous run recorded.
     *
     * <p>Stops at the first record that does not read cleanly rather than failing. The file is
     * appended to without being forced, so a crash leaves a partial record at the end; the
     * assignments before it are intact and are the ones every existing artifact refers to.
     *
     * @param path The file
     * @return The labels, in id order
     * @throws IOException if the file exists but cannot be read at all
     */
    private static Replay readExisting(Path path) throws IOException {
        List<String> labels = new ArrayList<>();
        if (!Files.exists(path) || Files.size(path) < HEADER_BYTES) {
            return new Replay(labels, HEADER_BYTES);
        }

        byte[] all = Files.readAllBytes(path);
        ByteBuffer buffer = ByteBuffer.wrap(all);
        long magic = buffer.getLong();
        if (magic != MAGIC) {
            throw new IOException("not a label interner file (magic 0x"
                + Long.toHexString(magic) + "): " + path);
        }
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IOException("unsupported label interner version " + version + ": " + path);
        }
        buffer.getInt();

        long validBytes = buffer.position();
        while (true) {
            try {
                if (buffer.remaining() < Integer.BYTES) {
                    break;
                }
                int length = buffer.getInt();
                if (length <= 0 || length > MAX_LABEL_BYTES
                    || buffer.remaining() < length + Integer.BYTES) {
                    break;
                }
                byte[] bytes = new byte[length];
                buffer.get(bytes);
                int stored = buffer.getInt();
                CRC32 crc = new CRC32();
                crc.update(bytes);
                if (stored != (int) crc.getValue()) {
                    LOGGER.warn("label interner {} has a corrupt record at label {}; "
                        + "keeping the {} labels before it", path, labels.size(), labels.size());
                    break;
                }
                labels.add(new String(bytes, StandardCharsets.UTF_8));
                validBytes = buffer.position();
            } catch (RuntimeException e) {
                break;
            }
        }
        return new Replay(labels, validBytes);
    }

    /**
     * What a replay recovered, and how far into the file it got.
     *
     * @param labels     the labels read, in id order
     * @param validBytes byte offset just past the last record that read cleanly
     */
    private record Replay(List<String> labels, long validBytes) {
    }

    @Override
    public String toString() {
        return "LabelInterner[" + path + ", " + size() + " labels]";
    }
}
