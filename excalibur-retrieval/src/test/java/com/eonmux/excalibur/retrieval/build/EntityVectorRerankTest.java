package com.eonmux.excalibur.retrieval.build;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;
import com.eonmux.excalibur.graph.vector.EntityVectors;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.lexical.LuceneRanker;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rerank.EmbeddingGatedRanker;
import com.eonmux.excalibur.retrieval.rerank.FullPrecisionReranker;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ranking a graph's entities against the vectors it stores for them.
 *
 * <p>BM25 ranks by term overlap. That makes it a good first pass and a poor last one: it cannot
 * tell an entity whose document repeats the query's words from one that is actually about them.
 * Re-scoring its candidates against the entities' real vectors is the correction, and these tests
 * are the whole path - text staged at ingest, embedded at build, mapped at query time, and used to
 * reorder a ranking BM25 got wrong.</p>
 */
@DisplayName("Entity vector rerank")
class EntityVectorRerankTest {

    private static final int DIMENSION = 4;
    private static final double RAM_BUFFER_MB = 4.0;

    private static Provenance from(long document) {
        return Provenance.of(SourceType.DOCUMENT, document, 0.9, 1);
    }

    /**
     * An embedder that reads its vector from the text, so a test can state the geometry it wants
     * rather than depend on a model.
     *
     * <p>A document containing "v=a,b,c,d" embeds to those four numbers; anything else embeds to
     * the zero-adjacent vector, which cosine treats as maximally distant from the query used here.
     */
    private static GraphDirectoryBuilder.EntityEmbedder statedVectors() {
        return texts -> {
            List<float[]> out = new ArrayList<>(texts.size());
            for (String text : texts) {
                out.add(parse(text));
            }
            return out;
        };
    }

    private static float[] parse(String text) {
        int marker = text.indexOf("v=");
        if (marker < 0) {
            return new float[] { 0.0f, 0.0f, 0.0f, 1.0f };
        }
        String[] parts = text.substring(marker + 2).split("[^0-9.\\-]")[0].split(",");
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION && i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }

    /** Stages two entities whose text differs, and whose stated vectors differ from that order. */
    private static void stage(GraphStagingStore staging) throws IOException {
        staging.append(ExtractedTriple.alwaysValid("Alpha", "knows", "Beta", from(1)));

        // Alpha's document repeats the query term more often, so BM25 puts it first. Its stated
        // vector points away from the query; Beta's points at it.
        staging.appendEntityText(staging.entityIdOf("Alpha"),
            "engine engine engine engine v=0,1,0,0");
        staging.appendEntityText(staging.entityIdOf("Beta"),
            "engine v=1,0,0,0");
    }

