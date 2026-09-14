package io.excalibur.retrieval.lexical.native_;

import io.excalibur.core.mapped.MappedFile;
import io.excalibur.retrieval.lexical.Bm25Parameters;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The few numbers a reader needs before it can make sense of any other file in an index.
 *
 * <pre>
 * 0  termCount        int32
 * 4  docCount         int32
 * 8  hashSlots        int32
 * 12 formatVersion    int32
 * 16 averageDocLength float64
 * 24 bm25 k1          float64   version 1 and above
 * 32 bm25 b           float64   version 1 and above
 * </pre>
 *
 * <h2>Why the BM25 pair lives here</h2>
 * <p>It is not a scoring preference an index is indifferent to. Changing {@code k1} or {@code b}
 * changes every score the corpus can produce, and with it every ranking and every threshold a
 * deployment has calibrated against those rankings. Recording the pair beside the bytes it was
 * written for is what lets an index refuse to open under a pair it was not built for, rather than
 * quietly answering differently.</p>
 *
 * <h2>What the version says</h2>
 * <p>The field occupies four bytes that were always written as zero, so an index written before
 * the pair was recorded reads as version 0 without any length guessing. Such an index was built by
 * a writer with 1.2 and 0.75 compiled into it, so that is exactly what it is taken to hold - which
 * is what lets an existing deployment keep opening its index.</p>
 *
 * <p>Versions 0 and 1 also carry a postings block header whose impact field is a bound computed
 * against the segment's own mean document length - which is not the mean a query over several
 * segments scores with, and is therefore not a bound at all once the segment is one of several.
 * Version 2 replaces that field with the largest frequency and the shortest document of the block,
 * from which a sound bound is computed at query time. A reader has to know which it is holding,
 * so the distinction is a version rather than a guess at the bytes.</p>
 *
 * <p>One definition, read by {@link PostingsIndex} and written by both writers. It was three
 * copies of the same offsets, and a header that has to grow is the wrong thing to have three
 * copies of.</p>
 */
record IndexMeta(int termCount, int docCount, int hashSlots, double averageDocLength,
                 Bm25Parameters bm25, int formatVersion) {

    /** The metadata file's name within an index directory. */
    static final String FILE_NAME = "index.meta";

    /** Before the BM25 pair was recorded. */
    private static final int VERSION_WITHOUT_BM25 = 0;

    /** With the BM25 pair appended, and a block impact bound baked in at build time. */
    private static final int VERSION_WITH_BM25 = 1;

    /** With block headers carrying the largest frequency and the shortest document instead. */
    static final int VERSION_WITH_STATISTIC_FREE_BLOCKS = 2;

    /** Bytes up to and including the average document length, present in every version. */
    private static final int HEADER_BYTES = 24;

    /** Bytes the BM25 pair adds. */
    private static final int BM25_BYTES = 16;

    /** Reads an index's metadata. */
    static IndexMeta read(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        MappedFile meta = MappedFile.open(file);
        try {
            if (meta.size() < HEADER_BYTES) {
                throw new IOException(file + " is " + meta.size() + " bytes, too short to be "
                    + "index metadata; the index is truncated and has to be rebuilt");
            }
            int version = meta.readInt(12);
            return new IndexMeta(meta.readInt(0), meta.readInt(4), meta.readInt(8),
                meta.readDouble(16), readBm25(meta, version, file), version);
        } finally {
            meta.close();
        }
    }

    private static Bm25Parameters readBm25(MappedFile meta, int version, Path file)
            throws IOException {
        if (version == VERSION_WITHOUT_BM25) {
            return Bm25Parameters.DEFAULT;
        }
        if (version != VERSION_WITH_BM25 && version != VERSION_WITH_STATISTIC_FREE_BLOCKS) {
            throw new IOException(file + " is metadata format version " + version + ", which this "
                + "build cannot read; it was written by a newer version of the engine");
        }
        if (meta.size() < HEADER_BYTES + BM25_BYTES) {
            throw new IOException(file + " records BM25 parameters but is only " + meta.size()
                + " bytes; the index is truncated and has to be rebuilt");
        }
        double k1 = meta.readDouble(HEADER_BYTES);
        double b = meta.readDouble(HEADER_BYTES + 8);
        try {
            return new Bm25Parameters(k1, b);
        } catch (IllegalArgumentException e) {
            throw new IOException(file + " records BM25 k1=" + k1 + ", b=" + b
                + ", which is not a usable pair; the index is corrupt and has to be rebuilt", e);
        }
    }

    /**
     * Metadata for an index this build has just written, which is always the current format.
     *
     * <p>A writer has no business choosing a version: the bytes it emits decide which one it is,
     * and letting the two be set separately is how they come to disagree.</p>
     */
    static IndexMeta current(int termCount, int docCount, int hashSlots, double averageDocLength,
                             Bm25Parameters bm25) {
        return new IndexMeta(termCount, docCount, hashSlots, averageDocLength, bm25,
            VERSION_WITH_STATISTIC_FREE_BLOCKS);
    }

    /**
     * Whether the postings blocks carry a bound baked in against this segment's own statistics.
     *
     * <p>Such a bound cannot be trusted by a scorer using collection-wide statistics, so a reader
     * of one of these has to fall back to a bound that depends on no statistics at all.</p>
     */
    boolean hasBakedInBlockBounds() {
        return formatVersion < VERSION_WITH_STATISTIC_FREE_BLOCKS;
    }

    /** Writes this metadata, replacing whatever was there. */
    void write(Path directory) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(directory.resolve(FILE_NAME)), HEADER_BYTES + BM25_BYTES))) {
            out.writeInt(termCount);
            out.writeInt(docCount);
            out.writeInt(hashSlots);
            out.writeInt(formatVersion);
            out.writeDouble(averageDocLength);
            out.writeDouble(bm25.k1());
            out.writeDouble(bm25.b());
        }
    }
}
