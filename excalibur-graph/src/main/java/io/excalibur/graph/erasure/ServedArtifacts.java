package io.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.excalibur.graph.file.Durability;
import io.excalibur.graph.format.EdgeColumn;
import io.excalibur.graph.format.EdgeStoreDescriptor;
import io.excalibur.graph.staging.GraphBuilder;

import org.roaringbitmap.RoaringBitmap;

/**
 * Everything in a graph directory that is not the edge store, as an erasure has to see it.
 *
 * <h2>What this is for</h2>
 * <p>{@link EdgeStoreCompactor} rewrites the columns. The directory those columns live in also
 * holds the erased person's name, the prose their snippets were copied out of and a vector
 * computed from that prose - and until something took responsibility for that material, a
 * compaction carried all of it across untouched and reported the obligation discharged.
 *
 * <p>This is where the responsibility sits. It holds the erasers, runs them into the replacement
 * directory, and - the part that matters most - works out what none of them could reach, so a
 * request covering data that is still on disk is left outstanding instead of being called done.
 *
 * @author ExcaliburDB
 */
final class ServedArtifacts {

    /** The names {@link #rebuiltOrImpersonal} lists, computed once. */
    private static final Set<String> ACCOUNTED_FOR = Set.copyOf(rebuiltOrImpersonal());

    private final List<ServedArtifactEraser> erasers;

    /** The requests this compaction is discharging, which decide what each eraser has to do. */
    private final List<ErasureRequest> batch;

    /**
     * @param supplied   The caller's erasers, possibly null or empty
     * @param durability How a rewritten artifact of this module's is forced
     * @param batch      The requests being discharged, possibly null or empty
     * @throws IllegalArgumentException if two erasers claim the same name
     */
    ServedArtifacts(Collection<ServedArtifactEraser> supplied, Durability durability,
                    Collection<ErasureRequest> batch) {
        this.erasers = withBuiltIns(supplied, durability);
        this.batch = batch == null ? List.of() : List.copyOf(batch);
    }

    /**
     * Names a compaction rebuilds itself, or carries across holding nothing an erasure reaches.
     *
     * <p>The edge columns and the descriptor are rewritten from the surviving edges. The relation
     * dictionary is carried across because a relation label is a predicate - {@code worksAt} -
     * and no scope covers one: an entity erasure removes a person, not the vocabulary the facts
     * about them were expressed in.
     *
     * @return The names, as they appear in a graph directory
     */
    private static Set<String> rebuiltOrImpersonal() {
        Set<String> names = new HashSet<>();
        for (EdgeColumn column : EdgeColumn.values()) {
            names.add(column.fileName());
        }
        names.add(EdgeStoreDescriptor.FILE_NAME);
        names.add(GraphBuilder.RELATION_DICTIONARY + ".idx");
        names.add(GraphBuilder.RELATION_DICTIONARY + ".dat");
        return names;
    }

    /**
     * This module's own erasers followed by the caller's.
     *
     * <p>The two built in are defaults rather than options. The entity dictionary and the entity
     * vector file are this module's formats, they hold the erased person's name and a derivation
     * of the erased prose, and a caller forgetting to pass anything for them would be a silent
     * return to leaving both on disk.
     *
     * <p>A supplied eraser claiming one of their names replaces it. That is how a caller with
     * more to work with takes over: this module's vector eraser can clear an erased entity's
     * slot and no more, because recomputing a vector after a document is withdrawn needs the
     * embedder and the erased staging - which a caller that has both can do, and can then say it
     * reaches a source scope where the default cannot. Two <em>supplied</em> erasers claiming one
     * name is still refused, because neither is the obvious winner and one would quietly
     * overwrite the other.
     *
     * @param supplied   The caller's erasers, possibly null or empty
     * @param durability How a rewritten artifact of this module's is forced
     * @return Every eraser to run
     * @throws IllegalArgumentException if two supplied erasers claim the same name
     */
    private static List<ServedArtifactEraser> withBuiltIns(
            Collection<ServedArtifactEraser> supplied, Durability durability) {
        List<ServedArtifactEraser> caller =
            supplied == null ? List.of() : List.copyOf(supplied);

        Set<String> claimed = new HashSet<>();
        for (ServedArtifactEraser eraser : caller) {
            for (String name : eraser.names()) {
                if (!claimed.add(name)) {
                    throw new IllegalArgumentException("two erasers claim \"" + name
                        + "\"; one of them would silently overwrite the other's work");
                }
            }
        }

        List<ServedArtifactEraser> all = new ArrayList<>();
        for (ServedArtifactEraser builtIn
                : List.of(new EntityDictionaryEraser(), new EntityVectorEraser(durability))) {
            if (java.util.Collections.disjoint(builtIn.names(), claimed)) {
                all.add(builtIn);
            }
        }
        all.addAll(caller);
        return all;
    }

