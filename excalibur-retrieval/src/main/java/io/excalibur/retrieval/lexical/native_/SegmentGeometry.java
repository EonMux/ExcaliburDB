package io.excalibur.retrieval.lexical.native_;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Measures a segment's files against the shape its metadata claims for them.
 *
 * <h2>Why a length check is the whole defence</h2>
 * <p>Every one of the six columns is addressed by arithmetic on three numbers in
 * {@code index.meta}, and none of them carries a length, a count or a checksum of its own. A
 * column that lost its tail is therefore not a read error: a shorter {@code post.off} is a
 * postings row that ends early, a shorter {@code post.blk} is a row whose blocks are simply not
 * there, and a {@code post.off} of the right length full of zeroes is an index in which every
 * term resolves and matches nothing. All of those decode, and all of them answer a query with
 * part of a segment while reporting a complete one.</p>
 *
 * <p>So the columns are measured before any of them is mapped. The three that depend only on the
 * counts are exact; the three that depend on each other are measured against where their own
 * last record ends, which is the only length they can legitimately have. It is cheap - six
 * lengths and three short reads, once in a segment's life - and it converts every whole class of
 * silent truncation into a refusal to open.</p>
 *
 * <p>It is not a checksum and does not pretend to be: bytes that were rewritten rather than lost
 * will pass. What it removes is the failure that costs nothing to produce and everything to
 * detect, which is a file that stopped short.</p>
 */
final class SegmentGeometry {

    private SegmentGeometry() {
        throw new AssertionError("Utility class");
    }

    /**
     * Refuses a segment whose files disagree with its metadata.
     *
     * @param directory The index directory
     * @param meta      What that directory says it holds
     * @throws IOException if any column is not the length its metadata describes
     */
    static void verify(Path directory, IndexMeta meta) throws IOException {
        int terms = meta.termCount();
        int documents = meta.docCount();
        int slots = meta.hashSlots();
        if (terms < 0 || documents < 0) {
            throw corrupt(directory, "it declares " + terms + " term(s) and " + documents
                + " document(s)");
        }
        if (slots <= 0 || Integer.bitCount(slots) != 1) {
            throw corrupt(directory, "it declares " + slots + " hash slot(s), and the table is "
                + "probed with a mask that only a positive power of two produces");
        }
        requireLength(directory, SegmentFiles.TERMS_HASH,
            (long) slots * PostingsWriter.SLOT_BYTES);
        requireLength(directory, SegmentFiles.POST_OFF,
            (long) (terms + 1) * Integer.BYTES + (long) terms * Long.BYTES);
        requireLength(directory, SegmentFiles.DOCS_LEN, (long) documents * Integer.BYTES);
        verifyBlocks(directory, terms);
        verifyDictionary(directory, terms);
    }

    /** The block column against the row index, and the payload against the last block. */
    private static void verifyBlocks(Path directory, int terms) throws IOException {
        Path rows = directory.resolve(SegmentFiles.POST_OFF);
        if (terms > 0 && readInt(rows, 0) != 0) {
            throw corrupt(directory, "its first postings row does not start at block zero");
        }
        long blocks = readInt(rows, (long) terms * Integer.BYTES) & 0xFFFFFFFFL;
        requireLength(directory, SegmentFiles.POST_BLK, blocks * BlockLayout.RECORD_BYTES);
        requireLength(directory, SegmentFiles.POST_DAT, payloadEnd(directory, blocks));
    }

    /**
     * Where the last block's packed bytes end, which is the whole payload.
     *
     * <p>Read from the block header rather than summed over every block: the headers are written
     * with a running offset, so the last one plus its own width is the total by construction,
     * and a segment of a hundred million blocks is not worth a pass to learn the same number.</p>
     */
    private static long payloadEnd(Path directory, long blocks) throws IOException {
        if (blocks == 0) {
            return 0;
        }
        ByteBuffer record = read(directory.resolve(SegmentFiles.POST_BLK),
            (blocks - 1) * BlockLayout.RECORD_BYTES, BlockLayout.RECORD_BYTES);
        long offset = record.getLong(BlockLayout.OFFSET_DATA);
        int count = record.getShort(BlockLayout.OFFSET_COUNT) & 0xFFFF;
        int docBits = record.get(BlockLayout.OFFSET_DOC_BITS) & 0xFF;
        int tfBits = record.get(BlockLayout.OFFSET_TF_BITS) & 0xFF;
        if (offset < 0 || count < 1 || count > BlockLayout.BLOCK_SIZE
                || !packable(docBits) || !packable(tfBits)) {
            throw corrupt(directory, "its last postings block records " + count + " posting(s) "
                + "at byte " + offset + ", packed " + docBits + " and " + tfBits + " bits wide, "
                + "which is not a block any writer of this format produces");
        }
        return offset + BitPack.packedBytes(count, docBits) + BitPack.packedBytes(count, tfBits);
    }

    /** The dictionary against the offset its last term is recorded at. */
    private static void verifyDictionary(Path directory, int terms) throws IOException {
        if (terms == 0) {
            requireLength(directory, SegmentFiles.TERMS_DICT, 0);
            return;
        }
        Path dictionary = directory.resolve(SegmentFiles.TERMS_DICT);
        long last = readLong(directory.resolve(SegmentFiles.POST_OFF),
            (long) (terms + 1) * Integer.BYTES + (long) (terms - 1) * Long.BYTES);
        long size = Files.size(dictionary);
        if (last < 0 || last + Integer.BYTES > size) {
            throw corrupt(directory, "its last term is recorded at byte " + last + " of a "
                + size + " byte dictionary");
        }
        requireLength(directory, SegmentFiles.TERMS_DICT,
            last + Integer.BYTES + readInt(dictionary, last));
    }

    private static void requireLength(Path directory, String column, long expected)
            throws IOException {
        long actual = Files.size(directory.resolve(column));
        if (actual != expected) {
            throw corrupt(directory, column + " is " + actual + " bytes where the metadata "
                + "describes " + expected);
        }
    }

    private static boolean packable(int bits) {
        return bits >= 1 && bits <= Integer.SIZE;
    }

    private static int readInt(Path file, long position) throws IOException {
        return read(file, position, Integer.BYTES).getInt(0);
    }

    private static long readLong(Path file, long position) throws IOException {
        return read(file, position, Long.BYTES).getLong(0);
    }

    /** Big-endian, the order every one of these files is written and mapped in. */
    private static ByteBuffer read(Path file, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, position + buffer.position()) < 0) {
                    throw new IOException(file + " ends before byte " + (position + length)
                        + ", which the index metadata says it holds");
                }
            }
        }
        return buffer.flip();
    }

    private static IOException corrupt(Path directory, String detail) {
        return new IOException("lexical segment " + directory + " does not match its own "
            + "metadata: " + detail + ". It was left by an interrupted build or has lost bytes "
            + "since, and opening it would answer queries from part of a segment without saying "
            + "so; restore it from a backup or rebuild it.");
    }
}
