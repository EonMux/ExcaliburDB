package excalibur.backup.snapshot;

import excalibur.backup.manifest.EpochSource;
import io.excalibur.retrieval.lexical.native_.SegmentManifest;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.catalog.CatalogFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Reads the epoch a data directory is coherent at, without starting a node on it.
 *
 * <h2>Where the number comes from</h2>
 * <p>There is no durable epoch counter. The global epoch is an {@code AtomicLong} that
 * advances on every write and on every tick of the epoch scheduler, and it dies with the
 * process. What survives is the epochs <em>stamped</em> on things: a segment's tombstone
 * epoch in the catalog, and the epoch a lexical batch was published at in the chunk
 * index's manifest. On open, the node recomputes its counter as one past the highest stamp
 * it can find, because a counter that resumed lower would read a segment tombstoned at
 * 6400 as tombstoned in the future and bring it back to life.</p>
 *
 * <p>This reads the same stamps from the same two files. The epoch it reports is therefore
 * not a number invented for the manifest: it is the epoch a node opening this image would
 * resume from, and it is the only sense in which a directory can be said to be "at" an
 * epoch at all.</p>
 *
 * <h2>Why the catalog is copied before it is read</h2>
 * <p>{@link CatalogFile} maps its file read-write and may repair a header as it opens.
 * Doing that to the directory being snapshotted would modify the thing being copied, and
 * would then be reported by the quiescence check as a node writing. So the catalog is
 * copied to a scratch file, read there and thrown away. It is sixty kilobytes.</p>
 *
 * @author ExcaliburDB
 */
public final class DurableEpoch {

    /** The segment catalog's name inside a data directory. */
    public static final String CATALOG_FILE = "catalog.dat";

    /** The lexical index's directory inside a data directory. */
    public static final String LEXICAL_DIRECTORY = "chunk-index";

    private DurableEpoch() {
    }

    /**
     * Reads the highest epoch stamped anywhere in a data directory.
     *
     * @param dataDir The data directory; not modified
     * @return The epoch and where it was read from
     * @throws IOException          if scratch space cannot be created
     * @throws NullPointerException if dataDir is null
     */
    public static EpochStamp read(Path dataDir) throws IOException {
        Objects.requireNonNull(dataDir, "dataDir");
        Path catalog = dataDir.resolve(CATALOG_FILE);
        if (!Files.isRegularFile(catalog)) {
            return lexicalOnly(dataDir);
        }
        long catalogEpoch;
        try {
            catalogEpoch = highestCatalogStamp(catalog);
        } catch (IOException | RuntimeException e) {
            return new EpochStamp(0L, EpochSource.UNREADABLE, "the segment catalog at "
                + catalog + " could not be read (" + e + "), so the epoch this image is "
                + "coherent at is unknown; verify it deeply before relying on it");
        }
        long lexicalEpoch = highestLexicalStamp(dataDir);
        long highest = Math.max(catalogEpoch, lexicalEpoch);
        if (highest < 0) {
            return new EpochStamp(0L, EpochSource.NOTHING_STAMPED, "nothing in " + dataDir
                + " carries an epoch stamp yet, so a node opening it resumes at epoch 0");
        }
        return new EpochStamp(highest, lexicalEpoch >= 0
            ? EpochSource.CATALOG_AND_LEXICAL_STAMPS : EpochSource.CATALOG_STAMPS,
            "the highest epoch stamped on the catalog and the lexical index is " + highest
                + ", which is the epoch a node opening this image is coherent at");
    }

    /**
     * Reads the epoch of a directory that has no catalog yet.
     *
     * @param dataDir The data directory
     * @return The epoch and where it was read from
     */
    private static EpochStamp lexicalOnly(Path dataDir) {
        long lexicalEpoch = highestLexicalStamp(dataDir);
        if (lexicalEpoch < 0) {
            return new EpochStamp(0L, EpochSource.NOTHING_STAMPED, "there is no segment "
                + "catalog in " + dataDir + " and nothing else carries an epoch stamp, so a "
                + "node opening it resumes at epoch 0");
        }
        return new EpochStamp(lexicalEpoch, EpochSource.CATALOG_AND_LEXICAL_STAMPS,
            "there is no segment catalog; the lexical index's latest published epoch is "
                + lexicalEpoch);
    }

    /**
     * Returns the highest creation or tombstone epoch in the catalog.
     *
     * @param catalog The catalog file, which is copied rather than opened in place
     * @return The highest stamp, or -1 if nothing is stamped
     * @throws IOException if the copy or the read fails
     */
    private static long highestCatalogStamp(Path catalog) throws IOException {
        Path scratch = Files.createTempFile("excalibur-backup-catalog", ".dat");
        try {
            Files.copy(catalog, scratch, StandardCopyOption.REPLACE_EXISTING);
            try (CatalogFile file = new CatalogFile(scratch)) {
                List<SegmentCatalog.SegmentMetadata> records = file.readAll();
                long highest = -1L;
                for (SegmentCatalog.SegmentMetadata record : records) {
                    highest = Math.max(highest, record.getCreationEpoch());
                    highest = Math.max(highest, record.getTombstoneEpoch());
                }
                return highest;
            }
        } finally {
            Files.deleteIfExists(scratch);
        }
    }

    /**
     * Returns the epoch of the most recently published lexical batch.
     *
     * <p>A manifest that is absent, empty or unreadable means the lexical index has
     * published nothing this can learn from, which is not an error: a node with no lexical
     * index is a node whose epoch comes from the catalog alone.</p>
     *
     * @param dataDir The data directory
     * @return The latest published epoch, or -1 if there is none
     */
    private static long highestLexicalStamp(Path dataDir) {
        Path lexical = dataDir.resolve(LEXICAL_DIRECTORY);
        if (!Files.isDirectory(lexical)) {
            return -1L;
        }
        try {
            long latest = SegmentManifest.read(lexical).latestEpoch();
            return latest > 0 ? latest : -1L;
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }
}
