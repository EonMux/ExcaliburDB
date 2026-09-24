package com.eonmux.excalibur.backup.snapshot;

import com.eonmux.excalibur.backup.io.AtomicPublish;
import com.eonmux.excalibur.backup.io.FileDigest;
import com.eonmux.excalibur.backup.io.FileDigests;
import com.eonmux.excalibur.backup.io.SparseFileCopy;
import com.eonmux.excalibur.backup.manifest.Consistency;
import com.eonmux.excalibur.backup.manifest.FileEntry;
import com.eonmux.excalibur.backup.manifest.ManifestCodec;
import com.eonmux.excalibur.backup.manifest.SnapshotManifest;
import com.eonmux.excalibur.core.datadir.DataDirectoryLock;
import com.eonmux.excalibur.core.datadir.DataDirectoryLockedException;
import com.eonmux.excalibur.core.datadir.FilesystemEntries;
import com.eonmux.excalibur.core.datadir.InterruptedRestore;
import com.eonmux.excalibur.core.datadir.LockHolder;
import com.eonmux.excalibur.store.catalog.CatalogFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Takes a consistent, self-describing image of a data directory.
 *
 * <h2>What "consistent" means here</h2>
 * <p>Nothing in this engine orders one file against another. The catalog, the lexical
 * manifest and the two registries are published by atomic rename; the segments, their
 * write-ahead logs and the HNSW graphs are written in place through memory mappings that
 * are forced only on flush and on close. There is no global write barrier and no durable
 * checkpoint. A clean shutdown is the only moment at which every file in the directory
 * describes the same instant, which is why the offline path is the one this tool is built
 * around.</p>
 *
 * <p>So an image is consistent when three things hold. Every file was copied from a
 * directory nothing was writing to. That is established twice: the snapshot takes the data
 * directory's lock ({@link DataDirectoryLock}) before it copies anything - refusing a directory
 * a node holds, and keeping one from starting until the copy is done - and it fingerprints the
 * tree before and after the copy and refuses if anything moved, which still catches a writer
 * that does not take the lock. Each segment's data file and its
 * write-ahead log came from the same moment, which follows from the first, and matters
 * because the {@code .dat} is forced only on flush while every log append is fsynced before
 * it is acknowledged - the pair is the segment, and either half alone is lossy. And each
 * HNSW graph came from the same moment as the document mapping beside it, including any
 * staged pair left by an interrupted compaction, because those two files renamed
 * separately are the recovery record for that interruption.</p>
 *
 * <p>The epoch is recorded rather than chosen. It is read from the durable stamps in the
 * catalog and the lexical manifest, which is the same computation a node performs on open,
 * so it is the epoch a node restoring this image would resume from.</p>
 *
 * @author ExcaliburDB
 */
