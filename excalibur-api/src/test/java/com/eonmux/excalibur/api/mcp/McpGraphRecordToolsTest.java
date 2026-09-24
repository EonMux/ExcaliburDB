package com.eonmux.excalibur.api.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tools that read the graph by entity and state facts into it: what each sends, and what it
 * refuses before sending anything.
 */
@DisplayName("MCP graph record tools")
class McpGraphRecordToolsTest {

    private static Object tool(String name) {
        for (Object described : McpTools.catalogue()) {
            if (name.equals(Json.stringAt(described, "name"))) {
                return described;
            }
        }
        throw new AssertionError("no tool named " + name);
    }

    private static Map<String, Object> properties(String name) {
        return Json.objectAt(Json.objectAt(tool(name), "inputSchema"), "properties");
    }

    private static List<Object> required(String name) {
        return List.copyOf((List<?>) Json.objectAt(tool(name), "inputSchema").get("required"));
    }

    @Test
    @DisplayName("find_graph_entities reads the entity listing with only the arguments given")
    void findSendsItsQuery() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(200, "{\"entities\":[],\"next_cursor\":null}");
            McpTools tools = new McpTools(node.client(Map.of()));

            McpTools.ToolResult result = tools.call(McpTools.FIND_GRAPH_ENTITIES,
                Map.of("name", " Ada Lovelace ", "limit", 5.0, "namespace", "tenant-a"));

