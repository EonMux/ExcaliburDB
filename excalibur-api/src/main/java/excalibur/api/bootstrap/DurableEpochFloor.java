package excalibur.api.bootstrap;

import io.excalibur.retrieval.lexical.native_.SegmentManifest;
import io.excalibur.store.SegmentCatalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The visibility epoch a restarting node has to resume at.
 *
 * <h2>Why a node cannot start at zero</h2>
 * <p>Visibility is decided by comparing an epoch held in memory against epochs written into
 * durable records. The counter is not itself durable, so on its own it restarts at zero while the
 * records keep the numbers the previous run gave them, and every comparison is then made between
 * two different runs of the same counter.</p>
 *
 * <p>Both directions of that mismatch are silent. A lexical segment published at epoch 9100 is
 * read as written in the future and drops out of every result set, so the node answers queries
 * with nothing while reporting full coverage; the counter climbs a hundred epochs a second, so
 * the corpus reappears by itself hours or days later with no ingestion in between. A segment
 * tombstoned at epoch 6400 is read as tombstoned in the future and comes back to life, which puts
 * documents that were erased back in front of callers.</p>
 *
 * <p>Resuming one past the highest stamp any store carries closes both. It needs no new file and
 * no flush on the write path, and it repairs a data directory written by a version that had this
 * wrong, because the floor is derived from the records themselves.</p>
 *
 * <h2>Adding a store</h2>
 * <p>Every durable store that stamps a visibility epoch into what it writes must be represented
 * in {@link #resumeEpoch}. A store left out of it is a store whose records the node restarts
 * underneath. Stores that record no epoch - the Lucene chunk index, the graph edge columns, whose
 * dates are calendar days rather than visibility epochs - contribute nothing and are absent for
 * that reason.</p>
 */
public final class DurableEpochFloor {

    private static final Logger LOGGER = LoggerFactory.getLogger(DurableEpochFloor.class);

    /** No store carries a stamp. */
    private static final long NOTHING_STAMPED = -1L;

    private DurableEpochFloor() {
    }

    /**
     * The epoch to start the node's visibility counter at.
     *
     * @param dataDirectory the node's data directory
     * @param catalog       the segment catalog, already recovered from disk
     * @return one past the highest epoch any durable store carries, or zero when none does
     */
    public static long resumeEpoch(Path dataDirectory, SegmentCatalog catalog) {
        Objects.requireNonNull(dataDirectory, "dataDirectory must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");

        long highest = Math.max(
            highestCatalogEpoch(catalog),
            highestLexicalEpoch(dataDirectory));

        if (highest <= NOTHING_STAMPED) {
            return 0L;
        }
        // A stamp read back from a file is external input. Advancing past Long.MAX_VALUE would
        // wrap to a negative epoch, which reads as older than everything rather than newer.
        return highest == Long.MAX_VALUE ? Long.MAX_VALUE : highest + 1;
    }

    /**
     * The highest epoch the segment catalog carries.
     *
     * <p>Creation and tombstone epochs are both persisted and both compared against the live
     * counter, so both set the floor.</p>
     */
    private static long highestCatalogEpoch(SegmentCatalog catalog) {
        long highest = NOTHING_STAMPED;
        for (SegmentCatalog.SegmentMetadata metadata : catalog.getAll().values()) {
            highest = Math.max(highest, metadata.getCreationEpoch());
            highest = Math.max(highest, metadata.getTombstoneEpoch());
        }
        return highest;
    }

    /**
     * The highest epoch the native lexical store published a segment at.
     *
     * <p>A manifest that cannot be read contributes nothing rather than failing the start: the
     * lexical store opens by reading the same file, so a manifest unreadable here leaves the
     * store unopened and there is no reader to be blind.</p>
     */
    private static long highestLexicalEpoch(Path dataDirectory) {
        Path store = dataDirectory.resolve(ChunkIndexFactory.NATIVE_DIRECTORY);
        try {
            return SegmentManifest.read(store).latestEpoch();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Lexical manifest at {} could not be read for the epoch floor, "
                + "so it contributes nothing: {}", store, e.toString());
            return NOTHING_STAMPED;
        }
    }
}
