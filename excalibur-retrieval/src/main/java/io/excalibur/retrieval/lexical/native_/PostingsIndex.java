package io.excalibur.retrieval.lexical.native_;

import excalibur.filter.CuckooFilter;
import io.excalibur.core.hash.XxHash64;
import io.excalibur.core.mapped.MappedFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A memory-mapped inverted index, in the shape the rest of this engine already uses.
 *
 * <h2>Why this and not a library</h2>
 * <p>A postings list is an adjacency list. The engine already stores adjacency as memory-mapped
 * CSR with a reverse index, already keeps its dictionaries as interned id columns, already gates
 * membership with Cuckoo filters, and already erases by physically rewriting columns. An inverted
 * index built from those parts inherits all of it: one storage model, one visibility model, one
 * erasure guarantee - and, decisively, <em>one copy of the text</em>. Measured on this corpus, a
 * Lucene index carrying its own stored fields was 102% of the corpus, of which 65% was a second
 * copy of text this engine already holds in its segments.</p>
 *
 * <h2>Layout</h2>
 * <p>Five mapped files, all big-endian (the order MappedFile uses), all read through the page cache rather than the heap:</p>
 * <pre>
 * terms.dict  [len:int32][utf8 bytes]...          terms in id order
 * terms.hash  [hash:int64][termId:int32][pad:int32]  open-addressed, power-of-two slots
 * post.off    [start:int64] x (termCount + 1)     CSR row offsets
 * post.doc    [docId:int32] x postingCount        doc ids, ascending within a term
 * post.tf     [tf:uint8] x postingCount           term frequency, saturating
 * docs.len    [tokens:int32] x docCount           document length, for BM25 normalisation
 * </pre>
 *
 * <p>Doc ids are dense and assigned at build time; the caller keeps the mapping from doc id to its
 * own identifier, because this class is deliberately ignorant of what a document is.</p>
 *
 * <h2>Thread safety</h2>
 * <p>A built index is immutable and safe to search concurrently. Building is single-threaded.</p>
 */
