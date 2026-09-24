package com.eonmux.excalibur.api.protocol;

/**
 * The machine-readable reason carried in the {@code code} member of every error response.
 *
 * <p>The {@code error} member is prose written for a person, and it changes whenever the wording
 * is improved. A client that branches on it breaks on a reworded sentence; a client that branches
 * on the status alone cannot tell a rate limit from a full source table, both of which are
 * {@code 429}. The code is the stable half: a snake_case name that is never reworded, and that
 * names the cause where the status alone does not.</p>
 *
 * <p>The first group are the defaults, one per status, used wherever a call site has only the
 * status to go on. The rest refine one of those statuses for a cause a client can act on
 * differently. A new code may be added; an existing one is never renamed or reused for a
 * different cause.</p>
 *
 * @author ExcaliburDB
 */
public enum ErrorCode {

    /** 400: the request was malformed, or a field was missing or of the wrong type. */
    INVALID_REQUEST("invalid_request"),
    /** 401: no bearer token, or the wrong one. */
    UNAUTHORIZED("unauthorized"),
    /** 404: nothing at this path, or nothing by the name the request gave. */
    NOT_FOUND("not_found"),
    /** 405: the path exists and does not serve this method. */
    METHOD_NOT_ALLOWED("method_not_allowed"),
    /** 409: the request conflicts with the node's current state. */
    CONFLICT("conflict"),
    /** 413: the request body is over the size limit. */
    PAYLOAD_TOO_LARGE("payload_too_large"),
    /** 422: the request is well formed and cannot be served as asked. */
    UNPROCESSABLE("unprocessable"),
    /** 429: the caller's request budget is spent; retry after {@code Retry-After}. */
    RATE_LIMITED("rate_limited"),
    /** 500: the node failed; the reason is in its log. */
    INTERNAL_ERROR("internal_error"),
    /** 503: the node cannot serve this now. */
    UNAVAILABLE("unavailable"),
    /** Any other 4xx. */
    CLIENT_ERROR("client_error"),
    /** Any other 5xx. */
    SERVER_ERROR("server_error"),

    /** 400: {@code X-Excalibur-Namespace} named a tenant on a route that serves the shared corpus. */
    NAMESPACE_NOT_ACCEPTED("namespace_not_accepted"),
    /** 400: a namespace name that is not a legal namespace id. */
    INVALID_NAMESPACE("invalid_namespace"),
    /** 404: no namespace by that name exists on this node. */
    NAMESPACE_NOT_FOUND("namespace_not_found"),
    /** 409: the shared namespace cannot be dropped. */
    NAMESPACE_PROTECTED("namespace_protected"),
    /** 429: the node already holds as many namespaces as it is configured for. */
    NAMESPACE_LIMIT_REACHED("namespace_limit_reached"),
    /** 503: no graph has been built for this namespace yet. */
    GRAPH_NOT_SERVED("graph_not_served"),
    /** 409: a name matches more than one entity; the body lists {@code candidates}. */
    AMBIGUOUS_ENTITY("ambiguous_entity"),
    /** 404: no entity in the namespace matches the name or id. */
    ENTITY_NOT_FOUND("entity_not_found"),
    /** 404: no asserted fact in the namespace has that id or states that triple. */
    FACT_NOT_FOUND("fact_not_found"),
    /** 404: no erasure request with that id is known, in that namespace or on this node. */
    ERASURE_NOT_FOUND("erasure_not_found"),
    /** 409: a graph build is already running. */
    BUILD_IN_PROGRESS("build_in_progress"),
    /** 409: a compaction is already running. */
    COMPACTION_IN_PROGRESS("compaction_in_progress"),
    /** 409: a reindex is already running for the source. */
    REINDEX_IN_PROGRESS("reindex_in_progress"),
    /** 503: the node is shutting down and refuses new work. */
    SHUTTING_DOWN("shutting_down"),
    /** 404: no query in flight has that id. */
    QUERY_NOT_FOUND("query_not_found"),
    /** 404: no source is registered under that id. */
    SOURCE_NOT_FOUND("source_not_found"),
    /** 409: a source is already registered under that id. */
    SOURCE_EXISTS("source_exists"),
    /** 409: the id belongs to the other kind of source - crawled versus supplied. */
    SOURCE_KIND_CONFLICT("source_kind_conflict"),
    /** 429: the node already holds as many sources as it is configured for. */
    SOURCE_LIMIT_REACHED("source_limit_reached"),
    /**
     * 409: a crawl or reindex of the source is running, so its registration cannot change - or,
     * for a source-scoped erasure, a run was still writing when the wait for it ran out, so
     * nothing was erased.
     */
    SOURCE_BUSY("source_busy"),
    /** 409: a replacement names a different namespace from the source's own. */
    SOURCE_NAMESPACE_MISMATCH("source_namespace_mismatch"),
    /** 403: the ingest target is outside the allow-lists. */
    TARGET_NOT_ALLOWED("target_not_allowed"),
    /** 503: a registration change could not be recorded in the source catalog. */
    CATALOG_UNAVAILABLE("catalog_unavailable"),
    /** 503: every permit for the work is taken; retry with backoff. */
    SATURATED("saturated"),
    /** 503: the node was started without what this operation needs. */
    NOT_CONFIGURED("not_configured");

    private final String wireName;

    ErrorCode(String wireName) {
        this.wireName = wireName;
    }

    /**
     * The name as it appears in a response body.
     *
     * @return The snake_case code
     */
    public String wireName() {
        return wireName;
    }

    /**
     * The code an error response carries when its call site names none.
     *
     * @param statusCode The HTTP status, 400 or above
     * @return The default code for that status
     */
    public static ErrorCode forStatus(int statusCode) {
        return switch (statusCode) {
            case 400 -> INVALID_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 422 -> UNPROCESSABLE;
            case 429 -> RATE_LIMITED;
            case 500 -> INTERNAL_ERROR;
            case 503 -> UNAVAILABLE;
            default -> statusCode >= 500 ? SERVER_ERROR : CLIENT_ERROR;
        };
    }
}
