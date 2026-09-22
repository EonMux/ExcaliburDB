package com.eonmux.excalibur.fsck.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Walks a data directory and reaches a verdict on everything in it.
 *
 * <p>Enumerates rather than expects. A node's directory holds whatever the builds that ran
 * against it left there, and a checker that looks only for the files it knows about reports
 * a clean bill of health over anything it has never heard of. Every file is listed, and
 * one it cannot identify is reported as unidentified.</p>
 *
 * <p>Nothing here writes. Every reader it dispatches to was chosen or built for that
 * property; see {@link ReadOnlyMapping} for why the store's own open paths could not be
 * used directly.</p>
 */
public final class DataDirectoryScanner {

    private final Path dataDirectory;
    private final Path quarantineDirectory;

    /**
     * Prepares a scan of one directory.
     *
     * @param dataDirectory       The directory to survey
     * @param quarantineDirectory A directory to skip, or null; quarantined files are
     *                            evidence set aside, not state to be re-reported
     */
    public DataDirectoryScanner(Path dataDirectory, Path quarantineDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.quarantineDirectory = quarantineDirectory;
    }

    /**
     * Surveys the directory.
     *
     * @return What was found
     * @throws IOException if the directory cannot be walked
     */
    public DataDirectoryScan scan() throws IOException {
        List<FileCheck> files = new ArrayList<>();
        List<ConsistencyFinding> findings = new ArrayList<>();
        List<CatalogRecord> catalogRows = List.of();
        int[] routingSlots = new int[0];

        for (Path file : listFiles()) {
            String relative = FileClassifier.relativise(dataDirectory, file);
            FileKind kind = FileClassifier.classify(relative);
            switch (kind) {
                case CATALOG -> {
                    CatalogInspection inspection = CatalogCheck.check(file, relative);
                    files.add(inspection.check());
                    catalogRows = inspection.records();
                }
                case ROUTING_DIRECTORY -> {
                    var inspection = RoutingDirectoryCheck.check(file, relative);
                    files.add(inspection.check());
                    routingSlots = inspection.slots();
                }
                default -> files.add(inspect(kind, file, relative));
            }
        }
        addLexicalStore(files, findings);
        files.sort(Comparator.comparing(FileCheck::relative));
        findings.addAll(new CrossFileChecks(dataDirectory).run(files, catalogRows, routingSlots));
        return new DataDirectoryScan(dataDirectory, files, findings, catalogRows, routingSlots);
    }

    /**
     * Dispatches one file to the reader for its format.
     *
     * @param kind     What the file is
     * @param file     The file
     * @param relative Its path relative to the data directory
     * @return The verdict
     */
    private FileCheck inspect(FileKind kind, Path file, String relative) {
        return switch (kind) {
            case SEGMENT_DATA -> SegmentDataCheck.check(file, relative);
            case WRITE_AHEAD_LOG -> WriteAheadLogCheck.check(file, relative);
            case HNSW_GRAPH -> VectorIndexCheck.graph(file, relative);
            case HNSW_DOCUMENT_MAP -> VectorIndexCheck.documentMapping(file, relative);
            case PQ_CODEBOOK -> VectorIndexCheck.codebook(file, relative);
            case LSH_TABLE -> LshTableCheck.check(file, relative);
            case GRAPH_STAGING_LOG -> GraphStagingCheck.check(file, relative);
            case DOCUMENT_REGISTRY -> CountMarkedTextCheck.check(
                file, relative, "document registry", "\\#document-registry");
            case SOURCE_CATALOG -> CountMarkedTextCheck.check(
                file, relative, "source catalog", "\\#source-catalog");
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
            case UNRECOGNISED -> unverifiable(file, relative, "unrecognised",
                "nothing in this build recognises this file, so it was not read at all. It is "
                    + "not evidence of damage and it is not evidence of health");
            case CATALOG, ROUTING_DIRECTORY -> throw new IllegalStateException(
                "handled by the caller: " + kind);
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
     * Checks the native lexical store, if the directory has one.
     *
     * @param files    Where the manifest's verdict is added
     * @param findings Where missing segments and columns are added
     */
    private void addLexicalStore(List<FileCheck> files, List<ConsistencyFinding> findings) {
        Path store = dataDirectory.resolve(FileClassifier.LEXICAL_STORE_DIRECTORY);
        if (!Files.isDirectory(store)) {
            return;
        }
        String relative = FileClassifier.LEXICAL_STORE_DIRECTORY;
        var inspection = LexicalStoreCheck.check(store, relative);
        files.removeIf(file -> file.relative().equals(inspection.manifest().relative()));
        // The segment verdict covers every column inside the segment directory, so listing
        // those columns again as unchecked would report the same bytes twice and contradict
        // the verdict that did read them.
        for (FileCheck segment : inspection.segments()) {
            String prefix = segment.relative() + "/";
            files.removeIf(file -> file.relative().startsWith(prefix));
        }
        files.add(inspection.manifest());
        files.addAll(inspection.segments());
        findings.addAll(inspection.findings());
    }

    /**
     * Lists every regular file under the data directory, skipping quarantine.
     *
     * @return The files, in walk order
     * @throws IOException if the tree cannot be walked
     */
    private List<Path> listFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(dataDirectory)) {
            for (Path path : stream.toList()) {
                if (Files.isRegularFile(path) && !isQuarantined(path)) {
                    files.add(path);
                }
            }
        }
        return files;
    }

    /**
     * Reports whether a path lies inside the quarantine directory.
     *
     * @param path The path
     * @return true if it has been set aside already
     */
    private boolean isQuarantined(Path path) {
        return quarantineDirectory != null
            && path.toAbsolutePath().normalize()
                .startsWith(quarantineDirectory.toAbsolutePath().normalize());
    }
}
