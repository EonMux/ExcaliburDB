package com.eonmux.excalibur.planner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Binds pronouns to entities mentioned earlier, lexically, and asks when it cannot.
 *
 * <p>Coreference belongs in planning rather than in the model. The model never sees the graph, so
 * it cannot check whether its guess about "she" corresponds to an entity that exists; and a guess
 * it makes silently produces a fluent, confident answer about the wrong person. Resolving here
 * means a failure to bind is a fact the system knows about and can act on.
 *
 * <p>And when it cannot bind, it asks. That is the whole point. A pronoun with no antecedent, or
 * two equally good ones, is not a case for picking the likelier — the cost of being wrong is a
 * confidently wrong answer the user has no way to detect, and the cost of asking is one turn.
 *
 * <h2>What this is not</h2>
 * Not a parser and not a coreference model. It handles the pronoun cases that dominate ordinary
 * dialogue — a recent, grammatically compatible antecedent — and refers everything else back to the
 * user. Getting a hard case wrong silently would be worse than declining it.
 */
public final class CoreferenceResolver {

    /** Pronouns that can only refer to a single person. */
    private static final Set<String> PERSON_PRONOUNS =
        Set.of("he", "him", "his", "she", "her", "hers");

    /** Pronouns that can only refer to a single non-person. */
    private static final Set<String> THING_PRONOUNS = Set.of("it", "its");

    /** Pronouns that may refer to a group, or to one person of unstated gender. */
    private static final Set<String> PLURAL_OR_PERSON_PRONOUNS =
        Set.of("they", "them", "their", "theirs");

    /**
     * Demonstratives.
     *
     * <p>Kept apart because they routinely refer to a whole preceding clause rather than an entity
     * — "tell me more about that" often means the topic, not a noun. Binding them to the most
     * recent entity is a common and confidently wrong move, so they only bind when exactly one
     * candidate is visible.
     */
    private static final Set<String> DEMONSTRATIVES =
        Set.of("this", "that", "these", "those");

    private static final Map<Grammar, Set<Grammar>> COMPATIBLE = Map.of(
        Grammar.SINGULAR_PERSON, Set.of(Grammar.SINGULAR_PERSON, Grammar.UNKNOWN),
        Grammar.SINGULAR_THING, Set.of(Grammar.SINGULAR_THING, Grammar.UNKNOWN),
        Grammar.PLURAL, Set.of(Grammar.PLURAL, Grammar.SINGULAR_PERSON, Grammar.UNKNOWN),
        Grammar.UNKNOWN, Set.of(Grammar.SINGULAR_PERSON, Grammar.SINGULAR_THING,
            Grammar.PLURAL, Grammar.UNKNOWN));

    /** Every pronoun this resolver recognises. */
    public static Set<String> pronouns() {
        Set<String> all = new LinkedHashSet<>(PERSON_PRONOUNS);
        all.addAll(THING_PRONOUNS);
        all.addAll(PLURAL_OR_PERSON_PRONOUNS);
        all.addAll(DEMONSTRATIVES);
        return Set.copyOf(all);
    }

