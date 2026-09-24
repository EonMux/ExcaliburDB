package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.StubExchange;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.mcp.Json;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /v1/graph/erasure} - finding a namespace's obligations without holding their ids.
 */
@DisplayName("Graph erasure listing endpoint")
class GraphErasureListEndpointTest {

    private static final String SOURCE =
        "{\"scope\": \"source\", \"source_type\": \"DOCUMENT\", \"source_id\": 99}";

    @TempDir
    Path root;

    private GraphTenants tenants;

    @BeforeEach
    void setUp() throws IOException {
        tenants = open();
    }

    @AfterEach
    void tearDown() {
        if (tenants != null) {
            tenants.close();
        }
    }

    private GraphTenants open() throws IOException {
        Config config = new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
        return new GraphTenants(root, config, MappingBudget.detect(), 4);
    }

    private long submit(String body, String namespace) throws IOException {
        StubExchange exchange = new StubExchange("POST", "/v1/graph/erasure", body);
        if (namespace != null) {
            exchange.header(GraphTenants.NAMESPACE_HEADER, namespace);
        }
        new GraphErasureEndpoint(tenants).handle(exchange);
        assertThat(exchange.getResponseCode()).as(exchange.responseText()).isEqualTo(202);
        return (Long) Json.at(Json.parse(exchange.responseText()), "request_id");
    }

    private StubExchange get(String query, String namespace) throws IOException {
        StubExchange exchange = new StubExchange("GET", "/v1/graph/erasure" + query, "");
        if (namespace != null) {
            exchange.header(GraphTenants.NAMESPACE_HEADER, namespace);
        }
        new GraphErasureListEndpoint(tenants).handle(exchange);
        return exchange;
    }

    private Object list(String query, String namespace) throws IOException {
        StubExchange exchange = get(query, namespace);
        assertThat(exchange.getResponseCode()).as(exchange.responseText()).isEqualTo(200);
        return Json.parse(exchange.responseText());
    }

    private static List<?> requests(Object json) {
        return (List<?>) Json.at(json, "requests");
    }

    @Test
    @DisplayName("lists the namespace's obligations, each spelled as the member route spells it")
    void listsObligations() throws IOException {
        long first = submit(SOURCE, "acme");
        long second = submit("{\"scope\": \"extractor\", \"version\": 2}", "acme");

        Object json = list("", "acme");

        assertThat(Json.stringAt(json, "namespace")).isEqualTo("acme");
        assertThat(Json.at(json, "count")).isEqualTo(2L);
        assertThat(Json.at(json, "total")).isEqualTo(2L);
        assertThat(Json.at(json, "next_cursor")).isNull();
        Object one = requests(json).get(0);
        assertThat(Json.at(one, "request_id")).isEqualTo(first);
        assertThat(Json.stringAt(one, "state")).isEqualTo("pending");
        assertThat(Json.stringAt(one, "scope")).isEqualTo("source");
        assertThat(Json.at(one, "source_id")).isEqualTo(99L);
        assertThat(Json.at(one, "detail_retained")).isEqualTo(Boolean.TRUE);
        assertThat(Json.at(requests(json).get(1), "request_id")).isEqualTo(second);
    }

    @Test
    @DisplayName("pages by cursor, and narrows by state")
    void pagesAndNarrows() throws IOException {
        long first = submit(SOURCE, "acme");
        long second = submit(SOURCE, "acme");
        long discharged = submit("{\"scope\": \"extractor\", \"version\": 3, \"compact\": true}",
            "acme");

        Object page = list("?limit=1", "acme");
        assertThat(Json.at(requests(page).get(0), "request_id")).isEqualTo(first);
        assertThat(Json.stringAt(page, "next_cursor")).isEqualTo(Long.toString(first));
        Object next = list("?limit=1&cursor=" + first, "acme");
        assertThat(Json.at(requests(next).get(0), "request_id")).isEqualTo(second);

        // The forced compaction discharged everything the namespace owed.
        Object done = list("?state=discharged", "acme");
        assertThat(requests(done)).hasSize(3);
        assertThat(Json.at(requests(done).get(2), "request_id")).isEqualTo(discharged);
        assertThat(requests(list("?state=pending", "acme"))).isEmpty();
    }

    @Test
    @DisplayName("reads a namespace that is not open from its log, without opening it")
    void readsClosedNamespaces() throws IOException {
        long id = submit(SOURCE, "acme");
        tenants.close();
        tenants = open();

        Object json = list("", "acme");

        assertThat(Json.at(requests(json).get(0), "request_id")).isEqualTo(id);
        assertThat(tenants.openCount())
            .as("only the shared namespace, which is always open")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a namespace that does not exist lists nothing, and asking creates nothing")
    void missingNamespacesListNothing() throws IOException {
        Object json = list("", "nobody");

        assertThat(requests(json)).isEmpty();
        assertThat(Json.at(json, "total")).isEqualTo(0L);
        assertThat(Files.exists(NamespaceId.of("nobody").directoryUnder(root))).isFalse();
    }

    @Test
    @DisplayName("a malformed state, limit, cursor or namespace is a 400")
    void malformedRequests() throws IOException {
        for (String query : new String[] {"?state=done", "?limit=0", "?limit=1001",
            "?limit=x", "?cursor=abc", "?cursor=0", "?cursor=-4"}) {
            StubExchange exchange = get(query, null);
            assertThat(exchange.getResponseCode()).as(query).isEqualTo(400);
            assertThat(Json.stringAt(Json.parse(exchange.responseText()), "code")).as(query)
                .isEqualTo("invalid_request");
        }
        assertThat(get("", "Not A Namespace!").getResponseCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("answers any method but GET 405")
    void refusesOtherMethods() throws IOException {
        StubExchange exchange = new StubExchange("DELETE", "/v1/graph/erasure", "");
        new GraphErasureListEndpoint(tenants).handle(exchange);

        assertThat(exchange.getResponseCode()).isEqualTo(405);
    }
}
