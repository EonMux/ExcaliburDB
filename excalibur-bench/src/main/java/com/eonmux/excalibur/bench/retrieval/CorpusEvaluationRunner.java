package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.ingest.lexical.NativeChunkIndex;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Indexes a real corpus and scores the retrieval strategies over it.
 *
 * <p>Deliberately built without the HTTP server, the storage engine or the HNSW segments: those
 * are not what a retrieval comparison measures, and running them makes the number harder to read
 * rather than more realistic. What is real here is the corpus, the chunking, the lexical index and
 * the query model.
 *
 * <h2>Which engine it measures</h2>
 * <p>The one a node ships with, {@code native}, unless told otherwise - a benchmark of an engine
 * the product does not use is a number about nothing. {@code lucene} measures the alternative and
 * {@code both} runs each in turn, which is the only way to read the two side by side: they index
 * the same corpus with the same chunking, so the difference in the reported index size is the cost
 * of Lucene keeping its own copy of every chunk's text.
 *
 * <p>Not every strategy exists on both engines, and the ones that do not are reported as skipped
 * with the reason rather than left out. The native ranker checks generated terms against the corpus
 * before searching for them, so an ungated expansion is not a configuration it can be asked for;
 * the approximate vocabulary gate needs the index's whole term list, which only Lucene enumerates.
 *
 * <pre>
 * usage: CorpusEvaluationRunner &lt;corpusDir&gt; &lt;indexDir&gt; &lt;qrelsFile&gt; [k] [chatBaseUrl]
 *                              [chatModel] [native|lucene|both]
 * </pre>
 *
 * <p>The optional arguments are positional, so {@code -} stands in for one being skipped:
 * {@code ... 10 - - both} measures both engines with no query expansion.</p>
 *
 * <p>The qrels file is one judged query per line: {@code query<TAB>chunkId[,chunkId...]}.
 */
public final class CorpusEvaluationRunner {

    /**
     * What one expansion call is allowed while benchmarking.
     *
     * <p>Generous on purpose: a served query bounds expansion at five seconds because a client
     * is waiting, and a benchmark that inherited that bound would record a slow endpoint as a
     * weak retrieval strategy.</p>
     */
    private static final long EXPANSION_DEADLINE_MS = 120_000L;

    /** Chunk size, matching the ingestion pipeline's own target. */
    private static final int CHUNK_CHARS = 2048;

    /** Which lexical engine indexed the corpus for one pass of the comparison. */
    private enum Engine {
        NATIVE("native", "chunk-index"),
        LUCENE("lucene", "lucene-chunks");

        private final String id;
        private final String directory;

        Engine(String id, String directory) {
            this.id = id;
            this.directory = directory;
        }
    }

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

