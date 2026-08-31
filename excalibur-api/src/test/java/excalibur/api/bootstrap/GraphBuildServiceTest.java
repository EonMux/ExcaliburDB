package excalibur.api.bootstrap;

import excalibur.ingest.graph.RelationExtractor;
import excalibur.ingest.graph.StagingGraphIngestSink;
import io.excalibur.config.Config;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.staging.ExtractedTriple;
import io.excalibur.graph.staging.GraphStagingStore;

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

    @Test
    @DisplayName("a node with nothing staged serves no graph and says so")
    void anEmptyNodeServesNoGraph(@TempDir Path tempDir) throws IOException {
        Path graphDirectory = tempDir.resolve("graph");
        Config config = configFor(graphDirectory);

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"));
             GraphRagHolder holder = new GraphRagHolder(graphDirectory, null)) {

            GraphBuildService service = new GraphBuildService(staging, holder, config, List.of());

            assertThat(holder.isPresent()).isFalse();
            assertThat(holder.pipeline()).isEmpty();

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
            GraphBuildService service = new GraphBuildService(staging, holder, config, List.of());

            // Exactly what IngestionOrchestrator does once a document is durably published.
            sink.onDocumentPublished("docs:ada",
                "Ada Lovelace worked with Charles Babbage on the Analytical Engine.");

            assertThat(sink.factsStaged()).isEqualTo(2);
            assertThat(service.stagedCount()).isEqualTo(2);

            GraphBuildService.BuildOutcome outcome = service.build();

            assertThat(outcome.published()).isTrue();
            assertThat(outcome.edges()).isEqualTo(2);
            assertThat(outcome.entities()).isEqualTo(3);

            // The node is now answering from a graph it built itself, with no restart.
            assertThat(holder.isPresent()).isTrue();
            assertThat(holder.pipeline()).isPresent();
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
            GraphBuildService service = new GraphBuildService(staging, holder, config, List.of());

            sink.onDocumentPublished("docs:ada", "Ada Lovelace and Charles Babbage.");
            service.build();
            Object first = holder.pipeline().orElseThrow();

            staging.append(ExtractedTriple.alwaysValid(
                "Ada Lovelace", "wrote", "Note G", from(2)));
            GraphBuildService.BuildOutcome second = service.build();

            assertThat(second.edges()).isEqualTo(3);
            // A new stack, not the old one still mapping a directory the rebuild replaced.
            assertThat(holder.pipeline().orElseThrow()).isNotSameAs(first);
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
            sink.onDocumentPublished("docs:ada", "Ada Lovelace and Charles Babbage.");

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
                .onDocumentPublished("docs:ada", "Ada Lovelace and Charles Babbage.");
            new GraphBuildService(staging, holder, config, List.of()).build();
        }

        // Staging lives beside the graph, not inside it: every build replaces the graph directory
        // wholesale, and the record the next build reads has to survive that.
        try (GraphStagingStore reopened = GraphStagingStore.open(stagingDirectory)) {
            assertThat(reopened.stagedCount()).isEqualTo(2);
        }
    }
}
