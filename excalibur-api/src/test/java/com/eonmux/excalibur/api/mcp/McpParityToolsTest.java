package com.eonmux.excalibur.api.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tools that give a model what the HTTP API gives an operator short of the node itself.
 *
 * <p>A model could register, search and build, and could not read back what it had indexed, see
 * which namespaces existed, stop work it had started, or honour a request to forget a document.
 * The destructive two of these carry a {@code confirm} argument that must repeat what is being
 * destroyed, checked here before anything is sent, so a model cannot erase by reaching for the
 * wrong tool with the right id.</p>
 */
@DisplayName("MCP parity tools")
class McpParityToolsTest {

    private static Object tool(String name) {
        for (Object described : McpTools.catalogue()) {
            if (name.equals(Json.stringAt(described, "name"))) {
                return described;
            }
        }
        throw new AssertionError("no tool named " + name);
    }

    private static List<Object> required(String name) {
        return List.copyOf((List<?>) Json.objectAt(tool(name), "inputSchema").get("required"));
    }

    private static Map<String, Object> properties(String name) {
        return Json.objectAt(Json.objectAt(tool(name), "inputSchema"), "properties");
    }

    @Nested
    @DisplayName("destructive tools")
    class Destructive {

        @Test
        @DisplayName("both destructive tools require a confirmation, and no other tool does")
        void confirmationIsConsistent() {
            for (Object described : McpTools.catalogue()) {
                String name = Json.stringAt(described, "name");
                boolean destructive = McpTools.ERASE_DOCUMENTS.equals(name)
                    || McpTools.DELETE_NAMESPACE.equals(name);
                assertThat(required(name).contains("confirm"))
                    .as("%s requires confirm exactly when it destroys data", name)
                    .isEqualTo(destructive);
                if (destructive) {
                    assertThat(Json.stringAt(described, "description"))
                        .as("%s says it is irreversible and when it may be called", name)
                        .containsIgnoringCase("irreversible")
                        .containsIgnoringCase("explicitly asked");
                }
            }
        }

        @Test
        @DisplayName("erase_documents sends the erasure the node parses")
        void eraseSendsTheErasure() throws IOException {
            try (StubNode node = StubNode.start()) {
                node.answerWith(200, "{\"documents_erased\": 2}");

                McpTools.ToolResult result = new McpTools(node.client(Map.of()))
                    .call(McpTools.ERASE_DOCUMENTS, Map.of(
                        "source_id", "notes",
                        "document_ids", List.of("a.md", "b.md"),
                        "compact", true,
                        "confirm", "notes"));

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.path()).isEqualTo("/v1/erasure");
                Object body = Json.parse(request.body());
                assertThat(Json.stringAt(body, "source_id")).isEqualTo("notes");
                assertThat(Json.at(body, "document_ids")).isEqualTo(List.of("a.md", "b.md"));
                assertThat(Json.at(body, "compact")).isEqualTo(Boolean.TRUE);
                assertThat(Json.at(body, "confirm"))
                    .as("the confirmation is this adapter's check, not a field the node reads")
                    .isNull();
                assertThat(request.headers())
                    .as("the corpus has no namespaces, so the route refuses one")
                    .doesNotContainKey(ExcaliburClient.NAMESPACE_HEADER);
                assertThat(result.isError()).isFalse();
            }
        }

        @Test
        @DisplayName("erase_documents can erase a whole source, which must be asked for by name")
        void eraseAWholeSource() throws IOException {
            try (StubNode node = StubNode.start()) {
                new McpTools(node.client(Map.of())).call(McpTools.ERASE_DOCUMENTS,
                    Map.of("source_id", "notes", "scope", "source", "confirm", "notes"));

                Object body = Json.parse(node.onlyRequest().body());
                assertThat(Json.stringAt(body, "scope")).isEqualTo("source");
                assertThat(Json.at(body, "document_ids")).isNull();
            }
        }

