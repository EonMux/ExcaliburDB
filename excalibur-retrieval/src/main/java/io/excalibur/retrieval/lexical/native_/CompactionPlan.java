package io.excalibur.retrieval.lexical.native_;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which segments a compaction will rewrite, and why.
 *
 * <p>A plan is a list of groups, each of which becomes one segment. A group of one is a purge: the
 * segment is rewritten without its tombstoned documents and keeps its epoch. A group of several is
 * also a merge. Both are the same operation - read the surviving documents, write a new segment -
 * so they are one shape rather than two code paths.</p>
 *
 * <p>A plan naming no groups is not a mistake. It is the outcome when compaction is due but there
 * is nothing left to remove, and it still settles the erasure log, which is what stops an
 * unsatisfiable promise from demanding compaction forever.</p>
 *
 * @param groups Segments to rewrite, each group becoming one segment
 * @param reason What made this compaction due, for the operator reading the log
 */
public record CompactionPlan(List<List<String>> groups, String reason) {

    public CompactionPlan {
        Objects.requireNonNull(groups, "groups must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        List<List<String>> copies = new ArrayList<>(groups.size());
        Set<String> seen = new HashSet<>();
        for (List<String> group : groups) {
            if (group == null || group.isEmpty()) {
                throw new IllegalArgumentException("a compaction group must name a segment");
            }
            for (String name : group) {
                Objects.requireNonNull(name, "segment name must not be null");
                if (!seen.add(name)) {
                    // Two groups rewriting one segment would have the second read what the first
                    // already deleted, so the collision is refused rather than raced.
                    throw new IllegalArgumentException("segment named by two groups: " + name);
                }
            }
            copies.add(List.copyOf(group));
        }
        groups = List.copyOf(copies);
    }

    /** A plan that rewrites nothing, made when compaction is due but nothing is left to remove. */
    public static CompactionPlan nothingToRewrite(String reason) {
        return new CompactionPlan(List.of(), reason);
    }

    /** Whether this plan rewrites any segment at all. */
    public boolean rewritesNothing() {
        return groups.isEmpty();
    }

    /** Segments this plan reads and replaces. */
    public int segmentsRewritten() {
        int total = 0;
        for (List<String> group : groups) {
            total += group.size();
        }
        return total;
    }

    /** Segments this plan leaves behind in their place. */
    public int segmentsProduced() {
        return groups.size();
    }
}
