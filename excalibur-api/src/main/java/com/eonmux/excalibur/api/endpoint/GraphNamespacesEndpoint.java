package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.eonmux.excalibur.api.bootstrap.GraphFreshness;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.StringJoiner;

/**
 * {@code GET /v1/graph/namespaces} - every namespace this node holds on disk.
 *
 * <p>A namespace is created by naming it in the {@value GraphTenants#NAMESPACE_HEADER} header on
 * a graph request, and this route is how an operator finds out what that has created. Which
 * graphs are resident is a cache's contents, not what is on disk: a tenant offboarded from the
 * product leaves its graph, its staged facts and its erasure log here until they are deleted, and
 * this listing is what makes them discoverable.</p>
 *
 * <h3>Response</h3>
 * <pre>{@code
 * {
 *   "namespaces": [
 *     {"namespace": "acme", "open": false, "built": true,
 *      "staged_facts": 1204, "outstanding_erasures": 2,
 *      "behind_staging": true, "staged_changes_since_ms": 1736899500000, "error": null}
 *   ],
 *   "count": 1,
 *   "open": 0
 * }
 * }</pre>
 *
 * <h3>Whether the graph is current</h3>
 * <p>{@code behind_staging} is true when the namespace has staged a change - a new document's
 * facts, or a replaced or removed one's withdrawal - that the graph it serves does not have yet,
 * and {@code staged_changes_since_ms} is when the oldest such change was made, or {@code null}
 * when that is not known. The node's automatic build publishes it; {@code GET
 * /v1/graph/build/status} says when.</p>
 *
 * <h3>What it costs</h3>
 * <p>No graph is opened. A resident namespace answers from the tenant already in memory; one
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

        try {
            List<GraphTenants.NamespaceSummary> summaries = tenants.describeNamespaces();
            ResponseBody.sendJson(exchange, 200, body(summaries));
        } catch (IOException | RuntimeException e) {
            // Logged with the path the node failed on, answered without it: the graph root is
            // this deployment's filesystem layout and no caller's business.
            LOGGER.error("Could not enumerate the graph namespaces", e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
        }
    }

    /**
     * Renders the listing.
     *
     * @param summaries One per namespace, in identifier order
     * @return The response body
     */
    private static String body(List<GraphTenants.NamespaceSummary> summaries) {
        StringJoiner entries = new StringJoiner(", ", "[", "]");
        int open = 0;
        for (GraphTenants.NamespaceSummary summary : summaries) {
            entries.add(entry(summary));
            open += summary.open() ? 1 : 0;
        }
        return "{"
            + "\"namespaces\": " + entries + ", "
            + "\"count\": " + summaries.size() + ", "
            // Beside the total rather than instead of it: the difference between the two is how
            // much of this node's inventory is costing it mappings right now.
            + "\"open\": " + open
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
            + "\"behind_staging\": "
            + summary.freshness().map(f -> Boolean.toString(f.behind())).orElse("null") + ", "
            + "\"staged_changes_since_ms\": " + pendingSince(summary.freshness()) + ", "
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
     * Renders when the oldest change the served graph does not have was staged.
     *
     * @param freshness Whether the graph is behind its staging, or empty when unknown
     * @return Epoch milliseconds, or {@code null} when current, unknown or undated
     */
    private static String pendingSince(Optional<GraphFreshness> freshness) {
        return freshness.filter(GraphFreshness::behind)
            .flatMap(GraphFreshness::pendingSince)
            .map(since -> Long.toString(since.toEpochMilli()))
            .orElse("null");
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
