package com.eonmux.excalibur.api.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The catalogue in the shapes the two function-calling APIs actually take.
 *
 * <p>{@code --print-schema} wrote the MCP wire shape and the README called it "the same
 * catalogue, in the form the OpenAI and Anthropic function-calling APIs take". It is neither.
 * Anthropic spells the schema {@code input_schema}, so an MCP descriptor's {@code inputSchema}
 * is an unknown field beside a missing required one; OpenAI wants each tool wrapped as
 * {@code {"type": "function", "function": {...}}} with the schema under {@code parameters}.
 * Piped into either, the file is a 400 - and the whole point of the flag is to save a caller
 * from writing the schemas by hand.</p>
 */
@DisplayName("Function-calling schemas")
class FunctionSchemasTest {

    private static Object named(List<Object> tools, String name, String nameField) {
        for (Object tool : tools) {
            if (name.equals(Json.stringAt(tool, nameField))) {
                return tool;
            }
        }
        throw new AssertionError("no tool named " + name + " in " + tools);
    }

    @Test
    @DisplayName("the Anthropic shape spells the schema input_schema")
    void anthropicUsesInputSchema() {
        List<Object> tools = FunctionSchemas.anthropic();

        assertThat(tools).hasSameSizeAs(McpTools.catalogue());
        Object search = named(tools, McpTools.SEARCH_DOCUMENTS, "name");
        assertThat(Json.objectAt(search, "input_schema"))
            .as("Anthropic requires input_schema; inputSchema is an unknown field")
            .isNotNull();
        assertThat(Json.at(search, "inputSchema")).isNull();
        assertThat(Json.stringAt(search, "description")).isNotBlank();
        assertThat(Json.objectAt(search, "input_schema").get("type")).isEqualTo("object");
    }

    @Test
    @DisplayName("the OpenAI shape wraps each tool as a function")
    void openAiWrapsEachTool() {
        List<Object> tools = FunctionSchemas.openAi();

        assertThat(tools).hasSameSizeAs(McpTools.catalogue());
        for (Object tool : tools) {
            assertThat(Json.stringAt(tool, "type")).isEqualTo("function");
            Map<String, Object> function = Json.objectAt(tool, "function");
            assertThat(function).isNotNull();
            assertThat(function.get("name")).isNotNull();
            assertThat(function.get("description")).isNotNull();
            assertThat(function.get("parameters"))
                .as("OpenAI reads the schema from 'parameters'")
                .isInstanceOf(Map.class);
        }
    }

    @Test
    @DisplayName("the MCP wire shape is still available, unchanged")
    void mcpIsStillThere() {
        Object search = named(McpTools.catalogue(), McpTools.SEARCH_DOCUMENTS, "name");

        assertThat(Json.objectAt(search, "inputSchema")).isNotNull();
    }

    @Test
    @DisplayName("neither shape shares mutable state with the catalogue")
    void theShapesAreIndependent() {
        Object anthropic = named(FunctionSchemas.anthropic(), McpTools.BUILD_GRAPH, "name");
        Map<String, Object> mcp = Json.objectAt(
            named(McpTools.catalogue(), McpTools.BUILD_GRAPH, "name"), "inputSchema");

        assertThat(Json.objectAt(anthropic, "input_schema"))
            .as("the converted shape must carry the same schema, not a reference that a "
                + "later edit of one would change in the other")
            .isEqualTo(mcp)
            .isNotSameAs(mcp);
    }

    @Test
    @DisplayName("every shape names itself in the combined document")
    void theCombinedDocumentIsSelfDescribing() {
        Object document = Json.parse(FunctionSchemas.allShapes());

        assertThat(Json.at(document, "mcp")).isInstanceOf(List.class);
        assertThat(Json.at(document, "anthropic")).isInstanceOf(List.class);
        assertThat(Json.at(document, "openai")).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("a shape nobody has is refused by name, not answered with an empty one")
    void anUnknownShapeIsRefused() {
        assertThatThrownBy(() -> FunctionSchemas.shaped("langchain"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("langchain");
    }

    @Test
    @DisplayName("each shape is valid JSON, since it is piped straight into an API")
    void eachShapeSerialises() {
        for (String shape : List.of("mcp", "anthropic", "openai")) {
            assertThat(Json.parse(FunctionSchemas.shaped(shape)))
                .as("shape %s", shape)
                .isInstanceOf(Map.class);
        }
    }
}
