package excalibur.api.bootstrap;

import io.excalibur.config.Config;
import io.excalibur.config.GraphConfig;
import io.excalibur.graph.dictionary.LabelDictionary;
import io.excalibur.graph.store.MappedEdgeStore;
import io.excalibur.planner.QueryClassifier;
import io.excalibur.retrieval.rank.Ranker;
import io.excalibur.retrieval.rerank.BatchedLlmReranker;
import io.excalibur.retrieval.rerank.LexicalCandidateText;
import io.excalibur.retrieval.rerank.LlmBatchRelevanceModel;
import io.excalibur.llm.client.LLMHttpClient;
import excalibur.llm.client.LLMClientConfig;
import io.excalibur.retrieval.rerank.EmbeddingGatedRanker;
import io.excalibur.retrieval.rerank.FullPrecisionReranker;
import io.excalibur.vector.distance.DistanceFunctions;
import excalibur.llm.embedding.EmbeddingClient;
import io.excalibur.retrieval.HybridRetriever;
import io.excalibur.retrieval.abstain.ThresholdAbstentionPolicy;
import io.excalibur.retrieval.lexical.LexicalIndex;
import io.excalibur.retrieval.lexical.LuceneRanker;
import io.excalibur.retrieval.pipeline.GraphRagPipeline;
import io.excalibur.retrieval.pipeline.LexicalEntityLinker;
import io.excalibur.retrieval.serialize.BpeTokenCounter;
import io.excalibur.retrieval.serialize.SerializationBudget;
import io.excalibur.retrieval.serialize.TripleSubgraphSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Opens the graph-RAG stack, when there is one on disk.
 *
 * <p>{@code POST /v1/graph/query} answers from a graph the node did not build: an edge store, a
 * lexical index over documents, another over entity labels, and the dictionaries that turn integer
 * ids back into names. All of it is produced by an offline build and read here. A deployment doing
 * only vector search has none of it, which is why this returns null rather than failing - an
 * absent graph is a configuration, and the route is simply not registered.
 *
 * <h2>All or nothing</h2>
 * <p>A directory holding some of these files and not others is a failure, not a partial
 * configuration. Opening what is present and quietly dropping the rest would produce a pipeline
 * that answers - with no entity linking, so every question resolves to nothing and every answer is
 * a refusal that looks like "I don't know" rather than "this node is misconfigured".
 */
