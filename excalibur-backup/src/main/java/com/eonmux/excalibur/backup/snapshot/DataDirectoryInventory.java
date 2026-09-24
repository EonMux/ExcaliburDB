package com.eonmux.excalibur.backup.snapshot;

import com.eonmux.excalibur.core.datadir.DataDirectoryContents;
import com.eonmux.excalibur.core.datadir.FilesystemEntries;
import com.eonmux.excalibur.core.datadir.ReservedNames;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.retrieval.lexical.native_.DocumentErasureLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Walks a data directory and says what each file in it is.
 *
 * <h2>What is here</h2>
 * <p>{@code catalog.dat}; {@code segment_N.dat}, {@code segment_N.wal},
 * {@code segment_N.hnsw} and {@code segment_N.hnsw.docmap} per segment, with {@code segment_N.hnsw.compact.docmap} beside a graph whose compaction was
 * interrupted; {@code routing.dir}; {@code document-registry}; {@code source-catalog};
 * {@code source-status}, how each source's last crawl ended, which exists nowhere else and so is
 * kept like the catalog beside it; {@code erasure-receipts}, what each document erasure asked
 * for and which graph requests it queued, so that where it stands can still be answered after a
 * restore; the lexical index, {@code chunk-index/} or, under the Lucene engine,
 * {@code lucene-chunks/}, either with the erasure log it still owes; and a directory per tenant
 * namespace
 * holding {@code graph-staging/} and, once a build has run, {@code graph/}. A directory an
 * earlier build wrote may also hold {@code segment_N.tfs}, a term sketch this build has no use
 * for; it is {@link Inclusion#OBSOLETE} and never copied.</p>
 *
 * <h2>What is not listed at all</h2>
 * <p>The entries that coordinate processes rather than hold data - the lock file every node
 * takes, and a restore's journal and scratch ({@link ReservedNames}) - are left out of the
 * inventory entirely, rather than listed as omitted. They describe a process, not the store: an
 * image carrying the lock file would hand a stale holder's record to whatever restored it, and
 * listing it as omitted on every snapshot would be noise about a file that is always there.
 * The same goes for the {@code lost+found} of a filesystem mounted at the data directory
 * ({@link FilesystemEntries}): it is the filesystem's, it is unreadable to the node's user, and
 * it is never opened, so an image taken on one volume carries nothing of it to another.</p>
 *
 * <h2>Why the classification is a table and not a filter</h2>
 * <p>An unrecognised file is kept, not dropped. A build newer than this tool will write
 * names this tool has never heard of, and a backup that silently omits them restores a node
 * that is missing something nobody will notice until it matters. The cost of being wrong in
 * the other direction is a few bytes of scratch in the image, which verification will
 * happily account for.</p>
 *
 * @author ExcaliburDB
 */
public final class DataDirectoryInventory {

    private DataDirectoryInventory() {
    }

    /**
     * Walks a data directory and classifies every regular file under it that holds data.
     *
     * @param dataDir The directory
     * @return Every file but the reserved entries and the filesystem's own, in sorted path order
     * @throws IOException          if the directory cannot be walked
     * @throws NullPointerException if dataDir is null
     */
    public static List<ClassifiedFile> of(Path dataDir) throws IOException {
        Objects.requireNonNull(dataDir, "dataDir");
        if (!Files.isDirectory(dataDir)) {
            throw new IOException("Not a data directory: " + dataDir);
        }
        return DataDirectoryContents.regularFiles(dataDir).stream()
            .map(file -> relativize(dataDir, file))
            .sorted(Comparator.naturalOrder())
            .map(path -> new ClassifiedFile(path, classify(path), reason(path)))
            .toList();
    }

    /**
     * Decides what one relative path is.
     *
     * <p>Not decided here. What a file is comes from {@code excalibur-fsck}'s
     * {@link FileClassifier}, the classification that tool surveys a live directory with and
     * that {@code verify --deep} dispatches an image's files by; this only says what a snapshot
     * does with each kind. A second set of name rules would drift from the first, and the two
     * tools would then disagree about a file - one calling scratch what the other copies and
     * restores, or one keeping as state what the other reports as a leftover.</p>
     *
     * @param relativePath Path relative to the data directory, forward-slashed
     * @return What the file is
     * @throws IllegalArgumentException if the path is the data directory's lock, which describes a
     *                                  process rather than the store and is never inventoried
     * @throws NullPointerException     if relativePath is null
     */
    public static Inclusion classify(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        FileKind kind = FileClassifier.classify(relativePath);
        return switch (kind) {
            case CATALOG, ROUTING_DIRECTORY, SEGMENT_DATA, WRITE_AHEAD_LOG, LSH_TABLE,
                 DOCUMENT_REGISTRY, IN_FLIGHT_JOURNAL, SOURCE_CATALOG, SOURCE_STATUS,
                 ERASURE_RECEIPTS, GRAPH_STAGING_LOG, GRAPH_ERASURE_MARKER,
                 GRAPH_UNPUBLISHED_MARKER, UNRECOGNISED -> Inclusion.AUTHORITATIVE;
            case HNSW_GRAPH, HNSW_DOCUMENT_MAP, HNSW_STAGED_DOCUMENT_MAP ->
                Inclusion.REQUIRED_DERIVED;
            case PQ_CODEBOOK, LEXICAL_STORE_PART, LEXICAL_KEY_LOOKUP, LUCENE_CHUNK_INDEX_PART,
                 LUCENE_CHUNK_ERASURE_LOG -> Inclusion.REBUILDABLE;
            // Kept or left out with the graph it sits in, since what recovery does with a
            // directory beside the graph depends on whether the graph is there - but a staged
            // name inside one is scratch like any other.
            case GRAPH_STORE_PART -> FileKind.isLeftover(nameOf(relativePath))
                ? Inclusion.TRANSIENT : Inclusion.REBUILDABLE;
            // The staged graph is scratch, and still copied: the staged mapping beside it means
            // "discard both" only while the graph is there, and alone it would be installed over
            // the live mapping by the restored node.
            case STAGING_LEFTOVER ->
                FileKind.SEGMENT_STAGED_GRAPH_NAME.matcher(nameOf(relativePath)).matches()
                    ? Inclusion.REQUIRED_DERIVED : Inclusion.TRANSIENT;
            case DIAGNOSTIC_DUMP -> Inclusion.DIAGNOSTIC;
            case OBSOLETE -> Inclusion.OBSOLETE;
            case NODE_LOCK -> throw new IllegalArgumentException(relativePath + " is the data "
                + "directory's lock, which describes a process rather than the store and is "
                + "never inventoried");
        };
    }

    /**
     * Explains a classification.
     *
     * @param relativePath Path relative to the data directory
     * @return Why the file is treated the way it is
     */
    public static String reason(String relativePath) {
        FileKind kind = FileClassifier.classify(relativePath);
        if (kind == FileKind.HNSW_STAGED_DOCUMENT_MAP) {
            return "the node mapping - each node's chunk, or its document in a file an older "
                + "build wrote - that an interrupted HNSW compaction staged; with "
                + "its compacted graph beside it the node discards both, and without it the "
                + "graph swap completed and this is the only mapping that matches the graph, "
                + "which the node installs on open - so it is copied either way";
        }
        if (kind == FileKind.UNRECOGNISED) {
            return "nothing in this build recognises it, so it is kept: a newer build may write "
                + "state under a name this one has never heard of, and leaving it out would "
                + "restore a node missing it";
        }
        if (kind == FileKind.LUCENE_CHUNK_ERASURE_LOG || isNativeErasureLog(relativePath)) {
            return "the erasures the lexical index has accepted and not yet reclaimed; kept or "
                + "left out with that index and never without it, since every promise in it is "
                + "about text in that index, and a reindex rebuilds the index from storage, "
                + "which an erasure empties first";
        }
        return switch (classify(relativePath)) {
            case REQUIRED_DERIVED -> "vector index; derived from the segments in principle, "
                + "but nothing rebuilds it, and a graph and its node mapping must be "
                + "restored as the pair they were written as";
            case TRANSIENT -> "a staged name left by a write that was never published; "
                + "nothing reads it and copying it would put scratch in the image";
            case REBUILDABLE -> "derived state with a rebuild path; a restore without it "
                + "serves once a reindex, a graph build or a retrain has run";
            case DIAGNOSTIC -> "a JVM heap dump, not engine state; nothing reads it and it is "
                + "the size of the heap it records. Point the node's -XX:HeapDumpPath outside "
                + "the data directory, and move this one there if it is still wanted";
            case OBSOLETE -> "a term sketch an earlier build kept beside its segment; nothing "
                + "in this build reads it, and the node removes it when it starts";
            case AUTHORITATIVE -> "authoritative state that exists nowhere else";
        };
    }

    /**
     * Reports whether a path is the native lexical store's erasure log.
     *
     * @param path The relative path
     * @return true for {@code chunk-index/erasure.log}
     */
    private static boolean isNativeErasureLog(String path) {
        return path.equals(FileClassifier.LEXICAL_STORE_DIRECTORY + "/" + DocumentErasureLog.FILE);
    }

    /**
     * Returns the last element of a relative path.
     *
     * @param path A forward-slashed relative path
     * @return Its file name
     */
    private static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Renders a file's path relative to the data directory, forward-slashed.
     *
     * @param dataDir The root
     * @param file    A file beneath it
     * @return The relative path
     */
    private static String relativize(Path dataDir, Path file) {
        return dataDir.relativize(file).toString().replace('\\', '/');
    }
}
