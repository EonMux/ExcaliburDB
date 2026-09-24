package com.eonmux.excalibur.api.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The objects inside a tool's array arguments: a document of {@code add_documents}, a fact of
 * {@code assert_graph_facts}.
 *
 * <p>Both routes read the members they know and ignore the rest, so a misspelt member reaches the
 * node as an absent one: {@code "last_modified"} dates a document's facts on the day they are
 * staged, {@code "valid_until"} states a fact that still holds. The adapter checks each element
 * against the members its schema declares before anything is sent.</p>
 */
@DisplayName("MCP array elements")
class McpElementArgumentsTest {

    private static Map<String, Object> itemProperties(String tool, String field) {
        for (Object described : McpTools.catalogue()) {
            if (tool.equals(Json.stringAt(described, "name"))) {
                Map<String, Object> property = Json.objectAt(Json.objectAt(
                    Json.objectAt(described, "inputSchema"), "properties"), field);
                return Json.objectAt(Json.objectAt(property, "items"), "properties");
            }
        }
        throw new AssertionError("no tool named " + tool);
    }

    @Nested
    @DisplayName("add_documents")
    class AddDocuments {

        /**
         * The route reads both, and {@code content_type} decides whether markup is indexed as
         * the text it shows or as markup. A model that cannot see them sends HTML as plain text.
         */
        @Test
        @DisplayName("declares every member the supply route reads")
        void declaresEveryMemberTheRouteReads() {
            Map<String, Object> properties = itemProperties(McpTools.ADD_DOCUMENTS, "documents");

            assertThat(properties).containsOnlyKeys("document_id", "text", "content_type",
                "last_modified_millis");
            assertThat(Json.stringAt(properties.get("content_type"), "description"))
                .contains("text/html").contains("text/plain");
            assertThat(Json.stringAt(properties.get("last_modified_millis"), "type"))
                .isEqualTo("integer");
        }

        @Test
        @DisplayName("forwards content_type and last_modified_millis as written")
        void forwardsTheOptionalMembers() throws IOException {
            try (StubNode node = StubNode.start()) {
                node.answerWith(200, "{\"documents_ingested\":1}");
                Map<String, Object> document = Map.of("document_id", "a.html",
                    "text", "<p>alpha</p>", "content_type", "text/html",
                    "last_modified_millis", 1736899200000L);

                McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                    McpTools.ADD_DOCUMENTS,
                    Map.of("source_id", "notes", "documents", List.of(document)));

                assertThat(result.isError()).isFalse();
                Object sent = ((List<?>) Json.at(Json.parse(node.onlyRequest().body()),
                    "documents")).get(0);
                assertThat(Json.stringAt(sent, "content_type")).isEqualTo("text/html");
                assertThat(((Number) Json.at(sent, "last_modified_millis")).longValue())
                    .isEqualTo(1736899200000L);
            }
        }

