package com.eonmux.excalibur.api.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.eonmux.excalibur.api.endpoint.SourceFactory;

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
                McpTools.REINDEX_SOURCE, McpTools.REINDEX_STATUS, McpTools.GRAPH_BUILD_STATUS,
                McpTools.FIND_GRAPH_ENTITIES, McpTools.GRAPH_ENTITY_EDGES,
                McpTools.ASSERT_GRAPH_FACTS, McpTools.LIST_GRAPH_RELATIONS,
                McpTools.LIST_DOCUMENTS, McpTools.FETCH_DOCUMENTS, McpTools.LIST_NAMESPACES,
                McpTools.CREATE_NAMESPACE, McpTools.LIST_QUERIES, McpTools.CANCEL_QUERY,
                McpTools.CANCEL_INGESTION, McpTools.CANCEL_REINDEX, McpTools.CANCEL_COMPACTION,
                McpTools.CANCEL_GRAPH_BUILD, McpTools.ERASE_DOCUMENTS, McpTools.DELETE_NAMESPACE,
                McpTools.READ_DOCUMENT, McpTools.ERASURE_STATUS, McpTools.COMPACT,
                McpTools.COMPACTION_STATUS, McpTools.LIST_SOURCES, McpTools.GRAPH_ENTITY);
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
            .containsKeys("query", "context_entities", "namespace", "direction");
        assertThat(Json.stringAt(described, "description"))
            .as("a model must not turn an abstention into a guess")
            .contains("declined");
        assertThat(Json.stringAt(described, "description"))
            .as("a model has to know an ambiguity comes back as candidates to choose between, "
                + "and that a computed count is there to be used rather than recounted")
            .contains("needs_input").contains("candidates").contains("aggregate");
    }

    @Test
    @DisplayName("search_graph lists the three directions, so a misspelt one fails validation")
    void searchGraphDirectionIsEnumerated() {
        Map<String, Object> direction = Json.objectAt(
            Json.objectAt(Json.objectAt(tool(McpTools.SEARCH_GRAPH), "inputSchema"),
                "properties"),
            "direction");

        assertThat(direction.get("type")).isEqualTo("string");
        assertThat(names(direction.get("enum"))).containsExactly("forward", "reverse", "both");
        assertThat(names(Json.objectAt(tool(McpTools.SEARCH_GRAPH), "inputSchema")
            .get("required"))).doesNotContain("direction");
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
    @DisplayName("a search confined to sources sends them, and an unconfined one sends none")
    void searchDocumentsForwardsSources() throws IOException {
        assertThat(Json.objectAt(Json.objectAt(tool(McpTools.SEARCH_DOCUMENTS), "inputSchema"),
            "properties")).containsKey("source_ids");
        try (StubNode node = StubNode.start()) {
            node.answerWith(200, "{\"results\":[],\"coverage\":{}}");
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.SEARCH_DOCUMENTS,
                Map.of("query", "analytical engine", "source_ids", List.of("team", "team:wiki")));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "source_ids"))
                .isEqualTo(List.of("team", "team:wiki"));
        }
        try (StubNode node = StubNode.start()) {
            node.answerWith(200, "{\"results\":[],\"coverage\":{}}");
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.SEARCH_DOCUMENTS, Map.of("query", "analytical engine"));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "source_ids"))
                .as("omitted, so the node searches every source")
                .isNull();
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
    @DisplayName("search_graph describes a context entity as a name, or an object with a shape and turn")
    void contextEntitySchemaNamesShapeAndTurn() {
        Map<String, Object> property = Json.objectAt(
            Json.objectAt(Json.objectAt(tool(McpTools.SEARCH_GRAPH), "inputSchema"),
                "properties"),
            "context_entities");

        assertThat(property.get("type")).isEqualTo("array");
        List<?> alternatives = (List<?>) Json.objectAt(property, "items").get("anyOf");
        assertThat(alternatives).hasSize(2);
        assertThat(Json.stringAt(alternatives.get(0), "type")).isEqualTo("string");
        Map<String, Object> entity = Json.objectAt(alternatives.get(1), "properties");
        assertThat(names(Json.objectAt(entity, "grammar").get("enum")))
            .containsExactly("singular_person", "singular_thing", "plural", "unknown");
        assertThat(Json.objectAt(entity, "turn").get("type")).isEqualTo("integer");
        assertThat(((Number) Json.objectAt(entity, "turn").get("minimum")).intValue()).isZero();
        assertThat(names(((Map<?, ?>) alternatives.get(1)).get("required")))
            .containsExactly("name");
    }

    @Test
    @DisplayName("entity objects reach the endpoint with their shape spelled as the node reads it")
    void contextEntityObjectsArePassedThrough() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                McpTools.SEARCH_GRAPH, Map.of(
                    "query", "What did she write?",
                    "context_entities", List.of(
                        Map.of("name", " Charles Babbage ", "grammar", "Singular_Person",
                            "turn", 0),
                        Map.of("name", "Ada Lovelace", "grammar", "singular_person",
                            "turn", 1.0))));

            assertThat(result.isError()).as(result.text()).isFalse();
            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "context_entities"))
                .isEqualTo(List.of(
                    Map.of("name", " Charles Babbage ", "grammar", "singular_person", "turn", 0L),
                    Map.of("name", "Ada Lovelace", "grammar", "singular_person", "turn", 1L)));
        }
    }

    @Test
    @DisplayName("names mixed with objects are all sent as objects, which the node accepts")
    void mixedContextEntitiesBecomeObjects() throws IOException {
        // The node refuses a mixed array; a model is not made to rewrite the names it has no
        // shape for.
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.SEARCH_GRAPH, Map.of(
                "query", "What did she write?",
                "context_entities", List.of(
                    "Analytical Engine", " ", Map.of("name", "Ada Lovelace", "turn", 2))));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "context_entities"))
                .isEqualTo(List.of(
                    Map.of("name", "Analytical Engine"),
                    Map.of("name", "Ada Lovelace", "turn", 2L)));
        }
    }

    @Test
    @DisplayName("a context entity the node would refuse is a tool error, and nothing is sent")
    void badContextEntitiesAreToolErrors() throws IOException {
        List<Object> bad = List.of(
            Map.of("grammar", "plural"),
            Map.of("name", "  "),
            Map.of("name", "Ada Lovelace", "grammar", "person"),
            Map.of("name", "Ada Lovelace", "grammar", 1),
            Map.of("name", "Ada Lovelace", "grammar", " "),
            Map.of("name", "Ada Lovelace", "turn", -1),
            Map.of("name", "Ada Lovelace", "turn", 1.5),
            Map.of("name", "Ada Lovelace", "turn", Integer.MAX_VALUE),
            Map.of("name", "Ada Lovelace", "gramar", "singular_person"),
            7);
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));
            for (Object entity : bad) {
                McpTools.ToolResult result = tools.call(McpTools.SEARCH_GRAPH, Map.of(
                    "query", "What did she write?",
                    "context_entities", List.of("Charles Babbage", entity)));

                assertThat(result.isError()).as("%s", entity).isTrue();
                assertThat(result.text()).as("%s", entity)
                    .contains("'context_entities' element 1");
            }
            assertThat(node.requests()).isEmpty();
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
    @DisplayName("a direction reaches the graph endpoint as its wire name")
    void directionIsPassedThrough() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.SEARCH_GRAPH, Map.of(
                "query", "What did Acme acquire?", "direction", " Forward "));

            assertThat(Json.stringAt(Json.parse(node.onlyRequest().body()), "direction"))
                .isEqualTo("forward");
        }
    }

    @Test
    @DisplayName("a query with no direction omits the field, so the node walks both ways")
    void noDirectionIsOmitted() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of()))
                .call(McpTools.SEARCH_GRAPH, Map.of("query", "anything"));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "direction")).isNull();
        }
    }

    @Test
    @DisplayName("a direction that is not one of the three is a tool error, and nothing is sent")
    void unknownDirectionIsAToolError() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                McpTools.SEARCH_GRAPH, Map.of("query", "anything", "direction", "outgoing"));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("direction").contains("outgoing")
                .contains("forward");
            assertThat(node.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("a direction that is not a string is a tool error, and nothing is sent")
    void nonStringDirectionIsAToolError() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                McpTools.SEARCH_GRAPH, Map.of("query", "anything", "direction", 1));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("'direction' must be a string");
            assertThat(node.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("a query with no context entities omits the field rather than sending an empty one")
    void absentContextEntitiesAreOmitted() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of()))
                .call(McpTools.SEARCH_GRAPH, Map.of("query", "anything"));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "context_entities"))
                .isNull();
        }
    }

    @Test
    @DisplayName("search_graph forwards timeout_ms as a whole number, and only when given")
    void searchGraphForwardsItsDeadline() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.SEARCH_GRAPH, Map.of("query", "anything", "timeout_ms", 2500.0));
            tools.call(McpTools.SEARCH_GRAPH, Map.of("query", "anything"));

            assertThat(((Number) Json.at(Json.parse(node.requests().get(0).body()), "timeout_ms"))
                .longValue()).isEqualTo(2500L);
            assertThat(Json.at(Json.parse(node.requests().get(1).body()), "timeout_ms")).isNull();
            assertThat(Json.objectAt(Json.objectAt(tool(McpTools.SEARCH_GRAPH), "inputSchema"),
                "properties")).containsKey("timeout_ms");
        }
    }

    @Test
    @DisplayName("an empty context_entities is forwarded as the empty array it is")
    void emptyContextEntitiesAreForwarded() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.SEARCH_GRAPH,
                Map.of("query", "anything", "context_entities", List.of()));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "context_entities"))
                .isEqualTo(List.of());
        }
    }

    @Test
    @DisplayName("names are forwarded exactly as sent, blank ones included, for the node to read")
    void contextNamesAreForwardedVerbatim() throws IOException {
        // The node reads a blank name as mentioning nobody. Deciding that here as well would
        // make the tool the second of two places that rule, and the body sent would no longer
        // be the one the model wrote.
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.SEARCH_GRAPH, Map.of(
                "query", "What did she write?",
                "context_entities", List.of(" Ada Lovelace ", " ")));

            assertThat(Json.at(Json.parse(node.onlyRequest().body()), "context_entities"))
                .isEqualTo(List.of(" Ada Lovelace ", " "));
        }
    }

    @Test
    @DisplayName("a null context entity is a tool error, as the same array is a 400 over HTTP")
    void aNullContextEntityIsAToolError() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                McpTools.SEARCH_GRAPH, Map.of("query", "What did she write?",
                    "context_entities", java.util.Arrays.asList("Ada Lovelace", null)));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("'context_entities' element 1");
            assertThat(node.requests()).isEmpty();
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
    @DisplayName("ingest_documents sends empty lists, which the node reads as clearing a default")
    void ingestSendsEmptyLists() throws IOException {
        try (StubNode node = StubNode.start()) {
            // Over HTTP an empty excluded_directories clears the default skip list, so the walk
            // descends into node_modules and target as well. Dropping it here would register a
            // different source than the model asked for, and say it had succeeded.
            new McpTools(node.client(Map.of())).call(McpTools.INGEST_DOCUMENTS, Map.of(
                "source_id", "everything",
                "root_path", "/srv/everything",
                "included_extensions", List.of(),
                "excluded_directories", List.of()));

            Map<String, Object> config =
                Json.objectAt(Json.parse(node.onlyRequest().body()), "config");
            assertThat(config).containsEntry("included_extensions", List.of());
            assertThat(config).containsEntry("excluded_directories", List.of());
        }
    }

    @Test
    @DisplayName("list settings are forwarded element for element, blank ones included")
    void listElementsAreForwardedVerbatim() throws IOException {
        // [""] is not the same body as []. What the node makes of a blank element is the node's
        // to decide, and a tool that decided it first would register a source from a body the
        // model never wrote.
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));
            tools.call(McpTools.INGEST_DOCUMENTS, Map.of(
                "source_id", "docs",
                "root_path", "/srv/docs",
                "included_extensions", List.of(""),
                "excluded_directories", List.of(" vendor ", "  ")));
            tools.call(McpTools.CRAWL_WEBSITE, Map.of(
                "source_id", "site",
                "base_url", "https://example.com",
                "excluded_path_patterns", List.of("")));

            Map<String, Object> ingest =
                Json.objectAt(Json.parse(node.requests().get(0).body()), "config");
            assertThat(ingest).containsEntry("included_extensions", List.of(""));
            assertThat(ingest).containsEntry("excluded_directories", List.of(" vendor ", "  "));
            Map<String, Object> crawl =
                Json.objectAt(Json.parse(node.requests().get(1).body()), "config");
            assertThat(crawl).containsEntry("excluded_path_patterns", List.of(""));
        }
    }

    @Test
    @DisplayName("a list setting holding a null is a tool error, and nothing is sent")
    void aNullListElementIsAToolError() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                McpTools.INGEST_DOCUMENTS, Map.of("source_id", "docs", "root_path", "/srv/docs",
                    "excluded_directories", java.util.Arrays.asList("vendor", null)));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("excluded_directories");
            assertThat(node.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("ingest_documents leaves a list the model did not send absent")
    void ingestLeavesAbsentListsAbsent() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.INGEST_DOCUMENTS, Map.of(
                "source_id", "docs",
                "root_path", "/srv/docs",
                "excluded_directories", List.of("vendor")));

            Map<String, Object> config =
                Json.objectAt(Json.parse(node.onlyRequest().body()), "config");
            assertThat(config).containsEntry("excluded_directories", List.of("vendor"));
            assertThat(config)
                .as("absent keeps the node's default, which is not the same as empty")
                .doesNotContainKey("included_extensions");
        }
    }

    @Test
    @DisplayName("ingest_documents offers the encoding a directory's files are read in")
    void ingestOffersTheCharset() {
        Map<String, Object> schema = Json.objectAt(tool(McpTools.INGEST_DOCUMENTS), "inputSchema");
        Map<String, Object> charset = Json.objectAt(Json.objectAt(schema, "properties"), "charset");

        assertThat(charset).isNotNull();
        assertThat(charset.get("type")).isEqualTo("string");
        assertThat(Json.stringAt(charset, "description"))
            .as("the model has to know a byte-order mark overrides what it declares")
            .containsIgnoringCase("byte-order mark");
        assertThat(names(schema.get("required"))).doesNotContain("charset");
    }

    @Test
    @DisplayName("ingest_documents forwards a charset as it was given, and none when absent")
    void ingestForwardsTheCharset() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.INGEST_DOCUMENTS, Map.of("source_id", "exports",
                "root_path", "/srv/exports", "charset", " windows-1252 "));
            tools.call(McpTools.INGEST_DOCUMENTS,
                Map.of("source_id", "docs", "root_path", "/srv/docs"));
            tools.call(McpTools.INGEST_DOCUMENTS,
                Map.of("source_id", "blank", "root_path", "/srv/blank", "charset", "  "));

            List<StubNode.Received> requests = node.requests();
            assertThat(requests).hasSize(3);
            assertThat(Json.objectAt(Json.parse(requests.get(0).body()), "config"))
                .containsEntry("charset", "windows-1252");
            assertThat(Json.objectAt(Json.parse(requests.get(1).body()), "config"))
                .as("absent, so the node reads the directory as UTF-8")
                .doesNotContainKey("charset");
            assertThat(Json.objectAt(Json.parse(requests.get(2).body()), "config"))
                .as("blank is no declaration, not a name for the node to refuse")
                .doesNotContainKey("charset");
        }
    }

    @Test
    @DisplayName("ingest_documents refuses a charset that is not a string, before sending")
    void ingestRefusesANonStringCharset() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                McpTools.INGEST_DOCUMENTS,
                Map.of("source_id", "d", "root_path", "/srv", "charset", 1252));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("charset");
            assertThat(node.requests()).isEmpty();
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
    @DisplayName("graph_build_status explains withheld facts and a superseded automatic build")
    void graphBuildStatusDescribesErasureOutcomes() {
        // Both are outcomes a model would otherwise read as trouble: facts missing from a build
        // that published, and an automatic build that ended without publishing.
        String description = Json.stringAt(tool(McpTools.GRAPH_BUILD_STATUS), "description");

        assertThat(description).contains("withheld_facts").contains("superseded")
            .contains("automatic.last_result");
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

    @Test
    @DisplayName("adding documents refuses a null document rather than sending the rest")
    void addDocumentsRefusesANullDocument() throws IOException {
        // Over HTTP the same array is a 400. Dropping the null and sending the others would
        // report a batch as added that is one short of what the model sent.
        try (StubNode node = StubNode.start()) {
            McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                McpTools.ADD_DOCUMENTS, Map.of("source_id", "notes", "documents",
                    java.util.Arrays.asList(Map.of("document_id", "a.md", "content", "alpha"),
                        null)));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("'documents'").contains("element 1");
            assertThat(node.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("the default namespace reaches only the routes that honour one")
    void theDefaultNamespaceStaysOffCorpusRoutes() throws IOException {
        // The node answers 400 to a namespace on every corpus route. A client configured for a
        // tenant must still be able to search, list status and re-crawl.
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of("EXCALIBUR_NAMESPACE", "acme")));

            tools.call(McpTools.SEARCH_DOCUMENTS, Map.of("query", "q"));
            tools.call(McpTools.INGESTION_STATUS, Map.of());
            tools.call(McpTools.REINDEX_SOURCE, Map.of("source_id", "docs"));
            tools.call(McpTools.ADD_DOCUMENTS, Map.of("source_id", "s",
                "documents", List.of(Map.of("document_id", "d", "text", "t"))));
            tools.call(McpTools.INGEST_DOCUMENTS,
                Map.of("source_id", "docs", "root_path", "/srv/docs"));

            List<StubNode.Received> requests = node.requests();
            assertThat(requests).hasSize(5);
            for (StubNode.Received corpus : requests.subList(0, 4)) {
                assertThat(corpus.headers())
                    .as(corpus.method() + " " + corpus.path())
                    .doesNotContainKey(ExcaliburClient.NAMESPACE_HEADER);
            }
            assertThat(requests.get(4).headers())
                .as("registration records the namespace a source stages its facts into")
                .containsEntry(ExcaliburClient.NAMESPACE_HEADER, "acme");
        }
    }

    @Test
    @DisplayName("registering a source can name its namespace")
    void registrationTakesANamespace() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.CRAWL_WEBSITE, Map.of("source_id", "site",
                "base_url", "https://example.com", "namespace", "acme"));
            tools.call(McpTools.INGEST_DOCUMENTS,
                Map.of("source_id", "docs", "root_path", "/srv/docs"));

            List<StubNode.Received> requests = node.requests();
            assertThat(requests.get(0).headers())
                .containsEntry(ExcaliburClient.NAMESPACE_HEADER, "acme");
            assertThat(Json.objectAt(Json.parse(requests.get(0).body()), "config"))
                .as("the namespace is a header, not a source setting the node would refuse")
                .doesNotContainKey("namespace");
            assertThat(requests.get(1).headers())
                .as("with no default configured, no namespace is sent")
                .doesNotContainKey(ExcaliburClient.NAMESPACE_HEADER);
        }
    }

    @Test
    @DisplayName("an argument of the wrong type is refused, not dropped")
    void wrongTypesAreRefused() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            McpTools.ToolResult topK = tools.call(McpTools.SEARCH_DOCUMENTS,
                Map.of("query", "q", "top_k", "many"));
            McpTools.ToolResult excluded = tools.call(McpTools.CRAWL_WEBSITE, Map.of("source_id", "s",
                "base_url", "https://example.com", "excluded_path_patterns", "/api"));
            McpTools.ToolResult namespace = tools.call(McpTools.BUILD_GRAPH, Map.of("namespace", 7));
            McpTools.ToolResult element = tools.call(McpTools.INGEST_DOCUMENTS, Map.of("source_id", "d",
                "root_path", "/srv", "included_extensions", List.of(".md", 3)));

            assertThat(topK.isError()).isTrue();
            assertThat(topK.text()).contains("top_k");
            assertThat(excluded.isError()).isTrue();
            assertThat(excluded.text()).contains("excluded_path_patterns");
            assertThat(namespace.isError()).isTrue();
            assertThat(element.isError()).isTrue();
            assertThat(node.requests()).isEmpty();
        }
    }

    /** The properties a tool's input schema declares. */
    private static Map<String, Object> properties(String toolName) {
        return Json.objectAt(Json.objectAt(tool(toolName), "inputSchema"), "properties");
    }

    /** The config object of the one registration a stub node received. */
    private static Map<String, Object> sentConfig(StubNode.Received request) {
        return Json.objectAt(Json.parse(request.body()), "config");
    }

    @Test
    @DisplayName("ingest_documents offers every setting a filesystem registration accepts")
    void ingestOffersEveryFilesystemSetting() {
        assertThat(properties(McpTools.INGEST_DOCUMENTS))
            .as("a setting the HTTP API accepts and this tool does not is one a model cannot "
                + "make, however plainly the operator's documentation describes it")
            .containsKeys(SourceFactory.ROOT_PATH, SourceFactory.INCLUDED_EXTENSIONS,
                SourceFactory.EXCLUDED_DIRECTORIES, SourceFactory.MAX_DEPTH,
                SourceFactory.FOLLOW_SYMLINKS, SourceFactory.INCLUDE_HIDDEN,
                SourceFactory.MAX_FILE_BYTES, SourceFactory.CHARSET);
    }

    @Test
    @DisplayName("crawl_website offers every setting an HTTP registration accepts")
    void crawlOffersEveryHttpSetting() {
        assertThat(properties(McpTools.CRAWL_WEBSITE))
            .containsKeys(SourceFactory.BASE_URL, SourceFactory.MAX_DEPTH,
                SourceFactory.MAX_DOCUMENTS, SourceFactory.MAX_RESPONSE_BYTES,
                SourceFactory.TIMEOUT_SECONDS, SourceFactory.INCLUDED_PATH_PATTERNS,
                SourceFactory.EXCLUDED_PATH_PATTERNS, SourceFactory.USER_AGENT,
                SourceFactory.CRAWL_DELAY_MS, SourceFactory.RESPECT_ROBOTS_TXT);
    }

    @Test
    @DisplayName("the new filesystem settings are typed, so a validating client catches a slip")
    void ingestTypesTheWalkSettings() {
        Map<String, Object> properties = properties(McpTools.INGEST_DOCUMENTS);

        assertThat(Json.objectAt(properties, "follow_symlinks").get("type")).isEqualTo("boolean");
        assertThat(Json.objectAt(properties, "include_hidden").get("type")).isEqualTo("boolean");
        assertThat(Json.objectAt(properties, "max_file_bytes").get("type")).isEqualTo("integer");
        assertThat(Json.objectAt(properties(McpTools.CRAWL_WEBSITE), "crawl_delay_ms")
            .get("type")).isEqualTo("integer");
        assertThat(names(Json.objectAt(tool(McpTools.INGEST_DOCUMENTS), "inputSchema")
            .get("required"))).containsExactlyInAnyOrder("source_id", "root_path");
    }

    @Test
    @DisplayName("ingest_documents forwards the walk settings, typed as the endpoint parses them")
    void ingestForwardsTheWalkSettings() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.INGEST_DOCUMENTS, Map.of(
                "source_id", "docs",
                "root_path", "/srv/docs",
                "follow_symlinks", true,
                "include_hidden", false,
                "max_file_bytes", 1_048_576));

            Map<String, Object> config = sentConfig(node.onlyRequest());
            assertThat(config).containsEntry("follow_symlinks", true);
            assertThat(config)
                .as("false is a setting, not an absence: it is forwarded")
                .containsEntry("include_hidden", false);
            assertThat(config).containsEntry("max_file_bytes", 1_048_576L);
        }
    }

    @Test
    @DisplayName("ingest_documents leaves an unset walk setting to the node's default")
    void ingestOmitsUnsetWalkSettings() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.INGEST_DOCUMENTS,
                Map.of("source_id", "docs", "root_path", "/srv/docs"));

            assertThat(sentConfig(node.onlyRequest()))
                .doesNotContainKeys("follow_symlinks", "include_hidden", "max_file_bytes",
                    "included_extensions", "excluded_directories", "max_depth");
        }
    }

    @Test
    @DisplayName("max_file_bytes reaches the node's ceiling unchanged")
    void ingestCarriesTheFileSizeCeiling() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.INGEST_DOCUMENTS, Map.of(
                "source_id", "docs", "root_path", "/srv/docs",
                "max_file_bytes", SourceFactory.FILE_BYTES_LIMIT));

            assertThat(sentConfig(node.onlyRequest()))
                .as("the node accepts up to its ceiling; refusing it here would make the "
                    + "ceiling one only the HTTP API could reach")
                .containsEntry("max_file_bytes", SourceFactory.FILE_BYTES_LIMIT);
        }
    }

    @Test
    @DisplayName("quoted scalars and whole-valued doubles are read as what they spell")
    void ingestToleratesQuotedScalars() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.INGEST_DOCUMENTS, Map.of(
                "source_id", "docs", "root_path", "/srv/docs",
                "follow_symlinks", " TRUE ",
                "include_hidden", "false",
                "max_file_bytes", 4096.0));

            Map<String, Object> config = sentConfig(node.onlyRequest());
            assertThat(config).containsEntry("follow_symlinks", true);
            assertThat(config).containsEntry("include_hidden", false);
            assertThat(config).containsEntry("max_file_bytes", 4096L);
        }
    }

    @Test
    @DisplayName("a walk setting of the wrong type is refused before anything is sent")
    void ingestRefusesMistypedWalkSettings() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            McpTools.ToolResult numericFlag = tools.call(McpTools.INGEST_DOCUMENTS, Map.of(
                "source_id", "d", "root_path", "/srv", "follow_symlinks", 1));
            McpTools.ToolResult wordFlag = tools.call(McpTools.INGEST_DOCUMENTS, Map.of(
                "source_id", "d", "root_path", "/srv", "include_hidden", "yes"));
            McpTools.ToolResult fraction = tools.call(McpTools.INGEST_DOCUMENTS, Map.of(
                "source_id", "d", "root_path", "/srv", "max_file_bytes", 1.5));
            McpTools.ToolResult word = tools.call(McpTools.INGEST_DOCUMENTS, Map.of(
                "source_id", "d", "root_path", "/srv", "max_file_bytes", "big"));

            assertThat(numericFlag.isError()).isTrue();
            assertThat(numericFlag.text()).contains("follow_symlinks");
            assertThat(wordFlag.isError()).isTrue();
            assertThat(wordFlag.text()).contains("include_hidden");
            assertThat(fraction.isError()).isTrue();
            assertThat(fraction.text()).contains("max_file_bytes");
            assertThat(word.isError()).isTrue();
            assertThat(word.text()).contains("max_file_bytes");
            assertThat(node.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("crawl_website forwards a crawl delay, and none when absent")
    void crawlForwardsTheCrawlDelay() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.CRAWL_WEBSITE, Map.of("source_id", "gentle",
                "base_url", "https://example.com", "crawl_delay_ms", 2000));
            tools.call(McpTools.CRAWL_WEBSITE, Map.of("source_id", "plain",
                "base_url", "https://example.com"));

            List<StubNode.Received> requests = node.requests();
            assertThat(requests).hasSize(2);
            assertThat(sentConfig(requests.get(0))).containsEntry("crawl_delay_ms", 2000L);
            assertThat(sentConfig(requests.get(1)))
                .as("absent, so the crawl runs at the deployment's own pace")
                .doesNotContainKey("crawl_delay_ms");
        }
    }

    @Test
    @DisplayName("crawl_website refuses a crawl delay that is not a whole number")
    void crawlRefusesAMistypedCrawlDelay() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                McpTools.CRAWL_WEBSITE, Map.of("source_id", "s",
                    "base_url", "https://example.com", "crawl_delay_ms", 0.5));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("crawl_delay_ms");
            assertThat(node.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("the crawl delay says it can only slow a crawl, never speed it up")
    void crawlDelayDescribesItsFloor() {
        assertThat(Json.stringAt(Json.objectAt(properties(McpTools.CRAWL_WEBSITE),
            "crawl_delay_ms"), "description"))
            .as("a model asking for 0 to go faster has to know it gets the node's pace instead")
            .containsIgnoringCase("minimum");
    }

    @Test
    @DisplayName("crawl_website forwards respect_robots_txt as sent, and nothing when absent")
    void crawlForwardsTheRobotsSetting() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.CRAWL_WEBSITE, Map.of("source_id", "opted-out",
                "base_url", "https://example.com", "respect_robots_txt", false));
            tools.call(McpTools.CRAWL_WEBSITE, Map.of("source_id", "explicit",
                "base_url", "https://example.com", "respect_robots_txt", true));
            tools.call(McpTools.CRAWL_WEBSITE, Map.of("source_id", "plain",
                "base_url", "https://example.com"));

            List<StubNode.Received> requests = node.requests();
            assertThat(requests).hasSize(3);
            assertThat(sentConfig(requests.get(0)))
                .as("false is the one value that changes the crawl: it must arrive")
                .containsEntry("respect_robots_txt", false);
            assertThat(sentConfig(requests.get(1))).containsEntry("respect_robots_txt", true);
            assertThat(sentConfig(requests.get(2)))
                .as("absent, so the node's default - obeying robots.txt - applies")
                .doesNotContainKey("respect_robots_txt");
        }
    }

    @Test
    @DisplayName("crawl_website refuses a respect_robots_txt that is not a boolean")
    void crawlRefusesAMistypedRobotsSetting() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                McpTools.CRAWL_WEBSITE, Map.of("source_id", "s",
                    "base_url", "https://example.com", "respect_robots_txt", "sometimes"));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("respect_robots_txt");
            assertThat(node.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("respect_robots_txt is typed and says what switching it off means")
    void crawlDescribesTheRobotsSetting() {
        Map<String, Object> property = Json.objectAt(properties(McpTools.CRAWL_WEBSITE),
            "respect_robots_txt");

        assertThat(property.get("type")).isEqualTo("boolean");
        assertThat(Json.stringAt(property, "description")).contains("robots.txt")
            .contains("entries_disallowed");
    }
}
