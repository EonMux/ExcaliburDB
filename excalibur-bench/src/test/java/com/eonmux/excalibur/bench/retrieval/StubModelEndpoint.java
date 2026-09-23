package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.api.mcp.Json;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An OpenAI-compatible model on loopback, so the harness's model-backed strategies run offline.
 *
 * <p>Deterministic in everything it answers, because the tests read exact scores off its answers:
 * <ul>
 *   <li><b>Embeddings</b> are a hashed bag of words over a constant component, L2-normalised, with
 *       one synonym - {@code ram} means {@code memory} - and one word given no weight at all,
 *       {@code kernel}. A node's vector search only searches the segments whose term filter
 *       holds a word of the question, so a question reaches a document through vectors only if
 *       they share a word; weighing nothing for the shared word is what leaves the ranking inside
 *       those segments to meaning, the way a real model largely discounts a word every candidate
 *       contains.</li>
 *   <li><b>Relation extraction</b> finds one dated fact in the document about Byron's daughter,
 *       naming her, and nothing anywhere else.</li>
 *   <li><b>Query expansion</b> turns a question about TLS into the words the TLS document
 *       contains, and adds nothing to any other question.</li>
 *   <li>Anything else the node asks a chat model is answered with an empty completion, which
 *       every caller treats as the model having nothing to add.</li>
 * </ul>
 */
final class StubModelEndpoint implements AutoCloseable {

    /** Width of every vector, inside the range a node accepts. */
    static final int DIMENSIONS = 256;

    /** The fact extracted from the Byron document; its words are not in the document. */
    private static final String EXTRACTED = "[{\"subject\":\"Ada Lovelace\","
        + "\"relation\":\"wrote\",\"object\":\"first algorithm\",\"confidence\":0.95,"
        + "\"valid_from\":\"1843-09-01\"}]";

    private static final String TLS_EXPANSION =
        "[{\"q\":\"certificate chain\",\"w\":0.9},{\"q\":\"handshake\",\"w\":0.8}]";

    private static final Map<String, String> SAME_AS = Map.of("ram", "memory");

    private static final Set<String> WEIGHTLESS = Set.of("kernel");

    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger extractions = new AtomicInteger();
    private final AtomicInteger expansions = new AtomicInteger();
    private final AtomicInteger embeddings = new AtomicInteger();

    private StubModelEndpoint(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /** Starts the endpoint on an ephemeral loopback port. */
    static StubModelEndpoint start() throws IOException {
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        StubModelEndpoint stub = new StubModelEndpoint(server, executor);
        server.createContext("/v1/embeddings", exchange -> stub.reply(exchange, stub::embed));
        server.createContext("/v1/chat/completions", exchange -> stub.reply(exchange, stub::chat));
        server.setExecutor(executor);
        server.start();
        return stub;
    }

    /** What to configure a client with: the host, without the API version path. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int extractionCalls() {
        return extractions.get();
    }

    int expansionCalls() {
        return expansions.get();
    }

    int embeddingCalls() {
        return embeddings.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private interface Responder {
        String respond(Object request);
    }

    private void reply(HttpExchange exchange, Responder responder) throws IOException {
        try (exchange) {
            String request;
            try (InputStream in = exchange.getRequestBody()) {
                request = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            byte[] body = responder.respond(Json.parse(request)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private String embed(Object request) {
        embeddings.incrementAndGet();
        Object input = Json.at(request, "input");
        List<?> texts = input instanceof List<?> list ? list : List.of(String.valueOf(input));
        List<Object> data = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            Map<String, Object> item = Json.object();
            item.put("object", "embedding");
            item.put("index", (long) i);
            item.put("embedding", vectorOf(String.valueOf(texts.get(i))));
            data.add(item);
        }
        Map<String, Object> response = Json.object();
        response.put("object", "list");
        response.put("data", data);
        return Json.write(response);
    }

    private static List<Object> vectorOf(String text) {
        double[] vector = new double[DIMENSIONS];
        vector[0] = 1.0;
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty() && !WEIGHTLESS.contains(word)) {
                String meant = SAME_AS.getOrDefault(word, word);
                vector[1 + Math.floorMod(meant.hashCode(), DIMENSIONS - 1)] += 1.0;
            }
        }
        double norm = 0;
        for (double component : vector) {
            norm += component * component;
        }
        double scale = 1.0 / Math.sqrt(norm);
        List<Object> out = new ArrayList<>(DIMENSIONS);
        for (double component : vector) {
            out.add(component * scale);
        }
        return out;
    }

    private String chat(Object request) {
        StringBuilder prompt = new StringBuilder();
        if (Json.at(request, "messages") instanceof List<?> messages) {
            for (Object message : messages) {
                prompt.append(Json.stringAt(message, "content")).append('\n');
            }
        }
        String text = prompt.toString();
        String content = "";
        if (text.contains("You extract factual relationships")) {
            extractions.incrementAndGet();
            content = text.contains("Byron's daughter") ? EXTRACTED : "[]";
        } else if (text.contains("You expand a search query")) {
            expansions.incrementAndGet();
            content = text.contains("TLS") ? TLS_EXPANSION : "[]";
        }
        Map<String, Object> message = Json.object();
        message.put("role", "assistant");
        message.put("content", content);
        Map<String, Object> choice = Json.object();
        choice.put("index", 0L);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        Map<String, Object> usage = Json.object();
        usage.put("prompt_tokens", 1L);
        usage.put("completion_tokens", 1L);
        usage.put("total_tokens", 2L);
        Map<String, Object> response = Json.object();
        response.put("id", "chatcmpl-stub");
        response.put("object", "chat.completion");
        response.put("created", 0L);
        response.put("model", "stub");
        response.put("choices", List.of(choice));
        response.put("usage", usage);
        return Json.write(response);
    }
}
