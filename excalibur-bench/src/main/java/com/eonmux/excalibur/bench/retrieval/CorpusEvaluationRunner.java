package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.ingest.lexical.NativeChunkIndex;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.retrieval.expand.ExpandedLexicalRanker;
import com.eonmux.excalibur.retrieval.expand.LlmQueryExpander;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Reads a real corpus and scores every retrieval strategy over it.
 *
 * <h2>Two ways a strategy is run</h2>
 * <p>The strategies that need no model beyond query expansion run on a lexical index the harness
 * builds itself, from the corpus as ingestion would chunk and name it (see
 * {@link EvaluationCorpus}). No storage engine, vector segments or HTTP server are involved, so
 * the index's cost is the only cost in its row.
 *
 * <p>The strategies that need the corpus embedded, or its facts extracted into a graph, run on an
 * {@link EvaluationNode}: a real node, composed in-process, that ingests the same directory
 * through its own ingestion pipeline and answers through its own routes. Reassembling the hybrid
 * route or the graph build in the harness would measure the reassembly; see
 * {@link ModelStrategies} for what each of those strategies is. The node for an engine keeps its
 * data under {@code <indexDir>/node-<engine>}, which must be empty: a node over an earlier run's
 * data would serve a corpus that is not the one being judged.
 *
 * <h2>Which engine it measures</h2>
 * <p>The one a node ships with, {@code native}, unless told otherwise - a benchmark of an engine
 * the product does not use is a number about nothing. {@code lucene} measures the alternative and
 * {@code both} runs each in turn, which is the only way to read the two side by side: they index
 * the same corpus with the same chunking, so the difference in the reported index size is the cost
 * of Lucene keeping its own copy of every chunk's text. With a model, {@code both} ingests the
 * corpus once per engine, so it pays for embedding and extraction twice.
 *
 * <p>Not every strategy exists on both engines, and the ones that do not are reported as skipped
 * with the reason rather than left out. The native ranker checks generated terms against the corpus
 * before searching for them, so an ungated expansion is not a configuration it can be asked for;
 * the approximate vocabulary gate needs the index's whole term list, which only Lucene enumerates.
 *
 * <p>For the command line see {@link EvaluationArguments}. The qrels file is one judged query per
 * line: {@code query<TAB>id[,id...]}, where an id names a chunk or a document; every hit is
 * credited to its document (see {@link StrategyEvaluator}).
 */
public final class CorpusEvaluationRunner {

    /**
     * What one expansion call is allowed while benchmarking.
     *
     * <p>Generous on purpose: a served query bounds expansion at five seconds because a client
     * is waiting, and a benchmark that inherited that bound would record a slow endpoint as a
     * weak retrieval strategy.</p>
     */
    static final long EXPANSION_DEADLINE_MS = 120_000L;

    /** What one engine cost to build, which is half of what the comparison is about. */
    private record IndexCost(long chunks, long corpusBytes, long indexBytes, double seconds) {

        double corpusMiB() {
            return corpusBytes / (double) (1 << 20);
        }

        double indexMiB() {
            return indexBytes / (double) (1 << 20);
        }

        /** Index bytes as a share of the text indexed: what the engine adds on top of storage. */
        double overhead() {
            return corpusBytes == 0 ? 0.0 : indexBytes / (double) corpusBytes;
        }
    }

    /** Everything one engine's pass needs that does not depend on the engine. */
    private record Run(EvaluationArguments arguments, EvaluationCorpus corpus, Path corpusRoot,
                       List<StrategyEvaluator.Judged> judged, QueryExpander expander) {
    }

