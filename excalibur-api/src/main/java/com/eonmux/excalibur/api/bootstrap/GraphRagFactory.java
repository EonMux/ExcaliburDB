package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.core.memory.MappedFootprint;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.GraphConfig;
import com.eonmux.excalibur.graph.dictionary.LabelDictionary;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.planner.QueryClassifier;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rerank.BatchedLlmReranker;
import com.eonmux.excalibur.retrieval.rerank.LexicalCandidateText;
import com.eonmux.excalibur.retrieval.rerank.LlmBatchRelevanceModel;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.retrieval.rerank.EmbeddingGatedRanker;
import com.eonmux.excalibur.retrieval.rerank.FullPrecisionReranker;
import com.eonmux.excalibur.vector.distance.DistanceFunctions;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.retrieval.HybridRetriever;
import com.eonmux.excalibur.retrieval.fusion.ScorePassthrough;
import com.eonmux.excalibur.retrieval.abstain.AbstentionPolicy;
import com.eonmux.excalibur.retrieval.abstain.CalibratedAbstentionPolicy;
import com.eonmux.excalibur.retrieval.abstain.ThresholdAbstentionPolicy;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.lexical.LuceneRanker;
import com.eonmux.excalibur.retrieval.pipeline.EntityLinker;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;
import com.eonmux.excalibur.retrieval.pipeline.RelationResolver;
import com.eonmux.excalibur.retrieval.pipeline.LexicalEntityLinker;
import com.eonmux.excalibur.retrieval.serialize.BpeTokenCounter;
import com.eonmux.excalibur.retrieval.serialize.SerializationBudget;
import com.eonmux.excalibur.retrieval.serialize.TripleSubgraphSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
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
     * @param linker      The entity linker the pipeline resolves mentions with. Exposed because a
     *                    caller holding a name rather than an id - an evaluation harness reading
     *                    judged answers, for one - has to cross the same text-to-id boundary the
     *                    pipeline does, and doing it against a second index opened separately
     *                    would let the ground truth resolve differently from the questions
     * @param closeables  Everything opened, in the order it should be closed
     * @param directory   Where it was read from
     */
    public record GraphRag(GraphRagPipeline pipeline, EntityLinker linker,
                           List<AutoCloseable> closeables, Path directory)
            implements AutoCloseable {

        public GraphRag {
            Objects.requireNonNull(pipeline, "pipeline cannot be null");
            Objects.requireNonNull(linker, "linker cannot be null");
            closeables = List.copyOf(closeables);
        }

        /**
         * What this open stack costs the process, as {@code [mappings, descriptors]}.
         *
         * <p>Summed over what was actually opened rather than named component by component: the
         * stack is the edge store, two Lucene indexes, two dictionaries and, where the graph was
         * built with an embedder, the entity vectors - and the last of those is optional, so a
         * figure that assumed it would be wrong on half the deployments. Anything in the stack
         * that can account for itself does; anything that cannot contributes nothing, which is
         * the honest answer rather than a guess.
         *
         * <p>Charging only the edge store would report thirteen mappings and no descriptors for a
         * tenant holding rather more of both, so the descriptor limit could never be reached
         * however many tenants were open and the mapping limit would be approached faster than
         * the budget believed.
         *
         * @return The mappings and descriptors held, in that order
         */
        public long[] cost() {
            long mappings = 0;
            long descriptors = 0;
            for (AutoCloseable closeable : closeables) {
                if (closeable instanceof MappedFootprint footprint) {
                    mappings += footprint.mappings();
                    descriptors += footprint.descriptors();
                }
            }
            return new long[] { mappings, descriptors };
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

        com.eonmux.excalibur.graph.staging.GraphBuilder.recoverInterruptedPublish(directory);
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

            // Read-only: a published graph is never written through the stack that serves it.
            // Without a writer no write lock is held, so a build can open its replacement at
            // this path while queries finish against this one - and an index missing from the
            // directory fails the open instead of being created empty and served as the graph.
            LexicalIndex documents = LexicalIndex.openReadOnly(directory.resolve(DOCUMENT_INDEX));
            opened.push(documents);

            LexicalIndex labels = LexicalIndex.openReadOnly(directory.resolve(LABEL_INDEX));
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
            com.eonmux.excalibur.graph.vector.EntityVectors entityVectors =
                com.eonmux.excalibur.graph.vector.EntityVectors.openIfPresent(directory);
            if (entityVectors != null) {
                opened.push(entityVectors);
            }

            LexicalEntityLinker linker = new LexicalEntityLinker(labels);
            GraphRagPipeline pipeline = new GraphRagPipeline(
                new QueryClassifier(),
                linker,
                edges,
                // One signal, whose scores are handed through rather than fused: abstention
                // reads them, and on the unit scale every stage below reports they measure the
                // evidence. Reciprocal rank fusion would replace each with a function of its rank.
                new HybridRetriever(List.of(rankerFor(config, documents, entityVectors)),
                    new ScorePassthrough(), config.query().getPerSignalOverFetch()),
                new TripleSubgraphSerializer(entityNames, relationNames),
                abstentionFor(settings),
                new SerializationBudget(
                    settings.getContextTokenBudget(), BpeTokenCounter.defaultCounter()),
                settings.getTopK(),
                embedderFor(config, entityVectors),
                relationResolverFor(relationNames),
                entityNames);

            LOGGER.info("Graph-RAG pipeline opened from {}: {} entity names, {} relation names",
                directory, entityNames.size(), relationNames.size());
            return new GraphRag(pipeline, linker, new ArrayList<>(opened), directory);
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
     * <p>Whichever stage has the last word, the top of the list is scored on one unit relevance
     * scale, which is what {@code excalibur.graph.min-score} and {@code min-separation} are set
     * against: BM25 as a share of what the question could score, the exact rerank as a vector
     * similarity, and the model as the relevance it judged. A stage that falls back hands on the
     * scale of the stage before it, which is the same scale.</p>
     *
     * @param config    Configuration, read for the LLM settings and the context budget
     * @param documents The index over each entity's text
     * @return The ranker the retriever should use
     */
    private static Ranker rankerFor(Config config, LexicalIndex documents,
                                    com.eonmux.excalibur.graph.vector.EntityVectors vectors) {
        // As a share of the attainable score rather than raw BM25, so that min-score reads the
        // same for a question naming a rare entity as for one naming a common one.
        Ranker ranked = new LuceneRanker(documents, "bm25", LuceneRanker.Scale.ATTAINABLE_SHARE);

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
            Config config, com.eonmux.excalibur.graph.vector.EntityVectors vectors) {
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
     * <p>The configured value is the root itself, not its parent: taking the parent would discard
     * the last segment, so {@code /mnt/graphs/alpha} and {@code /mnt/graphs/beta} would name one
     * deployment, and a relative single-segment value would name none.</p>
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


    /**
     * A resolver over the relation names this graph holds.
     *
     * <p>Read once at open, into a map. The dictionary is a memory-mapped file addressed by id and
     * the question here is the other way round - a name, wanting ids - so every query would
     * otherwise scan the whole thing. Relation vocabularies are small, in the thousands at worst,
     * which is what makes reading them into a map at open the right trade rather than a second
     * index on disk.
     */
    private static RelationResolver relationResolverFor(LabelDictionary relationNames) {
        Map<Integer, String> names = new LinkedHashMap<>(relationNames.size());
        relationNames.forEach((id, name) -> names.put((int) id, name));
        return RelationResolver.over(names);
    }

    /**
     * The abstention policy a configured node runs, thresholds first and calibration over them.
     *
     * <p>The two are separate keys because they are separately measured. The three
     * {@code excalibur.graph.min-*} thresholds decide which queries get answered and are fitted by
     * {@code AbstentionCalibrator.calibrate}; {@code excalibur.graph.confidence-temperature}
     * decides how strongly an answer is claimed and is fitted by
     * {@code AbstentionCalibrator.fitTemperature}. A deployment can have measured either, both or
     * neither.
     *
     * <p>A neutral temperature returns the bare policy rather than a decorator that would divide
     * every log-odds by one. Not an optimisation - it keeps a node that has not calibrated its
     * confidences from reporting, in a log line or a {@code toString}, that it has.
     */
    private static AbstentionPolicy abstentionFor(GraphConfig settings) {
        ThresholdAbstentionPolicy thresholds = new ThresholdAbstentionPolicy(
            settings.getMinScore(),
            settings.getMinSeparation(),
            settings.getMinMeanConfidence(),
            settings.getMaxAmbiguousCandidates());

        double temperature = settings.getConfidenceTemperature();
        if (temperature == CalibratedAbstentionPolicy.NEUTRAL_TEMPERATURE) {
            return thresholds;
        }
        LOGGER.info("Reported confidences scaled by a fitted temperature of {}", temperature);
        return new CalibratedAbstentionPolicy(thresholds, temperature);
    }
}
