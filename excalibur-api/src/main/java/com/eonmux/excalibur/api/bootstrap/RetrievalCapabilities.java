package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.LlmConfig;
import com.eonmux.excalibur.config.QueryConfig;

/**
 * Which model-backed retrieval signals a node offers, decided once from its configuration.
 *
 * <h2>Why this is not one boolean</h2>
 * <p>Embedding the corpus and calling a chat model are separate endpoints with separate switches
 * ({@link LlmConfig#embeddingEndpoint()} and {@link LlmConfig#chatEndpoint()}), and they are
 * alternatives more often than companions: the first spends a model on every document once, the
 * second on every query, and query expansion only ever runs on a node that took the lexical path
 * because it has no vectors. Deriving everything from one condition put the expander on exactly
 * the nodes that would never consult it.</p>
 *
 * <h2>Why not "is a key set"</h2>
 * <p>A key does not say whether a model can be called. Ollama, vLLM and TGI need none, so a node
 * pointed at one of them is used with or without a key; only the hosted default endpoint needs
 * one. A role is in use when its enabled flag is on and it either has a key or names a server
 * other than the hosted default - see {@link com.eonmux.excalibur.config.LlmEndpoint}.</p>
 *
 * <h2>The deployments this describes</h2>
 * <ul>
 *   <li><b>No model.</b> Documents are chunked, stored and term-indexed; queries answer from plain
 *       BM25. Nothing calls out to a model.</li>
 *   <li><b>Both endpoints.</b> The corpus is embedded, and {@code POST /v1/query} runs both
 *       signals: vector search and BM25 over the same chunks, fused by rank. The query is not
 *       expanded by default, because expansion is what carries the semantics when there are no
 *       vectors and here there are - but the BM25 half is then plain BM25, weaker than the same
 *       node would run with no embeddings at all, so
 *       {@code excalibur.query.expand-hybrid-queries} turns it on for a corpus where that
 *       trade does not hold.</li>
 *   <li><b>Chat only</b> ({@code excalibur.llm.embeddings-enabled=false}). No embedding pass;
 *       the chat model expands the query instead, and BM25 does the retrieval. A few dozen
 *       generated tokens per query against billions of embedded tokens per corpus.</li>
 *   <li><b>Embeddings only</b> ({@code excalibur.llm.chat-enabled=false}, or an embedding
 *       server beside an unusable chat endpoint). Hybrid retrieval with no relevancy scoring,
 *       query expansion, relation extraction or graph rerank.</li>
 * </ul>
 *
 * @param chatModel     Whether the chat endpoint is in use
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
        boolean chatModel = llm.chatEndpoint().isActive();
        boolean embedCorpus = llm.embeddingEndpoint().isActive();
        boolean expandQueries = chatModel && (!embedCorpus || expandOnHybrid);
        return new RetrievalCapabilities(chatModel, embedCorpus, expandQueries);
    }
}
