package excalibur.api.bootstrap;

import io.excalibur.config.Config;
import io.excalibur.graph.dictionary.LabelDictionaryWriter;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.store.EdgeStoreWriter;
import io.excalibur.graph.temporal.TemporalFilter;
import io.excalibur.retrieval.lexical.LexicalIndex;
import io.excalibur.retrieval.pipeline.GraphRagResult;
import io.excalibur.retrieval.pipeline.LexicalEntityLinker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that a graph on disk becomes a working pipeline.
 *
 * <p>The whole graph-RAG layer was unreachable from the running server for want of this: the
 * server had a {@code withGraphPipeline} setter and nothing ever called it, so a documented
 * endpoint answered 404 on every deployment. These tests build a real graph directory and
 * answer a real question through it, which is the only shape of test that would have noticed.
 */
@DisplayName("GraphRagFactory")
class GraphRagFactoryTest {

    private static final int ADA = 1;
    private static final int BABBAGE = 2;
    private static final int COLLABORATED_WITH = 0;

    private static Config config(Map<String, String> overrides) {
        return new Config(new HashMap<>(overrides));
    }

    /** Writes a complete, minimal graph store into {@code directory}. */
    private static void buildGraph(Path directory) throws IOException {
        EdgeStoreWriter edges = new EdgeStoreWriter();
        edges.add(ADA, COLLABORATED_WITH, BABBAGE, Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.KNOWLEDGE_BASE, 1, 96, 7));
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
    }

    @Test
    @DisplayName("no graph directory means no pipeline, not a failure")
    void absentGraphIsAbsent(@TempDir Path dataDir) throws IOException {
        // A deployment doing only vector search has no graph. It must start.
        assertThat(GraphRagFactory.open(config(Map.of()), dataDir)).isNull();
    }

    @Test
    @DisplayName("a graph on disk answers a question with real names")
    void answersFromTheGraph(@TempDir Path dataDir) throws Exception {
        buildGraph(dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY));

        try (GraphRagFactory.GraphRag graph = GraphRagFactory.open(config(Map.of()), dataDir)) {
            assertThat(graph).isNotNull();

            GraphRagResult result =
                graph.pipeline().answer("Who did Ada Lovelace collaborate with?");

            assertThat(result.hasAnswer()).isTrue();
            // The names, not E1 and R0. The dictionaries exist precisely so a model is not
            // asked to reason about integers.
            assertThat(result.contextText())
                .contains("Charles Babbage")
                .contains("collaboratedWith")
                .doesNotContain("E2")
                .doesNotContain("R0");
        }
    }

    @Test
    @DisplayName("an explicit path is used ahead of the data directory default")
    void explicitPathWins(@TempDir Path dataDir, @TempDir Path elsewhere) throws Exception {
        buildGraph(elsewhere);
        Config config = config(Map.of("excalibur.graph.path", elsewhere.toString()));

        assertThat(GraphRagFactory.directoryFor(config, dataDir)).isEqualTo(elsewhere);
        try (GraphRagFactory.GraphRag graph = GraphRagFactory.open(config, dataDir)) {
            assertThat(graph.directory()).isEqualTo(elsewhere);
            assertThat(graph.pipeline().answer("Who did Ada Lovelace collaborate with?")
                .hasAnswer()).isTrue();
        }
    }

    @Test
    @DisplayName("a half-built graph fails the start rather than answering nothing")
    void incompleteGraphFails(@TempDir Path dataDir) throws IOException {
        // Opening what is present and dropping the rest would give a pipeline with no entity
        // linking: every question resolves to nothing, and every refusal reads as "I don't know"
        // rather than "this node is misconfigured".
        Path directory = dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY);
        buildGraph(directory);
        Files.delete(directory.resolve(GraphRagFactory.ENTITY_DICTIONARY + ".idx"));

        assertThatThrownBy(() -> GraphRagFactory.open(config(Map.of()), dataDir))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(GraphRagFactory.ENTITY_DICTIONARY);
    }

    @Test
    @DisplayName("a failed open leaves nothing mapped behind")
    void failedOpenReleasesWhatItOpened(@TempDir Path dataDir) throws IOException {
        Path directory = dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY);
        buildGraph(directory);
        Files.delete(directory.resolve(GraphRagFactory.RELATION_DICTIONARY + ".idx"));

        assertThatThrownBy(() -> GraphRagFactory.open(config(Map.of()), dataDir))
            .isInstanceOf(IOException.class);

        // The edge store, both Lucene indexes and the entity dictionary were opened before the
        // failure. If any were still held, this rebuild-and-reopen would fail on Windows and
        // leak mappings everywhere else; that it succeeds is the observable form of "released".
        buildGraph(directory);
        try (GraphRagFactory.GraphRag graph = GraphRagFactory.open(config(Map.of()), dataDir)) {
            assertThat(graph).isNotNull();
        }
    }

    @Test
    @DisplayName("closing is idempotent and does not throw")
    void closeIsSafe(@TempDir Path dataDir) throws Exception {
        buildGraph(dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY));

        GraphRagFactory.GraphRag graph = GraphRagFactory.open(config(Map.of()), dataDir);
        graph.close();
        // A second close must not throw: the shutdown sequence runs regardless of what already
        // ran, and a throwing close would strand the steps after it.
        graph.close();
    }

    @Test
    @DisplayName("abstention thresholds come from configuration")
    void thresholdsAreConfigured(@TempDir Path dataDir) throws Exception {
        buildGraph(dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY));

        // A score floor nothing can clear turns every answer into a refusal, which is the
        // observable proof that the configured value reached the policy.
        Config strict = config(Map.of("excalibur.graph.min-score", "1.0"));
        try (GraphRagFactory.GraphRag graph = GraphRagFactory.open(strict, dataDir)) {
            GraphRagResult result =
                graph.pipeline().answer("Who did Ada Lovelace collaborate with?");
            assertThat(result.hasAnswer()).isFalse();
            assertThat(result.declined()).isPresent();
        }
    }
}
