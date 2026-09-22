package com.eonmux.excalibur.retrieval.lexical.native_;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The files one index is made of, and the step that turns them into a segment.
 *
 * <p>Named here rather than spelled out at each of the fourteen places that resolved them,
 * because a writer and a reader that disagree about a filename produce a segment that is missing
 * a column rather than a compile error.</p>
 */
final class SegmentFiles {

    /** Terms in id order: {@code [len:int32][utf8 bytes]}. */
    static final String TERMS_DICT = "terms.dict";

    /** Open-addressed table from a term's hash to its id. */
    static final String TERMS_HASH = "terms.hash";

    /** CSR row offsets as block indices, then each term's offset into the dictionary. */
    static final String POST_OFF = "post.off";

    /** One {@link BlockLayout} header per postings block. */
    static final String POST_BLK = "post.blk";

    /** Packed doc gaps and frequencies, addressed by the block headers. */
    static final String POST_DAT = "post.dat";

    /** Document lengths in id order, for BM25 normalisation. */
    static final String DOCS_LEN = "docs.len";

    /** Every file a reader maps. The metadata is not among them: it is what commits them. */
    static final List<String> COLUMNS =
        List.of(TERMS_DICT, TERMS_HASH, POST_OFF, POST_BLK, POST_DAT, DOCS_LEN);

    private SegmentFiles() {
        throw new AssertionError("Utility class");
    }

    /**
     * Makes a finished index durable, and only then names it finished.
     *
     * <h2>Why the metadata is the commit marker</h2>
     * <p>A reader opens {@code index.meta} first and takes every other file's shape from the
     * counts in it, so an index without readable metadata is an index that does not exist yet.
     * That makes publishing the metadata the moment the segment becomes real - which is only
     * true if the columns it describes are already on the device when it lands. A crash between
     * the two orders otherwise produces metadata that promises columns the device never
     * received, and those columns are the right length and full of nothing.</p>
     *
     * <p>{@link IndexMeta#write} publishes by rename, and that rename forces this directory,
     * which is also what makes the newly created columns' directory entries durable.</p>
     *
     * <h2>Why the columns are digested here</h2>
     * <p>The metadata carries a CRC32C of every column, and this is the one point in a segment's
     * life at which all of them exist and none of them will change again. Digesting after the
     * force rather than before is deliberate: the checksum then describes the bytes the device
     * actually accepted, not the ones a writer handed to the page cache.</p>
     *
     * @param directory The index directory, holding every column already written
     * @param meta      What the columns are
     * @throws IOException if a column cannot be forced or digested, or the metadata cannot be
     *                     published
     */
    static void commit(Path directory, IndexMeta meta) throws IOException {
        for (String column : COLUMNS) {
            Durability.FILE_SYSTEM.force(directory.resolve(column));
        }
        meta.withColumns(LexicalSegmentIntegrity.digest(directory)).write(directory);
    }
}
