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
 * <p>Which checker a file belongs to is decided by {@link FileInspector}, the same dispatch
 * {@code excalibur-backup verify --deep} uses, so this class owns only what is true of a
 * directory rather than of a file: the walk, the lexical store as a whole, and the checks
 * made between files.</p>
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

    /**
     * Prepares a scan of one directory.
     *
     * <p>Nothing inside the directory is skipped: the quarantine is refused anywhere within
     * it, so every file here is state the node reads.</p>
     *
     * @param dataDirectory The directory to survey
     */
    public DataDirectoryScanner(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
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
            FileInspection inspection =
                FileInspector.inspect(file, FileClassifier.relativise(dataDirectory, file));
            files.add(inspection.check());
            switch (inspection.kind()) {
                case CATALOG -> catalogRows = inspection.catalogRecords();
                case ROUTING_DIRECTORY -> routingSlots = inspection.routingSlots();
                default -> {
                    // Every other kind describes only itself.
                }
            }
        }
        addLexicalStore(files, findings);
        addGraphStores(files);
        files.sort(Comparator.comparing(FileCheck::relative));
        findings.addAll(new CrossFileChecks(dataDirectory).run(files, catalogRows, routingSlots));
        return new DataDirectoryScan(dataDirectory, files, findings, catalogRows, routingSlots);
    }

    /**
     * Checks the native lexical store, if the directory has one.
     *
     * @param files    Where the manifest's verdict is added
     * @param findings Where missing segments and columns are added
     */
    private void addLexicalStore(List<FileCheck> files, List<ConsistencyFinding> findings) {
        FileInspector.lexicalStore(dataDirectory).ifPresent(inspection -> {
            // The segment verdict covers every column inside the segment directory, so
            // listing those columns again as unchecked would report the same bytes twice and
            // contradict the verdict that did read them.
            files.removeIf(file -> inspection.covers(file.relative()));
            files.addAll(inspection.verdicts());
            findings.addAll(inspection.findings());
        });
    }

    /**
     * Checks each tenant's built graph and the directories beside it.
     *
     * @param files Where the graphs' verdicts replace the per-file ones they cover
     */
    private void addGraphStores(List<FileCheck> files) {
        for (GraphStoreCheck.GraphInspection inspection
                : FileInspector.graphStores(dataDirectory)) {
            files.removeIf(file -> inspection.covers(file.relative()));
            files.addAll(inspection.verdicts());
        }
    }

    /**
     * Lists every regular file under the data directory.
     *
     * @return The files, in walk order
     * @throws IOException if the tree cannot be walked
     */
    private List<Path> listFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(dataDirectory)) {
            for (Path path : stream.toList()) {
                if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
        }
        return files;
    }
}
