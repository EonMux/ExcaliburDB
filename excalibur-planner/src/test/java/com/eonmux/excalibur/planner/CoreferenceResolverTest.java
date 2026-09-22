package com.eonmux.excalibur.planner;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreferenceResolverTest {

    private final QueryClassifier classifier = new QueryClassifier();
    private final CoreferenceResolver resolver = new CoreferenceResolver();

    private static QueryPlan planOf(PlanningOutcome outcome) {
        assertThat(outcome).isInstanceOf(PlanningOutcome.Planned.class);
        return ((PlanningOutcome.Planned) outcome).plan();
    }

    private static PlanningOutcome.AskUser askOf(PlanningOutcome outcome) {
        assertThat(outcome).isInstanceOf(PlanningOutcome.AskUser.class);
        return (PlanningOutcome.AskUser) outcome;
    }

    @Nested
    @DisplayName("words spelled like pronouns that refer to nothing")
    class NonReferring {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "Who founded the company that Ada Lovelace worked at?",
            "Who founded the company that hired Ada Lovelace?",
            "Which company that Ada Lovelace advised was founded by Charles Babbage?",
            "Where is the machine that the Analytical Society built?",
            "What did Charles Babbage's machine that Ada Lovelace described compute?",
            "Who said that Ada Lovelace wrote Note G?",
            "Is it true that Ada Lovelace worked with Charles Babbage?",
            "Is it possible to trace Ada Lovelace to Charles Babbage?",
            "Is it the case that Ada Lovelace met Charles Babbage?",
            "Was it Ada Lovelace who wrote Note G?",
            "It was Charles Babbage that designed the Difference Engine, wasn't it?",
            "When was it that Ada Lovelace met Charles Babbage?",
            "It seems that Ada Lovelace knew Charles Babbage; who else did?",
            "How long did it take to build the Difference Engine?",
            "Where did Ada Lovelace live at that time?",
            "Who did Charles Babbage work with that year?",
            "What did Ada Lovelace publish this year?",
            "Who were those who worked with Ada Lovelace?",
            "Who directed \"That Thing You Do\"?",
            "What is \"It\" about?"
        })
        @DisplayName("a fully specified question is planned rather than handed back")
        void plannedWithoutContext(String question) {
            // With no conversation behind them, every one of these used to come back as
            // needs_input asking who "that" or "it" was - a question with no right answer.
            assertThat(classifier.classify(question, new ConversationContext()))
                .isInstanceOf(PlanningOutcome.Planned.class);
        }

        @Test
        @DisplayName("a relative clause adds nothing from the conversation")
        void relativeThatBindsNothing() {
            ConversationContext context = new ConversationContext()
                .mention("Note G", Grammar.UNKNOWN)
                .nextTurn();

            QueryPlan plan = planOf(classifier.classify(
                "Who founded the company that Ada Lovelace worked at?", context));

            assertThat(plan.mentions()).containsExactly("Ada Lovelace");
        }

        @Test
        @DisplayName("a quoted span is a name, not a place to look for pronouns")
        void quotedSpanIsAName() {
            assertThat(planOf(classifier.classify("Who directed \"That Thing You Do\"?"))
                .mentions()).containsExactly("That Thing You Do");
        }

        @Test
        @DisplayName("a capitalised pronoun starting the question is not taken for a name")
        void sentenceInitialPronounIsNotAName() {
            ConversationContext context = new ConversationContext()
                .mention("Note G", Grammar.SINGULAR_THING)
                .nextTurn();

            QueryPlan plan = planOf(classifier.classify("It was published when?", context));

            assertThat(plan.mentions()).containsExactly("Note G");
        }
    }

    @Nested
    @DisplayName("pronouns that do refer")
    class Referring {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "Who founded it?",
            "When was it published?",
            "What is it known for?",
            "Did it belong to the Crown?",
            "Who did he work with?",
            "Tell me more about that",
            "Who founded that company?",
            "Is that true?",
            "Who knew that man?",
            "Is it the company that hired Charles Babbage?",
            "Who reviewed those?",
            "What did their members publish?"
        })
        @DisplayName("an unbound referring pronoun still asks")
        void stillAsksWithoutContext(String question) {
            PlanningOutcome.AskUser ask =
                askOf(classifier.classify(question, new ConversationContext()));

            assertThat(ask.reason()).isEqualTo(PlanningOutcome.Reason.UNBOUND_PRONOUN);
        }

        @Test
        @DisplayName("real anaphora still resolves through the conversation")
        void anaphoraResolvesThroughContext() {
            ConversationContext context = new ConversationContext()
                .mention("Charles Babbage", Grammar.SINGULAR_PERSON)
                .nextTurn();

            assertThat(planOf(classifier.classify("Who did he work with?", context)).mentions())
                .containsExactly("Charles Babbage");
        }

        @Test
        @DisplayName("a relative clause and a real pronoun in one question: only the pronoun binds")
        void relativeClauseBesideARealPronoun() {
            ConversationContext context = new ConversationContext()
                .mention("Analytical Engine", Grammar.SINGULAR_THING)
                .nextTurn();

            QueryPlan plan = planOf(classifier.classify(
                "Who built the part that failed when it was tested?", context));

            assertThat(plan.mentions()).containsExactly("Analytical Engine");
        }

        @Test
        @DisplayName("a demonstrative with one candidate binds to it")
        void demonstrativeWithOneCandidateBinds() {
            ConversationContext context = new ConversationContext()
                .mention("Analytical Engine", Grammar.SINGULAR_THING)
                .nextTurn();

            assertThat(planOf(classifier.classify("Who founded that company?", context))
                .mentions()).containsExactly("Analytical Engine");
        }
    }

    @Nested
    @DisplayName("antecedents inside the question")
    class WithinTheQuestion {

        @Test
        @DisplayName("a name before the pronoun is its antecedent")
        void namedAntecedentBinds() {
            QueryPlan plan = planOf(classifier.classify(
                "What did Ada Lovelace publish in her lifetime?", new ConversationContext()));

            assertThat(plan.mentions()).containsExactly("Ada Lovelace");
        }

        @Test
        @DisplayName("the question's own name wins over the conversation")
        void questionOutranksConversation() {
            // The question is the most recent turn, and the same rule that prefers a newer turn
            // over an older one prefers it over the conversation.
            ConversationContext context = new ConversationContext()
                .mention("Charles Babbage", Grammar.SINGULAR_PERSON)
                .nextTurn();

            QueryPlan plan = planOf(classifier.classify(
                "What did Ada Lovelace publish in her lifetime?", context));

            assertThat(plan.mentions()).containsExactly("Ada Lovelace");
        }

        @Test
        @DisplayName("two names before the pronoun are ambiguous, and asked about")
        void twoNamedAntecedentsAsk() {
            PlanningOutcome.AskUser ask = askOf(classifier.classify(
                "Where did Ada Lovelace meet Charles Babbage before he died?",
                new ConversationContext()));

            assertThat(ask.reason()).isEqualTo(PlanningOutcome.Reason.AMBIGUOUS_PRONOUN);
            assertThat(ask.candidates()).containsExactly("Ada Lovelace", "Charles Babbage");
        }

        @Test
        @DisplayName("a name after the pronoun is not its antecedent")
        void laterNameDoesNotBind() {
            assertThat(classifier.classify("Where did she meet Charles Babbage?",
                new ConversationContext())).isInstanceOf(PlanningOutcome.AskUser.class);
        }

        @Test
        @DisplayName("a demonstrative binds through the conversation, not the question")
        void demonstrativeIgnoresTheQuestion() {
            ConversationContext context = new ConversationContext()
                .mention("Note G", Grammar.SINGULAR_THING)
                .nextTurn();

            QueryPlan plan = planOf(classifier.classify(
                "What did Ada Lovelace say about that?", context));

            assertThat(plan.mentions()).containsExactly("Ada Lovelace", "Note G");
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {

        @Test
        @DisplayName("the names a question gives are required, and may be empty")
        void namedInQuestionIsRequired() {
            assertThatThrownBy(() -> resolver.resolve("Who?", new ConversationContext(), null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(resolver.resolve("Who?", new ConversationContext(), List.of())
                .isResolved()).isTrue();
        }

        @Test
        @DisplayName("text and context are required")
        void textAndContextAreRequired() {
            assertThatThrownBy(() -> resolver.resolve(null, new ConversationContext()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> resolver.resolve("Who?", null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("spelling alone makes a pronoun, whatever job it is doing")
        void isPronounIsSpelling() {
            assertThat(CoreferenceResolver.isPronoun("That")).isTrue();
            assertThat(CoreferenceResolver.isPronoun("company")).isFalse();
            assertThat(CoreferenceResolver.isPronoun(null)).isFalse();
        }
    }
}
