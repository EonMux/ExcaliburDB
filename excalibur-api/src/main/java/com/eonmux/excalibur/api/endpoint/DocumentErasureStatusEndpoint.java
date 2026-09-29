package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.registry.ErasureReceiptFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code GET /v1/erasure/{request_id}} - where one document erasure stands.
 *
 * <p>{@code POST /v1/erasure} takes documents out of storage and out of the index before it
 * answers, and leaves two things to finish later: the graph obligations it submitted for what was
 * extracted from those documents, and the lexical index's reclamation of the removed chunks from
 * its segment files. It answers 202 when either is still owed, with a {@code request_id}. This is
 * how the caller learns they have finished.</p>
 *
 * <h3>Response</h3>
 * <pre>
 * {"request_id": "5f0c...", "state": "pending", "requested_at_ms": 1736899200000,
 *  "discharged_at_ms": null, "source_id": "docs", "scope": "document",
 *  "documents_erased": 2, "documents_incomplete": 0, "documents_not_found": 0,
 *  "documents_remaining": 0, "source_deregistered": false,
 *  "remaining": {"graph_requests_pending": 2, "lexical_reclamation_pending": true,
 *                "documents_incomplete": 0},
 *  "graph": [{"namespace": "shared", "requests": 2, "pending": 2, "discharged": 0,
 *             "namespace_dropped": false}]}
 * </pre>
 * <ul>
 *   <li>{@code incomplete} - some documents could not be fully removed; the request failed for
 *       them and has to be repeated. Final for this request: the receipt names no documents, so
 *       it cannot see a repeat finish them, and the repeat reports under its own id. The other
 *       fields still say what the rest did.</li>
 *   <li>{@code pending} - the graph still owes some of the obligations this erasure
 *       submitted.</li>
 *   <li>{@code applied} - nothing is searchable or served any more, and the lexical index has
 *       still to reclaim the removed chunks from its segment files.</li>
 *   <li>{@code discharged} - every part this node tracks is done.</li>
 * </ul>
 *
 * <h3>How each half is read</h3>
 * <p>The graph half is read from each namespace's erasure log, the same answer
 * {@code GET /v1/graph/erasure/{request_id}} gives for each obligation. A namespace dropped since
 * holds nothing at all, so its obligations count as discharged, and the entry says the namespace
 * is gone.</p>
 *
 * <p>The lexical half is read conservatively. The index records what it owes by the time each
 * obligation was accepted, not by which request asked, so the reclamation counts as done once the
 * index owes nothing accepted at or before this erasure was answered. An older obligation still
 * outstanding keeps this one reported as owed; nothing makes it report done early.</p>
 *
 * <p>{@code discharged_at_ms} is when the last graph obligation was discharged, or when the
 * request answered if nothing was owed. It is null when the log no longer retains a completion
 * time, or when a lexical reclamation was owed, whose completion time the index does not record.
 * The vector indexes are reclaimed per segment and are not tracked here, as the POST reply says.</p>
 *
 * <h3>Retention and privacy</h3>
 * <p>Receipts are kept durably, the {@value ErasureReceiptFile#RETAINED_RECEIPTS} most recent of
 * them; an older one answers 404. A receipt holds counts and graph request ids and never the
 * document ids, so this response cannot name what was erased.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; what it reads is thread-safe.</p>
 */
public class DocumentErasureStatusEndpoint implements HttpHandler {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(DocumentErasureStatusEndpoint.class);

    /** The collection whose members this serves. */
    static final String COLLECTION_PATH = "/v1/erasure";

    private static final Pattern RECEIPT_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final ErasureReceiptFile receipts;
    private final DocumentErasureService erasureService;
    private final GraphTenants graphTenants;

    /**
     * Creates the endpoint.
     *
     * @param receipts       Where erasures left their receipts
     * @param erasureService Asked what the lexical index still owes
     * @param graphTenants   The namespaces whose erasure logs hold the graph half, or null on a
     *                       node that serves no graph
     * @throws NullPointerException if the receipts or the service is null
     */
    public DocumentErasureStatusEndpoint(ErasureReceiptFile receipts,
                                         DocumentErasureService erasureService,
                                         GraphTenants graphTenants) {
        this.receipts = Objects.requireNonNull(receipts, "receipts cannot be null");
        this.erasureService = Objects.requireNonNull(erasureService,
            "erasureService cannot be null");
        this.graphTenants = graphTenants;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405, "{\"error\": \"Method Not Allowed - Use GET "
                + COLLECTION_PATH + "/{request_id}\"}");
            return;
        }
        String id = receiptIdOf(exchange.getRequestURI().getPath());
        if (id == null) {
            ResponseBody.sendJson(exchange, 400, "{\"error\": \"the path must end in the "
                + "request_id POST " + COLLECTION_PATH + " answered with: GET " + COLLECTION_PATH
                + "/{request_id}\"}");
            return;
        }
        Optional<ErasureReceiptFile.Receipt> found = receipts.find(id);
        if (found.isEmpty()) {
            ResponseBody.sendError(exchange, 404, ErrorCode.ERASURE_NOT_FOUND,
                "No erasure request " + id + " is known to this node; receipts are kept for "
                    + "the " + ErasureReceiptFile.RETAINED_RECEIPTS + " most recent erasures");
            return;
        }
        String json;
        try {
            json = render(found.get());
        } catch (IOException | RuntimeException e) {
            // Logged with detail, answered without: the message names an erasure log's path.
            LOGGER.error("could not read where erasure request {} stands", id, e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
            return;
        }
        ResponseBody.sendJson(exchange, 200, json);
    }

    /**
     * What one namespace's share of the graph half has come to.
     *
     * @param namespace   The namespace
     * @param requests    Obligations submitted to it
     * @param pending     Of those, still owed
     * @param dropped     Whether the namespace no longer exists
     * @param lastDone    The latest completion time among them, when every one is known
     */
    private record GraphShare(String namespace, int requests, int pending, boolean dropped,
                              Optional<Instant> lastDone) {
    }

    private String render(ErasureReceiptFile.Receipt receipt) throws IOException {
        StringBuilder graph = new StringBuilder("[");
        int graphPending = 0;
        boolean completionTimesKnown = true;
        long lastDoneMs = receipt.recordedAtMs();
        for (ErasureReceiptFile.GraphRequests requests : receipt.graph()) {
            GraphShare share = shareOf(requests);
            graphPending += share.pending();
            if (share.lastDone().isPresent()) {
                lastDoneMs = Math.max(lastDoneMs, share.lastDone().get().toEpochMilli());
            } else {
                completionTimesKnown = false;
            }
            if (graph.length() > 1) {
                graph.append(", ");
            }
            graph.append("{\"namespace\": \"").append(JsonText.escape(share.namespace()))
                .append("\", \"requests\": ").append(share.requests())
                .append(", \"pending\": ").append(share.pending())
                .append(", \"discharged\": ").append(share.requests() - share.pending())
                .append(", \"namespace_dropped\": ").append(share.dropped()).append('}');
        }
        graph.append(']');

        boolean lexicalPending = receipt.lexicalOwed() && erasureService.oldestOutstandingErasure()
            .map(oldest -> oldest.toEpochMilli() <= receipt.recordedAtMs())
            .orElse(false);
        String state;
        if (receipt.documentsIncomplete() > 0) {
            state = "incomplete";
        } else if (graphPending > 0) {
            state = "pending";
        } else if (lexicalPending) {
            state = "applied";
        } else {
            state = "discharged";
        }
        String dischargedAt = "discharged".equals(state) && completionTimesKnown
            && !receipt.lexicalOwed() ? Long.toString(lastDoneMs) : "null";

        return "{"
            + "\"request_id\": \"" + receipt.id() + "\", "
            + "\"state\": \"" + state + "\", "
            + "\"requested_at_ms\": " + receipt.recordedAtMs() + ", "
            + "\"discharged_at_ms\": " + dischargedAt + ", "
            + "\"source_id\": \"" + JsonText.escape(receipt.sourceId()) + "\", "
            + "\"scope\": \"" + receipt.scope() + "\", "
            + "\"documents_erased\": " + receipt.documentsErased() + ", "
            + "\"documents_incomplete\": " + receipt.documentsIncomplete() + ", "
            + "\"documents_not_found\": " + receipt.documentsNotFound() + ", "
            + "\"documents_remaining\": " + receipt.documentsRemaining() + ", "
            + "\"source_deregistered\": " + receipt.sourceDeregistered() + ", "
            + "\"remaining\": {"
            + "\"graph_requests_pending\": " + graphPending + ", "
            + "\"lexical_reclamation_pending\": " + lexicalPending + ", "
            + "\"documents_incomplete\": " + receipt.documentsIncomplete() + "}, "
            + "\"graph\": " + graph
            + "}";
    }

    /**
     * Reads one namespace's obligations back from its erasure log.
     *
     * @param requests What the erasure submitted there
     * @return Where they stand
     * @throws IOException if the namespace's log exists and cannot be read
     */
    private GraphShare shareOf(ErasureReceiptFile.GraphRequests requests) throws IOException {
        Map<Long, ErasureLog.RequestStatus> statuses = graphTenants == null
            ? Map.of()
            : graphTenants.erasureStatuses(GraphTenants.namespaceOf(requests.namespace()),
                requests.requestIds());
        // No status for an id the namespace handed out means its log is gone, and a namespace's
        // log goes only with the namespace: everything it held, these facts included, went too.
        boolean dropped = statuses.isEmpty();
        int pending = 0;
        boolean timesKnown = !dropped;
        Instant last = null;
        for (long id : requests.requestIds()) {
            ErasureLog.RequestStatus status = statuses.get(id);
            if (status == null) {
                timesKnown = false;
            } else if (status.state() == ErasureLog.State.PENDING) {
                pending++;
            } else if (status.completedAt().isPresent()) {
                Instant done = status.completedAt().get();
                last = last == null || done.isAfter(last) ? done : last;
            } else {
                timesKnown = false;
            }
        }
        return new GraphShare(requests.namespace(), requests.requestIds().size(), pending,
            dropped, timesKnown ? Optional.ofNullable(last) : Optional.empty());
    }

    /**
     * The receipt id a member path names.
     *
     * @param path The decoded request path
     * @return The id, or null when the path names none this node could have issued
     */
    static String receiptIdOf(String path) {
        if (path == null || !path.startsWith(COLLECTION_PATH + "/")) {
            return null;
        }
        String segment = path.substring(COLLECTION_PATH.length() + 1);
        if (segment.endsWith("/")) {
            segment = segment.substring(0, segment.length() - 1);
        }
        return RECEIPT_ID.matcher(segment).matches() ? segment : null;
    }
}
