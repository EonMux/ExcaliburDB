package excalibur.bench.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The corpus evaluation harness, run over both engines.
 *
 * <p>What this guards is faithfulness rather than a number. The harness measured Lucene while every
 * node shipped with the native index, which made its output a statement about a system nobody was
 * running - and no test noticed, because a benchmark that indexes and reports is indistinguishable
 * from a benchmark that indexes the right thing and reports. So the properties asserted here are
 * that the engine a run measured is named in its own output, that the shipped engine is what a run
 * measures unless told otherwise, and that the cost of building the index is reported beside the
 * quality, because the comparison this harness exists for is a cost comparison.
 */
@DisplayName("Corpus evaluation runner")
class CorpusEvaluationRunnerTest {

    /** Writes a corpus small enough to index in a test and specific enough to judge. */
    private static Path corpus(Path root) throws Exception {
        Path corpus = Files.createDirectories(root.resolve("corpus"));
        Files.writeString(corpus.resolve("oom.txt"),
            "the OOM killer terminated the process under memory pressure", StandardCharsets.UTF_8);
        Files.writeString(corpus.resolve("tls.txt"),
            "the certificate chain could not be verified during the handshake",
            StandardCharsets.UTF_8);
        return corpus;
    }

    private static Path qrels(Path root) throws Exception {
        Path file = root.resolve("qrels.tsv");
        Files.writeString(file,
            "memory pressure\tcorpus:oom.txt:chunk:0\n"
                + "certificate handshake\tcorpus:tls.txt:chunk:0\n", StandardCharsets.UTF_8);
        return file;
    }

    /** Runs the harness and captures what it printed. */
    private static String run(Path root, String... extra) throws Exception {
        String[] args = new String[3 + extra.length];
        args[0] = corpus(root).toString();
        args[1] = root.resolve("index").toString();
        args[2] = qrels(root).toString();
        System.arraycopy(extra, 0, args, 3, extra.length);

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            CorpusEvaluationRunner.main(args);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("measures the engine a node ships with by default")
    void defaultsToTheShippedEngine(@TempDir Path root) throws Exception {
        // The default has to be what production runs. A harness whose default measures the
        // alternative reports a number about a system nobody deployed.
        String output = run(root);

        assertThat(output).contains("=== native engine ===");
        assertThat(output).doesNotContain("=== lucene engine ===");
        assertThat(output).contains("engine=native");
    }

    @Test
    @DisplayName("finds the judged chunks on the shipped engine")
    void scoresTheNativeEngine(@TempDir Path root) throws Exception {
        String output = run(root, "10");

        // Two judged queries, each answered by exactly one chunk that contains its words.
        assertThat(output).contains("judged queries: 2");
        assertThat(output).containsPattern("BM25\\s+1\\.0000");
    }

    @Test
    @DisplayName("reports what the index cost to build, beside the quality")
    void reportsTheIndexCost(@TempDir Path root) throws Exception {
        // The question the harness exists for is whether query-side work reaches the same
        // documents at a fraction of the indexing cost. A quality number printed without that
        // cost cannot answer it.
        String output = run(root, "10");

        assertThat(output).contains("index on disk:");
        assertThat(output).contains("MiB");
        assertThat(output).containsPattern("index on disk: [0-9.]+ MiB \\([0-9.]+x the text");
    }

    @Test
    @DisplayName("a skipped positional argument does not become an empty one")
    void placeholdersAreNotValues(@TempDir Path root) throws Exception {
        // These are positional, so naming an engine means passing placeholders for the chat model
        // arguments before it. A placeholder taken as a value fails inside the LLM client, with a
        // message about configuration the caller never set.
        assertThat(run(root, "10", "-", "-", "native")).contains("=== native engine ===");
        assertThat(run(root, "10", "''", "''", "native")).contains("=== native engine ===");
    }

    @Test
    @DisplayName("both engines can be measured over the same corpus in one run")
    void measuresBothEngines(@TempDir Path root) throws Exception {
        String output = run(root, "10", "-", "-", "both");

        assertThat(output).contains("=== native engine ===");
        assertThat(output).contains("=== lucene engine ===");
        assertThat(output).contains("engine=native").contains("engine=lucene");
        assertThat(output.indexOf("=== native engine ==="))
            .as("each engine's cost and quality stay together rather than interleaving")
            .isLessThan(output.indexOf("=== lucene engine ==="));
    }

    @Test
    @DisplayName("a strategy an engine cannot run is reported as skipped, not scored")
    void unavailableStrategiesAreNamed(@TempDir Path root) throws Exception {
        // With no chat model there is no expansion to measure, and a zero row would average into
        // a comparison and quietly make the strategies that did run look worse.
        String output = run(root, "10");

        assertThat(output).contains("BM25_EXPANSION").contains("skipped:");
        assertThat(output).contains("BM25_VECTOR");
        assertThat(output).contains("no embedded corpus");
    }

    @Test
    @DisplayName("an engine it does not have is refused rather than silently substituted")
    void unknownEnginesAreRefused(@TempDir Path root) throws Exception {
        assertThatThrownBy(() -> run(root, "10", "-", "-", "elasticsearch"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("native, lucene or both");
    }
}
