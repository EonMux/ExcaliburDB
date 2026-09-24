package com.eonmux.excalibur.api.mcp;

import java.util.Map;

/**
 * The behaviour hints each tool carries as its MCP {@code annotations}.
 *
 * <p>A client reads them to decide which calls to put in front of the user before running
 * them and which it may run unattended, so each one is a claim about what the call does to the
 * node, and a wrong one is a defect of the same kind as a wrong description. All four are
 * stated for every tool rather than left to the specification's defaults, which assume the
 * worst - not read-only, destructive, not idempotent, open-world - and would have a client
 * confirm every search.</p>
 *
 * <ul>
 *   <li>{@code readOnlyHint} - the call changes nothing on the node. A search, a listing, a
 *       status.</li>
 *   <li>{@code destructiveHint} - in the specification's sense: the call may do more than add.
 *       {@link McpTools#ERASE_DOCUMENTS} and {@link McpTools#DELETE_NAMESPACE} remove data for
 *       good and are also gated on a confirmation. {@link McpTools#ADD_DOCUMENTS} replaces what
 *       is indexed under a document id sent again, and {@link McpTools#REINDEX_SOURCE} replaces
 *       changed documents and removes the ones its source no longer lists, so both carry it too,
 *       without the confirmation: replacing is what they exist to do. Recomputing a derived
 *       structure from data that stays - a graph build, a compaction of what is already
 *       erased - is not destructive.</li>
 *   <li>{@code idempotentHint} - repeating the call with the same arguments changes nothing
 *       further. A repeat registration is refused with 409, a repeat cancellation finds nothing
 *       running. Not {@link McpTools#ASSERT_GRAPH_FACTS}, which stages another fact under a new
 *       id each time, nor {@link McpTools#ERASE_DOCUMENTS}, whose scope {@code source} erases at
 *       most 1000 documents a call and is meant to be repeated.</li>
 *   <li>{@code openWorldHint} - the call reaches outside the node: a crawl of a website, and a
 *       re-crawl, which fetches it again. Reading a directory on the node's own host is not.</li>
 * </ul>
 *
 * <p>Sent under every negotiated protocol revision. {@code annotations} first appears in
 * 2025-03-26, but the 2024-11-05 {@code Tool} schema does not close its members and clients of it
 * ignore one they do not know; withholding the hints there would buy nothing and would make the
 * catalogue depend on session state that {@code --print-schema} does not have. The
 * function-calling shapes of {@link FunctionSchemas} leave them out: those APIs take a closed
 * shape.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; every call returns a fresh map.</p>
 */
final class ToolHints {

    private ToolHints() {
        throw new AssertionError("Utility class");
    }

    /**
     * The annotations one tool carries.
     *
     * <p>Every tool is named: one missing from the switch is refused rather than given defaults,
     * so a tool cannot join the catalogue without somebody deciding what it does.</p>
     *
     * @param tool The tool name
     * @return {@code readOnlyHint}, {@code destructiveHint}, {@code idempotentHint} and
     *         {@code openWorldHint}
     * @throws IllegalStateException if the tool is not one this class classifies
     */
    static Map<String, Object> of(String tool) {
        return switch (tool) {
            case McpTools.SEARCH_DOCUMENTS, McpTools.SEARCH_GRAPH, McpTools.GRAPH_BUILD_STATUS,
                 McpTools.INGESTION_STATUS, McpTools.REINDEX_STATUS, McpTools.LIST_DOCUMENTS,
                 McpTools.FETCH_DOCUMENTS, McpTools.LIST_NAMESPACES, McpTools.LIST_QUERIES,
                 McpTools.READ_DOCUMENT, McpTools.ERASURE_STATUS, McpTools.COMPACTION_STATUS,
                 McpTools.NODE_STATUS, McpTools.LIST_SOURCES, McpTools.FIND_GRAPH_ENTITIES,
                 McpTools.GRAPH_ENTITY, McpTools.GRAPH_ENTITY_EDGES,
                 McpTools.LIST_GRAPH_RELATIONS -> hints(true, false, true, false);
            case McpTools.DELETE_NAMESPACE, McpTools.ADD_DOCUMENTS -> hints(false, true, true, false);
            case McpTools.ERASE_DOCUMENTS -> hints(false, true, false, false);
            case McpTools.REINDEX_SOURCE -> hints(false, true, true, true);
            case McpTools.CRAWL_WEBSITE -> hints(false, false, true, true);
            case McpTools.INGEST_DOCUMENTS, McpTools.BUILD_GRAPH, McpTools.CREATE_NAMESPACE,
                 McpTools.DEREGISTER_SOURCE, McpTools.COMPACT, McpTools.CANCEL_QUERY,
                 McpTools.CANCEL_INGESTION, McpTools.CANCEL_REINDEX, McpTools.CANCEL_COMPACTION,
                 McpTools.CANCEL_GRAPH_BUILD -> hints(false, false, true, false);
            case McpTools.ASSERT_GRAPH_FACTS -> hints(false, false, false, false);
            default -> throw new IllegalStateException("No behaviour hints are declared for "
                + tool);
        };
    }

    private static Map<String, Object> hints(boolean readOnly, boolean destructive,
                                             boolean idempotent, boolean openWorld) {
        Map<String, Object> annotations = Json.object();
        annotations.put("readOnlyHint", readOnly);
        annotations.put("destructiveHint", destructive);
        annotations.put("idempotentHint", idempotent);
        annotations.put("openWorldHint", openWorld);
        return annotations;
    }
}
