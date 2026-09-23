package com.eonmux.excalibur.retrieval.lexical;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.erasure.GraphErasureCoordinator;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.vector.EntityVectors;
import com.eonmux.excalibur.retrieval.build.GraphDirectoryBuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.eonmux.excalibur.retrieval.lexical.LexicalIndexEraserTest.anyFileContains;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Withdrawing a document has to reach what the graph <em>derived</em> from its prose.
 *
 * <p>An entity's document in the {@code documents} index is text accumulated from every source
 * that mentioned them, and its vector is computed from that document. Neither can be edited to
 * remove one source's contribution: deleting by entity id would take prose nobody asked to
 * erase, and a vector cannot be un-added from. So the compaction reported both as unreached and
 * left the request outstanding, which was honest and left the withdrawn prose being served.
 *
 * <p>The way through is the one the build already takes. The coordinator erases staging before
 * the compaction, so an eraser that rebuilds these artifacts from staging is rebuilding them
 * from a corpus the withdrawn document has already left.
 */
@DisplayName("Derived graph artifact erasure")
class DerivedArtifactEraserTest {

    private static final double RAM_BUFFER_MB = 4.0;
    private static final int DIMENSION = 4;

    /** A term of the withdrawn document's prose and of nothing else. */
    private static final String WITHDRAWN_TERM = "zymurgic";

    /** A term of a document nobody withdrew. */
    private static final String KEPT_TERM = "quazzlethorpe";

    /** The withdrawn document's provenance id. */
    private static final long WITHDRAWN = 42;

    /** Counts what the rebuild asked to be embedded, so the cheap path can be told from it. */
    private static final class CountingEmbedder
            implements GraphDirectoryBuilder.EntityEmbedder {

        private int calls;
        private int texts;

        @Override
        public List<float[]> embedAll(List<String> batch) {
            calls++;
            texts += batch.size();
            List<float[]> vectors = new ArrayList<>(batch.size());
            for (String text : batch) {
                float[] vector = new float[DIMENSION];
                java.util.Arrays.fill(vector, text.length() % 7 + 1f);
                vectors.add(vector);
            }
            return vectors;
        }
    }

    private static Provenance from(long sourceId) {
        return new Provenance(SourceType.DOCUMENT, sourceId, 90, 1);
    }

    /**
     * Opens the edge store a build published, as the node's stack does before the publish lets
     * the previous graph go.
     */
    private static Long opensEdgeStore(Path published) throws IOException {
        try (MappedEdgeStore store = MappedEdgeStore.open(published)) {
            return store.edgeCount();
        }
    }

    /** Stages two documents' facts and prose, and publishes a graph built from them. */
    private static void stageAndBuild(GraphStagingStore staging, Path graph,
                                      GraphDirectoryBuilder.EntityEmbedder embedder)
            throws IOException {
        int alice = staging.entityIdOf("Alice");
        int bob = staging.entityIdOf("Bob");
        staging.append(ExtractedTriple.alwaysValid("Alice", "knows", "Bob", from(WITHDRAWN)));
        staging.append(ExtractedTriple.alwaysValid("Bob", "knows", "Alice", from(77)));
        staging.appendEntityText(alice,
            "Alice was treated for zymurgic fatigue in March", from(WITHDRAWN));
        staging.appendEntityText(bob,
            "Bob works at the quazzlethorpe foundry", from(77));
        staging.flush();

        GraphDirectoryBuilder.build(staging, graph,
            GraphBuilder.singleValued(staging, List.of()), RAM_BUFFER_MB, embedder, DIMENSION,
            DerivedArtifactEraserTest::opensEdgeStore);
    }

