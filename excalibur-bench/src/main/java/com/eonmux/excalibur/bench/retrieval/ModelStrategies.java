package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The strategies that need a model, run on an evaluation node for one lexical engine.
 *
 * <h2>What each one is</h2>
 * <ul>
 *   <li>{@link RetrievalStrategy#BM25_VECTOR}: the node's {@code /v1/query} hybrid - vector and
 *       BM25 run concurrently and fused by reciprocal rank - with expansion of the hybrid's BM25
 *       half off, as {@code excalibur.query.expand-hybrid-queries} ships.</li>
 *   <li>{@link RetrievalStrategy#BM25_EXPANSION_VECTOR}: the same route with that setting on, on
 *       a node restarted over the same data rather than one ingested again.</li>
 *   <li>{@link RetrievalStrategy#BM25_GRAPH}: BM25 fused with the documents the graph route's
 *       answer cites (see {@link GraphCitations}).</li>
 *   <li>{@link RetrievalStrategy#BM25_EXPANSION_GRAPH}: expanded BM25 - the ranker a node's
 *       lexical route runs with a chat model - fused with the graph's citations.</li>
 *   <li>{@link RetrievalStrategy#FULL_HYBRID}: the expanded hybrid fused with the graph's
 *       citations.</li>
 * </ul>
 *
 * <p>No route in the product fuses the graph with anything: the graph answers with facts and the
 * other routes with passages. The graph strategies are therefore the one place the harness
 * composes something itself, and it composes it from production parts - the fusion is the one
 * the hybrid route runs, with the node's rank offset, and the ranking being extended is read at
 * the depth the hybrid route reads each of its signals at. Both sides are reduced to documents
 * before fusing, because a document is what the graph can cite; the ranking being extended goes
 * first, so a tie keeps its order.
 *
 * <h2>When one cannot run</h2>
 * <p>Every strategy that cannot run is reported as skipped with the reason: the argument that was
 * not supplied, or what went wrong on the way - a node that did not start, an ingest that did not
 * complete, a graph build that published nothing. A strategy scored on a node that holds part of
 * the corpus would be a weaker number for a reason that has nothing to do with the strategy.
 */
final class ModelStrategies implements AutoCloseable {

    static final String NO_EMBEDDINGS =
        "no embedded corpus: needs a base URL and an embedding model (arguments 5 and 8)";
    static final String NO_GRAPH =
        "no graph: needs a base URL and a chat model to extract facts (arguments 5 and 6)";
    static final String NO_EXPANSION =
        "no query expansion: needs a base URL and a chat model (arguments 5 and 6)";

    private static final String HYBRID = "hybrid";

    private final EvaluationArguments arguments;
    private final LexicalEngine engine;
    private final EvaluationCorpus corpus;
    private final Path corpusRoot;
    private final Path dataDir;
    private final Map<RetrievalStrategy, String> reasons = new EnumMap<>(RetrievalStrategy.class);
    private final Map<RetrievalStrategy, Map<String, AtomicInteger>> modes =
        new ConcurrentHashMap<>();
    private final Map<RetrievalStrategy, AtomicInteger> withoutVectors = new ConcurrentHashMap<>();
    private final List<String> costs = new ArrayList<>();

    private EvaluationNode node;
    private boolean ingested;
    private boolean graphBuilt;

    private ModelStrategies(EvaluationArguments arguments, LexicalEngine engine,
                            EvaluationCorpus corpus, Path corpusRoot, Path dataDir) {
        this.arguments = arguments;
        this.engine = engine;
        this.corpus = corpus;
        this.corpusRoot = corpusRoot;
        this.dataDir = dataDir;
    }

    /**
     * Starts a node, ingests the corpus into it and, with a chat model, builds its graph.
     *
     * <p>Nothing is thrown for a node that cannot be brought up: the strategies it would have
     * served are reported as skipped with what went wrong, and the ones that need no node are
     * still measured.
     *
     * @param corpusRoot The corpus directory, as its real path
     * @param dataDir    The node's data directory, empty or absent
     */
    static ModelStrategies prepare(EvaluationArguments arguments, LexicalEngine engine,
                                   EvaluationCorpus corpus, Path corpusRoot, Path dataDir)
            throws InterruptedException {
        ModelStrategies strategies =
            new ModelStrategies(arguments, engine, corpus, corpusRoot, dataDir);
        strategies.recordMissingArguments();
        if (arguments.hasChatModel() || arguments.hasEmbeddingModel()) {
            strategies.bringUp();
        }
        return strategies;
    }

    private void recordMissingArguments() {
        if (!arguments.hasEmbeddingModel()) {
            reasons.put(RetrievalStrategy.BM25_VECTOR, NO_EMBEDDINGS);
            reasons.put(RetrievalStrategy.BM25_EXPANSION_VECTOR, NO_EMBEDDINGS);
            reasons.put(RetrievalStrategy.FULL_HYBRID, NO_EMBEDDINGS);
        }
        if (!arguments.hasChatModel()) {
            reasons.putIfAbsent(RetrievalStrategy.BM25_EXPANSION_VECTOR, NO_EXPANSION);
            reasons.put(RetrievalStrategy.BM25_GRAPH, NO_GRAPH);
            reasons.put(RetrievalStrategy.BM25_EXPANSION_GRAPH, NO_GRAPH);
            reasons.putIfAbsent(RetrievalStrategy.FULL_HYBRID, NO_GRAPH);
        }
    }

    /** Why a strategy did not run, where that was this class's to decide. */
    Map<RetrievalStrategy, String> skipReasons() {
        return Map.copyOf(reasons);
    }

    /** What the node cost, for printing beside the table. */
    List<String> costs() {
        return List.copyOf(costs);
    }

    private void bringUp() throws InterruptedException {
        try {
            node = EvaluationNode.start(
                EvaluationNode.configFor(arguments, engine, corpusRoot, false), dataDir);
        } catch (IOException | RuntimeException e) {
            skipEverything("the evaluation node did not start: " + e.getMessage());
            return;
        }
        try {
            EvaluationNode.Ingestion ingestion = node.ingest(EvaluationCorpus.SOURCE_ID,
                corpusRoot);
            if (ingestion.failed()) {
                skipEverything("the evaluation node's ingest did not complete: "
                    + ingestion.error());
                return;
            }
            ingested = true;
            // No size beside it, unlike the harness's own index: a node reserves its storage up
            // front, so the size of its directory is the reservation rather than the use. The
            // time is the cost that matters here - it is dominated by the model calls.
            costs.add(String.format("  evaluation node: ingested %,d of %,d chunks in %.1fs "
                    + "(%s%s)",
                ingestion.chunks(), corpus.chunks().size(), ingestion.seconds(),
                arguments.hasEmbeddingModel() ? "embedded" : "not embedded",
                arguments.hasChatModel() ? ", facts extracted" : ""));
            if (arguments.hasChatModel()) {
                buildGraph();
            }
        } catch (IOException e) {
            skipEverything("the evaluation node could not be reached: " + e.getMessage());
        }
    }

    private void buildGraph() throws IOException, InterruptedException {
        EvaluationNode.Build build = node.buildGraph();
        if (build.servable()) {
            graphBuilt = true;
            costs.add(String.format("  graph built: %,d facts, %,d entities in %.1fs",
                build.edges(), build.entities(), build.seconds()));
            return;
        }
        String reason = "failed".equals(build.state())
            ? "no graph: the build failed: " + build.problem()
            : "no graph: the build published nothing, because no facts were extracted from the "
                + "corpus" + (build.problem() == null ? "" : " (" + build.problem() + ")");
        costs.add("  graph not built: " + reason);
        reasons.putIfAbsent(RetrievalStrategy.BM25_GRAPH, reason);
        reasons.putIfAbsent(RetrievalStrategy.BM25_EXPANSION_GRAPH, reason);
        reasons.putIfAbsent(RetrievalStrategy.FULL_HYBRID, reason);
    }

    private void skipEverything(String reason) {
        costs.add("  " + reason);
        for (RetrievalStrategy strategy : List.of(RetrievalStrategy.BM25_VECTOR,
                RetrievalStrategy.BM25_EXPANSION_VECTOR, RetrievalStrategy.BM25_GRAPH,
                RetrievalStrategy.BM25_EXPANSION_GRAPH, RetrievalStrategy.FULL_HYBRID)) {
            reasons.putIfAbsent(strategy, reason);
        }
    }

    /**
     * The strategies the node serves as first started: with the hybrid's BM25 half unexpanded.
     *
     * @param bm25     BM25 as the harness runs it on the same engine
     * @param expanded Expanded BM25 as a node's lexical route runs it, or null without a chat
     *                 model
     */
    Map<RetrievalStrategy, StrategyEvaluator.Retrieval> firstPass(
            StrategyEvaluator.Retrieval bm25, StrategyEvaluator.Retrieval expanded) {
        Map<RetrievalStrategy, StrategyEvaluator.Retrieval> out =
            new EnumMap<>(RetrievalStrategy.class);
        if (!ingested) {
            return out;
        }
        if (arguments.hasEmbeddingModel()) {
            out.put(RetrievalStrategy.BM25_VECTOR, hybrid(RetrievalStrategy.BM25_VECTOR));
        }
        if (graphBuilt) {
            out.put(RetrievalStrategy.BM25_GRAPH, withGraph(bm25));
            if (expanded != null) {
                out.put(RetrievalStrategy.BM25_EXPANSION_GRAPH, withGraph(expanded));
            }
        }
        return out;
    }

    /**
     * Restarts the node with {@code excalibur.query.expand-hybrid-queries} on, and returns the
     * strategies that need it.
     *
     * <p>A restart rather than a second node: the setting changes how a question is asked, not
     * what was ingested, and ingesting again would pay for every embedding and extraction twice.
     * A restarted node does not crawl its sources again, so it serves the same data.
     */
    Map<RetrievalStrategy, StrategyEvaluator.Retrieval> secondPass() throws InterruptedException {
        Map<RetrievalStrategy, StrategyEvaluator.Retrieval> out =
            new EnumMap<>(RetrievalStrategy.class);
        if (!ingested || !arguments.hasEmbeddingModel() || !arguments.hasChatModel()) {
            return out;
        }
        node.close();
        node = null;
        try {
            node = EvaluationNode.start(
                EvaluationNode.configFor(arguments, engine, corpusRoot, true), dataDir);
        } catch (IOException | RuntimeException e) {
            String reason = "the evaluation node did not restart with expansion on: "
                + e.getMessage();
            reasons.putIfAbsent(RetrievalStrategy.BM25_EXPANSION_VECTOR, reason);
            reasons.putIfAbsent(RetrievalStrategy.FULL_HYBRID, reason);
            return out;
        }
        out.put(RetrievalStrategy.BM25_EXPANSION_VECTOR,
            hybrid(RetrievalStrategy.BM25_EXPANSION_VECTOR));
        if (graphBuilt) {
            out.put(RetrievalStrategy.FULL_HYBRID,
                withGraph(hybrid(RetrievalStrategy.FULL_HYBRID)));
        }
        return out;
    }

    /**
     * Answers that came from fewer signals than the strategy is named for.
     *
     * <p>The hybrid route answers from one signal when the other fails or runs out of time, and
     * says so. Such an answer is still the route's answer and is scored as one, but a row built
     * from them is not a measurement of the hybrid, and the table has to say that.
     *
     * <p>A route can also call an answer hybrid when the vector signal found none of its results:
     * each result names the signals that found it. Those answers are BM25's under the hybrid's
     * name, so the table counts them too.
     */
    List<String> notes() {
        List<String> out = new ArrayList<>();
        for (RetrievalStrategy strategy : RetrievalStrategy.values()) {
            Map<String, AtomicInteger> counts = modes.get(strategy);
            if (counts == null) {
                continue;
            }
            int total = 0;
            List<String> fewer = new ArrayList<>();
            for (Map.Entry<String, AtomicInteger> entry : counts.entrySet()) {
                total += entry.getValue().get();
                if (!HYBRID.equals(entry.getKey())) {
                    fewer.add(entry.getKey() + " " + entry.getValue().get());
                }
            }
            if (!fewer.isEmpty()) {
                out.add(String.format("  note: %s - %d of its %d answers came from fewer "
                    + "signals than the hybrid (%s)", strategy,
                    total - counts.getOrDefault(HYBRID, new AtomicInteger()).get(), total,
                    String.join(", ", fewer)));
            }
            AtomicInteger unvectored = withoutVectors.get(strategy);
            if (unvectored != null) {
                out.add(String.format("  note: %s - the vector signal found none of the results "
                    + "of %d of its %d answers", strategy, unvectored.get(), total));
            }
        }
        return out;
    }

    /** The node's {@code /v1/query}, recording which signals each answer came from. */
    private StrategyEvaluator.Retrieval hybrid(RetrievalStrategy strategy) {
        EvaluationNode serving = node;
        return (query, limit) -> {
            EvaluationNode.Answer answer = serving.query(query, limit);
            modes.computeIfAbsent(strategy, s -> new ConcurrentHashMap<>())
                .computeIfAbsent(String.valueOf(answer.retrieval()), m -> new AtomicInteger())
                .incrementAndGet();
            if (!answer.vector()) {
                withoutVectors.computeIfAbsent(strategy, s -> new AtomicInteger())
                    .incrementAndGet();
            }
            return answer.ids();
        };
    }

    /** A ranking fused with the documents the graph's answer to the same question cites. */
    private StrategyEvaluator.Retrieval withGraph(StrategyEvaluator.Retrieval base) {
        EvaluationNode serving = node;
        int rrfK = serving.config().query().getRrfK();
        int overFetch = serving.config().query().getPerSignalOverFetch();
        return (query, limit) -> {
            List<String> documents = base.search(query, limit * overFetch).stream()
                .map(ChunkIdentifiers::documentKeyOfChunk)
                .distinct()
                .toList();
            List<String> cited = serving.askGraph(query)
                .map(result -> GraphCitations.documentsCitedBy(result, corpus))
                .orElse(List.of());
            return RankFusion.fuse(limit, rrfK,
                new RankFusion.Ranking("base", documents),
                new RankFusion.Ranking("graph", cited));
        };
    }

    @Override
    public void close() {
        if (node != null) {
            node.close();
            node = null;
        }
    }
}
