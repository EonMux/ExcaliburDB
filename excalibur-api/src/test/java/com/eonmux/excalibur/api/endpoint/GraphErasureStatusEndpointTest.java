package com.eonmux.excalibur.api.endpoint;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eonmux.excalibur.api.StubExchange;
import com.eonmux.excalibur.api.audit.AuditLog;
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
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code GET /v1/graph/erasure/{request_id}} - reading back what a 202 promised.
 */
@DisplayName("Graph erasure status endpoint")
class GraphErasureStatusEndpointTest {

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

    private StubExchange post(String body, String namespace) throws IOException {
        StubExchange exchange = new StubExchange("POST", "/v1/graph/erasure", body);
        if (namespace != null) {
            exchange.header(GraphTenants.NAMESPACE_HEADER, namespace);
        }
        new GraphErasureEndpoint(tenants).handle(exchange);
        assertThat(exchange.getResponseCode()).as(exchange.responseText()).isEqualTo(202);
        return exchange;
    }

    private long submit(String body, String namespace) throws IOException {
        return (Long) Json.at(Json.parse(post(body, namespace).responseText()), "request_id");
    }

    private StubExchange get(String path, String namespace) throws IOException {
        StubExchange exchange = new StubExchange("GET", path, "");
        if (namespace != null) {
            exchange.header(GraphTenants.NAMESPACE_HEADER, namespace);
        }
        new GraphErasureStatusEndpoint(tenants).handle(exchange);
        return exchange;
    }

    private Object status(long id, String namespace) throws IOException {
        StubExchange exchange = get("/v1/graph/erasure/" + id, namespace);
        assertThat(exchange.getResponseCode()).as(exchange.responseText()).isEqualTo(200);
        return Json.parse(exchange.responseText());
    }

    @Test
    @DisplayName("an accepted request reads back as pending, with its scope and what is left")
    void pendingRequest() throws IOException {
        long id = submit(SOURCE, "acme");

        Object json = status(id, "acme");

        assertThat(Json.at(json, "request_id")).isEqualTo(id);
        assertThat(Json.stringAt(json, "state")).isEqualTo("pending");
        assertThat(Json.stringAt(json, "namespace")).isEqualTo("acme");
        assertThat(Json.stringAt(json, "scope")).isEqualTo("source");
        assertThat(Json.stringAt(json, "source_type")).isEqualTo("DOCUMENT");
        assertThat(Json.at(json, "source_id")).isEqualTo(99L);
        assertThat(Json.at(json, "requested_at_ms")).isInstanceOf(Long.class);
        assertThat(Json.at(json, "discharged_at_ms")).isNull();
        assertThat(Json.at(json, "detail_retained")).isEqualTo(Boolean.TRUE);
        assertThat(Json.stringAt(json, "remaining")).contains("compaction");
    }

    @Test
    @DisplayName("a discharged request says when, and owes nothing")
    void dischargedRequest() throws IOException {
        long id = submit("{\"scope\": \"extractor\", \"version\": 3, \"compact\": true}", null);

        Object json = status(id, null);

        assertThat(Json.stringAt(json, "state")).isEqualTo("discharged");
        assertThat(Json.stringAt(json, "namespace")).isEqualTo(NamespaceId.SHARED.value());
        assertThat(Json.stringAt(json, "scope")).isEqualTo("extractor");
        assertThat(Json.at(json, "version")).isEqualTo(3L);
        assertThat((Long) Json.at(json, "discharged_at_ms"))
            .isGreaterThanOrEqualTo((Long) Json.at(json, "requested_at_ms"));
        assertThat(Json.at(json, "remaining")).isNull();
    }

