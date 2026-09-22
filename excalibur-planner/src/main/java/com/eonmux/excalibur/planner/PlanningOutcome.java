package com.eonmux.excalibur.planner;

import java.util.List;

/**
 * What planning concluded — a plan, or a question back.
 *
 * <p>Sealed for the same reason as {@link QueryPlan}: a caller that forgets to handle the "ask the
 * user" case does not compile. That case is the one most likely to be skipped, and skipping it
 * means guessing.
 */
public sealed interface PlanningOutcome
        permits PlanningOutcome.Planned, PlanningOutcome.AskUser {

    /** Planning succeeded. */
    record Planned(QueryPlan plan) implements PlanningOutcome {
        public Planned {
            if (plan == null) {
                throw new IllegalArgumentException("plan must not be null");
            }
        }
    }

    /**
     * Planning could not proceed without something only the user can supply.
     *
     * <p>Overwhelmingly a pronoun with no antecedent. Guessing which entity "she" refers to
     * produces a confident answer about the wrong person, which is far worse than a question —
     * the user cannot tell it happened, and the answer looks exactly like a correct one.
     *
     * @param reason      why planning stopped
     * @param question    what to ask, phrased for a person
     * @param unresolved  the mentions that could not be bound
     * @param candidates  possible antecedents, when there were several
     */
    record AskUser(Reason reason, String question, List<String> unresolved,
                   List<String> candidates) implements PlanningOutcome {
        public AskUser {
            if (reason == null) {
                throw new IllegalArgumentException("reason must not be null");
            }
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException(
                    "an AskUser outcome must carry a question; without one the caller has nothing "
                        + "to put to the user and will fall back to guessing");
            }
            unresolved = List.copyOf(unresolved == null ? List.of() : unresolved);
            candidates = List.copyOf(candidates == null ? List.of() : candidates);
        }
    }

    /** Why planning stopped and asked. */
    enum Reason {
        /** A pronoun with nothing in the conversation to bind it to. */
        UNBOUND_PRONOUN,

        /** A pronoun with several equally plausible antecedents. */
        AMBIGUOUS_PRONOUN,

        /** No entity mention at all, so there is nothing to look up. */
        NO_ENTITY_MENTION
    }
}
