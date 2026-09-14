package io.excalibur.retrieval.lexical.native_;

import excalibur.filter.CuckooFilter;
import io.excalibur.core.hash.XxHash64;
import io.excalibur.core.mapped.MappedFile;
import io.excalibur.retrieval.lexical.Bm25Parameters;

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
 * <p>All big-endian (the order MappedFile uses), all read through the page cache rather than the
 * heap:</p>
 * <pre>
 * index.meta  counts, and the BM25 pair the index was built with; see IndexMeta
 * terms.dict  [len:int32][utf8 bytes]...          terms in id order
 * terms.hash  [hash:int64][termId:int32][pad:int32]  open-addressed, power-of-two slots
 * post.off    [start:int32] x (termCount + 1),    CSR row offsets, as block indices
 *             then [offset:int64] x termCount     where each term starts in terms.dict
 * post.blk    [record:24 bytes] x blockCount      one header per block; see BlockLayout
 * post.dat    packed doc gaps and frequencies     addressed by the block headers
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

    /**
     * BM25 term-frequency saturation, when a deployment has not chosen its own.
     *
     * @see Bm25Parameters
     */
    public static final double K1 = Bm25Parameters.DEFAULT_K1;

    /**
     * BM25 length normalisation, when a deployment has not chosen its own.
     *
     * @see Bm25Parameters
     */
    public static final double B = Bm25Parameters.DEFAULT_B;

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
    private final Bm25Parameters bm25;
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
     * Whether this index's block headers hold a bound baked in against its own statistics.
     *
     * <p>Such a bound is only valid for the mean document length the segment happened to have
     * when it was written, which is not the mean a query across segments scores with, so it is
     * ignored entirely and every term falls back to the saturation ceiling. See
     * {@link #boundsFor}.</p>
     */
    private final boolean bakedInBlockBounds;

    /**
     * The largest query-independent BM25 factor each term can contribute, for one mean length.
     *
     * <p>This is the bound MaxScore prunes with, so it has to be tight or no term ever becomes
     * non-essential. It also has to be free at query time: deriving it per query meant scanning
     * the block headers of every query term, which for a word appearing in most of the corpus is
     * thousands of mapped reads and measured slower than not pruning at all.</p>
     *
     * <p>It cannot simply be computed once at open, because the mean document length a query
     * scores against belongs to the whole collection and changes as segments are published. So it
     * is computed for one mean and kept until a query arrives with a different one - which, for a
     * store whose segment set is not changing, is never. Publication of the replacement is a
     * plain volatile write and two readers that race merely compute the same array twice, because
     * the array is a pure function of the mean and of bytes that never change.</p>
     */
    private volatile TermBounds bounds;

    private PostingsIndex(MappedFile termsDict, MappedFile termsHash, MappedFile postOff,
                          MappedFile postBlk, MappedFile postDat, MappedFile docsLen,
                          int termCount, int docCount, int hashSlots, double averageDocLength,
                          Bm25Parameters bm25, CuckooFilter presence, int[] docLengths,
                          boolean bakedInBlockBounds) {
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
        this.bm25 = bm25;
        this.presence = presence;
        this.docLengths = docLengths;
        this.bakedInBlockBounds = bakedInBlockBounds;
    }

    /** Every term's impact bound, for the one mean document length it was computed against. */
    private record TermBounds(double average, float[] byTerm) { }

    /** Opens an index previously written by {@link PostingsWriter}, scored with the defaults. */
    public static PostingsIndex open(Path directory) throws IOException {
        return open(directory, Bm25Parameters.DEFAULT);
    }

    /**
     * Opens an index to be scored with a chosen BM25 pair.
     *
     * @param bm25 What this node is configured to score with
     * @throws Bm25MismatchException if the index was built with a different pair, since every
     *                               score and therefore every ranking it can produce would differ
     *                               from the one the corpus was ingested for
     */
    public static PostingsIndex open(Path directory, Bm25Parameters bm25) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(bm25, "bm25 must not be null");
        IndexMeta meta = IndexMeta.read(directory);
        if (!meta.bm25().equals(bm25)) {
            throw new Bm25MismatchException(directory, meta.bm25(), bm25);
        }
        int termCount = meta.termCount();
        int docCount = meta.docCount();
        int hashSlots = meta.hashSlots();
        double averageDocLength = meta.averageDocLength();

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
        if (meta.hasBakedInBlockBounds()) {
            LOGGER.warn("Lexical index {} predates statistic-free block bounds, so its impact "
                + "bounds are ignored and pruning falls back to the saturation ceiling; results "
                + "are exact but slower until the segment is rewritten by a compaction",
                directory);
        }
        PostingsIndex index = new PostingsIndex(dict, hash, off, blk, dat, len,
            termCount, docCount, hashSlots, averageDocLength, bm25, presence, lengths,
            meta.hasBakedInBlockBounds());
        // Warmed for this index's own mean length, which is what a search that supplies no
        // collection statistics scores against.
        index.boundsFor(averageDocLength);
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
     * <p>Each postings block records the last doc id it holds, the largest frequency any of its
     * postings has and the length of its shortest document. That is enough to decide two things
     * without touching the block: whether every document in it is already behind the cursor, and
     * whether the most it could contribute still leaves a document below the current tenth-best
     * score. A postings row for a common word is mostly blocks of the second kind, which is why a
     * query full of ordinary English words stopped costing what it did.</p>
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
     * <p>The statistics reach the pruning bounds as well as the scores, and they have to: BM25
     * divides a document's length by the mean length of the collection, so a bound measured
     * against this segment's own mean is an underestimate for any collection whose documents are
     * longer on average - and MaxScore treats a bound as certain. A segment of short chunks
     * inside a corpus of long ones would have its best documents pruned, silently.</p>
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
        double average = stats == null ? averageDocLength : stats.averageDocLength();
        Cursor[] cursors = openCursors(terms, weights, stats, average);
        if (cursors.length == 0) {
            return new Hit[0];
        }
        return maxScore(cursors, Math.min(limit, docCount), average);
    }

    /**
     * A cursor for every query term the dictionary knows, carrying its bound under {@code
     * average}.
     *
     * <p>Terms the index does not hold are dropped rather than carried as empty cursors: an
     * expanded query is mostly words this corpus never used, and a cursor for one of them would
     * be scanned, sorted and suffix-summed to contribute nothing.</p>
     */
    private Cursor[] openCursors(String[] terms, double[] weights, Stats stats, double average) {
        float[] bound = boundsFor(average).byTerm();
        int collectionSize = stats == null ? docCount : stats.docCount();
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
            int df = stats == null ? documentFrequency(termId)
                : Math.max(1, stats.documentFrequency().applyAsInt(terms[t]));
            double idf = Math.log(1.0 + (collectionSize - df + 0.5) / (df + 0.5));
            cursors[distinct++] =
                new Cursor(firstBlock, endBlock, idf, weights[t], bound[termId]);
        }
        return java.util.Arrays.copyOf(cursors, distinct);
    }

    /**
     * MaxScore, with bounds taken from what the postings actually contain.
     *
     * <p>Terms are ordered by the most they can contribute and a suffix sum is kept. Once the
     * heap is full, any term whose suffix sum has fallen below the tenth-best score can no longer
     * <em>surface</em> a candidate: a document appearing only in those terms cannot reach the heap
     * however it scores. Such terms stop being scanned and are only sought into, which is where
     * the saving is - but they still contribute to every document another term surfaced, which is
     * what keeps the result exact.</p>
     *
     * <p>WAND was measured here too, with the same tight bounds, and was slower: on a query of six
     * ordinary English words its pivot sits at the first cursor almost every iteration, so every
     * document is scored anyway and the pivot arithmetic is pure overhead - 39 ms against 24 ms.
     * Its advantage needs queries that mix very rare terms with very common ones, which expansion
     * produces less often than it looks.</p>
     */
    private Hit[] maxScore(Cursor[] cursors, int wanted, double average) {
        // Strongest bound first, so the suffix sums shrink as fast as possible.
        java.util.Arrays.sort(cursors, (a, b) -> Double.compare(b.bound, a.bound));
        double[] suffix = new double[cursors.length + 1];
        for (int i = cursors.length - 1; i >= 0; i--) {
            suffix[i] = suffix[i + 1] + cursors[i].bound;
        }
        for (Cursor cursor : cursors) {
            cursor.load(this);
        }

        TopHits top = new TopHits(wanted);
        int essential = cursors.length;
        while (true) {
            int current = nextCandidate(cursors, essential);
            if (current < 0) {
                break;
            }
            double score = scoreDocument(cursors, current, average);
            if (score <= 0) {
                continue;
            }
            top.offer(current, score);
            while (essential > 1 && top.isFull() && suffix[essential - 1] <= top.threshold()) {
                essential--;
            }
        }
        return top.ranked();
    }

    /** The lowest doc id any term still able to surface a candidate is sitting on, or -1. */
    private static int nextCandidate(Cursor[] cursors, int essential) {
        int lowest = Integer.MAX_VALUE;
        for (int c = 0; c < essential; c++) {
            int doc = cursors[c].currentDoc();
            if (doc >= 0 && doc < lowest) {
                lowest = doc;
            }
        }
        return lowest == Integer.MAX_VALUE ? -1 : lowest;
    }

    /**
     * One document's whole score, from every term including the ones that cannot surface it.
     *
     * <p>Every cursor is advanced, not only the essential ones. A term too weak to bring a
     * document into the top-k on its own still adds its score to one another term brought in, and
     * dropping that contribution is how a pruning scorer ends up returning the right documents in
     * the wrong order.</p>
     */
    private double scoreDocument(Cursor[] cursors, int doc, double average) {
        double normBase = bm25.lengthNorm(docLengths[doc], average);
        double score = 0;
        for (Cursor cursor : cursors) {
            cursor.advanceTo(this, doc);
            if (cursor.currentDoc() == doc) {
                score += cursor.weight * cursor.idf
                    * bm25.saturation(cursor.currentFreq(), normBase);
                cursor.step(this);
            }
        }
        return score;
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
        final int[] docs = new int[BlockLayout.BLOCK_SIZE];
        final int[] freqs = new int[BlockLayout.BLOCK_SIZE];
        // Reused across blocks: a decode per block otherwise allocates and discards a buffer per
        // block read, which on a long postings row is thousands of short-lived arrays a query.
        byte[] scratch = new byte[BlockLayout.BLOCK_SIZE * 8 + 16];
        boolean decoded;

        Cursor(long firstBlock, long endBlock, double idf, double weight, double maxImpact) {
            this.block = firstBlock;
            this.endBlock = endBlock;
            this.idf = idf;
            this.weight = weight;
            // The largest score this term can add to any document, taken from what its blocks
            // actually contain rather than from the theoretical ceiling of the BM25 saturation
            // curve. (K1 + 1) is a true bound and a useless one: real postings sit far below it,
            // so a term computed from it almost never becomes non-essential and the skipping
            // never happens.
            this.bound = Math.abs(weight) * idf * maxImpact;
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

    /**
     * Every term's impact bound under a given mean document length, computed or reused.
     *
     * <p>The mean is compared by value rather than by how it was obtained: a store recomputes it
     * from the same segments on every query and gets the same double back, so the cached array
     * survives for as long as the segment set does. When it does change, the whole array is
     * rebuilt in one sequential pass over the block headers - which is exactly the pass open
     * already makes, so the worst a change of statistics costs is one more open.</p>
     */
    private TermBounds boundsFor(double average) {
        TermBounds cached = bounds;
        if (cached != null && cached.average() == average) {
            return cached;
        }
        TermBounds fresh = new TermBounds(average, computeTermBounds(average));
        bounds = fresh;
        return fresh;
    }

    /** One pass over the block headers, filling in every term's bound. */
    private float[] computeTermBounds(double average) {
        float[] byTerm = new float[termCount];
        if (bakedInBlockBounds) {
            java.util.Arrays.fill(byTerm, (float) bm25.maximumSaturation());
            return byTerm;
        }
        for (int termId = 0; termId < termCount; termId++) {
            double max = 0;
            long end = rowEnd(termId);
            for (long block = rowStart(termId); block < end; block++) {
                max = Math.max(max, blockBound(block, average));
            }
            // Rounded up rather than to nearest. A float is four bytes a term instead of eight,
            // which matters on a dictionary of millions, but rounding to nearest can land one
            // ulp below the double the scorer computes - and a bound one ulp too low is not a
            // bound. Rounding away from zero costs nothing and cannot be crossed.
            byTerm[termId] = Math.nextUp((float) max);
        }
        return byTerm;
    }

    /**
     * The most any posting in one block can contribute, before the query's weight and idf.
     *
     * <p>Taken from the block's largest frequency and its shortest document rather than from any
     * single posting: saturation rises with frequency and falls with length, so that corner
     * bounds every posting the block holds whether or not one of them sits at it. A block whose
     * frequency field has hit its ceiling says only "this many or more", so it is bounded by the
     * saturation ceiling instead.</p>
     */
    private double blockBound(long block, double average) {
        long record = block * BlockLayout.RECORD_BYTES;
        int maxFrequency = postBlk.readByte(record + BlockLayout.OFFSET_MAX_FREQUENCY) & 0xFF;
        if (maxFrequency >= BlockLayout.FREQUENCY_LIMIT) {
            return bm25.maximumSaturation();
        }
        return bm25.saturation(maxFrequency, bm25.lengthNorm(minLength(record), average));
    }

    /** MappedFile exposes no 24-bit accessor, so the length is read as three big-endian bytes. */
    private int minLength(long record) {
        long at = record + BlockLayout.OFFSET_MIN_LENGTH;
        return ((postBlk.readByte(at) & 0xFF) << 16)
             | ((postBlk.readByte(at + 1) & 0xFF) << 8)
             | (postBlk.readByte(at + 2) & 0xFF);
    }

    /**
     * The largest impact any of a term's postings can have, as the pruning bound reads it.
     *
     * <p>Exposed so that the soundness of skipping can be asserted against the scorer's own
     * arithmetic rather than inferred from whether a sample of queries happens to expose a
     * bound that is too low.
     *
     * @param averageDocumentLength The mean the query scores against, which is the only mean the
     *                              bound is claimed to hold for
     */
    float termBound(int termId, double averageDocumentLength) {
        return boundsFor(averageDocumentLength).byTerm()[termId];
    }

    /** MappedFile exposes no 16-bit accessor, so the count is read as two big-endian bytes. */
    private int readCount(long offset) {
        return ((postBlk.readByte(offset) & 0xFF) << 8) | (postBlk.readByte(offset + 1) & 0xFF);
    }

    /** Decodes one block into a cursor. */
    private void decode(Cursor cursor) {
        long record = cursor.block * BlockLayout.RECORD_BYTES;
        int firstDoc = postBlk.readInt(record + BlockLayout.OFFSET_FIRST_DOC);
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

    /**
     * The BM25 pair this index is scored with, which is also the pair it was built with.
     *
     * <p>The two cannot differ: opening an index under a pair it was not built with is refused.
     */
    public Bm25Parameters bm25() {
        return bm25;
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
