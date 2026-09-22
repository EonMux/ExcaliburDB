package com.eonmux.excalibur.graph.format;

/**
 * The files an edge store is made of.
 *
 * <p>Every column is a pure array on disk: element {@code i} lives at exactly {@code i * width},
 * with no header and no padding. Metadata lives in {@link EdgeStoreDescriptor} instead, because a
 * per-file header would push every element off its natural alignment and break the bulk reads and
 * {@code madvise} hints the traversal depends on.
 *
 * <p>Adding a fact about an edge means adding a constant here and writing one more file. It never
 * means widening an existing record: a packed struct would force every existing store to be
 * rewritten to carry a field most queries never read, and would put unrelated fields on the same
 * cache line as the adjacency.
 */
public enum EdgeColumn {

    /** Target entity of each forward edge, ordered by (subject, relation). */
    EDGES_FWD("edges_fwd.csr", Integer.BYTES, Extent.PER_EDGE),

    /** Prefix offsets into {@link #EDGES_FWD}, one per entity plus a terminator. */
    OFFSETS_FWD("offsets_fwd.idx", Long.BYTES, Extent.PER_ENTITY_PLUS_ONE),

    /** Relation of each forward edge, parallel to {@link #EDGES_FWD}. */
    REL_FWD("rel_fwd.csr", Integer.BYTES, Extent.PER_EDGE),

    /** Source entity of each reverse edge, ordered by (object, relation). */
    EDGES_REV("edges_rev.csr", Integer.BYTES, Extent.PER_EDGE),

    /** Prefix offsets into {@link #EDGES_REV}. */
    OFFSETS_REV("offsets_rev.idx", Long.BYTES, Extent.PER_ENTITY_PLUS_ONE),

    /** Relation of each reverse edge, parallel to {@link #EDGES_REV}. */
    REL_REV("rel_rev.csr", Integer.BYTES, Extent.PER_EDGE),

    /**
     * Reverse edge position to forward edge index.
     *
     * <p>The reverse index visits edges in a different order, so a column laid out in forward order
     * cannot be addressed by a reverse position. This array is what lets every other column exist
     * exactly once: reverse traversal reads {@code perm_rev[i]} and then indexes the column with
     * it. The alternative — a second copy of every column in reverse order — costs more per edge
     * as soon as there are three columns, doubles the work of adding a fourth, and introduces two
     * copies of the valid interval that can drift apart.
     */
    PERM_REV("perm_rev.idx", Long.BYTES, Extent.PER_EDGE),

    /** First day each edge's fact held, days since epoch, in forward edge order. */
    VALID_FROM("valid_from.col", Integer.BYTES, Extent.PER_EDGE),

    /**
     * First day each edge's fact no longer held, or {@code Integer.MAX_VALUE} while it still does.
     */
    VALID_TO("valid_to.col", Integer.BYTES, Extent.PER_EDGE),

    /** Where each edge came from, as a {@code SourceType} ordinal. */
    SOURCE_TYPE("source_type.col", Byte.BYTES, Extent.PER_EDGE),

    /** Identifier of the specific document, statement or turn each edge came from. */
    SOURCE_ID("source_id.col", Long.BYTES, Extent.PER_EDGE),

    /** Extractor confidence, quantised to 0-100. */
    EXTRACTION_CONFIDENCE("extraction_conf.col", Byte.BYTES, Extent.PER_EDGE),

    /**
     * Which extractor build produced each edge.
     *
     * <p>Not optional. Without it there is no way to answer "which of these facts came from the
     * model we since found to be wrong", and therefore no way to re-extract a subset rather than
     * the whole corpus.
     */
    EXTRACTOR_VERSION("extractor_version.col", Short.BYTES, Extent.PER_EDGE);

    /** How many elements a column holds, relative to the store's counts. */
    public enum Extent {
        /** One element per edge. */
        PER_EDGE,
        /** One element per entity, plus a terminating offset. */
        PER_ENTITY_PLUS_ONE
    }

    private final String fileName;
    private final int elementBytes;
    private final Extent extent;

    EdgeColumn(String fileName, int elementBytes, Extent extent) {
        this.fileName = fileName;
        this.elementBytes = elementBytes;
        this.extent = extent;
    }

    /** File name of this column within an edge store directory. */
    public String fileName() {
        return fileName;
    }

    /** Size of one element in bytes. */
    public int elementBytes() {
        return elementBytes;
    }

    /** Whether this column is sized by edges or by entities. */
    public Extent extent() {
        return extent;
    }

    /** Number of elements this column holds for a store of the given size. */
    public long elementCount(long entityCount, long edgeCount) {
        return switch (extent) {
            case PER_EDGE -> edgeCount;
            case PER_ENTITY_PLUS_ONE -> entityCount + 1;
        };
    }

    /** Size in bytes this column occupies for a store of the given size. */
    public long byteSize(long entityCount, long edgeCount) {
        return Math.multiplyExact(elementCount(entityCount, edgeCount), (long) elementBytes);
    }
}
