package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.ingest.graph.RelationExtractor;
import com.eonmux.excalibur.ingest.graph.StagingGraphIngestSink;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node accumulating facts and then serving them.
 *
 * <p>The whole graph half of the system was previously read-only: {@code GraphRagFactory} opened an
 * edge store, two Lucene indexes and two dictionaries that nothing in the repository produced, and
 * {@code /v1/graph/query} could only answer from artifacts built somewhere else. These tests are
 * the path that closes it - documents published by ingestion become staged facts, a build turns
 * those into the store, and the node serves the result without being restarted.</p>
 */
@DisplayName("Graph build service")
class GraphBuildServiceTest {

    /** The pipeline being served, for identity checks; the reading is released at once. */
    private static java.util.Optional<com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline>
            served(GraphRagHolder holder) {
        java.util.Optional<GraphRagHolder.Reading> reading = holder.acquire();
        reading.ifPresent(GraphRagHolder.Reading::close);
        return reading.map(GraphRagHolder.Reading::pipeline);
    }

    private static Config configFor(Path graphDirectory) {
        return new Config(new java.util.HashMap<>(Map.of(
            "excalibur.graph.path", graphDirectory.toString(),
            "excalibur.graph.ram-buffer-mb", "4.0")));
    }

    private static Provenance from(long document) {
        return Provenance.of(SourceType.DOCUMENT, document, 0.9, 1);
    }

    /** An extractor that returns fixed facts, standing in for the chat model. */
    private static RelationExtractor fixedFacts() {
        return (documentKey, text) -> List.of(
            ExtractedTriple.alwaysValid("Ada Lovelace", "collaborated_with", "Charles Babbage",
                from(1)),
            ExtractedTriple.alwaysValid("Charles Babbage", "designed", "Analytical Engine",
                from(1)));
    }

    /**
     * Fixed facts attributed to the document they were extracted from, as the chat-model
     * extractor attributes them, so withdrawing the document takes them.
     */
    private static RelationExtractor factsOfTheDocument() {
        return (documentKey, text) -> {
            long source = LlmRelationExtractor.sourceIdOf(documentKey);
            return List.of(
                ExtractedTriple.alwaysValid("Ada Lovelace", "collaborated_with",
                    "Charles Babbage", from(source)),
                ExtractedTriple.alwaysValid("Charles Babbage", "designed", "Analytical Engine",
                    from(source)));
        };
    }

    /** The node's model clients, as the tenants that own a build service would hold them. */
    private static GraphModelClients models(Config config) {
        return GraphModelClients.forConfig(config);
    }

