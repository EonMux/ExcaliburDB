package com.eonmux.excalibur.shard.allocator;

import com.eonmux.excalibur.store.SegmentPlacement;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spreads a storage engine's segments over the configured volumes.
 *
 * <p>{@code excalibur-store} decides nothing about volumes; it asks a
 * {@link SegmentPlacement} where each segment belongs and scans everywhere that placement can
 * name. Turning {@code excalibur.shard.volumes} into such a placement needs the
 * {@link VolumeManager}'s consistent hash ring, so it happens here.</p>
 *
 * <h2>Ordering</h2>
 * <p>Installation must precede {@link StorageEngine#start()}. Start is where the engine
 * discovers what is on disk and creates whatever the base keyspace is missing; a placement
 * installed afterwards would have the engine scan one set of directories and write to another.
 * The engine refuses a late installation outright.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and thread-safe. The engine and volume manager it is handed are not expected to
 * be shared during installation, which happens once while a node is being built.</p>
 */
public final class VolumePlacements {

    private static final Logger LOGGER = LoggerFactory.getLogger(VolumePlacements.class);

    private VolumePlacements() {
        throw new AssertionError("No instances");
    }

    /**
     * Installs a placement spreading the engine's segments over the data directory and the
     * configured volumes.
     *
     * <p>An empty list changes nothing: the engine keeps the single-directory placement it was
     * born with and every file lands exactly where it always did. That is the default, so the
     * overwhelmingly common deployment is byte-for-byte unaffected.</p>
     *
     * <p>A configured volume that is not already a directory is refused, before the volume
     * manager can create it. An unmounted volume quietly replaced by an empty directory on the
     * root filesystem is how a node loses part of its corpus and reports nothing. So is a
     * volume list that names one directory twice under two spellings, for the reasons in
     * {@link #resolveDirectories(Path, List)}.</p>
     *
     * @param storageEngine The engine whose segments are being placed, not yet started
     * @param volumeManager The volume manager that owns the hash ring, may be null only when
     *                      no volumes are configured
     * @param volumePaths   The configured volume directories, possibly empty
     * @throws NullPointerException     if storageEngine or volumePaths is null
     * @throws IllegalArgumentException if a configured volume is not an existing directory, or
     *                                  if two of them resolve to the same directory
     * @throws IllegalStateException    if volumes are configured with no volume manager to
     *                                  place them, or the engine has already started
     */
    public static void install(StorageEngine storageEngine, VolumeManager volumeManager,
                               List<String> volumePaths) {
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        Objects.requireNonNull(volumePaths, "volumePaths cannot be null");
        if (volumePaths.isEmpty()) {
            return;
        }
        if (volumeManager == null) {
            throw new IllegalStateException(
                "excalibur.shard.volumes names " + volumePaths.size() + " volume(s) but there "
                    + "is no volume manager to place segments with");
        }

        List<Path> directories = resolveDirectories(storageEngine.dataDir(), volumePaths);
        volumeManager.registerVolumes(directories);
        storageEngine.withSegmentPlacement(
            SegmentPlacement.across(directories, volumeManager::assignSegmentToVolume));

        LOGGER.info("Segments will be spread across {} volume(s): {}",
            directories.size(), directories);
    }

    /**
     * Resolves the configured volumes into the directory list a restart will scan.
     *
     * <p>A directory has many spellings - a relative path, {@code .} or {@code ..} segments, a
     * symlink onto the same mount - so the list is deduplicated on the directory each entry
     * actually resolves to rather than on the string that was typed. Two spellings registered
     * as two volumes would give the hash ring two positions backed by one disk: nothing fails,
     * segments are placed and found exactly as before, and the disk quietly takes twice the
     * share it was sized for.</p>
     *
     * <p>Two configured volumes naming one directory is refused, because the operator believes
     * they have two. A configured volume that turns out to be the data directory is folded
     * into it instead: the data directory is always the first entry and always holds segments,
     * so naming it adds nothing and removing it from the list costs nothing.</p>
     *
     * @param dataDir     The data directory, always the first entry
     * @param volumePaths The configured volumes
     * @return The distinct directories, data directory first
     * @throws IllegalArgumentException if a volume is not an existing directory, or if two
     *                                  configured volumes resolve to the same directory
     */
    private static List<Path> resolveDirectories(Path dataDir, List<String> volumePaths) {
        List<Path> directories = new ArrayList<>();
        directories.add(dataDir);

        Path dataIdentity = identityOf(dataDir);
        Map<Path, String> claimed = new LinkedHashMap<>();

        for (String configured : volumePaths) {
            Path volume = Path.of(configured);
            if (!Files.isDirectory(volume)) {
                throw new IllegalArgumentException(
                    "Configured volume is not an existing directory: " + volume
                        + ". Mount it before starting; creating it empty would hide every "
                        + "segment already on it.");
            }

            Path identity = identityOf(volume);
            if (identity.equals(dataIdentity)) {
                LOGGER.info("Configured volume '{}' is the data directory {}; it already takes "
                    + "a share of the segments and is not registered a second time",
                    configured, dataDir);
                continue;
            }

            String earlier = claimed.putIfAbsent(identity, configured);
            if (earlier != null) {
                throw new IllegalArgumentException(
                    "excalibur.shard.volumes names one directory twice: '" + earlier + "' and '"
                        + configured + "' both resolve to " + identity + ". Registering it "
                        + "twice puts two positions on the hash ring behind one disk, so that "
                        + "disk silently takes twice the share it was sized for. Remove one of "
                        + "the two.");
            }
            directories.add(identity);
        }
        return List.copyOf(directories);
    }

    /**
     * Returns the directory a path actually names.
     *
     * <p>{@link Path#toRealPath()} is the only answer that sees through a symlink, and a
     * volume is required to exist before this is called, so it is the normal case. The
     * normalised absolute path is the fallback for a directory that vanished between the check
     * and here - a worse answer than none, but better than comparing the strings that were
     * typed.</p>
     *
     * @param path The configured path
     * @return The directory it resolves to
     */
    private static Path identityOf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            LOGGER.warn("Could not resolve {} to a real path; comparing volumes on its "
                + "normalised form instead", path, e);
            return path.toAbsolutePath().normalize();
        }
    }
}
