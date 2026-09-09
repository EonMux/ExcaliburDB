package io.excalibur.retrieval.lexical.native_;

/**
 * The shape of one postings block on disk.
 *
 * <pre>
 * firstDoc       int32   first doc id, the base the packed gaps are relative to
 * lastDoc        int32   last doc id, so a cursor can skip the block without reading it
 * maxSaturation  float32 largest query-independent BM25 factor in the block
 * dataOffset     int64   where the packed payload starts in post.dat
 * count          int16   postings in the block
 * docBits        uint8   bits per packed doc gap
 * tfBits         uint8   bits per packed frequency
 * </pre>
 *
 * <p>{@code lastDoc} and {@code maxSaturation} are the whole point. The first lets a merge advance
 * past a block whose documents are all behind the cursor; the second bounds what the block can
 * contribute, so a block that cannot lift a document into the current top-k is never decoded. A
 * postings row for a common word is mostly blocks of both kinds.</p>
 */
public final class BlockLayout {

    /**
     * Postings per block.
     *
     * <p>128 is the usual choice and the reason is arithmetic rather than taste: it is large
     * enough that the 24-byte header amortises to under two bits a posting, and small enough that
     * skipping a block discards a useful amount of work. It also keeps a decoded block inside a
     * few cache lines.</p>
     */
    public static final int BLOCK_SIZE = 128;

    /** Bytes in one block metadata record. */
    public static final int RECORD_BYTES = 24;

    static final int OFFSET_FIRST_DOC = 0;
    static final int OFFSET_LAST_DOC = 4;
    static final int OFFSET_MAX_SATURATION = 8;
    static final int OFFSET_DATA = 12;
    static final int OFFSET_COUNT = 20;
    static final int OFFSET_DOC_BITS = 22;
    static final int OFFSET_TF_BITS = 23;

    private BlockLayout() {
        throw new AssertionError("Utility class");
    }
}
