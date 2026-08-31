package io.excalibur.retrieval.pipeline;

/**
 * Resolves a textual mention to the entity ids it might mean.
 *
 * <p>The boundary between text and integers. Everything before it works on strings the user typed;
 * everything after it — traversal, ranking, serialisation — works on integer ids, which is what
 * makes the hot paths allocation-free and the columns addressable.
 *
 * <p>Returns several ids because a mention is often genuinely ambiguous: "Cambridge" is at least
 * two cities and a university. Returning one would be a guess made where the caller can no longer
 * see that a choice happened, which is precisely what abstention exists to prevent.
 */
@FunctionalInterface
public interface EntityLinker {

    /**
     * Entity ids {@code mention} could refer to, best first.
     *
     * @return the candidates, empty if the mention matches nothing
     */
    long[] link(String mention);
}