    @Test
    @DisplayName("a withdrawn document's prose leaves the served document index")
    void aWithdrawnDocumentLeavesTheDocumentIndex(@TempDir Path root) throws IOException {
        Path graph = root.resolve("graph");
        CountingEmbedder embedder = new CountingEmbedder();

        try (GraphStagingStore staging = GraphStagingStore.open(root.resolve("graph-staging"));
             ErasureLog log = ErasureLog.open(root.resolve("erasure.log"))) {
            stageAndBuild(staging, graph, embedder);

            assertThat(anyFileContains(graph, WITHDRAWN_TERM))
                .as("the withdrawn prose starts out in the served index")
                .isTrue();

            GraphErasureCoordinator coordinator = new GraphErasureCoordinator(
                log, (stats, now) -> true, Clock.systemUTC(), staging,
                List.of(new DerivedArtifactEraser(staging, RAM_BUFFER_MB, embedder, DIMENSION)));
            coordinator.submit(new ErasureScope.Source(SourceType.DOCUMENT, WITHDRAWN));

            GraphErasureCoordinator.CompactionOutcome outcome = coordinator.compactNow(graph);

            assertThat(outcome.requestsWithheld())
                .as("nothing in the directory is beyond this request any more")
                .isZero();
            assertThat(outcome.requestsSatisfied()).isEqualTo(1);
            assertThat(log.outstandingCount()).isZero();
        }

        assertThat(anyFileContains(graph, WITHDRAWN_TERM))
            .as("the prose of the withdrawn document has to stop existing")
            .isFalse();
        assertThat(anyFileContains(graph, KEPT_TERM))
            .as("and the prose of the document nobody withdrew must not")
            .isTrue();
    }

    @Test
    @DisplayName("an entity erasure is not paid for with a full re-embedding")
    void anEntityScopeTakesTheCheapPath(@TempDir Path root) throws IOException {
        // A rebuild is one embedding call per batch of entities over the whole graph. That is
        // the price of reaching a withdrawn document and it must not be the price of every
        // erasure: deleting one entity's document and clearing one vector slot reaches an
        // entity scope exactly, and is what the built-in erasers already do.
        Path graph = root.resolve("graph");
        CountingEmbedder embedder = new CountingEmbedder();

        try (GraphStagingStore staging = GraphStagingStore.open(root.resolve("graph-staging"));
             ErasureLog log = ErasureLog.open(root.resolve("erasure.log"))) {
            stageAndBuild(staging, graph, embedder);
            int embeddedByTheBuild = embedder.calls;

            GraphErasureCoordinator coordinator = new GraphErasureCoordinator(
                log, (stats, now) -> true, Clock.systemUTC(), staging,
                List.of(new DerivedArtifactEraser(staging, RAM_BUFFER_MB, embedder, DIMENSION)));
            coordinator.submit(new ErasureScope.Entity(staging.entityIdOf("Alice")));

            GraphErasureCoordinator.CompactionOutcome outcome = coordinator.compactNow(graph);

            assertThat(outcome.requestsSatisfied()).isEqualTo(1);
            assertThat(embedder.calls)
                .as("an entity scope needs no model call at all")
                .isEqualTo(embeddedByTheBuild);
        }

        assertThat(anyFileContains(graph, WITHDRAWN_TERM))
            .as("the erased entity's prose still goes")
            .isFalse();
        try (EntityVectors vectors = EntityVectors.openIfPresent(graph)) {
            assertThat(vectors).isNotNull();
            assertThat(vectors.vectorOf(0))
                .as("and so does the vector computed from it")
                .isNull();
        }
    }

