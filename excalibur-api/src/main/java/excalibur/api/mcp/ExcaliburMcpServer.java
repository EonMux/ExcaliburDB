package excalibur.api.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exposes a running ExcaliburDB node to a language model as MCP tools.
 *
 * <h2>What this is for</h2>
 * <p>The graph query endpoint already answers in the shape a model needs - entities and facts to
 * ground on, a confidence, and an explicit refusal when the evidence is too thin. What it did not
 * have was a way for a model to reach it. Without this, every user writes the same adapter: four
 * HTTP calls, a tool schema, and the argument marshalling in between. This is that adapter, once,
 * next to the API it wraps, so the descriptions the model reads cannot drift from the endpoints
 * they describe.</p>
 *
 * <h2>Running it</h2>
 * <pre>
 * java --enable-preview --enable-native-access=ALL-UNNAMED \
 *      -cp excalibur.jar excalibur.api.mcp.ExcaliburMcpServer
 * </pre>
 * <p>It speaks MCP over stdin and stdout and reaches the node over HTTP; see
 * {@link ExcaliburClient} for the environment it reads. Pass {@code --print-schema} instead to
 * write the tool definitions to stdout and exit, which is the same catalogue in the form the
 * OpenAI and Anthropic function-calling APIs take - so a caller not using MCP still does not have
 * to write the schemas by hand.</p>
 *
 * <h2>Why stdout carries nothing else</h2>
 * <p>stdout is the protocol channel: one JSON-RPC message per line, and a single stray line of
 * logging on it desynchronises the client for the rest of the session. Everything this class says
 * to a human goes to stderr, and it does not log through SLF4J, whose appenders it does not
 * control.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Single-threaded by construction: one message is read, handled and answered before the next is
 * read, which is what keeps responses on stdout in the order the client expects.</p>
 *
 * @author ExcaliburDB
 */
public final class ExcaliburMcpServer {

    /** Name reported to the client. */
    public static final String SERVER_NAME = "excaliburdb";

    /** Version reported to the client. */
    public static final String SERVER_VERSION = "1.0.0";

    /** Protocol revision spoken when the client asks for one this does not know. */
    public static final String LATEST_PROTOCOL_VERSION = "2025-06-18";

    /** Protocol revisions this server understands. */
    public static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
        Set.of("2025-06-18", "2025-03-26", "2024-11-05");

    /** JSON-RPC: the message was not valid JSON. */
    public static final int PARSE_ERROR = -32700;

    /** JSON-RPC: the message was not a valid request. */
    public static final int INVALID_REQUEST = -32600;

    /** JSON-RPC: no such method. */
    public static final int METHOD_NOT_FOUND = -32601;

    /** JSON-RPC: the parameters were not usable. */
    public static final int INVALID_PARAMS = -32602;

    /** JSON-RPC: the server failed to handle a well-formed request. */
    public static final int INTERNAL_ERROR = -32603;

    private final McpTools tools;
    private final PrintStream out;
    private final PrintStream diagnostics;

    /**
     * Creates the server.
     *
     * @param tools       The tools to expose
     * @param out         Where JSON-RPC messages are written; nothing else may write here
     * @param diagnostics Where messages for a human are written
     */
    public ExcaliburMcpServer(McpTools tools, PrintStream out, PrintStream diagnostics) {
        this.tools = Objects.requireNonNull(tools, "tools must not be null");
        this.out = Objects.requireNonNull(out, "out must not be null");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }

    /**
     * Entry point.
     *
     * @param args {@code --print-schema} to write the tool catalogue and exit; otherwise none
     * @throws IOException if stdin cannot be read
     */
    public static void main(String[] args) throws IOException {
        if (args.length > 0 && "--print-schema".equals(args[0])) {
            System.out.println(Json.write(Map.of("tools", McpTools.catalogue())));
            return;
        }

        ExcaliburClient client;
        try {
            client = ExcaliburClient.fromEnvironment(System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("excaliburdb-mcp: " + e.getMessage());
            System.exit(1);
            return;
        }

        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        err.println("excaliburdb-mcp: serving tools against " + client.baseUri());

        new ExcaliburMcpServer(new McpTools(client), out, err).serve(System.in);
    }

    /**
     * Reads messages until the stream ends.
     *
     * @param in The stream carrying JSON-RPC messages, one per line
     * @throws IOException if the stream cannot be read
     */
    public void serve(InputStream in) throws IOException {
        try (BufferedReader reader =
                 new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String response = handle(line);
                if (response != null) {
                    out.println(response);
                }
            }
        }
        diagnostics.println("excaliburdb-mcp: input closed, exiting");
    }

