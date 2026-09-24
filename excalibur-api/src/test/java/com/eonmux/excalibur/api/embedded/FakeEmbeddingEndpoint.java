package com.eonmux.excalibur.api.embedded;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A keyless OpenAI-compatible embeddings endpoint on loopback, with no model behind it.
 *
 * <p>Embeds a text as an L2-normalised hashed bag of its lower-cased words, so texts sharing words
 * point the same way and the same text always gets the same vector. {@link #fail(boolean)} makes
 * it answer 500, for the paths where the vector half of a search has to degrade.</p>
 */
final class FakeEmbeddingEndpoint implements AutoCloseable {

    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final HttpServer server;
    private final ExecutorService executor;
    private final int dimension;
    private final AtomicBoolean failing = new AtomicBoolean();

    private FakeEmbeddingEndpoint(HttpServer server, ExecutorService executor, int dimension) {
        this.server = server;
        this.executor = executor;
        this.dimension = dimension;
    }

    static FakeEmbeddingEndpoint start(int dimension) throws IOException {
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        FakeEmbeddingEndpoint fake = new FakeEmbeddingEndpoint(server, executor, dimension);
        server.createContext("/v1/embeddings", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
                byte[] response = fake.failing.get()
                    ? "{\"error\":\"down\"}".getBytes(StandardCharsets.UTF_8)
                    : fake.respond(inputs(body)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(fake.failing.get() ? 500 : 200, response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
            }
        });
        server.setExecutor(executor);
        server.start();
        return fake;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void fail(boolean fail) {
        failing.set(fail);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private String respond(List<String> texts) {
        StringBuilder json = new StringBuilder("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < texts.size(); i++) {
            json.append(i > 0 ? "," : "").append("{\"object\":\"embedding\",\"index\":").append(i)
                .append(",\"embedding\":[");
            float[] vector = embed(texts.get(i));
            for (int d = 0; d < vector.length; d++) {
                json.append(d > 0 ? "," : "").append(vector[d]);
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

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
     * The strings of the request's {@code input}, whether it is one string or an array.
     *
     * <p>The client writes {@code input} last, after {@code model}, so every string from there
     * to the end of the body is one of the texts.</p>
     */
    private static List<String> inputs(String body) {
        int at = body.indexOf("\"input\"");
        String input = body.substring(body.indexOf(':', at) + 1);
        List<String> texts = new ArrayList<>();
        Matcher matcher = STRING.matcher(input);
        while (matcher.find()) {
            texts.add(matcher.group(1).replace("\\n", "\n").replace("\\\"", "\"")
                .replace("\\\\", "\\"));
        }
        return texts;
    }
}
