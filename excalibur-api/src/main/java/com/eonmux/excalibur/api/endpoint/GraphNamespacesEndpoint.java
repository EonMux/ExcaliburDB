package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.QueryParams;
import com.eonmux.excalibur.api.protocol.ResponseBody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.StringJoiner;

/**
 * {@code GET /v1/graph/namespaces} - the namespaces this node holds on disk, a page at a time.
 *
 * <p>A namespace is created by naming it in the {@value GraphTenants#NAMESPACE_HEADER} header on
 * a graph request, or by {@code PUT /v1/graph/namespaces/{namespace}}, and this route is how an
 * operator finds out what that has created. Which
 * graphs are resident is a cache's contents, not what is on disk: a tenant offboarded from the
 * product leaves its graph, its staged facts and its erasure log here until they are deleted, and
 * this listing is what makes them discoverable.</p>
 *
 * <h3>Paging</h3>
 * <p>{@code limit} bounds the page, {@value #DEFAULT_LIMIT} by default and at most
 * {@value #MAX_LIMIT}; a larger one is refused rather than trimmed, so a caller paging by the
 * limit is not silently handed less than it asked for. {@code cursor} continues after the
 * {@code next_cursor} of the previous page, which is null on the last one. Namespaces are listed
 * in identifier order and a page starts strictly after its cursor, so one dropped between two
 * requests does not end the walk and one created behind the cursor is not shown twice.</p>
 *
 * <h3>Response</h3>
 * <pre>{@code
 * {
 *   "namespaces": [
 *     {"namespace": "acme", "open": false, "built": true,
 *      "staged_facts": 1204, "outstanding_erasures": 2, "error": null}
 *   ],
 *   "count": 1,
 *   "total": 1,
 *   "open": 0,
 *   "next_cursor": null
 * }
 * }</pre>
 *
 * <p>{@code count} is this page's length and {@code total} the node's whole inventory, the shared
 * namespace included; {@code open} is how many graphs the node holds open right now.</p>
 *
 * <h3>What it costs</h3>
 * <p>No graph is opened, and only the page is described: the rest of the inventory costs a
 * directory listing. A resident namespace answers from the tenant already in memory; one
 * that is not resident is measured from two files - a {@code stat} of the staging log, whose
 * fixed-width records make its length the fact count, and a read of the erasure log, which holds
 * one small record per accepted obligation. That matters because the alternative would have been
 * the most expensive call on the node: reopening every tenant is dozens of memory mappings each,
 * and it would evict the working set in order to produce a report about it.</p>
 *
 * <h3>Unknown is not zero</h3>
 * <p>A count the node could not read is {@code null}, with {@code error} saying why. Reporting
 * zero outstanding erasures for a namespace whose log is unreadable is the one wrong answer that
 * looks like an invitation - an operator reading it would offboard a tenant the node still owes
 * deletions for.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the registry it reads is safe for concurrent use.</p>
 *
 * @author ExcaliburDB
 */
public class GraphNamespacesEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphNamespacesEndpoint.class);

    /** Namespaces a page carries when the request names no limit. */
    public static final int DEFAULT_LIMIT = 100;

    /** Most namespaces one page may carry. */
    public static final int MAX_LIMIT = 1000;

    private final GraphTenants tenants;

    /**
     * Creates the endpoint.
     *
     * @param tenants The namespaces to report on
     */
    public GraphNamespacesEndpoint(GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405,
                "{\"error\": \"Method Not Allowed - Use GET to list namespaces\"}");
            return;
        }

        String rawQuery = exchange.getRequestURI().getRawQuery();
        int limit;
        String cursor;
        try {
            limit = limit(QueryParams.value(rawQuery, "limit"));
            cursor = QueryParams.value(rawQuery, "cursor");
        } catch (IllegalArgumentException e) {
            ResponseBody.sendError(exchange, 400, e.getMessage());
            return;
        }

        try {
            GraphTenants.NamespacePage page = tenants.describeNamespaces(cursor, limit);
            ResponseBody.sendJson(exchange, 200, body(page, tenants.openCount()));
        } catch (IllegalArgumentException e) {
            // The cursor, which only the registry can tell is not a namespace name.
            ResponseBody.sendError(exchange, 400, e.getMessage());
        } catch (IOException | RuntimeException e) {
            // Logged with the path the node failed on, answered without it: the graph root is
            // this deployment's filesystem layout and no caller's business.
            LOGGER.error("Could not enumerate the graph namespaces", e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
        }
    }

    /**
     * Reads the page size.
     *
     * @param raw What the request carried, or null
     * @return The limit to use
     * @throws IllegalArgumentException if it is present and not a whole number in range
     */
    static int limit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be a whole number between 1 and "
                + MAX_LIMIT);
        }
        if (value < 1 || value > MAX_LIMIT) {
            throw new IllegalArgumentException(
                "limit must be between 1 and " + MAX_LIMIT + ", got: " + value);
        }
        return value;
    }

    /**
     * Renders one page of the listing.
     *
     * @param page One summary per namespace on the page, in identifier order
     * @param open How many graphs the node holds open
     * @return The response body
     */
    private static String body(GraphTenants.NamespacePage page, int open) {
        StringJoiner entries = new StringJoiner(", ", "[", "]");
        for (GraphTenants.NamespaceSummary summary : page.namespaces()) {
            entries.add(entry(summary));
        }
        return "{"
            + "\"namespaces\": " + entries + ", "
            + "\"count\": " + page.namespaces().size() + ", "
            + "\"total\": " + page.total() + ", "
            // Beside the total rather than instead of it: the difference between the two is how
            // much of this node's inventory is costing it mappings right now.
            + "\"open\": " + open + ", "
            // Null rather than a cursor on the last page: a caller stops when it is told to.
            + "\"next_cursor\": " + page.next()
                .map(id -> "\"" + JsonText.escape(id.value()) + "\"").orElse("null")
            + "}";
    }

    /**
     * Renders one namespace.
     *
     * @param summary What is known about it
     * @return The JSON object
     */
    private static String entry(GraphTenants.NamespaceSummary summary) {
        return "{"
            + "\"namespace\": \"" + JsonText.escape(summary.id().value()) + "\", "
            + "\"open\": " + summary.open() + ", "
            + "\"built\": " + summary.built() + ", "
            + "\"staged_facts\": " + unknownAsNull(summary.stagedFacts()) + ", "
            + "\"outstanding_erasures\": " + unknownAsNull(summary.outstandingErasures()) + ", "
            + "\"error\": " + quotedOrNull(summary.error())
            + "}";
    }

    /**
     * Renders a count the node could not produce as {@code null} rather than as a number.
     *
     * @param value The count, or empty
     * @return The JSON value
     */
    private static String unknownAsNull(OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) : "null";
    }

    /**
     * Renders a count the node could not produce as {@code null} rather than as a number.
     *
     * @param value The count, or empty
     * @return The JSON value
     */
    private static String unknownAsNull(OptionalInt value) {
        return value.isPresent() ? Integer.toString(value.getAsInt()) : "null";
    }

    /**
     * Renders an optional message.
     *
     * @param value The message, or null
     * @return The JSON value
     */
    private static String quotedOrNull(String value) {
        return value == null ? "null" : "\"" + JsonText.escape(value) + "\"";
    }
}
