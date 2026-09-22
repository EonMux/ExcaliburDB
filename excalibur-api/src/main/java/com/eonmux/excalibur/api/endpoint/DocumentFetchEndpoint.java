package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.RequestBody;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.api.retrieval.ChunkTextResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads back the text behind identifiers a search returned.
 *
 * <h3>Request</h3>
 * <pre>
 * GET  /v1/documents?document_id=docs%3Aguide.md%3Achunk%3A0
 * POST /v1/documents/fetch   {"document_ids": ["docs:guide.md:chunk:0", "docs:guide.md:chunk:1"]}
 * </pre>
 *
 * <p>The batch form has a path of its own because {@code POST /v1/documents} is how documents are
 * supplied - see {@link DocumentUpsertEndpoint} - and one method on one path cannot mean both
 * "here is content" and "send me content".</p>
 *
 * <h3>Why the identifier is not in the path</h3>
 * <p>A chunk identifier is assembled from a source id, a document path and an ordinal, so it
 * routinely contains both colons and slashes. Putting it in the path would make the route's own
 * shape depend on what a document happens to be called, and a caller would have to know which of
 * the slashes in the identifier it had to escape. A query parameter and a JSON array both carry
 * the identifier exactly as the search handed it over.</p>
 *
 * <h3>Why POST fetches</h3>
 * <p>A search answers with {@code top_k} identifiers at once. Resolving them one at a time turns
 * one answer into {@code top_k} round trips, each with its own rate-limit charge, so the batch
 * form exists and is bounded by the same {@code top_k} ceiling the search itself is - a caller
 * can always fetch the text for a whole page of results and never more than one. Identifiers
 * must be distinct, which is what makes that a bound on the text returned and not merely on the
 * identifiers named.</p>
 *
 * <h3>What the response reports</h3>
 * <pre>
 * {"documents": [{"document_id": "...", "text": "...", "text_truncated": false}],
 *  "documents_not_found": 0}
 * </pre>
 *
 * <p>Every identifier asked for comes back, in the order it was asked for, whether or not the
 * node holds it: a caller that named three and received two cannot tell which was dropped.
 * {@code text} is null for one this node does not hold, and {@code documents_not_found} counts
 * them so the common case needs no scan of the list. Text past
 * {@code excalibur.api.max-result-text-chars} is clipped, and {@code text_truncated} says so -
 * a clipped passage that does not say so gets quoted as the whole one.</p>
 *
 * <h3>A miss is not a 404</h3>
 * <p>The request is for a set, and part of a set being absent does not make the request wrong.
 * A 404 would also have to mean something different on a batch that missed one identifier than
 * on one that missed all of them; the count means the same thing in both.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable, and the resolver it reads through is safe for concurrent use.</p>
 */
public final class DocumentFetchEndpoint implements HttpHandler {

    /** The query parameter naming a single document on a GET. */
    private static final String DOCUMENT_ID = "document_id";

    /** The JSON field naming documents on a POST. */
    private static final String DOCUMENT_IDS = "document_ids";

    private final ChunkTextResolver text;
    private final int maxDocuments;