    @Test
    @DisplayName("both states survive a restart, read from a namespace that is not reopened")
    void survivesRestart() throws IOException {
        // Two namespaces, because a forced compaction discharges everything its namespace owes.
        long pending = submit(SOURCE, "acme");
        long discharged = submit(
            "{\"scope\": \"extractor\", \"version\": 5, \"compact\": true}", "beta");
        tenants.close();
        tenants = open();

        assertThat(Json.stringAt(status(pending, "acme"), "state")).isEqualTo("pending");
        Object after = status(discharged, "beta");
        assertThat(Json.stringAt(after, "state")).isEqualTo("discharged");
        assertThat(Json.at(after, "discharged_at_ms")).isInstanceOf(Long.class);
        assertThat(tenants.openCount())
            .as("asking about a request never opens the namespace it belongs to")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a request named by entity_name reads back by id, and neither the status nor "
        + "the audit trail carries the name")
    void nameIsNeverEchoed() throws IOException {
        tenants.shared().staging().entityIdOf("Ada Quazzlethorpe");
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        Logger audit = (Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME);
        audit.addAppender(events);
        StubExchange exchange = new StubExchange("POST", "/v1/graph/erasure",
            "{\"scope\": \"entity\", \"entity_name\": \"Ada Quazzlethorpe\"}");
        try {
            AuditLog.audited("erasure.graph", new GraphErasureEndpoint(tenants))
                .handle(exchange);
        } finally {
            audit.detachAppender(events);
        }
        long id = (Long) Json.at(Json.parse(exchange.responseText()), "request_id");

        StubExchange read = get("/v1/graph/erasure/" + id, null);

        assertThat(read.responseText()).doesNotContain("Quazzlethorpe");
        assertThat(Json.stringAt(Json.parse(read.responseText()), "scope")).isEqualTo("entity");
        assertThat(Json.at(Json.parse(read.responseText()), "entity_id")).isEqualTo(0L);
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("Quazzlethorpe");
            assertThat(event.getKeyValuePairs().toString()).doesNotContain("Quazzlethorpe");
            assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo("audit_targets");
                assertThat(new HashMap<Object, Object>((Map<?, ?>) pair.value)).containsEntry("entity_by", "name")
                    .containsEntry("entity_id", 0L).containsEntry("request_id", id)
                    .containsEntry("scope", "entity");
            });
        });
    }

    @Test
    @DisplayName("an id never handed out is a 404, in an existing namespace or a missing one, "
        + "and asking creates nothing")
    void unknownIds() throws IOException {
        submit(SOURCE, "acme");

        assertThat(get("/v1/graph/erasure/5000", "acme").getResponseCode()).isEqualTo(404);
        assertThat(get("/v1/graph/erasure/1", "nobody").getResponseCode()).isEqualTo(404);
        assertThat(Files.exists(NamespaceId.of("nobody").directoryUnder(root))).isFalse();
    }

    @Test
    @DisplayName("a path that does not end in a positive id, or an unusable namespace, is a 400")
    void malformedRequests() throws IOException {
        for (String path : new String[] {"/v1/graph/erasure/abc", "/v1/graph/erasure/0",
            "/v1/graph/erasure/1/2", "/v1/graph/erasure/", "/v1/graph/erasure/-3",
            "/v1/graph/erasure/99999999999999999999"}) {
            assertThat(get(path, null).getResponseCode()).as(path).isEqualTo(400);
        }
        assertThat(get("/v1/graph/erasure/1", "../escape").getResponseCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a trailing slash is accepted and anything but GET is refused")
    void trailingSlashAndMethods() throws IOException {
        long id = submit(SOURCE, null);
        assertThat(get("/v1/graph/erasure/" + id + "/", null).getResponseCode()).isEqualTo(200);

        StubExchange put = new StubExchange("PUT", "/v1/graph/erasure/" + id, "");
        new GraphErasureStatusEndpoint(tenants).handle(put);
        assertThat(put.getResponseCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("an id past the range a long holds is refused rather than wrapped")
    void outOfRange() {
        assertThatThrownBy(() -> GraphErasureStatusEndpoint.requestIdOf(
            "/v1/graph/erasure/9999999999999999999", "/v1/graph/erasure"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("out of range");
    }
}