    /**
     * Whether {@code word} is spelled like a pronoun this resolver binds.
     *
     * <p>Spelling only. Whether a particular occurrence refers to anything depends on the words
     * around it - "that" in "the company that Ada founded" does not - which is what
     * {@link #resolve} decides.
     */
    public static boolean isPronoun(String word) {
        return word != null && pronouns().contains(word.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves every referring pronoun in {@code text} against {@code context}.
     *
     * @return the mentions the pronouns bound to, or an {@link PlanningOutcome.AskUser} describing
     *         the first one that could not be bound
     */
    public Resolution resolve(String text, ConversationContext context) {
        return resolve(text, context, List.of());
    }

    /**
     * Resolves every referring pronoun in {@code text}, against the question's own names first.
     *
     * <h2>Only pronouns that refer</h2>
     * A word spelled like a pronoun is often doing another job - a relative "that", a complement
     * "that", the placeholder "it" of "is it true that" - and asking the user what such a word
     * refers to is asking a question with no answer. {@link PronounUses} decides which occurrences
     * point at something, and only those are bound.
     *
     * <h2>The question is the most recent turn</h2>
     * "What did Ada publish in her lifetime" names its own antecedent. Binding "her" through the
     * conversation instead would ask about - or, with one candidate there, silently bind to -
     * someone the question had already moved on from. So a name the question gives before the
     * pronoun is its antecedent, under the same most-recent-turn rule {@link #bind} applies to the
     * conversation: one such name binds, several are ambiguous and asked about. Demonstratives are
     * the exception, as everywhere here: "that" rarely points back at a name in its own sentence,
     * so they bind through the conversation only.
     *
     * @param text            the question
     * @param context         the conversation it is asked in
     * @param namedInQuestion the entity mentions the question itself names, as planning read them
     * @return the mentions the pronouns bound to, or the first one that could not be bound
     */
    public Resolution resolve(String text, ConversationContext context,
                              List<String> namedInQuestion) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (namedInQuestion == null) {
            throw new IllegalArgumentException("namedInQuestion must not be null");
        }

        List<String> bound = new ArrayList<>();
        List<PronounUses.Token> tokens = PronounUses.tokenize(text);
        for (int i = 0; i < tokens.size(); i++) {
            PronounUses.Token token = tokens.get(i);
            if (!token.is(PronounUses.Kind.WORD) || !isPronoun(token.text())
                    || !PronounUses.refers(tokens, i)) {
                continue;
            }
            String pronoun = token.lower();
            Binding binding = DEMONSTRATIVES.contains(pronoun)
                ? bind(pronoun, context)
                : bindWithin(pronoun, text, token.offset(), namedInQuestion, context);
            if (binding.ask != null) {
                return new Resolution(List.of(), binding.ask);
            }
            bound.add(binding.mention.text());
        }
        return new Resolution(List.copyOf(bound), null);
    }

    /**
     * Binds to a name the question gave before the pronoun, or through the conversation if none.
     *
     * @param pronoun         the pronoun, lower-cased
     * @param text            the question
     * @param offset          where the pronoun starts in it
     * @param namedInQuestion the names the question gives
     * @param context         the conversation, for a pronoun the question does not bind
     */
    private Binding bindWithin(String pronoun, String text, int offset,
                               List<String> namedInQuestion, ConversationContext context) {
        List<String> preceding = new ArrayList<>();
        for (String name : namedInQuestion) {
            int at = text.indexOf(name);
            if (at >= 0 && at < offset) {
                preceding.add(name);
            }
        }
        if (preceding.isEmpty()) {
            return bind(pronoun, context);
        }
        if (preceding.size() > 1) {
            return Binding.ask(new PlanningOutcome.AskUser(
                PlanningOutcome.Reason.AMBIGUOUS_PRONOUN,
                "Does \"" + pronoun + "\" refer to " + String.join(" or ", preceding) + "?",
                List.of(pronoun), preceding));
        }
        // Shape unknown: planning reads a name, not its grammar, and refusing the binding would
        // ask about something the question has just said.
        return Binding.bound(new Mention(preceding.get(0), Grammar.UNKNOWN, context.currentTurn()));
    }

    private Binding bind(String pronoun, ConversationContext context) {
        Grammar required = requiredGrammar(pronoun);
        List<Mention> candidates = compatibleMentions(context, required);

        if (candidates.isEmpty()) {
            return Binding.ask(new PlanningOutcome.AskUser(
                PlanningOutcome.Reason.UNBOUND_PRONOUN,
                "Who or what does \"" + pronoun + "\" refer to?",
                List.of(pronoun), List.of()));
        }

        // Most recent turn wins. Within one turn there is no ordering information that says which
        // of two entities a pronoun meant, so that is genuinely ambiguous rather than a case for
        // preferring whichever was parsed first.
        int mostRecentTurn = candidates.get(candidates.size() - 1).turn();
        List<Mention> newest = candidates.stream()
            .filter(mention -> mention.turn() == mostRecentTurn)
            .toList();

        boolean demonstrative = DEMONSTRATIVES.contains(pronoun);
        if (newest.size() > 1 || (demonstrative && candidates.size() > 1)) {
            List<String> options = new ArrayList<>();
            for (Mention mention : (newest.size() > 1 ? newest : candidates)) {
                options.add(mention.text());
            }
            return Binding.ask(new PlanningOutcome.AskUser(
                PlanningOutcome.Reason.AMBIGUOUS_PRONOUN,
                "Does \"" + pronoun + "\" refer to " + String.join(" or ", options) + "?",
                List.of(pronoun), options));
        }
        return Binding.bound(newest.get(0));
    }

    private List<Mention> compatibleMentions(ConversationContext context, Grammar required) {
        Set<Grammar> allowed = COMPATIBLE.get(required);
        List<Mention> compatible = new ArrayList<>();
        for (Mention mention : context.visibleMentions()) {
            if (allowed.contains(mention.grammar())) {
                compatible.add(mention);
            }
        }
        return compatible;
    }

    private Grammar requiredGrammar(String pronoun) {
        if (PERSON_PRONOUNS.contains(pronoun)) {
            return Grammar.SINGULAR_PERSON;
        }
        if (THING_PRONOUNS.contains(pronoun)) {
            return Grammar.SINGULAR_THING;
        }
        if (PLURAL_OR_PERSON_PRONOUNS.contains(pronoun)) {
            return Grammar.PLURAL;
        }
        return Grammar.UNKNOWN;
    }

    /**
     * The result of resolving a piece of text.
     *
     * @param boundMentions the entities its pronouns resolved to
     * @param ask           set when a pronoun could not be bound; the rest is then empty
     */
    public record Resolution(List<String> boundMentions, PlanningOutcome.AskUser ask) {

        /** Whether resolution succeeded. */
        public boolean isResolved() {
            return ask == null;
        }
    }

    private record Binding(Mention mention, PlanningOutcome.AskUser ask) {
        static Binding bound(Mention mention) {
            return new Binding(mention, null);
        }

        static Binding ask(PlanningOutcome.AskUser ask) {
            return new Binding(null, ask);
        }
    }
}
