package com.eonmux.excalibur.planner;

import java.util.List;

import com.eonmux.excalibur.graph.temporal.TemporalFilter;

/**
 * What a query is asking for, decided before any model is involved.
 *
 * <p>Planning is a classification problem with a small, fixed set of answers, and a model is the
 * wrong tool for it: it is non-deterministic, it costs a round trip on the critical path of every
 * query, and when it plans badly there is nothing to inspect. A lexical classifier is repeatable,
 * free, and debuggable — a wrong plan is a rule someone can read and fix. Ask the model to answer
 * the question, not to decide how to look it up.
 *
 * <p>Sealed, so a consumer switching over plans and forgetting a case does not compile. Adding a
 * fifth plan type later is then a compile error at every site that must handle it, rather than a
 * silent fall-through to whatever the default branch did.
 */
public sealed interface QueryPlan
        permits QueryPlan.Lookup, QueryPlan.Traverse, QueryPlan.Aggregate, QueryPlan.Temporal {

    /** The entity mentions this plan starts from. */
    List<String> mentions();

    /**
     * How the answer is found, with any temporal wrapper removed.
     *
     * <p>{@link Temporal} says when to evaluate, not what to evaluate, so it is the wrong answer
     * to "which plan ran": a caller told only "temporal" learns nothing it did not already know
     * from having asked at an instant. Since a temporal plan cannot wrap another, one unwrapping
     * always reaches the plan that decides the work.
     *
     * @return this plan, or the plan inside it if this is a {@link Temporal}
     */
    default QueryPlan shape() {
        return this instanceof Temporal temporal ? temporal.inner() : this;
    }

    /**
     * Retrieve facts about named entities directly.
     *
     * <p>"What is Ada Lovelace's date of birth". No traversal: the answer is on the entity.
     *
     * @param mentions entity mentions to resolve and look up
     */
    record Lookup(List<String> mentions) implements QueryPlan {
        public Lookup {
            mentions = List.copyOf(requireNonEmpty(mentions));
        }
    }

    /**
     * Walk the graph outward from named entities.
     *
     * <p>"Who did Ada Lovelace work with". The answer is not on the entity, it is reachable from
     * it, and how far to go is the plan's decision rather than the traversal's.
     *
     * @param mentions      entity mentions to seed from
     * @param hops          how far to walk
     * @param relationHints relation names the question suggests, empty if none were recognised
     */
    record Traverse(List<String> mentions, int hops, List<String> relationHints)
            implements QueryPlan {
        public Traverse {
            mentions = List.copyOf(requireNonEmpty(mentions));
            relationHints = List.copyOf(relationHints == null ? List.of() : relationHints);
            if (hops < 1) {
                throw new IllegalArgumentException(
                    "a traversal needs at least one hop; zero hops is a Lookup");
            }
        }
    }

    /**
     * Count or summarise rather than enumerate.
     *
     * <p>"How many papers did she publish". Distinguished from {@link Traverse} because a CSR
     * answers a count from the offset array alone, without materialising the neighbours — so
     * planning it as a traversal and counting the results afterwards does real work to produce a
     * number that was already available.
     *
     * @param mentions      entity mentions to aggregate over
     * @param function      what to compute
     * @param relationHints relation names the question suggests
     */
    record Aggregate(List<String> mentions, AggregateFunction function, List<String> relationHints)
            implements QueryPlan {
        public Aggregate {
            mentions = List.copyOf(requireNonEmpty(mentions));
            relationHints = List.copyOf(relationHints == null ? List.of() : relationHints);
            if (function == null) {
                throw new IllegalArgumentException("function must not be null");
            }
        }
    }

    /**
     * Any other plan, evaluated at a point in valid time.
     *
     * <p>A wrapper rather than a fourth sibling, because "as of 2019" modifies a question without
     * changing what is being asked: every one of the other plans can be asked about the past. Making
     * it a separate kind would mean a temporal variant of each.
     *
     * @param inner what to evaluate
     * @param asOf  when to evaluate it — valid time, never the index's epoch
     */
    record Temporal(QueryPlan inner, TemporalFilter asOf) implements QueryPlan {
        public Temporal {
            if (inner == null) {
                throw new IllegalArgumentException("inner plan must not be null");
            }
            if (inner instanceof Temporal) {
                throw new IllegalArgumentException(
                    "a temporal plan cannot wrap another; a question has one as-of instant");
            }
            if (asOf == null) {
                throw new IllegalArgumentException("asOf must not be null");
            }
        }

        @Override
        public List<String> mentions() {
            return inner.mentions();
        }
    }

    /** What an {@link Aggregate} computes. */
    enum AggregateFunction {
        /** How many. */
        COUNT,
        /** The earliest. */
        EARLIEST,
        /** The latest. */
        LATEST
    }

    private static List<String> requireNonEmpty(List<String> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            throw new IllegalArgumentException(
                "a plan needs at least one entity mention; a question with none is unanswerable "
                    + "and should have become an AskUser outcome instead");
        }
        return mentions;
    }
}
