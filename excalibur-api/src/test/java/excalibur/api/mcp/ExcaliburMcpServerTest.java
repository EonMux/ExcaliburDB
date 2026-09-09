package excalibur.api.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON-RPC the client on the other end of the pipe is entitled to.
 *
 * <p>Two of these properties are the ones a hand-rolled MCP server usually gets wrong, and both
 * fail in ways that look like the server hanging: answering a notification, which leaves the client
 * holding a response it has no request for, and writing anything but protocol on stdout, which
 * desynchronises the stream for the rest of the session.</p>
 */
@DisplayName("MCP server")
class ExcaliburMcpServerTest {

    /** A server over a stub node, with stdout captured. */
    private static final class Harness implements AutoCloseable {
        private final StubNode node;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private final ExcaliburMcpServer server;

        Harness() throws IOException {
            this.node = StubNode.start();
            this.server = new ExcaliburMcpServer(
                new McpTools(node.client(Map.of())),
                new PrintStream(captured, true, StandardCharsets.UTF_8),
                new PrintStream(ByteArrayOutputStream.nullOutputStream(), true,
                    StandardCharsets.UTF_8));
        }

        Object ask(String message) {
            String response = server.handle(message);
            return response == null ? null : Json.parse(response);
        }

        String stdout() {
            return captured.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            node.close();
        }
    }

    private static String request(long id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\""
            + (params == null ? "" : ",\"params\":" + params) + "}";
    }

    @Test
    @DisplayName("initialize answers with a protocol version, capabilities and an identity")
    void initializeAnswers() throws IOException {
        try (Harness harness = new Harness()) {
            Object response = harness.ask(request(1, "initialize",
                "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}}"));

            assertThat(Json.at(response, "id")).isEqualTo(1L);
            Map<String, Object> result = Json.objectAt(response, "result");
            assertThat(Json.stringAt(result, "protocolVersion"))
                .as("a client that asked for a version this server speaks gets that version")
                .isEqualTo("2024-11-05");
            assertThat(Json.objectAt(result, "capabilities")).containsKey("tools");
            assertThat(Json.stringAt(Json.objectAt(result, "serverInfo"), "name"))
                .isEqualTo(ExcaliburMcpServer.SERVER_NAME);
        }
    }

    @Test
    @DisplayName("a protocol version this server does not speak is answered with one it does")
    void anUnknownProtocolVersionFallsBack() throws IOException {
        try (Harness harness = new Harness()) {
            Object response = harness.ask(request(1, "initialize",
                "{\"protocolVersion\":\"1999-01-01\"}"));

            assertThat(Json.stringAt(Json.objectAt(response, "result"), "protocolVersion"))
                .isEqualTo(ExcaliburMcpServer.LATEST_PROTOCOL_VERSION);
        }
    }

    @Test
    @DisplayName("initialize with no params is still answered")
    void initializeWithoutParams() throws IOException {
        try (Harness harness = new Harness()) {
            Object response = harness.ask(request(1, "initialize", null));

            assertThat(Json.objectAt(response, "result")).isNotNull();
            assertThat(Json.at(response, "error")).isNull();
        }
    }

    @Test
    @DisplayName("tools/list returns the catalogue")
    void toolsListReturnsTheCatalogue() throws IOException {
        try (Harness harness = new Harness()) {
            Object response = harness.ask(request(2, "tools/list", null));

            Object tools = Json.at(Json.objectAt(response, "result"), "tools");
            assertThat(tools).isInstanceOf(List.class);
            assertThat((List<?>) tools).hasSize(4);
        }
    }

    @Test
    @DisplayName("tools/call runs the tool and returns a text block")
    void toolsCallReturnsContent() throws IOException {
        try (Harness harness = new Harness()) {
            harness.node.answerWith(200, "{\"status\":\"declined\",\"reason\":\"no evidence\"}");

            Object response = harness.ask(request(3, "tools/call",
                "{\"name\":\"search_graph\",\"arguments\":{\"query\":\"who?\"}}"));

            Map<String, Object> result = Json.objectAt(response, "result");
            assertThat(result.get("isError")).isEqualTo(Boolean.FALSE);
            List<?> content = (List<?>) result.get("content");
            assertThat(content).hasSize(1);
            assertThat(Json.stringAt(content.get(0), "type")).isEqualTo("text");
            assertThat(Json.stringAt(content.get(0), "text")).contains("declined");
        }
    }

