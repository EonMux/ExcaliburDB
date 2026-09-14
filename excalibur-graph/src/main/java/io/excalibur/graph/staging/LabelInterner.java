package io.excalibur.graph.staging;

import io.excalibur.graph.file.Durability;
import io.excalibur.graph.file.TailIntegrity;

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
import java.util.function.IntPredicate;
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
 * <p>That tolerance holds only while nothing readable follows the damage. Labels lost from the
 * middle of the file would have their ids handed out again to different labels, while every
 * artifact built from an earlier state - the published dictionary above all - still maps those ids
 * to the labels that were lost. An entity served under a stranger's name is the one outcome this
 * class exists to prevent, so that case fails the open instead; see
 * {@link io.excalibur.graph.file.TailIntegrity}.</p>
 *
 * <h2>Erasure</h2>
 * <p>{@link #tombstone} is the only way a label leaves this file. It rewrites the file in place of
 * appending, because an erased name that is still on disk has not been erased, and it keeps every
 * ordinal where it was, because an erasure may not renumber a published graph.</p>
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

    /** Suffix of the whole-file replacement a tombstoning rewrite builds before swapping it in. */
    private static final String REWRITE_SUFFIX = ".rewriting";

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
        //
        // Which is also why the truncation is refused unless the unreadable bytes really are a
        // tail. Labels lost from the middle of the file would be reassigned to whatever is
        // interned next, while the published entities.idx still maps those ids to the labels that
        // were lost - one entity served under another's name, reported by nothing.
        if (Files.exists(path) && Files.size(path) > replay.validBytes()) {
            TailIntegrity.requireTornTail(path, "label interner", replay.contents(),
                replay.validBytes(), LabelInterner::recordStartsAt);
            LOGGER.warn("truncating {} from {} to {} bytes: the tail was not readable",
                path, Files.size(path), replay.validBytes());
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    path, StandardOpenOption.WRITE)) {
                channel.truncate(replay.validBytes());
            }
        }

        LabelInterner interner = new LabelInterner(path, openForAppend(path));
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
     * The placeholder an erased id's label is replaced by.
     *
     * <p>Carries the id so that two erased labels stay distinct strings. Were they identical, the
     * label-to-id map would hold one entry for both and {@link #size()} would fall below the
     * number of ids assigned - and that count is the entity count an edge store is built with, so
     * shrinking it would size the store's offset array below ids that are still in use.</p>
     *
     * @param id The erased id
     * @return A non-blank label that names nobody
     */
    public static String erasedLabel(int id) {
        return "(erased " + id + ")";
    }

    /**
     * Replaces the labels of erased ids with {@link #erasedLabel}, keeping every ordinal in place.
     *
     * <h3>Why a rewrite rather than an append</h3>
     * <p>The file is the only record of what each id is called, and an id's label is its ordinal in
     * the file. An erased name cannot be retracted by appending anything, because the bytes of the
     * name would still be on disk - which is the whole distinction between a tombstone and an
     * erasure. So the file is rewritten with the same records in the same order, and only the
     * erased ones carrying different bytes.</p>
     *
     * <p>Ids therefore do not move, which they must not: the published edge store, the lexical
     * index over labels and any serialized subgraph all address entities by these integers, and
     * none of them is rebuilt by an erasure.</p>
     *
     * <p>The erased label is also dropped from the lookup map, so it survives neither on disk nor
     * in this process, and re-ingesting the same name afterwards assigns a fresh id rather than
     * reviving the one that was erased.</p>
     *
     * @param erased Answers true for each id whose label must go
     * @return How many labels were replaced
     * @throws IOException if the rewritten file cannot be written, forced or moved into place
     * @throws NullPointerException if {@code erased} is null
     * @throws IllegalStateException if the interner is closed
     */
    public int tombstone(IntPredicate erased) throws IOException {
        Objects.requireNonNull(erased, "erased must not be null");

        synchronized (appendLock) {
            requireOpen();
            List<Integer> victims = new ArrayList<>();
            for (int id = 0; id < labels.size(); id++) {
                if (erased.test(id)) {
                    victims.add(id);
                }
            }
            if (victims.isEmpty()) {
                return 0;
            }

            out.flush();
            List<String> rewritten = new ArrayList<>(labels);
            for (int id : victims) {
                rewritten.set(id, erasedLabel(id));
            }
            rewriteFile(rewritten);

            for (int id : victims) {
                ids.remove(labels.get(id));
                labels.set(id, rewritten.get(id));
                ids.put(rewritten.get(id), id);
            }
            LOGGER.info("tombstoned {} label(s) in {}; {} ids unchanged",
                victims.size(), path, labels.size());
            return victims.size();
        }
    }

    /**
     * Writes {@code replacement} over the file as a whole, durably.
     *
     * <p>Built beside the real file and moved onto it, so a failure part way leaves the previous
     * labels rather than a file holding half of each. Forced before the move because the point of
     * the rewrite is that the erased bytes are gone: a rename that outran its own contents would
     * leave the erased name readable on the platter after the erasure was reported discharged.</p>
     *
     * <p>The append stream is reopened whatever happens, since the move replaces the file it was
     * writing to and an interner left holding the old one would accept labels nothing reads.</p>
     *
     * @param replacement Every label, in id order
     * @throws IOException if the replacement cannot be written or swapped in
     */
    private void rewriteFile(List<String> replacement) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + REWRITE_SUFFIX);
        Files.deleteIfExists(temp);
        out.close();
        try {
            try (DataOutputStream rewritten = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)))) {
                rewritten.writeLong(MAGIC);
                rewritten.writeInt(VERSION);
                rewritten.writeInt(0);
                for (String label : replacement) {
                    byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    rewritten.writeInt(bytes.length);
                    rewritten.write(bytes);
                    rewritten.writeInt((int) crc.getValue());
                }
            }
            Durability.DEVICE.replaceDurably(temp, path);
        } finally {
            Files.deleteIfExists(temp);
            out = openForAppend(path);
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
     * Opens the file for appending, writing the header when there is not one yet.
     *
     * @param path The file
     * @return A buffered stream positioned at the end
     * @throws IOException if the file cannot be opened
     */
    private static DataOutputStream openForAppend(Path path) throws IOException {
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
        return out;
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
            return new Replay(labels, HEADER_BYTES, new byte[0]);
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
        return new Replay(labels, validBytes, all);
    }

    /**
     * What a replay recovered, and how far into the file it got.
     *
     * @param labels     the labels read, in id order
     * @param validBytes byte offset just past the last record that read cleanly
     * @param contents   the whole file as read, so the tail can be examined without re-reading it
     */
    private record Replay(List<String> labels, long validBytes, byte[] contents) {
    }

    /**
     * Whether a complete, checksum-clean label record begins at {@code offset}.
     *
     * @param contents The whole file
     * @param offset   Where to look
     * @return true when a record starts exactly there
     */
    private static boolean recordStartsAt(byte[] contents, int offset) {
        if (offset + Integer.BYTES > contents.length) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(contents, offset, contents.length - offset);
        int length = buffer.getInt();
        if (length <= 0 || length > MAX_LABEL_BYTES
            || buffer.remaining() < length + Integer.BYTES) {
            return false;
        }
        CRC32 crc = new CRC32();
        crc.update(contents, offset + Integer.BYTES, length);
        buffer.position(buffer.position() + length);
        return buffer.getInt() == (int) crc.getValue();
    }

    @Override
    public String toString() {
        return "LabelInterner[" + path + ", " + size() + " labels]";
    }
}
