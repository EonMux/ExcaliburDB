package excalibur.bench.retrieval;

/**
 * The retrieval configurations worth comparing before committing to an architecture.
 *
 * <p>The question these exist to answer is not which stack is most sophisticated. It is whether
 * query-side semantic expansion over a lexical index reaches the same documents as embedding the
 * whole corpus, at a fraction of the indexing cost. Every strategy that includes {@link #VECTOR}
 * carries that indexing cost; every one that does not is free of it. A result is only interesting
 * if it is read alongside what each strategy costs to build.
 *
 * <p>These name what the query does, not what indexes it. Which lexical engine answered is a
 * property of the run and is reported beside the table, because the same strategy over the two
 * engines is the same strategy - and a comparison that folded the engine into the strategy name
 * would make two runs of the same configuration look like different configurations.
 */
public enum RetrievalStrategy {

    /** The lexical index alone, on the user's words as typed. The floor everything else must beat. */
    BM25(false, false, false),

    /** The lexical index over model-generated formulations of the query. No corpus embedding. */
    BM25_EXPANSION(true, false, false),

    /**
     * Expansion whose generated terms are checked against the corpus vocabulary before they are
     * searched, using the index's own dictionaries.
     *
     * <p>Measured apart from plain expansion so the gate's contribution is visible rather than
     * folded into the expansion result: a term the corpus does not contain cannot change a ranking,
     * but it can make a formulation look as though it was tried on its merits.</p>
     */
    BM25_EXPANSION_EXACT_VOCAB(true, false, false),

    /**
     * The same gate, answered by a Cuckoo filter over the vocabulary rather than by the index.
     *
     * <p>Approximate, and therefore able to admit a term the corpus lacks. What the comparison with
     * {@link #BM25_EXPANSION_EXACT_VOCAB} shows is what that approximation costs in quality, against
     * a filter that fits in memory where a dictionary lookup per term does not.</p>
     */
    BM25_EXPANSION_APPROX_VOCAB(true, false, false),

    /** The lexical index, then relationship traversal from what it found. No corpus embedding. */
    BM25_GRAPH(false, true, false),

    /** The lexical index fused with dense retrieval. Requires the corpus to be embedded. */
    BM25_VECTOR(false, false, true),

    /** Expansion and traversal together, still with no corpus embedding. */
    BM25_EXPANSION_GRAPH(true, true, false),

    /** Expansion and dense retrieval, without traversal. */
    BM25_EXPANSION_VECTOR(true, false, true),

    /** Everything: expansion, traversal and dense retrieval, fused. */
    FULL_HYBRID(true, true, true);

    private final boolean expansion;
    private final boolean graph;
    private final boolean vector;

    RetrievalStrategy(boolean expansion, boolean graph, boolean vector) {
        this.expansion = expansion;
        this.graph = graph;
        this.vector = vector;
    }

    public boolean usesExpansion() {
        return expansion;
    }

    public boolean usesGraph() {
        return graph;
    }

    /** Whether this strategy requires a corpus-wide embedding pass to exist at all. */
    public boolean usesVector() {
        return vector;
    }
}