    @Test
    @DisplayName("a relation configured single-valued closes its predecessor in the served graph")
    void singleValuedRelationsSupersede(@TempDir Path tempDir) throws IOException {
        Path graphDirectory = tempDir.resolve("graph");
        Config config = configFor(graphDirectory);

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
             GraphRagHolder holder = new GraphRagHolder(graphDirectory, null)) {
            staging.append(new ExtractedTriple("Ada Lovelace", "employer", "Acme",
                0, com.eonmux.excalibur.graph.temporal.TemporalFilter.OPEN, from(1)));
            staging.append(new ExtractedTriple("Ada Lovelace", "employer", "Beta",
                100, com.eonmux.excalibur.graph.temporal.TemporalFilter.OPEN, from(2)));
            int ada = staging.entityIdOf("Ada Lovelace");
            int acme = staging.entityIdOf("Acme");
            int beta = staging.entityIdOf("Beta");

            new GraphBuildService(staging, holder, config, models(config), List.of("employer"))
                .build();

            assertThat(holder.isPresent()).isTrue();
            try (com.eonmux.excalibur.graph.store.MappedEdgeStore store =
                     com.eonmux.excalibur.graph.store.MappedEdgeStore.open(graphDirectory)) {
                long[] now = store.expand(new long[] { ada }, 1, null,
                    com.eonmux.excalibur.graph.temporal.TemporalFilter.asOf(
                        java.time.LocalDate.ofEpochDay(200)));
                assertThat(now)
                    .as("the service passes its single-valued relations to the build")
                    .contains(beta)
                    .doesNotContain(acme);
            }
        }
    }

    @Test
    @DisplayName("a node with nothing staged serves no graph and says so")
    void anEmptyNodeServesNoGraph(@TempDir Path tempDir) throws IOException {
        Path graphDirectory = tempDir.resolve("graph");
        Config config = configFor(graphDirectory);

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
             GraphRagHolder holder = new GraphRagHolder(graphDirectory, null)) {

            GraphBuildService service =
                new GraphBuildService(staging, holder, config, models(config), List.of());

            assertThat(holder.isPresent()).isFalse();
            assertThat(served(holder)).isEmpty();

            GraphBuildService.BuildOutcome outcome = service.build();

            // Nothing staged, so nothing published - and crucially the holder is still empty
            // rather than serving an empty graph that refuses every question.
            assertThat(outcome.published()).isFalse();
            assertThat(holder.isPresent()).isFalse();
        }
    }

    @Test
    @DisplayName("published documents become a served graph")
    void ingestedDocumentsBecomeAServedGraph(@TempDir Path tempDir) throws IOException {
        Path graphDirectory = tempDir.resolve("graph");
        Config config = configFor(graphDirectory);

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
             GraphRagHolder holder = new GraphRagHolder(graphDirectory, null)) {

            StagingGraphIngestSink sink = new StagingGraphIngestSink(staging, fixedFacts());
            GraphBuildService service =
                new GraphBuildService(staging, holder, config, models(config), List.of());

            // Exactly what IngestionOrchestrator does once a document is durably published.
            sink.onDocumentPublished("docs", "docs:ada",
                "Ada Lovelace worked with Charles Babbage on the Analytical Engine.");

            assertThat(sink.factsStaged()).isEqualTo(2);
            assertThat(service.stagedCount()).isEqualTo(2);

            GraphBuildService.BuildOutcome outcome = service.build();

            assertThat(outcome.published()).isTrue();
            assertThat(outcome.edges()).isEqualTo(2);
            assertThat(outcome.entities()).isEqualTo(3);

            // The node is now answering from a graph it built itself, with no restart.
            assertThat(holder.isPresent()).isTrue();
            assertThat(served(holder)).isPresent();
        }
    }

    @Test
    @DisplayName("a second build replaces what is being served")
    void aSecondBuildReplacesTheServedGraph(@TempDir Path tempDir) throws IOException {
        Path graphDirectory = tempDir.resolve("graph");
        Config config = configFor(graphDirectory);

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
             GraphRagHolder holder = new GraphRagHolder(graphDirectory, null)) {

            StagingGraphIngestSink sink = new StagingGraphIngestSink(staging, fixedFacts());
            GraphBuildService service =
                new GraphBuildService(staging, holder, config, models(config), List.of());

            sink.onDocumentPublished("docs", "docs:ada", "Ada Lovelace and Charles Babbage.");
            service.build();
            Object first = served(holder).orElseThrow();

            staging.append(ExtractedTriple.alwaysValid(
                "Ada Lovelace", "wrote", "Note G", from(2)));
            GraphBuildService.BuildOutcome second = service.build();

            assertThat(second.edges()).isEqualTo(3);
            // A new stack, not the old one still mapping a directory the rebuild replaced.
            assertThat(served(holder).orElseThrow()).isNotSameAs(first);
        }
    }

    @Test
    @DisplayName("a build that saw every staged change leaves nothing unpublished")
    void aBuildClearsTheUnpublishedRecord(@TempDir Path tempDir) throws IOException {
        Path graphDirectory = tempDir.resolve("graph");
        Config config = configFor(graphDirectory);

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
             GraphRagHolder holder = new GraphRagHolder(graphDirectory, null)) {
            new StagingGraphIngestSink(staging, fixedFacts())
                .onDocumentPublished("docs", "docs:ada", "Ada Lovelace and Charles Babbage.");
            assertThat(staging.unpublishedChanges().pending()).isTrue();

            GraphBuildService.BuildOutcome outcome =
                new GraphBuildService(staging, holder, config, models(config), List.of()).build();

            assertThat(outcome.published()).isTrue();
            assertThat(outcome.retired()).isFalse();
            assertThat(staging.unpublishedChanges().pending()).isFalse();
        }
    }

    @Test
    @DisplayName("a build of nothing staged clears the record without touching the graph")
    void anEmptyBuildClearsTheRecord(@TempDir Path tempDir) throws IOException {
        Path graphDirectory = tempDir.resolve("graph");
        Config config = configFor(graphDirectory);

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
             GraphRagHolder holder = new GraphRagHolder(graphDirectory, null)) {
            staging.markUnpublished();

            GraphBuildService.BuildOutcome outcome =
                new GraphBuildService(staging, holder, config, models(config), List.of()).build();

            assertThat(outcome.published()).isFalse();
            assertThat(outcome.retired()).as("there was no graph to retire").isFalse();
            assertThat(staging.unpublishedChanges().pending()).isFalse();
        }
    }

    @Test
    @DisplayName("once every fact is withdrawn, a build stops serving the graph they built")
    void everythingWithdrawnRetiresTheGraph(@TempDir Path tempDir) throws IOException {
        Path graphDirectory = tempDir.resolve("graph");
        Config config = configFor(graphDirectory);

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
             GraphRagHolder holder = new GraphRagHolder(graphDirectory, null)) {
            StagingGraphIngestSink sink =
                new StagingGraphIngestSink(staging, factsOfTheDocument());
            GraphBuildService service =
                new GraphBuildService(staging, holder, config, models(config), List.of());
            sink.onDocumentPublished("docs", "docs:ada", "Ada Lovelace and Charles Babbage.");
            assertThat(service.build().published()).isTrue();

            sink.onDocumentRemoved("docs", "docs:ada");
            assertThat(staging.stagedCount()).isZero();
            assertThat(staging.unpublishedChanges().pending()).isTrue();

            GraphBuildService.BuildOutcome outcome = service.build();

            assertThat(outcome.published()).isFalse();
            assertThat(outcome.retired()).isTrue();
            assertThat(holder.isPresent()).as("the removed document's facts are not served")
                .isFalse();
            assertThat(graphDirectory).doesNotExist();
            assertThat(staging.unpublishedChanges().pending()).isFalse();
        }
    }

    @Test
    @DisplayName("a graph nothing staged ever changed is kept by a build of nothing")
    void anUnchangedGraphIsKept(@TempDir Path tempDir) throws IOException {
        Path graphDirectory = tempDir.resolve("graph");
        MinimalGraph.write(graphDirectory, false);
        Config config = configFor(graphDirectory);

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
             GraphRagHolder holder = new GraphRagHolder(graphDirectory,
                 GraphRagFactory.openAt(config, graphDirectory))) {
            GraphBuildService.BuildOutcome outcome =
                new GraphBuildService(staging, holder, config, models(config), List.of()).build();

            assertThat(outcome.retired()).isFalse();
            assertThat(holder.isPresent()).isTrue();
            assertThat(graphDirectory).isDirectory();
        }
    }

    @Test
    @DisplayName("a build that publishes an unopenable graph leaves the served one on disk")
    void anUnopenableBuildKeepsThePreviousGraph(@TempDir Path tempDir) throws IOException {
        Path graphDirectory = tempDir.resolve("graph");
        Config config = configFor(graphDirectory);

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
             GraphRagHolder holder = new GraphRagHolder(graphDirectory, null)) {
            new StagingGraphIngestSink(staging, fixedFacts())
                .onDocumentPublished("docs", "docs:ada", "Ada Lovelace and Charles Babbage.");
            assertThat(new GraphBuildService(staging, holder, config, models(config), List.of())
                .build().edges()).isEqualTo(2);
            Object served = served(holder).orElseThrow();

            // The embedder runs after the lexical indexes are written into the scratch
            // directory and before the publish, which makes it the one place a test can remove
            // an artifact the opener requires: a build that assembles something the reader
            // cannot open, as a version mismatch or a missing file would.
            GraphBuildService corrupting = new GraphBuildService(
                staging, holder, config, models(config), List.of(),
                texts -> {
                    deleteTree(com.eonmux.excalibur.graph.staging.GraphBuilder
                        .scratchFor(graphDirectory).resolve(GraphRagFactory.LABEL_INDEX));
                    return texts.stream().map(text -> new float[] { 1f, 0f }).toList();
                }, 2);
            staging.append(ExtractedTriple.alwaysValid(
                "Ada Lovelace", "wrote", "Note G", from(2)));

            org.assertj.core.api.Assertions.assertThatThrownBy(corrupting::build)
                .isInstanceOf(IOException.class);

            assertThat(served(holder).orElseThrow())
                .as("the node goes on serving the graph it served before")
                .isSameAs(served);
            assertThat(com.eonmux.excalibur.graph.staging.GraphBuilder.previousFor(graphDirectory))
                .doesNotExist();
        }

        // What a restart would open: the previous graph, whole, at the served path.
        try (GraphRagFactory.GraphRag reopened = GraphRagFactory.openAt(config, graphDirectory);
             com.eonmux.excalibur.graph.store.MappedEdgeStore store =
                 com.eonmux.excalibur.graph.store.MappedEdgeStore.open(graphDirectory)) {
            assertThat(reopened).isNotNull();
            assertThat(store.edgeCount()).isEqualTo(2);
        }
    }

    /** Removes a directory tree, for a test that damages a build in progress. */
    private static void deleteTree(Path root) {
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(root)) {
            for (Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.delete(each);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("an extractor that fails does not fail the document")
    void anExtractorFailureIsContained(@TempDir Path tempDir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"))) {
            StagingGraphIngestSink sink = new StagingGraphIngestSink(staging,
                (documentKey, text) -> {
                    throw new RelationExtractor.ExtractionException("the model is down");
                });

            // The document is already published and searchable by the time the sink runs;
            // throwing here would report a loss that did not happen.
            sink.onDocumentPublished("docs", "docs:ada", "Ada Lovelace and Charles Babbage.");

            assertThat(sink.extractionFailures()).isEqualTo(1);
            assertThat(sink.factsStaged()).isZero();
            assertThat(staging.stagedCount()).isZero();
        }
    }

    @Test
    @DisplayName("staged facts outlive the graph directory a build replaces")
    void stagingOutlivesTheGraphDirectory(@TempDir Path tempDir) throws IOException {
        Path graphDirectory = tempDir.resolve("graph");
        Path stagingDirectory = tempDir.resolve("staging");
        Config config = configFor(graphDirectory);

        try (GraphStagingStore staging = GraphStagingStore.open(stagingDirectory);
             GraphRagHolder holder = new GraphRagHolder(graphDirectory, null)) {
            new StagingGraphIngestSink(staging, fixedFacts())
                .onDocumentPublished("docs", "docs:ada", "Ada Lovelace and Charles Babbage.");
            new GraphBuildService(staging, holder, config, models(config), List.of()).build();
        }

        // Staging lives beside the graph, not inside it: every build replaces the graph directory
        // wholesale, and the record the next build reads has to survive that.
        try (GraphStagingStore reopened = GraphStagingStore.open(stagingDirectory)) {
            assertThat(reopened.stagedCount()).isEqualTo(2);
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Serving while the graph is replaced")
    class ServingWhileReplaced {

        private static final String QUESTION = "Who did Ada Lovelace work with?";

        /** A holder serving a graph built from the fixed facts. */
        private GraphBuildService built(Path tempDir, GraphStagingStore staging,
                                        GraphRagHolder holder) throws IOException {
            GraphBuildService service = new GraphBuildService(
                staging, holder, configFor(holder.directory()),
                models(configFor(holder.directory())), List.of());
            new StagingGraphIngestSink(staging, fixedFacts())
                .onDocumentPublished("docs", "docs:ada", "Ada Lovelace and Charles Babbage.");
            assertThat(service.build().published()).isTrue();
            return service;
        }

        @Test
        @DisplayName("a query holding the graph finishes against it after a rebuild")
        void aReaderOutlivesARebuild(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
                 GraphRagHolder holder = new GraphRagHolder(tempDir.resolve("graph"), null)) {
                GraphBuildService service = built(tempDir, staging, holder);

                try (GraphRagHolder.Reading reading = holder.acquire().orElseThrow()) {
                    staging.append(ExtractedTriple.alwaysValid(
                        "Ada Lovelace", "wrote", "Note G", from(2)));
                    assertThat(service.build().published()).isTrue();

                    // The replaced stack is still open for the reader that took it: before, the
                    // build closed it here and this query failed on a closed Lucene reader.
                    assertThat(reading.pipeline().answer(QUESTION).hasAnswer()).isTrue();
                    assertThat(served(holder).orElseThrow())
                        .as("new queries are answered by the rebuilt graph")
                        .isNotSameAs(reading.pipeline());
                }
            }
        }

        @Test
        @DisplayName("a build never leaves a moment in which no graph is served")
        void aBuildHasNoGap(@TempDir Path tempDir) throws Exception {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
                 GraphRagHolder holder = new GraphRagHolder(tempDir.resolve("graph"), null)) {
                GraphBuildService service = built(tempDir, staging, holder);

                java.util.concurrent.atomic.AtomicBoolean building =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
                java.util.concurrent.atomic.AtomicInteger absent =
                    new java.util.concurrent.atomic.AtomicInteger();
                Thread watcher = Thread.ofPlatform().start(() -> {
                    while (building.get()) {
                        if (!holder.isPresent()) {
                            absent.incrementAndGet();
                        }
                    }
                });
                try {
                    for (int i = 0; i < 3; i++) {
                        staging.append(ExtractedTriple.alwaysValid(
                            "Ada Lovelace", "wrote", "Note " + i, from(10 + i)));
                        service.build();
                    }
                } finally {
                    building.set(false);
                    watcher.join();
                }

                assertThat(absent.get()).isZero();
            }
        }

        @Test
        @DisplayName("a withdrawal waits for the queries reading the graph")
        void withdrawalWaitsForReaders(@TempDir Path tempDir) throws Exception {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
                 GraphRagHolder holder = new GraphRagHolder(tempDir.resolve("graph"), null)) {
                built(tempDir, staging, holder);
                GraphRagHolder.Reading reading = holder.acquire().orElseThrow();

                java.util.concurrent.CompletableFuture<Boolean> withdrawn =
                    java.util.concurrent.CompletableFuture.supplyAsync(holder::withdraw);

                // Withdrawn at once for new queries, and held open for this one.
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (holder.isPresent() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertThat(holder.isPresent()).isFalse();
                assertThat(holder.acquire()).isEmpty();
                assertThat(reading.pipeline().answer(QUESTION).hasAnswer()).isTrue();
                assertThat(withdrawn).isNotDone();

                reading.close();
                assertThat(withdrawn.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        }

        @Test
        @DisplayName("a build waits for a compaction that holds the directory")
        void buildsAndCompactionsDoNotOverlap(@TempDir Path tempDir) throws Exception {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
                 GraphRagHolder holder = new GraphRagHolder(tempDir.resolve("graph"), null)) {
                GraphBuildService service = built(tempDir, staging, holder);

                // What GraphErasureService.compact holds for the length of a compaction.
                holder.replacementLock().lock();
                java.util.concurrent.CompletableFuture<GraphBuildService.BuildOutcome> build;
                try {
                    build = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return service.build();
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
                    Thread.sleep(200);
                    assertThat(build).as("the build is blocked behind the compaction").isNotDone();
                } finally {
                    holder.replacementLock().unlock();
                }
                assertThat(build.get(30, java.util.concurrent.TimeUnit.SECONDS).published())
                    .isTrue();
            }
        }
    }
}
