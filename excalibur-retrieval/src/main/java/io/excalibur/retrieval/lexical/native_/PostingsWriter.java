package io.excalibur.retrieval.lexical.native_;

import io.excalibur.core.hash.XxHash64;
import io.excalibur.retrieval.lexical.Bm25Parameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a {@link PostingsIndex} from documents, in two passes over an in-memory term map.
 *
 * <h2>Why the postings are inverted at the end</h2>
 * <p>Documents arrive one at a time and every one of them touches terms scattered across the whole
 * dictionary, so writing postings as documents arrive would be a random write per term per
 * document. Accumulating per-term lists and emitting them in term order at {@link #write} turns
 * the whole build into one sequential pass over each file, which is what makes it fast enough to
 * be worth doing on the write path at all.</p>
 *
 * <h2>What this costs</h2>
 * <p>The accumulated postings live on the heap until written, so a build is bounded by memory
 * rather than by corpus size. Very large corpora want this run per shard and the shards searched
 * together - which is the arrangement the engine's segments already impose, and the reason the
 * term-membership filter is hierarchical.</p>
 */
public final class PostingsWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostingsWriter.class);

    private static final double HASH_LOAD = 0.6;

    /** Bytes per term-hash slot: a 32-bit hash tag and a 32-bit term id. */
    static final int SLOT_BYTES = 8;

    /** Postings per term, keyed by term. Values are (docId, tf) pairs held as parallel lists. */
    private final Map<String, TermPostings> terms = new HashMap<>();
    private final List<Integer> docLengths = new ArrayList<>();
    private final Bm25Parameters bm25;
    private long totalTokens;

    /** A writer producing an index scored with the default BM25 pair. */
    public PostingsWriter() {
        this(Bm25Parameters.DEFAULT);
    }

    /**
     * A writer producing an index scored with a chosen BM25 pair.
     *
     * <p>The pair is recorded in the index and is the only one it will open under. Changing it
     * changes every score and therefore every ranking the corpus can produce, including the
     * thresholds a deployment has calibrated against them, so it is a property of the corpus
     * rather than a query-time preference - and an index that opened silently under a different
     * pair would answer differently with nothing to show for it.
     */
    public PostingsWriter(Bm25Parameters bm25) {
        this.bm25 = Objects.requireNonNull(bm25, "bm25 must not be null");
    }

    private static final class TermPostings {
        int[] docs = new int[4];
        short[] freqs = new short[4];
        int size;

        void add(int doc, int freq) {
            if (size == docs.length) {
                docs = java.util.Arrays.copyOf(docs, size * 2);
                freqs = java.util.Arrays.copyOf(freqs, size * 2);
            }
            docs[size] = doc;
            freqs[size] = (short) Math.min(freq, 255);
            size++;
        }
    }

    /**
     * Adds one document and returns the id assigned to it.
     *
     * <p>Ids are dense and ascending, so the caller's own identifiers can be kept in a parallel
     * list rather than a map.</p>
     *
     * @param text The document text
     * @return The document id
     */
    public int add(String text) {
        int docId = docLengths.size();
        List<String> tokens = Tokens.of(text);
        docLengths.add(tokens.size());
        totalTokens += tokens.size();
        if (tokens.isEmpty()) {
            return docId;
        }
        // Counted per document first, so a term appearing many times becomes one posting with a
        // frequency rather than many postings that would each have to be merged later.
        Map<String, Integer> counts = new HashMap<>();
        for (String token : tokens) {
            counts.merge(token, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            terms.computeIfAbsent(entry.getKey(), k -> new TermPostings())
                .add(docId, entry.getValue());
        }
        return docId;
    }

    /**
     * Takes document lengths from an index being merged in.
     *
     * @param lengths Lengths in that index's document order
     */
    void adoptDocumentLengths(int[] lengths) {
        for (int length : lengths) {
            docLengths.add(length);
            totalTokens += length;
        }
    }

    /**
     * Takes one term's postings from an index being merged in.
     *
     * @param term      The term
     * @param docs      Doc ids in that index's own numbering, ascending
     * @param freqs     Frequencies, parallel to {@code docs}
     * @param docOffset Added to each doc id to place it in the merged numbering
     */
    void adoptRow(String term, int[] docs, int[] freqs, int docOffset) {
        TermPostings postings = terms.computeIfAbsent(term, k -> new TermPostings());
        for (int i = 0; i < docs.length; i++) {
            postings.add(docs[i] + docOffset, freqs[i]);
        }
    }

    /** Documents added so far. */
    public int docCount() {
        return docLengths.size();
    }

    /** Distinct terms seen so far. */
    public int termCount() {
        return terms.size();
    }

    /**
     * Writes the index.
     *
     * @param directory Where to write; created if absent
     * @throws IOException if any file cannot be written
     */
    public void write(Path directory) throws IOException {
        Files.createDirectories(directory);
        List<String> sorted = new ArrayList<>(terms.keySet());
        java.util.Collections.sort(sorted);

        int termCount = sorted.size();
        int docCount = docLengths.size();
        long postingCount = 0;
        for (TermPostings postings : terms.values()) {
            postingCount += postings.size;
        }

        // Dictionary, and the offset of every term within it.
        long[] termOffsets = new long[termCount];
        try (DataOutputStream dict = stream(directory.resolve("terms.dict"))) {
            long cursor = 0;
            for (int i = 0; i < termCount; i++) {
                byte[] bytes = sorted.get(i).getBytes(StandardCharsets.UTF_8);
                termOffsets[i] = cursor;
                dict.writeInt(bytes.length);
                dict.write(bytes);
                cursor += 4 + bytes.length;
            }
        }

        // CSR row offsets, followed by the dictionary offsets in the same file: both are
        // term-indexed longs, and one mapping is cheaper than two.

        // Postings are emitted as fixed-count blocks, each carrying enough metadata to be
        // skipped without being read. See BlockLayout for the record shape and why each field
        // is there.
        long[] blockStarts = new long[termCount + 1];
        try (DataOutputStream blocks = stream(directory.resolve("post.blk"));
             DataOutputStream data = stream(directory.resolve("post.dat"))) {
            long blockIndex = 0;
            long dataOffset = 0;
            int[] gaps = new int[BlockLayout.BLOCK_SIZE];
            int[] freqs = new int[BlockLayout.BLOCK_SIZE];
            byte[] scratch = new byte[BlockLayout.BLOCK_SIZE * 8 + 16];

            for (int t = 0; t < termCount; t++) {
                blockStarts[t] = blockIndex;
                TermPostings postings = terms.get(sorted.get(t));
                for (int start = 0; start < postings.size; start += BlockLayout.BLOCK_SIZE) {
                    int count = Math.min(BlockLayout.BLOCK_SIZE, postings.size - start);
                    int firstDoc = postings.docs[start];
                    int previous = firstDoc;
                    int maxGap = 0;
                    int maxFreq = 0;
                    int minLength = Integer.MAX_VALUE;
                    for (int i = 0; i < count; i++) {
                        int doc = postings.docs[start + i];
                        int gap = i == 0 ? 0 : doc - previous;
                        previous = doc;
                        gaps[i] = gap;
                        maxGap = Math.max(maxGap, gap);
                        int tf = postings.freqs[start + i] & 0xFF;
                        freqs[i] = tf;
                        maxFreq = Math.max(maxFreq, tf);
                        // The two ingredients the scorer's impact bound is computed from. They
                        // are stored rather than the bound itself because the bound depends on
                        // the mean document length of the collection, which a segment written on
                        // its own does not know; see BlockLayout.
                        minLength = Math.min(minLength, docLengths.get(doc));
                    }
                    int docBits = BitPack.bitsFor(maxGap);
                    int tfBits = BitPack.bitsFor(maxFreq);
                    int docBytes = BitPack.pack(gaps, count, docBits, scratch, 0);
                    int tfBytes = BitPack.pack(freqs, count, tfBits, scratch, docBytes);
                    data.write(scratch, 0, docBytes + tfBytes);

                    blocks.writeInt(firstDoc);
                    blocks.writeInt(postings.docs[start + count - 1]);
                    BlockLayout.writeImpactFields(blocks, maxFreq, minLength);
                    blocks.writeLong(dataOffset);
                    blocks.writeShort(count);
                    blocks.write(docBits);
                    blocks.write(tfBits);

                    dataOffset += docBytes + tfBytes;
                    blockIndex++;
                }
            }
            blockStarts[termCount] = blockIndex;
        }

        try (DataOutputStream lengths = stream(directory.resolve("docs.len"))) {
            for (int length : docLengths) {
                lengths.writeInt(length);
            }
        }

        // Written after the blocks, because a term's row is now a range of blocks and the
        // count is only known once they have been emitted.
        // Block indices as int32 and dictionary offsets as int64. A corpus would need over two
        // billion postings blocks to overflow the first, which is two hundred billion postings;
        // the dictionary genuinely can exceed 2 GiB and is left wide.
        try (DataOutputStream off = stream(directory.resolve("post.off"))) {
            for (int t = 0; t <= termCount; t++) {
                off.writeInt((int) blockStarts[t]);
            }
            for (long offset : termOffsets) {
                writeLong(off, offset);
            }
        }

        int hashSlots = Integer.highestOneBit(
            Math.max(16, (int) Math.min(Integer.MAX_VALUE / 2, termCount / HASH_LOAD))) * 2;
        writeTermHash(directory.resolve("terms.hash"), sorted, hashSlots);

        double averageDocLength = docCount == 0 ? 1.0 : (double) totalTokens / docCount;
        IndexMeta.current(termCount, docCount, hashSlots, averageDocLength, bm25).write(directory);

        LOGGER.info("Wrote lexical index: {} terms, {} docs, {} postings, {} hash slots",
            termCount, docCount, postingCount, hashSlots);
    }

    /** Open-addressed, linear-probed table from term hash to term id. */
    private void writeTermHash(Path path, List<String> sorted, int slots) throws IOException {
        // Eight bytes a slot, not sixteen. The stored hash is only a cheap rejection before the
        // dictionary comparison that decides the match, so the upper half of a 64-bit hash earns
        // nothing: a collision in the retained 32 bits costs one string compare, while carrying
        // the other 32 cost 8 MiB on this corpus alone.
        ByteBuffer table = ByteBuffer.allocate(slots * SLOT_BYTES).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < slots; i++) {
            table.putInt(i * SLOT_BYTES, 0);
            table.putInt(i * SLOT_BYTES + 4, -1);
        }
        int mask = slots - 1;
        for (int id = 0; id < sorted.size(); id++) {
            long hash = XxHash64.hash(sorted.get(id).getBytes(StandardCharsets.UTF_8));
            int slot = (int) (hash & mask);
            while (table.getInt(slot * SLOT_BYTES + 4) >= 0) {
                slot = (slot + 1) & mask;
            }
            table.putInt(slot * SLOT_BYTES, (int) (hash >>> 32));
            table.putInt(slot * SLOT_BYTES + 4, id);
        }
        Files.write(path, table.array());
    }

    private static DataOutputStream stream(Path path) throws IOException {
        OutputStream out = Files.newOutputStream(path);
        return new DataOutputStream(new BufferedOutputStream(out, 1 << 20));
    }

    // MappedFile reads big-endian throughout, and DataOutputStream writes big-endian, so the
    // two agree without any swapping. Reversing here produced a length field of 0x01000000 for a
    // one-byte term and an out-of-bounds read on the first lookup.
    private static void writeLong(DataOutputStream out, long value) throws IOException {
        out.writeLong(value);
    }
}
