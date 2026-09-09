package excalibur.api.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a model can do with an ExcaliburDB node, and how each of those maps onto its HTTP API.
 *
 * <h2>Why these four</h2>
 * <p>They are the whole loop: point the node at documents, watch it index them, turn what was
 * extracted into a graph, then ask the graph questions. A model handed only the last of those can
 * query a corpus somebody else prepared; handed all four it can prepare one, which is the
 * difference between a retrieval endpoint and something an agent can actually be pointed at.</p>
 *
 * <h2>What is deliberately not here</h2>
 * <p>Neither erasure route is a tool: not {@code POST /v1/erasure}, which removes a document from
 * the corpus, and not {@code POST /v1/graph/erasure}, which removes facts from the graph. They
 * exist to honour a right-to-be-forgotten request and they destroy data to do it, which makes them
 * the calls whose cost of being made wrongly is not bounded by retrying. A model asked to tidy up a
 * corpus would have every reason to reach for them. They stay behind the HTTP API, where a person
 * issues them.</p>
 *
 * <p>Nor is {@code DELETE /v1/ingest}, which stops a crawl. It destroys nothing, but it silently
 * stops the corpus from following its source - a failure that looks like nothing at all until
 * someone asks why an answer is out of date. Deciding a source should stop being read is a
 * standing decision about the deployment rather than a step in a task.</p>
 *
 * <h2>Descriptions are the interface</h2>
 * <p>A tool's description is not documentation, it is the only thing the model reads before
 * deciding whether to call it. The ones here say what the call returns, what has to have happened
 * first, and - for the graph query - that declining to answer is a result and not a failure, which
 * is the distinction a model will otherwise paper over by guessing.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable over a thread-safe client.</p>
 *
 * @author ExcaliburDB
 */
public final class McpTools {

    /** Ask the graph a question. */
    public static final String SEARCH_GRAPH = "search_graph";

    /** Publish the staged facts as the served graph. */
    public static final String BUILD_GRAPH = "build_graph";

    /** Register a directory and start indexing it. */
    public static final String INGEST_DOCUMENTS = "ingest_documents";

    /** Report indexing progress. */
    public static final String INGESTION_STATUS = "ingestion_status";

    private final ExcaliburClient client;

