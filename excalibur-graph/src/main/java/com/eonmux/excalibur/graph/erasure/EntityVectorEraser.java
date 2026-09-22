package com.eonmux.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.vector.EntityVectorWriter;
import com.eonmux.excalibur.graph.vector.EntityVectors;

import org.roaringbitmap.RoaringBitmap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clears an erased entity's vector out of the served vector file.
 *
 * <h2>Why a vector is personal data</h2>
 * <p>Each one is computed from the entity's document, which is the prose copied out of the
 * ingested text the entity was mentioned in. It is a lossy encoding of that prose rather than
 * something separate from it, and the whole reason it exists is that distances between these
 * numbers reproduce meaning. Leaving it in place after an erasure keeps the erased person
 * rerankable, and keeps a derivation of the withdrawn text on disk.
 *
 * <h2>Why the slot stays</h2>
 * <p>The reader finds a vector by computing an offset from the entity id, so removing a slot
 * would shift every entity after it onto somebody else's vector. The slot is written as zeros
 * with its presence flag cleared, which is exactly how the format already records an entity
 * whose text could not be embedded: {@code vectorOf} returns null and the entity keeps whatever
 * score the lexical ranking gave it.
 *
 * @author ExcaliburDB
 */
public final class EntityVectorEraser implements ServedArtifactEraser {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityVectorEraser.class);

    private static final Set<String> NAMES = Set.of(EntityVectors.FILE_NAME);

    private final Durability durability;

    /** An eraser that forces the rewritten file to the device. */
    public EntityVectorEraser() {
        this(Durability.DEVICE);
    }

    /**
     * As above, with how the rewritten file is forced under the caller's control.
     *
     * @param durability How the replacement is forced before it takes its name
     * @throws IllegalArgumentException if {@code durability} is null
     */
    public EntityVectorEraser(Durability durability) {
        if (durability == null) {
            throw new IllegalArgumentException("durability must not be null");
        }
        this.durability = durability;
    }

    @Override
    public Set<String> names() {
        return NAMES;
    }

    /**
     * {@inheritDoc}
     *
     * <p>An entity scope only. A vector is computed from every snippet recorded for the entity,
     * so a source-scoped erasure that withdraws some of those snippets leaves a vector still
     * encoding them - and recomputing it needs the embedder and the post-erasure staging, which
     * a compaction has neither of. Answering false is what makes that gap visible instead of
     * letting the request be reported as discharged; the next build recomputes the vector from
     * staging the erasure has already cleaned.
     */
    @Override
    public boolean reaches(ErasureScope scope) {
        return scope instanceof ErasureScope.Entity;
    }

    @Override
    public void erase(Path served, Path replacement, RoaringBitmap erasedEntities)
            throws IOException {
        EntityVectors vectors = EntityVectors.openIfPresent(served);
        if (vectors == null) {
            return;
        }

        Files.createDirectories(replacement);
        Path path = replacement.resolve(EntityVectors.FILE_NAME);
        long cleared = 0;
        try (vectors;
             EntityVectorWriter rewritten = new EntityVectorWriter(
                 path, vectors.dimension(), vectors.count(), durability)) {
            for (long id = 0; id < vectors.count(); id++) {
                boolean erased = id <= Integer.MAX_VALUE && erasedEntities.contains((int) id);
                if (erased && vectors.vectorOf(id) != null) {
                    cleared++;
                }
                rewritten.put(erased ? null : vectors.vectorOf(id));
            }
            rewritten.finish();
        }
        if (cleared > 0) {
            LOGGER.info("cleared {} vector(s) from the served vector file at {}",
                cleared, replacement);
        }
    }

    @Override
    public String toString() {
        return "EntityVectorEraser" + NAMES;
    }
}
