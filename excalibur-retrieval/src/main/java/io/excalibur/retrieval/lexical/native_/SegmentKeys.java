package io.excalibur.retrieval.lexical.native_;

import io.excalibur.core.hash.XxHash64;
import io.excalibur.core.mapped.MappedFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * The caller's name for every document in a segment, kept on disk rather than in the heap.
 *
 * <h2>Why not a String array</h2>
 * <p>Holding one Java string per document costs roughly seventy bytes of heap for a forty-character
 * chunk identifier, so a hundred million chunks is several gigabytes of heap that exists only to
 * answer "what was this document called". Lucene stores external keys as a field on disk and reads
 * them when a hit needs one; a replacement that kept them resident would be spending memory Lucene
 * did not, which is the wrong direction to replace it in.</p>
 *
 * <p>So the names live in three files and are decoded on demand. A query touching ten hits decodes
 * ten names, and the page cache - not the heap - decides what stays resident.</p>
 *
 * <h2>Finding a document by name</h2>
 * <p>Names are also read the other way round: erasing a document, and republishing one, both start
 * from a name and need the document it belongs to. Scanning the names for it costs a pass over the
 * whole segment, which turns erasing three chunks out of a hundred million into reading every
 * chunk identifier in the store. So each segment carries an open-addressed table from the hash of
 * a name to its document id, giving that lookup a couple of probes instead.</p>
 *
 * <p>The table is derived data: it is rebuilt on open whenever it is missing or the wrong size, so
 * a segment written by a build that had no table, or interrupted while writing one, repairs itself
 * rather than being unreadable.</p>
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@code <segment>.keys} - the names, UTF-8, one per line. Text on purpose: this is the file
 *       an operator opens to find out what a segment actually holds.</li>
 *   <li>{@code <segment>.keyidx} - {@code count + 1} big-endian int32 byte offsets into it. The
 *       count is the file's length rather than a header, so there is no number that can disagree
 *       with the offsets beside it.</li>
 *   <li>{@code <segment>.keymap} - a power-of-two number of eight-byte slots: the document id plus
 *       one, then a fingerprint of the name's hash. Zero is an empty slot, which is why the id is
 *       stored offset by one and why a freshly created file needs no initialising pass. Capacity
 *       is at least twice the document count, so a probe always meets an empty slot.</li>
 * </ul>
 */
final class SegmentKeys implements AutoCloseable {

    static final String KEYS_SUFFIX = ".keys";
    static final String INDEX_SUFFIX = ".keyidx";
    static final String MAP_SUFFIX = ".keymap";

    /** Document id plus one, then the fingerprint. */
    private static final int SLOT_BYTES = 2 * Integer.BYTES;

    /** Smallest table written, so an empty segment still has somewhere to probe. */
    private static final int MIN_SLOTS = 8;

    /** Beyond this a table cannot be addressed by a power-of-two int capacity. */
    private static final int MAX_DOCUMENTS = 1 << 29;

    private final MappedFile names;
    private final MappedFile offsets;
    private final MappedFile map;
    private final int count;
    private final int mask;

    private SegmentKeys(MappedFile names, MappedFile offsets, MappedFile map, int count) {
        this.names = names;
        this.offsets = offsets;
        this.map = map;
        this.count = count;
        this.mask = (int) (map.size() / SLOT_BYTES) - 1;
    }

