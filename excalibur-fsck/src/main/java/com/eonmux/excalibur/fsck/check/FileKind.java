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

    /**
     * {@code segment_N.hnsw.compact.docmap}: the node-to-document mapping an HNSW compaction
     * staged in the compacted numbering.
     *
     * <p>One name, two meanings, and which one is decided by its neighbour. Beside
     * {@code segment_N.hnsw.compact} the compaction never swapped its graph in, and both files
     * are scratch the next open discards. Alone, the graph swap completed and the mapping
     * rename did not: {@code segment_N.hnsw} already holds the compacted numbering, this file
     * is the only mapping that matches it, and the next open installs it over
     * {@code segment_N.hnsw.docmap}. Losing it then leaves the graph answering with the wrong
     * documents, so a name-only verdict of "leftover" would be exactly wrong half the
     * time.</p>
     */
    HNSW_STAGED_DOCUMENT_MAP,

    /** {@code segment_N.hnsw.pqcb}: a product quantizer's codebook. */
    PQ_CODEBOOK,

    /**
     * {@code segment_N.tfs}: a segment's term-frequency sketch.
     *
     * <p>Derived entirely from the segment's live entries and dated by a watermark the node
     * compares on open; a missing, stale or damaged sketch is rebuilt from the segment before
     * it serves anything. See {@link #rebuiltOnOpen()}.</p>
     */
    TERM_SKETCH,

    /** {@code lsh_table_N.dat}: a locality-sensitive hash table. */
    LSH_TABLE,

    /** {@code document-registry}: which documents have been ingested. */
    DOCUMENT_REGISTRY,

    /** {@code document-registry.in-flight}: ingestions that had not finished. */
    IN_FLIGHT_JOURNAL,

    /** {@code source-catalog}: which sources are registered. */
    SOURCE_CATALOG,

    /**
     * {@code source-status}: how each registered source's last crawl ended.
     *
     * <p>A report rather than state the store depends on: the node reads one it cannot trust as
     * no history at all, and each source reports from its next run. See
     * {@link #discardedOnOpen()}.</p>
     */
    SOURCE_STATUS,

    /** A log inside a tenant's {@code graph-staging} directory. */
    GRAPH_STAGING_LOG,

    /** A file inside the native lexical store directory. */
    LEXICAL_STORE_PART,

    /** A half-finished write some operation left behind. */
    STAGING_LEFTOVER,

    /**
     * A JVM diagnostic dump, {@code *.hprof}: not engine state at all.
     *
     * <p>{@code -XX:+HeapDumpOnOutOfMemoryError} writes one wherever
     * {@code -XX:HeapDumpPath} points, and a node whose dump path is its data directory
     * leaves it beside the segments. Nothing in the engine reads it and nothing checksums it,
     * so it is neither verified nor unverified data - it is evidence about a process, and it
     * is reported as such rather than as a file this tool failed to check.</p>
     */
    DIAGNOSTIC_DUMP,

    /** Nothing here recognises this file. */
    UNRECOGNISED;

    /** {@code segment_N.dat}, the same shape the store's own discovery matches. */
    public static final Pattern SEGMENT_DATA_NAME = Pattern.compile("^segment_(\\d+)\\.dat$");

    /** {@code segment_N.wal}. */
    public static final Pattern SEGMENT_LOG_NAME = Pattern.compile("^segment_(\\d+)\\.wal$");

    /** {@code segment_N.hnsw} and its two sidecars. */
    public static final Pattern SEGMENT_INDEX_NAME =
        Pattern.compile("^segment_(\\d+)\\.hnsw(\\.docmap|\\.pqcb)?$");

    /**
     * {@code segment_N.hnsw.compact.docmap}, the mapping a compaction stages.
     *
     * <p>Not a leftover by the suffix rule, which it would defeat: see
     * {@link #HNSW_STAGED_DOCUMENT_MAP} for why its name alone does not say what it is.</p>
     */
    public static final Pattern SEGMENT_STAGED_MAPPING_NAME =
        Pattern.compile("^segment_(\\d+)\\.hnsw\\.compact\\.docmap$");

    /**
     * {@code segment_N.tfs}, the only name a term sketch is written under.
     *
     * <p>The store derives the sidecar's name from its segment's - {@code .dat} replaced, or
     * {@code .tfs} appended to any other name - but writes one only for a segment that has a
     * write log, and only a segment at its canonical {@code segment_N.dat} name has one. A
     * split child or a compaction target is renamed onto that name, and the sidecar there
     * removed, before it is given a log. A {@code .tfs} under any other name is therefore
     * nothing this build writes, and is left unrecognised rather than read as a sketch.</p>
     */
    public static final Pattern TERM_SKETCH_NAME = Pattern.compile("^segment_(\\d+)\\.tfs$");

    /** Suffixes and prefixes that mark a write that never completed. */
    private static final Pattern LEFTOVER_NAME = Pattern.compile(
        ".*(\\.tmp|\\.compact|\\.rewriting|\\.erasing)$|^\\.compact-.*|.*\\.new(\\.\\d+)?$");

    /** The suffix the JVM gives a heap dump. */
    private static final String DIAGNOSTIC_DUMP_SUFFIX = ".hprof";

    /**
     * Reports whether the node rebuilds a file of this kind by itself when it opens.
     *
     * <p>Which decides whether damage to one is damage to the store. Every other derived file
     * - a graph, its mapping, a codebook, an LSH table - is rebuilt only when someone runs a
     * reindex or a retrain, so a damaged one leaves the node serving less until an operator
     * acts, and it fails the run like any other damage. A term sketch is recomputed from the
     * segment's live entries before the segment serves, whenever the file is missing, stale or
     * fails its checksum, so a damaged one costs a rebuild at start-up and nothing else.</p>
     *
     * @return true for a kind whose damage the node repairs by itself on open
     */
    public boolean rebuiltOnOpen() {
        return this == TERM_SKETCH;
    }

    /**
     * Reports whether the node sets a damaged file of this kind aside by itself when it starts.
     *
     * <p>The other way damage can cost the node nothing but the file. The source status history
     * is not rebuilt - what each source's last crawl did is not derivable from anything else -
     * but nothing the node serves depends on it either: one that does not read back whole is
     * logged and treated as empty, each source reports from its next run, and the file is
     * rewritten when the first run ends. What is lost is the history, never data.</p>
     *
     * @return true for a kind the node discards, rather than refuses to start on, when damaged
     */
    public boolean discardedOnOpen() {
        return this == SOURCE_STATUS;
    }

    /**
     * Reports whether damage to a file of this kind is something the node recovers from on its
     * own, by rebuilding it or by setting it aside.
     *
     * @return true when a corrupt file of this kind is not damage to the store
     */
    public boolean recoveredOnOpen() {
        return rebuiltOnOpen() || discardedOnOpen();
    }

    /**
     * Reports whether a name is a JVM heap dump.
     *
     * <p>The suffix alone, and only as the suffix: the JVM names a dump
     * {@code java_pid<N>.hprof} by default and whatever {@code -XX:HeapDumpPath} says
     * otherwise, and the one thing every form shares is that it ends in {@code .hprof}. A name
     * that merely contains it is something else, and is left to be unrecognised.</p>
     *
     * @param fileName The file's name
     * @return true if the name is a heap dump's
     */
    public static boolean isDiagnosticDump(String fileName) {
        return fileName.endsWith(DIAGNOSTIC_DUMP_SUFFIX);
    }

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