    private CorpusEvaluationRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println(EvaluationArguments.USAGE);
            System.exit(2);
        }
        EvaluationArguments arguments = EvaluationArguments.parse(args, System.getenv());

        List<StrategyEvaluator.Judged> judged = readQrels(arguments.qrels());
        System.out.printf("  judged queries: %d%n", judged.size());
        if (judged.isEmpty()) {
            return;
        }

        Path corpusRoot = arguments.corpus().toRealPath();
        // Refused before anything is indexed or any model is called, rather than after an hour
        // of embedding the corpus into a second copy beside the first.
        if (arguments.hasChatModel() || arguments.hasEmbeddingModel()) {
            for (LexicalEngine engine : arguments.engines()) {
                requireEmpty(arguments.indexRoot().resolve(engine.nodeDirectory()));
            }
        }
        // The chunk sizing is read from the configuration an evaluation node runs with, so the
        // harness's own index and the node's are cut from the corpus the same way. Building it
        // also validates the model arguments the way a node would refuse them.
        Config nodeConfig = EvaluationNode.configFor(arguments, arguments.engines().get(0),
            corpusRoot, false);
        EvaluationCorpus corpus = EvaluationCorpus.read(corpusRoot, nodeConfig.ingest());
        System.out.printf("  corpus: %,d documents, %,d chunks%n", corpus.documents(),
            corpus.chunks().size());

        QueryExpander expander = null;
        if (arguments.hasChatModel()) {
            // A longer deadline than a served query gets, deliberately. The node's 5s bound
            // exists because a caller is holding a socket open; nobody is holding one here, and
            // an expansion that timed out would be scored as a strategy that produced nothing
            // rather than as a call that was cut short - which would read as a result.
            expander = new LlmQueryExpander(new LLMHttpClient(LLMClientConfig.builder()
                .baseUrl(arguments.baseUrl()).chatModel(arguments.chatModel())
                .apiKey(arguments.apiKey()).build()),
                EXPANSION_DEADLINE_MS);
        }

        Run run = new Run(arguments, corpus, corpusRoot, judged, expander);
        for (LexicalEngine engine : arguments.engines()) {
            evaluate(run, engine);
        }
    }

    /** Refuses a node directory an earlier run left data in. */
    private static void requireEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException(directory + " holds an evaluation node from "
                    + "an earlier run, which would serve that run's corpus rather than this one; "
                    + "remove it or choose another index directory");
            }
        }
    }

    /** Runs the whole comparison on one engine. */
    private static void evaluate(Run run, LexicalEngine engine)
            throws IOException, InterruptedException {
        System.out.printf("%n=== %s engine ===%n", engine.id());
        Path indexRoot = run.arguments().indexRoot();
        Path indexDir = indexRoot.resolve(engine.directory());
        AtomicLong epoch = new AtomicLong(1);
        try (ChunkIndex index = open(engine, indexDir, epoch)) {
            IndexCost cost = indexCorpus(run.corpus(), index, indexDir);
            // The native index publishes at the epoch after the one it read, so nothing it just
            // wrote is visible to a query still reading at the old one. A node's tick advances
            // this; here the harness does, once, after the corpus is in.
            epoch.incrementAndGet();

            System.out.printf("  indexed %,d chunks (%.1f MiB of text) in %.1fs "
                    + "-> %.1f MiB/s, %,.0f chunks/s%n",
                cost.chunks(), cost.corpusMiB(), cost.seconds(),
                cost.corpusMiB() / cost.seconds(), cost.chunks() / cost.seconds());
            System.out.printf("  index on disk: %.1f MiB (%.2fx the text it indexes)%n",
                cost.indexMiB(), cost.overhead());

            StrategyEvaluator evaluator =
                new StrategyEvaluator(run.judged(), run.arguments().k());
            Map<RetrievalStrategy, StrategyEvaluator.Retrieval> lexical =
                strategiesFor(engine, index, run.expander());
            Map<RetrievalStrategy, StrategyResult> ran =
                new EnumMap<>(RetrievalStrategy.class);
            ran.putAll(evaluator.evaluateEach(lexical));
            Map<RetrievalStrategy, String> reasons = new EnumMap<>(RetrievalStrategy.class);
            reasons.putAll(skipReasonsFor(engine));
            if (run.expander() == null) {
                for (RetrievalStrategy strategy : List.of(RetrievalStrategy.BM25_EXPANSION,
                        RetrievalStrategy.BM25_EXPANSION_EXACT_VOCAB,
                        RetrievalStrategy.BM25_EXPANSION_APPROX_VOCAB)) {
                    reasons.putIfAbsent(strategy, ModelStrategies.NO_EXPANSION);
                }
            }

            try (ModelStrategies models = ModelStrategies.prepare(run.arguments(), engine,
                    run.corpus(), run.corpusRoot(), indexRoot.resolve(engine.nodeDirectory()))) {
                models.costs().forEach(System.out::println);
                // The ranker a node's lexical route runs with a chat model, which on the native
                // engine is the gated expansion and on Lucene the ungated one.
                StrategyEvaluator.Retrieval expanded = run.expander() == null ? null
                    : retrievalFor(index.ranker(run.expander()), index);
                ran.putAll(evaluator.evaluateEach(
                    models.firstPass(lexical.get(RetrievalStrategy.BM25), expanded)));
                ran.putAll(evaluator.evaluateEach(models.secondPass()));
                models.skipReasons().forEach(reasons::putIfAbsent);

                System.out.println(StrategyEvaluator.report(
                    StrategyEvaluator.inOrder(ran, reasons), run.arguments().k(), engine.id()));
                models.notes().forEach(System.out::println);
            }
        }
    }

    private static ChunkIndex open(LexicalEngine engine, Path directory, AtomicLong epoch)
            throws IOException {
        return switch (engine) {
            // Never compacts during a run: compaction reads surviving text back from the storage
            // engine, and this harness deliberately does not run one. Nothing is erased here, so
            // there is nothing for it to reclaim either.
            case NATIVE -> NativeChunkIndex.open(directory, epoch::get, 1,
                CompactionPlanner.standard());
            case LUCENE -> ChunkLexicalIndex.open(directory, 256.0);
        };
    }

    /**
     * The lexical strategies this engine can actually be asked for.
     *
     * <p>A strategy an engine cannot run is left out, so the evaluator reports it as skipped. A
     * row scored on a substitute would be the one thing worse than a missing row.</p>
     */
    private static Map<RetrievalStrategy, StrategyEvaluator.Retrieval> strategiesFor(
            LexicalEngine engine, ChunkIndex index, QueryExpander expander) throws IOException {
        Map<RetrievalStrategy, StrategyEvaluator.Retrieval> available =
            new EnumMap<>(RetrievalStrategy.class);
        available.put(RetrievalStrategy.BM25,
            retrievalFor(index.ranker(QueryExpander.identity()), index));
        if (expander == null) {
            return available;
        }

        if (engine == LexicalEngine.NATIVE) {
            // The native ranker checks a generated formulation's terms against the corpus before
            // searching for them, so what it runs is the gated configuration and there is no
            // ungated one to report beside it.
            available.put(RetrievalStrategy.BM25_EXPANSION_EXACT_VOCAB,
                retrievalFor(index.ranker(expander), index));
            return available;
        }

        com.eonmux.excalibur.retrieval.lexical.LexicalIndex lucene = ((ChunkLexicalIndex) index).index();
        available.put(RetrievalStrategy.BM25_EXPANSION,
            retrievalFor(new ExpandedLexicalRanker(lucene, expander), index));
        available.put(RetrievalStrategy.BM25_EXPANSION_EXACT_VOCAB,
            retrievalFor(new ExpandedLexicalRanker(lucene, expander, "expansion+vocab-exact",
                new com.eonmux.excalibur.retrieval.expand.LuceneVocabulary(lucene)), index));
        available.put(RetrievalStrategy.BM25_EXPANSION_APPROX_VOCAB,
            retrievalFor(new ExpandedLexicalRanker(lucene, expander, "expansion+vocab-cuckoo",
                buildCuckoo(lucene)), index));
        return available;
    }

    /**
     * Why an engine has no answer for a strategy, where the reason is the engine and not the setup.
     *
     * <p>Without these the table says "no retrieval function supplied" for a configuration that
     * cannot exist, which reads as an omission a better-configured run would fill in.</p>
     */
    private static Map<RetrievalStrategy, String> skipReasonsFor(LexicalEngine engine) {
        if (engine != LexicalEngine.NATIVE) {
            return Map.of();
        }
        return Map.of(
            RetrievalStrategy.BM25_EXPANSION,
            "the native ranker checks generated terms against the corpus before searching for "
                + "them, so an ungated expansion is not a configuration it can run",
            RetrievalStrategy.BM25_EXPANSION_APPROX_VOCAB,
            "the native index does not enumerate its vocabulary, so there is nothing to build an "
                + "approximate gate from");
    }

    /**
     * Builds a Cuckoo filter over the corpus vocabulary, and reports what it cost.
     *
     * <p>Sized from the term count so the load factor stays in the range where insertion does not
     * start failing; a filter that refuses terms would under-report vocabulary, which is the one
     * error direction that loses good expansions.</p>
     */
    private static com.eonmux.excalibur.retrieval.expand.CuckooVocabulary buildCuckoo(
            com.eonmux.excalibur.retrieval.lexical.LexicalIndex index) throws IOException {
        long start = System.nanoTime();
        java.util.Set<String> vocabulary = new java.util.HashSet<>();
        try (com.eonmux.excalibur.retrieval.lexical.LexicalIndex.Lease lease = index.acquire()) {
            for (org.apache.lucene.index.LeafReaderContext leaf
                    : lease.searcher().getIndexReader().leaves()) {
                org.apache.lucene.index.Terms terms = leaf.reader()
                    .terms(com.eonmux.excalibur.retrieval.lexical.LexicalIndex.FIELD_TEXT);
                if (terms == null) {
                    continue;
                }
                org.apache.lucene.index.TermsEnum cursor = terms.iterator();
                for (org.apache.lucene.util.BytesRef t = cursor.next(); t != null;
                        t = cursor.next()) {
                    vocabulary.add(t.utf8ToString());
                }
            }
        }
        com.eonmux.excalibur.filter.CuckooFilter filter =
            com.eonmux.excalibur.filter.CuckooFilter.create(Math.max(1024, vocabulary.size() * 2L), 13);
        com.eonmux.excalibur.retrieval.expand.CuckooVocabulary gate =
            new com.eonmux.excalibur.retrieval.expand.CuckooVocabulary(filter);
        int rejected = 0;
        for (String term : vocabulary) {
            if (!gate.add(term)) {
                rejected++;
            }
        }
        System.out.printf("  cuckoo vocabulary: %,d terms in %.2fs, %d rejected%n",
            vocabulary.size(), (System.nanoTime() - start) / 1e9, rejected);
        return gate;
    }

    /** Indexes every chunk of the corpus, reporting what it cost. */
    private static IndexCost indexCorpus(EvaluationCorpus corpus, ChunkIndex index, Path indexDir)
            throws IOException {
        long start = System.nanoTime();
        for (EvaluationCorpus.Chunk chunk : corpus.chunks()) {
            index.add(chunk.id(), chunk.text());
        }
        index.refresh();
        double seconds = (System.nanoTime() - start) / 1e9;
        // Measured after the refresh, so what is counted is what a node would be left holding
        // rather than what was still buffered when the clock stopped.
        return new IndexCost(corpus.chunks().size(), corpus.characters(), bytesUnder(indexDir),
            seconds);
    }

    /** Every byte the engine wrote, which is the cost the quality number has to be read against. */
    private static long bytesUnder(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        long total = 0;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                total += Files.size(file);
            }
        }
        return total;
    }

    /** Adapts a ranker to the evaluator, resolving engine ids back to chunk ids. */
    private static StrategyEvaluator.Retrieval retrievalFor(Ranker ranker, ChunkIndex index) {
        return (query, limit) -> {
            RankedList ranked = ranker.rank(RetrievalQuery.ofText(query), limit);
            List<String> out = new ArrayList<>(ranked.documents().size());
            for (ScoredDocument document : ranked.documents()) {
                try {
                    String chunkId = index.chunkIdOf(document.documentId());
                    if (chunkId != null) {
                        out.add(chunkId);
                    }
                } catch (IOException e) {
                    // A hit that cannot be resolved is not a hit anyone can use.
                }
            }
            return out;
        };
    }

    private static List<StrategyEvaluator.Judged> readQrels(Path file) throws IOException {
        List<StrategyEvaluator.Judged> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String query = line.substring(0, tab).trim();
            List<String> relevant = List.of(line.substring(tab + 1).trim().split(","));
            if (!query.isEmpty() && !relevant.isEmpty()) {
                out.add(new StrategyEvaluator.Judged(query, relevant));
            }
        }
        return out;
    }
}
