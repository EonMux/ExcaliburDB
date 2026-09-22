package excalibur.bench.calibration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import excalibur.api.bootstrap.GraphRagFactory;
import io.excalibur.config.Config;
import io.excalibur.config.ConfigLoader;

/**
 * Fits a graph's abstention from the command line, and prints the configuration it implies.
 *
 * <pre>
 * usage: GraphCalibrationRunner &lt;graphDir&gt; &lt;judgedFile&gt; [targetPrecision] [configFile]
 * </pre>
 *
 * <p>{@code graphDir} is a built graph store - the directory holding {@code edges.csr}, the two
 * Lucene indexes and the dictionaries - not the data directory above it.
 *
 * <p>The judgement file is one question per line, {@code question<TAB>name[,name...]}, naming the
 * entities that answer it. The same shape as the document route's qrels, so an operator learns one
 * format; entities rather than chunk ids, because the graph route answers with entities.
 *
 * <h2>Pass the configuration the node runs</h2>
 * Optional and strongly advised. Thresholds are only valid for the retrieval that produced them,
 * and {@code top-k}, {@code rerank-over-fetch} and whether an embedding endpoint is configured all
 * change what retrieval returns. Fitting under defaults and deploying under something else gives a
 * node gates measured on a pipeline it is not running. The abstention keys in that file are read
 * and ignored: the fit stops before abstention, so it is not anchored to the gates already in
 * place.
 */
public final class GraphCalibrationRunner {

    /** Target precision when the caller does not name one. */
    private static final double DEFAULT_TARGET_PRECISION = 0.9;

    private GraphCalibrationRunner() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: GraphCalibrationRunner <graphDir> <judgedFile> "
                + "[targetPrecision] [configFile]");
            System.exit(2);
        }
        Path graphDir = Path.of(args[0]);
        Path judgedFile = Path.of(args[1]);
        double targetPrecision =
            args.length > 2 ? Double.parseDouble(args[2]) : DEFAULT_TARGET_PRECISION;
        Config config = args.length > 3
            ? ConfigLoader.load(args[3], Map.of())
            : new Config(new java.util.HashMap<>());

        List<JudgedQuestion> judged = readJudged(judgedFile);
        if (judged.isEmpty()) {
            System.err.println("no judged questions in " + judgedFile);
            System.exit(2);
        }

        System.out.printf("Calibrating %s against %d judged questions, target precision %.4f%n%n",
            graphDir, judged.size(), targetPrecision);

        try (GraphRagFactory.GraphRag graph = GraphRagFactory.openAt(config, graphDir)) {
            CalibrationReport report =
                new GraphCalibrationHarness(graph.pipeline(), graph.linker())
                    .run(judged, targetPrecision);

            System.out.println(report.summary());
            System.out.println("  --- excalibur.properties ---");
            System.out.println();
            System.out.println(report.properties());
            System.out.println("  Measured in-sample, on a set someone wrote knowing this graph.");
            System.out.println("  Hold questions back and re-measure before quoting these "
                + "numbers as the system's.");
        }
    }

    /**
     * Reads the judgement file, skipping blanks, comments and malformed lines.
     *
     * <p>Skipping rather than failing: a judgement file is written by hand over time, and one bad
     * line should not cost the run. The count printed against the file's length is what shows a
     * line went missing.
     */
    static List<JudgedQuestion> readJudged(Path file) throws IOException {
        List<JudgedQuestion> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String question = line.substring(0, tab).trim();
            List<String> expected = new ArrayList<>();
            for (String name : line.substring(tab + 1).split(",")) {
                if (!name.isBlank()) {
                    expected.add(name.trim());
                }
            }
            if (!question.isEmpty() && !expected.isEmpty()) {
                out.add(new JudgedQuestion(question, expected));
            }
        }
        return out;
    }
}
