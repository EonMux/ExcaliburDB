package com.eonmux.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;

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
 * <p>A crash before the move leaves the original untouched and a stray temporary directory, which
 * the next compaction clears before it starts; a crash after it leaves the compacted store, which
 * is the intended outcome. There is no window in which the store is neither. A <em>failure</em>,
 * as opposed to a crash, clears the temporary itself, so an erasure that could not be carried out
 * leaves nothing of itself behind at all.
 *
 * <h2>The directory holds more than the edges</h2>
 * <p>An erasure is not discharged by removing edges. The graph is served out of a directory that
 * also holds the person's name - in the entity dictionary and in the label index - up to two
 * kilobytes of the document body per mention in the document index, and a vector computed from
 * that text. Rewriting the columns and carrying all of that across unchanged left the erased
 * person findable by searching for themselves, with the node reporting the obligation met.
 *
 * <p>So the replacement directory is assembled rather than merely inherited: this module rewrites
 * the artifacts whose formats it owns (see {@link EntityDictionaryEraser} and
 * {@link EntityVectorEraser}), a caller supplies a {@link ServedArtifactEraser} for each artifact
 * it cannot read, and anything left over is <em>reported</em> - see
 * {@link CompactionResult#unreached()} - rather than carried across in silence. Only what nothing
 * could have written the erased data into is carried across untouched.
 *
 * <h2>Why this one writes through the page cache</h2>
 * <p>Nothing else in this module fsyncs, and rightly: a build whose bytes were still in the page
 * cache when the power failed is a build to run again from the staging log that outlived it. This
 * method has no such fallback, because its last act is to delete the original it replaced. So the
 * replacement is forced to the device, and its name after that, before the retired copy goes — see
 * {@link com.eonmux.excalibur.graph.file.Durability}.
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
        return compact(store, directory, tombstoned, Durability.DEVICE);
    }

    /**
     * As above, with the durability of the replacement under the caller's control.
     *
     * @param store       the open store, which this closes
     * @param directory   where it lives
     * @param tombstoned  forward edge indices to drop
     * @param durability  how the replacement is forced to the device before the original goes
     * @return how many edges were removed
     * @throws IOException if the rewrite, the sync or either rename fails
     */
    static long compact(MappedEdgeStore store, Path directory, RoaringBitmap tombstoned,
                        Durability durability) throws IOException {
        return compact(store, directory, tombstoned, List.of(), List.of(), durability)
            .edgesRemoved();
    }

    /**
     * Rewrites the store and the served artifacts beside it without what the requests cover.
     *
     * <p>This is the overload an erasure obligation is discharged by. The three-argument one
     * removes edges and nothing else, which is all a caller with no obligation behind it needs.
     *
     * @param store      the open store, which this closes on the path that rewrites anything
     * @param directory  where the graph is served from
     * @param tombstoned forward edge indices to drop, from {@link #resolve}
     * @param requests   the requests being discharged, which say which entities are erased and
     *                   which scopes each artifact has to answer
     * @param erasers    one per artifact this module cannot read; see {@link ServedArtifactEraser}
     * @return what was rewritten, and which artifacts each request did not reach
     * @throws IOException if the rewrite, the sync or either rename fails
     */
    public static CompactionResult compact(MappedEdgeStore store, Path directory,
                                           RoaringBitmap tombstoned,
                                           Collection<ErasureRequest> requests,
                                           Collection<ServedArtifactEraser> erasers)
            throws IOException {
        return compact(store, directory, tombstoned, requests, erasers, Durability.DEVICE);
    }

    /**
     * As above, with the durability of the replacement under the caller's control.
     *
     * @param store       the open store, which this closes on the path that rewrites anything
     * @param directory   where the graph is served from
     * @param tombstoned  forward edge indices to drop
     * @param requests    the requests being discharged
     * @param erasers     one per artifact this module cannot read
     * @param durability  how the replacement is forced to the device before the original goes
     * @return what was rewritten, and which artifacts each request did not reach
     * @throws IOException if the rewrite, the sync or either rename fails
     */
    static CompactionResult compact(MappedEdgeStore store, Path directory,
                                    RoaringBitmap tombstoned,
                                    Collection<ErasureRequest> requests,
                                    Collection<ServedArtifactEraser> erasers,
                                    Durability durability) throws IOException {
        if (store == null || directory == null || tombstoned == null || durability == null) {
            throw new IllegalArgumentException(
                "store, directory, tombstoned and durability are all required");
        }
        List<ErasureRequest> batch = requests == null ? List.of() : List.copyOf(requests);
        ServedArtifacts served = new ServedArtifacts(erasers, durability, batch);
        RoaringBitmap erased = erasedEntities(batch);

        // Worked out before deciding whether to rewrite anything, and reported either way:
        // whether an artifact still holds data a request covers has nothing to do with whether
        // there happened to be edges to remove. A source-scoped request matching no edge still
        // leaves its prose in the document index, and saying so is the point.
        Map<Long, List<String>> unreached = served.unreached(directory);

        // Not "nothing tombstoned". An entity with no edges still has a name in the dictionary,
        // prose in the document index and a vector computed from it, and erasing nothing while
        // reporting success is the failure this class exists to prevent.
        if (tombstoned.isEmpty() && !served.needed(directory, erased)) {
            LOGGER.debug("nothing tombstoned at {} and nothing served to erase; skipping",
                directory);
            return new CompactionResult(false, 0, unreached);
        }

        long removed = rewrite(store, directory, tombstoned, erased, served, durability);
        return new CompactionResult(true, removed, unreached);
    }

    /**
     * Prepares a clean scratch directory, assembles into it, and clears it if that fails.
     *
     * @param store      the open store, closed once the swap is done
     * @param directory  where the graph is served from
     * @param tombstoned forward edge indices to drop
     * @param erased     entity ids whose served data must not survive
     * @param served     the artifacts beside the edge store
     * @param durability how the replacement is forced before the original is destroyed
     * @return how many edges were removed
     * @throws IOException if the rewrite, the sync or either rename fails
     */
    private static long rewrite(MappedEdgeStore store, Path directory, RoaringBitmap tombstoned,
                                RoaringBitmap erased, ServedArtifacts served,
                                Durability durability) throws IOException {
        long entityCount = store.entityCount();
        long before = store.edgeCount();
        Path temp = directory.resolveSibling(directory.getFileName() + TEMP_SUFFIX);
        deleteRecursively(temp);
        Files.createDirectories(temp);
        try {
            return assembleAndSwap(store, directory, temp, tombstoned, erased, served,
                durability, entityCount, before);
        } catch (IOException | RuntimeException e) {
            // A compaction that fails leaves the served directory exactly as it was, and that
            // has to include not leaving a half-assembled copy of it lying beside it. The next
            // compaction would clear the scratch anyway, but an erasure that failed is exactly
            // the moment not to leave an extra directory of hard links to the data behind.
            discard(temp, e);
            throw e;
        }
    }

    /**
     * Writes the replacement, forces it and moves it onto the served name.
     *
     * @param store       the open store, closed once the swap is done
     * @param directory   where the graph is served from
     * @param temp        the empty scratch directory to assemble into
     * @param tombstoned  forward edge indices to drop
     * @param erased      entity ids whose served data must not survive
     * @param served      the artifacts beside the edge store
     * @param durability  how the replacement is forced before the original is destroyed
     * @param entityCount the id space, preserved rather than recomputed
     * @param before      how many edges the store held
     * @return how many edges were removed
     * @throws IOException if the rewrite, the sync or either rename fails
     */
    private static long assembleAndSwap(MappedEdgeStore store, Path directory, Path temp,
                                        RoaringBitmap tombstoned, RoaringBitmap erased,
                                        ServedArtifacts served, Durability durability,
                                        long entityCount, long before) throws IOException {
        EdgeStoreWriter writer = new EdgeStoreWriter();
        store.forEachEdge((subject, relation, object, index) -> {
            if (tombstoned.contains((int) index)) {
                return;
            }
            // As stored, and with no supersession policy: the intervals were closed when the
            // store was built, and an empty one - a statement superseded the day it began - is
            // carried across rather than refused.
            writer.addStored(subject, relation, object,
                store.validFrom(index), store.validTo(index), store.provenanceOf(index));
        });
        // Entity count is preserved rather than recomputed. Compaction removes edges, not the id
        // space: renumbering entities here would invalidate every id held anywhere else.
        writer.build(temp, entityCount, durability);

        // Each eraser produces its own artifacts in the replacement, reading the served copy and
        // never modifying it, so the original stays complete and readable up to the swap.
        Set<String> rebuilt = served.erase(directory, temp, erased);

        // Whatever is left. The swap replaces the directory whole, so anything not carried across
        // here is deleted with the retired copy - and a compaction is the one operation that must
        // not lose data, being the one an erasure obligation is discharged by.
        carryOverTree(directory, temp, rebuilt);

        // Forced to the device before anything is renamed, and therefore while the original is
        // still the store being served. Nothing else this module writes needs it - a build whose
        // bytes were lost is a build to run again from the staging log - but this method deletes
        // the retired original at the end, so from that point the replacement is the only copy
        // there is. A rename that outran its own contents would leave a present descriptor over
        // short or empty columns, which MappedColumns.open refuses: a graph that will not open,
        // with nothing to fall back to. A failure here costs the erasure a retry and no data.
        durability.fsyncTree(temp);
        swap(directory, temp, durability);

        store.close();

        long removed = before - writer.edgeCount();
        LOGGER.info("compacted edge store at {}: {} edges removed, {} remain",
            directory, removed, writer.edgeCount());
        return removed;
    }

    /**
     * Removes a scratch directory a failed compaction left behind, without losing the failure.
     *
     * <p>Attached to the original failure rather than thrown, because what the caller needs to
     * know is why the erasure did not happen, not that the tidying up afterwards also did not.
     *
     * @param temp    The scratch directory
     * @param failure The failure being propagated
     */
    private static void discard(Path temp, Exception failure) {
        try {
            deleteRecursively(temp);
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * Moves the replacement onto the served name and destroys the copy it replaced.
     *
     * @param directory  where the graph is served from
     * @param temp       the assembled replacement
     * @param durability how the published name is forced before the fallback is deleted
     * @throws IOException if either rename or the sync fails
     */
    private static void swap(Path directory, Path temp, Durability durability) throws IOException {
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

        // Both renames happened in the parent, and a synced file whose name is not yet durable
        // can come back with no directory entry pointing at it. Forced before the retired copy is
        // deleted, so the replacement is reachable by name before the fallback stops existing.
        Path parent = directory.getParent();
        if (parent != null) {
            durability.fsyncDirectory(parent);
        }
        deleteRecursively(retired);
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

    /**
     * What a compaction did, and which artifacts each request did not reach.
     *
     * @param rewritten    Whether the directory was actually replaced
     * @param edgesRemoved How many edges the rewrite dropped
     * @param unreached    Request id to the names in the served directory that still hold data
     *                     that request covers. A non-empty entry is an obligation this pass did
     *                     not meet, and a caller that marks such a request discharged is
     *                     reporting an erasure that did not happen.
     */
    public record CompactionResult(boolean rewritten, long edgesRemoved,
                                   Map<Long, List<String>> unreached) {

        /**
         * @throws IllegalArgumentException if {@code edgesRemoved} is negative
         * @throws NullPointerException if {@code unreached} is null
         */
        public CompactionResult {
            if (edgesRemoved < 0) {
                throw new IllegalArgumentException("edgesRemoved must not be negative, got "
                    + edgesRemoved);
            }
            unreached = Map.copyOf(unreached);
        }

        /** Whether every request given was reached in full. */
        public boolean complete() {
            return unreached.isEmpty();
        }
    }

    /**
     * The entity ids an erasure batch names.
     *
     * <p>Only an entity scope names one. A withdrawn document must not take the names, vectors
     * and documents of everyone it happened to mention with it: those people did not ask to be
     * forgotten, and their ids are referenced by facts from other sources. This is the same rule
     * the staging erasure applies to {@code entities.labels}, stated once here so the served copy
     * and the staged one cannot come to different conclusions about whose name goes.
     *
     * @param requests The batch
     * @return The ids, possibly empty
     */
    public static RoaringBitmap erasedEntities(Collection<ErasureRequest> requests) {
        RoaringBitmap entities = new RoaringBitmap();
        if (requests == null) {
            return entities;
        }
        for (ErasureRequest request : requests) {
            if (!(request.scope() instanceof ErasureScope.Entity entity)) {
                continue;
            }
            // Entity ids are 32-bit everywhere they are stored; see EdgeStoreDescriptor. A
            // request naming one beyond that names nobody who could be in a graph, and is said
            // so rather than narrowed into somebody else's id.
            if (entity.entityId() > Integer.MAX_VALUE) {
                LOGGER.warn("erasure request {} names entity id {}, beyond the {} an entity id "
                    + "can hold; no entity in any graph has it", request.id(), entity.entityId(),
                    Integer.MAX_VALUE);
                continue;
            }
            entities.add((int) entity.entityId());
        }
        return entities;
    }

    /**
     * Carries across every entry the replacement does not already own.
     *
     * @param source  The served directory
     * @param target  The replacement
     * @param rebuilt Names an eraser has produced, which must not be topped up from the original
     * @throws IOException if an entry can be neither linked nor copied
     */
    private static void carryOverTree(Path source, Path target, Set<String> rebuilt)
            throws IOException {
        Files.createDirectories(target);
        try (var entries = Files.list(source)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                Path child = target.resolve(name);
                // An eraser owns the whole of what it claims. Descending into a rebuilt Lucene
                // directory to top it up from the original would put the erased segments back
                // beside the ones written without them.
                if (rebuilt.contains(name)) {
                    continue;
                }
                // A file the rebuild already wrote is the current version of it and is left
                // alone. A directory is descended into rather than skipped, so a sidecar tree
                // survives even if the rebuild ever writes one of its own beside it.
                if (Files.isDirectory(entry) || !Files.exists(child)) {
                    carryOver(entry, child);
                }
            }
        }
    }

    /**
     * Links everything the rebuild did not write into the replacement directory.
     *
     * <p>Compaction rewrites the edge store; the directory it lives in holds more than the edge
     * store, and none of the rest is changed by removing an edge. So whatever the rebuild did not
     * produce is carried across as it stands, matched by name so that a file the graph gains later
     * is preserved without this having to be told about it.</p>
     *
     * <p>By hard link rather than by move, which is what keeps the crash guarantee the class
     * javadoc makes: the original directory stays complete and readable right up to the atomic
     * swap, and deleting the retired copy afterwards removes only that copy's names. A filesystem
     * that cannot link is copied instead, which costs the bytes and keeps the guarantee.</p>
     *
     * @param source Where the entry lives now
     * @param target Where it should also be reachable
     * @throws IOException if an entry can be neither linked nor copied
     */
    private static void carryOver(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target);
            try (var entries = Files.list(source)) {
                for (Path entry : entries.toList()) {
                    Path child = target.resolve(entry.getFileName().toString());
                    // A file the rebuild already wrote is the current version of it and is left
                    // alone. A directory is descended into rather than skipped, so a sidecar tree
                    // survives even if the rebuild ever writes one of its own beside it.
                    if (Files.isDirectory(entry) || !Files.exists(child)) {
                        carryOver(entry, child);
                    }
                }
            }
            return;
        }
        try {
            Files.createLink(target, source);
        } catch (IOException | UnsupportedOperationException e) {
            LOGGER.debug("no hard link for {} ({}); copying instead", source, e.toString());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
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
