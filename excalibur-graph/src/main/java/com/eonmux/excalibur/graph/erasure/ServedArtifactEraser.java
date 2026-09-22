package com.eonmux.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

import org.roaringbitmap.RoaringBitmap;

/**
 * Removes erased entities from one artifact a graph is served beside.
 *
 * <h2>Why this is an interface rather than code in this module</h2>
 * <p>A graph directory holds more than the edge store, and the erasable parts of it are not all
 * written by the same module. The entity dictionary and the entity vectors are this module's own
 * formats and are erased by {@link EntityDictionaryEraser} and {@link EntityVectorEraser}. The two
 * lexical indexes beside them are Lucene directories written by {@code excalibur-retrieval},
 * which depends on this module - so this module cannot read them without a dependency cycle, and
 * a second Lucene writer here would be a second schema to keep in step with the first.
 *
 * <p>So the compaction names what it cannot reach and asks. An implementation lives wherever the
 * artifact's format already lives, and is handed to
 * {@link EdgeStoreCompactor#compact(com.eonmux.excalibur.graph.store.MappedEdgeStore, Path,
 * RoaringBitmap, java.util.Collection, java.util.Collection,
 * com.eonmux.excalibur.graph.file.Durability)}. An artifact nobody claims is reported as unreached rather
 * than carried across in silence, because a compaction that leaves the erased prose in the
 * document index and reports the obligation discharged is the failure this whole mechanism
 * exists to prevent.
 *
 * <h2>The crash guarantee an implementation has to keep</h2>
 * <p>{@link #erase} must write only into {@code replacement} and must not modify {@code served}
 * in any way. The compaction's contract is that the served directory stays complete and readable
 * right up to a single atomic swap, so that a crash at any point leaves either the old graph or
 * the new one. Mutating the original would destroy that, and the original is the only copy while
 * the replacement is being assembled.
 *
 * @author ExcaliburDB
 */
public interface ServedArtifactEraser {

    /**
     * The entries of a graph directory this is responsible for.
     *
     * <p>Names relative to the directory, files or directories alike - {@code "documents"},
     * {@code "entities.idx"}. A name claimed here is not carried across from the served copy by
     * the compaction; producing it in the replacement is entirely this eraser's job.
     *
     * @return The names, never null and never empty
     */
    Set<String> names();

    /**
     * Whether {@link #names()} hold nothing that {@code scope} covers once {@link #erase} has run.
     *
     * <p>Answered per scope because the scopes reach different things. An entity's name is
     * covered by an entity-scoped request and by nothing else - withdrawing a document does not
     * take the names of everyone it mentioned with it - so a dictionary reaches every scope. An
     * entity's vector, by contrast, is computed from the prose a source-scoped request withdraws,
     * and cannot be recomputed from inside a compaction; so it reaches an entity scope and not a
     * source one, and says so rather than letting the request be reported as discharged.
     *
     * @param scope What a request covers
     * @return true when nothing this eraser owns survives that scope
     */
    boolean reaches(ErasureScope scope);

    /**
     * Writes {@link #names()} into {@code replacement} without the erased entities.
     *
     * <p>Every name claimed must exist in {@code replacement} afterwards if it existed in
     * {@code served}, because the replacement directory replaces the served one whole and
     * anything missing from it is deleted with the retired copy.
     *
     * @param served         The directory being served, which must not be modified
     * @param replacement    The directory being assembled, which the swap will move into place
     * @param erasedEntities Entity ids whose data must not appear in the replacement
     * @throws IOException if the artifact cannot be read or its replacement written
     */
    void erase(Path served, Path replacement, RoaringBitmap erasedEntities) throws IOException;

    /**
     * As {@link #erase}, told which requests the compaction is discharging.
     *
     * <h2>Why an eraser is offered the requests at all</h2>
     * <p>{@code erasedEntities} says who was named and not what was asked, and for an artifact
     * whose content is <em>derived</em> those are different questions. An entity's document in
     * the {@code documents} index is prose accumulated from every source that mentioned them:
     * an entity-scoped request is answered by deleting that one document, and a source-scoped
     * one only by rebuilding the index from staging the erasure has already cleaned. The second
     * is a pass over the whole corpus, and for the vectors beside it an embedding call per batch
     * of entities - a price worth paying to reach a withdrawn document and not worth paying for
     * every erasure.
     *
     * <p>An eraser that does not override this cannot tell the two apart and takes whichever
     * path {@link #erase} implements, which is why the default forwards rather than failing: an
     * eraser with one strategy is the ordinary case and must not have to know this exists.
     *
     * @param served         The directory being served, which must not be modified
     * @param replacement    The directory being assembled
     * @param erasedEntities Entity ids whose data must not appear in the replacement
     * @param requests       Every request this compaction is discharging, never null
     * @throws IOException if the artifact cannot be read or its replacement written
     */
    default void erase(Path served, Path replacement, RoaringBitmap erasedEntities,
                       Collection<ErasureRequest> requests) throws IOException {
        erase(served, replacement, erasedEntities);
    }
}
