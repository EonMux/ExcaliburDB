package io.excalibur.graph.temporal;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which relations hold only one value at a time, and therefore close their predecessor when a new
 * statement arrives.
 *
 * <p>Without this, a conversation that says "I work at Acme" and later "I work at Beta" leaves both
 * edges open, and a query as of today returns two employers with no way to tell which is current.
 * With it, the second statement closes the first at its own start, and the same query returns one.
 *
 * <p>This is necessarily per-relation rather than global. {@code employer} is single-valued, so a
 * new value supersedes the old; {@code authored} is not, and closing the previous book when someone
 * writes another would be a straightforward data-loss bug. There is no safe default that covers
 * both, so the policy is explicit and the default supersedes nothing.
 */
@FunctionalInterface
public interface SupersessionPolicy {

    /**
     * Supersedes nothing. The default, because closing an interval destroys information and the
     * cost of getting it wrong is asymmetric: a missed supersession returns a stale extra answer,
     * an incorrect one silently deletes a true fact.
     */
    SupersessionPolicy NONE = relation -> false;

    /**
     * Whether {@code relation} holds at most one value at any instant.
     *
     * @param relation a relation id, always non-negative
     */
    boolean isSingleValued(int relation);

    /** A policy where exactly the given relations are single-valued. */
    static SupersessionPolicy forRelations(int... relations) {
        Set<Integer> single = Arrays.stream(relations).boxed().collect(Collectors.toSet());
        for (int relation : relations) {
            if (relation < 0) {
                throw new IllegalArgumentException(
                    "relation id must be non-negative, got " + relation);
            }
        }
        return single::contains;
    }

    /** A policy that supersedes every relation. Correct only for a purely functional schema. */
    static SupersessionPolicy all() {
        return relation -> true;
    }
}