            StubNode.Received request = node.onlyRequest();
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/v1/graph/entities");
            assertThat(request.query()).isEqualTo("name=Ada Lovelace&limit=5");
            assertThat(request.headers()).containsEntry(ExcaliburClient.NAMESPACE_HEADER,
                "tenant-a");
            assertThat(result.isError()).isFalse();
            assertThat(result.text()).contains("next_cursor");
        }
    }

    @Test
    @DisplayName("find_graph_entities with nothing given lists everything, and refuses name "
        + "with prefix")
    void findArguments() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            McpTools.ToolResult both = tools.call(McpTools.FIND_GRAPH_ENTITIES,
                Map.of("name", "Ada", "prefix", "Ad"));
            assertThat(both.isError()).isTrue();
            assertThat(node.requests()).isEmpty();

            tools.call(McpTools.FIND_GRAPH_ENTITIES, Map.of("prefix", "ad", "cursor", "abc"));
            tools.call(McpTools.FIND_GRAPH_ENTITIES, null);
            assertThat(node.requests()).extracting(StubNode.Received::query)
                .containsExactly("prefix=ad&cursor=abc", null);
        }
    }

    @Test
    @DisplayName("graph_entity_edges reads one entity's edges, checking the direction first")
    void edgesSendsItsQuery() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));
            Map<String, Object> args = new HashMap<>();
            args.put("entity_id", 42);
            args.put("relation", "worked with");
            args.put("direction", "IN");
            args.put("as_of", "1840-01-01");
            args.put("cursor", "ZTpvOjE6MjoxCg");

            assertThat(tools.call(McpTools.GRAPH_ENTITY_EDGES, args).isError()).isFalse();

            StubNode.Received request = node.onlyRequest();
            assertThat(request.path()).isEqualTo("/v1/graph/entities/42/edges");
            assertThat(request.query()).isEqualTo(
                "relation=worked with&direction=in&as_of=1840-01-01&cursor=ZTpvOjE6MjoxCg");
        }
    }

    @Test
    @DisplayName("graph_entity_edges refuses a missing or negative id and an unknown direction "
        + "without calling the node")
    void edgesRefusals() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            assertThat(tools.call(McpTools.GRAPH_ENTITY_EDGES, Map.of()).text())
                .contains("entity_id");
            assertThat(tools.call(McpTools.GRAPH_ENTITY_EDGES, Map.of("entity_id", -1))
                .isError()).isTrue();
            assertThat(tools.call(McpTools.GRAPH_ENTITY_EDGES, Map.of("entity_id", 1.5))
                .isError()).isTrue();
            McpTools.ToolResult sideways = tools.call(McpTools.GRAPH_ENTITY_EDGES,
                Map.of("entity_id", 1, "direction", "sideways"));
            assertThat(sideways.isError()).isTrue();
            assertThat(sideways.text()).contains("\"out\"", "\"in\"", "\"both\"");
            assertThat(node.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("a 404 for an id the graph does not hold reaches the model as an error")
    void edgesNotFound() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(404, "{\"error\":\"no entity 9\"}");
            McpTools.ToolResult result = new McpTools(node.client(Map.of()))
                .call(McpTools.GRAPH_ENTITY_EDGES, Map.of("entity_id", 9));

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("404").contains("no entity 9");
        }
    }

    @Test
    @DisplayName("assert_graph_facts posts the facts as written, into the namespace")
    void assertPostsTheFacts() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(201, "{\"staged\":1,\"facts\":[{\"fact_id\":7}]}");
            McpTools tools = new McpTools(node.client(Map.of("EXCALIBUR_NAMESPACE", "tenant-b")));
            Map<String, Object> fact = Map.of("subject", "Ada Lovelace",
                "relation", "worked with", "object", "Charles Babbage", "confidence", 0.9);

            McpTools.ToolResult result = tools.call(McpTools.ASSERT_GRAPH_FACTS,
                Map.of("facts", List.of(fact)));

            StubNode.Received request = node.onlyRequest();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/graph/facts");
            assertThat(request.headers()).containsEntry(ExcaliburClient.NAMESPACE_HEADER,
                "tenant-b");
            assertThat(Json.at(Json.parse(request.body()), "facts")).isEqualTo(List.of(fact));
            assertThat(result.isError()).isFalse();
            assertThat(result.text()).contains("fact_id");
        }
    }

    @Test
    @DisplayName("assert_graph_facts refuses no facts without calling the node")
    void assertRefusesNothing() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            assertThat(tools.call(McpTools.ASSERT_GRAPH_FACTS, Map.of()).isError()).isTrue();
            assertThat(tools.call(McpTools.ASSERT_GRAPH_FACTS, Map.of("facts", List.of()))
                .isError()).isTrue();
            assertThat(tools.call(McpTools.ASSERT_GRAPH_FACTS, Map.of("facts", "Ada"))
                .isError()).isTrue();
            assertThat(node.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("the schemas require what the routes require, and enumerate the directions")
    void schemas() {
        assertThat(required(McpTools.FIND_GRAPH_ENTITIES)).isEmpty();
        assertThat(properties(McpTools.FIND_GRAPH_ENTITIES))
            .containsKeys("name", "prefix", "limit", "cursor", "namespace");

        assertThat(required(McpTools.GRAPH_ENTITY_EDGES)).containsExactly("entity_id");
        assertThat(List.<Object>copyOf((List<?>) Json.objectAt(
            properties(McpTools.GRAPH_ENTITY_EDGES), "direction")
            .get("enum"))).containsExactly("out", "in", "both");

        assertThat(required(McpTools.ASSERT_GRAPH_FACTS)).containsExactly("facts");
        Map<String, Object> items = Json.objectAt(
            Json.objectAt(properties(McpTools.ASSERT_GRAPH_FACTS), "facts"), "items");
        assertThat(List.<Object>copyOf((List<?>) items.get("required")))
            .containsExactly("subject", "relation", "object");
    }

    @Test
    @DisplayName("the descriptions say a fact is served only after a build, and cannot be "
        + "retracted here")
    void descriptions() {
        String assertFacts = Json.stringAt(tool(McpTools.ASSERT_GRAPH_FACTS), "description");
        assertThat(assertFacts).contains(McpTools.BUILD_GRAPH).contains("fact_id")
            .contains("no tool to retract");
        assertThat(Json.stringAt(tool(McpTools.GRAPH_ENTITY_EDGES), "description"))
            .contains("USER_ASSERTED").contains("next_cursor");
        assertThat(McpTools.catalogue())
            .as("retraction is an erasure and stays with a person")
            .noneMatch(t -> Json.stringAt(t, "name").contains("retract"));
    }
}
