package io.excalibur.retrieval.serialize;

/**
 * Counts how many tokens a piece of text will cost the model.
 *
 * <p>An interface rather than a function, because the right counter depends on the model and the
 * wrong one is worse than useless: a budget enforced with a counter that disagrees with the model
 * either wastes context or overruns it, and overrunning is a hard failure at request time rather
 * than a degradation.
 *
 * <p>Characters divided by four is the estimate everyone reaches for, and it is worst exactly where
 * this system lives. Knowledge-graph text is dense in identifiers, dates and rare proper nouns —
 * the strings a BPE vocabulary has no single token for and splits into many. An estimate tuned on
 * prose will silently under-count that by a wide margin.
 */
@FunctionalInterface
public interface TokenCounter {

    /** How many tokens {@code text} costs. */
    int count(String text);

    /**
     * A counter that over-estimates rather than measuring, for tests and for text whose model is
     * not known.
     *
     * <p>One token per three characters. Deliberately pessimistic: under-counting overruns the
     * context window, over-counting only leaves some of it unused, and those are not comparable
     * failures.
     */
    static TokenCounter conservativeEstimate() {
        return text -> text == null || text.isEmpty() ? 0 : (text.length() + 2) / 3;
    }
}
