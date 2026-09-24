package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.retrieval.lexical.native_.LexicalSidecars;

import java.nio.file.Path;

/**
 * Decides which format a file is from where it sits and what it is called.
 *
 * <p>Separate from the dispatch that reads it, because "what is this" and "is it sound" are
 * different questions and only the first can be answered without touching the file.</p>
 */
public final class FileClassifier {

    /** Directory name of the native lexical store inside a data directory. */
    public static final String LEXICAL_STORE_DIRECTORY = "chunk-index";

    private FileClassifier() {
        throw new AssertionError("Utility class");
    }

    /**
     * Classifies one file.
     *
     * @param relative The file's path relative to the data directory, using "/" separators
     * @return What the file is
     */
    public static FileKind classify(String relative) {
        // At the top of the directory only: the lock is taken there and nowhere else, and the
        // same name deeper down is not this build's.
        if (relative.equals(com.eonmux.excalibur.core.datadir.DataDirectoryLock.FILE_NAME)) {
            return FileKind.NODE_LOCK;
        }
        String name = nameOf(relative);
        // First, and wherever it sits: a heap dump is the JVM's, not any format's, so the
        // directory it landed in says nothing about what it is.
        if (FileKind.isDiagnosticDump(name)) {
            return FileKind.DIAGNOSTIC_DUMP;
        }
        // Before the leftover rule, which the staging name of a term sketch would otherwise
        // match: the node removes both forms when it starts, and says why.
        if (FileKind.isObsolete(name)) {
            return FileKind.OBSOLETE;
        }
        // Before the leftover test: a build writes staged names inside its own scratch
        // directory, and what any file under a graph directory is depends on that directory's
        // role, which the graph check decides as a whole.
        if (GraphStoreCheck.isGraphPart(relative)) {
            return FileKind.GRAPH_STORE_PART;
        }
        if (FileKind.isLeftover(name)) {
            return FileKind.STAGING_LEFTOVER;
        }
        if (relative.startsWith(LEXICAL_STORE_DIRECTORY + "/")) {
            return name.endsWith(LexicalSidecars.LOOKUP_TABLE)
                ? FileKind.LEXICAL_KEY_LOOKUP : FileKind.LEXICAL_STORE_PART;
        }
        if (relative.contains(GraphStagingCheck.DIRECTORY_SUFFIX + "/")) {
            if (GraphStagingCheck.matches(name)) {
                return FileKind.GRAPH_STAGING_LOG;
            }
            if (GraphStagingCheck.ERASURE_INTERRUPTED_MARKER.equals(name)) {
                return FileKind.GRAPH_ERASURE_MARKER;
            }
            if (GraphStagingStore.UNPUBLISHED_MARKER.equals(name)) {
                return FileKind.GRAPH_UNPUBLISHED_MARKER;
            }
        }
        FileKind byName = byFileName(name);
        return byName != null ? byName : FileKind.UNRECOGNISED;
    }

    /**
     * Classifies by file name alone.
     *
     * @param name The file's name
     * @return What the name says it is, or null if the name says nothing
     */
    private static FileKind byFileName(String name) {
        FileKind fixed = byFixedName(name);
        if (fixed != null) {
            return fixed;
        }
        if (FileKind.SEGMENT_DATA_NAME.matcher(name).matches()) {
            return FileKind.SEGMENT_DATA;
        }
        if (FileKind.SEGMENT_LOG_NAME.matcher(name).matches()) {
            return FileKind.WRITE_AHEAD_LOG;
        }
        if (LshTableCheck.matches(name)) {
            return FileKind.LSH_TABLE;
        }
        if (FileKind.SEGMENT_STAGED_MAPPING_NAME.matcher(name).matches()) {
            return FileKind.HNSW_STAGED_DOCUMENT_MAP;
        }
        return bySegmentIndexName(name);
    }

    /**
     * Classifies the files whose names are fixed rather than patterned.
     *
     * @param name The file's name
     * @return What it is, or null
     */
    private static FileKind byFixedName(String name) {
        return switch (name) {
            case CatalogCheck.FILE_NAME -> FileKind.CATALOG;
            case com.eonmux.excalibur.store.RoutingDirectory.FILE_NAME -> FileKind.ROUTING_DIRECTORY;
            case "document-registry" -> FileKind.DOCUMENT_REGISTRY;
            case "document-registry.in-flight" -> FileKind.IN_FLIGHT_JOURNAL;
            case "source-catalog" -> FileKind.SOURCE_CATALOG;
            case "source-status" -> FileKind.SOURCE_STATUS;
            case "erasure-receipts" -> FileKind.ERASURE_RECEIPTS;
            default -> null;
        };
    }

    /**
     * Classifies an HNSW graph and its two sidecars.
     *
     * @param name The file's name
     * @return What it is, or null
     */
    private static FileKind bySegmentIndexName(String name) {
        if (!FileKind.SEGMENT_INDEX_NAME.matcher(name).matches()) {
            return null;
        }
        if (name.endsWith(".docmap")) {
            return FileKind.HNSW_DOCUMENT_MAP;
        }
        return name.endsWith(".pqcb") ? FileKind.PQ_CODEBOOK : FileKind.HNSW_GRAPH;
    }

    /**
     * Returns the path of the compacted graph a staged mapping was written beside.
     *
     * <p>Whether that graph is still on disk is what decides whether the staged mapping is
     * scratch or state; see {@link FileKind#HNSW_STAGED_DOCUMENT_MAP}.</p>
     *
     * @param stagedMapping A {@code segment_N.hnsw.compact.docmap} path, relative or absolute
     * @return The {@code segment_N.hnsw.compact} path in the same form
     * @throws IllegalArgumentException if the name is not a staged mapping's
     */
    public static String compactedGraphOf(String stagedMapping) {
        String suffix = ".docmap";
        if (!FileKind.SEGMENT_STAGED_MAPPING_NAME.matcher(nameOf(stagedMapping)).matches()) {
            throw new IllegalArgumentException("Not a staged document mapping: " + stagedMapping);
        }
        return stagedMapping.substring(0, stagedMapping.length() - suffix.length());
    }

    /**
     * Returns the name of the live mapping a staged one is installed over.
     *
     * @param stagedMapping A {@code segment_N.hnsw.compact.docmap} name
     * @return The {@code segment_N.hnsw.docmap} name
     * @throws IllegalArgumentException if the name is not a staged mapping's
     */
    public static String liveMappingOf(String stagedMapping) {
        String graph = compactedGraphOf(stagedMapping);
        return graph.substring(0, graph.length() - ".compact".length()) + ".docmap";
    }

    /**
     * Returns the segment number a file's name carries.
     *
     * @param name The file's name
     * @return The segment number, or -1 if the name carries none
     */
    public static long segmentNumber(String name) {
        for (java.util.regex.Pattern pattern : new java.util.regex.Pattern[] {
                FileKind.SEGMENT_DATA_NAME, FileKind.SEGMENT_LOG_NAME,
                FileKind.SEGMENT_INDEX_NAME, FileKind.SEGMENT_STAGED_MAPPING_NAME }) {
            var matcher = pattern.matcher(name);
            if (matcher.matches()) {
                return Long.parseLong(matcher.group(1));
            }
        }
        return -1;
    }

    /**
     * Returns the last element of a relative path.
     *
     * @param relative A "/"-separated relative path
     * @return Its final element
     */
    private static String nameOf(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    /**
     * Renders a path relative to the data directory with "/" separators.
     *
     * @param dataDir The data directory
     * @param file    A file inside it
     * @return The relative path
     */
    public static String relativise(Path dataDir, Path file) {
        return dataDir.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }
}