    /**
     * Whether there is anything in the served directory for the erasers to remove.
     *
     * <p>Asked so that an erasure naming nothing the graph holds does not pay for a whole
     * directory rewrite, and - more to the point - so that one naming something it <em>does</em>
     * hold is not skipped merely because the entity had no edges.
     *
     * <p>A request that names no entity at all still counts, as long as it selects by
     * provenance. The prose a withdrawn document contributed to an entity's document is in the
     * served index whether or not that document also produced an edge, and an eraser able to
     * rebuild the index from cleaned staging can remove it - which it never gets the chance to
     * do if the compaction decides beforehand that an erasure naming nobody has nothing to do.
     *
     * @param directory The served directory
     * @param erased    The erased entity ids
     * @return true when at least one claimed artifact is present and something covers it
     */
    boolean needed(Path directory, RoaringBitmap erased) {
        if (erased.isEmpty() && !selectsByProvenance()) {
            return false;
        }
        for (ServedArtifactEraser eraser : erasers) {
            for (String name : eraser.names()) {
                if (Files.exists(directory.resolve(name))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether any request in the batch covers records by where they came from.
     *
     * @return true when a source or extractor scope is in the batch
     */
    private boolean selectsByProvenance() {
        for (ErasureRequest request : batch) {
            if (!(request.scope() instanceof ErasureScope.Entity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs every eraser and reports which names they have taken responsibility for.
     *
     * <p>Skipped entirely when nothing is being discharged, because then every eraser would do
     * nothing but copy its artifact, and carrying it across by hard link is both cheaper and
     * safer.
     *
     * <p>A name counts as rebuilt only once it is actually there. An eraser whose artifact was
     * not in the served directory writes nothing, and claiming the name regardless would tell
     * the carry-over to skip something that does exist - a dictionary published as two files,
     * caught mid-crash with only one of them present, would lose the other to a compaction whose
     * whole contract is that it loses nothing.
     *
     * @param served      The served directory, which no eraser may modify
     * @param replacement The directory being assembled
     * @param erased      The erased entity ids
     * @return The names produced, which must not then be carried across from the served copy
     * @throws IOException if an artifact cannot be read or its replacement written
     */
    Set<String> erase(Path served, Path replacement, RoaringBitmap erased)
            throws IOException {
        if (batch.isEmpty()) {
            return Set.of();
        }
        Set<String> rebuilt = new HashSet<>();
        for (ServedArtifactEraser eraser : erasers) {
            // The batch and not only the ids: an artifact whose content is derived from the
            // prose a request withdraws answers the two differently, and the only one that can
            // say which is needed is the eraser that owns the artifact.
            eraser.erase(served, replacement, erased, batch);
            for (String name : eraser.names()) {
                if (Files.exists(replacement.resolve(name))) {
                    rebuilt.add(name);
                }
            }
        }
        return rebuilt;
    }

    /**
     * Which entries of the served directory each request still leaves its data in.
     *
     * <p>Computed from what is actually on disk rather than from a list of what ought to be, so
     * an artifact a future build adds is reported the day it appears instead of being carried
     * across in silence until somebody notices. An unknown entry is assumed to hold erased data
     * until an eraser says otherwise, which is the direction to be wrong in: a caller told its
     * erasure is incomplete can act, and one told it succeeded cannot.
     *
     * @param directory The served directory
     * @return Request id to the names it does not reach; absent means fully reached
     * @throws IOException if the directory cannot be listed
     */
    Map<Long, List<String>> unreached(Path directory) throws IOException {
        if (batch.isEmpty() || !Files.isDirectory(directory)) {
            return Map.of();
        }
        List<String> present;
        try (var entries = Files.list(directory)) {
            present = entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }

        Map<Long, List<String>> unreached = new LinkedHashMap<>();
        for (ErasureRequest request : batch) {
            List<String> beyond = present.stream()
                .filter(name -> !reaches(name, request.scope()))
                .toList();
            if (!beyond.isEmpty()) {
                unreached.put(request.id(), beyond);
            }
        }
        return unreached;
    }

    /**
     * Whether one directory entry holds nothing a scope covers once the compaction has run.
     *
     * @param name  The entry, relative to the graph directory
     * @param scope What the request covers
     * @return true when the entry is accounted for
     */
    private boolean reaches(String name, ErasureScope scope) {
        if (ACCOUNTED_FOR.contains(name)) {
            return true;
        }
        for (ServedArtifactEraser eraser : erasers) {
            if (eraser.names().contains(name)) {
                return eraser.reaches(scope);
            }
        }
        return false;
    }
}