public final class SnapshotWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotWriter.class);

    /** Stamped into every manifest so an image can say what wrote it. */
    private static final String PRODUCER = "excalibur-backup/"
        + Objects.requireNonNullElse(SnapshotWriter.class.getPackage()
        .getImplementationVersion(), "1.0.0-SNAPSHOT");

    /** What a snapshot calls itself in the data directory's lock file. */
    static final String LOCK_HOLDER = "excalibur-backup snapshot";

    /** Where the timestamp in the manifest comes from. */
    private final Clock clock;

    /** Where the snapshot identifier comes from. */
    private final Supplier<String> identifiers;

    /** Creates a writer stamping real time and random identifiers. */
    public SnapshotWriter() {
        this(Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    /**
     * Creates a writer with the two sources of non-determinism supplied.
     *
     * @param clock       Where the manifest's timestamp comes from
     * @param identifiers Where the snapshot identifier comes from
     * @throws NullPointerException if either argument is null
     */
    public SnapshotWriter(Clock clock, Supplier<String> identifiers) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    /**
     * Takes a snapshot.
     *
     * @param options What to copy and where
     * @return The image and what was left out of it
     * @throws SnapshotRefusedException if another process holds the directory and
     *                                  {@code --allow-live} was not given, the destination is
     *                                  occupied, the directory moved underneath the copy, or a
     *                                  copy did not read back
     * @throws IOException              if a restore into the directory was interrupted, or
     *                                  anything cannot be read or written
     * @throws NullPointerException     if options is null
     */
    public SnapshotResult take(SnapshotOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        Path dataDir = options.dataDir();
        if (!Files.isDirectory(dataDir)) {
            throw new IOException("There is no data directory at " + dataDir);
        }
        Optional<DataDirectoryLock> lock = claim(dataDir, options.allowLive());
        try {
            InterruptedRestore.requireNoneIn(dataDir);
            boolean destinationIsOurs = !Files.exists(options.destination());
            requireUsableDestination(options.destination());
            try {
                return build(options, dataDir);
            } catch (IOException | RuntimeException e) {
                discard(options.destination(), destinationIsOurs, e);
                throw e;
            }
        } finally {
            release(lock);
        }
    }

    /**
     * Takes the data directory's lock for the length of the copy, or learns why it cannot.
     *
     * <p>A directory another process holds is refused - a node is running on it - unless
     * {@code --allow-live} asked for a live image, in which case it is copied under the holder
     * and the image says so. A lock that cannot be taken for any other reason - a read-only
     * mount, a directory this user may read and not write - is not a reason to refuse: the
     * directory is probed without writing, a holder found that way is treated the same, and
     * otherwise the copy goes ahead on the fingerprint alone, which is how every snapshot was
     * taken before there was a lock.</p>
     *
     * @param dataDir   The source
     * @param allowLive Whether a held directory may be copied anyway
     * @return The lock, or empty when it is not held by this snapshot
     * @throws SnapshotRefusedException if another process holds the directory and a live image
     *                                  was not asked for
     */
    private static Optional<DataDirectoryLock> claim(Path dataDir, boolean allowLive)
        throws IOException {
        try {
            return Optional.of(DataDirectoryLock.acquire(dataDir, LOCK_HOLDER));
        } catch (DataDirectoryLockedException e) {
            return inUse(dataDir, e.getMessage(), allowLive);
        } catch (IOException e) {
            Optional<LockHolder> holder;
            try {
                holder = DataDirectoryLock.probe(dataDir);
            } catch (IOException probeFailure) {
                e.addSuppressed(probeFailure);
                LOGGER.warn("Could not take or test the lock on {} ({}). Whether a node is "
                    + "running there is left to the fingerprint of the copy.", dataDir,
                    e.toString());
                return Optional.empty();
            }
            if (holder.isPresent()) {
                return inUse(dataDir, "The data directory " + dataDir + " is in use by "
                    + holder.get().describe() + ".", allowLive);
            }
            LOGGER.warn("Could not take the lock on {} ({}), and no process holds it. The "
                + "snapshot goes ahead; a node that started during the copy would be caught "
                + "only by the fingerprint.", dataDir, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Refuses a held directory, or lets a live snapshot proceed with a warning.
     *
     * @param dataDir   The source
     * @param detail    Who holds it
     * @param allowLive Whether a live image was asked for
     * @return Empty, when the snapshot proceeds without the lock
     * @throws SnapshotRefusedException if a live image was not asked for
     */
    private static Optional<DataDirectoryLock> inUse(Path dataDir, String detail,
                                                     boolean allowLive)
        throws SnapshotRefusedException {
        if (!allowLive) {
            throw new SnapshotRefusedException(SnapshotRefusedException.Reason.SOURCE_IN_USE,
                detail + " A snapshot is taken with the node stopped; stop it and take the "
                    + "snapshot again, or pass --allow-live to accept an image whose files may "
                    + "not agree with one another.");
        }
        LOGGER.warn("{} --allow-live: copying it anyway, and recording the image as {}.",
            detail, Consistency.LIVE_UNSAFE);
        return Optional.empty();
    }

    /**
     * Releases the lock, if this snapshot held one.
     *
     * @param lock The lock, or empty
     */
    private static void release(Optional<DataDirectoryLock> lock) {
        lock.ifPresent(held -> {
            try {
                held.close();
            } catch (IOException e) {
                LOGGER.warn("Could not release the lock on {}: {}. It is released when this "
                    + "process exits.", held.directory(), e.toString());
            }
        });
    }

    /**
     * Copies the directory and publishes the manifest describing it.
     *
     * @param options What to copy and where
     * @param dataDir The source, already checked to exist
     * @return The image and what was left out of it
     * @throws IOException if anything cannot be read or written, or the snapshot is refused
     */
    private SnapshotResult build(SnapshotOptions options, Path dataDir) throws IOException {
        Instant startedAt = clock.instant();
        List<ClassifiedFile> inventory = DataDirectoryInventory.of(dataDir);
        DirectoryFingerprint before = QuiescenceProbe.fingerprint(dataDir);
        Path payload = Files.createDirectories(
            options.destination().resolve(SnapshotLayout.PAYLOAD_DIRECTORY));
        List<FileEntry> copied = copyAll(dataDir, payload, inventory, options.includeDerived());

        // Drawn here rather than at the top: the identifier names the image, and until the
        // payload is written there is no image for it to name.
        String snapshotId = identifiers.get();
        Consistency consistency = settleConsistency(dataDir, before, options.allowLive());
        EpochStamp epoch = DurableEpoch.read(dataDir);
        SnapshotManifest manifest = new SnapshotManifest(ManifestCodec.MANIFEST_VERSION,
            snapshotId, startedAt, PRODUCER, dataDir.toAbsolutePath().toString(),
            epoch.epoch(), epoch.source(), consistency, formatVersions(), copied);

        AtomicPublish.syncTree(payload);
        ManifestCodec.write(manifest, options.destination().resolve(ManifestCodec.FILE_NAME));
        AtomicPublish.syncDirectory(options.destination());
        return new SnapshotResult(options.destination(), manifest,
            skipped(inventory, options.includeDerived()));
    }

    /**
     * Removes what a failed snapshot wrote.
     *
     * <p>A refused image is worth nothing - it has no manifest, so no later run can say
     * what it holds - and leaving it behind both wastes the disk and makes the obvious
     * retry to the same destination fail for the wrong reason. A destination the operator
     * had already created is emptied rather than removed, because it was theirs first.</p>
     *
     * @param destination          Where the image was going
     * @param destinationIsOurs    Whether this run created the destination itself
     * @param failure              What went wrong, so a failed cleanup is attached to it
     */
    private static void discard(Path destination, boolean destinationIsOurs,
                                Exception failure) {
        try {
            AtomicPublish.deleteTree(destination.resolve(SnapshotLayout.PAYLOAD_DIRECTORY));
            Files.deleteIfExists(destination.resolve(ManifestCodec.FILE_NAME));
            if (destinationIsOurs) {
                Files.deleteIfExists(destination);
            }
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
            LOGGER.warn("Could not remove the partial snapshot at {}: {}. It has no manifest, "
                + "so nothing will mistake it for an image, but it is occupying disk.",
                destination, e.toString());
        }
    }

    /**
     * Refuses a destination that already holds anything.
     *
     * <p>A snapshot is never a merge. A destination holding files from an earlier image
     * would produce a directory the manifest describes only part of, and verification
     * would then be reporting on a mixture. The {@code lost+found} of a volume mounted there is
     * the filesystem's rather than an earlier image's ({@link FilesystemEntries}), so a freshly
     * made backup volume is an empty destination; the image is written beside it and never
     * reads it.</p>
     *
     * @param destination Where the image is to go
     * @throws SnapshotRefusedException if it exists and is not empty
     * @throws IOException              if it cannot be created or listed
     */
    private static void requireUsableDestination(Path destination) throws IOException {
        if (!Files.exists(destination)) {
            Files.createDirectories(destination);
            return;
        }
        if (!Files.isDirectory(destination)) {
            throw new IOException("A snapshot goes in a directory, and " + destination
                + " is a file");
        }
        try (var entries = Files.list(destination)) {
            if (entries.anyMatch(entry -> !FilesystemEntries.isFilesystemEntry(entry))) {
                throw new SnapshotRefusedException(
                    SnapshotRefusedException.Reason.DESTINATION_NOT_EMPTY,
                    destination + " already holds something. A snapshot never merges into an "
                        + "existing image; choose an empty directory or a new name.");
            }
        }
    }

    /**
     * Copies every file the inventory says belongs in the image.
     *
     * @param dataDir        The source
     * @param payload        The payload root inside the snapshot
     * @param inventory      Every file and what it is
     * @param includeDerived Whether rebuildable derived state is copied
     * @return One manifest entry per copied file, in inventory order
     * @throws IOException if a copy fails or does not read back as the original
     */
    private static List<FileEntry> copyAll(Path dataDir, Path payload,
                                           List<ClassifiedFile> inventory,
                                           boolean includeDerived) throws IOException {
        List<FileEntry> entries = new ArrayList<>();
        for (ClassifiedFile file : inventory) {
            if (!file.inclusion().includedWhen(includeDerived)) {
                continue;
            }
            entries.add(copyOne(dataDir.resolve(file.path()), payload.resolve(file.path()),
                file.path()));
        }
        return entries;
    }

    /**
     * Copies one file and proves the copy equal to its source.
     *
     * <p>Both are digested. A backup whose copies were never read back is a backup whose
     * first proof of correctness is the restore, and the point of this tool is that the
     * restore is not the first proof of anything.</p>
     *
     * @param source       The file in the data directory
     * @param destination  Where it goes in the payload
     * @param relativePath Its name in the manifest
     * @return The manifest entry for it
     * @throws IOException if either file cannot be read, or they differ
     */
    private static FileEntry copyOne(Path source, Path destination, String relativePath)
        throws IOException {
        SparseFileCopy.copy(source, destination);
        FileDigest original = FileDigests.of(source);
        FileDigest copy = FileDigests.of(destination);
        if (!original.equals(copy)) {
            throw new SnapshotRefusedException(
                SnapshotRefusedException.Reason.COPY_DID_NOT_MATCH,
                relativePath + " did not read back as itself: the source is " + original.size()
                    + " bytes checksumming to " + original.crc32c() + " and the copy is "
                    + copy.size() + " bytes checksumming to " + copy.crc32c()
                    + ". Either the source is being written to, or the storage is faulty.");
        }
        return new FileEntry(relativePath, copy.size(), copy.crc32c(), copy.sha256());
    }

    /**
     * Decides what the image can honestly claim about its own internal agreement.
     *
     * @param dataDir   The source
     * @param before    What the directory looked like before the copy
     * @param allowLive Whether a directory that moved is tolerated rather than refused
     * @return What the manifest should say
     * @throws SnapshotRefusedException if the directory moved and that is not tolerated
     * @throws IOException              if the directory cannot be walked again
     */
    private static Consistency settleConsistency(Path dataDir, DirectoryFingerprint before,
                                                 boolean allowLive) throws IOException {
        List<String> differences =
            QuiescenceProbe.differences(before, QuiescenceProbe.fingerprint(dataDir));
        if (differences.isEmpty() && !allowLive) {
            return Consistency.OFFLINE;
        }
        if (differences.isEmpty()) {
            LOGGER.warn("Nothing in {} moved while it was being copied, but --allow-live "
                + "means this tool was not asked to insist on that. A page dirtied through "
                + "a mapping need not touch a timestamp, so the image is recorded as {}.",
                dataDir, Consistency.LIVE_UNSAFE);
            return Consistency.LIVE_UNSAFE;
        }
        String detail = dataDir + " changed while it was being copied, so a node is running "
            + "on it and this image is torn: " + String.join("; ",
            differences.subList(0, Math.min(differences.size(), 5)))
            + (differences.size() > 5 ? " (and " + (differences.size() - 5) + " more)" : "");
        if (!allowLive) {
            throw new SnapshotRefusedException(
                SnapshotRefusedException.Reason.SOURCE_CHANGED_UNDERNEATH, detail
                + ". Stop the node and take it again, or pass --allow-live to accept an "
                + "image whose files may not agree with one another.");
        }
        LOGGER.warn("{}. The manifest records this image as {}.", detail,
            Consistency.LIVE_UNSAFE);
        return Consistency.LIVE_UNSAFE;
    }

    /**
     * Returns the on-disk format versions this image was written against.
     *
     * @return Structure name to version
     */
    private static Map<String, String> formatVersions() {
        return Map.of(
            "snapshot-manifest", Integer.toString(ManifestCodec.MANIFEST_VERSION),
            "snapshot-layout", SnapshotLayout.LAYOUT_VERSION,
            "segment-catalog", Integer.toString(CatalogFile.VERSION));
    }

    /**
     * Returns every file the image deliberately does not hold.
     *
     * @param inventory      Every file in the data directory
     * @param includeDerived Whether rebuildable derived state was copied
     * @return The files that were left out
     */
    private static List<ClassifiedFile> skipped(List<ClassifiedFile> inventory,
                                                boolean includeDerived) {
        return inventory.stream()
            .filter(file -> !file.inclusion().includedWhen(includeDerived)).toList();
    }
}