    /** Opens a segment's names for reading, rebuilding the lookup table if it is not usable. */
    static SegmentKeys open(Path directory, String segment) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(segment, "segment must not be null");
        MappedFile offsets = MappedFile.open(directory.resolve(segment + INDEX_SUFFIX));
        MappedFile names = null;
        MappedFile map = null;
        try {
            names = MappedFile.open(directory.resolve(segment + KEYS_SUFFIX));
            // One offset per document plus a terminating one, so a count is never stored twice.
            int count = (int) (offsets.size() / Integer.BYTES) - 1;
            if (count < 0) {
                throw new IOException("Truncated key index for segment " + segment);
            }
            Path table = directory.resolve(segment + MAP_SUFFIX);
            long expected = (long) capacityFor(count) * SLOT_BYTES;
            if (!Files.exists(table) || Files.size(table) != expected) {
                writeMap(table, names, offsets, count);
            }
            map = MappedFile.open(table);
            return new SegmentKeys(names, offsets, map, count);
        } catch (IOException | RuntimeException e) {
            offsets.close();
            if (names != null) {
                names.close();
            }
            if (map != null) {
                map.close();
            }
            throw e;
        }
    }

    /** Documents named here. */
    int count() {
        return count;
    }

    /**
     * The name of one document.
     *
     * @param docId The segment-local document id
     */
    String at(int docId) {
        return new String(rawAt(docId), StandardCharsets.UTF_8);
    }

    /**
     * The UTF-8 bytes of one document's name.
     *
     * <p>What supersession iterates: it compares names rather than reading them, and decoding
     * every name of a batch into a String only to encode it again is work with no reader.</p>
     *
     * @param docId The segment-local document id
     */
    byte[] rawAt(int docId) {
        if (docId < 0 || docId >= count) {
            throw new IndexOutOfBoundsException(
                "docId " + docId + " outside 0.." + (count - 1));
        }
        int start = offsets.readInt((long) docId * Integer.BYTES);
        int end = offsets.readInt((long) (docId + 1) * Integer.BYTES);
        // The stored run ends with the newline that separates it from the next name.
        return names.readBytes(start, end - start - 1);
    }

    /**
     * Reports every document in this segment carrying a name.
     *
     * <p>Every, not the first: a segment merged from others may hold a name twice, and an erasure
     * that stopped at one copy would leave the other readable under a name its caller was told had
     * been removed.</p>
     *
     * @param key  The name to look for, UTF-8 encoded
     * @param sink Called with each matching document id
     */
    void find(byte[] key, IntConsumer sink) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        long hash = XxHash64.hash(key, 0, key.length);
        int fingerprint = fingerprintOf(hash);
        int slot = (int) (hash & mask);
        // The table is never more than half full, so an empty slot is always reachable and this
        // bound is a guard against a corrupt file rather than the normal exit.
        for (int probe = 0; probe <= mask; probe++) {
            long at = (long) slot * SLOT_BYTES;
            int stored = map.readInt(at);
            if (stored == 0) {
                return;
            }
            if (map.readInt(at + Integer.BYTES) == fingerprint && Arrays.equals(rawAt(stored - 1), key)) {
                sink.accept(stored - 1);
            }
            slot = (slot + 1) & mask;
        }
    }

    /** Whether any document here carries this name. */
    boolean contains(String key) {
        boolean[] found = {false};
        find(key.getBytes(StandardCharsets.UTF_8), doc -> found[0] = true);
        return found[0];
    }

    @Override
    public void close() {
        names.close();
        offsets.close();
        map.close();
    }

    /** Removes every file for a segment. */
    static void delete(Path directory, String segment) throws IOException {
        Files.deleteIfExists(directory.resolve(segment + KEYS_SUFFIX));
        Files.deleteIfExists(directory.resolve(segment + INDEX_SUFFIX));
        Files.deleteIfExists(directory.resolve(segment + MAP_SUFFIX));
        Files.deleteIfExists(directory.resolve(segment + MAP_SUFFIX + ".tmp"));
    }

    /** Slots for a document count: a power of two, at least twice as many as there are documents. */
    private static int capacityFor(int count) {
        if (count > MAX_DOCUMENTS) {
            throw new IllegalArgumentException(
                "A segment of " + count + " documents exceeds the addressable " + MAX_DOCUMENTS);
        }
        int capacity = MIN_SLOTS;
        while (capacity < 2 * count) {
            capacity <<= 1;
        }
        return capacity;
    }

    /** The half of a name's hash the table stores, to reject a wrong slot without reading it. */
    private static int fingerprintOf(long hash) {
        return (int) (hash >>> Integer.SIZE);
    }

    /**
     * Builds the lookup table for a segment whose names are already written.
     *
     * <p>Written to a temporary file and renamed, so a table under its real name is a complete
     * one. A crash in the middle leaves the temporary file, which the next open overwrites.</p>
     */
    private static void writeMap(Path table, MappedFile names, MappedFile offsets, int count)
            throws IOException {
        int capacity = capacityFor(count);
        long bytes = (long) capacity * SLOT_BYTES;
        Path temp = table.resolveSibling(table.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        int mask = capacity - 1;
        byte[] scratch = new byte[64];
        try (MappedFile building = MappedFile.create(temp, bytes)) {
            for (int doc = 0; doc < count; doc++) {
                int start = offsets.readInt((long) doc * Integer.BYTES);
                int end = offsets.readInt((long) (doc + 1) * Integer.BYTES);
                int length = end - start - 1;
                if (length > scratch.length) {
                    scratch = new byte[length];
                }
                names.read(start, scratch, 0, length);
                long hash = XxHash64.hash(scratch, 0, length);
                int slot = (int) (hash & mask);
                while (building.readInt((long) slot * SLOT_BYTES) != 0) {
                    slot = (slot + 1) & mask;
                }
                building.writeInt((long) slot * SLOT_BYTES, doc + 1);
                building.writeInt((long) slot * SLOT_BYTES + Integer.BYTES, fingerprintOf(hash));
            }
            building.force();
        }
        // A writable mapping is rounded up to a page, so the file is longer than the table.
        // Cutting it back is what lets the capacity be read from the length rather than stored
        // in a header that could disagree with it.
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.truncate(bytes);
            channel.force(true);
        }
        Files.move(temp, table,
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Writes a segment's names one at a time.
     *
     * <p>Streaming rather than taking a collection, because the caller that has the most names to
     * write is compaction, and requiring it to hold them all would put back the heap cost this
     * class exists to remove.</p>
     */
    static final class Writer implements AutoCloseable {

        private final Path directory;
        private final String segment;
        private final OutputStream names;
        private final OutputStream offsets;
        private int position;
        private int written;
        private boolean finished;
        private boolean mapped;

        Writer(Path directory, String segment) throws IOException {
            this.directory = directory;
            this.segment = segment;
            this.names = Files.newOutputStream(directory.resolve(segment + KEYS_SUFFIX),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
            try {
                this.offsets = Files.newOutputStream(directory.resolve(segment + INDEX_SUFFIX),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                names.close();
                throw e;
            }
        }

        /** Appends one name, in document order. */
        void add(String key) throws IOException {
            byte[] bytes = DocumentKeys.require(key).getBytes(StandardCharsets.UTF_8);
            writeOffset(position);
            names.write(bytes);
            names.write('\n');
            position += bytes.length + 1;
            written++;
        }

        /** Names written so far. */
        int count() {
            return written;
        }

        /** Writes the terminating offset. Required before the files can be read. */
        void finish() throws IOException {
            if (!finished) {
                writeOffset(position);
                finished = true;
            }
        }

        private void writeOffset(int value) throws IOException {
            // Big-endian, the order MappedFile reads in.
            offsets.write(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
        }

        /**
         * Closes the name files and builds the lookup table over them.
         *
         * <p>The table is built here rather than as names arrive because its size depends on how
         * many there turn out to be, which a streaming writer only knows once it is done. Reading
         * the names back is one sequential pass over a file that was just written, so it is still
         * in the page cache.</p>
         */
        @Override
        public void close() throws IOException {
            try (OutputStream a = names; OutputStream b = offsets) {
                finish();
            }
            if (mapped) {
                return;
            }
            mapped = true;
            try (MappedFile keyFile = MappedFile.open(directory.resolve(segment + KEYS_SUFFIX));
                 MappedFile indexFile = MappedFile.open(directory.resolve(segment + INDEX_SUFFIX))) {
                writeMap(directory.resolve(segment + MAP_SUFFIX), keyFile, indexFile, written);
            }
        }
    }
}
