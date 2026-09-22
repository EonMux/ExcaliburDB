package com.eonmux.excalibur.api.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each tool sends to the node, and what it hands back to the model.
 *
 * <p>These are the two places the adapter can be wrong in a way nothing else catches: a request
 * body the endpoints do not parse, and an error the model reads as an answer.</p>
 */
@DisplayName("MCP tools")
class McpToolsTest {

    /** The entries of a JSON array, as strings AssertJ can compare against. */
    private static List<Object> names(Object array) {
        return List.copyOf((List<?>) array);
    }

    private static Object tool(String name) {
        for (Object described : McpTools.catalogue()) {
            if (name.equals(Json.stringAt(described, "name"))) {
                return described;
            }
        }
        throw new AssertionError("no tool named " + name);
    }

    @Test
    @DisplayName("every tool declares a name, a description and an object schema")
    void theCatalogueIsWellFormed() {
        List<Object> catalogue = McpTools.catalogue();

        assertThat(catalogue.stream().map(t -> Json.stringAt(t, "name")))
            .containsExactlyInAnyOrder(McpTools.SEARCH_DOCUMENTS, McpTools.SEARCH_GRAPH,
                McpTools.BUILD_GRAPH, McpTools.INGEST_DOCUMENTS, McpTools.ADD_DOCUMENTS,
                McpTools.INGESTION_STATUS, McpTools.CRAWL_WEBSITE, McpTools.DEREGISTER_SOURCE,
                McpTools.REINDEX_SOURCE, McpTools.REINDEX_STATUS, McpTools.GRAPH_BUILD_STATUS);
        for (Object described : catalogue) {
            assertThat(Json.stringAt(described, "name")).isNotBlank();
            assertThat(Json.stringAt(described, "description"))
                .as("the description is the only thing the model reads before calling")
                .isNotBlank();
            Map<String, Object> schema = Json.objectAt(described, "inputSchema");
            assertThat(schema).isNotNull();
            assertThat(schema.get("type")).isEqualTo("object");
            assertThat(schema.get("properties")).isInstanceOf(Map.class);
            assertThat(schema.get("required")).isInstanceOf(List.class);
        }
    }

