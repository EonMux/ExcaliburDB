package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.api.embedded.EmbeddedDocument;
import com.eonmux.excalibur.api.embedded.ErasureOutcome;
import com.eonmux.excalibur.api.embedded.ExcaliburEmbedded;
import com.eonmux.excalibur.api.embedded.SearchAnswer;
import com.eonmux.excalibur.api.embedded.SearchHit;
import com.eonmux.excalibur.api.embedded.SearchRoute;
import com.eonmux.excalibur.api.embedded.StoredDocument;
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
import java.nio.file.Path;
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
 * data directory, supplies documents, queries them and erases them without the HTTP server. The
 * bodies of {@link #ingestAndQuery(Path, String)} and {@link #reopenAndErase(Path, String)} are
 * that example: the documentation reproduces them line for line, so this test is what keeps the
 * published snippet compiling against the current public API and doing what it says. A change
 * that breaks this test breaks every embedder who copied the snippet, and the documentation has
 * to change with it; {@link EmbeddedUsageDocumentationTest} fails until it does.</p>
 *
 * <p>It lives in the {@code embeddedExample} source set rather than beside the other integration
 * tests because that source set's classpath is only the coordinate USAGE.md tells an embedder to
 * declare. Compiled there, it also proves that coordinate is enough: every type the example names
 * is one {@code excalibur-api} publishes.</p>
 *
 * <p>The one thing the test substitutes is the embedding provider. The node speaks the
 * OpenAI-compatible {@code POST /v1/embeddings} protocol, so the test points it at
 * {@link FakeEmbeddingServer}, an in-process endpoint that embeds text deterministically as a
 * hashed bag of words and needs no key. The example itself is unchanged: an embedder passes the
 * base URL of a real provider (a local Ollama, vLLM or TGI server, or a hosted one with its key)
 * where the test passes the fake's.</p>
 */
@DisplayName("Embedded usage example (USAGE.md)")
class EmbeddedUsageExampleTest {

    /** Width of the vectors the fake endpoint returns: the node's default width. */
    private static final int DIMENSION = 1536;

    @Test
    @Timeout(120)
    @DisplayName("documents supplied in-process are returned by an in-process query")
    void embeddedIngestionIsFoundByEmbeddedQuery(@TempDir Path tempDir) throws Exception {
        Path dataDir = tempDir.resolve("data");

        List<SearchHit> results;
        try (FakeEmbeddingServer embeddings = FakeEmbeddingServer.start(DIMENSION)) {
            results = ingestAndQuery(dataDir, embeddings.baseUrl());
        }

        assertThat(results)
            .as("the query shares most of its words with one document and nothing with another")
            .isNotEmpty();
        assertThat(results.getFirst().chunkId())
            .as("the closest result is the chunk about tiering, named as ingestion named it")
            .isEqualTo("handbook:tiering.txt:chunk:0");
        assertThat(results.getFirst().text()).contains("tiering policy");
        assertThat(results)
            .extracting(SearchHit::chunkId)
            .as("each chunk is returned once, under the identifier ingestion assigned")
            .doesNotHaveDuplicates()
            .allSatisfy(id -> assertThat(id).startsWith("handbook:").contains(":chunk:"));
    }

    @Test
    @Timeout(120)
    @DisplayName("what was supplied survives closing the node, and can be replaced and erased after")
    void suppliedDocumentsSurviveARestart(@TempDir Path tempDir) throws Exception {
        Path dataDir = tempDir.resolve("data");

        SearchAnswer answer;
        try (FakeEmbeddingServer embeddings = FakeEmbeddingServer.start(DIMENSION)) {
            ingestAndQuery(dataDir, embeddings.baseUrl());
            answer = reopenAndErase(dataDir, embeddings.baseUrl());

            try (ExcaliburEmbedded db = ExcaliburEmbedded.builder(dataDir)
                    .property("excalibur.llm.embedding-base-url", embeddings.baseUrl())
                    .property("excalibur.llm.chat-enabled", "false")
                    .open()) {
                assertThat(db.documents("handbook"))
                    .as("the erasure outlived the node that made it")
                    .extracting(StoredDocument::documentId)
                    .containsExactly("hnsw.txt", "tiering.txt");
                assertThat(db.fetch("handbook:tiering.txt:chunk:0"))
                    .as("and so did the replacement")
                    .hasValueSatisfying(text -> assertThat(text).contains("Warm segments"));
            }
        }

        assertThat(answer.route())
            .as("a node with an embedding endpoint and a chunk index fuses both signals")
            .isEqualTo(SearchRoute.HYBRID);
        assertThat(answer.hits().getFirst().chunkId())
            .as("the replaced document answers for what it says now")
            .isEqualTo("handbook:tiering.txt:chunk:0");
        assertThat(answer.hits())
            .extracting(SearchHit::chunkId)
            .as("the erased document answers for nothing")
            .doesNotContain("handbook:backup.txt:chunk:0");
    }

    /**
     * Opens {@code dataDir}, supplies three documents and returns the hits of one query.
     *
     * <p>This method body is the USAGE.md example. Keep it readable as documentation: no test
     * scaffolding, no assertions, nothing a reader would have to strip out.</p>
     *
     * @param dataDir           The directory the node keeps its files in; created if missing
     * @param embeddingEndpoint Base URL of an OpenAI-compatible embeddings API
     * @return The hits, closest first
     * @throws Exception if the node cannot be opened or the query is interrupted
     */
    static List<SearchHit> ingestAndQuery(Path dataDir, String embeddingEndpoint)
            throws Exception {
        // The same keys excalibur.properties takes, set in code. A local Ollama, vLLM or TGI
        // server needs no key; a hosted endpoint also takes excalibur.llm.api-key, and a model
        // other than the default takes excalibur.llm.embedding-model and its dimensions.
        try (ExcaliburEmbedded db = ExcaliburEmbedded.builder(dataDir)
                .property("excalibur.llm.embedding-base-url", embeddingEndpoint)
                .property("excalibur.llm.chat-enabled", "false")
                .open()) {
            // Chunked, embedded and indexed before supply returns; results name the chunk
            // that matched, "<sourceId>:<documentId>:chunk:<n>".
            db.supply("handbook", List.of(
                EmbeddedDocument.of("tiering.txt",
                    "Cold segments are moved to cheaper disks by the tiering policy."),
                EmbeddedDocument.of("hnsw.txt",
                    "The HNSW graph links every vector to its nearest neighbours."),
                EmbeddedDocument.of("backup.txt",
                    "Backups copy the write log and the catalog to object storage.")));

            // Embedded by the node with the model the corpus was embedded with, and fused
            // with BM25 over the same chunks.
            SearchAnswer answer = db.query("which policy moves cold segments to cheaper disks", 3);
            answer.hits().forEach(hit ->
                System.out.println(hit.chunkId() + " -> " + hit.score() + ": " + hit.text()));
            return answer.hits();
        }
    }

    /**
     * Reopens {@code dataDir}, replaces one document, erases another and queries again.
     *
     * <p>This method body is the second half of the USAGE.md example.</p>
     *
     * @param dataDir           A directory {@link #ingestAndQuery} has already filled
     * @param embeddingEndpoint The embeddings API the corpus was embedded with
     * @return The answer to a query against what is left
     * @throws Exception if the node cannot be opened or the query is interrupted
     */
    static SearchAnswer reopenAndErase(Path dataDir, String embeddingEndpoint) throws Exception {
        try (ExcaliburEmbedded db = ExcaliburEmbedded.builder(dataDir)
                .property("excalibur.llm.embedding-base-url", embeddingEndpoint)
                .property("excalibur.llm.chat-enabled", "false")
                .open()) {
            // What the last node supplied is still here: documents and their chunks.
            for (StoredDocument document : db.documents("handbook")) {
                System.out.println(document.documentId() + " " + document.chunkIds());
            }

            // Supplying a document id again replaces it; a query sees one version or the other.
            db.supply("handbook", List.of(EmbeddedDocument.of("tiering.txt",
                "Warm segments stay on fast disks until the tiering policy demotes them.")));

            // Gone from storage, from the lexical index and from the vectors.
            ErasureOutcome erased = db.erase("handbook", List.of("backup.txt"));
            System.out.println("erased " + erased.erasedDocumentIds());

            return db.query("when are warm segments demoted from fast disks", 3);
        }
    }

    /**
     * An in-process OpenAI-compatible embeddings endpoint that needs no model.
     *
     * <p>Serves {@code POST /v1/embeddings} for both request shapes the node's embedding client sends,
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
         * @return The base URL to configure the node's embedding client with
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
