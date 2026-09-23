package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.core.memory.ForeignMemoryBootstrap;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.source.InlineSource;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.HnswInitializer;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The embedded (in-process) usage example from USAGE.md, compiled and run.
 *
 * <p>USAGE.md's "Programmatic Usage (JVM Embedded)" section shows how an application opens a
 * data directory, ingests documents and queries them without the HTTP server. The body of
 * {@link #ingestAndQuery(Path, String)} is that example: the documentation reproduces it line for
 * line, so this test is what keeps the published snippet compiling against the current public
 * API and producing an answer. A change that breaks this test breaks every embedder who copied
 * the snippet, and the documentation has to change with it; {@link EmbeddedUsageDocumentationTest}
 * fails until it does.</p>
 *
 * <p>It lives in the {@code embeddedExample} source set rather than beside the other integration
 * tests because that source set's classpath is only the seven coordinates USAGE.md tells an
 * embedder to declare. Compiled there, it also proves that list is enough.</p>
 *
 * <p>The example uses only public constructors and methods of the library modules, assembled in
 * the order the server's own bootstrap uses:</p>
 * <ol>
 *   <li>{@link ForeignMemoryBootstrap#ensureInitialized()} before anything can start a thread;</li>
 *   <li>a {@link StorageEngine} on the data directory, with an {@link HnswInitializer} installed
 *       before {@link StorageEngine#start()} so every segment carries a vector index;</li>
 *   <li>an {@link IndexOrchestrator}, which owns the visibility clock the engine, ingestion and
 *       queries share and runs the worker that integrates written vectors into each segment's
 *       HNSW graph;</li>
 *   <li>an {@link IngestionOrchestrator}, which chunks, embeds, writes and publishes documents
 *       atomically; {@link IngestionOrchestrator#ingestNow} returns once they are published;</li>
 *   <li>a {@link QueryPipeline} over the same engine, clock and document visibility, answering
 *       {@link QueryPipeline#executeSync} with a vector the same embedding client produced.</li>
 * </ol>
 *
 * <p>The one thing the test substitutes is the embedding provider. {@link EmbeddingClient} speaks
 * the OpenAI-compatible {@code POST /v1/embeddings} protocol, so the test points it at
 * {@link FakeEmbeddingServer}, an in-process endpoint that embeds text deterministically as a
 * hashed bag of words. The example itself is unchanged: an embedder passes the base URL of a real
 * provider (OpenAI, Ollama, a local inference server) where the test passes the fake's.</p>
 */
@DisplayName("Embedded usage example (USAGE.md)")
class EmbeddedUsageExampleTest {

    /** Width of the vectors the fake endpoint returns; the example configures the same width. */
    private static final int DIMENSION = 1536;

    @Test
    @Timeout(120)
    @DisplayName("documents ingested in-process are returned by an in-process query")
    void embeddedIngestionIsFoundByEmbeddedQuery(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        List<QueryResultItem> results;
        try (FakeEmbeddingServer embeddings = FakeEmbeddingServer.start(DIMENSION)) {
            results = ingestAndQuery(dataDir, embeddings.baseUrl());
        }

        assertThat(results)
            .as("the query shares most of its words with one document and nothing with another")
            .isNotEmpty();
        assertThat(results.getFirst().documentIdStr())
            .as("the closest result is the chunk about tiering, named as ingestion named it")
            .isEqualTo("handbook:tiering.txt:chunk:0");
        assertThat(results)
            .extracting(QueryResultItem::documentIdStr)
            .as("each chunk is returned once, under the identifier ingestion assigned")
            .doesNotHaveDuplicates()
            .allSatisfy(id -> assertThat(id).startsWith("handbook:").contains(":chunk:"));
    }

    /**
     * Opens {@code dataDir}, ingests three documents and returns the results of one query.
     *
     * <p>This method body is the USAGE.md example. Keep it readable as documentation: no test
     * scaffolding, no assertions, nothing a reader would have to strip out.</p>
     *
     * @param dataDir           An existing directory the engine keeps its files in
     * @param embeddingEndpoint Base URL of an OpenAI-compatible embeddings API
     * @return The ranked results, closest first
     * @throws Exception if the engine cannot be opened or a document cannot be ingested
     */
    static List<QueryResultItem> ingestAndQuery(Path dataDir, String embeddingEndpoint)
            throws Exception {
        // First statement, before anything can start a thread.
        ForeignMemoryBootstrap.ensureInitialized();

        // The embedding width and the distance metric are properties of the corpus: ingestion and
        // queries must use the same model, width and metric for the lifetime of dataDir.
        int dimension = 1536;
        DistanceFunction metric = CosineSimilarity.getInstance();
        EmbeddingClient embeddingClient = new EmbeddingClient(LLMClientConfig.builder()
            .baseUrl(embeddingEndpoint)                  // e.g. "https://api.openai.com"
            .apiKey(System.getenv().getOrDefault("OPENAI_API_KEY", ""))
            .embeddingModel("text-embedding-3-small")
            .embeddingDimension(dimension)
            .build());

        try (StorageEngine storage = new StorageEngine(dataDir)) {
            // Installed before start(), so every segment start() opens or creates has a graph.
            storage.setHnswInitializer(new HnswInitializer(HnswConfigFactory.defaults(), metric,
                dimension, HnswInitializer.DEFAULT_MAX_NODES, storage.arena()));
            storage.start();

            try (IndexOrchestrator indexing = new IndexOrchestrator(storage.getCatalog(), storage)) {
                // One visibility clock for storage, ingestion and queries.
                storage.setVisibilityTracker(indexing.getVisibilityTracker());
                indexing.syncSegmentsFromStorage();
                indexing.start();
                storage.startLifecycleManager();

                DocumentVisibilityTracker documents = new DocumentVisibilityTracker(
                    new DocumentRegistry(), indexing.getVisibilityTracker());
                IngestionOrchestrator ingestion = new IngestionOrchestrator(
                    new TextChunker(), embeddingClient, storage, indexing,
                    sourceId -> new IngestionOrchestrator.IngestionStatus() {
                        @Override
                        public void update(String state, double progress, long ingested,
                                           long pending) {
                        }

                        @Override
                        public void setSkipped(long skipped) {
                        }

                        @Override
                        public void setDisallowed(long disallowed) {
                        }

                        @Override
                        public void setError(String error) {
                            System.err.println(sourceId + ": " + error);
                        }
                    },
                    documents);

                QueryPipeline pipeline = new QueryPipeline(
                    storage,
                    indexing.getVisibilityTracker(),
                    new DocumentVisibilityTrackerAdapter(documents),
                    segmentId -> {
                        Segment segment = storage.getSegment(segmentId);
                        return segment != null ? segment.getHnswGraph() : null;
                    },
                    metric);

                try {
                    // Ingest: chunk, embed, write, publish. Returns once the documents are
                    // published; results name the chunk that matched,
                    // "<sourceId>:<documentId>:chunk:<n>".
                    ingestion.ingestNow(InlineSource.of("handbook", List.of(
                        text("tiering.txt",
                            "Cold segments are moved to cheaper disks by the tiering policy."),
                        text("hnsw.txt",
                            "The HNSW graph links every vector to its nearest neighbours."),
                        text("backup.txt",
                            "Backups copy the write log and the catalog to object storage."))));

                    // Query: embed the question with the SAME client, then search.
                    String queryText = "which policy moves cold segments to cheaper disks";
                    QueryRequest request = QueryRequest.builder()
                        .queryText(queryText)
                        .queryVector(embeddingClient.embed(queryText))
                        .topK(3)
                        .timeoutMs(5000)
                        .includePartialSegments(true)
                        .enableLlmScoring(false)
                        .build();
                    QueryResponse response = pipeline.executeSync(request, new CancellationToken());

                    response.results().forEach(item ->
                        System.out.println(item.documentIdStr() + " -> " + item.score()));
                    return response.results();
                } finally {
                    // Stop taking work before the storage underneath it closes.
                    pipeline.shutdown();
                    ingestion.shutdown(Duration.ofSeconds(30));
                }
            }
        }
    }

    /**
     * One plain-text document for an {@link InlineSource}.
     *
     * @param documentId The document's identifier within its source
     * @param content    Its text
     * @return The document
     */
    static InlineSource.Document text(String documentId, String content) {
        return new InlineSource.Document(documentId, content.getBytes(StandardCharsets.UTF_8),
            "text/plain", 0L);
    }

    /**
     * An in-process OpenAI-compatible embeddings endpoint that needs no model.
     *
     * <p>Serves {@code POST /v1/embeddings} for both request shapes {@link EmbeddingClient} sends,
     * a single {@code "input"} string and an array of them, and answers in the OpenAI response
     * schema. A text is embedded as a hashed bag of lower-cased words, L2-normalised: texts that
     * share words point in similar directions, so a query ranks the documents it overlaps with
     * first, and the same text always produces the same vector.</p>
     */
    static final class FakeEmbeddingServer implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor;
        private final int dimension;

        private FakeEmbeddingServer(HttpServer server, ExecutorService executor, int dimension) {
            this.server = server;
            this.executor = executor;
            this.dimension = dimension;
        }

        /**
         * Starts an endpoint on an ephemeral loopback port.
         *
         * @param dimension Width of every vector it returns
         * @return The running endpoint
         * @throws IOException if no port can be bound
         */
        static FakeEmbeddingServer start(int dimension) throws IOException {
            HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            FakeEmbeddingServer fake = new FakeEmbeddingServer(server, executor, dimension);
            server.createContext("/v1/embeddings", exchange -> {
                try (exchange) {
                    String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                    byte[] response = fake.respond(inputs(body))
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(response);
                    }
                }
            });
            server.setExecutor(executor);
            server.start();
            return fake;
        }

        /**
         * @return The base URL to configure {@link EmbeddingClient} with
         */
        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }

        /**
         * Builds the OpenAI response body for a batch of texts, in request order.
         *
         * @param texts The texts to embed
         * @return {@code {"data":[{"index":i,"embedding":[...]}...]}}
         */
        private String respond(List<String> texts) {
            StringBuilder json = new StringBuilder("{\"object\":\"list\",\"data\":[");
            for (int i = 0; i < texts.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"object\":\"embedding\",\"index\":").append(i)
                    .append(",\"embedding\":[");
                float[] vector = embed(texts.get(i));
                for (int d = 0; d < vector.length; d++) {
                    if (d > 0) {
                        json.append(',');
                    }
                    json.append(vector[d]);
                }
                json.append("]}");
            }
            return json.append("]}").toString();
        }

        /**
         * Embeds one text as an L2-normalised hashed bag of words.
         *
         * @param text The text
         * @return A unit vector of {@link #dimension} components
         */
        private float[] embed(String text) {
            float[] vector = new float[dimension];
            for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (!word.isEmpty()) {
                    vector[Math.floorMod(word.hashCode(), dimension)] += 1f;
                }
            }
            double norm = 0;
            for (float component : vector) {
                norm += component * component;
            }
            if (norm == 0) {
                vector[0] = 1f;
                return vector;
            }
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int d = 0; d < dimension; d++) {
                vector[d] *= scale;
            }
            return vector;
        }

        /**
         * Reads the {@code "input"} member of an embeddings request.
         *
         * <p>Handles the two shapes the client sends, a JSON string and a JSON array of strings,
         * and the escapes a JSON string may carry.</p>
         *
         * @param body The request body
         * @return The texts to embed, in request order
         */
        private static List<String> inputs(String body) {
            int at = body.indexOf("\"input\"");
            if (at < 0) {
                throw new IllegalArgumentException("request has no input: " + body);
            }
            at = body.indexOf(':', at) + 1;
            while (Character.isWhitespace(body.charAt(at))) {
                at++;
            }
            List<String> texts = new ArrayList<>();
            if (body.charAt(at) == '"') {
                texts.add(readString(body, at, new int[1]));
                return texts;
            }
            int[] end = new int[1];
            int cursor = at + 1;
            while (true) {
                while (Character.isWhitespace(body.charAt(cursor)) || body.charAt(cursor) == ',') {
                    cursor++;
                }
                if (body.charAt(cursor) == ']') {
                    return texts;
                }
                texts.add(readString(body, cursor, end));
                cursor = end[0];
            }
        }

        /**
         * Reads the JSON string starting at the opening quote {@code start}.
         *
         * @param json  The document
         * @param start Index of the opening quote
         * @param end   Receives the index just past the closing quote
         * @return The unescaped string
         */
        private static String readString(String json, int start, int[] end) {
            StringBuilder text = new StringBuilder();
            int i = start + 1;
            while (json.charAt(i) != '"') {
                char c = json.charAt(i++);
                if (c != '\\') {
                    text.append(c);
                    continue;
                }
                char escape = json.charAt(i++);
                switch (escape) {
                    case 'n' -> text.append('\n');
                    case 't' -> text.append('\t');
                    case 'r' -> text.append('\r');
                    case 'b' -> text.append('\b');
                    case 'f' -> text.append('\f');
                    case 'u' -> {
                        text.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> text.append(escape);
                }
            }
            end[0] = i + 1;
            return text.toString();
        }
    }
}
