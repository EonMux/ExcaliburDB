package com.eonmux.excalibur.backup.restore;

import com.eonmux.excalibur.backup.io.AtomicPublish;
import com.eonmux.excalibur.backup.io.FileDigest;
import com.eonmux.excalibur.backup.io.FileDigests;
import com.eonmux.excalibur.backup.io.SparseFileCopy;
import com.eonmux.excalibur.backup.manifest.FileEntry;
import com.eonmux.excalibur.backup.manifest.ManifestFormatException;
import com.eonmux.excalibur.backup.manifest.SnapshotManifest;
import com.eonmux.excalibur.backup.snapshot.SnapshotLayout;
import com.eonmux.excalibur.backup.verify.Problem;
import com.eonmux.excalibur.backup.verify.SnapshotVerifier;
import com.eonmux.excalibur.backup.verify.VerificationReport;
import com.eonmux.excalibur.core.datadir.DataDirectoryContents;
import com.eonmux.excalibur.core.datadir.DataDirectoryLock;
import com.eonmux.excalibur.core.datadir.DataDirectoryLockedException;
import com.eonmux.excalibur.core.datadir.FilesystemEntries;
import com.eonmux.excalibur.core.datadir.ReservedNames;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.store.ObsoleteSidecars;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Rebuilds a data directory from a snapshot.
 *
 * <h2>Nothing is written until everything is proven</h2>
 * <p>The image is verified in full before the target is touched. An image that does not
 * verify is refused outright rather than restored as far as it goes, because a restore
 * that fails halfway has destroyed the thing it was replacing and produced something that
 * cannot be started - the precise failure a verified backup exists to prevent.</p>
 *
 * <h2>Inside the target, never beside it</h2>
 * <p>The new tree is built inside the target, and the target itself is never renamed, created
 * beside, or removed. A data directory is very often a mount point - the container image's
 * volume is one - and a mount point cannot be renamed, while its parent is typically owned by
 * someone else and a sibling would sit on another filesystem, where no rename reaches. Only the
 * target has to be writable, and only the target's filesystem needs room for the image beside
 * what it holds.</p>
 *
 * <h2>What in the target is the filesystem's</h2>
 * <p>A target that is the root of a mounted volume holds that filesystem's {@code lost+found}
 * ({@link FilesystemEntries}), owned by root and movable by no one else. It is not the store's,
 * so it is never moved aside or replaced, it does not make the target hold anything, and it
 * stays where it is through the restore, an unwinding and the recovery of an interrupted one. An
 * image never carries one; if an image an earlier build took does, it is verified with the rest
 * of the image and then not written, since the target's filesystem has its own.</p>
 *
 * <h2>The target has one owner while this runs</h2>
 * <p>The restore takes the target's {@link DataDirectoryLock} before it writes anything and
 * holds it to the end, so a node cannot start on a half-installed directory and a restore cannot
 * run into a directory a node is using: that is refused with
 * {@link RestoreRefusedException.Reason#TARGET_IN_USE}.</p>
 *
 * <h2>What a crash can leave, and what then happens</h2>
 * <p>The image is copied into a staging directory, forced to the device, and then published by
 * moving the target's previous entries into a displaced directory and the staged entries into
 * the target, one rename each. That cannot be one atomic step, so every step is journaled first
 * ({@link RestoreJournal}), and the guarantees are these:</p>
 * <ul>
 *   <li>The previous contents are never deleted until every entry of the image is in place.
 *       Until then they are either in the target or, whole, in the displaced directory.</li>
 *   <li>A failure while publishing - an entry that will not move - is unwound on the spot: the
 *       installed entries go back, the displaced ones return, and the target is as it was.</li>
 *   <li>A crash leaves the journal. While it exists the node refuses to start on the directory,
 *       so a mixture of old and new entries is never served, and the next restore into the
 *       directory finishes what the journal records before doing anything else - forward to the
 *       image once staging was complete, back to the previous contents when it was unwinding,
 *       and discarding a staging directory that was never complete.</li>
 * </ul>
 * <p>What is given up against a rename of the whole directory is only that the switch is
 * visible as a sequence to anyone listing the directory while it runs; nothing that opens it as a
 * data directory can see that sequence.</p>
 *
 * @author ExcaliburDB
 */
public final class SnapshotRestorer {

    /** What a restore calls itself in the target's lock file. */
    static final String LOCK_HOLDER = "excalibur-backup restore";

    /** Checks the image before anything is written. */
    private final SnapshotVerifier verifier = new SnapshotVerifier();

    /** Performs the renames that publish the restored entries. */
    private final EntryMover mover;

    /**
     * Creates a restorer that publishes with atomic renames.
     */
    public SnapshotRestorer() {
        this((source, destination) ->
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE));
    }

    /**
     * Creates a restorer that publishes through the given mover.
     *
     * <p>The seam exists for the failures the journal guards against and no real filesystem
     * can be made to produce on demand: an entry that will not move aside, a staged entry that
     * will not move in, an unwinding that fails as well, a process that dies between two
     * renames. Tests use it to fail exactly those renames while every other one stays real.</p>
     *
     * @param mover Performs every rename the publish makes
     * @throws NullPointerException if mover is null
     */
    SnapshotRestorer(EntryMover mover) {
        this.mover = Objects.requireNonNull(mover, "mover");
    }

    /**
     * Restores a snapshot.
     *
     * @param options What to restore and where
     * @return What now exists, and what still has to be rebuilt
     * @throws RestoreRefusedException if the image does not verify, the target holds
     *                                 something and was not explicitly allowed to be
     *                                 replaced, or another process holds the target; in each
     *                                 case the target's data is untouched
     * @throws IOException             if anything cannot be read or written
     * @throws NullPointerException    if options is null
     */
    public RestoreResult restore(RestoreOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        VerificationReport report = verifyOrRefuse(options);
        SnapshotManifest manifest = report.manifest();
        Path target = options.target();
        requireDirectoryOrAbsent(target);
        boolean created = !Files.exists(target);
        // Before the lock, so that the common refusal - the wrong target - leaves not even a
        // lock file behind. Checked again under the lock, where it is authoritative.
        if (!created && RestoreJournal.read(target).isEmpty()) {
            requireUsableTarget(target, options.overwriteNonEmpty());
        }
        Files.createDirectories(target);
        boolean restored = false;
        DataDirectoryLock lock = claim(target, created);
        try {
            RestoreResult result = restoreUnderLock(options, manifest, target);
            restored = true;
            return result;
        } finally {
            if (created && !restored) {
                removeCreatedTarget(target, lock);
            }
            lock.close();
        }
    }

    /**
     * Completes any interrupted restore, then restores this image.
     *
     * @param options  What to restore and where
     * @param manifest The verified image
     * @param target   The target, which exists and is locked
     * @return What now exists
     * @throws IOException if the restore is refused or fails
     */
    private RestoreResult restoreUnderLock(RestoreOptions options, SnapshotManifest manifest,
                                           Path target) throws IOException {
        List<FileEntry> restored = restorable(manifest);
        Optional<Recovery> recovery = recover(target);
        if (recovery.isPresent() && recovery.get().completed()
                && recovery.get().snapshotId().equals(manifest.snapshotId())) {
            // This very restore was interrupted, and it has now been finished from its journal.
            return new RestoreResult(target, restored.size(), sizeOf(restored), manifest,
                rebuildRequired(manifest), recovery.map(Recovery::describe));
        }
        requireUsableTarget(target, options.overwriteNonEmpty());
        long bytes = install(options.snapshotDir(), target, manifest.snapshotId(), restored);
        return new RestoreResult(target, restored.size(), bytes, manifest,
            rebuildRequired(manifest), recovery.map(Recovery::describe));
    }

    /**
     * Takes the target's lock, turning a holder into a refusal.
     *
     * @param target  The target, which exists
     * @param created Whether this restore created it, and so must remove it on refusal
     * @return The lock
     * @throws RestoreRefusedException if another process holds the target
     * @throws IOException             if the lock cannot be taken
     */
    private static DataDirectoryLock claim(Path target, boolean created) throws IOException {
        try {
            return DataDirectoryLock.acquire(target, LOCK_HOLDER);
        } catch (DataDirectoryLockedException e) {
            throw new RestoreRefusedException(RestoreRefusedException.Reason.TARGET_IN_USE,
                e.getMessage() + " Nothing was restored.");
        } catch (IOException | RuntimeException e) {
            if (created) {
                Files.deleteIfExists(target);
            }
            throw e;
        }
    }

    /**
     * Stages the image inside the target and publishes it over what the target held.
     *
     * @param snapshotDir The image
     * @param target      The target, locked and with no journal
     * @param snapshotId  The image's identifier, recorded in the journal
     * @param entries     The files to restore
     * @return How many logical bytes were written
     * @throws IOException if staging or publishing fails; the target then holds what it held
     *                     before, unless the failure left a journal for the next restore
     */
    private long install(Path snapshotDir, Path target, String snapshotId,
                         List<FileEntry> entries) throws IOException {
        RestoreJournal journal = RestoreJournal.begin(snapshotId);
        journal.write(target);
        long bytes;
        try {
            bytes = populate(snapshotDir, journal.stagingIn(target), entries);
            AtomicPublish.syncTree(journal.stagingIn(target));
        } catch (IOException | RuntimeException e) {
            try {
                AtomicPublish.deleteTree(journal.stagingIn(target));
                RestoreJournal.remove(target);
            } catch (IOException | RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        publish(target, journal);
        return bytes;
    }

    /**
     * Moves the previous entries aside and the staged ones in, unwinding on failure.
     *
     * @param target  The target
     * @param journal The journal, in {@link RestoreJournal.Phase#STAGING} with staging complete
     * @throws IOException if an entry will not move; the target is put back as it was, and the
     *                     failure carries what could not be put back if that failed too
     */
    private void publish(Path target, RestoreJournal journal) throws IOException {
        RestoreJournal displacing = journal.at(RestoreJournal.Phase.DISPLACING);
        displacing.write(target);
        try {
            displace(target, displacing);
        } catch (IOException | RuntimeException e) {
            unwind(target, displacing, e);
            throw e;
        }
        RestoreJournal installing = journal.at(RestoreJournal.Phase.INSTALLING);
        installing.write(target);
        try {
            installStaged(target, installing);
        } catch (IOException | RuntimeException e) {
            unwind(target, installing, e);
            throw e;
        }
        finish(target, installing);
    }

    /**
     * Finishes whatever restore into this target was interrupted, in the direction its journal
     * records.
     *
     * @param target The target, locked
     * @return What was done, or empty when there was no journal
     * @throws IOException if the journal cannot be believed or a step fails again
     */
    private Optional<Recovery> recover(Path target) throws IOException {
        Optional<RestoreJournal> found = RestoreJournal.read(target);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RestoreJournal journal = found.get();
        switch (journal.phase()) {
            case STAGING -> {
                AtomicPublish.deleteTree(journal.stagingIn(target));
                RestoreJournal.remove(target);
                return Optional.of(new Recovery(journal.snapshotId(), false));
            }
            case DISPLACING -> {
                displace(target, journal);
                RestoreJournal installing = journal.at(RestoreJournal.Phase.INSTALLING);
                installing.write(target);
                installStaged(target, installing);
                finish(target, installing);
                return Optional.of(new Recovery(journal.snapshotId(), true));
            }
            case INSTALLING -> {
                installStaged(target, journal);
                finish(target, journal);
                return Optional.of(new Recovery(journal.snapshotId(), true));
            }
            case UNINSTALLING, UNDISPLACING -> {
                putBack(target, journal);
                return Optional.of(new Recovery(journal.snapshotId(), false));
            }
            default -> throw new IllegalStateException("Unknown phase " + journal.phase());
        }
    }

    /**
     * Moves every previous entry of the target into the displaced directory.
     *
     * @param target  The target
     * @param journal The journal, in {@link RestoreJournal.Phase#DISPLACING}
     * @throws IOException if an entry will not move
     */
    private void displace(Path target, RestoreJournal journal) throws IOException {
        Path displaced = Files.createDirectories(journal.displacedIn(target));
        moveEntries(DataDirectoryContents.entries(target), displaced);
        AtomicPublish.syncDirectory(displaced);
        AtomicPublish.syncDirectory(target);
    }

    /**
     * Moves every staged entry into the target.
     *
     * @param target  The target
     * @param journal The journal, in {@link RestoreJournal.Phase#INSTALLING}
     * @throws IOException if an entry will not move
     */
    private void installStaged(Path target, RestoreJournal journal) throws IOException {
        moveEntries(scratchEntries(journal.stagingIn(target)), target);
        AtomicPublish.syncDirectory(target);
    }

    /**
     * Deletes both scratch directories and then the journal, the image being in place.
     *
     * <p>The journal goes last, so a crash in the middle leaves a journal whose recovery is
     * this same deletion.</p>
     *
     * @param target  The target
     * @param journal The journal
     * @throws IOException if either cannot be deleted
     */
    private static void finish(Path target, RestoreJournal journal) throws IOException {
        AtomicPublish.deleteTree(journal.displacedIn(target));
        AtomicPublish.deleteTree(journal.stagingIn(target));
        RestoreJournal.remove(target);
    }

    /**
     * Unwinds a publish that failed part-way, attaching to the failure anything that could not
     * be unwound.
     *
     * @param target  The target
     * @param journal The journal, in the phase that failed
     * @param failure What went wrong
     */
    private void unwind(Path target, RestoreJournal journal, Exception failure) {
        try {
            RestoreJournal back = journal.phase() == RestoreJournal.Phase.INSTALLING
                ? journal.at(RestoreJournal.Phase.UNINSTALLING)
                : journal.at(RestoreJournal.Phase.UNDISPLACING);
            back.write(target);
            putBack(target, back);
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(new IOException("the restore could not be unwound either ("
                + e.getMessage() + "). " + target + " is left with its journal, "
                + journal.displacedIn(target).getFileName() + " holding what it held before and "
                + journal.stagingIn(target).getFileName() + " the image; the node refuses to "
                + "start on it until excalibur-backup restore is run into it again, which "
                + "finishes the unwinding from the journal first", e));
        }
    }

    /**
     * Puts the previous contents back and discards the image.
     *
     * @param target  The target
     * @param journal The journal, in {@link RestoreJournal.Phase#UNINSTALLING} or
     *                {@link RestoreJournal.Phase#UNDISPLACING}
     * @throws IOException if an entry will not move back
     */
    private void putBack(Path target, RestoreJournal journal) throws IOException {
        RestoreJournal undisplacing = journal;
        if (journal.phase() == RestoreJournal.Phase.UNINSTALLING) {
            Files.createDirectories(journal.stagingIn(target));
            moveEntries(DataDirectoryContents.entries(target), journal.stagingIn(target));
            undisplacing = journal.at(RestoreJournal.Phase.UNDISPLACING);
            undisplacing.write(target);
        }
        moveEntries(scratchEntries(undisplacing.displacedIn(target)), target);
        AtomicPublish.syncDirectory(target);
        AtomicPublish.deleteTree(undisplacing.stagingIn(target));
        AtomicPublish.deleteTree(undisplacing.displacedIn(target));
        RestoreJournal.remove(target);
    }

    /**
     * Lists every entry of one of a restore's scratch directories.
     *
     * <p>Everything in it is the restore's to move: the staged image, or what the target held
     * before, which was listed without the target's reserved entries and its filesystem's own.
     * The reserved names are still left out, as they were of every listing before this one.</p>
     *
     * @param scratch The directory; nothing is listed if it is absent
     * @return Its entries, in name order
     * @throws IOException if it cannot be listed
     */
    private static List<Path> scratchEntries(Path scratch) throws IOException {
        if (!Files.isDirectory(scratch)) {
            return List.of();
        }
        try (var listing = Files.list(scratch)) {
            return listing.filter(entry ->
                !ReservedNames.isReserved(entry.getFileName().toString())).sorted().toList();
        }
    }

    /**
     * Moves each of the given entries into a directory, under its own name.
     *
     * @param entries What to move
     * @param to      The directory filled
     * @throws IOException if an entry will not move
     */
    private void moveEntries(List<Path> entries, Path to) throws IOException {
        for (Path entry : entries) {
            mover.move(entry, to.resolve(entry.getFileName().toString()));
        }
    }

    /**
     * Removes a target this restore created, once nothing of the restore is in it.
     *
     * <p>The lock file is deleted while the lock is still held, so a node that reached the
     * directory in the meantime is still refused, and a directory holding anything else - a
     * journal left for the next restore - is left where it is.</p>
     *
     * @param target The target
     * @param lock   The lock this restore holds on it
     */
    private static void removeCreatedTarget(Path target, DataDirectoryLock lock) {
        try (var listing = Files.list(target)) {
            if (listing.anyMatch(entry ->
                    !entry.getFileName().toString().equals(DataDirectoryLock.FILE_NAME))) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        try {
            Files.deleteIfExists(target.resolve(DataDirectoryLock.FILE_NAME));
            Files.deleteIfExists(target);
        } catch (IOException e) {
            // Left behind: an empty directory with a lock file is harmless, and the failure
            // being reported is the restore's own.
        }
    }

    /**
     * Verifies the image, turning any fault into a refusal.
     *
     * @param options What is being restored
     * @return The verification, which carries the manifest
     * @throws RestoreRefusedException if the image cannot be believed
     * @throws IOException             if the image cannot be read
     */
    private VerificationReport verifyOrRefuse(RestoreOptions options) throws IOException {
        VerificationReport report;
        try {
            report = verifier.verify(options.snapshotDir(), options.depth());
        } catch (ManifestFormatException e) {
            throw new RestoreRefusedException(
                RestoreRefusedException.Reason.SNAPSHOT_DOES_NOT_VERIFY,
                "the snapshot's manifest cannot be believed, so nothing about the image can "
                    + "be: " + e.getMessage());
        }
        if (report.isValid()) {
            return report;
        }
        List<Problem> shown = report.problems().subList(0,
            Math.min(report.problems().size(), 10));
        StringBuilder detail = new StringBuilder("the snapshot at ")
            .append(options.snapshotDir()).append(" does not verify, so it has not been ")
            .append("restored and ").append(options.target()).append(" is untouched. ")
            .append(report.problems().size()).append(" problem(s):");
        for (Problem problem : shown) {
            detail.append("\n  ").append(problem.path()).append(": ").append(problem.detail());
        }
        if (report.problems().size() > shown.size()) {
            detail.append("\n  (and ").append(report.problems().size() - shown.size())
                .append(" more)");
        }
        throw new RestoreRefusedException(
            RestoreRefusedException.Reason.SNAPSHOT_DOES_NOT_VERIFY, detail.toString());
    }

    /**
     * Refuses a target that is a file rather than a directory.
     *
     * @param target The target
     * @throws IOException if it exists and is not a directory
     */
    private static void requireDirectoryOrAbsent(Path target) throws IOException {
        if (Files.exists(target) && !Files.isDirectory(target)) {
            throw new IOException("A data directory is a directory, and " + target
                + " is a file");
        }
    }

    /**
     * Refuses a target that holds anything, unless told not to.
     *
     * <p>The reserved entries do not count, and nor does a mounted filesystem's own directory: a
     * directory holding only the lock file a stopped node left, or a freshly made volume holding
     * only its {@code lost+found}, is as empty as one that never held a node.</p>
     *
     * @param target            The data directory to produce, which exists
     * @param overwriteNonEmpty Whether the operator asked for it to be replaced
     * @throws RestoreRefusedException if it holds something and that was not asked for
     * @throws IOException             if it cannot be listed
     */
    private static void requireUsableTarget(Path target, boolean overwriteNonEmpty)
        throws IOException {
        if (overwriteNonEmpty || !DataDirectoryContents.holdsData(target)) {
            return;
        }
        throw new RestoreRefusedException(RestoreRefusedException.Reason.TARGET_NOT_EMPTY,
            target + " is not empty. A restore replaces a data directory wholesale rather "
                + "than merging into one, and most of the time a non-empty target is the "
                + "wrong target. Pass --force to replace what is there.");
    }

    /**
     * The files of the image this build restores: all of them, less the ones it has no use for.
     *
     * <p>An image an earlier build took carries each segment's term sketch,
     * {@code segment_N.tfs}, which nothing in this build reads. It is verified with the rest of
     * the image, since its bytes are part of what the image certifies, and then left out of the
     * restored directory rather than written where the node would remove it as it starts. A file
     * under {@code lost+found/}, which an earlier build could have copied from a volume whose
     * {@code lost+found} it could read, is left out the same way: it was that filesystem's, and
     * the target's filesystem has its own.</p>
     *
     * @param manifest What the image contains
     * @return The entries to write into the restored directory
     */
    private static List<FileEntry> restorable(SnapshotManifest manifest) {
        return manifest.files().stream()
            .filter(entry -> !ObsoleteSidecars.isObsolete(
                entry.path().substring(entry.path().lastIndexOf('/') + 1)))
            .filter(entry -> !FilesystemEntries.isWithinFilesystemEntry(entry.path()))
            .toList();
    }

    /**
     * Sums the logical size of the given entries.
     *
     * @param entries The entries
     * @return Their total size in bytes
     */
    private static long sizeOf(List<FileEntry> entries) {
        return entries.stream().mapToLong(FileEntry::size).sum();
    }

    /**
     * Copies each given file of the image into the staging tree, checking each copy.
     *
     * @param snapshotDir The image
     * @param staging     The tree being built
     * @param entries     The files to write, as the manifest describes them
     * @return How many logical bytes were written
     * @throws IOException if a copy fails or does not read back as the manifest describes
     */
    private static long populate(Path snapshotDir, Path staging, List<FileEntry> entries)
        throws IOException {
        Path payload = snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY);
        Files.createDirectories(staging);
        long bytes = 0L;
        for (FileEntry entry : entries) {
            Path destination = staging.resolve(entry.path());
            SparseFileCopy.copy(payload.resolve(entry.path()), destination);
            FileDigest written = FileDigests.of(destination);
            if (written.size() != entry.size() || !written.crc32c().equals(entry.crc32c())) {
                throw new IOException("Restoring " + entry.path() + " produced "
                    + written.size() + " bytes checksumming to " + written.crc32c()
                    + " where the manifest requires " + entry.size() + " bytes checksumming "
                    + "to " + entry.crc32c() + ". The target's contents have not been "
                    + "modified.");
            }
            bytes += written.size();
        }
        return bytes;
    }

    /**
     * Renames one entry of a directory into another directory on the same filesystem.
     *
     * <p>Every rename the publish makes goes through this, so that it is one call the atomic
     * default makes and a test can intercept.</p>
     */
    @FunctionalInterface
    interface EntryMover {

        /**
         * Renames source to destination.
         *
         * @param source      What to rename
         * @param destination Its new name, which does not exist
         * @throws IOException if the rename fails
         */
        void move(Path source, Path destination) throws IOException;
    }

    /**
     * What finishing an interrupted restore did.
     *
     * @param snapshotId The image the interrupted restore was restoring
     * @param completed  True if it was carried forward to that image, false if it was undone
     */
    private record Recovery(String snapshotId, boolean completed) {

        /**
         * Says what was done, for the operator.
         *
         * @return One sentence
         */
        String describe() {
            return completed
                ? "an interrupted restore of " + snapshotId + " was completed first"
                : "an interrupted restore of " + snapshotId + " was undone first, putting back "
                    + "what the directory held before it";
        }
    }

    /**
     * Names the derived structures the image does not hold.
     *
     * @param manifest What the image contains
     * @return One sentence per thing that has to be rebuilt before the node is whole
     */
    private static List<String> rebuildRequired(SnapshotManifest manifest) {
        List<String> required = new ArrayList<>();
        // Either engine's index: a node reads only the one it is configured for, and an image
        // holds whichever that was.
        boolean hasLexical = manifest.files().stream().map(FileEntry::path).anyMatch(path ->
            path.startsWith(FileClassifier.LEXICAL_STORE_DIRECTORY + "/")
                || path.startsWith(FileClassifier.LUCENE_CHUNK_DIRECTORY + "/"));
        boolean hasCodebooks = manifest.files().stream()
            .anyMatch(entry -> entry.path().endsWith(".pqcb"));
        boolean hasStaging = manifest.files().stream()
            .anyMatch(entry -> entry.path().contains("/graph-staging/"));
        boolean hasGraph = manifest.files().stream()
            .anyMatch(entry -> entry.path().matches("[^/]+/graph/.*"));
        if (!hasLexical) {
            required.add("the lexical chunk index is not in this image; run a reindex before "
                + "expecting keyword results");
        }
        if (hasStaging && !hasGraph) {
            required.add("a tenant's built edge store is not in this image; run a graph build "
                + "from the staging logs before expecting graph results");
        }
        if (!hasCodebooks) {
            required.add("no quantiser codebooks are in this image; they retrain on their own "
                + "as segments warm, at a cost in early query latency");
        }
        return required;
    }
}