        @Test
        @DisplayName("refuses a member no document takes, naming it, and sends nothing")
        void refusesAnUndeclaredMember() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                    McpTools.ADD_DOCUMENTS, Map.of("source_id", "notes", "documents", List.of(
                        Map.of("document_id", "a.md", "text", "alpha"),
                        Map.of("document_id", "b.md", "text", "beta", "last_modified", 5))));

                assertThat(result.isError()).isTrue();
                assertThat(result.text()).contains("'documents'").contains("element 1")
                    .contains("'last_modified'").contains("last_modified_millis");
                assertThat(node.requests()).isEmpty();
            }
        }

        @Test
        @DisplayName("refuses a document that is not an object, or lacks what it needs")
        void refusesAMalformedDocument() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                McpTools.ToolResult notAnObject = tools.call(McpTools.ADD_DOCUMENTS,
                    Map.of("source_id", "notes", "documents", List.of("alpha")));
                McpTools.ToolResult noText = tools.call(McpTools.ADD_DOCUMENTS,
                    Map.of("source_id", "notes", "documents",
                        List.of(Map.of("document_id", "a.md"))));

                assertThat(notAnObject.isError()).isTrue();
                assertThat(notAnObject.text()).contains("element 0").contains("object");
                assertThat(noText.isError()).isTrue();
                assertThat(noText.text()).contains("'text'");
                assertThat(node.requests()).isEmpty();
            }
        }

        @Test
        @DisplayName("refuses a member of the wrong type rather than forwarding it")
        void refusesAMemberOfTheWrongType() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                McpTools.ToolResult date = tools.call(McpTools.ADD_DOCUMENTS,
                    Map.of("source_id", "notes", "documents", List.of(Map.of(
                        "document_id", "a.md", "text", "alpha",
                        "last_modified_millis", "yesterday"))));
                McpTools.ToolResult fraction = tools.call(McpTools.ADD_DOCUMENTS,
                    Map.of("source_id", "notes", "documents", List.of(Map.of(
                        "document_id", "a.md", "text", "alpha",
                        "last_modified_millis", 1.5))));
                McpTools.ToolResult id = tools.call(McpTools.ADD_DOCUMENTS,
                    Map.of("source_id", "notes", "documents", List.of(Map.of(
                        "document_id", 7, "text", "alpha"))));

                assertThat(date.isError()).isTrue();
                assertThat(date.text()).contains("'last_modified_millis'");
                assertThat(fraction.isError()).isTrue();
                assertThat(id.isError()).isTrue();
                assertThat(id.text()).contains("'document_id'");
                assertThat(node.requests()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("assert_graph_facts")
    class AssertGraphFacts {

        @Test
        @DisplayName("declares exactly the members the facts route reads")
        void declaresTheMembersTheRouteReads() {
            assertThat(itemProperties(McpTools.ASSERT_GRAPH_FACTS, "facts")).containsOnlyKeys(
                "subject", "relation", "object", "valid_from", "valid_to", "confidence");
        }

        /**
         * The route ignores a member it does not read, so {@code "valid_until"} would stage a fact
         * that still holds - one a model cannot take back, since no tool retracts.
         */
        @Test
        @DisplayName("refuses a member no fact takes, naming it, and stages nothing")
        void refusesAnUndeclaredMember() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                    McpTools.ASSERT_GRAPH_FACTS, Map.of("facts", List.of(Map.of(
                        "subject", "Ada Lovelace", "relation", "worked with",
                        "object", "Charles Babbage", "valid_until", "1843-01-01"))));

                assertThat(result.isError()).isTrue();
                assertThat(result.text()).contains("'facts'").contains("element 0")
                    .contains("'valid_until'").contains("valid_to");
                assertThat(node.requests()).isEmpty();
            }
        }

        @Test
        @DisplayName("refuses a fact missing its object, or with a confidence that is not a number")
        void refusesAMalformedFact() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                McpTools.ToolResult noObject = tools.call(McpTools.ASSERT_GRAPH_FACTS,
                    Map.of("facts", List.of(Map.of("subject", "Ada", "relation", "knew"))));
                McpTools.ToolResult wordy = tools.call(McpTools.ASSERT_GRAPH_FACTS,
                    Map.of("facts", List.of(Map.of("subject", "Ada", "relation", "knew",
                        "object", "Charles", "confidence", "high"))));

                assertThat(noObject.isError()).isTrue();
                assertThat(noObject.text()).contains("'object'");
                assertThat(wordy.isError()).isTrue();
                assertThat(wordy.text()).contains("'confidence'");
                assertThat(node.requests()).isEmpty();
            }
        }

        /**
         * An explicit null is how the route is told a fact has no known start or still holds, so
         * it is forwarded rather than refused as a missing string.
         */
        @Test
        @DisplayName("forwards an explicit null for an optional member")
        void forwardsAnExplicitNull() throws IOException {
            try (StubNode node = StubNode.start()) {
                node.answerWith(201, "{\"staged\":1}");
                Map<String, Object> fact = new java.util.LinkedHashMap<>();
                fact.put("subject", "Ada");
                fact.put("relation", "knew");
                fact.put("object", "Charles");
                fact.put("valid_to", null);

                McpTools.ToolResult result = new McpTools(node.client(Map.of())).call(
                    McpTools.ASSERT_GRAPH_FACTS, Map.of("facts", List.of(fact)));

                assertThat(result.isError()).isFalse();
                assertThat(node.onlyRequest().body()).contains("valid_to");
            }
        }
    }
}
