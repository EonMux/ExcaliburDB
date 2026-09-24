package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphRagHolder;
import com.eonmux.excalibur.api.bootstrap.GraphTenant;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.QueryParams;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.graph.browse.CatalogEdge;
import com.eonmux.excalibur.graph.browse.CatalogEntity;
import com.eonmux.excalibur.graph.browse.CatalogPage;
import com.eonmux.excalibur.graph.browse.EdgeDirection;
import com.eonmux.excalibur.graph.browse.GraphCatalog;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code GET /v1/graph/entities} and the routes under it - the served graph, read by entity.
 *
 * <p>{@code POST /v1/graph/query} answers a question. It cannot say what extraction produced, what
 * the graph holds about one entity, or which id an entity has; these routes can. They read the
 * graph the query is answered from, through the same lease, so a listing never describes a graph
 * the query would not use.</p>
 *
 * <h3>Routes</h3>
 * <pre>
 * GET /v1/graph/entities?name=Ada%20Lovelace          entities with exactly this name
 * GET /v1/graph/entities?prefix=ada                   entities whose name starts with this
 * GET /v1/graph/entities                              every entity, in name order
 * GET /v1/graph/entities/{id}                         one entity, its edges counted by relation
 * GET /v1/graph/entities/{id}/edges?relation=&amp;direction=out|in|both&amp;as_of=
 * </pre>
 * <p>Names match ignoring case and surrounding space. Every listing takes {@code limit}
 * (default {@value #DEFAULT_LIMIT}, at most {@value GraphCatalog#MAX_LIMIT}) and
 * {@code cursor}, and answers with a {@code next_cursor} that is null on the last page. A cursor
 * names the last item returned rather than a position, so one carried across a rebuild resumes
 * after that item.</p>
 *
 * <p>{@code as_of} is spelled as the query spells it: an ISO date, an ISO instant, or
 * {@code all}; absent is all of valid time, since a listing is a record of what is held rather
 * than an answer about today.</p>
 *
 * <h3>No graph</h3>
 * <p>A namespace with no graph being served - never built, a file of it damaged, or withheld
 * until an interrupted erasure has been applied - is answered 503, exactly as the query answers
 * it and for the query's reason: every tenant presents the same bearer token, so a reply that
 * told those cases apart from a namespace that does not exist would tell one tenant which
 * namespaces the others have. An id the served graph does not hold is a 404.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; each request holds its own lease and reading.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphEntitiesEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphEntitiesEndpoint.class);

    /** The collection this endpoint serves, and every route under it. */
    public static final String PATH = "/v1/graph/entities";

    /** Items per page when the request names no limit. */
    static final int DEFAULT_LIMIT = 50;

    private static final String EDGES = "edges";

    private final GraphTenants tenants;

    /**
     * Creates the endpoint.
     *
     * @param tenants The namespaces whose served graphs are read
     */
    public GraphEntitiesEndpoint(GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed; use GET");
            return;
        }
        Request request;
        NamespaceId namespace;
        try {
            request = Request.parse(exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery());
            namespace = GraphTenants.namespaceOf(
                exchange.getRequestHeaders().getFirst(GraphTenants.NAMESPACE_HEADER));
        } catch (NotARoute e) {
            sendError(exchange, 404, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        }

        Optional<NamespaceRegistry.Lease<GraphTenant>> borrowed;
        try {
            borrowed = tenants.borrowIfPresent(namespace);
        } catch (RuntimeException e) {
            LOGGER.error("graph entity read failed for namespace {}", namespace, e);
            sendError(exchange, 500, "Internal Server Error");
            return;
        }
        if (borrowed.isEmpty()) {
            sendNoGraph(exchange);
            return;
        }

        int status;
        String body;
        try (NamespaceRegistry.Lease<GraphTenant> lease = borrowed.get()) {
            Optional<GraphRagHolder.Reading> reading = lease.resource().holder().acquire();
            if (reading.isEmpty()) {
                sendNoGraph(exchange);
                return;
            }
            // Released before the response is written, as the query releases its reading: a
            // slow client must not keep a replaced graph mapped.
            try (GraphRagHolder.Reading held = reading.get()) {
                Optional<String> found = request.answer(held.catalog(), namespace);
                status = found.isPresent() ? 200 : 404;
                body = found.orElseGet(() -> errorJson("no entity " + request.entityId()
                    + " in the graph this namespace serves"));
            }
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        } catch (RuntimeException e) {
            LOGGER.error("graph entity read failed for namespace {}", namespace, e);
            sendError(exchange, 500, "Internal Server Error");
            return;
        }
        ResponseBody.sendJson(exchange, status, body);
    }

    /**
     * What a request asked for, read from its path and query before any graph is touched.
     *
     * @param entityId  The entity the path names, or -1 for the collection
     * @param edges     Whether the path is the entity's edges
     * @param query     The raw query string
     */
    private record Request(long entityId, boolean edges, String query) {

        static Request parse(String path, String query) {
            String rest = path.length() > PATH.length() ? path.substring(PATH.length()) : "";
            if (rest.isEmpty() || "/".equals(rest)) {
                return new Request(-1, false, query);
            }
            String[] segments = rest.substring(1).split("/", -1);
            if (segments.length > 2 || (segments.length == 2 && !EDGES.equals(segments[1]))) {
                throw new NotARoute("no such route; expected " + PATH + ", " + PATH
                    + "/{id} or " + PATH + "/{id}/edges");
            }
            return new Request(entityId(segments[0]), segments.length == 2, query);
        }

        /**
         * Reads the request against one served graph.
         *
         * @return The JSON body, or empty when the graph holds no entity by the id asked about
         */
        Optional<String> answer(GraphCatalog catalog, NamespaceId namespace) {
            if (entityId < 0) {
                return Optional.of(listing(catalog, namespace));
            }
            if (!edges) {
                return catalog.entity(entityId).map(entity ->
                    "{\"namespace\":" + GraphCatalogJson.quoted(namespace.value())
                        + ",\"entity\":" + GraphCatalogJson.entity(entity) + "}");
            }
            EdgeDirection direction = param("direction") == null
                ? EdgeDirection.BOTH
                : EdgeDirection.parse(param("direction"));
            String asOfText = param("as_of");
            TemporalFilter asOf = asOfText == null
                ? TemporalFilter.ALL
                : GraphQueryEndpoint.temporalFilterFrom(asOfText);
            String relation = param("relation");
            return catalog.edges(entityId, relation, direction, asOf, limit(), param("cursor"))
                .map(page -> "{\"namespace\":" + GraphCatalogJson.quoted(namespace.value())
                    + ",\"entity_id\":" + entityId
                    + ",\"direction\":" + GraphCatalogJson.quoted(direction.wireName())
                    + ",\"edges\":" + array(page, GraphCatalogJson::edge)
                    + ",\"next_cursor\":" + cursor(page) + "}");
        }

        private String listing(GraphCatalog catalog, NamespaceId namespace) {
            String name = param("name");
            String prefix = param("prefix");
            if (name != null && prefix != null) {
                throw new IllegalArgumentException(
                    "give 'name' or 'prefix', not both: one is an exact match and the other is "
                        + "not");
            }
            CatalogPage<CatalogEntity> page = name != null
                ? catalog.entitiesNamed(name, limit(), param("cursor"))
                : prefix != null
                    ? catalog.entitiesStartingWith(prefix, limit(), param("cursor"))
                    : catalog.allEntities(limit(), param("cursor"));
            return "{\"namespace\":" + GraphCatalogJson.quoted(namespace.value())
                + ",\"entities\":" + array(page, GraphCatalogJson::entity)
                + ",\"next_cursor\":" + cursor(page) + "}";
        }

        private String param(String name) {
            String value = QueryParams.value(query, name);
            return value == null || value.isEmpty() ? null : value;
        }

        private int limit() {
            String text = param("limit");
            if (text == null) {
                return DEFAULT_LIMIT;
            }
            try {
                int limit = Integer.parseInt(text.trim());
                if (limit >= 1 && limit <= GraphCatalog.MAX_LIMIT) {
                    return limit;
                }
            } catch (NumberFormatException notANumber) {
                // Answered below, with the range.
            }
            throw new IllegalArgumentException("'limit' must be a whole number from 1 to "
                + GraphCatalog.MAX_LIMIT + ", got \"" + text + "\"");
        }

        private static long entityId(String segment) {
            try {
                long id = Long.parseLong(segment);
                if (id >= 0) {
                    return id;
                }
            } catch (NumberFormatException notANumber) {
                // Answered below.
            }
            throw new IllegalArgumentException("an entity id is a non-negative whole number, "
                + "got \"" + segment + "\"; find one with " + PATH + "?name=");
        }
    }

    private static <T> String array(CatalogPage<T> page, Function<T, String> render) {
        List<T> items = page.items();
        StringBuilder json = new StringBuilder(items.size() * 128 + 2).append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(render.apply(items.get(i)));
        }
        return json.append(']').toString();
    }

    private static String cursor(CatalogPage<?> page) {
        return page.nextCursor() == null ? "null" : GraphCatalogJson.quoted(page.nextCursor());
    }

    /** A path under the collection that names no route. */
    private static final class NotARoute extends IllegalArgumentException {
        NotARoute(String message) {
            super(message);
        }
    }

    private static String errorJson(String message) {
        return "{\"error\":" + GraphCatalogJson.quoted(message) + "}";
    }

    private static void sendError(HttpExchange exchange, int status, String message)
            throws IOException {
        ResponseBody.sendJson(exchange, status, errorJson(message));
    }

    /**
     * Says there is no graph to read, in the query's words.
     *
     * @param exchange The exchange
     * @throws IOException if the response cannot be written
     */
    private static void sendNoGraph(HttpExchange exchange) throws IOException {
        sendError(exchange, 503, GraphQueryEndpoint.NO_GRAPH);
    }
}
