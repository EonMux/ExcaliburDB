package com.eonmux.excalibur.api.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for a running node, so the MCP tests exercise the real HTTP client.
 *
 * <p>Mocking the client would test that the code calls the mock. What has to be true is that the
 * bytes leaving this process are the request the node's endpoints actually parse - the body shape,
 * the namespace header, the bearer token - so the tests send real requests to a real socket and
 * assert on what arrived.</p>
 */
final class StubNode implements AutoCloseable {

    /** One request as the node received it. */
    record Received(String method, String path, String query, String body,
                    Map<String, String> headers) {
    }

    private final HttpServer server;
    private final List<Received> received = new ArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private final Map<String, Answer> byPath = new ConcurrentHashMap<>();

    /** What one path is answered with, in place of the answer every other path gets. */
    private record Answer(int status, String body) {
    }

    private StubNode(HttpServer server) {
        this.server = server;
    }

    static StubNode start() throws IOException {
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        StubNode node = new StubNode(server);
        server.createContext("/", node::record);
        server.start();
        return node;
    }

    private void record(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Header names are case-insensitive by spec, and the JDK's server normalises them
        // on the way in; comparing them case-sensitively would fail on capitalisation the
        // real server does not care about either.
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders()
            .forEach((name, values) -> headers.put(name, values.isEmpty() ? "" : values.get(0)));

        synchronized (received) {
            received.add(new Received(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(),
                body, headers));
        }

        Answer answer = byPath.getOrDefault(exchange.getRequestURI().getPath(),
            new Answer(status, responseBody));
        byte[] payload = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    /** Sets what every subsequent request is answered with. */
    void answerWith(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    /** Sets what every subsequent request to one path is answered with, whatever the others get. */
    void answerAt(String path, int status, String body) {
        byPath.put(path, new Answer(status, body));
    }

    /** The base URL a client should be pointed at. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Everything received so far, in order. */
    List<Received> requests() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    /** The only request received; fails the caller's expectation if there was not exactly one. */
    Received onlyRequest() {
        List<Received> all = requests();
        if (all.size() != 1) {
            throw new AssertionError("expected exactly one request, got " + all.size() + ": " + all);
        }
        return all.get(0);
    }

    /** A client pointed at this node, with the given extra environment. */
    ExcaliburClient client(Map<String, String> environment) {
        Map<String, String> env = new HashMap<>(environment);
        env.put("EXCALIBUR_URL", url());
        return ExcaliburClient.fromEnvironment(env);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
