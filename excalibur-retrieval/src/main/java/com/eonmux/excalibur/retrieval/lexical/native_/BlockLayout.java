package com.eonmux.excalibur.retrieval.lexical.native_;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The shape of one postings block on disk.
 *
 * <pre>
 * firstDoc      int32   first doc id, the base the packed gaps are relative to
 * lastDoc       int32   last doc id, so a cursor can skip the block without reading it
 * maxFrequency  uint8   largest term frequency in the block; 255 reads as "255 or more"
 * minLength     uint24  tokens in the shortest document of the block
 * dataOffset    int64   where the packed payload starts in post.dat
 * count         int16   postings in the block
 * docBits       uint8   bits per packed doc gap
 * tfBits        uint8   bits per packed frequency
 * </pre>
 *
 * <h2>Why the header holds the ingredients rather than the bound</h2>
 * <p>{@code lastDoc} lets a cursor advance past a block whose documents are all behind it, and the
 * frequency and length pair bounds what the block can contribute, so a block that cannot lift a
 * document into the current top-k is never decoded. A postings row for a common word is mostly
 * blocks of both kinds.</p>
 *
 * <p>The bound itself is deliberately <em>not</em> stored. BM25's length normalisation divides by
 * the mean length of the collection, and a segment only knows its own documents - so a bound
 * computed when the segment was written is measured against the wrong mean the moment that segment
 * is one of several. Because normalisation moves against the mean, a segment of short documents
 * scores higher under a larger collection mean than under its own, and a stored bound would be an
 * underestimate: MaxScore would stop scanning a term whose documents still belonged in the
 * results. Storing the largest frequency and the shortest document instead leaves the bound to be
 * computed at query time against whatever mean the query is actually scoring with, which is the
 * only mean that makes it a bound.</p>
 *
 * <p>Both fields are the corner of the block's frequency/length pairs rather than the pair of any
 * one posting: BM25's saturation rises with frequency and falls with length, so the largest
 * frequency paired with the shortest document bounds every posting in the block, whether or not
 * any single posting has both.</p>
 */
final class BlockLayout {

    /**
     * Postings per block.
     *
     * <p>128 is the usual choice and the reason is arithmetic rather than taste: it is large
     * enough that the 24-byte header amortises to under two bits a posting, and small enough that
     * skipping a block discards a useful amount of work. It also keeps a decoded block inside a
     * few cache lines.</p>
     */
    static final int BLOCK_SIZE = 128;

    /** Bytes in one block metadata record. */
    static final int RECORD_BYTES = 24;

    static final int OFFSET_FIRST_DOC = 0;
    static final int OFFSET_LAST_DOC = 4;
    static final int OFFSET_MAX_FREQUENCY = 8;
    static final int OFFSET_MIN_LENGTH = 9;
    static final int OFFSET_DATA = 12;
    static final int OFFSET_COUNT = 20;
    static final int OFFSET_DOC_BITS = 22;
    static final int OFFSET_TF_BITS = 23;

    /**
     * The largest frequency one byte can name.
     *
     * <p>A block recording this is read as holding <em>at least</em> this many occurrences, and is
     * bounded by the saturation ceiling instead of by arithmetic on the recorded value. The
     * distinction matters because the accumulating writer already clamps a stored frequency here,
     * so 255 in a header genuinely means "this or more" rather than "exactly this".</p>
     */
    static final int FREQUENCY_LIMIT = 255;

    /**
     * The longest document three bytes can name.
     *
     * <p>A longer one is recorded as this. Recording a document as shorter than it is can only
     * lower the length normalisation the bound is computed from, which raises the bound - so the
     * clamp loosens the bound rather than breaking it, and no document in this corpus comes within
     * three orders of magnitude of it.</p>
     */
    static final int LENGTH_LIMIT = 0xFFFFFF;

    private BlockLayout() {
        throw new AssertionError("Utility class");
    }

    /**
     * Writes the two impact fields, in the one place that knows how wide they are.
     *
     * @param out          The block header stream, positioned after {@code lastDoc}
     * @param maxFrequency Largest frequency in the block, before clamping
     * @param minLength    Tokens in the shortest document of the block, before clamping
     */
    static void writeImpactFields(DataOutputStream out, int maxFrequency, int minLength)
            throws IOException {
        int length = Math.min(minLength, LENGTH_LIMIT);
        out.write(Math.min(maxFrequency, FREQUENCY_LIMIT));
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
    }
}
