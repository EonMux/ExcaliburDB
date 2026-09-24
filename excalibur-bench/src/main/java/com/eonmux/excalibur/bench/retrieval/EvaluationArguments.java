package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.config.LlmConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a run of the retrieval evaluation was asked to measure.
 *
 * <pre>
 * usage: CorpusEvaluationRunner &lt;corpusDir&gt; &lt;indexDir&gt; &lt;qrelsFile&gt; [k] [baseUrl]
 *                              [chatModel] [native|lucene|both] [embeddingModel] [dimensions]
 * </pre>
 *
 * <p>The optional arguments are positional because the Gradle task passes them through one
 * space-separated property. New ones are only ever appended, so a command line written before
 * the embedding model existed still means what it meant. {@code -} stands in for one being
 * skipped.
 *
 * <p>One base URL serves both models, as it does on a node: {@code excalibur.llm.base-url} is the
 * endpoint a node sends both chat and embedding requests to, and the evaluation node is configured
 * from these arguments. The key is read from {@value #API_KEY_VARIABLE}, the variable a node
 * reads it from, rather than taken on a command line that ends up in shell history.
 *
 * @param corpus              Directory of documents to evaluate over
 * @param indexRoot           Directory the indexes and evaluation nodes are written under
 * @param qrels               The judged queries
 * @param k                   Depth every metric is computed at
 * @param baseUrl             The model endpoint, or null when no model is used
 * @param chatModel           The chat model, or null for none
 * @param engines             The lexical engines to measure, in order
 * @param embeddingModel      The embedding model, or null for none
 * @param embeddingDimensions Width of the embedding model's vectors
 * @param apiKey              The key sent to the endpoint; empty for one that needs none
 */
record EvaluationArguments(Path corpus, Path indexRoot, Path qrels, int k, String baseUrl,
                           String chatModel, List<LexicalEngine> engines, String embeddingModel,
                           int embeddingDimensions, String apiKey) {

    /** The environment variable the endpoint's key is read from, as a node reads it. */
    static final String API_KEY_VARIABLE = "EXCALIBUR_LLM_API_KEY";

    static final String USAGE = "usage: CorpusEvaluationRunner <corpusDir> <indexDir> <qrels> "
        + "[k] [baseUrl] [chatModel] [native|lucene|both] [embeddingModel] [dimensions]\n"
        + "       optional arguments are positional; '-' skips one. The endpoint's key is read "
        + "from " + API_KEY_VARIABLE + ".";

    private static final int DEFAULT_K = 10;
    private static final int MAX_ARGUMENTS = 9;

    EvaluationArguments {
        Objects.requireNonNull(corpus, "corpus must not be null");
        Objects.requireNonNull(indexRoot, "indexRoot must not be null");
        Objects.requireNonNull(qrels, "qrels must not be null");
        engines = List.copyOf(engines);
    }

    /** Whether a chat model was named: query expansion and relation extraction. */
    boolean hasChatModel() {
        return chatModel != null;
    }

    /** Whether an embedding model was named: the vector half of the hybrid. */
    boolean hasEmbeddingModel() {
        return embeddingModel != null;
    }

    /**
     * Reads a command line.
     *
     * @param args        The arguments, as {@code main} received them
     * @param environment Where the endpoint's key is looked up
     * @throws IllegalArgumentException with the usage, or naming the argument that is wrong
     */
    static EvaluationArguments parse(String[] args, Map<String, String> environment) {
        if (args.length < 3 || args.length > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("expected 3 to " + MAX_ARGUMENTS
                + " arguments, got " + args.length + "\n" + USAGE);
        }
        String k = optional(args, 3);
        String baseUrl = optional(args, 4);
        String chatModel = optional(args, 5);
        String engine = optional(args, 6);
        String embeddingModel = optional(args, 7);
        String dimensions = optional(args, 8);

        // Refused rather than dropped: a run that silently measured without the model it was
        // given would print a table in which that model's strategies read as skipped for a
        // reason the caller believes they addressed.
        if ((chatModel != null || embeddingModel != null) && baseUrl == null) {
            throw new IllegalArgumentException("a model was named with no base URL to reach it "
                + "through (argument 5)\n" + USAGE);
        }

        String key = environment.get(API_KEY_VARIABLE);
        return new EvaluationArguments(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]),
            k == null ? DEFAULT_K : positive(k, "k"),
            baseUrl,
            chatModel,
            LexicalEngine.parse(engine == null ? LexicalEngine.NATIVE.id() : engine),
            embeddingModel,
            dimensions == null ? LlmConfig.DEFAULT_EMBEDDINGS_DIMENSIONS
                : positive(dimensions, "embedding dimensions"),
            key == null ? "" : key.trim());
    }

    /**
     * A positional argument that was actually supplied.
     *
     * <p>Blank and {@code -} both mean "not supplied", because these are positional: naming an
     * engine without a chat model means passing placeholders for the arguments in between, and a
     * runner invoked through Gradle cannot pass an empty one. Without this, {@code ''} arrives as
     * two literal quote characters and fails deep inside a client with a message about
     * configuration the caller never set.
     */
    private static String optional(String[] args, int index) {
        if (args.length <= index) {
            return null;
        }
        String value = args[index].trim();
        return value.isEmpty() || "-".equals(value) || "''".equals(value) || "\"\"".equals(value)
            ? null : value;
    }

    private static int positive(String value, String name) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive whole number, got '"
                + value + "'", e);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + parsed);
        }
        return parsed;
    }
}
