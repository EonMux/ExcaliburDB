package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.core.datadir.DataDirectoryContents;
import com.eonmux.excalibur.core.datadir.FilesystemEntries;
import com.eonmux.excalibur.core.datadir.InterruptedRestore;
import com.eonmux.excalibur.core.datadir.ReservedNames;

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

    /** The kind of the finding an interrupted restore's journal raises. */
    public static final String INTERRUPTED_RESTORE = "interrupted-restore";

    /** The kind of the finding a restore's scratch directory with no journal raises. */
    public static final String RESTORE_SCRATCH = "restore-scratch";

    /**
     * Prepares a scan of one directory.
     *
     * <p>Nothing the node reads is skipped: the quarantine is refused anywhere within the
     * directory, so every file here is state. The entries that are not state are the ones
     * {@link ReservedNames} names - the lock file, and a restore's journal and scratch - and
     * those are left out of the per-file report: the lock describes a process, and a restore's
     * scratch is a second copy of a data directory, each of whose files would otherwise be
     * judged as though the node read it. What a restore left behind is reported instead as one
     * finding about the restore. The {@code lost+found} of a volume mounted at the directory
     * ({@link FilesystemEntries}) is not surveyed or reported at all: it is the filesystem's, and
     * the node's user cannot open it.</p>
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

        findings.addAll(restoreFindings());
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
     * Lists every regular file under the data directory that holds data.
     *
     * @return The files, in walk order, less the reserved entries and the filesystem's own
     * @throws IOException if the tree cannot be walked
     */
    private List<Path> listFiles() throws IOException {
        return DataDirectoryContents.regularFiles(dataDirectory);
    }

    /**
     * Reports what a restore left in the directory.
     *
     * <p>A journal is damage: it is written before a restore stages anything and removed only
     * once the restore is complete, so its presence means the directory may be any mixture of
     * its old contents and the image, and the node refuses to start on it until the restore is
     * run again. Scratch with no journal beside it is only disk: the journal is removed after the
     * scratch, so this is scratch whose removal failed, or a journal someone deleted by hand -
     * in either case nothing reads it.</p>
     *
     * @return One finding per entry a restore left, in name order
     * @throws IOException if the directory cannot be listed
     */
    private List<ConsistencyFinding> restoreFindings() throws IOException {
        boolean journal = InterruptedRestore.journalIn(dataDirectory).isPresent();
        List<ConsistencyFinding> findings = new ArrayList<>();
        if (journal) {
            findings.add(new ConsistencyFinding(INTERRUPTED_RESTORE,
                InterruptedRestore.JOURNAL_FILE_NAME, FindingSeverity.DAMAGE,
                "an excalibur-backup restore into this directory did not finish, so its files "
                    + "may be a mixture of what was here and the image being restored, and the "
                    + "node refuses to start on it. Run excalibur-backup restore into this "
                    + "directory again: it completes the interrupted restore from the journal. "
                    + "The restore's scratch directories are not checked here"));
        }
        try (var entries = Files.list(dataDirectory)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                boolean scratch = name.startsWith(InterruptedRestore.STAGING_PREFIX)
                    || name.startsWith(InterruptedRestore.DISPLACED_PREFIX);
                if (scratch && !journal) {
                    findings.add(new ConsistencyFinding(RESTORE_SCRATCH, name,
                        FindingSeverity.WARNING,
                        "a restore's scratch directory with no journal beside it: the restore "
                            + "finished or was unwound and its scratch was not removed. Nothing "
                            + "reads it and it is not checked; delete it to reclaim the disk"));
                }
            }
        }
        return findings;
    }
}
