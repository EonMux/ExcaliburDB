package com.eonmux.excalibur.api.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eonmux.excalibur.api.StubExchange;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.security.RequestGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit trail: one event per audited request, on one logger, in one shape.
 */
@DisplayName("Audit log")
class AuditLogTest {

    private ListAppender<ILoggingEvent> events;
    private Logger audit;

    @BeforeEach
    void capture() {
        audit = (Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME);
        events = new ListAppender<>();
        events.start();
        audit.addAppender(events);
    }

    @AfterEach
    void release() {
        audit.detachAppender(events);
    }

    private static HttpHandler answering(int status, String targetName, Object targetValue) {
        return exchange -> {
            AuditLog.target(exchange, targetName, targetValue);
            ResponseBody.sendJson(exchange, status, "{}");
        };
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
            .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    @Test
    @DisplayName("records the action, outcome, status, client, request id and targets")
    void recordsOneEvent() throws IOException {
        StubExchange exchange = new StubExchange("POST", "/v1/ingest", "{}");

        AuditLog.audited("source.register", answering(200, "source_id", "docs"))
            .handle(exchange);

        assertThat(events.list).hasSize(1);
        ILoggingEvent event = events.list.get(0);
        assertThat(event.getLoggerName()).isEqualTo("excalibur.audit");
        Map<String, Object> fields = fields(event);
        assertThat(fields).containsEntry("audit_action", "source.register")
            .containsEntry("audit_outcome", "success")
            .containsEntry("http_status", 200)
            .containsEntry("client_address", "203.0.113.9")
            .containsEntry("http_method", "POST")
            .containsEntry("http_path", "/v1/ingest")
            .containsEntry("audit_targets", Map.of("source_id", "docs"));
        assertThat((String) fields.get("request_id")).isNotBlank()
            .isEqualTo(exchange.getResponseHeaders().getFirst(AuditLog.REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("carries a usable caller request id and replaces an unusable one")
    void requestIds() throws IOException {
        StubExchange usable = new StubExchange("DELETE", "/v1/drain", null)
            .header(AuditLog.REQUEST_ID_HEADER, "abc-123");
        AuditLog.audited("node.undrain", answering(200, "x", null)).handle(usable);
        assertThat(fields(events.list.get(0))).containsEntry("request_id", "abc-123");

        StubExchange forged = new StubExchange("DELETE", "/v1/drain", null)
            .header(AuditLog.REQUEST_ID_HEADER, "evil\" injected {}");
        AuditLog.audited("node.undrain", answering(200, "x", null)).handle(forged);
        assertThat((String) fields(events.list.get(1)).get("request_id"))
            .doesNotContain("evil").hasSize(36);
    }

    @Test
    @DisplayName("says how each kind of answer went")
    void outcomes() {
        assertThat(AuditLog.outcomeOf(200)).isEqualTo("success");
        assertThat(AuditLog.outcomeOf(204)).isEqualTo("success");
        assertThat(AuditLog.outcomeOf(202)).isEqualTo("accepted");
        assertThat(AuditLog.outcomeOf(400)).isEqualTo("rejected");
        assertThat(AuditLog.outcomeOf(409)).isEqualTo("rejected");
        assertThat(AuditLog.outcomeOf(401)).isEqualTo("unauthenticated");
        assertThat(AuditLog.outcomeOf(429)).isEqualTo("rate_limited");
        assertThat(AuditLog.outcomeOf(500)).isEqualTo("failed");
        assertThat(AuditLog.outcomeOf(-1)).isEqualTo("error");
        assertThat(AuditLog.outcomeOf(302)).isEqualTo("error");
    }

    @Test
    @DisplayName("records a handler that threw, and lets the failure through")
    void recordsAThrowingHandler() {
        StubExchange exchange = new StubExchange("POST", "/v1/compact", null);
        HttpHandler throwing = e -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> AuditLog.audited("compaction.start", throwing).handle(exchange))
            .isInstanceOf(IllegalStateException.class);
        assertThat(fields(events.list.get(0)))
            .containsEntry("audit_outcome", "error")
            .containsEntry("http_status", -1);
    }

    @Test
    @DisplayName("records an attempt the guard refused, naming the client behind a trusted proxy")
    void recordsRefusedAttempts() throws IOException {
        RequestGuard guard = new RequestGuard(ApiSecurityConfig.builder()
            .authToken("secret-token-value")
            .trustProxy("10.0.0.0/8")
            .build());
        StubExchange exchange = new StubExchange("POST", "/v1/erasure", "{}",
            new InetSocketAddress("10.1.2.3", 5000))
            .header("X-Forwarded-For", "2001:db8::7");

        AuditLog.audited("erasure.documents",
            guard.protect(RequestGuard.Access.MUTATE, answering(200, "source_id", "docs")))
            .handle(exchange);

        Map<String, Object> fields = fields(events.list.get(0));
        assertThat(fields).containsEntry("audit_outcome", "unauthenticated")
            .containsEntry("http_status", 401)
            .containsEntry("client_address", "2001:db8:0:0:0:0:0:7")
            .containsEntry("audit_targets", Map.of());
    }

    @Test
    @DisplayName("a target named outside an audited route is ignored")
    void targetsOutsideAnAuditedRoute() {
        StubExchange exchange = new StubExchange("GET", "/v1/stats", null);

        AuditLog.target(exchange, "source_id", "docs");

        assertThat(exchange.getAttribute("excalibur.audit.targets")).isNull();
    }

    @Test
    @DisplayName("the shipped JSON encoder writes the fields at the top level")
    void encodesAsJson() throws IOException {
        AuditLog.audited("graph.namespace.delete", answering(200, "namespace", "tenant-a"))
            .handle(new StubExchange("DELETE", "/v1/graph/namespaces/tenant-a", null));

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.start();
        try {
            JsonNode json = new ObjectMapper().readTree(new String(
                encoder.encode(events.list.get(0)), StandardCharsets.UTF_8));
            assertThat(json.path("logger_name").asText()).isEqualTo("excalibur.audit");
            assertThat(json.path("audit_action").asText()).isEqualTo("graph.namespace.delete");
            assertThat(json.path("audit_outcome").asText()).isEqualTo("success");
            assertThat(json.path("http_status").asInt()).isEqualTo(200);
            assertThat(json.path("audit_targets").path("namespace").asText())
                .isEqualTo("tenant-a");
        } finally {
            encoder.stop();
        }
    }

    @Test
    @DisplayName("every event is written at INFO, so a quieter root level does not drop it")
    void writtenAtInfo() throws IOException {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Level before = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.WARN);
        try {
            AuditLog.audited("node.drain", answering(200, "x", null))
                .handle(new StubExchange("POST", "/v1/drain", null));
        } finally {
            root.setLevel(before);
        }

        assertThat(events.list).hasSize(1);
        List<KeyValuePair> pairs = events.list.get(0).getKeyValuePairs();
        assertThat(pairs).isNotEmpty();
        assertThat(events.list.get(0).getLevel().toString()).isEqualTo("INFO");
    }
}