    @Test
    @DisplayName("without an embedder the vectors are reported rather than silently kept")
    void withoutAnEmbedderTheVectorsAreReported(@TempDir Path root) throws IOException {
        // A node with no embedding endpoint can rebuild the text but not the vectors. Claiming
        // the vector file anyway would publish a graph whose vectors were dropped; not claiming
        // it leaves the built-in eraser, which answers an entity scope and says so for the rest.
        Path graph = root.resolve("graph");

        try (GraphStagingStore staging = GraphStagingStore.open(root.resolve("graph-staging"));
             ErasureLog log = ErasureLog.open(root.resolve("erasure.log"))) {
            stageAndBuild(staging, graph, new CountingEmbedder());

            GraphErasureCoordinator coordinator = new GraphErasureCoordinator(
                log, (stats, now) -> true, Clock.systemUTC(), staging,
                List.of(new DerivedArtifactEraser(staging, RAM_BUFFER_MB)));
            coordinator.submit(new ErasureScope.Source(SourceType.DOCUMENT, WITHDRAWN));

            GraphErasureCoordinator.CompactionOutcome outcome = coordinator.compactNow(graph);

            assertThat(outcome.requestsWithheld()).isEqualTo(1);
            assertThat(outcome.reason()).contains(EntityVectors.FILE_NAME);
            assertThat(log.outstandingCount())
                .as("the obligation stays owed while a derivation of the prose is on disk")
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the rebuild publishes no entity the served store cannot address")
    void theRebuildIsBoundedByTheStore(@TempDir Path root) throws IOException {
        // Staging runs ahead of the store: facts are ingested continuously and a build is a
        // step somebody takes. A rebuild that published every entity staging knows would put
        // ids past the store's entity count into the index entity linking searches, and the
        // store refuses one - so a question that happened to match the new name would fail the
        // request rather than answer it. The compaction preserves the id space; so must this.
        Path graph = root.resolve("graph");
        CountingEmbedder embedder = new CountingEmbedder();

        try (GraphStagingStore staging = GraphStagingStore.open(root.resolve("graph-staging"));
             ErasureLog log = ErasureLog.open(root.resolve("erasure.log"))) {
            stageAndBuild(staging, graph, embedder);
            // Ingested after the build, so the served store has no id for it.
            int carol = staging.entityIdOf("Carol Ultrasonics");
            staging.appendEntityText(carol, "Carol Ultrasonics joined in April", from(88));
            staging.flush();

            GraphErasureCoordinator coordinator = new GraphErasureCoordinator(
                log, (stats, now) -> true, Clock.systemUTC(), staging,
                List.of(new DerivedArtifactEraser(staging, RAM_BUFFER_MB, embedder, DIMENSION)));
            coordinator.submit(new ErasureScope.Source(SourceType.DOCUMENT, WITHDRAWN));
            assertThat(coordinator.compactNow(graph).requestsSatisfied()).isEqualTo(1);
        }

        try (LexicalIndex labels = LexicalIndex.open(graph.resolve("labels"), RAM_BUFFER_MB)) {
            assertThat(labels.documentCount())
                .as("the two entities the store addresses, and not the third")
                .isEqualTo(2);
        }
        assertThat(anyFileContains(graph, "ultrasonics"))
            .as("an entity the served store cannot reach must not be served as findable")
            .isFalse();
    }

    @Test
    @DisplayName("an extractor retraction reaches the prose that version's facts came with")
    void anExtractorScopeIsReached(@TempDir Path root) throws IOException {
        Path graph = root.resolve("graph");
        CountingEmbedder embedder = new CountingEmbedder();

        try (GraphStagingStore staging = GraphStagingStore.open(root.resolve("graph-staging"));
             ErasureLog log = ErasureLog.open(root.resolve("erasure.log"))) {
            int alice = staging.entityIdOf("Alice");
            staging.append(ExtractedTriple.alwaysValid("Alice", "knows", "Bob",
                new Provenance(SourceType.DOCUMENT, WITHDRAWN, 90, 3)));
            staging.appendEntityText(alice, "Alice was treated for zymurgic fatigue",
                new Provenance(SourceType.DOCUMENT, WITHDRAWN, 90, 3));
            staging.flush();
            GraphDirectoryBuilder.build(staging, graph,
                GraphBuilder.singleValued(staging, List.of()), RAM_BUFFER_MB, embedder,
                DIMENSION, DerivedArtifactEraserTest::opensEdgeStore);

            GraphErasureCoordinator coordinator = new GraphErasureCoordinator(
                log, (stats, now) -> true, Clock.systemUTC(), staging,
                List.of(new DerivedArtifactEraser(staging, RAM_BUFFER_MB, embedder, DIMENSION)));
            coordinator.submit(new ErasureScope.ExtractorVersion(3));

            assertThat(coordinator.compactNow(graph).requestsSatisfied()).isEqualTo(1);
        }

        assertThat(anyFileContains(graph, WITHDRAWN_TERM)).isFalse();
    }
}
