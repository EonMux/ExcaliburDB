package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.audit.AuditLog;
import com.eonmux.excalibur.api.bootstrap.GraphErasureService;
import com.eonmux.excalibur.api.bootstrap.GraphTenant;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRefusedException;
import com.eonmux.excalibur.api.mcp.Json;
import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.QueryParams;
import com.eonmux.excalibur.api.protocol.RequestBody;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.AssertedFact;
import com.eonmux.excalibur.graph.staging.FactLabels;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.StagedAssertion;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code POST /v1/graph/facts} and {@code DELETE /v1/graph/facts} - facts a caller states, rather
 * than facts extraction found.
 *
 * <h3>Asserting</h3>
 * <pre>
 * POST /v1/graph/facts
 * {"subject": "Ada Lovelace", "relation": "worked with", "object": "Charles Babbage",
 *  "valid_from": "1833-06-05", "valid_to": null, "confidence": 1.0}
 *
 * {"facts": [ {...}, {...} ]}
 * </pre>
 * <p>One fact, or up to {@value #MAX_FACTS} under {@code facts} - the most one extraction call
 * may stage, so a request cannot stage more in one step than a document can. Labels are cleaned
 * and bounded exactly as extracted ones are: surrounding space removed, a relation lower-cased
 * with spaces turned to underscores, each at most {@value FactLabels#MAX_LABEL_CHARS}
 * characters. {@code valid_from} and {@code valid_to} are ISO dates, absent or null for an
 * interval with no known start or no end; {@code confidence} is a fraction, 1.0 when absent.
 * A request with any fact that fails those checks stages none of them.</p>
 *
 * <p>Each fact is staged with {@link SourceType#USER_ASSERTED} provenance under a fact id of its
 * own, which the answer returns. Staged is not served: the fact reaches
 * {@code /v1/graph/query} and {@code /v1/graph/entities} when the graph is next built. The
 * answer is 201 and says so.</p>
 *
 * <h3>Retracting</h3>
 * <pre>
 * DELETE /v1/graph/facts/{fact_id}
 * DELETE /v1/graph/facts?subject=Ada%20Lovelace&amp;relation=worked_with&amp;object=Charles%20Babbage
 * </pre>
 * <p>A fact id is the source id of the fact's provenance, so retracting one is a source-scoped
 * erasure of it, recorded through the same service as {@code POST /v1/graph/erasure}: durable
 * before this answers, and applied to staging and to the served graph by a compaction. By
 * subject, relation and object it retracts every asserted fact stating that triple; facts
 * extraction found are never touched by this route. Nothing staged matching is a 404, and
 * nothing is recorded. Add {@code compact=true} to run the compaction now rather than when the
 * erasure policy calls it due - until it runs, the graph already published still serves the
 * fact. No build publishes it again: a build leaves out whatever an outstanding erasure covers.</p>
 *
 * <p>Fact ids are never reused, whether the fact is retracted or its entity erased, so an id in a
 * client's hands never comes to mean another fact.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; staging and the erasure service serialise what they must.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphFactsEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphFactsEndpoint.class);

    /** The collection this endpoint serves, and every route under it. */
    public static final String PATH = "/v1/graph/facts";

    /** Most facts one request may assert: as many as one extraction call may stage. */
    public static final int MAX_FACTS = LlmRelationExtractor.DEFAULT_MAX_TRIPLES;

    private final GraphTenants tenants;

    /**
     * Creates the endpoint.
     *
     * @param tenants The namespaces facts are asserted into and retracted from
     */
    public GraphFactsEndpoint(GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("POST".equalsIgnoreCase(method)) {
            assertFacts(exchange);
        } else if ("DELETE".equalsIgnoreCase(method)) {
            retract(exchange);
        } else {
            sendError(exchange, 405, "Method not allowed; use POST to assert facts or DELETE "
                + "to retract them");
        }
    }

    // ---------------------------------------------------------------- asserting

    private void assertFacts(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!PATH.equals(path) && !(PATH + "/").equals(path)) {
            sendError(exchange, 404, "facts are asserted by POST " + PATH);
            return;
        }
        String body;
        try {
            body = RequestBody.read(exchange);
        } catch (RequestBody.TooLargeException e) {
            sendError(exchange, 413, "Request body too large");
            return;
        }

        NamespaceId namespace;
        try {
            namespace = namespaceOf(exchange);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, ErrorCode.INVALID_NAMESPACE, e.getMessage());
            return;
        }
        List<AssertedFact> facts;
        try {
            facts = factsFrom(body);
        } catch (IllegalArgumentException | Json.JsonException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        }

        // Borrowing admits the namespace: a name nobody created is refused where the node
        // requires explicit creation, and one past the node's cap is refused everywhere, before
        // anything is staged or any directory is made.
        List<Long> ids;
        try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(namespace)) {
            ids = lease.resource().staging().assertFacts(facts);
        } catch (NamespaceRefusedException e) {
            sendError(exchange, e.reason().statusCode(), e.reason().code(), e.getMessage());
            return;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("asserting {} facts into namespace {} failed", facts.size(), namespace, e);
            sendError(exchange, 500, "Internal Server Error");
            return;
        }
        LOGGER.info("{} facts asserted into namespace {}: ids {}", ids.size(), namespace, ids);

        StringBuilder json = new StringBuilder(128 + facts.size() * 192)
            .append("{\"namespace\":").append(GraphCatalogJson.quoted(namespace.value()))
            .append(",\"staged\":").append(ids.size())
            .append(",\"facts\":[");
        for (int i = 0; i < facts.size(); i++) {
            AssertedFact fact = facts.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"fact_id\":").append(ids.get(i))
                .append(",\"subject\":").append(GraphCatalogJson.quoted(fact.subject()))
                .append(",\"relation\":").append(GraphCatalogJson.quoted(fact.relation()))
                .append(",\"object\":").append(GraphCatalogJson.quoted(fact.object()))
                .append(",\"valid_from\":").append(GraphCatalogJson.validFrom(fact.validFrom()))
                .append(",\"valid_to\":").append(GraphCatalogJson.validTo(fact.validTo()))
                .append(",\"confidence\":").append(JsonText.number(
                    fact.confidence() / (double) Provenance.MAX_CONFIDENCE, 2))
                .append('}');
        }
        json.append("],\"served\":false")
            .append(",\"detail\":").append(GraphCatalogJson.quoted("staged; served once the "
                + "graph is next built (POST /v1/graph/build)"))
            .append('}');
        ResponseBody.sendJson(exchange, 201, json.toString());
    }

    /**
     * Reads the facts a request asserts: one at the top level, or a list under {@code facts}.
     *
     * @param body The request body
     * @return The facts, each validated
     * @throws IllegalArgumentException if the body is not an object, holds no facts or too many,
     *                                  or any fact is malformed
     * @throws Json.JsonException if the body is not JSON
     */
    static List<AssertedFact> factsFrom(String body) {
        Object parsed = Json.parse(body);
        if (!(parsed instanceof Map<?, ?> request)) {
            throw new IllegalArgumentException("the body must be a JSON object: one fact, or "
                + "{\"facts\": [...]}");
        }
        if (!request.containsKey("facts")) {
            return List.of(factFrom(request, "the fact"));
        }
        if (!(request.get("facts") instanceof List<?> listed) || listed.isEmpty()
                || listed.size() > MAX_FACTS) {
            throw new IllegalArgumentException("'facts' must be an array of 1 to " + MAX_FACTS
                + " facts");
        }
        List<AssertedFact> facts = new ArrayList<>(listed.size());
        for (int i = 0; i < listed.size(); i++) {
            String which = "facts[" + i + "]";
            if (!(listed.get(i) instanceof Map<?, ?> fact)) {
                throw new IllegalArgumentException(which + " must be an object");
            }
            facts.add(factFrom(fact, which));
        }
        return facts;
    }

    private static AssertedFact factFrom(Map<?, ?> fact, String which) {
        int validFrom = day(fact, "valid_from", which, TemporalFilter.UNDATED);
        int validTo = day(fact, "valid_to", which, TemporalFilter.OPEN);
        try {
            return new AssertedFact(text(fact, "subject", which), text(fact, "relation", which),
                text(fact, "object", which), validFrom, validTo, confidence(fact, which));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(which + ": " + e.getMessage(), e);
        }
    }

    private static String text(Map<?, ?> fact, String field, String which) {
        Object value = fact.get(field);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(which + " needs '" + field + "' as a string");
        }
        return text;
    }

    /**
     * Reads a day, as an epoch day the stored interval can hold.
     *
     * @param absent What an absent or null field means: undated, or still holding
     */
    private static int day(Map<?, ?> fact, String field, String which, int absent) {
        Object value = fact.get(field);
        if (value == null) {
            return absent;
        }
        if (value instanceof String text) {
            try {
                long day = LocalDate.parse(text.trim()).toEpochDay();
                // Strictly inside the sentinels, which mean "undated" and "still holds".
                if (day > TemporalFilter.UNDATED && day < TemporalFilter.OPEN) {
                    return (int) day;
                }
            } catch (DateTimeParseException notADate) {
                // Answered below.
            }
        }
        throw new IllegalArgumentException(which + ": '" + field + "' must be an ISO-8601 date "
            + "(2019-03-01), or null");
    }

    private static int confidence(Map<?, ?> fact, String which) {
        Object value = fact.get("confidence");
        if (value == null) {
            return Provenance.MAX_CONFIDENCE;
        }
        if (value instanceof Number number) {
            double fraction = number.doubleValue();
            if (fraction >= 0.0 && fraction <= 1.0) {
                return (int) Math.round(fraction * Provenance.MAX_CONFIDENCE);
            }
        }
        throw new IllegalArgumentException(which + ": 'confidence' must be a number from 0 to 1");
    }

    // ---------------------------------------------------------------- retracting

    private void retract(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!Retraction.isRoute(path)) {
            sendError(exchange, 404, "no such route; retract with DELETE " + PATH
                + "/{fact_id} or DELETE " + PATH + "?subject=&relation=&object=");
            return;
        }
        NamespaceId namespace;
        try {
            namespace = namespaceOf(exchange);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, ErrorCode.INVALID_NAMESPACE, e.getMessage());
            return;
        }
        Retraction retraction;
        boolean compactNow;
        try {
            retraction = Retraction.parse(path, exchange.getRequestURI().getRawQuery());
            compactNow = compactFrom(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        }
        // How the facts were named, never the triple itself: a retraction is an erasure, and
        // the audit trail must not keep the labels it removes.
        AuditLog.target(exchange, "namespace", namespace.value());
        AuditLog.target(exchange, "retract_by", retraction.factId() >= 0 ? "fact_id" : "triple");

        try {
            // Never creates the namespace: one that does not exist holds no facts to retract,
            // and answering that costs no directory and no open slot.
            Optional<NamespaceRegistry.Lease<GraphTenant>> borrowed =
                tenants.borrowIfPresent(namespace);
            if (borrowed.isEmpty()) {
                sendError(exchange, 404, ErrorCode.FACT_NOT_FOUND, retraction.notFound());
                return;
            }
            try (NamespaceRegistry.Lease<GraphTenant> lease = borrowed.get()) {
                List<Long> factIds = retraction.matching(lease.resource().staging());
                if (factIds.isEmpty()) {
                    sendError(exchange, 404, ErrorCode.FACT_NOT_FOUND, retraction.notFound());
                    return;
                }
                accept(exchange, namespace, lease.resource().erasureService(), factIds,
                    compactNow);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("retracting facts from namespace {} failed", namespace, e);
            sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * Records one source-scoped erasure per fact, then compacts as asked, and answers 202.
     *
     * <p>Past the first submit the retraction is durable, so a compaction failure is reported in
     * the answer rather than as a 500, which a caller would retry into a second obligation; see
     * {@link GraphErasureEndpoint}, whose rule this follows.</p>
     */
    private void accept(HttpExchange exchange, NamespaceId namespace,
                        GraphErasureService erasure, List<Long> factIds, boolean compactNow)
            throws IOException {
        List<Long> requestIds = new ArrayList<>(factIds.size());
        for (long factId : factIds) {
            requestIds.add(erasure.submit(new ErasureScope.Source(SourceType.USER_ASSERTED,
                factId)));
        }
        LOGGER.info("facts {} retracted from namespace {} as erasure requests {}", factIds,
            namespace, requestIds);
        AuditLog.target(exchange, "fact_ids", List.copyOf(factIds));
        AuditLog.target(exchange, "request_ids", List.copyOf(requestIds));

        GraphErasureService.ErasureOutcome outcome;
        try {
            outcome = compactNow ? erasure.compactNow() : erasure.compactIfDue();
            if (outcome.ran()) {
                tenants.recost(namespace);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("retraction of facts {} was recorded durably, but the compaction that "
                + "followed it failed; the obligation stands and the erasure sweep will retry "
                + "it", factIds, e);
            outcome = new GraphErasureService.ErasureOutcome(false, 0, 0, factIds.size(),
                "the retraction was recorded; the compaction that followed it failed and will "
                    + "be retried by the next sweep");
        }
        ResponseBody.sendJson(exchange, 202, "{"
            + "\"namespace\":" + GraphCatalogJson.quoted(namespace.value())
            + ",\"fact_ids\":" + factIds
            + ",\"request_ids\":" + requestIds
            + ",\"accepted\":true"
            + ",\"compacted\":" + outcome.ran()
            + ",\"edges_removed\":" + outcome.edgesRemoved()
            + ",\"outstanding\":" + erasure.outstandingCount()
            + ",\"detail\":" + GraphCatalogJson.quoted(outcome.ran()
                ? outcome.reason()
                : "retraction recorded; the fact is served until a compaction applies it, "
                    + "though no build publishes it again ("
                    + outcome.reason() + ")")
            + "}");
    }

    /**
     * Which asserted facts a DELETE names: one fact id from the path, or a triple from the query.
     *
     * @param factId   The fact id, or -1 when a triple names them
     * @param subject  The subject, cleaned, when a triple names them
     * @param relation The relation, cleaned
     * @param object   The object, cleaned
     */
    private record Retraction(long factId, String subject, String relation, String object) {

        /**
         * Whether a path is the collection or one fact under it.
         *
         * @param path The decoded request path
         * @return false for anything deeper
         */
        static boolean isRoute(String path) {
            if (PATH.equals(path) || (PATH + "/").equals(path)) {
                return true;
            }
            return path.startsWith(PATH + "/") && path.indexOf('/', PATH.length() + 1) < 0;
        }

        static Retraction parse(String path, String query) {
            if (path.startsWith(PATH + "/") && path.length() > PATH.length() + 1) {
                String segment = path.substring(PATH.length() + 1);
                if (query != null && hasTriple(query)) {
                    throw new IllegalArgumentException("retract by fact id or by subject, "
                        + "relation and object, not both");
                }
                return new Retraction(factId(segment), null, null, null);
            }
            String subject = FactLabels.entity(required(query, "subject"));
            String relation = FactLabels.relation(required(query, "relation"));
            String object = FactLabels.entity(required(query, "object"));
            if (subject == null || relation == null || object == null) {
                throw new IllegalArgumentException("'subject', 'relation' and 'object' must each "
                    + "be 1 to " + FactLabels.MAX_LABEL_CHARS + " characters");
            }
            return new Retraction(-1, subject, relation, object);
        }

        /**
         * The staged asserted facts this retraction names.
         *
         * @param staging The namespace's staging store
         * @return Their fact ids, in staging order; empty when none is staged
         * @throws IOException if the triple log cannot be read
         */
        List<Long> matching(GraphStagingStore staging) throws IOException {
            int subjectId = -1;
            int relationId = -1;
            int objectId = -1;
            if (factId < 0) {
                subjectId = staging.findEntity(subject);
                relationId = staging.findRelation(relation);
                objectId = staging.findEntity(object);
                if (subjectId < 0 || relationId < 0 || objectId < 0) {
                    return List.of();
                }
            }
            List<Long> ids = new ArrayList<>();
            for (StagedAssertion assertion : staging.assertions()) {
                boolean named = factId >= 0
                    ? assertion.factId() == factId
                    : assertion.subject() == subjectId && assertion.relation() == relationId
                        && assertion.object() == objectId;
                if (named && !ids.contains(assertion.factId())) {
                    ids.add(assertion.factId());
                }
            }
            return ids;
        }

        String notFound() {
            return factId >= 0
                ? "no asserted fact " + factId + " is staged in this namespace"
                : "no asserted fact in this namespace states that subject, relation and object";
        }

        private static long factId(String segment) {
            try {
                long id = Long.parseLong(segment);
                if (id >= 0) {
                    return id;
                }
            } catch (NumberFormatException notANumber) {
                // Answered below.
            }
            throw new IllegalArgumentException("a fact id is a non-negative whole number, got \""
                + segment + "\"");
        }

        private static boolean hasTriple(String query) {
            return QueryParams.value(query, "subject") != null
                || QueryParams.value(query, "relation") != null
                || QueryParams.value(query, "object") != null;
        }

        private static String required(String query, String name) {
            String value = QueryParams.value(query, name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("retracting by triple needs 'subject', "
                    + "'relation' and 'object'; '" + name + "' is missing");
            }
            return value;
        }
    }

    private static boolean compactFrom(String query) {
        String value = QueryParams.value(query, "compact");
        if (value == null || "false".equalsIgnoreCase(value)) {
            return false;
        }
        if ("true".equalsIgnoreCase(value) || value.isEmpty()) {
            return true;
        }
        throw new IllegalArgumentException("'compact' must be true or false, got \"" + value
            + "\"");
    }

    private static NamespaceId namespaceOf(HttpExchange exchange) {
        return GraphTenants.namespaceOf(
            exchange.getRequestHeaders().getFirst(GraphTenants.NAMESPACE_HEADER));
    }

    private static void sendError(HttpExchange exchange, int status, String message)
            throws IOException {
        ResponseBody.sendJson(exchange, status, "{\"error\":" + GraphCatalogJson.quoted(message)
            + "}");
    }

    private static void sendError(HttpExchange exchange, int status, ErrorCode code,
                                  String message) throws IOException {
        ResponseBody.sendError(exchange, status, code, message);
    }
}
