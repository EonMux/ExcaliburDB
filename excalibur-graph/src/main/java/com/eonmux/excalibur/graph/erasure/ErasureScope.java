package com.eonmux.excalibur.graph.erasure;

import com.eonmux.excalibur.graph.provenance.SourceType;

/**
 * What an erasure request covers.
 *
 * <p>Sealed, so a compactor that gains a new scope must handle it rather than silently ignoring
 * requests it does not recognise — an erasure quietly not applied is the worst possible outcome of
 * this whole mechanism.
 */
public sealed interface ErasureScope {

    /**
     * Every edge touching an entity, in either direction.
     *
     * <p>Both directions matter. Erasing a person means erasing what they did and what was done to
     * them; keeping the reverse edges would leave the entity reconstructible from its neighbours.
     */
    record Entity(long entityId) implements ErasureScope {
        public Entity {
            if (entityId < 0) {
                throw new IllegalArgumentException("entityId must not be negative");
            }
        }
    }

    /**
     * Every edge extracted from one source.
     *
     * <p>For withdrawing a document, a conversation, or everything a retracted feed contributed.
     * Answerable only because provenance is stored per edge.
     */
    record Source(SourceType sourceType, long sourceId) implements ErasureScope {
        public Source {
            if (sourceType == null) {
                throw new IllegalArgumentException("sourceType must not be null");
            }
        }
    }

    /**
     * Every edge produced by one extractor build.
     *
     * <p>For retracting the output of an extractor found to be wrong, without touching what other
     * versions produced. This is what {@code extractor_version} is stored for.
     */
    record ExtractorVersion(int version) implements ErasureScope {
    }
}
