package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An in-process OpenAI-compatible embeddings endpoint that needs no model.
 *
 * <p>Serves {@code POST /v1/embeddings} for both request shapes {@link EmbeddingClient} sends,
 * a single {@code "input"} string and an array of them, and answers in the OpenAI response
 * schema. A text is embedded as a hashed bag of lower-cased words, L2-normalised: texts that
 * share words point in similar directions, so a query ranks the documents it overlaps with
 * first, and the same text always produces the same vector.</p>
 *
 * <p>{@code POST /v1/chat/completions} is answered with an empty completion, which every caller
 * on a node treats as the model having nothing to add, so a node configured against this endpoint
 * runs its embedding path for real and its chat features as though the model declined.</p>
 *
 * <p>USAGE.md's embedded example carries its own embeddings-only copy: its source set compiles
 * against exactly the dependencies the example documents, and none of the test classes.</p>
 */
final class FakeEmbeddingServer implements AutoCloseable {

    /** A chat completion in the OpenAI schema whose message says nothing. */
    private static final String EMPTY_COMPLETION = "{\"id\":\"chatcmpl-fake\","
        + "\"object\":\"chat.completion\",\"created\":0,\"model\":\"fake\",\"choices\":[{"
        + "\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
        + "\"completion_tokens\":1,\"total_tokens\":2}}";

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
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] response = EMPTY_COMPLETION.getBytes(StandardCharsets.UTF_8);
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
