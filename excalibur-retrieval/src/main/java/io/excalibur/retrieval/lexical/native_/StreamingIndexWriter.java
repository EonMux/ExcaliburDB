package io.excalibur.retrieval.lexical.native_;

import io.excalibur.core.hash.XxHash64;
import io.excalibur.retrieval.lexical.Bm25Parameters;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Writes an index one term at a time, holding no postings beyond the term in hand.
 *
 * <h2>Why streaming</h2>
 * <p>The accumulating writer keeps every postings row until it can emit them in term order, which
 * is what bounds a build by the heap. A merge already visits terms in sorted order, so it has no
 * need to accumulate anything: each term can be encoded and appended the moment it is complete.
 * That is the difference between a merge that scales with the corpus and one that merely moves
 * the memory ceiling to a later stage - which is precisely what the first version of the merger
 * did, and it failed on a 793 MiB corpus in a 1 GiB heap after successfully flushing 38 pieces.</p>
 *
 * <h2>What is still held</h2>
 * <p>Twelve bytes a term: where its row starts and where its dictionary entry starts. The term
 * strings themselves are not kept - the hash table is built at the end by reading back the
 * dictionary that was just written, which is sequential and costs no memory.</p>
 *
 * <p>Terms must be supplied in ascending order; the dictionary and the hash table both depend on
 * it, and it is free for a merge to provide.</p>
 */
public final class StreamingIndexWriter implements AutoCloseable {

    private static final double HASH_LOAD = 0.6;

    private final Path directory;
    private final int[] docLengths;
    private final double averageDocLength;
    private final Bm25Parameters bm25;

    private final DataOutputStream dict;
    private final DataOutputStream blocks;
    private final DataOutputStream data;

    private long[] blockStart = new long[1024];
    private long[] dictOffset = new long[1024];
    private int termCount;
    private long dictCursor;
    private long blockCursor;
    private long dataCursor;
    private String previousTerm;

    private final int[] gaps = new int[BlockLayout.BLOCK_SIZE];
    private final int[] freqScratch = new int[BlockLayout.BLOCK_SIZE];
    private final byte[] packScratch = new byte[BlockLayout.BLOCK_SIZE * 8 + 16];

    /**
     * @param directory  Where the index is written
     * @param docLengths Length of every document, in id order
     */
    public StreamingIndexWriter(Path directory, int[] docLengths) throws IOException {
        this(directory, docLengths, Bm25Parameters.DEFAULT);
    }

