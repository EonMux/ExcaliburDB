package excalibur.api.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a model can do with an ExcaliburDB node, and how each of those maps onto its HTTP API.
 *
 * <h2>Why these five</h2>
 * <p>They are the whole loop: point the node at documents, watch it index them, search them, turn
 * what was extracted into a graph, then ask the graph questions. A model handed only the last of
 * those can query a corpus somebody else prepared; handed all five it can prepare one, which is
 * the difference between a retrieval endpoint and something an agent can actually be pointed at.
 * </p>
 *
 * <h2>Why both searches</h2>
 * <p>{@link #SEARCH_DOCUMENTS} and {@link #SEARCH_GRAPH} answer different questions and neither
 * subsumes the other. The first names the chunks that match and needs nothing but an
 * ingestion run; the second returns entities and the facts connecting them, and needs a graph to
 * have been built out of extracted triples - which requires a chat model and
 * {@code excalibur.graph.extraction-enabled}, off by default. A tool set with only the graph
 * search leaves a model on an ordinary node with no way to reach the corpus at all.</p>
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

    /** Search the indexed documents. */
    public static final String SEARCH_DOCUMENTS = "search_documents";

    /** Ask the graph a question. */
    public static final String SEARCH_GRAPH = "search_graph";

    /** Publish the staged facts as the served graph. */
    public static final String BUILD_GRAPH = "build_graph";

    /** Register a directory and start indexing it. */
    public static final String INGEST_DOCUMENTS = "ingest_documents";

    /** Index documents supplied in the call itself. */
    public static final String ADD_DOCUMENTS = "add_documents";

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

        tools.add(tool(SEARCH_DOCUMENTS,
            "Search the documents this node has indexed and return the matching passages, best "
            + "first. Each result carries the passage in 'text', its 'document_id', and a score; "
            + "'text' is null when the node no longer holds that chunk, and 'text_truncated' is "
            + "true when the passage was clipped to the node's per-result limit - quote such a "
            + "result as a fragment rather than as the whole passage. This is the plain "
            + "retrieval call: it needs only that "
            + "ingest_documents has run, and it does not require build_graph. Prefer it when the question is answered "
            + "by the text of a document; prefer search_graph when the question is about how "
            + "entities relate to each other. Read 'coverage' in the reply: it says what fraction "
            + "of the index was actually searched, and a value below 1 means segments were still "
            + "indexing or the deadline cut the search short, so the result may be incomplete. "
            + "'timed_out' and 'cancelled' say which. Use the order of the results, not the "
            + "'score' on them: 'retrieval' says which signals answered - 'vector' scores are "
            + "similarities, 'lexical' are BM25 scores, 'hybrid' are fused rank sums near zero - "
            + "and the three share no scale, so a score is comparable only with others from the "
            + "same reply.",
            schema(
                required("query", "string",
                    "What to search for, in natural language. The server embeds it with the same "
                    + "model it indexed with."),
                optional("top_k", "integer",
                    "How many passages to return. Omit for the server's default.", null))));

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
                optional("as_of", "string",
                    "Answer as the facts stood at this point in valid time: an ISO-8601 date "
                    + "('2019-03-01'), an ISO-8601 instant ('2019-03-01T14:00:00Z'), or 'all' "
                    + "for the whole history including facts that have since been superseded. "
                    + "Omit to answer from what holds now, or from a date mentioned in the "
                    + "question itself.", null),
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
                    "Namespace to build. Omit for the shared one, which is what you want: "
                    + "ingestion stages every fact it extracts under the shared namespace, so "
                    + "building a named one finds nothing staged and publishes nothing.", null))));

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
                    + "server's default.", "string"),
                optional("excluded_directories", "array",
                    "Directory names to skip anywhere under the root, e.g. ['node_modules', "
                    + "'target']. Omit to keep the server's default skip list.", "string"),
                optional("max_depth", "integer",
                    "How many directory levels below the root to descend. Omit for the "
                    + "server's default.", null))));

        tools.add(tool(ADD_DOCUMENTS,
            "Index documents by sending their text directly, for content you are holding rather "
            + "than a directory on the server. Prefer this over ingest_documents unless the "
            + "content is already in a directory the server is configured to read: a server with "
            + "no allowed roots refuses ingest_documents outright, and this call needs none. The "
            + "documents are indexed before the call returns, so they are searchable immediately "
            + "and there is no status to poll - unlike ingest_documents, which only starts a "
            + "crawl. Sending a 'document_id' that was sent before replaces what is indexed "
            + "under it. Use a 'source_id' that no crawl is using; one that is already a "
            + "registered crawl source is refused, because the next crawl of it would delete "
            + "these documents.",
            schema(
                required("source_id", "string",
                    "A stable name for this batch's documents, used later to erase or re-index "
                    + "them. Reusing it adds to the same source."),
                requiredObjectArray("documents",
                    "The documents to index; at most 256 per call.",
                    required("document_id", "string",
                        "Names the document within its source. Sending the same one again "
                        + "replaces what is indexed under it."),
                    required("text", "string", "The document's text. Must not be empty.")))));

        tools.add(tool(INGESTION_STATUS,
            "How far indexing has got, for one source or for all of them. 'idle' with no error "
            + "means the run finished and indexed everything it listed. 'idle' with an error "
            + "means the run finished without indexing all of it - 'last_error' says how many "
            + "documents were dropped, and those documents are not searchable. 'unconfigured' "
            + "means the server has no ingestion "
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
                case SEARCH_DOCUMENTS -> searchDocuments(args);
                case SEARCH_GRAPH -> searchGraph(args);
                case BUILD_GRAPH -> buildGraph(args);
                case INGEST_DOCUMENTS -> ingestDocuments(args);
                case ADD_DOCUMENTS -> addDocuments(args);
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

    private ToolResult searchDocuments(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> body = Json.object();
        body.put("query", requiredString(args, "query"));
        // Always on for this caller. A model cannot resolve a chunk identifier against the store
        // the corpus was ingested from - it has no access to it - so a search that answered with
        // identifiers alone would give it nothing it could read. HTTP clients still opt in.
        body.put("include_text", true);
        // Omitted rather than defaulted here: the node has a configured default, and sending one
        // of this adapter's own would quietly override whatever the operator set.
        Integer topK = optionalInteger(args, "top_k");
        if (topK != null) {
            body.put("top_k", topK);
        }
        return answer(client.post("/v1/query", Json.write(body), null));
    }

    private ToolResult searchGraph(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> body = Json.object();
        body.put("query", requiredString(args, "query"));
        List<String> mentions = stringList(args, "context_entities");
        if (!mentions.isEmpty()) {
            body.put("context_entities", mentions);
        }
        // Omitted rather than defaulted: without one the node answers at the instant the
        // question implies, and sending "today" here would silently overrule that.
        String asOf = optionalString(args, "as_of");
        if (asOf != null) {
            body.put("as_of", asOf);
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
        List<String> excluded = stringList(args, "excluded_directories");
        if (!excluded.isEmpty()) {
            sourceConfig.put("excluded_directories", excluded);
        }
        Integer maxDepth = wholeNumber(args, "max_depth");
        if (maxDepth != null) {
            sourceConfig.put("max_depth", maxDepth);
        }

        Map<String, Object> body = Json.object();
        body.put("source_type", "filesystem");
        body.put("config", sourceConfig);
        return answer(client.post("/v1/ingest", Json.write(body), null));
    }

    private ToolResult addDocuments(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> body = Json.object();
        body.put("source_id", requiredString(args, "source_id"));
        // Refused here rather than sent on. The node would answer 400 anyway, and a round trip
        // to be told what this call already knows is a slower way to say the same thing.
        List<Object> documents = objectList(args, "documents");
        if (documents.isEmpty()) {
            return ToolResult.error(
                "'documents' is required and must carry at least one document.");
        }
        body.put("documents", documents);
        return answer(client.post("/v1/documents", Json.write(body), null));
    }

    /**
     * Reads a list-of-objects argument.
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The elements, empty when the field is absent or is not a list
     */
    private static List<Object> objectList(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Object> items = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element != null) {
                items.add(element);
            }
        }
        return List.copyOf(items);
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

    /**
     * An integer argument, or null when it is absent or is not a whole number.
     *
     * <p>JSON has one number type, so a client that means 5 may send {@code 5.0}; both are the
     * same value and both are accepted. A fractional one is not - it is not a count, and passing
     * it through as a truncation would answer a question nobody asked.</p>
     */
    private static Integer optionalInteger(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (!(value instanceof Number number)) {
            return null;
        }
        double exact = number.doubleValue();
        if (exact != Math.rint(exact) || exact < Integer.MIN_VALUE || exact > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Argument '" + field + "' must be a whole number: " + value);
        }
        return (int) exact;
    }

    /**
     * Reads an optional whole number, or null when the caller did not send one.
     *
     * <p>A model sends numbers as whatever its JSON layer produced, so a value that arrived as
     * a double with no fractional part is taken as the integer it is. Anything else is refused
     * here rather than forwarded, because the node's refusal would arrive one round trip later
     * saying the same thing.</p>
     */
    private static Integer wholeNumber(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Field '" + field + "' must be a whole number, got: " + text, e);
            }
        }
        throw new IllegalArgumentException(
            "Field '" + field + "' must be a whole number, got: " + value);
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

    /**
     * An array-of-objects property, with the shape of one element spelled out.
     *
     * <p>A model given a bare {@code array} guesses the field names of what goes in it, and the
     * guess is wrong often enough that the call fails on its first attempt.</p>
     *
     * @param name           The field name
     * @param description    What the array is for
     * @param itemProperties The properties of one element
     * @return The property
     */
    private static Property requiredObjectArray(String name, String description,
                                                Property... itemProperties) {
        Map<String, Object> schema = Json.object();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", schema(itemProperties));
        return new Property(name, schema, true);
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
