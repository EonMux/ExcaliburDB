package com.eonmux.excalibur.backup.snapshot;

import com.eonmux.excalibur.core.datadir.ReservedNames;
import com.eonmux.excalibur.store.ObsoleteSidecars;

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
 * kept like the catalog beside it; {@code chunk-index/}; and a directory per tenant namespace
 * holding {@code graph-staging/} and, once a build has run, {@code graph/}. A directory an
 * earlier build wrote may also hold {@code segment_N.tfs}, a term sketch this build has no use
 * for; it is {@link Inclusion#OBSOLETE} and never copied.</p>
 *
 * <h2>What is not listed at all</h2>
 * <p>The entries that coordinate processes rather than hold data - the lock file every node
 * takes, and a restore's journal and scratch ({@link ReservedNames}) - are left out of the
 * inventory entirely, rather than listed as omitted. They describe a process, not the store: an
 * image carrying the lock file would hand a stale holder's record to whatever restored it, and
 * listing it as omitted on every snapshot would be noise about a file that is always there.</p>
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

    /** Names a staged write takes while it is not yet published. */
    private static final List<String> TRANSIENT_SUFFIXES = List.of(
        ".tmp", ".staging", ".old", ".compact", ".left", ".right", ".building", ".previous",
        ".body", ".assembled", ".erasing", ".rewriting");

    /** The suffix the JVM gives a heap dump, whatever else the dump is called. */
    private static final String HEAP_DUMP_SUFFIX = ".hprof";

    /** Scratch directories the lexical store builds a compaction into. */
    private static final String SCRATCH_PREFIX = ".compact-";

    /** The derived lexical index, rebuildable by a reindex. */
    private static final String LEXICAL_DIRECTORY = "chunk-index";

    /** A tenant's built edge store, rebuildable from that tenant's staging logs. */
    private static final String BUILT_GRAPH_DIRECTORY = "graph";

    /**
     * What begins the name of each directory a publish, a build or an erasure compaction leaves
     * beside a tenant's built graph: {@code graph.previous}, {@code graph.retired},
     * {@code graph.building} and {@code graph.compacting}.
     */
    private static final String BUILT_GRAPH_SIBLING_PREFIX = BUILT_GRAPH_DIRECTORY + ".";

    /** The quantiser codebook sidecar, rebuildable by retraining. */
    private static final String CODEBOOK_SUFFIX = ".pqcb";

    /** The mapping an HNSW compaction stages in the compacted numbering. */
    private static final String STAGED_MAPPING_SUFFIX = ".hnsw.compact.docmap";

    private DataDirectoryInventory() {
    }

    /**
     * Walks a data directory and classifies every regular file under it that holds data.
     *
     * @param dataDir The directory
     * @return Every file but the reserved entries, in sorted path order
     * @throws IOException          if the directory cannot be walked
     * @throws NullPointerException if dataDir is null
     */
    public static List<ClassifiedFile> of(Path dataDir) throws IOException {
        Objects.requireNonNull(dataDir, "dataDir");
        if (!Files.isDirectory(dataDir)) {
            throw new IOException("Not a data directory: " + dataDir);
        }
        try (var walk = Files.walk(dataDir)) {
            return walk.filter(Files::isRegularFile)
                .map(file -> relativize(dataDir, file))
                .filter(path -> !ReservedNames.isReserved(path))
                .sorted(Comparator.naturalOrder())
                .map(path -> new ClassifiedFile(path, classify(path), reason(path)))
                .toList();
        }
    }

    /**
     * Decides what one relative path is.
     *
     * <p>The order of the tests is load-bearing. A heap dump is recognised first, wherever
     * it sits and whatever precedes its suffix, because the JVM names it and nothing about
     * the directory it landed in makes it that directory's state. An obsolete file is
     * recognised next, before the staged-name test its staging name would otherwise match,
     * so that it is reported as what it is. An HNSW file is recognised before the
     * staged-name test, because a graph staged by an interrupted compaction ends in
     * {@code .compact} and is part of the recovery record rather than scratch; and scratch
     * is recognised before the lexical directory, because the lexical store stages its
     * compactions inside it.</p>
     *
     * @param relativePath Path relative to the data directory, forward-slashed
     * @return What the file is
     * @throws NullPointerException if relativePath is null
     */
    public static Inclusion classify(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.endsWith(HEAP_DUMP_SUFFIX)) {
            return Inclusion.DIAGNOSTIC;
        }
        if (ObsoleteSidecars.isObsolete(nameOf(relativePath))) {
            return Inclusion.OBSOLETE;
        }
        if (isVectorIndex(relativePath)) {
            return Inclusion.REQUIRED_DERIVED;
        }
        if (isStagedName(relativePath)) {
            return Inclusion.TRANSIENT;
        }
        if (isRebuildable(relativePath)) {
            return Inclusion.REBUILDABLE;
        }
        return Inclusion.AUTHORITATIVE;
    }

    /**
     * Explains a classification.
     *
     * @param relativePath Path relative to the data directory
     * @return Why the file is treated the way it is
     */
    public static String reason(String relativePath) {
        if (relativePath.endsWith(STAGED_MAPPING_SUFFIX)) {
            return "the node mapping - each node's chunk, or its document in a file an older "
                + "build wrote - that an interrupted HNSW compaction staged; with "
                + "its compacted graph beside it the node discards both, and without it the "
                + "graph swap completed and this is the only mapping that matches the graph, "
                + "which the node installs on open - so it is copied either way";
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
     * Reports whether a path is an HNSW graph, its mapping, or a staged pair of either.
     *
     * @param path The relative path
     * @return true if it belongs to the vector index
     */
    private static boolean isVectorIndex(String path) {
        return path.endsWith(".hnsw") || path.endsWith(".hnsw.docmap")
            || path.endsWith(".hnsw.compact") || path.endsWith(".hnsw.compact.docmap");
    }

    /**
     * Reports whether a path is a staged write rather than published state.
     *
     * @param path The relative path
     * @return true if it is scratch
     */
    private static boolean isStagedName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.startsWith(SCRATCH_PREFIX)) {
            return true;
        }
        return TRANSIENT_SUFFIXES.stream().anyMatch(path::endsWith);
    }

    /**
     * Reports whether a path is derived state something will rebuild.
     *
     * @param path The relative path
     * @return true if a reindex, a graph build or a retrain would produce it again
     */
    private static boolean isRebuildable(String path) {
        if (path.endsWith(CODEBOOK_SUFFIX)) {
            return true;
        }
        String[] segments = path.split("/");
        if (segments.length > 1 && segments[0].equals(LEXICAL_DIRECTORY)) {
            return true;
        }
        // The directories beside the graph hold copies of it or scratch toward the next one,
        // derived from staging like the graph itself; kept or left out with it, since what
        // recovery does with each depends on whether the graph is there.
        return segments.length > 2 && (segments[1].equals(BUILT_GRAPH_DIRECTORY)
            || segments[1].startsWith(BUILT_GRAPH_SIBLING_PREFIX));
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
