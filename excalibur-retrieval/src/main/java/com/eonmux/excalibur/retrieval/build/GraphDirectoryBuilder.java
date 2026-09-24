package com.eonmux.excalibur.retrieval.build;

import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.PublishFence;
import com.eonmux.excalibur.graph.staging.PublishRefusedException;
import com.eonmux.excalibur.graph.staging.StagedErasure;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;
import com.eonmux.excalibur.graph.vector.EntityVectorWriter;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds everything the graph-RAG stack reads, and publishes it in one move.
 *
 * <h2>Why the whole directory is built together</h2>
 * <p>The stack is four artifacts that have to agree: the edge store, the dictionaries that name
 * its integer ids, a Lucene index over entity labels that entity linking searches, and another
 * over each entity's text that the lexical retrieval signal ranks. All four are keyed by the same
 * entity ids. Publishing them separately would leave a window in which an id means one thing to
 * the store and another to the index over it - and because an integer is always a valid integer,
 * that window produces confident wrong answers rather than errors.</p>
 *
 * <p>So the whole directory is assembled beside the live one and renamed into place: a reader sees
 * the previous graph or the new one, never a mixture.</p>
 *
 * <h2>One way to build</h2>
 * <p>{@link #build} is the only entry point, and the node's own build runs through it. It takes
 * the opener the caller serves the graph through, because opening is part of publishing: the
 * graph being replaced is deleted only once the new one has opened. A second, shorter path that
 * published without opening would be a build the node never runs, and a test passing against it
 * would say nothing about the one it does.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless, but two concurrent builds for the same target would race over one scratch
 * directory. Callers serialise builds.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphDirectoryBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphDirectoryBuilder.class);

    /** Lucene index over each entity's text, ranked by the lexical retrieval signal. */
    public static final String DOCUMENT_INDEX = "documents";

    /** Lucene index over entity labels, searched by entity linking. */
    public static final String LABEL_INDEX = "labels";

    /**
     * How many entity documents are embedded in one call.
     *
     * <p>Batched because an embedding endpoint charges per call as well as per token, and one call
     * per entity over a corpus of any size is the difference between a build that finishes and one
     * that is still going. Bounded because the request body has to fit.
     */
    public static final int EMBEDDING_BATCH = 64;

    /**
     * Longest document one entity gets.
     *
     * <p>An entity named in ten thousand documents would otherwise accumulate all of them, and
     * BM25 over a document that large ranks by nothing in particular.
     */
    public static final int MAX_ENTITY_TEXT_CHARS = 16_000;

    private GraphDirectoryBuilder() {
        throw new AssertionError("Utility class");
    }

    /**
     * What a build produced.
     *
     * @param edges     how many facts reached the store
     * @param entities  how many entity ids it addresses
     * @param relations how many relations were named
     * @param indexed   how many entities got a text document
     * @param embedded  how many entities got a vector
     * @param withheld  how many staged facts were left out because an outstanding erasure
     *                  obligation covers them
     * @param directory where it was published
     */
    public record BuildResult(long edges, int entities, int relations, int indexed,
                              long embedded, long withheld, Path directory) {

        /** Whether anything was published. */
        public boolean published() {
            return edges > 0;
        }
    }

    /**
     * What a build produced, and what opening it produced when it published.
     *
     * @param <T>    What the caller serves the graph through
     * @param result What was built
     * @param opened What the opener returned, or null when nothing was published
     */
    public record Published<T>(BuildResult result, T opened) {

        /**
         * Validates the result.
         *
         * @throws NullPointerException if result is null
         */
        public Published {
            Objects.requireNonNull(result, "result must not be null");
        }
    }

    /**
     * Turns entity documents into vectors at build time.
     *
     * <p>An interface rather than the embedding client itself, so the builder does not care which
     * model produced them and a test can supply its own. Implementations return one vector per
     * input, in order.</p>
     */
    @FunctionalInterface
    public interface EntityEmbedder {

        /**
         * Embeds a batch of entity documents.
         *
         * @param texts The documents, in entity id order
         * @return One vector per document, in the same order
         */
        java.util.List<float[]> embedAll(java.util.List<String> texts);
    }

    /**
     * Builds the graph directory from staged facts, publishes it, and opens it.
     *
     * <p>The store, both indexes and the entity vectors are assembled beside the served
     * directory, forced to the device, renamed into place and opened with {@code opener} - and
     * the graph they replace is kept until that open succeeds, so a build that publishes
     * something unopenable leaves the previous graph where it was. Nothing being served is
     * touched before the rename, so a build that fails earlier leaves the node answering exactly
     * as it did.</p>
     *
     * <p>A build with nothing staged publishes nothing and opens nothing: an empty store would
     * turn every question into a refusal, where the graph already on disk answers some of
     * them.</p>
     *
     * @param <T>          What the caller serves the graph through
     * @param staging      The facts and the text to build from
     * @param target       Where the graph is served from
     * @param supersession Which relations hold one value at a time
     * @param ramBufferMb  Lucene write buffer, in megabytes
     * @param embedder     Produces entity vectors, or null to build without them
     * @param dimension    The dimension those vectors have
     * @param opener       Opens the published directory, before the previous one is deleted
     * @return What was built, and what the opener returned
     * @throws IOException if any artifact cannot be written, published or opened
     * @throws NullPointerException if staging, target, supersession or opener is null
     */
    public static <T> Published<T> build(GraphStagingStore staging, Path target,
                                         SupersessionPolicy supersession, double ramBufferMb,
                                         EntityEmbedder embedder, int dimension,
                                         GraphBuilder.Opener<T> opener) throws IOException {
        return build(staging, target, supersession, ramBufferMb, embedder, dimension, opener,
            PublishFence.NONE);
    }

    /**
     * As {@link #build(GraphStagingStore, Path, SupersessionPolicy, double, EntityEmbedder, int,
     * GraphBuilder.Opener)}, leaving out what {@code fence} withholds and publishing through it.
     *
     * <h3>Every artifact leaves the same things out</h3>
     * <p>A fact the fence withholds is not in the store, a name it withholds is its erased
     * placeholder in the dictionary and in the label index, and a snippet it withholds is in
     * neither the entity documents nor the vectors computed from them. Leaving a fact out of the
     * store alone would still publish the name and the prose it came with, which is what an
     * erasure of a person is most about.</p>
     *
     * <h3>A refused publish</h3>
     * <p>The scratch directory is discarded before the refusal propagates: it was assembled
     * from staging read before the refusing obligation existed, so it may hold what that
     * obligation covers, and nothing will serve it.</p>
     *
     * @param <T>          What the caller serves the graph through
     * @param staging      The facts and the text to build from
     * @param target       Where the graph is served from
     * @param supersession Which relations hold one value at a time
     * @param ramBufferMb  Lucene write buffer, in megabytes
     * @param embedder     Produces entity vectors, or null to build without them
     * @param dimension    The dimension those vectors have
     * @param opener       Opens the published directory, before the previous one is deleted
     * @param fence        What to leave out, and the gate the publish passes through
     * @return What was built, and what the opener returned
     * @throws PublishRefusedException if an erasure was recorded while the graph was being
     *                                 built; nothing was published
     * @throws IOException if any artifact cannot be written, published or opened
     * @throws NullPointerException if staging, target, supersession, opener or fence is null
     */
    public static <T> Published<T> build(GraphStagingStore staging, Path target,
                                         SupersessionPolicy supersession, double ramBufferMb,
                                         EntityEmbedder embedder, int dimension,
                                         GraphBuilder.Opener<T> opener, PublishFence fence)
            throws IOException {
        Objects.requireNonNull(staging, "staging must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(supersession, "supersession must not be null");
        Objects.requireNonNull(opener, "opener must not be null");
        Objects.requireNonNull(fence, "fence must not be null");

        StagedErasure withheld = fence.withheld();
        GraphBuilder.BuildResult store =
            GraphBuilder.buildInto(staging, target, supersession, withheld);
        if (store.edges() == 0) {
            LOGGER.info("nothing to publish for {}; leaving the existing graph in place", target);
            return new Published<>(new BuildResult(0, store.entities(), store.relations(), 0, 0,
                store.withheld(), target), null);
        }

        Path scratch = GraphBuilder.scratchFor(target);
        List<String> documents = entityDocuments(staging, withheld);
        int indexed = writeLexicalIndexes(GraphBuilder.entityLabels(staging, withheld),
            documents, scratch, ramBufferMb);
        // Into the same scratch directory, so the vectors are published by the same rename as
        // the store they describe. Vectors that arrived a moment later would be vectors for the
        // previous numbering, and an entity id is always a valid entity id.
        long embedded = embedder == null
            ? 0
            : writeEntityVectors(documents, scratch, embedder, dimension);
        T opened;
        try {
            opened = GraphBuilder.publish(scratch, target, Durability.DEVICE, opener, fence);
        } catch (PublishRefusedException refused) {
            GraphBuilder.discard(scratch);
            throw refused;
        }

        LOGGER.info("published graph at {}: {} facts, {} entities ({} with text), {} relations",
            target, store.edges(), store.entities(), indexed, store.relations());
        return new Published<>(new BuildResult(store.edges(), store.entities(),
            store.relations(), indexed, embedded, store.withheld(), target), opened);
    }

    /**
     * Embeds each entity's document and writes the vectors beside the store.
     *
     * <p>The same text the lexical index holds, so the two describe the same entity: BM25 decides
     * which entities are worth considering and these decide how close each one actually is. Writing
     * them into the scratch directory means they are published by the same rename as everything
     * else.</p>
     *
     * <p>An entity whose batch could not be embedded is written as having no vector rather than
     * failing the build. It stays traversable, stays in the lexical index, and simply keeps
     * whatever score the ranking gave it - which is what a reranker does with an unscored candidate
     * anyway.</p>
     *
     * @param documents The document text per entity id, in id order
     * @param scratch   Where the build is being assembled
     * @param embedder  Produces the vectors
     * @param dimension The dimension every vector must have
     * @return How many entities got a vector
     * @throws IOException if the file cannot be written
     */
    private static long writeEntityVectors(List<String> documents, Path scratch,
                                           EntityEmbedder embedder, int dimension)
            throws IOException {
        Path path = scratch.resolve(com.eonmux.excalibur.graph.vector.EntityVectors.FILE_NAME);
        try (EntityVectorWriter writer =
                 new EntityVectorWriter(path, dimension, documents.size())) {

            for (int start = 0; start < documents.size(); start += EMBEDDING_BATCH) {
                int end = Math.min(start + EMBEDDING_BATCH, documents.size());
                List<String> batch = documents.subList(start, end);

                List<float[]> embedded;
                try {
                    embedded = embedder.embedAll(batch);
                } catch (RuntimeException e) {
                    LOGGER.warn("could not embed entities {}..{}; they will have no vector and "
                        + "keep their lexical rank: {}", start, end - 1, e.toString());
                    embedded = null;
                }

                for (int i = 0; i < batch.size(); i++) {
                    float[] vector = embedded == null || i >= embedded.size()
                        ? null
                        : embedded.get(i);
                    if (vector != null && vector.length != dimension) {
                        LOGGER.warn("entity {} was embedded at dimension {} but the file is {}; "
                            + "recording it as having no vector",
                            start + i, vector.length, dimension);
                        vector = null;
                    }
                    writer.put(vector);
                }
            }

            writer.finish();
            return writer.withVector();
        }
    }

    /**
     * Writes the label and entity-text indexes into the directory the store was assembled in.
     *
     * @param staging     The staged text
     * @param scratch     Where the build is being assembled
     * @param ramBufferMb Lucene write buffer, in megabytes
     * @return How many entities got a text document
     * @throws IOException if an index cannot be written
     */
    private static int writeLexicalIndexes(List<String> labels, List<String> documents,
                                           Path scratch, double ramBufferMb) throws IOException {
        try (LexicalIndex labelIndex = LexicalIndex.open(scratch.resolve(LABEL_INDEX),
                ramBufferMb)) {
            for (int id = 0; id < labels.size(); id++) {
                labelIndex.put(id, labels.get(id));
            }
            labelIndex.commit();
        }

        try (LexicalIndex documentIndex = LexicalIndex.open(scratch.resolve(DOCUMENT_INDEX),
                ramBufferMb)) {
            for (int id = 0; id < documents.size(); id++) {
                documentIndex.put(id, documents.get(id));
            }
            documentIndex.commit();
        }

        return documents.size();
    }

    /**
     * The text that describes each entity, in id order.
     *
     * <p>What BM25 ranks an entity by, and what its vector is computed from - deliberately the same
     * string, so the lexical signal and the vector rerank are describing the same thing. An entity
     * that was never mentioned in any staged snippet falls back to its own label: leaving it out
     * would make it unrankable rather than weakly ranked, and it is reachable by traversal either
     * way.</p>
     *
     * <p>Public because erasure rewrites these same documents after removing a fact, and has to
     * produce exactly what a build would.</p>
     *
     * @param staging The staged text
     * @return One document per entity id
     * @throws IOException if the staged text cannot be read
     */
    public static List<String> entityDocuments(GraphStagingStore staging) throws IOException {
        return entityDocuments(staging, StagedErasure.NOTHING);
    }

    /**
     * The text that describes each entity, in id order, leaving out what {@code withheld}
     * covers: a snippet it covers is part of no document, and an entity whose document falls
     * back to its label falls back to the label a build publishes - the erased placeholder when
     * the name is withheld.
     *
     * @param staging  The staged text
     * @param withheld What to leave out
     * @return One document per entity id
     * @throws IOException if the staged text cannot be read
     * @throws NullPointerException if an argument is null
     */
    public static List<String> entityDocuments(GraphStagingStore staging,
                                               StagedErasure withheld) throws IOException {
        Objects.requireNonNull(withheld, "withheld must not be null");
        List<String> labels = GraphBuilder.entityLabels(staging, withheld);

        Map<Integer, StringBuilder> texts = new HashMap<>();
        staging.forEachEntityText((entityId, snippet, provenance) -> {
            if (withheld.erasesSnippet(entityId, provenance)) {
                return;
            }
            StringBuilder document = texts.computeIfAbsent(entityId, id -> new StringBuilder());
            if (document.length() >= MAX_ENTITY_TEXT_CHARS) {
                return;
            }
            if (document.length() > 0) {
                document.append(' ');
            }
            document.append(snippet);
        });

        List<String> documents = new java.util.ArrayList<>(labels.size());
        for (int id = 0; id < labels.size(); id++) {
            StringBuilder document = texts.get(id);
            documents.add(document == null || document.length() == 0
                ? labels.get(id)
                : document.toString());
        }
        return documents;
    }
}