    /**
     * Creates the tool set over a node.
     *
     * @param client The node to call
     */
    public McpTools(ExcaliburClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    /**
     * The catalogue, as MCP {@code tools/list} returns it.
     *
     * @return One descriptor per tool, each with its name, description and input schema
     */
    public static List<Object> catalogue() {
        List<Object> tools = new ArrayList<>();

        tools.add(tool(SEARCH_GRAPH,
            "Answer a question from the knowledge graph built out of the ingested documents. "
            + "Returns entities and facts to ground an answer on, not prose. Read the 'status' "
            + "field: 'answered' carries the evidence in 'context' along with a confidence score; "
            + "'needs_input' means an entity in the question was ambiguous and lists the candidates "
            + "to pass back as context_entities; 'declined' means the graph does not hold enough to "
            + "answer, which is a result and not an error - report that rather than guessing. "
            + "Requires build_graph to have been run at least once.",
            schema(
                required("query", "string",
                    "The question, in natural language."),
                optional("context_entities", "array",
                    "Entity names that disambiguate the question, normally the candidates a "
                    + "previous 'needs_input' answer returned.", "string"),
                optional("namespace", "string",
                    "Namespace to query. Omit for the shared one.", null))));

        tools.add(tool(BUILD_GRAPH,
            "Turn the facts ingestion has extracted into the graph that search_graph reads. Run it "
            + "once after ingesting documents and again after ingesting more; until it has run, "
            + "search_graph reports that no graph is being served. Returns how many edges, entities "
            + "and relations were published, and 'staged_facts': when that is 0 nothing was "
            + "extracted, which usually means ingestion has not finished or the server has "
            + "extraction disabled.",
            schema(
                optional("namespace", "string",
                    "Namespace to build. Omit for the shared one.", null))));

        tools.add(tool(INGEST_DOCUMENTS,
            "Register a local directory as a source and start indexing the documents in it. "
            + "Returns as soon as the source is registered; indexing continues in the background, "
            + "so poll ingestion_status until its state is 'idle' before calling build_graph. The "
            + "directory must be inside one of the server's configured allowed roots, or the "
            + "request is refused.",
            schema(
                required("source_id", "string",
                    "A stable name for this source; reusing it re-indexes the same source."),
                required("root_path", "string",
                    "Absolute path of the directory to index."),
                optional("included_extensions", "array",
                    "File extensions to index, e.g. ['.md', '.txt']. Omit to accept the "
                    + "server's default.", "string"))));

        tools.add(tool(INGESTION_STATUS,
            "How far indexing has got, for one source or for all of them. 'idle' with no error "
            + "means there is nothing left to do. 'unconfigured' means the server has no ingestion "
            + "pipeline and indexed nothing at all, with 'last_error' naming what has to be set - "
            + "in that case do not call build_graph, because there will be nothing to build.",
            schema(
                optional("source_id", "string",
                    "Report only this source. Omit for every source.", null))));

        return tools;
    }

    /**
     * Runs one tool.
     *
     * @param name      The tool name
     * @param arguments Its arguments, as the client supplied them
     * @return What to hand back to the model
     */
    public ToolResult call(String name, Map<String, Object> arguments) {
        Map<String, Object> args = arguments != null ? arguments : Map.of();
        try {
            return switch (name) {
                case SEARCH_GRAPH -> searchGraph(args);
                case BUILD_GRAPH -> buildGraph(args);
                case INGEST_DOCUMENTS -> ingestDocuments(args);
                case INGESTION_STATUS -> ingestionStatus(args);
                default -> ToolResult.error("No such tool: " + name);
            };
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            // By far the most common failure, and the one whose message has to name the address:
            // the node is not running, or is not where this server was told to look.
            return ToolResult.error("Could not reach ExcaliburDB at " + client.baseUri()
                + " (" + e + "). Check the node is running and EXCALIBUR_URL points at it.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while calling ExcaliburDB.");
        }
    }

    private ToolResult searchGraph(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> body = Json.object();
        body.put("query", requiredString(args, "query"));
        List<String> mentions = stringList(args, "context_entities");
        if (!mentions.isEmpty()) {
            body.put("context_entities", mentions);
        }
        return answer(client.post("/v1/graph/query", Json.write(body), namespace(args)));
    }

    private ToolResult buildGraph(Map<String, Object> args)
            throws IOException, InterruptedException {
        return answer(client.post("/v1/graph/build", "{}", namespace(args)));
    }

    private ToolResult ingestDocuments(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> sourceConfig = Json.object();
        sourceConfig.put("source_id", requiredString(args, "source_id"));
        sourceConfig.put("root_path", requiredString(args, "root_path"));
        List<String> extensions = stringList(args, "included_extensions");
        if (!extensions.isEmpty()) {
            sourceConfig.put("included_extensions", extensions);
        }

        Map<String, Object> body = Json.object();
        body.put("source_type", "filesystem");
        body.put("config", sourceConfig);
        return answer(client.post("/v1/ingest", Json.write(body), null));
    }

    private ToolResult ingestionStatus(Map<String, Object> args)
            throws IOException, InterruptedException {
        String sourceId = optionalString(args, "source_id");
        return answer(client.get("/v1/ingest/status",
            sourceId != null ? "source_id" : null, sourceId));
    }

    /**
     * Turns a node response into a tool result.
     *
     * <p>A non-2xx is surfaced as an error with the node's own body attached rather than a summary
     * of it: the endpoints already explain what was wrong - which root is not allowed, that no
     * graph has been built - and rewriting that in the client would only lose it.</p>
     *
     * @param response What the node said
     * @return The tool result
     */
    private static ToolResult answer(ExcaliburClient.Response response) {
        if (response.isSuccess()) {
            return ToolResult.ok(response.body());
        }
        return ToolResult.error("ExcaliburDB returned HTTP " + response.status() + ": "
            + response.body());
    }

    private static String namespace(Map<String, Object> args) {
        return optionalString(args, "namespace");
    }

    private static String requiredString(Map<String, Object> args, String field) {
        String value = optionalString(args, field);
        if (value == null) {
            throw new IllegalArgumentException("Argument '" + field + "' is required.");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (!(value instanceof String text)) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> stringList(Map<String, Object> args, String field) {
        Object value = args.get(field);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof String text && !text.isBlank()) {
                    out.add(text.trim());
                }
            }
        }
        return out;
    }

    // ============================================
    // Schema construction
    // ============================================

    private static Map<String, Object> tool(String name, String description,
                                            Map<String, Object> inputSchema) {
        Map<String, Object> tool = Json.object();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema);
        return tool;
    }

    /** One property of a tool's input schema, and whether it has to be supplied. */
    private record Property(String name, Map<String, Object> schema, boolean required) {
    }

    private static Property required(String name, String type, String description) {
        return new Property(name, propertySchema(type, description, null), true);
    }

    private static Property optional(String name, String type, String description,
                                     String itemType) {
        return new Property(name, propertySchema(type, description, itemType), false);
    }

    private static Map<String, Object> propertySchema(String type, String description,
                                                      String itemType) {
        Map<String, Object> schema = Json.object();
        schema.put("type", type);
        schema.put("description", description);
        if (itemType != null) {
            Map<String, Object> items = Json.object();
            items.put("type", itemType);
            schema.put("items", items);
        }
        return schema;
    }

    private static Map<String, Object> schema(Property... properties) {
        Map<String, Object> fields = Json.object();
        List<Object> requiredNames = new ArrayList<>();
        for (Property property : properties) {
            fields.put(property.name(), property.schema());
            if (property.required()) {
                requiredNames.add(property.name());
            }
        }

        Map<String, Object> schema = Json.object();
        schema.put("type", "object");
        schema.put("properties", fields);
        schema.put("required", requiredNames);
        return schema;
    }

    /**
     * What a tool call produced.
     *
     * @param text    The text handed to the model
     * @param isError Whether the call failed
     */
    public record ToolResult(String text, boolean isError) {

        /**
         * A successful call.
         *
         * @param text The node's answer
         * @return The result
         */
        public static ToolResult ok(String text) {
            return new ToolResult(text, false);
        }

        /**
         * A failed call.
         *
         * @param text What went wrong
         * @return The result
         */
        public static ToolResult error(String text) {
            return new ToolResult(text, true);
        }
    }
}