    private static Path build(Path tempDir, GraphDirectoryBuilder.EntityEmbedder embedder)
            throws IOException {
        Path target = tempDir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"))) {
            stage(staging);
            GraphDirectoryBuilder.build(staging, target, SupersessionPolicy.NONE, RAM_BUFFER_MB,
                embedder, DIMENSION, EntityVectorRerankTest::openVectors);
        }
        return target;
    }

    /**
     * Opens the vectors a build published, as the node's stack does before the publish lets the
     * previous graph go.
     */
    private static Long openVectors(Path published) throws IOException {
        try (EntityVectors vectors = EntityVectors.openIfPresent(published)) {
            return vectors == null ? 0L : vectors.presentCount();
        }
    }

    @Test
    @DisplayName("a build with an embedder writes one vector per entity")
    void aBuildWritesEntityVectors(@TempDir Path tempDir) throws IOException {
        Path target = build(tempDir, statedVectors());

        try (EntityVectors vectors = EntityVectors.openIfPresent(target)) {
            assertThat(vectors).isNotNull();
            assertThat(vectors.dimension()).isEqualTo(DIMENSION);
            assertThat(vectors.count()).isEqualTo(2);
            assertThat(vectors.presentCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a build without an embedder writes none, and the graph still opens")
    void aBuildWithoutAnEmbedderWritesNone(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"))) {
            stage(staging);
            GraphDirectoryBuilder.Published<Long> built = GraphDirectoryBuilder.build(
                staging, target, SupersessionPolicy.NONE, RAM_BUFFER_MB, null, 0,
                EntityVectorRerankTest::openVectors);
            assertThat(built.result().embedded()).isZero();
            assertThat(built.opened()).as("nothing to open, and the graph opened anyway").isZero();
        }

        assertThat(EntityVectors.openIfPresent(target)).isNull();
    }

    @Test
    @DisplayName("the rerank reorders a ranking that term overlap got wrong")
    void theRerankReordersByVectorDistance(@TempDir Path tempDir) throws IOException {
        Path target = build(tempDir, statedVectors());

        try (LexicalIndex documents = LexicalIndex.open(target.resolve("documents"), RAM_BUFFER_MB);
             EntityVectors vectors = EntityVectors.openIfPresent(target)) {
            documents.refresh();

            Ranker lexical = new LuceneRanker(documents);
            Ranker reranked = new FullPrecisionReranker(
                lexical, vectors::vectorOf, CosineSimilarity.getInstance());

            RetrievalQuery query = RetrievalQuery.of("engine", new float[] { 1.0f, 0.0f, 0.0f, 0.0f });

            // Alpha repeats the term and wins on BM25; Beta is the one the query vector points at.
            List<Long> lexicalOrder = ids(lexical.rank(RetrievalQuery.ofText("engine"), 2));
            List<Long> exactOrder = ids(reranked.rank(query, 2));

            assertThat(lexicalOrder).hasSize(2);
            assertThat(exactOrder).hasSize(2);
            assertThat(exactOrder.get(0))
                .as("the entity the query vector points at is first after reranking")
                .isNotEqualTo(lexicalOrder.get(0));
        }
    }

    @Test
    @DisplayName("a query with no embedding falls back rather than failing")
    void aQueryWithoutAnEmbeddingFallsBack(@TempDir Path tempDir) throws IOException {
        Path target = build(tempDir, statedVectors());

        try (LexicalIndex documents = LexicalIndex.open(target.resolve("documents"), RAM_BUFFER_MB);
             EntityVectors vectors = EntityVectors.openIfPresent(target)) {
            documents.refresh();

            Ranker lexical = new LuceneRanker(documents);
            Ranker exact = new FullPrecisionReranker(
                lexical, vectors::vectorOf, CosineSimilarity.getInstance());

            // The reranker is right to refuse: a reranker that silently did nothing would be
            // indistinguishable from one that was working.
            assertThatThrownBy(() -> exact.rank(RetrievalQuery.ofText("engine"), 2))
                .isInstanceOf(IllegalArgumentException.class);

            // The gate is what turns that into a degraded answer instead of a failed request,
            // which is what happens when the embedding endpoint is briefly unavailable.
            Ranker gated = new EmbeddingGatedRanker(exact, lexical);
            assertThat(ids(gated.rank(RetrievalQuery.ofText("engine"), 2))).hasSize(2);
        }
    }

    @Test
    @DisplayName("the gate still reranks when the query does carry an embedding")
    void theGatePassesAnEmbeddedQueryThrough(@TempDir Path tempDir) throws IOException {
        Path target = build(tempDir, statedVectors());

        try (LexicalIndex documents = LexicalIndex.open(target.resolve("documents"), RAM_BUFFER_MB);
             EntityVectors vectors = EntityVectors.openIfPresent(target)) {
            documents.refresh();

            Ranker lexical = new LuceneRanker(documents);
            Ranker exact = new FullPrecisionReranker(
                lexical, vectors::vectorOf, CosineSimilarity.getInstance());
            Ranker gated = new EmbeddingGatedRanker(exact, lexical);

            RetrievalQuery embedded =
                RetrievalQuery.of("engine", new float[] { 1.0f, 0.0f, 0.0f, 0.0f });

            assertThat(ids(gated.rank(embedded, 2)))
                .isEqualTo(ids(exact.rank(embedded, 2)));
            assertThat(gated.name()).isEqualTo(exact.name());
        }
    }

    @Test
    @DisplayName("an embedder that fails leaves the entities without vectors, not the build broken")
    void anEmbedderFailureDoesNotFailTheBuild(@TempDir Path tempDir) throws IOException {
        Path target = build(tempDir, texts -> {
            throw new IllegalStateException("the embedding endpoint is down");
        });

        try (EntityVectors vectors = EntityVectors.openIfPresent(target)) {
            assertThat(vectors).isNotNull();
            assertThat(vectors.count()).isEqualTo(2);
            assertThat(vectors.presentCount())
                .as("no entity has a vector, and every one of them keeps its lexical rank")
                .isZero();
            assertThat(vectors.vectorOf(0)).isNull();
        }
    }

    @Test
    @DisplayName("an embedder that returns the wrong dimension is refused per entity")
    void aWrongDimensionIsRefused(@TempDir Path tempDir) throws IOException {
        Path target = build(tempDir, texts -> {
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                // One usable, one of the wrong shape: storing the second would make every read of
                // it disagree with the query about how many dimensions there are.
                out.add(i == 0 ? new float[DIMENSION] : new float[DIMENSION + 3]);
            }
            return out;
        });

        try (EntityVectors vectors = EntityVectors.openIfPresent(target)) {
            assertThat(vectors.presentCount()).isEqualTo(1);
            assertThat(vectors.vectorOf(0)).isNotNull();
            assertThat(vectors.vectorOf(1)).isNull();
        }
    }

    private static List<Long> ids(RankedList ranked) {
        List<Long> ids = new ArrayList<>();
        ranked.documents().forEach(document -> ids.add(document.documentId()));
        return ids;
    }
}
