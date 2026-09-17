package io.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import io.excalibur.graph.dictionary.LabelDictionary;
import io.excalibur.graph.dictionary.LabelDictionaryWriter;
import io.excalibur.graph.staging.GraphBuilder;
import io.excalibur.graph.staging.LabelInterner;

import org.roaringbitmap.RoaringBitmap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replaces an erased entity's name in the served entity dictionary with a placeholder.
 *
 * <h2>Why the served copy needs this at all</h2>
 * <p>The staging side already does it: {@code GraphStagingStore.erase} tombstones the label in
 * {@code entities.labels} so the next build cannot republish the name of somebody who asked to be
 * forgotten. The dictionary the graph is <em>served</em> from is a second copy of that name, in a
 * different file, written by the build rather than by the erasure - and a compaction that carried
 * it across unchanged left the erased person named on disk and named in every answer that
 * happened to include their id, indefinitely, with the obligation reported as met.
 *
 * <h2>Why a placeholder rather than a missing entry</h2>
 * <p>Ids may not move: the edge store, the lexical indexes and any serialized subgraph all
 * address entities by these integers and none of them is rebuilt by an erasure. The entry
 * therefore stays and only its text changes, to {@link LabelInterner#erasedLabel} - the same
 * placeholder the staging interner uses, so that this compaction and the next build agree about
 * what an erased id is called instead of alternating between two answers.
 *
 * @author ExcaliburDB
 */
public final class EntityDictionaryEraser implements ServedArtifactEraser {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityDictionaryEraser.class);

    /** The entity dictionary, as {@code GraphBuilder} writes it and {@code GraphRag} reads it. */
    private static final Set<String> NAMES = Set.of(
        GraphBuilder.ENTITY_DICTIONARY + ".idx",
        GraphBuilder.ENTITY_DICTIONARY + ".dat");

    @Override
    public Set<String> names() {
        return NAMES;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every scope. An entity-scoped request has its name replaced here; a source-scoped or
     * extractor-scoped one covers nothing in this file, because a withdrawn document must not
     * take the names of everyone it happened to mention with it - those people did not ask to be
     * forgotten, and their ids are referenced by facts from other sources. That is the same rule
     * the staging erasure applies to {@code entities.labels}.
     */
    @Override
    public boolean reaches(ErasureScope scope) {
        return true;
    }

    @Override
    public void erase(Path served, Path replacement, RoaringBitmap erasedEntities)
            throws IOException {
        LabelDictionary dictionary =
            LabelDictionary.openIfPresent(served, GraphBuilder.ENTITY_DICTIONARY, "E");
        if (dictionary == null) {
            return;
        }

        LabelDictionaryWriter rewritten = new LabelDictionaryWriter();
        int[] tombstoned = {0};
        try (dictionary) {
            dictionary.forEach((id, name) -> {
                boolean erased = id >= 0 && id <= Integer.MAX_VALUE
                    && erasedEntities.contains((int) id);
                if (erased) {
                    tombstoned[0]++;
                }
                rewritten.put(id, erased ? LabelInterner.erasedLabel((int) id) : name);
            });
        }

        Files.createDirectories(replacement);
        rewritten.write(replacement, GraphBuilder.ENTITY_DICTIONARY);
        if (tombstoned[0] > 0) {
            LOGGER.info("tombstoned {} name(s) in the served entity dictionary at {}; "
                + "{} entries unchanged", tombstoned[0], replacement,
                rewritten.size() - tombstoned[0]);
        }
    }

    @Override
    public String toString() {
        return "EntityDictionaryEraser" + NAMES;
    }
}
