package com.eonmux.excalibur.fsck.check;

import java.util.regex.Pattern;

/**
 * Which on-disk format a file in a data directory is, decided from its name.
 *
 * <p>Naming is the only thing available before a file is opened, and opening the wrong
 * reader at a file is how a checker turns a misnamed file into a false corruption report.
 * Everything a name does not identify is {@link #UNRECOGNISED}, which is a verdict of its
 * own rather than a default of "fine".</p>
 */
public enum FileKind {

    /** {@code catalog.dat}: which segments the store owns. */
    CATALOG,

    /** {@code routing.dir}: which segment each hash slot belongs to. */
    ROUTING_DIRECTORY,

    /** {@code segment_N.dat}: the key/value records themselves. */
    SEGMENT_DATA,

    /** {@code segment_N.wal}: a segment's write-ahead log. */
    WRITE_AHEAD_LOG,

    /** {@code segment_N.hnsw}: a segment's vector graph. */
    HNSW_GRAPH,

    /** {@code segment_N.hnsw.docmap}: node ids to document ids. */
    HNSW_DOCUMENT_MAP,

    /** {@code segment_N.hnsw.pqcb}: a product quantizer's codebook. */
    PQ_CODEBOOK,

    /** {@code lsh_table_N.dat}: a locality-sensitive hash table. */
    LSH_TABLE,

    /** {@code document-registry}: which documents have been ingested. */
    DOCUMENT_REGISTRY,

    /** {@code document-registry.in-flight}: ingestions that had not finished. */
    IN_FLIGHT_JOURNAL,

    /** {@code source-catalog}: which sources are registered. */
    SOURCE_CATALOG,

    /** A log inside a tenant's {@code graph-staging} directory. */
    GRAPH_STAGING_LOG,

    /** A file inside the native lexical store directory. */
    LEXICAL_STORE_PART,

    /** A half-finished write some operation left behind. */
    STAGING_LEFTOVER,

    /** Nothing here recognises this file. */
    UNRECOGNISED;

    /** {@code segment_N.dat}, the same shape the store's own discovery matches. */
    public static final Pattern SEGMENT_DATA_NAME = Pattern.compile("^segment_(\\d+)\\.dat$");

    /** {@code segment_N.wal}. */
    public static final Pattern SEGMENT_LOG_NAME = Pattern.compile("^segment_(\\d+)\\.wal$");

    /** {@code segment_N.hnsw} and its two sidecars. */
    public static final Pattern SEGMENT_INDEX_NAME =
        Pattern.compile("^segment_(\\d+)\\.hnsw(\\.docmap|\\.pqcb)?$");

    /** Suffixes and prefixes that mark a write that never completed. */
    private static final Pattern LEFTOVER_NAME = Pattern.compile(
        ".*(\\.tmp|\\.compact|\\.rewriting|\\.erasing)$|^\\.compact-.*|.*\\.new(\\.\\d+)?$");

    /**
     * Reports whether a name is a half-finished write left behind.
     *
     * @param fileName The file's name
     * @return true if the name marks staging rather than state
     */
    public static boolean isLeftover(String fileName) {
        return LEFTOVER_NAME.matcher(fileName).matches();
    }
}
