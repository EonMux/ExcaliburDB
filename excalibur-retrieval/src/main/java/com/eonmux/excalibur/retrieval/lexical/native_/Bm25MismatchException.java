package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.retrieval.lexical.Bm25Parameters;

import java.io.IOException;
import java.nio.file.Path;

/**
 * An index was built with one BM25 pair and a node configured with another tried to open it.
 *
 * <h2>Why this is refused rather than reconciled</h2>
 * <p>Each postings block stores the largest impact any posting in it can have, computed from
 * {@code k1} and {@code b} when the block was written, and the scorer skips blocks whose bound
 * cannot lift a document into the current top-k. Scoring with a pair the bounds were not computed
 * from breaks that reasoning in the one direction that matters: a block whose true impact under
 * the new pair is higher than the recorded bound can be skipped while it holds a top result, and
 * the query comes back short with nothing to show that it did.
 *
 * <p>Neither side can be adopted silently. Taking the persisted pair ignores what the operator
 * asked for, and taking the configured one is exactly the unsound combination above. The values
 * are baked into the index at build time, so applying new ones means building the index again.
 */
public final class Bm25MismatchException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient Bm25Parameters persisted;
    private final transient Bm25Parameters configured;

    Bm25MismatchException(Path index, Bm25Parameters persisted, Bm25Parameters configured) {
        super("lexical index " + index + " was built with BM25 " + persisted.describe()
            + " but this node is configured for " + configured.describe()
            + "; the per-block impact bounds it holds were computed from the values it was built "
            + "with, so scoring against different ones would silently skip blocks that hold "
            + "matching documents. Reingest the corpus to rebuild the index for the new values, "
            + "or restore the values it was built with.");
        this.persisted = persisted;
        this.configured = configured;
    }

    /** What the index on disk was built with. */
    public Bm25Parameters persisted() {
        return persisted;
    }

    /** What this node was asked to score with. */
    public Bm25Parameters configured() {
        return configured;
    }
}