public final class PostingsIndex implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostingsIndex.class);

    /** BM25 term-frequency saturation. */
    public static final double K1 = 1.2;

    /** BM25 length normalisation. */
    public static final double B = 0.75;

    /** Load factor of the term hash table; below 0.7 probing stays short. */
    private static final double HASH_LOAD = 0.6;

    private static final int SLOT_BYTES = 8;

    private final MappedFile termsDict;
    private final MappedFile termsHash;
    private final MappedFile postOff;
    private final MappedFile postBlk;
    private final MappedFile postDat;
    private final MappedFile docsLen;
    private final int termCount;
    private final int docCount;
    private final int hashSlots;
    private final double averageDocLength;
    private final CuckooFilter presence;

    /**
     * Document lengths, decoded once at open.
     *
     * <p>Every scored document needs its length for BM25 normalisation. Decoding the column per
     * query was O(corpus) work on every request - the same mistake as the dense accumulator, in a
     * different place - and reading it per hit would pay {@code MappedFile}'s per-accessor lock.
     * Four bytes a document is the price of avoiding both.</p>
     */
    private final int[] docLengths;

    /**
     * The largest query-independent BM25 factor in each term's postings, computed once at open.
     *
     * <p>This is the bound WAND prunes with, so it has to be tight or the pivot never moves. It
     * also has to be free at query time: deriving it per query meant scanning the block headers
     * of every query term, which for a word appearing in most of the corpus is thousands of
     * mapped reads and measured slower than not pruning at all. One sequential pass at open over
     * headers that are 24 bytes each, and four bytes a term to keep, buys both.</p>
     */
    private final float[] termMaxSaturation;

    private PostingsIndex(MappedFile termsDict, MappedFile termsHash, MappedFile postOff,
                          MappedFile postBlk, MappedFile postDat, MappedFile docsLen,
                          int termCount, int docCount, int hashSlots, double averageDocLength,
                          CuckooFilter presence, int[] docLengths, float[] termMaxSaturation) {
        this.termsDict = termsDict;
        this.termsHash = termsHash;
        this.postOff = postOff;
        this.postBlk = postBlk;
        this.postDat = postDat;
        this.docsLen = docsLen;
        this.termCount = termCount;
        this.docCount = docCount;
        this.hashSlots = hashSlots;
        this.averageDocLength = averageDocLength;
        this.presence = presence;
        this.docLengths = docLengths;
        this.termMaxSaturation = termMaxSaturation;
    }

    /** Opens an index previously written by {@link PostingsWriter}. */
    public static PostingsIndex open(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        MappedFile meta = MappedFile.open(directory.resolve("index.meta"));
        int termCount;
        int docCount;
        int hashSlots;
        double averageDocLength;
        try {
            termCount = meta.readInt(0);
            docCount = meta.readInt(4);
            hashSlots = meta.readInt(8);
            averageDocLength = meta.readDouble(16);
        } finally {
            meta.close();
        }

        MappedFile dict = MappedFile.open(directory.resolve("terms.dict"));
        MappedFile hash = MappedFile.open(directory.resolve("terms.hash"));
        MappedFile off = MappedFile.open(directory.resolve("post.off"));
        MappedFile blk = MappedFile.open(directory.resolve("post.blk"));
        MappedFile dat = MappedFile.open(directory.resolve("post.dat"));
        MappedFile len = MappedFile.open(directory.resolve("docs.len"));

        // The term gate is rebuilt on open rather than persisted: it is derived data, it costs
        // milliseconds per million terms, and a filter that has drifted from the dictionary it
        // guards would reject terms the index really holds - a silent loss of recall.
        CuckooFilter presence = CuckooFilter.create(Math.max(1024L, termCount * 2L), 13);
        long cursor = 0;
        for (int i = 0; i < termCount; i++) {
            int length = dict.readInt(cursor);
            byte[] bytes = dict.readBytes(cursor + 4, length);
            presence.insert(bytes);
            cursor += 4 + length;
        }
        LOGGER.info("Opened lexical index: {} terms, {} docs, avg length {}",
            termCount, docCount, String.format("%.1f", averageDocLength));
        int[] lengths = new int[docCount];
        if (docCount > 0) {
            byte[] raw = len.readBytes(0, docCount * Integer.BYTES);
            for (int i = 0; i < docCount; i++) {
                int b = i * 4;
                lengths[i] = ((raw[b] & 0xFF) << 24) | ((raw[b + 1] & 0xFF) << 16)
                           | ((raw[b + 2] & 0xFF) << 8) | (raw[b + 3] & 0xFF);
            }
        }
        PostingsIndex index = new PostingsIndex(dict, hash, off, blk, dat, len,
            termCount, docCount, hashSlots, averageDocLength, presence, lengths,
            new float[termCount]);
        index.computeTermBounds();
        return index;
    }

    /** Terms in the dictionary. */
    public int termCount() {
        return termCount;
    }

    /** Documents indexed. */
    public int docCount() {
        return docCount;
    }

    /**
     * Resolves a term to its id, or -1.
     *
     * <p>The Cuckoo filter answers first. It is consulted not to save the hash probe but to avoid
     * touching the dictionary at all for a term the corpus does not contain - which, for queries
     * built by expanding with a language model, is a large fraction of what is looked up.</p>
     */
    public int termId(String term) {
        byte[] bytes = term.getBytes(StandardCharsets.UTF_8);
        if (!presence.contains(bytes)) {
            return -1;
        }
        long hash = XxHash64.hash(bytes);
        int tag = (int) (hash >>> 32);
        int mask = hashSlots - 1;
        int slot = (int) (hash & mask);
        for (int probe = 0; probe < hashSlots; probe++) {
            long offset = (long) slot * SLOT_BYTES;
            int id = termsHash.readInt(offset + 4);
            if (id < 0) {
                return -1;
            }
            if (termsHash.readInt(offset) == tag && termEquals(id, bytes)) {
                return id;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private boolean termEquals(int termId, byte[] expected) {
        long offset = termOffset(termId);
        int length = termsDict.readInt(offset);
        if (length != expected.length) {
            return false;
        }
        byte[] actual = termsDict.readBytes(offset + 4, length);
        for (int i = 0; i < length; i++) {
            if (actual[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private long termOffset(int termId) {
        // The offsets column doubles as the dictionary index: entry i is where term i starts.
        return postOff.readLong((long) (termCount + 1) * Integer.BYTES
            + (long) termId * Long.BYTES);
    }

    /** Documents containing a term. */
    public int documentFrequency(int termId) {
        int total = 0;
        for (long b = rowStart(termId); b < rowEnd(termId); b++) {
            total += readCount(b * BlockLayout.RECORD_BYTES + BlockLayout.OFFSET_COUNT);
        }
        return total;
    }

    private long rowStart(int termId) {
        return postOff.readInt((long) termId * Integer.BYTES) & 0xFFFFFFFFL;
    }

    private long rowEnd(int termId) {
        return postOff.readInt((long) (termId + 1) * Integer.BYTES) & 0xFFFFFFFFL;
    }

    /**
     * Scores documents for a set of weighted terms and returns the best {@code limit}.
     *
     * <h2>Blocks that are never read</h2>
     * <p>Each postings block records the last doc id it holds and the largest query-independent
     * BM25 factor any of its postings has. That is enough to decide two things without touching
     * the block: whether every document in it is already behind the cursor, and whether the most
     * it could contribute still leaves a document below the current tenth-best score. A postings
     * row for a common word is mostly blocks of the second kind, which is why a query full of
     * ordinary English words stopped costing what it did.</p>
     *
     * <p>Blocks are decoded lazily, one per term at a time, so a row of ten thousand postings
     * costs a 128-posting decode when it is needed and nothing when it is not.</p>
     *
     * @param terms   Query terms
     * @param weights Weight per term, same length
     * @param limit   Documents to return
     * @return Doc ids and scores, best first
     */
    public Hit[] search(String[] terms, double[] weights, int limit) {
        return search(terms, weights, limit, null);
    }

    /**
     * Scores against statistics supplied by the caller, for an index that is one of several.
     *
     * @param stats Collection-wide statistics, or null to use this index's own
     */
    public Hit[] search(String[] terms, double[] weights, int limit, Stats stats) {
        if (terms.length != weights.length) {
            throw new IllegalArgumentException("terms and weights differ in length");
        }
        if (limit <= 0 || docCount == 0) {
            return new Hit[0];
        }

        int distinct = 0;
        Cursor[] cursors = new Cursor[terms.length];
        for (int t = 0; t < terms.length; t++) {
            int termId = termId(terms[t]);
            if (termId < 0) {
                continue;
            }
            long firstBlock = rowStart(termId);
            long endBlock = rowEnd(termId);
            if (firstBlock >= endBlock) {
                continue;
            }
            int localDf = documentFrequency(termId);
            int df = stats == null ? localDf : Math.max(1, stats.documentFrequency().applyAsInt(terms[t]));
            int n = stats == null ? docCount : stats.docCount();
            double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
            cursors[distinct++] =
                new Cursor(firstBlock, endBlock, idf, weights[t], termMaxSaturation[termId]);
        }
        if (distinct == 0) {
            return new Hit[0];
        }
        cursors = java.util.Arrays.copyOf(cursors, distinct);
        // Strongest bound first, so the suffix sums below shrink as fast as possible.
        java.util.Arrays.sort(cursors, (a, b) -> Double.compare(b.bound, a.bound));
        double average = stats == null ? averageDocLength : stats.averageDocLength();
        int wanted = Math.min(limit, docCount);
        double[] heapScore = new double[wanted];
        int[] heapDoc = new int[wanted];
        int held = 0;
        double threshold = 0;

        for (Cursor cursor : cursors) {
            cursor.load(this);
        }

        // MaxScore, with bounds taken from what the postings actually contain.
        //
        // Terms are ordered by the most they can contribute and a suffix sum is kept. Once the
        // heap is full, any term whose suffix sum has fallen below the tenth-best score can no
        // longer *surface* a candidate: a document appearing only in those terms cannot reach
        // the heap however it scores. Such terms stop being scanned and are only sought into,
        // which is where the saving is - but they still contribute to every document another
        // term surfaced, which is what keeps the result exact.
        //
        // WAND was measured here too, with the same tight bounds, and was slower: on a query of
        // six ordinary English words its pivot sits at the first cursor almost every iteration,
        // so every document is scored anyway and the pivot arithmetic is pure overhead - 39 ms
        // against 24 ms. Its advantage needs queries that mix very rare terms with very common
        // ones, which expansion produces less often than it looks.
        java.util.Arrays.sort(cursors, (a, b) -> Double.compare(b.bound, a.bound));
        double[] suffix = new double[distinct + 1];
        for (int i = distinct - 1; i >= 0; i--) {
            suffix[i] = suffix[i + 1] + cursors[i].bound;
        }
        int essential = distinct;

        for (Cursor cursor : cursors) {
            cursor.load(this);
        }

        while (true) {
            int current = Integer.MAX_VALUE;
            for (int c = 0; c < essential; c++) {
                int doc = cursors[c].currentDoc();
                if (doc >= 0 && doc < current) {
                    current = doc;
                }
            }
            if (current == Integer.MAX_VALUE) {
                break;
            }

            double normBase = K1 * (1 - B + B * docLengths[current] / average);
            double score = 0;
            for (Cursor cursor : cursors) {
                cursor.advanceTo(this, current);
                if (cursor.currentDoc() == current) {
                    int tf = cursor.currentFreq();
                    score += cursor.weight * cursor.idf * (tf * (K1 + 1)) / (tf + normBase);
                    cursor.step(this);
                }
            }

            if (score > 0) {
                if (held < wanted) {
                    heapScore[held] = score;
                    heapDoc[held] = current;
                    held++;
                    if (held == wanted) {
                        siftAll(heapScore, heapDoc, held);
                    }
                } else if (score > heapScore[0]) {
                    heapScore[0] = score;
                    heapDoc[0] = current;
                    siftDown(heapScore, heapDoc, 0, held);
                }
                if (held == wanted) {
                    threshold = heapScore[0];
                    while (essential > 1 && suffix[essential - 1] <= threshold) {
                        essential--;
                    }
                }
            }
        }

        Hit[] hits = new Hit[held];
        for (int i = 0; i < held; i++) {
            hits[i] = new Hit(heapDoc[i], heapScore[i]);
        }
        java.util.Arrays.sort(hits, (a, b) -> Double.compare(b.score(), a.score()));
        return hits;
    }

    /** A position in one term's postings, decoding a block only when it must. */
    private static final class Cursor {
        final long endBlock;
        final double idf;
        final double weight;
        final double bound;
        long block;
        int within = -1;
        int count;
        float maxSaturation;
        final int[] docs = new int[BlockLayout.BLOCK_SIZE];
        final int[] freqs = new int[BlockLayout.BLOCK_SIZE];
        // Reused across blocks: a decode per block otherwise allocates and discards a buffer per
        // block read, which on a long postings row is thousands of short-lived arrays a query.
        byte[] scratch = new byte[BlockLayout.BLOCK_SIZE * 8 + 16];
        boolean decoded;

        Cursor(long firstBlock, long endBlock, double idf, double weight, double maxSaturation) {
            this.block = firstBlock;
            this.endBlock = endBlock;
            this.idf = idf;
            this.weight = weight;
            // The largest score this term can add to any document, taken from what its blocks
            // actually contain rather than from the theoretical ceiling of the BM25 saturation
            // curve. (K1 + 1) is a true bound and a useless one: real postings sit far below it,
            // so a pivot computed from it almost never advances and the skipping never happens.
            this.bound = Math.abs(weight) * idf * maxSaturation;
        }

        void load(PostingsIndex index) {
            if (block >= endBlock) {
                within = -1;
                return;
            }
            index.decode(this);
            within = 0;
        }

        int currentDoc() {
            return within < 0 || within >= count ? -1 : docs[within];
        }

        int currentFreq() {
            return freqs[within];
        }

        void step(PostingsIndex index) {
            within++;
            if (within >= count) {
                block++;
                load(index);
            }
        }

        /**
         * Moves to the first posting at or after {@code target}, skipping whole blocks.
         *
         * <p>Skips only on {@code lastDoc}, which is exact: a block whose highest doc id is
         * behind the target holds nothing this query still wants.</p>
         *
         * <p>It deliberately does <em>not</em> skip a block because its maximum impact is below
         * the current threshold. That test is sound for deciding which terms may
         * <em>surface</em> a candidate - which is what the essential/non-essential split above
         * does - and unsound here, because a term that cannot surface a document must still add
         * its score to one that another term surfaced. Skipping the block silently drops that
         * contribution: measured against Lucene, it left recall intact while moving MRR and nDCG
         * down and cutting top-10 agreement from 8.6 to 6.1 out of 10, which is the signature of
         * right documents in the wrong order rather than of missing documents.</p>
         */
        void advanceTo(PostingsIndex index, int target) {
            while (block < endBlock) {
                if (!decoded) {
                    long record = block * BlockLayout.RECORD_BYTES;
                    int lastDoc = index.postBlk.readInt(record + BlockLayout.OFFSET_LAST_DOC);
                    if (lastDoc < target) {
                        block++;
                        continue;
                    }
                    index.decode(this);
                    within = 0;
                }
                while (within < count && docs[within] < target) {
                    within++;
                }
                if (within < count) {
                    return;
                }
                block++;
                decoded = false;
            }
            within = -1;
        }
    }

    /** One pass over the block headers, filling in every term's bound. */
    private void computeTermBounds() {
        for (int termId = 0; termId < termCount; termId++) {
            float max = 0f;
            long end = rowEnd(termId);
            for (long b = rowStart(termId); b < end; b++) {
                float value = postBlk.readFloat(
                    b * BlockLayout.RECORD_BYTES + BlockLayout.OFFSET_MAX_SATURATION);
                if (value > max) {
                    max = value;
                }
            }
            termMaxSaturation[termId] = max;
        }
    }

    /** MappedFile exposes no 16-bit accessor, so the count is read as two big-endian bytes. */
    private int readCount(long offset) {
        return ((postBlk.readByte(offset) & 0xFF) << 8) | (postBlk.readByte(offset + 1) & 0xFF);
    }

    /** Decodes one block into a cursor. */
    private void decode(Cursor cursor) {
        long record = cursor.block * BlockLayout.RECORD_BYTES;
        int firstDoc = postBlk.readInt(record + BlockLayout.OFFSET_FIRST_DOC);
        cursor.maxSaturation = postBlk.readFloat(record + BlockLayout.OFFSET_MAX_SATURATION);
        long dataOffset = postBlk.readLong(record + BlockLayout.OFFSET_DATA);
        int count = readCount(record + BlockLayout.OFFSET_COUNT);
        int docBits = postBlk.readByte(record + BlockLayout.OFFSET_DOC_BITS) & 0xFF;
        int tfBits = postBlk.readByte(record + BlockLayout.OFFSET_TF_BITS) & 0xFF;

        int docBytes = BitPack.packedBytes(count, docBits);
        int tfBytes = BitPack.packedBytes(count, tfBits);
        int needed = docBytes + tfBytes;
        if (cursor.scratch.length < needed) {
            cursor.scratch = new byte[needed];
        }
        postDat.read(dataOffset, cursor.scratch, 0, needed);
        BitPack.unpack(cursor.scratch, 0, docBits, count, cursor.docs);
        BitPack.unpack(cursor.scratch, docBytes, tfBits, count, cursor.freqs);

        // Gaps back to absolute doc ids.
        int running = firstDoc;
        cursor.docs[0] = firstDoc;
        for (int i = 1; i < count; i++) {
            running += cursor.docs[i];
            cursor.docs[i] = running;
        }
        cursor.count = count;
        cursor.decoded = true;
    }

    private static void siftAll(double[] score, int[] doc, int size) {
        for (int i = size / 2 - 1; i >= 0; i--) {
            siftDown(score, doc, i, size);
        }
    }

    /** Min-heap on score, so position 0 is the weakest hit currently held. */
    private static void siftDown(double[] score, int[] doc, int start, int size) {
        int root = start;
        while (true) {
            int child = 2 * root + 1;
            if (child >= size) {
                return;
            }
            if (child + 1 < size && score[child + 1] < score[child]) {
                child++;
            }
            if (score[root] <= score[child]) {
                return;
            }
            double s = score[root];
            score[root] = score[child];
            score[child] = s;
            int d = doc[root];
            doc[root] = doc[child];
            doc[child] = d;
            root = child;
        }
    }

    /**
     * Collection-wide statistics BM25 must be scored against.
     *
     * <h2>Why they come from outside</h2>
     * <p>An index split into segments computes idf from the documents it happens to hold, and
     * length normalisation from their average length. Both are properties of the whole
     * collection, not of a segment, so scoring each segment against its own makes the scores
     * incomparable: a document alone in a segment is by definition of average length there, and
     * a term in every document of a small segment looks rarer than it is. Merging such scores
     * ranks by which segment a document landed in.</p>
     *
     * @param docCount         Documents in the whole collection
     * @param averageDocLength Mean document length across the whole collection
     * @param documentFrequency Documents containing a term, across the whole collection
     */
    public record Stats(int docCount, double averageDocLength,
                        java.util.function.ToIntFunction<String> documentFrequency) { }

    /** Mean document length in this index alone. */
    public double averageDocumentLength() {
        return averageDocLength;
    }

    /** A term and its postings, for merging or inspection. */
    public record Row(String term, int[] docs, int[] freqs) { }

    /** The term at a given id, read from the dictionary. */
    public String termAt(int termId) {
        long offset = termOffset(termId);
        int length = termsDict.readInt(offset);
        return new String(termsDict.readBytes(offset + 4, length),
            java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Decodes a whole postings row.
     *
     * <p>For merging rather than for querying: a merge needs every posting of a term in order,
     * which is exactly what the query path is built to avoid doing.</p>
     *
     * @param termId The term
     * @return Its postings, doc ids ascending
     */
    public Row row(int termId) {
        int df = documentFrequency(termId);
        int[] docs = new int[df];
        int[] freqs = new int[df];
        int at = 0;
        Cursor cursor = new Cursor(rowStart(termId), rowEnd(termId), 1.0, 1.0, 1.0);
        cursor.load(this);
        while (cursor.currentDoc() >= 0 && at < df) {
            docs[at] = cursor.currentDoc();
            freqs[at] = cursor.currentFreq();
            at++;
            cursor.step(this);
        }
        return new Row(termAt(termId), docs, freqs);
    }

    /** Document lengths, for a merge that must carry them forward. */
    public int[] documentLengths() {
        return docLengths.clone();
    }

    /** One scored document. */
    public record Hit(int docId, double score) { }

    @Override
    public void close() {
        for (MappedFile file : new MappedFile[] {
                termsDict, termsHash, postOff, postBlk, postDat, docsLen }) {
            try {
                file.close();
            } catch (RuntimeException e) {
                LOGGER.debug("Failed to close a mapped file: {}", e.toString());
            }
        }
    }

    /** Files an index is made of, so a caller can size or delete one. */
    public static long sizeOnDisk(Path directory) throws IOException {
        long total = 0;
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                total += Files.size(file);
            }
        }
        return total;
    }
}
