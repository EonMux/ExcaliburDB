package com.eonmux.excalibur.api.audit;

import com.eonmux.excalibur.api.security.RequestGuard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The record of every request that changed, or tried to change, what this node holds or how it
 * behaves.
 *
 * <h2>Why a logger of its own</h2>
 * <p>An operator asked "who erased this, and when" - or "who drained this node" - has to be able
 * to answer from something other than the absence of a warning. Those actions succeeded quietly:
 * the handlers log at INFO what they did, in prose, under their class name, beside every other
 * line the node writes, and several log nothing at all on success. Every audited route now
 * writes exactly one event to {@value #LOGGER_NAME}, whatever its outcome, so the audit trail is
 * one logger name to route, retain and alert on, and its shape is the same for every action.</p>
 *
 * <h2>What an event carries</h2>
 * <p>Structured key/value pairs, which the node's JSON encoder writes as top-level fields:</p>
 * <ul>
 *   <li>{@code audit_action} - what was asked for, e.g. {@code erasure.graph}</li>
 *   <li>{@code audit_outcome} - {@code success}, {@code accepted} (a 202: recorded, finishing
 *       in the background), {@code rejected} (4xx), {@code unauthenticated} (401),
 *       {@code rate_limited} (429), {@code failed} (5xx) or {@code error} (the handler threw
 *       before answering)</li>
 *   <li>{@code http_status} - the status the caller was answered with</li>
 *   <li>{@code request_id} - the caller's {@value #REQUEST_ID_HEADER} when it sent a usable one,
 *       otherwise one generated here; returned to the caller in the same header</li>
 *   <li>{@code client_address} - the client as the request guard resolved it, which honours
 *       {@code excalibur.api.trusted-proxies} exactly as rate limiting does</li>
 *   <li>{@code http_method}, {@code http_path}</li>
 *   <li>{@code audit_targets} - an object naming what the action was applied to: source ids,
 *       namespaces, request ids, counts</li>
 * </ul>
 * <p>The event's own timestamp is the time it was written, when the request finished.</p>
 *
 * <h2>What an event never carries</h2>
 * <p>The content an erasure removes. Targets are identifiers and counts chosen by each route: a
 * graph erasure records the entity id it resolved and never the name it was asked by; a
 * document erasure records its source and how many documents it named, never which. An audit
 * trail is kept longer and read more widely than the data it describes, and copying an erased
 * name into it would defeat the erasure it records.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless. Each exchange's targets live on the exchange.</p>
 *
 * @author ExcaliburDB
 */
public final class AuditLog {

    /** The logger every audit event is written to. */
    public static final String LOGGER_NAME = "excalibur.audit";

    /** The header a request id is read from and returned in. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** The exchange attribute holding the request id once one has been settled. */
    public static final String REQUEST_ID_ATTRIBUTE = "excalibur.request_id";

    private static final String TARGETS_ATTRIBUTE = "excalibur.audit.targets";

    /**
     * What a caller-supplied request id may look like.
     *
     * <p>Bounded and plain, because it is written into every event and echoed in a response
     * header: anything longer or stranger is replaced rather than carried, so a caller cannot
     * choose what the audit trail says beyond an identifier.</p>
     */
    private static final Pattern USABLE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER_NAME);

    private AuditLog() {
    }

    /**
     * Wraps a route so every request reaching it leaves one audit event.
     *
     * <p>Register it outside the request guard, so a request the guard refuses - unauthenticated
     * or over its budget - is recorded as an attempt too; the event is written after the handler
     * has answered, and says how.</p>
     *
     * @param action  What the route does, e.g. {@code source.register}
     * @param handler The route
     * @return The audited route
     * @throws NullPointerException if either argument is null
     */
    public static HttpHandler audited(String action, HttpHandler handler) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        return exchange -> {
            String requestId = requestId(exchange);
            try {
                handler.handle(exchange);
            } catch (IOException | RuntimeException e) {
                record(exchange, action, requestId, "error", -1);
                throw e;
            }
            int status = exchange.getResponseCode();
            record(exchange, action, requestId, outcomeOf(status), status);
        };
    }

    /**
     * Names something the audited action was applied to.
     *
     * <p>Called by the route once it knows: a value is an identifier or a count, never content.
     * Does nothing on an exchange no audited route is handling, so a handler can call it
     * whether or not it was registered as audited.</p>
     *
     * @param exchange The exchange being handled
     * @param name     The target's name, e.g. {@code source_id}
     * @param value    Its value; null is ignored
     */
    public static void target(HttpExchange exchange, String name, Object value) {
        if (value == null || !(exchange.getAttribute(TARGETS_ATTRIBUTE) instanceof Map<?, ?>)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> targets = (Map<String, Object>) exchange.getAttribute(
            TARGETS_ATTRIBUTE);
        targets.put(name, value);
    }

    /**
     * The request id this exchange is recorded under, settling one if none has been.
     *
     * <p>The caller's own {@value #REQUEST_ID_HEADER} when it is usable, so a client can
     * correlate its request with the node's record of it; otherwise a fresh one. Either way it
     * is returned in the response header, and a route may repeat it in its body.</p>
     *
     * @param exchange The exchange
     * @return The id
     */
    public static String requestId(HttpExchange exchange) {
        Object settled = exchange.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (settled instanceof String id) {
            return id;
        }
        String supplied = exchange.getRequestHeaders().getFirst(REQUEST_ID_HEADER);
        String id = supplied != null && USABLE_REQUEST_ID.matcher(supplied).matches()
            ? supplied
            : UUID.randomUUID().toString();
        exchange.setAttribute(REQUEST_ID_ATTRIBUTE, id);
        exchange.setAttribute(TARGETS_ATTRIBUTE, Collections.synchronizedMap(
            new LinkedHashMap<String, Object>()));
        exchange.getResponseHeaders().set(REQUEST_ID_HEADER, id);
        return id;
    }

    /**
     * What an answer says about how the action went.
     *
     * @param status The response status, or -1 when none was sent
     * @return The outcome word an event carries
     */
    static String outcomeOf(int status) {
        if (status == 401) {
            return "unauthenticated";
        }
        if (status == 429) {
            return "rate_limited";
        }
        if (status == 202) {
            return "accepted";
        }
        if (status >= 200 && status < 300) {
            return "success";
        }
        if (status >= 400 && status < 500) {
            return "rejected";
        }
        if (status >= 500) {
            return "failed";
        }
        return "error";
    }

    private static void record(HttpExchange exchange, String action, String requestId,
                               String outcome, int status) {
        Map<String, Object> targets = targetsOf(exchange);
        String client = RequestGuard.clientAddress(exchange);
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        LoggingEventBuilder event = AUDIT.atInfo()
            .addKeyValue("audit_action", action)
            .addKeyValue("audit_outcome", outcome)
            .addKeyValue("http_status", status)
            .addKeyValue("request_id", requestId)
            .addKeyValue("client_address", client)
            .addKeyValue("http_method", method)
            .addKeyValue("http_path", path)
            .addKeyValue("audit_targets", targets);
        // The message repeats the fields for a deployment reading plain text; the pairs are
        // what a JSON encoder writes and what a log pipeline should match on.
        event.log("audit {} {} status={} request_id={} client={} targets={}",
            action, outcome, status, requestId, client, targets);
    }

    private static Map<String, Object> targetsOf(HttpExchange exchange) {
        if (exchange.getAttribute(TARGETS_ATTRIBUTE) instanceof Map<?, ?> targets) {
            synchronized (targets) {
                Map<String, Object> copy = new LinkedHashMap<>();
                targets.forEach((name, value) -> copy.put(String.valueOf(name), value));
                return copy;
            }
        }
        return Map.of();
    }
}
