package io.excalibur.retrieval.build;

import io.excalibur.graph.staging.GraphBuilder;
import io.excalibur.graph.staging.GraphStagingStore;
import io.excalibur.graph.temporal.SupersessionPolicy;
import io.excalibur.graph.vector.EntityVectorWriter;
import io.excalibur.retrieval.lexical.LexicalIndex;

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
     * @param directory where it was published
     */
    public record BuildResult(long edges, int entities, int relations, int indexed,
                              long embedded, Path directory) {

        /** Whether anything was published. */
        public boolean published() {
            return edges > 0;
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
     * Builds the graph directory from staged facts and publishes it.
     *
     * @param staging       The facts and the text to build from
     * @param target        Where the graph is served from
     * @param supersession  Which relations hold one value at a time
     * @param ramBufferMb   Lucene write buffer, in megabytes
     * @return What was built
     * @throws IOException if any artifact cannot be written or published
     */
    public static BuildResult build(GraphStagingStore staging, Path target,
                                    SupersessionPolicy supersession, double ramBufferMb)
            throws IOException {
        return build(staging, target, supersession, ramBufferMb, null, 0);
    }

    /**
     * Builds the graph directory, including the vectors a rerank scores against.
     *
     * @param staging      The facts and the text to build from
     * @param target       Where the graph is served from
     * @param supersession Which relations hold one value at a time
     * @param ramBufferMb  Lucene write buffer, in megabytes
     * @param embedder     Produces entity vectors, or null to build without them
     * @param dimension    The dimension those vectors have
     * @return What was built
     * @throws IOException if any artifact cannot be written or published
     */
    public static BuildResult build(GraphStagingStore staging, Path target,
                                    SupersessionPolicy supersession, double ramBufferMb,
                                    EntityEmbedder embedder, int dimension) throws IOException {
        Objects.requireNonNull(staging, "staging must not be null");
        Objects.requireNonNull(target, "target must not be null");

        GraphBuilder.BuildResult store = GraphBuilder.buildInto(staging, target, supersession);
        if (store.edges() == 0) {
            LOGGER.info("nothing staged for {}; leaving the existing graph in place", target);
            return new BuildResult(0, store.entities(), store.relations(), 0, 0, target);
        }

        Path scratch = GraphBuilder.scratchFor(target);
        int indexed = writeLexicalIndexes(staging, scratch, ramBufferMb);
        long embedded = embedder == null
            ? 0
            : writeEntityVectors(staging, scratch, embedder, dimension);
        GraphBuilder.publish(scratch, target);

        LOGGER.info("published graph at {}: {} facts, {} entities ({} with text), {} relations",
            target, store.edges(), store.entities(), indexed, store.relations());
        return new BuildResult(store.edges(), store.entities(), store.relations(), indexed,
            embedded, target);
    }

    /**
     * Embeds each entity's document and writes the vectors beside the store.
     *
     * <p>The same text the lexical index holds, so the two describe the same entity: BM25 decides
     * which entities are worth considering and these decide how close each one actually is. Writing
     * them into the scratch directory means they are published by the same rename as everything
     * else - vectors that arrived a moment after the store they belong to would be vectors for the
     * previous numbering.</p>
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
        Path path = scratch.resolve(io.excalibur.graph.vector.EntityVectors.FILE_NAME);
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
     * <p>Public so a caller that has to sequence the publish itself - closing what it is serving
     * before the directory moves, because Lucene holds a per-path write lock for the whole JVM -
     * can still assemble the same directory this class does.
     *
     * @param staging     The staged text
     * @param scratch     Where the build is being assembled
     * @param ramBufferMb Lucene write buffer, in megabytes
     * @return How many entities got a text document
     * @throws IOException if an index cannot be written
     */
    public static int writeLexicalIndexes(GraphStagingStore staging, Path scratch,
                                          double ramBufferMb) throws IOException {
        List<String> labels = staging.entityLabels();

        try (LexicalIndex labelIndex = LexicalIndex.open(scratch.resolve(LABEL_INDEX),
                ramBufferMb)) {
            for (int id = 0; id < labels.size(); id++) {
                labelIndex.put(id, labels.get(id));
            }
            labelIndex.commit();
        }

        List<String> documents = entityDocuments(staging);

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
     * @param staging The staged text
     * @return One document per entity id
     * @throws IOException if the staged text cannot be read
     */
    public static List<String> entityDocuments(GraphStagingStore staging) throws IOException {
        List<String> labels = staging.entityLabels();

        Map<Integer, StringBuilder> texts = new HashMap<>();
        staging.forEachEntityText((entityId, snippet) -> {
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

    /**
     * Embeds every entity's document and writes the vectors into the build being assembled.
     *
     * <p>Public because the caller that has to sequence the publish itself - closing what it is
     * serving before the directory moves - assembles the same directory this class does.</p>
     *
     * @param staging   The staged text
     * @param scratch   Where the build is being assembled
     * @param embedder  Produces the vectors
     * @param dimension The dimension every vector must have
     * @return How many entities got a vector
     * @throws IOException if the file cannot be written
     */
    public static long writeEntityVectors(GraphStagingStore staging, Path scratch,
                                          EntityEmbedder embedder, int dimension)
            throws IOException {
        Objects.requireNonNull(staging, "staging must not be null");
        Objects.requireNonNull(embedder, "embedder must not be null");
        return writeEntityVectors(entityDocuments(staging), scratch, embedder, dimension);
    }
}
