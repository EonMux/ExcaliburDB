package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.cache.CachedAnswer;
import com.eonmux.excalibur.query.cache.CorpusVersion;
import com.eonmux.excalibur.query.cache.QueryCacheKey;
import com.eonmux.excalibur.query.cache.QueryResultCache;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;

/**
 * One request's dealings with the query result cache: what was asked, what the corpus looked
 * like, and whether an answer was already held.
 *
 * <h2>Why the fingerprint is taken here and kept</h2>
 * <p>The version read before the search is the one the answer is stored against afterwards.
 * Re-reading it at store time would be the bug this whole mechanism exists to prevent: a
 * document ingested while the query ran would move the corpus, and storing the older answer
 * under the newer fingerprint would make it look current to every subsequent caller.
 * {@link QueryResultCache#put} therefore receives the fingerprint from before, and refuses
 * the answer outright when the corpus has moved past it.</p>
 *
 * <h2>What the fingerprint covers</h2>
 * <p>Everything the route this node answers by reads. For vector search that is the segment
 * catalog, {@code QueryPipeline.corpusVersion()}. For BM25 and for the two fused it is that and
 * the lexical index as a search started now would see it - {@link LexicalQueryService#searchVersion()},
 * which moves when the query epoch makes a new manifest entry visible, when an erasure
 * tombstones a chunk and when compaction swaps a segment, with no write this process sees.
 * Either index moving therefore moves the fingerprint, and the cache, which keeps one
 * generation, drops every answer computed before it. An index that cannot say what it would
 * read makes the fingerprint unreadable, and an unreadable fingerprint is neither served from
 * nor stored to.</p>
 *
 * <p>The route is decided by what the node holds, not per request, so every lookup on one node
 * presents a fingerprint of the same shape; a node that alternated between two would discard
 * its own cache on every other query.</p>
 *
 * <h2>What is cached, and what is not</h2>
 * <p>The ranking and what the search said about itself - one of {@link QueryResponse},
 * {@link LexicalResponse} or {@link HybridResponse}, after relevancy scoring when it ran. Never
 * the result text, snippets, explanations or {@code ignored_fields}: those are read and decided
 * again for the request being answered. And only an answer from the route the fingerprint was
 * taken for - a fused request that ended up answered by one signal is not the answer its key
 * names, and is not stored under it.</p>
 *
 * @param cache   The cache, or null on a node that runs without one
 * @param key     The request, or null when there is no cache
 * @param version The corpus as it stood before the search, or null when there is no cache
 * @param route   The route the key and fingerprint were taken for, or null when there is no
 *                cache
 * @author ExcaliburDB
 */