    @Test
    @DisplayName("a question full of quotes and newlines survives the transport")
    void awkwardTextSurvivesTheTransport() throws IOException {
        try (Harness harness = new Harness()) {
            String question = "Explain \"the engine\",\nthen \\ stop.";
            String message = Json.write(Map.of(
                "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                "params", Map.of("name", "search_graph",
                    "arguments", Map.of("query", question))));

            String response = harness.server.handle(message);

            // One message per line: a raw newline anywhere in the response splits it in two.
            assertThat(response).doesNotContain("\n");
            assertThat(Json.stringAt(Json.parse(harness.node.onlyRequest().body()), "query"))
                .isEqualTo(question);
        }
    }

    @Test
    @DisplayName("a failing tool is reported in the result, not as a protocol error")
    void toolFailuresAreResultsNotErrors() throws IOException {
        try (Harness harness = new Harness()) {
            harness.node.answerWith(503, "{\"error\":\"no graph is being served\"}");

            Object response = harness.ask(request(5, "tools/call",
                "{\"name\":\"search_graph\",\"arguments\":{\"query\":\"who?\"}}"));

            // A JSON-RPC error means the call could not be made; this one was made and answered.
            assertThat(Json.at(response, "error")).isNull();
            Map<String, Object> result = Json.objectAt(response, "result");
            assertThat(result.get("isError")).isEqualTo(Boolean.TRUE);
        }
    }

    @Test
    @DisplayName("tools/call without a tool name is an invalid-params error")
    void toolsCallNeedsAName() throws IOException {
        try (Harness harness = new Harness()) {
            Object response = harness.ask(request(6, "tools/call", "{}"));

            assertThat(Json.at(Json.objectAt(response, "error"), "code"))
                .isEqualTo((long) ExcaliburMcpServer.INVALID_PARAMS);
        }
    }

    @Test
    @DisplayName("a notification is not answered at all")
    void notificationsGetNoResponse() throws IOException {
        try (Harness harness = new Harness()) {
            // A response to a notification is an unsolicited message the client cannot match to
            // anything, and clients that check will drop the connection over it.
            assertThat(harness.ask("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .isNull();
            assertThat(harness.ask("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                + "\"params\":{\"requestId\":1}}")).isNull();
            assertThat(harness.ask("{\"jsonrpc\":\"2.0\",\"method\":\"who/knows\"}")).isNull();
        }
    }

    @Test
    @DisplayName("ping is answered, because clients use it to check the server is alive")
    void pingIsAnswered() throws IOException {
        try (Harness harness = new Harness()) {
            Object response = harness.ask(request(7, "ping", null));

            assertThat(Json.objectAt(response, "result")).isEmpty();
        }
    }

    @Test
    @DisplayName("an unknown method with an id gets method-not-found")
    void unknownMethodsAreReported() throws IOException {
        try (Harness harness = new Harness()) {
            Object response = harness.ask(request(8, "tools/delete", null));

            assertThat(Json.at(Json.objectAt(response, "error"), "code"))
                .isEqualTo((long) ExcaliburMcpServer.METHOD_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("a message that is not JSON gets a parse error rather than ending the session")
    void malformedMessagesAreParseErrors() throws IOException {
        try (Harness harness = new Harness()) {
            Object response = harness.ask("{not json");

            assertThat(Json.at(Json.objectAt(response, "error"), "code"))
                .isEqualTo((long) ExcaliburMcpServer.PARSE_ERROR);
            assertThat(Json.at(response, "id")).isNull();
        }
    }

    @Test
    @DisplayName("a JSON value that is not a request object is refused")
    void nonObjectMessagesAreRefused() throws IOException {
        try (Harness harness = new Harness()) {
            assertThat(Json.at(Json.objectAt(harness.ask("[1,2,3]"), "error"), "code"))
                .isEqualTo((long) ExcaliburMcpServer.INVALID_REQUEST);
        }
    }

    @Test
    @DisplayName("an id is echoed in the type it arrived as")
    void idsAreEchoedFaithfully() throws IOException {
        try (Harness harness = new Harness()) {
            assertThat(Json.at(harness.ask(
                "{\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\"method\":\"ping\"}"), "id"))
                .isEqualTo("call-1");
            assertThat(Json.at(harness.ask(
                "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ping\"}"), "id"))
                .as("an id echoed as 42.0 is one the client cannot match")
                .isEqualTo(42L);
        }
    }

    @Test
    @DisplayName("a session of several messages writes one line each, and nothing else")
    void aSessionWritesOneLinePerResponse() throws IOException {
        try (Harness harness = new Harness()) {
            String session = String.join("\n",
                request(1, "initialize", "{\"protocolVersion\":\"2025-06-18\"}"),
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "",
                request(2, "tools/list", null),
                request(3, "ping", null));

            harness.server.serve(new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)));

            List<String> lines = harness.stdout().lines().toList();
            assertThat(lines)
                .as("three requests, one notification and a blank line: three responses")
                .hasSize(3);
            for (String line : lines) {
                assertThat(Json.parse(line)).isInstanceOf(Map.class);
            }
            assertThat(Json.at(Json.parse(lines.get(0)), "id")).isEqualTo(1L);
            assertThat(Json.at(Json.parse(lines.get(1)), "id")).isEqualTo(2L);
            assertThat(Json.at(Json.parse(lines.get(2)), "id")).isEqualTo(3L);
        }
    }

    @Test
    @DisplayName("the loop survives a bad message in the middle of a session")
    void aBadMessageDoesNotEndTheSession() throws IOException {
        try (Harness harness = new Harness()) {
            String session = String.join("\n",
                "{ this is not json",
                request(2, "ping", null));

            harness.server.serve(new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)));

            List<String> lines = harness.stdout().lines().toList();
            assertThat(lines).hasSize(2);
            assertThat(Json.at(Json.parse(lines.get(1)), "id")).isEqualTo(2L);
        }
    }
}
