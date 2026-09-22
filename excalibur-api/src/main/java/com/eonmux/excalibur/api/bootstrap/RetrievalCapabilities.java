package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.LlmConfig;
import com.eonmux.excalibur.config.QueryConfig;

/**
 * Which model-backed retrieval signals a node offers, decided once from its configuration.
 *
 * <h2>Why this is not one boolean</h2>
 * <p>A single API key opens both the embeddings endpoint and the chat endpoint, so "is a key set"
 * looks like it answers every question about what this node can do. It does not. Embedding the
 * corpus and expanding the query are alternatives, not companions: the first spends the model on
 * every document once, the second spends it on every query, and the second only ever runs on a
 * node that took the lexical path because it has no vectors. Deriving both from key-presence put
 * the expander on exactly the nodes that would never consult it.</p>
 *
 * <h2>The three deployments this describes</h2>
 * <ul>
 *   <li><b>No key.</b> Documents are chunked, stored and term-indexed; queries answer from plain
 *       BM25. Nothing calls out to a model.</li>
 *   <li><b>A key.</b> The corpus is embedded, and {@code POST /v1/query} runs both signals:
 *       vector search and BM25 over the same chunks, fused by rank. The query is not expanded
 *       by default, because expansion is what carries the semantics when there are no vectors
 *       and here there are - but the BM25 half is then plain BM25, weaker than the same node
 *       would run with no embeddings at all, so
 *       {@code excalibur.query.expand-hybrid-queries} turns it on for a corpus where that
 *       trade does not hold. Without that setting the combination was unreachable: the
 *       retrieval benchmark could score it and no deployment could be put into it.</li>
 *   <li><b>A key with {@code excalibur.llm.embeddings-enabled=false}.</b> No embedding pass; the
 *       chat model expands the query instead, and BM25 does the retrieval. A few dozen generated
 *       tokens per query against billions of embedded tokens per corpus.</li>
 * </ul>
 *
 * @param chatModel     Whether a model can be called at all, which is what a key means
 * @param embedCorpus   Whether documents and query text are turned into vectors
 * @param expandQueries Whether the lexical path expands a query with the chat model
 */
public record RetrievalCapabilities(boolean chatModel, boolean embedCorpus,
                                    boolean expandQueries) {

    /**
     * Reads the capabilities out of a node's configuration.
     *
     * @param config The node's settings
     * @return What this node can offer
     */
    public static RetrievalCapabilities from(Config config) {
        return from(config.llm(), config.query().isExpandHybridQueries());
    }

    /**
     * Reads the capabilities out of the LLM configuration alone, leaving expansion on a hybrid
     * node at its default.
     *
     * @param llm The node's LLM settings
     * @return What this node can offer
     */
    public static RetrievalCapabilities from(LlmConfig llm) {
        return from(llm, QueryConfig.DEFAULT_EXPAND_HYBRID_QUERIES);
    }

    /**
     * Reads the capabilities out of the LLM configuration and the expansion policy.
     *
     * @param llm             The node's LLM settings
     * @param expandOnHybrid  Whether a node that embeds its corpus also expands the query
     * @return What this node can offer
     */
    public static RetrievalCapabilities from(LlmConfig llm, boolean expandOnHybrid) {
        // Blank counts as absent. A key of spaces is a mis-set environment variable, and treating
        // it as present buys an authentication failure on every document instead of the
        // lexical-only start the operator would otherwise have got.
        String apiKey = llm.getApiKey();
        boolean chatModel = apiKey != null && !apiKey.isBlank();
        boolean embedCorpus = chatModel && llm.isEmbeddingsEnabled();
        boolean expandQueries = chatModel && (!embedCorpus || expandOnHybrid);
        return new RetrievalCapabilities(chatModel, embedCorpus, expandQueries);
    }
}
