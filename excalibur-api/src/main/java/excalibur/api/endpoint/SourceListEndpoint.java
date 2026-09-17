package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.QueryParams;
import excalibur.api.protocol.ResponseBody;
import excalibur.ingest.registry.SourceCatalog;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceDescriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code GET /v1/ingest} - what this node has been asked to crawl, and whether it can.
 *
 * <h2>Why this exists</h2>
 * <p>A registration is written down in full - its type, its id and every setting the request
 * carried - and until now that record was read back at exactly one moment: startup. After a
 * restart nobody could ask what {@code my-docs} pointed at, what it filtered, or how deep it
 * went. {@code GET /v1/ingest/status} answers a different question - how a run went - and
 * carries none of it.</p>
 *
 * <p>The second answer here is the one nothing else gives at all. A registration whose target
 * the allow-list no longer permits is kept on file and skipped at startup, deliberately, so the
 * operator has something to correct rather than a deletion to discover. But the node then
 * crawls it never again, and reports nothing anywhere: no status entry, no error, no absence
 * that looks like one. {@code "registered": false} is that fact.</p>
 *
 * <h2>Response</h2>
 * <p>The two shapes {@code /v1/ingest/status} uses, for the same reasons. With
 * {@code ?source_id=...}, one object, and 404 when nothing is registered under it. Without it,
 * {@code {"sources": [...]}}, so the response is one shape whether none or many are
 * registered.</p>
 *
 * <pre>{@code
 * {
 *   "source_id": "my-docs",
 *   "source_type": "filesystem",
 *   "registered": true,
 *   "config": {"max_depth": "4", "root_path": "/srv/docs"}
 * }
 * }</pre>
 *
 * <p>What the {@code config} object may carry, and why it is an allow-list rather than an echo,
 * is {@link SourceConfigView}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the registry and the catalog it reads are both thread-safe.</p>
 */
public class SourceListEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceListEndpoint.class);

    private final SourceRegistry sourceRegistry;

    /** The recorded registrations, or null on a node that keeps none between restarts. */
    private final SourceCatalog sourceCatalog;

    /**
     * Creates the endpoint.
     *
     * @param sourceRegistry The live sources, which is what says whether one is being crawled
     * @param sourceCatalog  The recorded registrations, or null when this node keeps none
     * @throws NullPointerException if the registry is null
     */
    public SourceListEndpoint(SourceRegistry sourceRegistry, SourceCatalog sourceCatalog) {
        this.sourceRegistry = Objects.requireNonNull(sourceRegistry,
            "sourceRegistry cannot be null");
        this.sourceCatalog = sourceCatalog;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405,
                "{\"error\": \"Method not allowed; use GET to list registered sources\"}");
            return;
        }

        try {
            // The raw query, so a source id carrying an encoded delimiter is not split on its
            // own decoded text - see QueryParams.
            String sourceId = QueryParams.value(
                exchange.getRequestURI().getRawQuery(), "source_id");
            if (sourceId == null) {
                sendAll(exchange);
                return;
            }
            String violation = SourceIds.violation(sourceId, "source_id");
            if (violation != null) {
                ResponseBody.sendJson(exchange, 400,
                    "{\"error\": \"" + JsonText.escape(violation) + "\"}");
                return;
            }
            sendOne(exchange, sourceId);
        } catch (RuntimeException e) {
            LOGGER.error("Error listing registered sources: {}", e.getMessage(), e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
        }
    }

    /** Answers with every source this node knows about, recorded or live. */
    private void sendAll(HttpExchange exchange) throws IOException {
        StringBuilder body = new StringBuilder(256).append("{\"sources\": [");
        boolean first = true;
        for (Entry entry : entries().values()) {
            if (!first) {
                body.append(", ");
            }
            first = false;
            body.append(toJson(entry));
        }
        ResponseBody.sendJson(exchange, 200, body.append("]}").toString());
    }

    /** Answers with one source, or 404 when nothing is registered under that id. */
    private void sendOne(HttpExchange exchange, String sourceId) throws IOException {
        Entry entry = entries().get(sourceId);
        if (entry == null) {
            ResponseBody.sendJson(exchange, 404,
                "{\"error\": \"Source not registered: " + JsonText.escape(sourceId) + "\"}");
            return;
        }
        ResponseBody.sendJson(exchange, 200, toJson(entry));
    }

    /**
     * Gathers what the node knows about each source, from both places it could know it.
     *
     * <p>The catalog is the authority on what a source was registered as, because that is what
     * it holds; the registry is the authority on whether the node is crawling it, because that
     * is what it holds. Neither on its own answers the question, and the interesting cases are
     * exactly where they disagree: a record with no live source is a registration the node
     * could not rebuild, and a live source with no record is a node running without a
     * catalog.</p>
     *
     * @return One entry per source, keyed and ordered by id
     */
    private Map<String, Entry> entries() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (sourceCatalog != null) {
            List<SourceDescriptor> recorded = new ArrayList<>(sourceCatalog.all());
            recorded.sort((left, right) -> left.sourceId().compareTo(right.sourceId()));
            for (SourceDescriptor descriptor : recorded) {
                entries.put(descriptor.sourceId(), new Entry(descriptor.sourceId(),
                    descriptor.sourceType(),
                    sourceRegistry.isRegistered(descriptor.sourceId()),
                    descriptor.namespace().value(),
                    descriptor.config()));
            }
        }

        List<String> live = new ArrayList<>(sourceRegistry.getAllSourceIds());
        live.sort(String::compareTo);
        for (String sourceId : live) {
            if (entries.containsKey(sourceId)) {
                continue;
            }
            // Registered in this process and recorded nowhere: either this node keeps no
            // catalog, or the source was registered through a path that does not write one.
            // Its type comes off the live crawler, which is the only thing that knows it.
            Source source = sourceRegistry.get(sourceId);
            entries.put(sourceId, new Entry(sourceId,
                source != null ? source.getSourceType() : "unknown", true,
                sourceRegistry.namespaceOf(sourceId)
                    .orElse(io.excalibur.tenancy.NamespaceId.SHARED).value(),
                Map.of()));
        }
        return entries;
    }

    /**
     * Renders one source.
     *
     * @param entry What is known about it
     * @return The JSON object
     */
    private static String toJson(Entry entry) {
        return "{"
            + "\"source_id\": \"" + JsonText.escape(entry.sourceId()) + "\", "
            + "\"source_type\": \"" + JsonText.escape(entry.sourceType()) + "\", "
            + "\"registered\": " + entry.registered() + ", "
            // Always present, never omitted for the shared namespace: a caller reading back
            // which tenant a crawl feeds should not have to know that a missing field means
            // "shared", and a single-tenant node reads the same as a multi-tenant one.
            + "\"namespace\": \"" + JsonText.escape(entry.namespace()) + "\", "
            + SourceConfigView.toJson(entry.config())
            + "}";
    }

    /**
     * One source, as the two halves of the node see it between them.
     *
     * @param sourceId   The id it is addressed by
     * @param sourceType Which crawler it names
     * @param registered Whether this node currently holds a crawler for it; false means the
     *                   registration is on file and is not being crawled
     * @param namespace  The graph namespace its documents' facts are staged into
     * @param config     What the registration supplied, before this endpoint decides how much
     *                   of it to quote
     */
    private record Entry(String sourceId, String sourceType, boolean registered,
                         String namespace, Map<String, String> config) {
    }
}
