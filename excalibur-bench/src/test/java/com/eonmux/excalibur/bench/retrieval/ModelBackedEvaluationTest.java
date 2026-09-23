package com.eonmux.excalibur.bench.retrieval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The strategies that need a model, run end to end against a stand-in one.
 *
 * <p>Each runs the way a node runs it: the corpus is ingested through a node's ingestion path -
 * embedded, and with facts extracted - the graph is built by its build route, hybrid questions
 * go to {@code /v1/query} and graph questions to the pipeline {@code /v1/graph/query} answers
 * from. The model is the one stand-in, and it is built so each signal can be told apart by the
 * score it earns: see {@link StubModelEndpoint}.
 *
 * <p>With k=1 and three judged questions, recall is the share of questions whose first answer is
 * right, so every expected score below is a count of questions out of three:
 * <ul>
 *   <li>"RAM exhausted kernel" shares only "kernel" with the OOM document, and BM25 ranks the
 *       kernel document above it, which says "kernel" twice in fewer words. The embedding (ram
 *       means memory) ranks the OOM document first, and the node's hybrid breaks the resulting tie
 *       towards the vector signal, so only a strategy with vectors answers it.</li>
 *   <li>"TLS problem" shares no word with the TLS document; only expansion (certificate chain,
 *       handshake) reaches it. A vector search still answers it - every segment has nearest
 *       neighbours - with a document that merely scores least badly, and at k=1 that neighbour and
 *       expansion's first answer each top one list. The hybrid breaks that tie towards the vector
 *       signal, so expansion earns this question alone or beside the graph, but not beside the
 *       vectors. That is the trade the comparison exists to show: a dense signal with nothing
 *       relevant to say still takes a rank.</li>
 *   <li>"What did Ada Lovelace write?" is ranked by BM25 to the portrait document, which names her
 *       twice in fewer words than the Byron document names her once. Only the graph points at
 *       the Byron document, through the fact extracted from it, so only a strategy with the graph
 *       answers it.</li>
 * </ul>
 */
