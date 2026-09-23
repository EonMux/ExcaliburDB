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
import com.eonmux.excalibur.fsck.check.FileKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Rebuilds a data directory from a snapshot.
 *
 * <h2>Nothing is written until everything is proven</h2>
 * <p>The image is verified in full before the target is touched. An image that does not
 * verify is refused outright rather than restored as far as it goes, because a restore
 * that fails halfway has destroyed the thing it was replacing and produced something that
 * cannot be started - the precise failure a verified backup exists to prevent.</p>
 *
 * <h2>Stage and swap</h2>
 * <p>The new tree is built beside the target, under a name carrying
 * {@link SnapshotLayout#STAGING_INFIX}, forced to the device, and then renamed on in one
 * move - the same way every durable file in this codebase is published. A target that
 * already exists is renamed out of the way first and deleted only once the new tree is in
 * place, so at no moment is the target a blend of the old contents and the new, and a
 * crash at any point leaves either the old directory or the new one.</p>
 *
 * <p>Staging in the target's own parent is deliberate: a rename is only atomic within a
 * filesystem, and a data directory is frequently a mount of its own.</p>
 *
 * @author ExcaliburDB
 */
public final class SnapshotRestorer {

    /** Checks the image before anything is written. */
    private final SnapshotVerifier verifier = new SnapshotVerifier();

    /** Performs the renames that swap the restored tree into place. */
    private final DirectoryMover mover;

    /**
     * Creates a restorer that swaps with atomic renames.
     */
    public SnapshotRestorer() {
        this((source, destination) ->
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE));
    }

    /**
     * Creates a restorer that swaps through the given mover.
     *
     * <p>The seam exists for the one failure the swap guards against and no real
     * filesystem can be made to produce on demand: the old directory renamed aside and the
     * new one refused its name. Tests use it to fail exactly that rename, and the rename that
     * puts the old directory back, while every other rename stays real.</p>
     *
     * @param mover Performs every rename the swap makes
     * @throws NullPointerException if mover is null
     */
    SnapshotRestorer(DirectoryMover mover) {
        this.mover = Objects.requireNonNull(mover, "mover");
    }

    /**
     * Restores a snapshot.
     *
     * @param options What to restore and where
     * @return What now exists, and what still has to be rebuilt
     * @throws RestoreRefusedException if the image does not verify, or the target holds
     *                                 something and was not explicitly allowed to be
     *                                 replaced; in both cases the target is untouched
     * @throws IOException             if anything cannot be read or written
     * @throws NullPointerException    if options is null
     */
    public RestoreResult restore(RestoreOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        VerificationReport report = verifyOrRefuse(options);
        requireUsableTarget(options.target(), options.overwriteNonEmpty());

        Path parent = parentOf(options.target());
        Files.createDirectories(parent);
        Path staging = parent.resolve(options.target().getFileName() + SnapshotLayout.
            STAGING_INFIX + UUID.randomUUID());
        SnapshotManifest manifest = report.manifest();
        long bytes;
        try {
            bytes = populate(options.snapshotDir(), staging, manifest);
            AtomicPublish.syncTree(staging);
            swapIntoPlace(staging, options.target(), parent);
        } catch (IOException | RuntimeException e) {
            AtomicPublish.deleteTree(staging);
            throw e;
        }
        return new RestoreResult(options.target(), manifest.files().size(), bytes, manifest,
            rebuildRequired(manifest));
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
     * Refuses a target that holds anything, unless told not to.
     *
     * @param target            The data directory to produce
     * @param overwriteNonEmpty Whether the operator asked for it to be replaced
     * @throws RestoreRefusedException if it holds something and that was not asked for
     * @throws IOException             if it exists and is not a directory, or cannot be
     *                                 listed
     */
    private static void requireUsableTarget(Path target, boolean overwriteNonEmpty)
        throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        if (!Files.isDirectory(target)) {
            throw new IOException("A data directory is a directory, and " + target
                + " is a file");
        }
        try (var entries = Files.list(target)) {
            if (entries.findAny().isEmpty() || overwriteNonEmpty) {
                return;
            }
        }
        throw new RestoreRefusedException(RestoreRefusedException.Reason.TARGET_NOT_EMPTY,
            target + " is not empty. A restore replaces a data directory wholesale rather "
                + "than merging into one, and most of the time a non-empty target is the "
                + "wrong target. Pass --force to replace what is there.");
    }

    /**
     * Copies every file the manifest names into the staging tree, checking each copy.
     *
     * @param snapshotDir The image
     * @param staging     The tree being built
     * @param manifest    What the image contains
     * @return How many logical bytes were written
     * @throws IOException if a copy fails or does not read back as the manifest describes
     */
    private static long populate(Path snapshotDir, Path staging, SnapshotManifest manifest)
        throws IOException {
        Path payload = snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY);
        Files.createDirectories(staging);
        long bytes = 0L;
        for (FileEntry entry : manifest.files()) {
            Path destination = staging.resolve(entry.path());
            SparseFileCopy.copy(payload.resolve(entry.path()), destination);
            FileDigest written = FileDigests.of(destination);
            if (written.size() != entry.size() || !written.crc32c().equals(entry.crc32c())) {
                throw new IOException("Restoring " + entry.path() + " produced "
                    + written.size() + " bytes checksumming to " + written.crc32c()
                    + " where the manifest requires " + entry.size() + " bytes checksumming "
                    + "to " + entry.crc32c() + ". The target has not been modified.");
            }
            bytes += written.size();
        }
        return bytes;
    }

    /**
     * Renames the staged tree onto the target, moving anything already there aside first.
     *
     * @param staging The fully written tree
     * @param target  Where it belongs
     * @param parent  The directory both live in
     * @throws IOException if a rename fails
     */
    private void swapIntoPlace(Path staging, Path target, Path parent)
        throws IOException {
        Path displaced = null;
        if (Files.exists(target)) {
            displaced = parent.resolve(target.getFileName() + SnapshotLayout.STAGING_INFIX
                + "replaced-" + UUID.randomUUID());
            mover.move(target, displaced);
        }
        try {
            mover.move(staging, target);
        } catch (IOException | RuntimeException e) {
            restoreDisplaced(displaced, target, e);
            throw e;
        }
        AtomicPublish.syncDirectory(parent);
        if (displaced != null) {
            AtomicPublish.deleteTree(displaced);
            AtomicPublish.syncDirectory(parent);
        }
    }

    /**
     * Puts a displaced target back after a swap that did not happen.
     *
     * <p>The window between renaming the old directory aside and renaming the new one on
     * is the only moment at which the target does not exist. Nothing is expected to fail
     * there - both names are in one directory on one filesystem - but if it does, leaving
     * the operator with no data directory at all is the one outcome worse than refusing.</p>
     *
     * @param displaced Where the old directory was moved to, or null if there was none
     * @param target    Where it belongs
     * @param failure   What went wrong, so a failed rollback is attached to it
     */
    private void restoreDisplaced(Path displaced, Path target, Exception failure) {
        if (displaced == null) {
            return;
        }
        try {
            mover.move(displaced, target);
        } catch (IOException | RuntimeException e) {
            IOException rollback = new IOException("the previous contents of " + target
                + " could not be put back and are intact at " + displaced
                + "; rename that directory to " + target.getFileName()
                + " to recover them: " + e.getMessage(), e);
            failure.addSuppressed(rollback);
        }
    }

    /**
     * Renames a directory within one parent.
     *
     * <p>Every rename the swap makes goes through this, so that it is one call the atomic
     * default makes and a test can intercept.</p>
     */
    @FunctionalInterface
    interface DirectoryMover {

        /**
         * Renames source to destination.
         *
         * @param source      What to rename
         * @param destination Its new name, in the same directory
         * @throws IOException if the rename fails
         */
        void move(Path source, Path destination) throws IOException;
    }

    /**
     * Names the derived structures the image does not hold.
     *
     * @param manifest What the image contains
     * @return One sentence per thing that has to be rebuilt before the node is whole
     */
    private static List<String> rebuildRequired(SnapshotManifest manifest) {
        List<String> required = new ArrayList<>();
        boolean hasLexical = manifest.files().stream()
            .anyMatch(entry -> entry.path().startsWith("chunk-index/"));
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
        boolean hasSegments = manifest.files().stream()
            .anyMatch(entry -> FileKind.SEGMENT_DATA_NAME.matcher(entry.path()).matches());
        boolean hasSketches = manifest.files().stream()
            .anyMatch(entry -> FileKind.TERM_SKETCH_NAME.matcher(entry.path()).matches());
        if (hasSegments && !hasSketches) {
            required.add("no term sketches are in this image; the node rebuilds each from its "
                + "segment's live entries as it opens the segment, at a cost in start-up time");
        }
        return required;
    }

    /**
     * Returns the directory a target lives in, treating a bare name as living here.
     *
     * @param target The target
     * @return Its parent
     */
    private static Path parentOf(Path target) {
        Path parent = target.toAbsolutePath().getParent();
        return parent != null ? parent : target.toAbsolutePath().getRoot();
    }
}
