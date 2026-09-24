package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.graph.dictionary.LabelDictionaryWriter;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.graph.vector.EntityVectorWriter;
import com.eonmux.excalibur.graph.vector.EntityVectors;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.pipeline.LexicalEntityLinker;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A complete, two-entity graph store written straight to disk, for tests that need
 * {@link GraphRagFactory} to open something real.
 *
 * <p>Public so that the route tests in other packages can lay a served graph out where a
 * {@link GraphTenants} will open it, without a build and so without an embedding endpoint.</p>
 */
public final class MinimalGraph {

    /** Dimension of the entity vectors {@link #write(Path, boolean)} can add. */
    public static final int DIMENSION = 4;

    private static final int ADA = 1;
    private static final int BABBAGE = 2;
    private static final int COLLABORATED_WITH = 0;

    private MinimalGraph() {
    }

    /**
     * Writes the store into {@code directory}.
     *
     * @param directory   Where the graph goes; created if absent
     * @param withVectors Whether to add entity vectors, which is what gives a served graph a
     *                    query embedder
     * @throws IOException if any part cannot be written
     */
    public static void write(Path directory, boolean withVectors) throws IOException {
        EdgeStoreWriter edges = new EdgeStoreWriter();
        edges.add(ADA, COLLABORATED_WITH, BABBAGE, Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.USER_ASSERTED, 1, 96, 7));
        edges.build(directory, 8);

        try (LexicalIndex documents = LexicalIndex.open(
                directory.resolve(GraphRagFactory.DOCUMENT_INDEX), 16.0)) {
            documents.put(ADA, "Ada Lovelace mathematician");
            documents.put(BABBAGE, "Charles Babbage engineer");
            documents.refresh();
        }
        try (LexicalIndex labels = LexicalIndex.open(
                directory.resolve(GraphRagFactory.LABEL_INDEX), 16.0)) {
            LexicalEntityLinker.index(labels, ADA, "Ada Lovelace");
            LexicalEntityLinker.index(labels, BABBAGE, "Charles Babbage");
            labels.refresh();
        }

        new LabelDictionaryWriter()
            .put(ADA, "Ada Lovelace")
            .put(BABBAGE, "Charles Babbage")
            .write(directory, GraphRagFactory.ENTITY_DICTIONARY);
        new LabelDictionaryWriter()
            .put(COLLABORATED_WITH, "collaboratedWith")
            .write(directory, GraphRagFactory.RELATION_DICTIONARY);

        if (withVectors) {
            try (EntityVectorWriter vectors = new EntityVectorWriter(
                    directory.resolve(EntityVectors.FILE_NAME), DIMENSION, BABBAGE + 1)) {
                vectors.put(null);
                vectors.put(new float[] {1f, 0f, 0f, 0f});
                vectors.put(new float[] {0f, 1f, 0f, 0f});
                vectors.finish();
            }
        }
    }
}
