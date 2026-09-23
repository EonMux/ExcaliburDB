package com.eonmux.excalibur.fsck.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides which checker a file belongs to and runs it: the one per-file dispatch.
 *
 * <p>Public because it has two callers. {@link DataDirectoryScanner} surveys a live data
 * directory with it, and {@code excalibur-backup verify --deep} hands it every file in a
 * snapshot's payload whose format the backup tool has no engine checker of its own for - the
 * write-ahead logs, the routing directory, the lexical store, the graph staging logs, the
 * segments' term sketches and the ingestion registries. One dispatch rather than two is the
 * point: a format added to one caller's switch and not the other's would be verified by one
 * tool and waved through by the other.</p>
 *
 * <p>Nothing here writes. Every checker it reaches maps read-only, streams past a digest or
 * parses bytes it read, so it can be pointed at a snapshot's payload directly, with no
 * scratch copy between it and the file. See {@link ReadOnlyMapping} for why the store's own
 * open paths could not be used.</p>
 *
 * <p>The native lexical store is the one structure that is not a file: its manifest names
 * segment directories whose columns are verified together. {@link #inspect} reports each file
 * inside it as a lexical store part, and {@link #lexicalStore} checks the store as a whole;
 * the verdicts the second returns supersede the first's for the files they cover.</p>
 */
public final class FileInspector {

    private FileInspector() {
        throw new AssertionError("Utility class");
    }

    /**
     * Classifies one file and runs the checker for its kind.
     *
     * @param file     The file
     * @param relative Its path relative to the data directory, "/"-separated; its name and
     *                 where it sits are what decide its kind
     * @return The kind, the verdict, and what a catalog or routing directory holds
     * @throws NullPointerException if either argument is null
     */
    public static FileInspection inspect(Path file, String relative) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(relative, "relative");
        FileKind kind = FileClassifier.classify(relative);
        return switch (kind) {
            case CATALOG -> {
                CatalogInspection inspection = CatalogCheck.check(file, relative);
                yield new FileInspection(kind, inspection.check(), inspection.records(),
                    new int[0]);
            }
            case ROUTING_DIRECTORY -> {
                var inspection = RoutingDirectoryCheck.check(file, relative);
                yield new FileInspection(kind, inspection.check(), List.of(),
                    inspection.slots());
            }
            default -> FileInspection.of(kind, check(kind, file, relative));
        };
    }

    /**
     * Checks the native lexical store under a data directory, if there is one.
     *
     * <p>A store directory that holds no file is not a store. A node creates the directory
     * when it opens and writes the manifest only when the first batch is published, so one
     * that started and stopped without ingesting leaves exactly an empty directory - and
     * reporting its manifest missing would call a data directory no write ever reached
     * damaged. A directory with any file in it and no manifest is a different thing: something
     * was written and the record of it is gone, which the store check reports as such.</p>
     *
     * @param dataDirectory The data directory, or a snapshot's payload root
     * @return The verdicts on the store's manifest and the segments it names, or empty when
     *         the directory has no lexical store or one that nothing was ever written to
     * @throws NullPointerException if dataDirectory is null
     */
    public static Optional<LexicalStoreCheck.LexicalInspection> lexicalStore(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Path store = dataDirectory.resolve(FileClassifier.LEXICAL_STORE_DIRECTORY);
        if (!Files.isDirectory(store) || holdsNoFile(store)) {
            return Optional.empty();
        }
        return Optional.of(LexicalStoreCheck.check(store, FileClassifier.LEXICAL_STORE_DIRECTORY));
    }

    /**
     * Reports whether a directory tree holds no regular file anywhere in it.
     *
     * @param directory The directory
     * @return true if nothing was ever written under it; false as well if it cannot be walked,
     *         so that the store check reports what it can see rather than nothing
     */
    private static boolean holdsNoFile(Path directory) {
        try (var walk = Files.walk(directory)) {
            return walk.noneMatch(Files::isRegularFile);
        } catch (IOException | java.io.UncheckedIOException e) {
            return false;
        }
    }

    /**
     * Runs the checker for every kind that describes only itself.
     *
     * @param kind     What the file is
     * @param file     The file
     * @param relative Its path relative to the data directory
     * @return The verdict
     */
    private static FileCheck check(FileKind kind, Path file, String relative) {
        return switch (kind) {
            case SEGMENT_DATA -> SegmentDataCheck.check(file, relative);
            case WRITE_AHEAD_LOG -> WriteAheadLogCheck.check(file, relative);
            case HNSW_GRAPH -> VectorIndexCheck.graph(file, relative);
            case HNSW_DOCUMENT_MAP -> VectorIndexCheck.documentMapping(file, relative);
            case HNSW_STAGED_DOCUMENT_MAP ->
                VectorIndexCheck.stagedDocumentMapping(file, relative);
            case PQ_CODEBOOK -> VectorIndexCheck.codebook(file, relative);
            case TERM_SKETCH -> TermSketchCheck.check(file, relative);
            case LSH_TABLE -> LshTableCheck.check(file, relative);
            case GRAPH_STAGING_LOG -> GraphStagingCheck.check(file, relative);
            case DOCUMENT_REGISTRY -> CountMarkedTextCheck.check(
                file, relative, CountMarkedFormat.DOCUMENT_REGISTRY);
            case SOURCE_CATALOG -> CountMarkedTextCheck.check(
                file, relative, CountMarkedFormat.SOURCE_CATALOG);
            case SOURCE_STATUS -> CountMarkedTextCheck.check(
                file, relative, CountMarkedFormat.SOURCE_STATUS);
            case IN_FLIGHT_JOURNAL -> unverifiable(file, relative, "in-flight ingest journal",
                "this journal carries neither a checksum nor a record count, by design: it is "
                    + "truncation-tolerant, because losing either half of a begin/end pair is "
                    + "safe. Nothing here can confirm its contents");
            case LEXICAL_STORE_PART -> unverifiable(file, relative, "lexical store sidecar",
                "this file sits beside the lexical segments rather than inside one - document "
                    + "keys, their offsets, a tombstone bitmap or the erasure log - and none "
                    + "of those formats carries a checksum or has a verifier here. The "
                    + "segments the manifest names were checked; this was not");
            case STAGING_LEFTOVER -> unverifiable(file, relative, "staging leftover",
                "this is a half-finished write, not state: nothing reads it, and it is here "
                    + "because the operation that staged it did not finish");
            case DIAGNOSTIC_DUMP -> diagnosticDump(file, relative);
            case UNRECOGNISED -> unverifiable(file, relative, "unrecognised",
                "nothing in this build recognises this file, so it was not read at all. It is "
                    + "not evidence of damage and it is not evidence of health");
            case CATALOG, ROUTING_DIRECTORY -> throw new IllegalStateException(
                "describes other files as well as itself, so handled by inspect: " + kind);
        };
    }

    /**
     * Builds the verdict for a file whose format carries nothing to check.
     *
     * @param file     The file
     * @param relative Its path relative to the data directory
     * @param format   Name of the format
     * @param detail   Why nothing could be concluded
     * @return An unchecked verdict
     */
    private static FileCheck unverifiable(Path file, String relative, String format,
                                          String detail) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return new FileCheck(file, relative, format, null, CheckStatus.UNREADABLE,
                "the file could not be sized: " + e, -1);
        }
        return new FileCheck(file, relative, format, null, CheckStatus.UNCHECKED, detail, size);
    }

    /**
     * Builds the verdict for a JVM heap dump, which is reported and never read.
     *
     * @param file     The dump
     * @param relative Its path relative to the data directory
     * @return A not-data verdict carrying the dump's size
     */
    private static FileCheck diagnosticDump(Path file, String relative) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return new FileCheck(file, relative, "JVM heap dump", null, CheckStatus.UNREADABLE,
                "the file could not be sized: " + e, -1);
        }
        return new FileCheck(file, relative, "JVM heap dump", null, CheckStatus.NOT_DATA,
            "a heap dump the JVM wrote here, not engine state: nothing reads it, so there is "
                + "nothing in it to verify, and it does not count against --strict. It is "
                + size + " bytes of disk; move it somewhere the node's -XX:HeapDumpPath "
                + "points instead of the data directory, and keep it for as long as the "
                + "out-of-memory it records is still being investigated", size);
    }
}
