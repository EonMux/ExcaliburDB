package com.eonmux.excalibur.api.embedded;

/**
 * Which retrieval signals answered a search.
 *
 * <p>Decided by what the node holds, exactly as {@code POST /v1/query} decides it: a node with an
 * embedding endpoint and a chunk index fuses both, one with only the index answers from BM25, and
 * one with only vectors answers from those. A signal that fails during a search leaves the other
 * one to answer, and the answer says which did.</p>
 */
public enum SearchRoute {

    /** Vector search alone: nearest neighbours of the embedded query. */
    VECTOR,

    /** BM25 over the chunk index alone, with the query expanded when a chat model is in use. */
    LEXICAL,

    /** Both signals, fused by reciprocal rank. */
    HYBRID
}
