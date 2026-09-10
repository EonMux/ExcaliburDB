package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.bootstrap.GraphErasureService;
import excalibur.api.bootstrap.GraphTenant;
import excalibur.api.bootstrap.GraphTenants;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.RequestBody;
import excalibur.api.protocol.ResponseBody;
import io.excalibur.graph.erasure.ErasureScope;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.tenancy.NamespaceId;
import io.excalibur.tenancy.NamespaceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code POST /v1/graph/erasure} - accepts an obligation to remove data from the graph.
 *
 * <p>The request is recorded durably before this answers. An erasure obligation outlives the
 * process that accepted it: a client told "accepted" and then losing the request to a restart is
 * the one outcome that must not happen, because nothing afterwards would notice the data was still
 * there.</p>
 *
 * <p>Accepting is not removing. The edges are rewritten out of the store by a compaction, which the
 * policy schedules against how much has accumulated and how long the oldest request has waited -
 * rewriting the whole store per request would make erasure a denial-of-service. The response says
 * which of the two happened.</p>
 *
 * <h3>Request</h3>
 * <pre>
 * {"scope": "entity",   "entity_id": 42}
 * {"scope": "source",   "source_type": "DOCUMENT", "source_id": 1234}
 * {"scope": "extractor","version": 7}
 * </pre>
 *
 * <p>Optionally {@code "compact": true} to run the compaction immediately rather than leaving it to
 * the policy - for a caller under a deadline that must be able to say the data is gone.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the service it calls serialises compactions.</p>
 *
 * @author ExcaliburDB
 */
public class GraphErasureEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphErasureEndpoint.class);

    private final GraphTenants tenants;

    /**
     * Creates the endpoint.
     *
     * @param tenants The namespaces whose graphs can be erased from
     */
    public GraphErasureEndpoint(GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405, "{\"error\": \"Method not allowed; use POST\"}");
            return;
        }

        String body;
        try {
            body = RequestBody.read(exchange);
        } catch (IllegalArgumentException e) {
            ResponseBody.sendJson(exchange, 413, "{\"error\": \"Request body too large\"}");
            return;
        }

        NamespaceId namespace;
        ErasureScope scope;
        try {
            namespace = GraphTenants.namespaceOf(
                exchange.getRequestHeaders().getFirst(GraphTenants.NAMESPACE_HEADER));
            scope = scopeFrom(body);
        } catch (IllegalArgumentException e) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(e.getMessage()) + "\"}");
            return;
        }

        try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(namespace)) {
            GraphErasureService erasureService = lease.resource().erasureService();
            long id = erasureService.submit(scope);
            boolean compactNow = JsonText.booleanValue(body, "compact", false);

            GraphErasureService.ErasureOutcome outcome = compactNow
                ? erasureService.compactNow()
                : erasureService.compactIfDue();
            if (outcome.ran()) {
                // The store on disk has been rewritten, so any stack this node still holds for
                // the namespace describes edges that are no longer there.
                tenants.invalidate(namespace);
            }

            ResponseBody.sendJson(exchange, 202,
                "{"
                    + "\"request_id\": " + id + ", "
                    + "\"accepted\": true, "
                    + "\"compacted\": " + outcome.ran() + ", "
                    + "\"edges_removed\": " + outcome.edgesRemoved() + ", "
                    + "\"requests_satisfied\": " + outcome.requestsSatisfied() + ", "
                    + "\"outstanding\": " + erasureService.outstandingCount() + ", "
                    + "\"namespace\": \"" + JsonText.escape(namespace.value()) + "\", "
                    + "\"detail\": \"" + JsonText.escape(outcome.reason()) + "\""
                    + "}");
        } catch (IOException | RuntimeException e) {
            // RuntimeException is not defensive padding: a namespace that cannot be opened
            // arrives here as an UncheckedIOException from the registry, and catching only
            // IOException would let it escape the handler with no response sent at all.
            //
            // Logged with detail, answered without it: the message names the path the node
            // failed on, which a client has no business seeing.
            LOGGER.error("Erasure request failed", e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
        }
    }

    /**
     * Reads the scope a request names.
     *
     * @param body The request body
     * @return The scope
     * @throws IllegalArgumentException if the scope is missing, unknown, or missing its field
     */
    private static ErasureScope scopeFrom(String body) {
        String scope = JsonText.string(body, "scope");
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException(
                "'scope' is required and must be one of: entity, source, extractor");
        }

        return switch (scope.trim().toLowerCase(Locale.ROOT)) {
            case "entity" -> new ErasureScope.Entity(
                requireLong(body, "entity_id", "scope 'entity' requires 'entity_id'"));
            case "source" -> new ErasureScope.Source(
                sourceTypeFrom(body),
                requireLong(body, "source_id", "scope 'source' requires 'source_id'"));
            case "extractor" -> new ErasureScope.ExtractorVersion(extractorVersionFrom(body));
            default -> throw new IllegalArgumentException(
                "unknown scope '" + scope + "'; expected entity, source or extractor");
        };
    }

    /**
     * Reads the extractor version an extractor-scoped request names.
     *
     * <p>The store keeps the version in 32 bits, so a value outside that range has to be
     * refused rather than narrowed. Narrowing is not a cosmetic loss here: {@code 2^32 + 1}
     * becomes {@code 1}, and the request would be accepted as an obligation to erase
     * everything version 1 ever extracted - irreversibly, and reported to the caller as
     * {@code "accepted": true}.</p>
     *
     * @param body The request body
     * @return The version
     * @throws IllegalArgumentException if it is missing or outside the range the store holds
     */
    private static int extractorVersionFrom(String body) {
        long version = requireLong(body, "version", "scope 'extractor' requires 'version'");
        if (version < 0 || version > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "'version' must be between 0 and " + Integer.MAX_VALUE + ", got " + version);
        }
        return (int) version;
    }

    /**
     * Reads the source type a source-scoped request names.
     *
     * @param body The request body
     * @return The source type
     * @throws IllegalArgumentException if it is missing or not a known type
     */
    private static SourceType sourceTypeFrom(String body) {
        String value = JsonText.string(body, "source_type");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("scope 'source' requires 'source_type'");
        }
        try {
            return SourceType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "unknown source_type '" + value + "'; expected one of UNKNOWN, KNOWLEDGE_BASE, "
                    + "DOCUMENT, CONVERSATION, USER_ASSERTED, INFERRED");
        }
    }

    /**
     * Reads a required numeric field.
     *
     * @param body    The request body
     * @param field   The field name
     * @param message What to say when it is absent
     * @return The value
     * @throws IllegalArgumentException if the field is absent
     */
    private static long requireLong(String body, String field, String message) {
        // The sentinel is what says "absent": the parser reports a default rather than null, and
        // a negative id is not a value any of these fields can legitimately take.
        long value = JsonText.longValue(body, field, Long.MIN_VALUE);
        if (value == Long.MIN_VALUE) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