    /**
     * @param directory  Where the index is written
     * @param docLengths Length of every document, in id order
     * @param bm25       The pair recorded in the finished index, and therefore the only pair it
     *                   will open under
     */
    public StreamingIndexWriter(Path directory, int[] docLengths, Bm25Parameters bm25)
            throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.docLengths = Objects.requireNonNull(docLengths, "docLengths");
        this.bm25 = Objects.requireNonNull(bm25, "bm25");
        Files.createDirectories(directory);
        long tokens = 0;
        for (int length : docLengths) {
            tokens += length;
        }
        this.averageDocLength = docLengths.length == 0 ? 1.0 : (double) tokens / docLengths.length;
        this.dict = stream(directory.resolve("terms.dict"));
        this.blocks = stream(directory.resolve("post.blk"));
        this.data = stream(directory.resolve("post.dat"));
    }

    /**
     * Appends one term's postings.
     *
     * @param term  The term, strictly greater than the previous one
     * @param docs  Doc ids, ascending
     * @param freqs Frequencies, parallel to {@code docs}
     * @param count How many postings
     */
    public void addTerm(String term, int[] docs, int[] freqs, int count) throws IOException {
        if (previousTerm != null && term.compareTo(previousTerm) <= 0) {
            throw new IllegalArgumentException(
                "terms must ascend: '" + previousTerm + "' then '" + term + "'");
        }
        previousTerm = term;
        if (termCount == blockStart.length) {
            blockStart = java.util.Arrays.copyOf(blockStart, termCount * 2);
            dictOffset = java.util.Arrays.copyOf(dictOffset, termCount * 2);
        }
        blockStart[termCount] = blockCursor;
        dictOffset[termCount] = dictCursor;
        termCount++;

        byte[] bytes = term.getBytes(StandardCharsets.UTF_8);
        dict.writeInt(bytes.length);
        dict.write(bytes);
        dictCursor += 4 + bytes.length;

        for (int start = 0; start < count; start += BlockLayout.BLOCK_SIZE) {
            int size = Math.min(BlockLayout.BLOCK_SIZE, count - start);
            int firstDoc = docs[start];
            int previous = firstDoc;
            int maxGap = 0;
            int maxFreq = 0;
            int minLength = Integer.MAX_VALUE;
            for (int i = 0; i < size; i++) {
                int doc = docs[start + i];
                int gap = i == 0 ? 0 : doc - previous;
                previous = doc;
                gaps[i] = gap;
                maxGap = Math.max(maxGap, gap);
                int tf = freqs[start + i];
                freqScratch[i] = tf;
                maxFreq = Math.max(maxFreq, tf);
                minLength = Math.min(minLength, docLengths[doc]);
            }
            int docBits = BitPack.bitsFor(maxGap);
            int tfBits = BitPack.bitsFor(maxFreq);
            int docBytes = BitPack.pack(gaps, size, docBits, packScratch, 0);
            int tfBytes = BitPack.pack(freqScratch, size, tfBits, packScratch, docBytes);
            data.write(packScratch, 0, docBytes + tfBytes);

            blocks.writeInt(firstDoc);
            blocks.writeInt(docs[start + size - 1]);
            BlockLayout.writeImpactFields(blocks, maxFreq, minLength);
            blocks.writeLong(dataCursor);
            blocks.writeShort(size);
            blocks.write(docBits);
            blocks.write(tfBits);

            dataCursor += docBytes + tfBytes;
            blockCursor++;
        }
    }

    /** Writes the remaining columns and closes the streams. */
    public void finish() throws IOException {
        dict.close();
        blocks.close();
        data.close();

        try (DataOutputStream off = stream(directory.resolve("post.off"))) {
            for (int t = 0; t < termCount; t++) {
                off.writeInt((int) blockStart[t]);
            }
            off.writeInt((int) blockCursor);
            for (int t = 0; t < termCount; t++) {
                off.writeLong(dictOffset[t]);
            }
        }

        try (DataOutputStream lengths = stream(directory.resolve("docs.len"))) {
            for (int length : docLengths) {
                lengths.writeInt(length);
            }
        }

        int slots = Integer.highestOneBit(
            Math.max(16, (int) Math.min(Integer.MAX_VALUE / 2, termCount / HASH_LOAD))) * 2;
        writeHash(slots);

        IndexMeta.current(termCount, docLengths.length, slots, averageDocLength, bm25)
            .write(directory);
    }

    /** Builds the term hash by reading back the dictionary, so no term strings are retained. */
    private void writeHash(int slots) throws IOException {
        ByteBuffer table = ByteBuffer.allocate(slots * PostingsWriter.SLOT_BYTES)
            .order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < slots; i++) {
            table.putInt(i * PostingsWriter.SLOT_BYTES, 0);
            table.putInt(i * PostingsWriter.SLOT_BYTES + 4, -1);
        }
        int mask = slots - 1;
        byte[] dictBytes = Files.readAllBytes(directory.resolve("terms.dict"));
        int cursor = 0;
        for (int id = 0; id < termCount; id++) {
            int length = ((dictBytes[cursor] & 0xFF) << 24) | ((dictBytes[cursor + 1] & 0xFF) << 16)
                       | ((dictBytes[cursor + 2] & 0xFF) << 8) | (dictBytes[cursor + 3] & 0xFF);
            long hash = XxHash64.hash(
                java.util.Arrays.copyOfRange(dictBytes, cursor + 4, cursor + 4 + length));
            cursor += 4 + length;
            int slot = (int) (hash & mask);
            while (table.getInt(slot * PostingsWriter.SLOT_BYTES + 4) >= 0) {
                slot = (slot + 1) & mask;
            }
            table.putInt(slot * PostingsWriter.SLOT_BYTES, (int) (hash >>> 32));
            table.putInt(slot * PostingsWriter.SLOT_BYTES + 4, id);
        }
        Files.write(directory.resolve("terms.hash"), table.array());
    }

    @Override
    public void close() throws IOException {
        dict.close();
        blocks.close();
        data.close();
    }

    private static DataOutputStream stream(Path path) throws IOException {
        return new DataOutputStream(
            new BufferedOutputStream(Files.newOutputStream(path), 1 << 20));
    }
}