    @Test
    @DisplayName("the catalogue is valid JSON, since a client parses it")
    void theCatalogueSerialises() {
        String written = Json.write(Map.of("tools", McpTools.catalogue()));

        assertThat(Json.at(Json.parse(written), "tools")).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("search_graph requires a query and offers disambiguation")
    void searchGraphSchema() {
        Object described = tool(McpTools.SEARCH_GRAPH);
        Map<String, Object> schema = Json.objectAt(described, "inputSchema");

        assertThat(names(schema.get("required"))).containsExactly("query");
        assertThat(Json.objectAt(schema, "properties"))
            .containsKeys("query", "context_entities", "namespace");
        assertThat(Json.stringAt(described, "description"))
            .as("a model must not turn an abstention into a guess")
            .contains("declined");
    }

    @Test
    @DisplayName("search_documents requires a question and bounds how much comes back")
    void searchDocumentsSchema() {
        Object described = tool(McpTools.SEARCH_DOCUMENTS);
        Map<String, Object> schema = Json.objectAt(described, "inputSchema");

        assertThat(names(schema.get("required"))).containsExactly("query");
        assertThat(Json.objectAt(schema, "properties")).containsKeys("query", "top_k");
        assertThat(Json.stringAt(described, "description"))
            .as("a model must know this works without a graph having been built")
            .contains("build_graph");
        assertThat(Json.stringAt(described, "description"))
            .as("the score's scale depends on which signals answered, so a model told it is a "
                + "similarity will compare numbers that share no scale")
            .contains("'retrieval'");
        assertThat(Json.stringAt(described, "description"))
            .as("the reply carries the passage text, and a model told otherwise will go looking "
                + "for content it was already given")
            .contains("text");
        assertThat(Json.stringAt(described, "description"))
            .as("a passage past the node's bound is clipped, and a model that cannot tell a "
                + "clipped passage from a whole one will quote the clipped one as the whole one")
            .contains("text_truncated");
    }

    /**
     * A search that answers with bare identifiers is half an answer to a model: the identifiers
     * name chunks in a store it has no way to read. The adapter asks for the text because the
     * model cannot ask for it afterwards.
     */
    @Test
    @DisplayName("a search asks the node for the passage text, not just the identifiers")
    void searchDocumentsAsksForText() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(200, "{\"results\":[],\"coverage\":{}}");
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.SEARCH_DOCUMENTS, Map.of("query", "analytical engine"));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "include_text"))
                .isEqualTo(Boolean.TRUE);
        }
    }

    @Test
    @DisplayName("a search reaches the query endpoint as the body it parses")
    void searchDocumentsPostsTheQuestion() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(200, "{\"results\":[],\"coverage\":{}}");
            McpTools tools = new McpTools(node.client(Map.of()));

            McpTools.ToolResult result = tools.call(McpTools.SEARCH_DOCUMENTS,
                Map.of("query", "analytical engine", "top_k", 3));

            StubNode.Received request = node.onlyRequest();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/query");
            Object body = Json.parse(request.body());
            assertThat(Json.stringAt(body, "query")).isEqualTo("analytical engine");
            assertThat(Json.at(body, "top_k")).isEqualTo(3L);
            assertThat(result.isError()).isFalse();
        }
    }

    @Test
    @DisplayName("a search with no top_k lets the node apply its own default")
    void searchDocumentsOmitsAnUnsetTopK() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.SEARCH_DOCUMENTS, Map.of("query", "analytical engine"));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "top_k"))
                .as("the node's configured default must not be overridden by this adapter")
                .isNull();
        }
    }

    @Test
    @DisplayName("ingest_documents requires the source and the directory")
    void ingestSchema() {
        Map<String, Object> schema = Json.objectAt(tool(McpTools.INGEST_DOCUMENTS), "inputSchema");

        assertThat(names(schema.get("required")))
            .containsExactlyInAnyOrder("source_id", "root_path");
    }

    @Test
    @DisplayName("a question reaches the graph endpoint as the body it parses")
    void searchGraphPostsTheQuestion() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(200, "{\"status\":\"answered\",\"context\":\"# facts\"}");
            McpTools tools = new McpTools(node.client(Map.of()));

            McpTools.ToolResult result = tools.call(McpTools.SEARCH_GRAPH,
                Map.of("query", "Who did Ada work with?"));

            StubNode.Received request = node.onlyRequest();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/graph/query");
            assertThat(Json.stringAt(Json.parse(request.body()), "query"))
                .isEqualTo("Who did Ada work with?");
            assertThat(result.isError()).isFalse();
            assertThat(result.text()).contains("answered");
        }
    }

    @Test
    @DisplayName("a question containing quotes arrives intact")
    void aQuotedQuestionSurvivesTheRoundTrip() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));
            String question = "What is the \"analytical engine\", and who named it?";

            tools.call(McpTools.SEARCH_GRAPH, Map.of("query", question));

            assertThat(Json.stringAt(Json.parse(node.onlyRequest().body()), "query"))
                .isEqualTo(question);
        }
    }

    @Test
    @DisplayName("disambiguation candidates are passed back as the endpoint expects")
    void contextEntitiesArePassedThrough() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.SEARCH_GRAPH, Map.of(
                "query", "What did she build?",
                "context_entities", List.of("Ada Lovelace", "Charles Babbage")));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "context_entities"))
                .isEqualTo(List.of("Ada Lovelace", "Charles Babbage"));
        }
    }

    @Test
    @DisplayName("an as-of instant reaches the graph endpoint")
    void asOfIsPassedThrough() throws IOException {
        // The graph is bitemporal and the HTTP route takes an instant; without this the model
        // could only ask about now, or hope the classifier read a date out of its wording.
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.SEARCH_GRAPH, Map.of(
                "query", "Where did Ada Lovelace live?", "as_of", "1843-01-01"));

            assertThat(Json.stringAt(Json.parse(node.onlyRequest().body()), "as_of"))
                .isEqualTo("1843-01-01");
        }
    }

    @Test
    @DisplayName("a query with no as-of omits the field, so the node decides")
    void noAsOfIsOmitted() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of()))
                .call(McpTools.SEARCH_GRAPH, Map.of("query", "anything"));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "as_of")).isNull();
        }
    }

    @Test
    @DisplayName("a query with no context entities omits the field rather than sending an empty one")
    void emptyContextEntitiesAreOmitted() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of()))
                .call(McpTools.SEARCH_GRAPH, Map.of("query", "anything"));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "context_entities"))
                .isNull();
        }
    }

    @Test
    @DisplayName("ingest_documents sends the source body the endpoint parses")
    void ingestSendsTheSourceBody() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.INGEST_DOCUMENTS, Map.of(
                "source_id", "docs",
                "root_path", "/srv/docs",
                "included_extensions", List.of(".md", ".txt")));

            StubNode.Received request = node.onlyRequest();
            assertThat(request.path()).isEqualTo("/v1/ingest");
            Object body = Json.parse(request.body());
            assertThat(Json.stringAt(body, "source_type")).isEqualTo("filesystem");

            Map<String, Object> config = Json.objectAt(body, "config");
            assertThat(config).isNotNull();
            assertThat(config.get("source_id")).isEqualTo("docs");
            assertThat(config.get("root_path")).isEqualTo("/srv/docs");
            assertThat(config.get("included_extensions")).isEqualTo(List.of(".md", ".txt"));
        }
    }

    @Test
    @DisplayName("ingestion_status asks about one source when named, and all of them otherwise")
    void statusScopesItsQuery() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.INGESTION_STATUS, Map.of("source_id", "docs"));
            tools.call(McpTools.INGESTION_STATUS, Map.of());

            List<StubNode.Received> requests = node.requests();
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0).method()).isEqualTo("GET");
            assertThat(requests.get(0).query()).isEqualTo("source_id=docs");
            assertThat(requests.get(1).query()).isNull();
        }
    }

    @Test
    @DisplayName("build_graph posts, because it changes what is served")
    void buildPosts() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.BUILD_GRAPH, Map.of());

            assertThat(node.onlyRequest().method()).isEqualTo("POST");
            assertThat(node.onlyRequest().path()).isEqualTo("/v1/graph/build");
        }
    }

    @Test
    @DisplayName("build_graph tells the model the build is only accepted, not finished")
    void buildGraphDescribesItsOwnAsynchrony() {
        // The route answers 202 and runs behind the response. A description promising the
        // published counts leaves a model believing a build it never waited for is done.
        String description = Json.stringAt(tool(McpTools.BUILD_GRAPH), "description");

        assertThat(description).contains(McpTools.GRAPH_BUILD_STATUS);
        assertThat(description)
            .as("the old wording promised the counts came back from this call")
            .doesNotContain("Returns how many edges, entities and relations were published");
    }

    @Test
    @DisplayName("graph_build_status reads the build's outcome, scoped to the namespace")
    void graphBuildStatusReadsTheOutcome() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of()))
                .call(McpTools.GRAPH_BUILD_STATUS, Map.of("namespace", "acme"));

            assertThat(node.onlyRequest().method()).isEqualTo("GET");
            assertThat(node.onlyRequest().path()).isEqualTo("/v1/graph/build/status");
            assertThat(node.onlyRequest().headers())
                .as("a build is per namespace, so its status must be asked for per namespace")
                .containsEntry(ExcaliburClient.NAMESPACE_HEADER, "acme");
        }
    }

    @Test
    @DisplayName("the namespace argument scopes the call, and the environment sets the default")
    void namespacesAreCarriedInTheHeader() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(
                node.client(Map.of("EXCALIBUR_NAMESPACE", "from-environment")));

            tools.call(McpTools.SEARCH_GRAPH, Map.of("query", "q"));
            tools.call(McpTools.SEARCH_GRAPH,
                Map.of("query", "q", "namespace", "from-argument"));

            List<StubNode.Received> requests = node.requests();
            assertThat(requests.get(0).headers())
                .containsEntry(ExcaliburClient.NAMESPACE_HEADER, "from-environment");
            assertThat(requests.get(1).headers())
                .as("an explicit namespace wins over the default")
                .containsEntry(ExcaliburClient.NAMESPACE_HEADER, "from-argument");
        }
    }

    @Test
    @DisplayName("the bearer token is sent when the node needs one")
    void theTokenIsSent() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of("EXCALIBUR_API_AUTH_TOKEN", "secret-value")))
                .call(McpTools.BUILD_GRAPH, Map.of());

            assertThat(node.onlyRequest().headers())
                .containsEntry("Authorization", "Bearer secret-value");
        }
    }

    @Test
    @DisplayName("no token configured sends no header, rather than an empty one")
    void noTokenSendsNoHeader() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.BUILD_GRAPH, Map.of());

            assertThat(node.onlyRequest().headers()).doesNotContainKey("Authorization");
        }
    }

    @Test
    @DisplayName("a rejection is reported as an error, carrying what the node said")
    void rejectionsAreErrors() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(403, "{\"error\":\"root_path is not inside an allowed root\"}");

            McpTools.ToolResult result = new McpTools(node.client(Map.of()))
                .call(McpTools.INGEST_DOCUMENTS,
                    Map.of("source_id", "docs", "root_path", "/etc"));

            // Summarising this in the client would lose the one sentence that says what to fix.
            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("403").contains("allowed root");
        }
    }

    @Test
    @DisplayName("a missing required argument is refused before a request is made")
    void missingArgumentsAreRefused() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            assertThat(tools.call(McpTools.SEARCH_GRAPH, Map.of()).isError()).isTrue();
            assertThat(tools.call(McpTools.SEARCH_GRAPH, Map.of("query", "  ")).isError()).isTrue();
            assertThat(tools.call(McpTools.INGEST_DOCUMENTS, Map.of("source_id", "d")).isError())
                .isTrue();
            assertThat(node.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("an unknown tool is an error, not a silent no-op")
    void unknownToolsAreRefused() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools.ToolResult result =
                new McpTools(node.client(Map.of())).call("delete_everything", Map.of());

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("No such tool");
        }
    }

    @Test
    @DisplayName("a node that is not running says so, and says where it was looked for")
    void anUnreachableNodeIsDiagnosed() throws IOException {
        StubNode node = StubNode.start();
        String url = node.url();
        node.close();

        McpTools.ToolResult result = new McpTools(
            ExcaliburClient.fromEnvironment(Map.of("EXCALIBUR_URL", url)))
            .call(McpTools.BUILD_GRAPH, Map.of());

        // The commonest failure of all, and useless to a model unless it names the address.
        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains(url).contains("EXCALIBUR_URL");
    }

    @Test
    @DisplayName("the client refuses configuration it cannot use")
    void badConfigurationIsRefused() {
        assertThat(ExcaliburClient.fromEnvironment(Map.of()).baseUri().toString())
            .isEqualTo(ExcaliburClient.DEFAULT_BASE_URL);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> ExcaliburClient.fromEnvironment(Map.of("EXCALIBUR_URL", "not-a-url")))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> ExcaliburClient.fromEnvironment(Map.of("EXCALIBUR_URL", "ftp://host/")))
            .as("caught at startup, not on whichever tool the model tries first")
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> ExcaliburClient.fromEnvironment(Map.of("EXCALIBUR_URL", "file:///tmp/x")))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> ExcaliburClient.fromEnvironment(Map.of("EXCALIBUR_MCP_TIMEOUT_SECONDS", "0")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code ingest_documents} registers a directory to crawl, and a crawl target must be inside
     * an allow-listed root. A model that holds the text and has no allow-listed directory to put
     * it in cannot index anything at all through that tool - which on a default node is every
     * model. This is the other direction.
     */
    @Test
    @DisplayName("add_documents describes supplying content rather than a directory to crawl")
    void addDocumentsSchema() {
        Object described = tool(McpTools.ADD_DOCUMENTS);
        Map<String, Object> schema = Json.objectAt(described, "inputSchema");

        assertThat(names(schema.get("required"))).containsExactlyInAnyOrder("source_id", "documents");
        Map<String, Object> documents = Json.objectAt(Json.objectAt(schema, "properties"), "documents");
        assertThat(Json.stringAt(documents, "type")).isEqualTo("array");
        assertThat(Json.objectAt(Json.objectAt(documents, "items"), "properties"))
            .as("a model has to be told what one document looks like, or it will guess the fields")
            .containsKeys("document_id", "text");
        assertThat(Json.stringAt(described, "description"))
            .as("the call indexes inside the request, so a model must not poll for it the way "
                + "ingest_documents requires")
            .contains("searchable");
    }

    @Test
    @DisplayName("adding documents reaches the supply route as the body it parses")
    void addDocumentsPostsTheBatch() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(200, "{\"documents_ingested\":1}");
            McpTools tools = new McpTools(node.client(Map.of()));

            McpTools.ToolResult result = tools.call(McpTools.ADD_DOCUMENTS, Map.of(
                "source_id", "notes",
                "documents", java.util.List.of(
                    Map.of("document_id", "a.md", "text", "alpha"))));

            StubNode.Received request = node.onlyRequest();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/documents");
            Object body = Json.parse(request.body());
            assertThat(Json.stringAt(body, "source_id")).isEqualTo("notes");
            assertThat(request.body()).contains("a.md").contains("alpha");
            assertThat(result.isError()).isFalse();
        }
    }

    @Test
    @DisplayName("adding documents refuses a batch with no documents rather than calling the node")
    void addDocumentsRefusesAnEmptyBatch() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            McpTools.ToolResult result = tools.call(McpTools.ADD_DOCUMENTS,
                Map.of("source_id", "notes", "documents", java.util.List.of()));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("documents");
        }
    }
}
