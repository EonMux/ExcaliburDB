package io.excalibur.core.trace;

/**
 * The stages a retrieval request passes through.
 *
 * <p>Enumerated rather than left to free-form strings so that a timing can be attributed to the
 * same stage everywhere, and so a stage cannot be silently misspelled into a second series that
 * looks empty. When a query is slow the first question is which of these spent the time, and that
 * question is unanswerable if the boundaries are not fixed in advance.
 */
public enum Stage {

    /** Classifying the request and choosing a plan. */
    PLAN,

    /** Resolving mentions in the request to entity ids. */
    LINK,

    /** Walking the graph from the linked entities. */
    TRAVERSE,

    /** Running the retrieval signals and fusing them. */
    RANK,

    /** Turning the selected subgraph into text for the model. */
    SERIALIZE,

    /** The model reading the prompt. */
    PREFILL,

    /** The model producing tokens. */
    DECODE
}
