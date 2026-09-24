package com.eonmux.excalibur.api.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tools that let a model undo and refresh what it ingested.
 *
 * <p>The catalogue could register a source and could do nothing else with it afterwards. A model
 * that registered one against the wrong root was stuck for good: it could not deregister it, it
 * could not reindex it, and {@code add_documents} refuses an id a crawl has taken. The schema
 * told it otherwise - the {@code source_id} description said reusing the id re-indexes the same
 * source, and re-registering an existing id is a 409.</p>
 *
 * <p>Nor could it crawl a website, though the node has a full web crawler behind the same route
 * and seven documented options for it.</p>
 */
@DisplayName("MCP lifecycle tools")
class McpLifecycleToolsTest {

    private static Object tool(String name) {
        for (Object described : McpTools.catalogue()) {
            if (name.equals(Json.stringAt(described, "name"))) {
                return described;
            }
        }
        throw new AssertionError("no tool named " + name);
    }

    private static List<Object> names(Object array) {
        return List.copyOf((List<?>) array);
    }

    @Test
    @DisplayName("the false promise about reusing a source id is gone")
    void reusingAnIdIsNotAReindex() {
        String description = Json.stringAt(
            Json.objectAt(Json.objectAt(tool(McpTools.INGEST_DOCUMENTS), "inputSchema"),
                "properties").get("source_id"), "description");

        assertThat(description)
            .as("re-registering a taken id is a 409, so a model told it re-indexes will "
                + "keep retrying the one call that cannot work")
            .doesNotContain("re-indexes the same source");
        assertThat(description)
            .as("the model has to be pointed at the call that does refresh a source")
            .contains(McpTools.REINDEX_SOURCE);
    }

    @Test
    @DisplayName("deregister_source stops a crawl through the route that keeps the documents")
    void deregisterSendsDelete() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools.ToolResult result = new McpTools(node.client(Map.of()))
                .call(McpTools.DEREGISTER_SOURCE, Map.of("source_id", "wrong-root"));