@DisplayName("Model-backed retrieval strategies")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ModelBackedEvaluationTest {

    private StubModelEndpoint model;

    @BeforeEach
    void startModel() throws Exception {
        model = StubModelEndpoint.start();
    }

    @AfterEach
    void stopModel() {
        model.close();
    }

    private static Path corpus(Path root, boolean withTheExtractableFact) throws Exception {
        Path corpus = Files.createDirectories(root.resolve("corpus"));
        Files.writeString(corpus.resolve("oom.txt"),
            "the kernel OOM killer terminated the process under memory pressure");
        Files.writeString(corpus.resolve("kernel.txt"),
            "kernel kernel upgrade scheduled for the weekend window");
        Files.writeString(corpus.resolve("tls.txt"),
            "the certificate chain could not be verified during the handshake");
        Files.writeString(corpus.resolve("portrait.txt"),
            "Ada Lovelace Ada Lovelace portrait gallery");
        if (withTheExtractableFact) {
            Files.writeString(corpus.resolve("byron.txt"),
                "Byron's daughter Ada Lovelace wrote the first algorithm in 1843");
        }
        return corpus;
    }

    private static Path qrels(Path root) throws Exception {
        return Files.writeString(root.resolve("qrels.tsv"),
            "RAM exhausted kernel\tcorpus:oom.txt:chunk:0\n"
                + "TLS problem\tcorpus:tls.txt:chunk:0\n"
                + "What did Ada Lovelace write?\tcorpus:byron.txt\n",
            StandardCharsets.UTF_8);
    }

    /** Runs the harness at k=1 over the corpus, and captures what it printed. */
    private static String run(Path root, boolean withTheExtractableFact, String... models)
            throws Exception {
        String[] args = new String[4 + models.length];
        args[0] = corpus(root, withTheExtractableFact).toString();
        args[1] = root.resolve("index").toString();
        args[2] = qrels(root).toString();
        args[3] = "1";
        System.arraycopy(models, 0, args, 4, models.length);

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            CorpusEvaluationRunner.main(args);
        } finally {
            System.setOut(original);
        }
        String output = captured.toString(StandardCharsets.UTF_8);
        System.out.println(output);
        return output;
    }

    private static String row(String output, RetrievalStrategy strategy) {
        return output.lines()
            .filter(line -> line.startsWith(String.format("  %-28s", strategy)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no row for " + strategy + " in:\n" + output));
    }

    /** The recall column of a strategy that ran. */
    private static String recall(String output, RetrievalStrategy strategy) {
        String row = row(output, strategy);
        assertThat(row).as("%s ran", strategy).doesNotContain("skipped");
        return row.substring(32).trim().split("\\s+")[0];
    }

    /**
     * Checks a vector-backed row, whose vector signal must have answered every question: a row
     * the harness notes as short of vectors measured a node whose vector search failed, and its
     * score would say nothing about what the vectors add.
     */
    private static void assertVectorRow(String output, RetrievalStrategy strategy, String expected) {
        assertThat(output).as("%s had vectors in every answer", strategy)
            .doesNotContain("note: " + strategy + " - the vector signal found none of the results");
        assertThat(recall(output, strategy)).as("%s", strategy).isEqualTo(expected);
    }

    @Test
    @DisplayName("every strategy runs when there is a chat model and an embedding model")
    void everyStrategyRuns(@TempDir Path root) throws Exception {
        String output = run(root, true, model.baseUrl(), "stub-chat", "native", "stub-embed",
            String.valueOf(StubModelEndpoint.DIMENSIONS));

        assertThat(recall(output, RetrievalStrategy.BM25)).isEqualTo("0.0000");
        assertThat(recall(output, RetrievalStrategy.BM25_EXPANSION_EXACT_VOCAB))
            .as("expansion reaches the TLS document").isEqualTo("0.3333");
        assertThat(recall(output, RetrievalStrategy.BM25_GRAPH))
            .as("the graph reaches the Byron document through the extracted fact")
            .isEqualTo("0.3333");
        assertVectorRow(output, RetrievalStrategy.BM25_VECTOR, "0.3333");
        assertThat(recall(output, RetrievalStrategy.BM25_EXPANSION_GRAPH))
            .as("expansion answers TLS and the graph answers Ada Lovelace").isEqualTo("0.6667");
        assertVectorRow(output, RetrievalStrategy.BM25_EXPANSION_VECTOR, "0.3333");
        assertVectorRow(output, RetrievalStrategy.FULL_HYBRID, "0.6667");

        assertThat(model.extractionCalls()).as("every document went through extraction, once")
            .isEqualTo(5);
        assertThat(model.embeddingCalls()).isPositive();
        assertThat(output).contains("evaluation node").contains("graph built");
        assertThat(output).doesNotContain("fewer signals");
    }

    @Test
    @DisplayName("a chat model alone runs the graph strategies and names what the rest need")
    void chatModelAlone(@TempDir Path root) throws Exception {
        String output = run(root, true, model.baseUrl(), "stub-chat");

        assertThat(recall(output, RetrievalStrategy.BM25_GRAPH)).isEqualTo("0.3333");
        assertThat(recall(output, RetrievalStrategy.BM25_EXPANSION_GRAPH)).isEqualTo("0.6667");
        assertThat(row(output, RetrievalStrategy.BM25_VECTOR))
            .contains("skipped").contains("no embedded corpus").contains("embedding model");
        assertThat(row(output, RetrievalStrategy.BM25_EXPANSION_VECTOR))
            .contains("skipped").contains("embedding model");
        assertThat(row(output, RetrievalStrategy.FULL_HYBRID))
            .contains("skipped").contains("embedding model");
        assertThat(model.embeddingCalls()).as("nothing was embedded").isZero();
    }

    @Test
    @DisplayName("an embedding model alone runs the hybrid, on the engine asked for")
    void embeddingModelAlone(@TempDir Path root) throws Exception {
        String output = run(root, true, model.baseUrl(), "-", "lucene", "stub-embed",
            String.valueOf(StubModelEndpoint.DIMENSIONS));

        assertThat(output).contains("=== lucene engine ===").doesNotContain("native engine");
        assertVectorRow(output, RetrievalStrategy.BM25_VECTOR, "0.3333");
        assertThat(row(output, RetrievalStrategy.BM25_EXPANSION_VECTOR))
            .contains("skipped").contains("chat model");
        assertThat(row(output, RetrievalStrategy.BM25_GRAPH))
            .contains("skipped").contains("no graph").contains("chat model");
        assertThat(row(output, RetrievalStrategy.BM25_EXPANSION_GRAPH)).contains("skipped");
        assertThat(row(output, RetrievalStrategy.FULL_HYBRID)).contains("skipped");
        assertThat(model.extractionCalls()).as("nothing asked for facts").isZero();
        assertThat(model.expansionCalls()).isZero();
    }

    @Test
    @DisplayName("a graph built from no facts is a skipped strategy, not a scored one")
    void nothingExtracted(@TempDir Path root) throws Exception {
        String output = run(root, false, model.baseUrl(), "stub-chat");

        assertThat(row(output, RetrievalStrategy.BM25_GRAPH))
            .contains("skipped").contains("no facts");
        assertThat(row(output, RetrievalStrategy.BM25_EXPANSION_GRAPH))
            .contains("skipped").contains("no facts");
        assertThat(model.extractionCalls()).isEqualTo(4);
    }

    @Test
    @DisplayName("an endpoint nothing answers on skips its strategies with the reason")
    void unreachableEndpoint(@TempDir Path root) throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        String output = run(root, true, "http://127.0.0.1:" + closedPort, "-", "native",
            "stub-embed", String.valueOf(StubModelEndpoint.DIMENSIONS));

        assertThat(row(output, RetrievalStrategy.BM25_VECTOR))
            .contains("skipped").contains("ingest");
        assertThat(recall(output, RetrievalStrategy.BM25))
            .as("the strategies that need no model are still measured").isEqualTo("0.0000");
    }

    @Test
    @DisplayName("a node directory left by an earlier run is refused rather than evaluated")
    void refusesAnEarlierRunsNode(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("index").resolve("node-native"));
        Files.writeString(root.resolve("index").resolve("node-native").resolve("left"), "x");

        assertThatThrownBy(() -> run(root, true, model.baseUrl(), "stub-chat"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("node-native");
    }
}
