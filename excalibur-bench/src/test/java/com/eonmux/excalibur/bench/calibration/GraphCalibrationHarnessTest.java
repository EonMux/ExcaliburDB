package com.eonmux.excalibur.bench.calibration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.eonmux.excalibur.api.bootstrap.GraphRagFactory;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.dictionary.LabelDictionaryWriter;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.pipeline.LexicalEntityLinker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That a real graph on disk can be calibrated, end to end.
 *
 * <p>Built against a real store rather than a stubbed pipeline on purpose. The whole claim of this
 * harness is that it fits the retrieval a served query actually gets, and a test that supplied its
 * own evidence would be testing the arithmetic while leaving that claim unchecked.
 */
@DisplayName("Graph-route calibration")
class GraphCalibrationHarnessTest {

    private static final int ADA = 1;
    private static final int BABBAGE = 2;
    private static final int TURING = 3;
    private static final int NEWMAN = 4;
    private static final int HOPPER = 5;
    private static final int AIKEN = 6;
    private static final int COLLABORATED_WITH = 0;

    private static Config config() {
        return new Config(new HashMap<>());
    }

    /** A small graph of three collaborations, with the curated provenance a fit can check. */
    private static void buildGraph(Path directory) throws IOException {
        EdgeStoreWriter edges = new EdgeStoreWriter();
        edges.add(ADA, COLLABORATED_WITH, BABBAGE, Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.KNOWLEDGE_BASE, 1, 96, 7));
        edges.add(TURING, COLLABORATED_WITH, NEWMAN, Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.KNOWLEDGE_BASE, 1, 88, 7));
        edges.add(HOPPER, COLLABORATED_WITH, AIKEN, Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.DOCUMENT, 2, 41, 7));
        edges.build(directory, 8);

        Map<Integer, String> names = Map.of(
            ADA, "Ada Lovelace", BABBAGE, "Charles Babbage",
            TURING, "Alan Turing", NEWMAN, "Max Newman",
            HOPPER, "Grace Hopper", AIKEN, "Howard Aiken");

        try (LexicalIndex documents = LexicalIndex.open(
                directory.resolve(GraphRagFactory.DOCUMENT_INDEX), 16.0)) {
            for (Map.Entry<Integer, String> entry : names.entrySet()) {
                documents.put(entry.getKey(), entry.getValue() + " computing pioneer");
            }
            documents.refresh();
        }
        try (LexicalIndex labels = LexicalIndex.open(
                directory.resolve(GraphRagFactory.LABEL_INDEX), 16.0)) {
            for (Map.Entry<Integer, String> entry : names.entrySet()) {
                LexicalEntityLinker.index(labels, entry.getKey(), entry.getValue());
            }
            labels.refresh();
        }

        LabelDictionaryWriter entities = new LabelDictionaryWriter();
        for (Map.Entry<Integer, String> entry : names.entrySet()) {
            entities.put(entry.getKey(), entry.getValue());
        }
        entities.write(directory, GraphRagFactory.ENTITY_DICTIONARY);
        new LabelDictionaryWriter()
            .put(COLLABORATED_WITH, "collaboratedWith")
            .write(directory, GraphRagFactory.RELATION_DICTIONARY);
    }

    private static List<JudgedQuestion> judged() {
        return List.of(
            new JudgedQuestion("Who did Ada Lovelace collaborate with?",
                List.of("Charles Babbage")),
            new JudgedQuestion("Who did Alan Turing collaborate with?", List.of("Max Newman")),
            new JudgedQuestion("Who did Grace Hopper collaborate with?", List.of("Howard Aiken")),
            // No edge reaches Ada from Howard Aiken, so the evidence for this one is wrong.
            new JudgedQuestion("Who did Howard Aiken collaborate with?", List.of("Ada Lovelace")));
    }

    @Test
    @DisplayName("fits both axes against a real graph and reports what it measured")
    void fitsARealGraph(@TempDir Path dataDir) throws Exception {
        Path graphDir = dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY);
        buildGraph(graphDir);

        try (GraphRagFactory.GraphRag graph = GraphRagFactory.openAt(config(), graphDir)) {
            CalibrationReport report =
                new GraphCalibrationHarness(graph.pipeline(), graph.linker())
                    .run(judged(), 0.75);

            assertThat(report.judged()).isEqualTo(4);
            assertThat(report.fitted())
                .as("every judged question here names an entity the graph holds")
                .isEqualTo(4);
            assertThat(report.excluded()).isZero();

            assertThat(report.thresholds().sampleSize()).isEqualTo(4);
            assertThat(report.thresholds().answered()
                + report.thresholds().ambiguous()
                + report.thresholds().refused()).isEqualTo(4);
            assertThat(report.thresholds().precision()).isGreaterThanOrEqualTo(0.75);
        }
    }

    @Test
    @DisplayName("the correctness rule reads the subgraph, not the best-matching candidate")
    void correctnessReadsTheSubgraph(@TempDir Path dataDir) throws Exception {
        Path graphDir = dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY);
        buildGraph(graphDir);

        try (GraphRagFactory.GraphRag graph = GraphRagFactory.openAt(config(), graphDir)) {
            // Every question is judged against the collaborator the traversal reaches. The
            // top-ranked candidate is the entity the question is *about*, so a rule that scored
            // that would call all three of these wrong.
            List<JudgedQuestion> allRight = List.of(
                new JudgedQuestion("Who did Ada Lovelace collaborate with?",
                    List.of("Charles Babbage")),
                new JudgedQuestion("Who did Alan Turing collaborate with?", List.of("Max Newman")),
                new JudgedQuestion("Who did Grace Hopper collaborate with?",
                    List.of("Howard Aiken")));

            CalibrationReport report =
                new GraphCalibrationHarness(graph.pipeline(), graph.linker()).run(allRight, 1.0);

            assertThat(report.thresholds().precision())
                .as("the evidence for all three is present, so precision is perfect")
                .isEqualTo(1.0);
            assertThat(report.thresholds().metTarget()).isTrue();
        }
    }

    @Test
    @DisplayName("a judged answer this graph does not hold is excluded, not scored as wrong")
    void unjudgeableQuestionsAreExcluded(@TempDir Path dataDir) throws Exception {
        Path graphDir = dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY);
        buildGraph(graphDir);

        try (GraphRagFactory.GraphRag graph = GraphRagFactory.openAt(config(), graphDir)) {
            List<JudgedQuestion> mixed = List.of(
                new JudgedQuestion("Who did Ada Lovelace collaborate with?",
                    List.of("Charles Babbage")),
                new JudgedQuestion("Who did Ada Lovelace collaborate with?",
                    List.of("Zxqv Nonexistent")));

            CalibrationReport report =
                new GraphCalibrationHarness(graph.pipeline(), graph.linker()).run(mixed, 1.0);

            assertThat(report.unjudgeable())
                .as("no threshold closes a gap in the corpus; that is the build's to fix")
                .isEqualTo(1);
            assertThat(report.fitted()).isEqualTo(1);
            assertThat(report.judged()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("overall coverage counts the questions that never reached abstention")
    void overallCoverageCountsEveryQuestion(@TempDir Path dataDir) throws Exception {
        Path graphDir = dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY);
        buildGraph(graphDir);

        try (GraphRagFactory.GraphRag graph = GraphRagFactory.openAt(config(), graphDir)) {
            List<JudgedQuestion> mixed = List.of(
                new JudgedQuestion("Who did Ada Lovelace collaborate with?",
                    List.of("Charles Babbage")),
                new JudgedQuestion("Who did Ada Lovelace collaborate with?",
                    List.of("Zxqv Nonexistent")));

            CalibrationReport report =
                new GraphCalibrationHarness(graph.pipeline(), graph.linker()).run(mixed, 1.0);

            assertThat(report.overallCoverage())
                .as("an operator asking what fraction of questions get answered means all of them")
                .isLessThan(report.thresholds().coverage());
            assertThat(report.overallCoverage())
                .isEqualTo(report.thresholds().answered() / 2.0);
        }
    }

    @Test
    @DisplayName("the printed configuration is the configuration that was fitted")
    void propertiesMatchTheFit(@TempDir Path dataDir) throws Exception {
        Path graphDir = dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY);
        buildGraph(graphDir);

        try (GraphRagFactory.GraphRag graph = GraphRagFactory.openAt(config(), graphDir)) {
            CalibrationReport report =
                new GraphCalibrationHarness(graph.pipeline(), graph.linker())
                    .run(judged(), 0.75);

            String properties = report.properties();
            assertThat(properties)
                .contains("excalibur.graph.min-score=")
                .contains("excalibur.graph.min-separation=")
                .contains("excalibur.graph.min-mean-confidence=");
            assertThat(properties)
                .as("a temperature the fit called unusable must not be pasteable")
                .satisfies(text -> {
                    if (report.confidence().isPresent()
                            && !report.confidence().get().wellPosed()) {
                        assertThat(text)
                            .contains("# excalibur.graph.confidence-temperature")
                            .contains("NOT WELL POSED");
                    }
                });
            assertThat(report.summary()).contains("thresholds").contains("coverage");
        }
    }

    @Test
    @DisplayName("calibrating against no judged questions is refused")
    void needsJudgedQuestions(@TempDir Path dataDir) throws Exception {
        Path graphDir = dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY);
        buildGraph(graphDir);

        try (GraphRagFactory.GraphRag graph = GraphRagFactory.openAt(config(), graphDir)) {
            GraphCalibrationHarness harness =
                new GraphCalibrationHarness(graph.pipeline(), graph.linker());

            assertThatThrownBy(() -> harness.run(List.of(), 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one judged question");
        }
    }

    @Test
    @DisplayName("a set whose answers this graph holds none of is a failure, not an empty fit")
    void nothingFittableIsAFailure(@TempDir Path dataDir) throws Exception {
        Path graphDir = dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY);
        buildGraph(graphDir);

        try (GraphRagFactory.GraphRag graph = GraphRagFactory.openAt(config(), graphDir)) {
            GraphCalibrationHarness harness =
                new GraphCalibrationHarness(graph.pipeline(), graph.linker());
            List<JudgedQuestion> unusable = List.of(
                new JudgedQuestion("Who did Zxqv collaborate with?", List.of("Qqzz Nobody")));

            assertThatThrownBy(() -> harness.run(unusable, 0.9))
                .as("returning thresholds fitted to nothing would look like a measurement")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing here to fit");
        }
    }

    @Test
    @DisplayName("the judgement file is read, with blanks and comments skipped")
    void readsTheJudgementFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("judged.tsv");
        Files.writeString(file,
            "# a comment\n"
            + "\n"
            + "Who did Ada Lovelace collaborate with?\tCharles Babbage\n"
            + "Who did Alan Turing collaborate with?\tMax Newman, Alonzo Church\n"
            + "a line with no tab\n",
            StandardCharsets.UTF_8);

        List<JudgedQuestion> judged = GraphCalibrationRunner.readJudged(file);

        assertThat(judged).hasSize(2);
        assertThat(judged.get(0).expected()).containsExactly("Charles Babbage");
        assertThat(judged.get(1).expected())
            .as("several right answers are one judgement, not two")
            .containsExactly("Max Newman", "Alonzo Church");
    }
}