    private CorpusEvaluationRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: CorpusEvaluationRunner <corpusDir> <indexDir> <qrels> "
                + "[k] [chatBaseUrl] [chatModel] [native|lucene|both]\n"
                + "       optional arguments are positional; '-' skips one");
            System.exit(2);
        }
        Path corpus = Path.of(args[0]);
        Path indexRoot = Path.of(args[1]);
        Path qrelsFile = Path.of(args[2]);
        int k = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        // Blank is absent, not empty: these are positional, so naming an engine without a chat
        // model means passing placeholders for the two in between. Taking "" as a base URL fails
        // deep inside the client with a message about configuration the caller never set.
        String chatBaseUrl = optional(args, 4);
        String chatModel = optional(args, 5);
        String engineArg = optional(args, 6);
        List<Engine> engines = enginesFrom(engineArg == null ? Engine.NATIVE.id : engineArg);

        List<StrategyEvaluator.Judged> judged = readQrels(qrelsFile);
        System.out.printf("  judged queries: %d%n", judged.size());
        if (judged.isEmpty()) {
            return;
        }

        QueryExpander expander = null;
        if (chatBaseUrl != null && chatModel != null) {
            // A longer deadline than a served query gets, deliberately. The node's 5s bound
            // exists because a caller is holding a socket open; nobody is holding one here, and
            // an expansion that timed out would be scored as a strategy that produced nothing
            // rather than as a call that was cut short - which would read as a result.
            expander = new LlmQueryExpander(new LLMHttpClient(
                com.eonmux.excalibur.llm.client.LLMClientConfig.builder()
                    .baseUrl(chatBaseUrl).chatModel(chatModel).apiKey("none").build()),
                EXPANSION_DEADLINE_MS);
        }

        for (Engine engine : engines) {
            evaluate(engine, corpus, indexRoot.resolve(engine.directory), judged, k, expander);
        }
    }

    /** Runs the whole comparison on one engine. */
    private static void evaluate(Engine engine, Path corpus, Path indexDir,
                                 List<StrategyEvaluator.Judged> judged, int k,
                                 QueryExpander expander) throws IOException {
        System.out.printf("%n=== %s engine ===%n", engine.id);
        AtomicLong epoch = new AtomicLong(1);
        try (ChunkIndex index = open(engine, indexDir, epoch)) {
            IndexCost cost = indexCorpus(corpus, index, indexDir);
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

            List<StrategyResult> results = new StrategyEvaluator(judged, k)
                .evaluateAll(strategiesFor(engine, index, expander), skipReasonsFor(engine));
            System.out.println(StrategyEvaluator.report(results, k, engine.id));
        }
    }

    /**
     * A positional argument that was actually supplied.
     *
     * <p>Blank and {@code -} both mean "not supplied", because these are positional: naming an
     * engine without a chat model means passing placeholders for the two arguments in between, and
     * a runner invoked through Gradle cannot pass an empty one. Without this, {@code ''} arrives as
     * two literal quote characters and fails deep inside the LLM client with a message about
     * configuration the caller never set.</p>
     */
    private static String optional(String[] args, int index) {
        if (args.length <= index) {
            return null;
        }
        String value = args[index].trim();
        return value.isEmpty() || "-".equals(value) || "''".equals(value) || "\"\"".equals(value)
            ? null : value;
    }

    /** The engines a run was asked for. */
    private static List<Engine> enginesFrom(String argument) {
        String value = argument.trim().toLowerCase(Locale.ROOT);
        if ("both".equals(value)) {
            return List.of(Engine.NATIVE, Engine.LUCENE);
        }
        for (Engine engine : Engine.values()) {
            if (engine.id.equals(value)) {
                return List.of(engine);
            }
        }
        throw new IllegalArgumentException(
            "unknown engine '" + argument + "'; expected native, lucene or both");
    }

    private static ChunkIndex open(Engine engine, Path directory, AtomicLong epoch)
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
     * The strategies this engine can actually be asked for.
     *
     * <p>A strategy an engine cannot run is left out, so the evaluator reports it as skipped. A
     * row scored on a substitute would be the one thing worse than a missing row.</p>
     */
    private static Map<RetrievalStrategy, StrategyEvaluator.Retrieval> strategiesFor(
            Engine engine, ChunkIndex index, QueryExpander expander) throws IOException {
        Map<RetrievalStrategy, StrategyEvaluator.Retrieval> available =
            new EnumMap<>(RetrievalStrategy.class);
        available.put(RetrievalStrategy.BM25,
            retrievalFor(index.ranker(QueryExpander.identity()), index));
        if (expander == null) {
            return available;
        }

        if (engine == Engine.NATIVE) {
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
    private static Map<RetrievalStrategy, String> skipReasonsFor(Engine engine) {
        if (engine != Engine.NATIVE) {
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
        org.apache.lucene.search.IndexSearcher searcher = index.searcher();
        for (org.apache.lucene.index.LeafReaderContext leaf
                : searcher.getIndexReader().leaves()) {
            org.apache.lucene.index.Terms terms =
                leaf.reader().terms(com.eonmux.excalibur.retrieval.lexical.LexicalIndex.FIELD_TEXT);
            if (terms == null) {
                continue;
            }
            org.apache.lucene.index.TermsEnum cursor = terms.iterator();
            for (org.apache.lucene.util.BytesRef t = cursor.next(); t != null; t = cursor.next()) {
                vocabulary.add(t.utf8ToString());
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

    /** Chunks and indexes every file under a directory, reporting what it cost. */
    private static IndexCost indexCorpus(Path corpus, ChunkIndex index, Path indexDir)
            throws IOException {
        long start = System.nanoTime();
        long bytes = 0;
        long chunks = 0;
        try (Stream<Path> files = Files.walk(corpus)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String text;
                try {
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException e) {
                    continue;
                }
                bytes += text.length();
                String documentKey = "corpus:" + corpus.relativize(file);
                for (int offset = 0, n = 0; offset < text.length(); offset += CHUNK_CHARS, n++) {
                    index.add(documentKey + ":chunk:" + n,
                        text.substring(offset, Math.min(text.length(), offset + CHUNK_CHARS)));
                    chunks++;
                }
            }
        }
        index.refresh();
        double seconds = (System.nanoTime() - start) / 1e9;
        // Measured after the refresh, so what is counted is what a node would be left holding
        // rather than what was still buffered when the clock stopped.
        return new IndexCost(chunks, bytes, bytesUnder(indexDir), seconds);
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
