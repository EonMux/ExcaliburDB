package io.excalibur.retrieval.lexical;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.excalibur.graph.erasure.ErasureRequest;
import io.excalibur.graph.erasure.ErasureScope;
import io.excalibur.graph.erasure.EntityVectorEraser;
import io.excalibur.graph.erasure.ServedArtifactEraser;
import io.excalibur.graph.format.EdgeStoreDescriptor;
import io.excalibur.graph.staging.GraphStagingStore;
import io.excalibur.graph.vector.EntityVectors;
import io.excalibur.graph.vector.EntityVectorWriter;
import io.excalibur.retrieval.build.GraphDirectoryBuilder;

import org.roaringbitmap.RoaringBitmap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Erases from the served artifacts a build <em>derives</em> rather than stores.
 *
 * <h2>The gap this closes</h2>
 * <p>An entity's document in the {@code documents} index is prose accumulated from every source
 * that mentioned them, and the entity vector beside it is computed from that document. Neither
 * records which source contributed which words, so neither can be edited to withdraw one
 * document: deleting the entity's whole entry would take prose nobody asked to erase, and a
 * vector cannot have a sentence subtracted from it. The compaction therefore reported both as
 * unreached for a source- or extractor-scoped request, left the obligation outstanding, and
 * went on serving the withdrawn prose - honestly, and indefinitely.
 *
 * <p>The way through is the one the build already takes. {@code GraphErasureCoordinator} erases
 * the staged facts, snippets and labels <em>before</em> the compaction, so by the time an eraser
 * runs, staging no longer holds anything the request covers. Rebuilding these artifacts from it
 * is therefore rebuilding them from a corpus the withdrawn document has already left, and the
 * result is what the next ordinary build would have produced anyway - published now, rather than
 * whenever somebody next asks for a build.
 *
 * <h2>Why the cheap path still exists</h2>
 * <p>A rebuild is a pass over the whole staged corpus, and with an embedder it is also one model
 * call per batch of entities. That is worth paying to reach a withdrawn document and is not worth
 * paying for an erasure that names a person: deleting that entity's document and clearing that
 * entity's vector slot reaches an entity scope exactly. So the batch decides - see
 * {@link ServedArtifactEraser#erase(Path, Path, RoaringBitmap, Collection)} - and a batch of
 * nothing but entity scopes takes the cheap path.
 *
 * <h2>Why the vectors are claimed only with an embedder</h2>
 * <p>A node with no embedding endpoint can rebuild the text and not the vectors. Claiming the
 * vector file anyway would publish a graph whose vectors had silently been dropped; leaving it
 * unclaimed hands it to {@code EntityVectorEraser}, which clears an erased entity's slot, says
 * it cannot answer a withdrawn source, and so keeps the obligation visibly outstanding instead
 * of quietly unmet.
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use in the sense that the compaction needs: a graph directory is
 * compacted through one coordinator, which serialises the rewrites this is called from.
 *
 * @author ExcaliburDB
 */
public final class DerivedArtifactEraser implements ServedArtifactEraser {

    private static final Logger LOGGER = LoggerFactory.getLogger(DerivedArtifactEraser.class);

    /** How many entity documents are embedded in one call, as the build batches them. */
    private static final int EMBEDDING_BATCH = GraphDirectoryBuilder.EMBEDDING_BATCH;

    private final GraphStagingStore staging;
    private final double ramBufferMb;

    /** Produces the entity vectors, or null on a node with no embedding endpoint. */
    private final GraphDirectoryBuilder.EntityEmbedder embedder;

    private final int dimension;
    private final Set<String> names;

    /** The cheap path for the two Lucene indexes. */
    private final LexicalIndexEraser labels;
    private final LexicalIndexEraser documents;

    /** The cheap path for the vectors, used only when this eraser claims them. */
    private final EntityVectorEraser vectors;

    /**
     * An eraser that rebuilds the two Lucene indexes and leaves the vectors to the built-in.
     *
     * @param staging     The staged facts and prose, already cleaned by the erasure
     * @param ramBufferMb Lucene write buffer for a rebuild, in megabytes
     * @throws NullPointerException     if {@code staging} is null
     * @throws IllegalArgumentException if {@code ramBufferMb} is not positive
     */
    public DerivedArtifactEraser(GraphStagingStore staging, double ramBufferMb) {
        this(staging, ramBufferMb, null, 0);
    }

    /**
     * An eraser that also rebuilds the entity vectors, on a node that can embed.
     *
     * @param staging     The staged facts and prose, already cleaned by the erasure
     * @param ramBufferMb Lucene write buffer for a rebuild, in megabytes
     * @param embedder    Produces the entity vectors, or null to leave them to the built-in
     * @param dimension   The dimension that embedder produces
     * @throws NullPointerException     if {@code staging} is null
     * @throws IllegalArgumentException if {@code ramBufferMb} is not positive, or an embedder
     *                                  is given without a positive dimension
     */
    public DerivedArtifactEraser(GraphStagingStore staging, double ramBufferMb,
                                 GraphDirectoryBuilder.EntityEmbedder embedder, int dimension) {
        this.staging = Objects.requireNonNull(staging, "staging must not be null");
        if (ramBufferMb <= 0) {
            throw new IllegalArgumentException(
                "ramBufferMb must be positive, got " + ramBufferMb);
        }
        if (embedder != null && dimension <= 0) {
            throw new IllegalArgumentException("an embedder needs a positive dimension, got "
                + dimension);
        }
        this.ramBufferMb = ramBufferMb;
        this.embedder = embedder;
        this.dimension = dimension;
        this.labels = LexicalIndexEraser.labels(ramBufferMb);
        this.documents = LexicalIndexEraser.documents(ramBufferMb);
        this.vectors = embedder == null ? null : new EntityVectorEraser();

        Set<String> claimed = new LinkedHashSet<>(
            Set.of(GraphDirectoryBuilder.LABEL_INDEX, GraphDirectoryBuilder.DOCUMENT_INDEX));
        if (embedder != null) {
            claimed.add(EntityVectors.FILE_NAME);
        }
        this.names = Set.copyOf(claimed);
    }

    @Override
    public Set<String> names() {
        return names;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every scope, because a rebuild from cleaned staging answers every scope: whatever the
     * request covered is no longer in the corpus these artifacts are derived from. That is the
     * claim this class exists to be able to make, and it is what lets a withdrawn document stop
     * being reported as unreachable for ever.
     */
    @Override
    public boolean reaches(ErasureScope scope) {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Rebuilds, because without the batch there is no way to know that the cheap path would
     * have been enough, and being slower is the safe direction to be wrong in.
     */
    @Override
    public void erase(Path served, Path replacement, RoaringBitmap erasedEntities)
            throws IOException {
        rebuild(replacement);
    }

    @Override
    public void erase(Path served, Path replacement, RoaringBitmap erasedEntities,
                      Collection<ErasureRequest> requests) throws IOException {
        if (namesEntitiesOnly(requests)) {
            deleteNamedEntities(served, replacement, erasedEntities);
            return;
        }
        rebuild(replacement);
    }

    /**
     * Whether every request in the batch selects an entity rather than a provenance.
     *
     * <p>An empty batch counts as neither and is answered by the rebuild, which is the safe way
     * round: the compaction only calls an eraser when it is discharging something, so an empty
     * batch here means this class is being used in a way it was not designed for.
     *
     * @param requests The batch
     * @return true when the cheap path reaches all of it
     */
    private static boolean namesEntitiesOnly(Collection<ErasureRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return false;
        }
        for (ErasureRequest request : requests) {
            if (!(request.scope() instanceof ErasureScope.Entity)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the named entities from each artifact without rebuilding any of them.
     *
     * @param served         The served directory, which is only read
     * @param replacement    The directory being assembled
     * @param erasedEntities The ids whose data must not survive
     * @throws IOException if an artifact cannot be read or its replacement written
     */
    private void deleteNamedEntities(Path served, Path replacement, RoaringBitmap erasedEntities)
            throws IOException {
        labels.erase(served, replacement, erasedEntities);
        documents.erase(served, replacement, erasedEntities);
        if (vectors != null) {
            vectors.erase(served, replacement, erasedEntities);
        }
    }

    /**
     * Writes the derived artifacts into the replacement from staging as it now stands.
     *
     * <p>Staging is the input, and the served copy is not read at all: there is nothing in it to
     * carry across that staging does not already say, and reading it would only offer the chance
     * to reintroduce what the erasure has just taken out.
     *
     * @param replacement The directory being assembled
     * @throws IOException if staging cannot be read or an artifact cannot be written
     */
    private void rebuild(Path replacement) throws IOException {
        Files.createDirectories(replacement);
        List<String> labels = staging.entityLabels();
        List<String> documents = GraphDirectoryBuilder.entityDocuments(staging);
        int addressable = addressableEntities(replacement, labels.size());

        writeIndex(replacement.resolve(GraphDirectoryBuilder.LABEL_INDEX), labels, addressable);
        writeIndex(replacement.resolve(GraphDirectoryBuilder.DOCUMENT_INDEX), documents,
            addressable);
        long embedded = embedder == null
            ? 0
            : writeVectors(replacement, documents.subList(0, addressable));

        LOGGER.info("rebuilt the derived graph artifacts at {} from erased staging: {} entity "
            + "document(s), {} vector(s)", replacement, addressable, embedded);
    }

    /**
     * How many entity ids the replacement store addresses, which bounds what may be published.
     *
     * <h3>Why this is not simply what staging holds</h3>
     * <p>Staging runs ahead of the store by design: facts are ingested continuously and a build
     * is a step somebody takes. A rebuild that published every entity staging knows would put
     * ids past the store's entity count into the index entity linking searches, and the store
     * refuses one outright - so a question that happened to match a name ingested since the last
     * build would fail the request instead of answering it. A compaction preserves the id space
     * rather than renumbering it, and this has to preserve the same one.
     *
     * <p>Read from the replacement rather than from the served copy because the compaction has
     * already written the rewritten store there, carrying the original entity count forward.
     *
     * @param replacement The directory being assembled
     * @param staged      How many entities staging holds
     * @return The smaller of the two
     * @throws IOException if the descriptor is present and cannot be read
     */
    private static int addressableEntities(Path replacement, int staged) throws IOException {
        if (!Files.exists(replacement.resolve(EdgeStoreDescriptor.FILE_NAME))) {
            // No store beside these artifacts, so nothing constrains the id space. An eraser
            // used outside a compaction, which is the only way to get here.
            return staged;
        }
        long addressable = EdgeStoreDescriptor.read(replacement).entityCount();
        return (int) Math.min(staged, addressable);
    }

    /**
     * Writes one Lucene index over the first {@code count} entities' text.
     *
     * @param path  Where the index goes, inside the replacement directory
     * @param texts One string per entity id, in id order
     * @param count How many of them to publish
     * @throws IOException if the index cannot be written
     */
    private void writeIndex(Path path, List<String> texts, int count) throws IOException {
        try (LexicalIndex index = LexicalIndex.open(path, ramBufferMb)) {
            for (int id = 0; id < count; id++) {
                index.put(id, texts.get(id));
            }
            index.commit();
        }
    }

    /**
     * Embeds the rebuilt entity documents and writes the vectors beside them.
     *
     * <p>Batched, because an embedding endpoint charges per call as well as per token. A batch
     * the endpoint could not answer is written as no vector rather than failing the erasure:
     * those entities keep whatever score the lexical ranking gives them, which is what a
     * reranker does with an unscored candidate anyway, and an erasure that failed because a
     * model was unavailable would leave the withdrawn prose on disk.
     *
     * @param replacement The directory being assembled
     * @param documents   The entity documents to embed, in id order
     * @return How many entities got a vector
     * @throws IOException if the file cannot be written
     */
    private long writeVectors(Path replacement, List<String> documents) throws IOException {
        Path path = replacement.resolve(EntityVectors.FILE_NAME);
        try (EntityVectorWriter writer =
                 new EntityVectorWriter(path, dimension, documents.size())) {
            for (int start = 0; start < documents.size(); start += EMBEDDING_BATCH) {
                int end = Math.min(start + EMBEDDING_BATCH, documents.size());
                writeBatch(writer, documents.subList(start, end), start);
            }
            writer.finish();
            return writer.withVector();
        }
    }

    /**
     * Embeds one batch and writes its vectors, or writes none if the endpoint refused.
     *
     * @param writer Where the vectors go
     * @param batch  The documents of this batch, in id order
     * @param first  The entity id of the batch's first document, for the log
     * @throws IOException if the file cannot be written
     */
    private void writeBatch(EntityVectorWriter writer, List<String> batch, int first)
            throws IOException {
        List<float[]> embedded;
        try {
            embedded = embedder.embedAll(batch);
        } catch (RuntimeException e) {
            LOGGER.warn("could not embed entities {}..{} during an erasure rebuild; they will "
                + "have no vector and keep their lexical rank: {}", first,
                first + batch.size() - 1, e.toString());
            embedded = List.of();
        }
        for (int i = 0; i < batch.size(); i++) {
            float[] vector = i < embedded.size() ? embedded.get(i) : null;
            if (vector != null && vector.length != dimension) {
                LOGGER.warn("entity {} was embedded at dimension {} but the file is {}; "
                    + "recording it as having no vector", first + i, vector.length, dimension);
                vector = null;
            }
            writer.put(vector);
        }
    }

    @Override
    public String toString() {
        return "DerivedArtifactEraser" + names;
    }
}