record QueryCacheLookup(QueryResultCache cache, QueryCacheKey key, CorpusVersion version,
                        String route) {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryCacheLookup.class);

    /** The lookup a node with no cache performs: none, and it costs nothing. */
    static final QueryCacheLookup NONE = new QueryCacheLookup(null, null, null, null);

    /** The identity of a request that presented no credential. */
    private static final String ANONYMOUS = "anonymous";

    /**
     * Opens one request's lookup, fingerprinting the corpus first.
     *
     * @param cache    The cache, or null on a node that runs without one
     * @param pipeline The pipeline the segment catalog's fingerprint is read from
     * @param lexical  The lexical index, read for its own fingerprint on the lexical and hybrid
     *                 routes; may be null on the vector route
     * @param route    The route this node answers by: {@link QueryResponseJson#VECTOR},
     *                 {@link QueryResponseJson#LEXICAL} or {@link QueryResponseJson#HYBRID}
     * @param parsed   The validated search fields
     * @param context  The validated presentation fields
     * @param exchange The request, for the credential it presented
     * @return The lookup, which on a node without a cache is {@link #NONE}
     */
    static QueryCacheLookup of(QueryResultCache cache, QueryPipeline pipeline,
                               LexicalQueryService lexical, String route,
                               QueryRequestParser.ParsedQuery parsed, QueryContext context,
                               HttpExchange exchange) {
        if (cache == null) {
            return NONE;
        }
        return new QueryCacheLookup(cache, keyOf(parsed, context, route, exchange),
            versionOf(pipeline, lexical, route), route);
    }

    /**
     * The answer already held for this request, if any.
     *
     * @param type The answer type this route produces
     * @param <A>  The answer type
     * @return The held answer, marked as served from the cache, or null
     */
    <A extends CachedAnswer> A hit(Class<A> type) {
        if (cache == null) {
            return null;
        }
        return cache.get(key, version, type);
    }

    /**
     * Stores an answer against the corpus it was computed from.
     *
     * <p>A no-op on a node with no cache, on an answer from a route other than the one this
     * lookup was taken for, and on an answer the cache refuses - one that is degraded, timed
     * out or cancelled, or computed against a corpus that has since moved.</p>
     *
     * @param answeredBy The route that actually produced the answer
     * @param response   The answer
     */
    void keep(String answeredBy, CachedAnswer response) {
        if (cache == null || !route.equals(answeredBy)) {
            return;
        }
        cache.put(key, version, response);
    }

    /**
     * The fingerprint of everything the route reads.
     *
     * @param pipeline The pipeline, for the segment catalog
     * @param lexical  The lexical index, for its own version
     * @param route    The route being fingerprinted
     * @return The fingerprint; {@link CorpusVersion#UNREADABLE} when any part cannot be read
     */
    private static CorpusVersion versionOf(QueryPipeline pipeline, LexicalQueryService lexical,
                                           String route) {
        CorpusVersion segments = pipeline.corpusVersion();
        if (segments == null) {
            return CorpusVersion.UNREADABLE;
        }
        if (QueryResponseJson.VECTOR.equals(route)) {
            return segments;
        }
        if (lexical == null) {
            return CorpusVersion.UNREADABLE;
        }
        OptionalLong lexicalVersion;
        try {
            lexicalVersion = lexical.searchVersion();
        } catch (RuntimeException e) {
            // Not a reason to fail the query, which can still search. It is a reason not to
            // cache: an index that cannot say what it would read cannot be shown unchanged.
            LOGGER.warn("The lexical index could not report its version; this answer will not "
                + "be cached: {}", e.getMessage());
            return CorpusVersion.UNREADABLE;
        }
        return lexicalVersion.isPresent()
            ? segments.alongside(lexicalVersion.getAsLong())
            : CorpusVersion.UNREADABLE;
    }

    /**
     * Builds the key naming every input that can change this request's answer.
     *
     * <h3>Namespace</h3>
     * <p>{@code POST /v1/query} names no namespace: the vector corpus on a node is one
     * corpus, and the only namespaced route is the graph one, which does not come through
     * here. The component is carried anyway and left empty, so that a future per-tenant
     * corpus has to fill it in rather than silently share entries across tenants - which is
     * the failure that would not be noticed until it had already happened.</p>
     *
     * <h3>Security context</h3>
     * <p>A digest of the bearer token presented, never the token. One shared token is
     * configured today, so this is constant across callers and costs no hit rate; if a
     * deployment ever issues more than one, entries stop being shared between them without
     * anybody having to remember to make that happen.</p>
     *
     * @param parsed   The validated search fields
     * @param context  The validated presentation fields
     * @param route    The route the answer will come from
     * @param exchange The request, for the credential it presented
     * @return The key
     */
    private static QueryCacheKey keyOf(QueryRequestParser.ParsedQuery parsed,
                                       QueryContext context, String route,
                                       HttpExchange exchange) {
        QuerySearchOptions options = context.options();
        return QueryCacheKey.builder()
            .namespace("")
            .securityContext(securityContext(exchange))
            .sourceScope(parsed.sourceScope())
            .retrievalMode(route)
            .queryText(parsed.query())
            .topK(parsed.topK())
            .offset(options.offset())
            .threshold(parsed.threshold())
            .includePartialSegments(parsed.includePartial())
            .enableLlmScoring(parsed.enableLlmScoring())
            .llmThreshold(parsed.llmThreshold())
            .oversamplingFactor(parsed.oversamplingFactor())
            .includeText(parsed.includeText())
            .snippet(options.snippet())
            .snippetChars(options.snippetChars())
            .explain(options.explain())
            .build();
    }

    /**
     * An opaque, stable identity for whatever credential a request presented.
     *
     * <p>Hashed rather than kept, because a cache key is held in memory for the life of the
     * entry and turns up in any dump of it; a bearer token must not be. A request with no
     * credential - which is every request on an unauthenticated node - is its own context
     * rather than sharing one with authenticated callers, and so is a request whose headers
     * cannot be read at all.</p>
     *
     * @param exchange The request
     * @return A hex digest, or {@code anonymous}
     */
    private static String securityContext(HttpExchange exchange) {
        Headers requestHeaders = exchange.getRequestHeaders();
        List<String> headers =
            requestHeaders == null ? null : requestHeaders.get("Authorization");
        if (headers == null || headers.isEmpty() || headers.get(0) == null) {
            return ANONYMOUS;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(headers.get(0).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // Not swallowed, and not a reason to fail the query: a JVM without SHA-256 can
            // still search. What it cannot do is tell two credentials apart, so every
            // request from here on shares one context and the cache is correspondingly
            // coarse - which is safe only because it is also reported.
            LOGGER.warn("SHA-256 is unavailable, so cached answers cannot be separated by "
                + "credential; every caller will share one cache scope: {}", e.getMessage());
            return ANONYMOUS;
        }
    }
}
