package com.eonmux.excalibur.retrieval.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eonmux.excalibur.planner.PlanningOutcome;
import com.eonmux.excalibur.retrieval.abstain.RetrievalOutcome;

/**
 * What the pipeline needs the user to settle before it can answer, whichever stage found it.
 *
 * <p>Two stages can hand a question back. Planning does when a pronoun has no antecedent or
 * several; abstention does when the question's entity matched several things the ranking could
 * not tell apart. They are one thing to a caller - here are the readings, say which you meant -
 * and one shape for both is what lets a caller handle them with one code path. Two would leave the
 * second to be rendered as a refusal, which is how an ambiguity the user could have resolved in a
 * word came to read as "the graph does not know".
 *
 * <p>Every candidate is a name the caller can send straight back as a conversation entity, and
 * sending one back is what resolves the question: a pronoun binds to it, and a name several
 * entities share resolves to the one the conversation names. Entities that share a label are
 * offered as {@link #qualifiedName(String, long) the label and their id}, so each choice names
 * exactly one of them.
 *
 * @param reason     why the question came back
 * @param question   what to put to the user, phrased for a person
 * @param unresolved the words that could not be settled
 * @param candidates the readings to choose between, by name; empty when there is nothing to
 *                   offer and the user has to supply the name themselves
 */
public record InputRequest(Reason reason, String question, List<String> unresolved,
                           List<String> candidates) {

    public InputRequest {
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        unresolved = List.copyOf(unresolved);
        candidates = List.copyOf(candidates);
    }

    /** Why a question came back to the user. */
    public enum Reason {
        /** A pronoun with nothing in the conversation to bind it to. */
        UNBOUND_PRONOUN,

        /** A pronoun with several equally plausible antecedents. */
        AMBIGUOUS_PRONOUN,

        /** No entity mention at all, so there is nothing to look up. */
        NO_ENTITY_MENTION,

        /** An entity the question named matched several the ranking could not tell apart. */
        AMBIGUOUS_ENTITY
    }

    /**
     * The request planning made.
     *
     * @param ask what planning asked
     * @return the same request, in the shape every stage shares
     */
    static InputRequest of(PlanningOutcome.AskUser ask) {
        Reason reason = switch (ask.reason()) {
            case UNBOUND_PRONOUN -> Reason.UNBOUND_PRONOUN;
            case AMBIGUOUS_PRONOUN -> Reason.AMBIGUOUS_PRONOUN;
            case NO_ENTITY_MENTION -> Reason.NO_ENTITY_MENTION;
        };
        return new InputRequest(reason, ask.question(), ask.unresolved(), ask.candidates());
    }

    /**
     * The request an ambiguous retrieval makes.
     *
     * @param ambiguous the competing readings, labelled
     * @param mentions  the entity mentions the question named, for saying what was ambiguous
     * @return the request
     */
    static InputRequest of(RetrievalOutcome.Ambiguous ambiguous, List<String> mentions) {
        Map<Long, String> labels = new LinkedHashMap<>();
        Map<String, Integer> sharing = new HashMap<>();
        for (RetrievalOutcome.Candidate candidate : ambiguous.candidates()) {
            if (labels.putIfAbsent(candidate.entityId(), candidate.label()) == null) {
                sharing.merge(candidate.label(), 1, Integer::sum);
            }
        }
        List<String> names = new ArrayList<>(labels.size());
        labels.forEach((entityId, label) -> names.add(sharing.get(label) > 1
            ? qualifiedName(label, entityId)
            : label));
        String subject = mentions.isEmpty()
            ? "the question"
            : String.join(" or ", quoted(mentions));
        return new InputRequest(Reason.AMBIGUOUS_ENTITY,
            "Which did you mean by " + subject + ": " + String.join(", ", names) + "?",
            mentions, names);
    }

    /**
     * The name that tells one entity apart from others sharing its label.
     *
     * <p>A candidate is offered by its label, and the label is what the caller sends back. Two
     * entities with one label would come back as the same word and resolve to both again, so each
     * of them is offered under this form instead, which names exactly one.
     *
     * @param label    the entity's label
     * @param entityId the entity
     * @return the label followed by the entity's id
     */
    static String qualifiedName(String label, long entityId) {
        return label + " #" + entityId;
    }

    private static List<String> quoted(List<String> values) {
        List<String> quoted = new ArrayList<>(values.size());
        for (String value : values) {
            quoted.add("\"" + value + "\"");
        }
        return quoted;
    }
}