    /**
     * Handles one message.
     *
     * @param message The JSON-RPC message text
     * @return The response to write, or null when the message was a notification
     */
    public String handle(String message) {
        Object request;
        try {
            request = Json.parse(message);
        } catch (Json.JsonException e) {
            return Json.write(error(null, PARSE_ERROR, e.getMessage()));
        }
        if (!(request instanceof Map)) {
            return Json.write(error(null, INVALID_REQUEST, "expected a JSON-RPC object"));
        }

        Object id = Json.at(request, "id");
        String method = Json.stringAt(request, "method");
        if (method == null) {
            // A response or a malformed message. Answering a message with no method with an error
            // that has no id helps nobody, so only say something when there is an id to say it to.
            return id == null ? null : Json.write(error(id, INVALID_REQUEST, "no 'method'"));
        }

        // A notification carries no id and must not be answered at all - a response to one is an
        // unsolicited message the client has nothing to match it against.
        boolean isNotification = id == null;

        try {
            Map<String, Object> result = dispatch(method, Json.objectAt(request, "params"));
            if (isNotification) {
                return null;
            }
            if (result == null) {
                return Json.write(error(id, METHOD_NOT_FOUND, "no such method: " + method));
            }
            return Json.write(success(id, result));
        } catch (IllegalArgumentException e) {
            return isNotification ? null : Json.write(error(id, INVALID_PARAMS, e.getMessage()));
        } catch (RuntimeException e) {
            // One failed call must not end the session; the client is entitled to try another.
            diagnostics.println("excaliburdb-mcp: " + method + " failed: " + e);
            return isNotification ? null : Json.write(error(id, INTERNAL_ERROR, String.valueOf(e)));
        }
    }

    /**
     * Runs one method.
     *
     * @param method The method name
     * @param params Its parameters, or null when it had none
     * @return The result, or null when there is no such method
     */
    private Map<String, Object> dispatch(String method, Map<String, Object> params) {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "tools/list" -> Map.of("tools", McpTools.catalogue());
            case "tools/call" -> callTool(params);
            case "ping" -> Json.object();
            // Notifications the client sends and expects nothing back from. Recognised rather than
            // reported as unknown, so a well-behaved client does not see a spurious error.
            case "notifications/initialized", "notifications/cancelled" -> Json.object();
            default -> null;
        };
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        String requested = params != null ? Json.stringAt(params, "protocolVersion") : null;
        String version = requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested)
            ? requested
            : LATEST_PROTOCOL_VERSION;

        Map<String, Object> serverInfo = Json.object();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);

        Map<String, Object> result = Json.object();
        result.put("protocolVersion", version);
        result.put("capabilities", Map.of("tools", Json.object()));
        result.put("serverInfo", serverInfo);
        return result;
    }

    private Map<String, Object> callTool(Map<String, Object> params) {
        if (params == null) {
            throw new IllegalArgumentException("tools/call requires params");
        }
        String name = Json.stringAt(params, "name");
        if (name == null) {
            throw new IllegalArgumentException("tools/call requires a tool 'name'");
        }
        McpTools.ToolResult outcome = tools.call(name, Json.objectAt(params, "arguments"));

        Map<String, Object> content = Json.object();
        content.put("type", "text");
        content.put("text", outcome.text());

        List<Object> blocks = new ArrayList<>();
        blocks.add(content);

        Map<String, Object> result = Json.object();
        result.put("content", blocks);
        result.put("isError", outcome.isError());
        return result;
    }

    private static Map<String, Object> success(Object id, Map<String, Object> result) {
        Map<String, Object> response = Json.object();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> error = Json.object();
        error.put("code", code);
        error.put("message", message != null ? message : "error");

        Map<String, Object> response = Json.object();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        return response;
    }
}
