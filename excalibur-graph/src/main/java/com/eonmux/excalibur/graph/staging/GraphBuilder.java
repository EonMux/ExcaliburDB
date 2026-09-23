package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.dictionary.LabelDictionaryWriter;
import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Builds a whole edge store, and the dictionaries that name it, from staged facts.
 *
 * <h2>Why a rebuild rather than an update</h2>
 * <p>A CSR adjacency is sorted and contiguous; that is the property traversal depends on, and it
 * is not one an insert can preserve. So the graph is produced rather than maintained: everything
 * ingestion has staged is replayed into a fresh store, which then replaces the previous one whole.
 * Entity ids survive the replacement because {@link GraphStagingStore} assigned them once and
 * keeps them - so the lexical index over entity labels, which stores those ids, stays correct
 * across a rebuild it did not participate in.</p>
 *
 * <h2>Publishing</h2>
 * <p>The store is written to a sibling directory and moved into place, so a reader either sees the
 * previous graph or the new one. A half-written directory is never the one being served, and a
 * build that fails leaves the previous graph exactly as it was. An erasure compaction replaces the
 * served directory by the same {@link #publish(Path, Path, Durability) publish}, so one
 * {@link #recoverInterruptedPublish(Path) recovery} answers for a crash in either.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and safe to call from any thread, but two concurrent builds into the same target
 * would race over the same scratch directory: callers serialise builds themselves.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphBuilder.class);

    /** Suffix of the directory a build is assembled in before it is moved into place. */
    public static final String BUILD_SUFFIX = ".building";

    /** Suffix of the directory the previous graph is moved to while the new one is published. */
    public static final String PREVIOUS_SUFFIX = ".previous";

    /**
     * Suffix of a directory an erasure compaction's swap set the served graph aside under when it
     * renamed directories by a protocol of its own.
     *
     * <p>Nothing writes it: a compaction publishes through {@link #publish(Path, Path, Durability)}
     * like a build. Recovery still reads it, because a disk a crash left holding one holds either
     * the only copy of the graph - the served name having gone - or, beside a served graph, a
     * copy of facts an erasure removed from it. The first must be restored and the second must
     * not survive, which is what {@link #recoverInterruptedPublish(Path)} does with a
     * {@code .previous} directory in the same two positions.</p>
     */
    public static final String RETIRED_SUFFIX = ".retired";

    /** Dictionary naming entities, as {@code GraphRagFactory} expects to find it. */
    public static final String ENTITY_DICTIONARY = "entities";

    /** Dictionary naming relations. */
    public static final String RELATION_DICTIONARY = "relations";

    private GraphBuilder() {
        throw new AssertionError("Utility class");
    }

    /**
     * What a build produced.
     *
     * @param edges      how many facts were written into the store
     * @param entities   how many entity ids the store addresses
     * @param relations  how many distinct relations were named
     * @param directory  where the store was published
     */
    public record BuildResult(long edges, int entities, int relations, Path directory) {
    }

    /**
     * Builds the graph in {@code target} from everything staged.
     *
     * <p>Publishes only a graph that has something in it. A build from empty staging leaves any
     * existing graph alone and reports zero: replacing a served graph with an empty one because
     * extraction has not produced anything yet would turn every question into a refusal.
     *
     * @param staging The facts to build from
     * @param target  Where the graph is served from
     * @return What was built
     * @throws IOException if the store cannot be written or published
     */
    public static BuildResult build(GraphStagingStore staging, Path target) throws IOException {
        return build(staging, target, SupersessionPolicy.NONE);
    }

    /**
     * Builds the graph in {@code target}, closing the previous value of every single-valued
     * relation.
     *
     * @param staging     The facts to build from
     * @param target      Where the graph is served from
     * @param supersession Which relations hold one value at a time
     * @return What was built
     * @throws IOException if the store cannot be written or published
     */
    public static BuildResult build(GraphStagingStore staging, Path target,
                                    SupersessionPolicy supersession) throws IOException {
        BuildResult result = buildInto(staging, target, supersession);
        if (result.edges() > 0) {
            publish(scratchFor(target), target);
        }
        return result;
    }

    /**
     * The directory a build for {@code target} is assembled in.
     *
     * <p>Exposed so a caller can add its own artifacts - the Lucene indexes the graph is served
     * with - to the same directory, and have them published in the same move. Two directories
     * published separately would leave a window in which the edge store and the indexes over it
     * describe different graphs.
     *
     * @param target Where the graph is served from
     * @return The scratch directory
     */
    public static Path scratchFor(Path target) {
        return target.resolveSibling(target.getFileName() + BUILD_SUFFIX);
    }

    /**
     * Writes the edge store and its dictionaries into the scratch directory, without publishing.
     *
     * @param staging      The facts to build from
     * @param target       Where the graph will be served from
     * @param supersession Which relations hold one value at a time
     * @return What was built; {@code edges() == 0} means nothing was staged and nothing was written
     * @throws IOException if the store cannot be written
     */
    public static BuildResult buildInto(GraphStagingStore staging, Path target,
                                        SupersessionPolicy supersession) throws IOException {
        Objects.requireNonNull(staging, "staging must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(supersession, "supersession must not be null");

        staging.flush();
        long stagedCount = staging.stagedCount();
        int entityCount = staging.entityCount();
        if (stagedCount == 0 || entityCount == 0) {
            LOGGER.info("nothing staged for {}; leaving the existing graph in place", target);
            return new BuildResult(0, entityCount, staging.relationCount(), target);
        }

        Path scratch = scratchFor(target);
        deleteRecursively(scratch);
        Files.createDirectories(scratch);

        // Whichever relations the deployment declared single-valued, and nothing else. Closing an
        // interval destroys information, and the cost is asymmetric: a relation wrongly left
        // multi-valued returns a stale extra answer, while one wrongly superseded silently
        // deletes a true fact - "authored" closing the previous book when someone writes another.
        EdgeStoreWriter writer = new EdgeStoreWriter().supersessionPolicy(supersession);

        // In the order the facts were staged, which is supersession's last tie-break: of two
        // statements from different sources that nothing else orders, the one staged later is
        // the one that superseded the other.
        long written = staging.forEachStaged(
            (subject, relation, object, validFrom, validTo, statedOn, provenance) ->
                writer.add(subject, relation, object, validFrom, validTo, statedOn, provenance));

        writer.build(scratch, entityCount);
        writeDictionary(scratch, ENTITY_DICTIONARY, staging.entityLabels());
        writeDictionary(scratch, RELATION_DICTIONARY, staging.relationLabels());

        LOGGER.info("assembled graph for {}: {} facts, {} entities, {} relations",
            target, written, entityCount, staging.relationCount());
        return new BuildResult(written, entityCount, staging.relationCount(), target);
    }

    /**
     * A supersession policy naming relations by label rather than by id.
     *
     * <p>A deployment configures which relations are single-valued in the words its extractor
     * produces; the ids those words were given belong to the staging store. Resolving here keeps
     * the configuration readable and the policy correct across rebuilds, which renumber nothing
     * but do introduce ids the configuration has never seen.
     *
     * @param staging     The store holding the relation assignments
     * @param labels      Labels of the relations that hold one value at a time
     * @return The policy
     */
    public static SupersessionPolicy singleValued(GraphStagingStore staging,
                                                  java.util.Collection<String> labels) {
        Objects.requireNonNull(staging, "staging must not be null");
        if (labels == null || labels.isEmpty()) {
            return SupersessionPolicy.NONE;
        }
        java.util.Set<String> wanted = labels.stream()
            .filter(label -> label != null && !label.isBlank())
            .map(label -> label.trim().toLowerCase(java.util.Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());

        List<String> relations = staging.relationLabels();
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (int id = 0; id < relations.size(); id++) {
            if (wanted.contains(relations.get(id).toLowerCase(java.util.Locale.ROOT))) {
                ids.add(id);
            }
        }
        return ids::contains;
    }

    /**
     * Writes one dictionary of labels, keyed by the id each label was assigned.
     *
     * @param directory Where the dictionary goes
     * @param name      Its name within the directory
     * @param labels    The labels, index being the id
     * @throws IOException if the dictionary cannot be written
     */
    private static void writeDictionary(Path directory, String name, List<String> labels)
            throws IOException {
        LabelDictionaryWriter dictionary = new LabelDictionaryWriter();
        for (int id = 0; id < labels.size(); id++) {
            dictionary.put(id, labels.get(id));
        }
        dictionary.write(directory, name);
    }

    /**
     * Moves a completed build into place, durably.
     *
     * @param scratch Where the build was assembled
     * @param target  Where it is to be served from
     * @throws IOException if the build cannot be forced or the move fails
     */
    public static void publish(Path scratch, Path target) throws IOException {
        publish(scratch, target, Durability.DEVICE);
    }

    /**
     * As above, with how the build is forced to the device under the caller's control.
     *
     * <p>Two renames within one filesystem, so the target is a whole graph at every moment a
     * reader could look at it. The previous graph is kept until the new one is in place and is
     * removed afterwards, which means a failure between the two leaves something serveable rather
     * than nothing.
     *
     * <h3>Recovery comes first</h3>
     * <p>The name the previous graph is set aside under may already be taken when a publish
     * starts, and what is there is not always debris. Beside a served graph it is the leftover of
     * a publish that completed its renames, and deleting it is right. With nothing at the target
     * it is the only graph there is - a crash between the renames, or a rollback whose own rename
     * failed - and deleting it would leave this publish, and its rollback, with nothing to fall
     * back to. So the directory is put through {@link #recoverInterruptedPublish(Path)} rather
     * than cleared, which tells the two apart.</p>
     *
     * <h3>Why a publish forces and an assembly does not</h3>
     * <p>Nothing earlier in a build needs to reach the device: a build whose bytes were lost is a
     * build to run again from the staging log that outlived it, and a half-assembled scratch
     * directory is never the one being served. The last line here is what changes that, by
     * deleting the graph this replaced. From that point the new directory is the only copy there
     * is, and a rename that reached the platter ahead of the bytes it names leaves a descriptor
     * over columns of zeros - a graph that will not open, with nothing to fall back to.
     *
     * <p>The whole tree is forced, not only what this module wrote. A caller assembles its own
     * artifacts into the same scratch directory precisely so that they are published by this one
     * move; forcing only the files this class knows about would make the Lucene indexes and the
     * entity vectors the part that comes back empty.
     *
     * @param scratch    Where the build was assembled
     * @param target     Where it is to be served from
     * @param durability How the assembled build and the published name are forced
     * @throws IOException if the build cannot be forced or the move fails
     * @throws NullPointerException if any argument is null
     */
    public static void publish(Path scratch, Path target, Durability durability)
            throws IOException {
        publish(scratch, target, durability, published -> null);
    }

    /**
     * Opens what a publish put in place, before the graph it replaced is discarded.
     *
     * @param <T> What opening it produces
     */
    @FunctionalInterface
    public interface Opener<T> {

        /**
         * Opens the graph just published.
         *
         * @param published The target directory, now holding the new graph
         * @return Whatever the caller serves it through
         * @throws IOException if the graph cannot be opened
         */
        T open(Path published) throws IOException;
    }

    /**
     * Moves a completed build into place and opens it, keeping the previous graph until it has.
     *
     * <p>As {@link #publish(Path, Path, Durability)}, except that the previous graph is deleted
     * only after {@code opener} has opened the new one. A graph that was forced to the device and
     * renamed into place can still fail to open - an artifact written by one version and read by
     * another, a file the opener expects and the build did not write - and deleting the fallback
     * before finding that out leaves a node with nothing it can serve. If the opener fails, the
     * new graph goes back to {@code scratch} and the previous one back to {@code target}, and
     * the opener's exception propagates.</p>
     *
     * <p>The opener must leave the previous graph alone: it is the rollback's source, and so it
     * must not run {@link #recoverInterruptedPublish(Path)}, which reads this exact layout as a
     * completed publish and deletes it. Should the previous graph be gone all the same, the new
     * one is left at {@code target} rather than moved out, since it is then the only graph
     * there is.</p>
     *
     * @param <T>        What opening the graph produces
     * @param scratch    Where the build was assembled
     * @param target     Where it is to be served from
     * @param durability How the assembled build and the published name are forced
     * @param opener     Opens the published graph
     * @return What {@code opener} returned
     * @throws IOException if the build cannot be forced, a move fails, or the opener fails
     * @throws NullPointerException if any argument is null
     */
    public static <T> T publish(Path scratch, Path target, Durability durability,
                                Opener<T> opener) throws IOException {
        Objects.requireNonNull(scratch, "scratch must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(durability, "durability must not be null");
        Objects.requireNonNull(opener, "opener must not be null");

        Path previous = previousFor(target);
        recoverInterruptedPublish(target);

        // Before either rename, and therefore while the previous graph is still the one being
        // served. A device error here costs the build and no data.
        durability.fsyncTree(scratch);

        boolean hadPrevious = Files.exists(target);
        if (hadPrevious) {
            Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            Files.move(scratch, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (hadPrevious) {
                // Put back what was being served rather than leaving the target absent.
                Files.move(previous, target, StandardCopyOption.ATOMIC_MOVE);
            }
            throw e;
        }

        // Both renames happened in the parent, and a forced file whose name is not yet durable
        // can come back with no directory entry pointing at it. Forced before the previous graph
        // is deleted, so the new one is reachable by name before the fallback stops existing.
        Path parent = target.getParent();
        if (parent != null) {
            durability.fsyncDirectory(parent);
        }

        T opened;
        try {
            opened = opener.open(target);
        } catch (IOException | RuntimeException e) {
            if (hadPrevious && !Files.isDirectory(previous)) {
                // Moving the new graph out now would leave nothing at the served path and
                // nothing to put there, which is the one outcome the rollback exists to prevent.
                // It stays: a graph that failed to open once is still the only copy there is.
                e.addSuppressed(new IOException("cannot roll back to " + previous
                    + ", which the opener removed; the new graph is left at " + target));
                throw e;
            }
            try {
                Files.move(target, scratch, StandardCopyOption.ATOMIC_MOVE);
                if (hadPrevious) {
                    Files.move(previous, target, StandardCopyOption.ATOMIC_MOVE);
                }
                if (parent != null) {
                    durability.fsyncDirectory(parent);
                }
            } catch (IOException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        }
        deleteRecursively(previous);
        return opened;
    }

    /**
     * Puts back a graph that a publish interrupted by a crash left displaced.
     *
     * <p>A publish renames the served graph aside and then renames the new one into its place.
     * A crash between the two leaves no graph at {@code target} and the whole previous one beside
     * it, and a node that opened {@code target} as it found it would serve nothing, and a later
     * build would delete the only copy. Restoring it here makes that crash cost the build and
     * nothing else. When both are present the publish got as far as the new graph, which was
     * forced before it was renamed, so it stands and the previous one is deleted: it may hold
     * facts an erasure has since removed from the served graph, and nothing will read it.</p>
     *
     * <p>An erasure compaction publishes by this same protocol, so a restored graph may be the
     * one an erasure was replacing: a graph still holding what the erasure removes. Nothing marks
     * an obligation discharged before its publish has completed, so the obligation is still
     * outstanding; a caller that serves the restored graph applies what is outstanding first
     * when {@code true} comes back. A {@link #RETIRED_SUFFIX} directory is treated as a displaced
     * graph too.</p>
     *
     * @param target Where the graph is served from
     * @return Whether a displaced graph was restored
     * @throws IOException if the restoring rename or the removal fails
     */
    public static boolean recoverInterruptedPublish(Path target) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Path previous = previousFor(target);
        adoptRetired(target, previous);
        if (!Files.isDirectory(previous)) {
            return false;
        }
        if (Files.exists(target)) {
            deleteRecursively(previous);
            LOGGER.info("removed {}, which a publish that completed its rename left behind",
                previous);
            return false;
        }
        Files.move(previous, target, StandardCopyOption.ATOMIC_MOVE);
        LOGGER.warn("restored the graph at {} from {}, which an interrupted publish left in its "
            + "place", target, previous);
        return true;
    }

    /**
     * Whether a graph is set aside beside {@code target}, which recovery would restore or remove.
     *
     * <p>For a caller asking whether a namespace has a graph without opening it: a displaced
     * graph with nothing at the target is a graph the next open restores.</p>
     *
     * @param target Where the graph is served from
     * @return true if a displaced graph is on disk beside it
     */
    public static boolean hasDisplacedGraph(Path target) {
        Objects.requireNonNull(target, "target must not be null");
        return Files.isDirectory(previousFor(target)) || Files.isDirectory(retiredFor(target));
    }

    /**
     * Folds a {@link #RETIRED_SUFFIX} directory into the {@code .previous} protocol.
     *
     * <p>Beside a served graph, or beside a displaced graph that recovery is about to restore, it
     * is a copy of what an erasure compaction replaced, and is deleted. Alone, it is the only
     * graph there is, and becomes the displaced graph recovery restores.</p>
     *
     * @param target   Where the graph is served from
     * @param previous Where a publish sets the served graph aside
     * @throws IOException if the rename or the removal fails
     */
    private static void adoptRetired(Path target, Path previous) throws IOException {
        Path retired = retiredFor(target);
        if (!Files.isDirectory(retired)) {
            return;
        }
        if (Files.exists(target) || Files.isDirectory(previous)) {
            deleteRecursively(retired);
            LOGGER.info("removed {}, a graph an erasure compaction had already replaced", retired);
            return;
        }
        Files.move(retired, previous, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Path retiredFor(Path target) {
        return target.resolveSibling(target.getFileName() + RETIRED_SUFFIX);
    }

    /**
     * Where a publish moves the served graph aside while it renames the new one into place.
     *
     * @param target Where the graph is served from
     * @return The sibling that holds the displaced graph during a publish
     */
    public static Path previousFor(Path target) {
        return target.resolveSibling(target.getFileName() + PREVIOUS_SUFFIX);
    }

    /**
     * Removes a directory tree, if it is there.
     *
     * @param path The tree
     * @throws IOException if it cannot be removed
     */
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