        @Test
        @DisplayName("an erasure whose confirmation does not match is refused, and nothing is sent")
        void aMismatchedConfirmationSendsNothing() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                McpTools.ToolResult missing = tools.call(McpTools.ERASE_DOCUMENTS,
                    Map.of("source_id", "notes", "scope", "source"));
                McpTools.ToolResult wrong = tools.call(McpTools.ERASE_DOCUMENTS,
                    Map.of("source_id", "notes", "scope", "source", "confirm", "yes"));
                McpTools.ToolResult nearly = tools.call(McpTools.ERASE_DOCUMENTS,
                    Map.of("source_id", "notes", "scope", "source", "confirm", "Notes"));

                assertThat(missing.isError()).isTrue();
                assertThat(wrong.isError()).isTrue();
                assertThat(wrong.text()).contains("confirm").contains("source_id")
                    .as("the refusal says what is required without handing over the value")
                    .doesNotContain("notes");
                assertThat(nearly.isError()).as("a confirmation is exact").isTrue();
                assertThat(node.requests()).isEmpty();
            }
        }

        @Test
        @DisplayName("an erasure that names neither documents nor a whole source sends nothing")
        void anErasureMustSayWhat() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                assertThat(tools.call(McpTools.ERASE_DOCUMENTS,
                    Map.of("source_id", "notes", "confirm", "notes")).isError()).isTrue();
                assertThat(tools.call(McpTools.ERASE_DOCUMENTS, Map.of("source_id", "notes",
                    "document_ids", List.of(), "confirm", "notes")).isError()).isTrue();
                assertThat(tools.call(McpTools.ERASE_DOCUMENTS, Map.of("source_id", "notes",
                    "document_ids", List.of("a.md"), "scope", "source", "confirm", "notes"))
                    .isError())
                    .as("both at once is ambiguous about how much to destroy")
                    .isTrue();
                assertThat(node.requests()).isEmpty();
            }
        }

        @Test
        @DisplayName("delete_namespace drops the named namespace, and only with its name confirmed")
        void deleteNamespace() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of("EXCALIBUR_NAMESPACE", "acme")));

                assertThat(tools.call(McpTools.DELETE_NAMESPACE,
                    Map.of("namespace", "acme", "confirm", "acm")).isError()).isTrue();
                assertThat(tools.call(McpTools.DELETE_NAMESPACE, Map.of("confirm", "acme"))
                    .isError())
                    .as("the configured default namespace is never what a drop falls back on")
                    .isTrue();
                assertThat(node.requests()).isEmpty();

                McpTools.ToolResult result = tools.call(McpTools.DELETE_NAMESPACE,
                    Map.of("namespace", "acme", "confirm", "acme"));

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("DELETE");
                assertThat(request.path()).isEqualTo("/v1/graph/namespaces/acme");
                assertThat(request.headers()).doesNotContainKey(ExcaliburClient.NAMESPACE_HEADER);
                assertThat(result.isError()).isFalse();
            }
        }

        @Test
        @DisplayName("a namespace that is not one never reaches a path")
        void aHostileNamespaceIsRefused() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                for (String hostile : List.of("..", "../v1", "a/b", "UPPER", "a b")) {
                    assertThat(tools.call(McpTools.DELETE_NAMESPACE,
                        Map.of("namespace", hostile, "confirm", hostile)).isError())
                        .as(hostile).isTrue();
                    assertThat(tools.call(McpTools.CREATE_NAMESPACE,
                        Map.of("namespace", hostile)).isError())
                        .as(hostile).isTrue();
                }
                assertThat(node.requests()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("reading back")
    class ReadingBack {

        @Test
        @DisplayName("list_documents pages the registry with the node's own parameters")
        void listDocuments() throws IOException {
            try (StubNode node = StubNode.start()) {
                new McpTools(node.client(Map.of())).call(McpTools.LIST_DOCUMENTS,
                    Map.of("source_id", "my notes", "limit", 5, "cursor", "bm90ZXM"));

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("GET");
                assertThat(request.path()).isEqualTo("/v1/documents/list");
                assertThat(request.query())
                    .isEqualTo("source_id=my notes&limit=5&cursor=bm90ZXM");
            }
        }

        @Test
        @DisplayName("list_documents with no arguments asks for the first page, at the node's size")
        void listDocumentsDefaults() throws IOException {
            try (StubNode node = StubNode.start()) {
                new McpTools(node.client(Map.of())).call(McpTools.LIST_DOCUMENTS, Map.of());

                assertThat(node.onlyRequest().query()).isNull();
            }
        }

        @Test
        @DisplayName("fetch_documents reads a page of chunks in one request")
        void fetchDocuments() throws IOException {
            try (StubNode node = StubNode.start()) {
                new McpTools(node.client(Map.of())).call(McpTools.FETCH_DOCUMENTS,
                    Map.of("document_ids", List.of("notes:a.md:chunk:0", "notes:a.md:chunk:1")));

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.path()).isEqualTo("/v1/documents/fetch");
                assertThat(Json.at(Json.parse(request.body()), "document_ids"))
                    .isEqualTo(List.of("notes:a.md:chunk:0", "notes:a.md:chunk:1"));
                assertThat(required(McpTools.FETCH_DOCUMENTS)).containsExactly("document_ids");
            }
        }

        @Test
        @DisplayName("fetch_documents with no identifiers is refused before sending")
        void fetchNeedsIdentifiers() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                assertThat(tools.call(McpTools.FETCH_DOCUMENTS, Map.of()).isError()).isTrue();
                assertThat(tools.call(McpTools.FETCH_DOCUMENTS,
                    Map.of("document_ids", List.of())).isError()).isTrue();
                assertThat(node.requests()).isEmpty();
            }
        }

        @Test
        @DisplayName("list_namespaces pages the inventory, sending no namespace of its own")
        void listNamespaces() throws IOException {
            try (StubNode node = StubNode.start()) {
                new McpTools(node.client(Map.of("EXCALIBUR_NAMESPACE", "acme")))
                    .call(McpTools.LIST_NAMESPACES, Map.of("limit", 2, "cursor", "beta"));

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("GET");
                assertThat(request.path()).isEqualTo("/v1/graph/namespaces");
                assertThat(request.query()).isEqualTo("limit=2&cursor=beta");
                assertThat(request.headers()).doesNotContainKey(ExcaliburClient.NAMESPACE_HEADER);
            }
        }

        @Test
        @DisplayName("create_namespace creates the named namespace with a PUT")
        void createNamespace() throws IOException {
            try (StubNode node = StubNode.start()) {
                node.answerWith(201, "{\"namespace\": \"acme\", \"created\": true}");

                McpTools.ToolResult result = new McpTools(node.client(Map.of()))
                    .call(McpTools.CREATE_NAMESPACE, Map.of("namespace", "acme"));

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("PUT");
                assertThat(request.path()).isEqualTo("/v1/graph/namespaces/acme");
                assertThat(result.isError()).isFalse();
                assertThat(result.text()).contains("\"created\": true");
            }
        }

        @Test
        @DisplayName("read_document reads one document's chunks with their text by default")
        void readDocument() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of("EXCALIBUR_NAMESPACE", "acme")));

                tools.call(McpTools.READ_DOCUMENT,
                    Map.of("source_id", "my notes", "document_id", "guide.md"));
                tools.call(McpTools.READ_DOCUMENT, Map.of("source_id", "notes",
                    "document_id", "a.md", "include_text", false, "limit", 50,
                    "cursor", "Y3Vyc29y"));

                StubNode.Received first = node.requests().get(0);
                assertThat(first.method()).isEqualTo("GET");
                assertThat(first.path()).isEqualTo("/v1/documents/list");
                assertThat(first.query())
                    .isEqualTo("source_id=my notes&document_id=guide.md&include_text=true");
                assertThat(first.headers()).doesNotContainKey(ExcaliburClient.NAMESPACE_HEADER);
                assertThat(node.requests().get(1).query()).isEqualTo(
                    "source_id=notes&document_id=a.md&include_text=false&limit=50"
                        + "&cursor=Y3Vyc29y");
                assertThat(required(McpTools.READ_DOCUMENT))
                    .containsExactlyInAnyOrder("source_id", "document_id");
            }
        }

        @Test
        @DisplayName("read_document needs both the source and the document")
        void readDocumentNeedsBoth() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                assertThat(tools.call(McpTools.READ_DOCUMENT,
                    Map.of("source_id", "notes")).isError()).isTrue();
                assertThat(tools.call(McpTools.READ_DOCUMENT,
                    Map.of("document_id", "a.md")).isError()).isTrue();
                assertThat(node.requests()).isEmpty();
            }
        }

        @Test
        @DisplayName("erasure_status reads back the receipt erase_documents answered with")
        void erasureStatus() throws IOException {
            try (StubNode node = StubNode.start()) {
                String id = "0b7e1f7a-3c1d-4e59-9a4f-2a6f1d0c9e11";

                new McpTools(node.client(Map.of("EXCALIBUR_NAMESPACE", "acme")))
                    .call(McpTools.ERASURE_STATUS, Map.of("request_id", id));

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("GET");
                assertThat(request.path()).isEqualTo("/v1/erasure/" + id);
                assertThat(request.headers()).doesNotContainKey(ExcaliburClient.NAMESPACE_HEADER);
                assertThat(required(McpTools.ERASURE_STATUS)).containsExactly("request_id");
                assertThat(Json.stringAt(tool(McpTools.ERASURE_STATUS), "description"))
                    .contains(McpTools.ERASE_DOCUMENTS);
                assertThat(Json.stringAt(tool(McpTools.ERASE_DOCUMENTS), "description"))
                    .contains(McpTools.ERASURE_STATUS);
            }
        }

        @Test
        @DisplayName("erasure_status refuses an id that could name another path")
        void erasureStatusRefusesHostileIds() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                for (String hostile : List.of("..", "../compact", "a/b", "a b", "x".repeat(65))) {
                    assertThat(tools.call(McpTools.ERASURE_STATUS,
                        Map.of("request_id", hostile)).isError()).as(hostile).isTrue();
                }
                assertThat(tools.call(McpTools.ERASURE_STATUS, Map.of()).isError()).isTrue();
                assertThat(node.requests()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("stopping work")
    class Stopping {

        @Test
        @DisplayName("list_queries reads the queries in flight, whose ids cancel_query takes")
        void listQueries() throws IOException {
            try (StubNode node = StubNode.start()) {
                new McpTools(node.client(Map.of())).call(McpTools.LIST_QUERIES, Map.of());

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("GET");
                assertThat(request.path()).isEqualTo("/v1/query/status");
            }
        }

        @Test
        @DisplayName("cancel_query deletes the query by its id")
        void cancelQuery() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));
                String id = "b3d9f4a0-1e2c-4a7b-9c3d-5e6f7a8b9c0d";

                tools.call(McpTools.CANCEL_QUERY, Map.of("query_id", id));

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("DELETE");
                assertThat(request.path()).isEqualTo("/v1/query/" + id);
            }
        }

        @Test
        @DisplayName("a query id that is not one is refused, so it cannot reach another path")
        void aHostileQueryIdIsRefused() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                for (String hostile : List.of("..", "../../v1/compact", "a/b", "a b")) {
                    assertThat(tools.call(McpTools.CANCEL_QUERY, Map.of("query_id", hostile))
                        .isError()).as(hostile).isTrue();
                }
                assertThat(tools.call(McpTools.CANCEL_QUERY, Map.of()).isError()).isTrue();
                assertThat(node.requests()).isEmpty();
            }
        }

        @Test
        @DisplayName("cancel_ingestion stops a source's run and keeps it registered")
        void cancelIngestion() throws IOException {
            try (StubNode node = StubNode.start()) {
                new McpTools(node.client(Map.of()))
                    .call(McpTools.CANCEL_INGESTION, Map.of("source_id", "docs-site"));

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("DELETE");
                assertThat(request.path()).isEqualTo("/v1/ingest/run");
                assertThat(Json.stringAt(Json.parse(request.body()), "source_id"))
                    .isEqualTo("docs-site");
            }
        }

        @Test
        @DisplayName("cancel_reindex asks a source's re-crawl to stop")
        void cancelReindex() throws IOException {
            try (StubNode node = StubNode.start()) {
                new McpTools(node.client(Map.of()))
                    .call(McpTools.CANCEL_REINDEX, Map.of("source_id", "notes"));

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("DELETE");
                assertThat(request.path()).isEqualTo("/v1/reindex");
                assertThat(Json.stringAt(Json.parse(request.body()), "source_id"))
                    .isEqualTo("notes");
            }
        }

        @Test
        @DisplayName("cancel_compaction stops the compaction in progress")
        void cancelCompaction() throws IOException {
            try (StubNode node = StubNode.start()) {
                new McpTools(node.client(Map.of())).call(McpTools.CANCEL_COMPACTION, Map.of());

                StubNode.Received request = node.onlyRequest();
                assertThat(request.method()).isEqualTo("DELETE");
                assertThat(request.path()).isEqualTo("/v1/compact");
                assertThat(request.body()).isEmpty();
            }
        }

        @Test
        @DisplayName("cancel_graph_build stops the build, scoped like build_graph")
        void cancelGraphBuild() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of("EXCALIBUR_NAMESPACE", "acme")));

                tools.call(McpTools.CANCEL_GRAPH_BUILD, Map.of());
                tools.call(McpTools.CANCEL_GRAPH_BUILD, Map.of("namespace", "beta"));

                List<StubNode.Received> requests = node.requests();
                assertThat(requests).extracting(StubNode.Received::method)
                    .containsExactly("DELETE", "DELETE");
                assertThat(requests).extracting(StubNode.Received::path)
                    .containsOnly("/v1/graph/build");
                assertThat(requests.get(0).headers().get(ExcaliburClient.NAMESPACE_HEADER))
                    .isEqualTo("acme");
                assertThat(requests.get(1).headers().get(ExcaliburClient.NAMESPACE_HEADER))
                    .isEqualTo("beta");
            }
        }

        @Test
        @DisplayName("stopping tools say they keep what was already done")
        void cancelsDescribeWhatTheyKeep() {
            for (String name : List.of(McpTools.CANCEL_INGESTION, McpTools.CANCEL_REINDEX,
                McpTools.CANCEL_COMPACTION, McpTools.CANCEL_GRAPH_BUILD)) {
                assertThat(Json.stringAt(tool(name), "description"))
                    .as(name).containsIgnoringCase("keep");
            }
        }
    }

    @Nested
    @DisplayName("search_documents")
    class SearchOptions {

        @Test
        @DisplayName("offers paging, a deadline and snippets")
        void theOptionsAreDeclared() {
            assertThat(properties(McpTools.SEARCH_DOCUMENTS))
                .containsKeys("offset", "timeout_ms", "snippet", "snippet_chars");
            assertThat(required(McpTools.SEARCH_DOCUMENTS)).containsExactly("query");
        }

        @Test
        @DisplayName("forwards the options the model set, and only those")
        void theOptionsAreForwarded() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                tools.call(McpTools.SEARCH_DOCUMENTS, Map.of("query", "engine", "offset", 10,
                    "timeout_ms", 2500, "snippet", true, "snippet_chars", 120));
                tools.call(McpTools.SEARCH_DOCUMENTS, Map.of("query", "engine",
                    "snippet", false));

                Object set = Json.parse(node.requests().get(0).body());
                assertThat(Json.at(set, "offset")).isEqualTo(10L);
                assertThat(Json.at(set, "timeout_ms")).isEqualTo(2500L);
                assertThat(Json.at(set, "snippet")).isEqualTo(Boolean.TRUE);
                assertThat(Json.at(set, "snippet_chars")).isEqualTo(120L);
                assertThat(Json.at(set, "include_text")).isEqualTo(Boolean.TRUE);

                Object unset = Json.parse(node.requests().get(1).body());
                assertThat(Json.at(unset, "offset")).isNull();
                assertThat(Json.at(unset, "timeout_ms")).isNull();
                assertThat(Json.at(unset, "snippet_chars")).isNull();
                assertThat(Json.at(unset, "snippet"))
                    .as("false is a setting the model made, and is forwarded")
                    .isEqualTo(Boolean.FALSE);
            }
        }

        @Test
        @DisplayName("an option of the wrong type is refused before sending")
        void wrongTypesAreRefused() throws IOException {
            try (StubNode node = StubNode.start()) {
                McpTools tools = new McpTools(node.client(Map.of()));

                assertThat(tools.call(McpTools.SEARCH_DOCUMENTS,
                    Map.of("query", "engine", "offset", 1.5)).isError()).isTrue();
                assertThat(tools.call(McpTools.SEARCH_DOCUMENTS,
                    Map.of("query", "engine", "snippet", "yes")).isError()).isTrue();
                assertThat(node.requests()).isEmpty();
            }
        }
    }
}
