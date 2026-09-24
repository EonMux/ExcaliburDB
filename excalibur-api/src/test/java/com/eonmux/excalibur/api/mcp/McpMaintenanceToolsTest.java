package com.eonmux.excalibur.api.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tools that reclaim space and read back registrations: each reaches the one route it names,
 * sends only what the model supplied, and is described so a model knows when to reach for it.
 */
@DisplayName("MCP maintenance tools")
class McpMaintenanceToolsTest {

    private static Object tool(String name) {
        for (Object described : McpTools.catalogue()) {
            if (name.equals(Json.stringAt(described, "name"))) {
                return described;
            }
        }
        throw new AssertionError("no tool named " + name);
    }

    private static List<?> required(String name) {
        return (List<?>) Json.objectAt(tool(name), "inputSchema").get("required");
    }

    @Test
    @DisplayName("compact starts a compaction, sending force only when the model set it")
    void compactPostsForce() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(202, "{\"state\":\"running\",\"force\":true}");
            McpTools tools = new McpTools(node.client(Map.of()));

            McpTools.ToolResult forced = tools.call(McpTools.COMPACT, Map.of("force", true));
            tools.call(McpTools.COMPACT, Map.of());

            List<StubNode.Received> requests = node.requests();
            assertThat(requests).extracting(StubNode.Received::method)
                .containsExactly("POST", "POST");
            assertThat(requests).extracting(StubNode.Received::path).containsOnly("/v1/compact");
            assertThat(Json.at(Json.parse(requests.get(0).body()), "force"))
                .isEqualTo(Boolean.TRUE);
            assertThat(Json.at(Json.parse(requests.get(1).body()), "force"))
                .as("omitted, so the node's own default applies")
                .isNull();
            assertThat(forced.isError()).isFalse();
            assertThat(forced.text()).contains("running");
        }
    }

    @Test
    @DisplayName("compact surfaces the node's refusal while another compaction runs")
    void compactReportsAConflict() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(409, "{\"error\":\"A compaction is already running\"}");

            McpTools.ToolResult result =
                new McpTools(node.client(Map.of())).call(McpTools.COMPACT, Map.of());

            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("already running");
        }
    }

    @Test
    @DisplayName("compaction_status reads back the most recent run")
    void compactionStatusReads() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(200, "{\"state\":\"completed\"}");

            McpTools.ToolResult result =
                new McpTools(node.client(Map.of())).call(McpTools.COMPACTION_STATUS, Map.of());

            StubNode.Received request = node.onlyRequest();
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/v1/compact/status");
            assertThat(result.text()).contains("completed");
        }
    }

    @Test
    @DisplayName("list_sources reads back every registration, or the one named")
    void listSourcesReads() throws IOException {
        try (StubNode node = StubNode.start()) {
            node.answerWith(200, "{\"sources\":[]}");
            McpTools tools = new McpTools(node.client(Map.of()));

            tools.call(McpTools.LIST_SOURCES, Map.of());
            tools.call(McpTools.LIST_SOURCES, Map.of("source_id", "team:wiki"));

            List<StubNode.Received> requests = node.requests();
            assertThat(requests).extracting(StubNode.Received::method).containsOnly("GET");
            assertThat(requests).extracting(StubNode.Received::path).containsOnly("/v1/ingest");
            assertThat(requests.get(0).query()).isNull();
            assertThat(requests.get(1).query()).isEqualTo("source_id=team:wiki");
        }
    }

    @Test
    @DisplayName("none of them requires an argument, and each points at the tools it pairs with")
    void describedForAModel() {
        assertThat(required(McpTools.COMPACT)).isEmpty();
        assertThat(required(McpTools.COMPACTION_STATUS)).isEmpty();
        assertThat(required(McpTools.LIST_SOURCES)).isEmpty();
        assertThat(Json.stringAt(tool(McpTools.COMPACT), "description"))
            .contains(McpTools.COMPACTION_STATUS, McpTools.CANCEL_COMPACTION);
        assertThat(Json.stringAt(tool(McpTools.LIST_SOURCES), "description"))
            .contains("'registered'", McpTools.INGESTION_STATUS);
    }
}
