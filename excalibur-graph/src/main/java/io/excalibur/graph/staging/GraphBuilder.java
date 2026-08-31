package io.excalibur.graph.staging;

import io.excalibur.graph.dictionary.LabelDictionaryWriter;
import io.excalibur.graph.store.EdgeStoreWriter;
import io.excalibur.graph.temporal.SupersessionPolicy;

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
 * build that fails leaves the previous graph exactly as it was.</p>
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

        long written = staging.forEach((subject, relation, object, validFrom, validTo, provenance) ->
            writer.add(subject, relation, object, validFrom, validTo, provenance));

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
     * Moves a completed build into place.
     *
     * <p>Two renames within one filesystem, so the target is a whole graph at every moment a
     * reader could look at it. The previous graph is kept until the new one is in place and is
     * removed afterwards, which means a failure between the two leaves something serveable rather
     * than nothing.
     *
     * @param scratch Where the build was assembled
     * @param target  Where it is to be served from
     * @throws IOException if the move fails
     */
    public static void publish(Path scratch, Path target) throws IOException {
        Path previous = target.resolveSibling(target.getFileName() + PREVIOUS_SUFFIX);
        deleteRecursively(previous);

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
        deleteRecursively(previous);
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
