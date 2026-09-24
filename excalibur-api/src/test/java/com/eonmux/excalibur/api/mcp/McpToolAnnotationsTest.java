package com.eonmux.excalibur.api.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The behaviour hints each tool carries in {@code tools/list}, which a client reads to decide
 * which calls to put in front of the user before running them.
 */
@DisplayName("MCP tool annotations")
class McpToolAnnotationsTest {

    /** readOnly, destructive, idempotent, openWorld - in the order the spec lists them. */
    private static List<Boolean> hints(boolean readOnly, boolean destructive, boolean idempotent,
                                       boolean openWorld) {
        return List.of(readOnly, destructive, idempotent, openWorld);
    }

    private static final List<Boolean> READS = hints(true, false, true, false);

    /** Every tool, and the hints it has to carry. */
    private static Map<String, List<Boolean>> expected() {
        Map<String, List<Boolean>> expected = new LinkedHashMap<>();
        for (String reader : List.of(McpTools.SEARCH_DOCUMENTS, McpTools.SEARCH_GRAPH,
                McpTools.GRAPH_BUILD_STATUS, McpTools.INGESTION_STATUS, McpTools.REINDEX_STATUS,
                McpTools.LIST_DOCUMENTS, McpTools.FETCH_DOCUMENTS, McpTools.LIST_NAMESPACES,
                McpTools.LIST_QUERIES, McpTools.READ_DOCUMENT, McpTools.ERASURE_STATUS,
                McpTools.COMPACTION_STATUS, McpTools.NODE_STATUS, McpTools.LIST_SOURCES,
                McpTools.FIND_GRAPH_ENTITIES, McpTools.GRAPH_ENTITY, McpTools.GRAPH_ENTITY_EDGES,
                McpTools.LIST_GRAPH_RELATIONS)) {
            expected.put(reader, READS);
        }
        // Irreversible removals. A scope 'source' erasure takes at most 1000 documents a call,
        // so repeating it erases more.
        expected.put(McpTools.ERASE_DOCUMENTS, hints(false, true, false, false));
        expected.put(McpTools.DELETE_NAMESPACE, hints(false, true, true, false));
        // Not additive: each can replace or remove documents the node holds.
        expected.put(McpTools.ADD_DOCUMENTS, hints(false, true, true, false));
        expected.put(McpTools.REINDEX_SOURCE, hints(false, true, true, true));
        // Additive, and a repeat changes nothing more.
        expected.put(McpTools.INGEST_DOCUMENTS, hints(false, false, true, false));
        expected.put(McpTools.CRAWL_WEBSITE, hints(false, false, true, true));
        expected.put(McpTools.BUILD_GRAPH, hints(false, false, true, false));
        expected.put(McpTools.CREATE_NAMESPACE, hints(false, false, true, false));
        expected.put(McpTools.DEREGISTER_SOURCE, hints(false, false, true, false));
        expected.put(McpTools.COMPACT, hints(false, false, true, false));
        expected.put(McpTools.CANCEL_QUERY, hints(false, false, true, false));
        expected.put(McpTools.CANCEL_INGESTION, hints(false, false, true, false));
        expected.put(McpTools.CANCEL_REINDEX, hints(false, false, true, false));
        expected.put(McpTools.CANCEL_COMPACTION, hints(false, false, true, false));
        expected.put(McpTools.CANCEL_GRAPH_BUILD, hints(false, false, true, false));
        // Every call stages another fact, under a new fact_id.
        expected.put(McpTools.ASSERT_GRAPH_FACTS, hints(false, false, false, false));
        return expected;
    }

    @Test
    @DisplayName("every tool carries all four hints, and each says what the tool does")
    void everyToolIsAnnotated() {
        Map<String, List<Boolean>> expected = expected();
        List<Object> catalogue = McpTools.catalogue();
        assertThat(catalogue).hasSize(expected.size());

        for (Object described : catalogue) {
            String name = Json.stringAt(described, "name");
            Map<String, Object> annotations = Json.objectAt(described, "annotations");
            assertThat(annotations).as(name).isNotNull()
                .containsOnlyKeys("readOnlyHint", "destructiveHint", "idempotentHint",
                    "openWorldHint");
            assertThat(List.of(annotations.get("readOnlyHint"), annotations.get("destructiveHint"),
                    annotations.get("idempotentHint"), annotations.get("openWorldHint")))
                .as("%s: readOnly, destructive, idempotent, openWorld", name)
                .isEqualTo(expected.get(name));
        }
    }

    /**
     * The confirmation gate and the hint are two statements of the same fact, and a client that
     * prompts on the hint must prompt for at least the calls the gate guards.
     */
    @Test
    @DisplayName("every confirmation-gated tool is marked destructive")
    void gatedToolsAreDestructive() {
        for (Object described : McpTools.catalogue()) {
            Map<String, Object> properties = Json.objectAt(
                Json.objectAt(described, "inputSchema"), "properties");
            if (properties.containsKey(McpTools.CONFIRM)) {
                assertThat(Json.at(Json.objectAt(described, "annotations"), "destructiveHint"))
                    .as(Json.stringAt(described, "name")).isEqualTo(true);
            }
        }
    }

    /**
     * The function-calling APIs take a closed shape, and an unknown member there is a 400 from
     * an API that did not write this catalogue.
     */
    @Test
    @DisplayName("the function-calling shapes carry no annotations")
    void theFunctionCallingShapesStayClosed() {
        for (Object tool : FunctionSchemas.anthropic()) {
            assertThat(Json.at(tool, "annotations")).isNull();
        }
        for (Object tool : FunctionSchemas.openAi()) {
            assertThat(Json.at(Json.at(tool, "function"), "annotations")).isNull();
        }
    }
}
