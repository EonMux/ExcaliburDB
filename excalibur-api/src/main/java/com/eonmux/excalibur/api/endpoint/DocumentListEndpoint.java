package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.QueryParams;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * {@code GET /v1/documents/list} - which documents this node holds.
 *
 * <h2>Why this exists</h2>
 * <p>The document registry is what says which documents the node has ingested and under which
 * chunk ids. It decides change detection on a re-crawl, it is what {@code POST /v1/erasure}
 * resolves a document to its chunks through, and it survives restarts. Nothing read it back.
 * A caller could fetch a chunk's text knowing the chunk id, and erase a document knowing the
 * document id, but no request produced either: the ids came out of a search result or out of
 * whatever crawled the source, and for a corpus ingested before a restart, out of nothing.</p>
 *
 * <p>"Which documents do you hold, and what are their chunks" is the question behind an
 * erasure request, a reconciliation against the source of truth, and any answer to "did the
 * crawl actually land". This is that question.</p>
 *
 * <h2>Request</h2>
 * <p>{@code source_id} narrows the listing to one source. {@code limit} bounds the page and
 * {@code cursor} continues the previous one - a cursor rather than an offset because the
 * registry is written to while it is read, and an offset into a set that is growing repeats
 * and skips documents between two requests that asked for adjacent pages. A limit above
 * {@link #MAX_LIMIT} is refused rather than quietly trimmed, so a caller paging by the limit
 * it asked for never silently skips the documents it did not receive.</p>
 *
 * <h2>Response</h2>
 * <pre>{@code
 * {
 *   "documents": [
 *     {
 *       "source_id": "my-docs",
 *       "document_id": "guide.md",
 *       "chunk_count": 2,
 *       "chunk_ids": ["my-docs:guide.md:0", "my-docs:guide.md:1"],
 *       "content_length": 8241,
 *       "content_hash": "913472233",
 *       "last_modified_ms": 1757808000000,
 *       "indexed_at_ms": 1757808123456
 *     }
 *   ],
 *   "total": 1,
 *   "returned": 1,
 *   "next_cursor": null
 * }
 * }</pre>
 *
 * <p>{@code total} counts the whole filtered set, not the page, so a caller knows how far it
 * has to walk before it starts. {@code next_cursor} is null on the last page, which is the
 * only termination condition a caller needs. {@code content_hash} is a string because
 * xxHash64 spans the whole positive {@code long} range, and a JSON parser backed by doubles
 * rounds anything past two to the fifty-third - which would turn a reconciliation into a
 * permanent re-ingest of every large-hashed document.</p>
 *
 * <h2>What bounds a request</h2>
 * <p>The registry is a hash table with no ordering, so producing an ordered page means walking
 * it; that cost is proportional to the corpus and is why {@code source_id} exists. What the
 * page itself costs is bounded rather than proportional: a page is selected through a heap of
 * at most {@code limit} entries, and {@link #MAX_CHUNK_IDS_PER_PAGE} caps the chunk ids it
 * renders. Counting a page in documents bounds nothing on its own - a document may hold
 * thousands of chunks - so a page ends at whichever of the two limits comes first, and the
 * cursor carries on from there.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the registry it reads is thread-safe. A page is a view of the registry as the
 * walk saw it rather than a snapshot, which is what the cursor is for: a document added behind
 * the cursor is not shown twice, and one added ahead of it is shown on a later page.</p>
 *
 * @author ExcaliburDB
 */
public class DocumentListEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentListEndpoint.class);

    /** Documents a page carries when the request names no limit. */
    public static final int DEFAULT_LIMIT = 100;

    /** Most documents one page may carry. */
    public static final int MAX_LIMIT = 1000;

    /**
     * Most chunk ids one page may render.
     *
     * <p>A page is counted in documents, and a document holds up to ten thousand chunks, so
     * the document limit on its own bounds the response at hundreds of megabytes. This is the
     * bound that actually holds; roughly a megabyte of ids.</p>
     */
    public static final int MAX_CHUNK_IDS_PER_PAGE = 20_000;

    /** How much of a refused parameter is quoted back, so a reply cannot be made enormous. */
    private static final int ECHO_LIMIT = 64;

    /** The order a page is walked in: the registry's own key, as two fields. */
    private static final Comparator<DocumentMetadata> ORDER =
        Comparator.comparing(DocumentMetadata::getSourceId)
            .thenComparing(DocumentMetadata::getDocumentId);

    /** Separates the two halves of a cursor. A source id cannot contain it. */
    private static final char CURSOR_SEPARATOR = ' ';

    private final DocumentRegistry documentRegistry;

    /**
     * Creates the endpoint.
     *
     * @param documentRegistry What the node holds, and the only thing that knows it
     * @throws NullPointerException if documentRegistry is null
     */
    public DocumentListEndpoint(DocumentRegistry documentRegistry) {
        this.documentRegistry = Objects.requireNonNull(documentRegistry,
            "documentRegistry cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405,
                "{\"error\": \"Method not allowed; use GET to list ingested documents\"}");
            return;
        }

        try {
            sendListing(exchange, exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException refused) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(refused.getMessage()) + "\"}");
        } catch (RuntimeException e) {
            LOGGER.error("Error listing ingested documents: {}", e.getMessage(), e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
        }
    }

    /**
     * Writes one page of the listing.
     *
     * @param exchange The exchange to answer on
     * @param rawQuery The undecoded query string, or null when there is none
     * @throws IOException              if the response cannot be written
     * @throws IllegalArgumentException if a parameter is outside what the endpoint accepts
     */
    private void sendListing(HttpExchange exchange, String rawQuery) throws IOException {
        String sourceId = sourceFilter(rawQuery);
        int limit = boundedInt(rawQuery, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        Cursor after = Cursor.decode(QueryParams.value(rawQuery, "cursor"));

        ResponseBody.sendJson(exchange, 200, render(walk(sourceId, after, limit)));
    }

    /**
     * Reads the source filter, treating a blank one as no filter at all.
     *
     * <p>{@code ?source_id=} with nothing after it is what a client templating an unset
     * variable sends. Filtering for the source whose id is the empty string answers that with
     * an empty listing, which reads as "this node holds nothing".</p>
     *
     * @param rawQuery The undecoded query string, or null
     * @return The source to narrow to, or null for every document
     * @throws IllegalArgumentException if the id is present and not a usable source id
     */
    private static String sourceFilter(String rawQuery) {
        String sourceId = QueryParams.value(rawQuery, "source_id");
        if (sourceId == null || sourceId.isBlank()) {
            return null;
        }
        String violation = SourceIds.violation(sourceId, "source_id");
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
        return sourceId;
    }

    /**
     * Walks the registry once, counting the filtered set and selecting the next page from it.
     *
     * <p>One pass and a heap of at most {@code limit} entries: the alternative - copying the
     * whole registry into a list and sorting it to serve a thousand entries - allocates a
     * reference per document in the corpus on every request.</p>
     *
     * @param sourceId The source to narrow to, or null for every document
     * @param after    Where the previous page ended, or null to start at the beginning
     * @param limit    Most documents this page may carry
     * @return The page, with the counts that describe the set it came from
     */
    private Page walk(String sourceId, Cursor after, int limit) {
        PriorityQueue<DocumentMetadata> selected = new PriorityQueue<>(ORDER.reversed());
        int total = 0;
        int remaining = 0;
        for (DocumentMetadata metadata : documentRegistry.getAll()) {
            if (sourceId != null && !sourceId.equals(metadata.getSourceId())) {
                continue;
            }
            total++;
            if (after != null && !after.precedes(metadata)) {
                continue;
            }
            remaining++;
            selected.offer(metadata);
            if (selected.size() > limit) {
                selected.poll();
            }
        }
        List<DocumentMetadata> page = new ArrayList<>(selected);
        page.sort(ORDER);
        return trimToChunkBudget(page, total, remaining);
    }

    /**
     * Cuts a page short of the chunk-id budget.
     *
     * <p>The first document is always kept, whatever it costs: a page that returned nothing
     * would not advance the cursor, and the walk would never end.</p>
     *
     * @param page      The documents selected for this page, in order
     * @param total     How many documents the filter matched in total
     * @param remaining How many of them lie after the cursor
     * @return The page as it will be rendered
     */
    private static Page trimToChunkBudget(List<DocumentMetadata> page, int total, int remaining) {
        int chunkIds = 0;
        int kept = 0;
        for (DocumentMetadata metadata : page) {
            int cost = metadata.getChunkCount();
            if (kept > 0 && chunkIds + cost > MAX_CHUNK_IDS_PER_PAGE) {
                break;
            }
            chunkIds += cost;
            kept++;
        }
        List<DocumentMetadata> trimmed = List.copyOf(page.subList(0, kept));
        return new Page(trimmed, total,
            kept < remaining && kept > 0 ? Cursor.of(trimmed.get(kept - 1)) : null);
    }

    /**
     * Renders a page.
     *
     * @param page What to write
     * @return The JSON body
     */
    private static String render(Page page) {
        StringBuilder body = new StringBuilder(256).append("{\"documents\": [");
        for (int i = 0; i < page.documents().size(); i++) {
            if (i > 0) {
                body.append(", ");
            }
            body.append(toJson(page.documents().get(i)));
        }
        return body.append("], \"total\": ").append(page.total())
            .append(", \"returned\": ").append(page.documents().size())
            // Null rather than a cursor: a caller stops when it is told to stop, and does not
            // have to compare anything against a total to work out that it has reached the end.
            .append(", \"next_cursor\": ")
            .append(page.nextCursor() == null ? "null" : "\"" + page.nextCursor().encode() + "\"")
            .append('}')
            .toString();
    }

    /**
     * Reads a whole-number parameter, refusing anything outside its range.
     *
     * @param rawQuery The undecoded query string, or null
     * @param name     The parameter name
     * @param fallback What an absent parameter means
     * @param minimum  Smallest accepted value
     * @param maximum  Largest accepted value
     * @return The value to use
     * @throws IllegalArgumentException if the parameter is present and unusable
     */
    private static int boundedInt(String rawQuery, String name, int fallback,
                                  int minimum, int maximum) {
        String raw = QueryParams.value(rawQuery, name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                name + " must be a whole number, got: " + echo(raw));
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                name + " must be between " + minimum + " and " + maximum + ", got: " + value);
        }
        return value;
    }

    /**
     * Quotes back enough of a refused value to identify it, and no more.
     *
     * <p>A request line runs to hundreds of kilobytes, and a reply that echoes one in full is
     * a reply the caller chose the size of - and a log line the same size.</p>
     *
     * @param value The value as it arrived
     * @return The value, clipped
     */
    private static String echo(String value) {
        return value.length() <= ECHO_LIMIT ? value : value.substring(0, ECHO_LIMIT) + "...";
    }

    /**
     * Renders one document.
     *
     * @param metadata What the registry holds about it
     * @return The JSON object
     */
    private static String toJson(DocumentMetadata metadata) {
        StringBuilder chunks = new StringBuilder(64).append('[');
        List<String> chunkIds = metadata.getChunkIds();
        for (int i = 0; i < chunkIds.size(); i++) {
            if (i > 0) {
                chunks.append(", ");
            }
            chunks.append('"').append(JsonText.escape(chunkIds.get(i))).append('"');
        }
        return "{"
            + "\"source_id\": \"" + JsonText.escape(metadata.getSourceId()) + "\", "
            + "\"document_id\": \"" + JsonText.escape(metadata.getDocumentId()) + "\", "
            + "\"chunk_count\": " + metadata.getChunkCount() + ", "
            + "\"chunk_ids\": " + chunks.append(']') + ", "
            + "\"content_length\": " + metadata.getContentLength() + ", "
            + "\"content_hash\": \"" + metadata.getContentHash() + "\", "
            + "\"last_modified_ms\": " + metadata.getLastModifiedMillis() + ", "
            + "\"indexed_at_ms\": " + metadata.getIndexTimeMillis()
            + "}";
    }

    /**
     * One page, and what it says about the set it was drawn from.
     *
     * @param documents  The documents on this page, in order
     * @param total      How many documents the filter matched, page or no page
     * @param nextCursor Where the next page starts, or null when this was the last
     */
    private record Page(List<DocumentMetadata> documents, int total, Cursor nextCursor) {
    }

    /**
     * Where a page ended, as the two fields the listing is ordered by.
     *
     * <p>Opaque on the wire so the pair can change without breaking a caller that kept one,
     * and so nobody constructs one by hand out of a document id that needs escaping.</p>
     *
     * @param sourceId   The source id of the last document returned
     * @param documentId Its document id
     */
    private record Cursor(String sourceId, String documentId) {

        /** What a value that is not a cursor this node issued is answered with. */
        private static final String UNREADABLE =
            "cursor is not one this node issued; start the listing again without it";

        /**
         * The cursor that resumes after a document.
         *
         * @param metadata The last document of a page
         * @return A cursor the next request continues from
         */
        static Cursor of(DocumentMetadata metadata) {
            return new Cursor(metadata.getSourceId(), metadata.getDocumentId());
        }

        /**
         * Reads a cursor off a request.
         *
         * @param encoded What the request carried, or null when it carried none
         * @return The cursor, or null to start at the beginning
         * @throws IllegalArgumentException if the value is not a cursor this node issued
         */
        static Cursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            String decoded;
            try {
                decoded = new String(Base64.getUrlDecoder().decode(encoded.trim()),
                    StandardCharsets.UTF_8);
            } catch (IllegalArgumentException notBase64) {
                throw new IllegalArgumentException(UNREADABLE);
            }
            int separator = decoded.indexOf(CURSOR_SEPARATOR);
            if (separator < 0) {
                throw new IllegalArgumentException(UNREADABLE);
            }
            return new Cursor(decoded.substring(0, separator),
                decoded.substring(separator + 1));
        }

        /** @return the cursor as it travels, opaque to the caller */
        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (sourceId + CURSOR_SEPARATOR + documentId).getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Whether a document falls after this cursor in the listing's order.
         *
         * @param metadata The document to place
         * @return true when the document has not been returned yet
         */
        boolean precedes(DocumentMetadata metadata) {
            int bySource = sourceId.compareTo(metadata.getSourceId());
            return bySource != 0 ? bySource < 0
                : documentId.compareTo(metadata.getDocumentId()) < 0;
        }
    }
}