public final class GraphRagFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphRagFactory.class);

    /** Subdirectory of the data directory holding the graph, when none is configured. */
    public static final String DEFAULT_DIRECTORY = "graph";

    /** Lucene index over document text, searched by the lexical retrieval signal. */
    public static final String DOCUMENT_INDEX = "documents";

    /** Lucene index over entity labels, searched by entity linking. */
    public static final String LABEL_INDEX = "labels";

    /** Dictionary mapping entity ids to names. */
    public static final String ENTITY_DICTIONARY = "entities";

    /** Dictionary mapping relation ids to names. */
    public static final String RELATION_DICTIONARY = "relations";

    private GraphRagFactory() {
        // Utility class
    }

    /**
     * The opened stack, and the handles that have to be closed with it.
     *
     * @param pipeline    The pipeline to serve requests from
     * @param closeables  Everything opened, in the order it should be closed
     * @param directory   Where it was read from
     */
    public record GraphRag(GraphRagPipeline pipeline, List<AutoCloseable> closeables,
                           Path directory) implements AutoCloseable {

        public GraphRag {
            Objects.requireNonNull(pipeline, "pipeline cannot be null");
            closeables = List.copyOf(closeables);
        }

        @Override
        public void close() {
            // Every handle gets a close attempt even if an earlier one throws; one failing
            // mapping must not strand the rest for the life of the process.
            for (AutoCloseable closeable : closeables) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    LOGGER.warn("Failed to close {}: {}",
                        closeable.getClass().getSimpleName(), e.toString());
                }
            }
        }
    }

    /**
     * Opens a single store under a data directory, at {@code <dataDir>/graph}.
     *
     * <p>Deliberately not a reader of {@code excalibur.graph.path}: that key names the root the
     * node serves <em>namespaces</em> from, and a store lives one level inside it. Resolving it
     * here would give this method and the running node two different answers for the same
     * configuration - which is how a previous version of this class came to open a graph at a
     * path nothing served.</p>
     *
     * @param config  Application configuration, for the read-side settings
     * @param dataDir The node's data directory
     * @return The opened stack, or null when there is no graph there
     * @throws IOException if a graph is present but cannot be opened
     */
    public static GraphRag open(Config config, Path dataDir) throws IOException {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(dataDir, "dataDir cannot be null");
        return openAt(config, dataDir.resolve(DEFAULT_DIRECTORY));
    }

    /**
     * Opens the graph-RAG stack from a directory that is already resolved.
     *
     * <p>For a caller that has just built the graph and must reopen the directory it wrote rather
     * than the one configuration would resolve. Those are the same path in every ordinary
     * deployment and are not when {@code excalibur.graph.path} is set, where re-resolving would
     * reopen somewhere else entirely.
     *
     * @param config    Configuration, for the read-side settings
     * @param directory The graph directory
     * @return The opened stack, or null when the directory is not there
     * @throws IOException if the directory exists but cannot be opened
     */
    public static GraphRag openAt(Config config, Path directory) throws IOException {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(directory, "directory cannot be null");

        if (!Files.isDirectory(directory)) {
            LOGGER.info("No graph store at {}; graph queries will report that none is built",
                directory);
            return null;
        }

        GraphConfig settings = config.graph();
        Deque<AutoCloseable> opened = new ArrayDeque<>();
        try {
            MappedEdgeStore edges = MappedEdgeStore.open(directory);
            opened.push(edges);

            LexicalIndex documents = LexicalIndex.open(
                directory.resolve(DOCUMENT_INDEX), settings.getRamBufferMb());
            opened.push(documents);

            LexicalIndex labels = LexicalIndex.open(
                directory.resolve(LABEL_INDEX), settings.getRamBufferMb());
            opened.push(labels);

            LabelDictionary entityNames =
                LabelDictionary.open(directory, ENTITY_DICTIONARY, "E");
            opened.push(entityNames);

            LabelDictionary relationNames =
                LabelDictionary.open(directory, RELATION_DICTIONARY, "R");
            opened.push(relationNames);

            // Absent is a configuration: a graph built without an embedder has none, and is
            // reranked lexically. Present but unreadable is a failure, because the file is there
            // and says something this build cannot act on.
            io.excalibur.graph.vector.EntityVectors entityVectors =
                io.excalibur.graph.vector.EntityVectors.openIfPresent(directory);
            if (entityVectors != null) {
                opened.push(entityVectors);
            }

            GraphRagPipeline pipeline = new GraphRagPipeline(
                new QueryClassifier(),
                new LexicalEntityLinker(labels),
                edges,
                new HybridRetriever(List.of(rankerFor(config, documents, entityVectors)),
                    new io.excalibur.retrieval.fusion.ReciprocalRankFusion(
                        config.query().getRrfK()),
                    config.query().getPerSignalOverFetch()),
                new TripleSubgraphSerializer(entityNames, relationNames),
                new ThresholdAbstentionPolicy(
                    settings.getMinScore(),
                    settings.getMinSeparation(),
                    settings.getMinMeanConfidence(),
                    settings.getMaxAmbiguousCandidates()),
                new SerializationBudget(
                    settings.getContextTokenBudget(), BpeTokenCounter.defaultCounter()),
                settings.getTopK(),
                embedderFor(config, entityVectors));

            LOGGER.info("Graph-RAG pipeline opened from {}: {} entity names, {} relation names",
                directory, entityNames.size(), relationNames.size());
            return new GraphRag(pipeline, new ArrayList<>(opened), directory);
        } catch (IOException | RuntimeException e) {
            // Everything opened so far, released in reverse order. Leaving mappings behind on a
            // failed start is how a node that refuses to serve also refuses to shut down cleanly.
            for (AutoCloseable closeable : opened) {
                try {
                    closeable.close();
                } catch (Exception suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
    }

    /**
     * The directory the graph is read from.
     *
     * @param config  Application configuration
     * @param dataDir The node's data directory
     * @return The configured path, or {@code <dataDir>/graph}
     */
    /**
     * The lexical signal, reranked by the chat model when there is one to ask.
     *
     * <p>BM25 ranks by term overlap, which is what makes it a good first pass and a poor last one:
     * it cannot tell a passage that mentions the query's words from one that answers it. Reranking
     * the top of that list with a model is the step that separates them, and it is bounded - one
     * call over a capped number of candidates, behind the same circuit breaker as every other model
     * call, falling back to the BM25 order when the model is unavailable.</p>
     *
     * <p>Without an API key there is no model to ask, and the BM25 ranking is returned as it is.
     * That is a weaker answer, not a broken one.</p>
     *
     * @param config    Configuration, read for the LLM settings and the context budget
     * @param documents The index over each entity's text
     * @return The ranker the retriever should use
     */
    private static Ranker rankerFor(Config config, LexicalIndex documents,
                                    io.excalibur.graph.vector.EntityVectors vectors) {
        Ranker ranked = new LuceneRanker(documents);

        // Cheapest correction first. BM25 ranks by term overlap, which is why it is a good first
        // pass and a poor last one; re-scoring its top candidates against the entities' real
        // vectors costs a few dozen array reads and fixes the ordering it cannot see. Gated on the
        // query actually carrying an embedding, because the endpoint that produces one can be
        // unavailable and a missing embedding must degrade the answer rather than fail it.
        if (vectors != null) {
            ranked = new EmbeddingGatedRanker(
                new FullPrecisionReranker(ranked, vectors::vectorOf,
                    DistanceFunctions.byName(config.query().getDistanceMetric()),
                    config.graph().getRerankOverFetch()),
                ranked);
        }

        String apiKey = config.llm().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            LOGGER.info("No LLM API key; graph retrieval ranks by BM25{}",
                vectors == null ? "" : " with an exact vector rerank");
            return ranked;
        }

        // And the most expensive correction last, over the smallest set: one model call, capped,
        // behind the breaker every other model call uses.
        LLMHttpClient client = new LLMHttpClient(LLMClientConfig.fromConfig(config.llm()));
        return new BatchedLlmReranker(
            ranked,
            new LlmBatchRelevanceModel(client),
            new LexicalCandidateText(documents),
            client.getCircuitBreaker(),
            new SerializationBudget(
                config.graph().getContextTokenBudget(), BpeTokenCounter.defaultCounter()));
    }

    /**
     * Embeds the question, when this node has an embedding endpoint.
     *
     * <p>Only worth having when there is something to compare the result against: without stored
     * entity vectors nothing in the chain reads the embedding, and producing one would be a model
     * call per query for nothing.</p>
     *
     * @param config  Configuration, for the LLM settings
     * @param vectors The stored entity vectors, or null when the graph has none
     * @return The embedder, or null
     */
    private static GraphRagPipeline.QueryEmbedder embedderFor(
            Config config, io.excalibur.graph.vector.EntityVectors vectors) {
        String apiKey = config.llm().getApiKey();
        if (vectors == null || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        EmbeddingClient client = new EmbeddingClient(LLMClientConfig.fromConfig(config.llm()));
        return text -> client.embedWithCircuitBreaker(text);
    }

    /**
     * The directory holding one subdirectory per namespace, each with that namespace's graph.
     *
     * <p>This is what {@code excalibur.graph.path} names, and it is the only reader of that key.
     * The served store for namespace {@code n} is {@code <root>/n/graph} and its staging area is
     * {@code <root>/n/graph-staging}; a node that never sees the namespace header uses exactly
     * one, {@code shared}.</p>
     *
     * <p>The configured value is the root itself. Taking its parent - which an earlier version
     * did - discarded the last segment, so {@code /mnt/graphs/alpha} and {@code /mnt/graphs/beta}
     * named one deployment, and a relative single-segment value named none.</p>
     *
     * @param config  Application configuration
     * @param dataDir The node's data directory, used when the key is unset or blank
     * @return The graph root
     */
    public static Path tenantRootFor(Config config, Path dataDir) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(dataDir, "dataDir cannot be null");
        String configured = config.graph().getPath();
        return configured != null && !configured.isBlank() ? Path.of(configured) : dataDir;
    }
}
