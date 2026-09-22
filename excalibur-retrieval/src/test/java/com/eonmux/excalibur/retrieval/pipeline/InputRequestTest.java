package com.eonmux.excalibur.retrieval.pipeline;

import java.util.List;

import com.eonmux.excalibur.planner.PlanningOutcome;
import com.eonmux.excalibur.retrieval.abstain.RetrievalOutcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputRequestTest {

    @Test
    @DisplayName("every planning reason carries across under the same name")
    void planningReasonsCarryAcross() {
        for (PlanningOutcome.Reason reason : PlanningOutcome.Reason.values()) {
            InputRequest request = InputRequest.of(new PlanningOutcome.AskUser(
                reason, "Who is meant?", List.of("she"), List.of("Ada Lovelace")));

            assertThat(request.reason().name()).isEqualTo(reason.name());
            assertThat(request.question()).isEqualTo("Who is meant?");
            assertThat(request.unresolved()).containsExactly("she");
            assertThat(request.candidates()).containsExactly("Ada Lovelace");
        }
    }

    @Test
    @DisplayName("an ambiguous retrieval names each reading once, in rank order")
    void ambiguityNamesEachReadingOnce() {
        InputRequest request = InputRequest.of(new RetrievalOutcome.Ambiguous(List.of(
                new RetrievalOutcome.Candidate(1, "Ada Lovelace", 0.4),
                new RetrievalOutcome.Candidate(7, "Ada King", 0.4),
                new RetrievalOutcome.Candidate(1, "Ada Lovelace", 0.3))),
            List.of("Ada"));

        assertThat(request.reason()).isEqualTo(InputRequest.Reason.AMBIGUOUS_ENTITY);
        assertThat(request.candidates()).containsExactly("Ada Lovelace", "Ada King");
        assertThat(request.question())
            .isEqualTo("Which did you mean by \"Ada\": Ada Lovelace, Ada King?");
    }

    @Test
    @DisplayName("entities sharing a label are each offered under a name that picks out one")
    void sharedLabelsAreQualified() {
        InputRequest request = InputRequest.of(new RetrievalOutcome.Ambiguous(List.of(
                new RetrievalOutcome.Candidate(1, "Ada Lovelace", 0.4),
                new RetrievalOutcome.Candidate(7, "Ada King", 0.4),
                new RetrievalOutcome.Candidate(9, "Ada Lovelace", 0.3))),
            List.of("Ada"));

        assertThat(request.candidates())
            .containsExactly("Ada Lovelace #1", "Ada King", "Ada Lovelace #9");
    }

    @Test
    @DisplayName("an ambiguity with no mention to point at asks about the question itself")
    void ambiguityWithoutMentions() {
        InputRequest request = InputRequest.of(new RetrievalOutcome.Ambiguous(List.of(
                new RetrievalOutcome.Candidate(1, "Ada Lovelace", 0.4),
                new RetrievalOutcome.Candidate(7, "Ada King", 0.4))),
            List.of());

        assertThat(request.question()).startsWith("Which did you mean by the question:");
        assertThat(request.unresolved()).isEmpty();
    }

    @Test
    @DisplayName("a request with no reason or no question is refused")
    void validates() {
        assertThatThrownBy(() -> new InputRequest(null, "Which?", List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InputRequest(
                InputRequest.Reason.AMBIGUOUS_ENTITY, " ", List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