    /**
     * Creates the endpoint.
     *
     * @param text         Reads a chunk's text by identifier
     * @param maxDocuments Most identifiers one request may name
     * @throws NullPointerException     if the resolver is null
     * @throws IllegalArgumentException if maxDocuments is not positive
     */
    public DocumentFetchEndpoint(ChunkTextResolver text, int maxDocuments) {
        this.text = Objects.requireNonNull(text, "text must not be null");
        if (maxDocuments <= 0) {
            throw new IllegalArgumentException("maxDocuments must be positive: " + maxDocuments);
        }
        this.maxDocuments = maxDocuments;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        List<String> ids;
        try {
            if ("GET".equalsIgnoreCase(method)) {
                ids = List.of(requireQueryId(exchange));
            } else if ("POST".equalsIgnoreCase(method)) {
                ids = requireBodyIds(exchange);
            } else {
                ResponseBody.sendJson(exchange, 405,
                    "{\"error\": \"Method not allowed; use GET /v1/documents with '" + DOCUMENT_ID
                        + "' or POST /v1/documents/fetch with '" + DOCUMENT_IDS + "'\"}");
                return;
            }
        } catch (RequestBody.TooLargeException e) {
            ResponseBody.sendJson(exchange, 413, "{\"error\": \"Request body too large\"}");
            return;
        } catch (IllegalArgumentException e) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(e.getMessage()) + "\"}");
            return;
        }

        ResponseBody.sendJson(exchange, 200, render(ids));
    }

    /**
     * Renders the documents asked for, held and not held alike.
     *
     * @param ids The identifiers, in the order the caller named them
     * @return The response body
     */
    private String render(List<String> ids) {
        StringBuilder sb = new StringBuilder(256).append("{\"documents\": [");
        int notFound = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String id = ids.get(i);
            ChunkTextResolver.Resolved resolved = text.resolve(id);
            if (resolved.text() == null) {
                notFound++;
            }
            sb.append("{\"document_id\": \"").append(JsonText.escape(id)).append("\", \"text\": ");
            if (resolved.text() == null) {
                sb.append("null");
            } else {
                sb.append('"').append(JsonText.escape(resolved.text())).append('"');
            }
            sb.append(", \"text_truncated\": ").append(resolved.truncated()).append('}');
        }
        return sb.append("], \"documents_not_found\": ").append(notFound).append('}').toString();
    }

    /**
     * Reads the single identifier a GET names.
     *
     * @param exchange The request
     * @return The identifier, decoded
     * @throws IllegalArgumentException if the request names none
     */
    private String requireQueryId(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        String id = query == null ? null : parameter(query);
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException(
                "Query parameter '" + DOCUMENT_ID + "' is required");
        }
        return id;
    }

    /**
     * Finds and decodes {@code document_id} in a raw query string.
     *
     * <p>Decoded here rather than through {@code URI.getQuery()} because that decodes the whole
     * string before it is split, so an identifier containing an escaped {@code &} would be read
     * as the start of another parameter.</p>
     *
     * @param rawQuery The undecoded query string
     * @return The value, or null when the parameter is absent
     */
    private static String parameter(String rawQuery) {
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0 || !DOCUMENT_ID.equals(pair.substring(0, eq))) {
                continue;
            }
            try {
                // URLDecoder is form decoding, which maps '+' to a space. A chunk id is built
                // from a document path, and '+' is an ordinary character in one, so a document
                // called c++notes.md would be looked up under "c  notes.md" and reported as a
                // chunk this node does not hold - indistinguishable from an erased one.
                return percentDecode(pair.substring(eq + 1));
            } catch (IllegalArgumentException e) {
                // A percent escape the caller got wrong is a bad request, not a server fault.
                throw new IllegalArgumentException(
                    "Query parameter '" + DOCUMENT_ID + "' is not valid percent-encoding");
            }
        }
        return null;
    }

    /**
     * Decodes percent escapes, and nothing else.
     *
     * @param value The raw parameter value
     * @return The decoded value
     * @throws IllegalArgumentException if an escape is truncated or is not hexadecimal
     */
    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '%') {
                // Encoded as UTF-8, so a literal non-ASCII character survives unchanged.
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                continue;
            }
            if (i + 2 >= value.length()) {
                throw new IllegalArgumentException("Truncated percent escape");
            }
            int high = Character.digit(value.charAt(i + 1), 16);
            int low = Character.digit(value.charAt(i + 2), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Percent escape is not hexadecimal");
            }
            bytes.write((high << 4) + low);
            i += 2;
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * Reads the identifiers a POST names.
     *
     * @param exchange The request
     * @return The identifiers, in the order given
     * @throws IllegalArgumentException if the body names none or names too many
     */
    private List<String> requireBodyIds(HttpExchange exchange) throws IOException {
        String body = RequestBody.read(exchange);
        String[] named = JsonText.stringArray(body, DOCUMENT_IDS);
        if (named == null || named.length == 0) {
            throw new IllegalArgumentException(
                "Field '" + DOCUMENT_IDS + "' is required and must name at least one document");
        }
        // Bounded because each identifier is a storage read, and the texts share one response
        // body. A caller with more than a page of results has a loop, not a request.
        if (named.length > maxDocuments) {
            throw new IllegalArgumentException("Field '" + DOCUMENT_IDS + "' must name at most "
                + maxDocuments + " documents: " + named.length);
        }
        List<String> ids = new ArrayList<>(named.length);
        java.util.Set<String> seen = new java.util.HashSet<>(named.length);
        for (String id : named) {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException(
                    "Field '" + DOCUMENT_IDS + "' must not contain an empty identifier");
            }
            // Refused rather than answered twice. Without this the bound is on how many
            // identifiers are named and not on how much text comes back, because one chunk can
            // be named every time - and a caller that asked for the same id twice has a bug it
            // is better off being told about.
            if (!seen.add(id)) {
                throw new IllegalArgumentException(
                    "Field '" + DOCUMENT_IDS + "' names the same document twice: " + id);
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }
}
