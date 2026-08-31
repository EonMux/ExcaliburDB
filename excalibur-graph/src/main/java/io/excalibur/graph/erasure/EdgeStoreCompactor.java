package io.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.store.EdgeStoreWriter;
import io.excalibur.graph.store.MappedEdgeStore;

import org.roaringbitmap.RoaringBitmap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes erased edges from disk by rewriting the store without them.
 *
 * <p>A tombstone is not a deletion. Marking an edge invisible satisfies a query and satisfies
 * nobody else: the subject, the object, the relation, the source document id and the extraction
 * confidence are all still sitting in the column files, readable by anyone with the disk. For an
 * erasure obligation that is not compliance, it is a filter. The data has to stop existing, which
 * means rewriting the store.
 *
 * <h2>How the swap works</h2>
 * The compacted store is built in a sibling directory and moved into place atomically, so the store
 * is never partially replaced. The old mapping is unmapped only after the swap, because a reader
 * mid-query still holds it — closing first would turn every in-flight read into an error, and
 * unmapping while a reader is inside would be worse.
 *
 * <p>A crash before the move leaves the original untouched and a stray temporary directory; a crash
 * after it leaves the compacted store, which is the intended outcome. There is no window in which
 * the store is neither.
 */
public final class EdgeStoreCompactor {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdgeStoreCompactor.class);

    private static final String TEMP_SUFFIX = ".compacting";

    private EdgeStoreCompactor() {
    }

    /**
     * Which forward edge indices the requests cover.
     *
     * <p>Resolved by one pass over the store rather than one per request: erasure requests arrive in
     * batches and a pass each would read the whole store once per request.
     */
    public static RoaringBitmap resolve(MappedEdgeStore store,
                                        Collection<ErasureRequest> requests) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (requests == null || requests.isEmpty()) {
            return new RoaringBitmap();
        }

        RoaringBitmap tombstoned = new RoaringBitmap();
        store.forEachEdge((subject, relation, object, index) -> {
            for (ErasureRequest request : requests) {
                if (covers(request.scope(), store, subject, object, index)) {
                    tombstoned.add((int) index);
                    return;
                }
            }
        });
        return tombstoned;
    }

    private static boolean covers(ErasureScope scope, MappedEdgeStore store,
                                  int subject, int object, long index) {
        return switch (scope) {
            // Both endpoints. Erasing a person means erasing what they did and what was done to
            // them; keeping the incoming edges would leave them reconstructible from neighbours.
            case ErasureScope.Entity entity ->
                subject == entity.entityId() || object == entity.entityId();
            case ErasureScope.Source source -> {
                Provenance provenance = store.provenanceOf(index);
                yield provenance.sourceType() == source.sourceType()
                    && provenance.sourceId() == source.sourceId();
            }
            case ErasureScope.ExtractorVersion version ->
                store.provenanceOf(index).extractorVersion() == version.version();
        };
    }

    /**
     * Rewrites the store at {@code directory} without the tombstoned edges.
     *
     * <p>Closes {@code store} once the swap is done. The caller must not use it afterwards and
     * should reopen from the same directory.
     *
     * @param store      the open store, which this closes
     * @param directory  where it lives
     * @param tombstoned forward edge indices to drop
     * @return how many edges were removed
     */
    public static long compact(MappedEdgeStore store, Path directory, RoaringBitmap tombstoned)
            throws IOException {
        if (store == null || directory == null || tombstoned == null) {
            throw new IllegalArgumentException("store, directory and tombstoned are all required");
        }
        long entityCount = store.entityCount();
        long before = store.edgeCount();

        if (tombstoned.isEmpty()) {
            LOGGER.debug("nothing tombstoned at {}; skipping compaction", directory);
            return 0;
        }

        Path temp = directory.resolveSibling(directory.getFileName() + TEMP_SUFFIX);
        deleteRecursively(temp);
        Files.createDirectories(temp);

        EdgeStoreWriter writer = new EdgeStoreWriter();
        store.forEachEdge((subject, relation, object, index) -> {
            if (tombstoned.contains((int) index)) {
                return;
            }
            writer.add(subject, relation, object,
                store.validFrom(index), store.validTo(index), store.provenanceOf(index));
        });
        // Entity count is preserved rather than recomputed. Compaction removes edges, not the id
        // space: renumbering entities here would invalidate every id held anywhere else.
        writer.build(temp, entityCount);

        // The old mapping stays live until the directories have swapped, so a reader mid-query is
        // reading a store that still exists.
        Path retired = directory.resolveSibling(directory.getFileName() + ".retired");
        deleteRecursively(retired);
        Files.move(directory, retired, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(temp, directory, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Put the original back rather than leaving no store at all.
            Files.move(retired, directory, StandardCopyOption.ATOMIC_MOVE);
            throw e;
        }

        store.close();
        deleteRecursively(retired);

        long removed = before - writer.edgeCount();
        LOGGER.info("compacted edge store at {}: {} edges removed, {} remain",
            directory, removed, writer.edgeCount());
        return removed;
    }

    /**
     * When the longest-waiting request arrived, for {@link CompactionPolicy}.
     *
     * <p>The oldest, not the newest: the deadline that matters is the one closest to expiring.
     */
    public static java.util.Optional<java.time.Instant> oldestOutstanding(
            Collection<ErasureRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return java.util.Optional.empty();
        }
        return requests.stream()
            .map(ErasureRequest::requestedAt)
            .min(Comparator.naturalOrder());
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path each : paths) {
                Files.deleteIfExists(each);
            }
        }
    }
}