            StubNode.Received request = node.onlyRequest();
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.path()).isEqualTo("/v1/ingest");
            assertThat(Json.stringAt(Json.parse(request.body()), "source_id"))
                .isEqualTo("wrong-root");
            assertThat(result.isError()).isFalse();
        }
    }

    @Test
    @DisplayName("deregister_source says it keeps the indexed documents, because it does")
    void deregisterDescribesWhatItKeeps() {
        assertThat(Json.stringAt(tool(McpTools.DEREGISTER_SOURCE), "description"))
            .as("a model told this erases the corpus will not use it, and a model told "
                + "nothing will use it believing it does")
            .contains("erase");
    }

    @Test
    @DisplayName("reindex_source posts to the reindex route")
    void reindexPosts() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(202, "{\"source_id\":\"docs\",\"status\":\"reindexing\"}");

            McpTools.ToolResult result = new McpTools(node.client(Map.of()))
                .call(McpTools.REINDEX_SOURCE, Map.of("source_id", "docs"));

            StubNode.Received request = node.onlyRequest();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/reindex");
            assertThat(Json.stringAt(Json.parse(request.body()), "source_id")).isEqualTo("docs");
            assertThat(result.isError()).isFalse();
        }
    }

    @Test
    @DisplayName("reindex_source tells the model to poll, because the route answers 202")
    void reindexDescribesItsAsynchrony() {
        assertThat(Json.stringAt(tool(McpTools.REINDEX_SOURCE), "description"))
            .as("a model that reads 202 as done will search a corpus that has not changed yet")
            .contains(McpTools.REINDEX_STATUS);
    }

    @Test
    @DisplayName("reindex_status reads the run back, for one source or all")
    void reindexStatusScopesItsQuery() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.REINDEX_STATUS, Map.of("source_id", "docs"));
            tools.call(McpTools.REINDEX_STATUS, Map.of());

            List<StubNode.Received> requests = node.requests();
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0).method()).isEqualTo("GET");
            assertThat(requests.get(0).path()).isEqualTo("/v1/reindex/status");
            assertThat(requests.get(0).query()).isEqualTo("source_id=docs");
            assertThat(requests.get(1).query()).isNull();
        }
    }

    @Test
    @DisplayName("crawl_website registers an HTTP source, not a filesystem one")
    void crawlSendsAnHttpSource() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.CRAWL_WEBSITE, Map.of(
                "source_id", "docs-site",
                "base_url", "https://example.com/docs",
                "max_depth", 2,
                "excluded_path_patterns", List.of("/api", "/login")));

            StubNode.Received request = node.onlyRequest();
            assertThat(request.path()).isEqualTo("/v1/ingest");
            Object body = Json.parse(request.body());
            assertThat(Json.stringAt(body, "source_type")).isEqualTo("http");

            Map<String, Object> config = Json.objectAt(body, "config");
            assertThat(config).containsEntry("source_id", "docs-site");
            assertThat(config).containsEntry("base_url", "https://example.com/docs");
            assertThat(config).containsEntry("max_depth", 2L);
            assertThat(config).containsEntry("excluded_path_patterns", List.of("/api", "/login"));
        }
    }

    @Test
    @DisplayName("an empty exclusion list is sent, because it clears the node's defaults")
    void emptyExclusionsAreSent() throws IOException {
        try (StubNode node = StubNode.start()) {
            new McpTools(node.client(Map.of())).call(McpTools.CRAWL_WEBSITE, Map.of(
                "source_id", "docs-site",
                "base_url", "https://example.com/docs",
                "excluded_path_patterns", List.of()));

            Map<String, Object> config =
                Json.objectAt(Json.parse(node.onlyRequest().body()), "config");
            assertThat(config).containsEntry("excluded_path_patterns", List.of());
            assertThat(config)
                .as("a setting the model did not send stays absent")
                .doesNotContainKey("included_path_patterns");
        }
    }

    @Test
    @DisplayName("crawl_website carries no setting a filesystem walk owns")
    void crawlSendsNoFilesystemSettings() {
        Map<String, Object> properties = Json.objectAt(
            Json.objectAt(tool(McpTools.CRAWL_WEBSITE), "inputSchema"), "properties");

        assertThat(properties)
            .as("the node refuses a crawl carrying one of these with a 400, so offering "
                + "them to the model is offering it a call that cannot succeed")
            .doesNotContainKeys("root_path", "included_extensions", "excluded_directories",
                "follow_symlinks", "include_hidden", "max_file_bytes", "charset");
        assertThat(names(
            Json.objectAt(tool(McpTools.CRAWL_WEBSITE), "inputSchema").get("required")))
            .containsExactlyInAnyOrder("source_id", "base_url");
    }

    @Test
    @DisplayName("ingest_documents carries no setting an HTTP crawl owns")
    void ingestSendsNoCrawlSettings() {
        Map<String, Object> properties = Json.objectAt(
            Json.objectAt(tool(McpTools.INGEST_DOCUMENTS), "inputSchema"), "properties");

        assertThat(properties).doesNotContainKeys("base_url", "max_documents",
            "max_response_bytes", "timeout_seconds", "included_path_patterns",
            "excluded_path_patterns", "user_agent");
    }

    @Test
    @DisplayName("every new tool refuses a call with no source id before it reaches the node")
    void missingSourceIdIsRefusedLocally() throws IOException {
        try (StubNode node = StubNode.start()) {
            McpTools tools = new McpTools(node.client(Map.of()));

            assertThat(tools.call(McpTools.DEREGISTER_SOURCE, Map.of()).isError()).isTrue();
            assertThat(tools.call(McpTools.REINDEX_SOURCE, Map.of()).isError()).isTrue();
            assertThat(tools.call(McpTools.CRAWL_WEBSITE,
                Map.of("source_id", "s")).isError()).isTrue();
            assertThat(node.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("graph erasure stays behind the HTTP API")
    void graphErasureStaysBehindTheHttpApi() {
        List<String> registered = McpTools.catalogue().stream()
            .map(described -> Json.stringAt(described, "name"))
            .toList();

        assertThat(registered)
            .as("it removes facts by entity or extractor version, which a model cannot relate "
                + "to anything it ingested, and the cost of a wrong call is not bounded by "
                + "retrying")
            .doesNotContain("erase_graph", "erase");
    }
}
