package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import excalibur.query.QueryResponse;
import excalibur.query.cache.CorpusVersion;
import excalibur.query.cache.QueryCacheKey;
import excalibur.query.cache.QueryResultCache;
import excalibur.query.pipeline.QueryPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

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
 * <h2>What is cached, and what is not</h2>
 * <p>Only the vector pipeline's own answer. The BM25 half of a hybrid query runs fresh on
 * every request, and so does every read of result text - passages, snippets and
 * explanations. That is deliberate rather than an omission: the lexical index publishes
 * through a manifest whose entries become visible when the query epoch crosses them, so its
 * answer can change with no mutation to observe at all, and there is no cheap fingerprint of
 * it to key on. Caching the half that can be shown to be unchanged and re-running the half
 * that cannot is what keeps the answer correct.</p>
 *
 * @param cache   The cache, or null on a node that runs without one
 * @param key     The request, or null when there is no cache
 * @param version The corpus as it stood before the search, or null when there is no cache
 * @param hit     The answer already held, or null when there was none
 * @author ExcaliburDB
 */
record QueryCacheLookup(QueryResultCache cache, QueryCacheKey key, CorpusVersion version,
                        QueryResponse hit) {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryCacheLookup.class);

    /** The lookup a node with no cache performs: none, and it costs nothing. */
    static final QueryCacheLookup NONE = new QueryCacheLookup(null, null, null, null);

    /** The identity of a request that presented no credential. */
    private static final String ANONYMOUS = "anonymous";

    /**
     * Looks one request up, fingerprinting the corpus first.
     *
     * @param cache    The cache, or null on a node that runs without one
     * @param pipeline The pipeline the corpus fingerprint is read from
     * @param parsed   The validated search fields
     * @param context  The validated presentation fields
     * @param hybrid   Whether a second signal was started for this request
     * @param exchange The request, for the credential it presented
     * @return What the cache had to say, which on a node without one is {@link #NONE}
     */
    static QueryCacheLookup of(QueryResultCache cache, QueryPipeline pipeline,
                               QueryRequestParser.ParsedQuery parsed, QueryContext context,
                               boolean hybrid, HttpExchange exchange) {
        if (cache == null) {
            return NONE;
        }
        QueryCacheKey key = keyOf(parsed, context, hybrid, exchange);
        CorpusVersion version = pipeline.corpusVersion();
        return new QueryCacheLookup(cache, key, version, cache.get(key, version));
    }

    /**
     * Stores an answer against the corpus it was computed from.
     *
     * <p>A no-op on a node with no cache, and on an answer the cache refuses - a degraded,
     * timed-out or cancelled one, or one computed against a corpus that has since moved.</p>
     *
     * @param response The answer the pipeline produced
     */
    void keep(QueryResponse response) {
        if (cache == null) {
            return;
        }
        cache.put(key, version, response);
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
     * @param hybrid   Whether a second signal was started for this request
     * @param exchange The request, for the credential it presented
     * @return The key
     */
    private static QueryCacheKey keyOf(QueryRequestParser.ParsedQuery parsed,
                                       QueryContext context, boolean hybrid,
                                       HttpExchange exchange) {
        QuerySearchOptions options = context.options();
        return QueryCacheKey.builder()
            .namespace("")
            .securityContext(securityContext(exchange))
            .retrievalMode(hybrid ? "hybrid" : "vector")
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
