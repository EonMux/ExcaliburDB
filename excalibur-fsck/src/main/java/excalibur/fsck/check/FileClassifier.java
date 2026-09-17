package excalibur.fsck.check;

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
        String name = nameOf(relative);
        if (FileKind.isLeftover(name)) {
            return FileKind.STAGING_LEFTOVER;
        }
        if (relative.startsWith(LEXICAL_STORE_DIRECTORY + "/")) {
            return FileKind.LEXICAL_STORE_PART;
        }
        if (relative.contains(GraphStagingCheck.DIRECTORY_SUFFIX + "/")
                && GraphStagingCheck.matches(name)) {
            return FileKind.GRAPH_STAGING_LOG;
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
            case io.excalibur.store.RoutingDirectory.FILE_NAME -> FileKind.ROUTING_DIRECTORY;
            case "document-registry" -> FileKind.DOCUMENT_REGISTRY;
            case "document-registry.in-flight" -> FileKind.IN_FLIGHT_JOURNAL;
            case "source-catalog" -> FileKind.SOURCE_CATALOG;
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
     * Returns the segment number a file's name carries.
     *
     * @param name The file's name
     * @return The segment number, or -1 if the name carries none
     */
    public static long segmentNumber(String name) {
        for (java.util.regex.Pattern pattern : new java.util.regex.Pattern[] {
                FileKind.SEGMENT_DATA_NAME, FileKind.SEGMENT_LOG_NAME,
                FileKind.SEGMENT_INDEX_NAME }) {
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
