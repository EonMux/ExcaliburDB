package io.excalibur.planner;

import java.time.LocalDate;

import io.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryClassifierTest {

    private final QueryClassifier classifier = new QueryClassifier();

    private static QueryPlan planOf(PlanningOutcome outcome) {
        assertThat(outcome).isInstanceOf(PlanningOutcome.Planned.class);
        return ((PlanningOutcome.Planned) outcome).plan();
    }

    private static PlanningOutcome.AskUser askOf(PlanningOutcome outcome) {
        assertThat(outcome).isInstanceOf(PlanningOutcome.AskUser.class);
        return (PlanningOutcome.AskUser) outcome;
    }

    @Nested
    @DisplayName("classification")
    class Classification {

        @Test
        @DisplayName("a question about an entity's own attribute is a lookup")
        void lookup() {
            QueryPlan plan = planOf(classifier.classify("When was Ada Lovelace born?"));

            assertThat(plan).isInstanceOf(QueryPlan.Lookup.class);
            assertThat(plan.mentions()).contains("Ada Lovelace");
        }

        @Test
        @DisplayName("a relational question is a traversal")
        void traverse() {
            QueryPlan plan = planOf(classifier.classify("Who did Ada Lovelace work with?"));

            assertThat(plan).isInstanceOf(QueryPlan.Traverse.class);
            QueryPlan.Traverse traverse = (QueryPlan.Traverse) plan;
            assertThat(traverse.hops()).isEqualTo(1);
            assertThat(traverse.relationHints()).isNotEmpty();
        }

        @Test
        @DisplayName("a question naming two entities gets the hops to meet in the middle")
        void twoEntitiesGetTwoHops() {
            QueryPlan plan = planOf(classifier.classify(
                "What is the relationship between Ada Lovelace and Charles Babbage?"));

            assertThat(plan).isInstanceOf(QueryPlan.Traverse.class);
            assertThat(((QueryPlan.Traverse) plan).hops()).isEqualTo(2);
            assertThat(plan.mentions()).contains("Ada Lovelace", "Charles Babbage");
        }

        @Test
        @DisplayName("a counting question is an aggregate, not a traversal to be counted")
        void aggregateCount() {
            // A CSR answers a count from the offset array alone. Planning this as a traversal
            // would materialise every neighbour just to take its size.
            QueryPlan plan = planOf(classifier.classify(
                "How many papers did Ada Lovelace publish?"));

            assertThat(plan).isInstanceOf(QueryPlan.Aggregate.class);
            assertThat(((QueryPlan.Aggregate) plan).function())
                .isEqualTo(QueryPlan.AggregateFunction.COUNT);
        }

        @Test
        @DisplayName("earliest and latest are aggregates too")
        void aggregateExtremes() {
            assertThat(((QueryPlan.Aggregate) planOf(classifier.classify(
                "What was the first thing Ada Lovelace published?"))).function())
                .isEqualTo(QueryPlan.AggregateFunction.EARLIEST);

            assertThat(((QueryPlan.Aggregate) planOf(classifier.classify(
                "What is the most recent work by Ada Lovelace?"))).function())
                .isEqualTo(QueryPlan.AggregateFunction.LATEST);
        }

        @Test
        @DisplayName("aggregation wins over traversal when a question is both")
        void aggregateBeatsTraverse() {
            QueryPlan plan = planOf(classifier.classify(
                "How many people did Ada Lovelace work with?"));

            assertThat(plan)
                .as("the more specific plan does strictly less work")
                .isInstanceOf(QueryPlan.Aggregate.class);
        }

        @Test
        @DisplayName("classification is deterministic")
        void deterministic() {
            // A plan that varies run to run makes every downstream measurement noisy.
            String question = "Who did Ada Lovelace work with in 1843?";
            assertThat(classifier.classify(question)).isEqualTo(classifier.classify(question));
        }

        @Test
        @DisplayName("a quoted mention is extracted verbatim")
        void quotedMention() {
            QueryPlan plan = planOf(classifier.classify("Tell me about \"note g\""));

            assertThat(plan.mentions()).contains("note g");
        }

        @Test
        @DisplayName("a question with no entity at all asks rather than planning")
        void noEntityAsks() {
            PlanningOutcome.AskUser ask = askOf(classifier.classify("what about that thing"));

            assertThat(ask.reason()).isIn(
                PlanningOutcome.Reason.NO_ENTITY_MENTION,
                PlanningOutcome.Reason.UNBOUND_PRONOUN);
            assertThat(ask.question()).isNotBlank();
        }

        @Test
        @DisplayName("a blank question is refused")
        void blankQuestion() {
            assertThatThrownBy(() -> classifier.classify("  "))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("temporal")
    class Temporal {

        @Test
        @DisplayName("a year wraps the plan in a temporal one")
        void yearIsExtracted() {
            QueryPlan plan = planOf(classifier.classify(
                "Where did Ada Lovelace live in 1843?"));

            assertThat(plan).isInstanceOf(QueryPlan.Temporal.class);
            QueryPlan.Temporal temporal = (QueryPlan.Temporal) plan;
            assertThat(temporal.asOf().asOfDays())
                .isEqualTo(Math.toIntExact(LocalDate.of(1843, 7, 1).toEpochDay()));
            assertThat(temporal.inner()).isInstanceOf(QueryPlan.Lookup.class);
        }

        @Test
        @DisplayName("an ISO date is used exactly")
        void isoDate() {
            QueryPlan plan = planOf(classifier.classify(
                "Who employed Ada Lovelace on 1843-07-15?"));

            assertThat(((QueryPlan.Temporal) plan).asOf().asOfDays())
                .isEqualTo(Math.toIntExact(LocalDate.of(1843, 7, 15).toEpochDay()));
        }

        @Test
        @DisplayName("a past-tense question with no instant asks for the whole history")
        void pastTenseWithoutInstant() {
            // Answering as of now would contradict the question; inventing an instant would be a
            // guess. Returning the history lets ranking present what actually held.
            QueryPlan plan = planOf(classifier.classify(
                "Where did Ada Lovelace used to live?"));

            assertThat(plan).isInstanceOf(QueryPlan.Temporal.class);
            assertThat(((QueryPlan.Temporal) plan).asOf()).isEqualTo(TemporalFilter.ALL);
        }

        @Test
        @DisplayName("a present-tense question carries no temporal wrapper")
        void presentTenseIsNotTemporal() {
            QueryPlan plan = planOf(classifier.classify("Where does Ada Lovelace live?"));

            assertThat(plan).isNotInstanceOf(QueryPlan.Temporal.class);
        }

        @Test
        @DisplayName("temporal composes with aggregation")
        void temporalWrapsAggregate() {
            QueryPlan plan = planOf(classifier.classify(
                "How many papers did Ada Lovelace publish in 1843?"));

            assertThat(plan).isInstanceOf(QueryPlan.Temporal.class);
            assertThat(((QueryPlan.Temporal) plan).inner())
                .isInstanceOf(QueryPlan.Aggregate.class);
        }

        @Test
        @DisplayName("a temporal plan cannot wrap another")
        void noNestedTemporal() {
            QueryPlan inner = new QueryPlan.Lookup(java.util.List.of("Ada"));
            QueryPlan.Temporal once = new QueryPlan.Temporal(inner, TemporalFilter.ALL);

            assertThatThrownBy(() -> new QueryPlan.Temporal(once, TemporalFilter.ALL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one as-of instant");
        }

        @Test
        @DisplayName("the shape of a temporal plan is the plan inside it")
        void shapeUnwraps() {
            QueryPlan inner = new QueryPlan.Lookup(java.util.List.of("Ada"));

            assertThat(new QueryPlan.Temporal(inner, TemporalFilter.ALL).shape())
                .isSameAs(inner);
        }

        @Test
        @DisplayName("the shape of an untimed plan is the plan itself")
        void shapeOfAnUntimedPlanIsItself() {
            QueryPlan plan = new QueryPlan.Lookup(java.util.List.of("Ada"));

            assertThat(plan.shape()).isSameAs(plan);
        }
    }

    @Nested
    @DisplayName("coreference")
    class Coreference {

        @Test
        @DisplayName("a pronoun binds to a recent compatible mention")
        void bindsToRecentMention() {
            ConversationContext context = new ConversationContext()
                .mention("Ada Lovelace", Grammar.SINGULAR_PERSON)
                .nextTurn();

            QueryPlan plan = planOf(classifier.classify("Where did she live?", context));

            assertThat(plan.mentions()).contains("Ada Lovelace");
        }

        @Test
        @DisplayName("an unbound pronoun asks instead of guessing")
        void unboundPronounAsks() {
            // A guess here produces a fluent, confident answer about the wrong person, and the
            // user has no way to tell it happened.
            PlanningOutcome.AskUser ask =
                askOf(classifier.classify("Where did she live?", new ConversationContext()));

            assertThat(ask.reason()).isEqualTo(PlanningOutcome.Reason.UNBOUND_PRONOUN);
            assertThat(ask.question()).contains("she");
            assertThat(ask.unresolved()).contains("she");
        }

        @Test
        @DisplayName("two candidates in the same turn are ambiguous, not resolved by order")
        void ambiguousPronounAsks() {
            ConversationContext context = new ConversationContext()
                .mention("Ada Lovelace", Grammar.SINGULAR_PERSON)
                .mention("Mary Somerville", Grammar.SINGULAR_PERSON)
                .nextTurn();

            PlanningOutcome.AskUser ask =
                askOf(classifier.classify("Where did she live?", context));

            assertThat(ask.reason()).isEqualTo(PlanningOutcome.Reason.AMBIGUOUS_PRONOUN);
            assertThat(ask.candidates()).contains("Ada Lovelace", "Mary Somerville");
        }

        @Test
        @DisplayName("the more recent turn wins over an older one")
        void recencyResolvesAcrossTurns() {
            ConversationContext context = new ConversationContext()
                .mention("Charles Babbage", Grammar.SINGULAR_PERSON)
                .nextTurn()
                .mention("Ada Lovelace", Grammar.SINGULAR_PERSON)
                .nextTurn();

            QueryPlan plan = planOf(classifier.classify("What did she write?", context));

            assertThat(plan.mentions()).contains("Ada Lovelace").doesNotContain("Charles Babbage");
        }

        @Test
        @DisplayName("grammatical shape rules out an impossible binding")
        void grammarRulesOutBinding() {
            ConversationContext context = new ConversationContext()
                .mention("Ada Lovelace", Grammar.SINGULAR_PERSON)
                .nextTurn();

            // "it" cannot refer to a person, so there is nothing to bind to.
            PlanningOutcome.AskUser ask =
                askOf(classifier.classify("When was it published?", context));

            assertThat(ask.reason()).isEqualTo(PlanningOutcome.Reason.UNBOUND_PRONOUN);
        }

        @Test
        @DisplayName("a mention of unknown shape binds any pronoun")
        void unknownGrammarIsPermissive() {
            ConversationContext context = new ConversationContext()
                .mention("Note G", Grammar.UNKNOWN)
                .nextTurn();

            assertThat(planOf(classifier.classify("When was it published?", context)).mentions())
                .contains("Note G");
        }

        @Test
        @DisplayName("a mention outside the window is out of reach")
        void windowBoundsBinding() {
            // A pronoun binding to something the user stopped talking about several turns ago is a
            // wrong binding that looks plausible.
            ConversationContext context = new ConversationContext(2)
                .mention("Ada Lovelace", Grammar.SINGULAR_PERSON)
                .nextTurn().nextTurn().nextTurn();

            assertThat(classifier.classify("Where did she live?", context))
                .isInstanceOf(PlanningOutcome.AskUser.class);
        }

        @Test
        @DisplayName("a demonstrative with several candidates asks")
        void demonstrativeIsCautious() {
            // Demonstratives often refer to a whole preceding clause rather than an entity, so
            // binding them to the nearest noun is a common and confidently wrong move.
            ConversationContext context = new ConversationContext()
                .mention("Ada Lovelace", Grammar.SINGULAR_PERSON)
                .nextTurn()
                .mention("Note G", Grammar.SINGULAR_THING)
                .nextTurn();

            assertThat(classifier.classify("Tell me more about that", context))
                .isInstanceOf(PlanningOutcome.AskUser.class);
        }

        @Test
        @DisplayName("singular they binds to a person")
        void singularTheyBinds() {
            ConversationContext context = new ConversationContext()
                .mention("Ada Lovelace", Grammar.SINGULAR_PERSON)
                .nextTurn();

            assertThat(planOf(classifier.classify("What did they write?", context)).mentions())
                .contains("Ada Lovelace");
        }

        @Test
        @DisplayName("an AskUser outcome must carry a question")
        void askUserNeedsAQuestion() {
            assertThatThrownBy(() -> new PlanningOutcome.AskUser(
                    PlanningOutcome.Reason.UNBOUND_PRONOUN, "  ",
                    java.util.List.of(), java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
        }
    }

    @Nested
    @DisplayName("cue matching")
    class CueMatching {

        @Test
        @DisplayName("a cue inside a longer word is not a cue")
        void cuesMatchWholeTokens() {
            // "know" contains "now" and "plastic" contains "last", both latest-value cues. Any
            // question using them was planned as an extremum over the entity's history.
            assertThat(planOf(classifier.classify("What do we know about Ada Lovelace?")))
                .isInstanceOf(QueryPlan.Lookup.class);
            assertThat(planOf(classifier.classify("What did Ada Lovelace say about plastic?")))
                .isInstanceOf(QueryPlan.Lookup.class);
            assertThat(planOf(classifier.classify("Firstly, what is Ada Lovelace known for?")))
                .isInstanceOf(QueryPlan.Lookup.class);
        }

        @Test
        @DisplayName("a relational question whose words merely contain a cue is a traversal")
        void relationalQuestionsSurviveSubstringCues() {
            QueryPlan plan = planOf(classifier.classify("Who did Ada Lovelace know?"));

            assertThat(plan).isInstanceOf(QueryPlan.Traverse.class);
        }

        @Test
        @DisplayName("a real cue still fires")
        void realCuesStillFire() {
            assertThat(planOf(classifier.classify("What is the latest role of Ada Lovelace?")))
                .isInstanceOf(QueryPlan.Aggregate.class);
            assertThat(planOf(classifier.classify("What was the first machine Ada Lovelace used?")))
                .isInstanceOf(QueryPlan.Aggregate.class);
        }

        @Test
        @DisplayName("relation hints do not include cues that only appear inside words")
        void relationHintsAreWholeTokens() {
            QueryPlan plan = planOf(classifier.classify(
                "What is the relationship between Ada Lovelace and Charles Babbage?"));

            assertThat(((QueryPlan.Traverse) plan).relationHints())
                .contains("relationship between", "between")
                .doesNotContain("part of");
        }
    }

    @Nested
    @DisplayName("temporal reading")
    class TemporalReading {

        private TemporalFilter filterOf(String question) {
            QueryPlan plan = planOf(classifier.classify(question));
            assertThat(plan).isInstanceOf(QueryPlan.Temporal.class);
            return ((QueryPlan.Temporal) plan).asOf();
        }

        @Test
        @DisplayName("a digit run that is not a date does not fail the request")
        void invalidIsoDateDoesNotThrow() {
            // A part number, invoice id or version string of the same shape reaches the
            // classifier as ordinary text. LocalDate.of throws DateTimeException for it, which
            // is neither an IllegalArgumentException nor anything the endpoint maps to a client
            // error, so it surfaced as a 500 for a well-formed question.
            assertThatCode(() -> classifier.classify("Did Ada Lovelace file patent 1843-99-99?"))
                .doesNotThrowAnyException();
            assertThatCode(() -> classifier.classify("Is Ada Lovelace document 2024-13-01?"))
                .doesNotThrowAnyException();
            assertThatCode(() -> classifier.classify("Ada Lovelace phone 5551-23-45"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a date the calendar rejects is not read as a date")
        void impossibleCalendarDateIsNotADate() {
            assertThat(planOf(classifier.classify("Is Ada Lovelace item 2023-02-31?")))
                .isNotInstanceOf(QueryPlan.Temporal.class);
        }

        @Test
        @DisplayName("a real ISO date is still read as one")
        void realIsoDateIsRead() {
            assertThat(filterOf("Where did Ada Lovelace live on 1843-03-01?"))
                .isEqualTo(TemporalFilter.asOf(LocalDate.of(1843, 3, 1)));
        }

        @Test
        @DisplayName("in, before, by and since name different instants")
        void prepositionsAreDistinguished() {
            assertThat(filterOf("Where did Ada Lovelace live in 1843?"))
                .isEqualTo(TemporalFilter.asOf(LocalDate.of(1843, 7, 1)));
            assertThat(filterOf("Where did Ada Lovelace live before 1843?"))
                .isEqualTo(TemporalFilter.asOf(LocalDate.of(1843, 1, 1)));
            assertThat(filterOf("Where did Ada Lovelace live by 1843?"))
                .isEqualTo(TemporalFilter.asOf(LocalDate.of(1843, 12, 31)));
            assertThat(filterOf("Where did Ada Lovelace live since 1843?"))
                .isEqualTo(TemporalFilter.ALL);
            assertThat(filterOf("Where did Ada Lovelace live after 1843?"))
                .isEqualTo(TemporalFilter.ALL);
        }
    }
}
